(ns digdir.api.config-resolve-shape-test
  "The two config-resolve responses must match what their handlers emit (#193).

   #192 declared schemas for six previously-undocumented responses. A declared
   schema is only a promise until something exercises the handler and compares,
   which is what this does: call the handler, parse the real body, compare KEY
   SETS.

   Key sets rather than individual fields, per #191: a fabricated key is
   invisible to an assertion that reads only the fields it cares about.

   These two endpoints look like a matched pair AND ARE NOT. Runtime emits
   `runtime-config-key`, dataset emits `dataset-config-key`; runtime's
   `compatibility` carries `agent-id` and makes `dataset-id` CONDITIONAL, while
   dataset's carries `dataset-id` unconditionally and no agent-id. Assuming
   symmetry is what #145 got wrong, and their prose descriptions are
   byte-identical, so anything anchored on description text lands on both. Each
   test below therefore asserts which path it exercised.

   BOTH DIRECTIONS OF THE KEY-SET COMPARISON HAVE BEEN DEMONSTRATED FAILING,
   which matters because they fail for different reasons and one of them is easy
   to prove hollow:

     advertised-but-not-emitted  injected into openapi.yaml - a bogus property
                                 added to the schema under test. Fires.
     emitted-but-not-advertised  injected into the PRODUCER - a stray key added
                                 to resolve-runtime-config-handler's
                                 response-data. Fires.

   The second is the one worth insisting on. A fabricated key added to the TEST
   proves only that the assertion can read a key it was handed; added to the
   producer it proves the assertion catches a key THE REAL CODE EMITS.

   Both sabotages were verified BY LINE NUMBER rather than by anchor text. The
   first attempt was anchored on \"runtime-config-key: type: string\", which
   occurs three times in the spec, and landed in an unrelated schema - so the
   test passed and looked like success. A mis-targeted sabotage always produces
   the quiet answer, and for a sabotage the quiet answer is green."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.context :as api-ctx]
            [digdir.api.openapi-test-util :refer [keyset non-empty-properties]]
            [digdir.api.routes.datasets :as datasets]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]))

;; spec helpers live in digdir.api.openapi-test-util so the response-shape
;; tests share one definition of "advertised".

;; ---------------------------------------------------------------------------
;; Runtime
;; ---------------------------------------------------------------------------

