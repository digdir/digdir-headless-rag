(ns digdir.api.context
  "Unified request and execution-context resolution."
  (:require [clojure.string :as str]
            [digdir.config.structure :as structure]
            [clojure.tools.logging :as log]
            [digdir.agents.db :as agents-db]
            [digdir.agents.policy :as agents-policy]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.accessor :as cfg]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [digdir.execution.scope :as scope]))

(def invalid-dataset-ref ::invalid-dataset-ref)

(defn non-blank-value
  [v]
  (cond
    (string? v) (when-not (str/blank? v) v)
    :else v))

(defn param-value
  [params k]
  (let [k-name (name k)]
    (or (non-blank-value (get params k))
        (non-blank-value (get params k-name))
        (non-blank-value (get params (keyword k-name))))))

(def ^:private legacy-dataset-key-aliases
  #{:tenant-config-key :tenant_config_key :config-key :config_key
    "tenant-config-key" "tenant_config_key" "config-key" "config_key"})

(def ^:private legacy-pipeline-aliases
  #{:pipeline :pipeline-id :pipeline_id
    "pipeline" "pipeline-id" "pipeline_id"})

(defn- reject-legacy-dataset-keys!
  [m]
  (doseq [k (keys m)]
    (when (contains? legacy-dataset-key-aliases k)
      (throw (ex-info (str "Legacy parameter '" (name k) "' is no longer accepted. "
                           "Use 'dataset-config-key' (or 'dataset_config_key') instead.")
                      {:status 400
                       :legacy-key k
                       :canonical-key :dataset-config-key})))))

(defn- reject-legacy-pipeline-keys!
  [m]
  (doseq [k (keys m)]
    (when (contains? legacy-pipeline-aliases k)
      (throw (ex-info (str "Legacy parameter '" (name k) "' is no longer accepted. "
                           "Use 'dataset-config-key' (or 'dataset_config_key') with 'tenant' instead.")
                      {:status 400
                       :legacy-key k
                       :canonical-keys [:tenant :dataset-config-key]})))))

(defn normalize-dataset-ref
  [dataset-ref]
  (when (map? dataset-ref)
    (reject-legacy-dataset-keys! dataset-ref)
    (scope/normalize-dataset-ref dataset-ref)))

(defn request-dataset-ref
  [params]
  (reject-legacy-dataset-keys! params)
  (let [dataset-ref-input (param-value params :dataset-ref)
        tenant (param-value params :tenant)
        dataset-config-key (or (param-value params :dataset-config-key)
                               (param-value params :dataset_config_key))]
    (cond
      dataset-ref-input
      (or (normalize-dataset-ref dataset-ref-input) invalid-dataset-ref)

      (or tenant dataset-config-key)
      (if (every? some? [tenant dataset-config-key])
        {:tenant tenant
         :dataset-config-key dataset-config-key}
        invalid-dataset-ref)

      :else nil)))

(defn request-explicit-dataset-ref
  [params]
  (reject-legacy-dataset-keys! params)
  (reject-legacy-pipeline-keys! params)
  (request-dataset-ref params))

(defn request-agent-id
  [params]
  (or (param-value params :agent-id)
      (param-value params :agent)))

(defn normalize-agent-ref
  [agent-ref]
  (let [agent-id (cond
                   (string? agent-ref) agent-ref
                   (keyword? agent-ref) (name agent-ref)
                   (map? agent-ref) (or (param-value agent-ref :agent-id)
                                        (param-value agent-ref :id))
                   :else nil)]
    (when (and (string? agent-id) (not (str/blank? agent-id)))
      agent-id)))

(defn normalize-agent-refs
  [agent-refs]
  (->> (or agent-refs [])
       (map normalize-agent-ref)
       (remove nil?)
       distinct
       vec))

(defn dataset-ref-key
  [dataset-ref]
  (scope/dataset-ref-key (or (normalize-dataset-ref dataset-ref)
                             dataset-ref)))

