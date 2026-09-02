(ns digdir.import-export.entities.api-keys
  "API key import/export helpers."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.api-keys :as config-api-keys]
            [digdir.config.db :as config-db]
            [digdir.import-export.report :as report]))

(defn export-api-keys
  [db]
  (config-api-keys/export-all-api-keys db))

(defn- non-blank-string
  [v]
  (let [s (some-> v str str/trim)]
    (when-not (str/blank? s)
      s)))

(defn- api-key-eid
  [db api-key-id]
  (d/q '[:find ?e .
         :in $ ?api-key-id
         :where
         [?e :api-key/id ?api-key-id]]
       db api-key-id))

(defn- credential-storage-fields
  "Normalize both current hashed exports and legacy plaintext exports into
  hashed-at-rest fields. Plaintext is intentionally never returned."
  [api-key]
  (let [legacy-plaintext (:api-key/key api-key)
        digest (or (:api-key/key-digest api-key)
                   (some-> legacy-plaintext config-api-keys/api-key-digest))
        display-metadata (when legacy-plaintext
                           (config-api-keys/api-key-display-metadata legacy-plaintext))]
    (when-not (non-blank-string digest)
      (throw (ex-info "Imported API key has no credential digest"
                      {:api-key-id (:api-key/id api-key)})))
    (merge {:api-key/key-digest digest}
           display-metadata
           (when-let [prefix (non-blank-string (:api-key/prefix api-key))]
             {:api-key/prefix prefix})
           (when-let [last-four (non-blank-string (:api-key/last-four api-key))]
             {:api-key/last-four last-four}))))

(defn- canonicalize-imported-allowed-config-key
  [config-conn allowed-config-key]
  (if-let [node-id (:api-key.allowed-config-key/node-id allowed-config-key)]
    (let [resolved-tenant-config-key (some-> (config-db/get-config-node @config-conn node-id)
                                             :config.node/tenant-config-key
                                             non-blank-string)]
      (cond-> allowed-config-key
        resolved-tenant-config-key
        (assoc :api-key.allowed-config-key/tenant-config-key resolved-tenant-config-key)))
    allowed-config-key))

