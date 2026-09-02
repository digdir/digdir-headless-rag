(ns digdir.skills.enrichment.compose-report
  "`:builtin/enrichment-compose-report` — deterministic terminal node
   for the self-improve graph.

   Walks the outer graph's `{:analysis :outcomes}` workspace and emits
   a Markdown summary plus a structured stats map. The Markdown becomes
   the assistant turn in the playground chat; the structured map gives
   the observability drawer a hook for stat-chip rendering.

   This skill exists to dissolve one of the two LLM-non-determinism
   failure modes the ReAct version of the self-improve agent surfaced:
   the model emitting 'I can't actually run these tools' after running
   them successfully. The graph version has no LLM-authored final turn
   — the final response is this skill's deterministic walk of the
   workspace.

   Lives in `src-dev/` alongside its siblings — offline tooling, not
   part of the runtime retrieval path."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def compose-report-metadata
  {:skill-id :builtin/enrichment-compose-report
   :name "Compose self-improve run report"
   :description "Walk {:analysis :outcomes} from a self-improve graph run and emit a Markdown summary plus structured stats. Pure function — no side effects, no LLM calls."
   :category :orchestration
   ;; :analysis is optional (the report degrades to a 'no analysis
   ;; available' placeholder if missing). :outcomes is the meaningful
   ;; input; without it there's nothing to summarise.
   :inputs [:outcomes]
   :outputs [:report :report-structured]
   :parameters {}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :graph-only}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- outcome-decision
  "Classify an outcome map as `:keep`, `:revert`, or `:error`. Falls
   back to `:unknown` so a missing/typo'd `:decision` doesn't crash
   the report — we surface it as a separate bucket instead."
  [outcome]
  (cond
    (:error outcome) :error
    (= :keep (:decision outcome)) :keep
    (= :revert (:decision outcome)) :revert
    :else :unknown))

(defn- partition-outcomes
  "Bucket the outcome vec by decision. Returns a map
   `{:keep [...] :revert [...] :error [...] :unknown [...]}`."
  [outcomes]
  (let [grouped (group-by outcome-decision outcomes)]
    {:keep    (vec (:keep grouped))
     :revert  (vec (:revert grouped))
     :error   (vec (:error grouped))
     :unknown (vec (:unknown grouped))}))

(defn- format-gate-summary
  "One-line gate verdict + counter pair for a kept/reverted chunk's eval
   summary. Returns nil if no summary present, so callers can drop the
   line cleanly."
  [eval-summary]
  (when (map? eval-summary)
    (let [pass? (:gate-pass eval-summary)
          current (or (:current-pass eval-summary) 0)
          relaxed (or (:relaxed-pass eval-summary) 0)
          cases (or (:cases eval-summary) 0)]
      (str (if pass? "gate-pass ✓" "gate-fail ✗")
           ", current " current "/" cases
           ", relaxed " relaxed "/" cases))))

(defn- format-rank
  [r]
  (when (number? r) (format "%.3f" (double r))))

(defn- format-verify-summary
  "One-line summary of the verify step's retrieval delta. Returns nil
   when no verify map is attached (e.g. older traces from before D2.8)."
  [verify]
  (when (map? verify)
    (let [{:keys [baseline-found? baseline-index baseline-rank
                  enriched-found? enriched-index enriched-rank
                  position-delta newly-findable? improved?]} verify]
      (cond
        ;; D2.8 fallback / pre-D2.10 outputs without a baseline.
        (and (nil? baseline-found?) (false? enriched-found?))
        "verify-skip"

        (and (nil? baseline-found?) enriched-found?)
        (str "enriched found at index " enriched-index
             (when enriched-rank (str " (rank " (format-rank enriched-rank) ")"))
             " (no baseline)")

        (true? newly-findable?)
        (str "newly findable ✓ — enriched index " enriched-index
             (when enriched-rank (str ", rank " (format-rank enriched-rank))))

        (and (true? baseline-found?) (true? enriched-found?)
             (number? position-delta) (pos? position-delta))
        (str "moved up " position-delta " place(s): index "
             baseline-index " → " enriched-index
             (when (and baseline-rank enriched-rank)
               (str " (rank " (format-rank baseline-rank)
                    " → " (format-rank enriched-rank) ")")))

        (and (true? baseline-found?) (true? enriched-found?)
             (number? position-delta) (neg? position-delta))
        (str "regressed " (Math/abs (long position-delta))
             " place(s): index " baseline-index " → " enriched-index)

        (and (true? baseline-found?) (false? enriched-found?))
        "baseline had it, enriched missed ✗"

        (false? improved?)
        "no improvement"

        :else "verify n/a"))))

(defn- proposal-items
  "Pull the per-enrichment-type items out of a propose-step output map.
   Returns `[label, vec-of-rendered-strings]` so the formatter can write
   `Items applied:` / `Phrases applied:` / `Facts applied:` consistently.
   Returns nil when the proposal carries no recognisable items."
  [proposal]
  (cond
    (seq (:questions proposal))
    ["Questions" (mapv str (:questions proposal))]

    (seq (:phrases proposal))
    ["Phrases" (mapv str (:phrases proposal))]

    (seq (:facts proposal))
    ["Facts"
     (mapv (fn [{:keys [subject predicate object]}]
             (str (or subject "?") " | "
                  (or predicate "?") " | "
                  (or object "?")))
           (:facts proposal))]

    :else nil))

(defn- format-context-details
  "Render the fetch step's :context map as a collapsible <details>
   block — chunk content + a 'from <doc-title>' attribution line.
   Returns nil when no context is attached so callers can drop the
   line cleanly. D2.16.

   The base playground UI renders this through commonmark, which
   preserves raw HTML (<details>/<summary>) verbatim so the browser
   provides the expand/collapse affordance for free."
  [context]
  (when (map? context)
    (let [{:keys [chunk-content doc-title doc-url]} context
          attribution (cond
                        (and (seq doc-title) (seq doc-url))
                        (str "_from [" doc-title "](" doc-url ")_")
                        (seq doc-title) (str "_from " doc-title "_")
                        (seq doc-url) (str "_from " doc-url "_")
                        :else nil)]
      (when (and (string? chunk-content) (seq chunk-content))
        (str "\n  <details>\n"
             "  <summary>Chunk content</summary>\n\n"
             (when attribution (str "  " attribution "\n\n"))
             "  > " (str/replace chunk-content #"\n" "\n  > ") "\n"
             "  </details>")))))

(defn- format-matched-enrichment
  "Render the verify step's :matched-enrichment as a single line ready
   for inclusion under the kept-block. Returns nil for missing/unknown
   shapes."
  [matched]
  (cond
    (nil? matched) nil
    (:question matched) (str "    - " (:question matched))
    (:phrase matched)   (str "    - " (:phrase matched))
    (and (:subject matched) (:predicate matched) (:object matched))
    (str "    - " (:subject matched) " | "
         (:predicate matched) " | "
         (:object matched))
    :else nil))

(defn- format-kept-block
  [{:keys [chunk-id eval proposal verify context] :as _outcome}]
  (let [gate-line (format-gate-summary eval)
        verify-line (format-verify-summary verify)
        [items-label items] (proposal-items proposal)
        matched-line (format-matched-enrichment (:matched-enrichment verify))
        details (format-context-details context)
        sb (StringBuilder.)]
    (.append sb (str "- `" chunk-id "`"))
    (when gate-line (.append sb (str " — " gate-line)))
    (when verify-line (.append sb (str " · " verify-line)))
    (when (and items-label (seq items))
      (.append sb (str "\n  " items-label " applied (" (count items) "):\n"))
      (.append sb (->> items
                       (map #(str "    - " %))
                       (str/join "\n"))))
    (when matched-line
      (.append sb (str "\n  Matched on intent:\n" matched-line)))
    (when details (.append sb details))
    (.toString sb)))

(defn- revert-reason
  "Translate the verify+eval signals into a plain-English sentence that
   explains WHY this chunk was reverted. D2.15.

   The cases, in priority order:

   - eval gate failed → regression risk dominates anything else, name it.
   - baseline-found + enriched-miss → the propose surface form wasn't
     reachable by the new enrichment lookup at all for this query,
     even though content-search already finds the chunk.
   - baseline-found + enriched-found + negative position-delta → the
     enrichment competes with content-search and pushed the chunk
     down the ranking.
   - no-baseline + no-enriched → neither path found the chunk for the
     user's query; analyze-corpus may have picked a weak topical match.
   - all other paths → generic 'verify didn't see improvement'.

   Returns nil when neither verify nor eval are present (older
   outcomes pre-D2.10)."
  [{:keys [eval verify] :as _outcome}]
  (let [eval-gate-pass? (when (map? eval) (:gate-pass eval))
        {:keys [baseline-found? baseline-index
                enriched-found? enriched-index position-delta]} verify]
    (cond
      (false? eval-gate-pass?)
      (str "Eval suite regressed when this enrichment was applied "
           "(current " (or (:current-pass eval) "?")
           "/" (or (:cases eval) "?")
           ", relaxed " (or (:relaxed-pass eval) "?")
           "/" (or (:cases eval) "?")
           "). Reverted to protect the benchmark.")

      (and (true? baseline-found?) (false? enriched-found?))
      (str "Content search already finds this chunk at position "
           (or baseline-index "?")
           " for the user query, but the new enrichment lookup didn't "
           "surface it at all. The proposed surface forms are probably "
           "topically adjacent but not the chunk's distinctive vocabulary.")

      (and (true? baseline-found?) (true? enriched-found?)
           (number? position-delta) (neg? position-delta))
      (str "Adding the enrichment pushed the chunk DOWN in ranking — "
           "from position " (or baseline-index "?")
           " (baseline content search) to " (or enriched-index "?")
           " (with enrichment). The new surface forms compete with the "
           "chunk's own content for this query.")

      (and (false? baseline-found?) (false? enriched-found?))
      (str "Neither baseline content search nor the new enrichment "
           "lookup surfaced this chunk for the user query. "
           "Analyze-corpus may have picked a weakly relevant chunk; "
           "consider rephrasing the intent.")

      (when (map? verify)
        (false? (:improved? verify)))
      "Verify saw no measurable improvement in retrieval for the user's intent."

      :else nil)))

(defn- format-reverted-block
  [{:keys [chunk-id eval proposal verify context reverted-count] :as outcome}]
  (let [gate-line (format-gate-summary eval)
        verify-line (format-verify-summary verify)
        [items-label items] (proposal-items proposal)
        matched-line (format-matched-enrichment (:matched-enrichment verify))
        reason (revert-reason outcome)
        details (format-context-details context)
        sb (StringBuilder.)]
    (.append sb (str "- `" chunk-id "`"))
    (when gate-line (.append sb (str " — " gate-line)))
    (when verify-line (.append sb (str " · " verify-line)))
    (when (number? reverted-count)
      (.append sb (str " — " reverted-count " row(s) removed")))
    ;; D2.15 — Plain-English reason BEFORE the data dump, so a reader
    ;; gets the verdict without parsing the verify signal numbers.
    (when reason
      (.append sb (str "\n  _Why reverted:_ " reason)))
    ;; D2.13 — Reverted blocks now carry the same proposal+matched
    ;; content as kept blocks, so the user can see what was tried
    ;; and judge for themselves whether verify's revert was right.
    (when (and items-label (seq items))
      (.append sb (str "\n  " items-label " tried (" (count items) "):\n"))
      (.append sb (->> items
                       (map #(str "    - " %))
                       (str/join "\n"))))
    (when matched-line
      (.append sb (str "\n  Closest enrichment match for intent:\n" matched-line)))
    ;; D2.16 — chunk source text in a <details> block so the user can
    ;; verify whether the propose was a reasonable read of the source.
    (when details (.append sb details))
    (.toString sb)))

(defn- format-error-block
  [{:keys [chunk-id error] :as _outcome}]
  (str "- `" (or chunk-id "<unknown>") "` — Error: " (or error "<no message>")))

(defn- format-unknown-block
  [{:keys [chunk-id decision] :as _outcome}]
  (str "- `" (or chunk-id "<unknown>") "` — decision="
       (pr-str decision)
       " (no recognised verdict — likely a wiring bug)"))

;; =============================================================================
;; Markdown composition
;; =============================================================================

(defn compose-markdown
  "Pure function: builds the Markdown report string from `analysis` (a
   map like `{:chunk-ids [...] :analysis '...'}`, the output of
   `:builtin/enrichment-analyze-corpus`) and `outcomes` (vec of chunk
   outcome maps from the sub-graph's foreach).

   Designed to read well in the playground chat. Sections are omitted
   rather than emitted-empty when their corresponding bucket has no
   entries."
  [analysis outcomes]
  (let [buckets (partition-outcomes outcomes)
        total (count outcomes)
        kept (count (:keep buckets))
        reverted (count (:revert buckets))
        errored (count (:error buckets))
        unknown (count (:unknown buckets))
        analysis-prose (when (map? analysis) (:analysis analysis))
        sb (StringBuilder.)]
    (.append sb "# Self-improvement run\n\n")

    (.append sb "## Corpus analysis\n\n")
    (.append sb (if (and (string? analysis-prose) (seq analysis-prose))
                  analysis-prose
                  "_No corpus analysis available._"))
    (.append sb "\n\n")

    (.append sb (str "## Per-chunk outcomes (" total " chunk(s) tried)\n\n"))
    (if (zero? total)
      (.append sb "_No chunks selected for enrichment._\n")
      (do
        (.append sb (str "- Kept: " kept "\n"))
        (.append sb (str "- Reverted: " reverted "\n"))
        (when (pos? errored)
          (.append sb (str "- Errored: " errored "\n")))
        (when (pos? unknown)
          (.append sb (str "- Unknown verdict: " unknown "\n")))
        (.append sb "\n")

        (when (pos? kept)
          (.append sb "### Chunks kept\n\n")
          (doseq [o (:keep buckets)]
            (.append sb (format-kept-block o))
            (.append sb "\n"))
          (.append sb "\n"))

        (when (pos? reverted)
          (.append sb "### Chunks reverted\n\n")
          (doseq [o (:revert buckets)]
            (.append sb (format-reverted-block o))
            (.append sb "\n"))
          (.append sb "\n"))

        (when (pos? errored)
          (.append sb "### Chunks with errors\n\n")
          (doseq [o (:error buckets)]
            (.append sb (format-error-block o))
            (.append sb "\n"))
          (.append sb "\n"))

        (when (pos? unknown)
          (.append sb "### Chunks with unrecognised verdict\n\n")
          (doseq [o (:unknown buckets)]
            (.append sb (format-unknown-block o))
            (.append sb "\n"))
          (.append sb "\n"))))
    (str/trim-newline (.toString sb))))

(defn- verify-stats
  "Aggregate the verify-step signals across all kept+reverted outcomes
   so the playground side drawer can chart 'how often did the
   enrichment help?' without re-walking the per-chunk list.

   Returns nil when no outcome carries a verify map — i.e. pre-D2.8
   traces — so the structured-stats shape stays backwards-compatible."
  [outcomes]
  (let [vs (->> outcomes (keep :verify) vec)]
    (when (seq vs)
      (let [position-deltas (->> vs (keep :position-delta) (filter number?) vec)
            avg-position-delta (when (seq position-deltas)
                                 (/ (double (reduce + position-deltas))
                                    (count position-deltas)))]
        {:verify-considered (count vs)
         :newly-findable-count (count (filter :newly-findable? vs))
         :improved-count (count (filter :improved? vs))
         :regressed-count (count (filter (fn [{:keys [position-delta]}]
                                           (and (number? position-delta)
                                                (neg? position-delta)))
                                         vs))
         :avg-position-delta avg-position-delta}))))

(defn structured-stats
  "Pure function: derive the `:report-structured` map from `outcomes`.
   The playground drawer can render this directly into stat chips
   without re-parsing the Markdown."
  [outcomes]
  (let [buckets (partition-outcomes outcomes)
        ids-from (fn [bucket] (->> bucket (map :chunk-id) (remove nil?) vec))]
    (cond-> {:total (count outcomes)
             :kept-count (count (:keep buckets))
             :reverted-count (count (:revert buckets))
             :errored-count (count (:error buckets))
             :unknown-count (count (:unknown buckets))
             :kept-chunk-ids (ids-from (:keep buckets))
             :reverted-chunk-ids (ids-from (:revert buckets))
             :errored-chunk-ids (ids-from (:error buckets))
             :unknown-chunk-ids (ids-from (:unknown buckets))}
      (verify-stats outcomes) (assoc :verify (verify-stats outcomes)))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-compose-report
  "Emit `:report` (Markdown string) and `:report-structured` (stats map)
   from the workspace state collected by the outer graph."
  [{:keys [inputs]}]
  (let [{:keys [analysis outcomes]} inputs
        outcomes-vec (vec (or outcomes []))]
    (skills/success-result
     {:report (compose-markdown analysis outcomes-vec)
      :report-structured (structured-stats outcomes-vec)}
     {})))

;; =============================================================================
;; Registration
;; =============================================================================

(def compose-report-skill
  {:metadata compose-report-metadata
   :execute execute-compose-report})

(defn register!
  "Register the compose-report skill. Idempotent."
  []
  (skills/register-skill! compose-report-skill))

(register!)
