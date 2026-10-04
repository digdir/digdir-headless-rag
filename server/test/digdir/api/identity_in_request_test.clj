(ns digdir.api.identity-in-request-test
  "a skill acts only in the
   scope the door AUTHORIZED.

   a key granted only kt/ds-kt
   executed `enrichment-fetch-chunk-context` with `inputs.tenant \"ku\"`, and the
   server searched ku's Typesense host with ku's admin key and returned ku's
   chunk - also when the collection named was kt's own. Two layers close
   it, and each test says which one refused:
   1. the execute DOOR refuses identity keys anywhere in `inputs`/`parameters`
      (400 `:identity-in-request`), with the request collection-names fix's one walker;
   2. the skills read identity ONLY from skill-params (a census pins it).

   Driven through the real `api-router` and API-key middleware with a REAL key
   (`create-api-key!`). Stubbed: Typesense, at `typesense.client/search`, which
   RECORDS the host it was asked on, and the tenant's Typesense settings, whose
   host names the tenant. The keys are synthetic; nothing asserts on one."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [digdir.api.context :as api-ctx]
            [digdir.api.routes :as routes]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.rag.typesense :as ts-utils]
            [digdir.setup.config :as setup-config]
            [digdir.skills.enrichment.eval-runner :as eval-runner]
            [digdir.skills.enrichment.eval-sweep :as eval-sweep]
            [digdir.skills.enrichment.fetch-chunk-context :as fetch-chunk-context]
            [ring.middleware.params :refer [wrap-params]]
            [typesense.client :as ts-client]))

(def ^:private mk "test-key-for-encryption")
(def ^:private agent-id "builtin/agent-rag-agent")

(def ^:private !searched "The Typesense host of every search the server asked for." (atom []))
(def ^:private !ts-tenants "Every tenant whose Typesense settings were resolved." (atom []))
(def ^:private !key "Key A's plaintext: kt/ds-kt only." (atom nil))

(defn- host-of [tenant] (str "http://FAKE-ts-" tenant ":8108"))

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

