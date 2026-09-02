(ns digdir.import-export.canonical.system
  "Canonical steady-state system export normalization."
  (:require [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.structure :as structure]
            [digdir.import-export.model :as model]))

(def ^:private default-agent-id "builtin/agent-rag-agent")
(def ^:private legacy-runtime-config-path-renames
  {"pipeline.generate.prompt.query-relax" "skills.query-planner.prompt"
   "pipeline.generate.prompt.phrase-gen" "skills.retrieval.phrase-gen-prompt"
   "pipeline.retrieval.retrieve.top-k" "skills.retrieval.top-k"
   "pipeline.retrieval.retrieve.max-per-document" "skills.retrieval.max-per-document"
   "pipeline.retrieval.retrieve.query-aware-boost" "skills.retrieval.query-aware-boost"
   "pipeline.retrieval.rerank.enabled" "skills.rerank.enabled"
   "pipeline.retrieval.rerank.top-k" "skills.rerank.top-k"
   ;; The 5 rerank knobs below were split into mode-namespaced variants
   ;; (skills.rerank.{rag,retrieval}.*) in 2026-04. Legacy imports default to
   ;; the :rag variants since that's the full-RAG path most deployments use.
   ;; Operators importing values intended for retrieval-only mode must hand-edit
   ;; the new :retrieval-* paths after import.
   "pipeline.retrieval.rerank.max-chunk-length" "skills.rerank.rag.max-chunk-length"
   "pipeline.retrieval.rerank.max-total-length" "skills.rerank.rag.max-total-length"
   "pipeline.retrieval.context.top-k" "skills.rerank.rag.context.top-k"
   "pipeline.retrieval.context.min-chunks" "skills.rerank.context.min-chunks"
   "pipeline.retrieval.context.relative-score-threshold" "skills.rerank.context.relative-score-threshold"
   "pipeline.retrieval.context.max-docs" "skills.synthesis.max-docs"
   "pipeline.retrieval.context.max-chunk-length" "skills.rerank.rag.context.max-chunk-length"
   "pipeline.retrieval.context.max-total-length" "skills.rerank.rag.max-context-length"
   "pipeline.generate.prompt.rag-generate" "skills.synthesis.generation-prompt"})

(defn- kw-or-string
  [m k]
  (or (get m k)
      (get m (keyword (name k)))
      (get m (name k))
      (get m (str (namespace k) "/" (name k)))))

(defn- non-blank-string
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s)
      s)))

(defn- parse-legacy-pipeline-id!
  [!report pipeline-id context]
  (let [[tenant environment pipeline] (when (string? pipeline-id)
                                        (str/split pipeline-id #":" 3))]
    (when (string? pipeline-id)
      (swap! !report update :composite-ids-parsed (fnil inc 0)))
    (if (and (non-blank-string tenant)
             (non-blank-string environment)
             (non-blank-string pipeline))
      {:tenant tenant
       :tenant-config-key environment
       :pipeline pipeline}
      (do
        (swap! !report update :malformed-ids (fnil conj []) {:pipeline-id pipeline-id
                                                             :context context})
        (throw (ex-info "Malformed composite pipeline-id"
                        {:pipeline-id pipeline-id
                         :context context}))))))

(defn- normalize-dataset-ref
  [dataset-ref]
  (let [tenant (or (:tenant dataset-ref)
                   (:api-key.dataset-scope/tenant dataset-ref)
                   (:dataset-scope/tenant dataset-ref)
                   (get dataset-ref "tenant")
                   (get dataset-ref "api-key.dataset-scope/tenant")
                   (get dataset-ref "dataset-scope/tenant"))
        dataset-config-key (or (:dataset-config-key dataset-ref)
                               (:api-key.dataset-scope/dataset-config-key dataset-ref)
                               (:dataset-scope/dataset-config-key dataset-ref)
                               (get dataset-ref "dataset-config-key")
                               (get dataset-ref "api-key.dataset-scope/dataset-config-key")
                               (get dataset-ref "dataset-scope/dataset-config-key")
                               (get dataset-ref "dataset_config_key")
                               (:tenant-config-key dataset-ref)
                               (get dataset-ref "tenant-config-key")
                               (:environment dataset-ref)
                               (get dataset-ref "environment"))]
    (when (and (non-blank-string tenant)
               (non-blank-string dataset-config-key))
      {:tenant tenant
       :dataset-config-key dataset-config-key})))

(defn- normalize-dataset-scopes!
  [!report api-key]
  (let [explicit (keep normalize-dataset-ref (or (kw-or-string api-key :api-key/dataset-scopes)
                                                 (kw-or-string api-key :api-key/dataset-refs)
                                                 []))
        parsed (keep #(some-> (parse-legacy-pipeline-id! !report % {:type :api-key
                                                                    :api-key-id (kw-or-string api-key :api-key/id)})
                              normalize-dataset-ref)
                     (or (kw-or-string api-key :api-key/pipelines) []))
        dataset-scopes (vec (concat explicit parsed))]
    (swap! !report update :dataset-scopes-created (fnil + 0) (count dataset-scopes))
    dataset-scopes))

