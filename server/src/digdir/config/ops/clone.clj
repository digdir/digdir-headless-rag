(ns digdir.config.ops.clone
  "Tenant clone helpers for the tenant/root/node config model."
  (:require [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.ops.sync :as ops-sync]
            [digdir.config.structure :as structure]))

(def ^:private clone-roots
  ;; DERIVED. Ordered, because a clone walks the roots in sequence.
  structure/config-roots-ordered)

(defn- exported-key
  [k]
  (cond
    (string? k) k
    (keyword? k) (str (namespace k) "/" (name k))
    :else (str k)))

(defn- exported-get
  [m k]
  (or (get m k)
      (get m (exported-key k))))

(defn- normalize-root
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn- tenant-node-count
  [db tenant]
  (reduce + (map #(count (config-db/list-config-nodes db tenant %)) clone-roots)))

(defn- ensure-clone-preconditions!
  [db source-tenant target-tenant]
  (when (str/blank? source-tenant)
    (throw (ex-info "Source tenant is required" {})))
  (when (str/blank? target-tenant)
    (throw (ex-info "Target tenant is required" {})))
  (when (= source-tenant target-tenant)
    (throw (ex-info "Target tenant must differ from source tenant"
                    {:source-tenant source-tenant
                     :target-tenant target-tenant})))
  (when (zero? (tenant-node-count db source-tenant))
    (throw (ex-info "Source tenant has no config nodes to clone"
                    {:source-tenant source-tenant})))
  (when (or (config-db/get-tenant db target-tenant)
            (pos? (tenant-node-count db target-tenant)))
    (throw (ex-info "Target tenant already exists"
                    {:target-tenant target-tenant}))))

(defn- legacy-dataset-materialization-node-id
  [tenant dataset-id pipeline-id]
  (str "dataset/" tenant "/" dataset-id "/" pipeline-id "/materialization"))

(defn- shared-dataset-materialization-node-id
  [tenant dataset-id]
  (str "dataset/" tenant "/" dataset-id "/shared-materialization"))

(defn- rewrite-generic-tenant-node-id
  [root source-tenant target-tenant node-id]
  (let [source-prefix (str (name root) "/" source-tenant)
        target-prefix (str (name root) "/" target-tenant)]
    (cond
      (= node-id source-prefix)
      target-prefix

      (str/starts-with? node-id (str source-prefix "/"))
      (str target-prefix (subs node-id (count source-prefix)))

      :else
      (throw (ex-info "Cannot rewrite non-canonical tenant-local node ID during clone"
                      {:root root
                       :source-tenant source-tenant
                       :target-tenant target-tenant
                       :node-id node-id})))))

(defn- rewrite-node-id
  [source-tenant target-tenant node]
  (let [root (normalize-root (exported-get node :config.node/root))
        node-id (exported-get node :config.node/id)]
    (if (= :dataset root)
      (if-let [{:keys [kind tenant dataset-id tenant-config-key pipeline-id legacy?]} (config-db/parse-dataset-node-id node-id)]
        (do
          (when (not= tenant source-tenant)
            (throw (ex-info "Dataset node tenant mismatch during clone"
                            {:source-tenant source-tenant
                             :parsed-tenant tenant
                             :node-id node-id})))
          (case kind
            :base
            (config-db/dataset-base-node-id target-tenant dataset-id)

            :shared-materialization
            (shared-dataset-materialization-node-id target-tenant dataset-id)

            :materialization
            (if legacy?
              (legacy-dataset-materialization-node-id target-tenant dataset-id pipeline-id)
              (config-db/dataset-materialization-node-id target-tenant tenant-config-key dataset-id pipeline-id))

            (throw (ex-info "Unsupported dataset node kind during clone"
                            {:kind kind
                             :node-id node-id}))))
        (rewrite-generic-tenant-node-id root source-tenant target-tenant node-id))
      (rewrite-generic-tenant-node-id root source-tenant target-tenant node-id))))

(defn- materialization-node?
  [node]
  (let [root (normalize-root (exported-get node :config.node/root))
        node-id (exported-get node :config.node/id)]
    (and (= :dataset root)
         (= :materialization
            (:kind (config-db/parse-dataset-node-id node-id))))))

(defn- cloneable-node?
  [exclude-dataset-pipelines? node]
  (not (and exclude-dataset-pipelines?
            (materialization-node? node))))

(defn- rewrite-node-entity
  [target-tenant node-id-map node]
  (let [source-node-id (exported-get node :config.node/id)
        parent-id (exported-get node :config.node/parent-id)]
    (cond-> {:config.node/id (get node-id-map source-node-id)
             :config.node/root (normalize-root (exported-get node :config.node/root))
             :config.node/tenant target-tenant
             :config.node/label (exported-get node :config.node/label)
             :config.node/tenant-config-key (exported-get node :config.node/tenant-config-key)
             :config.node/system-managed? (boolean (exported-get node :config.node/system-managed?))
             :config.node/enabled? (if (nil? (exported-get node :config.node/enabled?))
                                     true
                                     (boolean (exported-get node :config.node/enabled?)))}
      parent-id
      (assoc :config.node/parent-id
             (or (get node-id-map parent-id)
                 (throw (ex-info "Clone filtered out a required parent node"
                                 {:node-id source-node-id
                                  :parent-id parent-id})))))))

(defn- rewrite-node-value-entity
  [target-tenant node-id-map value]
  {:config.value/root (normalize-root (exported-get value :config.value/root))
   :config.value/tenant target-tenant
   :config.value/node-id (or (get node-id-map (exported-get value :config.value/node-id))
                             (throw (ex-info "Clone missing node mapping for value"
                                             {:node-id (exported-get value :config.value/node-id)
                                              :definition-path (exported-get value :config.value/definition-path)})))
   :config.value/definition-path (exported-get value :config.value/definition-path)
   :config.value/raw (exported-get value :config.value/raw)})

(defn- clone-summary
  [source-tenant target-tenant exclude-dataset-pipelines? source-nodes source-node-values datasets dataset-pipelines cloned-nodes cloned-node-values]
  {:source-tenant source-tenant
   :target-tenant target-tenant
   :exclude-dataset-pipelines? exclude-dataset-pipelines?
   :source {:nodes (count source-nodes)
            :node-values (count source-node-values)
            :datasets (count datasets)
            :dataset-pipelines (count dataset-pipelines)}
   :clone {:nodes {:count (count cloned-nodes)
                   :by-root (frequencies (map :config.node/root cloned-nodes))}
           :node-values {:count (count cloned-node-values)
                         :by-root (frequencies (map :config.value/root cloned-node-values))}
           :linked-datasets (->> datasets
                                 (map #(or (:dataset/id %)
                                           (get % "dataset/id")))
                                 sort
                                 vec)
           :linked-dataset-pipelines (->> dataset-pipelines
                                          (map #(or (:dataset.pipeline/id %)
                                                    (get % "dataset.pipeline/id")))
                                          sort
                                          vec)}})

(defn- build-clone-import-payload
  [conn source-tenant target-tenant {:keys [exclude-dataset-pipelines?]
                                     :or {exclude-dataset-pipelines? false}}]
  (let [export-data (ops-sync/export-tenant conn source-tenant {:include-audit? false})
        source-nodes (vec (or (get-in export-data [:data :nodes])
                              (get-in export-data ["data" "nodes"])
                              []))
        source-node-values (vec (or (get-in export-data [:data :node-values])
                                    (get-in export-data ["data" "node-values"])
                                    []))
        datasets (vec (or (get-in export-data [:data :datasets])
                          (get-in export-data ["data" "datasets"])
                          []))
        dataset-pipelines (vec (or (get-in export-data [:data :dataset-pipelines])
                                   (get-in export-data ["data" "dataset-pipelines"])
                                   []))
        kept-nodes (->> source-nodes
                        (filter #(cloneable-node? exclude-dataset-pipelines? %))
                        vec)
        node-id-map (into {}
                          (map (fn [node]
                                 [(exported-get node :config.node/id)
                                  (rewrite-node-id source-tenant target-tenant node)]))
                          kept-nodes)
        kept-source-node-ids (set (keys node-id-map))
        kept-node-values (->> source-node-values
                              (filter #(contains? kept-source-node-ids
                                                  (exported-get % :config.value/node-id)))
                              vec)
        cloned-nodes (mapv #(rewrite-node-entity target-tenant node-id-map %) kept-nodes)
        cloned-node-values (mapv #(rewrite-node-value-entity target-tenant node-id-map %) kept-node-values)
        summary (clone-summary source-tenant
                               target-tenant
                               exclude-dataset-pipelines?
                               source-nodes
                               source-node-values
                               datasets
                               dataset-pipelines
                               cloned-nodes
                               cloned-node-values)]
    {:summary summary
     :import-data {:version (:version export-data)
                   :exported-at (:exported-at export-data)
                   :scope "tenant-clone"
                   :tenant target-tenant
                   :data {:definitions []
                          :nodes cloned-nodes
                          :bindings []
                          :compatibilities []
                          :datasets []
                          :dataset-pipelines []
                          :node-values cloned-node-values
                          :audit []}}}))

(defn preview-clone-tenant!
  "Preview cloning a source tenant into a new target tenant."
  [conn source-tenant target-tenant opts]
  (let [db @conn]
    (ensure-clone-preconditions! db source-tenant target-tenant)
    (let [{:keys [summary import-data]} (build-clone-import-payload conn source-tenant target-tenant opts)
          preview (ops-sync/import-data conn import-data {:on-conflict :skip
                                                          :dry-run? true})]
      {:summary summary
       :preview preview})))

(defn clone-tenant!
  "Clone tenant-local config trees from source tenant into a new target tenant."
  [conn source-tenant target-tenant opts]
  (let [db @conn]
    (ensure-clone-preconditions! db source-tenant target-tenant)
    (let [{:keys [summary import-data]} (build-clone-import-payload conn source-tenant target-tenant opts)
          result (ops-sync/import-data conn import-data {:on-conflict :skip})]
      {:summary summary
       :result result})))
