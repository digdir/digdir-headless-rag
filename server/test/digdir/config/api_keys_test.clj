(ns digdir.config.api-keys-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [datahike.api :as d]
            [nano-id.core :as nano-id]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.audit :as audit]
            [digdir.config.db :as config-db]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as data-db]))

(use-fixtures :each
  (fn [f]
    (let [previous-config-conn (config-db/get-conn)]
      (try
        (f)
        (finally
          (config-db/set-conn! previous-config-conn))))))

;; ===== generate-api-key tests =====

(deftest test-generate-api-key-format
  (testing "Generated API key format"
    (let [key (api-keys/generate-api-key)]
      (testing "has correct prefix"
        (is (str/starts-with? key "rag_")))

      (testing "has correct length (rag_ + 64 hex chars = 68)"
        (is (= 68 (count key))))

      (testing "contains only valid hex characters after prefix"
        (let [hex-part (subs key 4)]
          (is (re-matches #"[0-9a-f]+" hex-part)))))))

(deftest test-generate-api-key-uniqueness
  (testing "Generated API keys are unique"
    (let [keys (repeatedly 100 api-keys/generate-api-key)]
      (is (= 100 (count (set keys)))))))

(deftest test-api-key-digest-and-display-metadata
  (testing "digest is deterministic and display metadata contains no credential"
    (let [plaintext "rag_test123"
          digest (api-keys/api-key-digest plaintext)]
      (is (= "2228b5c0a36e071e2d6fae8e3c9d72bc0a29bea2dfa27652924e5e656c8c8fce"
             digest))
      (is (= 64 (count digest)))
      (is (= {:api-key/prefix "rag_test"
              :api-key/last-four "t123"}
             (api-keys/api-key-display-metadata plaintext)))
      (is (not (str/includes? digest plaintext))))))

;; ===== validate-api-key tests =====

(deftest test-validate-api-key-valid
  (testing "Valid API key returns key info"
    (let [mock-key-entity {:api-key/id "key-123"
                           :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                                   :api-key.dataset-scope/dataset-config-key "prod"}]
                           :api-key/name "Test Key"
                           :api-key/revoked false
                           :api-key/scopes [:query]
                           :api-key/usage-count 5}
          transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ api-key]
                          (when (= api-key (api-keys/api-key-digest "rag_valid123"))
                            mock-key-entity))
                    d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data))]
        (let [result (api-keys/validate-api-key mock-conn "rag_valid123")]
          (testing "returns explicit dataset-scopes"
            (is (= [{:tenant "ka"
                     :dataset-config-key "prod"}]
                   (:dataset-scopes result))))

          (testing "returns explicit agent-refs"
            (is (= [] (:agent-refs result))))

          (testing "returns api-key-id"
            (is (= "key-123" (:api-key-id result))))

          (testing "returns name"
            (is (= "Test Key" (:name result))))

          (testing "returns scopes as set"
            (is (= #{:query} (:scopes result))))

          (testing "updates last-used timestamp"
            (is (= 1 (count @transactions)))
            (let [tx-data (first (first @transactions))]
              (is (= "key-123" (:api-key/id tx-data)))
              (is (number? (:api-key/last-used tx-data)))
              (is (= 6 (:api-key/usage-count tx-data))))))))))

(deftest test-validate-api-key-normalizes-legacy-scope-values
  (testing "Valid API key normalizes string and symbol scope values to keywords"
    (let [mock-key-entity {:api-key/id "key-123"
                           :api-key/dataset-scopes []
                           :api-key/name "Test Key"
                           :api-key/revoked false
                           :api-key/scopes ["query" 'ingest]}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ api-key]
                          (when (= api-key (api-keys/api-key-digest "rag_valid123"))
                            mock-key-entity))
                    d/transact (fn [_ {:keys [tx-data]}]
                                 {:tx-data tx-data})]
        (let [result (api-keys/validate-api-key mock-conn "rag_valid123")]
          (is (= #{:query :ingest} (:scopes result))))))))

(deftest test-validate-api-key-normalizes-legacy-allowed-config-keys
  (testing "Valid API key normalizes legacy allowed-config-key values on read"
    (let [mock-key-entity {:api-key/id "key-123"
                           :api-key/dataset-scopes []
                           :api-key/name "Test Key"
                           :api-key/revoked false
                           :api-key/scopes [:query]
                           :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                          :api-key.allowed-config-key/root 'dataset
                                                          :api-key.allowed-config-key/tenant 'digdir
                                                          :api-key.allowed-config-key/node-id (symbol "dataset" "digdir/public-docs/default")
                                                          :api-key.allowed-config-key/tenant-config-key 'default}]}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ api-key]
                          (when (= api-key (api-keys/api-key-digest "rag_valid123"))
                            mock-key-entity))
                    d/transact (fn [_ {:keys [tx-data]}]
                                 {:tx-data tx-data})]
        (let [result (api-keys/validate-api-key mock-conn "rag_valid123")
              allowed-config-key (first (:allowed-config-keys result))]
          (is (= :dataset (:api-key.allowed-config-key/root allowed-config-key)))
          (is (= "digdir" (:api-key.allowed-config-key/tenant allowed-config-key)))
          (is (= "dataset/digdir/public-docs/default" (:api-key.allowed-config-key/node-id allowed-config-key)))
          (is (= "default" (:api-key.allowed-config-key/tenant-config-key allowed-config-key))))))))

(deftest test-validate-api-key-nil
  (testing "Nil API key returns nil"
    (let [mock-conn (atom :mock-db)]
      (is (nil? (api-keys/validate-api-key mock-conn nil))))))

(deftest test-validate-api-key-not-found
  (testing "Non-existent API key returns nil"
    (let [mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ _] nil)]
        (is (nil? (api-keys/validate-api-key mock-conn "rag_nonexistent")))))))

(deftest test-validate-api-key-revoked
  (testing "Revoked API key returns nil"
    (let [mock-key-entity {:api-key/id "key-123"
                           :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                                   :api-key.dataset-scope/dataset-config-key "prod"}]
                           :api-key/revoked true}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ _] mock-key-entity)]
        (is (nil? (api-keys/validate-api-key mock-conn "rag_revoked")))))))

(deftest test-validate-api-key-expired
  (testing "Expired API key returns nil"
    (let [past-time (- (System/currentTimeMillis) 100000)
          mock-key-entity {:api-key/id "key-123"
                           :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                                   :api-key.dataset-scope/dataset-config-key "prod"}]
                           :api-key/revoked false
                           :api-key/expires-at past-time}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ _] mock-key-entity)]
        (is (nil? (api-keys/validate-api-key mock-conn "rag_expired")))))))