(defn- normalize-agent-refs
  [api-key]
  (->> (or (kw-or-string api-key :api-key/agent-refs) [])
       (map (fn [agent-ref]
              (cond
                (string? agent-ref) agent-ref
                (map? agent-ref) (or (:agent-id agent-ref)
                                     (:api-key.agent-ref/agent-id agent-ref)
                                     (get agent-ref "agent-id")
                                     (get agent-ref "api-key.agent-ref/agent-id"))
                :else nil)))
       (keep non-blank-string)
       distinct
       vec))

(defn- normalize-message-export
  [message]
  (cond-> {:message/id (kw-or-string message :message/id)
           :message/text (kw-or-string message :message/text)
           :message/role (kw-or-string message :message/role)
           :message/voice (kw-or-string message :message/voice)
           :message/completion (kw-or-string message :message/completion)
           :message/kind (kw-or-string message :message/kind)
           :message/created (kw-or-string message :message/created)
           :message/chunks (vec (or (kw-or-string message :message/chunks) []))}
    (kw-or-string message :message/config) (assoc :message/config (kw-or-string message :message/config))
    (kw-or-string message :message/diagnostics) (assoc :message/diagnostics (kw-or-string message :message/diagnostics))
    (kw-or-string message :message/execution-id) (assoc :message/execution-id (kw-or-string message :message/execution-id))
    (some? (kw-or-string message :message/branch-index)) (assoc :message/branch-index (kw-or-string message :message/branch-index))
    (kw-or-string message :message.filter/value) (assoc :message.filter/value (kw-or-string message :message.filter/value))
    (kw-or-string message :message/parent-id) (assoc :message/parent-id (kw-or-string message :message/parent-id))))

(defn- normalize-conversation-export
  [!report conversation]
  (swap! !report update :conversations-transformed (fnil inc 0))
  (let [agent-id (or (kw-or-string conversation :conversation/agent-id)
                     (do
                       (swap! !report update :defaults-applied (fnil inc 0))
                       default-agent-id))
        explicit-pipeline (kw-or-string conversation :conversation/pipeline)
        legacy-pipeline-id (kw-or-string conversation :conversation/pipeline-id)
        parsed-legacy (when (and (nil? explicit-pipeline)
                                 (string? legacy-pipeline-id)
                                 (str/includes? legacy-pipeline-id ":"))
                        (parse-legacy-pipeline-id! !report
                                                   legacy-pipeline-id
                                                   {:type :conversation
                                                    :conversation-id (kw-or-string conversation :conversation/id)}))
        tenant (or (kw-or-string conversation :conversation/tenant)
                   (:tenant parsed-legacy))
        environment (or (kw-or-string conversation :conversation/environment)
                        (:tenant-config-key parsed-legacy))
        pipeline (or explicit-pipeline
                     (:pipeline parsed-legacy)
                     legacy-pipeline-id)]
    (cond-> {:conversation/id (kw-or-string conversation :conversation/id)
             :conversation/topic (kw-or-string conversation :conversation/topic)
             :conversation/created (kw-or-string conversation :conversation/created)
             :conversation/agent-id agent-id
             :conversation/messages (->> (or (kw-or-string conversation :conversation/messages) [])
                                         (mapv normalize-message-export)
                                         (sort-by (juxt :message/created :message/id))
                                         vec)}
      pipeline (assoc :conversation/pipeline pipeline)
      (kw-or-string conversation :conversation/user-id) (assoc :conversation/user-id (kw-or-string conversation :conversation/user-id))
      (kw-or-string conversation :conversation/type) (assoc :conversation/type (kw-or-string conversation :conversation/type))
      tenant (assoc :conversation/tenant tenant)
      environment (assoc :conversation/environment environment)
      (kw-or-string conversation :conversation/view-mode) (assoc :conversation/view-mode (kw-or-string conversation :conversation/view-mode))
      (or (kw-or-string conversation :conversation/folder-id)
          (get-in conversation [:conversation/folder :folder/id]))
      (assoc :conversation/folder-id (or (kw-or-string conversation :conversation/folder-id)
                                         (get-in conversation [:conversation/folder :folder/id]))))))

(defn- normalize-folder-export
  [folder]
  (cond-> {:folder/id (kw-or-string folder :folder/id)
           :folder/name (kw-or-string folder :folder/name)}
    (kw-or-string folder :folder/created) (assoc :folder/created (kw-or-string folder :folder/created))))

(defn- normalize-agent-export
  [agent]
  (cond-> {:id (kw-or-string agent :id)
           :name (kw-or-string agent :name)
           :description (kw-or-string agent :description)
           :instructions (kw-or-string agent :instructions)
           :default-skill-graph (kw-or-string agent :default-skill-graph)
           :allowed-skill-graphs (vec (or (kw-or-string agent :allowed-skill-graphs) []))
           :allowed-dataset-scopes (->> (or (kw-or-string agent :allowed-dataset-scopes) [])
                                        (keep normalize-dataset-ref)
                                        vec)
           :guardrails (or (kw-or-string agent :guardrails) {})
           :enabled? (boolean (kw-or-string agent :enabled?))}
    (kw-or-string agent :input-title) (assoc :input-title (kw-or-string agent :input-title))
    (kw-or-string agent :input-placeholder) (assoc :input-placeholder (kw-or-string agent :input-placeholder))))

