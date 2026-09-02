(ns digdir.api.routes.datasets
  "Dataset, materialization, and config-resolution routes for the headless API."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [digdir.api.context :as api-ctx]
   [digdir.config.structure :as structure]
   [digdir.api.util :refer [api-error-body
                            param-value
                            normalize-request-paths
                            normalize-pipeline-properties
                            request-body-params
                            request-path-params
                            request-query-params]]
   [digdir.config.api-keys :as api-keys]
   [digdir.config.accessor :as cfg]
   [digdir.config.core :as config-core]
   [digdir.config.db :as config-db]
   [digdir.data.db :as db]
   [digdir.pipeline.core :as pipeline]
   [digdir.pipeline.executor :as executor]
   [ring.util.response :as res]))

(def ^:private config-root-prefixes
  [{:prefix "skills." :root :runtime}
   {:prefix "pipeline." :root :dataset}
   {:prefix "client." :root :dataset}
   {:prefix "services." :root :platform}
   {:prefix "features." :root :platform}])

(defn- infer-config-root
  [db path]
  (or (some-> (config-db/get-definition db path) :config-def/root)
      (some (fn [{:keys [prefix root]}]
              (when (str/starts-with? path prefix)
                root))
            config-root-prefixes)))

(defn- tenant-root-node-id!
  [conn tenant root]
  (or (some-> (config-db/get-config-node-by-tenant-config-key @conn tenant root "default")
              :config.node/id)
      (throw (ex-info "Canonical tenant root node not found"
                      {:status 404
                       :tenant tenant
                       :root root
                       :required-config-key "default"}))))

(defn authorize-config-request!
  [ring-req conn path tenant _action]
  (when-let [allowed-config-keys (seq (:api-key/allowed-config-keys ring-req))]
    (let [db @conn
          root (or (infer-config-root db path)
                   (throw (ex-info "Cannot determine config root for path"
                                   {:status 400
                                    :path path})))
          node-id (tenant-root-node-id! conn tenant root)]
      (api-keys/require-allowed-config-key! conn
                                            allowed-config-keys
                                            {:root root
                                             :tenant tenant
                                             :node-id node-id}))))

(defn authorize-dataset-materialization-request!
  [ring-req conn {:keys [tenant]}]
  (when-let [allowed-config-keys (seq (:api-key/allowed-config-keys ring-req))]
    (api-keys/require-allowed-config-key!
     conn
     allowed-config-keys
     {:root :dataset
      :tenant tenant
      :node-id (tenant-root-node-id! conn tenant :dataset)})))

(defn- pipeline-summary
  [effective-record]
  (let [id (:dataset.pipeline/id effective-record)
        effective-name (:dataset.pipeline/effective-name effective-record)]
    (cond-> {:id id
             :datasetId (get-in effective-record [:dataset.pipeline/dataset :dataset/id])
             :name (or effective-name id)
             :sourceType (:dataset.pipeline/effective-source-type effective-record)
             :enabled? (:dataset.pipeline/enabled? effective-record)}
      (:dataset.pipeline/effective-name-ambiguous? effective-record)
      (assoc :nameAmbiguous true)

      (:dataset.pipeline/effective-source-type-ambiguous? effective-record)
      (assoc :sourceTypeAmbiguous true))))

(defn summarize-dataset-record
  ([db dataset-record pipelines]
   (summarize-dataset-record db dataset-record pipelines nil))
  ([db dataset-record pipelines {:keys [contexts-by-pipeline-id master-key]}]
   {:id (:dataset/id dataset-record)
    :name (:dataset/name dataset-record)
    :description (:dataset/description dataset-record)
    :enabled? (:dataset/enabled? dataset-record)
    :pipelineCount (count pipelines)
    :pipelines (mapv (fn [pipeline-record]
                       (let [pipeline-id (:dataset.pipeline/id pipeline-record)
                             contexts (when contexts-by-pipeline-id
                                        (get contexts-by-pipeline-id pipeline-id))
                             effective-record (config-db/effective-dataset-pipeline-record
                                               db
                                               pipeline-record
                                               (cond-> {:master-key master-key}
                                                 contexts (assoc :contexts contexts)))]
                         (pipeline-summary effective-record)))
                     pipelines)}))

