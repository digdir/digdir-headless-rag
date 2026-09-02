(ns digdir.tools.diagnostics
  "Deterministic, machine-readable retrieval diagnostics for bb tasks."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [digdir.agents.policy :as agents-policy]
            [digdir.api.context :as api-ctx]
            [digdir.api.routes :as routes]
            [digdir.config.db :as config-db]
            [digdir.pipeline.collections :as collections]
            [digdir.rag.auto-filter :as auto-filter]
            [digdir.rag.core :as rag]
            [digdir.skills.api :as skills-api]
            [digdir.skills.builtin.retrieval :as retrieval-skill]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]))

(def ^:dynamic *in-process?*
  "When true, diagnostics are running inside a long-lived JVM (the
   dev-server's playground / the `:builtin/enrichment-eval-suite`
   skill) and `cleanup!` must NOT release the shared Datahike
   connection — every other consumer of the conn (config DB, agent
   loop, playground UI) would lose its db value and start throwing
   `Connection has been released.` The CLI entry points (bb tasks)
   leave this false; the eval-suite skill rebinds it to true."
  false)

(defn- cleanup!
  [conn]
  (when-not *in-process?*
    (try
      (when conn
        (d/release conn))
      (catch Throwable _))
    (shutdown-agents)))

(defn- key-compare [a b]
  (compare (str a) (str b)))

(defn- canonicalize
  [x]
  (cond
    (map? x)
    (into (sorted-map-by key-compare)
          (map (fn [[k v]] [k (canonicalize v)]))
          x)

    (set? x)
    (->> x
         (map canonicalize)
         (sort-by pr-str)
         vec)

    (sequential? x)
    (mapv canonicalize x)

    :else x))

(defn- emit!
  "Print the canonical EDN form of `m` to stdout and return `m`.
   Returning the value lets in-process callers (e.g. dev skills that
   wrap a benchmark fn) recover the structured result without having
   to capture and re-parse stdout."
  [m]
  (prn (canonicalize m))
  m)

(defn- emit-progress!
  [line]
  (binding [*out* *err*]
    (println line)
    (flush)))

(declare compact-throwable)

(defn- parse-int
  [s default]
  (try
    (Integer/parseInt (str s))
    (catch Exception _ default)))

(defn- parse-float
  [s default]
  (try
    (Double/parseDouble (str s))
    (catch Exception _ default)))