(defn import-api-keys-tx
  [config-conn db api-keys on-conflict]
  (reduce (fn [acc api-key]
            (let [api-key-id (:api-key/id api-key)
                  exists? (some? (api-key-eid db api-key-id))
                  overwrite? (and exists? (= on-conflict :overwrite))]
              (if (and exists? (= on-conflict :skip))
                acc
                (let [dataset-scopes (:api-key/dataset-scopes api-key)
                      agent-refs (:api-key/agent-refs api-key)
                      allowed-config-keys (mapv #(canonicalize-imported-allowed-config-key config-conn %)
                                                (:api-key/allowed-config-keys api-key))
                      dataset-scope-tx (mapv (fn [idx {:keys [tenant dataset-config-key]}]
                                               {:db/id (str "temp-dataset-scope-" api-key-id "-" idx)
                                                :api-key.dataset-scope/id (str "api-key-dataset-scope-" api-key-id "-" idx)
                                                :api-key.dataset-scope/tenant tenant
                                                :api-key.dataset-scope/dataset-config-key dataset-config-key})
                                             (range)
                                             dataset-scopes)
                      dataset-scope-ids (mapv :db/id dataset-scope-tx)
                      agent-ref-tx (mapv (fn [idx agent-id]
                                           {:db/id (str "temp-agent-ref-" api-key-id "-" idx)
                                            :api-key.agent-ref/id (str "api-key-agent-ref-" api-key-id "-" idx)
                                            :api-key.agent-ref/agent-id agent-id})
                                         (range)
                                         agent-refs)
                      agent-ref-ids (mapv :db/id agent-ref-tx)
                      allowed-config-key-tx (mapv (fn [idx allowed-config-key]
                                                    (cond-> {:db/id (str "temp-allowed-config-key-" api-key-id "-" idx)
                                                             :api-key.allowed-config-key/id (:api-key.allowed-config-key/id allowed-config-key)
                                                             :api-key.allowed-config-key/root (:api-key.allowed-config-key/root allowed-config-key)
                                                             :api-key.allowed-config-key/tenant (:api-key.allowed-config-key/tenant allowed-config-key)}
                                                      (:api-key.allowed-config-key/created-at allowed-config-key)
                                                      (assoc :api-key.allowed-config-key/created-at (:api-key.allowed-config-key/created-at allowed-config-key))
                                                      (:api-key.allowed-config-key/node-id allowed-config-key)
                                                      (assoc :api-key.allowed-config-key/node-id (:api-key.allowed-config-key/node-id allowed-config-key))
                                                      (:api-key.allowed-config-key/tenant-config-key allowed-config-key)
                                                      (assoc :api-key.allowed-config-key/tenant-config-key (:api-key.allowed-config-key/tenant-config-key allowed-config-key))))
                                                  (range)
                                                  allowed-config-keys)
                      allowed-config-key-ids (mapv :db/id allowed-config-key-tx)
                      tx-data (cond-> (merge {:api-key/id api-key-id
                                       :api-key/name (:api-key/name api-key)
                                       :api-key/created (:api-key/created api-key)
                                       :api-key/created-by (:api-key/created-by api-key)
                                       :api-key/revoked (boolean (:api-key/revoked api-key))
                                       :api-key/scopes (vec (:api-key/scopes api-key))
                                       :api-key/usage-count (or (:api-key/usage-count api-key) 0)}
                                              (credential-storage-fields api-key))
                                (seq (:api-key/clients api-key)) (assoc :api-key/clients (vec (:api-key/clients api-key)))
                                (seq (:api-key/skill-graphs api-key)) (assoc :api-key/skill-graphs (vec (:api-key/skill-graphs api-key)))
                                (:api-key/last-used api-key) (assoc :api-key/last-used (:api-key/last-used api-key))
                                (:api-key/expires-at api-key) (assoc :api-key/expires-at (:api-key/expires-at api-key))
                                (seq dataset-scope-ids) (assoc :api-key/dataset-scopes dataset-scope-ids)
                                (seq agent-ref-ids) (assoc :api-key/agent-refs agent-ref-ids)
                                (seq allowed-config-key-ids) (assoc :api-key/allowed-config-keys allowed-config-key-ids))
                      retract-tx (when overwrite?
                                   (let [entity (d/pull db '[{:api-key/dataset-scopes [:db/id]}
                                                             {:api-key/agent-refs [:db/id]}
                                                             {:api-key/allowed-config-keys [:db/id]}]
                                                        [:api-key/id api-key-id])
                                         child-eids (concat (map :db/id (:api-key/dataset-scopes entity))
                                                            (map :db/id (:api-key/agent-refs entity))
                                                            (map :db/id (:api-key/allowed-config-keys entity)))]
                                     (vec (concat
                                           (map (fn [eid] [:db/retractEntity eid]) child-eids)
                                           [[:db/retractEntity (api-key-eid db api-key-id)]]))))]
                  (into acc (concat (or retract-tx []) dataset-scope-tx agent-ref-tx allowed-config-key-tx [tx-data]))))))
          []
          api-keys))

(defn import-api-keys!
  [config-conn conn api-keys on-conflict]
  (let [existing-by-id (into {}
                             (map (fn [api-key]
                                    [(:api-key/id api-key)
                                     (some? (api-key-eid @conn (:api-key/id api-key)))]))
                             api-keys)
        tx-data (import-api-keys-tx config-conn @conn api-keys on-conflict)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    ;; For reporting, we still need to calculate stats
    (reduce (fn [acc api-key]
              (let [exists? (get existing-by-id (:api-key/id api-key))]
                (cond
                  (and exists? (= on-conflict :skip)) (update acc :skipped inc)
                  (and exists? (= on-conflict :overwrite)) (update acc :overwritten inc)
                  :else (update acc :created inc))))
            {:created 0 :skipped 0 :overwritten 0}
            api-keys)))

(defn preview-import-api-keys
  [db api-keys on-conflict]
  (report/preview-existing-items api-keys
                                 #(some? (api-key-eid db (:api-key/id %)))
                                 on-conflict))