(defn summarize-dataset-materialization-record
  ([db pipeline-record]
   (summarize-dataset-materialization-record db pipeline-record nil))
  ([db pipeline-record opts]
   (let [effective-record (config-db/effective-dataset-pipeline-record db pipeline-record opts)]
     (pipeline-summary effective-record))))

(defn- summarize-latest-execution
  [execution]
  (when execution
    {:id (:pipeline-execution/id execution)
     :execution-pipeline-id (:pipeline-execution/pipeline-id execution)
     :status (some-> (:pipeline-execution/status execution) name)
     :startedAt (some-> (:pipeline-execution/started-at execution) str)
     :completedAt (some-> (:pipeline-execution/completed-at execution) str)
     :documentsProcessed (:pipeline-execution/documents-processed execution)
     :documentsFailed (:pipeline-execution/documents-failed execution)
     :errorMessage (:pipeline-execution/error-message execution)
     :startedBy (:pipeline-execution/started-by execution)}))

(defn- public-materialization-contexts
  [db dataset-refs pipeline-id]
  (->> (or dataset-refs [])
       (mapcat (fn [dataset-ref]
                 (let [{:keys [tenant dataset-config-key]}
                       (or (api-ctx/normalize-dataset-ref dataset-ref) dataset-ref)]
                   (try
                     (->> (:pipeline-records
                           (config-db/resolve-dataset-ref-materializations db dataset-ref))
                          (filter #(= pipeline-id (:dataset.pipeline/id %)))
                          (map (fn [_]
                                 (let [external-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
                                       latest-execution (first (executor/list-executions db external-pipeline-id))]
                                   {:tenant tenant
                                    :dataset-config-key dataset-config-key
                                    :execution-pipeline-id external-pipeline-id
                                    :latestExecution (summarize-latest-execution latest-execution)}))))
                     (catch clojure.lang.ExceptionInfo _
                       [])))))
       vec))

(defn- public-materialization-status
  [{:keys [enabled? contexts]}]
  (let [execution-statuses (keep #(get-in % [:latestExecution :status]) contexts)]
    (cond
      (false? enabled?) "disabled"
      (empty? contexts) "unbound"
      (some #{"failed"} execution-statuses) "failed"
      (some #{"running"} execution-statuses) "running"
      (some #{"completed"} execution-statuses) "ready"
      :else "configured")))

(defn- summarize-public-materialization-record
  [db dataset-refs pipeline-record]
  (let [contexts (public-materialization-contexts db dataset-refs (:dataset.pipeline/id pipeline-record))
        resolution-contexts (mapv (fn [ctx]
                                    {:tenant (:tenant ctx)
                                     :tenant-config-key (:dataset-config-key ctx)})
                                  contexts)
        effective-record (config-db/effective-dataset-pipeline-record
                          db
                          pipeline-record
                          {:master-key (config-core/get-master-key)
                           :contexts resolution-contexts})
        summary (pipeline-summary effective-record)]
    (assoc summary
           :status (public-materialization-status {:enabled? (:dataset.pipeline/enabled? effective-record)
                                                   :contexts contexts})
           :contexts contexts)))

(defn- public-dataset-status
  [pipelines]
  (let [statuses (map :status pipelines)]
    (cond
      (empty? pipelines) "needs-pipeline"
      (some #{"failed"} statuses) "degraded"
      (some #{"running"} statuses) "running"
      (every? #{"ready"} statuses) "ready"
      :else "configured")))

(defn- summarize-public-dataset-record
  [db dataset-refs dataset-record pipelines]
  (let [pipeline-summaries (mapv #(summarize-public-materialization-record db dataset-refs %) pipelines)]
    {:id (:dataset/id dataset-record)
     :name (:dataset/name dataset-record)
     :description (:dataset/description dataset-record)
     :enabled? (:dataset/enabled? dataset-record)
     :status (public-dataset-status pipeline-summaries)}))

(defn- visible-public-dataset-records
  [db dataset-refs]
  (let [visible-pipelines (->> (or dataset-refs [])
                               (mapcat (fn [dataset-ref]
                                         (try
                                           (:pipeline-records
                                            (config-db/resolve-dataset-ref-materializations db dataset-ref))
                                           (catch clojure.lang.ExceptionInfo _
                                             []))))
                               (reduce (fn [acc pipeline-record]
                                         (assoc acc (:dataset.pipeline/id pipeline-record) pipeline-record))
                                       {})
                               vals
                               (sort-by :dataset.pipeline/id)
                               vec)
        visible-dataset-ids (->> visible-pipelines
                                 (map #(get-in % [:dataset.pipeline/dataset :dataset/id]))
                                 distinct
                                 sort
                                 vec)
        pipelines-by-dataset-id (group-by #(get-in % [:dataset.pipeline/dataset :dataset/id]) visible-pipelines)]
    (->> visible-dataset-ids
         (keep (fn [dataset-id]
                 (when-let [dataset-record (pipeline/get-dataset-record db dataset-id)]
                   {:dataset-record dataset-record
                    :pipelines (get pipelines-by-dataset-id dataset-id [])})))
         vec)))

(defn list-public-datasets-handler
  "List the datasets visible to the current API key through dataset scopes."
  [ring-req]
  (try
    (let [conn (db/get-conn)
          db @conn
          dataset-refs (api-ctx/normalize-dataset-scopes! (or (:api-key/dataset-scopes ring-req) []))
          datasets (mapv (fn [{:keys [dataset-record pipelines]}]
                           (summarize-public-dataset-record db dataset-refs dataset-record pipelines))
                         (visible-public-dataset-records db dataset-refs))]
      (-> (res/response (json/generate-string {:datasets datasets}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list public datasets")
      (-> (res/response (json/generate-string {:error "Failed to list datasets"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn get-public-dataset-handler
  "Get one visible dataset."
  [ring-req]
  (try
    (let [dataset-id (:dataset-id (request-path-params ring-req))
          conn (db/get-conn)
          db @conn
          dataset-refs (api-ctx/normalize-dataset-scopes! (or (:api-key/dataset-scopes ring-req) []))
          visible-dataset (->> (visible-public-dataset-records db dataset-refs)
                               (some (fn [{:keys [dataset-record] :as visible}]
                                       (when (= dataset-id (:dataset/id dataset-record))
                                         visible))))]
      (if visible-dataset
        (-> (res/response (json/generate-string
                           {:dataset (summarize-public-dataset-record db
                                                                      dataset-refs
                                                                      (:dataset-record visible-dataset)
                                                                      (:pipelines visible-dataset))}))
            (res/status 200)
            (res/content-type "application/json"))
        (-> (res/response (json/generate-string {:error "Dataset not found"}))
            (res/status 404)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to get public dataset")
      (-> (res/response (json/generate-string {:error "Failed to get dataset"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn- request-config-root!
  [ring-req]
  (let [root-param (:root (request-path-params ring-req))
        root (some-> root-param keyword)]
    (when-not (contains? structure/config-roots root)
      (throw (ex-info "Invalid config root"
                      {:status 400
                       :root root-param})))
    root))

(defn- public-node-summary
  [node]
  (cond-> {:config-key (:config.node/tenant-config-key node)
           :label (:config.node/label node)
           :enabled? (:config.node/enabled? node)}
    (get-in node [:config.node/parent :config.node/id])
    (assoc :parent-node-id (get-in node [:config.node/parent :config.node/id]))

    (get-in node [:config.node/parent :config.node/id])
    (assoc :parent-config-key (some-> (get-in node [:config.node/parent :config.node/id])))))

(defn list-config-nodes-handler
  "List config nodes reachable through the API key's allowed config keys for a tenant/root."
  [ring-req]
  (try
    (let [query-params (request-query-params ring-req)
          tenant (param-value query-params :tenant)
          _ (when (str/blank? tenant)
              (throw (ex-info "Missing tenant" {:status 400})))
          root (request-config-root! ring-req)
          conn (db/get-conn)
          allowed-config-keys (vec (or (:api-key/allowed-config-keys ring-req) []))
          nodes (->> (config-db/list-config-nodes @conn tenant root)
                     (remove :config.node/system-managed?)
                     (filter (fn [node]
                               (or (empty? allowed-config-keys)
                                   (api-keys/allowed-config-key-allows? conn
                                                                        allowed-config-keys
                                                                        {:root root
                                                                         :tenant tenant
                                                                         :node-id (:config.node/id node)}))))
                     vec)
          tenant-config-keys-by-node-id (into {} (map (juxt :config.node/id :config.node/tenant-config-key)) nodes)
          response-data {:root (name root)
                         :tenant tenant
                         :allowed-config-keys (->> allowed-config-keys
                                                   (filter #(and (= root (:api-key.allowed-config-key/root %))
                                                                 (= tenant (:api-key.allowed-config-key/tenant %))))
                                                   (mapv :api-key.allowed-config-key/tenant-config-key))
                         :nodes (->> nodes
                                     (mapv public-node-summary)
                                     (mapv (fn [node]
                                             (cond-> node
                                               (get-in node [:parent-node-id])
                                               (assoc :parent-config-key (get tenant-config-keys-by-node-id
                                                                              (get-in node [:parent-node-id]))))))
                                     (sort-by :config-key)
                                     vec)}]
      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to list config nodes")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to list config nodes")
      (-> (res/response (json/generate-string {:error "Failed to list config nodes"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn resolve-runtime-config-handler
  "Resolve runtime config explicitly against a tenant runtime config key."
  [ring-req]
  (try
    (let [params (request-body-params ring-req)
          tenant (or (api-ctx/param-value params :tenant)
                     (throw (ex-info "Missing required field: tenant" {:status 400})))
          tenant-config-key (api-ctx/require-request-config-key! params
                                                                 :runtime
                                                                 "Missing required field: runtime-config-key")
          agent-id (or (api-ctx/request-agent-id params)
                       (throw (ex-info "Missing required field: agent-id" {:status 400})))
          dataset-ref (when (or (api-ctx/param-value params :dataset-ref)
                                (api-ctx/param-value params :dataset-config-key)
                                (api-ctx/param-value params :dataset_config_key))
                        (api-ctx/request-explicit-dataset-ref params))
          _ (when (= dataset-ref api-ctx/invalid-dataset-ref)
              (throw (ex-info "Dataset selection must include tenant and dataset-config-key" {:status 400})))
          conn (db/get-conn)
          {:keys [node matched-allowed-config-key]} (api-ctx/resolve-request-config-node! ring-req conn
                                                                                           {:tenant tenant
                                                                                            :root :runtime
                                                                                            :tenant-config-key tenant-config-key})
          dataset-id (or (api-ctx/param-value params :dataset-id)
                         (some-> (and dataset-ref
                                      (config-db/resolve-dataset-ref-materializations @conn dataset-ref))
                                 :dataset-id))
          paths (normalize-request-paths params)
          {:keys [config traces]} (cfg/load-runtime-config-v2-with-trace
                                   (cond-> {:tenant tenant
                                            :tenant-config-key tenant-config-key
                                            :agent-id agent-id}
                                     dataset-id (assoc :dataset-id dataset-id)
                                     (seq paths) (assoc :paths paths)))
          response-data {:root "runtime"
                         :tenant tenant
                         :runtime-config-key tenant-config-key
                         :config config
                         :traces traces
                         :authorization {:allowed true
                                         :matched-allowed-config-key (some-> matched-allowed-config-key :api-key.allowed-config-key/tenant-config-key)}
                         :compatibility (cond-> {:status "ok"
                                                 :checked-on-node "default"
                                         :agent-id agent-id}
                                         dataset-id (assoc :dataset-id dataset-id))
                         :node {:node-id (:config.node/id node)
                                :runtime-config-key (:config.node/tenant-config-key node)}}]
      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to resolve runtime config")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to resolve runtime config")
      (-> (res/response (json/generate-string {:error "Failed to resolve runtime config"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn resolve-dataset-config-handler
  "Resolve dataset config explicitly against a tenant dataset config key."
  [ring-req]
  (try
    (let [params (request-body-params ring-req)
          dataset-ref (or (api-ctx/request-dataset-ref params)
                          (throw (ex-info "Dataset selection must include tenant and dataset-config-key" {:status 400})))
          _ (when (= dataset-ref api-ctx/invalid-dataset-ref)
              (throw (ex-info "Dataset selection must include tenant and dataset-config-key" {:status 400})))
          conn (db/get-conn)
          master-key (config-core/get-master-key)
          dataset-config (or (config-db/get-dataset-by-ref @conn dataset-ref master-key)
                             (throw (ex-info "Dataset not found"
                                             {:status 404
                                              :dataset-ref dataset-ref})))
          tenant-config-key (:dataset-config-key dataset-config)
          {:keys [node matched-allowed-config-key]} (api-ctx/resolve-request-config-node! ring-req conn
                                                                                           {:tenant (:tenant dataset-config)
                                                                                            :root :dataset
                                                                                            :tenant-config-key tenant-config-key
                                                                                            :node-id (:dataset-node-id dataset-config)})
          dataset-id (or (api-ctx/param-value params :dataset-id)
                         (:dataset-id dataset-config))
          paths (normalize-request-paths params)
          {:keys [config traces]} (cfg/load-dataset-config-v2-with-trace
                                   (cond-> {:tenant (:tenant dataset-config)
                                            :dataset-id dataset-id
                                            :dataset-config-key tenant-config-key}
                                     (seq paths) (assoc :paths paths)))
          response-data {:root "dataset"
                         :tenant (:tenant dataset-config)
                         :dataset-config-key tenant-config-key
                         :config config
                         :traces traces
                         :authorization {:allowed true
                                         :matched-allowed-config-key (some-> matched-allowed-config-key :api-key.allowed-config-key/tenant-config-key)}
                         :compatibility {:status "ok"
                                         :checked-on-node "default"
                                         :dataset-id dataset-id}
                         :node {:node-id (:config.node/id node)
                                :dataset-config-key tenant-config-key}}]
      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to resolve dataset config")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to resolve dataset config")
      (-> (res/response (json/generate-string {:error "Failed to resolve dataset config"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn dataset-materialization-record!
  [db dataset-id pipeline-id]
  (let [pipeline-record (config-db/get-dataset-pipeline db pipeline-id)]
    (when-not (= dataset-id (get-in pipeline-record [:dataset.pipeline/dataset :dataset/id]))
      (throw (ex-info "Pipeline not found"
                      {:status 404
                       :dataset-id dataset-id
                       :pipeline-id pipeline-id})))
    pipeline-record))

(defn required-console-materialization-context
  [ring-req]
  (let [query-params (request-query-params ring-req)
        tenant (param-value query-params :tenant)
        dataset-config-key (or (param-value query-params :dataset-config-key)
                               (param-value query-params :dataset_config_key))]
    (when-not (seq tenant)
      (throw (ex-info "Missing tenant" {:status 400})))
    (when-not (seq dataset-config-key)
      (throw (ex-info "Missing dataset-config-key" {:status 400})))
    {:tenant tenant
     :dataset-config-key dataset-config-key}))

(defn list-datasets-handler
  "List durable parent datasets for the operator console."
  [_ring-req]
  (try
    (let [conn (db/get-conn)
          db @conn
          pipelines-by-dataset-id (group-by #(get-in % [:dataset.pipeline/dataset :dataset/id])
                                            (config-db/list-dataset-pipelines db))
          contexts-by-pipeline-id (config-db/materialization-contexts-by-pipeline-id db)
          summary-opts {:contexts-by-pipeline-id contexts-by-pipeline-id
                        :master-key (config-core/get-master-key)}
          datasets (mapv (fn [dataset-record]
                           (summarize-dataset-record db
                                                     dataset-record
                                                     (get pipelines-by-dataset-id (:dataset/id dataset-record) [])
                                                     summary-opts))
                         (pipeline/list-dataset-records db))]
      (-> (res/response (json/generate-string {:datasets datasets}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list datasets")
      (-> (res/response (json/generate-string {:error "Failed to list datasets"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn get-dataset-handler
  "Get a durable parent dataset and its child materialization pipeline summaries."
  [ring-req]
  (try
    (let [dataset-id (:dataset-id (request-path-params ring-req))
          conn (db/get-conn)
          db @conn
          dataset-record (pipeline/get-dataset-record db dataset-id)]
      (if dataset-record
        (let [pipelines (config-db/list-dataset-pipelines db dataset-id)
              contexts-by-pipeline-id (config-db/materialization-contexts-by-pipeline-id db)
              summary-opts {:contexts-by-pipeline-id contexts-by-pipeline-id
                            :master-key (config-core/get-master-key)}]
          (-> (res/response (json/generate-string {:dataset (summarize-dataset-record db dataset-record pipelines summary-opts)}))
              (res/status 200)
              (res/content-type "application/json")))
        (-> (res/response (json/generate-string {:error "Dataset not found"}))
            (res/status 404)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to get dataset")
      (-> (res/response (json/generate-string {:error "Failed to get dataset"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn create-dataset-handler
  "Create a durable parent dataset for operator workflows."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))
          params (request-body-params ring-req)
          name (:name params)
          description (:description params)
          _ (when-not (seq name)
              (throw (ex-info "Missing dataset name" {:status 400})))
          conn (db/get-conn)
          dataset (pipeline/create-dataset! conn (cond-> {:name name}
                                                   (contains? params :description)
                                                   (assoc :description description)))]
      (log/info "Created dataset" {:dataset-id (:dataset/id dataset)
                                   :user user-email})
      (-> (res/response (json/generate-string {:datasetId (:dataset/id dataset)
                                               :dataset (summarize-dataset-record @conn dataset [])}))
          (res/status 201)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to create dataset")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error creating dataset")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn update-dataset-handler
  "Update mutable attributes on a durable parent dataset.

   Admin authorization is enforced upstream by `digdir.api.http/wrap-admin-auth`
   for every `/console-api/*` route — this handler only validates the email
   header used for log attribution."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))
          dataset-id (:dataset-id (request-path-params ring-req))
          params (request-body-params ring-req)
          _ (when-not (seq params)
              (throw (ex-info "Missing dataset update fields" {:status 400
                                                               :dataset-id dataset-id})))
          conn (db/get-conn)
          dataset (pipeline/update-dataset! conn (assoc params :dataset-id dataset-id))
          db @conn
          pipelines (config-db/list-dataset-pipelines db dataset-id)
          contexts-by-pipeline-id (config-db/materialization-contexts-by-pipeline-id db)
          summary-opts {:contexts-by-pipeline-id contexts-by-pipeline-id
                        :master-key (config-core/get-master-key)}]
      (log/info "Updated dataset" {:dataset-id dataset-id
                                   :user user-email
                                   :fields (sort (map name (keys params)))})
      (-> (res/response (json/generate-string {:dataset (summarize-dataset-record db dataset pipelines summary-opts)}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to update dataset")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error updating dataset")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn list-pipelines-handler
  "List child materialization pipelines for a durable parent dataset."
  [ring-req]
  (try
    (let [dataset-id (:dataset-id (request-path-params ring-req))
          query-params (request-query-params ring-req)
          tenant (param-value query-params :tenant)
          conn (db/get-conn)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                 {:tenant tenant
                                                  :action :read})
          db @conn
          summary-opts {:tenant tenant
                        :master-key (config-core/get-master-key)}
          response-data (mapv #(summarize-dataset-materialization-record db % summary-opts)
                              (config-db/list-dataset-pipelines db dataset-id))]

      (-> (res/response (json/generate-string {:pipelines response-data}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to list pipelines")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to list pipelines")
      (-> (res/response (json/generate-string {:error "Failed to list pipelines"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn get-pipeline-handler
  "Get a specific child materialization pipeline under a dataset."
  [ring-req]
  (try
    (let [{:keys [dataset-id pipeline-id]} (request-path-params ring-req)
          {:keys [tenant dataset-config-key]} (required-console-materialization-context ring-req)
          conn (db/get-conn)
          db @conn
          _ (dataset-materialization-record! db dataset-id pipeline-id)
          execution-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                        {:tenant tenant
                                                         :action :read
                                                         :dataset-id execution-pipeline-id})
          master-key (config-core/get-master-key)
          p (pipeline/get-dataset db tenant dataset-config-key pipeline-id master-key)]

      (if p
        (-> (res/response (json/generate-string
                           {:pipeline (-> p
                                          (assoc :dataset-config-key dataset-config-key
                                                 :execution-pipeline-id execution-pipeline-id)
                                          (dissoc :tenant-config-key :id))}))
            (res/status 200)
            (res/content-type "application/json"))
        (-> (res/response (json/generate-string {:error "Pipeline not found"}))
            (res/status 404)
            (res/content-type "application/json"))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to get pipeline")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to get pipeline")
      (-> (res/response (json/generate-string {:error "Failed to get pipeline"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn create-pipeline-handler
  "Create a child materialization pipeline under an existing parent dataset."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))

          params (request-body-params ring-req)
          dataset-id (:dataset-id (request-path-params ring-req))
          tenant (:tenant params)
          dataset-config-key (:dataset-config-key params)
          pipeline-name (:pipeline-name params)
          properties (normalize-pipeline-properties (:properties params))

          conn (db/get-conn)
          master-key (config-core/get-master-key)
          pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-name)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                        {:tenant tenant
                                                         :action :write
                                                         :dataset-id pipeline-id})

          pipeline-id (pipeline/create-pipeline! conn
                                                 {:tenant tenant
                                                  :tenant-config-key dataset-config-key
                                                  :dataset-id dataset-id
                                                  :pipeline-name pipeline-name
                                                  :properties properties
                                                  :master-key master-key})]

      (log/info "Created pipeline" {:pipeline-id pipeline-id
                                    :dataset-id dataset-id
                                    :user user-email})

      (-> (res/response (json/generate-string {:pipeline-name pipeline-name
                                               :execution-pipeline-id pipeline-id
                                               :dataset-id dataset-id}))
          (res/status 201)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to create pipeline")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error creating pipeline")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn update-pipeline-handler
  "Update an existing child materialization pipeline."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))

          {:keys [dataset-id pipeline-id]} (request-path-params ring-req)
          {:keys [tenant dataset-config-key]} (required-console-materialization-context ring-req)

          params (request-body-params ring-req)
          properties (normalize-pipeline-properties (:properties params))

          conn (db/get-conn)
          db @conn
          _ (dataset-materialization-record! db dataset-id pipeline-id)
          master-key (config-core/get-master-key)
          external-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                 {:tenant tenant
                                                  :action :write
                                                  :dataset-id external-pipeline-id})

          _ (pipeline/update-pipeline! conn
                                      {:tenant tenant
                                       :tenant-config-key dataset-config-key
                                       :pipeline-name pipeline-id
                                       :properties properties
                                       :master-key master-key})]

      (log/info "Updated pipeline" {:dataset-id dataset-id :user user-email})

      (-> (res/response (json/generate-string {:success true}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to update pipeline")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error updating pipeline")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn delete-pipeline-handler
  "Delete a child materialization pipeline."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))

          {:keys [dataset-id pipeline-id]} (request-path-params ring-req)
          {:keys [tenant dataset-config-key]} (required-console-materialization-context ring-req)

          conn (db/get-conn)
          db @conn
          _ (dataset-materialization-record! db dataset-id pipeline-id)
          external-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                 {:tenant tenant
                                                  :action :write
                                                  :dataset-id external-pipeline-id})
          _ (pipeline/soft-delete-pipeline! conn
                                           tenant
                                           dataset-config-key
                                           pipeline-id)]

      (log/info "Deleted pipeline" {:dataset-id dataset-id :user user-email})

      (-> (res/response (json/generate-string {:success true}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to delete pipeline")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to delete pipeline")
      (-> (res/response (json/generate-string {:error "Failed to delete pipeline"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn execute-pipeline-handler
  "Execute a child materialization pipeline asynchronously."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))

          {:keys [dataset-id pipeline-id]} (request-path-params ring-req)
          {:keys [tenant dataset-config-key]} (required-console-materialization-context ring-req)

          conn (db/get-conn)
          db @conn
          _ (dataset-materialization-record! db dataset-id pipeline-id)
          master-key (config-core/get-master-key)
          external-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                 {:tenant tenant
                                                  :action :write
                                                  :dataset-id external-pipeline-id})

          ;; Import executor namespace
          _ (require 'digdir.pipeline.executor)
          execute-fn (resolve 'digdir.pipeline.executor/execute-pipeline-async!)

          execution-id (execute-fn conn
                                   tenant
                                   dataset-config-key
                                   pipeline-id
                                   master-key
                                   user-email)]

      (log/info "Started pipeline execution" {:dataset-id dataset-id :execution-id execution-id :user user-email})

      (-> (res/response (json/generate-string {:executionId execution-id}))
          (res/status 202)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to execute pipeline")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error executing pipeline")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn list-executions-handler
  "List executions for a child materialization pipeline."
  [ring-req]
  (try
    (let [{:keys [dataset-id pipeline-id]} (request-path-params ring-req)
          {:keys [tenant dataset-config-key]} (required-console-materialization-context ring-req)
          conn (db/get-conn)
          db @conn
          _ (dataset-materialization-record! db dataset-id pipeline-id)
          external-pipeline-id (pipeline/make-pipeline-id tenant dataset-config-key pipeline-id)
          _ (authorize-dataset-materialization-request! ring-req conn
                                                 {:tenant tenant
                                                  :action :read
                                                  :dataset-id external-pipeline-id})

          ;; Import executor namespace
          _ (require 'digdir.pipeline.executor)
          list-fn (resolve 'digdir.pipeline.executor/list-executions)

          executions (list-fn db external-pipeline-id)

          response-data (mapv (fn [e]
                               {:id (:pipeline-execution/id e)
                                :execution-pipeline-id (:pipeline-execution/pipeline-id e)
                                :status (name (:pipeline-execution/status e))
                                :startedAt (str (:pipeline-execution/started-at e))
                                :completedAt (when-let [t (:pipeline-execution/completed-at e)] (str t))
                                :documentsProcessed (:pipeline-execution/documents-processed e)
                                :documentsFailed (:pipeline-execution/documents-failed e)
                                :errorMessage (:pipeline-execution/error-message e)
                                :startedBy (:pipeline-execution/started-by e)})
                             executions)]

      (-> (res/response (json/generate-string {:executions response-data}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to list executions")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to list executions")
      (-> (res/response (json/generate-string {:error "Failed to list executions"}))
          (res/status 500)
          (res/content-type "application/json")))))