(defn- normalize-user-export
  [user]
  (cond-> {:user/id (or (kw-or-string user :user/id)
                        (kw-or-string user :id))
           :user/email (or (kw-or-string user :user/email)
                           (kw-or-string user :email))
           :user/preferred-language (or (kw-or-string user :user/preferred-language)
                                        (kw-or-string user :preferred-language))
           :user/created (or (kw-or-string user :user/created)
                             (kw-or-string user :created))
           :user/permissions (->> (or (kw-or-string user :user/permissions)
                                      (kw-or-string user :permissions)
                                      [])
                                  (mapv #(or (:permission/id %)
                                             (:id %)
                                             (get % "permission/id")
                                             (get % "id")
                                             (when (string? %) %)))
                                  (keep non-blank-string)
                                  distinct
                                  vec)}))

(defn- normalize-api-key-export
  [!report api-key]
  (swap! !report update :api-keys-transformed (fnil inc 0))
  (when (or (seq (kw-or-string api-key :api-key/tenants))
            (seq (kw-or-string api-key :api-key/environments))
            (seq (kw-or-string api-key :api-key/pipelines)))
    (swap! !report update :fields-dropped (fnil + 0) 3))
  (let [allowed-config-keys (->> (or (kw-or-string api-key :api-key/allowed-config-keys)
                                     (kw-or-string api-key :api-key/config-ceilings)
                                     [])
                                 (mapv (fn [allowed-config-key]
                                         {:api-key.allowed-config-key/id (kw-or-string allowed-config-key :api-key.allowed-config-key/id)
                                          :api-key.allowed-config-key/root (some-> (or (kw-or-string allowed-config-key :api-key.allowed-config-key/root)
                                                                                       (kw-or-string allowed-config-key :api-key.config-ceiling/root))
                                                                                  keyword)
                                          :api-key.allowed-config-key/tenant (or (kw-or-string allowed-config-key :api-key.allowed-config-key/tenant)
                                                                                 (kw-or-string allowed-config-key :api-key.config-ceiling/tenant))
                                          :api-key.allowed-config-key/node-id (or (kw-or-string allowed-config-key :api-key.allowed-config-key/node-id)
                                                                                  (kw-or-string allowed-config-key :api-key.config-ceiling/node-id))
                                          :api-key.allowed-config-key/tenant-config-key (or (kw-or-string allowed-config-key :api-key.allowed-config-key/tenant-config-key)
                                                                                             (kw-or-string allowed-config-key :api-key.config-ceiling/tenant-config-key))
                                          :api-key.allowed-config-key/created-at (or (kw-or-string allowed-config-key :api-key.allowed-config-key/created-at)
                                                                                     (kw-or-string allowed-config-key :api-key.config-ceiling/created-at))})))]
    {:api-key/id (kw-or-string api-key :api-key/id)
     :api-key/key (kw-or-string api-key :api-key/key)
     :api-key/key-digest (kw-or-string api-key :api-key/key-digest)
     :api-key/prefix (kw-or-string api-key :api-key/prefix)
     :api-key/last-four (kw-or-string api-key :api-key/last-four)
     :api-key/name (kw-or-string api-key :api-key/name)
     :api-key/created (kw-or-string api-key :api-key/created)
     :api-key/created-by (kw-or-string api-key :api-key/created-by)
     :api-key/revoked (boolean (kw-or-string api-key :api-key/revoked))
     :api-key/last-used (kw-or-string api-key :api-key/last-used)
     :api-key/usage-count (or (kw-or-string api-key :api-key/usage-count) 0)
     :api-key/expires-at (kw-or-string api-key :api-key/expires-at)
     :api-key/clients (vec (or (kw-or-string api-key :api-key/clients) []))
     :api-key/skill-graphs (vec (or (kw-or-string api-key :api-key/skill-graphs) []))
     :api-key/scopes (vec (or (kw-or-string api-key :api-key/scopes) [:query]))
     :api-key/dataset-scopes (let [dataset-scopes (->> (or (kw-or-string api-key :api-key/dataset-scopes)
                                                           (kw-or-string api-key :api-key/dataset-refs)
                                                           [])
                                                       (keep normalize-dataset-ref)
                                                       vec)]
                               (if (seq dataset-scopes)
                                 (do
                                   (swap! !report update :dataset-scopes-created (fnil + 0) (count dataset-scopes))
                                   dataset-scopes)
                                 (normalize-dataset-scopes! !report api-key)))
     :api-key/agent-refs (let [agent-refs (->> (or (kw-or-string api-key :api-key/agent-refs) [])
                                               (map (fn [agent-ref]
                                                      (or (:api-key.agent-ref/agent-id agent-ref)
                                                          (kw-or-string agent-ref :api-key.agent-ref/agent-id)
                                                          (kw-or-string agent-ref :agent-id)
                                                          (when (string? agent-ref) agent-ref))))
                                               (keep non-blank-string)
                                               distinct
                                               vec)]
                           (if (seq agent-refs)
                             agent-refs
                             (normalize-agent-refs api-key)))
     :api-key/allowed-config-keys allowed-config-keys}))

