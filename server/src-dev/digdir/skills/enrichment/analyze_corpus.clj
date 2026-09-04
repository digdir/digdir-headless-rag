(ns digdir.skills.enrichment.analyze-corpus
  "`:builtin/enrichment-analyze-corpus` — open the self-improve graph
   by orienting against the corpus and selecting which chunks to try
   enriching.

   The ReAct version of the self-improve agent had a tool of the same
   name that returned raw corpus stats and let the LLM pick which
   chunks to act on. This skill is the graph-version analog: it picks
   a small set of chunk-ids using a heuristic (skip chunks that
   already have enrichment rows; take the first `:max-chunks`),
   reports the corpus stats, and emits a one-paragraph analysis prose
   that the terminal `:builtin/enrichment-compose-report` will cite.

   v1 is deliberately heuristic, not LLM-driven. The validation
   experiment for the skill-graph thesis is cleaner when the graph
   has fewer LLM slots than the ReAct comparison — if the graph wins
   with ONE LLM slot (propose-questions) versus the ReAct loop's
   unbounded slots, the structural-determinism claim is stronger. An
   LLM-driven variant can be added later by extending the
   `:selection-mode` parameter.

   Lives in `src-dev/` alongside its siblings — offline tooling, not
   part of the runtime retrieval path."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.collections :as enrich-coll]
            [taoensso.timbre :as timbre]
            [typesense.client :as ts]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def analyze-corpus-metadata
  {:skill-id :builtin/enrichment-analyze-corpus
   :name "Analyze corpus and pick chunks for enrichment"
   :description "Report corpus stats and select up to :max-chunks chunk-ids to try enriching. Default mode is heuristic (skip already-enriched, take first N). Pass :selection-mode :llm with a :user-query to instead rank candidates by topical relevance via a single LLM call (degrades to heuristic if the LLM errors)."
   :category :orchestration
   ;; Only :chunks-collection is strictly required (the skill body
   ;; throws otherwise). Everything else has a sensible default and is
   ;; deliberately omitted from this list so the runner's input
   ;; validator (which treats every declared input as REQUIRED) doesn't
   ;; reject callers that elide an optional argument. The body still
   ;; destructures all of them from `:inputs` when present.
   :inputs [:chunks-collection]
   :outputs [:chunk-ids :analysis :corpus-stats :enrichment-collection-name]
   ;; `:selection-mode` is a parameter (literal value) rather than an
   ;; input — the graph runner's input-ref validator otherwise treats
   ;; the keyword `:llm` as a step-output ref and rejects the graph.
   :parameters {:selection-mode :keyword
                :enrichment-type :keyword}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :enrichment :self-improve :graph-only}})

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private enrichment-type->segment
  "Maps enrichment-type keyword → underscore segment used inside the
   derived collection name. Stays in sync with
   `digdir.skills.enrichment.collections/type->name-segment`."
  {:hypothetical-questions "enrichment_hypothetical_questions_"
   :verified-phrases       "enrichment_verified_phrases_"
   :fact-assertions        "enrichment_fact_assertions_"})

(defn derive-enrichment-collection-name
  "Derive an enrichment collection name from a `docs-collection` name
   by swapping the `documents_<hash>` segment for
   `enrichment_<type>_<hash>`. Returns nil when the input doesn't
   match the invariant — better to surface a missing-collection error
   than silently target a wrong-looking name.

   `enrichment-type` defaults to `:hypothetical-questions` to preserve
   the Phase B/C.5 behaviour for callers that don't pass it explicitly;
   Phase D1 callers pass `:verified-phrases` to target the new
   collection."
  ([docs-collection]
   (derive-enrichment-collection-name docs-collection :hypothetical-questions))
  ([docs-collection enrichment-type]
   (when (and docs-collection (seq docs-collection))
     (when-some [segment (get enrichment-type->segment enrichment-type)]
       (let [replaced (str/replace docs-collection
                                   #"documents_(?=[a-f0-9]+$)"
                                   segment)]
         (when (not= replaced docs-collection)
           replaced))))))

