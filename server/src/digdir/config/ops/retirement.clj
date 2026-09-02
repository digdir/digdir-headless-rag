(ns digdir.config.ops.retirement
  "Tenant retirement helpers for config and playground cleanup."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.structure :as structure]))

(def ^:private default-retired-source-tenants
  ["altinn" "altinn-docs" "ka"])

(defn- safe-conversation-count
  [db tenant]
  (try
    (or (d/q '[:find (count ?c) .
               :in $ ?tenant
               :where [?c :conversation/tenant ?tenant]]
             db tenant)
        0)
    (catch Exception _
      0)))

(defn- tenant-scoped-dataset-retirement-data
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

(defn- tenant-root-summary
  [db tenant root]
  {:root root
   :nodes (count (config-db/list-config-nodes db tenant root))
   :bindings 0})

(defn- tenant-root-summaries
  [db tenant]
  (mapv #(tenant-root-summary db tenant %)
        structure/config-roots-ordered))

(defn- enabled-node-tenant-config-keys
  [db tenant root]
  (->> (config-db/list-config-nodes db tenant root)
       (filter :config.node/enabled?)
       (keep :config.node/tenant-config-key)
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- enabled-dataset-profile-keys
  [db tenant]
  (->> (config-db/list-config-nodes db tenant :dataset)
       (filter :config.node/enabled?)
       (keep (fn [node]
               (when-let [{:keys [kind tenant-config-key]} (config-db/parse-dataset-node-id (:config.node/id node))]
                 (when (= :materialization kind)
                   tenant-config-key))))
       distinct
       sort
       vec))

(defn- tenant-retirement-summary
  [db tenant tenant-config-key]
  (let [registered? (boolean (config-db/get-tenant db tenant))
        root-summaries (tenant-root-summaries db tenant)
        roots (into {}
                    (map (juxt :root #(dissoc % :root)))
                    root-summaries)]
    (if-not registered?
      {:tenant tenant
       :registered? false
       :root-summaries root-summaries
       :roots roots
       :datasets []
       :dataset-profiles []
       :runtime-agents []
       :platform-profiles []
       :pipelines []
       :tenant-config-key-pipelines []
       :conversations 0}
      (let [{:keys [datasets dataset-pipelines]} (tenant-scoped-dataset-retirement-data db tenant)
            dataset-ids (->> datasets
                             (map :dataset/id)
                             distinct
                             sort
                             vec)
            pipeline-ids (->> dataset-pipelines
                              (map :dataset.pipeline/id)
                              distinct
                              sort
                              vec)]
        {:tenant tenant
         :registered? true
         :root-summaries root-summaries
         :roots roots
         :datasets dataset-ids
         :dataset-profiles (enabled-dataset-profile-keys db tenant)
         :runtime-agents []
         :platform-profiles (enabled-node-tenant-config-keys db tenant :platform)
         :pipelines pipeline-ids
         :tenant-config-key-pipelines (config-db/list-datasets db tenant tenant-config-key)
         :conversations (safe-conversation-count db tenant)}))))

(defn preview-tenant-retirement!
  "Read-only preview of what the source-tenant retirement step would affect."
  ([conn]
   (preview-tenant-retirement! conn {}))
  ([conn {:keys [tenants tenant-config-key]
          :or {tenants default-retired-source-tenants
               tenant-config-key "default"}}]
   (let [db @conn
         preview (mapv #(tenant-retirement-summary db % tenant-config-key) tenants)]
     {:tenant-config-key tenant-config-key
      :tenants preview
      :all-tenants (config-db/list-tenants db)
      :removable-tenants (->> preview
                              (filter :registered?)
                              (map :tenant)
                              vec)})))

(defn- query-tenant-value-eids
  [db tenant]
  (d/q '[:find [?e ...]
         :in $ ?tenant
         :where [?e :config.value/tenant ?tenant]]
       db tenant))

(defn- query-tenant-node-eids
  [db tenant]
  (d/q '[:find [?e ...]
         :in $ ?tenant
         :where [?e :config.node/tenant ?tenant]]
       db tenant))

(defn- query-tenant-entity-eid
  [db tenant]
  (d/q '[:find ?e .
         :in $ ?tenant
         :where [?e :tenant/id ?tenant]]
       db tenant))

(defn- query-playground-conversation-ids
  [db tenant]
  (try
    (vec
     (d/q '[:find [?conversation-id ...]
            :in $ ?tenant
            :where
            [?e :conversation/tenant ?tenant]
            [?e :conversation/id ?conversation-id]]
          db tenant))
    (catch Exception _
      [])))

(defn- query-config-node-eid
  [db node-id]
  (d/q '[:find ?e .
         :in $ ?node-id
         :where [?e :config.node/id ?node-id]]
       db node-id))

(defn- retirement-effect
  [db tenant]
  {:tenant tenant
   :value-eids (query-tenant-value-eids db tenant)
   :node-eids (query-tenant-node-eids db tenant)
   :tenant-eid (query-tenant-entity-eid db tenant)
   :conversation-ids (query-playground-conversation-ids db tenant)})

(defn- query-dangling-node-parent-ids
  [db]
  (vec
   (remove nil?
           (d/q '[:find [?node-id ...]
                  :where
                  [?e :config.node/id ?node-id]
                  [?e :config.node/parent ?parent]
                  (not [?parent :config.node/id _])]
                db))))