(defn- runtime-response
  "Exercise resolve-runtime-config-handler with a dataset-ref supplied, so the
   CONDITIONAL compatibility.dataset-id is present."
  []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                config-core/get-master-key (constantly "master-key")
                api-ctx/resolve-request-config-node!
                (fn [_ _ _] {:node {:config.node/id "runtime/ka/default"
                                    :config.node/tenant-config-key "default"}
                             :matched-allowed-config-key
                             {:api-key.allowed-config-key/tenant-config-key "default"}})
                config-db/resolve-dataset-ref-materializations
                (fn [_ _] {:dataset-id "ds_123"})
                cfg/load-runtime-config-v2-with-trace
                (fn [_] {:config {"skills.retrieval.top-k" 30}
                         :traces {"skills.retrieval.top-k" [{:node "default"}]}})]
    (let [response (datasets/resolve-runtime-config-handler
                     {:body-params {:tenant "ka"
                                    :runtime-config-key "default"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-config-key "prod"}})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest runtime-resolve-response-matches-its-schema
  (testing "top-level key set"
    (let [body (runtime-response)
          advertised (non-empty-properties [:RuntimeConfigResolveResponse :properties])]
      (is (contains? (keyset body) "runtime-config-key")
          "sanity: this is the RUNTIME path, not its byte-identical twin")
      (is (empty? (set/difference advertised (keyset body)))
          (str "schema advertises fields the handler does not emit: "
               (sort (set/difference advertised (keyset body)))))
      (is (empty? (set/difference (keyset body) advertised))
          (str "handler emits fields the schema does not advertise: "
               (sort (set/difference (keyset body) advertised))))))

  (testing "nested compatibility, including the conditional dataset-id"
    (let [body (runtime-response)
          advertised (non-empty-properties [:RuntimeConfigResolveResponse :properties
                                         :compatibility :properties])
          actual (keyset (:compatibility body))]
      (is (contains? actual "agent-id")
          "runtime compatibility carries agent-id; the dataset variant does not")
      (is (contains? actual "dataset-id")
          "exercised WITH a dataset-ref, so the conditional field must appear")
      (is (= advertised actual)
          (str "compatibility key set differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "nested node"
    (let [body (runtime-response)
          advertised (non-empty-properties [:RuntimeConfigResolveResponse :properties
                                         :node :properties])]
      (is (= advertised (keyset (:node body)))
          (str "node key set differs. advertised " (sort advertised)
               " actual " (sort (keyset (:node body))))))))

(deftest runtime-compatibility-dataset-id-is-genuinely-conditional
  (testing "without a dataset-ref the field is absent"
    ;; The schema may advertise it, but the handler only assocs it when a
    ;; dataset is in play — so this pins the conditionality rather than letting
    ;; the always-present case stand in for both.
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-core/get-master-key (constantly "master-key")
                  api-ctx/resolve-request-config-node!
                  (fn [_ _ _] {:node {:config.node/id "runtime/ka/default"
                                      :config.node/tenant-config-key "default"}
                               :matched-allowed-config-key nil})
                  cfg/load-runtime-config-v2-with-trace
                  (fn [_] {:config {} :traces {}})]
      (let [response (datasets/resolve-runtime-config-handler
                       {:body-params {:tenant "ka"
                                      :runtime-config-key "default"
                                      :agent-id "builtin/agent-rag-agent"}})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (not (contains? (keyset (:compatibility body)) "dataset-id"))
            "no dataset-ref supplied, so compatibility.dataset-id must be absent")))))

;; ---------------------------------------------------------------------------
;; Dataset
;; ---------------------------------------------------------------------------

(defn- dataset-response
  []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                config-core/get-master-key (constantly "master-key")
                config-db/get-dataset-by-ref
                (fn [_ _ _] {:tenant "ka"
                             :dataset-config-key "prod"
                             :dataset-id "ds_123"
                             :dataset-node-id "dataset/ka/prod"})
                api-ctx/resolve-request-config-node!
                (fn [_ _ _] {:node {:config.node/id "dataset/ka/prod"}
                             :matched-allowed-config-key
                             {:api-key.allowed-config-key/tenant-config-key "prod"}})
                cfg/load-dataset-config-v2-with-trace
                (fn [_] {:config {"chunking.strategy" "semantic"}
                         :traces {"chunking.strategy" [{:node "prod"}]}})]
    (let [response (datasets/resolve-dataset-config-handler
                     {:body-params {:tenant "ka" :dataset-config-key "prod"}})]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (json/parse-string (:body response) true))))

(deftest dataset-resolve-response-matches-its-schema
  (testing "top-level key set"
    (let [body (dataset-response)
          advertised (non-empty-properties [:DatasetConfigResolveResponse :properties])]
      (is (contains? (keyset body) "dataset-config-key")
          "sanity: this is the DATASET path, not its byte-identical twin")
      (is (empty? (set/difference advertised (keyset body)))
          (str "schema advertises fields the handler does not emit: "
               (sort (set/difference advertised (keyset body)))))
      (is (empty? (set/difference (keyset body) advertised))
          (str "handler emits fields the schema does not advertise: "
               (sort (set/difference (keyset body) advertised))))))

  (testing "nested compatibility — dataset-id unconditional, no agent-id"
    (let [body (dataset-response)
          advertised (non-empty-properties [:DatasetConfigResolveResponse :properties
                                         :compatibility :properties])
          actual (keyset (:compatibility body))]
      (is (contains? actual "dataset-id")
          "dataset compatibility.dataset-id is unconditional")
      (is (not (contains? actual "agent-id"))
          "agent-id belongs to the runtime variant only")
      (is (= advertised actual)
          (str "compatibility key set differs. advertised " (sort advertised)
               " actual " (sort actual)))))

  (testing "nested node"
    (let [body (dataset-response)
          advertised (non-empty-properties [:DatasetConfigResolveResponse :properties
                                         :node :properties])]
      (is (= advertised (keyset (:node body)))
          (str "node key set differs. advertised " (sort advertised)
               " actual " (sort (keyset (:node body))))))))
