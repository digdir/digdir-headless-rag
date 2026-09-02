(ns digdir.skills.builtin.retrieval
  "Retrieval skill - wraps TypeSense search functions.

   This skill performs multi-strategy document retrieval:
   1. Search phrase similarity matching
   2. Metadata-based search
   3. Content-based search

   Results are merged and deduplicated."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.auto-filter :as auto-filter]
            [digdir.rag.rerank :as rerank]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.retrieval.union :as union]
            [digdir.skills.enrichment.naming :as enrich-naming]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.edn :as edn]))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def retrieval-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "retrieval"
    :description "Search documents using multi-strategy retrieval (phrase matching, metadata search, content search)"
    :parameters
    {:type "object"
     :properties
     {:queries
      {:type "array"
       :items {:type "string"}
       :description "Search queries to execute"}
      :user-intent
      {:type "string"
       :description "Optional canonical user-intent string. When present and distinct from :queries, retrieval runs a second pass with this string only and union-merges the result with the expansion pass (slice-21/22 first-pass union). Off by default; enabled per-tenant via skills.retrieval.user-intent-union.enabled."}
      :filter
      {:type "object"
       :description "Optional filters to apply to search results"}}
     :required ["queries"]}}})

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def retrieval-metadata
  {:skill-id :builtin/retrieval
   :name "Document Retrieval"
   :description "Multi-strategy document retrieval from TypeSense collections"
   :category :retrieval
   ;; :user-intent is declared for documentation but marked :optional-inputs —
   ;; slice-23 union only consults it when present (see the docstring), and direct
   ;; `search` tool calls only stash it after a prior plan_queries. :optional-inputs
   ;; exempts it from check-required-inputs (which otherwise treats every :inputs
   ;; entry as REQUIRED, the bug that broke direct search). It is still received
   ;; when provided (resolved-inputs is a full merge, not a select).
   :inputs [:queries :user-intent :docs-collection :chunks-collection :phrases-collection]
   :optional-inputs [:user-intent]
   :outputs [:chunks :search-attribution]
   :parameters {:limit :number
                :retrieve-top-k :number
                :max-per-document :number
                :query-aware-boost :boolean
                :metadata-only :boolean
                :filter-by :map
                :phrase-gen-prompt :string
                :strategy-weights :map
                :strategy-contribution-caps :map
                :boost-weights :map
                :diversity-config :map
                :rerank-with-colbert :boolean
                :rerank-candidate-k :number
                :rerank-max-chunk-length :number
                :rerank-windowing :boolean
                :per-strategy-rerank? :boolean
                :rerank-final-cap :number
                ;; Sibling-strategy opt-in for parallel enrichment
                ;; collections. Value: {enrichment-type collection-name}.
                ;; Empty / absent = behavior identical to the prior
                ;; 3-strategy retrieval. Only `:hypothetical-questions`
                ;; is wired today; later enrichment types will plug into
                ;; the same parameter as B/D phases add them.
                :enrichment-search-targets :map
                ;; Enrichment types to activate (e.g. [:hypothetical-questions]).
                ;; When :enrichment-search-targets is absent, the skill self-
                ;; derives the {type -> collection} map from these + the resolved
                ;; chunks-collection. This is the path the production :bundled
                ;; graph uses (it threads :enrichment-types via its step config).
                :enrichment-types :vector
                ;; Doc-title strategy opt-in. When `:title-fields` is a
                ;; non-empty vector of doc-collection field names (e.g.
                ;; ["linktitle" "frontmatter_title"]), a 4th `:doc-title`
                ;; strategy runs alongside phrase/metadata/content. Each
                ;; matched doc contributes its first
                ;; `:doc-title-chunk-fanout` chunks (default 3) to the
                ;; merge stage. Empty / absent = no doc-title strategy.
                :title-fields :vector
                :doc-title-chunk-fanout :number
                ;; Per-strategy chunk-field overrides for stemmed-language
                ;; corpora. When set, the `:metadata` and `:content`
                ;; strategies query these fields instead of the legacy
                ;; single `metadata` / `content_markdown` fields. Typical
                ;; use: ["content_markdown_en" "content_markdown_nb"] so
                ;; Snowball stemming picks the right locale per field.
                :chunk-content-fields :vector
                :chunk-metadata-fields :vector
                ;; Retrieval orchestration mode. `:multi-strategy` (default)
                ;; runs phrase/metadata/content/doc-title in parallel and
                ;; merges. `:hierarchical` runs only doc-title (Pass A doc
                ;; selection + Pass B chunk fanout) so ColBERT reranks a
                ;; focused, doc-coherent pool — avoids cross-strategy
                ;; dilution when stemmed title signals are strong.
                :retrieval-mode :keyword
                ;; Auto-filter rule engine (opt-in via per-dataset
                ;; config). Vector of rule specs; each rule is a map
                ;; with `:rule/type` and rule-specific parameters.
                ;; Empty / absent preserves the prior hardcoded
                ;; org+year auto-detection only. See
                ;; digdir.rag.auto-filter-rules for supported types.
                :auto-filter-rules :vector
                ;; Fusion mode for merging per-strategy results.
                ;; - :weighted-sum (default) — min-max normalized
                ;;   weighted-sum (the historical behavior).
                ;; - :rrf — Reciprocal Rank Fusion (Cormack et al.
                ;;   SIGIR 2009). Pool-size invariant; doesn't suffer
                ;;   from the per-strategy normalization redistribution
                ;;   that destabilizes weighted-sum when the candidate
                ;;   pool narrows.
                :merge-mode :keyword
                ;; RRF smoothing constant. Only consulted when
                ;; :merge-mode is :rrf. Default 60 (matches Cormack
                ;; et al. and Elasticsearch). Smaller k = more
                ;; spread between top and bottom ranks.
                :rrf-k :number
                ;; User-intent first-pass union (slice 23). When
                ;; `:user-intent-union-enabled` is true AND `:user-intent`
                ;; is provided and distinct from `:queries`, retrieval
                ;; runs a second pass with the intent-only string and
                ;; union-merges the two reranked outputs. Mode = "cap"
                ;; (slice-22 winner) takes the intent pass's top
                ;; `:user-intent-union-cap` chunks first, then fills
                ;; from the expansion pass. Modes "interleave" and "rrf"
                ;; are also available.
                :user-intent-union-enabled :boolean
                :user-intent-union-mode :keyword
                :user-intent-union-cap :number
                :user-intent-union-rrf-k :number}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :search :production}
   :tool-definition retrieval-tool-definition})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn- run-search-strategies
  "Execute the search strategies and return merged hit IDs with attribution.
   Does NOT fetch chunk content — caller is responsible for fetching via
   retrieve-chunks-by-id or retrieve-chunk-metadata-by-id.

   Always runs the three base strategies: phrase/metadata/content.
   When `(:enrichment-search-targets opts)` is non-empty, additionally
   runs a sibling strategy per supported enrichment type and merges
   those hits in alongside the base three. The enrichment targets value
   is a map `{enrichment-type collection-name}`; an empty/absent map
   preserves the prior 3-strategy behavior exactly."
  [queries docs-collection chunks-collection phrases-collection
   filter-by opts]
  (let [dedupe-hits (fn [hits]
                      (->> hits
                           (group-by :chunk_id)
                           (map (fn [[_ hs]]
                                  ;; Keep strongest hit per strategy/list for this chunk.
                                  (first (sort-by (juxt (comp - #(double (or % 0)) :rank)
                                                         :index)
                                                  hs))))
                           (sort-by :index)
                           vec))
        ;; Hierarchical mode: only the :doc-title strategy runs, with the
        ;; configured top-N docs and chunk-fanout. Skips :phrase / :metadata /
        ;; :content / enrichments entirely. Designed for stemmed-title corpora
        ;; where doc-level signal is strong and chunk-level retrieval risks
        ;; diluting ColBERT's rerank input.
        hierarchical? (= :hierarchical (:retrieval-mode opts))
        phrase-hits (if hierarchical?
                      []
                      (-> (rag/lookup-search-phrases-similar
                            phrases-collection
                            docs-collection
                            queries
                            filter-by
                            opts)
                          dedupe-hits))
        metadata-hits (if hierarchical?
                        []
                        (-> (rag/search-chunks-by-metadata
                              chunks-collection
                              docs-collection
                              queries
                              filter-by
                              opts)
                            dedupe-hits))
        content-hits (if hierarchical?
                       []
                       (-> (rag/search-chunks-by-content
                             chunks-collection
                             docs-collection
                             queries
                             filter-by
                             opts)
                           dedupe-hits))

        ;; Doc-title strategy is opt-in via skill config; when the
        ;; corpus declares which doc fields to search and how many
        ;; chunks to fan out per matched doc, run a 4th strategy.
        ;; In hierarchical mode this becomes the SOLE strategy.
        title-fields (:title-fields opts)
        doc-title-chunk-fanout (:doc-title-chunk-fanout opts)
        doc-title-active? (and (seq title-fields) (pos? (long (or doc-title-chunk-fanout 0))))
        doc-title-hits (if doc-title-active?
                         (dedupe-hits (rag/search-docs-by-title
                                       docs-collection
                                       chunks-collection
                                       title-fields
                                       doc-title-chunk-fanout
                                       queries
                                       filter-by
                                       opts))
                         [])

        enrichment-targets (when-not hierarchical?
                             (or (:enrichment-search-targets opts) {}))
        ;; One result-list per enrichment type. Each list is already
        ;; tagged with :search-type so the merge step weights it
        ;; correctly. Unknown enrichment types are silently skipped —
        ;; we can't fail closed here because the runtime retrieval skill
        ;; receives this map as an opt-in parameter and a typo on the
        ;; caller side shouldn't break unrelated search strategies.
        enrichment-result-lists
        (->> enrichment-targets
             (keep (fn [[enrichment-type coll-name]]
                     (when (and coll-name (seq coll-name))
                       (case enrichment-type
                         :hypothetical-questions
                         (->> (rag/lookup-hypothetical-questions-similar
                               coll-name docs-collection queries filter-by opts)
                              dedupe-hits
                              (mapv #(assoc % :search-type :hypothetical-questions)))

                         :verified-phrases
                         (->> (rag/lookup-verified-phrases-similar
                               coll-name docs-collection queries filter-by opts)
                              dedupe-hits
                              (mapv #(assoc % :search-type :verified-phrases)))

                         :fact-assertions
                         (->> (rag/lookup-fact-assertions-similar
                               coll-name docs-collection queries filter-by opts)
                              dedupe-hits
                              (mapv #(assoc % :search-type :fact-assertions)))

                         nil))))
             (remove nil?)
             vec)

        merge-opts (:merge-opts opts)
        base-strategy-lists (cond-> [(map #(assoc % :search-type :phrase) phrase-hits)
                                     metadata-hits
                                     content-hits]
                              doc-title-active? (conj doc-title-hits))
        all-strategy-lists (into base-strategy-lists enrichment-result-lists)
        merged-hits (if merge-opts
                      (apply rag/merge-chunk-search-results merge-opts all-strategy-lists)
                      (apply rag/merge-chunk-search-results all-strategy-lists))]

    {:phrase-hits phrase-hits
     :metadata-hits metadata-hits
     :content-hits content-hits
     :doc-title-hits doc-title-hits
     :enrichment-hits-by-type
     (let [all-enrichment-hits (apply concat enrichment-result-lists)]
       (into {} (map (fn [[t _coll-name]]
                       [t (count (filter #(= (:search-type %) t)
                                         all-enrichment-hits))]))
             (filter (fn [[_ coll]] (and coll (seq coll))) enrichment-targets)))
     :merged-hits merged-hits}))

(defn- field-key
  "Identity key for merging filter fields."
  [field-spec]
  [(:field field-spec) (or (:type field-spec) :multiselect)])

(defn- merge-filter-by
  "Merge explicit and auto-detected filter maps.
   Explicit fields win on key conflicts."
  [explicit-filter auto-filter]
  (let [explicit-fields (vec (:fields explicit-filter))
        auto-fields (vec (:fields auto-filter))
        explicit-keys (set (map field-key explicit-fields))
        merged-fields (vec (concat explicit-fields
                                   (remove #(contains? explicit-keys (field-key %)) auto-fields)))]
    (when (seq merged-fields)
      {:fields merged-fields})))

(defn- tokenize
  [s]
  (when (seq (str s))
    (let [parts (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")]
      (->> parts
           (remove str/blank?)
           set))))

(defn- query-years
  [queries]
  (->> queries
       (mapcat #(re-seq #"\b(19\d{2}|20\d{2}|21\d{2})\b" (str %)))
       (map second)
       set))

(defn- selected-options-for
  [filter-by field]
  (->> (get filter-by :fields)
       (filter #(= field (:field %)))
       (mapcat :selected-options)
       (map str)
       set))

(defn- chunk-title
  [chunk docs-collection]
  (or (get-in chunk [(keyword docs-collection) :title]) ""))

(defn- chunk-org-values
  "Extract org values from a chunk. Checks the joined doc reference first
   (where Typesense stores referenced document fields), then falls back
   to parsing the chunk's metadata string."
  [chunk docs-collection]
  (let [;; Try joined doc reference first (e.g. :some_docs_collection -> {:orgs_long [...] ...})
        doc-ref (get chunk (keyword docs-collection))
        doc-org-long (when (map? doc-ref) (or (:orgs_long doc-ref) []))
        doc-org-short (when (map? doc-ref) (or (:orgs_short doc-ref) []))
        ;; Fallback: try parsing the metadata string
        md-str (:metadata chunk)
        parsed-md (when (and (string? md-str) (not (str/blank? md-str)))
                    (try (edn/read-string md-str) (catch Exception _ nil)))
        md-org-long (when (map? parsed-md) (or (:orgs_long parsed-md) []))
        md-org-short (when (map? parsed-md) (or (:orgs_short parsed-md) []))]
    (set (map str (concat doc-org-long doc-org-short md-org-long md-org-short)))))

;; Query-aware ranking weights tuned from diagnostic benchmarks.
;; Exposed as the :boost-weights skill parameter; tenants can override any
;; subset and the rest fall back to these defaults.
(def default-boost-weights
  {:title-overlap-per-token 0.03
   :title-overlap-max 0.30
   :content-overlap-per-token 0.04
   :content-overlap-max 0.40
   :year-match 0.15
   :org-filter-match 0.40
   :content-search-type 0.35
   :phrase-search-type 0.08
   :metadata-search-type 0.05
   :numeric-evidence 0.45
   :original-rank-weight 0.1})

;; Heuristic for relaxing default diversity cap in highly single-document pools.
;; Exposed as the :diversity-config skill parameter.
(def default-diversity-config
  {:relax-min-total 30
   :relax-min-docs 3
   :relax-min-top-doc-count 20
   :relax-min-top-doc-share 0.45
   :relaxed-max-per-document 50
   :default-max-per-document 10})

(defn- resolve-boost-weights
  [override]
  (merge default-boost-weights (or override {})))

(defn- resolve-diversity-config
  [override]
  (merge default-diversity-config (or override {})))

(defn- numeric-fact-query?
  [queries]
  (let [normalized (str/lower-case (str/join " " queries))]
    (boolean
     (or (re-find #"\b(hvor mange|kor mange|how many|antall|number of)\b" normalized)
         (re-find #"\b(årsverk|ansatte|employees|fte|stillinger)\b" normalized)))))

(defn- chunk-searchable-text
  [chunk docs-collection]
  (str/join " "
            (remove str/blank?
                    [(chunk-title chunk docs-collection)
                     (some-> (:metadata chunk) str)
                     (or (:content_markdown chunk) "")])))

(defn- search-type-boost
  [chunk boost-weights]
  (let [search-types (set (map keyword (or (:search-types chunk) [])))]
    (+ (if (contains? search-types :content) (:content-search-type boost-weights) 0.0)
       (if (contains? search-types :phrase) (:phrase-search-type boost-weights) 0.0)
       (if (contains? search-types :metadata) (:metadata-search-type boost-weights) 0.0))))

(defn- content-evidence-boost
  [chunk query-token-set years numeric-query? docs-collection boost-weights]
  (let [searchable-text (chunk-searchable-text chunk docs-collection)
        searchable-tokens (or (tokenize searchable-text) #{})
        overlap (count (set/intersection query-token-set searchable-tokens))
        overlap-boost (min (:content-overlap-max boost-weights)
                           (* (:content-overlap-per-token boost-weights) overlap))
        has-year? (and (seq years)
                       (seq (set/intersection years
                                              (set (keep second (re-seq #"\b(19\d{2}|20\d{2}|21\d{2})\b"
                                                                        searchable-text))))))
        has-number? (->> (re-seq #"\b\d[\d .,:]*\b" searchable-text)
                         (map str/trim)
                         (some (fn [n]
                                 (not (contains? years n)))))
        has-metric? (seq (set/intersection query-token-set searchable-tokens))
        numeric-boost (if (and numeric-query? has-year? has-number? has-metric?)
                        (:numeric-evidence boost-weights)
                        0.0)]
    {:overlap overlap-boost
     :numeric numeric-boost}))

(defn- prioritize-chunks
  [chunks queries docs-collection effective-filter-by boost-weights]
  (let [query-token-set (apply set/union #{} (keep tokenize queries))
        years (query-years queries)
        numeric-query? (numeric-fact-query? queries)
        filter-orgs (set (concat (selected-options-for effective-filter-by "orgs_long")
                                 (selected-options-for effective-filter-by "orgs_short")))]
    (->> chunks
         (map (fn [chunk]
                (let [title (chunk-title chunk docs-collection)
                      title-tokens (or (tokenize title) #{})
                      overlap (count (set/intersection query-token-set title-tokens))
                      title-boost (min (:title-overlap-max boost-weights)
                                       (* (:title-overlap-per-token boost-weights) overlap))
                      chunk-years (set (keep second (re-seq #"\b(19\d{2}|20\d{2}|21\d{2})\b" title)))
                      year-boost (if (seq (set/intersection years chunk-years))
                                   (:year-match boost-weights)
                                   0.0)
                      org-overlap (seq (set/intersection filter-orgs (chunk-org-values chunk docs-collection)))
                      org-boost (if org-overlap (:org-filter-match boost-weights) 0.0)
                      content-boosts (content-evidence-boost chunk query-token-set years numeric-query?
                                                             docs-collection boost-weights)
                      strategy-boost (search-type-boost chunk boost-weights)
                      composite (+ (double (or (:hit-count chunk) 0))
                                   (* (:original-rank-weight boost-weights)
                                      (double (or (:original-rank chunk) 0)))
                                   title-boost
                                   year-boost
                                   org-boost
                                   strategy-boost
                                   (:overlap content-boosts)
                                   (:numeric content-boosts))]
                  (assoc chunk :retrieval-prior composite
                               :retrieval-boosts {:title title-boost
                                                  :year year-boost
                                                  :org org-boost
                                                  :search-type strategy-boost
                                                  :content-overlap (:overlap content-boosts)
                                                  :numeric-evidence (:numeric content-boosts)}))))
         (sort-by (juxt (comp - :retrieval-prior)
                        (comp - #(double (or % 0)) :hit-count)
                        (comp - #(double (or % 0)) :original-rank)
                        :chunk_id))
         vec)))

(defn- cap-per-document
  [chunks max-per-document]
  (if (and max-per-document (pos? max-per-document))
    (let [counts (atom {})
          kept (reduce (fn [acc chunk]
                         (let [doc-id (or (:doc_num chunk) (:chunk_id chunk))
                               n (get @counts doc-id 0)]
                           (if (< n max-per-document)
                             (do (swap! counts update doc-id (fnil inc 0))
                                 (conj acc chunk))
                             acc)))
                       []
                       chunks)]
      (vec kept))
    (vec chunks)))

(defn- suppress-duplicate-files
  "Drop chunks whose document is the SAME FILE as a higher-ranked result (#101).

   222 sha256 digests in the deployed corpus appear under more than one
   doc_num. Seeing the same file twice in one answer is a wrong answer; seeing
   two VERSIONS of a report is arguably right - so this keys on the file digest
   and never on title or year. Deduplicating by title+year would have removed
   1,362 genuinely distinct documents.

   Three properties this must have, each measured rather than assumed:

   IT DOES NOT FORCE A WINNER. Keeping the highest-ranked registration is a
   RANKING decision, not a claim the other registration is false. Nothing is
   deleted and the suppressed document remains retrievable by its own doc_num -
   44% of these groups differ in title, org list, year or type, and for the 25
   less-complete-org cases there may be no fact of the matter at all.

   IT DOES NOT TOUCH THE ANNEX CASE. A file legitimately attached to two
   different documents is not a duplicate registration. #228 separated the two
   populations by file count: 216 groups where every document has exactly ONE
   file are duplicates; 6 groups where some document carries MORE are shared
   attachments and both records are correct. So only single-file documents take
   part here - a document with several files is never suppressed and never
   suppresses.

   IT INVENTS NOTHING. No merged org lists, no synthesised title. A record that
   was never registered is a worse answer than a duplicated one."
  [chunks]
  ;; Tracks WHICH document claimed each digest, not merely that one did.
  ;; Keying on the digest alone would drop every chunk of a document after its
  ;; first, because all of them share their document's file - collapsing every
  ;; multi-chunk result to a single chunk. Per-document limits are
  ;; cap-per-document's job; this one is only about two DIFFERENT registrations
  ;; of one file.
  (let [claimed (volatile! {})]
    (reduce (fn [acc chunk]
              (let [digests (:file-digests chunk)
                    doc-num (:doc_num chunk)]
                (if-not (= 1 (count digests))
                  ;; no digest, or several files: not a duplicate-registration
                  ;; candidate either way. Passes through untouched.
                  (conj acc chunk)
                  (let [digest (first digests)
                        owner (get @claimed digest)]
                    (cond
                      (nil? owner) (do (vswap! claimed assoc digest doc-num)
                                       (conj acc chunk))
                      (= owner doc-num) (conj acc chunk)
                      :else acc)))))
            []
            chunks)))

(defn- maybe-relax-default-diversity-cap
  "Relaxes the default per-document cap for heavily single-document result sets.
   Only used when caller did not explicitly set :max-per-document."
  [chunks default-cap diversity-config]
  (let [doc-freqs (frequencies (map #(or (:doc_num %) (:chunk_id %)) chunks))
        doc-count (count doc-freqs)
        total (count chunks)
        top-doc-count (if (seq doc-freqs) (apply max (vals doc-freqs)) 0)
        top-doc-share (if (pos? total) (/ (double top-doc-count) (double total)) 0.0)]
    (if (and (= default-cap (:default-max-per-document diversity-config))
             (>= total (:relax-min-total diversity-config))
             (>= doc-count (:relax-min-docs diversity-config))
             (>= top-doc-count (:relax-min-top-doc-count diversity-config))
             (>= top-doc-share (:relax-min-top-doc-share diversity-config)))
      (:relaxed-max-per-document diversity-config)
      default-cap)))

(declare apply-colbert-rerank)

(defn- merge-per-strategy-rerank
  "Combine top-K from each per-strategy ColBERT rerank into a single list.

   Strategy: round-robin interleave the ranked outputs (`per-strat`), preserve
   each chunk's strategy-best rerank-score for later sorting, dedupe by
   :chunk_id keeping the highest score, and limit to `final-cap`. The
   interleave ensures each strategy contributes diverse top hits before
   any one strategy floods the merged list.

   Each entry in `per-strat` is the `:chunks` vector from `apply-colbert-rerank`
   applied to that strategy's candidate list — already sorted by
   :rerank-score desc."
  [per-strat final-cap]
  (let [;; Round-robin interleave the strategies' ranked outputs
        max-len (apply max 0 (map count per-strat))
        interleaved (->> (range max-len)
                         (mapcat (fn [i] (keep #(get % i) per-strat)))
                         vec)
        ;; Dedupe by chunk_id, keeping the variant with the highest rerank score
        deduped (->> interleaved
                     (group-by :chunk_id)
                     (map (fn [[_ vs]]
                            (apply max-key #(double (or (:rerank-score %) 0.0)) vs)))
                     ;; Order: original interleave position determines tie-break
                     (sort-by (fn [c]
                                [(- (double (or (:rerank-score c) 0.0)))
                                 (->> interleaved
                                      (keep-indexed (fn [i x] (when (= (:chunk_id x) (:chunk_id c)) i)))
                                      first
                                      (or Long/MAX_VALUE))])))]
    (vec (take final-cap deduped))))

(defn- apply-per-strategy-colbert-rerank
  "Run `apply-colbert-rerank` independently against each of the three base
   strategies' hit lists, in parallel, then merge the top-N from each into
   a single combined list capped at `final-cap`.

   Parallel via futures: each ColBERT call is an HTTP round-trip (~300-800ms);
   running three concurrently brings total latency to roughly the slowest
   single call rather than 3× sequential.

   Returns the same shape as `apply-colbert-rerank` so call sites can use
   either interchangeably."
  [strategy-hit-lists queries docs-collection chunks-collection opts
   {:keys [rerank-candidate-k rerank-max-chunk-length strip-content? final-cap rerank-windowing]
    :or {rerank-candidate-k 40
         ;; Lever A default (see top-level entry): windowing ON, budget 2000.
         rerank-max-chunk-length 2000
         rerank-windowing true
         strip-content? true
         final-cap 40}}]
  (let [start (System/currentTimeMillis)
        ;; Materialise chunks for each non-empty strategy list, then run
        ;; the rerank fns in parallel
        per-strat-futures
        (for [hits strategy-hit-lists
              :when (seq hits)]
          (future
            (try
              (let [chunks (rag/retrieve-chunks-by-id
                            docs-collection chunks-collection
                            (vec hits) opts)]
                (apply-colbert-rerank (vec chunks) queries docs-collection
                                      chunks-collection opts
                                      {:rerank-candidate-k rerank-candidate-k
                                       :rerank-max-chunk-length rerank-max-chunk-length
                                       :rerank-windowing rerank-windowing
                                       :strip-content? strip-content?}))
              (catch Throwable t
                {:chunks []
                 :rerank-error (.getMessage t)}))))
        ;; Block on all futures; max ~rerank-candidate-k wall-clock
        per-strat-results (mapv deref per-strat-futures)
        per-strat-chunks (mapv :chunks per-strat-results)
        errors (->> per-strat-results
                    (keep :rerank-error)
                    seq)
        merged (merge-per-strategy-rerank per-strat-chunks final-cap)
        duration (- (System/currentTimeMillis) start)]
    {:chunks merged
     :rerank-ms duration
     :rerank-candidate-count (reduce + 0 (map count per-strat-chunks))
     :rerank-error (when errors (str/join "; " errors))
     :per-strategy-counts (mapv count per-strat-chunks)}))

(defn- apply-colbert-rerank
  "Rerank `final-chunks` using ColBERT. Fetches content for the top
   `rerank-candidate-k` candidates (input chunks may be metadata-only),
   calls the ColBERT API, attaches :rerank-score / :rerank-rank to each
   reranked chunk, and returns chunks sorted by rerank-score desc. Chunks
   beyond the candidate window keep their original position.

   Returns {:chunks ..., :rerank-ms N, :rerank-candidate-count N,
            :rerank-error <msg or nil>}. On any failure (including ColBERT
   service unreachable) returns the input chunks unchanged with :rerank-error set.

   When `strip-content?` is true, :content_markdown is removed from the
   returned chunks so the caller can preserve its metadata-only contract."
  [final-chunks queries docs-collection chunks-collection opts
   {:keys [rerank-candidate-k rerank-max-chunk-length strip-content? rerank-windowing]
    :or {rerank-candidate-k 40
         ;; Lever A default (see top-level entry): windowing ON, budget 2000.
         rerank-max-chunk-length 2000
         rerank-windowing true
         strip-content? true}}]
  (let [start (System/currentTimeMillis)]
    (try
      (if (empty? final-chunks)
        {:chunks final-chunks :rerank-ms 0 :rerank-candidate-count 0}
        (let [candidates (vec (take rerank-candidate-k final-chunks))
              tail (vec (drop rerank-candidate-k final-chunks))
              needs-fetch? (some #(nil? (:content_markdown %)) candidates)
              candidates-with-content
              (if needs-fetch?
                (let [fetched (rag/retrieve-chunks-by-id
                                docs-collection chunks-collection
                                (mapv (fn [c] {:chunk_id (:chunk_id c)}) candidates)
                                opts)
                      fetched-by-id (into {} (map (juxt :chunk_id identity)) fetched)]
                  (mapv (fn [c]
                          (if-let [full (get fetched-by-id (:chunk_id c))]
                            (merge c (select-keys full [:content_markdown]))
                            c))
                        candidates))
                candidates)
              rerank-params {:translated_user_query (or (first queries) "")
                             :docsCollectionName docs-collection
                             :rerankTopkChunks rerank-candidate-k
                             :rerankMaxChunkLength rerank-max-chunk-length
                             :rerankWindowing rerank-windowing
                             :rerankMaxLength 10000
                             ;; contextTopkChunks / maxContextLength are required
                             ;; by rerank-chunks but their output (context-docs,
                             ;; used-chunks) is unused here.
                             :contextTopkChunks 1
                             :maxContextLength 1
                             ;; Forward tenant so rerank/rerank-chunks can resolve
                             ;; :services.colbert.api-url / :services.colbert.api-key
                             ;; via the caller's tenant rather than normalizing to
                             ;; __platform-defaults__ (which lacks a platform tree
                             ;; in most deployments).
                             :tenant (:tenant opts)
                             :dataset-ref (:dataset-ref opts)}
              {:keys [reranked-chunks]} (rerank/rerank-chunks candidates-with-content rerank-params)
              reranked-by-id (into {} (map (juxt :chunk_id identity)) reranked-chunks)
              annotated (mapv (fn [c]
                                (let [rr (get reranked-by-id (:chunk_id c))
                                      c (if strip-content? (dissoc c :content_markdown) c)]
                                  (cond-> c
                                    ;; :snippet is the bounded read-decision
                                    ;; preview — it survives the content strip on
                                    ;; purpose (full content goes; the ~220-char
                                    ;; window stays) so the agent's metadata-only
                                    ;; display can show why the chunk matched.
                                    rr (assoc :rerank-score (:rerank-score rr)
                                              :rerank-rank (:rerank-rank rr)
                                              :snippet (:snippet rr)))))
                              candidates-with-content)
              ;; Stable sort by rerank-score desc; chunks without a score keep relative order.
              sorted (vec (sort-by #(- (double (or (:rerank-score %) 0.0))) annotated))
              duration (- (System/currentTimeMillis) start)]
          {:chunks (into sorted tail)
           :rerank-ms duration
           :rerank-candidate-count (count candidates)}))
      (catch Exception e
        {:chunks final-chunks
         :rerank-ms (- (System/currentTimeMillis) start)
         :rerank-candidate-count 0
         :rerank-error (.getMessage e)}))))

(defn execute-retrieval
  "Execute the retrieval skill.

   Inputs:
     :queries - Vector of search query strings, or a single query string
     :user-intent - Optional canonical user-intent string. When present, distinct
       from :queries, and :user-intent-union-enabled is true, retrieval runs a
       second pass with intent-only and union-merges the two reranked outputs.
     :docs-collection - TypeSense documents collection name
     :chunks-collection - TypeSense chunks collection name
     :phrases-collection - TypeSense phrases collection name

   Parameters:
     :limit - Max results per search strategy per query (default 30)
     :filter-by - Optional filter map for TypeSense
     :auto-filter - Enable automatic org detection (default true)
     :phrase-gen-prompt - Prompt used for phrase similarity search (optional)
     :user-intent-union-enabled (boolean, slice-23) - opt-in two-pass union.
     :user-intent-union-mode (:cap/:interleave/:rrf, default :cap)
     :user-intent-union-cap (default 3, only :cap mode)
     :user-intent-union-rrf-k (default 60, only :rrf mode)

   Returns:
     :chunks - Vector of chunk maps with search attribution
     :search-attribution - Map of search-type to hit counts. When the two-pass
                           union ran, :user-intent-pass holds the intent-pass
                           attribution (merged-hits count, rerank-ms, etc.)."
  [{:keys [inputs parameters skill-params dataset-ref]}]
  (let [{:keys [queries user-intent docs-collection chunks-collection phrases-collection]} inputs
        ;; Normalize queries to always be a vector
        queries (cond
                  (nil? queries) []
                  (string? queries) [queries]
                  (sequential? queries) (vec queries)
                  :else [queries])
        ;; Normalize :user-intent — only honor non-blank strings.
        user-intent (when (and (string? user-intent)
                               (not (str/blank? user-intent)))
                      (str/trim user-intent))]
    (try
      (let [{:keys [limit retrieve-top-k max-per-document query-aware-boost metadata-only
                    filter-by auto-filter strategy-weights strategy-contribution-caps
                    boost-weights diversity-config rerank-with-colbert
                    rerank-candidate-k rerank-max-chunk-length rerank-windowing
                    per-strategy-rerank? rerank-final-cap
                    enrichment-search-targets enrichment-types
                    title-fields doc-title-chunk-fanout
                    chunk-content-fields chunk-metadata-fields
                    retrieval-mode
                    auto-filter-rules
                    merge-mode rrf-k
                    user-intent-union-enabled
                    user-intent-union-mode
                    user-intent-union-cap
                    user-intent-union-rrf-k]
             ;; Lever A promoted to default (2026-06-03): rerank passage
             ;; windowing ON, window budget 2000. Confirmed net-positive at
             ;; full-42 N=5 JUDGED — recall@20 0.674->0.760, judge correct
             ;; 140->149, incorrect 14->7, every subset (long/mid/clean) up,
             ;; none regressed. See plans/proposed/rerank-truncation-plan.md.
             ;; The window budget IS rerank-max-chunk-length, so both must
             ;; default together to match the validated arm. To reproduce the
             ;; historical pre-Lever-A baseline, pass BOTH
             ;; {:rerank-windowing false :rerank-max-chunk-length 1000}.
             ;; :builtin/rerank is the other caller of rerank-chunks and
             ;; declares the same pair (#455);
             ;; digdir.skills.builtin.rerank-regime-parity-test fails if either
             ;; side moves alone.
             :or {rerank-windowing true
                  rerank-max-chunk-length 2000}} parameters
            ;; Self-derive enrichment targets so the enrichment lever works on
            ;; EVERY call path. The :bundled graph (production default) invokes
            ;; retrieval with :enrichment-types in its step config but never the
            ;; derived :enrichment-search-targets map (that derivation lived only
            ;; in agent-setup, which the bundled retrieval step doesn't read), so
            ;; enrichment was silently dormant. Honor explicit targets when given
            ;; (agent-setup / direct callers); otherwise derive {type ->
            ;; collection} from :enrichment-types + the resolved chunks-collection
            ;; (sibling name, no DB round-trip).
            enrichment-search-targets (or (not-empty enrichment-search-targets)
                                          (let [known (filter enrich-naming/enrichment-types
                                                              (when (sequential? enrichment-types)
                                                                enrichment-types))]
                                            (when (and (seq known) chunks-collection)
                                              (enrich-naming/enrichment-collection-names-from-base
                                               chunks-collection known))))
            effective-boost-weights (resolve-boost-weights boost-weights)
            effective-diversity-config (resolve-diversity-config diversity-config)
            effective-limit (or limit 30)
            effective-retrieve-top-k (or retrieve-top-k 100)
            configured-max-per-document (or max-per-document
                                            (:default-max-per-document effective-diversity-config))
            query-aware-boost-enabled (not (false? query-aware-boost))
            auto-filter-enabled (not (false? auto-filter))
            ;; When an enrichment sibling strategy is active and the
            ;; caller hasn't pinned its own weight, default
            ;; `:hypothetical-questions` to the phrase-strategy weight
            ;; (0.7). Without this it would fall through to the
            ;; merge fn's `:unknown 0.15` default and get demoted into
            ;; oblivion, defeating the point of the opt-in.
            enrichment-active? (and (map? enrichment-search-targets)
                                    (seq (filter (fn [[_ c]] (and c (seq c)))
                                                 enrichment-search-targets)))
            effective-strategy-weights (cond-> (or strategy-weights {})
                                         (and enrichment-active?
                                              (nil? (get strategy-weights :hypothetical-questions)))
                                         (assoc :hypothetical-questions 0.7)

                                         (and enrichment-active?
                                              (nil? (get strategy-weights :verified-phrases)))
                                         (assoc :verified-phrases 0.7)

                                         (and enrichment-active?
                                              (nil? (get strategy-weights :fact-assertions)))
                                         (assoc :fact-assertions 0.7))
            normalized-merge-mode (when merge-mode
                                    (let [m (if (keyword? merge-mode)
                                              merge-mode
                                              (keyword (str merge-mode)))]
                                      (when (contains? #{:rrf :rrf-with-score :combmnz :weighted-sum} m) m)))
            merge-opts (cond-> {}
                         (seq effective-strategy-weights) (assoc :strategy-weights effective-strategy-weights)
                         (seq strategy-contribution-caps) (assoc :strategy-contribution-caps strategy-contribution-caps)
                         normalized-merge-mode (assoc :merge-mode normalized-merge-mode)
                         (and rrf-k (pos? (long rrf-k))) (assoc :rrf-k (long rrf-k)))
            doc-title-fanout (or doc-title-chunk-fanout 3)
            doc-title-strategy-active? (and (sequential? title-fields)
                                            (seq title-fields)
                                            (pos? (long doc-title-fanout)))
            opts (cond-> {:tenant (:tenant skill-params)
                          :dataset-config-key (or (:dataset-config-key skill-params)
                                                  (:tenant-config-key skill-params))
                          :runtime-config-key (or (:runtime-config-key skill-params)
                                                  (:tenant-config-key skill-params))
                          :limit effective-limit
                          :retrieve-top-k effective-retrieve-top-k}
                   (seq merge-opts) (assoc :merge-opts merge-opts)
                   enrichment-active? (assoc :enrichment-search-targets
                                             enrichment-search-targets)
                   doc-title-strategy-active?
                   (assoc :title-fields (vec title-fields)
                          :doc-title-chunk-fanout (long doc-title-fanout))

                   (and (sequential? chunk-content-fields) (seq chunk-content-fields))
                   (assoc :content-fields (vec chunk-content-fields))

                   (and (sequential? chunk-metadata-fields) (seq chunk-metadata-fields))
                   (assoc :metadata-fields (vec chunk-metadata-fields))

                   (let [m (when retrieval-mode
                             (if (keyword? retrieval-mode)
                               retrieval-mode
                               (keyword (str/replace (str retrieval-mode) #"^:" ""))))]
                     (= m :hierarchical))
                   (assoc :retrieval-mode :hierarchical)

                   (and (sequential? auto-filter-rules) (seq auto-filter-rules))
                   (assoc :auto-filter-rules (vec auto-filter-rules)))
            ;; Per-pass pipeline: auto-filter → search → fetch → boost →
            ;; diversity → optional ColBERT rerank. Slice 23 calls this twice
            ;; when a distinct :user-intent is supplied (slice 21/22 union).
            do-pass
            (fn [pass-queries]
              (let [detected-filter (when (and auto-filter-enabled (seq pass-queries))
                                      (try
                                        (auto-filter/detect-query-filters
                                          pass-queries docs-collection opts)
                                        (catch Exception e
                                          (println "auto-filter detection failed:" (.getMessage e))
                                          nil)))
                    effective-filter-by (merge-filter-by filter-by detected-filter)
                    result (run-search-strategies
                             pass-queries docs-collection chunks-collection phrases-collection
                             effective-filter-by opts)
                    fallback-filter (when detected-filter filter-by)
                    [result auto-filter-fallback]
                    (if (and detected-filter effective-filter-by (empty? (:merged-hits result)))
                      [(run-search-strategies
                         pass-queries docs-collection chunks-collection phrases-collection
                         fallback-filter opts)
                       true]
                      [result false])
                    fetch-fn (if metadata-only
                               rag/retrieve-chunk-metadata-by-id
                               rag/retrieve-chunks-by-id)
                    chunks (when (seq (:merged-hits result))
                             (fetch-fn docs-collection chunks-collection (:merged-hits result) opts))
                    boosted-chunks (if query-aware-boost-enabled
                                     (prioritize-chunks (vec chunks) pass-queries docs-collection
                                                        effective-filter-by effective-boost-weights)
                                     (vec chunks))
                    effective-max-per-document (if (some? max-per-document)
                                                 configured-max-per-document
                                                 (maybe-relax-default-diversity-cap
                                                   boosted-chunks
                                                   configured-max-per-document
                                                   effective-diversity-config))
                    capped-chunks (cap-per-document boosted-chunks effective-max-per-document)
                    dropped-by-diversity (max 0 (- (count boosted-chunks) (count capped-chunks)))
                    final-chunks (suppress-duplicate-files capped-chunks)
                    ;; Reported rather than silent, matching dropped-by-diversity
                    ;; above: a response that quietly differs from the retrieval
                    ;; is the defect shape this repository keeps finding.
                    dropped-as-duplicate-file (max 0 (- (count capped-chunks) (count final-chunks)))
                    ;; Optional ColBERT rerank. Two modes:
                    ;;   - default      : single rerank over the merged candidate list
                    ;;   - per-strategy : ColBERT rerank each of phrase/metadata/content
                    ;;                    independently (in parallel), then merge top-N
                    ;;                    from each. Surfaces strategy-level signals
                    ;;                    that the merge function might dilute.
                    {ranked-chunks :chunks
                     rerank-ms :rerank-ms
                     rerank-candidate-count :rerank-candidate-count
                     rerank-error :rerank-error
                     per-strategy-counts :per-strategy-counts}
                    (cond
                      (and rerank-with-colbert per-strategy-rerank?)
                      (apply-per-strategy-colbert-rerank
                        [(:phrase-hits result) (:metadata-hits result) (:content-hits result)]
                        pass-queries docs-collection chunks-collection opts
                        {:rerank-candidate-k (or rerank-candidate-k 40)
                         :rerank-max-chunk-length (or rerank-max-chunk-length 2000)
                         :rerank-windowing rerank-windowing
                         :strip-content? (boolean metadata-only)
                         :final-cap (or rerank-final-cap 40)})

                      rerank-with-colbert
                      (apply-colbert-rerank final-chunks pass-queries docs-collection
                                            chunks-collection opts
                                            {:rerank-candidate-k (or rerank-candidate-k 40)
                                             :rerank-max-chunk-length (or rerank-max-chunk-length 2000)
                                             :rerank-windowing rerank-windowing
                                             :strip-content? (boolean metadata-only)})

                      :else
                      {:chunks final-chunks :rerank-ms 0 :rerank-candidate-count 0})]
                {:ranked-chunks ranked-chunks
                 :result result
                 :effective-filter-by effective-filter-by
                 :detected-filter detected-filter
                 :auto-filter-fallback auto-filter-fallback
                 :effective-max-per-document effective-max-per-document
                 :chunks-before-diversity (count boosted-chunks)
                 :chunks-after-diversity (count final-chunks)
                 :dropped-by-diversity dropped-by-diversity
                 :dropped-as-duplicate-file dropped-as-duplicate-file
                 :rerank-ms rerank-ms
                 :rerank-candidate-count rerank-candidate-count
                 :rerank-error rerank-error
                 :per-strategy-counts per-strategy-counts}))

            ;; Decide whether to run the user-intent union (slice 23).
            ;; Off unless explicitly enabled AND :user-intent is supplied AND
            ;; it's distinct from the single-query case.
            union-config (when (and user-intent
                                    (true? user-intent-union-enabled)
                                    (not (and (= 1 (count queries))
                                              (= user-intent (first queries)))))
                           (let [m (cond
                                     (keyword? user-intent-union-mode) user-intent-union-mode
                                     (string? user-intent-union-mode)
                                     (keyword (str/replace user-intent-union-mode #"^:" ""))
                                     :else :cap)]
                             {:mode m
                              :cap (or user-intent-union-cap 3)
                              :rrf-k (or user-intent-union-rrf-k 60)}))

            ;; Expansion pass — always runs.
            expansion-pass (do-pass queries)
            ;; Intent pass — only when union is configured.
            intent-pass (when union-config (do-pass [user-intent]))

            ranked-chunks (if intent-pass
                            (union/union-merge (:ranked-chunks intent-pass)
                                               (:ranked-chunks expansion-pass)
                                               union-config)
                            (:ranked-chunks expansion-pass))

            ;; Surface expansion-pass attribution as the primary view.
            result (:result expansion-pass)
            effective-filter-by (:effective-filter-by expansion-pass)
            detected-filter (:detected-filter expansion-pass)
            auto-filter-fallback (:auto-filter-fallback expansion-pass)
            effective-max-per-document (:effective-max-per-document expansion-pass)
            rerank-ms (:rerank-ms expansion-pass)
            rerank-candidate-count (:rerank-candidate-count expansion-pass)
            rerank-error (:rerank-error expansion-pass)
            per-strategy-counts (:per-strategy-counts expansion-pass)

            search-attribution (cond-> {:phrase (count (:phrase-hits result))
                                        :metadata (count (:metadata-hits result))
                                        :content (count (:content-hits result))
                                        :merged (count (:merged-hits result))
                                        :retrieve-top-k effective-retrieve-top-k
                                        :query-aware-boost-enabled query-aware-boost-enabled
                                        :max-per-document configured-max-per-document
                                        :effective-max-per-document effective-max-per-document
                                        :chunks-before-diversity (:chunks-before-diversity expansion-pass)
                                        :chunks-after-diversity (:chunks-after-diversity expansion-pass)
                                        :dropped-by-diversity (:dropped-by-diversity expansion-pass)}
                                 doc-title-strategy-active?
                                 (assoc :doc-title (count (:doc-title-hits result)))
                                 enrichment-active?
                                 (assoc :enrichment-hits-by-type
                                        (:enrichment-hits-by-type result))
                                 effective-filter-by
                                 (assoc :filter-applied effective-filter-by
                                        :filter-source (cond
                                                         (and filter-by detected-filter) :merged
                                                         detected-filter :auto
                                                         filter-by :explicit
                                                         :else nil))
                                 detected-filter
                                 (assoc :auto-filter-applied detected-filter)
                                 auto-filter-fallback
                                 (assoc :auto-filter-fallback true)
                                 rerank-with-colbert
                                 (assoc :rerank-enabled? true
                                        :rerank-ms rerank-ms
                                        :rerank-candidate-count rerank-candidate-count)
                                 per-strategy-counts
                                 (assoc :per-strategy-rerank? true
                                        :per-strategy-counts per-strategy-counts)
                                 rerank-error
                                 (assoc :rerank-error rerank-error)
                                 intent-pass
                                 (assoc :user-intent-pass
                                        {:merged (count (:merged-hits (:result intent-pass)))
                                         :chunks-after-diversity (:chunks-after-diversity intent-pass)
                                         :rerank-ms (:rerank-ms intent-pass)
                                         :rerank-candidate-count (:rerank-candidate-count intent-pass)
                                         :rerank-error (:rerank-error intent-pass)
                                         :union-mode (:mode union-config)
                                         :union-cap (:cap union-config)
                                         :union-rrf-k (:rrf-k union-config)}))]

        (skills/success-result
          {:chunks ranked-chunks
           :search-attribution search-attribution}
          {:search-strategies-used (+ 3
                                      (if doc-title-strategy-active? 1 0)
                                      (if enrichment-active?
                                        (count (filter (fn [[_ c]] (and c (seq c)))
                                                       enrichment-search-targets))
                                        0))
           :dataset-ref (or dataset-ref (:dataset-ref inputs) (:dataset-ref skill-params))
           :total-hits-before-merge (+ (count (:phrase-hits result))
                                       (count (:metadata-hits result))
                                       (count (:content-hits result))
                                       (count (:doc-title-hits result)))}))
      (catch Exception e
        (skills/error-result
         :retrieval-backend-failure
         (str "Retrieval backend failure: " (.getMessage e))
          {:queries queries
          :dataset-ref (or dataset-ref (:dataset-ref inputs) (:dataset-ref skill-params))
          :docs-collection docs-collection
          :chunks-collection chunks-collection
          :phrases-collection phrases-collection
          :tenant (:tenant skill-params)
          :dataset-config-key (or (:dataset-config-key skill-params)
                                  (:tenant-config-key skill-params))
          :runtime-config-key (or (:runtime-config-key skill-params)
                                  (:tenant-config-key skill-params))})))))
;; =============================================================================
;; Skill Registration
;; =============================================================================

(def retrieval-skill
  {:metadata retrieval-metadata
   :execute execute-retrieval})

(defn register!
  "Register the retrieval skill."
  []
  (skills/register-skill! retrieval-skill))