(defn- repair-dangling-config-refs!
  [conn]
  (let [db @conn
        dangling-node-parent-ids (->> (query-dangling-node-parent-ids db)
                                      (filter #(and (string? %)
                                                    (not (str/blank? %))))
                                      vec)]
    (doseq [node-id dangling-node-parent-ids]
      (try
        (config-db/clear-config-node-parent! conn {:node-id node-id})
        (catch Exception ex
          (when-not (= "Config node not found" (ex-message ex))
            (throw ex)))))
    {:cleared-parent-count (count dangling-node-parent-ids)
     :cleared-parent-node-ids dangling-node-parent-ids
     :deleted-binding-count 0
     :deleted-binding-ids []}))

(defn- node-depths
  [nodes]
  (let [parent-by-id (into {} (map (juxt :config.node/id #(get-in % [:config.node/parent :config.node/id]))) nodes)
        depth* (fn depth* [node-id]
                 (loop [current-id node-id
                        depth 0]
                   (if-let [parent-id (get parent-by-id current-id)]
                     (recur parent-id (inc depth))
                     depth)))]
    (into {} (map (fn [node] [(:config.node/id node) (depth* (:config.node/id node))]) nodes))))

(defn- tenant-node-delete-order
  [db tenant]
  (let [nodes (mapcat #(config-db/list-config-nodes db tenant %)
                      ;; ORDERED, not the set: this feeds a delete order.
                      structure/config-roots-ordered)
        depths (node-depths nodes)]
    (->> nodes
         (sort-by (fn [node]
                    [(- (get depths (:config.node/id node) 0))
                     (:config.node/id node)]))
         vec)))

(defn- raw-delete-config-node!
  [conn node-id]
  (when-let [node-eid (query-config-node-eid @conn node-id)]
    (let [db @conn
          binding-eids (d/q '[:find [?e ...]
                              :in $ ?node
                              :where [?e :config.binding/node ?node]]
                            db node-eid)
          value-eids (d/q '[:find [?e ...]
                            :in $ ?node
                            :where [?e :config.value/node ?node]]
                          db node-eid)
          tx-data (vec (concat (map (fn [eid] [:db.fn/retractEntity eid]) binding-eids)
                               (map (fn [eid] [:db.fn/retractEntity eid]) value-eids)
                               [[:db.fn/retractEntity node-eid]]))]
      (when (seq tx-data)
        (d/transact conn {:tx-data tx-data}))
      true)))

(defn- delete-config-node-for-retirement!
  [conn {:keys [root tenant node-id]}]
  (try
    (config-db/delete-config-node! conn {:root root
                                         :tenant tenant
                                         :node-id node-id})
    (catch Exception ex
      (cond
        (raw-delete-config-node! conn node-id)
        true

        (= "Config node not found" (ex-message ex))
        false

        :else
        (throw ex)))))

(defn- purge-tenant-config-residue!
  [conn tenant]
  (let [db @conn
        value-eids (query-tenant-value-eids db tenant)
        node-eids (query-tenant-node-eids db tenant)]
    (doseq [eid (concat value-eids node-eids)]
      (let [tx-data (->> (d/datoms @conn :eavt eid)
                         (mapv (fn [datom]
                                 (if (vector? (:v datom))
                                   [:db/retract (:e datom) (:a datom)]
                                   [:db/retract (:e datom) (:a datom) (:v datom)]))))]
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))))
    {:value-count (count value-eids)
     :binding-count 0
     :node-count (count node-eids)}))