(defn- normalize-export-map-keys
  [prefix m]
  (into {}
        (map (fn [[k v]]
               (let [k-str (cond
                             (keyword? k) (if-let [ns (namespace k)]
                                            (str ns "/" (name k))
                                            (name k))
                             (string? k) k
                             :else (str k))
                     export-key (if (str/includes? k-str "/")
                                  k-str
                                  (str prefix "/" k-str))]
                 [export-key v])))
        (or m {})))

(defn- envelope->system-data
  [data]
  (let [config-export (or (:config data) (get data "config"))
        main-export (or (:main data) (get data "main"))
        direct-data (or (:data data) (get data "data"))]
    (cond
      (and direct-data (or (:definitions direct-data)
                           (:users direct-data)
                           (:api-keys direct-data)
                           (:conversations direct-data)))
      {:definitions (or (:definitions direct-data) (get direct-data "definitions") [])
       :values (or (:values direct-data) (get direct-data "values") [])
       :nodes (or (:nodes direct-data) (get direct-data "nodes") [])
       :bindings (or (:bindings direct-data) (get direct-data "bindings") [])
       :compatibilities (or (:compatibilities direct-data) (get direct-data "compatibilities") [])
       :datasets (or (:datasets direct-data) (get direct-data "datasets") [])
       :dataset-pipelines (or (:dataset-pipelines direct-data) (get direct-data "dataset-pipelines") [])
       :node-values (or (:node-values direct-data) (get direct-data "node-values") [])
       :users (or (:users direct-data) (get direct-data "users") [])
       :agents (or (:agents direct-data) (get direct-data "agents") [])
       :api-keys (or (:api-keys direct-data) (get direct-data "api-keys") [])
       :conversations (or (:conversations direct-data) (get direct-data "conversations") [])
       :folders (or (:folders direct-data) (get direct-data "folders") [])
       :audit (or (:audit direct-data) (get direct-data "audit") [])}

      (or config-export main-export)
      {:definitions (or (get-in config-export [:data :definitions])
                        (get-in config-export ["data" "definitions"])
                        [])
       :values (or (get-in config-export [:data :values])
                   (get-in config-export ["data" "values"])
                   [])
       :nodes (or (get-in config-export [:data :nodes])
                  (get-in config-export ["data" "nodes"])
                  [])
       :bindings (or (get-in config-export [:data :bindings])
                     (get-in config-export ["data" "bindings"])
                     [])
       :compatibilities (or (get-in config-export [:data :compatibilities])
                            (get-in config-export ["data" "compatibilities"])
                            [])
       :datasets (or (get-in config-export [:data :datasets])
                     (get-in config-export ["data" "datasets"])
                     [])
       :dataset-pipelines (or (get-in config-export [:data :dataset-pipelines])
                              (get-in config-export ["data" "dataset-pipelines"])
                              [])
       :node-values (or (get-in config-export [:data :node-values])
                        (get-in config-export ["data" "node-values"])
                        [])
       :users (or (:users config-export)
                  (get config-export "users")
                  (get-in config-export [:data :users])
                  (get-in config-export ["data" "users"])
                  [])
       :agents (or (:agents main-export)
                   (get main-export "agents")
                   [])
       :api-keys (or (:api-keys main-export)
                     (get main-export "api-keys")
                     [])
       :conversations (or (:conversations main-export)
                          (get main-export "conversations")
                          [])
       :folders (or (:folders main-export)
                    (get main-export "folders")
                    [])
       :audit (or (get-in config-export [:data :audit])
                  (get-in config-export ["data" "audit"])
                  [])}

      :else
      {:definitions []
       :values []
       :nodes []
       :bindings []
       :compatibilities []
       :datasets []
       :dataset-pipelines []
       :node-values []
       :users []
       :agents []
       :api-keys []
       :conversations []
       :folders []
       :audit []})))

