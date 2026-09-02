(ns digdir.config.ops.sync
  "Export and import operations for the V2 config model."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.ops.util :as ops-util])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

(def ^:private export-version ops-util/export-version)
(def ^:private encryption-metadata ops-util/encryption-metadata)
(def ^:private clean-db-pipeline ops-util/clean-db-pipeline)
(def ^:private serialize-pipeline ops-util/serialize-pipeline)

(defn- iso-timestamp
  "Generate an ISO-8601 timestamp string."
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))

(defn- export-definitions
  "Export all config definitions."
  [db]
  (->> (config-db/get-all-definitions db)
       (mapv (comp serialize-pipeline clean-db-pipeline))))

(defn- query-config-nodes
  "Get config nodes, optionally filtered by tenant."
  [db tenant]
  (let [nodes (if tenant
                (d/q '[:find [(pull ?e [*]) ...]
                       :in $ ?tenant
                       :where
                       [?e :config.node/id]
                       [?e :config.node/tenant ?tenant]]
                     db
                     tenant)
                (d/q '[:find [(pull ?e [*]) ...]
                       :where
                       [?e :config.node/id]]
                     db))
        parent-id-by-node-id
        (into {}
              (if tenant
                (d/q '[:find ?node-id ?parent-id
                       :in $ ?tenant
                       :where
                       [?e :config.node/id ?node-id]
                       [?e :config.node/tenant ?tenant]
                       [?e :config.node/parent ?parent]
                       [?parent :config.node/id ?parent-id]]
                     db
                     tenant)
                (d/q '[:find ?node-id ?parent-id
                       :where
                       [?e :config.node/id ?node-id]
                       [?e :config.node/parent ?parent]
                       [?parent :config.node/id ?parent-id]]
                     db)))]
    (mapv (fn [node]
            (if-let [parent-id (get parent-id-by-node-id (:config.node/id node))]
              (assoc node :config.node/parent #:config.node{:id parent-id})
              node))
          nodes)))

(defn- serialize-config-node
  [node]
  (cond-> (-> node
              (dissoc :db/id :config.node/parent)
              clean-db-pipeline
              serialize-pipeline)
    (get-in node [:config.node/parent :config.node/id])
    (assoc "config.node/parent-id"
           (get-in node [:config.node/parent :config.node/id]))))

(defn- export-config-nodes
  [db tenant]
  (->> (query-config-nodes db tenant)
       (sort-by :config.node/id)
       (mapv serialize-config-node)))

(defn- tenant-scoped-dataset-export-data
  [db tenant]
  (let [tenant-dataset-nodes (config-db/list-config-nodes db tenant :dataset)
        node-refs (reduce (fn [acc node]
                            (if-let [{:keys [kind dataset-id pipeline-id]}
                                     (config-db/parse-dataset-node-id (:config.node/id node))]
                              (cond-> acc
                                dataset-id (update :dataset-ids conj dataset-id)
                                (= :materialization kind) (update :pipeline-ids conj pipeline-id))
                              acc))
                          {:dataset-ids #{}
                           :pipeline-ids #{}}
                          tenant-dataset-nodes)
        dataset-ids (:dataset-ids node-refs)
        pipeline-ids (:pipeline-ids node-refs)
        selected-pipelines (->> (config-db/list-dataset-pipelines db)
                                (filter #(contains? pipeline-ids (:dataset.pipeline/id %)))
                                vec)
        selected-dataset-ids (into dataset-ids
                                   (keep #(get-in % [:dataset.pipeline/dataset :dataset/id]))
                                   selected-pipelines)
        selected-datasets (->> (config-db/list-dataset-records db)
                               (filter #(contains? selected-dataset-ids (:dataset/id %)))
                               vec)]
    {:datasets selected-datasets
     :dataset-pipelines selected-pipelines}))

(defn- query-node-values
  "Get active node-scoped values, optionally filtered by tenant."
  [db tenant]
  (let [node-values (if tenant
                      (d/q '[:find [(pull ?e [* #:config.value{:definition [:config-def/path
                                                                            :config-def/encrypted?
                                                                            :config-def/value-type]}]) ...]
                             :in $ ?tenant
                             :where
                             [?e :config.value/id]
                             [?e :config.value/tenant ?tenant]
                             (not [?e :config.value/deleted-at])]
                           db
                           tenant)
                      (d/q '[:find [(pull ?e [* #:config.value{:definition [:config-def/path
                                                                            :config-def/encrypted?
                                                                            :config-def/value-type]}]) ...]
                             :where
                             [?e :config.value/id]
                             (not [?e :config.value/deleted-at])]
                           db))
        node-id-by-value-id
        (into {}
              (if tenant
                (d/q '[:find ?value-id ?node-id
                       :in $ ?tenant
                       :where
                       [?e :config.value/id ?value-id]
                       [?e :config.value/tenant ?tenant]
                       [?e :config.value/node ?node]
                       [?node :config.node/id ?node-id]]
                     db
                     tenant)
                (d/q '[:find ?value-id ?node-id
                       :where
                       [?e :config.value/id ?value-id]
                       [?e :config.value/node ?node]
                       [?node :config.node/id ?node-id]]
                     db)))]
    (mapv (fn [value]
            (if-let [node-id (get node-id-by-value-id (:config.value/id value))]
              (assoc value :config.value/node #:config.node{:id node-id})
              value))
          node-values)))

