(ns digdir.config.ops.ownership
  "Server-side ops for promoting a fork-owned definition to :inherit
   (with safe preservation of every existing tenant's effective value) and
   demoting back. Called by the operator-console promotion wizard (Phase 3 of
   the global-config-root plan)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.audit :as audit]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.global :as ops-global]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- assert-fork-owned!
  [db path]
  (let [definition (or (config-db/get-definition db path)
                       (throw (ex-info "Config definition not found" {:path path})))
        ownership (or (:config-def/ownership definition) :fork)]
    (when-not (= :fork ownership)
      (throw (ex-info "Promote requires a :fork-owned definition"
                      {:path path :ownership ownership})))
    definition))

(defn- assert-inherit-owned!
  [db path]
  (let [definition (or (config-db/get-definition db path)
                       (throw (ex-info "Config definition not found" {:path path})))
        ownership (or (:config-def/ownership definition) :fork)]
    (when-not (= :inherit ownership)
      (throw (ex-info "Demote requires an :inherit-owned definition"
                      {:path path :ownership ownership})))
    definition))

(defn- participating-tenants
  "Every tenant that has a `default` root node at `root`, excluding the
   __global__ sentinel and the setup-time __platform-defaults__ tenant."
  [db root]
  (let [tenants (config-db/list-tenants db)]
    (->> tenants
         (remove #{core/global-tenant "__platform-defaults__"})
         (keep (fn [t]
                 (when-let [node (config-db/get-config-node-by-tenant-config-key
                                  db t root "default")]
                   {:tenant t :node-id (:config.node/id node)})))
         vec)))

(defn- tenant-direct-values
  "Return {tenant raw-value-entity} for every tenant that has an active direct
   value at this path on any of its nodes. Used to detect which tenants own an
   explicit value (as opposed to inheriting)."
  [db path]
  (->> (d/q '[:find ?tenant (pull ?v [*])
              :in $ ?path
              :where
              [?def :config-def/path ?path]
              [?v :config.value/definition ?def]
              [?v :config.value/tenant ?tenant]
              (not [?v :config.value/deleted-at])]
            db path)
       (reduce (fn [acc [t v]] (assoc acc t v)) {})))

(defn- all-direct-value-entities
  "Return every active value entity at `path`, with its tenant and
   tenant-local node-id attached. Each row:
     {:tenant t :node-id id :value-entity v-entity}

   Unlike `tenant-direct-values`, this does NOT collapse multi-node tenants to
   a single value — a tenant with values on two materialization nodes returns
   two rows."
  [db path]
  (->> (d/q '[:find ?tenant ?node-id (pull ?v [*])
              :in $ ?path
              :where
              [?def :config-def/path ?path]
              [?v :config.value/definition ?def]
              [?v :config.value/tenant ?tenant]
              [?v :config.value/node ?node]
              [?node :config.node/id ?node-id]
              (not [?v :config.value/deleted-at])]
            db path)
       (mapv (fn [[t node-id v]]
               {:tenant t :node-id node-id :value-entity v}))))

(defn- decode-raw
  [definition raw master-key]
  (when raw
    (config-db/decode-value raw
                            (:config-def/value-type definition)
                            (:config-def/encrypted? definition)
                            master-key)))

;; =============================================================================
;; Inspection
;; =============================================================================