(deftest test-allowed-config-key-allows-descendant-node
  (testing "Config ceilings authorize descendant nodes without binding restrictions"
    (let [conn (atom :mock-db)
          ceilings [{:api-key.allowed-config-key/id "ceiling-1"
                     :api-key.allowed-config-key/root :runtime
                     :api-key.allowed-config-key/tenant "ka"
                     :api-key.allowed-config-key/node-id "runtime/ka/default"
                     :api-key.allowed-config-key/tenant-config-key "default"}]]
      (with-redefs [digdir.config.db/get-config-node (fn [_db node-id]
                                                       (case node-id
                                                         "runtime/ka/frontpage" {:config.node/id "runtime/ka/frontpage"
                                                                                 :config.node/parent {:config.node/id "runtime/ka/default"}}
                                                         "runtime/ka/default" {:config.node/id "runtime/ka/default"}
                                                         nil))]
        (is (true? (api-keys/allowed-config-key-allows? conn ceilings
                                                    {:root :runtime
                                                     :tenant "ka"
                                                     :node-id "runtime/ka/frontpage"})))
        (is (= "ceiling-1"
               (:api-key.allowed-config-key/id
                (api-keys/require-allowed-config-key! conn ceilings
                                                  {:root :runtime
                                                   :tenant "ka"
                                                   :node-id "runtime/ka/frontpage"}))))
        (is (false? (api-keys/allowed-config-key-allows? conn ceilings
                                                     {:root :runtime
                                                      :tenant "ka"
                                                      :node-id "runtime/ka/other"})))))))

