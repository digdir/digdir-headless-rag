(ns digdir.rag.retrieval
  "Typesense-based retrieval logic and helpers for RAG."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as medley]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]
            [digdir.data.db :as db]
            [digdir.rag.filters :as filters]))

(defn- rag-debug-logging-enabled? []
  (let [env-val (System/getenv "RAG_DEBUG_LOGGING")]
    (contains? #{"1" "true" "yes" "on"}
               (str/lower-case (str (or env-val ""))))))

(defn- rag-debug-log [msg data]
  (when (rag-debug-logging-enabled?)
    (log/info msg data)))

(defn field->counts [facet_counts]
  (into {}
        (map (fn [{:keys [counts field_name]}]
               [field_name counts]))
        facet_counts))

(defn options [res]
  (let [all-facet-counts (mapcat :facet_counts res)
        grouped (group-by :field_name all-facet-counts)
        field->options
        (reduce-kv
         (fn [acc field-name field-entries]
           (let [all-counts (mapcat :counts field-entries)
                 combined-counts (->> all-counts
                                      (group-by :value)
                                      (map (fn [[value entries]]
                                             (if (seq entries)
                                               {:value value
                                                :count (if (seq (rest entries))
                                                         (apply max (map :count (rest entries)))
                                                         0)}
                                               {:value value
                                                :count 0})))
                                      (sort-by :value)
                                      vec)]
             (assoc acc field-name combined-counts)))
         {}
         grouped)]
    field->options))

(defn facet-result->ui-field [{:as filter-field :keys [selected-options]} options]
  (assoc filter-field :options (mapv (fn [{:keys [count value]}]
                                       (let [str-value (str value)]
                                         {:count count
                                          :value str-value
                                          :selected? (boolean (selected-options str-value))}))
                                     options)))

(defn fetch-facets
  "Fetch facets from Typesense for filtering UI."
  ([conversation-pipeline filter-map]
   (fetch-facets conversation-pipeline filter-map nil))
  ([conversation-pipeline filter-map opts]
   (rag-debug-log "fetch-facets start"
                  {:docs-collection (:docs-collection conversation-pipeline)
                   :filter-map filter-map})
   (if-let [ts-config (ts-utils/make-ts-settings opts)]
     (let [multi-search (filters/filter-map->typesense-facet-multi-search
                         filter-map
                         (:docs-collection conversation-pipeline))]
       (try
         (let [response (ts-client/multi-search
                         ts-config multi-search {:query_by "doc_num"})
               results (:results response)
               facet-opts (options results)]
           (assoc filter-map :ui/fields
                  (mapv (fn [filter-field]
                          (facet-result->ui-field
                           filter-field (facet-opts (:field filter-field))))
                        (:fields filter-map))))
         (catch Exception e
           (log/warn e "Error in fetch-facets" {:multi-search multi-search})
           filter-map)))
     (do
       (log/warn "fetch-facets skipped: Typesense config is nil")
       filter-map))))

(defn prepare-conversation [dh-conn convo-id conversation-pipeline]
  (mapv
   #(cond-> %
      (:message.filter/value %) (update :message.filter/value (partial fetch-facets conversation-pipeline)))
   (db/fetch-convo-messages-mapped dh-conn convo-id)))

