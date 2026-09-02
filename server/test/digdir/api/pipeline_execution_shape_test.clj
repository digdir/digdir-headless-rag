(ns digdir.api.pipeline-execution-shape-test
  "PipelineExecution must match the rows list-executions-handler emits (#193).

   The schema describes ONE ROW of the console executions endpoint, not the
   envelope, so the comparison is against a row rather than against the response
   body — reading the wrong level is the same mistake as reading the wrong
   handler.

   Key sets rather than individual fields, per #191."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.openapi-test-util :refer [keyset non-empty-properties]]
            [digdir.api.routes.datasets :as datasets]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.executor :as executor]))

(def ^:private execution-record
  "One row as digdir.pipeline.executor/list-executions returns it — the
   producer's own attribute shape, so the handler's mapping is exercised rather
   than bypassed. completed-at and error-message are left nil deliberately: the
   schema says everything but id, execution-pipeline-id, status and startedAt
   serialises as null when unset, and a row with them populated would not
   exercise that."
  #:pipeline-execution{:id "exec-1"
                       :pipeline-id "ka/prod/assistant"
                       :status :completed
                       :started-at "2026-08-21T10:00:00Z"
                       :completed-at nil
                       :documents-processed 12
                       :documents-failed 0
                       :error-message nil
                       :started-by "someone@example.com"})

(defn- execution-rows []
  (with-redefs [datasets/required-console-materialization-context
                (fn [_] {:tenant "ka" :dataset-config-key "prod"})
                db/get-conn (fn [] (atom :mock-db))
                datasets/dataset-materialization-record! (fn [_ _ _] {:dataset/id "ds_123"})
                pipeline/make-pipeline-id (fn [& _] "ka/prod/assistant")
                datasets/authorize-dataset-materialization-request! (fn [_ _ _] nil)
                executor/list-executions (fn [_ _] [execution-record])]
    (let [response (datasets/list-executions-handler
                     {:path-params {:dataset-id "ds_123" :pipeline-id "assistant"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (is (= 1 (count (:executions body))) "one row in, one row out")
      (:executions body))))

(deftest pipeline-execution-row-matches-its-schema
  (testing "row key set, both directions"
    (let [advertised (non-empty-properties [:PipelineExecution :properties])
          actual (keyset (first (execution-rows)))]
      (is (empty? (set/difference advertised actual))
          (str "schema advertises fields the handler does not emit: "
               (sort (set/difference advertised actual))))
      (is (empty? (set/difference actual advertised))
          (str "handler emits fields the schema does not advertise: "
               (sort (set/difference actual advertised))))))

  (testing "the nullable fields are present-and-null rather than absent"
    ;; The schema's description says they serialise as null when unset. If the
    ;; handler ever switched to omitting them, the key set would shrink and a
    ;; client trusting the description would break — so this pins the
    ;; distinction the description draws.
    (let [row (first (execution-rows))]
      (is (contains? row :completedAt) "completedAt must be present even when nil")
      (is (nil? (:completedAt row)))
      (is (contains? row :errorMessage) "errorMessage must be present even when nil")
      (is (nil? (:errorMessage row))))))