(defn- prepare-node-value-for-export
  [value master-key export-password]
  (let [definition (:config.value/definition value)
        encrypted? (:config-def/encrypted? definition)
        raw-value (:config.value/raw value)
        exported-value (if (and encrypted? raw-value master-key export-password)
                         (ops-util/re-encrypt-config-value raw-value master-key export-password)
                         raw-value)]
    (cond-> (-> value
                (dissoc :db/id :config.value/node :config.value/definition)
                (assoc :config.value/raw exported-value)
                clean-db-pipeline
                serialize-pipeline)
      (:config-def/path definition)
      (assoc "config.value/definition-path" (:config-def/path definition))

      (get-in value [:config.value/node :config.node/id])
      (assoc "config.value/node-id"
             (get-in value [:config.value/node :config.node/id])))))

(defn- export-node-values
  [db tenant master-key export-password]
  (->> (query-node-values db tenant)
       (sort-by (juxt :config.value/root :config.value/tenant :config.value/id))
       (mapv #(prepare-node-value-for-export % master-key export-password))))

(defn- export-datasets
  [datasets]
  (->> datasets
       (sort-by :dataset/id)
       (mapv (fn [dataset]
               (-> dataset
                   (dissoc :db/id)
                   clean-db-pipeline
                   serialize-pipeline)))))

(defn- export-dataset-pipelines
  [dataset-pipelines]
  (->> dataset-pipelines
       (sort-by :dataset.pipeline/id)
       (mapv (fn [pipeline]
               (-> pipeline
                   (assoc :dataset.pipeline/dataset-id
                          (get-in pipeline [:dataset.pipeline/dataset :dataset/id]))
                   (dissoc :db/id
                           :dataset.pipeline/dataset
                           :dataset.pipeline/name
                           :dataset.pipeline/source-type)
                   clean-db-pipeline
                   serialize-pipeline)))))

(defn- query-audit
  "Get audit records, optionally filtered by tenant."
  [db tenant]
  (if tenant
    (d/q '[:find [(pull ?e [*]) ...]
           :in $ ?tenant
           :where
           [?e :audit/tenant ?tenant]]
         db
         tenant)
    (d/q '[:find [(pull ?e [*]) ...]
           :where
           [?e :audit/id]]
         db)))

(defn- export-audit
  "Export audit records, optionally filtered by tenant."
  [db tenant]
  (->> (query-audit db tenant)
       (mapv (fn [record]
               (-> record
                   (dissoc :audit/config-def :audit/config-value)
                   clean-db-pipeline
                   serialize-pipeline)))
       (sort-by #(get % "audit/timestamp"))))

(defn- build-export-preview
  "Build a preview of what would be exported."
  [db tenant include-audit?]
  (let [all-defs (config-db/get-all-definitions db)
        config-nodes (query-config-nodes db tenant)
        node-values (query-node-values db tenant)
        {:keys [datasets dataset-pipelines]}
        (if tenant
          (tenant-scoped-dataset-export-data db tenant)
          {:datasets (config-db/list-dataset-records db)
           :dataset-pipelines (config-db/list-dataset-pipelines db)})
        audit-count (when include-audit? (count (query-audit db tenant)))]
    {:definitions {:count (count all-defs)}
     :nodes {:count (count config-nodes)
             :by-root (frequencies (map :config.node/root config-nodes))}
     :bindings {:count 0}
     :compatibilities {:count 0}
     :node-values {:count (count node-values)
                   :by-root (frequencies (map :config.value/root node-values))}
     :datasets {:count (count datasets)}
     :dataset-pipelines {:count (count dataset-pipelines)}
     :audit {:count (or audit-count 0)}}))

(defn- export-data*
  "Internal export implementation for both full and tenant-scoped exports."
  [db tenant {:keys [master-key export-password include-audit?]}]
  (let [{:keys [datasets dataset-pipelines]}
        (if tenant
          (tenant-scoped-dataset-export-data db tenant)
          {:datasets (config-db/list-dataset-records db)
           :dataset-pipelines (config-db/list-dataset-pipelines db)})]
    {:version export-version
     :exported-at (iso-timestamp)
     :scope (if tenant "tenant" "full")
     :tenant tenant
     :encryption encryption-metadata
     :data {:definitions (export-definitions db)
            :nodes (export-config-nodes db tenant)
            :bindings []
            :compatibilities []
            :datasets (export-datasets datasets)
            :dataset-pipelines (export-dataset-pipelines dataset-pipelines)
            :node-values (export-node-values db tenant master-key export-password)
            :audit (when include-audit? (export-audit db tenant))}}))

(defn export-full
  "Export full database backup."
  [conn {:keys [include-audit? dry-run?]
         :or {include-audit? true
              dry-run? false}
         :as opts}]
  (let [db @conn]
    (if dry-run?
      (build-export-preview db nil include-audit?)
      (export-data* db nil (assoc opts :include-audit? include-audit?)))))

(defn export-tenant
  "Export tenant-scoped backup."
  [conn tenant {:keys [include-audit? dry-run?]
                :or {include-audit? true
                     dry-run? false}
                :as opts}]
  (let [db @conn]
    (if dry-run?
      (build-export-preview db tenant include-audit?)
      (export-data* db tenant (assoc opts :include-audit? include-audit?)))))

(def definition-keyword-fields
  "Fields that should be converted to keywords when deserializing definitions.
   These mirror the :db.type/keyword attributes declared in config.schema —
   omitting one here causes the value to be stored as a string post-import,
   silently breaking any predicate that checks against the keyword (e.g.
   the :inherit ownership branch of the global-fallback resolver)."
  #{:config-def/function
    :config-def/service
    :config-def/value-type
    :config-def/root
    :config-def/sensitivity
    :config-def/category
    :config-def/ownership})

(defn- deserialize-definition
  "Deserialize a definition from JSON import."
  [def-map]
  (reduce-kv (fn [acc k v]
               (let [key-str (name (if (keyword? k) k (keyword k)))
                     simple-key (keyword (str/replace key-str #"^config-def/" ""))
                     keyword-field (keyword (str "config-def/" (name simple-key)))
                     kw-val (if (contains? definition-keyword-fields keyword-field)
                              (keyword v)
                              v)]
                 (assoc acc simple-key kw-val)))
             {}
             def-map))

(defn- preview-definition-import
  "Preview what would happen if definitions were imported."
  [db definitions]
  (let [existing-paths (set (map :config-def/path (config-db/get-all-definitions db)))
        new-defs (remove #(contains? existing-paths (:path %)) definitions)]
    {:would-create (count new-defs)
     :existing (count (filter #(contains? existing-paths (:path %)) definitions))
     :total (count definitions)}))

(defn- import-definitions!
  "Import definitions into the database."
  [conn definitions]
  (log/info "Importing definitions" {:count (count definitions)})
  (config-db/upsert-definitions-batch! conn definitions)
  (log/info "Definitions imported successfully" {:count (count definitions)})
  {:created (count definitions)})

(def config-tree-keyword-fields
  ;; Kept for legacy dataset-pipeline import compatibility. These attrs are no
  ;; longer exported as durable state, but older envelopes may still include them.
  #{:config.value/root
    :config.node/root
    :dataset.pipeline/source-type})

(defn- deserialize-config-tree-entity
  [entity-map]
  (reduce-kv (fn [acc k v]
               (let [kw-key (if (keyword? k) k (keyword k))
                     kw-val (if (contains? config-tree-keyword-fields kw-key)
                              (when v (keyword v))
                              v)]
                 (assoc acc kw-key kw-val)))
             {}
             entity-map))

(defn- preview-create-or-update-import
  [existing-fn items id-key on-conflict]
  (reduce (fn [acc item]
            (if (existing-fn (id-key item))
              (update acc (if (= on-conflict :overwrite) :would-overwrite :would-skip) inc)
              (update acc :would-create inc)))
          {:would-create 0
           :would-overwrite 0
           :would-skip 0
           :total (count items)}
          items))

(defn- preview-node-value-import
  [db node-values on-conflict]
  (reduce (fn [acc value]
            (if (config-db/get-node-value db
                                          (:config.value/root value)
                                          (:config.value/tenant value)
                                          (:config.value/node-id value)
                                          (:config.value/definition-path value))
              (update acc (if (= on-conflict :overwrite) :would-overwrite :would-skip) inc)
              (update acc :would-create inc)))
          {:would-create 0
           :would-overwrite 0
           :would-skip 0
           :total (count node-values)}
          node-values))

(defn- register-tenants-from-nodes!
  [conn nodes]
  (doseq [tenant (->> nodes
                      (map :config.node/tenant)
                      (remove nil?)
                      distinct)]
    (config-db/register-tenant! conn tenant {:created-by "import"})))

(defn- partition-importable-config-nodes
  [db nodes imported-node-ids]
  (reduce (fn [{:keys [ready waiting]} node]
            (let [node-id (:config.node/id node)
                  parent-id (:config.node/parent-id node)
                  existing? (some? (config-db/get-config-node db node-id))
                  parent-ready? (or (nil? parent-id)
                                    (contains? imported-node-ids parent-id)
                                    (some? (config-db/get-config-node db parent-id)))]
              (if (or existing? parent-ready?)
                {:ready (conj ready node)
                 :waiting waiting}
                {:ready ready
                 :waiting (conj waiting node)})))
          {:ready []
           :waiting []}
          nodes))

(defn- import-config-nodes!
  [conn nodes on-conflict]
  (loop [remaining (vec nodes)
         imported-node-ids #{}
         acc {:created 0
              :updated 0
              :skipped 0}]
    (if (empty? remaining)
      acc
      (let [{:keys [ready waiting]}
            (partition-importable-config-nodes @conn remaining imported-node-ids)]
        (when (empty? ready)
          (throw (ex-info "Config node import could not resolve parent ordering"
                          {:remaining-node-ids (mapv :config.node/id waiting)
                           :missing-parent-ids (->> waiting
                                                    (map :config.node/parent-id)
                                                    (remove nil?)
                                                    (remove #(config-db/get-config-node @conn %))
                                                    distinct
                                                    vec)})))
        (let [next-acc
              (reduce (fn [acc* node]
                        (let [node-id (:config.node/id node)
                              existing (config-db/get-config-node @conn node-id)
                              root (:config.node/root node)
                              tenant (:config.node/tenant node)
                              payload {:root root
                                       :tenant tenant
                                       :node-id node-id
                                       :label (:config.node/label node)
                                       :tenant-config-key (:config.node/tenant-config-key node)
                                       :system-managed? (:config.node/system-managed? node false)
                                       :parent-id (:config.node/parent-id node)
                                       :enabled? (:config.node/enabled? node true)
                                       :created-at (:config.node/created-at node)
                                       :updated-at (:config.node/updated-at node)}]
                          (cond
                            (nil? existing)
                            (do
                              (ops-bootstrap/ensure-config-node! conn payload)
                              (update acc* :created inc))

                            (= on-conflict :skip)
                            (update acc* :skipped inc)

                            :else
                            (do
                              (when (or (not= root (:config.node/root existing))
                                        (not= tenant (:config.node/tenant existing)))
                                (throw (ex-info "Config node root or tenant mismatch during import"
                                                {:node-id node-id
                                                 :existing-root (:config.node/root existing)
                                                 :existing-tenant (:config.node/tenant existing)
                                                 :import-root root
                                                 :import-tenant tenant})))
                              (ops-bootstrap/ensure-config-node! conn payload)
                              (update acc* :updated inc)))))
                      acc
                      ready)
              next-imported-node-ids (into imported-node-ids (map :config.node/id ready))]
          (recur (vec waiting) next-imported-node-ids next-acc))))))

(defn- import-datasets!
  [conn datasets on-conflict]
  (reduce (fn [acc dataset]
            (let [dataset-id (:dataset/id dataset)
                  existing (config-db/get-dataset-record @conn dataset-id)
                  payload (cond-> {:dataset-id dataset-id
                                   :name (:dataset/name dataset)
                                   :enabled? (:dataset/enabled? dataset true)
                                   :created-at (:dataset/created-at dataset)
                                   :updated-at (:dataset/updated-at dataset)}
                            (contains? dataset :dataset/description)
                            (assoc :description (:dataset/description dataset)))]
              (cond
                (nil? existing)
                (do
                  (config-db/create-dataset! conn payload)
                  (update acc :created inc))

                (= on-conflict :skip)
                (update acc :skipped inc)

                :else
                (do
                  (config-db/update-dataset! conn payload)
                  (update acc :updated inc)))))
          {:created 0
           :updated 0
           :skipped 0}
          datasets))

(defn- import-dataset-pipelines!
  [conn dataset-pipelines on-conflict]
  (reduce (fn [acc pipeline]
            (let [pipeline-id (:dataset.pipeline/id pipeline)
                  dataset-id (or (:dataset.pipeline/dataset-id pipeline)
                                 (get-in pipeline [:dataset.pipeline/dataset :dataset/id]))
                  existing (config-db/get-dataset-pipeline @conn pipeline-id)
                  payload {:pipeline-id pipeline-id
                           :dataset-id dataset-id
                           :enabled? (:dataset.pipeline/enabled? pipeline true)
                           :created-at (:dataset.pipeline/created-at pipeline)
                           :updated-at (:dataset.pipeline/updated-at pipeline)}
                  backfill-opts {:name (:dataset.pipeline/name pipeline)
                                 :source-type (:dataset.pipeline/source-type pipeline)}]
              (cond
                (nil? existing)
                (do
                  (config-db/create-dataset-pipeline! conn payload)
                  (when (or (some? (:name backfill-opts))
                            (some? (:source-type backfill-opts)))
                    (config-db/backfill-dataset-pipeline-config-projections! conn pipeline-id backfill-opts))
                  (update acc :created inc))

                (= on-conflict :skip)
                (update acc :skipped inc)

                :else
                (do
                  (config-db/update-dataset-pipeline! conn payload)
                  (when (or (some? (:name backfill-opts))
                            (some? (:source-type backfill-opts)))
                    (config-db/backfill-dataset-pipeline-config-projections! conn pipeline-id backfill-opts))
                  (update acc :updated inc)))))
          {:created 0
           :updated 0
           :skipped 0}
          dataset-pipelines))

(defn- import-node-values!
  [conn node-values master-key export-password on-conflict]
  (let [db @conn
        res (reduce (fn [acc value]
                      (let [root (:config.value/root value)
                            tenant (:config.value/tenant value)
                            node-id (:config.value/node-id value)
                            path (:config.value/definition-path value)
                            definition (config-db/get-definition db path)
                            node (config-db/get-config-node db node-id)
                            _ (when-not definition
                                (throw (ex-info "Missing definition during node-value import"
                                                {:path path
                                                 :node-id node-id})))
                            _ (when-not node
                                (throw (ex-info "Missing config node during node-value import"
                                                {:node-id node-id
                                                 :path path})))
                            _ (when-not (= root (:config-def/root definition))
                                (throw (ex-info "Definition root mismatch during node-value import"
                                                {:path path
                                                 :definition-root (:config-def/root definition)
                                                 :value-root root})))
                            raw-value (:config.value/raw value)
                            final-value (if (and (:config-def/encrypted? definition)
                                                 raw-value
                                                 export-password
                                                 master-key)
                                          (ops-util/re-encrypt-config-value raw-value export-password master-key)
                                          raw-value)
                            value-id (config-db/make-node-value-id root tenant node-id path)
                            existing (config-db/get-node-value db root tenant node-id path)
                            now (System/currentTimeMillis)
                            ;; Import is authoritative: dump's timestamps win
                            ;; over both pre-seeded init values and existing
                            ;; rows. UI write paths use set-node-value!, not
                            ;; this function, so no operator-flow regression.
                            input-created-at (:config.value/created-at value)
                            input-updated-at (:config.value/updated-at value)
                            input-pin-of-version (:config.value/pin-of-version value)
                            base-tx (cond-> #:config.value{:id value-id
                                                           :root root
                                                           :tenant tenant
                                                           :node [:config.node/id node-id]
                                                           :definition [:config-def/path path]
                                                           :raw final-value
                                                           :created-at (or input-created-at now)
                                                           :updated-at (or input-updated-at now)}
                                      input-pin-of-version
                                      (assoc :config.value/pin-of-version
                                             input-pin-of-version))]
                        (cond
                          (nil? existing)
                          (-> acc
                              (update :tx conj base-tx)
                              (update :created inc))

                          (= on-conflict :skip)
                          (update acc :skipped inc)

                          :else
                          (let [existing-pin (:config.value/pin-of-version existing)
                                ;; If the dump unpinned a value (no pin field
                                ;; while the existing row has one), retract
                                ;; the existing pin alongside the upsert.
                                retract-pin? (and existing-pin (nil? input-pin-of-version))]
                            (cond-> acc
                              true (update :tx conj base-tx)
                              retract-pin? (update :tx conj
                                                   [:db/retract
                                                    [:config.value/id value-id]
                                                    :config.value/pin-of-version
                                                    existing-pin])
                              true (update :updated inc))))))
                    {:tx [] :created 0 :updated 0 :skipped 0}
                    node-values)]
    (when (seq (:tx res))
      (d/transact conn {:tx-data (:tx res)}))
    (dissoc res :tx)))

(defn- deserialize-audit
  "Deserialize an audit record from JSON import."
  [audit-map]
  (reduce-kv (fn [acc k v]
               (let [kw-key (keyword k)
                     kw-val (if (= kw-key :audit/action)
                              (keyword v)
                              v)]
                 (assoc acc kw-key kw-val)))
             {}
             audit-map))

(defn- import-audit!
  "Import audit records into the database."
  [conn audit-records]
  (let [tx-data (when (seq audit-records)
                  (mapv (fn [record]
                          (-> record
                              (select-keys [:audit/id
                                            :audit/timestamp
                                            :audit/action
                                            :audit/config-path
                                            :audit/tenant
                                            :audit/tenant-config-key
                                            :audit/previous-value
                                            :audit/new-value
                                            :audit/user-email
                                            :audit/user-id
                                            :audit/ip-address])
                              (cond-> (:audit/config-path record)
                                (assoc :audit/config-def [:config-def/path (:audit/config-path record)]))))
                        audit-records))]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    {:imported (count audit-records)}))

(defn import-data
  "Import data from a backup."
  [conn data {:keys [master-key export-password on-conflict dry-run? progress-atom]
              :or {on-conflict :skip
                   dry-run? false}}]
  (log/info "Starting config import"
            {:version (:version data)
             :scope (:scope data)
             :exported-at (:exported-at data)
             :on-conflict on-conflict
             :dry-run? dry-run?})
  (when progress-atom
    (reset! progress-atom {:phase :parsing
                           :status :running
                           :message "Parsing import data..."}))
  (let [db @conn
        raw-definitions (get-in data [:data :definitions] (get-in data ["data" "definitions"]))
        raw-nodes (get-in data [:data :nodes] (get-in data ["data" "nodes"]))
        raw-bindings (get-in data [:data :bindings] (get-in data ["data" "bindings"]))
        raw-compatibilities (get-in data [:data :compatibilities] (get-in data ["data" "compatibilities"]))
        raw-datasets (get-in data [:data :datasets] (get-in data ["data" "datasets"]))
        raw-dataset-pipelines (get-in data [:data :dataset-pipelines] (get-in data ["data" "dataset-pipelines"]))
        raw-node-values (get-in data [:data :node-values] (get-in data ["data" "node-values"]))
        raw-audit (get-in data [:data :audit] (get-in data ["data" "audit"]))]
    (log/info "Parsed import data"
              {:definitions-count (count raw-definitions)
               :nodes-count (count raw-nodes)
               :bindings-count (count raw-bindings)
               :compatibilities-count (count raw-compatibilities)
               :datasets-count (count raw-datasets)
               :dataset-pipelines-count (count raw-dataset-pipelines)
               :node-values-count (count raw-node-values)
               :audit-count (count raw-audit)})
    (when progress-atom
      (swap! progress-atom assoc
             :phase :parsed
             :definitions-count (count raw-definitions)
             :message (str "Found "
                           (count raw-definitions)
                           " definitions, "
                           (count raw-node-values)
                           " node values, "
                           (count raw-nodes)
                           " nodes")))
    (let [definitions (mapv deserialize-definition raw-definitions)
          nodes (mapv deserialize-config-tree-entity (or raw-nodes []))
          bindings (mapv deserialize-config-tree-entity (or raw-bindings []))
          compatibilities (mapv deserialize-config-tree-entity (or raw-compatibilities []))
          datasets (mapv deserialize-config-tree-entity (or raw-datasets []))
          dataset-pipelines (mapv deserialize-config-tree-entity (or raw-dataset-pipelines []))
          node-values (mapv deserialize-config-tree-entity (or raw-node-values []))
          audit-records (mapv deserialize-audit (or raw-audit []))]
      (when (seq bindings)
        (throw (ex-info "Legacy config bindings are no longer importable"
                        {:binding-count (count bindings)})))
      (if dry-run?
        (let [result {:definitions (preview-definition-import db definitions)
                      :nodes (preview-create-or-update-import #(config-db/get-config-node db %)
                                                              nodes
                                                              :config.node/id
                                                              on-conflict)
                      :bindings {:would-create 0
                                 :would-overwrite 0
                                 :would-skip 0
                                 :total 0}
                      :compatibilities {:would-create (count compatibilities)
                                        :would-overwrite 0
                                        :would-skip 0
                                        :total (count compatibilities)}
                      :datasets (preview-create-or-update-import #(config-db/get-dataset-record db %)
                                                                 datasets
                                                                 :dataset/id
                                                                 on-conflict)
                      :dataset-pipelines (preview-create-or-update-import #(config-db/get-dataset-pipeline db %)
                                                                          dataset-pipelines
                                                                          :dataset.pipeline/id
                                                                          on-conflict)
                      :node-values (preview-node-value-import db node-values on-conflict)
                      :audit {:count (count audit-records)}}]
          (log/info "Dry-run import preview" result)
          (when progress-atom
            (reset! progress-atom {:phase :preview
                                   :status :complete
                                   :result result}))
          result)
        (do
          (when progress-atom
            (swap! progress-atom assoc
                   :phase :definitions
                   :message (str "Importing " (count definitions) " definitions...")))
          (let [def-result (import-definitions! conn definitions)]
            (when progress-atom
              (swap! progress-atom assoc :definitions-result def-result))
            (register-tenants-from-nodes! conn nodes)
            (when progress-atom
              (swap! progress-atom assoc
                     :phase :nodes
                     :message (str "Importing "
                                   (count nodes)
                                   " nodes and "
                                   (count node-values)
                                   " node values...")))
            (let [node-result (import-config-nodes! conn nodes on-conflict)
                  dataset-result (import-datasets! conn datasets on-conflict)
                  dataset-pipelines-result (import-dataset-pipelines! conn dataset-pipelines on-conflict)
                  node-value-result (import-node-values! conn node-values master-key export-password on-conflict)
                  audit-result (when (seq audit-records)
                                 (import-audit! conn audit-records))
                  result {:definitions def-result
                          :nodes node-result
                          :bindings {:created 0
                                     :updated 0
                                     :skipped 0}
                          :compatibilities {:created 0
                                            :updated 0
                                            :skipped 0}
                          :datasets dataset-result
                          :dataset-pipelines dataset-pipelines-result
                          :dataset-pipeline-result dataset-pipelines-result
                          :node-values node-value-result
                          :audit audit-result}]
              (log/info "Import completed successfully" result)
              (when progress-atom
                (reset! progress-atom {:phase :complete
                                       :status :complete
                                       :result result
                                       :message "Import completed successfully"}))
              result)))))))

(defn export-to-file
  "Export data and write to a JSON file."
  [conn file-path {:keys [tenant] :as opts}]
  (let [export-fn (if tenant export-tenant export-full)
        export-args (if tenant [conn tenant opts] [conn opts])
        data (apply export-fn export-args)]
    (spit file-path (json/write-str data :escape-slash false))
    {:file-path file-path
     :scope (or (:scope data) (if tenant "tenant" "full"))
     :definitions (count (get-in data [:data :definitions]))
     :node-values (count (get-in data [:data :node-values]))
     :audit (count (get-in data [:data :audit]))}))

(defn import-from-file
  "Read a JSON file and import the data."
  [conn file-path opts]
  (log/info "Reading import file" {:file-path file-path})
  (let [data (json/read-str (slurp file-path) :key-fn keyword)]
    (log/info "Import file parsed successfully" {:file-path file-path})
    (import-data conn data opts)))