(defn- count-rows
  "One-document `*` search just to read `:found`. Cheap; we don't
   actually need the hit. On Typesense error, warn-log and return 0 —
   the analyse-step's corpus-stats will show a 0 count which the
   trace consumer can spot as suspicious."
  [settings collection-name]
  (try
    (:found (ts/search settings collection-name {:q "*" :per_page 1 :page 1}))
    (catch Throwable t
      (timbre/warn t (str "analyze-corpus: count-rows failed for " collection-name))
      0)))

(defn- sample-chunks
  "Pull a page of `sample-size` chunks for the LLM to choose from.

   When `:query` is supplied, runs a content-based search (Typesense
   ranks by relevance to the query) — gives the LLM-selection path a
   topically-coherent candidate pool. Without a query, sorts by
   `chunk_id:asc` (deterministic alphabetical-first), which is the
   heuristic path's behavior.

   `include-content?` defaults to false (the heuristic only needs
   chunk_id / chunk_index / title / content_length). The LLM-driven
   path passes `true` so the preview list shown to the model includes
   a content snippet."
  ([settings collection-name sample-size]
   (sample-chunks settings collection-name sample-size false nil))
  ([settings collection-name sample-size include-content?]
   (sample-chunks settings collection-name sample-size include-content? nil))
  ([settings collection-name sample-size include-content? query]
   (try
     (let [page-size (min (max 1 sample-size) 250)
           ;; When we have a query, ask Typesense to rank by content
           ;; relevance. Without one, we want the deterministic
           ;; alphabetical-first slice the heuristic path relies on.
           ;; Chunks collection has no `title` field (it lives on the
           ;; docs collection). Searching against a non-existent field
           ;; would throw, get caught silently, and return an empty
           ;; sample — which is the bug that surfaced in the 15:32 trace.
           base-opts (if (and (string? query) (seq (str/trim query)))
                       {:q query
                        :query_by "content_markdown"
                        :per_page page-size
                        :page 1}
                       {:q "*"
                        :per_page page-size
                        :sort_by "chunk_id:asc"
                        :page 1})
           resp (ts/search settings collection-name
                           (cond-> base-opts
                             include-content?
                             (assoc :include_fields
                                    "chunk_id,doc_num,chunk_index,title,url,content_length,content_markdown")))]
       (mapv :document (:hits resp)))
     ;; Distinguish "Typesense returned 0 hits" (caller gets [] from
     ;; the `:hits resp` path) from "search threw" (caller gets [] but
     ;; the dev console gets a warn). Five validation-time bugs hid
     ;; behind this exception getting swallowed silently.
     (catch Throwable t
       (timbre/warn t (str "analyze-corpus: sample-chunks failed against "
                           collection-name
                           (when query (str " (query=" (pr-str query) ")"))))
       []))))