(defn public-dataset-scope
  [dataset-ref]
  (let [{:keys [tenant dataset-config-key]} (or (normalize-dataset-ref dataset-ref)
                                                dataset-ref)]
    {:tenant tenant
     :dataset-config-key dataset-config-key}))

(defn normalize-dataset-scopes!
  [dataset-scopes]
  (let [normalized (mapv normalize-dataset-ref (or dataset-scopes []))]
    (when (some nil? normalized)
      (throw (ex-info "Every dataset-scope must include non-blank tenant and dataset-config-key"
                      {:status 400})))
    (scope/distinct-dataset-refs normalized)))

(defn root-config-key-param [root]
  (let [root (keyword root)]
    (if (contains? structure/config-roots root)
      (keyword (str (name root) "-config-key"))
      ;; DERIVED, not written out: this message is read by a caller deciding what
      ;; to send. A hardcoded list here would tell them a fourth root is invalid
      ;; while the system accepted it — a copy with a reader is the worst kind.
      (throw (ex-info (str "Unknown config root: " root
                           ". Use one of: "
                           (str/join ", " structure/config-roots-ordered) ".")
                      {:status 400 :root root})))))

(def ^:private legacy-config-key-aliases
  #{:config-key :config_key "config-key" "config_key"
    :tenant-config-key :tenant_config_key "tenant-config-key" "tenant_config_key"})

(defn- reject-legacy-config-keys!
  [m]
  (doseq [k (keys m)]
    (when (contains? legacy-config-key-aliases k)
      (throw (ex-info (str "Legacy parameter '" (name k) "' is no longer accepted. "
                           "Use a root-scoped key: "
                           (str/join ", "
                                     (map #(str "'" (name (root-config-key-param %)) "'")
                                          structure/config-roots-ordered))
                           ".")
                      {:status 400
                       :legacy-key k
                       :canonical-keys (mapv root-config-key-param
                                             structure/config-roots-ordered)})))))

(defn normalize-allowed-config-key [allowed-config-key]
  (when (map? allowed-config-key)
    (reject-legacy-config-keys! allowed-config-key)
    (let [root (some-> (param-value allowed-config-key :root) keyword)
          tenant (param-value allowed-config-key :tenant)
          node-id (param-value allowed-config-key :node-id)
          config-key (when root
                       (param-value allowed-config-key (root-config-key-param root)))]
      (cond-> {}
        root (assoc :root root)
        tenant (assoc :tenant tenant)
        node-id (assoc :node-id node-id)
        config-key (assoc (root-config-key-param root) config-key)))))

(defn normalize-allowed-config-keys! [allowed-config-keys]
  (mapv normalize-allowed-config-key (or allowed-config-keys [])))

(defn request-config-key [params root]
  (reject-legacy-config-keys! params)
  (param-value params (root-config-key-param root)))

(defn require-request-config-key! [params root message]
  (or (request-config-key params root)
      (throw (ex-info message {:status 400}))))

