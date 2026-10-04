(ns digdir.api.tenant-scope-doors-test
  "THE TENANT AXIS FAILS CLOSED, at EVERY API-key door the route census
   declares `:derived` (the project owner's decision; the spec; a review
   matrix, MEASURED at base). The door table below is pinned to the census
   (`digdir.api.route-auth-census-test`): a declared route without a matrix row
   is red.

   Three keys, made by `create-api-key!` and validated for real:
   - A: dataset scope kt/ds-kt only;
   - B: no dataset scope, no tenant - before the tenant-scope fix a superuser \"by allocation\";
   - C: B, MARKED all-tenant (`:access-policy/all-tenants?`), the explicit
     deliberate breadth. Marked here by writing the attribute, so this ns runs
     at base too (where the marker means nothing, and C is B).
   A → kt is served (the positive control: a door that refused everything would
   pass the refusals), A → ku is refused, B → any tenant is refused, C → any
   tenant is served - except the dataset LISTINGS, which show a key's own
   dataset scopes only (a tenant grant is not a dataset grant).
   A refused WRITE leaves the store unchanged; a refused READ carries none of
   the tenant's data (ordering is behavioural, not structural).

   Driven through the real `api-router` and `wrap-api-key-auth`. Stubbed: the
   Typesense settings, `typesense.client/search`, the agent registry and
   `invoke-rag` (the MCP, tools and /v1 doors)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.api.route-auth-census-test :as census]
            [digdir.api.routes :as routes]
            [digdir.api.util :as api-util]
            [digdir.config.accessor :as cfg]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.rag.typesense :as ts-utils]
            [digdir.setup.config :as setup-config]
            [digdir.skills.api :as skills-api]
            [digdir.skills.enrichment.fetch-chunk-context :as fetch-chunk-context]
            [digdir.skills.invoke :as invoke]
            [ring.middleware.params :refer [wrap-params]]
            [typesense.client :as ts-client]))

(def ^:private mk "test-key-for-encryption")
(def ^:private agent-id "builtin/agent-rag-agent")
(def ^:private !keys (atom {}))
(def ^:private !conn (atom nil))

(def ^:private rag-agent
  {:id "builtin/rag-agent" :name "RAG Agent" :description "Answers questions."
   :default-skill-graph :builtin/agent-rag-graph-bundled
   :allowed-skill-graphs [:builtin/agent-rag-graph-bundled]
   :allowed-dataset-scopes [] :enabled? true})

(def ^:private tool "builtin.rag-agent__agent-rag-graph-bundled")

(defn- marker
  "What only tenant `t`'s data carries: a refused response must not."
  [t] (str "SECRET-" t))

(defn- dataset! [conn tenant dataset-id]
  (pipeline/create-dataset! conn {:dataset-id dataset-id :name dataset-id})
  (config-db/create-dataset-pipeline! conn {:pipeline-id (str dataset-id "-p") :dataset-id dataset-id})
  (config-bootstrap/bootstrap-dataset-tree!
   conn {:tenant tenant :tenant-name tenant :dataset-id dataset-id :pipeline-id (str dataset-id "-p")
         :base-tenant-config-key dataset-id
         :base-node-id (config-db/dataset-base-node-id tenant dataset-id)
         :materialization-node-id (config-db/dataset-materialization-node-id tenant "default" dataset-id (str dataset-id "-p"))
         :materialization-label "M"
         :materialization-tenant-config-key (config-db/default-dataset-tenant-config-key "default" (str dataset-id "-p"))
         :base-values {"pipeline.storage.docs-collection" (str tenant "_docs")
                       "pipeline.storage.chunks-collection" (str tenant "_chunks")
                       "pipeline.storage.phrases-collection" (str tenant "_phrases")}
         :dataset-values {} :master-key mk}))

