(ns digdir.config.verify
  "Can the runtime actually resolve the config it needs? (#275)

   After the documented import the runtime resolved NO `services` config at
   all, and the failure surfaced several layers downstream as an HTTP 400 from
   a model server complaining about a null model name. Every check anyone had
   run looked at the snapshot FILE or at `bb config-get` - and both of those
   read a node the runtime never enters.

   The mechanism, established from the producer rather than inferred:

     - `config.node/parent` exists, the bootstrap DOES set it, and value
       resolution DOES follow it. The fallback is real.
     - It runs CHILD -> PARENT. `prefetch-ancestor-chain` walks
       \"requested node -> tenant ancestors -> tenant tree root\".
     - `bootstrap-config-tree!` writes values to the LEAF and makes the leaf a
       child of the base.
     - `tenant-root-node!` is literally
       `(get-config-node-by-tenant-config-key db tenant root \"default\")`, so
       the runtime enters at the BASE.

   Entering at the parent can never reach the child's values. Nothing is
   misconfigured; the tree is built downward and read upward.

   DECIDED (PI, #275): values belong on \"default\", the tenant root - so the
   fix was at the write end, and ops/topology.clj now seeds the base node like
   the other three platform bootstraps always have.

   This namespace is what keeps it fixed. It answers the question that was
   wrong under every option: CAN THE RUNTIME SEE ITS OWN SERVICE CONFIG, and
   if not, where did the values actually land? It also separates that from a
   different failure it would otherwise mask - values that ARE reachable and
   still cannot be read, because the master key does not match the one they
   were sealed with (#279).

   WHAT IT REPORTS is a shopping list, not a diagnosis alone. Every finding
   carries the environment variable that supplies it, taken from
   `digdir.config.env-bridge` so the two cannot drift. A reader gets the
   checklist at the moment of import instead of a vendor's 401 several layers
   later at first query - which is the same failure shape, one level up, that
   this namespace already exists to remove."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [taoensso.telemere :as t]))

(def runtime-required-service-paths
  "Paths the runtime reads with a tenant and NO tenant-config-key.

   All 109 `:services` read sites in src resolve this way, so any of these
   coming back nil means the corresponding subsystem is unconfigured at
   runtime however healthy the database looks."
  [["services" "typesense" "api-host"]
   ["services" "typesense" "api-tls"]
   ["services" "typesense" "api-key-admin"]
   ["services" "azure-openai" "model-name"]
   ["services" "azure-openai" "use-azure-openai-api"]])

(defn- path->string [path]
  (str/join "." (map name path)))

(defn- definition-path [value]
  (or (get-in value [:config.value/definition :config-def/path])
      (:config.value/definition-path value)))

(defn- paths-on-node [db tenant node-id]
  (try
    (set (keep definition-path (config-db/list-node-values db :platform tenant node-id)))
    (catch Exception _ #{})))

(defn- ancestor-ids
  "The node the runtime enters at, plus everything it inherits FROM.

   Deliberately walks the same direction as prefetch-ancestor-chain. A node
   below the entry point is not reachable, which is the whole defect."
  [nodes-by-id entry-id]
  (loop [id entry-id acc []]
    (if (or (nil? id) (some #{id} acc))
      acc
      (recur (get-in nodes-by-id [id :config.node/parent :config.node/id])
             (conj acc id)))))

(defn unresolved-service-config
  "Service paths the runtime cannot see for `tenant`, and where they actually are.

   Returns a vector of
     {:path \"services.typesense.api-host\"
      :entry-node-id \"platform/digdir/default\"
      :defined-on [\"platform/digdir/shared\"]   ; or [] if genuinely absent
      :reachable? false
      :env-var \"TYPESENSE_API_HOST\"            ; or nil - see below
      :supplied-by :config-db}
   and an empty vector when everything resolves.

   `:env-var` nil is a real answer, not a gap: it means no environment
   variable supplies that path and the reader needs `bb config-set`. Reporting
   it as nil rather than omitting the key is what stops a caller rendering a
   blank where an instruction belongs."
  [db tenant]
  (let [nodes (config-db/list-config-nodes db tenant :platform)
        nodes-by-id (into {} (map (juxt :config.node/id identity)) nodes)
        entry (some #(when (= "default" (:config.node/tenant-config-key %)) %) nodes)
        entry-id (:config.node/id entry)
        reachable (set (ancestor-ids nodes-by-id entry-id))
        paths-by-node (into {} (map (juxt :config.node/id
                                          #(paths-on-node db tenant (:config.node/id %))))
                            nodes)
        reachable-paths (reduce into #{} (vals (select-keys paths-by-node reachable)))]
    (->> runtime-required-service-paths
         (map path->string)
         (remove reachable-paths)
         (mapv (fn [p]
                 (let [binding (env-bridge/binding-for-path p)]
                   {:path p
                    :entry-node-id entry-id
                    :defined-on (vec (sort (keep (fn [[node-id paths]]
                                                   (when (contains? paths p) node-id))
                                                 paths-by-node)))
                    :reachable? false
                    :env-var (:env-var binding)
                    :supplied-by (:destination binding)}))))))

(defn- encrypted-paths-on [db tenant node-ids]
  (->> node-ids
       (mapcat (fn [node-id]
                 (try (config-db/list-node-values db :platform tenant node-id)
                      (catch Exception _ nil))))
       (filter #(get-in % [:config.value/definition :config-def/encrypted?]))
       (keep definition-path)
       distinct
       sort
       vec))

(defn undecryptable-service-config
  "Encrypted values that are REACHABLE but still cannot be read.

   Reachability is not usability. An encrypted value walks the same nodes as a
   plain one and then fails at the last step if the master key does not match
   the one it was sealed with - AES-GCM reports that as \"Tag mismatch\". A
   fresh install importing a shared snapshot hits exactly this: the values
   arrive and the key that would open them does not (#279).

   Scans every encrypted value actually PRESENT on a reachable node, not the
   required-paths list above. Decryptability is a property of what is stored,
   so a check driven by a hand-maintained list would under-report by exactly
   the paths nobody remembered to add - and the point of this function is to
   stop #275's fix from looking like a clean bill of health."
  [db tenant]
  (try
    (let [nodes (config-db/list-config-nodes db tenant :platform)
          nodes-by-id (into {} (map (juxt :config.node/id identity)) nodes)
          entry-id (some #(when (= "default" (:config.node/tenant-config-key %))
                            (:config.node/id %))
                         nodes)]
      (->> (encrypted-paths-on db tenant (ancestor-ids nodes-by-id entry-id))
           (keep (fn [path-str]
                   (try
                     (accessor/get-platform-value
                      (mapv keyword (str/split path-str #"\."))
                      {:tenant tenant :default ::absent})
                     nil
                     (catch Exception e
                       (let [binding (env-bridge/binding-for-path path-str)]
                         {:path path-str
                          :tenant tenant
                          :reason (.getMessage e)
                          :env-var (:env-var binding)
                          :supplied-by (:destination binding)})))))
           vec))
    (catch Exception _ [])))

(defn- selected-provider
  "Which LLM path `tenant` is configured for: :azure or :openai-compatible.

   Defaults to :azure when the switch is absent or unreadable, because that is
   what the shipped snapshot sets (`raw=true` for both tenants). Defaulting the
   other way would quietly stop asking for the Azure credentials on exactly the
   installs that need them."
  [tenant]
  (let [v (try
            (accessor/get-platform-value [:services :azure-openai :use-azure-openai-api]
                                         {:tenant tenant :default true})
            (catch Exception _ true))]
    (if (false? v) :openai-compatible :azure)))

(defn unsupplied-first-query-config
  "Config a first real query needs for `tenant` that has no usable value.

   A DIFFERENT QUESTION from `unresolved-service-config`, and the two must not
   be merged by whoever reads them next to each other:

     unresolved-service-config   CAN THE RUNTIME SEE ITS CONFIG. Driven by
                                 `runtime-required-service-paths`, the five
                                 paths read with a tenant and no
                                 tenant-config-key. A topology answer.
     unsupplied-first-query-config
                                 WHAT MUST A NEWCOMER SUPPLY. Driven by the
                                 :query tier of `env-bridge`. A supply answer.

   They are not the same set, and the gap has a live example:
   `services.azure-openai.api-key` is absent from the shipped snapshot since
   #279 and is not on the reachability list, so after the documented import a
   clean install reports zero unresolvable and the first LLM call still fails.
   Reporting only the first list is the failure direction that makes a broken
   install look healthy.

   Asks the real resolver rather than the node tree, so an absent value and one
   that is present-but-undecryptable both count as unsupplied - a caller wants
   to know it cannot be used, and `undecryptable-service-config` is where the
   distinction between those two lives."
  [_db tenant]
  (->> (env-bridge/first-query-bindings (selected-provider tenant))
       (keep (fn [{:keys [path env-var destination]}]
               (let [finding {:path (or path env-var) :tenant tenant :env-var env-var}]
                 (if (= :environment destination)
                   ;; No config value to look at - `digdir.llm.client` reads
                   ;; these per call. Checking the config DB for them would
                   ;; report every install as broken.
                   (when-not (env-bridge/env-var-present? env-var)
                     (assoc finding :reason "absent"))
                   (let [result (try
                                  (accessor/get-platform-value
                                   (mapv keyword (str/split path #"\."))
                                   {:tenant tenant :default ::absent})
                                  (catch Exception e (ex-info "unreadable" {:reason (.getMessage e)})))]
                     (cond
                       (instance? clojure.lang.ExceptionInfo result)
                       (assoc finding :reason (:reason (ex-data result)))

                       ;; `false` is a legitimate value for use-azure-openai-api,
                       ;; so this compares against a sentinel rather than testing
                       ;; truthiness - which would report a correctly-disabled
                       ;; switch as missing.
                       (= ::absent result)
                       (assoc finding :reason "absent")

                       (and (string? result) (str/blank? result))
                       (assoc finding :reason "blank")))))))
       vec))

(defn- checklist
  "The findings as lines a reader can act on, one per path.

   Rendered here rather than left to each caller so the instruction and the
   diagnosis travel together. `env-bridge/shopping-list` owns the wording;
   this only supplies the paths and the heading."
  [heading findings]
  (str heading "\n"
       (str/join "\n" (map #(str "  - " %)
                           (env-bridge/shopping-list (map :path findings))))))

(defn report-unresolved-service-config!
  "Log loudly if the runtime cannot resolve its service config. Returns the findings.

   Logged rather than thrown deliberately: which end should change is an open
   product decision (#275), and throwing here would pick one by making the
   import fail. What is NOT in question is that silence is wrong - the symptom
   this replaces was a null model name surfacing as an HTTP 400 from a model
   server, several layers and one subsystem away from the cause.

   Each message ends in a SHOPPING LIST: the environment variable that supplies
   each unsupplied value, named from `digdir.config.env-bridge`. \"These cannot
   be decrypted\" is a diagnosis the reader cannot act on; \"set
   TYPESENSE_API_KEY_ADMIN\" is one they can, and it is the same fact."
  [db tenant]
  (let [findings (unresolved-service-config db tenant)
        sealed (undecryptable-service-config db tenant)]
    (when (seq sealed)
      (t/log! {:level :error
               :id ::service-config-present-but-undecryptable
               :data {:tenant tenant
                      :paths (mapv :path sealed)
                      :env-vars (vec (keep :env-var sealed))
                      :reason (:reason (first sealed))}}
              (str (count sealed) " service config value(s) for tenant " tenant
                   " are reachable but cannot be decrypted with this master key."
                   " The values imported; the key that seals them did not."
                   " This is #279 and is NOT fixed by the node placement in #275.\n"
                   (checklist "Supply them yourself - the import writes any of these that are set:"
                              sealed))))
    (when (seq findings)
      (let [found-on (vec (sort (distinct (mapcat :defined-on findings))))]
        (t/log! {:level :error
                 :id ::runtime-cannot-resolve-service-config
                 :data {:tenant tenant
                        :entry-node-id (:entry-node-id (first findings))
                        :unresolved (mapv :path findings)
                        :env-vars (vec (keep :env-var findings))
                        :values-found-on found-on}}
                (str "The runtime cannot resolve " (count findings)
                     " required service config value(s) for tenant " tenant ". "
                     ;; MISPLACED and ABSENT are different problems with
                     ;; different fixes, and the findings have always
                     ;; distinguished them. The message did not: it asserted
                     ;; "values exist on other nodes" even when :defined-on was
                     ;; empty everywhere, which since #279 removed the shipped
                     ;; secrets is the ordinary case. That sends the reader
                     ;; hunting a node holding the value, and there is none.
                     (if (seq found-on)
                       (str "Values exist on other nodes but config inheritance"
                            " runs CHILD -> PARENT, so a node below the entry"
                            " point is never reached. See #275.")
                       (str "They are not defined anywhere in this tenant's"
                            " config tree - absent, not misplaced."))
                     "\n"
                     (checklist "These must be supplied:" findings)))))
    (let [;; Subtracted rather than reported twice. The counts have to mean
          ;; something on their own - the same reasoning that keeps
          ;; undecryptable and unreachable disjoint above.
          already (set (concat (map :path findings) (map :path sealed)))
          unsupplied (vec (remove #(contains? already (:path %))
                                  (unsupplied-first-query-config db tenant)))]
      (when (seq unsupplied)
        ;; :warn, not :error. The two above mean the install is definitely
        ;; broken. This one means it is broken UNLESS the operator has pointed
        ;; the system at a different provider, which is a supported thing to do.
        (t/log! {:level :warn
                 :id ::first-query-config-unsupplied
                 :data {:tenant tenant
                        :paths (mapv :path unsupplied)
                        :env-vars (vec (keep :env-var unsupplied))}}
                (str (count unsupplied) " config value(s) a first query needs are"
                     " not supplied for tenant " tenant
                     ", and are NOT covered by the reachability check above"
                     " - that check asks whether the runtime can see its config,"
                     " not whether a newcomer has provided it.\n"
                     (checklist "Supply these before the first query:" unsupplied))))
      {:unreachable findings
       :undecryptable sealed
       :unsupplied unsupplied})))
