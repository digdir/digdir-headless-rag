(ns digdir.rag.skills.io-validation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]))

(defn with-clean-registry [f]
  (skills/clear-registry!)
  (f)
  (skills/clear-registry!))

(use-fixtures :each with-clean-registry)

(def valid-query-skill
  {:metadata {:skill-id :test/query-skill
              :name "Query Skill"
              :description "Test skill for I/O validation"
              :category :query-transformation
              :inputs [:query]
              :outputs [:queries]
              :parameters {}
              :required-services #{}}
   :execute (fn [{:keys [inputs]}]
              (skills/success-result
               {:queries [(str (:query inputs))]}))})

(def invalid-output-skill
  {:metadata {:skill-id :test/invalid-output-skill
              :name "Invalid Output Skill"
              :description "Emits invalid query output for validation testing"
              :category :query-transformation
              :inputs [:query]
              :outputs [:queries]
              :parameters {}
              :required-services #{}}
   :execute (fn [_]
              (skills/success-result
               {:queries "not-a-vector"}))})

(def undeclared-output-skill
  {:metadata {:skill-id :test/undeclared-output-skill
              :name "Undeclared Output Skill"
              :description "Emits an undeclared output for validation testing"
              :category :query-transformation
              :inputs [:query]
              :outputs [:queries]
              :parameters {}
              :required-services #{}}
   :execute (fn [{:keys [inputs]}]
              (skills/success-result
               {:queries [(:query inputs)]
                :response "unexpected"}))})

(deftest execute-skill-rejects-invalid-input-shapes
  (skills/register-skill! valid-query-skill)
  (let [ctx (skills/make-execution-context
             :test/query-skill
             {:query []}
             {}
             {}
             {}
             {:validate-io? true})
        result (skills/execute-skill :test/query-skill ctx)]
    (testing "input validation returns a structured error"
      (is (skills/result-error? result))
      (is (= :invalid-skill-inputs (get-in result [:error :error-type])))
      (is (= :query (get-in result [:error :error-data :io-key])))
      (is (seq (get-in result [:error :error-data :errors]))))))

(deftest execute-skill-rejects-invalid-output-shapes
  (skills/register-skill! invalid-output-skill)
  (let [ctx (skills/make-execution-context
             :test/invalid-output-skill
             {:query "hello"}
             {}
             {}
             {}
             {:validate-io? true})
        result (skills/execute-skill :test/invalid-output-skill ctx)]
    (testing "output validation returns a structured error"
      (is (skills/result-error? result))
      (is (= :invalid-skill-outputs (get-in result [:error :error-type])))
      (is (= :queries (get-in result [:error :error-data :io-key])))
      (is (seq (get-in result [:error :error-data :errors]))))))

(deftest execute-skill-rejects-undeclared-outputs
  (skills/register-skill! undeclared-output-skill)
  (let [ctx (skills/make-execution-context
             :test/undeclared-output-skill
             {:query "hello"}
             {}
             {}
             {}
             {:validate-io? true})
        result (skills/execute-skill :test/undeclared-output-skill ctx)]
    (testing "undeclared outputs are rejected explicitly"
      (is (skills/result-error? result))
      (is (= :invalid-skill-outputs (get-in result [:error :error-type])))
      (is (= [:response] (get-in result [:error :error-data :unexpected]))))))