;; ===== store-api-key tests =====

(deftest test-store-api-key
  (testing "Store API key creates correct transaction"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :mock-db})
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 100
                                  [:access-policy/id "policy-id"] 101
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (let [ids (atom ["api-key-id" "policy-id" "dataset-scope-id"])]
                                      (fn []
                                        (let [id (first @ids)]
                                          (swap! ids (fn [v] (if (seq v) (subvec v 1) [])))
                                          (or id (str "extra-" (random-uuid))))))
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (let [result (api-keys/store-api-key mock-conn "rag_test123" "My Key" "user-1"
                                             {:dataset-scopes [{:tenant "ka"
                                                              :dataset-config-key "prod"}]})]

          (testing "returns api-key-id and api-key"
            (is (= "api-key-id" (:api-key-id result)))
            (is (= "rag_test123" (:api-key result))))

          (testing "transaction contains correct data"
            (let [tx-items (mapcat identity @transactions)
                  policy-tx (first (filter :access-policy/id tx-items))
                  api-key-tx (first (filter :api-key/id tx-items))]
              (is (= "policy-id" (:access-policy/id policy-tx)))
              (is (= "api-key-id" (:api-key/id api-key-tx)))
              (is (= (api-keys/api-key-digest "rag_test123")
                     (:api-key/key-digest api-key-tx)))
              (is (= "rag_test" (:api-key/prefix api-key-tx)))
              (is (= "t123" (:api-key/last-four api-key-tx)))
              (is (not (contains? api-key-tx :api-key/key)))
              (is (= [:access-policy/id "policy-id"] (:api-key/policy api-key-tx))))))))))

(deftest test-store-api-key-with-options
  (testing "Store API key with structured grants, custom scopes, and expiration"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :mock-db})
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 200
                                  [:access-policy/id "policy-id"] 201
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (let [ids (atom ["api-key-id" "policy-id" "dataset-scope-id" "agent-ref-id"])]
                                      (fn []
                                        (let [id (first @ids)]
                                          (swap! ids (fn [v] (if (seq v) (subvec v 1) [])))
                                          (or id (str "extra-" (random-uuid))))))
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (api-keys/store-api-key mock-conn "rag_test" "Key" "user"
                                {:dataset-scopes [{:tenant "altinn-docs"
                                                 :dataset-config-key "dev"}]
                                 :agent-refs ["builtin/agent-rag-agent"]
                                 :scopes #{:query :admin}
                                 :expires-at 1234567890})

        (let [tx-items (mapcat identity @transactions)
              policy-tx (first (filter :access-policy/id tx-items))
              api-key-tx (first (filter :api-key/id tx-items))]
          (testing "scopes are set correctly on policy"
            (is (some #{:query} (:access-policy/scopes policy-tx)))
            (is (some #{:admin} (:access-policy/scopes policy-tx))))

          (testing "API key links to policy"
            (is (= [:access-policy/id "policy-id"] (:api-key/policy api-key-tx))))

          (testing "expiration is set on API key"
            (is (= 1234567890 (:api-key/expires-at api-key-tx)))))))))

(deftest test-store-api-key-with-allowed-config-keys
  (testing "Store API key persists allowed config keys with resolved node IDs and tenant-config-keys"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :mock-db})
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 300
                                  [:access-policy/id "policy-id"] 301
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (let [ids (atom ["api-key-id" "policy-id" "dataset-scope-id" "ceiling-id"])]
                                      (fn []
                                        (let [id (first @ids)]
                                          (swap! ids (fn [v] (if (seq v) (subvec v 1) [])))
                                          (or id (str "extra-" (random-uuid))))))
                    audit/log-api-key-change! (fn [_ _] "audit-id")
                    digdir.config.db/get-conn (constantly nil)
                    digdir.config.db/get-config-node (fn [_db node-id]
                                                       (when (= node-id "runtime/ka/default")
                                                         {:config.node/id "runtime/ka/default"
                                                          :config.node/root :runtime
                                                          :config.node/tenant "ka"
                                                          :config.node/tenant-config-key "default"
                                                          :config.node/enabled? true}))
                    digdir.config.db/get-config-node-by-tenant-config-key (fn [_db tenant root tenant-config-key]
                                                               (when (= [tenant root tenant-config-key] ["ka" :runtime "default"])
                                                                 {:config.node/id "runtime/ka/default"
                                                                  :config.node/root :runtime
                                                                  :config.node/tenant "ka"
                                                                  :config.node/tenant-config-key "default"
                                                                  :config.node/enabled? true}))]
        (let [result (api-keys/store-api-key mock-conn "rag_cfg" "Scoped API Key" "user-1"
                                             {:dataset-scopes [{:tenant "ka"
                                                              :tenant-config-key "prod"}]
                                              :allowed-config-keys [{:root :runtime
                                                                 :tenant "ka"
                                                                 :tenant-config-key "default"}]})]
          (is (= "api-key-id" (:api-key-id result)))
          (let [tx-items (mapcat identity @transactions)
                ceiling (first (filter :api-key.allowed-config-key/id tx-items))
                api-key-tx (first (filter :api-key/id tx-items))]
            (is (= :runtime (:api-key.allowed-config-key/root ceiling)))
            (is (= "ka" (:api-key.allowed-config-key/tenant ceiling)))
            (is (= [:access-policy/id "policy-id"] (:api-key/policy api-key-tx)))))))))

