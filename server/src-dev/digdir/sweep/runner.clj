(ns digdir.sweep.runner
  "Phase S1 — sweep runner.

   Drives a cartesian product of {sweep-config × question × repeat}
   through `digdir.sweep.invoke/invoke-with-clarification-loop`,
   scores each run inline, and writes one row per run to a CSV under
   `server/results/sweep-<timestamp>/runs.csv`.

   Design choices:

   - Scoring is computed at runtime *and* the raw chunk-ids + response
     are persisted in the CSV, so the same runs can be re-scored later
     by `digdir.sweep.report` if the rubric evolves without re-running
     the (expensive) agent calls.

   - The runner accepts an EDN matrix file with one map per config to
     sweep. Each row in the matrix becomes a `:config-id` in the CSV.
     The matrix's per-config keys are merged into the invoke-rag call
     — currently `:skill-graph-id`, `:model`, `:temperature`, and a
     `:skill-params` overrides map. Runtime-config knobs (rerank top-k
     etc.) will be handled by a future config-DB-patching mode; for v1
     we sweep what's reachable from the invoke-rag arg map.

   - Collections + execution-scope are resolved once per
     (tenant, dataset-config-key) pair via the `/api/debug/dataset-config`
     equivalent (`digdir.config.db/get-dataset-by-ref`), then reused
     across every question. The runner never hits the HTTP layer."
  (:require [clojure.set :as set]
            [digdir.eval.run-validity :as run-validity]
            [digdir.eval.answer-language :as answer-language]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digdir.agents.db :as agents-db]
            [digdir.api.util :as api-util]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as cfg-core]
            [digdir.config.db :as cfg-db]
            [digdir.rag.typesense :as ts]
            [digdir.sweep.invoke :as sweep-invoke]
            [digdir.sweep.judge :as judge]
            [digdir.sweep.questions :as questions]
            [digdir.sweep.user-simulator :as sim]
            [taoensso.telemere :as t]))

;; =============================================================================
;; Chunk-id extraction
;; =============================================================================

(def ^:private chunk-id-keys
  "Keys we'll try, in order, when pulling the chunk id from a result's
   :chunks entry. Different code paths label this field differently;
   defending against all of them keeps the runner stable across
   skill-graph variants."
  [:chunk-id :chunk_id :id :uid :external-id])

(defn- chunk->id
  "Best-effort extraction of a stable chunk-id from a retrieval result
   chunk. Returns nil if none of the known keys carry a string value."
  [chunk]
  (some #(let [v (get chunk %)] (when (string? v) v)) chunk-id-keys))

(defn retrieved-chunk-ids
  "Ordered, deduplicated chunk-ids from the result.

   For the agent-rag graph variants the canonical retrieval list lives
   at `[:diagnostics :outputs :workspace-final :reranked-chunks]` — a
   rank-ordered vector of chunk maps each carrying `:chunk_id`. We
   read from there first because the rank order is what `recall@k` is
   measured against. Falls back to top-level `:chunks` for callers
   that populate it directly (legacy paths), and finally to the
   workspace's `:chunks` array-map (where keys are chunk-ids) — used
   in some sub-skill outputs.

   `result` is the full invoke-rag return map."
  [result]
  (let [reranked (get-in result [:diagnostics :outputs :workspace-final :reranked-chunks])
        top-chunks (:chunks result)
        ws-chunks-map (get-in result [:diagnostics :outputs :workspace-final :chunks])]
    (->> (cond
           (seq reranked) reranked
           (seq top-chunks) top-chunks
           ;; workspace :chunks is {chunk-id → chunk-data}; keys are ids.
           (map? ws-chunks-map) (map (fn [[k _]] {:chunk_id k}) ws-chunks-map)
           :else [])
         (keep chunk->id)
         distinct
         vec)))

(defn cited-chunk-ids
  "Chunks the synthesis actually cited, in citation-index order. A
   stricter analogue of `retrieved-chunk-ids`: a config that retrieves
   the golden chunk but never cites it shows up in retrieval recall
   but not in citation recall."
  [result]
  (let [citations (get-in result [:diagnostics :outputs :workspace-final :citations])]
    (->> citations
         (sort-by :index)
         (keep :chunk-id)
         distinct
         vec)))

(def ^:private default-display-window
  "The number of chunks the agent's `search_documents` tool result shows
   to the LLM by default (see `default-search-result-display-limit` in
   agent/tools.clj). Used to compute `:golden-in-display?` — chunks past
   this window are present in the workspace's search-history but never
   surfaced to the LLM in the tool-result text."
  20)

(defn search-pool-chunk-ids
  "All chunk-ids ever returned by any `search_documents` tool call in
   this run, deduped while preserving first-seen order. Reflects the
   total upstream candidate pool the agent could have read from."
  [result]
  (let [history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))]
    (->> history
         (mapcat :chunk-ids)
         distinct
         vec)))

(defn search-display-chunk-ids
  "Chunk-ids that were visible to the LLM in any search_documents tool
   result — i.e., the union of the top-`display-window` chunks across
   every search call's response.

   Distinct from `search-pool-chunk-ids` (which is everything that came
   back from retrieval) because the agent's tool-result rendering caps
   the list at `display-window` lines. Used to compute `:golden-in-display?`."
  [result display-window]
  (let [history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))]
    (->> history
         (mapcat (fn [entry] (take display-window (:chunk-ids entry))))
         distinct
         vec)))

(defn golden-display-rank
  "1-based rank of the best-ranked golden within the displayed top-`display-window`
   of any search call — the minimum position the golden (any of them, for
   multi-golden) ever reached across search passes. nil when no golden was
   displayed. This is the missing signal for diagnosing rank-gated read levers:
   a golden displayed at rank 12 explains why auto-read-top-3 never reads it."
  [result expected display-window]
  (let [expected-set (set expected)
        history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))
        ranks (for [entry history
                    :let [displayed (vec (take display-window (:chunk-ids entry)))]
                    [idx cid] (map-indexed vector displayed)
                    :when (expected-set cid)]
                (inc idx))]
    (when (seq ranks) (apply min ranks))))

(defn golden-pool-rank
  "1-based best rank of the golden in the FULL retrieved pool — the complete
   `:chunk-ids` of each search pass (up to retrieve-top-k), across passes — not
   capped at the display window. nil when no golden was ever retrieved.

   Complements `golden-display-rank` (≤ display-window) to split the pool-miss
   failure mode: pool-rank=nil → a TRUE retrieval blind spot (golden never in any
   query's top-k → vocab/enrichment territory); pool-rank=85 but display-rank=nil
   → retrieved-but-BURIED (a rank/merge problem the display cap hides). Same
   diagnostic value for the pool stage that golden-display-rank gave the display
   stage."
  [result expected]
  (let [expected-set (set expected)
        history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))
        ranks (for [entry history
                    [idx cid] (map-indexed vector (:chunk-ids entry))
                    :when (expected-set cid)]
                (inc idx))]
    (when (seq ranks) (apply min ranks))))

(defn read-chunk-ids
  "Chunk-ids the agent actually read via the read_chunks tool, in the
   order they entered the workspace. Counts both single-chunk-id reads
   and chunks fetched via doc_num + chunk_range expansion."
  [result]
  (let [history (or (get-in result [:diagnostics :outputs :workspace-final :read-history])
                    (get-in result [:diagnostics :read-history]))]
    (->> history
         (mapcat (fn [entry]
                   (concat (:returned-chunk-ids entry)
                           (:chunk-ids entry))))
         (remove nil?)
         distinct
         vec)))

(defn issued-queries
  "The search query strings the agent actually issued, across all passes, in
   order. Each search-history entry's :queries holds that pass's (multi-)query
   list. The missing signal for diagnosing rank variance: when a golden ranks 1
   on one run and 42 on another, this shows WHICH query phrasing buried it —
   you cannot tune a reranker against a min-across-queries rank without it."
  [result]
  (let [history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))]
    (->> history
         (mapcat (fn [e] (let [q (:queries e)]
                           (cond (sequential? q) q
                                 (some? q)       [q]
                                 (:query e)      [(:query e)]
                                 :else           nil))))
         (remove nil?)
         vec)))

;; ---------------------------------------------------------------------------
;; Pool-head characterization — WHAT outranks the golden, not just where it
;; landed. The missing signal for designing SUBTRACTIVE levers: every additive
;; ("boost") strategy failed by displacing good chunks; to instead penalize the
;; noise we must first see its composition (near-dup twins, generic overviews,
;; long diluting siblings, multi-strategy winners). Built entirely from the
;; `:chunk-summaries` the workspace already persists per search pass.
;; ---------------------------------------------------------------------------

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- n1 [x] (when (number? x) (-> x double (* 10) Math/round (/ 10.0))))

(defn- fmt-chunk-summary
  "Compact one search :chunk-summary into a single descriptor carrying the
   features a subtractive penalty might key on. Crucially includes the ranking-
   prior decomposition — P=composite prior, h=hit-count (promiscuity reward: how
   many of the run's queries returned this chunk), or=original-rank (base
   relevance) — plus the per-feature boost magnitudes b[ti,yr,or,st,co,nu]. That
   is what reveals WHICH term lifts the noise above the golden, i.e. exactly what
   to subtract. Also length, doc-position, strategies, doc-num, clipped title."
  [cs]
  (let [types (some->> (:search-types cs) seq (map name) sort (str/join "+"))
        b (:retrieval-boosts cs)
        bvec (when b (str/join "," (map #(or (n1 (get b %)) 0)
                                        [:title :year :org :search-type :content-overlap :numeric-evidence])))]
    (str "d" (or (:doc-num cs) "?")
         " R" (if (:rerank-score cs) (n1 (:rerank-score cs)) "—")
         " P" (or (n1 (:retrieval-prior cs)) "?")
         " h" (or (:hit-count cs) "?")
         " or" (or (n1 (:original-rank cs)) "?")
         " L" (or (:content-length cs) "?")
         " " (or (:chunk-index cs) "?") "/" (or (:total-chunks cs) "?")
         (when types (str " ty:" types))
         (when bvec (str " b[" bvec "]"))
         (when (:matched-questions cs) " +q")
         (when-let [t (:title cs)] (str " | " (clip t 40))))))

(defn- golden-best-showing
  "The {:entry :pos :rank} for the search pass where any golden achieved its
   minimum (best) rank, or nil if no golden was ever retrieved. Shared basis for
   both `golden-pool-head` and `golden-self-features` so they describe the SAME
   pass — the golden's strongest appearance and exactly what still beat it there."
  [result expected]
  (let [expected-set (set expected)
        history (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
                    (get-in result [:diagnostics :search-history]))]
    (->> (for [entry history
               [idx cid] (map-indexed vector (:chunk-ids entry))
               :when (expected-set cid)]
           {:entry entry :pos idx :rank (inc idx)})
         (sort-by :rank)
         first)))

