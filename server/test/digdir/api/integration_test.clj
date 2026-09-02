(ns digdir.api.integration-test
  "Integration tests for /api endpoints against deployed environments.

   Requires environment variables:
   - RAG_API_BASE_URL: Base URL of the API (e.g., https://admin.staging.kunnskap.digdir.cloud)
   - RAG_API_TEST_KEY: Valid API key for the target environment

   Run with: bb integration-test <environment>"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clj-http.client :as http]
            [cheshire.core :as json]))

;; === Configuration ===

(def base-url (System/getenv "RAG_API_BASE_URL"))
(def api-key (System/getenv "RAG_API_TEST_KEY"))
(def external-user-id "integration-test-user")

(def ^:private integration-tests-configured?
  "Flag indicating whether integration tests can run"
  (and (seq base-url)
       (seq api-key)))

(defn skip-unless-configured
  "Prints skip message and returns true if tests should be skipped"
  []
  (when-not integration-tests-configured?
    (println "Skipping integration tests: RAG_API_BASE_URL or RAG_API_TEST_KEY not set")
    true))

;; === Fixtures ===

(defn configuration-check-fixture [f]
  ;; Only run the test if configured, otherwise just return without running
  (when integration-tests-configured?
    (f)))

(use-fixtures :once configuration-check-fixture)

;; === Helper Functions ===

(defn api-url [path]
  (str base-url path))

(defn api-request [method path & [opts]]
  (http/request
   (merge {:method method
           :url (api-url path)
           :headers {"X-API-Key" api-key
                     "Content-Type" "application/json"
                     "X-User-Id" external-user-id}
           :throw-exceptions false
           :as :json}
          opts)))

;; === RAG Endpoint Tests ===

(deftest test-rag-endpoint-success
  (testing "RAG endpoint returns answer for valid query"
    (let [response (api-request :post "/api/rag"
                                {:body (json/generate-string
                                        {:query "Hva er Altinn?"
                                         :context-top-k 3
                                         :max-context-length 4000})})]
      (is (= 200 (:status response))
          (str "Expected 200, got " (:status response) " - " (:body response)))
      (let [body (:body response)]
        (is (string? (:answer body)) "Response should contain an answer string")
        (is (string? (:conversation-id body)) "Response should contain a conversation-id")
        (is (string? (:model body)) "Response should contain a model name")))))

(deftest test-rag-endpoint-missing-query
  (testing "RAG endpoint returns 400 for missing query"
    (let [response (api-request :post "/api/rag"
                                {:body (json/generate-string {:model "gpt-4o"})})]
      (is (= 400 (:status response))
          (str "Expected 400 for missing query, got " (:status response))))))

;; === Conversations Endpoint Tests ===

(deftest test-list-conversations
  (testing "List conversations returns paginated results"
    (let [response (api-request :get "/api/conversations")]
      (is (= 200 (:status response))
          (str "Expected 200, got " (:status response) " - " (:body response)))
      (let [body (:body response)]
        (is (vector? (:conversations body)) "Response should contain conversations vector")
        (is (number? (:total body)) "Response should contain total count")))))

(deftest test-get-conversation-not-found
  (testing "Get non-existent conversation returns 404"
    (let [response (api-request :get "/api/conversations/nonexistent-id-12345")]
      (is (= 404 (:status response))
          (str "Expected 404 for non-existent conversation, got " (:status response))))))

;; === Retrieve Endpoint Tests ===

(deftest test-retrieve-endpoint-success
  (testing "Retrieve endpoint returns ranked chunks for valid query"
    (let [response (api-request :post "/api/retrieve"
                                {:body (json/generate-string
                                        {:query "Hva er Altinn?"
                                         :top_k 5})})]
      (is (= 200 (:status response))
          (str "Expected 200, got " (:status response) " - " (:body response)))
      (let [body (:body response)]
        (is (vector? (:chunks body)) "Response should contain chunks vector")
        (is (map? (:query_expansion body)) "Response should contain query_expansion")
        (is (map? (:search_stats body)) "Response should contain search_stats")
        ;; Verify chunk structure
        (when (seq (:chunks body))
          (let [chunk (first (:chunks body))]
            (is (string? (:chunk_id chunk)) "Chunk should have chunk_id")
            (is (string? (:content_markdown chunk)) "Chunk should have content_markdown")
            (is (map? (:document chunk)) "Chunk should have document info")
            (is (map? (:relevance chunk)) "Chunk should have relevance info")))))))

(deftest test-retrieve-endpoint-missing-query
  (testing "Retrieve endpoint returns 400 for missing query"
    (let [response (api-request :post "/api/retrieve"
                                {:body (json/generate-string {:top_k 5})})]
      (is (= 400 (:status response))
          (str "Expected 400 for missing query, got " (:status response))))))

(deftest test-retrieve-endpoint-with-filter
  (testing "Retrieve endpoint accepts filter parameter"
    (let [response (api-request :post "/api/retrieve"
                                {:body (json/generate-string
                                        {:query "tilgjengelighet"
                                         :top_k 3
                                         :filter {:fields [{:field "owner_short"
                                                            :selected_options ["Digdir"]
                                                            :value_type "string"}]}})})]
      (is (#{200 500} (:status response))
          (str "Expected 200 or 500 (if filter field doesn't exist), got " (:status response))))))

(deftest test-retrieve-endpoint-disable-query-expansion
  (testing "Retrieve endpoint respects include_query_expansion=false"
    (let [response (api-request :post "/api/retrieve"
                                {:body (json/generate-string
                                        {:query "Altinn"
                                         :include_query_expansion false
                                         :top_k 3})})]
      (is (= 200 (:status response))
          (str "Expected 200, got " (:status response) " - " (:body response)))
      (let [body (:body response)]
        (is (false? (get-in body [:query_expansion :enabled]))
            "query_expansion.enabled should be false")))))

;; Note: We don't test delete to avoid destroying real data