(defn- with-two-tenants
  "Tenants kt (ds-kt; runtime nodes default-runtime and other-runtime) and ku
   (ds-ku). Key A: kt/ds-kt, config grants on that dataset and kt's
   default-runtime ONLY."
  [f]
  (let [cfg {:store {:backend :mem :id (str "identity-in-request-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (doto (d/connect cfg) (d/transact {:tx-data db/dh-schema}) config-db/ensure-schema!)]
    (try
      (with-redefs [db/get-conn (constantly conn)
                    config-core/get-master-key (constantly mk)
                    ts-utils/resolve-ts-settings (fn [tenant]
                                                   (swap! !ts-tenants conj tenant)
                                                   {:settings {:uri (host-of tenant) :key (str "SYNTHKEY-" tenant)}})
                    ts-client/search (fn [settings _coll opts]
                                       (swap! !searched conj (:uri settings))
                                       {:hits (if (str/includes? (str (:filter_by opts)) "absent")
                                                []
                                                [{:document {:chunk_id "c1" :doc_num "d1" :url "u"
                                                           :content_markdown (str "CHUNK@" (:uri settings))}}])})]
        (setup-config/ensure-pipeline-config-definitions!)
        (setup-config/ensure-system-config-definitions!)
        (setup-config/ensure-skill-config-definitions!)
        (doseq [[k v] {"default-runtime" "PROMPT-DEFAULT" "other-runtime" "PROMPT-OTHER"}]
          (config-bootstrap/bootstrap-runtime-tree! conn {:tenant "kt" :agent-id agent-id :master-key mk
                                                         :runtime-tenant-config-key k :runtime-label k
                                                         :runtime-values {"skills.query-planner.prompt" v}}))
        (dataset! conn "kt" "ds-kt")
        (dataset! conn "ku" "ds-ku")
        (fetch-chunk-context/register!)
        (eval-sweep/register!)
        (reset! !key (:api-key (api-keys/create-api-key!
                                conn "key-A" "test"
                                {:scopes #{:query}
                                 :dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]
                                 :agent-refs [agent-id]
                                 :allowed-config-keys [{:root :dataset :tenant "kt" :dataset-config-key "ds-kt"}
                                                       {:root :runtime :tenant "kt" :runtime-config-key "default-runtime"}]})))
        (f))
      (finally (d/release conn)))))

(use-fixtures :each with-two-tenants)

(def ^:private api (-> routes/api-router routes/wrap-api-key-auth wrap-params))

(defn- execute!
  "POST /api/skills/<skill>/execute with key A on kt/ds-kt: `{:status :body :hosts :ts-tenants}`."
  [skill inputs params]
  (reset! !searched [])
  (reset! !ts-tenants [])
  (let [body (json/generate-string (cond-> {:inputs inputs :tenant "kt" :datasetConfigKey "ds-kt"}
                                     params (assoc :parameters params)))
        r (api {:request-method :post :uri (str "/api/skills/" skill "/execute")
                :headers {"x-api-key" @!key "content-type" "application/json"}
                :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})]
    {:status (:status r) :body (str (:body r)) :hosts (set @!searched) :ts-tenants (set @!ts-tenants)}))

(def ^:private fetch "enrichment-fetch-chunk-context")

(defn- refused-at-the-door? [r]
  (and (= 400 (:status r))
       (str/includes? (:body r) "names a tenant, dataset or config node")
       (empty? (:hosts r))
       (not (contains? (:ts-tenants r) "ku"))))

;; =============================================================================
;; Layer 1: the DOOR (red at base: 200 with ku's chunk)
;; =============================================================================

(deftest p11-the-door-refuses-a-tenant-in-inputs
  (testing "CONTROL: key A's own dataset, no identity in inputs - 200, searched on kt's host only"
    (let [r (execute! fetch {:chunk-id "c1" :chunks-collection "kt_chunks"} nil)]
      (is (= 200 (:status r)) (:body r))
      (is (= #{(host-of "kt")} (:hosts r)) "PREMISE: the fake records the host")))
  (testing "CONTROL: the key's scope is real - ku's dataset directly is 403"
    (let [body (json/generate-string {:inputs {:chunk-id "c1" :chunks-collection "ku_chunks"} :tenant "ku" :datasetConfigKey "ds-ku"})
          r (api {:request-method :post :uri (str "/api/skills/" fetch "/execute")
                  :headers {"x-api-key" @!key "content-type" "application/json"}
                  :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})]
      (is (= 403 (:status r)))))
  (testing "inputs.tenant ku with ku's collection - refused at the door, ku's Typesense never reached"
    (let [r (execute! fetch {:chunk-id "c1" :chunks-collection "ku_chunks" :tenant "ku"} nil)]
      (is (refused-at-the-door? r) (pr-str r))))
  (testing "inputs.tenant ku with kt's OWN collection - refused (a collection pin cannot stop it)"
    (let [r (execute! fetch {:chunk-id "c1" :chunks-collection "kt_chunks" :tenant "ku"} nil)]
      (is (refused-at-the-door? r) (pr-str r)))))

(deftest the-door-refuses-every-spelling-and-depth
  (doseq [[label inputs params] [["camelCase in inputs" {:chunk-id "c1" :chunks-collection "kt_chunks" "datasetConfigKey" "ds-ku"} nil]
                                 ["snake_case in parameters" {:chunk-id "c1" :chunks-collection "kt_chunks"} {"runtime_config_key" "other-runtime"}]
                                 ["nested under a skill key" {:chunk-id "c1" :chunks-collection "kt_chunks"} {:builtin/retrieval {:dataset-ref {:tenant "ku" :dataset-config-key "ds-ku"}}}]
                                 ["inside a vector" {:chunk-id "c1" :chunks-collection "kt_chunks" :items [{:node-id "x"}]} nil]
                                 ["the dataset id" {:chunk-id "c1" :chunks-collection "kt_chunks" :datasetId "ds-ku"} nil]
                                 ["the legacy tenant-config-key alias" {:chunk-id "c1" :chunks-collection "kt_chunks" :tenant-config-key "ds-ku"} nil]]]
    (testing label
      (is (refused-at-the-door? (execute! fetch inputs params))))))

;; =============================================================================
;; Layer 2: the SKILLS read identity only from skill-params (red at base)
;; =============================================================================

(deftest k2-reads-its-tenant-from-skill-params-only
  ;; Layer 2 alone: the skill called directly, as an internal caller would, so
  ;; the door is not involved.
  (reset! !searched [])
  (reset! !ts-tenants [])
  (let [r (fetch-chunk-context/execute-fetch-chunk-context
           {:inputs {:chunk-id "c1" :chunks-collection "kt_chunks" :tenant "ku"}
            :skill-params {:tenant "kt"}})]
    (is (= "CHUNK@http://FAKE-ts-kt:8108" (get-in r [:outputs :chunk-content])) (pr-str r))
    (is (= #{(host-of "kt")} (set @!searched)))
    (is (not (contains? (set @!ts-tenants) "ku")) "ku's Typesense settings were never resolved")))

(deftest k1-reads-its-tenant-and-dataset-from-skill-params-only
  (let [scope (atom nil)]
    (with-redefs [eval-runner/run-comparison (fn [args] (reset! scope (:execution-scope args)) {:rows []})]
      (eval-sweep/execute-eval-sweep {:inputs {:user-query "q" :chunk-id "c1" :tenant "ku" :dataset-config-key "ds-ku"}
                                      :parameters {:repeats 1 :regression-questions []}
                                      :skill-params {:tenant "kt" :dataset-config-key "ds-kt"}}))
    (is (= {:tenant "kt" :dataset-config-key "ds-kt"} (select-keys @scope [:tenant :dataset-config-key])) (pr-str @scope))))

(def ^:private identity-read-forms
  "Each form a skill can read an identity key from a request-written map with,
   as a regex over one source line. `inputs`/`parameters` are the context keys a
   request writes (`make-execution-context`)."
  (let [ident "(?:tenant|dataset-id|dataset-config-key|tenant-config-key|dataset-ref|runtime-config-key|node-id|dataset_config_key|datasetConfigKey|runtimeConfigKey|datasetRef|datasetId|nodeId)"
        src "(?:inputs|parameters)"]
    [(re-pattern (str "\\(:" ident "\\s+" src "\\)"))
     (re-pattern (str "\\(get(?:-in)?\\s+" src "\\s+\\[?[:\"]" ident "\\b"))
     (re-pattern (str "\\{:keys\\s+\\[[^\\]]*(?<![\\w-])" ident "(?![\\w-])[^\\]]*\\][^}]*\\}\\s+" src "\\b"))
     (re-pattern (str "\\[:" src "\\s+[:\"]" ident "\\b"))]))

(defn- identity-reads [lines]
  (for [[i line] (map-indexed vector lines)
        :when (not (str/starts-with? (str/trim line) ";"))
        :when (some #(re-find % line) identity-read-forms)]
    [(inc i) (str/trim line)]))

(deftest no-skill-reads-identity-from-what-a-request-writes
  (testing "CONTROL: each read form is found when planted"
    (doseq [plant ["(let [{:keys [chunk-id tenant chunks-collection]} inputs"
                   "tenant (or (:tenant inputs) (:tenant skill-params))"
                   "(get inputs :dataset-config-key)"
                   "(get-in ctx [:parameters :runtime-config-key])"
                   ":dataset-ref (or dataset-ref (:dataset-ref inputs) (:dataset-ref skill-params))"]]
      (is (seq (identity-reads [plant])) plant)))
  (testing "no file under skills/ or rag/skills/ has one"
    (let [files (for [d ["src/digdir/skills" "src/digdir/rag/skills"]
                      f (file-seq (io/file d))
                      :when (re-find #"\.cljc?$" (str f))]
                  f)]
      (is (< 20 (count files)) "PREMISE: the census reads the skills")
      (is (= {} (into {} (for [f files :let [hits (identity-reads (str/split-lines (slurp f)))] :when (seq hits)]
                           [(str f) (vec hits)])))))))

;; =============================================================================
;; eval-sweep is not in the production build: pinned by what requires it
;; =============================================================================

(defn- code-forms
  "Every top-level form of the Clojure source `text`, read as CODE (reader
   conditionals allowed, any alias accepted), so a name in a string or a comment
   is not a reference."
  [text]
  (binding [*read-eval* false
            *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                (currentNS [_] 'user)
                                (resolveClass [_ s] s)
                                (resolveAlias [_ s] s)
                                (resolveVar [_ s] s))]
    (with-open [r (java.io.PushbackReader. (java.io.StringReader. text))]
      (doall (take-while #(not= ::eof %) (repeatedly #(read {:read-cond :allow :eof ::eof} r)))))))

(defn- references-ns?
  "Whether `forms` name namespace `ns-sym` in code: the symbol itself (a
   require, a quoted `require`) or a symbol qualified by it (`requiring-resolve`)."
  [forms ns-sym]
  (boolean (some #(and (symbol? %) (or (= % ns-sym) (= (namespace %) (str ns-sym))))
                 (tree-seq coll? seq forms))))

(deftest eval-sweep-is-loaded-only-by-dev-code
  ;; MEASURED under -M:prod: eval-sweep is not in the production
  ;; registry, because only dev code loads its namespace. This pins that cause.
  (let [k1 'digdir.skills.enrichment.eval-sweep
        loaders (fn [root] (for [f (file-seq (io/file root))
                                 :when (re-find #"\.cljc?$" (str f))
                                 :when (not (str/ends-with? (str f) "enrichment/eval_sweep.clj"))
                                 :when (references-ns? (code-forms (slurp f)) k1)]
                             (str f)))]
    (testing "CONTROL: the reference forms count, and a mention does not"
      (is (references-ns? (code-forms "(ns x (:require [digdir.skills.enrichment.eval-sweep :as e]))") k1))
      (is (references-ns? (code-forms "(require 'digdir.skills.enrichment.eval-sweep)") k1))
      (is (references-ns? (code-forms "#?(:clj (requiring-resolve 'digdir.skills.enrichment.eval-sweep/register!))") k1))
      (is (not (references-ns? (code-forms "(ns x \"Promoted out of `digdir.skills.enrichment.eval-sweep`\") ; digdir.skills.enrichment.eval-sweep") k1))))
    (is (seq (loaders "src-dev")) "CONTROL: the census finds the dev graph that loads it")
    (is (empty? (concat (loaders "src") (loaders "src-prod")))
        "nothing the production build loads references the eval-sweep namespace")))

;; =============================================================================
;; the runtime node a context loads is the one whose grant is checked
;; =============================================================================

(deftest p9-a-runtime-node-the-key-was-not-granted-is-never-loaded
  (let [ring-req (select-keys (let [conn (db/get-conn)
                                    info (api-keys/validate-api-key conn @!key)]
                                {:api-key/dataset-scopes (:dataset-scopes info)
                                 :api-key/allowed-config-keys (:allowed-config-keys info)})
                              [:api-key/dataset-scopes :api-key/allowed-config-keys])
        call (fn [runtime-key]
               (try (api-ctx/resolve-request-dataset-context!
                     ring-req {:tenant "kt" :dataset-config-key "ds-kt" :runtime-config-key runtime-key}
                     {:agent-id agent-id})
                    (catch clojure.lang.ExceptionInfo e {:refused (select-keys (ex-data e) [:status])})))]
    (testing "CONTROL: the granted runtime node loads"
      (let [c (call "default-runtime")]
        (is (nil? (:refused c)) (pr-str c))
        (is (= "default-runtime" (:runtime-config-key c)))))
    (testing "another runtime node of the same tenant, not granted - refused 403, not loaded"
      (let [c (call "other-runtime")]
        (is (= {:status 403} (:refused c)) (pr-str (select-keys c [:refused :runtime-config-key])))))))

;; =============================================================================
;; A skill error that carries a Class is its 400, not a 500
;; =============================================================================

(deftest a-skill-error-carrying-a-class-is-its-400
  ;; A chunk that is not found makes the skill throw; its error result carries
  ;; the exception's Class, which the door failed to encode (a 500).
  (let [r (execute! fetch {:chunk-id "absent" :chunks-collection "kt_chunks"} nil)]
    (is (= 400 (:status r)) (:body r))
    (is (str/includes? (:body r) "Chunk not found") "the skill's own error reaches the client")))