(defn golden-pool-head
  "The chunk-summaries (up to `n`) ranked ABOVE the golden in its best-showing
   pass — i.e. WHAT is burying it. nil when the golden was never retrieved (a
   vocab blind spot — a different failure mode than displacement)."
  [result expected n]
  (when-let [{:keys [entry pos]} (golden-best-showing result expected)]
    (->> (:chunk-summaries entry)
         (take (min n pos))
         (mapv fmt-chunk-summary))))

(defn golden-self-features
  "The golden's OWN descriptor in its best-showing pass — so the pool-head can be
   read comparatively (is the golden the SHORT/specific chunk crowded out by long
   siblings, or itself a long/generic overview?)."
  [result expected]
  (let [expected-set (set expected)]
    (when-let [{:keys [entry pos]} (golden-best-showing result expected)]
      (let [summaries (:chunk-summaries entry)
            cs (or (get summaries pos)
                   (first (filter #(expected-set (:chunk-id %)) summaries)))]
        (some-> cs fmt-chunk-summary)))))

;; ---------------------------------------------------------------------------
;; Agent-path behavior signals (what the agent DID, not just where the golden
;; landed). These slice the insufficiency/refinement loop so it can be verified
;; at scale — the layer every retrieval-only harness is blind to.
;; ---------------------------------------------------------------------------

(defn search-pass-count
  "Number of search_documents passes the agent ran. >1 means it re-searched —
   typically after the sufficiency gate flagged the evidence insufficient and
   emitted research guidance."
  [result]
  (count (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
             (get-in result [:diagnostics :search-history]))))

(defn insufficiency-fired?
  "Did the agent judge its evidence insufficient at any point — the trigger for
   the re-search / refinement path? True when any sufficiency-gate decision has
   a `:status` other than `:sufficient`, or the explicit-insufficient flag is set."
  [result]
  (let [wf (get-in result [:diagnostics :outputs :workspace-final])]
    (boolean
     (or (:last-generated-explicitly-insufficient? wf)
         (some (fn [d] (let [s (:status d)] (and s (not= :sufficient s))))
               (:sufficiency-decisions wf))))))

(defn enrichment-hit-count
  "Total enrichment-collection hits merged into retrieval across all search
   passes (0 when enrichment is inactive or matched nothing). Sums every pass's
   `[:attribution :enrichment-hits-by-type]` value map."
  [result]
  (->> (or (get-in result [:diagnostics :outputs :workspace-final :search-history])
           (get-in result [:diagnostics :search-history]))
       (mapcat (fn [e] (vals (or (get-in e [:attribution :enrichment-hits-by-type]) {}))))
       (reduce + 0)))

(defn- norm-query [s] (-> (or s "") str/lower-case str/trim))

(defn refinement-corpus-grounded?
  "The 'A' signal. When the agent RE-SEARCHED (>1 pass), did the later pass(es)
   use the planner's corpus-grounded phrases rather than collapsing to generic
   deterministic variants? Compares pass-2+ queries against the planner's
   `:last-planner-phrases` (normalized). Returns nil when there was no re-search
   (N/A — nothing to refine); the empty CSV cell then distinguishes
   \"no refinement happened\" from \"refinement was generic\" (false)."
  [result]
  (let [wf (get-in result [:diagnostics :outputs :workspace-final])
        history (or (:search-history wf) [])]
    (when (> (count history) 1)
      (let [planner (set (map norm-query (:last-planner-phrases wf)))
            later (mapcat :queries (rest history))]
        (boolean (some planner (map norm-query later)))))))

;; =============================================================================
;; Scoring
;; =============================================================================

(defn- recall-at-k
  "Fraction of `expected` ids appearing in the first `k` of `retrieved`.
   Returns nil if `expected` is empty (the row has no chunks to score
   against, e.g. :answer-only grounding)."
  [expected retrieved k]
  (when (seq expected)
    (let [head (set (take k retrieved))
          hits (count (filter head expected))]
      (/ (double hits) (count expected)))))

(defn- answer-substring-hit?
  "True if the response matches the row's :expected-answer-pattern."
  [pattern response]
  (boolean
    (when (and (string? pattern) (string? response) (seq response))
      (re-find (re-pattern pattern) response))))

(defn- find-stage-timings
  "The agent surfaces stage-timings in a couple of places depending on
   skill-graph topology — directly on the diagnostics meta, or nested
   under :outputs :trace. Return whichever has entries, else nil."
  [result]
  (or (not-empty (get-in result [:diagnostics :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :trace :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :workspace-final :stage-timings]))
      (not-empty (get-in result [:trace :stage-timings]))
      (not-empty (:stage-timings result))))

(def ^:private io-stage-groups
  "Stages whose wall-clock is I/O the model cannot change: the Typesense
   query, the reranker call, and chunk-fetch tool dispatch.

   This set is CLOSED on purpose. Anything not in it is classified by an
   INTRINSIC property — whether the entry reported token :usage — so a new
   LLM stage buckets itself correctly without anyone updating a list. A new
   entry that is neither lands in :other-ms, which is REPORTED rather than
   silently folded into one side. That matters because this measurement
   answers whether the interactive target is reachable AT ALL: quietly
   understating the I/O floor would wrongly say yes, and quietly
   overstating it would wrongly say no."
  ;; Grouped so the per-column breakdown and the io? predicate CANNOT
  ;; disagree: the predicate is the union of these groups, derived, not a
  ;; second list to keep in sync.
  {:search #{:search}
   :rerank #{:rerank_results :rerank}
   :tool   #{:read_chunks :inspect_filters :tool}})

(def ^:private io-stage?
  "Union of io-stage-groups."
  (into #{} cat (vals io-stage-groups)))

;; Two traps live in this set and both cost me a wrong answer once.
;;
;; 1. THE STAGE KEYWORDS ARE MIXED kebab- AND snake_case. `tool-stage-info`
;;    emits :read_chunks, :rerank_results, :plan_queries, :generate_response
;;    while the loop emits :agent-llm, :sufficiency-gate. I enumerated them
;;    with a regex whose character class was [a-z-]+, which SILENTLY DROPPED
;;    every snake_case stage, and I very nearly shipped an I/O set missing
;;    :read_chunks and :rerank_results.
;;
;; 2. :plan_queries IS NOT I/O. It is the query planner and it calls an LLM,
;;    inside what everyone calls "the retrieval stage". That is the concrete
;;    reason "everything before synthesis" is NOT the same quantity as
;;    "model-independent" - the pre-synthesis window contains LLM calls.

(defn stage-duration-totals
  "Wall-clock per stage class for one run, off the same stage-timings
   `agent-token-totals` already reads.

   :io-ms is the MODEL-INDEPENDENT floor — it does not shrink if you swap
   the synthesiser. :llm-ms does. Note the two do not answer the same
   question: a chattier model issues more searches, so :io-ms per RUN still
   moves with turn count even though the per-CALL cost does not. Divide by
   the call counts for the model-independent primitive.

   Returns nil when no stage-timings are present, so the CSV renders empty
   cells rather than zeros — `no data` must stay distinguishable from
   `summed to zero`."
  [result]
  (when-let [timings (find-stage-timings result)]
    (let [dur     #(or (:duration-ms %) 0)
          io?     #(contains? io-stage? (:stage %))
          llm?    #(some? (:usage %))
          sum-of  (fn [pred] (reduce + 0 (map dur (filter pred timings))))
          by-group (fn [group]
                     (let [members (get io-stage-groups group)]
                       (reduce + 0 (map dur (filter #(contains? members (:stage %)) timings)))))]
      {:io-search-ms (by-group :search)
       :io-rerank-ms (by-group :rerank)
       :io-tool-ms   (by-group :tool)
       :io-ms        (sum-of io?)
       :llm-ms       (sum-of #(and (not (io? %)) (llm? %)))
       :other-ms     (sum-of #(and (not (io? %)) (not (llm? %))))
       :io-call-count  (count (filter io? timings))
       :llm-call-count (count (filter #(and (not (io? %)) (llm? %)) timings))
       ;; #25 SENTINEL. A stage that declares :usage-expected? and has no
       ;; :usage means the ambient collector did not reach it — the binding was
       ;; absent, or was not conveyed across a thread. Non-zero here means THIS
       ;; ROW'S DECOMPOSITION IS UNRELIABLE, and it says so in the artifact
       ;; rather than in a log nobody reads months later. Absence has to be a
       ;; value; a nil that renders like a zero is how io-rerank-ms lied.
       ;; A stage that made NO LLM call cannot be "missing" usage. Some stages
       ;; SHORT-CIRCUIT: generate_response returns read-guidance without calling
       ;; synthesis when the workspace has no chunks, and plan_queries has no
       ;; such branch — which is exactly why one looked broken and the other
       ;; did not. Whether a stage calls an LLM is a PER-FIRING property, so an
       ;; unconditional stage-level claim over-reports.
       ;; `record-usage!` conjes `(:usage resp)` on every completion INCLUDING
       ;; nil, so :usage-writes counts CALLS, not usages, and separates the two:
       ;;   writes 0            -> no call was made; the claim does not apply
       ;;   writes >0, no usage -> a call was made and its usage was LOST
       ;; A stage with no :usage-writes key at all was never wrapped in
       ;; capture-usage (:agent-llm, :agent-final-llm), so it defaults to 1 —
       ;; still caught, which is how the ~2% transient stayed visible.
       :llm-stages-missing-usage (count (filter #(and (:usage-expected? %)
                                                      (nil? (:usage %))
                                                      (pos? (or (:usage-writes %) 1)))
                                                timings))
       ;; Reported separately rather than folded away: a short-circuit is not a
       ;; defect, but a run full of them means the agent kept calling a tool it
       ;; could not use, which is worth seeing.
       :llm-stages-short-circuited (count (filter #(and (:usage-expected? %)
                                                        (= 0 (:usage-writes %)))
                                                  timings))
       ;; The MIRROR defect, and the more dangerous direction: a double-written
       ;; usage inflates llm-ms and shrinks other-ms, which looks exactly like
       ;; this fix having worked. No retry can cause it today (all retries are
       ;; exception-driven, and a throwing attempt returned no response), but
       ;; that guarantee is conditional on nobody retrying a successful-but-
       ;; unsatisfactory response. Reported raw rather than judged — but note
       ;; there is now NO stage on this path where 2 is expected: every one of
       ;; the eight LLM stages makes exactly one call per firing.
       ;; (An earlier version excused 2 on :read-signal-eval on the belief that
       ;; infer-query-intent also called an LLM. It does not — it is pure
       ;; heuristic. Anything above 1 is an anomaly worth chasing.)
       :usage-writes-max (reduce max 0 (keep :usage-writes timings))
       ;; WHICH stage wrote more than once. usage-writes-max says a double-write
       ;; happened; it does not say where, and guessing which stage is exactly
       ;; the step that has been wrong all evening. Named so the next run
       ;; answers it instead of me.
       :multi-write-stages (->> timings
                                (filter #(> (or (:usage-writes %) 0) 1))
                                (map (fn [t] (str (name (:stage t)) "=" (:usage-writes t))))
                                distinct sort (str/join "|"))
       ;; #25: WHICH stages are in other-ms, named rather than left as a
       ;; number. The sentinel above says every CLAIMED stage reported usage;
       ;; it cannot say every LLM stage is claimed, because llm-backed-stages
       ;; is hand-maintained and tool-stage-info has an :unknown fallback
       ;; beneath it. So a green gate and a large other-ms are consistent, and
       ;; this column is what turns that from a puzzle into a list.
       ;; #25 THE RESIDUAL, REPORTED RATHER THAN FOLDED IN. `tool-stage-info`'s
       ;; `case` ends in an :unknown catch-all, so ANY tool it does not name
       ;; lands in other-ms indistinguishable from real orchestration time.
       ;; The arc has already made the mistake of reading other-ms as a closed
       ;; set of observed stages; this is what makes the set checkable instead.
       ;; A breakdown that cannot report its own residual is one you cannot
       ;; check, and non-zero here means the taxonomy has drifted.
       ;; The per-ITERATION LLM stage, counted separately (#25). Six of the
       ;; seven LLM-calling stages fire at most once per query; :agent-llm
       ;; fires once per iteration, and it is the only reason the count of
       ;; STAGE TYPES is not the count of serial CALLS. Reporting it beside the
       ;; call count is what stops a set being read as a total.
       :agent-llm-count (count (filter #(= :agent-llm (:stage %)) timings))
       ;; WHICH stages fired, with counts. A stage ABSENT from :other-stages
       ;; either did not fire or fired and was attributed correctly, and nothing
       ;; distinguished those — so the artifact could not answer "did this run
       ;; converge?" even in principle. Now it can.
       :stages-fired (->> timings
                          (keep :stage)
                          frequencies
                          (map (fn [[st n]] (str (name st) "=" n)))
                          sort
                          (str/join "|"))
       ;; TWO OF THE EIGHT LLM STAGES ARE PURE FAILURE HANDLING, so their rates
       ;; are QUALITY metrics rather than cost lines, and neither has ever been
       ;; reported:
       ;;   :agent-final-llm fires ONLY on exhaustion — every firing means the
       ;;   agent loop did NOT converge and a fallback answer was synthesised.
       ;;   :citation-backfill-synthesis fires ONLY when primary synthesis
       ;;   produced no citations — every firing is a citation repair.
       ;; Broken out as their own columns so they can be aggregated without
       ;; parsing :stages-fired.
       :convergence-failures (count (filter #(= :agent-final-llm (:stage %)) timings))
       :citation-repairs (count (filter #(= :citation-backfill-synthesis (:stage %)) timings))
       :unknown-stage-ms (reduce + 0 (map dur (filter #(= :unknown (:stage %)) timings)))
       :unknown-stage-count (count (filter #(= :unknown (:stage %)) timings))
       :other-stages (->> timings
                          (filter #(and (not (io? %)) (not (llm? %))))
                          (keep :stage)
                          distinct
                          (map name)
                          sort
                          (str/join "|"))
       :total-duration-ms (or (get-in result [:diagnostics :execution-metadata :total-duration-ms])
                              (get-in result [:diagnostics :total-duration-ms]))})))

(defn served-models
  "Model ids the SERVER reported on the responses of this run, off the same
   stage-timings everything else reads.

   This is not the configured model name. The name is what we ASKED for; this
   is what answered. They can disagree — a deployment-name mismatch once made
   the judge silently run a different model — and only the response can tell
   you which."
  [result]
  (some->> (find-stage-timings result) (keep :llm-model) distinct seq (str/join ";")))

(defn reported-models
  "Distinct non-blank model-reported values across `rows`.

   Accepts either keyword or string keys, because a runs.csv read back from
   disk has string keys and an in-memory row has keywords — and the whole
   point of this check is that it survives the round-trip through the
   artifact."
  [rows]
  (->> rows
       (map #(or (get % :llm-model-served) (get % "llm-model-served")))
       (map str)
       (remove str/blank?)
       (mapcat #(str/split % #";"))
       (remove str/blank?)
       distinct
       set))

(defn model-comparability
  "Whether two or more run sets may be compared at all.

   :comparable   — every row reports the SAME served model
   :void         — more than one model reported. NOT a discrepancy to
                   reconcile: they are different models and any delta between
                   the sets is confounded by the model itself.
   :unverifiable — no row reports a model, so `unchanged` cannot be asserted.
                   Distinct from :comparable on purpose — absence of evidence
                   is not evidence of sameness, and older run sets predate the
                   column entirely."
  [& row-sets]
  (let [models (reported-models (apply concat row-sets))]
    {:status (cond (empty? models)      :unverifiable
                   (= 1 (count models)) :comparable
                   :else                :void)
     :models models}))

(defn assert-comparable-models!
  "Throws unless every run set reports the same served model. Returns it.

   Call this BEFORE comparing two run sets. A vendor can upgrade a model
   underneath a deployment name that never changes — no commit, no config
   change, no log line — so the only field that can detect it is the one the
   SERVER reports, and the only moment it helps is before the delta is
   computed rather than after it is quoted."
  [& row-sets]
  (let [{:keys [status models]} (apply model-comparability row-sets)]
    (case status
      :comparable (first models)
      :void (throw (ex-info (str "Comparison is VOID: run sets report different served models "
                                 (pr-str (sort models))
                                 ". This is not a discrepancy to reconcile — they are different "
                                 "models, and any delta between the sets is confounded.")
                            {:models models :status status}))
      :unverifiable (throw (ex-info (str "Comparison is UNVERIFIABLE: no row reports a served "
                                         "model, so `unchanged` cannot be asserted. Runs predating "
                                         "the model-reported column cannot support a comparison.")
                                    {:models models :status status})))))

(defn agent-token-totals
  "Sum per-LLM-call :usage entries off the agent's stage-timings into a
   single per-run record. Tolerates both kebab- and snake-case usage
   keys (the OpenAI client returns snake_case, internal Clojure code
   sometimes normalises).

   Returns nil when no stage-timings are present (e.g. a :timeout row
   that never produced agent output) so the CSV writer renders empty
   cells rather than zeros — that way \"no data\" is visually
   distinguishable from \"sum happened to be zero\".

   That is the ONLY condition that yields nil, and it is deliberately the
   same one `stage-duration-totals` uses. A run that executed stages but
   reached no LLM records a PRESENT ZERO, not a blank: it is a fact we
   measured rather than one we lack. Historical artifacts written before
   this still carry blanks, which is why `digdir.eval.run-validity` keeps
   its `llm-ms` fallback and its `:unknown` state — this fixes the cause
   for future rows without making the past unreadable."
  [result]
  (when-let [timings (find-stage-timings result)]
    (let [usages (keep :usage timings)
          pt (fn [u] (or (:prompt_tokens u) (:prompt-tokens u) 0))
          ct (fn [u] (or (:completion_tokens u) (:completion-tokens u) 0))
          tt (fn [u]
               (or (:total_tokens u) (:total-tokens u)
                   ;; Some providers omit total — derive when both halves
                   ;; are present so the column is still useful.
                   (let [p (pt u) c (ct u)]
                     (when (or (pos? p) (pos? c)) (+ p c)))))
          cached (fn [u]
                   (or (get-in u [:prompt_tokens_details :cached_tokens])
                       (get-in u [:prompt-tokens-details :cached-tokens])
                       0))
          ;; Reasoning tokens — local-model metadata (Part A). LM Studio's
          ;; OpenAI-compat usage carries them under
          ;; :completion_tokens_details :reasoning_tokens; some providers
          ;; flatten to a top-level :reasoning_tokens. Summing across calls
          ;; proves whether/how much the hidden thinking channel fired —
          ;; the signal that would have flagged today's silent low→on
          ;; reasoning fallback instantly.
          rt (fn [u]
               (or (get-in u [:completion_tokens_details :reasoning_tokens])
                   (get-in u [:completion-tokens-details :reasoning-tokens])
                   (:reasoning_tokens u) (:reasoning-tokens u) 0))]
      ;; NO INNER GATE ON `usages` (#284). This used to be wrapped in
      ;; `(when (seq usages) …)`, which returned nil — and therefore BLANK
      ;; CELLS — for a run that executed stages but never reached an LLM.
      ;; `stage-duration-totals` reads the SAME `find-stage-timings` and
      ;; gates only on the timings, so the two disagreed on exactly those
      ;; rows and the artifact recorded a blank `llm-calls` beside an
      ;; `llm-ms` of 0. A blank cannot distinguish its own causes — "no call
      ;; happened", "the provider omitted usage" and "this CSV predates the
      ;; column" are three different facts and one empty string. A present
      ;; zero can.
      {:prompt-tokens (reduce + 0 (map pt usages))
       :completion-tokens (reduce + 0 (map ct usages))
       :total-tokens (reduce + 0 (keep tt usages))
       :cached-tokens (reduce + 0 (map cached usages))
       :reasoning-tokens (reduce + 0 (map rt usages))
       :llm-calls (count usages)})))

(defn agent-finish-summary
  "Per-run finish-reason rollup off the agent's stage-timings (Part A
   metadata). The agent records a `:finish-reason` on each `:agent-llm`
   stage timing (loop.clj). We surface two signals to the CSV:

     :finish-reason   — the LAST agent-llm finish-reason (the terminal
                        turn; what actually ended the loop)
     :length-finish?  — true iff ANY turn finished on `length` (the
                        token-cap-truncation that produces the
                        empty-answer/cut-off-reasoning bug we chase)

   Returns nil when no stage-timings carry a finish-reason, so the CSV
   renders blanks rather than misleading defaults."
  [result]
  (when-let [timings (find-stage-timings result)]
    (let [reasons (keep :finish-reason timings)]
      (when (seq reasons)
        {:finish-reason (last reasons)
         :length-finish? (boolean (some #(= "length" (str %)) reasons))}))))

(defn score-run
  "Compute the scoreable metrics for a single (question, result) pair.
   Returns a flat map ready to merge into the per-run CSV record."
  [question result]
  (let [retrieved (retrieved-chunk-ids result)
        cited (cited-chunk-ids result)
        ;; Filter-4 decomposition: stage-by-stage presence of the golden.
        search-pool (search-pool-chunk-ids result)
        search-display (search-display-chunk-ids result default-display-window)
        reads (read-chunk-ids result)
        expected (:golden-chunk-ids question)
        grounding (or (:grounding-mode question) :chunks+answer)
        pattern (:expected-answer-pattern question)
        response (:response result)
        tokens (agent-token-totals result)
        durations (stage-duration-totals result)
        finish (agent-finish-summary result)
        ;; Each filter-stage boolean: was ANY of the goldens reachable at
        ;; that stage? For multi-golden rows the question is "did any
        ;; survive that filter" — same liberal interpretation `recall-at-k`
        ;; uses for the numerator.
        any-of (fn [needles haystack]
                 (let [s (set haystack)]
                   (boolean (some s needles))))]
    {:retrieved-chunk-ids (str/join ";" retrieved)
     :n-retrieved (count retrieved)
     :cited-chunk-ids (str/join ";" cited)
     :n-cited (count cited)
     :expected-chunk-ids (str/join ";" expected)
     :n-expected (count expected)
     :grounding-mode grounding
     :recall-at-5 (recall-at-k expected retrieved 5)
     :recall-at-10 (recall-at-k expected retrieved 10)
     :recall-at-20 (recall-at-k expected retrieved 20)
     ;; "citation recall" — how often the synthesis actually cited the
     ;; chunk we expected. Tighter than retrieval recall: a config can
     ;; retrieve the golden but never cite it.
     :citation-recall (when (seq expected)
                        (let [cited-set (set cited)
                              hits (count (filter cited-set expected))]
                          (/ (double hits) (count expected))))
     :answer-substring-hit? (answer-substring-hit? pattern response)
     ;; Filter-4 stage decomposition. A "yes" means at least one golden
     ;; survived to that stage; "no" means the regression happened there
     ;; or earlier. Together with `:recall-at-10` they slice the recall
     ;; gap:
     ;;   in-pool=N        → retrieval miss (filter 1 or 2)
     ;;   in-pool=Y, display=N → display-window truncation (filter 3)
     ;;   display=Y, read=N    → agent read-decision miss (filter 4)
     ;;   read=Y, recall@10=0  → rerank/output trimming (filter 5)
     :golden-in-search-pool? (any-of expected search-pool)
     ;; best rank of the golden in the FULL pool (nil = never retrieved = true
     ;; blind spot; a number > display-window = retrieved but buried).
     :golden-pool-rank (golden-pool-rank result expected)
     :golden-in-display? (any-of expected search-display)
     ;; best display-rank of the golden (nil if not displayed) — diagnoses
     ;; rank-gated read levers (e.g. why auto-read-top-K misses a rank-12 golden).
     :golden-display-rank (golden-display-rank result expected default-display-window)
     :golden-read? (any-of expected reads)
     ;; Pool-head characterization: WHAT outranks the golden (subtractive-lever
     ;; design input). golden-features = the golden's own descriptor; pool-head =
     ;; the chunks above it in its best pass. Empty when golden ranks 1 (clean)
     ;; or was never retrieved (vocab blind spot).
     :golden-features (golden-self-features result expected)
     :pool-head (str/join " ;; " (golden-pool-head result expected 8))
     ;; Agent-path behavior signals — slice the insufficiency/refinement loop.
     :search-passes (search-pass-count result)
     ;; the actual query strings issued — diagnoses rank variance (which phrasing
     ;; buried the golden) without which a reranker is tuned against noise.
     :issued-queries (str/join " | " (issued-queries result))
     :insufficiency-fired? (insufficiency-fired? result)
     :refinement-corpus-grounded? (refinement-corpus-grounded? result)
     :enrichment-hits (enrichment-hit-count result)
     :prompt-tokens (:prompt-tokens tokens)
     :completion-tokens (:completion-tokens tokens)
     :total-tokens (:total-tokens tokens)
     :cached-tokens (:cached-tokens tokens)
     ;; Local-model metadata (Part A): reasoning-token spend + finish-reason
     ;; rollup + a first-class empty-answer flag. Together these surface the
     ;; two silent failure modes (hidden reasoning blow-up, length-truncated
     ;; empty answers) without reading provider logs.
     :reasoning-tokens (:reasoning-tokens tokens)
     :finish-reason (:finish-reason finish)
     :length-finish? (:length-finish? finish)
     :empty? (str/blank? (str response))
     :llm-calls (:llm-calls tokens)
     :steps-executed (some-> result :diagnostics :execution-metadata :steps-executed)
     ;; Latency decomposition (#25). :io-ms is the model-independent floor.
     :io-search-ms (:io-search-ms durations)
     :io-rerank-ms (:io-rerank-ms durations)
     :io-tool-ms (:io-tool-ms durations)
     :io-ms (:io-ms durations)
     :llm-ms (:llm-ms durations)
     :other-ms (:other-ms durations)
     ;; #25 sentinel + its mirror. Mapped here as well as declared in the
     ;; column list: a column declared but never populated renders as an empty
     ;; cell, which reads as "nothing wrong" — the failure mode this pair
     ;; exists to prevent.
     :llm-stages-missing-usage (:llm-stages-missing-usage durations)
     :llm-stages-short-circuited (:llm-stages-short-circuited durations)
     :usage-writes-max (:usage-writes-max durations)
     :multi-write-stages (:multi-write-stages durations)
     :other-stages (:other-stages durations)
     :agent-llm-count (:agent-llm-count durations)
     :stages-fired (:stages-fired durations)
     :convergence-failures (:convergence-failures durations)
     :citation-repairs (:citation-repairs durations)
     :unknown-stage-ms (:unknown-stage-ms durations)
     :unknown-stage-count (:unknown-stage-count durations)
     :io-call-count (:io-call-count durations)
     :llm-call-count (:llm-call-count durations)
     :total-duration-ms (:total-duration-ms durations)}))

;; =============================================================================
;; Dataset / collection resolution
;; =============================================================================

(defn- resolve-dataset-config!
  "Look up a dataset config from the running config DB.

   `execution-scope` keys used: :tenant, :dataset-config-key (or
   :dataset-ref shortcut). Returns a map with :docs-collection,
   :chunks-collection, :phrases-collection, plus the full dataset-config
   for downstream skill-params construction."
  [execution-scope]
  (let [conn (cfg-db/get-conn)
        master-key (cfg-core/get-master-key)
        dataset-ref (or (:dataset-ref execution-scope)
                        {:tenant (:tenant execution-scope)
                         :dataset-config-key (:dataset-config-key execution-scope)})
        ds-cfg (cfg-db/get-dataset-by-ref @conn dataset-ref master-key)]
    (when-not ds-cfg
      (throw (ex-info "sweep.runner: dataset config not found"
                      {:execution-scope execution-scope})))
    {:dataset-config ds-cfg
     :collections {:docs-collection (:docs-collection ds-cfg)
                   ;; Lever B: optional override so a sweep can retrieve from a
                   ;; re-chunked chunks collection without registering a new
                   ;; dataset/pipeline config tree. docs+phrases stay as resolved
                   ;; (the new chunks join to the same docs by doc_num).
                   :chunks-collection (or (:chunks-collection-override execution-scope)
                                          (:chunks-collection ds-cfg))
                   ;; Phrase-pruning lever: optional override so a sweep can
                   ;; retrieve from a pruned phrases clone (generic phrases removed)
                   ;; without registering a new dataset config. Pruning only drops
                   ;; phrase rows — chunks/docs/golden-ids are unchanged, so off vs
                   ;; pruned is a clean A/B needing no re-grounding.
                   :phrases-collection (or (:phrases-collection-override execution-scope)
                                           (:phrases-collection ds-cfg))}}))

(def ^:private agent-prompts-path "test/fixtures/sweep/agent_prompts.edn")

(defn- load-agent-prompts []
  (try (edn/read-string (slurp agent-prompts-path)) (catch Exception _ {})))

(defn- apply-prompt-variants
  "Resolve each config's optional `:agent-prompt-variant` (a key into
   agent_prompts.edn) into a concrete `:builtin/agent :system-prompt`
   override, BEFORE `resolve-effective-configs` overlays the matrix delta
   onto the production base (so the prompt rides the same precedence as any
   other skill-param). `:default` or absent → no injection (the agent falls
   through to its built-in `default-system-prompt`). An unknown variant
   THROWS — a typo'd variant must not silently run the default and confound
   the prompt-vs-prompt screen."
  [configs]
  (let [prompts (load-agent-prompts)]
    (mapv (fn [c]
            (let [variant (:agent-prompt-variant c)]
              (cond
                (or (nil? variant) (= :default variant)) c
                (contains? prompts variant)
                (assoc-in c [:skill-params :builtin/agent :system-prompt]
                          (get prompts variant))
                :else
                (throw (ex-info "sweep.runner: unknown :agent-prompt-variant"
                                {:variant variant :known (vec (keys prompts))})))))
          configs)))

(defn- resolve-effective-configs
  "Make each sweep config faithful to the real agent. For each config, compute
   `:effective-skill-params` = the agent's PRODUCTION effective config (dataset
   defaults + the agent-id's stored `:skill-params`, assembled by the canonical
   `api-util/build-rag-skill-params` — the SAME assembler the playground/production
   path uses) with the matrix's per-config `:skill-params` overlaid as the
   experimental delta (highest precedence, one-level merge-with-merge).

   This is the consolidation fix: the sweep now tests the configured agent + an
   explicit delta, not a hand-rolled config. `:effective-skill-params` is what
   actually reaches invoke-rag and is recorded in matrix.edn for the dashboard."
  [configs execution-scope dataset-config]
  (let [agent-id (:agent-id execution-scope)
        agent-skill-params (or (some-> (try (agents-db/get-agent @(cfg-db/get-conn) agent-id)
                                            (catch Exception _ nil))
                                       :skill-params)
                               {})
        prod-base (api-util/build-rag-skill-params dataset-config {} agent-skill-params)]
    (mapv (fn [c]
            (assoc c :effective-skill-params
                   (merge-with merge prod-base (or (:skill-params c) {}))))
          configs)))

;; =============================================================================
;; Single run
;; =============================================================================

(defn- truncate
  "Truncate a string for CSV-cell safety. nil → empty string."
  ([s] (truncate s 800))
  ([s n] (cond
           (nil? s) ""
           (<= (count s) n) s
           :else (str (subs s 0 n) "…"))))

(def default-run-timeout-ms
  "Per-run wall-clock cap. The agent should converge in ~30-60s; if a
   single run exceeds this, something is wedged (typically a hung
   socket read against the LLM provider) and we cap so the sweep can
   continue rather than parking the for-loop indefinitely.

   Set above the empirical p99 by a comfortable margin. The May-2026
   baseline-v0 sweep p99 was ~137s (faithful on a digdir-arsverk
   compound). 5 minutes leaves >2x headroom while still letting us
   recover from a stuck connection within the same sweep."
  (* 5 60 1000))

;; =============================================================================
;; LLM-as-judge (P2): optional answer-quality + task-difficulty scoring
;; =============================================================================

(def ^:private references-path "test/fixtures/sweep/references.edn")

(defn merge-references
  "Assoc :reference-answer onto each question from references.edn (keyed by :id).
   Questions already carrying :reference-answer are left untouched."
  [questions]
  (let [refs (try (edn/read-string (slurp references-path)) (catch Exception _ {}))]
    (mapv (fn [q] (cond-> q
                    (not (:reference-answer q))
                    (assoc :reference-answer (get-in refs [(:id q) :reference-answer]))))
          questions)))

(defonce ^:private !judge-cache (atom {}))

(def judge-timeout-ms
  "Wall-clock cap on a single judge LLM call. Unlike the agent call, the judge
   call is NOT inside the per-run future, so without this a hung judge socket
   (e.g. a network blip) would wedge the whole sweep indefinitely. On timeout we
   record a `timeout` verdict and move on."
  (* 2 60 1000))

(defn judge-run
  "Judge one run's answer against the question's reference, returning the flat
   judge columns. Cached by [judge-model question-id (hash response)] so identical
   answers (e.g. across repeats) are judged once. The judge LLM call is wrapped in
   a wall-clock timeout so a hung socket can't wedge the sweep; timeout/error
   verdicts are NOT cached (transient)."
  [tenant question response]
  (let [model (judge/judge-model tenant)
        ck [model (:id question) (hash (str response))]]
    (or (get @!judge-cache ck)
        (let [fut (future (judge/judge-answer tenant {:query (:query question)
                                                      :reference (:reference-answer question)
                                                      :response response}))
              v (let [r (deref fut judge-timeout-ms ::timeout)]
                  (if (= r ::timeout)
                    (do (future-cancel fut)
                        (t/log! :warn [:sweep.runner/judge-timeout
                                       {:question-id (:id question) :timeout-ms judge-timeout-ms}])
                        {:verdict "timeout" :score nil
                         :rationale (str "judge call exceeded " judge-timeout-ms "ms")
                         :judge-model model})
                    r))
              cols {:answer-judge-verdict (:verdict v)
                    :answer-judge-score (:score v)
                    :answer-judge-rationale (:rationale v)
                    :answer-judge-model (:judge-model v)
                    :task-difficulty (:difficulty v)
                    :task-difficulty-rationale (:difficulty-rationale v)}]
          ;; cache only clean verdicts — a timeout/error is transient
          (when-not (#{"timeout" "error" "unparseable"} (:verdict v))
            (swap! !judge-cache assoc ck cols))
          cols))))

(defn make-first-chunk-timer
  "Times the first user-visible prose chunk of a run.

   Returns {:progress-fn f :snapshot (fn [] {:ttft-ms n :response-chunk-count n})}.
   `progress-fn` is safe to hand to any agent call: it reacts only to
   :response/chunk and ignores every other event.

   :response-chunk-count is not decoration. `events/emit-progress!` swallows
   callback exceptions, so a broken timer and a non-streaming path would BOTH
   leave :ttft-ms nil. count 0 means nothing streamed; count > 0 with a nil
   ttft means this timer is broken. Without the count the two are
   indistinguishable, and a nil would be read as `instant`."
  [t0]
  (let [!ttft (atom nil)
        !n (atom 0)
        !first-text (atom nil)]
    {:progress-fn (fn [ev]
                    (when (= :response/chunk (:event ev))
                      (swap! !n inc)
                      ;; compare-and-set!, not reset!: only the FIRST chunk
                      ;; sets the clock.
                      (compare-and-set! !ttft nil (- (System/currentTimeMillis) t0))
                      ;; ...and only the first chunk's TEXT is kept (#25).
                      ;; Without it, ttft-ms is AMBIGUOUS BETWEEN TWO PRODUCTS:
                      ;; "the answer starts at 2.6s" and "the model narrates at
                      ;; 2.6s and the answer lands at 98s". The number cannot
                      ;; separate them; only the text can, so one look at a
                      ;; different quantity settles what more of the same
                      ;; quantity never would.
                      ;;
                      ;; The payload key is :delta — see events/response-chunk.
                      ;; A stub using any other key would leave this nil while
                      ;; every count assertion still passed.
                      (compare-and-set! !first-text nil (:delta ev))))
     :snapshot (fn [] {:ttft-ms @!ttft
                       :response-chunk-count @!n
                       :first-chunk-text @!first-text})}))

(defn run-single
  "Execute one (config, question) pair and return a flat record.

   The invoke-rag call is wrapped in a future and deref'd with a
   wall-clock timeout (:run-timeout-ms, defaults to
   `default-run-timeout-ms`). On timeout we cancel the future
   (Thread.interrupt) and return a :status :timeout row so the sweep
   can move on. The cancelled thread may keep ticking in the background
   if the HTTP client doesn't honor interrupts — that's a known
   limitation of the openai-clojure client — but the sweep loop is
   unblocked."
  [{:keys [config question collections execution-scope
           max-clarification-rounds default-skill-graph-id
           run-timeout-ms judge?]
    :or {max-clarification-rounds sim/default-max-clarification-rounds
         default-skill-graph-id :builtin/agent-rag-graph-bundled
         run-timeout-ms default-run-timeout-ms}}]
  (let [run-id (str (random-uuid))
        t0 (System/currentTimeMillis)
        ;; TIME TO FIRST USEFUL OUTPUT (#25). Streaming only happens when a
        ;; :progress-fn is present - call-llm's 5-arity is a blocking request
        ;; that emits nothing to time. Injecting one HERE, per run, rather
        ;; than into the config, keeps functions out of matrix.edn, which is
        ;; written with pr-str and re-read by sweep-judge.
        ;;
        ;; What this measures is time to the first user-visible PROSE chunk,
        ;; not the first token and not the first LLM response. A pure
        ;; tool-call turn produces no content deltas at all, so the clock
        ;; runs until the model starts emitting an answer - which is the
        ;; thing an interactive user actually waits for. The chunker flushes
        ;; on a paragraph boundary or a 250ms timeout, so this reads up to
        ;; 250ms LATER than the first token, bounded and known.
        ;;
        ;; :response-chunk-count exists so a nil ttft is DIAGNOSABLE.
        ;; emit-progress! swallows callback exceptions, so a broken timer and
        ;; a non-streaming path would both leave ttft nil. count 0 means
        ;; nothing streamed; count > 0 with nil ttft means this timer is
        ;; broken. Without it the two are indistinguishable.
        {:keys [progress-fn snapshot]} (make-first-chunk-timer t0)
        intent-hint (sim/intent-hint-from-row question)
        fut (future
              (try
                (sweep-invoke/invoke-with-clarification-loop
                  {:user-query (:query question)
                   :skill-graph-id (or (:skill-graph-id config)
                                       default-skill-graph-id)
                   :collections collections
                   :execution-scope execution-scope
                   ;; faithful-agent effective params (agent config + matrix delta);
                   ;; falls back to the raw matrix params for any direct caller.
                   :skill-params (or (:effective-skill-params config)
                                     (:skill-params config))
                   ;; TOP-LEVEL, not inside :skill-params (#323). `invoke-rag`
                   ;; is what lifts a top-level :progress-fn into
                   ;; `[:ambient-ctx-opts :opts :progress-fn]`, and
                   ;; `[:opts :progress-fn]` is the ONLY place the bundled
                   ;; graph reads it (iteration_bundled.clj, graphs.clj).
                   ;; Placed under [:builtin/agent :progress-fn] it was read by
                   ;; nothing at all, so every run took the blocking branch and
                   ;; ttft-ms came back nil on all 16 — see ttft_wiring_test.
                   :progress-fn progress-fn
                   :model (:model config)
                   :temperature (:temperature config)
                   :intent-hint intent-hint
                   :max-clarification-rounds max-clarification-rounds})
                (catch Throwable e
                  (t/log! :error [:sweep.runner/run-exception
                                  {:run-id run-id
                                   :config-id (:id config)
                                   :question-id (:id question)
                                   :error (.getMessage e)}])
                  {:status :error
                   :response ""
                   :chunks []
                   :clarification-rounds 0
                   :terminal-clarification? false
                   :error {:error-type :runner-exception
                           :error-message (.getMessage e)}})))
        sentinel (Object.)
        deref-result (deref fut run-timeout-ms sentinel)
        result (if (identical? deref-result sentinel)
                 (do
                   (future-cancel fut)
                   (t/log! :warn [:sweep.runner/run-timeout
                                  {:run-id run-id
                                   :config-id (:id config)
                                   :question-id (:id question)
                                   :timeout-ms run-timeout-ms}])
                   {:status :timeout
                    :response ""
                    :chunks []
                    :clarification-rounds 0
                    :terminal-clarification? false
                    :error {:error-type :run-timeout
                            :error-message (str "exceeded "
                                                run-timeout-ms "ms")}})
                 deref-result)
        elapsed-ms (- (System/currentTimeMillis) t0)
        scored (score-run question result)
        ;; Optional LLM-as-judge: answer-quality verdict + intrinsic task
        ;; difficulty, graded against the question's reference answer.
        judge-cols (when (and judge? (not (str/blank? (str (:response result)))))
                     (judge-run (:tenant execution-scope) question (:response result)))
        row (merge {:run-id run-id
            :timestamp (str (java.time.Instant/now))
            :config-id (:id config)
            :question-id (:id question)
            :question-tags (str/join "," (map name (or (:tags question) [])))
            :dataset (some-> question :dataset name)
            :source (some-> question :source name)
            :status (some-> result :status name)
            ;; Did the measurement happen? (#276) A run that reached no LLM is
            ;; recorded :complete with recall 0.0 and is indistinguishable in
            ;; the row from a model that answered everything wrong. Written as
            ;; its own column so the ARTIFACT carries the distinction, rather
            ;; than a reader having to notice elapsed-ms 34. Set AFTER this
            ;; merge, where :llm-calls is present — it arrives from a later map.
            ;; #289: the language of the question and of the answer, detected at
            ;; run time because the CSV carries no question text - only
            ;; :question-id - so a reader could not otherwise recover what
            ;; language the answer was SUPPOSED to be in. Recorded, not judged:
            ;; an answer in the wrong language is a broken run, not a bad one.
            :question-language (name (answer-language/detect (:query question)))
            :answer-language (name (answer-language/detect (:response result)))
            :clarification-rounds (:clarification-rounds result 0)
            :terminal-clarification? (boolean (:terminal-clarification? result))
            :elapsed-ms elapsed-ms
            ;; Endpoint provenance (#25), the other half of the corpus columns.
            ;; Requested vs served, per row, because a per-config model
            ;; override makes a sweep-level constant wrong for one arm.
            :llm-uri (or (System/getenv "OPENAI_API_ENDPOINT")
                         (try (cfg/get {:tenant (:tenant execution-scope)}
                                       :services :azure-openai :api-endpoint)
                              (catch Exception _ nil)))
            :llm-model-requested (:model config)
            :llm-model-served (served-models result)
            :ttft-ms (:ttft-ms (snapshot))
            :response-chunk-count (:response-chunk-count (snapshot))
            ;; #25: what the ttft clock actually saw. Truncated because this is
            ;; a discriminator, not a transcript — narration and answer-opening
            ;; are distinguishable in a few dozen characters.
            :first-chunk-text (some-> ^String (:first-chunk-text (snapshot))
                                      (as-> s (subs s 0 (min 120 (count s)))))
            ;; Persist the FULL response — never truncate the column the SEPARATE
            ;; judge pass (judge-sweep-dir!) reads, or it grades a clipped answer
            ;; and fakes "partial" on long/enumerative outputs (the 800-char bug).
            ;; The agent's max_tokens already bounds length; CSV quoting handles
            ;; multi-line. Truncate only human-facing fields (errors, previews).
            :response (str (or (:response result) ""))
            :response-chars (count (str (:response result)))
            :error (when-let [e (:error result)]
                     (truncate (str (or (:error-message e) e))))}
                  scored
                  judge-cols)]
      ;; #276: classified here, where :llm-calls exists. `name`d so the CSV
      ;; cell reads "measured" / "no-llm-call", matching how :status is
      ;; written one column to its left.
      (assoc row :measurement (name (run-validity/classify row)))))

;; =============================================================================
;; Matrix run + CSV write
;; =============================================================================

(def csv-columns
  "Stable column order for the CSV. Anything `score-run` returns that
   isn't in this list lands in the trailing 'extras' lane — but in
   practice we want this list to cover everything so the CSV stays
   spreadsheet-friendly."
  [:run-id :timestamp
   :config-id :question-id :repeat :dataset :source :question-tags
   :status :measurement :question-language :answer-language
   :clarification-rounds :terminal-clarification?
   :grounding-mode
   :answer-substring-hit?
   ;; LLM-as-judge (P2): answer-quality verdict + intrinsic task difficulty,
   ;; graded vs the question's reference answer. Blank when judging is off.
   :answer-judge-verdict :answer-judge-score :answer-judge-rationale :answer-judge-model
   :task-difficulty :task-difficulty-rationale
   :recall-at-5 :recall-at-10 :recall-at-20 :citation-recall
   ;; Filter-4 stage decomposition (Round-8 post-mortem). Each boolean
   ;; tells you whether *any* of the golden chunk-ids survived to that
   ;; pipeline stage. Together with recall-at-10 they slice the gap
   ;; between "candidate pool" and "final workspace top-10".
   :golden-in-search-pool? :golden-pool-rank :golden-in-display? :golden-display-rank :golden-read?
   ;; Pool-head characterization — WHAT buries the golden (subtractive-lever input).
   :golden-features :pool-head
   ;; Agent-path behavior signals (insufficiency/refinement loop visibility).
   ;; :refinement-corpus-grounded? is blank when no re-search happened.
   :search-passes :issued-queries :insufficiency-fired? :refinement-corpus-grounded? :enrichment-hits
   :n-retrieved :n-cited :n-expected
   :retrieved-chunk-ids :cited-chunk-ids :expected-chunk-ids
   :elapsed-ms :prompt-tokens :completion-tokens :total-tokens
   ;; Latency decomposition (#25). :io-ms is model-independent and does not
   ;; shrink with a better model; :llm-ms does.
   ;;
   ;; ":other-ms should stay near zero" was written here as an expectation and
   ;; nothing checked it. It reached 83% of wall-clock on the 16-run latency
   ;; sweep, because five LLM-calling stages reported no :usage and the
   ;; classifier buckets on (some? :usage). An expectation with no check is a
   ;; comment. :llm-stages-missing-usage is the check.
   :total-duration-ms :io-ms :io-search-ms :io-rerank-ms :io-tool-ms
   :llm-stages-missing-usage :llm-stages-short-circuited :usage-writes-max :other-stages :multi-write-stages
   :agent-llm-count :convergence-failures :citation-repairs :stages-fired
   :unknown-stage-ms :unknown-stage-count
   ;; What the TTFT clock actually saw. ttft-ms alone cannot separate
   ;; "the answer starts here" from "the model narrates here and the answer
   ;; lands 95s later", and those are different products.
   :first-chunk-text
   ;; Corpus provenance (#25): which corpus this row was measured against.
   :typesense-uri :docs-collection :chunks-collection :phrases-collection
   ;; Time to first user-visible prose chunk. chunk-count 0 = nothing
   ;; streamed, which makes a nil ttft diagnosable rather than ambiguous.
   :ttft-ms :response-chunk-count
   ;; Endpoint provenance: requested is what we asked for, served is what
   ;; answered. They can disagree.
   :llm-uri :llm-model-requested :llm-model-served
   :llm-ms :other-ms :io-call-count :llm-call-count
   :cached-tokens :llm-calls
   ;; Local-model run metadata (Part A): per-row dynamic fields. Constant
   ;; per-(sweep,config) model identity (id/quant/engine/endpoint/sampling)
   ;; lives in <sweep-dir>/models.edn to keep the row width sane.
   :reasoning-tokens :finish-reason :length-finish? :empty?
   :steps-executed
   :response :error])

(defn- csv-escape
  "RFC-4180-ish escaping. Wrap in quotes when the value contains a
   comma, quote, newline, or carriage-return; double quotes inside."
  [v]
  (let [s (cond
            (nil? v) ""
            (boolean? v) (str v)
            (number? v) (str v)
            (keyword? v) (name v)
            :else (str v))]
    (if (re-find #"[,\"\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn write-csv!
  "Batch writer (kept for any caller that already has the full rows
   vector in memory). The runner's primary path writes incrementally
   via `write-csv-header!` + `write-csv-row!`."
  [path rows]
  (with-open [w (io/writer path)]
    (.write w (str (str/join "," (map name csv-columns)) "\n"))
    (doseq [row rows]
      (.write w (str (str/join "," (map #(csv-escape (get row %)) csv-columns))
                     "\n")))))

(defn write-csv-header!
  "Write the header line. Caller is responsible for keeping the writer
   open and calling `write-csv-row!` for each subsequent row."
  [^java.io.Writer w]
  (.write w (str (str/join "," (map name csv-columns)) "\n"))
  (.flush w))

(defn write-csv-row!
  "Append one row + flush. Flushing per-row trades a small amount of
   throughput for the ability to tail runs.csv mid-sweep and to recover
   completed rows if the JVM crashes or the sweep is interrupted."
  [^java.io.Writer w row]
  (.write w (str (str/join "," (map #(csv-escape (get row %)) csv-columns))
                 "\n"))
  (.flush w))

(defn- ensure-dir! [path]
  (let [f (io/file path)]
    (when-not (.exists f) (.mkdirs f))
    path))

(defn write-progress!
  "Atomically write the live sweep-progress map to `<out-dir>/progress.edn` so the
   dashboard can show in-progress status + ETA while a sweep runs (runs.csv only
   gains a row when a run COMPLETES, so it can't express total/remaining/current).
   Tmp-then-rename avoids the dashboard reading a half-written file."
  [out-dir m]
  (let [tmp (io/file out-dir ".progress.edn.tmp")
        dst (io/file out-dir "progress.edn")]
    (spit tmp (pr-str (assoc m :updated-at-ms (System/currentTimeMillis))))
    (.renameTo tmp dst)))

;; =============================================================================
;; Part A — sweep-level model metadata manifest (models.edn)
;; =============================================================================

(def ^:private env-metadata-keys
  "OPENAI_* env vars that materially change what ran (sampling, reasoning
   mode, endpoint). Captured verbatim into the manifest so a screen cell is
   interpretable from the record alone — these are the knobs that varied
   SILENTLY today (low→on reasoning, MLX-vs-GGUF endpoint, sampling)."
  ["OPENAI_API_ENDPOINT" "OPENAI_REASONING_EFFORT" "OPENAI_DISABLE_THINKING"
   "OPENAI_PRESERVE_THINKING" "OPENAI_TEMPERATURE" "OPENAI_TOP_P" "OPENAI_TOP_K"
   "OPENAI_MIN_P" "OPENAI_PRESENCE_PENALTY" "OPENAI_REPETITION_PENALTY"
   "OPENAI_MAX_TOKENS"])

(defn- env-snapshot []
  ;; Plain array-map (insertion-ordered, key-sorted), NOT sorted-map: pr-str
  ;; emits sorted-map as a `#sorted/map` tagged literal that clojure.edn can't
  ;; read back, which would break every reader of models.edn.
  (into (array-map)
        (keep (fn [k] (when-let [v (System/getenv k)] [k v])) env-metadata-keys)))

(defn- env-true? [k]
  (contains? #{"true" "1" "yes" "on"}
             (some-> (System/getenv k) str/trim str/lower-case)))

(defn- lmstudio-models-url
  "Derive LM Studio's richer /api/v0/models endpoint from the OpenAI-compat
   base (…/v1). /api/v0/models returns quantization, arch,
   loaded_context_length, and state — none of which /v1/models exposes."
  [endpoint]
  (when (not-empty endpoint)
    (str (str/replace endpoint #"/v1/?$" "") "/api/v0/models")))

(defn- openai-models-url
  "The PORTABLE models endpoint. `lmstudio-models-url` targets LM Studio's
   richer /api/v0/models, which returns nil against llama.cpp or vLLM — and
   those are exactly the servers reached through a tunnel, where we most
   need a record of what answered."
  [endpoint]
  (when (not-empty endpoint)
    (str (str/replace endpoint #"/v1/?$" "") "/v1/models")))

(defn- fetch-openai-models
  "Best-effort GET of <endpoint>/v1/models → the served model ids.
   Returns nil when unreachable. NEVER throws — provenance capture must not
   be able to fail a sweep."
  [endpoint]
  (when-let [url (openai-models-url endpoint)]
    (try
      (let [resp (http/get url {:as :json :throw-exceptions false
                                :socket-timeout 5000 :connection-timeout 5000})]
        (some->> (get-in resp [:body :data]) (keep :id) seq vec))
      (catch Exception _ nil))))

(defn- fetch-lmstudio-models
  "Best-effort GET of LM Studio's /api/v0/models → {model-id → record}.
   Returns nil when unreachable or non-LM-Studio. NEVER throws — metadata
   capture must not be able to fail a sweep."
  [endpoint]
  (when-let [url (lmstudio-models-url endpoint)]
    (try
      (let [resp (http/get url {:as :json :throw-exceptions false
                                :socket-timeout 5000 :connection-timeout 5000})
            data (get-in resp [:body :data])]
        (when (seq data)
          (into {} (map (fn [m] [(:id m) m])) data)))
      (catch Exception _ nil))))

(defn resolve-model-manifest!
  "Capture the sweep-level model manifest (Part A). Records, per LLM task
   (agent / judge / generation), the resolved {model, provider, engine,
   endpoint, mode} merged with the live OPENAI_* env sampling snapshot and —
   for local models — the LM Studio /api/v0/models record (quant / arch /
   loaded_context_length / state). One capture per sweep keeps the constant
   model-identity fields out of every CSV row.

   The judge is recorded as its INTENDED cloud route (gpt-5.5, azure) — the
   agent sweep runs azure-off (local), and judging happens in a separate
   azure-on pass — so the manifest documents the real two-process design
   rather than the sweep-time routing.

   Best-effort throughout: a config-db miss or an unreachable LM Studio
   degrades to nils, never an exception."
  [tenant]
  (let [cfgv (fn [& path] (try (apply cfg/get {:tenant tenant} path)
                               (catch Exception _ nil)))
        use-azure? (boolean (cfgv :services :azure-openai :use-azure-openai-api))
        endpoint (System/getenv "OPENAI_API_ENDPOINT")
        lm-models (when-not use-azure? (fetch-lmstudio-models endpoint))
        lm-record (fn [model-id] (get lm-models model-id))
        agent-model (if use-azure?
                      (cfgv :services :azure-openai :deployment-name)
                      (cfgv :services :azure-openai :model-name))
        judge-model (or (cfgv :services :judge :model) judge/default-judge-model)
        gen-provider (cfgv :services :self-improvement :provider)
        gen-model (cfgv :services :lmstudio :model)
        gen-endpoint (cfgv :services :lmstudio :api-endpoint)]
    {:captured-at (str (java.time.Instant/now))
     :tenant tenant
     :env (env-snapshot)
     ;; What the endpoint says about ITSELF, portable across LM Studio,
     ;; llama.cpp and vLLM. The Kimi figures were produced against an
     ;; endpoint reached through a tunnel nobody recorded, and the harness
     ;; kept the results while discarding the conditions.
     :models-endpoint {:url (openai-models-url endpoint)
                       :served (fetch-openai-models endpoint)}
     :tasks
     {:agent (cond-> {:task :agent
                      :provider (if use-azure? :azure-openai :lmstudio)
                      :engine (if use-azure? :azure :llama.cpp)
                      :model agent-model
                      :endpoint (if use-azure?
                                  (cfgv :services :azure-openai :api-endpoint)
                                  endpoint)
                      :mode (if (env-true? "OPENAI_DISABLE_THINKING")
                              :non-thinking :thinking)
                      :reasoning-effort (System/getenv "OPENAI_REASONING_EFFORT")}
               (lm-record agent-model) (assoc :lmstudio (lm-record agent-model)))
      :judge {:task :judge
              :provider :azure-openai
              :engine :azure
              :model judge-model
              :note "cloud judge — run as a SEPARATE azure-on pass, not the sweep-time route"}
      :generation (cond-> {:task :generation
                           :provider gen-provider
                           :engine (if (= gen-provider :lmstudio) :llama.cpp :azure)
                           :model gen-model
                           :endpoint gen-endpoint
                           :mode :non-thinking}
                    (lm-record gen-model) (assoc :lmstudio (lm-record gen-model)))}}))

(defn run-matrix
  "Run the cartesian product of {configs × questions × repeats}.

   `opts` keys:
     :configs                  — vec of config maps (the matrix)
     :questions                — vec of question rows (already filtered)
     :repeats                  — int, default 1
     :execution-scope          — {:tenant :dataset-config-key :agent-id}
     :max-clarification-rounds — int, default 2
     :run-timeout-ms           — int, default 5 min — per-run wall-clock cap
     :out-dir                  — string; default
                                 'server/results/sweep-<ISO-ts>/'

   Returns {:rows vec :out-dir str :csv-path str}. Also persists
   `runs.csv` + `matrix.edn` (the input matrix, for provenance).

   The writer is kept open for the duration of the sweep and each
   completed row is written + flushed immediately. That lets us
   tail the CSV mid-sweep AND recover all completed rows if the sweep
   gets interrupted (Ctrl-C, JVM crash, future-cancel from the outside)."
  [{:keys [configs questions questions-path repeats execution-scope
           max-clarification-rounds run-timeout-ms out-dir judge? order concurrency]
    :or {repeats 1
         max-clarification-rounds sim/default-max-clarification-rounds
         run-timeout-ms default-run-timeout-ms
         concurrency 1}}]
  (when (empty? configs)   (throw (ex-info "run-matrix: :configs is empty"   {})))
  (when (empty? questions) (throw (ex-info "run-matrix: :questions is empty" {})))
  (let [;; Coerce an explicit nil (a caller may thread `(:concurrency matrix)`
        ;; through, and the `:or` default only fires on an ABSENT key) → serial.
        concurrency (or concurrency 1)
        ;; Smart default: an A/B (>=2 configs) auto-interleaves so the arms are
        ;; time-balanced; a single-config baseline stays config-major (trivially
        ;; identical). Explicit :order always wins.
        order (or order (if (> (count configs) 1) :interleaved :config-major))
        questions (if judge? (merge-references questions) questions)
        {:keys [collections dataset-config]} (resolve-dataset-config! execution-scope)
        ;; Corpus provenance (#25). resolve-dataset-config! already computes
        ;; these and we used to throw them away: a past sweep could not be
        ;; audited for WHICH CORPUS it ran against, and the config DB that
        ;; could have answered it afterwards had been deleted. Recording the
        ;; RESOLVED values also captures the chunks/phrases overrides, which a
        ;; dataset-config-key alone would not.
        ;; Only :uri is recorded. The admin key is never written to an artifact.
        ts-uri (try (:uri (ts/make-ts-settings {:tenant (:tenant execution-scope)}))
                    (catch Exception _ nil))
        ;; Consolidation: make each config faithful to the configured agent —
        ;; effective params = agent's production config + matrix delta. The
        ;; prompt-variant pass runs FIRST so a selected agent-prompt rides the
        ;; same merge precedence as any other per-config skill-param delta.
        configs (-> configs
                    apply-prompt-variants
                    (resolve-effective-configs execution-scope dataset-config))
        ts (-> (java.time.Instant/now) str
               (str/replace #"[:.]" "-"))
        out-dir (ensure-dir! (or out-dir (str "results/sweep-" ts)))
        csv-path (str out-dir "/runs.csv")
        matrix-path (str out-dir "/matrix.edn")
        ;; Persist provenance up-front so a crash mid-run still leaves
        ;; us a record of what was attempted.
        _ (spit matrix-path (pr-str {:configs configs   ; now carry :effective-skill-params
                                     :questions-path questions-path  ; so sweep-judge re-finds the fixture
                                     :agent-id (:agent-id execution-scope)
                                     :n-questions (count questions)
                                     :repeats repeats
                                     :execution-scope execution-scope
                                     :collections collections
                                     :typesense-uri ts-uri
                                     :max-clarification-rounds max-clarification-rounds
                                     :run-timeout-ms run-timeout-ms
                                     :judge? (boolean judge?)
                                     :order order
                                     :started-at (str (java.time.Instant/now))}))
        ;; Part A: capture the model manifest once at sweep start so every
        ;; cell is interpretable from the record alone (model/quant/engine/
        ;; endpoint/mode/sampling per task). Best-effort — never fails the run.
        _ (try (spit (str out-dir "/models.edn")
                     (pr-str (resolve-model-manifest! (:tenant execution-scope))))
               (catch Exception e
                 (t/log! :warn [:sweep.runner/manifest-failed
                                {:error (.getMessage e)}])))
        total (* (count configs) (count questions) repeats)
        ;; Execution order. :config-major (default) finishes all of config A
        ;; before config B — simple, fine for single-config baselines. But for an
        ;; A/B (>=2 configs) the arms land in DISJOINT time windows, so any
        ;; provider drift over a long run confounds the comparison.
        ;; :interleaved runs the configs back-to-back per (question, repeat) so the
        ;; arms are time-balanced (a crash also leaves a balanced partial A/B, and
        ;; per-question comparisons accumulate early). Same cells, different order.
        cells (case order
                :interleaved (vec (for [question questions
                                        r (range repeats)
                                        config configs]
                                    [config question r]))
                ;; :config-major (default)
                (vec (for [config configs
                           question questions
                           r (range repeats)]
                       [config question r])))
        started-at-ms (System/currentTimeMillis)
        _ (t/log! :info [:sweep.runner/start
                         {:configs (count configs)
                          :questions (count questions)
                          :repeats repeats
                          :total total
                          :run-timeout-ms run-timeout-ms
                          :out-dir out-dir}])
        _ (write-progress! out-dir {:total total :completed 0 :status "running"
                                    :started-at-ms started-at-ms :finished-at-ms nil
                                    :current nil})
        rows (atom [])
        io-lock (Object.)
        ;; Serialize all shared-state mutation (CSV writer, rows atom, progress
        ;; file) so the run itself can fan out across `:concurrency` threads.
        record-row! (fn [w row]
                      (locking io-lock
                        (write-csv-row! w row)
                        (let [done (count (swap! rows conj row))]
                          (t/log! :info [:sweep.runner/run-end
                                         {:run-id (:run-id row)
                                          :status (:status row)
                                          :elapsed-ms (:elapsed-ms row)
                                          :answer-hit? (:answer-substring-hit? row)
                                          :recall-10 (:recall-at-10 row)
                                          :progress (str done "/" total)}])
                          (write-progress! out-dir {:total total :completed done :status "running"
                                                    :started-at-ms started-at-ms :finished-at-ms nil
                                                    :current nil}))))
        run-cell! (fn [w [config question r]]
                    (t/log! :info [:sweep.runner/run-begin
                                   {:config-id (:id config)
                                    :question-id (:id question)
                                    :repeat r}])
                    (let [;; Per-config phrases override: lets a single interleaved A/B point
                          ;; one arm at a pruned phrases clone while the other uses the
                          ;; resolved default — the matrix-level scope resolves collections
                          ;; once, so the per-config swap happens here.
                          run-collections (if-let [pc (:phrases-collection-override config)]
                                            (assoc collections :phrases-collection pc)
                                            collections)
                          row (assoc (run-single
                                       {:config config
                                        :question question
                                        :collections run-collections
                                        :execution-scope execution-scope
                                        :max-clarification-rounds max-clarification-rounds
                                        :run-timeout-ms run-timeout-ms
                                        :judge? judge?})
                                     :repeat r
                                     ;; From run-collections, NOT the sweep-level
                                     ;; `collections`: a phrases override varies per
                                     ;; config, so a sweep-level constant would
                                     ;; mis-record the arm that used the clone.
                                     :docs-collection (:docs-collection run-collections)
                                     :chunks-collection (:chunks-collection run-collections)
                                     :phrases-collection (:phrases-collection run-collections)
                                     :typesense-uri ts-uri)]
                      (record-row! w row)
                      row))]
    (with-open [w (io/writer csv-path)]
      (write-csv-header! w)
      (if (<= (long concurrency) 1)
        (doseq [cell cells] (run-cell! w cell))
        ;; Bounded fan-out: at most `concurrency` runs in flight (one LM Studio
        ;; session each). Writes are serialized via `record-row!`'s io-lock.
        (let [sem (java.util.concurrent.Semaphore. (long concurrency))
              futs (mapv (fn [cell]
                           (.acquire sem)
                           (future (try (run-cell! w cell)
                                        (finally (.release sem)))))
                         cells)]
          (doseq [f futs] @f))))
    (t/log! :info [:sweep.runner/done
                   {:out-dir out-dir
                    :rows-written (count @rows)
                    :csv csv-path}])
    (write-progress! out-dir {:total total :completed (count @rows) :status "done"
                              :started-at-ms started-at-ms
                              :finished-at-ms (System/currentTimeMillis) :current nil})
    {:rows @rows
     :out-dir out-dir
     :csv-path csv-path}))

;; =============================================================================
;; Matrix loading
;; =============================================================================

(defn load-matrix
  "Read an EDN matrix file. Expected shape:

     {:configs [{:id \"baseline\" :skill-graph-id :builtin/...
                 :model nil :temperature 0.1 :skill-params {}}
                ...]
      :repeats 1
      :execution-scope {:tenant \"digdir\" :dataset-config-key \"default\"
                        :agent-id \"builtin/agent-rag-agent\"}
      :question-filter {:dataset :public-docs
                        :sources #{:exploratory-rerank :public-docs-smoke}
                        :ids nil  ;; or set of question ids to whitelist
                        :grounding-modes #{:chunks+answer}}}"
  [path]
  (with-open [r (io/reader path)]
    (edn/read (java.io.PushbackReader. r))))

(defn- apply-question-filter
  "Filter the full question fixture by an :ids set, :dataset, :sources,
   :grounding-modes, or :tags. nil filters mean 'allow all'."
  [questions {:keys [ids dataset sources grounding-modes tags]}]
  (cond->> questions
    ids (filter #(contains? (set ids) (:id %)))
    dataset (filter #(= dataset (:dataset %)))
    sources (filter #(contains? (set sources) (:source %)))
    grounding-modes (filter #(contains? (set grounding-modes)
                                        (or (:grounding-mode %) :chunks+answer)))
    tags (filter #(seq (clojure.set/intersection (set tags) (:tags %))))
    :always vec))

(defn run-from-files
  "Convenience entry: read a matrix EDN + the standard question fixture,
   apply the matrix's :question-filter, run, return.

   This is the function the `bb sweep:run` task (or a one-off
   `clojure -X` invocation) would call."
  [{:keys [matrix-path out-dir] :as opts}]
  (let [matrix (load-matrix matrix-path)
        ;; Lever B: a matrix may point at a re-grounded question fixture (long
        ;; goldens remapped to their new sub-chunk ids) via :questions-path.
        all-questions (if-let [qp (:questions-path matrix)]
                        (questions/load-questions! qp)
                        (questions/load-questions!))
        questions (apply-question-filter all-questions (:question-filter matrix))]
    (when (empty? questions)
      (throw (ex-info "run-from-files: question-filter selected zero questions"
                      {:filter (:question-filter matrix)
                       :pool-size (count all-questions)})))
    (run-matrix (merge {:configs (:configs matrix)
                        :questions questions
                        :repeats (:repeats matrix 1)
                        :execution-scope (:execution-scope matrix)
                        :max-clarification-rounds (:max-clarification-rounds matrix 2)
                        :run-timeout-ms (:run-timeout-ms matrix
                                                        default-run-timeout-ms)
                        :judge? (:judge? matrix false)
                        ;; nil when the matrix omits :order, so run-matrix's smart
                        ;; default applies (>=2 configs -> interleaved).
                        :order (:order matrix)
                        ;; Opt-in parallel fan-out; nil → run-matrix defaults to 1
                        ;; (serial). MUST be ≤ the loaded model's --parallel slots.
                        :concurrency (:concurrency matrix)
                        ;; Persisted into matrix.edn so a later `bb sweep-judge`
                        ;; re-loads the SAME question fixture (else it falls back to
                        ;; the default set and skips all rows with unknown ids).
                        :questions-path (:questions-path matrix)
                        :out-dir out-dir}
                       ;; opts override the matrix — e.g. a CLI --repeats N for
                       ;; variance bands without editing the matrix file.
                       (select-keys opts [:out-dir :run-timeout-ms :repeats :judge? :order :concurrency])))))

;; =============================================================================
;; Separate cloud-judge pass
;; =============================================================================
;;
;; The agent and the judge fight over one global flag
;; (services.azure-openai.use-azure-openai-api): the local agent needs it
;; OFF, the trustworthy gpt-5.5 judge needs it ON. Rather than special-case
;; routing inside a single run, we judge in a SECOND pass: run the agent
;; sweep azure-off (judge? false), then flip the config azure-on and call
;; `judge-sweep-dir!` over the produced runs.csv. Two processes, no tension.

(defn- parse-csv
  "Minimal RFC-4180 parser → vector of row-vectors. Handles quoted fields
   with embedded commas/newlines and doubled-quote escapes (the runs.csv
   :response column spans multiple physical lines). Mirrors the dashboard's
   reader so a judged re-read sees exactly what was written."
  [^String s]
  (let [n (count s)]
    (loop [i 0, fb (StringBuilder.), row (transient []), rows (transient []), q false]
      (if (>= i n)
        (persistent! (conj! rows (persistent! (conj! row (.toString fb)))))
        (let [c (.charAt s i)]
          (cond
            q (if (= c \")
                (if (and (< (inc i) n) (= (.charAt s (inc i)) \"))
                  (do (.append fb \") (recur (+ i 2) fb row rows true))
                  (recur (inc i) fb row rows false))
                (do (.append fb c) (recur (inc i) fb row rows true)))
            (= c \") (recur (inc i) fb row rows true)
            (= c \,) (recur (inc i) (StringBuilder.) (conj! row (.toString fb)) rows false)
            (= c \newline) (recur (inc i) (StringBuilder.) (transient [])
                                  (conj! rows (persistent! (conj! row (.toString fb)))) false)
            (= c \return) (recur (inc i) fb row rows false)
            :else (do (.append fb c) (recur (inc i) fb row rows false))))))))

(defn- read-csv-maps
  "Read a CSV file into a vector of header-keyed maps."
  [path]
  (let [rows (remove #(= % [""]) (parse-csv (slurp path)))
        header (mapv keyword (first rows))]
    (mapv #(zipmap header %) (rest rows))))

(defn judge-sweep-dir!
  "Cloud-judge an already-completed sweep dir as a SEPARATE pass. Reads
   `<sweep-dir>/runs.csv`, joins each row's question reference, judges every
   non-empty response with `digdir.sweep.judge` (which routes to cloud
   gpt-5.5 when services.azure-openai.use-azure-openai-api is TRUE — so run
   this with the config flipped azure-on), and writes
   `<sweep-dir>/runs-judged.csv` with the judge columns filled.

   Returns {:judged n :skipped n :out-path str}. Idempotent: a row that
   already carries a clean verdict is left untouched, so the pass can be
   re-run to fill only the gaps.

   `tenant` defaults to the matrix.edn execution-scope tenant; the question
   fixture follows the matrix's optional :questions-path (Lever B)."
  [sweep-dir & {:keys [tenant]}]
  (let [matrix-path (str sweep-dir "/matrix.edn")
        matrix (when (.exists (io/file matrix-path))
                 (try (load-matrix matrix-path) (catch Exception _ nil)))
        tenant (or tenant (get-in matrix [:execution-scope :tenant]) "digdir")
        all-questions (if-let [qp (:questions-path matrix)]
                        (questions/load-questions! qp)
                        (questions/load-questions!))
        q-index (->> (merge-references all-questions)
                     (map (juxt :id identity))
                     (into {}))
        rows (read-csv-maps (str sweep-dir "/runs.csv"))
        clean-verdict? (fn [v] (and (not (str/blank? (str v)))
                                    (not (#{"timeout" "error" "unparseable"} v))))
        judged (atom 0)
        skipped (atom 0)
        truncated (atom 0)
        out-rows
        (mapv (fn [row]
                (let [response (:response row)
                      question (get q-index (:question-id row))]
                  ;; GUARD: never silently grade a clipped answer. Older runs.csv (or any
                  ;; re-introduced cap) stored the response truncated with a trailing "…";
                  ;; judging that fakes "partial" on long answers (the 800-char bug). Warn
                  ;; loudly — such rows must be RE-RUN with the current runner (full text).
                  (when (str/ends-with? (str response) "…")
                    (swap! truncated inc)
                    (t/log! :warn [:sweep.runner/judge-on-truncated-response
                                   {:question-id (:question-id row)
                                    :chars (count (str response))
                                    :note "response ends in '…' — likely clipped at write time; verdict unreliable, re-run to get full text"}]))
                  (cond
                    (clean-verdict? (:answer-judge-verdict row))
                    (do (swap! skipped inc) row)

                    (or (str/blank? (str response)) (nil? question))
                    (do (swap! skipped inc) row)

                    :else
                    (let [cols (judge-run tenant question response)]
                      (swap! judged inc)
                      (when (zero? (mod @judged 10))
                        (t/log! :info [:sweep.runner/judge-pass-progress
                                       {:judged @judged :total (count rows)}]))
                      (merge row cols)))))
              rows)
        out-path (str sweep-dir "/runs-judged.csv")]
    (write-csv! out-path out-rows)
    (when (pos? @truncated)
      (t/log! :warn [:sweep.runner/judge-pass-truncated-inputs
                     {:truncated @truncated :total (count rows)
                      :note "judged responses ending in '…' — verdicts unreliable; re-run these sweeps with the current runner (full response persisted)"}]))
    (t/log! :info [:sweep.runner/judge-pass-done
                   {:judged @judged :skipped @skipped :truncated @truncated :out-path out-path}])
    {:judged @judged :skipped @skipped :truncated @truncated :out-path out-path}))