(defn filter-dataset-scopes
  [dataset-scopes allowed-dataset-scopes]
  (let [allowed-set (set (map dataset-ref-key (or allowed-dataset-scopes [])))]
    (if (seq allowed-set)
      (->> dataset-scopes
           (filter #(contains? allowed-set (dataset-ref-key %)))
           vec)
      (vec dataset-scopes))))

(defn available-dataset-scopes-for-error
  [effective-granted-scopes]
  (when (seq effective-granted-scopes)
    (mapv public-dataset-scope effective-granted-scopes)))

(defn select-request-dataset-ref!
  ([ring-req params]
   (select-request-dataset-ref! ring-req params nil))
  ([ring-req params {:keys [allowed-dataset-scopes]
                     :or {}}]
   (let [requested (request-explicit-dataset-ref params)
         allowed-dataset-scopes (some-> allowed-dataset-scopes normalize-dataset-scopes!)
         granted-scopes (normalize-dataset-scopes! (or (:api-key/dataset-scopes ring-req) []))
         granted-set (set (map dataset-ref-key granted-scopes))
         policy-restricted? (seq allowed-dataset-scopes)
         effective-granted-scopes (filter-dataset-scopes granted-scopes allowed-dataset-scopes)
         effective-granted-set (set (map dataset-ref-key effective-granted-scopes))
         available (available-dataset-scopes-for-error effective-granted-scopes)]
     (cond
       (= requested invalid-dataset-ref)
       (do
         (log/debug "Rejecting API request because dataset selection is malformed"
                    {:request-method (:request-method ring-req)
                     :uri (:uri ring-req)
                     :path-info (:path-info ring-req)
                     :requested-dataset-ref requested
                     :granted-dataset-scopes granted-scopes
                     :allowed-dataset-scopes allowed-dataset-scopes})
         (throw (ex-info "Dataset selection must include tenant and dataset-config-key"
                         (cond-> {:status 400}
                           available (assoc :available available)))))

       requested
       (do
         (when-not (seq granted-set)
           (log/debug "Rejecting API request because the API key has no dataset scopes"
                      {:request-method (:request-method ring-req)
                       :uri (:uri ring-req)
                       :path-info (:path-info ring-req)
                       :requested-dataset-ref requested
                       :allowed-dataset-scopes allowed-dataset-scopes
                       :granted-dataset-scopes granted-scopes})
           (throw (ex-info "API key missing dataset scopes" {:status 401})))
         (when (and policy-restricted?
                    (not (contains? effective-granted-set (dataset-ref-key requested))))
           (log/debug "Rejecting API request because the selected dataset is not allowed by the active agent policy"
                      {:request-method (:request-method ring-req)
                       :uri (:uri ring-req)
                       :path-info (:path-info ring-req)
                       :requested-dataset-ref requested
                       :allowed-dataset-scopes allowed-dataset-scopes
                       :granted-dataset-scopes granted-scopes
                       :effective-granted-dataset-scopes effective-granted-scopes})
           (throw (ex-info "Selected agent is not allowed to access the selected dataset"
                           {:status 403
                            :dataset-scope requested})))
         (when-not (contains? granted-set (dataset-ref-key requested))
           (log/debug "Rejecting API request because the API key is not granted access to the requested dataset"
                      {:request-method (:request-method ring-req)
                       :uri (:uri ring-req)
                       :path-info (:path-info ring-req)
                       :requested-dataset-ref requested
                       :granted-dataset-scopes granted-scopes})
           (throw (ex-info "API key is not allowed to access the selected dataset"
                           {:status 403
                            :dataset-scope requested})))
         requested)

       (and policy-restricted? (seq granted-scopes) (empty? effective-granted-scopes))
       (do
         (log/debug "Rejecting API request because the active agent has no accessible datasets under this API key"
                    {:request-method (:request-method ring-req)
                     :uri (:uri ring-req)
                     :path-info (:path-info ring-req)
                     :allowed-dataset-scopes allowed-dataset-scopes
                     :granted-dataset-scopes granted-scopes})
         (throw (ex-info "Selected agent has no accessible datasets under this API key"
                         {:status 403
                          :agent-dataset-scopes allowed-dataset-scopes
                          :dataset-scopes granted-scopes})))

       (seq effective-granted-scopes)
       (do
         (log/debug "Rejecting API request because dataset selection is required for this API key"
                    {:request-method (:request-method ring-req)
                     :uri (:uri ring-req)
                     :path-info (:path-info ring-req)
                     :available-dataset-scopes available
                     :granted-dataset-scopes granted-scopes
                     :allowed-dataset-scopes allowed-dataset-scopes})
         (throw (ex-info "Dataset selection is required; specify tenant and dataset-config-key"
                         {:status 400
                          :available available})))

       :else
       (do
         (log/debug "Rejecting API request because the API key has no dataset scopes"
                    {:request-method (:request-method ring-req)
                     :uri (:uri ring-req)
                     :path-info (:path-info ring-req)
                     :allowed-dataset-scopes allowed-dataset-scopes})
         (throw (ex-info "API key missing dataset scopes" {:status 401})))))))

(declare load-agent!)

(defn- enabled-agent-ids
  "Ids of the agents that exist AND are enabled — the set a request can
   actually reach."
  []
  (let [conn (config-db/get-conn)]
    (when-not conn
      (throw (ex-info "Config DB connection is not available" {:status 500})))
    (into #{} (map :id) (agents-db/list-enabled-agents @conn))))

(defn candidate-agent-ids
  "The agents a request may select: what the key GRANTS, intersected with what
   is REACHABLE.

   #349. An empty `agent-refs` grants every agent, so a grant list NARROWS the
   reachable set rather than conferring it. Pure, and separate from the DB read,
   so the rule can be tested without a connection."
  [granted-agent-refs enabled-ids]
  (if (seq granted-agent-refs)
    (into #{} (filter enabled-ids) granted-agent-refs)
    (set enabled-ids)))

(defn select-request-agent!
  "Pick the agent for this request, or throw explaining why it cannot.

   #349: this counts CANDIDATES, not grants. It used to count grants, while
   every other surface counted reachable agents, and that single mismatch
   produced two opposite-looking symptoms: a key with no grants was refused
   even when exactly one agent existed to pick, and a key granting two agents
   was told to `specify agent-id` when one of the two was disabled and
   unusable. Both are the same bug from different sides.

   It also VALIDATES the selection, which nothing on this path did. A caller
   could name any string — `POST /api/conversations` returned 201 and persisted
   a conversation against `no/such-agent-xyz`. Validation already existed in
   `load-agent!`; this path was simply the one caller that selected without it,
   so the 404/403 split below is delegated there rather than re-invented."
  ([ring-req params]
   (select-request-agent! ring-req params nil))
  ([ring-req params existing-agent-id]
   (let [requested-agent-id (request-agent-id params)
         granted-agent-refs (vec (or (:api-key/agent-refs ring-req) []))
         enabled-ids (enabled-agent-ids)
         candidates (candidate-agent-ids granted-agent-refs enabled-ids)
         default-agent-id (or existing-agent-id
                              (when (= 1 (count candidates))
                                (first candidates)))
         selected-agent-id (or requested-agent-id default-agent-id)]
     (cond
       (and requested-agent-id existing-agent-id (not= requested-agent-id existing-agent-id))
       (throw (ex-info "Conversation agent-id does not match the requested agent-id"
                       {:status 400
                        :agent-id requested-agent-id
                        :conversation-agent-id existing-agent-id}))

       selected-agent-id
       (do
         ;; Unreachable selection: let `load-agent!` raise its own 404 (absent)
         ;; or 403 (disabled), so one vocabulary describes one condition.
         (when-not (contains? enabled-ids selected-agent-id)
           (load-agent! selected-agent-id))
         (when-not (contains? candidates selected-agent-id)
           (throw (ex-info "API key is not allowed to access the selected agent"
                           {:status 403
                            :agent-id selected-agent-id})))
         selected-agent-id)

       (seq candidates)
       (throw (ex-info "Multiple agents are available; specify agent-id"
                       {:status 400
                        :candidates (vec (sort candidates))}))

       :else
       (throw (ex-info (if (seq granted-agent-refs)
                         "None of this API key's granted agents are available"
                         "No agents are available")
                       {:status 503
                        :agent-refs granted-agent-refs}))))))

(defn load-agent!
  [agent-id]
  (let [conn (config-db/get-conn)]
    (when-not conn
      (throw (ex-info "Config DB connection is not available" {:status 500})))
    (let [agent (agents-db/get-agent @conn agent-id)]
      (when-not agent
        (throw (ex-info (str "Agent not found: " agent-id)
                        {:status 404
                         :agent-id agent-id})))
      (when-not (agents-policy/enabled? agent)
        (throw (ex-info (str "Agent is disabled: " agent-id)
                        {:status 403
                         :agent-id agent-id})))
      agent)))

(defn validate-agent-refs! [agent-refs]
  (let [agent-refs (normalize-agent-refs agent-refs)]
    (when (seq agent-refs)
      (let [conn (config-db/get-conn)]
        (when-not conn
          (throw (ex-info "Config DB connection is not available" {:status 500})))
        (let [db @conn]
          (doseq [agent-id agent-refs]
            (when-not (agents-db/get-agent db agent-id)
              (throw (ex-info (str "Agent not found: " agent-id)
                              {:status 404
                               :agent-id agent-id})))))))
    agent-refs))

(defn resolve-request-agent-policy!
  ([ring-req params]
   (resolve-request-agent-policy! ring-req params nil))
  ([ring-req params existing-agent-id]
   (let [agent-id (select-request-agent! ring-req params existing-agent-id)
         agent (load-agent! agent-id)]
     {:agent-id agent-id
      :agent agent
      :execution-policy (agents-policy/resolve-execution-policy agent)})))

(defn resolve-request-config-node!
  [ring-req conn {:keys [tenant root tenant-config-key node-id]}]
  (let [node (or (when node-id
                   (config-db/get-config-node @conn node-id))
                 (when tenant-config-key
                   (config-db/get-config-node-by-tenant-config-key @conn tenant root tenant-config-key))
                 (throw (ex-info "Config node config key not found"
                                 {:status 404
                                  :tenant tenant
                                  :root root
                                  :tenant-config-key tenant-config-key
                                  :node-id node-id})))]
    (when-not (:config.node/enabled? node)
      (throw (ex-info "Config node is disabled"
                      {:status 403
                       :tenant tenant
                       :root root
                       :tenant-config-key tenant-config-key
                       :node-id (:config.node/id node)})))
    (when (:config.node/system-managed? node)
      (throw (ex-info "Explicit requests must target a tenant node"
                      {:status 400
                       :tenant tenant
                       :root root
                       :tenant-config-key tenant-config-key
                       :node-id (:config.node/id node)})))
    (let [allowed-config-keys (seq (:api-key/allowed-config-keys ring-req))
          request {:root root
                   :tenant tenant
                   :node-id (:config.node/id node)}
          matched-allowed-config-key (when allowed-config-keys
                                       (try
                                         (api-keys/require-allowed-config-key! conn
                                                                               allowed-config-keys
                                                                               request)
                                         (catch clojure.lang.ExceptionInfo e
                                           (when (and (= 403 (:status (ex-data e)))
                                                      (= "API key is not allowed to access the requested config node"
                                                         (.getMessage e)))
                                             (log/debug "Rejecting config node request because API key is not allowed to access the requested node"
                                                        {:api-key/id (:api-key/id ring-req)
                                                         :api-key/name (:api-key/name ring-req)
                                                         :api-key/client-id (:api-key/client-id ring-req)
                                                         :request request
                                                         :allowed-config-keys (vec allowed-config-keys)}))
                                           (throw e))))]
      {:node node
       :matched-allowed-config-key matched-allowed-config-key})))

(defn assoc-execution-scope
  "Attach canonical tenant/dataset/agent scope to execution opts and skill params."
  [opts {:keys [tenant dataset-config-key dataset-ref agent-id entity]}]
  (scope/assoc-execution-scope opts
                               {:tenant tenant
                                :dataset-config-key dataset-config-key
                                :dataset-ref dataset-ref
                                :agent-id agent-id
                                :entity entity}))

(defn resolve-dataset-context-by-ref!
  "Resolve canonical dataset context for a dataset-ref without request auth."
  [dataset-ref]
  (scope/resolve-dataset-context-by-ref! dataset-ref))

(defn resolve-request-dataset-context!
  ([ring-req params]
   (resolve-request-dataset-context! ring-req params nil))
  ([ring-req params {:keys [allowed-dataset-scopes runtime-config-key runtime-node-id agent-id]}]
   (let [dataset-ref (select-request-dataset-ref! ring-req params {:allowed-dataset-scopes allowed-dataset-scopes})
         dataset-context (resolve-dataset-context-by-ref! dataset-ref)
         dataset-config-key (:dataset-config-key dataset-context)
         conn (db/get-conn)
         {:keys [node matched-allowed-config-key]} (resolve-request-config-node! ring-req
                                                                                 conn
                                                                                 {:tenant (:tenant dataset-ref)
                                                                                  :root :dataset
                                                                                  :tenant-config-key dataset-config-key
                                                                                  :node-id (:dataset-node-id dataset-context)})
         runtime-config-key (or runtime-config-key
                                (param-value params :runtime-config-key))
         runtime-config-result (when agent-id
                                 (try
                                   (cfg/get-runtime-skill-config-v2-with-trace
                                    (cond-> {:tenant (:tenant dataset-ref)
                                             :tenant-config-key runtime-config-key
                                             :agent-id agent-id
                                             :dataset-id (:dataset-id dataset-context)}
                                      runtime-node-id (assoc :node-id runtime-node-id)))
                                   (catch Exception e
                                     (throw (ex-info "Runtime config resolution failed"
                                                     {:status 409
                                                      :tenant (:tenant dataset-ref)
                                                      :runtime-config-key runtime-config-key
                                                      :agent-id agent-id
                                                      :runtime-config-error {:message (.getMessage e)
                                                                             :type (str (type e))}}
                                                     e)))))
         runtime-config (or (:config runtime-config-result) {})
         config (merge (:dataset-config dataset-context) runtime-config)]
     (assoc dataset-context
            :dataset-node node
            :matched-dataset-allowed-config-key matched-allowed-config-key
            :runtime-config-key runtime-config-key
            :runtime-node (:node runtime-config-result)
            :runtime-config runtime-config
            :config config
            :traces {:dataset (:traces dataset-context)
                     :runtime (:traces runtime-config-result)}))))

(defn resolve-request-execution-context!
  "Resolve full request execution scope: agent policy, dataset context, merged config, and traces."
  ([ring-req params]
   (resolve-request-execution-context! ring-req params nil))
  ([ring-req params {:keys [existing-agent-id require-runtime-config? skip-agent? runtime-node-id]
                     :or {require-runtime-config? false
                          skip-agent? false}}]
   (let [{:keys [agent-id agent execution-policy]}
         (when-not skip-agent?
           (resolve-request-agent-policy! ring-req params existing-agent-id))
         runtime-config-key (or (param-value params :runtime-config-key)
                                (:runtime-config-key params))
         _ (when (and require-runtime-config?
                      (not skip-agent?)
                      (str/blank? runtime-config-key))
             (throw (ex-info "Missing required field: runtime-config-key" {:status 400})))
         dataset-context (resolve-request-dataset-context!
                          ring-req
                          params
                          {:allowed-dataset-scopes (:allowed-dataset-scopes execution-policy)
                           :runtime-config-key runtime-config-key
                           :runtime-node-id runtime-node-id
                           :agent-id agent-id})
         granted-dataset-scopes (normalize-dataset-scopes! (or (:api-key/dataset-scopes ring-req) []))
         allowed-dataset-scopes (if execution-policy
                                  (filter-dataset-scopes granted-dataset-scopes
                                                         (:allowed-dataset-scopes execution-policy))
                                  granted-dataset-scopes)]
     (assoc dataset-context
            :agent-id agent-id
            :agent agent
            :execution-policy execution-policy
            :allowed-dataset-scopes allowed-dataset-scopes
            :skill-graph-id (or (:default-skill-graph execution-policy)
                                "builtin/agent-rag-graph-bundled")))))
