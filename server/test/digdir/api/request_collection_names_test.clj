(ns digdir.api.request-collection-names-test
  "a request to `POST /api/skills/:id/execute` may name a Typesense collection
   only if it is one of the requested dataset's own collections.

   The door passes the client's `inputs` AND `parameters` to the skill unchanged,
   and skills honour an explicit collection name over the dataset's ('explicit
   inputs win', `skills/context.clj`; retrieval's `:enrichment-search-targets`
   parameter). A key scoped to dataset A could therefore make the server search -
   or, through `enrichment-apply-questions`, delete from and write to - any
   collection on the tenant's Typesense, including dataset B's, which the same
   key is refused directly.

   Driven through the real `api-router` and API-key middleware, the real request
   dataset context and the real skill bodies. Stubbed: the API-key lookup, and the
   `typesense.client` calls, which RECORD the collection each search or delete
   targets (no network). The assertion is on what the server would have asked
   Typesense for, not only on the status."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [digdir.api.request-collections :as request-collections]
            [digdir.api.routes :as routes]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.rag.typesense :as ts-utils]
            [digdir.setup.config :as setup-config]
            [digdir.skills.enrichment.apply-questions :as apply-questions]
            [ring.middleware.params :refer [wrap-params]]
            [typesense.client :as ts-client]))

(def ^:private mk "test-key-for-encryption")

(def ^:private own
  {:docs-collection "a_documents_h1" :chunks-collection "a_chunks_h1" :phrases-collection "a_phrases_h1"})

(def ^:private foreign
  {:docs-collection "user_docs" :chunks-collection "user_chunks" :phrases-collection "user_phrases"})

(def ^:private !touched
  "Every collection the server asked Typesense to search or delete from."
  (atom []))

(defn- base-tree! [conn tenant dataset-id base-values]
  (pipeline/create-dataset! conn {:dataset-id dataset-id :name dataset-id})
  (config-db/create-dataset-pipeline! conn {:pipeline-id (str dataset-id "-p") :dataset-id dataset-id})
  (config-bootstrap/bootstrap-dataset-tree!
   conn {:tenant tenant :tenant-name tenant :dataset-id dataset-id :pipeline-id (str dataset-id "-p")
         :base-tenant-config-key dataset-id
         :base-node-id (config-db/dataset-base-node-id tenant dataset-id)
         :materialization-node-id (config-db/dataset-materialization-node-id tenant "default" dataset-id (str dataset-id "-p"))
         :materialization-label "M"
         :materialization-tenant-config-key (config-db/default-dataset-tenant-config-key "default" (str dataset-id "-p"))
         :base-values base-values :dataset-values {} :master-key mk}))