(deftest test-store-api-key-with-allowed-config-keys-and-split-config-db
  (testing "Store API key resolves node ids from the config DB even when the main DB cannot hold config node refs"
    (let [transactions (atom [])
          mock-main-conn (atom :main-db)
          mock-config-conn (atom :config-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :main-db})
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 400
                                  [:access-policy/id "policy-id"] 401
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (let [ids (atom ["api-key-id" "policy-id" "dataset-scope-id" "ceiling-id"])]
                                      (fn []
                                        (let [id (first @ids)]
                                          (swap! ids (fn [v] (if (seq v) (subvec v 1) [])))
                                          (or id (str "extra-" (random-uuid))))))
                    audit/log-api-key-change! (fn [_ _] "audit-id")
                    digdir.config.db/get-conn (constantly mock-config-conn)
                    digdir.config.db/get-config-node (fn [db node-id]
                                                       (when (and (= db :config-db)
                                                                  (= node-id "runtime/public-sector-knowledge/default"))
                                                         {:config.node/id "runtime/public-sector-knowledge/default"
                                                          :config.node/root :runtime
                                                          :config.node/tenant "public-sector-knowledge"
                                                          :config.node/tenant-config-key "default"
                                                          :config.node/enabled? true}))
                    digdir.config.db/get-config-node-by-tenant-config-key (fn [db tenant root tenant-config-key]
                                                               (when (and (= db :config-db)
                                                                          (= [tenant root tenant-config-key]
                                                                             ["public-sector-knowledge" :runtime "default"]))
                                                                 {:config.node/id "runtime/public-sector-knowledge/default"
                                                                  :config.node/root :runtime
                                                                 :config.node/tenant "public-sector-knowledge"
                                                                  :config.node/tenant-config-key "default"
                                                                  :config.node/enabled? true}))]
        (api-keys/store-api-key mock-main-conn "rag_cfg_split" "Split Scoped API Key" "user-1"
                                {:dataset-scopes [{:tenant "public-sector-knowledge"
                                                 :tenant-config-key "prod"}]
                                 :allowed-config-keys [{:root :runtime
                                                    :tenant "public-sector-knowledge"
                                                    :tenant-config-key "default"}]})
        (let [tx-items (mapcat identity @transactions)
              ceiling (first (filter :api-key.allowed-config-key/id tx-items))]
          (is (= "runtime/public-sector-knowledge/default" (:api-key.allowed-config-key/node-id ceiling)))
          (is (= "default" (:api-key.allowed-config-key/tenant-config-key ceiling)))
          (is (nil? (:api-key.allowed-config-key/node ceiling))))))))

