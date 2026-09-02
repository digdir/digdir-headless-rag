(ns digdir.api.envelope-response-shape-test
  "The last two of #193's six: SkillExecutionResponse and
   OperatorDatasetResponse.

   Both are envelopes, and they are the two where the interesting question is
   NOT the envelope key but what the schema says about the inside:

     SkillExecutionResponse    declares `result` OPEN (additionalProperties),
                               because the inner shape is per-skill. Verifying
                               it means checking the envelope AND that the
                               openness is still declared - a later edit closing
                               it would make the schema claim a fixed shape that
                               is false for every skill but one.

     OperatorDatasetResponse   declares `dataset` as a $ref, so the inner shape
                               IS enumerated and can drift.

   Key sets rather than individual fields, per #191."
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.context :as api-ctx]
            [digdir.api.openapi-test-util :refer [keyset non-empty-properties]]
            [digdir.api.routes.datasets :as datasets]
            [digdir.api.routes.endpoints :as endpoints]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.skills.api :as skills-api]))

;; ---------------------------------------------------------------------------
;; SkillExecutionResponse
;; ---------------------------------------------------------------------------

(defn- skill-execution-body []
  (with-redefs [api-ctx/resolve-request-dataset-context!
                ;; Variadic, because the real fn has BOTH a 2- and a 3-arity and
                ;; callers use each. A fixed 3-arity stub throws ArityException
                ;; the moment a call site drops the opts map - which is exactly
                ;; what removing the no-op :require-explicit? flag did.
                (fn [& _] {:dataset-ref {:tenant "ka" :dataset-config-key "prod"}
                           :config {:docs-collection "docs"}})
                skills-api/execute
                (fn [_ _ _]
                  ;; A per-skill shape. Deliberately not a fixed one: the point
                  ;; of the open declaration is that this varies by skill.
                  {:queries ["q1"] :chunks [] :whatever-this-skill-returns 42})]
    (let [response (endpoints/execute-skill-handler
                     {:path-params {:id "query-planner"}
                      :body-params {:inputs {:query "hei"}}})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest skill-execution-response-matches-its-schema
  (testing "envelope key set"
    (let [advertised (non-empty-properties [:SkillExecutionResponse :properties])
          actual (keyset (skill-execution-body))]
      (is (= advertised actual)
          (str "envelope differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "result stays declared OPEN"
    ;; If someone closes this, the schema starts promising a fixed inner shape
    ;; that is false for every skill but the one they had in mind.
    (let [result-schema (-> (yaml/parse-string (slurp (io/file "docs/api/openapi.yaml")))
                            (get-in [:components :schemas :SkillExecutionResponse
                                     :properties :result]))]
      (is (true? (:additionalProperties result-schema))
          "SkillExecutionResponse.result must keep additionalProperties: true")
      (is (nil? (:properties result-schema))
          "enumerating properties here would contradict the open declaration"))))

;; ---------------------------------------------------------------------------
;; OperatorDatasetResponse
;; ---------------------------------------------------------------------------

(def ^:private dataset-record
  "As config-db/get-dataset-record really returns it. That fn pulls with '[*],
   and :dataset/enabled? is a genuine attribute — the pipeline pull names it
   explicitly — so a stub omitting it stands in for a record the system does not
   produce.

   It makes no difference to the key set today, because summarize-dataset-record
   builds a literal map and :enabled? is present either way. It would matter the
   moment that map became conditional, and a stub that cannot produce the field
   would quietly stop covering it (#209-class: assert against what the real code
   returns)."
  #:dataset{:id "ds_123"
            :name "Website"
            :description "Public site"
            :enabled? true})

(defn- operator-dataset-body []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                pipeline/get-dataset-record (fn [_ _] dataset-record)
                config-db/list-dataset-pipelines (fn [_ _] [])
                config-db/materialization-contexts-by-pipeline-id (fn [_] {})]
    (let [response (datasets/get-dataset-handler
                     {:path-params {:dataset-id "ds_123"}})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest operator-dataset-response-matches-its-schema
  (testing "envelope key set"
    (let [advertised (non-empty-properties [:OperatorDatasetResponse :properties])
          actual (keyset (operator-dataset-body))]
      (is (= advertised actual)
          (str "envelope differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "the nested dataset object, both directions"
    (let [advertised (non-empty-properties [:OperatorDatasetSummary :properties])
          actual (keyset (:dataset (operator-dataset-body)))]
      (is (empty? (set/difference advertised actual))
          (str "OperatorDatasetSummary advertises fields the handler does not emit: "
               (sort (set/difference advertised actual))))
      (is (empty? (set/difference actual advertised))
          (str "handler emits fields OperatorDatasetSummary does not advertise: "
               (sort (set/difference actual advertised)))))))

;; ---------------------------------------------------------------------------
;; OperatorPipelineSummary — the ELEMENT schema inside OperatorDatasetSummary
;;
;; The test above stubs list-dataset-pipelines to [], so `pipelines` was present
;; but empty and this $ref had NO coverage at all: an empty array satisfies any
;; element schema. Rule ten — assert the element shape of a collection, not only
;; that the collection key is present.
;;
;; It is also the one element here whose key set genuinely VARIES, because
;; pipeline-summary builds it with cond->. So the honest assertion is not one
;; key set but two: the base case, and the case where both conditional fields
;; are present. Neither alone can pin a conditional field as conditional.
;; ---------------------------------------------------------------------------

(defn- items-ref
  "The $ref an array property's items point at, with a vacuity guard.

   Comparing an element key set is worthless if the array no longer points at
   the schema being compared - re-pointing the $ref would leave the comparison
   green while it verifies a schema the response does not claim to use."
  [path]
  (let [items (-> (yaml/parse-string (slurp (io/file "docs/api/openapi.yaml")))
                  (get-in (concat [:components :schemas] path)))]
    (is (seq items)
        (str "no items schema found at " path
             " - the element comparison would be against nothing"))
    (:$ref items)))

(def ^:private raw-pipeline
  "Shaped as config-db/list-dataset-pipelines really returns it — a pull of
   [* {:dataset.pipeline/dataset [:dataset/id :dataset/name :dataset/enabled?]}] —
   so the owning dataset arrives as a nested join map, not a bare id.
   pipeline-summary reads :datasetId through that join."
  #:dataset.pipeline{:id "dp_1"
                     :enabled? true
                     :dataset #:dataset{:id "ds_123" :name "Website" :enabled? true}})

(defn- effective-returning
  "Stands in for config-db/effective-dataset-pipeline-record. Its documented
   contract is to decorate the record with resolved projection fields, or — when
   resolution is ambiguous across tenants — to omit the value and set the
   matching *-ambiguous? flag instead. `extra` is that decoration."
  [extra]
  (fn [_db pipeline-record _opts] (merge pipeline-record extra)))

(def ^:private resolved
  #:dataset.pipeline{:effective-name "Website crawl"
                     :effective-source-type "web"})

(defn- pipeline-elements [extra]
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                pipeline/get-dataset-record (fn [_ _] dataset-record)
                config-db/list-dataset-pipelines (fn [_ _] [raw-pipeline])
                config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                config-db/effective-dataset-pipeline-record (effective-returning extra)]
    (let [response (datasets/get-dataset-handler
                     {:path-params {:dataset-id "ds_123"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (get-in body [:dataset :pipelines]))))

(deftest operator-pipeline-summary-element-matches-its-schema
  (testing "the array's items actually point at the schema this test compares"
    (is (= "#/components/schemas/OperatorPipelineSummary"
           (items-ref [:OperatorDatasetSummary :properties :pipelines :items]))))

  (let [advertised (non-empty-properties [:OperatorPipelineSummary :properties])
        conditional #{"nameAmbiguous" "sourceTypeAmbiguous"}]

    (testing "the unambiguous element omits exactly the two conditional fields"
      (let [elements (pipeline-elements resolved)]
        (is (= 1 (count elements)) "one pipeline in, one summary out")
        (is (= (set/difference advertised conditional) (keyset (first elements)))
            (str "base element differs. expected "
                 (sort (set/difference advertised conditional))
                 " actual " (sort (keyset (first elements)))))))

    (testing "the fully-ambiguous element reaches every advertised field"
      ;; Proves the advertised superset is emittable. A schema can advertise a
      ;; field no code path produces, and no single-case test can see that.
      (let [elements (pipeline-elements
                       (assoc resolved
                              :dataset.pipeline/effective-name-ambiguous? true
                              :dataset.pipeline/effective-source-type-ambiguous? true))]
        (is (= advertised (keyset (first elements)))
            (str "ambiguous element differs. advertised " (sort advertised)
                 " actual " (sort (keyset (first elements)))))))

    (testing "pipelineCount counts the pipelines it ships"
      (let [body (with-redefs [db/get-conn (fn [] (atom :mock-db))
                               pipeline/get-dataset-record (fn [_ _] dataset-record)
                               config-db/list-dataset-pipelines
                               (fn [_ _] [raw-pipeline
                                          (assoc raw-pipeline :dataset.pipeline/id "dp_2")])
                               config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                               config-db/effective-dataset-pipeline-record
                               (effective-returning resolved)]
                   (json/parse-string
                     (:body (datasets/get-dataset-handler
                              {:path-params {:dataset-id "ds_123"}}))
                     true))]
        (is (= 2 (get-in body [:dataset :pipelineCount])))
        (is (= 2 (count (get-in body [:dataset :pipelines])))
            "pipelineCount and the array it counts must not disagree")))))

;; ---------------------------------------------------------------------------
;; The two LIST responses — #217
;;
;; Same vacuity hole as the one above, on two more arrays. Both handlers were
;; read and both are currently CORRECT, so this closes a coverage gap rather
;; than fixing a defect. Worth closing anyway, because
;; OperatorPipelineListResponse was added to satisfy strict validation and had
;; never been compared to the handler that fills it — a schema derived from a
;; validator rather than from a producer.
;;
;; Note the arity: list-datasets-handler calls list-dataset-pipelines with ONE
;; argument where get-dataset-handler passes two, so these stubs are variadic.
;; A fixed 2-arity stub throws ArityException here.
;;
;; Neither handler needs config-core/get-master-key stubbed (a plain atom
;; deref), and authorize-dataset-materialization-request! is a no-op unless the
;; request carries :api-key/allowed-config-keys — so the real authorization
;; path runs rather than being stubbed past.
;; ---------------------------------------------------------------------------

(defn- dataset-list-body [extra]
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                pipeline/list-dataset-records (fn [_] [dataset-record])
                config-db/list-dataset-pipelines (fn [& _] [raw-pipeline])
                config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                config-db/effective-dataset-pipeline-record (effective-returning extra)]
    (let [response (datasets/list-datasets-handler {})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest operator-dataset-list-response-matches-its-schema
  (testing "envelope key set"
    (let [advertised (non-empty-properties [:OperatorDatasetListResponse :properties])
          actual (keyset (dataset-list-body resolved))]
      (is (= advertised actual)
          (str "envelope differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "datasets.items point at the schema this test compares"
    (is (= "#/components/schemas/OperatorDatasetSummary"
           (items-ref [:OperatorDatasetListResponse :properties :datasets :items]))))

  (testing "the element, against a NON-EMPTY array"
    (let [advertised (non-empty-properties [:OperatorDatasetSummary :properties])
          datasets (:datasets (dataset-list-body resolved))]
      (is (seq datasets)
          "empty array — it would satisfy any element schema and assert nothing")
      (is (= advertised (keyset (first datasets)))
          (str "element differs. advertised " (sort advertised)
               " actual " (sort (keyset (first datasets)))))))

  (testing "the list path nests the same pipeline element as the detail path"
    ;; list-datasets-handler reaches pipeline-summary by a different route
    ;; (group-by over all pipelines rather than a per-dataset query), so this
    ;; pins that the two routes agree rather than assuming they must.
    (let [advertised (non-empty-properties [:OperatorPipelineSummary :properties])
          conditional #{"nameAmbiguous" "sourceTypeAmbiguous"}
          nested (get-in (dataset-list-body resolved) [:datasets 0 :pipelines])]
      (is (seq nested) "no nested pipelines — the grouping did not match the dataset")
      (is (= (set/difference advertised conditional) (keyset (first nested)))
          (str "nested element differs. actual " (sort (keyset (first nested))))))))

(defn- pipeline-list-body [extra]
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                config-db/list-dataset-pipelines (fn [& _] [raw-pipeline])
                config-db/effective-dataset-pipeline-record (effective-returning extra)]
    (let [response (datasets/list-pipelines-handler
                     {:path-params {:dataset-id "ds_123"}})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest operator-pipeline-list-response-matches-its-schema
  (testing "envelope key set"
    (let [advertised (non-empty-properties [:OperatorPipelineListResponse :properties])
          actual (keyset (pipeline-list-body resolved))]
      (is (= advertised actual)
          (str "envelope differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "pipelines.items point at the schema this test compares"
    ;; This $ref is the SECOND occurrence of that string in openapi.yaml; the
    ;; first is OperatorDatasetSummary's. Worth knowing when editing either.
    (is (= "#/components/schemas/OperatorPipelineSummary"
           (items-ref [:OperatorPipelineListResponse :properties :pipelines :items]))))

  (let [advertised (non-empty-properties [:OperatorPipelineSummary :properties])
        conditional #{"nameAmbiguous" "sourceTypeAmbiguous"}]

    (testing "the unambiguous element omits exactly the two conditional fields"
      (let [pipelines (:pipelines (pipeline-list-body resolved))]
        (is (seq pipelines)
            "empty array — it would satisfy any element schema and assert nothing")
        (is (= (set/difference advertised conditional) (keyset (first pipelines)))
            (str "base element differs. expected "
                 (sort (set/difference advertised conditional))
                 " actual " (sort (keyset (first pipelines)))))))

    (testing "the fully-ambiguous element reaches every advertised field"
      (let [pipelines (:pipelines
                        (pipeline-list-body
                          (assoc resolved
                                 :dataset.pipeline/effective-name-ambiguous? true
                                 :dataset.pipeline/effective-source-type-ambiguous? true)))]
        (is (= advertised (keyset (first pipelines)))
            (str "ambiguous element differs. advertised " (sort advertised)
                 " actual " (sort (keyset (first pipelines)))))))))

