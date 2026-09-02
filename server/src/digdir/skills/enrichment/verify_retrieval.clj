(ns digdir.skills.enrichment.verify-retrieval
  "`:builtin/enrichment-verify-retrieval` — D2.8 / D2.10

   After `:builtin/enrichment-apply-*` writes a chunk's enrichment to
   its parallel Typesense collection, this skill answers a sharper
   question than `:builtin/enrichment-eval-suite` does:

     *Did the new enrichment actually create a retrieval path from
      the user's stated intent to this chunk, that wasn't there
      before?*

   The eval-suite skill runs a fixed benchmark (regression guard).
   It tells you whether the change broke anything. Verify tells you
   whether the change is useful for this run's user query. Both
   matter; the inner sub-graph composes them.

   ## How we measure improvement (D2.10)

   Two Typesense calls, both scoped by the extracted user-intent topic:

   1. **Baseline** — `search-chunks-by-content` against the chunks
      collection. Does the chunk appear in the standard content-search
      result list, *without* the new enrichment?
   2. **Enriched** — `lookup-*-similar` against the new enrichment
      collection (dispatched by `:enrichment-type`).

   We then compare and derive:

   - `:newly-findable?` — true iff baseline missed the chunk and the
     enriched lookup found it. The strongest improvement signal.
   - `:position-delta` — `baseline-index - enriched-index`. Positive
     means the chunk moved up in the result list. Most-meaningful
     numeric signal even though the two result lists are produced by
     different Typesense scoring (text-match vs hybrid rank-fusion),
     because both lists are ordered by relevance.
   - `:rank-delta` — `enriched-rank - baseline-rank`. Reported for
     traces but **not directly comparable** across the two scoring
     systems — keep `:position-delta` as the primary signal.
   - `:improved?` — `newly-findable? OR positively-moved?`. False if
     baseline already found the chunk at a higher position and the
     enrichment didn't help.
   - `:keep?` — `(and improved? eval-gate-pass?)`. The composite gate
     the `:decide` step switches on.

   ## Graceful degradation

   If `:user-query`, `:chunks-collection`, or
   `:enrichment-collection-name` is missing, verify short-circuits to
   eval-only (`:keep?` = `:eval-gate-pass?`) and surfaces the reason in
   `:summary`. Lets graphs run pre-D2.8 callers without breaking.

   Lives in `src-dev/` alongside its enrichment siblings."
  (:require [digdir.rag.retrieval :as rag]
            [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def verify-retrieval-metadata
  {:skill-id :builtin/enrichment-verify-retrieval
   :name "Verify retrieval improvement"
   :description "Run pre/post lookups for the user's intent topic. Returns :improved? (with rank/position deltas) plus a composite :keep? gate that ANDs with the eval-suite's gate-pass."
   :category :validation
   ;; `:enrichment-type` is a PARAMETER, not an input — each graph
   ;; variant pins it as a literal keyword in its verify step
   ;; (`:hypothetical-questions` / `:verified-phrases` /
   ;; `:fact-assertions`). Putting a literal keyword in `:inputs`
   ;; would make the runner interpret it as a step reference and
   ;; reject the graph at semantic-validation time.
   :inputs [:chunk-id :enrichment-collection-name :docs-collection
            :chunks-collection :user-query]
   :outputs [:baseline-found? :baseline-rank :baseline-index
             :enriched-found? :enriched-rank :enriched-index
             :rank-delta :position-delta :newly-findable?
             :matched-enrichment :improved? :eval-gate-pass? :keep? :summary]
   :parameters {:limit :number :enrichment-type :keyword}
   :required-services #{:typesense}
   :version "1.1.0"
   :tags #{:enrichment :self-improve :diagnostics}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- lookup-fn-for
  "Return the rag/lookup-*-similar function for `enrichment-type`, or
   nil if we don't have a lookup wired for the type."
  [enrichment-type]
  (case enrichment-type
    :hypothetical-questions rag/lookup-hypothetical-questions-similar
    :verified-phrases       rag/lookup-verified-phrases-similar
    :fact-assertions        rag/lookup-fact-assertions-similar
    nil))

(defn- matched-enrichment-of
  "Extract the surface form that matched, in a shape compose-report can
   render. Falls through nil keys so an unknown enrichment-type doesn't
   throw — verify is observability, not a critical path."
  [hit]
  (cond
    (:matched-question hit) {:question (:matched-question hit)}
    (:matched-phrase hit)   {:phrase (:matched-phrase hit)}
    (:matched-triple hit)   (:matched-triple hit)
    :else nil))

(defn- find-hit
  "First hit in `hits` whose `:chunk_id` equals `chunk-id`, or nil."
  [hits chunk-id]
  (first (filter #(= chunk-id (:chunk_id %)) hits)))

(defn- format-rank
  [r]
  (when (number? r) (format "%.3f" (double r))))

(defn- summarise
  [{:keys [baseline-found? baseline-rank baseline-index
           enriched-found? enriched-rank enriched-index
           _rank-delta position-delta newly-findable?
           improved? eval-gate-pass? keep?]}]
  (let [baseline-fragment (cond
                            newly-findable?
                            "not in baseline"

                            baseline-found?
                            (str "baseline index " baseline-index
                                 (when baseline-rank
                                   (str " (rank " (format-rank baseline-rank) ")")))

                            :else
                            "baseline-miss")
        enriched-fragment (if enriched-found?
                            (str "enriched index " enriched-index
                                 (when enriched-rank
                                   (str " (rank " (format-rank enriched-rank) ")")))
                            "enriched-miss")
        delta-fragment (cond
                         newly-findable?
                         "newly-findable ✓"

                         (and (number? position-delta) (pos? position-delta))
                         (str "moved up " position-delta " place(s)")

                         (and (number? position-delta) (zero? position-delta))
                         "no rank change"

                         (and (number? position-delta) (neg? position-delta))
                         (str "regressed " (Math/abs (long position-delta)) " place(s)")

                         :else
                         "no comparison")
        eval-fragment (cond
                        (true? eval-gate-pass?) "eval-pass ✓"
                        (false? eval-gate-pass?) "eval-fail ✗"
                        :else "eval-n/a")
        verdict (cond
                  (true? keep?) "→ keep"
                  (false? keep?) "→ revert"
                  :else "→ ?")]
    (str baseline-fragment " → " enriched-fragment
         " · " delta-fragment
         " · " eval-fragment
         " · improved? " improved?
         " " verdict)))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-verify-retrieval
  "Run baseline + enriched lookups, compute deltas, compose the keep? gate.

   Inputs:
     :chunk-id                   — the chunk we just enriched
     :enrichment-collection-name — where the new rows landed
     :docs-collection            — base docs collection (for filter scope)
     :chunks-collection          — base chunks collection (for baseline lookup)
     :user-query                 — the intent topic extracted from the user message
     :eval-summary               — optional, pass-through; if present we read its
                                   `:gate-pass` to build the composite gate

   Parameters:
     :enrichment-type — :hypothetical-questions | :verified-phrases | :fact-assertions
     :limit           — how many hits to fetch from Typesense (defaults to 20).

   Outputs are documented in `verify-retrieval-metadata` and the ns
   docstring."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id enrichment-collection-name docs-collection
                chunks-collection user-query eval-summary]} inputs
        {:keys [limit enrichment-type]} parameters
        tenant (:tenant skill-params)
        lookup-fn (lookup-fn-for enrichment-type)
        cant-verify-reason
        (cond
          (nil? lookup-fn) (str "no lookup wired for enrichment-type " enrichment-type)
          (or (nil? enrichment-collection-name) (empty? enrichment-collection-name))
          "missing :enrichment-collection-name"
          (or (nil? user-query) (and (string? user-query) (empty? user-query)))
          "missing :user-query (extract-user-intent emitted no topic)"
          (or (nil? chunk-id) (and (string? chunk-id) (empty? chunk-id)))
          "missing :chunk-id"
          ;; :chunks-collection is required for the D2.10 baseline call.
          ;; If absent we fall back to enriched-only verify (D2.8 shape).
          :else nil)
        eval-gate-pass? (when (map? eval-summary) (:gate-pass eval-summary))]
    (if cant-verify-reason
      (let [keep? (boolean eval-gate-pass?)
            out {:baseline-found? nil
                 :baseline-rank nil
                 :baseline-index nil
                 :enriched-found? false
                 :enriched-rank nil
                 :enriched-index nil
                 :rank-delta nil
                 :position-delta nil
                 :newly-findable? nil
                 :matched-enrichment nil
                 :improved? nil
                 :eval-gate-pass? eval-gate-pass?
                 :keep? keep?
                 :summary (str "verify-skip · " cant-verify-reason " · "
                               (if keep? "→ keep (eval-only)" "→ revert (eval-only)"))}]
        (skills/success-result out {:skipped? true
                                    :reason cant-verify-reason}))
      (let [opts (cond-> {:limit (or limit 20)}
                   tenant (assoc :tenant tenant))
            ;; Baseline: content search against the chunks collection.
            ;; Skipped if chunks-collection is nil — verify still runs,
            ;; just without a delta. `improved?` then degrades to
            ;; "found in the enriched lookup at all" (the D2.8 shape).
            baseline-hits (if (and chunks-collection (seq chunks-collection))
                            (rag/search-chunks-by-content
                             chunks-collection docs-collection
                             [user-query] nil opts)
                            nil)
            baseline-hit (when baseline-hits (find-hit baseline-hits chunk-id))
            baseline-found? (some? baseline-hit)
            baseline-rank (:rank baseline-hit)
            baseline-index (:index baseline-hit)
            ;; Enriched: lookup via the new enrichment collection.
            enriched-hits (lookup-fn enrichment-collection-name docs-collection
                                     [user-query] nil opts)
            enriched-hit (find-hit enriched-hits chunk-id)
            enriched-found? (some? enriched-hit)
            enriched-rank (:rank enriched-hit)
            enriched-index (:index enriched-hit)
            ;; Deltas. `rank-delta` is informational only (units differ
            ;; between text-match and hybrid scoring) — `position-delta`
            ;; is the comparable signal.
            rank-delta (when (and (number? baseline-rank) (number? enriched-rank))
                         (- (double enriched-rank) (double baseline-rank)))
            position-delta (when (and (number? baseline-index) (number? enriched-index))
                             (- (long baseline-index) (long enriched-index)))
            newly-findable? (and (some? baseline-hits)
                                 (not baseline-found?)
                                 enriched-found?)
            ;; When no baseline ran (chunks-collection missing) we
            ;; treat any enriched hit as improvement — same as D2.8.
            improved? (cond
                        (nil? baseline-hits) enriched-found?
                        newly-findable? true
                        (and baseline-found? enriched-found?
                             (number? position-delta) (pos? position-delta)) true
                        :else false)
            keep? (boolean (and improved? eval-gate-pass?))
            base-out {:baseline-found? (when (some? baseline-hits) baseline-found?)
                      :baseline-rank baseline-rank
                      :baseline-index baseline-index
                      :enriched-found? enriched-found?
                      :enriched-rank enriched-rank
                      :enriched-index enriched-index
                      :rank-delta rank-delta
                      :position-delta position-delta
                      :newly-findable? newly-findable?
                      :matched-enrichment (matched-enrichment-of enriched-hit)
                      :improved? improved?
                      :eval-gate-pass? eval-gate-pass?
                      :keep? keep?}
            out (assoc base-out :summary (summarise base-out))]
        (skills/success-result
         out
         {:tenant tenant
          :enrichment-type enrichment-type
          :baseline-skipped? (nil? baseline-hits)
          :baseline-hits (count (or baseline-hits []))
          :enriched-hits (count enriched-hits)})))))

;; =============================================================================
;; Registration
;; =============================================================================

(def verify-retrieval-skill
  {:metadata verify-retrieval-metadata
   :execute execute-verify-retrieval})

(defn register!
  "Register the verify-retrieval skill. Idempotent."
  []
  (skills/register-skill! verify-retrieval-skill))

(register!)
