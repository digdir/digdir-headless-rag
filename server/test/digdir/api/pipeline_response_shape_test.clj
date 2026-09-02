(ns digdir.api.pipeline-response-shape-test
  "What we advertise as a RESPONSE must match what the handler emits.

   `openapi.yaml`'s Pipeline schema described `id`, `tenant` and `configKey`.
   `get-pipeline-handler` emits none of `id` or `configKey` (#184): it starts
   from `pipeline/get-dataset`, explicitly assoc's `dataset-config-key` and
   `execution-pipeline-id`, and explicitly DISSOC's `tenant-config-key` and
   `id`.

   This is the silent half of the class #176 covers. There, the field was a
   required query parameter, so a caller following the spec got a 400 and
   learned they were wrong. Here the call returns 200 and the field is simply
   absent — a generated client gets nil, or crashes far from the cause, and
   nothing in our logs ever shows it.

   ── Why this test exercises the handler instead of reading it ──

   The trap #147 documented is concluding a shape from a neighbouring schema,
   or from a function that builds that shape but never reaches the wire. So
   this asserts against a real response body produced by really calling the
   handler, not against a list restated in Clojure and not against what
   `pipeline-summary` happens to return — that is the LIST endpoint's shape and
   it is a different one. Reading the two as interchangeable is how the
   original report got `tenant` wrong: the list summary omits it, the detail
   response includes it.

   ── The limit of this test, stated rather than hidden ──

   Only the fields the HANDLER controls are pinned. The rest of the response is
   the resolved dataset/materialization property set, which `config-db/get-dataset`
   builds from whatever property paths are defined in the config tree — that is
   data-driven and cannot be enumerated in a schema without going stale, which
   is why `Pipeline` keeps `additionalProperties: true` and why extra keys in
   the response are not treated as drift here."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [digdir.api.routes :as routes]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]))

(defn- schema-properties
  "Property names the Pipeline response schema advertises."
  []
  (let [f (io/file "docs/api/openapi.yaml")]
    (is (.exists f) (str "expected to find " (.getPath f)))
    (-> (yaml/parse-string (slurp f))
        (get-in [:components :schemas :Pipeline :properties])
        keys
        (->> (map name))
        set)))

(defn- pipeline-schema []
  (-> (yaml/parse-string (slurp (io/file "docs/api/openapi.yaml")))
      (get-in [:components :schemas :Pipeline])))

(defn- actual-response-keys
  "Call the handler and return the keys it really puts on the pipeline object.

   The `pipeline/get-dataset` stub returns exactly what that function's contract
   says it returns — id, tenant, tenant-config-key, pipeline-name, plus resolved
   config properties — so the handler's own assoc/dissoc behaviour is exercised
   faithfully rather than against a shape no producer emits."
  []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                config-db/get-config-node-by-tenant-config-key
                (fn [_ tenant root slug]
                  (when (= [tenant root slug] ["ka" :dataset "default"])
                    {:config.node/id "dataset/ka/default"}))
                config-db/get-dataset-pipeline
                (fn [_ pipeline-id]
                  (when (= "assistant" pipeline-id)
                    {:dataset.pipeline/id "assistant"
                     :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                config-core/get-master-key (constantly "master-key")
                pipeline/get-dataset
                (fn [_ tenant tenant-config-key pipeline-name _]
                  {:id (pipeline/make-pipeline-id tenant tenant-config-key pipeline-name)
                   :tenant tenant
                   :tenant-config-key tenant-config-key
                   :pipeline-name pipeline-name
                   ;; stands in for the data-driven resolved properties
                   :name "Assistant"
                   :source-type "website"})]
    (let [request {:path-params {:dataset-id "ds_123" :pipeline-id "assistant"}
                   :params {"tenant" "ka" "dataset-config-key" "prod"}
                   :api-key/allowed-config-keys
                   [{:api-key.allowed-config-key/id "ceiling-1"
                     :api-key.allowed-config-key/root :dataset
                     :api-key.allowed-config-key/tenant "ka"
                     :api-key.allowed-config-key/node-id "dataset/ka/default"
                     :api-key.allowed-config-key/tenant-config-key "default"}]}
          response (routes/get-pipeline-handler request)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)) "the handler must reach its success path")
      (set (map name (keys (:pipeline body)))))))

;; ---------------------------------------------------------------------------

(deftest pipeline-schema-advertises-nothing-the-handler-omits
  (testing "every advertised property is actually present in the response"
    ;; This is the assertion that would have caught #184: `id` and `configKey`
    ;; were advertised and neither is ever sent.
    (let [advertised (schema-properties)
          actual (actual-response-keys)]
      (is (empty? (set/difference advertised actual))
          (str "openapi.yaml's Pipeline schema advertises fields the handler "
               "does not emit: " (sort (set/difference advertised actual))
               "\n  handler actually emitted: " (sort actual))))))

(deftest fields-the-handler-strips-are-not-advertised
  (testing "id and tenant-config-key are dissoc'd, so they must not be advertised"
    ;; get-pipeline-handler removes both before serialising. `id` in particular
    ;; looks plausible — pipeline/get-dataset really does compute one — which is
    ;; exactly why reading the producer rather than the schema matters.
    (let [advertised (schema-properties)
          actual (actual-response-keys)]
      (doseq [stripped ["id" "tenant-config-key"]]
        (is (not (contains? actual stripped))
            (str stripped " is expected to be dissoc'd by the handler; if this "
                 "fails the handler changed and the schema should follow"))
        (is (not (contains? advertised stripped))
            (str stripped " is removed by the handler and must not be advertised"))))))

(deftest pipeline-schema-keeps-additional-properties-open
  (testing "the resolved config properties are data-driven and cannot be enumerated"
    ;; If someone closes this, the schema starts claiming the response has ONLY
    ;; the handler-controlled fields, which is false for every real dataset.
    (is (true? (:additionalProperties (pipeline-schema)))
        "Pipeline must keep additionalProperties: true — the rest of the body is
         built from config-tree property paths, not from a fixed field list")))
