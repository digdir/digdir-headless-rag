(ns digdir.api.routes-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [digdir.api.routes :as routes]
            [digdir.config.accessor :as cfg]
            [digdir.config.api-keys :as api-keys]
            [digdir.data.db :as db]
            [digdir.rag.core :as rag]
            [digdir.test-utils :as tu]))

;; ===== Helper functions =====

(defn json-body
  "Create an input stream from a map for request body"
  [data]
  (io/input-stream (.getBytes (json/generate-string data) "UTF-8")))

;; ===== get-entity-by-id tests =====

(deftest test-get-entity-by-id-found
  (testing "Returns entity when found"
    (with-redefs [cfg/get-entity (fn [id]
                                    (case id
                                      "entity-1" {:id "entity-1" :name "Test Entity"}
                                      "entity-2" {:id "entity-2" :name "Other Entity"}
                                      nil))]
      (let [result (routes/get-entity-by-id "entity-1")]
        (is (= "entity-1" (:id result)))
        (is (= "Test Entity" (:name result)))))))

(deftest test-get-entity-by-id-not-found
  (testing "Returns nil when entity not found"
    (with-redefs [cfg/get-entity (fn [id]
                                    (when (= id "entity-1")
                                      {:id "entity-1" :name "Test Entity"}))]
      (is (nil? (routes/get-entity-by-id "nonexistent"))))))

;; ===== wrap-api-key-auth tests =====

(deftest test-wrap-api-key-auth-valid
  (testing "Valid API key passes through with entity-id"
    (let [handler-called (atom false)
          handler (fn [req]
                    (reset! handler-called true)
                    (is (= "entity-abc" (:api-key/entity-id req)))
                    {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {"x-api-key" "rag_valid123"}}]

      (with-redefs [api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:entity-id "entity-abc"}))]
        (let [response (wrapped request)]
          (is @handler-called)
          (is (= 200 (:status response))))))))