(deftest test-store-api-key-persists-cardinality-many-refs-in-real-db
  (testing "Store API key uses a transact shape that Datahike accepts for many-ref attrs"
    (let [cfg {:store {:backend :mem
                       :id (str "api-keys-main-test-" (random-uuid))}
               :schema-flexibility :read}
          conn (do (d/create-database cfg)
                   (d/connect cfg))
          config-cfg {:store {:backend :mem
                              :id (str "api-keys-config-test-" (random-uuid))}
                      :schema-flexibility :read}
          config-conn (do (d/create-database config-cfg)
                          (d/connect config-cfg))]
      (try
        (d/transact conn {:tx-data data-db/dh-schema})
        (d/transact config-conn {:tx-data config-schema/config-migration-schema})
        (config-db/set-conn! config-conn)
        (config-db/create-config-node! config-conn
                                       {:root :runtime
                                        :tenant "ka"
                                        :node-id "runtime/ka/default"
                                        :label "Default"
                                        :tenant-config-key "default"})
        (config-db/create-config-node! config-conn
                                       {:root :dataset
                                        :tenant "ka"
                                        :node-id "dataset/ka/default"
                                        :label "Dataset Default"
                                        :tenant-config-key "default"})
        (let [{:keys [api-key-id]} (api-keys/store-api-key conn
                                                           "rag_real_db_test"
                                                           "Real DB Test"
                                                           "user-1"
                                                           {:dataset-scopes [{:tenant "ka"
                                                                            :tenant-config-key "prod"}]
                                                            :allowed-config-keys [{:root :runtime
                                                                               :tenant "ka"
                                                                               :tenant-config-key "default"}
                                                                              {:root :dataset
                                                                               :tenant "ka"
                                                                               :tenant-config-key "default"}]})
              stored (api-keys/get-api-key-info conn api-key-id)]
          (let [raw (d/pull @conn '[:api-key/key :api-key/key-digest
                                    :api-key/prefix :api-key/last-four]
                            [:api-key/id api-key-id])]
            (is (nil? (:api-key/key raw)))
            (is (= (api-keys/api-key-digest "rag_real_db_test")
                   (:api-key/key-digest raw)))
            (is (= "rag_real" (:api-key/prefix raw)))
            (is (= "test" (:api-key/last-four raw))))
          (is (= api-key-id
                 (:api-key-id (api-keys/validate-api-key conn "rag_real_db_test"))))
          (is (not (contains? stored :api-key/key-digest)))
          (is (= 1 (count (:api-key/dataset-scopes stored))))
          (is (= 2 (count (:api-key/allowed-config-keys stored))))
          (is (= ["dataset/ka/default" "runtime/ka/default"]
                 (sort (map :api-key.allowed-config-key/node-id
                            (:api-key/allowed-config-keys stored))))))
        (finally
          (d/release conn)
          (d/delete-database cfg)
          (d/release config-conn)
          (d/delete-database config-cfg))))))

(deftest test-legacy-plaintext-key-migrates-after-successful-validation
  (let [cfg {:store {:backend :mem
                     :id (str "api-key-legacy-migration-test-" (random-uuid))}
             :schema-flexibility :read}
        conn (do (d/create-database cfg)
                 (d/connect cfg))
        plaintext "rag_legacy_plaintext"]
    (try
      (d/transact conn {:tx-data data-db/dh-schema})
      (d/transact conn {:tx-data [{:api-key/id "legacy-key"
                                   :api-key/key plaintext
                                   :api-key/name "Legacy"
                                   :api-key/created 1
                                   :api-key/created-by "user"
                                   :api-key/revoked false
                                   :api-key/scopes [:query]
                                   :api-key/usage-count 0}]})
      (is (= "legacy-key" (:api-key-id (api-keys/validate-api-key conn plaintext))))
      (let [raw (d/pull @conn '[:api-key/key :api-key/key-digest
                                :api-key/prefix :api-key/last-four]
                        [:api-key/id "legacy-key"])]
        (is (nil? (:api-key/key raw)))
        (is (= (api-keys/api-key-digest plaintext) (:api-key/key-digest raw)))
        (is (= "rag_lega" (:api-key/prefix raw)))
        (is (= "text" (:api-key/last-four raw))))
      (is (= "legacy-key" (:api-key-id (api-keys/validate-api-key conn plaintext))))
      (finally
        (d/release conn)
        (d/delete-database cfg)))))

