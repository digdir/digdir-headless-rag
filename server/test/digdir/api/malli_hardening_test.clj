(ns digdir.api.malli-hardening-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [digdir.api.routes :as routes]))

(defn- api-request [method uri body-input & [query-params]]
  {:request-method method
   :uri uri
   :path-info uri
   :headers {"content-type" "application/json"
             "x-api-key" "mock-key"}
   :body body-input
   :query-params query-params
   :params query-params})

;; Two middleware tests that previously exercised /api/rag were removed in
;; Phase 0 along with that route. The malli-coercion path is still
;; covered indirectly by test-include-diagnostics-boolean-coercion below
;; and the conversation/dataset body schema tests elsewhere.

(deftest test-include-diagnostics-boolean-coercion
  (testing "include_diagnostics is strictly coerced to boolean"
    (let [app routes/api-router]
      (testing "valid true string"
        (let [request (api-request :get "/api/conversations/123" nil {"include_diagnostics" "true"})
              response (app request)]
          (is (not= 400 (:status response)))))
      
      (testing "invalid non-boolean string '1' is rejected"
        (let [request (api-request :get "/api/conversations/123" nil {"include_diagnostics" "1"})
              response (app request)
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body))))))))

(deftest test-pagination-integer-coercion
  (testing "pagination parameters are coerced to integers"
    (let [app routes/api-router]
      (testing "valid integers"
        (let [request (api-request :get "/api/conversations" nil {"page_size" "10" "page_index" "2"})
              response (app request)]
          (is (not= 400 (:status response)))))
      
      (testing "invalid string is rejected"
        (let [request (api-request :get "/api/conversations" nil {"page_size" "ten"})
              response (app request)
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body))))))))