(defn lookup-search-phrases-similar
  "Search for similar phrases in Typesense."
  ([phrases-collection-name docs-collection-name relaxed-queries filter-by]
   (lookup-search-phrases-similar phrases-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([phrases-collection-name docs-collection-name relaxed-queries filter-by opts]
   (rag-debug-log "lookup-search-phrases-similar start" {:filter-by filter-by})
   (if (or (nil? relaxed-queries) (nil? phrases-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           multi-search-args {:searches (map (fn [query]
                                               (merge
                                                {:collection phrases-collection-name
                                                 :q query
                                                 :include_fields "chunk_id,search_phrase"
                                                 :exclude_fields "phrase_vec"
                                                 :limit per-query-limit
                                                 :sort_by "_text_match:desc"
                                                 :prioritize_exact_match false
                                                 :drop_tokens_threshold 5}
                                                (when (not-empty typesense-filter)
                                                  {:filter_by typesense-filter})))
                                             relaxed-queries)}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {:query_by "search_phrase,phrase_vec"})
           indexed-search-phrase-hits (->> (:results response)
                                           (mapcat :hits)
                                           (map-indexed (fn [idx phrase]
                                                          (assoc phrase :index idx))))]
       (map (fn [phrase]
              (let [rank-val (get-in phrase [:hybrid_search_info :rank_fusion_score])]
                {:chunk_id (get-in phrase [:document :chunk_id])
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 ;; Surface WHICH phrase matched (mirrors lookup-verified-phrases-
                 ;; similar's :matched-phrase) so the prune gate can tell "chunk
                 ;; surfaces via THIS candidate phrase" from "via another phrase".
                 :matched-phrase (get-in phrase [:document :search_phrase])
                 :index (:index phrase)}))
            indexed-search-phrase-hits)))))

(defn lookup-hypothetical-questions-similar
  "Search for similar hypothetical questions in the parallel enrichment
   collection. Mirrors `lookup-search-phrases-similar`: returns
   `[{:chunk_id :rank :index} ...]` so the merge stage can treat
   enrichment hits as a sibling strategy alongside phrase/metadata/content.

   The enrichment collection schema (see
   `digdir.skills.enrichment.collections/hypothetical-questions-schema`)
   pairs `chunk_id` and `doc_num` reference fields with the searchable
   `question` text and its `question_vec` embedding — same field shape
   as the phrases collection, just renamed. The hybrid query
   (`question,question_vec`) handles both token-overlap and
   semantic-match cases.

   Returns [] when either argument is nil so the retrieval skill can
   call this unconditionally and let an empty
   `:enrichment-search-targets` short-circuit naturally."
  ([questions-collection-name docs-collection-name relaxed-queries filter-by]
   (lookup-hypothetical-questions-similar
    questions-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([questions-collection-name docs-collection-name relaxed-queries filter-by opts]
   (rag-debug-log "lookup-hypothetical-questions-similar start"
                  {:collection questions-collection-name
                   :filter-by filter-by})
   (if (or (nil? relaxed-queries) (nil? questions-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           multi-search-args {:searches (map (fn [query]
                                               (merge
                                                {:collection questions-collection-name
                                                 :q query
                                                 :include_fields "chunk_id,question"
                                                 :exclude_fields "question_vec"
                                                 :limit per-query-limit
                                                 :sort_by "_text_match:desc"
                                                 :prioritize_exact_match false
                                                 :drop_tokens_threshold 5}
                                                (when (not-empty typesense-filter)
                                                  {:filter_by typesense-filter})))
                                             relaxed-queries)}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts)
                                            multi-search-args
                                            {:query_by "question,question_vec"})
           indexed-hits (->> (:results response)
                             (mapcat :hits)
                             (map-indexed (fn [idx hit]
                                            (assoc hit :index idx))))]
       (map (fn [hit]
              (let [rank-val (get-in hit [:hybrid_search_info :rank_fusion_score])]
                {:chunk_id (get-in hit [:document :chunk_id])
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 :index (:index hit)
                 ;; The matched question text — this is the key signal for
                 ;; the read-tool integration. The retrieval skill's
                 ;; sibling-strategy carries it through to the agent's
                 ;; chunk preview so the LLM can see *which* question the
                 ;; chunk was matched against and use that as evidence
                 ;; when picking what to read.
                 :matched-question (get-in hit [:document :question])}))
            indexed-hits)))))

(defn harvest-search-phrases
  "Pseudo-relevance-feedback harvest for corpus-aware query expansion.

   Unlike `lookup-search-phrases-similar` (which returns chunk refs for
   retrieval merging), this returns the matched `search_phrase` TEXT plus
   the source `doc_num` — the real corpus vocabulary the query-planner
   grounds its expansions on. See
   plans/proposed/corpus-aware-prf-expansion-plan.md.

   `queries`        — seed probes (typically the blind planner's expansions).
   `raw-filter`     — optional raw Typesense filter string (e.g. a hop-2
                      `doc_num:=[a,b]` scope). Passed through verbatim — the
                      caller owns the filter syntax (this is NOT a filter-map).
   `opts`           — `:limit` per-query (default 10) + `:tenant` for ts settings.

   Returns `[{:search-phrase \"...\" :doc-num \"...\" :chunk-id \"...\" :index n} ...]`
   ordered by text-match, blank phrases dropped. Returns [] when the
   collection name or queries are missing (so callers can call it
   unconditionally)."
  ([phrases-collection-name queries]
   (harvest-search-phrases phrases-collection-name queries nil nil))
  ([phrases-collection-name queries raw-filter opts]
   (rag-debug-log "harvest-search-phrases start"
                  {:collection phrases-collection-name :raw-filter raw-filter})
   (if (or (empty? queries) (nil? phrases-collection-name))
     []
     (let [per-query-limit (or (:limit opts) 10)
           searches (map (fn [q]
                           (cond-> {:collection phrases-collection-name
                                    :q q
                                    :include_fields "chunk_id,doc_num,search_phrase"
                                    :exclude_fields "phrase_vec"
                                    :limit per-query-limit
                                    :sort_by "_text_match:desc"
                                    :prioritize_exact_match false
                                    :drop_tokens_threshold 5}
                             (not-empty raw-filter) (assoc :filter_by raw-filter)))
                         queries)
           response (ts-client/multi-search (ts-utils/make-ts-settings opts)
                                            {:searches searches}
                                            {:query_by "search_phrase,phrase_vec"})]
       ;; Preserve per-probe rank (rank within each probe's own result set)
       ;; so callers can aggregate across probes with a rank-based method
       ;; (RRF) — raw `rank_fusion_score` is NOT comparable across different
       ;; probe queries, so a global score sort mixes incomparable scales.
       (->> (:results response)
            (mapcat (fn [r]
                      (map-indexed (fn [probe-rank hit]
                                     (let [doc (:document hit)]
                                       {:search-phrase (:search_phrase doc)
                                        :doc-num (:doc_num doc)
                                        :chunk-id (:chunk_id doc)
                                        :probe-rank probe-rank}))
                                   (:hits r))))
            (remove (comp str/blank? str :search-phrase))
            vec)))))

(defn lookup-verified-phrases-similar
  "Phase D1 — search for similar verified phrases in the parallel
   enrichment collection. Mirror of `lookup-hypothetical-questions-similar`
   against the verified-phrases schema (`phrase` / `phrase_vec` field
   names instead of `question` / `question_vec`).

   Returns `[{:chunk_id :rank :index :matched-phrase} ...]` so the
   merge stage can treat phrase hits as a sibling strategy alongside
   phrase/metadata/content/hypothetical-questions.

   Returns [] when either argument is nil so the retrieval skill can
   call this unconditionally."
  ([phrases-collection-name docs-collection-name relaxed-queries filter-by]
   (lookup-verified-phrases-similar
    phrases-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([phrases-collection-name docs-collection-name relaxed-queries filter-by opts]
   (rag-debug-log "lookup-verified-phrases-similar start"
                  {:collection phrases-collection-name
                   :filter-by filter-by})
   (if (or (nil? relaxed-queries) (nil? phrases-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           multi-search-args {:searches (map (fn [query]
                                               (merge
                                                {:collection phrases-collection-name
                                                 :q query
                                                 :include_fields "chunk_id,phrase"
                                                 :exclude_fields "phrase_vec"
                                                 :limit per-query-limit
                                                 :sort_by "_text_match:desc"
                                                 :prioritize_exact_match false
                                                 :drop_tokens_threshold 5}
                                                (when (not-empty typesense-filter)
                                                  {:filter_by typesense-filter})))
                                             relaxed-queries)}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts)
                                            multi-search-args
                                            {:query_by "phrase,phrase_vec"})
           indexed-hits (->> (:results response)
                             (mapcat :hits)
                             (map-indexed (fn [idx hit]
                                            (assoc hit :index idx))))]
       (map (fn [hit]
              (let [rank-val (get-in hit [:hybrid_search_info :rank_fusion_score])]
                {:chunk_id (get-in hit [:document :chunk_id])
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 :index (:index hit)
                 :matched-phrase (get-in hit [:document :phrase])}))
            indexed-hits)))))

(defn lookup-fact-assertions-similar
  "Phase D2 — search for matching fact-assertion triples in the
   parallel enrichment collection. Mirror of
   `lookup-verified-phrases-similar` against the fact-assertions schema.

   Differences from the phrases lookup:
   - `query_by` targets `triple_text,triple_vec` instead of `phrase,*`.
   - `prioritize_exact_match` is `true`. Entity-style user queries
     (`Altinn 3 lansert`) should surface assertions that literally
     contain those tokens. The vector path stays available as a
     fallback for paraphrases.

   Returns `[{:chunk_id :rank :index :matched-triple} ...]` so the
   merge stage can treat fact hits as a sibling strategy alongside
   phrase/metadata/content/hypothetical-questions/verified-phrases.
   `:matched-triple` is the full `{:subject :predicate :object}` map
   so callers can render the assertion as evidence without a second
   round trip.

   Returns [] when either argument is nil so the retrieval skill can
   call this unconditionally."
  ([facts-collection-name docs-collection-name relaxed-queries filter-by]
   (lookup-fact-assertions-similar
    facts-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([facts-collection-name docs-collection-name relaxed-queries filter-by opts]
   (rag-debug-log "lookup-fact-assertions-similar start"
                  {:collection facts-collection-name
                   :filter-by filter-by})
   (if (or (nil? relaxed-queries) (nil? facts-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           multi-search-args {:searches (map (fn [query]
                                               (merge
                                                {:collection facts-collection-name
                                                 :q query
                                                 :include_fields "chunk_id,subject,predicate,object,triple_text"
                                                 :exclude_fields "triple_vec"
                                                 :limit per-query-limit
                                                 :sort_by "_text_match:desc"
                                                 :prioritize_exact_match true
                                                 :drop_tokens_threshold 5}
                                                (when (not-empty typesense-filter)
                                                  {:filter_by typesense-filter})))
                                             relaxed-queries)}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts)
                                            multi-search-args
                                            {:query_by "triple_text,triple_vec"})
           indexed-hits (->> (:results response)
                             (mapcat :hits)
                             (map-indexed (fn [idx hit]
                                            (assoc hit :index idx))))]
       (map (fn [hit]
              (let [rank-val (get-in hit [:hybrid_search_info :rank_fusion_score])
                    doc (:document hit)]
                {:chunk_id (:chunk_id doc)
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 :index (:index hit)
                 :matched-triple {:subject (:subject doc)
                                  :predicate (:predicate doc)
                                  :object (:object doc)}}))
            indexed-hits)))))

(defn search-chunks-by-metadata
  "Search chunks by metadata in Typesense."
  ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
   (search-chunks-by-metadata chunks-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
   (if (or (nil? relaxed-queries) (nil? chunks-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           metadata-fields (let [mf (:metadata-fields opts)]
                             (if (seq mf) mf ["metadata"]))
           include-fields (str "chunk_id," (str/join "," metadata-fields))
           ;; Per-language fan-out: with multiple fields configured (typical
           ;; for stemmed `_en`/`_nb` variants), issue one multi-search item
           ;; per (query, field) pair instead of stuffing all fields into a
           ;; single `query_by`. Typesense's combined-field BM25 scoring
           ;; silently drops matches when each doc populates only one field
           ;; variant — see plans/in-progress/target-optimal-baseline-v3/
           ;; 25-stemming-implementation.md for the investigation.
           multi-search-args {:searches (for [query relaxed-queries
                                              field metadata-fields]
                                          (merge
                                           {:collection chunks-collection-name
                                            :q query
                                            :query_by field
                                            :include_fields include-fields
                                            :limit per-query-limit
                                            :sort_by "_text_match:desc"
                                            :prioritize_exact_match false
                                            :drop_tokens_threshold 5}
                                           (when (not-empty typesense-filter)
                                             {:filter_by typesense-filter})))}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {})
           indexed-metadata-hits (->> (:results response)
                                      (mapcat :hits)
                                      (map-indexed (fn [idx hit]
                                                     (assoc hit
                                                            :index idx
                                                            :search-type :metadata))))]
       (map (fn [hit]
              (let [rank-val (get-in hit [:text_match_info :score])]
                {:chunk_id (get-in hit [:document :chunk_id])
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 :index (:index hit)
                 :search-type :metadata}))
            indexed-metadata-hits)))))

(defn search-chunks-by-content
  "Search chunks by content in Typesense."
  ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
   (search-chunks-by-content chunks-collection-name docs-collection-name relaxed-queries filter-by nil))
  ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
   (if (or (nil? relaxed-queries) (nil? chunks-collection-name))
     []
     (let [typesense-filter (filters/filter-map->typesense-filter filter-by docs-collection-name)
           per-query-limit (or (:limit opts) 20)
           content-fields (let [cf (:content-fields opts)]
                            (if (seq cf) cf ["content_markdown"]))
           include-fields (str "chunk_id," (str/join "," content-fields))
           ;; Per-language fan-out — see search-chunks-by-metadata for rationale.
           multi-search-args {:searches (for [query relaxed-queries
                                              field content-fields]
                                          (merge
                                           {:collection chunks-collection-name
                                            :q query
                                            :query_by field
                                            :include_fields include-fields
                                            :limit per-query-limit
                                            :sort_by "_text_match:desc"
                                            :prioritize_exact_match false
                                            :drop_tokens_threshold 5}
                                           (when (not-empty typesense-filter)
                                             {:filter_by typesense-filter})))}
           response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {})
           indexed-content-hits (->> (:results response)
                                     (mapcat :hits)
                                     (map-indexed (fn [idx hit]
                                                    (assoc hit
                                                           :index idx
                                                           :search-type :content))))]
       (map (fn [hit]
              (let [rank-val (get-in hit [:text_match_info :score])]
                {:chunk_id (get-in hit [:document :chunk_id])
                 :rank (cond
                         (nil? rank-val) 0
                         (number? rank-val) rank-val
                         (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                         :else 0)
                 :index (:index hit)
                 :search-type :content}))
            indexed-content-hits)))))

(defn- parse-rank
  [rank-val]
  (cond
    (nil? rank-val) 0
    (number? rank-val) rank-val
    (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
    :else 0))

(defn search-docs-by-title
  "Search the docs collection by `title-fields` (a vector of doc-collection
   field names, e.g. [\"linktitle\" \"frontmatter_title\"]), then fan out
   each matched doc to its first `chunk-fanout` chunks (chunk_index 0..K-1).

   Returns chunk hits in the same shape as the other strategies:
   `[{:chunk_id :rank :index :search-type :doc-title}]`. The parent doc's
   `_text_match` score becomes the rank for every chunk it contributes;
   `chunk_index` becomes `:index` so the merge step's tie-breaker prefers
   earlier chunks of a matched doc.

   Two-pass implementation:
   - Pass 1: multi-search docs collection with `query_by` over title-fields
             and `filter_by` direct on the docs collection itself.
   - Pass 2: multi-search chunks collection, one branch per unique matched
             doc, filtered by doc_num and sorted by chunk_index.

   Returns `[]` when:
   - `title-fields` is empty (strategy is corpus-opt-in via config),
   - `chunk-fanout` is non-positive,
   - either collection name is nil,
   - `relaxed-queries` is empty,
   - Pass 1 returns no matched docs."
  ([docs-collection-name chunks-collection-name title-fields chunk-fanout
    relaxed-queries filter-by]
   (search-docs-by-title docs-collection-name chunks-collection-name
                         title-fields chunk-fanout relaxed-queries filter-by nil))
  ([docs-collection-name chunks-collection-name title-fields chunk-fanout
    relaxed-queries filter-by opts]
   (let [k (long (or chunk-fanout 0))]
     (if (or (empty? title-fields)
             (not (pos? k))
             (nil? docs-collection-name)
             (nil? chunks-collection-name)
             (empty? relaxed-queries))
       []
       (let [direct-filter (filters/filter-map->typesense-direct-filter filter-by)
             per-query-limit (or (:limit opts) 20)
             ;; Per-field fan-out so each title-field gets its own scoring
             ;; pass; see search-chunks-by-metadata for the BM25 rationale.
             docs-multi-search
             {:searches (for [query relaxed-queries
                              field title-fields]
                          (merge
                           {:collection docs-collection-name
                            :q query
                            :query_by field
                            :include_fields "doc_num,total_chunks"
                            :limit per-query-limit
                            :sort_by "_text_match:desc"
                            :prioritize_exact_match false
                            :drop_tokens_threshold 5}
                           (when (not-empty direct-filter)
                             {:filter_by direct-filter})))}
             docs-resp (ts-client/multi-search (ts-utils/make-ts-settings opts) docs-multi-search {})
             matched-docs (->> (:results docs-resp)
                               (mapcat :hits)
                               (map (fn [hit]
                                      (let [doc (:document hit)]
                                        {:doc_num (:doc_num doc)
                                         :rank (parse-rank (get-in hit [:text_match_info :score]))})))
                               (remove (fn [{:keys [doc_num]}] (str/blank? (str doc_num))))
                               ;; Dedupe by doc_num, keeping the best rank across queries.
                               (group-by :doc_num)
                               (map (fn [[_ entries]] (apply max-key :rank entries)))
                               ;; Sort matched-docs by rank descending so pass-2 issues its
                               ;; chunk lookups in rank order. The order doesn't change BM25
                               ;; scores, but it preserves doc priority through downstream
                               ;; rank-fusion steps that tie-break by index. Cap at 40 to
                               ;; stay under Typesense's `limit_multi_searches` (default 50).
                               (sort-by :rank >)
                               (take 40)
                               vec)]
         (if (empty? matched-docs)
           []
           (let [chunks-multi-search
                 {:searches (map (fn [{:keys [doc_num]}]
                                   {:collection chunks-collection-name
                                    :q "*"
                                    :query_by "chunk_id"
                                    :include_fields "chunk_id,chunk_index,doc_num"
                                    :filter_by (str "doc_num:=" doc_num)
                                    :sort_by "chunk_index:asc"
                                    :limit k})
                                 matched-docs)}
                 chunks-resp (ts-client/multi-search (ts-utils/make-ts-settings opts)
                                                    chunks-multi-search {})
                 rank-by-doc (into {} (map (juxt :doc_num :rank) matched-docs))]
             (->> (:results chunks-resp)
                  (mapcat :hits)
                  (map (fn [hit]
                         (let [doc (:document hit)]
                           {:chunk_id (:chunk_id doc)
                            :rank (get rank-by-doc (:doc_num doc) 0)
                            :index (or (:chunk_index doc) 0)
                            :search-type :doc-title})))
                  vec))))))))

(defn get-typesense-collection
  "Retrieves a Typesense collection by name. Returns nil if collection doesn't exist."
  [collection-name]
  (try
    (ts-client/retrieve-collection (ts-utils/make-ts-settings) collection-name)
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (throw e))
      nil)))

(defn joined-file-digests
  "The sha256 digests of the joined document's files, as a flat vector.

   The join payload arrives under a key named after the documents collection,
   so this is done here - where that name is in scope - rather than leaving
   every caller to reconstruct a dynamic key.

   Prefers `file_sha256`, the flat field ingest writes since #255. Falls back to
   reading the digests out of the nested `files` array, which is what the
   deployed corpus carries today: `files` is stored-but-unindexed and comes back
   through the join intact, verified against production. The fallback can go
   once every document has been re-ingested, and until then it is the only
   source - so this works on today's data rather than waiting for a cycle that
   is not currently safe to run (see plans/proposed/sha256-lookup-decision.md)."
  [doc docs-collection-name]
  (let [joined (get doc (keyword docs-collection-name))
        flat (:file_sha256 joined)]
    (if (seq flat)
      (vec flat)
      (vec (keep :sha256 (:files joined))))))

(defn retrieve-chunks-by-id
  "Retrieve full chunk documents by ID from Typesense.

   Uses a single Typesense search with an IN-filter on chunk_id
   (`chunk_id:=[\\`id1\\`,\\`id2\\`,...]`) rather than N per-chunk filters in a
   multi-search. Preserves input order, dedupes by :chunk_id, caps by
   `:retrieve-top-k` (default 40), truncates `:content_markdown` to
   `:max_content_length` when set, and merges search-info onto each result."
  ([docs-collection-name chunks-collection-name chunk-id-list]
   (retrieve-chunks-by-id docs-collection-name chunks-collection-name chunk-id-list nil))
  ([docs-collection-name chunks-collection-name chunk-id-list opts]
   (let [selected-chunks (take (or (:retrieve-top-k opts) 40)
                               (medley/distinct-by :chunk_id chunk-id-list))
         chunk-ids (mapv :chunk_id selected-chunks)]
     (if (empty? chunk-ids)
       []
       (let [filter-str (str "chunk_id:=["
                             (str/join "," (map #(str "`" % "`") chunk-ids))
                             "]")
             search-args {:searches [{:collection chunks-collection-name
                                      :q "*"
                                      ;; file_sha256 and files are joined for
                                      ;; duplicate-file suppression (#101). Only
                                      ;; the digests are read; see
                                      ;; joined-file-digests for why both.
                                      :include_fields (str "id,chunk_id,doc_num,chunk_index,content_markdown,content_length,metadata,$"
                                                           docs-collection-name "(url,title,total_chunks,orgs_long,orgs_short,file_sha256,files)")
                                      :filter_by filter-str
                                      :page 1
                                      :per_page (count chunk-ids)}]
                          :limit_multi_searches 1}
             response (ts-client/multi-search
                       (ts-utils/make-ts-settings opts)
                       search-args
                       {:query_by "chunk_id"})
             chunk-id-to-search-info (into {} (map (juxt :chunk_id identity)) selected-chunks)
             docs-by-id (into {}
                              (map (fn [hit]
                                     (let [doc (:document hit)]
                                       [(:chunk_id doc) doc])))
                              (mapcat :hits (:results response)))
             max-content-length (:max_content_length opts)]
         (->> selected-chunks
              (keep (fn [chunk-match]
                      (when-let [doc (get docs-by-id (:chunk_id chunk-match))]
                        (let [search-info (get chunk-id-to-search-info (:chunk_id doc))
                              truncated (if (and max-content-length (pos? max-content-length))
                                          (update doc :content_markdown
                                                  (fn [c]
                                                    (if (> (count (or c "")) max-content-length)
                                                      (str (subs c 0 max-content-length) "\n[truncated]")
                                                      c)))
                                          doc)]
                          (cond-> (assoc truncated
                                          :file-digests (joined-file-digests doc docs-collection-name)
                                          :search-types (:search-types search-info)
                                          :hit-count (:hit-count search-info)
                                          :original-rank (:rank search-info)
                                          :original-index (:index search-info)
                                          :type-ranks (:type-ranks search-info))
                            (seq (:matched-questions search-info))
                            (assoc :matched-questions (:matched-questions search-info)))))))
              vec))))))

(defn retrieve-chunk-metadata-by-id
  "Retrieve chunk metadata (no content) by ID from Typesense."
  [docs-collection-name chunks-collection-name chunk-id-list opts]
  (let [selected-chunks (take (or (:retrieve-top-k opts) 40)
                              (medley/distinct-by :chunk_id chunk-id-list))
        chunk-ids (mapv :chunk_id selected-chunks)]
    (if (empty? chunk-ids)
      []
      (let [filter-str (str "chunk_id:=["
                            (str/join "," (map #(str "`" % "`") chunk-ids))
                            "]")
            search-args {:searches [{:collection chunks-collection-name
                                     :q "*"
                                     :include_fields (str "id,chunk_id,doc_num,chunk_index,content_length,metadata,$"
                                                          docs-collection-name "(url,title,total_chunks,orgs_long,orgs_short)")
                                     :filter_by filter-str
                                     :page 1
                                     :per_page (count chunk-ids)}]
                         :limit_multi_searches 1}
            response (ts-client/multi-search
                      (ts-utils/make-ts-settings opts)
                      search-args
                      {:query_by "chunk_id"})
            chunk-id-to-search-info (into {} (map (juxt :chunk_id identity)) selected-chunks)
            docs-by-id (into {}
                             (map (fn [hit]
                                    (let [doc (:document hit)]
                                      [(:chunk_id doc) doc])))
                             (mapcat :hits (:results response)))]
        (->> selected-chunks
             (keep (fn [chunk-match]
                     (when-let [doc (get docs-by-id (:chunk_id chunk-match))]
                       (let [search-info (get chunk-id-to-search-info (:chunk_id doc))]
                         (cond-> (assoc doc
                                        :search-types (:search-types search-info)
                                        :hit-count (:hit-count search-info)
                                        :original-rank (:rank search-info)
                                        :original-index (:index search-info)
                                        :type-ranks (:type-ranks search-info))
                           (seq (:matched-questions search-info))
                           (assoc :matched-questions (:matched-questions search-info)))))))
             vec)))))

(defn retrieve-chunks-by-range
  "Retrieve chunks by doc_num and chunk_index range from Typesense."
  [docs-collection-name chunks-collection-name doc-num from-idx to-idx opts]
  (let [from-idx (max 0 (int from-idx))
        to-idx (max from-idx (int to-idx))
        per-page (min 50 (inc (- to-idx from-idx)))
        filter-str (str "doc_num:=`" doc-num "` && chunk_index:>=" from-idx " && chunk_index:<=" to-idx)
        max-content-length (:max_content_length opts)
        search-args {:searches [{:collection chunks-collection-name
                                 :q "*"
                                 :include_fields (str "id,chunk_id,doc_num,chunk_index,content_markdown,content_length,metadata,$"
                                                      docs-collection-name "(url,title,total_chunks,orgs_long,orgs_short)")
                                 :filter_by filter-str
                                 :sort_by "chunk_index:asc"
                                 :page 1
                                 :per_page per-page}]
                     :limit_multi_searches 1}
        response (ts-client/multi-search
                   (ts-utils/make-ts-settings opts)
                   search-args
                   {:query_by "chunk_id"})]
    (->> (:results response)
         (mapcat :hits)
         (map :document)
         (mapv (fn [doc]
                 (if (and max-content-length (pos? max-content-length))
                   (update doc :content_markdown
                           (fn [c]
                             (if (> (count (or c "")) max-content-length)
                               (str (subs c 0 max-content-length) "\n[truncated]")
                               c)))
                   doc))))))
