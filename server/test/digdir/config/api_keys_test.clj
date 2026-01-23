(ns digdir.config.api-keys-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [datahike.api :as d]
            [nano-id.core :as nano-id]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.audit :as audit]))

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

;; ===== validate-api-key tests =====

(deftest test-validate-api-key-valid
  (testing "Valid API key returns key info"
    (let [mock-key-entity {:api-key/id "key-123"
                           :api-key/entity-id "entity-abc"
                           :api-key/name "Test Key"
                           :api-key/revoked false
                           :api-key/scopes [:query]
                           :api-key/usage-count 5}
          transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ api-key]
                          (when (= api-key "rag_valid123")
                            mock-key-entity))
                    d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data))]
        (let [result (api-keys/validate-api-key mock-conn "rag_valid123")]
          (testing "returns entity-id"
            (is (= "entity-abc" (:entity-id result))))

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
                           :api-key/entity-id "entity-abc"
                           :api-key/revoked true}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ _] mock-key-entity)]
        (is (nil? (api-keys/validate-api-key mock-conn "rag_revoked")))))))

(deftest test-validate-api-key-expired
  (testing "Expired API key returns nil"
    (let [past-time (- (System/currentTimeMillis) 100000)
          mock-key-entity {:api-key/id "key-123"
                           :api-key/entity-id "entity-abc"
                           :api-key/revoked false
                           :api-key/expires-at past-time}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ _] mock-key-entity)]
        (is (nil? (api-keys/validate-api-key mock-conn "rag_expired")))))))

;; ===== store-api-key tests =====

(deftest test-store-api-key
  (testing "Store API key creates correct transaction"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data))
                    nano-id/nano-id (fn [] "fixed-nano-id")
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (let [result (api-keys/store-api-key mock-conn "rag_test123" "My Key" "user-1"
                                             {:entities ["entity-1"]})]

          (testing "returns api-key-id and api-key"
            (is (= "fixed-nano-id" (:api-key-id result)))
            (is (= "rag_test123" (:api-key result))))

          (testing "transaction contains correct data"
            (let [tx-data (first (first @transactions))]
              (is (= "fixed-nano-id" (:api-key/id tx-data)))
              (is (= "rag_test123" (:api-key/key tx-data)))
              (is (= "My Key" (:api-key/name tx-data)))
              (is (= ["entity-1"] (:api-key/entities tx-data)))
              (is (= "user-1" (:api-key/created-by tx-data)))
              (is (false? (:api-key/revoked tx-data)))
              (is (= [:query] (:api-key/scopes tx-data)))
              (is (= 0 (:api-key/usage-count tx-data)))
              (is (number? (:api-key/created tx-data))))))))))

(deftest test-store-api-key-with-options
  (testing "Store API key with custom scopes and expiration"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ {:keys [tx-data]}]
                                 (swap! transactions conj tx-data))
                    nano-id/nano-id (fn [] "nano-id-2")
                    audit/log-api-key-change! (fn [_ _] "audit-id")]
        (api-keys/store-api-key mock-conn "rag_test" "Key" "user"
                                {:entities ["entity"]
                                 :scopes #{:query :admin}
                                 :expires-at 1234567890})

        (let [tx-data (first (first @transactions))]
          (testing "scopes are set correctly"
            (is (some #{:query} (:api-key/scopes tx-data)))
            (is (some #{:admin} (:api-key/scopes tx-data))))

          (testing "expiration is set"
            (is (= 1234567890 (:api-key/expires-at tx-data)))))))))

(deftest test-store-api-key-creates-audit-log
  (testing "Store API key creates audit log entry"
    (let [audit-calls (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/transact (fn [_ _] nil)
                    nano-id/nano-id (fn [] "fixed-id")
                    audit/log-api-key-change! (fn [_ opts]
                                                (swap! audit-calls conj opts)
                                                "audit-id")]
        (api-keys/store-api-key mock-conn "rag_test" "Key" "user"
                                {:entities ["entity"]
                                 :user-email "test@example.com"})
        (is (= 1 (count @audit-calls)))
        (let [audit-opts (first @audit-calls)]
          (is (= :create (:action audit-opts)))
          (is (= "fixed-id" (:api-key-id audit-opts)))
          (is (= "Key" (:api-key-name audit-opts)))
          (is (= ["entity"] (:entities audit-opts)))
          (is (= "test@example.com" (:user-email audit-opts))))))))

;; ===== revoke-api-key tests =====

(deftest test-revoke-api-key-success
  (testing "Revoke existing API key returns true"
    (let [transactions (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/entity (fn [_ _] {:api-key/id "key-123"
                                         :api-key/name "Test Key"
                                         :api-key/entity-id "entity-1"})
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
      (with-redefs [d/entity (fn [_ _] nil)]
        (is (nil? (api-keys/revoke-api-key mock-conn "nonexistent")))))))

(deftest test-revoke-api-key-creates-audit-log
  (testing "Revoke API key creates audit log entry"
    (let [audit-calls (atom [])
          mock-conn (atom :mock-db)]
      (with-redefs [d/entity (fn [_ _] {:api-key/id "key-123"
                                         :api-key/name "Test Key"
                                         :api-key/entity-id "entity-1"})
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
          (is (= "test@example.com" (:user-email audit-opts)))
          (is (= "user-1" (:user-id audit-opts))))))))

;; ===== list-api-keys tests =====

(deftest test-list-api-keys
  (testing "List API keys returns keys for user"
    (let [mock-keys [{:api-key/id "key-1" :api-key/name "Key 1"}
                     {:api-key/id "key-2" :api-key/name "Key 2"}]
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ user-id]
                          (when (= user-id "user-123")
                            mock-keys))]
        (let [result (api-keys/list-api-keys mock-conn "user-123")]
          (is (= 2 (count result)))
          (is (= "key-1" (:api-key/id (first result)))))))))

;; ===== get-api-key-info tests =====

(deftest test-get-api-key-info
  (testing "Get API key info returns key details"
    (let [mock-info {:api-key/id "key-123"
                     :api-key/name "Test Key"
                     :api-key/entity-id "entity-1"
                     :api-key/created-by "user-1"}
          mock-conn (atom :mock-db)]
      (with-redefs [d/q (fn [_ _ key-id]
                          (when (= key-id "key-123")
                            mock-info))]
        (let [result (api-keys/get-api-key-info mock-conn "key-123")]
          (is (= "key-123" (:api-key/id result)))
          (is (= "Test Key" (:api-key/name result))))))))

;; ===== valid-scopes tests =====

(deftest test-valid-scopes
  (testing "Valid scopes constant"
    (is (contains? api-keys/valid-scopes :query))
    (is (contains? api-keys/valid-scopes :ingest))
    (is (contains? api-keys/valid-scopes :admin))
    (is (= 3 (count api-keys/valid-scopes)))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'digdir.config.api-keys-test))