(deftest test-batch-migration-removes-all-legacy-plaintext
  (let [cfg {:store {:backend :mem
                     :id (str "api-key-batch-migration-test-" (random-uuid))}
             :schema-flexibility :read}
        conn (do (d/create-database cfg)
                 (d/connect cfg))]
    (try
      (d/transact conn {:tx-data data-db/dh-schema})
      (d/transact conn {:tx-data [{:api-key/id "legacy-1" :api-key/key "rag_one"}
                                  {:api-key/id "legacy-2" :api-key/key "rag_two"}]})
      (is (= 2 (api-keys/migrate-legacy-api-key-storage! conn)))
      (is (= 0 (api-keys/migrate-legacy-api-key-storage! conn)))
      (is (empty? (d/q '[:find ?plaintext
                         :where [?e :api-key/key ?plaintext]] @conn)))
      (is (api-keys/api-key-stored? conn "rag_one"))
      (is (api-keys/api-key-stored? conn "rag_two"))
      (finally
        (d/release conn)
        (d/delete-database cfg)))))

(deftest test-query-api-key-dataset-scopes-ignores-invalid-ref-values
  (testing "Dataset scope loading skips malformed historic ref values instead of crashing"
    (with-redefs [d/q (fn [_ _ _] [#{-1} 42])
                  d/pull (fn [_ _ ref]
                           (when (= 42 ref)
                             {:api-key.dataset-scope/id "scope-42"
                              :api-key.dataset-scope/tenant "digdir"
                              :api-key.dataset-scope/dataset-config-key "public-docs"}))]
      (is (= [{:api-key.dataset-scope/id "scope-42"
               :api-key.dataset-scope/tenant "digdir"
               :api-key.dataset-scope/dataset-config-key "public-docs"}]
             (#'digdir.config.api-keys/query-api-key-dataset-scopes :db "key-1"))))))

(deftest test-store-api-key-creates-audit-log
  (testing "Store API key creates audit log entry"
    (let [audit-calls (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ _] nil)
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 500
                                  [:access-policy/id "policy-id"] 501
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (let [ids (atom ["api-key-id" "policy-id" "dataset-scope-id"])]
                                      (fn []
                                        (let [id (first @ids)]
                                          (swap! ids (fn [v] (if (seq v) (subvec v 1) [])))
                                          (or id (str "extra-" (random-uuid))))))
                    audit/log-api-key-change! (fn [_ opts]
                                                (swap! audit-calls conj opts)
                                                "audit-id")]
        (api-keys/store-api-key mock-conn "rag_test" "Key" "user"
                                {:dataset-scopes [{:tenant "ka"
                                                 :tenant-config-key "prod"}]
                                 :user-email "test@example.com"})
        (is (= 1 (count @audit-calls)))
        (let [audit-opts (first @audit-calls)]
          (is (= :create (:action audit-opts)))
          (is (= "api-key-id" (:api-key-id audit-opts)))
          (is (= "Key" (:api-key-name audit-opts)))
          (is (= "policy-id" (:policy-id audit-opts)))
          (is (= "test@example.com" (:user-email audit-opts))))))))

(deftest test-store-api-key-without-pipelines-creates-audit-log
  (testing "Store API key without grants does not pass a policy id to audit logging"
    (let [audit-calls (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ _] nil)
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:api-key/id "api-key-id"] 600
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (fn [] "api-key-id")
                    audit/log-api-key-change! (fn [_ opts]
                                                (swap! audit-calls conj opts)
                                                "audit-id")]
        (api-keys/store-api-key mock-conn "rag_test" "Key" "user"
                                {:user-email "test@example.com"})
        (is (= 1 (count @audit-calls)))
        (let [audit-opts (first @audit-calls)]
          (is (= :create (:action audit-opts)))
          (is (= "api-key-id" (:api-key-id audit-opts)))
          (is (= "Key" (:api-key-name audit-opts)))
          (is (nil? (:policy-id audit-opts)))
          (is (= "test@example.com" (:user-email audit-opts))))))))

;; ===== revoke-api-key tests =====

(deftest test-revoke-api-key-success
  (testing "Revoke existing API key returns true"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/pull (fn [_ _ _] {:api-key/id "key-123"
                                        :api-key/name "Test Key"
                                        :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                                                :api-key.dataset-scope/dataset-config-key "prod"}]})
                    d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data))
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (let [result (api-keys/revoke-api-key mock-conn "key-123")]
          (is (true? result))

          (testing "transaction sets revoked to true"
            (let [tx-data (first (first @transactions))]
              (is (= "key-123" (:api-key/id tx-data)))
              (is (true? (:api-key/revoked tx-data))))))))))