(defn- chunks-with-enrichment
  "Look up the set of `chunk_id`s that already have at least one
   enrichment row. We over-fetch (one page of N IDs) rather than
   querying per-chunk; on a small dev corpus this is fine, and the
   alternative (N round-trips) regresses startup latency for the
   graph's analysis step.

   Returns `#{}` (not nil) on any failure so the caller can still
   proceed without exclusion."
  [settings enrichment-collection chunk-ids]
  (if (or (nil? enrichment-collection)
          (empty? enrichment-collection)
          (empty? chunk-ids))
    #{}
    (try
      (let [filter-by (str "chunk_id:=[" (str/join "," chunk-ids) "]")
            resp (ts/search settings enrichment-collection
                            {:q "*"
                             :filter_by filter-by
                             :include_fields "chunk_id"
                             :per_page 250})]
        (->> (:hits resp)
             (map :document)
             (map :chunk_id)
             (remove nil?)
             set))
      (catch Throwable t
        (timbre/warn t (str "analyze-corpus: chunks-with-enrichment failed against "
                            enrichment-collection))
        #{}))))

(defn select-chunk-ids
  "Pure selection: given a vec of sample-chunk-maps, the set of
   already-enriched chunk-ids to skip, and a max-count, return the
   first `max-count` chunk-ids whose chunk_id is not in the skip-set.

   Pure function (no I/O) so the test suite can pin behavior without
   touching Typesense. Public for the test ns."
  [sample-chunks already-enriched max-chunks]
  (->> sample-chunks
       (map :chunk_id)
       (remove nil?)
       (remove (or already-enriched #{}))
       (take (max 0 (or max-chunks 0)))
       vec))

;; =============================================================================
;; LLM-driven selection (:selection-mode :llm)
;; =============================================================================

(defn- chunk-preview
  "One-line preview shown to the LLM during selection: chunk_id, then
   the document title, then a short snippet of the body. Capping at
   ~200 chars per chunk keeps a 50-chunk batch under ~12 KB."
  [chunk]
  (let [chunk-id (:chunk_id chunk)
        title (or (:title chunk) "")
        normalised (-> (or (:content_markdown chunk) "")
                       (str/replace #"\s+" " ")
                       str/trim)
        snippet (subs normalised 0 (min 200 (count normalised)))]
    (str chunk-id " | " title " :: " snippet)))

(defn render-selection-prompt
  "Build the prompt sent to the LLM when `:selection-mode :llm`.

   D2.18: The earlier prompt said 'pick the N chunks MOST RELEVANT',
   which the LLM read as a quality threshold and routinely returned 1
   chunk even when 10 were asked for. The new prompt frames this as a
   RANKING task — return the top N by relevance, knowing a downstream
   verify step will reject anything that doesn't measurably help
   retrieval. So the LLM should err on the side of inclusion: missing
   an opportunity is worse than emitting a candidate that gets
   reverted.

   Public so tests can pin the wording without hitting an LLM."
  [user-query max-chunks chunks]
  (str
   "You are RANKING chunks of a documentation corpus for search-enrichment.\n\n"
   "Each chunk_id you return will be independently scored by a downstream "
   "verify step that measures whether enrichment of that chunk actually "
   "improves retrieval for the user's query. Any candidate that doesn't "
   "demonstrably help gets reverted automatically. So your job is to RANK "
   "the candidates by topical relevance — NOT to filter on a quality "
   "threshold. Returning fewer than " max-chunks " chunks means missing "
   "improvement opportunities; the verify step is the gate, not you.\n\n"
   "Be inclusive: include candidates that are even loosely on-topic. The "
   "downstream verify call is cheap and accurate.\n\n"
   "User query:\n" (or user-query "") "\n\n"
   "Candidate chunks (chunk_id | title :: snippet):\n"
   (str/join "\n" (map chunk-preview chunks))
   "\n\nReturn the top " max-chunks " chunk_ids ranked by topical relevance, "
   "most-relevant first. Output ONLY the chunk_ids, one per line — no bullets, "
   "no numbering, no commentary, no header."))

(defn parse-llm-selection
  "Parse the LLM's selection response into a vec of chunk_ids. Strips
   list markers and blank lines, drops anything that isn't a recognised
   chunk_id (treats the LLM as a ranking signal, not a source of new
   ids — the corpus is closed). Public for tests."
  [response known-chunk-ids]
  (let [content (-> response :choices first :message :content (or ""))
        known (set known-chunk-ids)]
    (->> (str/split-lines content)
         (map #(str/replace % #"^\s*(?:[-•*]\s+|\(?\d+[\.\)]\s+)" ""))
         (map str/trim)
         (remove str/blank?)
         (filter known)
         distinct
         vec)))

(defn- resolve-model
  [tenant explicit-model]
  (or explicit-model
      (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)))

(defn- llm-select-via-azure
  "Single Azure OpenAI chat completion for chunk selection. Resolves
   the model + endpoint inside so tests can plumb a fake `llm-call-fn`
   that takes only the prompt — without needing fake Azure config."
  [tenant prompt]
  (let [model (resolve-model tenant nil)]
    (openai/create-chat-completion
     {:model model
      :messages [{:role "system"
                  :content "You select chunk_ids of documentation passages whose content is most relevant to a user's query. Reply only with chunk_ids, one per line."}
                 {:role "user" :content prompt}]
      :temperature 0.1}
     {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
      :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
      :impl :azure})))

(defn llm-select-chunk-ids
  "LLM-driven selection. Given the user's query, ranks the sampled
   chunks for topical relevance and returns the top `max-chunks` ids.
   Public so callers / tests can plumb a fake `llm-call-fn` (defaults
   to Azure).

   Degrades gracefully: if the LLM call throws or returns no parseable
   chunk_ids, returns `nil` so the caller can fall back to the
   heuristic path rather than abort the whole run."
  ([tenant user-query max-chunks chunks already-enriched]
   (llm-select-chunk-ids tenant user-query max-chunks chunks already-enriched
                         {:llm-call-fn (fn [p] (llm-select-via-azure tenant p))}))
  ([_tenant user-query max-chunks chunks already-enriched {:keys [llm-call-fn]}]
   (try
     (let [candidate-chunks (->> chunks
                                 (remove (fn [c]
                                           (contains? (or already-enriched #{})
                                                      (:chunk_id c))))
                                 vec)
           known-ids (mapv :chunk_id candidate-chunks)
           prompt (render-selection-prompt user-query max-chunks candidate-chunks)
           response (llm-call-fn prompt)
           selected (parse-llm-selection response known-ids)]
       (when (seq selected)
         (vec (take (max 0 (or max-chunks 0)) selected))))
     (catch Throwable t
       (timbre/warn t "analyze-corpus: LLM selection failed; will fall back to heuristic")
       nil))))

(defn build-analysis-prose
  "Pure: single-line Markdown summary that leads with the user-intent
   topic so a reader can immediately see WHAT this run is trying to
   improve retrieval for. Public so tests can pin the wording — the
   playground chat surface embeds this verbatim via `compose-report`.

   D2.14: collapsed three sentences to one, with the intent topic
   first and the rest as inline counters."
  [{:keys [total-docs total-chunks existing-enrichment-rows
           selected-count excluded-already-enriched? max-chunks
           user-query]}]
  (let [topic-fragment (when (and (string? user-query) (seq (str/trim user-query)))
                         (str "Intent: «" (str/trim user-query) "» · "))
        existing-fragment (when (pos? (or existing-enrichment-rows 0))
                            (format ", %d existing enrichment row(s)"
                                    existing-enrichment-rows))
        filter-fragment (when-not excluded-already-enriched?
                          " (no enrichment-status filter)")]
    (str topic-fragment
         (format "Selected %d/%d chunks from %d (%d docs%s)%s."
                 (or selected-count 0)
                 (or max-chunks 0)
                 (or total-chunks 0)
                 (or total-docs 0)
                 (or existing-fragment "")
                 (or filter-fragment "")))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-analyze-corpus
  "Read corpus stats from Typesense, pick chunk-ids, emit analysis
   prose. See ns docstring for design rationale."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [tenant
                docs-collection
                chunks-collection
                enrichment-collection
                max-chunks
                exclude-already-enriched?
                sample-size
                user-query
                queries
                explicit-chunk-ids]} inputs
        ;; `:selection-mode` is a PARAMETER (literal) not an input,
        ;; because the runner's input-ref validator otherwise rejects
        ;; `:llm` as an unknown step-output reference.
        selection-mode (:selection-mode parameters)
        ;; `:enrichment-type` decides which enrichment collection name
        ;; gets derived (hypothetical-questions vs verified-phrases).
        ;; Defaults to hypothetical-questions for Phase B/C.5 callers.
        enrichment-type (or (:enrichment-type parameters)
                            :hypothetical-questions)
        ;; When the upstream `:intent` step extracted chunk_ids the
        ;; user named verbatim, honour those directly: skip BOTH the
        ;; content-search candidate sampling AND the LLM ranking. The
        ;; user told us what to enrich; don't second-guess.
        explicit? (boolean (seq explicit-chunk-ids))
        effective-tenant (or tenant (:tenant skill-params))
        effective-max-chunks (or max-chunks 3)
        effective-sample-size (or sample-size 50)
        exclude? (if (some? exclude-already-enriched?)
                   (boolean exclude-already-enriched?)
                   true)
        ;; LLM mode triggers iff explicitly requested AND a non-blank
        ;; user-query is available. Otherwise fall through to the
        ;; deterministic heuristic — keeps the experiment reproducible
        ;; when the playground doesn't thread a meaningful query.
        llm-mode? (and (= :llm selection-mode)
                       (string? user-query)
                       (seq (str/trim user-query)))]
    (cond
      (or (nil? chunks-collection) (and (string? chunks-collection) (empty? chunks-collection)))
      (throw (ex-info "Missing :chunks-collection input"
                      {:skill-id :builtin/enrichment-analyze-corpus}))

      :else
      (let [settings (ts-utils/make-ts-settings
                      (when effective-tenant {:tenant effective-tenant}))
            _ (when (nil? settings)
                (throw (ex-info "No Typesense settings — tenant missing or unconfigured"
                                {:tenant effective-tenant})))
            ;; Derive the enrichment collection name from docs-collection
            ;; when caller didn't supply one. The playground threads
            ;; docs/chunks/phrases as graph inputs but doesn't know about
            ;; the enrichment collection; deriving here means the
            ;; downstream apply / revert steps can pick up
            ;; `[:analyze :enrichment-collection-name]` instead of
            ;; needing a separate graph input.
            effective-enrichment-collection
            (or enrichment-collection
                (derive-enrichment-collection-name docs-collection enrichment-type))
            ;; Auto-ensure the enrichment collection exists before any
            ;; downstream step (apply / revert / count-rows) targets
            ;; it. Idempotent — returns :already-exists when the
            ;; collection is already there. Wrapped to warn-log on
            ;; failure: if create errors with something genuinely
            ;; unrecoverable (typesense reachability, schema rejection)
            ;; the subsequent count-rows will already-warn-and-return-0,
            ;; and the apply step downstream will surface the real
            ;; error with full context, so swallowing here doesn't hide
            ;; the symptom.
            _ (when (and effective-enrichment-collection docs-collection)
                (try
                  (enrich-coll/ensure-collection-by-name!
                   {:tenant effective-tenant}
                   docs-collection
                   effective-enrichment-collection
                   enrichment-type)
                  (catch Throwable t
                    (timbre/warn t (str "analyze-corpus: ensure-collection failed for "
                                        effective-enrichment-collection)))))
            total-docs (when docs-collection
                         (count-rows settings docs-collection))
            total-chunks (count-rows settings chunks-collection)
            existing-rows (when effective-enrichment-collection
                            (count-rows settings effective-enrichment-collection))
            ;; Sampling strategy:
            ;; - `:explicit?` short-circuit: the user named chunk_ids
            ;;   verbatim (or the upstream `:intent` step did);
            ;;   don't sample or rank — go straight to those.
            ;; - LLM mode w/o explicit ids: content-search ranked by
            ;;   topical relevance to the user-query (corpus might be
            ;;   Norwegian, query might be English; the upstream intent
            ;;   step is expected to translate).
            ;; - Heuristic: alphabetical chunk_id-asc, deterministic.
            ;;
            ;; D2.19 — When `:queries` (a vec) is supplied (from a
            ;; preceding query-planner step), run sample-chunks once
            ;; per query and dedupe by chunk_id. Each query lands a
            ;; different topical slice; the merged pool gives the LLM
            ;; ranker more candidates than a single-query search would.
            effective-queries (cond
                                explicit? nil
                                (and (sequential? queries)
                                     (seq (filter (fn [q]
                                                    (and (string? q)
                                                         (seq (str/trim q))))
                                                  queries)))
                                (->> queries
                                     (filter string?)
                                     (map str/trim)
                                     (remove str/blank?)
                                     distinct
                                     vec)
                                llm-mode? [user-query]
                                :else nil)
            sample (cond
                     explicit?
                     []
                     (seq effective-queries)
                     ;; Multi-pass sample: union of per-query results,
                     ;; deduped by chunk_id. Each pass uses the same
                     ;; sample-size budget — total candidates ≤
                     ;; sample-size × |queries|, but with overlap usually
                     ;; far less in practice.
                     (->> effective-queries
                          (mapcat #(sample-chunks settings chunks-collection
                                                  effective-sample-size true %))
                          (reduce (fn [{:keys [seen acc] :as state} chunk]
                                    (let [cid (:chunk_id chunk)]
                                      (if (or (nil? cid) (contains? seen cid))
                                        state
                                        {:seen (conj seen cid)
                                         :acc (conj acc chunk)})))
                                  {:seen #{} :acc []})
                          :acc)
                     :else
                     (sample-chunks settings chunks-collection
                                    effective-sample-size false nil))
            sample-ids (->> sample (map :chunk_id) (remove nil?) distinct vec)
            already (cond
                      explicit?
                      (if exclude?
                        (chunks-with-enrichment settings effective-enrichment-collection
                                                explicit-chunk-ids)
                        #{})
                      exclude?
                      (chunks-with-enrichment settings effective-enrichment-collection sample-ids)
                      :else
                      #{})
            explicit-selected (when explicit?
                                (->> explicit-chunk-ids
                                     (remove (or already #{}))
                                     (take (max 0 (or effective-max-chunks 0)))
                                     vec))
            llm-selected (when (and (not explicit?) llm-mode?)
                           (llm-select-chunk-ids effective-tenant user-query
                                                 effective-max-chunks sample already))
            heuristic-selected (when-not explicit?
                                 (select-chunk-ids sample already effective-max-chunks))
            selected (or explicit-selected
                         llm-selected
                         heuristic-selected
                         [])
            effective-mode (cond
                             explicit? :explicit
                             (and llm-mode? (some? llm-selected)) :llm
                             llm-mode? :heuristic-fallback
                             :else :heuristic)
            stats {:total-docs total-docs
                   :total-chunks total-chunks
                   :existing-enrichment-rows existing-rows
                   :docs-collection docs-collection
                   :chunks-collection chunks-collection
                   :enrichment-collection effective-enrichment-collection
                   :sample-size (count sample)
                   :selection-mode effective-mode}
            prose (build-analysis-prose
                   {:total-docs total-docs
                    :total-chunks total-chunks
                    :existing-enrichment-rows existing-rows
                    :selected-count (count selected)
                    :excluded-already-enriched? exclude?
                    :max-chunks effective-max-chunks
                    :user-query user-query})]
        (skills/success-result
         {:chunk-ids selected
          :enrichment-collection-name effective-enrichment-collection
          :analysis prose
          :corpus-stats stats}
         {:tenant effective-tenant
          :max-chunks effective-max-chunks
          :sample-considered (count sample)})))))

;; =============================================================================
;; Registration
;; =============================================================================

(def analyze-corpus-skill
  {:metadata analyze-corpus-metadata
   :execute execute-analyze-corpus})

(defn register!
  "Register the analyze-corpus skill. Idempotent."
  []
  (skills/register-skill! analyze-corpus-skill))

(register!)