(defn- mark-all-tenants!
  "Mark `plaintext`'s key all-tenant by writing the policy attribute (what the
   console's setter writes)."
  [conn plaintext]
  (let [id (:api-key-id (api-keys/validate-api-key conn plaintext))
        policy (d/q '[:find ?p . :in $ ?id :where [?k :api-key/id ?id] [?k :api-key/policy ?p]] @conn id)]
    (assert policy "PREMISE: key C has an access policy to mark")
    (d/transact conn {:tx-data [[:db/add policy :access-policy/all-tenants? true]]})))

(defn- with-two-tenants [f]
  (let [cfg {:store {:backend :mem :id (str "tenant-scope-doors-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (doto (d/connect cfg) (d/transact {:tx-data db/dh-schema}) config-db/ensure-schema!)]
    (reset! !conn conn)
    (try
      (with-redefs [db/get-conn (constantly conn)
                    config-core/get-master-key (constantly mk)
                    ts-utils/resolve-ts-settings (fn [tenant] {:settings {:uri (str "http://FAKE-ts-" tenant ":8108") :key "SYNTHKEY"}})
                    ts-client/search (fn [settings _ _] {:hits [{:document {:chunk_id "c1" :doc_num "d1" :url "u"
                                                                            :content_markdown (str "chunk of " (:uri settings))}}]})
                    agents-db/list-enabled-agents (fn [_] [rag-agent])
                    agents-db/get-agent (fn [_ id] (when (= id (:id rag-agent)) rag-agent))
                    skills-api/initialize! (fn [] nil)
                    skills-api/get-skill-graph-info (fn [g] {:id g :name (name g) :description "stub"
                                                             :input-schema [:map [:user-query [:string {:min 1}]]
                                                                            [:claim {:optional true} :string]]})
                    api-util/build-rag-skill-params (fn [& _] {})
                    invoke/invoke-rag (fn [{:keys [execution-scope]}]
                                        {:status :complete :response (str "answer in " (:tenant execution-scope)) :chunks []
                                         :queries [] :search-attribution {} :diagnostics {} :raw-result {} :error nil})]
        (setup-config/ensure-pipeline-config-definitions!)
        (setup-config/ensure-system-config-definitions!)
        (setup-config/ensure-skill-config-definitions!)
        (doseq [t ["kt" "ku"]]
          (config-bootstrap/bootstrap-runtime-tree! conn {:tenant t :agent-id agent-id :master-key mk
                                                         :runtime-values {"skills.query-planner.prompt" (marker t)}})
          (dataset! conn t (str "ds-" t)))
        (dataset! conn "kt" "ds-kt2")
        (fetch-chunk-context/register!)
        (let [mint (fn [n opts] (:api-key (api-keys/create-api-key! conn n "test" (merge {:scopes #{:query}} opts))))]
          (reset! !keys {:A (mint "A" {:dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]})
                         :B (mint "B" {})
                         :C (mint "C" {})
                         ;; config-only grants
                         :D (mint "D" {:allowed-config-keys [{:root :dataset :tenant "kt" :dataset-config-key "ds-kt"}]})
                         :R (mint "R" {:allowed-config-keys [{:root :runtime :tenant "kt" :runtime-config-key "default-runtime"}]})
                         :E (mint "E" {:dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt2"}]
                                       :allowed-config-keys [{:root :dataset :tenant "kt" :dataset-config-key "ds-kt"}]})})
          (mark-all-tenants! conn (:C @!keys)))
        (f))
      (finally (d/release conn)))))

(use-fixtures :each with-two-tenants)

(def ^:private api (-> routes/api-router routes/wrap-api-key-auth wrap-params))

(defn- call
  ([k method uri body] (call k method uri body {}))
  ([k method uri body headers]
   (let [r (api (cond-> {:request-method method :uri (first (str/split uri #"\?"))
                         :query-string (second (str/split uri #"\?"))
                         :headers (merge {"x-api-key" (get @!keys k) "content-type" "application/json" "x-user-id" "u1"} headers)}
                  body (assoc :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8")))))]
     {:status (:status r) :body (str (:body r))})))

(defn- convo!
  "A fresh conversation of end user u1 in tenant `t`, carrying t's marker."
  [t]
  (let [conn @!conn
        {:keys [conversation-id]} (db/transact-new-msg-thread conn "builtin/rag-agent" "u1" nil t)]
    (db/rename-convo-topic conn conversation-id (str "topic " (marker t)))
    conversation-id))

(defn- conversations
  "The store's conversations, as `#{[id tenant topic]}`: equal before and after a refused write."
  []
  (set (d/q '[:find ?id ?t ?topic :where [?c :conversation/id ?id]
              [(get-else $ ?c :conversation/tenant "") ?t] [(get-else $ ?c :conversation/topic "") ?topic]]
            @@!conn)))

(defn- mcp-call [k t]
  (let [body {:jsonrpc "2.0" :id 1 :method "tools/call"
              :params {:name tool :arguments {:tenant t :dataset_config_key (str "ds-" t) :query "q"}}}]
    (call k :post "/api/mcp" body {"mcp-protocol-version" "2026-07-28" "mcp-method" "tools/call" "mcp-name" tool
                                   "accept" "application/json, text/event-stream"})))

(def ^:private doors
  "Every API-key route the census declares `:derived`, keyed as the census keys
   it, as `{:call (fn [key tenant] response) :write? bool :listing? bool}`."
  {[:get "/api/config/:root/nodes"]
   {:call (fn [k t _] (call k :get (str "/api/config/dataset/nodes?tenant=" t) nil))}
   [:post "/api/runtime/config/resolve"]
   {:call (fn [k t _] (call k :post "/api/runtime/config/resolve" {:tenant t :runtimeConfigKey "default-runtime" :agentId agent-id}))}
   [:post "/api/dataset/config/resolve"]
   {:call (fn [k t _] (call k :post "/api/dataset/config/resolve" {:tenant t :datasetConfigKey (str "ds-" t)}))}
   [:get "/api/conversations"]
   {:prepare convo! :call (fn [k t _] (call k :get (str "/api/conversations?tenant=" t) nil))}
   [:post "/api/conversations"]
   {:write? true :call (fn [k t _] (call k :post "/api/conversations" {:tenant t :agentId "builtin/rag-agent" :title (marker t)}))}
   [:get "/api/conversations/:id"]
   {:prepare convo! :call (fn [k t id] (call k :get (str "/api/conversations/" id "?tenant=" t) nil))}
   [:put "/api/conversations/:id"]
   {:write? true :prepare convo! :call (fn [k t id] (call k :put (str "/api/conversations/" id "?tenant=" t) {:title "renamed"}))}
   [:delete "/api/conversations/:id"]
   {:write? true :prepare convo! :call (fn [k t id] (call k :delete (str "/api/conversations/" id "?tenant=" t) nil))}
   [:post "/api/skills/:id/execute"]
   {:call (fn [k t _] (call k :post "/api/skills/enrichment-fetch-chunk-context/execute"
                          {:tenant t :datasetConfigKey (str "ds-" t) :inputs {:chunk-id "c1" :chunks-collection (str t "_chunks")}}))}
   [:post "/api/mcp"]
   {:call (fn [k t _] (mcp-call k t))}
   [:post "/api/tools/call/:tool-name"]
   {:call (fn [k t _] (call k :post (str "/api/tools/call/" tool) {:tenant t :dataset_config_key (str "ds-" t) :query "q"}))}
   [:post "/v1/chat/completions"]
   {:call (fn [k t _] (call k :post "/v1/chat/completions" {:model tool :messages [{:role "user" :content "q"}]
                                                          :tenant t :dataset_config_key (str "ds-" t)}))}
   [:get "/api/datasets"]
   {:listing? true
    :call (fn [k t _] (let [r (call k :get "/api/datasets" nil)]
                      ;; a listing is 200 either way: served = tenant t's dataset is listed
                      (cond-> r (not (str/includes? (:body r) (str "\"ds-" t "\""))) (assoc :status 404))))}
   [:get "/api/datasets/:dataset-id"]
   {:listing? true :call (fn [k t _] (call k :get (str "/api/datasets/ds-" t) nil))}})

(defn- outcome
  "`:served` or `:refused` - some tool doors report a refusal inside a 200."
  [{:keys [status body]}]
  (cond
    (<= 400 status 499) :refused
    (str/includes? body "dataset_not_authorized") :refused
    (<= 200 status 299) :served
    :else [:unexpected status body]))

(defn- expected [{:keys [listing?]} k t]
  (case k
    :A (if (= "kt" t) :served :refused)
    :B :refused
    :C (if listing? :refused :served)))

(deftest the-matrix-covers-every-declared-route
  (is (= (census/derived-routes) (set (keys doors)))
      "a route the census declares :derived with no matrix row, or a row for an undeclared route"))

(deftest every-door-serves-and-refuses-by-the-one-decision
  (doseq [[route {:keys [call write? prepare] :or {prepare (constantly nil)} :as door}] doors
          k [:A :B :C]
          t ["kt" "ku"]
          :let [want (expected door k t)
                ctx (prepare t)
                before (when (and write? (= :refused want)) (conversations))
                r (call k t ctx)]]
    (is (= want (outcome r)) (str (pr-str route) " " k " → " t ": " (:status r) " " (subs (:body r) 0 (min 300 (count (:body r))))))
    (when (= :refused want)
      (is (not (str/includes? (:body r) (marker t))) (str (pr-str route) " " k " → " t ": the refusal carries the tenant's data"))
      (when write?
        (is (= before (conversations)) (str (pr-str route) " " k " → " t ": a REFUSED write changed the store"))))))

;; =============================================================================
;; A config-node grant
;; =============================================================================

(def ^:private dataset-axis-refusal "API key is not allowed to access the selected dataset")

(deftest a-config-grant-grants-its-tenant-and-only-its-node
  (testing "a config-only key's tenant is granted"
    (is (= 200 (:status ((:call (doors [:get "/api/config/:root/nodes"])) :D "kt" nil))) "D → kt's nodes")
    (is (= 403 (:status ((:call (doors [:get "/api/config/:root/nodes"])) :D "ku" nil))) "D → ku")
    (is (= 200 (:status (call :R :post "/api/runtime/config/resolve" {:tenant "kt" :runtimeConfigKey "default-runtime" :agentId agent-id})))
        "R → its own runtime node"))
  (testing "on the CONFIG doors, a :dataset grant on that dataset's node satisfies the dataset axis"
    (is (= 200 (:status (call :D :post "/api/dataset/config/resolve" {:tenant "kt" :datasetConfigKey "ds-kt"}))) "D → its own dataset node")
    (let [r (call :D :post "/api/dataset/config/resolve" {:tenant "kt" :datasetConfigKey "ds-kt2"})]
      (is (= 403 (:status r)))
      (is (str/includes? (:body r) dataset-axis-refusal) (str "refused by the DATASET axis, not only the config axis: " (:body r))))
    (let [r (call :R :post "/api/dataset/config/resolve" {:tenant "kt" :datasetConfigKey "ds-kt"})]
      (is (= 403 (:status r)) "a runtime-only key stays off every dataset")
      (is (str/includes? (:body r) dataset-axis-refusal) (:body r)))
    (let [r (call :R :post "/api/runtime/config/resolve" {:tenant "kt" :runtimeConfigKey "default-runtime" :agentId agent-id
                                                            :datasetConfigKey "ds-kt"})]
      (is (= 403 (:status r)) "a runtime-only key's dataset-ref is refused")
      (is (str/includes? (:body r) dataset-axis-refusal) (:body r))))
  (testing "NOT on the data doors - a config grant is config access, not data access"
    (let [r (call :E :post "/api/skills/enrichment-fetch-chunk-context/execute"
                  {:tenant "kt" :datasetConfigKey "ds-kt" :inputs {:chunk-id "c1" :chunks-collection "kt_chunks"}})]
      (is (= 403 (:status r)) "E has a config grant on ds-kt's node and a data grant only on ds-kt2")
      (is (str/includes? (:body r) dataset-axis-refusal) (:body r)))))

;; =============================================================================
;; the loader is handed ONLY the authorized dataset
;; =============================================================================

(defn- loaded-datasets
  "`[response dataset-ids-the-loaders-were-handed]` for `(f)`."
  [f]
  (let [seen (atom [])
        orig-d cfg/load-dataset-config-v2-with-trace
        orig-r cfg/load-runtime-config-v2-with-trace]
    (with-redefs [cfg/load-dataset-config-v2-with-trace (fn [o] (swap! seen conj (:dataset-id o)) (orig-d o))
                  cfg/load-runtime-config-v2-with-trace (fn [o] (swap! seen conj (:dataset-id o)) (orig-r o))]
      [(f) @seen])))

(def ^:private mismatch "is not the dataset it selects")

(deftest p5-dataset-resolve-loads-only-the-authorized-dataset
  (let [ask #(call :A :post "/api/dataset/config/resolve" (merge {:tenant "kt" :datasetConfigKey "ds-kt"} %))]
    (testing "CONTROL: the selected dataset's own id, or none"
      (let [[r seen] (loaded-datasets #(ask {:datasetId "ds-kt"}))]
        (is (= 200 (:status r)) (:body r))
        (is (= ["ds-kt"] seen)))
      (is (= 200 (:status (ask {})))))
    (doseq [[label other] [["another dataset of the same tenant" "ds-kt2"]
                           ["another tenant's dataset" "ds-ku"]
                           ["a dataset that does not exist" "nope"]]]
      (testing label
        (let [[r seen] (loaded-datasets #(ask {:datasetId other}))]
          (is (= 400 (:status r)) (:body r))
          (is (str/includes? (:body r) mismatch) (:body r))
          (is (empty? seen) "no config was loaded"))))))

(deftest p5-runtime-resolve-refuses-a-differing-dataset-id
  (let [ask #(call :A :post "/api/runtime/config/resolve"
                   (merge {:tenant "kt" :runtimeConfigKey "default-runtime" :agentId agent-id :datasetConfigKey "ds-kt"} %))]
    (testing "CONTROL: equal, or absent"
      (let [[r seen] (loaded-datasets #(ask {:datasetId "ds-kt"}))]
        (is (= 200 (:status r)) (:body r))
        (is (= ["ds-kt"] seen)))
      (is (= 200 (:status (ask {})))))
    (doseq [other ["ds-kt2" "ds-ku"]]
      (testing (str "datasetId " other)
        (let [[r seen] (loaded-datasets #(ask {:datasetId other}))]
          (is (= 400 (:status r)) (:body r))
          (is (str/includes? (:body r) mismatch) (:body r))
          (is (empty? seen)))))))

(deftest p5-each-loader-call-takes-the-authorized-binding
  ;; A census by CODE over the two config handlers: the dataset the loader is
  ;; handed is the `authorized-dataset-id` binding, never a param read.
  (let [src (slurp (io/file "src/digdir/api/routes/datasets.clj"))
        forms (binding [*read-eval* false
                        *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                            (currentNS [_] 'user) (resolveClass [_ s] s)
                                            (resolveAlias [_ s] s) (resolveVar [_ s] s))]
                (with-open [r (java.io.PushbackReader. (java.io.StringReader. src))]
                  (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} r))))))
        handler (fn [nm] (some #(when (and (seq? %) (= 'defn (first %)) (= nm (second %))) %) forms))
        loader-calls (fn [form loader]
                       (filter #(and (seq? %) (= loader (first %))) (tree-seq coll? seq form)))
        dataset-args (fn [call] (keep (fn [x] (when (and (seq? x) (= 'assoc (first x)) (= :dataset-id (second x))) (nth x 2)))
                                      (tree-seq coll? seq call)))
        literal-args (fn [call] (keep #(when (map? %) (get % :dataset-id)) (tree-seq coll? seq call)))]
    (doseq [[h loader] [['resolve-dataset-config-handler 'cfg/load-dataset-config-v2-with-trace]
                        ['resolve-runtime-config-handler 'cfg/load-runtime-config-v2-with-trace]]]
      (let [calls (loader-calls (handler h) loader)]
        (is (= 1 (count calls)) (str h ": PREMISE: one loader call"))
        (is (= #{'authorized-dataset-id} (set (concat (mapcat dataset-args calls) (mapcat literal-args calls))))
            (str h ": the loader's :dataset-id is the authorized binding"))))))