(defn- apply-conversation-strategy!
  [conn tenant {:keys [conversation-ids conversation-strategy target-tenant]}]
  (case conversation-strategy
    :retarget
    (do
      (when (str/blank? target-tenant)
        (throw (ex-info "Retarget strategy requires a target tenant"
                        {:tenant tenant
                         :conversation-count (count conversation-ids)})))
      (when-not (config-db/get-tenant @conn target-tenant)
        (throw (ex-info "Retarget tenant not found"
                        {:tenant tenant
                         :target-tenant target-tenant})))
      (when (seq conversation-ids)
        (d/transact conn
                    {:tx-data (mapv (fn [conversation-id]
                                      {:db/id [:conversation/id conversation-id]
                                       :conversation/tenant target-tenant})
                                    conversation-ids)}))
      {:strategy :retarget
       :conversation-count (count conversation-ids)
       :target-tenant target-tenant})

    :delete
    (do
      (when (seq conversation-ids)
        (let [db @conn
              convo-eids (d/q '[:find [?e ...]
                                :in $ [?conversation-id ...]
                                :where
                                [?e :conversation/id ?conversation-id]]
                              db conversation-ids)
              msg-eids (d/q '[:find [?m ...]
                              :in $ [?conversation-id ...]
                              :where
                              [?c :conversation/id ?conversation-id]
                              [?c :conversation/messages ?m]]
                            db conversation-ids)
              tx-data (vec (concat
                            (map (fn [eid] [:db.fn/retractEntity eid]) msg-eids)
                            (map (fn [eid] [:db.fn/retractEntity eid]) convo-eids)))]
          (when (seq tx-data)
            (d/transact conn tx-data))))
      {:strategy :delete
       :conversation-count (count conversation-ids)})

    :retain
    (if (seq conversation-ids)
      (throw (ex-info "Cannot retire tenant while conversations still reference it"
                      {:tenant tenant
                       :conversation-count (count conversation-ids)
                       :conversation-strategy conversation-strategy}))
      {:strategy :retain
       :conversation-count 0})

    (throw (ex-info "Unsupported conversation retirement strategy"
                    {:tenant tenant
                     :conversation-strategy conversation-strategy
                     :supported-strategies [:retain :retarget :delete]}))))

(defn retire-source-tenants!
  "Remove source-tenant config trees after target-topology verification.

   Dry-run by default. When applying, conversation handling must be explicit:
   - :retain   -> only allowed when no conversations still reference the tenant
   - :retarget -> rewrite :conversation/tenant to the mapped target tenant
   - :delete   -> delete the playground conversations"
  ([conn]
   (retire-source-tenants! conn {}))
  ([conn {:keys [tenants tenant-config-key dry-run? conversation-strategy target-tenants]
          :or {tenants default-retired-source-tenants
               tenant-config-key "default"
               dry-run? true
               conversation-strategy :retain
               target-tenants {"altinn" "digdir"
                               "altinn-docs" "digdir"
                               "ka" "public-sector-knowledge"}}}]
   (let [preview (preview-tenant-retirement! conn {:tenants tenants
                                                   :tenant-config-key tenant-config-key})
         db @conn
         effects (mapv #(retirement-effect db %) tenants)]
     (if dry-run?
       {:dry-run? true
        :conversation-strategy conversation-strategy
        :preview preview
        :effects (mapv (fn [{:keys [tenant value-eids node-eids tenant-eid conversation-ids]}]
                         {:tenant tenant
                          :value-count (count value-eids)
                          :binding-count 0
                          :node-count (count node-eids)
                          :tenant-record? (boolean tenant-eid)
                          :conversation-count (count conversation-ids)
                          :target-tenant (get target-tenants tenant)})
                       effects)}
       (let [conversation-results (mapv (fn [{:keys [tenant conversation-ids]}]
                                          (apply-conversation-strategy! conn
                                                                        tenant
                                                                        {:conversation-ids conversation-ids
                                                                         :conversation-strategy conversation-strategy
                                                                         :target-tenant (get target-tenants tenant)}))
                                        effects)
             _ (doseq [tenant tenants]
                 (doseq [node (tenant-node-delete-order @conn tenant)]
                   (delete-config-node-for-retirement! conn
                                                       {:root (:config.node/root node)
                                                        :tenant tenant
                                                        :node-id (:config.node/id node)}))
                 (purge-tenant-config-residue! conn tenant)
                 (when-let [tenant-eid (query-tenant-entity-eid @conn tenant)]
                   (d/transact conn {:tx-data [[:db.fn/retractEntity tenant-eid]]})))
             repair-result (repair-dangling-config-refs! conn)]
         {:dry-run? false
          :conversation-strategy conversation-strategy
          :conversation-results conversation-results
          :repair-result repair-result
          :retired-tenants (vec tenants)
          :remaining-tenants (config-db/list-tenants @conn)})))))