(defn- with-two-datasets
  "Tenant `ka` holds `ds-a` (the key's ONLY scope) and `ds-u` (not in the key's scope)."
  [f]
  (let [cfg {:store {:backend :mem :id (str "req-coll-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (doto (d/connect cfg) config-db/ensure-schema!)]
    (try
      (with-redefs [db/get-conn (constantly conn)
                    config-core/get-master-key (constantly mk)
                    ts-utils/resolve-ts-settings (fn [tenant] {:settings {:uri (str "http://FAKE-ts-" tenant ":8108") :key "k"}})
                    api-keys/validate-api-key
                    (fn [_ k] (when (= k "rag_k")
                                {:api-key-id "key-7" :name "k" :client-id "c"
                                 :dataset-scopes [{:tenant "ka" :dataset-config-key "ds-a"}]
                                 :agent-refs [] :scopes #{:query} :skill-graphs []}))
                    ts-client/multi-search
                    (fn [_ args & _]
                      (swap! !touched into (map :collection (:searches args)))
                      {:results (vec (repeat (count (:searches args)) {:hits [] :found 0}))})
                    ts-client/delete-documents!
                    (fn [_ coll & _] (swap! !touched conj coll) {:num_deleted 0})
                    ts-client/upsert-documents!
                    (fn [_ coll rows & _] (swap! !touched conj coll) (mapv (constantly {:success true}) rows))]
        (setup-config/ensure-pipeline-config-definitions!)
        (setup-config/ensure-system-config-definitions!)
        (base-tree! conn "ka" "ds-a" own)
        (base-tree! conn "ka" "ds-u" foreign)
        (apply-questions/register!)
        (f))
      (finally (d/release conn)))))

(use-fixtures :each with-two-datasets)

(def ^:private api (-> routes/api-router routes/wrap-api-key-auth wrap-params))

(defn- execute!
  "POST /api/skills/<skill>/execute on dataset ds-a. Returns {:status :body :touched}."
  [skill inputs params]
  (reset! !touched [])
  (let [body (json/generate-string (cond-> {:inputs inputs :tenant "ka" :datasetConfigKey "ds-a"}
                                     params (assoc :parameters params)))
        r (api {:request-method :post :uri (str "/api/skills/" skill "/execute")
                :headers {"x-api-key" "rag_k" "content-type" "application/json"}
                :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})]
    {:status (:status r) :body (str (:body r)) :touched (set @!touched)}))

(def ^:private q {:query "hva er altinn" :queries ["hva er altinn"]})
(def ^:private foreign-names (set (vals foreign)))

(defn- touched-foreign? [r] (boolean (some foreign-names (:touched r))))

(deftest a-key-scoped-to-one-dataset-cannot-name-another-datasets-collection
  (testing "CONTROL: own dataset, no names supplied - 200, only own collections searched"
    (let [r (execute! "retrieval" q nil)]
      (is (= 200 (:status r)))
      (is (seq (:touched r)) "PREMISE: the fixture records searches")
      (is (not (touched-foreign? r)))))
  (testing "CONTROL: the key's scope is real - ds-u directly is 403"
    (let [body (json/generate-string {:inputs q :tenant "ka" :datasetConfigKey "ds-u"})
          r (api {:request-method :post :uri "/api/skills/retrieval/execute"
                  :headers {"x-api-key" "rag_k" "content-type" "application/json"}
                  :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})]
      (is (= 403 (:status r)))))
  (testing "parameters.enrichment-search-targets naming ds-u's collection - 400, nothing searched"
    (let [r (execute! "retrieval" q {:enrichment-search-targets {:hypothetical-questions "user_chunks"}})]
      (is (= 400 (:status r)) (:body r))
      (is (not (touched-foreign? r)) "the foreign collection was searched")))
  (testing "an arbitrary collection name - 400"
    (let [r (execute! "retrieval" q {:enrichment-search-targets {:verified-phrases "any_collection_at_all"}})]
      (is (= 400 (:status r)) (:body r))
      (is (not (contains? (:touched r) "any_collection_at_all")))))
  (testing "the same target nested under a skill key, and camelCase - both 400"
    (let [r1 (execute! "retrieval" q {:builtin/retrieval {:enrichment-search-targets {:hypothetical-questions "user_chunks"}}})
          r2 (execute! "retrieval" q {"enrichmentSearchTargets" {"hypothetical-questions" "user_chunks"}})]
      (is (= 400 (:status r1)) (:body r1))
      (is (= 400 (:status r2)) (:body r2))
      (is (not (touched-foreign? r1)))
      (is (not (touched-foreign? r2)))))
  (testing "an explicit INPUT collection ('explicit inputs win') naming ds-u's chunks - 400"
    (let [r (execute! "retrieval" (assoc q :chunks-collection "user_chunks") nil)]
      (is (= 400 (:status r)) (:body r))
      (is (not (touched-foreign? r))))))

(deftest another-skill-behind-the-same-door-is-covered
  (testing "multi-retrieval: a foreign :chunks-collection input - 400, nothing foreign searched"
    (let [r (execute! "multi-retrieval" (assoc q :chunks-collection "user_chunks") nil)]
      (is (= 400 (:status r)) (:body r))
      (is (not (touched-foreign? r)))))
  (testing "enrichment-apply-questions: a foreign :collection-name - 400, nothing deleted or written there"
    (let [r (execute! "enrichment-apply-questions"
                      {:collection-name "user_chunks"
                       :proposals [{:chunk-id "c1" :doc-num "d1" :questions ["Hvordan søker jeg?"]}]}
                      nil)]
      (is (= 400 (:status r)) (:body r))
      (is (not (touched-foreign? r)) "a foreign collection was deleted from or written to"))))

(deftest the-requests-own-collections-still-work
  (testing "an own enrichment target, derived from ds-a's chunks name - accepted and searched"
    (let [target "a_enrichment_hypothetical_questions_h1"
          r (execute! "retrieval" q {:enrichment-search-targets {:hypothetical-questions target}})]
      (is (= 200 (:status r)) (:body r))
      (is (contains? (:touched r) target))))
  (testing "an explicit own :chunks-collection input - accepted"
    (let [r (execute! "multi-retrieval" (assoc q :chunks-collection "a_chunks_h1") nil)]
      (is (= 200 (:status r)) (:body r))
      (is (not (touched-foreign? r)))))
  (testing "a non-collection key and the collection PREFIX are not refused"
    (let [r (execute! "retrieval" q {:retrieve-top-k 10 :collection-prefix "anything_"})]
      (is (= 200 (:status r)) (:body r)))))

;; the ONE walker also finds identity keys (the door's layer 1; the
;; door-level tests are digdir.api.identity-in-request-test).
(deftest the-walker-names-each-identity-key-and-nothing-else
  (is (= [[:inputs :a :tenant]] (map first (request-collections/keys-in request-collections/names-identity?
                                                                       {:a {:tenant "x"} :b [{:query "q"}]} [:inputs]))))
  (doseq [k [:tenant "datasetId" :dataset_config_key "tenantConfigKey" :dataset-ref "runtimeConfigKey" :node_id]]
    (is (request-collections/names-identity? k) (pr-str k)))
  (doseq [k [:query :chunk-id :chunks-collection :collection-prefix :agent-id :tenants :user-query]]
    (is (not (request-collections/names-identity? k)) (pr-str k))))

;; From the review of the request collection-names fix (non-blocking, added with the tenant-scope fix): two
;; guard branches pinned directly, each red when its branch is removed.
(def ^:private own-context {:dataset-inputs own})

(defn- collection-refusal [data]
  (try (request-collections/check-request-collections! own-context data) :no-refusal
       (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:reason :collection :path]))))

(deftest g7-a-camel-case-collection-key-is-the-same-key
  ;; Pinned until now only by upstream key normalisation; this drives the
  ;; walker's own separator- and case-insensitivity.
  (is (= {:reason :collection-not-in-dataset :collection "user_chunks" :path [:inputs "chunksCollection"]}
         (collection-refusal {:inputs {"chunksCollection" "user_chunks"}})))
  (is (= :no-refusal (collection-refusal {:inputs {"chunksCollection" "a_chunks_h1"}})) "CONTROL: its own name"))

(deftest g5-a-collection-name-nested-in-an-array-is-found
  ;; No skill reads one there today; the walker must not depend on that.
  (is (= {:reason :collection-not-in-dataset :collection "user_chunks" :path [:parameters :items 1 :chunks-collection]}
         (collection-refusal {:parameters {:items [{:query "q"} {:chunks-collection "user_chunks"}]}})))
  (is (= :no-refusal (collection-refusal {:parameters {:items [{:chunks-collection "a_chunks_h1"}]}})) "CONTROL: its own name"))