(defn- normalize-system-export
  [data]
  (let [!report (atom {:api-keys-transformed 0
                       :dataset-scopes-created 0
                       :conversations-transformed 0
                       :composite-ids-parsed 0
                       :malformed-ids []
                       :fields-dropped 0
                       :defaults-applied 0})
        {:keys [definitions values nodes bindings compatibilities datasets dataset-pipelines node-values
                users agents api-keys conversations folders audit]} (envelope->system-data data)]
    (assoc (model/system-envelope {:definitions (mapv #(normalize-export-map-keys "config-def" %) definitions)
                                   :values (mapv #(normalize-export-map-keys "config" %) values)
                                   :nodes (mapv #(normalize-export-map-keys "config.node" %) nodes)
                                   :bindings (mapv #(normalize-export-map-keys "config.binding" %) bindings)
                                   :compatibilities (mapv #(normalize-export-map-keys "config.compatibility" %) compatibilities)
                                   :datasets (mapv #(normalize-export-map-keys "dataset" %) datasets)
                                   :dataset-pipelines (mapv #(normalize-export-map-keys "dataset.pipeline" %) dataset-pipelines)
                                   :node-values (mapv #(normalize-export-map-keys "config.value" %) node-values)
                                   :users (mapv normalize-user-export users)
                                   :agents (mapv normalize-agent-export agents)
                                   :api-keys (mapv #(normalize-api-key-export !report %) api-keys)
                                   :conversations (mapv #(normalize-conversation-export !report %) conversations)
                                   :folders (mapv normalize-folder-export folders)
                                   :audit (mapv #(normalize-export-map-keys "audit" %) audit)}
                                  (or (:exported-at data) (get data "exported-at") (model/iso-timestamp)))
           :migration-report @!report)))

(defn- rename-runtime-config-path
  [path]
  (get legacy-runtime-config-path-renames path path))

(defn- rewrite-skill-config-description
  [description]
  (-> description
      (str/replace "Pipeline property:" "Skill property:")
      (str/replace "Pipeline properties" "Skill properties")
      (str/replace "Pipeline property" "Skill property")))

(defn- rewrite-config-id-runtime-path
  [config-id]
  (if (string? config-id)
    (let [{:keys [tenant environment client skill-graph pipeline path]} (config-db/parse-config-id config-id)
          renamed-path (rename-runtime-config-path path)]
      (if (= path renamed-path)
        config-id
        (config-db/make-config-id tenant environment client skill-graph pipeline renamed-path)))
    config-id))

(defn- rewrite-runtime-definition
  [definition]
  (let [path (get definition "config-def/path")
        renamed-path (rename-runtime-config-path path)]
    (if (= path renamed-path)
      [definition false]
      [(cond-> (assoc definition
                      "config-def/path" renamed-path
                      "config-def/category" "skills")
         (string? (get definition "config-def/description"))
         (assoc "config-def/description"
                (rewrite-skill-config-description
                 (get definition "config-def/description"))))
       true])))

(defn- rewrite-runtime-value
  [value]
  (let [definition-path (get value "config/definition-path")
        renamed-definition-path (rename-runtime-config-path definition-path)
        config-id (get value "config/id")
        renamed-config-id (rewrite-config-id-runtime-path config-id)
        touched? (or (not= definition-path renamed-definition-path)
                     (not= config-id renamed-config-id))]
    [(cond-> value
       (not= definition-path renamed-definition-path)
       (assoc "config/definition-path" renamed-definition-path)

       (not= config-id renamed-config-id)
       (assoc "config/id" renamed-config-id))
     touched?]))

(defn- rewrite-runtime-node-value
  [value]
  (let [definition-path (get value "config.value/definition-path")
        renamed-definition-path (rename-runtime-config-path definition-path)
        touched? (not= definition-path renamed-definition-path)]
    [(cond-> value
       touched? (assoc "config.value/definition-path" renamed-definition-path))
     touched?]))

(defn- rewrite-runtime-audit-record
  [record]
  (let [path (get record "audit/config-path")
        renamed-path (rename-runtime-config-path path)]
    [(cond-> record
       (not= path renamed-path)
       (assoc "audit/config-path" renamed-path))
     (not= path renamed-path)]))

(defn- rewrite-runtime-config-paths
  [normalized-export]
  (let [{:keys [definitions values node-values audit]} (:data normalized-export)
        rewritten-definitions (mapv rewrite-runtime-definition definitions)
        rewritten-values (mapv rewrite-runtime-value values)
        rewritten-node-values (mapv rewrite-runtime-node-value node-values)
        rewritten-audit (mapv rewrite-runtime-audit-record audit)
        definitions-renamed (count (filter second rewritten-definitions))
        values-renamed (count (filter second rewritten-values))
        node-values-renamed (count (filter second rewritten-node-values))
        audit-renamed (count (filter second rewritten-audit))
        total-renamed (+ definitions-renamed values-renamed node-values-renamed audit-renamed)]
    (-> normalized-export
        (assoc-in [:data :definitions] (mapv first rewritten-definitions))
        (assoc-in [:data :values] (mapv first rewritten-values))
        (assoc-in [:data :node-values] (mapv first rewritten-node-values))
        (assoc-in [:data :audit] (mapv first rewritten-audit))
        (update :migration-report merge
                {:runtime-config-definitions-renamed definitions-renamed
                 :runtime-config-values-renamed values-renamed
                 :runtime-node-values-renamed node-values-renamed
                 :runtime-config-audit-records-renamed audit-renamed
                 :runtime-config-paths-renamed total-renamed}))))

(defn- runtime-config-path?
  [path]
  (and (string? path)
       (str/starts-with? path "skills.")))

(defn- infer-definition-root
  [path]
  (cond
    (runtime-config-path? path) "runtime"
    (and (string? path)
         (or (str/starts-with? path "services.")
             (str/starts-with? path "auth.")
             (str/starts-with? path "email.")
             (str/starts-with? path "features.")))
    "platform"
    (and (string? path)
         (or (str/starts-with? path "dataset.")
             (str/starts-with? path "pipeline.")))
    "dataset"))

(defn- classify-definition-roots
  [normalized-export]
  (let [definitions (get-in normalized-export [:data :definitions])
        rewritten (mapv (fn [definition]
                          (let [path (get definition "config-def/path")
                                inferred-root (infer-definition-root path)
                                existing-root (get definition "config-def/root")]
                            [(cond-> definition
                               (and (nil? existing-root) inferred-root)
                               (assoc "config-def/root" inferred-root))
                             (and (nil? existing-root) inferred-root)]))
                        definitions)
        classified-count (count (filter second rewritten))]
    (-> normalized-export
        (assoc-in [:data :definitions] (mapv first rewritten))
        (update :migration-report merge
                {:definitions-root-classified classified-count}))))

(defn- export-root-keyword
  [root]
  (cond
    (keyword? root) root
    (string? root) (keyword root)
    :else nil))

(defn- exported-node-parent-id
  [node]
  (or (get node "config.node/parent-id")
      (get-in node ["config.node/parent" "config.node/id"])
      (get-in node [:config.node/parent :config.node/id])))

(defn- exported-tenant-config-key-candidate
  [node]
  (when-let [node-id (get node "config.node/id")]
    (last (str/split node-id #"/"))))

(defn- exported-tenant-config-key-candidates
  [node canonical-default-node-id]
  (let [node-id (get node "config.node/id")]
    (if (= node-id canonical-default-node-id)
      ["default"]
      (let [segments (->> (str/split (or node-id "") #"/")
                          (drop 2)
                          (remove str/blank?)
                          vec)]
        (->> (range 1 (inc (count segments)))
             (map (fn [segment-count]
                    (str/join "-" (take-last segment-count segments))))
             (remove str/blank?)
             distinct
             vec)))))

(defn- choose-unique-tenant-config-key
  [node canonical-default-node-id used-tenant-config-keys]
  (let [base-candidates (->> (concat
                              [(non-blank-string (get node "config.node/tenant-config-key"))]
                              (exported-tenant-config-key-candidates node canonical-default-node-id))
                             (remove nil?)
                             distinct
                             vec)
        unique-candidate (some #(when-not (contains? used-tenant-config-keys %) %) base-candidates)]
    (or unique-candidate
        (let [fallback-base (or (last base-candidates)
                                (non-blank-string (exported-tenant-config-key-candidate node))
                                "node")]
          (loop [suffix 2]
            (let [candidate (str fallback-base "-" suffix)]
              (if (contains? used-tenant-config-keys candidate)
                (recur (inc suffix))
                candidate)))))))

(defn- export-allowed-config-key-id
  [api-key-id root tenant tenant-config-key]
  (str "api-key/" api-key-id "/allowed-config-key/" (name root) "/" tenant "/" tenant-config-key))

(defn- api-key-tenant-set
  [api-key]
  (->> (concat
        (map :tenant (or (:api-key/dataset-scopes api-key) []))
        (map :api-key.allowed-config-key/tenant (or (:api-key/allowed-config-keys api-key) [])))
       (keep non-blank-string)
       distinct
       sort
       vec))

(defn- normalize-tenant-config-keys-for-explicit-resolution
  [normalized-export]
  (let [nodes (vec (get-in normalized-export [:data :nodes]))
        grouped (group-by (fn [node]
                            [(get node "config.node/tenant")
                             (export-root-keyword (get node "config.node/root"))])
                          nodes)
        {:keys [nodes summaries derived-tenant-config-keys canonical-defaults]}
        (reduce-kv
         (fn [acc [tenant root] tenant-root-nodes]
           (let [root-nodes (->> tenant-root-nodes
                                 (filter #(nil? (exported-node-parent-id %)))
                                 vec)
                 default-root-nodes (->> root-nodes
                                         (filter #(= "default" (get % "config.node/tenant-config-key")))
                                         vec)
                 _ (when (> (count default-root-nodes) 1)
                     (throw (ex-info "Multiple canonical default nodes found in export"
                                     {:tenant tenant
                                      :root root
                                      :node-ids (mapv #(get % "config.node/id") default-root-nodes)})))
                 [tenant-root-nodes canonical-default-node-id canonical-defaults-added]
                 (cond
                   (= 1 (count default-root-nodes))
                   [tenant-root-nodes (get (first default-root-nodes) "config.node/id") 0]

                   (= 1 (count root-nodes))
                   (let [root-node (first root-nodes)
                         node-id (get root-node "config.node/id")]
                     [(mapv (fn [node]
                              (if (= node-id (get node "config.node/id"))
                                (assoc node "config.node/tenant-config-key" "default")
                                node))
                            tenant-root-nodes)
                      node-id
                      (if (= "default" (get root-node "config.node/tenant-config-key")) 0 1)])

                   :else
                   (throw (ex-info "Cannot infer canonical default node for exported tenant tree"
                                   {:tenant tenant
                                    :root root
                                    :root-node-ids (mapv #(get % "config.node/id") root-nodes)})))
                 {:keys [nodes derived]}
                 (reduce (fn [{:keys [nodes used-tenant-config-keys derived]} node]
                           (let [original-tenant-config-key (non-blank-string (get node "config.node/tenant-config-key"))
                                 chosen-tenant-config-key (choose-unique-tenant-config-key node canonical-default-node-id used-tenant-config-keys)]
                             {:nodes (conj nodes (assoc node "config.node/tenant-config-key" chosen-tenant-config-key))
                              :used-tenant-config-keys (conj used-tenant-config-keys chosen-tenant-config-key)
                              :derived (if (= original-tenant-config-key chosen-tenant-config-key)
                                         derived
                                         (inc derived))}))
                         {:nodes []
                          :used-tenant-config-keys #{}
                          :derived canonical-defaults-added}
                         tenant-root-nodes)
                 duplicate-tenant-config-keys (->> nodes
                                                   (group-by #(get % "config.node/tenant-config-key"))
                                                   (keep (fn [[tenant-config-key nodes-with-tenant-config-key]]
                                                           (when (> (count nodes-with-tenant-config-key) 1)
                                                             {:tenant-config-key tenant-config-key
                                                              :node-ids (mapv #(get % "config.node/id") nodes-with-tenant-config-key)})))
                                                   vec)]
             (when (seq duplicate-tenant-config-keys)
               (throw (ex-info "Duplicate node tenant-config-keys remain after export fixup"
                               {:tenant tenant
                                :root root
                                :duplicates duplicate-tenant-config-keys})))
             (-> acc
                 (update :nodes into nodes)
                 (assoc-in [:summaries [tenant root]]
                           {:tenant tenant
                            :root root
                            :canonical-default-node-id canonical-default-node-id
                            :canonical-default-tenant-config-key "default"})
                 (update :derived-tenant-config-keys + derived)
                 (update :canonical-defaults + canonical-defaults-added))))
         {:nodes []
          :summaries {}
          :derived-tenant-config-keys 0
          :canonical-defaults 0}
         grouped)]
    (-> normalized-export
        (assoc-in [:data :nodes] (vec nodes))
        (assoc :explicit-node-export/node-summaries summaries)
        (update :migration-report merge
                {:explicit-tenant-config-keys-derived derived-tenant-config-keys
                 :explicit-node-canonical-defaults-fixed canonical-defaults}))))

(defn- drop-legacy-binding-metadata
  [normalized-export]
  (let [bindings (get-in normalized-export [:data :bindings])
        compatibilities (get-in normalized-export [:data :compatibilities])]
    (-> normalized-export
        (assoc-in [:data :bindings] [])
        (assoc-in [:data :compatibilities] [])
        (update :migration-report merge
                {:legacy-direct-model-bindings-dropped (count bindings)
                 :legacy-root-compatibilities-dropped (count compatibilities)}))))

(defn- build-export-node-lookups
  [nodes]
  {:by-id (into {}
                (map (fn [node]
                       [(get node "config.node/id")
                        {:node-id (get node "config.node/id")
                         :tenant (get node "config.node/tenant")
                         :root (export-root-keyword (get node "config.node/root"))
                         :tenant-config-key (get node "config.node/tenant-config-key")
                         :enabled? (if (contains? node "config.node/enabled?")
                                     (get node "config.node/enabled?")
                                     true)}]))
                nodes)
   :by-tenant-config-key (into {}
                            (map (fn [node]
                                   [[(get node "config.node/tenant")
                                     (export-root-keyword (get node "config.node/root"))
                                     (get node "config.node/tenant-config-key")]
                                    {:node-id (get node "config.node/id")
                                     :tenant (get node "config.node/tenant")
                                     :root (export-root-keyword (get node "config.node/root"))
                                     :tenant-config-key (get node "config.node/tenant-config-key")
                                     :enabled? (if (contains? node "config.node/enabled?")
                                                 (get node "config.node/enabled?")
                                                 true)}]))
                            nodes)})

(defn- resolve-exported-allowed-config-key-node!
  [nodes-by-id nodes-by-tenant-config-key {:keys [api-key-id root tenant node-id tenant-config-key source]}]
  (let [resolved-node (cond
                        node-id (get nodes-by-id node-id)
                        tenant-config-key (get nodes-by-tenant-config-key [tenant root tenant-config-key])
                        :else nil)]
    (when-not resolved-node
      (throw (ex-info "Cannot resolve allowed config key target in exported payload"
                      {:api-key-id api-key-id
                       :root root
                       :tenant tenant
                       :node-id node-id
                       :tenant-config-key tenant-config-key
                       :source source})))
    (when-not (= tenant (:tenant resolved-node))
      (throw (ex-info "Allowed config key tenant mismatch in exported payload"
                      {:api-key-id api-key-id
                       :root root
                       :tenant tenant
                       :node-id (:node-id resolved-node)
                       :node-tenant (:tenant resolved-node)
                       :source source})))
    (when-not (= root (:root resolved-node))
      (throw (ex-info "Allowed config key root mismatch in exported payload"
                      {:api-key-id api-key-id
                       :root root
                       :tenant tenant
                       :node-id (:node-id resolved-node)
                       :node-root (:root resolved-node)
                       :source source})))
    (when-not (:enabled? resolved-node)
      (throw (ex-info "Allowed config key points at a disabled node in exported payload"
                      {:api-key-id api-key-id
                       :root root
                       :tenant tenant
                       :node-id (:node-id resolved-node)
                       :source source})))
    resolved-node))

(defn- normalize-exported-allowed-config-key!
  [api-key-id allowed-config-key nodes-by-id nodes-by-tenant-config-key]
  (let [root (:api-key.allowed-config-key/root allowed-config-key)
        tenant (:api-key.allowed-config-key/tenant allowed-config-key)
        resolved-node (resolve-exported-allowed-config-key-node! nodes-by-id
                                                                 nodes-by-tenant-config-key
                                                                 {:api-key-id api-key-id
                                                                  :root root
                                                                  :tenant tenant
                                                                  :node-id (:api-key.allowed-config-key/node-id allowed-config-key)
                                                                  :tenant-config-key (:api-key.allowed-config-key/tenant-config-key allowed-config-key)
                                                                  :source :allowed-config-key})]
    (cond-> {:api-key.allowed-config-key/id (or (:api-key.allowed-config-key/id allowed-config-key)
                                                (export-allowed-config-key-id api-key-id root tenant (:tenant-config-key resolved-node)))
             :api-key.allowed-config-key/root root
             :api-key.allowed-config-key/tenant tenant
             :api-key.allowed-config-key/node-id (:node-id resolved-node)
             :api-key.allowed-config-key/tenant-config-key (:tenant-config-key resolved-node)}
      (:api-key.allowed-config-key/created-at allowed-config-key)
      (assoc :api-key.allowed-config-key/created-at (:api-key.allowed-config-key/created-at allowed-config-key)))))

(defn- synthesize-default-allowed-config-keys
  [api-key-id api-key existing-allowed-config-keys nodes-by-id node-summaries]
  (let [covered-root-tenants (set (map (juxt :api-key.allowed-config-key/root
                                             :api-key.allowed-config-key/tenant)
                                       existing-allowed-config-keys))]
    (->> (for [tenant (api-key-tenant-set api-key)
               ;; ORDERED, not the set: export output order is observable.
               root structure/config-roots-ordered
               :when (not (contains? covered-root-tenants [root tenant]))
               :let [canonical-default-node-id (get-in node-summaries [[tenant root] :canonical-default-node-id])]
               :when canonical-default-node-id
               :let [node (get nodes-by-id canonical-default-node-id)]]
           {:api-key.allowed-config-key/id (export-allowed-config-key-id api-key-id root tenant (:tenant-config-key node))
            :api-key.allowed-config-key/root root
            :api-key.allowed-config-key/tenant tenant
            :api-key.allowed-config-key/node-id (:node-id node)
            :api-key.allowed-config-key/tenant-config-key (:tenant-config-key node)})
         vec)))

(defn- normalize-api-key-allowed-config-keys
  [normalized-export]
  (let [node-summaries (:explicit-node-export/node-summaries normalized-export)
        {:keys [by-id by-tenant-config-key]} (build-export-node-lookups (get-in normalized-export [:data :nodes]))
        api-keys (get-in normalized-export [:data :api-keys])
        {:keys [api-keys normalized-count synthesized-count]}
        (reduce (fn [acc api-key]
                  (let [api-key-id (:api-key/id api-key)
                        normalized-existing (mapv #(normalize-exported-allowed-config-key! api-key-id % by-id by-tenant-config-key)
                                                  (or (:api-key/allowed-config-keys api-key) []))
                        synthesized-defaults (synthesize-default-allowed-config-keys api-key-id
                                                                                     api-key
                                                                                     normalized-existing
                                                                                     by-id
                                                                                     node-summaries)
                        final-allowed-config-keys (->> (concat normalized-existing synthesized-defaults)
                                                       (reduce (fn [[seen acc] allowed-config-key]
                                                                 (let [allowed-config-key-key [(:api-key.allowed-config-key/root allowed-config-key)
                                                                                               (:api-key.allowed-config-key/tenant allowed-config-key)
                                                                                               (:api-key.allowed-config-key/node-id allowed-config-key)]]
                                                                   (if (contains? seen allowed-config-key-key)
                                                                     [seen acc]
                                                                     [(conj seen allowed-config-key-key)
                                                                      (conj acc allowed-config-key)])))
                                                               [#{} []])
                                                       second
                                                       (sort-by (juxt :api-key.allowed-config-key/tenant
                                                                      :api-key.allowed-config-key/root
                                                                      :api-key.allowed-config-key/tenant-config-key))
                                                       vec)]
                    (-> acc
                        (update :api-keys conj (assoc api-key :api-key/allowed-config-keys final-allowed-config-keys))
                        (update :normalized-count + (count normalized-existing))
                        (update :synthesized-count + (count synthesized-defaults)))))
                {:api-keys []
                 :normalized-count 0
                 :synthesized-count 0}
                api-keys)]
    (-> normalized-export
        (assoc-in [:data :api-keys] (vec api-keys))
        (update :migration-report merge
                {:explicit-allowed-config-keys-normalized normalized-count
                 :explicit-default-allowed-config-keys-synthesized synthesized-count
                 :explicit-allowed-config-keys-total (->> api-keys
                                                           (mapcat :api-key/allowed-config-keys)
                                                           count)}))))

(defn finalize-explicit-node-resolution-export
  [normalized-export]
  (let [fixed (-> normalized-export
                  normalize-tenant-config-keys-for-explicit-resolution
                  drop-legacy-binding-metadata
                  normalize-api-key-allowed-config-keys)]
    (dissoc fixed :explicit-node-export/node-summaries)))

(defn canonicalize-system-export
  [data]
  (-> data
      normalize-system-export
      rewrite-runtime-config-paths
      classify-definition-roots
      finalize-explicit-node-resolution-export))