(defn inspect-definition-values
  "Inspect every tenant's current value for a fork-owned path. Clusters tenants
   by value so operators can pick a candidate global value for promotion.

   Uses `all-direct-value-entities` rather than a per-tenant-default lookup so
   tenants with values on non-default nodes (e.g. dataset materialization
   nodes) are surfaced correctly. A tenant with multiple values on different
   nodes contributes one entry per node.

   Returns:
   {:path path
    :value-type value-type
    :definition definition
    :tenant-count <int>              ; total tenants with a default node at this root
    :with-value-count <int>          ; distinct tenants that have ≥1 active value
    :without-value-count <int>       ; tenants with no direct value
    :clusters [{:value decoded :tenants [{:tenant t :node-id ...}] :count n}]
    :candidate-value decoded         ; modal value, or nil
    :candidate-cluster-size <int>}"
  [db {:keys [path master-key]}]
  (when (str/blank? path) (throw (ex-info "Missing :path" {})))
  (let [definition (or (config-db/get-definition db path)
                       (throw (ex-info "Config definition not found" {:path path})))
        root (:config-def/root definition)
        tenants (participating-tenants db root)
        entries (all-direct-value-entities db path)
        per-entry (mapv (fn [{:keys [tenant node-id value-entity]}]
                          (let [raw (:config.value/raw value-entity)]
                            {:tenant tenant
                             :node-id node-id
                             :raw raw
                             :decoded (decode-raw definition raw master-key)}))
                        entries)
        tenants-with-values (set (map :tenant per-entry))
        tenants-without-value (remove #(contains? tenants-with-values (:tenant %))
                                      tenants)
        ;; Group by decoded value.
        clusters (->> per-entry
                      (group-by :decoded)
                      (map (fn [[v members]]
                             {:value v
                              :tenants (mapv #(select-keys % [:tenant :node-id])
                                             members)
                              :count (count members)}))
                      (sort-by :count >)
                      vec)
        candidate (first clusters)]
    {:path path
     :value-type (:config-def/value-type definition)
     :definition definition
     :tenant-count (count tenants)
     :with-value-count (count tenants-with-values)
     :without-value-count (count tenants-without-value)
     :clusters clusters
     :candidate-value (:value candidate)
     :candidate-cluster-size (or (:count candidate) 0)
     :tenants-without-value (mapv :tenant tenants-without-value)}))

;; =============================================================================
;; Ownership flip helpers
;; =============================================================================

(defn- flip-ownership!
  [conn path ownership]
  (d/transact conn
              {:tx-data [{:db/id [:config-def/path path]
                          :config-def/ownership ownership}]}))

;; =============================================================================
;; Promotion
;; =============================================================================

