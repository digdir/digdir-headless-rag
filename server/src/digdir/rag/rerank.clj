(ns digdir.rag.rerank
  "Semantic reranking logic for RAG using ColBERT."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [medley.core :as medley]
            [digdir.config.accessor :as cfg]
            [clj-http.client :as http]
            [digdir.rag.formatting :as formatting]))

(defn- rag-debug-logging-enabled? []
  (let [env-val (System/getenv "RAG_DEBUG_LOGGING")]
    (contains? #{"1" "true" "yes" "on"}
               (str/lower-case (str (or env-val ""))))))

(defn- rag-debug-log [msg data]
  (when (rag-debug-logging-enabled?)
    (log/info msg data)))

(defn reranker-configured?
  "Is there a ColBERT reranker to call?

   Blank counts as absent, not as a value: `COLBERT_API_URL=` in a `.env` reads
   as an empty string rather than nil, and that is the SHIPPED state — the
   reranker is infrastructure the product deliberately supplies no value for."
  [rerank-url]
  (not (str/blank? rerank-url)))

(defonce ^:private !reranking-disabled-warned
  ;; Tenants already told. Once per tenant per process, not once per request:
  ;; this is read on every query, and a line per query is noise an operator
  ;; learns to scroll past — which is the same as not saying it.
  (atom #{}))

(defn- warn-reranking-disabled-once!
  "Say once, loudly, that reranking is off — the shape of the placeholder-secret
   boot warning.

   An operator should be able to learn this from the log rather than by
   noticing that answers got worse, which is the failure mode a silent
   degradation would have."
  [tenant]
  (when-not (contains? @!reranking-disabled-warned tenant)
    (swap! !reranking-disabled-warned conj tenant)
    (log/warn
     (str "RERANKING DISABLED for tenant '" tenant "': COLBERT_API_URL is not set. "
          "Results are returned in retrieval order, which is WORSE ORDERING, not "
          "fewer or wrong answers — retrieval, synthesis and citations are "
          "unaffected. This is the expected state on a fresh install; the "
          "reranker is infrastructure this product ships no value for. To enable "
          "it, set COLBERT_API_URL (and COLBERT_API_KEY) and restart."))))

(defn- build-context-doc
  "Build a context document from a search hit for inclusion in the LLM prompt."
  [search-hit docs-collection-kw]
  (let [title (get-in search-hit [docs-collection-kw :title])
        metadata (:metadata search-hit)
        metadata-str (when metadata (formatting/format-metadata-headers metadata))
        source-desc (str "\n```\nTitle: " title
                         (when metadata-str (str "\n" metadata-str))
                         "\n```\n\n")]
    {:page_content (str source-desc (:content_markdown search-hit))
     :metadata     {:source (:chunk_id search-hit)}}))

(defn- accumulate-context-docs
  "Select context documents from candidates, respecting chunk count and length budgets."
  [context-candidate-hits params context-max-chunks]
  (let [max-context-length (:maxContextLength params)
        docs-collection-kw (keyword (:docsCollectionName params))]
    (reduce (fn [{:keys [docs seen length] :as acc} search-hit]
              (if (or (>= length max-context-length)
                      (>= (count docs) context-max-chunks))
                (reduced acc)
                (let [chunk-id (:chunk_id search-hit)]
                  (if (or (nil? (:content_markdown search-hit))
                          (contains? seen chunk-id))
                    acc
                    (let [doc (build-context-doc search-hit docs-collection-kw)
                          new-length (+ length (count (:page_content doc)))
                          new-doc-count (inc (count docs))]
                      (when (>= new-length (:rerankMaxLength params))
                        (rag-debug-log "rerank payload size limit reached"
                                       {:loaded-chunks new-doc-count}))
                      (when (>= new-doc-count context-max-chunks)
                        (rag-debug-log "contextTopkChunks limit reached"
                                       {:loaded-chunks new-doc-count}))
                      {:docs   (conj docs doc)
                       :seen   (conj seen chunk-id)
                       :length new-length})))))
            {:docs [] :seen #{} :length 0}
            context-candidate-hits)))

(defn- tokenize-set
  "Lowercase word/number token set — mirrors retrieval's tokenizer (kept local to
   avoid a retrieval->rerank->retrieval require cycle)."
  [s]
  (when (seq (str s))
    (->> (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")
         (remove str/blank?)
         set)))

(defn- window-starts
  "Candidate window start offsets for a `size`-char window over `n` chars, at 50%
   overlap, always including the tail window so the chunk end is reachable."
  [n size]
  (-> (vec (range 0 (max 1 (- n size)) (max 1 (quot size 2))))
      (conj (max 0 (- n size)))
      distinct))

(defn- score-window
  "{:start :end :tokens} for the `size`-char window of `content` at `start` —
   :tokens = the distinct query tokens it covers."
  [content qtokens n start size]
  (let [end (min n (+ start size))]
    {:start start :end end
     :tokens (set/intersection qtokens (or (tokenize-set (subs content start end)) #{}))}))

(defn best-content-window
  "Select the ~`budget`-char span of `content` whose text maximizes distinct
   query-term coverage, so ColBERT scores the ANSWER-bearing passage instead of
   the chunk's (truncated) head+tail. Sliding char windows at 50% overlap, scored
   by the count of distinct query tokens present; ties keep the earliest window.

   Returns `content` unchanged when it already fits the budget (so short chunks —
   the rerank-clean <=1000-char set — are byte-identical to the non-windowed path),
   and the head when there is no query overlap anywhere (a graceful no-op)."
  [content query budget]
  (let [content (or content "")
        budget (max 1 budget)]
    (if (<= (count content) budget)
      content
      (let [qtokens (tokenize-set query)]
        (if (empty? qtokens)
          (subs content 0 budget)
          (let [n (count content)
                best (reduce (fn [acc s]
                               (let [w (score-window content qtokens n s budget)
                                     sc (count (:tokens w))]
                                 (if (or (nil? acc) (> sc (:score acc)))
                                   {:score sc :start s}
                                   acc)))
                             nil (window-starts n budget))]
            (if (and best (pos? (:score best)))
              (subs content (:start best) (min n (+ (:start best) budget)))
              (subs content 0 budget))))))))

(defn best-content-windows
  "Top-2 refinement of `best-content-window`: up to two non-overlapping ~budget/2
   windows covering the most distinct query terms, concatenated in document order —
   so a multi-passage answer (e.g. corr-01, 6240 chars) reaches ColBERT instead of
   a single mis-placed span.

   Crucially FALLS BACK to the proven single full-budget window whenever a second
   region adds NO new query coverage, so single-passage goldens (the P1 wins) are
   byte-identical to the single-window path — the refinement only fires when a
   distinct second region genuinely helps."
  [content query budget]
  (let [content (or content "")
        budget (max 1 budget)]
    (if (<= (count content) budget)
      content
      (let [qtokens (tokenize-set query)
            single #(best-content-window content query budget)]
        (if (empty? qtokens)
          (single)
          (let [n (count content)
                unit (max 1 (quot budget 2))
                wins (mapv #(score-window content qtokens n % unit) (window-starts n unit))
                w1 (apply max-key #(count (:tokens %)) wins)
                non-ov (remove #(and (< (:start %) (:end w1)) (> (:end %) (:start w1))) wins)
                w2 (when (seq non-ov)
                     (apply max-key #(count (set/difference (:tokens %) (:tokens w1))) non-ov))
                new-cov (if w2 (count (set/difference (:tokens w2) (:tokens w1))) 0)]
            (if (and w2 (pos? new-cov) (pos? (count (:tokens w1))))
              (let [[a b] (sort-by :start [w1 w2])]
                (str (subs content (:start a) (:end a))
                     "\n...\n"
                     (subs content (:start b) (:end b))))
              (single))))))))

(def ^:private default-snippet-budget 220)

(defn content-snippet
  "A short, single-line, query-relevant PREVIEW of `content` for the agent's
   read-decision display — the best ~`budget`-char window (reusing the rerank
   window machinery) with internal whitespace collapsed. Bounded on purpose:
   it surfaces WHY a chunk matched without breaking the metadata-only contract
   (the agent still read_chunks for the full text). Returns \"\" when there is
   no content."
  ([content query] (content-snippet content query default-snippet-budget))
  ([content query budget]
   (let [collapsed (-> (best-content-window (or content "") (or query "") budget)
                       (str/replace #"\s+" " ")
                       str/trim)]
     (if (> (count collapsed) budget)
       (str/trim (subs collapsed 0 budget))
       collapsed))))

(defn rerank-chunks
  "Rerank retrieved chunks using ColBERT API.

   :rerankTopkChunks is the maximum number of chunks to send to ColBERT
   (a cap, not a floor). Defaults to 100 if not provided."
  [retrieved-chunks params]
  (let [tenant (or (:tenant params) (:tenant (:dataset-ref params)))
        rerank-url (cfg/get {:tenant tenant} :services :colbert :api-url)
        rerank-api-key (cfg/get {:tenant tenant} :services :colbert :api-key)
        rerank-api-max-input-length 1000
        rerank-top-k (or (:rerankTopkChunks params) 100)
        rerank-candidates (vec (take rerank-top-k retrieved-chunks))
        partial-prompt (:promptRagGenerate params)
        build-prompt (fn [context-str]
                       (when partial-prompt
                         (-> partial-prompt
                             (str/replace "{context}" context-str)
                             (str/replace "{question}" (:translated_user_query params)))))]
    (if (empty? rerank-candidates)
      {:reranked-chunks []
       :used-chunks []
       :used-docs []
       :full-prompt (build-prompt "")}
      (do
        ;; ⚠️ AN UNCONFIGURED RERANKER DEGRADES; IT DOES NOT THROW (#519).
        ;;
        ;; This used to throw "Environment variable 'COLBERT_API_URL' is
        ;; invalid: ''" when the value was absent — which is the EXPECTED state
        ;; on a fresh install, because the reranker is infrastructure the
        ;; product deliberately ships no value for. `env-bridge` declares it
        ;; `:tier :optional` and its own `:what` says "Retrieval works without
        ;; it, less well". The code disagreed, and the throw took the whole
        ;; skill graph down: a newcomer choosing AI Overview got a failed
        ;; request rather than a slightly worse answer.
        ;;
        ;; Reranking improves ORDERING. Its absence costs quality, not
        ;; availability.
        (when-not (reranker-configured? rerank-url)
          (warn-reranking-disabled-once! tenant))
        (let [user-input (subs (:translated_user_query params)
                               0 (min (count (:translated_user_query params)) rerank-api-max-input-length))
              docs-collection-kw (keyword (:docsCollectionName params))
              ;; Read-snippet self-selection: a bounded query-relevant preview of
              ;; each candidate's content, attached to the reranked chunk so the
              ;; agent's metadata-only display can show WHY a chunk matched. The
              ;; full content is still stripped downstream — only this ~220-char
              ;; window survives. Parallel to `rerank-candidates` by index.
              snippets (mapv (fn [doc]
                               (content-snippet (:content_markdown doc)
                                                (:translated_user_query params)))
                             rerank-candidates)
              ;; The un-reranked result: candidates in their retrieval order,
              ;; carrying snippets but no rerank score or rank.
              ;;
              ;; This is not a new code path. It is the SAME expression the
              ;; empty-response branch below already used, lifted so the
              ;; unconfigured case and the empty-response case cannot drift into
              ;; two different ideas of "un-reranked". Everything downstream then
              ;; runs unchanged: `threshold-enabled?` requires a numeric
              ;; :rerank-score, so with none it falls through to plain top-k
              ;; selection, which is exactly the wanted behaviour.
              unreranked-hits (mapv (fn [c s] (assoc c :snippet s))
                                    rerank-candidates snippets)
              ;; Skipped entirely when there is no reranker: building this body
              ;; runs `best-content-windows` over every candidate, which is real
              ;; work to produce a request nothing will send.
              rerank-body (when (reranker-configured? rerank-url)
                            (json/write-str
                             {:user_input user-input
                              :k (count rerank-candidates)
                            :documents (mapv (fn [doc]
                                               (let [title (get-in doc [docs-collection-kw :title])
                                                     metadata (:metadata doc)
                                                     content (:content_markdown doc)
                                                     max-len (:rerankMaxChunkLength params)
                                                     prefix (str (when title (str "Title: " title "\n\n"))
                                                                 (when metadata (formatting/format-metadata-headers metadata)))]
                                                 (if (:rerankWindowing params)
                                                   ;; Lever A: keep the (short, query-relevant) title/metadata prefix
                                                   ;; in full and give the remaining budget to the best-matching
                                                   ;; content window, so the answer passage of a long chunk reaches
                                                   ;; ColBERT instead of being dropped by head+tail truncation.
                                                   (str prefix
                                                        (best-content-windows content
                                                                              (:translated_user_query params)
                                                                              (max 200 (- (or max-len 1000) (count prefix)))))
                                                   (formatting/truncate-head-tail (str prefix content) max-len))))
                                             rerank-candidates)}))
              ;; nil when there is no reranker, which the `empty?` test below
              ;; then treats exactly as it already treated an empty response —
              ;; ONE degradation path, not a second one that could drift.
              rerank-response-body (when rerank-body
                                     (-> (http/post rerank-url {:body rerank-body
                                                                :content-type :json
                                                                :headers {"X-API-Key" rerank-api-key}})
                                         :body
                                         (json/read-str :key-fn keyword)))
              search-hits-reranked (if (empty? rerank-response-body)
                                     unreranked-hits
                                     (keep (fn [rerank-entry]
                                             (when-let [idx (:index rerank-entry)]
                                               (when (and (number? idx)
                                                          (>= idx 0)
                                                          (< idx (count rerank-candidates)))
                                                 (assoc (nth rerank-candidates idx)
                                                        :rerank-score (:score rerank-entry)
                                                        :rerank-rank (:rank rerank-entry)
                                                        :snippet (nth snippets idx)))))
                                           rerank-response-body))

              context-max-chunks (or (:contextTopkChunks params) 10)
              context-min-chunks (max 1 (min (or (:contextMinChunks params) context-max-chunks)
                                             context-max-chunks))
              relative-threshold (:contextRelativeScoreThreshold params)
              top-score (some-> search-hits-reranked first :rerank-score)
              threshold-enabled? (and (number? relative-threshold)
                                      (number? top-score)
                                      (<= 0.0 (double relative-threshold))
                                      (<= (double relative-threshold) 1.0))
              threshold-score (when threshold-enabled?
                                (* (double top-score) (double relative-threshold)))
              above-threshold (if threshold-enabled?
                                (filterv (fn [chunk]
                                           (let [s (:rerank-score chunk)]
                                             (and (number? s)
                                                  (>= (double s) threshold-score))))
                                         search-hits-reranked)
                                [])
              context-candidate-hits (if threshold-enabled?
                                       (let [seed (if (>= (count above-threshold) context-min-chunks)
                                                    above-threshold
                                                    (vec (take context-min-chunks search-hits-reranked)))]
                                         (vec (take context-max-chunks seed)))
                                       (vec (take context-max-chunks search-hits-reranked)))

              {:keys [docs]} (accumulate-context-docs context-candidate-hits params context-max-chunks)
              loaded-chunk-ids (mapv #(get-in % [:metadata :source]) docs)
              chunks-by-id (medley/index-by :chunk_id search-hits-reranked)]
          {:reranked-chunks (vec search-hits-reranked)
           :used-chunks (mapv chunks-by-id loaded-chunk-ids)
           :used-docs docs
           :full-prompt (build-prompt (str/join "\n\n" (map :page_content docs)))})))))
