(ns digdir.tools.config
  "Config inspection helpers, shared by the `bb` tasks and the on-jar CLI.

   ## Why this is in `src` and not `src-dev` (#505)

   It used to live in `src-dev`, which the uberjar does not ship —
   `build.clj` copies `[\"src\" \"src-prod\" \"resources\"]` only. That was
   fine while the only caller was `bb config-get` on a developer machine.

   `digdir.setup.config-cli` needs the same read inside the runtime image,
   where there is no source tree. The alternative was a second
   implementation of \"resolve one config value\", which would own a copy of
   the pipeline-property special case and the `agent-id`/`pipeline-id`
   defaults below — the two would then drift about what a read means, which
   is the class of defect #497 and #500 both were. Moving the one
   implementation to where both callers can reach it is cheaper than
   keeping two honest."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.accessor :as accessor]))

(defn- normalize-path
  "Normalize dot-path strings for config lookup."
  [path]
  (cond
    (nil? path) ""
    (string? path) (str/trim path)
    (keyword? path) (name path)
    (symbol? path) (str path)
    :else (str path)))

(defn- cleanup!
  "Release resources so one-off bb tasks can terminate cleanly."
  [conn]
  (try
    (when conn
      (d/release conn))
    (catch Throwable _))
  (shutdown-agents))

(defn get-value
  "Print one resolved config value.

   Usage (via bb task):
   bb config-get <path> <tenant> <config-root> <config-key>
     [--dataset-id <dataset-id>] [--agent-id <agent-id>] [--pipeline-id <pipeline-id>]

   Dataset-scoped runtime lookups require `--dataset-id`. Without it, the
   runtime traversal ignores any dataset-level overrides.

   Example:
   bb config-get skills.rerank.context.top-k ka runtime default
   bb config-get skills.rerank.top-k digdir runtime default --dataset-id public-docs"
  [{:keys [path root tenant tenant-config-key agent-id pipeline-id dataset-id dataset-config-key]}]
  (let [path (normalize-path path)
        root (or (when root (keyword (name root))) :runtime)
        conn (config-db/get-conn)]
    (try
      (when-not (seq path)
        (throw (ex-info "Missing required :path" {:path path})))
      (when (str/blank? tenant)
        (throw (ex-info "Missing required :tenant" {})))
      (when (str/blank? tenant-config-key)
        (throw (ex-info "Missing required :tenant-config-key" {})))
      (when-not conn
        (throw (ex-info "Config DB connection is not available" {})))
      (let [db @conn
            pipeline-id (or pipeline-id "kudos")
            opts (cond-> {:tenant tenant
                          :tenant-config-key tenant-config-key
                          :agent-id (or agent-id "builtin/agent-rag-agent")
                          :pipeline-id pipeline-id}
                   dataset-id          (assoc :dataset-id dataset-id)
                   dataset-config-key  (assoc :dataset-config-key dataset-config-key))
            [effective-root res]
            (if (contains? config-db/pipeline-property-paths path)
              [:dataset
               (let [master-key (config-core/get-master-key)
                     pipeline-config (config-db/get-dataset db tenant tenant-config-key pipeline-id master-key)
                     _ (when-not pipeline-config
                         (throw (ex-info "Pipeline config not found"
                                         {:tenant tenant
                                          :tenant-config-key tenant-config-key
                                          :pipeline-id pipeline-id})))
                     node-id (:dataset-node-id pipeline-config)
                     pipeline-res (accessor/get-dataset-pipeline-config-v2-with-trace
                                   {:tenant tenant
                                    :node-id node-id
                                    :pipeline-id pipeline-id})
                     prop-name (config-db/path-to-pipeline-property path)]
                 {:value (get-in pipeline-res [:config prop-name])
                  :trace (get-in pipeline-res [:traces path])})]
              [root
               (case root
                 :platform (accessor/get-platform-value-with-trace path opts)
                 :runtime  (accessor/get-runtime-value-with-trace path opts)
                 :dataset  (accessor/get-dataset-value-with-trace path opts))])
              ;; Printed AND returned. `bb config-get` reads the printed form and
              ;; is unchanged by the return; `digdir.setup.config-cli` needs
              ;; `:resolved?` as a value so it can exit non-zero on a path that
              ;; does not resolve instead of printing nil and succeeding.
              summary (cond-> {:tenant tenant
                               :tenant-config-key tenant-config-key
                               :root effective-root
                               :path path
                               :resolved? (some? (:value res))
                               :value (:value res)
                               :winning-node (get-in res [:trace :winning-node])
                               :stop-reason (get-in res [:trace :stop-reason])
                               :traversal (:traversal-path (:trace res))}
                        dataset-id (assoc :dataset-id dataset-id))]
          (prn summary)
          summary)
      (finally
        (cleanup! conn)))))

(defn get-pipeline
  "Print resolved pipeline config map for a pipeline id.

   Usage (via bb task):
   bb pipeline-config <tenant> <config-key> <pipeline-id>

   Example:
   bb pipeline-config ka prod kudos"
  [{:keys [pipeline-id tenant tenant-config-key]}]
  (let [pipeline-id (name (or pipeline-id :kudos))
        conn (config-db/get-conn)]
    (try
      (when (str/blank? tenant)
        (throw (ex-info "Missing required :tenant" {})))
      (when (str/blank? tenant-config-key)
        (throw (ex-info "Missing required :tenant-config-key" {})))
      (when-not conn
        (throw (ex-info "Config DB connection is not available" {})))
      (let [db @conn
            master-key (config-core/get-master-key)
            pipeline-config (config-db/get-dataset db tenant tenant-config-key pipeline-id master-key)]
        (prn {:tenant tenant
              :tenant-config-key tenant-config-key
              :pipeline-id pipeline-id
              :pipeline-found? (boolean pipeline-config)
              :config pipeline-config}))
      (finally
        (cleanup! conn)))))