(defn promote-to-global!
  "Promote a fork-owned definition to :inherit-owned.

   The candidate-value becomes the global baseline. Each existing tenant's
   effective value is preserved unless `:non-matching-strategy` overrides:

     :pin-all (default) — tenants whose current value differs from the candidate
        keep their value (stamped with :pin-of-version so the override is
        traceable). Tenants whose value already equals the candidate have their
        direct value retracted and inherit from global.
     :revert-all — every tenant's direct value is retracted, so all tenants
        inherit the candidate value on next read.

   Opts:
     :path              - required string
     :candidate-value   - required value (decoded) — becomes the new global
     :non-matching-strategy :pin-all | :revert-all (default :pin-all)
     :changelog         - required string for the version-bump audit trail
     :master-key        - required for encrypted definitions
     :user-id :user-email :ip-address - audit metadata

   Returns {:version :pinned <tenants> :reverted <tenants> :inherited <tenants>}."
  [conn {:keys [path candidate-value non-matching-strategy changelog master-key
                user-id user-email ip-address]
         :or {non-matching-strategy :pin-all}}]
  (when (str/blank? path) (throw (ex-info "Missing :path" {})))
  (when (str/blank? changelog)
    (throw (ex-info "Promote requires a :changelog" {})))
  (when-not (contains? #{:pin-all :revert-all} non-matching-strategy)
    (throw (ex-info "Unsupported :non-matching-strategy"
                    {:strategy non-matching-strategy})))
  (let [db @conn
        definition (assert-fork-owned! db path)
        root (:config-def/root definition)
        ;; Iterate per value-entity rather than per tenant-default-node so that
        ;; tenants with values on non-default nodes (e.g. dataset materialization
        ;; nodes) are correctly pinned/reverted on the actual value entity.
        entries (all-direct-value-entities db path)
        matches? (fn [value-entity]
                   (= candidate-value
                      (decode-raw definition
                                  (:config.value/raw value-entity)
                                  master-key)))
        matching (filter #(matches? (:value-entity %)) entries)
        non-matching (remove #(matches? (:value-entity %)) entries)]
    ;; Step 1: flip ownership so ops-global operations pass their inherit check.
    (flip-ownership! conn path :inherit)
    ;; Step 2: write the candidate value on the global tenant. bumps version.
    (let [{:keys [version]}
          (ops-global/set-global-value!
           conn
           {:path path
            :value candidate-value
            :master-key master-key
            :changelog changelog
            :created-by (or user-id "promotion-wizard")
            :user-id user-id
            :user-email user-email
            :ip-address ip-address})
          now (System/currentTimeMillis)
          pinned (atom [])
          reverted (atom [])
          inherited (atom [])]
      ;; 3a: matching entries — retract their direct value so the tenant reads
      ;; from global at this point in the chain.
      (doseq [{:keys [tenant node-id]} matching]
        (config-db/delete-node-value! conn
                                      {:root root :tenant tenant
                                       :node-id node-id :path path})
        (swap! inherited conj tenant))
      ;; 3b: non-matching entries — pin or revert the specific value entity.
      (doseq [{:keys [tenant node-id value-entity]} non-matching]
        (case non-matching-strategy
          :pin-all
          (do (d/transact conn
                          {:tx-data [{:db/id [:config.value/id (:config.value/id value-entity)]
                                      :config.value/pin-of-version version
                                      :config.value/updated-at now}]})
              (swap! pinned conj tenant))

          :revert-all
          (do (config-db/delete-node-value! conn
                                            {:root root :tenant tenant
                                             :node-id node-id :path path})
              (swap! reverted conj tenant))))
      (audit/log-global-change! conn
                                {:action :promote-to-global
                                 :path path
                                 :global-version version
                                 :changelog changelog
                                 :previous-value nil
                                 :new-value candidate-value
                                 :encrypted? (boolean (:config-def/encrypted? definition))
                                 :user-email user-email
                                 :user-id user-id
                                 :ip-address ip-address})
      {:version version
       :pinned (vec (distinct @pinned))
       :reverted (vec (distinct @reverted))
       :inherited (vec (distinct @inherited))
       :strategy non-matching-strategy})))

;; =============================================================================
;; Demotion
;; =============================================================================

(defn demote-from-global!
  "Reverse of promote-to-global!. The definition's ownership returns to :fork.
   Any tenant that was inheriting (no direct value) receives a direct copy of
   the current global value so its effective value is preserved. The global
   tenant's value is then retracted. Pinned tenants keep their existing value
   (the :pin-of-version stamp is retracted since it's meaningless once the
   value is tenant-owned).

   Opts:
     :path        - required string
     :master-key  - required for encrypted definitions
     :user-id :user-email :ip-address - audit metadata

   Returns {:pushed-to-tenants [...] :was-already-tenant-owned [...]}."
  [conn {:keys [path master-key user-id user-email ip-address]}]
  (when (str/blank? path) (throw (ex-info "Missing :path" {})))
  (let [db @conn
        definition (assert-inherit-owned! db path)
        root (:config-def/root definition)
        global-raw (when-let [g-node (config-db/get-config-node-by-tenant-config-key
                                      db core/global-tenant root "default")]
                     (some-> (config-db/get-node-value db root core/global-tenant
                                                       (:config.node/id g-node) path)
                             :config.value/raw))
        global-decoded (decode-raw definition global-raw master-key)
        tenants (participating-tenants db root)
        direct (tenant-direct-values db path)
        inheriting (remove #(contains? direct (:tenant %)) tenants)
        already-owning (filter #(contains? direct (:tenant %)) tenants)]
    (when (nil? global-raw)
      (throw (ex-info "No global value present to demote from"
                      {:path path})))
    ;; Step 1: push the current global value down into every inheriting tenant.
    (doseq [{:keys [tenant node-id]} inheriting]
      (config-db/set-node-value! conn
                                 {:root root :tenant tenant :node-id node-id
                                  :path path :value global-decoded
                                  :master-key master-key}))
    ;; Step 2: retract :pin-of-version on tenants whose value was pinned.
    (let [pin-retracts (vec (for [{:keys [tenant node-id]} already-owning
                                  :let [vid (config-db/make-node-value-id
                                             root tenant node-id path)
                                        value-entity (get direct tenant)
                                        pin-version (:config.value/pin-of-version
                                                     value-entity)]
                                  :when pin-version]
                              [:db/retract [:config.value/id vid]
                               :config.value/pin-of-version pin-version]))]
      (when (seq pin-retracts)
        (d/transact conn {:tx-data pin-retracts})))
    ;; Step 3: retract the global value.
    (when-let [g-node (config-db/get-config-node-by-tenant-config-key
                       db core/global-tenant root "default")]
      (config-db/delete-node-value! conn
                                    {:root root :tenant core/global-tenant
                                     :node-id (:config.node/id g-node)
                                     :path path}))
    ;; Step 4: flip ownership back.
    (flip-ownership! conn path :fork)
    (audit/log-global-change! conn
                              {:action :demote-from-global
                               :path path
                               :previous-value global-raw
                               :new-value nil
                               :encrypted? (boolean (:config-def/encrypted? definition))
                               :user-email user-email
                               :user-id user-id
                               :ip-address ip-address})
    {:pushed-to-tenants (mapv :tenant inheriting)
     :was-already-tenant-owned (mapv :tenant already-owning)}))

;; =============================================================================
;; Priority-list heuristic for UI suggestions
;; =============================================================================

;; =============================================================================
;; Bulk promote-all (retire __platform-defaults__ preparation)
;; =============================================================================

(defn- platform-defaults-value-for
  "Raw value for a path on the __platform-defaults__ tenant's default node,
   or nil if the tenant has no tree / no value there."
  [db path root]
  (when-let [node (config-db/get-config-node-by-tenant-config-key
                   db "__platform-defaults__" root "default")]
    (some-> (config-db/get-node-value db root "__platform-defaults__"
                                      (:config.node/id node) path)
            :config.value/raw)))

(defn inspect-all-fork-definitions
  "Return a per-path report for every :fork-owned definition across all roots.
   Used as a dry-run before bulk promotion.

   For each path, the report entry is:
   {:path :root :value-type :encrypted?
    :tenants-with-value <int>
    :clusters [{:value v :count n :tenants [t...]}]
    :platform-defaults-value <decoded or nil>     ; if seed tenant has a value
    :candidate-source :platform-defaults | :modal | :none
    :candidate-value <decoded or nil>
    :promote? boolean                             ; true if we have a candidate
    :non-matching-tenants [t...]}"
  [db {:keys [master-key]}]
  (let [fork-defs (->> (config-db/get-all-definitions db)
                       (remove #(= :inherit (:config-def/ownership %)))
                       (sort-by :config-def/path))]
    (mapv
     (fn [definition]
       (let [path (:config-def/path definition)
             root (:config-def/root definition)
             inspection (inspect-definition-values db {:path path
                                                       :master-key master-key})
             pd-raw (platform-defaults-value-for db path root)
             pd-decoded (decode-raw definition pd-raw master-key)
             modal-value (:candidate-value inspection)
             has-pd? (some? pd-raw)
             has-modal? (pos? (:candidate-cluster-size inspection))
             [candidate-source candidate-value]
             (cond
               has-pd? [:platform-defaults pd-decoded]
               has-modal? [:modal modal-value]
               :else [:none nil])
             non-matching (->> (:clusters inspection)
                               (remove #(= (:value %) candidate-value))
                               (mapcat :tenants)
                               (map :tenant)
                               vec)]
         {:path path
          :root root
          :value-type (:config-def/value-type definition)
          :encrypted? (boolean (:config-def/encrypted? definition))
          :tenants-with-value (:with-value-count inspection)
          :clusters (:clusters inspection)
          :platform-defaults-value pd-decoded
          :candidate-source candidate-source
          :candidate-value candidate-value
          :promote? (not= candidate-source :none)
          :non-matching-tenants non-matching}))
     fork-defs)))

(defn promotion-plan-summary
  "Produce a high-level summary of inspect-all-fork-definitions for printing."
  [report]
  (let [total (count report)
        promotable (filter :promote? report)
        by-source (group-by :candidate-source promotable)
        skipped (remove :promote? report)]
    {:total-fork-defs total
     :would-promote (count promotable)
     :would-skip (count skipped)
     :from-platform-defaults (count (:platform-defaults by-source))
     :from-modal-cluster (count (:modal by-source))
     :skipped-paths (mapv :path skipped)}))

(defn promote-all-fork-definitions!
  "Promote every :fork-owned definition that has a candidate value (derived
   from __platform-defaults__ if available, otherwise the modal tenant-value
   cluster). Uses :pin-all so no tenant's effective value changes. Returns
   {:promoted [...] :skipped [...] :errors [...]}."
  [conn {:keys [master-key user-id changelog-prefix]
         :or {changelog-prefix "bulk promote: retire __platform-defaults__"}}]
  (let [db @conn
        report (inspect-all-fork-definitions db {:master-key master-key})
        promotable (filter :promote? report)
        results (atom {:promoted [] :skipped [] :errors []})]
    (doseq [{:keys [path candidate-value candidate-source]} promotable]
      (try
        (let [changelog (str changelog-prefix " — " path " (source " candidate-source ")")
              result (promote-to-global!
                      conn {:path path
                            :candidate-value candidate-value
                            :non-matching-strategy :pin-all
                            :changelog changelog
                            :master-key master-key
                            :user-id user-id})]
          (swap! results update :promoted conj
                 {:path path
                  :version (:version result)
                  :pinned (count (:pinned result))
                  :inherited (count (:inherited result))}))
        (catch Exception e
          (swap! results update :errors conj
                 {:path path :error (.getMessage e)}))))
    (doseq [skipped-entry (remove :promote? report)]
      (swap! results update :skipped conj
             {:path (:path skipped-entry)
              :reason "no candidate value — no tenant has an explicit value"}))
    @results))

(defn pin-all-globals-for-tenant!
  "Snapshot every inherit-owned global value at the given root into the
   tenant's default node, stamping :config.value/pin-of-version. Gives new
   tenants a fork-like posture on day one: future global edits won't change
   their effective values unless explicitly unpinned.

   The tenant's `default` tenant-config-key node must already exist at `root`.

   Opts:
     :tenant      - required tenant id
     :root        - a config root (see digdir.config.structure)
     :master-key  - required for encrypted definitions
     :user-id :user-email :ip-address - audit metadata

   Returns {:pinned-paths [...]}."
  [conn {:keys [tenant root master-key user-id user-email ip-address]}]
  (when (str/blank? tenant) (throw (ex-info "Missing :tenant" {})))
  (when-not root (throw (ex-info "Missing :root" {})))
  (let [db @conn
        node (or (config-db/get-config-node-by-tenant-config-key db tenant root "default")
                 (throw (ex-info "Tenant has no default node at root — bootstrap the tree first"
                                 {:tenant tenant :root root})))
        node-id (:config.node/id node)
        inherit-defs (->> (config-db/get-definitions-by-root db root)
                          (filter #(= :inherit (:config-def/ownership %))))
        pinned (vec (for [d inherit-defs
                          :let [path (:config-def/path d)
                                g-node (config-db/get-config-node-by-tenant-config-key
                                        db core/global-tenant root "default")
                                g-raw (when g-node
                                        (some-> (config-db/get-node-value
                                                 db root core/global-tenant
                                                 (:config.node/id g-node) path)
                                                :config.value/raw))]
                          :when g-raw]
                      (do (ops-global/pin-tenant-value!
                           conn {:tenant tenant
                                 :node-id node-id
                                 :path path
                                 :root root
                                 :master-key master-key
                                 :user-id user-id
                                 :user-email user-email
                                 :ip-address ip-address})
                          path)))]
    {:tenant tenant :root root :pinned-paths pinned}))

(defn suggested-for-promotion
  "Return fork-owned definitions whose current per-tenant values are ≥threshold
   consensus (default 0.8) — the \"low-hanging fruit\" for promotion. Operators
   can still promote anything; this is guidance, not a gate.

   Returns a vector of {:path :candidate-value :consensus-ratio :tenant-count}."
  [db {:keys [master-key threshold] :or {threshold 0.8}}]
  (->> (config-db/get-all-definitions db)
       (filter (fn [d] (not= :inherit (:config-def/ownership d))))
       (keep (fn [d]
               (let [path (:config-def/path d)
                     inspection (inspect-definition-values
                                 db {:path path :master-key master-key})
                     n (:with-value-count inspection)
                     top (:candidate-cluster-size inspection)
                     ratio (when (pos? n) (/ top n))]
                 (when (and ratio
                            (>= ratio threshold)
                            (pos? top))
                   {:path path
                    :candidate-value (:candidate-value inspection)
                    :consensus-ratio (double ratio)
                    :tenant-count n}))))
       (sort-by :consensus-ratio >)
       vec))
