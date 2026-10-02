(ns digdir.config.accessor
  "Primary config accessor API.

   Replaces (get-in config/config [:services :azure-openai :api-key]) with:
     (cfg/get {:tenant t} :services :azure-openai :api-key)

   Features:
   - Automatic decryption of encrypted values
   - Root-aware V2 resolution
   - Database-backed configuration (requires CONFIG_MASTER_KEY environment variable)"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]))

;; =============================================================================
;; Path Utilities
;; =============================================================================

(defn- normalize-path
  "Normalize a path to a vector of keywords.

   Accepts:
   - Vector: [:services :azure-openai :api-key]
   - Keywords: :services :azure-openai :api-key
   - String: \"services.azure-openai.api-key\""
  [path-or-keys]
  (cond
    (vector? path-or-keys)
    path-or-keys

    (string? path-or-keys)
    (mapv keyword (str/split path-or-keys #"\."))

    (keyword? path-or-keys)
    [path-or-keys]

    :else
    (vec path-or-keys)))

(defn- path->string
  "Convert a path to a dot-separated string."
  [path]
  (str/join "." (map name path)))

(defn- path-parts->path
  [path-parts]
  (if (and (= 1 (count path-parts))
           (or (vector? (first path-parts))
               (string? (first path-parts))))
    (normalize-path (first path-parts))
    (vec path-parts)))

(defn- path-str->path
  [path-str]
  (mapv keyword (str/split path-str #"\.")))

(defn- value-at-path-str
  [config path-str]
  (get-in config (path-str->path path-str) ::not-found))

(defn- config-conn!
  [context]
  (or (config-db/get-conn)
      (throw (ex-info "Database connection not available" context))))

(defn- normalize-platform-opts
  "Validate + fill platform opts.

   `tenant` MUST be provided by the caller. Before 2026-04, a nil tenant was
   silently rewritten to the setup-time seed tenant `__platform-defaults__`.
   That mechanism is retired (see plans/completed/global-config-root-plan.md
   Phase 4). Callers that genuinely want system-wide values should explicitly
   pass `core/global-tenant` (\"__global__\"); anything else should resolve
   through a real tenant."
  [{:keys [tenant] :as opts}]
  (when (or (nil? tenant) (and (string? tenant) (str/blank? tenant)))
    (throw (ex-info (str "cfg/get requires an explicit :tenant. Pass a tenant id, "
                         "or `digdir.config.core/global-tenant` for system-wide reads. "
                         "A nil tenant is no longer normalized to __platform-defaults__.")
                    {:opts opts})))
  opts)

(defn- ensure-db-config!
  [context]
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable."
                    context))))

(defn- ensure-explicit-node-selection!
  [{:keys [node-id tenant-config-key] :as context}]
  (when-not (or (some? node-id) (some? tenant-config-key))
    (throw (ex-info "Explicit node selection requires :node-id or :tenant-config-key"
                    context))))

(defn- decode-node-value
  [value master-key]
  (when value
    (let [definition (:config.value/definition value)]
      (config-db/decode-value (:config.value/raw value)
                              (:config-def/value-type definition)
                              (:config-def/encrypted? definition)
                              master-key))))

(defn- rooted-definition
  [db root path-str]
  (when-let [definition (config-db/get-definition db path-str)]
    (when-not (= root (:config-def/root definition))
      (throw (ex-info "V2 accessor requires a rooted definition"
                      {:path path-str
                       :root root
                       :definition-root (:config-def/root definition)})))
    definition))

(defn- synthetic-root-trace
  [{:keys [root tenant node-id path-str stop-reason]}]
  {:selected-root root
   :selected-tenant tenant
   :selected-node node-id
   :traversal-path (cond-> [] node-id (conj node-id))
   :winning-node nil
   :path path-str
   :stop-reason stop-reason})

(defn- resolve-root-value-with-trace
  [db root tenant selected-node path default]
  (let [path-str (path->string (normalize-path path))
        selected-node-id (:config.node/id selected-node)
        definition (rooted-definition db root path-str)
        master-key (core/get-master-key)]
    (if-not definition
      {:value default
       :trace (synthetic-root-trace {:root root
                                     :tenant tenant
                                     :node-id selected-node-id
                                     :path-str path-str
                                     :stop-reason :definition-not-found})
       :node selected-node}
      (let [{:keys [value trace]} (config-db/resolve-node-value-with-trace db
                                                                           root
                                                                           tenant
                                                                           selected-node-id
                                                                           path-str)
            decoded-value (if value
                            (decode-node-value value master-key)
                            default)]
        {:value decoded-value
         :trace (assoc trace :decoded-value decoded-value)
         :node selected-node}))))

(defn- tenant-root-missing?
  [e]
  (= :tenant-root-missing (:kind (ex-data e))))

(defn- resolve-missing-tenant-with-global-fallback
  "When a tenant has no tree, fall back to global-only resolution for
   inherit-owned paths. For fork-owned paths (or missing definitions), the
   caller's original tenant-root exception should stand — signaled by
   returning ::no-fallback so the caller re-throws."
  [db root tenant path-str default]
  (let [definition (config-db/get-definition db path-str)
        master-key (core/get-master-key)]
    (if (= :inherit (:config-def/ownership definition))
      (let [{:keys [value trace]} (config-db/resolve-global-value-with-trace db root path-str)
            decoded-value (if value (decode-node-value value master-key) default)]
        {:value decoded-value
         :trace (assoc trace
                       :decoded-value decoded-value
                       :selected-tenant tenant
                       :tenant-tree-missing? true)
         :node nil})
      ::no-fallback)))

(defn- definitions-by-path
  [db]
  (into {} (map (juxt :config-def/path identity))
        (config-db/get-all-definitions db)))

(defn- definitions-for-root
  [all-defs-by-path root]
  (into {} (filter (fn [[_ definition]]
                     (= root (:config-def/root definition))))
        all-defs-by-path))

(defn- validate-rooted-paths!
  [all-defs-by-path root path-strs]
  (doseq [path-str path-strs]
    (when-let [definition (clojure.core/get all-defs-by-path path-str)]
      (when-not (= root (:config-def/root definition))
        (throw (ex-info "V2 accessor requires a rooted definition"
                        {:path path-str
                         :root root
                         :definition-root (:config-def/root definition)}))))))

(defn- load-root-config-with-trace
  [{:keys [db root tenant selected-node paths]}]
  (let [all-defs-by-path (definitions-by-path db)
        root-defs-by-path (definitions-for-root all-defs-by-path root)
        path-strs (if (seq paths)
                    (mapv #(path->string (normalize-path %)) paths)
                    (vec (keys root-defs-by-path)))
        _ (validate-rooted-paths! all-defs-by-path root path-strs)
        {defined-paths true undefined-paths false}
        (group-by #(contains? root-defs-by-path %) path-strs)
        selected-node-id (:config.node/id selected-node)
        master-key (core/get-master-key)
        {:keys [results]} (when (seq defined-paths)
                            (config-db/resolve-node-values-batch db root tenant
                                                                  selected-node-id
                                                                  defined-paths))]
    (as-> {:config {} :traces {} :node selected-node} acc
      (reduce
       (fn [acc path-str]
         (let [{:keys [value trace]} (clojure.core/get results path-str)
               decoded-value (when value
                               (decode-node-value value master-key))]
           (cond-> (update acc :traces assoc path-str (assoc trace :decoded-value decoded-value))
             value (update :config assoc-in (path-str->path path-str) decoded-value))))
       acc
       (or defined-paths []))
      (reduce
       (fn [acc path-str]
         (update acc :traces assoc path-str
                 (synthetic-root-trace {:root root
                                        :tenant tenant
                                        :node-id selected-node-id
                                        :path-str path-str
                                        :stop-reason :definition-not-found})))
       acc
       (or undefined-paths [])))))

(defn- project-config
  [config property->path]
  (reduce-kv (fn [acc property path-str]
               (let [value (value-at-path-str config path-str)]
                 (if (= ::not-found value)
                   acc
                   (assoc acc property value))))
             {}
             property->path))

(defn- project-config-by-path
  [config path->property]
  (reduce-kv (fn [acc path-str property]
               (let [value (value-at-path-str config path-str)]
                 (if (= ::not-found value)
                   acc
                   (assoc acc property value))))
             {}
             path->property))

(defn- dataset-id-for-pipeline
  [db dataset-id pipeline-id]
  (or dataset-id
      (get-in (config-db/get-dataset-pipeline db pipeline-id)
              [:dataset.pipeline/dataset :dataset/id])
      pipeline-id))

(defn- resolve-dataset-materialization-context
  [{:keys [tenant node-id tenant-config-key dataset-id pipeline-id path] :as opts}]
  (let [context (cond-> {:pipeline-id pipeline-id}
                  path (assoc :path path))]
    (ensure-db-config! context)
    (when (str/blank? pipeline-id)
      (throw (ex-info "Missing required :pipeline-id" context)))
    (ensure-explicit-node-selection! (assoc opts :pipeline-id pipeline-id)))
  (let [conn (config-conn! {:tenant tenant})
        db @conn
        dataset-id (dataset-id-for-pipeline db dataset-id pipeline-id)
        selected-node (config-db/resolve-dataset-node! db
                                                       {:tenant tenant
                                                        :node-id node-id
                                                        :tenant-config-key tenant-config-key
                                                        :dataset-id dataset-id
                                                        :pipeline-id pipeline-id})]
    {:db db
     :tenant tenant
     :dataset-id dataset-id
     :selected-node selected-node}))

;; =============================================================================
;; Primary Accessor
;; =============================================================================

(declare get-platform-value-with-trace)

(defn get
  "Get a platform config value from database, scoped by tenant.

   Requires database mode (CONFIG_MASTER_KEY must be set).
   Platform-rooted definitions resolve through the Platform V2 tree.

   Usage:
     (get {:tenant t} :services :azure-openai :api-key)
     (get {:tenant t} [:services :azure-openai :api-key])
     (get {:tenant t :default \"fallback\"} :services :foo :bar)

   Opts keys (all optional — caller supplies from its natural source):
     :tenant — tenant identifier; nil selects setup-time platform defaults
     :tenant-config-key — node selector within the tenant tree
     :default — value returned if the path has no value"
  [opts & path-or-keys]
  (when-not (map? opts)
    (throw (ex-info "cfg/get opts must be a map; pass {:tenant t} as the first argument"
                    {:opts opts :path-or-keys path-or-keys})))
  (let [{:keys [tenant tenant-config-key default]} (normalize-platform-opts opts)
        path (path-parts->path path-or-keys)]
    (when-not (core/use-db-config?)
      (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable."
                      {:path path})))
    (let [conn (config-conn! {:path path})
          path-str (path->string path)
          definition (config-db/get-definition @conn path-str)
          root (:config-def/root definition)]
      (cond
        (nil? definition)
        (throw (ex-info (str "No config definition registered for path " (pr-str path-str))
                        {:path path-str}))

        (not= :platform root)
        (throw (ex-info (str "Primary accessor only supports platform-rooted definitions, but "
                             (pr-str path-str) " is rooted at " (pr-str root))
                        {:path path-str
                         :definition-root root}))

        :else
        (:value (get-platform-value-with-trace path
                                               {:tenant tenant
                                                :tenant-config-key tenant-config-key
                                                :default default}))))))

;; =============================================================================
;; Runtime V2 Accessor
;; =============================================================================

(defn get-platform-value-with-trace
  "Resolve a platform config value through the V2 node model.

   Returns:
   {:value decoded-value-or-default
    :trace resolution-trace
    :node selected-platform-node}"
  [path opts]
  (ensure-db-config! {:path path})
  (let [{:keys [tenant node-id tenant-config-key default]} (normalize-platform-opts opts)
        path-str (path->string (normalize-path path))
        conn (config-conn! {:path path-str})
        db @conn]
    (try
      (let [selected-node (config-db/resolve-platform-node! db
                                                            {:tenant tenant
                                                             :node-id node-id
                                                             :tenant-config-key tenant-config-key})]
        (resolve-root-value-with-trace db :platform tenant selected-node path-str default))
      (catch clojure.lang.ExceptionInfo e
        (if (tenant-root-missing? e)
          (let [result (resolve-missing-tenant-with-global-fallback db :platform tenant path-str default)]
            (if (= ::no-fallback result) (throw e) result))
          (throw e))))))

(defn get-platform-value
  "Resolve a platform config value through the V2 node model and return only the decoded value."
  [path opts]
  (:value (get-platform-value-with-trace path opts)))

(defn get-runtime-value-with-trace
  "Resolve a runtime config value through the V2 node model.

  Returns:
  {:value decoded-value-or-default
   :trace resolution-trace
   :node selected-runtime-node}"
  [path {:keys [tenant node-id tenant-config-key agent-id dataset-id default] :as _opts}]
  (ensure-db-config! {:path path})
  (when (str/blank? agent-id)
    (throw (ex-info "Missing required :agent-id" {:path path})))
  (ensure-explicit-node-selection! {:path path
                                    :tenant tenant
                                    :node-id node-id
                                    :tenant-config-key tenant-config-key
                                    :agent-id agent-id})
  (let [path-str (path->string (normalize-path path))
        conn (config-conn! {:path path-str})
        db @conn]
    (try
      (let [selected-node (config-db/resolve-runtime-node! db
                                                           {:tenant tenant
                                                            :node-id node-id
                                                            :tenant-config-key tenant-config-key
                                                            :agent-id agent-id
                                                            :dataset-id dataset-id})]
        (resolve-root-value-with-trace db :runtime tenant selected-node path-str default))
      (catch clojure.lang.ExceptionInfo e
        (if (tenant-root-missing? e)
          (let [result (resolve-missing-tenant-with-global-fallback db :runtime tenant path-str default)]
            (if (= ::no-fallback result) (throw e) result))
          (throw e))))))

(defn get-runtime-value
  "Resolve a runtime config value through the V2 node model and return only the decoded value."
  [path opts]
  (:value (get-runtime-value-with-trace path opts)))

(defn load-runtime-config-v2-with-trace
  "Load runtime config values through the V2 node model.

   If :paths is omitted, all runtime-rooted definitions are considered.

   Uses batch resolution to prefetch the ancestor chain and all node values
   once, then resolves all paths against the cached data.

  Returns:
  {:config nested-map
   :traces {\"path\" trace-map}
   :node selected-runtime-node}"
  [{:keys [tenant node-id tenant-config-key agent-id dataset-id paths] :as _opts}]
  (ensure-db-config! {:agent-id agent-id})
  (when (str/blank? agent-id)
    (throw (ex-info "Missing required :agent-id" {})))
  (ensure-explicit-node-selection! {:tenant tenant
                                    :node-id node-id
                                    :tenant-config-key tenant-config-key
                                    :agent-id agent-id})
  (let [conn (config-conn! {:tenant tenant})
        db @conn
        selected-node (config-db/resolve-runtime-node! db
                                                       {:tenant tenant
                                                        :node-id node-id
                                                        :tenant-config-key tenant-config-key
                                                        :agent-id agent-id
                                                        :dataset-id dataset-id})]
    (load-root-config-with-trace {:db db
                                  :root :runtime
                                  :tenant tenant
                                  :selected-node selected-node
                                  :paths paths})))

(defn load-runtime-config-v2
  "Load runtime config values through the V2 node model and return only the nested config map."
  [opts]
  (:config (load-runtime-config-v2-with-trace opts)))

(defn get-runtime-skill-config-v2-with-trace
  "Load canonical runtime skill config through the V2 node model.

   Returns:
   {:config {:retrieval-top-k 40 ...}
    :traces {\"skills.retrieval.top-k\" {...}}
    :node selected-runtime-node}"
  [opts]
  (let [skill-paths (->> config-db/skill-property-to-path vals sort vec)
        {:keys [config traces node]} (load-runtime-config-v2-with-trace
                                      (assoc opts :paths skill-paths))
        flat-config (project-config config config-db/skill-property-to-path)]
    {:config flat-config
     :traces traces
     :node node}))

(defn get-runtime-skill-config-v2
  "Load canonical runtime skill config through the V2 node model and return only the flat skill property map."
  [opts]
  (:config (get-runtime-skill-config-v2-with-trace opts)))

;; =============================================================================
;; Dataset V2 Accessor
;; =============================================================================

(defn resolve-dataset-runtime-node!
  "Resolve a canonical dataset runtime node through the V2 tree model.

   Required opts:
   - one of :dataset-config-key, :tenant-config-key, :dataset-id, or :node-id

   Optional opts:
   - :tenant"
  [{:keys [tenant node-id tenant-config-key dataset-config-key dataset-id] :as _opts}]
  (ensure-db-config! {:tenant tenant
                      :dataset-config-key dataset-config-key
                      :dataset-id dataset-id
                      :node-id node-id})
  (when-not (or (some? node-id)
                (some? tenant-config-key)
                (some? dataset-config-key)
                (some? dataset-id))
    (throw (ex-info "Dataset runtime resolution requires :dataset-config-key, :dataset-id, or :node-id"
                    {:tenant tenant
                     :node-id node-id
                     :dataset-config-key dataset-config-key
                     :dataset-id dataset-id})))
  (let [conn (config-conn! {:tenant tenant})
        db @conn]
    (:selected-node
     (config-db/resolve-dataset-runtime-node! db
                                              {:tenant tenant
                                               :node-id node-id
                                               :tenant-config-key tenant-config-key
                                               :dataset-config-key dataset-config-key
                                               :dataset-id dataset-id}))))

(defn get-dataset-value-with-trace
  "Resolve a dataset/materialization config value through the V2 node model."
  [path {:keys [default] :as opts}]
  (let [{:keys [db tenant selected-node]} (resolve-dataset-materialization-context
                                           (assoc opts :path path))]
    (resolve-root-value-with-trace db :dataset tenant selected-node path default)))

(defn get-dataset-value
  "Resolve a dataset/materialization config value and return only the decoded value."
  [path opts]
  (:value (get-dataset-value-with-trace path opts)))

(defn load-dataset-config-v2-with-trace
  "Load canonical dataset config values through the V2 node model.

   If :paths is omitted, all dataset-rooted definitions are considered.

  Returns:
  {:config nested-map
   :traces {\"path\" trace-map}
   :node selected-dataset-node}"
  [{:keys [tenant node-id tenant-config-key dataset-config-key dataset-id paths] :as _opts}]
  (ensure-db-config! {:tenant tenant
                      :dataset-config-key dataset-config-key
                      :dataset-id dataset-id
                      :node-id node-id})
  (let [conn (config-conn! {:tenant tenant})
        db @conn
        selected-node (resolve-dataset-runtime-node!
                       {:tenant tenant
                        :node-id node-id
                        :tenant-config-key tenant-config-key
                        :dataset-config-key dataset-config-key
                        :dataset-id dataset-id})]
    (load-root-config-with-trace {:db db
                                  :root :dataset
                                  :tenant tenant
                                  :selected-node selected-node
                                  :paths paths})))

(defn load-dataset-config-v2
  "Load dataset config values through the V2 node model and return only the nested config map."
  [opts]
  (:config (load-dataset-config-v2-with-trace opts)))

(defn get-dataset-pipeline-config-v2-with-trace
  "Load canonical dataset/materialization pipeline config through the V2 node model."
  [{:keys [pipeline-id] :as opts}]
  (let [{:keys [db tenant dataset-id selected-node]} (resolve-dataset-materialization-context opts)
        {:keys [config traces node]} (load-root-config-with-trace
                                      {:db db
                                       :root :dataset
                                       :tenant tenant
                                       :selected-node selected-node
                                       :paths (sort config-db/pipeline-property-paths)})
        flat-config (project-config-by-path config config-db/path-to-pipeline-property)]
    {:config (merge {:id pipeline-id
                     :dataset-id dataset-id
                     :dataset-node-id (:config.node/id selected-node)
                     :dataset-tenant-config-key (or (:tenant-config-key (config-db/parse-dataset-node-id
                                                                          (:config.node/id selected-node)))
                                                   (:config.node/tenant-config-key selected-node))}
                    flat-config)
     :traces traces
     :node node}))

(defn get-dataset-pipeline-config-v2
  "Load canonical dataset/materialization pipeline config through the V2 node model."
  [opts]
  (:config (get-dataset-pipeline-config-v2-with-trace opts)))

;; =============================================================================
;; Permission-Checked Access
;; =============================================================================

(defn- check-permission!
  "Check if a user has permission for an action on a path.
   Throws ex-info if permission is denied."
  [conn user-id path-str action opts]
  (when conn
    (when-let [check-fn (try
                          (require 'digdir.config.permissions)
                          (resolve 'digdir.config.permissions/can-access?)
                          (catch Exception _ nil))]
      (when-not (check-fn @conn user-id path-str action opts)
        (throw (ex-info "Permission denied"
                        {:path path-str :user-id user-id :action action}))))))

(defn get-if-allowed
  "Get a config value only if the user has permission.

   Returns the value if allowed, or throws an exception if denied.

   Args:
     user-id - User ID to check permissions for
     opts - Config opts map accepted by cfg/get
     path - Config path"
  [user-id opts & path-parts]
  (when-not (map? opts)
    (throw (ex-info "cfg/get-if-allowed opts must be a map; pass {:tenant t} after user-id"
                    {:opts opts :path-parts path-parts})))
  (let [path (path-parts->path path-parts)
        path-str (path->string path)
        conn (config-db/get-conn)]

    (check-permission! conn user-id path-str :read opts)
    (apply get opts path)))

(defn evaluate-access
  "Evaluate a user's access to a config path without fetching the value.

   Returns: {:allowed? bool :matched-permission id :reason string}

   Args:
     user-id - User ID to check
     path - Config path
     action - :read or :write"
  [user-id path action]
  (let [path-str (if (string? path) path (path->string (normalize-path path)))
        conn (config-db/get-conn)]
    (if conn
      (if-let [eval-fn (try
                         (require 'digdir.config.permissions)
                         (resolve 'digdir.config.permissions/evaluate-access)
                         (catch Exception _ nil))]
        (eval-fn @conn user-id path-str action)
        {:allowed? true :reason "Permissions module not loaded"})
      {:allowed? false :reason "Database not connected"})))

(comment
  (require '[digdir.config.accessor :as cfg])

  ;; Permission-checked access
  (cfg/get-if-allowed "user-123" {:tenant "ka"} :services :azure-openai :api-key)
  (cfg/evaluate-access "user-123" [:services :azure-openai :api-key] :read))
