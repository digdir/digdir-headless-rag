(ns digdir.rag.skills.core-test
  "Tests for skill core protocol and abstractions"
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.rag.skills.core :as skill-core]))

(deftest test-skill-metadata-validation
  (testing "Valid skill metadata passes validation"
    (let [metadata {:skill-id :query-expansion/llm-v1
                    :name "LLM Query Expansion v1"
                    :description "Expands user query using LLM"
                    :category :query-transformation
                    :inputs [:user-query :conversation-history]
                    :outputs [:search-phrases :confidence]
                    :parameters {:prompt :string
                                 :model :string
                                 :max-phrases :number}
                    :required-services #{:azure-openai}}]
      (is (skill-core/valid-skill-metadata? metadata))
      (is (= metadata (skill-core/validate-skill-metadata! metadata)))))

  (testing "Invalid skill metadata fails validation"
    (let [invalid-metadata {:skill-id :invalid  ; Missing namespace
                            :name "Test"
                            :category :query-transformation}]
      (is (not (skill-core/valid-skill-metadata? invalid-metadata)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (skill-core/validate-skill-metadata! invalid-metadata)))))

  (testing "Missing required fields fails validation"
    (let [incomplete-metadata {:skill-id :test/skill
                               :name "Test"}]
      (is (not (skill-core/valid-skill-metadata? incomplete-metadata))))))

(deftest test-execution-context-validation
  (testing "Valid execution context passes validation"
    (let [ctx (skill-core/make-execution-context
                :query-expansion/llm-v1
                {:user-query "test" :conversation-history []}
                {:prompt "..." :model "gpt-4o"}
                {:azure-openai {:client "..."}}
                {:tenant "ka" :environment "prod"}
                {:execution-id "exec-123"})]
      (is (skill-core/valid-execution-context? ctx))
      (is (= ctx (skill-core/validate-execution-context! ctx)))))

  (testing "Context without required fields fails validation"
    (let [invalid-ctx {:skill-id :test/skill}]
      (is (not (skill-core/valid-execution-context? invalid-ctx)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (skill-core/validate-execution-context! invalid-ctx))))))

(deftest test-result-helpers
  (testing "Success result creation and checking"
    (let [result (skill-core/success-result
                   {:search-phrases ["query1" "query2"]
                    :confidence 0.95}
                   {:duration-ms 250
                    :model-used "gpt-4o"})]
      (is (skill-core/result-success? result))
      (is (not (skill-core/result-error? result)))
      (is (= {:search-phrases ["query1" "query2"]
              :confidence 0.95}
             (skill-core/get-result-outputs result)))
      (is (nil? (skill-core/get-result-error result)))
      (is (= 250 (:duration-ms (skill-core/get-result-metadata result))))))

  (testing "Error result creation and checking"
    (let [result (skill-core/error-result
                   :api-timeout
                   "Request timed out"
                   {:timeout-ms 5000})]
      (is (skill-core/result-error? result))
      (is (not (skill-core/result-success? result)))
      (is (nil? (skill-core/get-result-outputs result)))
      (is (= :api-timeout (:error-type (skill-core/get-result-error result))))
      (is (= "Request timed out" (:error-message (skill-core/get-result-error result))))
      (is (= {:timeout-ms 5000} (:error-data (skill-core/get-result-error result)))))))

(deftest test-check-required-inputs
  (testing "All required inputs present returns nil"
    (let [metadata {:inputs [:user-query :conversation-history]}
          input-data {:user-query "test"
                      :conversation-history []
                      :extra-field "ignored"}]
      (is (nil? (skill-core/check-required-inputs metadata input-data)))))

  (testing "Missing required input returns error"
    (let [metadata {:inputs [:user-query :conversation-history]}
          input-data {:user-query "test"}]
      (let [error (skill-core/check-required-inputs metadata input-data)]
        (is (some? error))
        (is (= :missing-inputs (:error-type error)))
        (is (= [:conversation-history] (:missing (:error-data error))))))))

(deftest test-check-required-services
  (testing "All required services present returns nil"
    (let [metadata {:required-services #{:azure-openai :typesense}}
          services {:azure-openai {:client "..."}
                    :typesense {:client "..."}
                    :extra-service {:client "..."}}]
      (is (nil? (skill-core/check-required-services metadata services)))))

  (testing "Missing required service returns error"
    (let [metadata {:required-services #{:azure-openai :typesense}}
          services {:azure-openai {:client "..."}}]
      (let [error (skill-core/check-required-services metadata services)]
        (is (some? error))
        (is (= :missing-services (:error-type error)))
        (is (= [:typesense] (:missing (:error-data error))))))))

(deftest test-skill-metadata-helpers
  (let [metadata {:skill-id :query-expansion/llm-v1
                  :name "LLM Query Expansion"
                  :category :query-transformation
                  :inputs [:user-query]
                  :outputs [:search-phrases]
                  :parameters {:prompt :string :model :string}
                  :required-services #{:azure-openai}}]
    (testing "skill-id extraction"
      (is (= :query-expansion/llm-v1 (skill-core/skill-id metadata))))

    (testing "skill-category extraction"
      (is (= :query-transformation (skill-core/skill-category metadata))))

    (testing "skill-inputs extraction"
      (is (= [:user-query] (skill-core/skill-inputs metadata))))

    (testing "skill-outputs extraction"
      (is (= [:search-phrases] (skill-core/skill-outputs metadata))))

    (testing "skill-parameters extraction"
      (is (= {:prompt :string :model :string}
             (skill-core/skill-parameters metadata))))

    (testing "skill-required-services extraction"
      (is (= #{:azure-openai} (skill-core/skill-required-services metadata))))))

(deftest test-merge-outputs
  (testing "Merge outputs from multiple results"
    (let [result1 (skill-core/success-result {:output-a 1 :output-b 2})
          result2 (skill-core/success-result {:output-b 3 :output-c 4})
          result3 (skill-core/success-result {:output-d 5})]
      (is (= {:output-a 1 :output-b 3 :output-c 4 :output-d 5}
             (skill-core/merge-outputs [result1 result2 result3])))))

  (testing "Merge with error results (nil outputs)"
    (let [result1 (skill-core/success-result {:output-a 1})
          result2 (skill-core/error-result :test-error "test")
          result3 (skill-core/success-result {:output-c 3})]
      (is (= {:output-a 1 :output-c 3}
             (skill-core/merge-outputs [result1 result2 result3]))))))

(deftest test-wrap-execution-error
  (testing "Wrap exception into error result"
    (let [ex (Exception. "Test error")
          result (skill-core/wrap-execution-error ex :test/skill)]
      (is (skill-core/result-error? result))
      (let [error (skill-core/get-result-error result)]
        (is (= :execution-exception (:error-type error)))
        (is (= "Test error" (:error-message error)))
        (is (= :test/skill (get-in error [:error-data :skill-id])))))))

(deftest test-format-skill-summary
  (testing "Format skill metadata as readable summary"
    (let [metadata {:skill-id :query-expansion/llm-v1
                    :name "LLM Query Expansion"
                    :description "Expands queries using LLM"
                    :category :query-transformation
                    :inputs [:user-query]
                    :outputs [:search-phrases]
                    :parameters {:prompt :string}
                    :required-services #{:azure-openai}}
          summary (skill-core/format-skill-summary metadata)]
      (is (string? summary))
      (is (clojure.string/includes? summary "LLM Query Expansion"))
      (is (clojure.string/includes? summary ":query-expansion/llm-v1"))
      (is (clojure.string/includes? summary "query-transformation")))))

(deftest test-skill-categories
  (testing "All skill categories are valid keywords"
    (is (set? skill-core/skill-categories))
    (is (every? keyword? skill-core/skill-categories))
    (is (contains? skill-core/skill-categories :query-transformation))
    (is (contains? skill-core/skill-categories :retrieval))
    (is (contains? skill-core/skill-categories :reranking))
    (is (contains? skill-core/skill-categories :generation))))

(deftest test-parameter-types
  (testing "All parameter types are valid keywords"
    (is (set? skill-core/parameter-types))
    (is (every? keyword? skill-core/parameter-types))
    (is (contains? skill-core/parameter-types :string))
    (is (contains? skill-core/parameter-types :number))
    (is (contains? skill-core/parameter-types :edn))))