(deftest test-revoke-api-key-not-found
  (testing "Revoke non-existent API key returns nil"
    (let [mock-conn (atom :mock-db)]
      (with-redefs [d/pull (fn [_ _ _] nil)]
        (is (nil? (api-keys/revoke-api-key mock-conn "nonexistent")))))))

(deftest test-revoke-api-key-creates-audit-log
  (testing "Revoke API key creates audit log entry"
    (let [audit-calls (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/pull (fn [_ _ _] {:api-key/id "key-123"
                                        :api-key/name "Test Key"
                                        :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                                                :api-key.dataset-scope/dataset-config-key "prod"}]})
                    d/transact (fn [_ _] nil)
                    audit/log-api-key-change! (fn [_ opts]
                                                (swap! audit-calls conj opts)
                                                "audit-id")]
        (api-keys/revoke-api-key mock-conn "key-123"
                                 {:user-email "test@example.com" :user-id "user-1"})
        (is (= 1 (count @audit-calls)))
        (let [audit-opts (first @audit-calls)]
          (is (= :revoke (:action audit-opts)))
          (is (= "key-123" (:api-key-id audit-opts)))
          (is (= "Test Key" (:api-key-name audit-opts)))
          (is (= [{:tenant "ka"
                   :dataset-config-key "prod"}]
                 (:dataset-scopes audit-opts)))
          (is (= [] (:agent-refs audit-opts)))
          (is (= "test@example.com" (:user-email audit-opts)))
          (is (= "user-1" (:user-id audit-opts))))))))

;; ===== list-api-keys tests =====

(deftest test-list-api-keys
  (testing "List API keys returns keys for user"
    (let [mock-keys [{:api-key/id "key-1" :api-key/name "Key 1"
                      :api-key/key "legacy-secret"
                      :api-key/key-digest "lookup-digest"
                      :api-key/prefix "rag_abcd"
                      :api-key/last-four "1234"}
                     {:api-key/id "key-2" :api-key/name "Key 2"}]
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ user-id]
                          (when (= user-id "user-123")
                            mock-keys))]
        (let [result (api-keys/list-api-keys mock-conn "user-123")]
          (is (= 2 (count result)))
          (is (= "key-1" (:api-key/id (first result))))
          (is (= "rag_abcd" (:api-key/prefix (first result))))
          (is (not (contains? (first result) :api-key/key)))
          (is (not (contains? (first result) :api-key/key-digest))))))))

;; ===== get-api-key-info tests =====

(deftest test-get-api-key-info
  (testing "Get API key info returns key details"
    (let [mock-info {:api-key/id "key-123"
                     :api-key/name "Test Key"
                     :api-key/key "legacy-secret"
                     :api-key/key-digest "lookup-digest"
                     :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "ka"
                                             :api-key.dataset-scope/dataset-config-key "prod"}]
                     :api-key/created-by "user-1"}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ key-id]
                          (when (= key-id "key-123")
                            mock-info))]
        (let [result (api-keys/get-api-key-info mock-conn "key-123")]
          (is (= "key-123" (:api-key/id result)))
          (is (= "Test Key" (:api-key/name result)))
          (is (not (contains? result :api-key/key)))
          (is (not (contains? result :api-key/key-digest)))
          (is (= [{:tenant "ka"
                   :dataset-config-key "prod"}]
                 (:api-key/dataset-scopes result))))))))

;; ===== valid-scopes tests =====