(deftest test-wrap-api-key-auth-invalid
  (testing "Invalid API key returns 401"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {"x-api-key" "rag_invalid"}}]

      (with-redefs [api-keys/validate-api-key (fn [_ _] nil)]
        (let [response (wrapped request)]
          (is (= 401 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"]))))))))

(deftest test-wrap-api-key-auth-missing
  (testing "Missing API key returns 401"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {}}]

      (with-redefs [api-keys/validate-api-key (fn [_ _] nil)]
        (let [response (wrapped request)]
          (is (= 401 (:status response))))))))

;; ===== api-rag-handler tests =====

(deftest test-api-rag-handler-missing-query
  (testing "Missing query returns 400"
    (let [request {:api-key/entity-id "entity-1"
                   :body (json-body {:model "gpt-4"})}]
      (let [response (routes/api-rag-handler request)]
        (is (= 400 (:status response)))
        (let [body (json/parse-string (:body response) true)]
          (is (re-find #"query" (:error body))))))))

(deftest test-api-rag-handler-entity-not-found
  (testing "Non-existent entity returns 404"
    (with-redefs [cfg/get-entity (fn [_] nil)]
      (let [request {:api-key/entity-id "nonexistent"
                     :body (json-body {:query "test question"})}]
        (let [response (routes/api-rag-handler request)]
          (is (= 404 (:status response))))))))

(deftest test-api-rag-handler-success
  (testing "Successful RAG request returns answer"
    (let [mock-entity {:id "entity-1"
                       :docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :promptRagQueryRelax "relax prompt"
                       :promptRagGenerate "generate prompt"}
          mock-result {:english_answer "This is the answer"
                       :chunks [{:chunk_id "chunk-1"
                                 :doc_num 123
                                 :content_markdown "Content"}]}]

      (with-redefs [cfg/get-entity (fn [id] (when (= id "entity-1") mock-entity))
                    db/get-conn (fn [] (atom :mock-conn))
                    rag/rag-pipeline (fn [_ _] mock-result)]
        (let [request {:api-key/entity-id "entity-1"
                       :body (json-body {:query "What is the capital?"})}
              response (routes/api-rag-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= "This is the answer" (:answer body)))
            (is (string? (:conversation-id body)))
            (is (= "gpt-4" (:model body)))))))))

;; ===== API Key Handler tests =====

(deftest test-create-api-key-handler-missing-name
  (testing "Missing name returns 400"
    (let [request {:user/id "user-123"
                   :body (json-body {:entity-id "entity-1"})}]
      (let [response (routes/create-api-key-handler request)]
        (is (= 400 (:status response)))))))

(deftest test-create-api-key-handler-missing-entity-id
  (testing "Missing entity-id returns 400"
    (let [request {:user/id "user-123"
                   :body (json-body {:name "My API Key"})}]
      (let [response (routes/create-api-key-handler request)]
        (is (= 400 (:status response)))))))

(deftest test-create-api-key-handler-entity-not-found
  (testing "Non-existent entity returns 404"
    (with-redefs [cfg/get-entity (fn [_] nil)]
      (let [request {:user/id "user-123"
                     :body (json-body {:name "My Key" :entity-id "nonexistent"})}]
        (let [response (routes/create-api-key-handler request)]
          (is (= 404 (:status response))))))))

(deftest test-create-api-key-handler-success
  (testing "Successful API key creation returns key"
    (with-redefs [cfg/get-entity (fn [id] (when (= id "entity-1") {:id "entity-1"}))
                  api-keys/generate-api-key (fn [] "rag_newkey123")
                  api-keys/store-api-key (fn [_ key name _created-by _opts]
                                           {:api-key-id "key-id-123"
                                            :api-key key})]
      (let [request {:user/id "user-123"
                     :body (json-body {:name "My Key" :entity-id "entity-1"})}
            response (routes/create-api-key-handler request)]
        (is (= 201 (:status response)))
        (let [body (json/parse-string (:body response) true)]
          (is (= "key-id-123" (:api-key-id body)))
          (is (= "rag_newkey123" (:api-key body)))
          (is (some? (:warning body))))))))

(deftest test-list-api-keys-handler-success
  (testing "List API keys returns user's keys"
    (let [mock-keys [{:api-key/id "key-1" :api-key/name "Key 1"}
                     {:api-key/id "key-2" :api-key/name "Key 2"}]]
      (with-redefs [api-keys/list-api-keys (fn [_ user-id]
                                             (when (= user-id "user-123")
                                               mock-keys))]
        (let [request {:user/id "user-123"}
              response (routes/list-api-keys-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= 2 (count (:api-keys body))))))))))

(deftest test-revoke-api-key-handler-not-found
  (testing "Revoke non-existent key returns 404"
    (with-redefs [api-keys/get-api-key-info (fn [_ _] nil)]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "nonexistent"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-revoke-api-key-handler-unauthorized
  (testing "Revoke key owned by another user returns 403"
    (with-redefs [api-keys/get-api-key-info (fn [_ _]
                                              {:api-key/id "key-123"
                                               :api-key/created-by "other-user"})]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "key-123"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 403 (:status response)))))))

(deftest test-revoke-api-key-handler-success
  (testing "Successful revocation returns success"
    (with-redefs [api-keys/get-api-key-info (fn [_ _]
                                              {:api-key/id "key-123"
                                               :api-key/created-by "user-123"})
                  api-keys/revoke-api-key (fn [_ _] true)]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "key-123"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 200 (:status response)))
        (let [body (json/parse-string (:body response) true)]
          (is (true? (:success body))))))))

;; ===== Conversation Handler tests =====

(deftest test-list-conversations-handler-success
  (testing "List conversations returns paginated results"
    (let [mock-result {:conversations [{:conversation/id "conv-1"
                                        :conversation/topic "Topic 1"
                                        :conversation/entity-id "entity-1"}]
                       :total 1
                       :page-size 50
                       :page-index 0}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (fn [_ _ _] mock-result)]
        (let [request {:params {}}
              response (routes/list-conversations-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= 1 (count (:conversations body))))
            (is (= 1 (:total body)))))))))

(deftest test-get-conversation-handler-not-found
  (testing "Get non-existent conversation returns 404"
    (with-redefs [db/get-conn (fn [] (atom {:db true}))
                  db/conversation-by-id (fn [_ _] nil)]
      (let [request {:path-params {:id "nonexistent"}}
            response (routes/get-conversation-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-get-conversation-handler-success
  (testing "Get existing conversation returns conversation with messages"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/entity-id "entity-1"
                     :conversation/user-id "user-1"
                     :conversation/created 1234567890}
          mock-messages [{:message/id "msg-1"
                          :message/text "Hello"
                          :message/role :user
                          :message/created 1234567891}]]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _] mock-messages)]
        (let [request {:path-params {:id "conv-1"}}
              response (routes/get-conversation-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= "conv-1" (get-in body [:conversation :id])))
            (is (= 1 (count (:messages body))))))))))

(deftest test-delete-conversation-handler-not-found
  (testing "Delete non-existent conversation returns 404"
    (with-redefs [db/get-conn (fn [] (atom {:db true}))
                  db/conversation-by-id (fn [_ _] nil)]
      (let [request {:path-params {:id "nonexistent"}}
            response (routes/delete-conversation-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-delete-conversation-handler-success
  (testing "Delete existing conversation returns success"
    (let [mock-conv {:db/id 123
                     :conversation/id "conv-1"}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/delete-convo (fn [_ _] nil)]
        (let [request {:path-params {:id "conv-1"}}
              response (routes/delete-conversation-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (true? (:success body)))))))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'digdir.api.routes-test))