(defn- parse-bool
  [s default]
  (let [v (str/lower-case (str (or s "")))]
    (cond
      (#{"true" "1" "yes" "y"} v) true
      (#{"false" "0" "no" "n"} v) false
      :else default)))

(defn- parse-edn-safe
  [s]
  (when (and s (not (str/blank? s)))
    (edn/read-string s)))

(defn- cli-opt-raw
  "Return the raw value for a CLI flag, or default if missing."
  [cli-opts flag default]
  (loop [xs (seq cli-opts)]
    (if (empty? xs)
      default
      (let [[x y & _more] xs]
        (if (= flag x)
          (or y default)
          (recur (rest xs)))))))

(defn- cli-opt
  "Return parsed CLI option value for flag, or default if missing."
  [cli-opts flag parse-fn default]
  (let [raw (cli-opt-raw cli-opts flag nil)]
    (if (nil? raw)
      default
      (parse-fn raw default))))

(defmacro with-silenced-output
  [& body]
  `(binding [*out* (java.io.StringWriter.)
             *err* (java.io.StringWriter.)]
     ~@body))

(defn- normalize-string-vec
  [v]
  (cond
    (nil? v) []
    (string? v) [(str/trim v)]
    (sequential? v) (->> v (map str) (map str/trim) (remove str/blank?) vec)
    :else [(str v)]))

(defn- sort-hits
  [hits]
  (->> hits
       (sort-by (juxt :chunk-id :doc-num :title))
       vec))

(defn- preview-around
  [content needle]
  (let [content (or content "")
        needle (or needle "")
        content-lc (str/lower-case content)
        needle-lc (str/lower-case needle)
        idx (str/index-of content-lc needle-lc)
        start (max 0 (if idx (- idx 90) 0))
        end (min (count content) (+ (if idx (+ idx (count needle)) 0) 90))
        raw (subs content start end)
        compact (str/replace raw #"\s+" " ")]
    (str/trim compact)))

(defn- infra-error
  [service message data]
  {:error-type :infra-unavailable
   :service service
   :message message
   :data data})

(defn- typesense-preflight-error
  [opts]
  (let [ts-settings (ts-utils/make-ts-settings opts)
        uri (:uri ts-settings)
        key (:key ts-settings)]
    (cond
      (str/blank? (or uri ""))
      (infra-error :typesense
                   "Typesense preflight failed: missing Typesense URI"
                   {:tenant (:tenant opts)
                    :tenant-config-key (:tenant-config-key opts)})

      (str/blank? (or key ""))
      (infra-error :typesense
                   "Typesense preflight failed: missing Typesense API key"
                   {:tenant (:tenant opts)
                    :tenant-config-key (:tenant-config-key opts)
                    :uri uri})

      :else
      (try
        (let [health (with-silenced-output
                       (ts-client/health ts-settings))
              ok? (true? (:ok health))]
          (when-not ok?
            (infra-error :typesense
                         "Typesense preflight failed: health check returned unhealthy status"
                         {:tenant (:tenant opts)
                          :tenant-config-key (:tenant-config-key opts)
                          :uri uri
                          :health health})))
        (catch Exception e
          (infra-error :typesense
                       (str "Typesense preflight failed: " (.getMessage e))
                       {:tenant (:tenant opts)
                        :tenant-config-key (:tenant-config-key opts)
                        :uri uri
                        :exception-type (str (type e))}))))))

(defn- normalize-search-types
  [search-types]
  (->> (or search-types [])
       (map (fn [x] (if (keyword? x) x (keyword (str x)))))
       set))

(defn- as-name
  [v]
  (if (nil? v) "" (name v)))

(defn- normalize-config-root
  [root]
  (let [root-name (as-name root)]
    (when-not (str/blank? root-name)
      (keyword root-name))))

(defn- apply-config-root+key!
  "For diagnostic tasks that accept `<tenant> <config-root> <config-key>` positionally:
   when config-root is :dataset (the only supported root) and dataset-config-key
   isn't already set, map config-key → dataset-config-key on `target`.

   Throws when config-root is present but not :dataset, so the caller gets a
   clear error instead of a silent fallthrough. `tool-label` is prefixed to
   the error message so the user knows which tool rejected them."
  ([target] (apply-config-root+key! target "Diagnostic tool"))
  ([{:keys [config-root config-key dataset-config-key] :as target} tool-label]
   (let [root (normalize-config-root config-root)]
     (when (and root (not= root :dataset))
       (throw (ex-info (str tool-label " require config-root :dataset")
                       {:config-root root
                        :supported-roots [:dataset]})))
     (cond-> target
       (and (nil? dataset-config-key)
            (= root :dataset)
            (some? config-key))
       (assoc :dataset-config-key config-key)))))

(def ^:private default-agent-id "builtin/agent-rag-agent")

(defn- normalize-diagnostics-dataset-ref
  [{:keys [dataset-ref tenant dataset-config-key tenant-config-key environment config-key]}]
  (or (api-ctx/normalize-dataset-ref dataset-ref)
      (let [tenant (as-name tenant)
            dataset-config-key (as-name (or dataset-config-key
                                            config-key
                                            environment
                                            tenant-config-key))]
        (when (and (not (str/blank? tenant))
                   (not (str/blank? dataset-config-key)))
          {:tenant tenant
           :dataset-config-key dataset-config-key}))))

(defn- normalize-agent-id
  [agent-id]
  (let [agent-id (as-name agent-id)]
    (when-not (str/blank? agent-id)
      agent-id)))

(defn- diagnostic-target
  [{:keys [dataset-ref agent-id]}]
  (cond-> {:dataset-ref dataset-ref}
    agent-id (assoc :agent-id agent-id)))

(defn- resolve-diagnostics-context!
  [{:keys [agent-id tenant-config-key runtime-config-key] :as opts} & {:keys [require-agent? fallback-agent-id]}]
  (let [conn (or (config-db/get-conn) (throw (ex-info "No DB connection" {})))
        dataset-ref (or (normalize-diagnostics-dataset-ref opts)
                        (throw (ex-info "Missing required arguments"
                                        {:required [:dataset-ref]})))
        {:keys [config dataset-config]} (api-ctx/resolve-dataset-context-by-ref! dataset-ref)
        config (or config dataset-config)
        collection-names (collections/get-or-generate-collection-names config)
        agent-id (or (normalize-agent-id agent-id)
                     (when require-agent?
                       (or fallback-agent-id default-agent-id)))
        _ (when (and require-agent? (str/blank? (or agent-id "")))
            (throw (ex-info "Missing required arguments"
                            {:required [:dataset-ref :agent-id]})))
        agent (when agent-id
                (api-ctx/load-agent! agent-id))
        execution-policy (when agent
                           (agents-policy/resolve-execution-policy agent))
        _ (when (and agent
                     (seq (:allowed-dataset-scopes execution-policy))
                     (not (agents-policy/allows-dataset-scope? agent dataset-ref)))
            (throw (ex-info "Selected agent is not allowed to access the selected dataset"
                            {:dataset-ref dataset-ref
                             :agent-id agent-id})))
        dataset-config-key (:dataset-config-key dataset-ref)
        effective-tenant-config-key (or (when-not (str/blank? tenant-config-key) tenant-config-key)
                                        dataset-config-key)
        effective-runtime-config-key (or (when-not (str/blank? runtime-config-key) runtime-config-key)
                                         effective-tenant-config-key)]
    {:conn conn
     :dataset-ref dataset-ref
     :agent-id agent-id
     :execution-policy execution-policy
     :skill-graph-id (or (:default-skill-graph execution-policy) "builtin/agent-rag-graph-bundled")
     :pipeline-config config
     :dataset-config config
     :collection-names collection-names
     :opts {:tenant (:tenant dataset-ref)
            :dataset-config-key dataset-config-key
            :tenant-config-key effective-tenant-config-key
            :runtime-config-key effective-runtime-config-key}}))

(declare read-benchmark-suite
         percentile
         mean
         rank-position
         candidate-position
         reciprocal-rank)

(defn chunk-find
  [{:keys [tenant dataset-config-key tenant-config-key environment
           needle cli-opts] :as target}]
  (let [needle (or needle "")
        cli-opts (vec (or cli-opts []))
        limit (cli-opt cli-opts "--limit" parse-int 50)
        exact (cli-opt cli-opts "--exact" parse-bool false)
        effective-params {:needle needle
                          :limit limit
                          :exact exact}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Chunk diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? needle))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :needle]})))
            {:keys [conn collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [chunks-collection docs-collection]} collection-names
                response (with-silenced-output
                           (ts-client/multi-search
                            (ts-utils/make-ts-settings opts)
                            {:searches [{:collection chunks-collection
                                         :q needle
                                         :query_by "content_markdown,metadata"
                                         :include_fields (str "chunk_id,doc_num,content_markdown,metadata,$"
                                                              docs-collection "(title)")
                                         :per_page limit
                                         :page 1
                                         :sort_by "_text_match:desc"
                                         :prioritize_exact_match false
                                         :drop_tokens_threshold 5}]}
                            {}))
                result0 (get-in response [:results 0])
                total-hits (or (:found result0) 0)
                all-hits (->> (get result0 :hits)
                              (map (fn [hit]
                                     (let [doc (:document hit)
                                           content (or (:content_markdown doc) "")
                                           exact-match? (str/includes? (str/lower-case content)
                                                                       (str/lower-case needle))]
                                       {:chunk-id (:chunk_id doc)
                                        :doc-num (:doc_num doc)
                                        :title (or (get-in doc [(keyword docs-collection) :title]) "")
                                        :preview (preview-around content needle)
                                        :exact-match? exact-match?})))
                              vec)
                exact-hits-v (filterv :exact-match? all-hits)
                exact-hits (count exact-hits-v)
                selected-hits (if exact exact-hits-v all-hits)
                selected-hits (->> selected-hits
                                   (map #(dissoc % :exact-match?))
                                   sort-hits)]
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :found? (pos? (if exact exact-hits (count selected-hits)))
                           :total-hits total-hits
                           :exact-hits exact-hits
                           :hits selected-hits})))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (let [dataset-ref @!dataset-ref]
          (emit! (cond-> {:effective-params effective-params
                          :error {:message (.getMessage t)
                                  :type (str (type t))}}
                   dataset-ref (assoc :dataset-ref dataset-ref))))))))

(defn capture-rerank-language-candidates
  "Capture deterministic retrieved candidate pools for bilingual rerank comparisons.

   Usage:
   bb capture-rerank-language-candidates <tenant> <dataset-config-key> --suite <file.edn>
     [--out server/test/fixtures/rerank/language_pairs_arsverk_candidates.edn]
     [--limit 120]
     [--retrieve-top-k 100]
     [--max-per-document 10]
     [--query-aware-boost true|false]
     [--no-auto-filter]"
  [{:keys [tenant dataset-config-key tenant-config-key environment cli-opts] :as target}]
  (let [cli-opts (vec (or cli-opts []))
        suite-file (cli-opt-raw cli-opts "--suite" nil)
        out-file (cli-opt-raw cli-opts "--out" "server/test/fixtures/rerank/language_pairs_arsverk_candidates.edn")
        limit (cli-opt cli-opts "--limit" parse-int 120)
        retrieve-top-k (cli-opt cli-opts "--retrieve-top-k" parse-int 100)
        max-per-document (cli-opt cli-opts "--max-per-document" parse-int 10)
        query-aware-boost (cli-opt cli-opts "--query-aware-boost" parse-bool true)
        no-auto-filter (boolean (some #(= "--no-auto-filter" %) cli-opts))
        effective-params {:suite suite-file
                          :out out-file
                          :limit limit
                          :retrieve-top-k retrieve-top-k
                          :max-per-document max-per-document
                          :query-aware-boost query-aware-boost
                          :auto-filter-enabled (not no-auto-filter)}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Rerank language capture diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? suite-file))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :suite]})))
            cases (read-benchmark-suite suite-file)
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [docs-collection chunks-collection phrases-collection]} collection-names
                capture-start (System/nanoTime)
                captured-cases
                (mapv
                 (fn [{:keys [id pair-id language source-slice query golden-chunk-ids] :as _case-def}]
                   (try
                     (let [planned-queries (let [prompt (:prompt-query-relax pipeline-config)]
                                             (if (str/blank? prompt)
                                               [query]
                                               (let [messages [{:message/role :user :message/text query}]
                                                     generated (with-silenced-output
                                                                 (rag/query-relaxation tenant prompt messages nil))
                                                     normalized (normalize-string-vec generated)]
                                                 (if (seq normalized) normalized [query]))))
                           res (with-silenced-output
                                 (retrieval-skill/execute-retrieval
                                  {:inputs {:queries planned-queries
                                            :docs-collection docs-collection
                                            :chunks-collection chunks-collection
                                            :phrases-collection phrases-collection}
                                   :parameters {:limit limit
                                                :retrieve-top-k retrieve-top-k
                                                :max-per-document max-per-document
                                                :query-aware-boost query-aware-boost
                                                :auto-filter (not no-auto-filter)}
                                   :skill-params {:tenant (:tenant opts)
                                                  :tenant-config-key (:tenant-config-key opts)}}))
                           outputs (:outputs res)
                           merged-candidates (vec (or (:merged-candidates outputs) []))
                           retrieved (vec (or (:chunks outputs) []))]
                       {:id id
                        :pair-id pair-id
                        :language language
                        :source-slice source-slice
                        :query query
                        :queries planned-queries
                        :golden-chunk-ids golden-chunk-ids
                        :search-attribution (:search-attribution outputs)
                        :merged-chunk-ids (mapv :chunk_id merged-candidates)
                        :retrieved-chunk-ids (mapv :chunk_id retrieved)
                        :error nil})
                     (catch Throwable t
                       {:id id
                        :pair-id pair-id
                        :language language
                        :source-slice source-slice
                        :query query
                        :golden-chunk-ids golden-chunk-ids
                        :error {:message (.getMessage t)
                                :type (str (type t))}})))
                 cases)
                fixture {:capture {:captured-at (str (java.time.Instant/now))
                                   :dataset-ref dataset-ref
                                   :suite suite-file
                                   :collection-names collection-names
                                   :effective-params effective-params
                                   :elapsed-ms (/ (- (System/nanoTime) capture-start) 1e6)}
                         :cases captured-cases}
                errors (filterv :error captured-cases)]
            (io/make-parents out-file)
            (spit out-file (with-out-str (pprint/pprint fixture)))
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :output out-file
                           :counts {:cases (count captured-cases)
                                    :captured (count (remove :error captured-cases))
                                    :errors (count errors)}
                           :errors (mapv #(select-keys % [:id :query :error]) errors)})))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (let [dataset-ref @!dataset-ref]
          (emit! (cond-> {:effective-params effective-params
                          :error {:message (.getMessage t)
                                  :type (str (type t))}}
                   dataset-ref (assoc :dataset-ref dataset-ref))))))))

(defn- slice-summary
  "Aggregate per-slice metrics from benchmark result rows, keyed by `slice-key`
   (e.g. :language or :source-slice). Rows whose slice value is nil are grouped
   under the nil bucket so the caller can see unlabeled coverage."
  [slice-key results]
  (let [clean (filterv #(and (nil? (:error %)) (seq (:golden %))) results)
        grouped (group-by slice-key clean)]
    (into (sorted-map-by key-compare)
          (map (fn [[slice-val rows]]
                 (let [golden (map first (map :golden rows))
                       ranks (->> golden (map :rerank-position) (remove nil?) vec)
                       context-ranks (->> golden (map :context-position) (remove nil?) vec)]
                   [slice-val {:cases (count rows)
                               :golden-present-in-retrank (count (filter :rerank-position golden))
                               :golden-present-in-context (count (filter :context-position golden))
                               :mrr (mean (map reciprocal-rank ranks))
                               :mean-rank (mean ranks)
                               :p50-rank (percentile ranks 50)
                               :p95-rank (percentile ranks 95)
                               :p50-context-rank (percentile context-ranks 50)}]))
               grouped))))

(defn rerank-language-benchmark
  "Run rerank-only benchmark from captured candidate pools and compare NO/EN behavior.

   Usage:
   bb rerank-language-benchmark <tenant> <dataset-config-key> --fixture <file.edn>
     [--top-k 30]
     [--context-top-k 30]
     [--context-min-chunks 8]
     [--context-relative-score-threshold 0.85]
     [--max-acceptable-rank 10]
     [--require-context true|false]
     [--fail-on-gate true|false]"
  [{:keys [tenant dataset-config-key tenant-config-key environment cli-opts] :as target}]
  (let [cli-opts (vec (or cli-opts []))
        fixture-file (cli-opt-raw cli-opts "--fixture" nil)
        top-k (cli-opt cli-opts "--top-k" parse-int 30)
        context-top-k (cli-opt cli-opts "--context-top-k" parse-int 30)
        context-min-chunks (cli-opt cli-opts "--context-min-chunks" parse-int nil)
        context-relative-threshold (cli-opt cli-opts "--context-relative-score-threshold" parse-float nil)
        max-acceptable-rank (cli-opt cli-opts "--max-acceptable-rank" parse-int 10)
        require-context (cli-opt cli-opts "--require-context" parse-bool false)
        fail-on-gate (cli-opt cli-opts "--fail-on-gate" parse-bool false)
        effective-params {:fixture fixture-file
                          :top-k top-k
                          :context-top-k context-top-k
                          :context-min-chunks context-min-chunks
                          :context-relative-score-threshold context-relative-threshold
                          :max-acceptable-rank max-acceptable-rank
                          :require-context require-context
                          :fail-on-gate fail-on-gate}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Rerank language benchmark diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? fixture-file))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :fixture]})))
            fixture (edn/read-string {:readers {'sorted/map identity}}
                                     (-> fixture-file io/file slurp))
            cases (vec (or (:cases fixture) []))
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [docs-collection chunks-collection]} collection-names
                suite-start (System/nanoTime)
                results
                (mapv
                 (fn [{:keys [id pair-id language source-slice query golden-chunk-ids merged-chunk-ids retrieved-chunk-ids] :as _case-def}]
                   (try
                     (let [merged-ids (mapv str (or merged-chunk-ids []))
                           retrieved-ids (mapv str (or retrieved-chunk-ids []))
                           candidate-ids (if (seq merged-ids) merged-ids retrieved-ids)
                           golden-ids (mapv str (or golden-chunk-ids []))
                           candidates (->> candidate-ids
                                           (map-indexed (fn [idx chunk-id]
                                                          {:chunk_id chunk-id
                                                           :rank (- (count candidate-ids) idx)
                                                           :index idx
                                                           :hit-count 1
                                                           :search-types #{:snapshot}}))
                                           vec)
                           retrieved (with-silenced-output
                                       (rag/retrieve-chunks-by-id
                                        docs-collection
                                        chunks-collection
                                        candidates
                                        (assoc opts :retrieve-top-k (count candidates))))
                           rerank-params {:tenant tenant
                                          :translated_user_query query
                                          :docsCollectionName docs-collection
                                          :rerankTopkChunks top-k
                                          :rerankMaxChunkLength (or (:rerank-max-chunk-length pipeline-config) 1000)
                                          :rerankMaxLength (or (:rerank-max-total-length pipeline-config) 10000)
                                          :contextTopkChunks context-top-k
                                          :contextMinChunks context-min-chunks
                                          :contextRelativeScoreThreshold context-relative-threshold
                                          :contextMaxChunkLength (or (:context-max-chunk-length pipeline-config) 1000)
                                          :maxContextLength (or (:context-max-total-length pipeline-config) 8000)
                                          :promptRagGenerate nil}
                           rerank-res (with-silenced-output
                                        (rag/rerank-chunks (vec retrieved) rerank-params))
                           full-reranked (or (:reranked-chunks rerank-res)
                                             (:used-chunks rerank-res)
                                             [])
                           reranked (->> full-reranked
                                         (map-indexed (fn [idx chunk]
                                                        {:chunk-id (:chunk_id chunk)
                                                         :position (inc idx)
                                                         :rerank-score (:rerank-score chunk)}))
                                         vec)
                           context (->> (:used-docs rerank-res)
                                        (map-indexed (fn [idx doc]
                                                       {:chunk-id (get-in doc [:metadata :source])
                                                        :position (inc idx)}))
                                        vec)
                           golden-eval (->> golden-ids
                                            (map (fn [gold-id]
                                                   (let [merged-pos (candidate-position
                                                                     (mapv (fn [chunk-id]
                                                                             {:chunk_id chunk-id})
                                                                           merged-ids)
                                                                     gold-id)
                                                         retrieved-pos (candidate-position candidates gold-id)
                                                         rerank-pos (rank-position reranked gold-id)
                                                         context-pos (rank-position context gold-id)
                                                         pass-rank? (or (nil? max-acceptable-rank)
                                                                        (and rerank-pos
                                                                             (<= rerank-pos max-acceptable-rank)))
                                                         pass-context? (or (not require-context)
                                                                           (some? context-pos))]
                                                     {:chunk-id gold-id
                                                      :merged-position merged-pos
                                                      :retrieved-position retrieved-pos
                                                      :candidate-position retrieved-pos
                                                      :rerank-position rerank-pos
                                                      :context-position context-pos
                                                      :pass-rank pass-rank?
                                                      :pass-context pass-context?})))
                                            vec)
                           primary-rank (some-> golden-eval first :rerank-position)
                           pass? (every? (fn [g] (and (:pass-rank g) (:pass-context g))) golden-eval)]
                       {:id id
                        :pair-id pair-id
                        :language language
                        :source-slice source-slice
                        :query query
                        :stage-counts {:merged (count (or merged-chunk-ids []))}
                        :counts {:input-candidates (count candidates)
                                 :retrieved (count retrieved)
                                 :reranked (count reranked)
                                 :context (count context)}
                        :golden golden-eval
                        :primary-metrics {:rank primary-rank
                                          :reciprocal-rank (reciprocal-rank primary-rank)}
                        :pass pass?
                        :error nil})
                     (catch Throwable t
                       {:id id
                        :pair-id pair-id
                        :language language
                        :source-slice source-slice
                        :query query
                        :pass false
                        :error {:message (.getMessage t)
                                :type (str (type t))}})))
                 cases)
                suite-ms (/ (- (System/nanoTime) suite-start) 1e6)
                clean-results (filterv #(nil? (:error %)) results)
                failures (filterv (fn [r] (not (:pass r))) results)
                all-ranks (->> clean-results
                               (map (comp :rank :primary-metrics))
                               (remove nil?)
                               vec)
                all-rr (->> clean-results
                            (map (comp :reciprocal-rank :primary-metrics))
                            vec)
                summary {:cases (count cases)
                         :cases-succeeded (count clean-results)
                         :cases-failed (count (remove :pass results))
                         :mrr (mean all-rr)
                         :mean-rank (mean all-ranks)
                         :p50-rank (percentile all-ranks 50)
                         :p95-rank (percentile all-ranks 95)
                         :latency-ms {:suite-total suite-ms}
                         :gate-pass (empty? failures)}
                output (merge (diagnostic-target ctx)
                              {:effective-params effective-params
                               :summary summary
                               :summary-by-language (slice-summary :language results)
                               :summary-by-source-slice (slice-summary :source-slice results)
                               :failures (->> failures
                                              (mapv (fn [f]
                                                      {:id (:id f)
                                                       :pair-id (:pair-id f)
                                                       :language (:language f)
                                                       :source-slice (:source-slice f)
                                                       :query (:query f)
                                                       :golden (:golden f)
                                                       :error (:error f)})))
                               :results results})]
            (emit! output)
            (when (and fail-on-gate (not (:gate-pass summary)))
              (throw (ex-info "Rerank language benchmark gate failed"
                              {:summary summary
                               :benchmark-gate-failure true}))))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (if (-> t ex-data :benchmark-gate-failure)
          (throw t)
          (let [dataset-ref @!dataset-ref]
            (emit! (cond-> {:effective-params effective-params
                            :error {:message (.getMessage t)
                                    :type (str (type t))}}
                     dataset-ref (assoc :dataset-ref dataset-ref)))))))))

(defn retrieve-debug
  [{:keys [tenant dataset-config-key tenant-config-key environment user-query cli-opts] :as target}]
  (let [user-query (or user-query "")
        cli-opts (vec (or cli-opts []))
        provided-queries-raw (cli-opt-raw cli-opts "--queries" nil)
        no-auto-filter (boolean (some #(= "--no-auto-filter" %) cli-opts))
        limit (cli-opt cli-opts "--limit" parse-int 120)
        provided-queries (normalize-string-vec (parse-edn-safe provided-queries-raw))
        effective-params {:user-query user-query
                          :limit limit
                          :provided-queries? (boolean (seq provided-queries))
                          :auto-filter-enabled (not no-auto-filter)}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Retrieval diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? user-query))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :user-query]})))
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [docs-collection chunks-collection phrases-collection]} collection-names
                planned-queries
                (if (seq provided-queries)
                  provided-queries
                  (let [prompt (:prompt-query-relax pipeline-config)]
                    (if (str/blank? prompt)
                      [user-query]
                      (let [messages [{:message/role :user :message/text user-query}]
                            generated (with-silenced-output
                                        (rag/query-relaxation tenant prompt messages nil))
                            normalized (normalize-string-vec generated)]
                        (if (seq normalized) normalized [user-query])))))
                detected-filter
                (when (and (not no-auto-filter) (seq planned-queries))
                  (with-silenced-output
                    (auto-filter/detect-query-filters planned-queries docs-collection opts)))

                run-search
                (fn [filter-by]
                  (let [dedupe-hits (fn [hits]
                                      (->> hits
                                           (group-by :chunk_id)
                                           (map (fn [[_ hs]]
                                                  (first (sort-by (juxt (comp - #(double (or % 0)) :rank)
                                                                         :index)
                                                                  hs))))
                                           (sort-by :index)
                                           vec))
                        phrase-hits (-> (with-silenced-output
                                          (rag/lookup-search-phrases-similar
                                           phrases-collection docs-collection planned-queries filter-by
                                           (assoc opts :limit limit)))
                                        dedupe-hits)
                        metadata-hits (-> (with-silenced-output
                                            (rag/search-chunks-by-metadata
                                             chunks-collection docs-collection planned-queries filter-by
                                             (assoc opts :limit limit)))
                                          dedupe-hits)
                        content-hits (-> (with-silenced-output
                                           (rag/search-chunks-by-content
                                            chunks-collection docs-collection planned-queries filter-by
                                            (assoc opts :limit limit)))
                                         dedupe-hits)
                        merged (with-silenced-output
                                 (rag/merge-chunk-search-results
                                  (map #(assoc % :search-type :phrase) phrase-hits)
                                  metadata-hits
                                  content-hits))]
                    {:phrase-hits (vec phrase-hits)
                     :metadata-hits (vec metadata-hits)
                     :content-hits (vec content-hits)
                     :merged (vec merged)}))

                first-pass (run-search detected-filter)
                auto-filter-fallback (and (some? detected-filter)
                                          (empty? (:merged first-pass)))
                final-pass (if auto-filter-fallback
                             (run-search nil)
                             first-pass)
                merged-out (->> (:merged final-pass)
                                (map (fn [m]
                                       {:chunk-id (:chunk_id m)
                                        :rank (:rank m)
                                        :search-types (->> (:search-types m)
                                                           (map name)
                                                           sort
                                                           vec)
                                        :hit-count (:hit-count m)
                                        :type-ranks (:type-ranks m)}))
                                (sort-by (juxt (comp - :hit-count)
                                               (comp - (fn [x] (double (or x 0))) :rank)
                                               :chunk-id))
                                vec)
                output-filter (if auto-filter-fallback nil detected-filter)]
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params (assoc effective-params
                                                    :auto-filter-fallback auto-filter-fallback)
                           :queries planned-queries
                           :filter output-filter
                           :stage-counts {:phrase (count (:phrase-hits final-pass))
                                          :metadata (count (:metadata-hits final-pass))
                                          :content (count (:content-hits final-pass))
                                          :merged (count (:merged final-pass))}
                           :merged merged-out})))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (let [dataset-ref @!dataset-ref]
          (emit! (cond-> {:effective-params effective-params
                          :error {:message (.getMessage t)
                                  :type (str (type t))}}
                   dataset-ref (assoc :dataset-ref dataset-ref))))))))

(defn- read-retrieve-debug-file
  [path]
  (edn/read-string {:readers {'sorted/map identity}}
                   (-> path io/file slurp)))

(defn rerank-debug
  [{:keys [tenant dataset-config-key tenant-config-key environment user-query cli-opts] :as target}]
  (let [user-query (or user-query "")
        cli-opts (vec (or cli-opts []))
        from-file (cli-opt-raw cli-opts "--from-retrieve-debug" nil)
        top-k (cli-opt cli-opts "--top-k" parse-int 100)
        context-top-k (cli-opt cli-opts "--context-top-k" parse-int 30)
        context-min-chunks (cli-opt cli-opts "--context-min-chunks" parse-int nil)
        context-relative-threshold (cli-opt cli-opts "--context-relative-score-threshold" parse-float nil)
        effective-params {:user-query user-query
                          :from-retrieve-debug from-file
                          :top-k top-k
                          :context-top-k context-top-k
                          :context-min-chunks context-min-chunks
                          :context-relative-score-threshold context-relative-threshold}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Rerank diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref)
                        (str/blank? user-query) (str/blank? from-file))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :user-query :from-retrieve-debug]})))
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [docs-collection chunks-collection]} collection-names
                retrieve-debug (read-retrieve-debug-file from-file)
                merged-candidates (vec (or (:merged retrieve-debug) []))
                candidates (->> merged-candidates
                                (keep-indexed
                                 (fn [idx m]
                                   (when-let [chunk-id (:chunk-id m)]
                                     {:chunk_id chunk-id
                                      :rank (or (:rank m) 0)
                                      :index idx
                                      :hit-count (or (:hit-count m) 0)
                                      :search-types (normalize-search-types (:search-types m))
                                      :type-ranks (or (:type-ranks m) {})})))
                                vec)
                retrieved (with-silenced-output
                            (rag/retrieve-chunks-by-id
                             docs-collection
                             chunks-collection
                             candidates
                             (assoc opts :retrieve-top-k top-k)))
                rerank-params {:tenant tenant
                               :translated_user_query user-query
                               :docsCollectionName docs-collection
                               :rerankTopkChunks top-k
                               :rerankMaxChunkLength (or (:rerank-max-chunk-length pipeline-config) 1000)
                               :rerankMaxLength (or (:rerank-max-total-length pipeline-config) 10000)
                               :contextTopkChunks context-top-k
                               :contextMinChunks context-min-chunks
                               :contextRelativeScoreThreshold context-relative-threshold
                               :contextMaxChunkLength (or (:context-max-chunk-length pipeline-config) 1000)
                               :maxContextLength (or (:context-max-total-length pipeline-config) 8000)
                               :promptRagGenerate nil}
                rerank-res (with-silenced-output
                             (rag/rerank-chunks (vec retrieved) rerank-params))
                full-reranked (or (:reranked-chunks rerank-res)
                                  (:used-chunks rerank-res)
                                  [])
                reranked (->> full-reranked
                              (map-indexed (fn [idx chunk]
                                             {:chunk-id (:chunk_id chunk)
                                              :rerank-score (:rerank-score chunk)
                                              :position (inc idx)}))
                              vec)
                context (->> (:used-docs rerank-res)
                             (map-indexed (fn [idx doc]
                                            {:chunk-id (get-in doc [:metadata :source])
                                             :position (inc idx)}))
                             vec)]
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :input-candidates (count candidates)
                           :reranked reranked
                           :context context})))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (let [dataset-ref @!dataset-ref]
          (emit! (cond-> {:effective-params effective-params
                          :error {:message (.getMessage t)
                                  :type (str (type t))}}
                   dataset-ref (assoc :dataset-ref dataset-ref))))))))

(defn- percentile
  [xs p]
  (when (seq xs)
    (let [sorted (vec (sort xs))
          idx (int (Math/ceil (* (/ p 100.0) (count sorted))))
          idx (max 1 idx)]
      (nth sorted (dec idx)))))

(defn- mean
  [xs]
  (when (seq xs)
    (/ (reduce + xs) (double (count xs)))))

(defn- rank-position
  [entries chunk-id]
  (some (fn [[idx entry]]
          (when (= chunk-id (:chunk-id entry))
            (inc idx)))
        (map-indexed vector entries)))

(defn- candidate-position
  [entries chunk-id]
  (some (fn [[idx entry]]
          (when (= chunk-id (:chunk_id entry))
            (inc idx)))
        (map-indexed vector entries)))

(defn- reciprocal-rank
  [rank]
  (if (and rank (pos? rank))
    (/ 1.0 rank)
    0.0))

(defn- normalize-benchmark-case
  [idx m]
  (let [query (or (:query m) (:user-query m))
        golden-ids (->> (concat
                         (when-let [id (:golden-chunk-id m)] [id])
                         (when-let [id (:target-chunk-id m)] [id])
                         (or (:golden-chunk-ids m) [])
                         (or (:target-chunk-ids m) []))
                        (map str)
                        (remove str/blank?)
                        distinct
                        vec)
        language (some-> (or (:language m) (:lang m))
                         name
                         str/lower-case
                         keyword)
        source-slice (some-> (or (:source-slice m) (:source m))
                             name
                             str/lower-case
                             keyword)
        pair-id (some-> (or (:pair-id m) (:pair m))
                        str)]
    {:id (or (:id m) (str "case-" (inc idx)))
     :query (when query (str query))
     :golden-chunk-ids golden-ids
     :language language
     :source-slice source-slice
     :pair-id (or pair-id (or (:id m) (str "case-" (inc idx))))}))

(defn- read-benchmark-suite
  [path]
  (let [raw (-> path io/file slurp edn/read-string)
        cases (cond
                (map? raw) (:cases raw)
                (sequential? raw) raw
                :else nil)]
    (when-not (seq cases)
      (throw (ex-info "Benchmark suite must be a non-empty vector/list or {:cases [...]}"
                      {:path path})))
    (->> cases
         (map-indexed (fn [idx c]
                        (cond
                          (string? c) {:id (str "case-" (inc idx))
                                       :query c
                                       :golden-chunk-ids []}
                          (map? c) (normalize-benchmark-case idx c)
                          :else nil)))
         (remove nil?)
         vec)))

(defn- normalize-agent-budget-case
  [idx m]
  (let [base (normalize-benchmark-case idx m)]
    (assoc base
           :expected-answer-pattern (some-> (or (:expected-answer-pattern m)
                                                (:answer-pattern m))
                                           str)
           :budget-dimension (some-> (or (:budget-dimension m)
                                         (:dimension m))
                                     name
                                     str/lower-case
                                     keyword)
           :require-improvement? (boolean (or (:require-improvement? m)
                                              (:must-improve? m)))
           :current-budget (or (:current-budget m) {})
           :relaxed-budget (or (:relaxed-budget m) {}))))

(defn- read-agent-budget-suite
  [path]
  (let [raw (-> path io/file slurp edn/read-string)
        cases (cond
                (map? raw) (:cases raw)
                (sequential? raw) raw
                :else nil)]
    (when-not (or (vector? cases)
                  (list? cases))
      (throw (ex-info "Agent budget suite must be a vector/list or {:cases [...]}"
                      {:path path})))
    (->> cases
         (map-indexed (fn [idx c]
                        (cond
                          (map? c) (normalize-agent-budget-case idx c)
                          :else nil)))
         (remove nil?)
         vec)))

(defn- build-agent-skill-params
  [pipeline-config params]
  (assoc (routes/build-rag-skill-params pipeline-config params)
         :builtin/agent
         (into {}
               (filter (comp some? val))
               (select-keys params [:model
                                    :temperature
                                    :max-iterations
                                    :max-search-passes
                                    :max-read-operations
                                    :max-read-content-length]))))

(defn- answer-matches?
  [expected-pattern response]
  (if (str/blank? (or expected-pattern ""))
    true
    (boolean (re-find (re-pattern expected-pattern)
                      (or response "")))))

(def ^:private inspection-search-pass-limit 3)
(def ^:private inspection-search-chunk-limit 3)
(def ^:private inspection-top-chunk-limit 5)
(def ^:private inspection-read-limit 3)
(def ^:private inspection-read-chunk-limit 3)

(defn- merge-present
  [base extra]
  (merge base (into {} (remove (comp nil? val) extra))))

(def ^:private compact-throwable-max-depth 8)

(defn- compact-throwable
  ([t]
   (compact-throwable t 0))
  ([t depth]
   (when t
     (let [data (ex-data t)
           cause (.getCause t)]
       (merge-present
        {:message (.getMessage t)
         :type (str (type t))}
        {:data data
         :step-id (:step-id data)
         :step-error (:error data)
         :cause (when (and cause
                           (< depth compact-throwable-max-depth)
                           (not (identical? cause t)))
                  (compact-throwable cause (inc depth)))
         :cause-truncated? (when (and cause
                                     (or (>= depth compact-throwable-max-depth)
                                         (identical? cause t)))
                            true)})))))

(defn- compact-chunk-summary
  [chunk-summary]
  (when (map? chunk-summary)
    (merge-present
     {:chunk-id (:chunk-id chunk-summary)}
     {:doc-num (:doc-num chunk-summary)
      :chunk-index (:chunk-index chunk-summary)
      :title (:title chunk-summary)
      :content-length (:content-length chunk-summary)})))

(defn- search-chunk-index
  [search-history]
  (reduce (fn [idx {:keys [chunk-summaries]}]
            (reduce (fn [acc chunk-summary]
                      (if-let [chunk-id (:chunk-id chunk-summary)]
                        (update acc chunk-id #(or % (compact-chunk-summary chunk-summary)))
                        acc))
                    idx
                    (or chunk-summaries [])))
          {}
          (or search-history [])))

(defn- inspect-search-history
  [search-history]
  (->> (or search-history [])
       (take inspection-search-pass-limit)
       (map-indexed (fn [idx {:keys [queries result-count new-count fallback? chunk-summaries]}]
                      (merge-present
                       {:pass (inc idx)
                        :queries (vec (or queries []))
                        :result-count (or result-count 0)}
                       {:new-count new-count
                        :fallback? (when fallback? true)
                        :top-chunks (->> (or chunk-summaries [])
                                         (take inspection-search-chunk-limit)
                                         (mapv compact-chunk-summary))})))
       vec))

(defn- inspect-top-retrieved-chunks
  [search-history]
  (->> (or search-history [])
       (map-indexed (fn [idx {:keys [chunk-summaries]}]
                      (map #(assoc % :first-seen-pass (inc idx)) (or chunk-summaries []))))
       (mapcat identity)
       (reduce (fn [acc chunk-summary]
                 (if-let [chunk-id (:chunk-id chunk-summary)]
                   (update acc chunk-id
                           #(or %
                                (merge-present
                                 {:chunk-id chunk-id
                                  :first-seen-pass (:first-seen-pass chunk-summary)}
                                 {:doc-num (:doc-num chunk-summary)
                                  :chunk-index (:chunk-index chunk-summary)
                                  :title (:title chunk-summary)
                                  :content-length (:content-length chunk-summary)})))
                   acc))
               {})
       vals
       (sort-by (juxt :first-seen-pass :doc-num :chunk-index :chunk-id))
       (take inspection-top-chunk-limit)
       vec))

(defn- compact-read-entry
  [chunk-index read-entry operation]
  (let [returned-chunks (->> (or (:returned-chunk-ids read-entry) [])
                             (take inspection-read-chunk-limit)
                             (mapv (fn [chunk-id]
                                     (or (get chunk-index chunk-id)
                                         {:chunk-id chunk-id}))))]
    (merge-present
     {:operation operation
      :mode (:mode read-entry)
      :returned-count (or (:returned-count read-entry) 0)
      :content-length (or (:content-length read-entry) 0)}
     {:chunk-ids (when (seq (:chunk-ids read-entry))
                   (vec (take inspection-read-chunk-limit (:chunk-ids read-entry))))
      :doc-num (:doc-num read-entry)
      :chunk-range (:chunk-range read-entry)
      :returned-chunks returned-chunks})))

(defn- inspect-read-history
  [search-history read-history]
  (let [chunk-index (search-chunk-index search-history)]
    (->> (or read-history [])
         (take inspection-read-limit)
         (map-indexed (fn [idx read-entry]
                        (compact-read-entry chunk-index read-entry (inc idx))))
         vec)))

(defn- inspect-largest-read
  [search-history read-history]
  (let [chunk-index (search-chunk-index search-history)]
    (when-let [[idx read-entry] (->> (or read-history [])
                                     (map-indexed vector)
                                     (sort-by (fn [[idx read-entry]]
                                                [(- (or (:content-length read-entry) 0))
                                                 idx]))
                                     first)]
      (compact-read-entry chunk-index read-entry (inc idx)))))

(defn- build-agent-profile-inspection
  [agent-outputs]
  (let [search-history (or (:search-history agent-outputs) [])
        read-history (or (:read-history agent-outputs) [])]
    {:searches (inspect-search-history search-history)
     :top-retrieved-chunks (inspect-top-retrieved-chunks search-history)
     :reads (inspect-read-history search-history read-history)
     :largest-read (inspect-largest-read search-history read-history)}))

(defn- run-agent-budget-profile
  [{:keys [pipeline-config collection-names opts dataset-ref agent-id execution-policy skill-graph-id graph-variant retrieval-params-override]}
   {:keys [query golden-chunk-ids expected-answer-pattern]}
   profile-label
   budget]
  (let [{:keys [docs-collection chunks-collection phrases-collection]} collection-names
        skill-params (cond-> (build-agent-skill-params pipeline-config budget)
                       (seq (:allowed-dataset-scopes execution-policy))
                       (assoc :allowed-dataset-scopes (:allowed-dataset-scopes execution-policy))
                       graph-variant
                       (assoc :graph-variant graph-variant)
                       (seq retrieval-params-override)
                       (update :builtin/retrieval merge retrieval-params-override))
        ;; The agent-rag-graph reads tenant from the :ambient-ctx-opts graph
        ;; input ([:opts :tenant]); without it the agent's first LLM call hits
        ;; "cfg/get requires an explicit :tenant" at iteration 0. invoke-rag
        ;; constructs the same map (see digdir.skills.invoke); the bare
        ;; run-skill-graph path here previously omitted it, which is why
        ;; bare-JVM agent-budget-benchmark runs failed (and the self-improve
        ;; eval needed a hardcoded-tenant workaround).
        inputs {:user-query query
                :query query
                :docs-collection docs-collection
                :chunks-collection chunks-collection
                :phrases-collection phrases-collection
                :conversation-history []
                :ambient-ctx-opts {:opts (cond-> {:tenant (:tenant opts)
                                                  :dataset-config-key (:dataset-config-key opts)
                                                  :skill-params skill-params}
                                           agent-id    (assoc :agent-id agent-id)
                                           dataset-ref (assoc :dataset-ref dataset-ref))}}
        run-start (System/nanoTime)
        result (with-silenced-output
                 (skills-api/run-skill-graph
                  (keyword skill-graph-id)
                  inputs
                  (api-ctx/assoc-execution-scope
                   {:tenant (:tenant opts)
                    :tenant-config-key (:tenant-config-key opts)
                    :runtime-config-key (:runtime-config-key opts)
                    :skill-graph skill-graph-id
                    :skill-params skill-params}
                   {:tenant (:tenant opts)
                    :dataset-config-key (:dataset-config-key opts)
                    :dataset-ref dataset-ref
                    :agent-id agent-id})))
        run-ms (/ (- (System/nanoTime) run-start) 1e6)
        outputs (or (:outputs result) {})
        agent-step (get-in result [:step-results :agent])
        agent-outputs (or (:outputs agent-step) outputs)
        agent-metadata (or (:metadata agent-step) {})
        response (:response agent-outputs)
        chunk-ids (set (map :chunk_id (or (:chunks agent-outputs) [])))
        search-errors (vec (or (:search-errors agent-outputs) []))
        golden-present? (if (seq golden-chunk-ids)
                          (boolean (some chunk-ids golden-chunk-ids))
                          true)
        answer-pass? (answer-matches? expected-answer-pattern response)
        latest-decision (last (or (:sufficiency-decisions agent-outputs) []))
        budget-state (:budget-state agent-outputs)
        profile-error (when (seq search-errors)
                        {:type :search-backend-failure
                         :message (or (:error-message (last search-errors))
                                      "Search backend failure")
                         :search-errors search-errors})
        pass? (and answer-pass?
                   golden-present?
                   (nil? profile-error)
                   (not (true? (:insufficient-context agent-outputs))))]
    {:profile profile-label
     :query query
     :response response
     :answer-pass answer-pass?
     :golden-present golden-present?
     :pass pass?
     :search-errors search-errors
     :budget-state budget-state
     :search-passes (or (:search-passes agent-metadata)
                        (count (or (:search-history agent-outputs) [])))
     :read-operations (or (:read-operations agent-metadata)
                          (count (or (:read-history agent-outputs) [])))
     :read-content-length (or (:read-content-length agent-metadata)
                              (:read-content-length-used budget-state)
                              0)
     :inspection (build-agent-profile-inspection agent-outputs)
     :latest-status (:status latest-decision)
     :latest-action (:action latest-decision)
     :durations-ms {:total run-ms}
     :error profile-error}))

(defn agent-budget-summary
  [results]
  (let [error-count (count (filter :error results))
        non-error-results (filterv #(nil? (:error %)) results)
        current-pass (count (filter #(get-in % [:current :pass]) non-error-results))
        relaxed-pass (count (filter #(get-in % [:relaxed :pass]) non-error-results))
        improved (count (filter :improved non-error-results))
        regressed (count (filter :regressed non-error-results))
        required-improvement-cases (count (filter :require-improvement? non-error-results))
        missing-required-improvement (count (filter #(and (:require-improvement? %)
                                                          (not (:improved %)))
                                                   non-error-results))
        stable-pass (count (filter :stable-pass non-error-results))
        stable-fail (count (filter :stable-fail non-error-results))]
    {:cases (count results)
     :error-count error-count
     :current-pass current-pass
     :relaxed-pass relaxed-pass
     :improved improved
     :regressed regressed
     :required-improvement-cases required-improvement-cases
     :missing-required-improvement missing-required-improvement
     :stable-pass stable-pass
     :stable-fail stable-fail
     :gate-pass (and (zero? regressed)
                     (zero? error-count)
                     (zero? missing-required-improvement))}))

(defn agent-budget-benchmark
  [{:keys [tenant dataset-config-key tenant-config-key runtime-config-key environment agent-id cli-opts] :as target}]
  (let [agent-id (or (normalize-agent-id agent-id)
                     default-agent-id)
        cli-opts (vec (or cli-opts []))
        suite-file (cli-opt-raw cli-opts "--suite" nil)
        fail-on-gate (cli-opt cli-opts "--fail-on-gate" parse-bool false)
        progress? (cli-opt cli-opts "--progress" parse-bool false)
        tenant-config-key (or (cli-opt-raw cli-opts "--tenant-config-key" nil)
                              tenant-config-key)
        runtime-config-key (or (cli-opt-raw cli-opts "--runtime-config-key" nil)
                               runtime-config-key)
        ;; Phase 2.5.E — eval gate variant selector. Passed through to
        ;; :builtin/agent via skill-params :graph-variant; defaults to
        ;; :imperative (current loop). Other values: :bundled, :faithful.
        graph-variant-str (cli-opt-raw cli-opts "--graph-variant" nil)
        graph-variant (when graph-variant-str (keyword graph-variant-str))
        ;; Phase B.5 — EDN-literal retrieval-skill parameter overrides,
        ;; merged into the per-profile `:builtin/retrieval` skill-params
        ;; (see run-agent-budget-profile). Mirrors the same flag on
        ;; rerank-benchmark. Today the only consumer is the enrichment
        ;; smoke run that needs to inject :enrichment-search-targets;
        ;; future phases can pipe more knobs through without re-touching
        ;; this CLI surface.
        retrieval-params-raw (cli-opt-raw cli-opts "--retrieval-params" nil)
        retrieval-params-override (or (parse-edn-safe retrieval-params-raw) {})
        effective-params {:suite suite-file
                          :fail-on-gate fail-on-gate
                          :progress progress?
                          :tenant-config-key tenant-config-key
                          :runtime-config-key runtime-config-key
                          :graph-variant graph-variant
                          :retrieval-params retrieval-params-override}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Agent budget diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? suite-file) (str/blank? (or agent-id "")))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :agent-id :suite]})))
            cases (read-agent-budget-suite suite-file)
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :runtime-config-key runtime-config-key
                                           :environment environment
                                           :agent-id agent-id}
                                          :require-agent? true)]
        (try
          (if-let [preflight-error (typesense-preflight-error opts)]
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :error preflight-error}))
            (let [profile-ctx {:pipeline-config pipeline-config
                              :collection-names collection-names
                              :opts opts
                              :dataset-ref (:dataset-ref ctx)
                              :agent-id (:agent-id ctx)
                              :execution-policy (:execution-policy ctx)
                              :skill-graph-id (:skill-graph-id ctx)
                              :graph-variant graph-variant
                              :retrieval-params-override retrieval-params-override}
                  results (mapv (fn [{:keys [id query budget-dimension require-improvement?
                                             current-budget relaxed-budget] :as case-def}]
                                  (let [case-start (System/nanoTime)]
                                    (when progress?
                                      (emit-progress!
                                       (str "PROGRESS case=" id
                                            " dimension=" (or (some-> budget-dimension name) "-")
                                            " require-improvement=" (boolean require-improvement?)
                                            " status=starting"
                                            " query=" (pr-str query))))
                                  (try
                                    (let [current-start (System/nanoTime)
                                          current (run-agent-budget-profile profile-ctx
                                                                           case-def
                                                                           :current
                                                                           current-budget)
                                          current-ms (/ (- (System/nanoTime) current-start) 1e6)
                                          _ (when progress?
                                              (emit-progress!
                                               (str "PROGRESS case=" id
                                                    " profile=current"
                                                    " status=done"
                                                    " pass=" (boolean (:pass current))
                                                    " searches=" (or (:search-passes current) 0)
                                                    " reads=" (or (:read-operations current) 0)
                                                    " chars=" (or (:read-content-length current) 0)
                                                    " ms=" (long current-ms))))
                                          relaxed-start (System/nanoTime)
                                          relaxed (run-agent-budget-profile profile-ctx
                                                                           case-def
                                                                           :relaxed
                                                                           relaxed-budget)
                                          relaxed-ms (/ (- (System/nanoTime) relaxed-start) 1e6)
                                          _ (when progress?
                                              (emit-progress!
                                               (str "PROGRESS case=" id
                                                    " profile=relaxed"
                                                    " status=done"
                                                    " pass=" (boolean (:pass relaxed))
                                                    " searches=" (or (:search-passes relaxed) 0)
                                                    " reads=" (or (:read-operations relaxed) 0)
                                                    " chars=" (or (:read-content-length relaxed) 0)
                                                    " ms=" (long relaxed-ms))))
                                          current-error (:error current)
                                          relaxed-error (:error relaxed)
                                          current-pass (:pass current)
                                          relaxed-pass (:pass relaxed)
                                          case-error (or (when (or current-error relaxed-error)
                                                           {:current current-error
                                                            :relaxed relaxed-error})
                                                         nil)
                                          case-result {:id id
                                                       :query query
                                                       :budget-dimension budget-dimension
                                                       :require-improvement? (boolean require-improvement?)
                                                       :current current
                                                       :relaxed relaxed
                                                       :improved (and (not current-pass) relaxed-pass)
                                                       :regressed (and current-pass (not relaxed-pass))
                                                      :stable-pass (and current-pass relaxed-pass)
                                                      :stable-fail (and (not current-pass) (not relaxed-pass))
                                                      :error case-error}
                                          case-ms (/ (- (System/nanoTime) case-start) 1e6)]
                                      (when progress?
                                        (emit-progress!
                                         (str "PROGRESS case=" id
                                              " status=completed"
                                              " require-improvement=" (boolean require-improvement?)
                                              " improved=" (boolean (:improved case-result))
                                              " regressed=" (boolean (:regressed case-result))
                                              " stable-pass=" (boolean (:stable-pass case-result))
                                              " stable-fail=" (boolean (:stable-fail case-result))
                                              " ms=" (long case-ms))))
                                      case-result)
                                    (catch Throwable t
                                      (when progress?
                                        (emit-progress!
                                         (str "PROGRESS case=" id
                                              " status=error"
                                              " type=" (str (type t))
                                              " message=" (pr-str (.getMessage t)))))
                                      {:id id
                                       :query query
                                       :budget-dimension budget-dimension
                                       :require-improvement? (boolean require-improvement?)
                                       :error (compact-throwable t)}))))
                                cases)
                  summary (agent-budget-summary results)
                  output (merge (diagnostic-target ctx)
                                {:effective-params effective-params
                                 :summary summary
                                 :results results})]
              (emit! output)
              (when (and fail-on-gate (not (:gate-pass summary)))
                (throw (ex-info "Agent budget benchmark gate failed"
                                {:summary summary
                                 :benchmark-gate-failure true})))
              output))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (if (-> t ex-data :benchmark-gate-failure)
          (throw t)
          (let [dataset-ref @!dataset-ref]
            (emit! (cond-> {:agent-id agent-id
                            :effective-params effective-params
                            :error {:message (.getMessage t)
                                    :type (str (type t))}}
                     dataset-ref (assoc :dataset-ref dataset-ref)))))))))

(defn- run-benchmark-case
  [{:keys [tenant pipeline-config collection-names opts no-auto-filter limit top-k context-top-k
           context-min-chunks context-relative-threshold
           max-acceptable-rank require-context retrieval-params]}
   {:keys [id query golden-chunk-ids language source-slice pair-id] :as _case-def}]
  (let [{:keys [docs-collection chunks-collection phrases-collection]} collection-names
        case-start (System/nanoTime)
        planned-queries (let [prompt (:prompt-query-relax pipeline-config)]
                          (if (str/blank? prompt)
                            [query]
                            (let [messages [{:message/role :user :message/text query}]
                                  generated (with-silenced-output
                                              (rag/query-relaxation tenant prompt messages nil))
                                  normalized (normalize-string-vec generated)]
                              (if (seq normalized) normalized [query]))))
        retrieve-start (System/nanoTime)
        retrieval-result
        (with-silenced-output
          (retrieval-skill/execute-retrieval
           {:inputs {:queries planned-queries
                     :docs-collection docs-collection
                     :chunks-collection chunks-collection
                     :phrases-collection phrases-collection}
            :parameters (merge {:limit limit
                                :auto-filter (not no-auto-filter)}
                               retrieval-params)
            :skill-params {:tenant (:tenant opts)
                           :tenant-config-key (:tenant-config-key opts)}}))
        retrieval-outputs (:outputs retrieval-result)
        search-attribution (:search-attribution retrieval-outputs)
        merged-candidates (vec (or (:merged-candidates retrieval-outputs) []))
        retrieved (vec (:chunks retrieval-outputs))
        retrieve-ms (/ (- (System/nanoTime) retrieve-start) 1e6)
        candidates (->> retrieved
                        (map-indexed (fn [idx chunk]
                                       {:chunk_id (:chunk_id chunk)
                                        :rank (or (:original-rank chunk) 0)
                                        :index idx
                                        :hit-count (or (:hit-count chunk) 0)
                                        :search-types (:search-types chunk)
                                        :type-ranks (or (:type-ranks chunk) {})}))
                        vec)
        rerank-start (System/nanoTime)
        rerank-params {:tenant tenant
                       :translated_user_query query
                       :docsCollectionName docs-collection
                       :rerankTopkChunks top-k
                       :rerankMaxChunkLength (or (:rerank-max-chunk-length pipeline-config) 1000)
                       :rerankMaxLength (or (:rerank-max-total-length pipeline-config) 10000)
                       :contextTopkChunks context-top-k
                       :contextMinChunks context-min-chunks
                       :contextRelativeScoreThreshold context-relative-threshold
                       :contextMaxChunkLength (or (:context-max-chunk-length pipeline-config) 1000)
                       :maxContextLength (or (:context-max-total-length pipeline-config) 8000)
                       :promptRagGenerate nil}
        rerank-res (with-silenced-output
                     (rag/rerank-chunks (vec retrieved) rerank-params))
        rerank-ms (/ (- (System/nanoTime) rerank-start) 1e6)
        full-reranked (or (:reranked-chunks rerank-res)
                          (:used-chunks rerank-res)
                          [])
        reranked (->> full-reranked
                      (map-indexed (fn [idx chunk]
                                     {:chunk-id (:chunk_id chunk)
                                      :position (inc idx)
                                      :rerank-score (:rerank-score chunk)}))
                      vec)
        context (->> (:used-docs rerank-res)
                     (map-indexed (fn [idx doc]
                                    {:chunk-id (get-in doc [:metadata :source])
                                     :position (inc idx)}))
                     vec)
        golden-eval (->> golden-chunk-ids
                         (map (fn [gold-id]
                                (let [merged-pos (candidate-position merged-candidates gold-id)
                                      retrieved-pos (candidate-position candidates gold-id)
                                      rerank-pos (rank-position reranked gold-id)
                                      context-pos (rank-position context gold-id)
                                      pass-rank? (or (nil? max-acceptable-rank)
                                                     (and rerank-pos (<= rerank-pos max-acceptable-rank)))
                                      pass-context? (or (not require-context)
                                                        (some? context-pos))]
                                  {:chunk-id gold-id
                                   :merged-position merged-pos
                                   :retrieved-position retrieved-pos
                                   :candidate-position retrieved-pos
                                   :rerank-position rerank-pos
                                   :context-position context-pos
                                   :pass-rank pass-rank?
                                   :pass-context pass-context?})))
                         vec)
        primary-rank (some-> golden-eval first :rerank-position)
        case-ms (/ (- (System/nanoTime) case-start) 1e6)
        case-pass? (every? (fn [g]
                             (and (:pass-rank g) (:pass-context g)))
                           golden-eval)]
    {:id id
     :language language
     :source-slice source-slice
     :pair-id pair-id
     :query query
     :queries planned-queries
     :filter (:filter-applied search-attribution)
     :auto-filter-fallback (:auto-filter-fallback search-attribution)
     :stage-counts {:phrase (or (:phrase search-attribution) 0)
                    :metadata (or (:metadata search-attribution) 0)
                    :content (or (:content search-attribution) 0)
                    :merged (or (:merged search-attribution) 0)}
     :golden-stage-presence
     (->> golden-eval
          (map (fn [g]
                 [(:chunk-id g)
                  {:merged (some? (:merged-position g))
                   :retrieved (some? (:retrieved-position g))
                   :reranked (some? (:rerank-position g))
                   :context (some? (:context-position g))}]))
          (into {}))
     :counts {:input-candidates (count candidates)
              :retrieved (count retrieved)
              :reranked (count reranked)
              :context (count context)}
     :durations-ms {:search nil
                    :retrieve retrieve-ms
                    :rerank rerank-ms
                    :total case-ms}
     :golden golden-eval
     :primary-metrics {:rank primary-rank
                       :reciprocal-rank (reciprocal-rank primary-rank)}
     :pass case-pass?
     :error nil}))

(defn rerank-benchmark
  [{:keys [tenant dataset-config-key tenant-config-key environment cli-opts] :as target}]
  (let [cli-opts (vec (or cli-opts []))
        suite-file (cli-opt-raw cli-opts "--suite" nil)
        retrieve-top-k-opt (cli-opt cli-opts "--retrieve-top-k" parse-int nil)
        max-per-document-opt (cli-opt cli-opts "--max-per-document" parse-int nil)
        query-aware-boost-opt (let [raw (cli-opt-raw cli-opts "--query-aware-boost" nil)]
                                (when (some? raw)
                                  (parse-bool raw false)))
        retrieval-params-raw (cli-opt-raw cli-opts "--retrieval-params" nil)
        top-k (cli-opt cli-opts "--top-k" parse-int 100)
        context-top-k (cli-opt cli-opts "--context-top-k" parse-int 30)
        context-min-chunks (cli-opt cli-opts "--context-min-chunks" parse-int nil)
        context-relative-threshold (cli-opt cli-opts "--context-relative-score-threshold" parse-float nil)
        limit (cli-opt cli-opts "--limit" parse-int 120)
        max-acceptable-rank (cli-opt cli-opts "--max-acceptable-rank" parse-int 10)
        no-auto-filter (boolean (some #(= "--no-auto-filter" %) cli-opts))
        require-context (cli-opt cli-opts "--require-context" parse-bool false)
        fail-on-gate (cli-opt cli-opts "--fail-on-gate" parse-bool false)
        retrieval-params-from-edn (or (parse-edn-safe retrieval-params-raw) {})
        retrieval-params (cond-> retrieval-params-from-edn
                           retrieve-top-k-opt (assoc :retrieve-top-k retrieve-top-k-opt)
                           max-per-document-opt (assoc :max-per-document max-per-document-opt)
                           (some? query-aware-boost-opt) (assoc :query-aware-boost query-aware-boost-opt))
        effective-params {:suite suite-file
                          :top-k top-k
                          :context-top-k context-top-k
                          :context-min-chunks context-min-chunks
                          :context-relative-score-threshold context-relative-threshold
                          :limit limit
                          :retrieval-params retrieval-params
                          :max-acceptable-rank max-acceptable-rank
                          :auto-filter-enabled (not no-auto-filter)
                          :require-context require-context
                          :fail-on-gate fail-on-gate}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Rerank benchmark diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref) (str/blank? suite-file))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :suite]})))
            cases (read-benchmark-suite suite-file)
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (if-let [preflight-error (typesense-preflight-error opts)]
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :error preflight-error}))
            (let [suite-start (System/nanoTime)
                  results (mapv (fn [case-def]
                                  (try
                                    (run-benchmark-case {:tenant tenant
                                                         :pipeline-config pipeline-config
                                                         :collection-names collection-names
                                                         :opts opts
                                                         :no-auto-filter no-auto-filter
                                                         :limit limit
                                                         :top-k top-k
                                                         :context-top-k context-top-k
                                                         :context-min-chunks context-min-chunks
                                                         :context-relative-threshold context-relative-threshold
                                                         :max-acceptable-rank max-acceptable-rank
                                                         :require-context require-context
                                                         :retrieval-params retrieval-params}
                                                        case-def)
                                    (catch Throwable t
                                      {:id (:id case-def)
                                       :language (:language case-def)
                                       :source-slice (:source-slice case-def)
                                       :pair-id (:pair-id case-def)
                                       :query (:query case-def)
                                       :error {:message (.getMessage t)
                                               :type (str (type t))}
                                       :pass false})))
                                cases)
                  suite-ms (/ (- (System/nanoTime) suite-start) 1e6)
                  clean-results (filterv #(nil? (:error %)) results)
                  failures (filterv (fn [r] (not (:pass r))) results)
                  all-primary-ranks (->> clean-results
                                         (map (comp :rank :primary-metrics))
                                         (remove nil?)
                                         vec)
                  all-reciprocal-ranks (->> clean-results
                                            (map (comp :reciprocal-rank :primary-metrics))
                                            vec)
                  golden-evals (->> clean-results (mapcat :golden) vec)
                  with-golden (count (filter seq (map :golden clean-results)))
                  golden-present-in-candidates (count (filter :candidate-position golden-evals))
                  golden-present-in-rerank (count (filter :rerank-position golden-evals))
                  golden-present-in-context (count (filter :context-position golden-evals))
                  gate-pass? (empty? failures)
                  summary {:cases (count cases)
                           :cases-succeeded (count clean-results)
                           :cases-failed (count (remove :pass results))
                           :with-golden with-golden
                           :golden-count (count golden-evals)
                           :golden-present-in-candidates golden-present-in-candidates
                           :golden-present-in-rerank golden-present-in-rerank
                           :golden-present-in-context golden-present-in-context
                           :mrr (mean all-reciprocal-ranks)
                           :mean-rank (mean all-primary-ranks)
                           :p50-rank (percentile all-primary-ranks 50)
                           :p95-rank (percentile all-primary-ranks 95)
                           :latency-ms {:p50-total (percentile (map (comp :total :durations-ms) clean-results) 50)
                                        :p95-total (percentile (map (comp :total :durations-ms) clean-results) 95)
                                        :p50-rerank (percentile (map (comp :rerank :durations-ms) clean-results) 50)
                                        :p95-rerank (percentile (map (comp :rerank :durations-ms) clean-results) 95)
                                        :suite-total suite-ms}
                           :gate-pass gate-pass?}
                  output (merge (diagnostic-target ctx)
                                {:effective-params effective-params
                                 :summary summary
                                 :summary-by-language (slice-summary :language results)
                                 :summary-by-source-slice (slice-summary :source-slice results)
                                 :failures (->> failures
                                                (mapv (fn [f]
                                                        {:id (:id f)
                                                         :language (:language f)
                                                         :source-slice (:source-slice f)
                                                         :query (:query f)
                                                         :golden (:golden f)
                                                         :error (:error f)})))
                                 :results results})]
              (emit! output)
              (when (and fail-on-gate (not gate-pass?))
                (throw (ex-info "Rerank benchmark gate failed"
                                {:summary summary
                                 :benchmark-gate-failure true})))))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (if (-> t ex-data :benchmark-gate-failure)
          (throw t)
          (let [dataset-ref @!dataset-ref]
            (emit! (cond-> {:effective-params effective-params
                            :error {:message (.getMessage t)
                                    :type (str (type t))}}
                     dataset-ref (assoc :dataset-ref dataset-ref)))))))))

(defn capture-isolation-candidates
  "Capture filtered/unfiltered top40/top100 candidate pools for rerank isolation tests.

   Usage:
   bb capture-isolation-candidates <tenant> <dataset-config-key> \"<query>\" \"<golden-chunk-id>\"
     [--out server/test/fixtures/rerank/isolation_candidates.edn]
     [--queries '[\"q1\" \"q2\"]']
     [--limit 120]
     [--retrieve-top-k 100]
     [--max-per-document 50]
     [--query-aware-boost true|false]"
  [{:keys [tenant dataset-config-key tenant-config-key environment user-query golden-chunk-id cli-opts] :as target}]
  (let [user-query (or user-query "")
        golden-chunk-id (or golden-chunk-id "")
        cli-opts (vec (or cli-opts []))
        out-file (cli-opt-raw cli-opts "--out" "server/test/fixtures/rerank/isolation_candidates.edn")
        provided-queries-raw (cli-opt-raw cli-opts "--queries" nil)
        limit (cli-opt cli-opts "--limit" parse-int 120)
        retrieve-top-k (cli-opt cli-opts "--retrieve-top-k" parse-int 100)
        max-per-document (cli-opt cli-opts "--max-per-document" parse-int 50)
        query-aware-boost (cli-opt cli-opts "--query-aware-boost" parse-bool true)
        provided-queries (normalize-string-vec (parse-edn-safe provided-queries-raw))
        effective-params {:user-query user-query
                          :golden-chunk-id golden-chunk-id
                          :out out-file
                          :limit limit
                          :retrieve-top-k retrieve-top-k
                          :max-per-document max-per-document
                          :query-aware-boost query-aware-boost
                          :provided-queries? (boolean (seq provided-queries))}
        !dataset-ref (volatile! nil)]
    (try
      (let [dataset-target (apply-config-root+key! target "Isolation-candidate diagnostics")
            dataset-ref (normalize-diagnostics-dataset-ref dataset-target)
            _ (vreset! !dataset-ref dataset-ref)
            _ (when (or (nil? dataset-ref)
                        (str/blank? user-query)
                        (str/blank? golden-chunk-id))
                (throw (ex-info "Missing required arguments"
                                {:required [:dataset-ref :user-query :golden-chunk-id]})))
            {:keys [conn pipeline-config collection-names opts] :as ctx}
            (resolve-diagnostics-context! {:dataset-ref dataset-ref
                                           :tenant tenant
                                           :dataset-config-key dataset-config-key
                                           :tenant-config-key tenant-config-key
                                           :environment environment})]
        (try
          (let [{:keys [docs-collection chunks-collection phrases-collection]} collection-names
                planned-queries
                (if (seq provided-queries)
                  provided-queries
                  (let [prompt (:prompt-query-relax pipeline-config)]
                    (if (str/blank? prompt)
                      [user-query]
                      (let [messages [{:message/role :user :message/text user-query}]
                            generated (with-silenced-output
                                        (rag/query-relaxation tenant prompt messages nil))
                            normalized (normalize-string-vec generated)]
                        (if (seq normalized) normalized [user-query])))))
                retrieval-params {:limit limit
                                  :retrieve-top-k retrieve-top-k
                                  :max-per-document max-per-document
                                  :query-aware-boost query-aware-boost}
                run-retrieval
                (fn [auto-filter?]
                  (let [res (with-silenced-output
                              (retrieval-skill/execute-retrieval
                               {:inputs {:queries planned-queries
                                         :docs-collection docs-collection
                                         :chunks-collection chunks-collection
                                         :phrases-collection phrases-collection}
                                :parameters (assoc retrieval-params :auto-filter auto-filter?)
                                :skill-params {:tenant (:tenant opts)
                                               :tenant-config-key (:tenant-config-key opts)}}))
                        chunks (vec (get-in res [:outputs :chunks]))]
                    {:chunks chunks
                     :chunk-ids (mapv :chunk_id chunks)}))
                filtered (run-retrieval true)
                unfiltered (run-retrieval false)
                fixture {:query user-query
                         :golden-chunk-id golden-chunk-id
                         :capture {:captured-at (str (java.time.Instant/now))
                                   :dataset-ref dataset-ref
                                   :collection-names collection-names
                                   :effective-params (merge effective-params {:queries planned-queries})}
                         :candidate-sets {:filtered-top40 (vec (take 40 (:chunk-ids filtered)))
                                          :filtered-top100 (vec (take 100 (:chunk-ids filtered)))
                                          :unfiltered-top40 (vec (take 40 (:chunk-ids unfiltered)))
                                          :unfiltered-top100 (vec (take 100 (:chunk-ids unfiltered)))}}]
            (io/make-parents out-file)
            (spit out-file (with-out-str (pprint/pprint fixture)))
            (emit! (merge (diagnostic-target ctx)
                          {:effective-params effective-params
                           :output out-file
                           :counts {:filtered (count (:chunk-ids filtered))
                                    :unfiltered (count (:chunk-ids unfiltered))}
                           :golden-presence {:filtered (boolean (some #(= golden-chunk-id %) (:chunk-ids filtered)))
                                             :unfiltered (boolean (some #(= golden-chunk-id %) (:chunk-ids unfiltered)))}})))
          (finally
            (cleanup! conn))))
      (catch Throwable t
        (let [dataset-ref @!dataset-ref]
          (emit! (cond-> {:effective-params effective-params
                          :error {:message (.getMessage t)
                                  :type (str (type t))}}
                   dataset-ref (assoc :dataset-ref dataset-ref))))))))