(deftest test-valid-scopes
  (testing "Valid scopes constant"
    (is (contains? api-keys/valid-scopes :query))
    (is (contains? api-keys/valid-scopes :ingest))
    (is (contains? api-keys/valid-scopes :admin))
    (is (= 3 (count api-keys/valid-scopes)))))

;; ===== access-policy tests =====

(deftest test-create-access-policy
  (testing "Create access policy creates correct transaction"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :mock-db})
                    d/q (fn [_ _ & args]
                          (let [[attr value] args]
                            (or (case [attr value]
                                  [:access-policy/id "policy-id"] 700
                                  nil)
                                (when (string? value) (hash value)))))
                    nano-id/nano-id (fn [] "policy-id")]
        (let [result (api-keys/create-access-policy! mock-conn "My Policy" "user-1"
                                                     {:dataset-scopes [{:tenant "ka"
                                                                      :dataset-config-key "prod"}]})]
          (is (= "policy-id" (:access-policy/id result)))
          (let [tx-items (mapcat identity @transactions)
                policy-tx (first (filter :access-policy/id tx-items))]
            (is (= "My Policy" (:access-policy/name policy-tx)))
            (is (= "user-1" (:access-policy/created-by policy-tx)))))))))

(deftest test-store-api-key-with-policy-id
  (testing "Store API key linked to an existing policy"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data)
                                 {:db-after :mock-db})
                    nano-id/nano-id (fn [] "new-key-id")
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (let [result (api-keys/store-api-key mock-conn "rag_linked" "Linked Key" "user-1"
                                             {:policy-id "existing-policy-id"})]
          (is (= "new-key-id" (:api-key-id result)))
          (let [tx-data (first (first @transactions))]
            (is (= [:access-policy/id "existing-policy-id"] (:api-key/policy tx-data)))))))))

(deftest test-rotate-api-key
  (testing "Rotate API key creates new key and revokes old one"
    (let [_transactions (atom [])
          mock-conn (atom :mock-db)
          mock-old-key {:api-key/id "old-key-id"
                        :api-key/name "My Key"
                        :api-key/policy {:access-policy/id "policy-123"}
                        :api-key/created-by "user-1"}]
      (with-redefs [api-keys/pull-api-key-entity (fn [_ id] (when (= id "old-key-id") mock-old-key))
                    api-keys/generate-api-key (fn [] "rag_new_rotated")
                    api-keys/store-api-key (fn [_ key _name _user-id opts]
                                             (is (= key "rag_new_rotated"))
                                             (is (= (:policy-id opts) "policy-123"))
                                             {:api-key-id "new-key-id" :api-key key})
                    api-keys/revoke-api-key (fn [_ id _opts]
                                              (is (= id "old-key-id"))
                                              true)]
        (let [result (api-keys/rotate-api-key! mock-conn "old-key-id" {:user-id "user-1"})]
          (is (= "new-key-id" (:api-key-id result)))
          (is (= "rag_new_rotated" (:api-key result))))))))

(deftest test-normalize-key-entity-merges-policy
  (testing "Normalization merges policy data into API key entity"
    (let [policy {:access-policy/id "p1"
                  :access-policy/dataset-scopes [{:tenant "policy-tenant" :dataset-config-key "default"}]
                  :access-policy/scopes [:admin]}
          key-entity {:api-key/id "k1"
                      :api-key/policy policy
                      :api-key/dataset-scopes [{:api-key.dataset-scope/tenant "key-tenant" 
                                                :api-key.dataset-scope/dataset-config-key "default"}]
                      :api-key/scopes [:query]}
          normalized (#'api-keys/normalize-key-entity key-entity)]
      (testing "merges dataset-scopes"
        (is (= 2 (count (:api-key/dataset-scopes normalized))))
        (is (some #(= (:tenant %) "policy-tenant") (:api-key/dataset-scopes normalized)))
        (is (some #(= (:tenant %) "key-tenant") (:api-key/dataset-scopes normalized))))
      (testing "merges scopes"
        (is (= 2 (count (:api-key/scopes normalized))))
        (is (some #{:admin} (:api-key/scopes normalized)))
        (is (some #{:query} (:api-key/scopes normalized)))))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'digdir.config.api-keys-test))
