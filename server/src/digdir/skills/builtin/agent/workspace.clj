(ns digdir.skills.builtin.agent.workspace
  "Agent workspace management - State, budgeting, and intent inference."
  (:require [digdir.skills.builtin.agent.read-signals :as read-signals]
            [digdir.llm.client :as llm-client]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Workspace State
;; =============================================================================

(defn fresh-workspace
  "Pure: return a freshly-initialized workspace value (no atom).
   Used by graph-runner-friendly callers that thread workspace explicitly."
  []
  {:chunks {}
   :budget-limits nil
   :seen-search-chunk-ids #{}
   :queries []
   :query-intents []
   :last-query-intent nil
   ;; Slice 23: planner's canonical :user-intent. When set, the search tool
   ;; passes it to the retrieval skill which runs an intent-only second pass
   ;; and union-merges the result with the expansion pass.
   :last-user-intent nil
   :search-history []
   :search-errors []
   :backend-issues []
   :search-attributions []
   :read-history []
   :evidence-plan nil
   :claim-coverage {}
   :read-evaluations []
   :pending-eval-chunks []
   :open-evidence-gaps []
   :evidence-contradictions []
   :non-supporting-chunk-ids #{}
   :refunded-chunk-ids #{}
   :last-read-signal nil
   :shadow-sufficiency-decisions []
   :response-validations []
   :last-response-validation-insufficiency nil
   :read-content-length 0
   :stage-timings []
   :sufficiency-decisions []
   :last-insufficiency nil
   :reranked-chunks []
   :context-docs []
   :iteration-history []
   :citations []
   :citation-index {}
   :citation-validation nil
   :last-synthesis-prompts nil
   :last-generate-insufficient-context nil
   :last-generated-explicitly-insufficient? nil
   :last-generated-response nil})

(defn create-workspace
  "Create a fresh per-invocation workspace atom."
  []
  (atom (fresh-workspace)))

;; =============================================================================
;; Budgeting & Accounting
;; =============================================================================

(def ^:private default-max-search-passes 4)
(def ^:private default-max-read-operations 6)
(def ^:private default-max-read-content-length 12000)

(defn resolve-budget-limits
  [{:keys [max-search-passes max-read-operations max-read-content-length]}]
  {:max-search-passes (or max-search-passes default-max-search-passes)
   :max-read-operations (or max-read-operations default-max-read-operations)
   :max-read-content-length (or max-read-content-length default-max-read-content-length)})

(def budget-skill-param-keys
  "Budget knobs a caller may override via `:skill-params [:builtin/agent ...]`."
  [:max-search-passes :max-read-operations :max-read-content-length])

(defn resolve-budget-limits-from-skill-params
  "Like `resolve-budget-limits`, but lets a caller override the budget through
   the agent's `:skill-params` `:builtin/agent` map — the only channel the
   sweep runner / invoke-rag can reach (the graph's `parameters` is internal).
   skill-params win over `parameters` per present key; absent keys fall through
   to the defaults. Mirrors the `:system-prompt` hand-off in invoke.clj."
  [parameters skill-params]
  (resolve-budget-limits
   (merge parameters
          (select-keys (:builtin/agent skill-params) budget-skill-param-keys))))

(defn budget-state
  [workspace]
  (let [{:keys [max-search-passes max-read-operations max-read-content-length]}
        (or (:budget-limits workspace) {})
        search-used (count (or (:search-history workspace) []))
        read-used (count (or (:read-history workspace) []))
        read-content-used (or (:read-content-length workspace) 0)
        search-remaining (max 0 (- (or max-search-passes default-max-search-passes) search-used))
        read-remaining (max 0 (- (or max-read-operations default-max-read-operations) read-used))
        read-content-remaining (max 0 (- (or max-read-content-length default-max-read-content-length)
                                         read-content-used))]
    {:max-search-passes (or max-search-passes default-max-search-passes)
     :max-read-operations (or max-read-operations default-max-read-operations)
     :max-read-content-length (or max-read-content-length default-max-read-content-length)
     :search-passes-used search-used
     :search-passes-remaining search-remaining
     :read-operations-used read-used
     :read-operations-remaining read-remaining
     :read-content-length-used read-content-used
     :read-content-length-remaining read-content-remaining
     :search-budget-exhausted? (zero? search-remaining)
     :read-operations-exhausted? (zero? read-remaining)
     :read-content-budget-exhausted? (zero? read-content-remaining)
     :low-search-budget? (<= search-remaining 1)
     :low-read-budget? (or (<= read-remaining 1)
                           (<= read-content-remaining 2000))}))

;; =============================================================================
;; Chunk Management
;; =============================================================================

(defn add-chunks-to-workspace
  "Pure: merge new chunks (deduped by :chunk_id) into workspace and return it."
  [workspace chunks]
  (let [existing-ids (set (keys (:chunks workspace)))
        new-chunks (remove #(contains? existing-ids (:chunk_id %)) chunks)]
    (if (seq new-chunks)
      (update workspace :chunks
              merge (zipmap (map :chunk_id new-chunks) new-chunks))
      workspace)))

(defn count-new-chunks
  "Pure: how many of `chunks` are not yet in `workspace` (by :chunk_id)."
  [workspace chunks]
  (let [existing-ids (set (keys (:chunks workspace)))]
    (count (remove #(contains? existing-ids (:chunk_id %)) chunks))))

(defn add-chunks-to-workspace!
  "Atom shim: add chunks to workspace atom and return count of newly added."
  [!workspace chunks]
  (let [new-count (count-new-chunks @!workspace chunks)]
    (swap! !workspace add-chunks-to-workspace chunks)
    new-count))

(defn workspace-chunks
  "Pure: chunks in `workspace` sorted by retrieval priority. Most relevant
   first so the reranker's top-k window covers them."
  [workspace]
  (->> (vals (:chunks workspace))
       (sort-by (fn [chunk]
                  [(- (or (:retrieval-prior chunk) 0))
                   (- (or (:hit-count chunk) 0))
                   (- (or (:original-rank chunk) 0))])
                compare)
       vec))

(defn get-workspace-chunks
  "Atom shim of `workspace-chunks`."
  [!workspace]
  (workspace-chunks @!workspace))

(defn joined-document
  "Pure: the document a chunk was joined against, or nil.

   Typesense returns the join under a key named after the DOCS COLLECTION
   (`$<docs-collection>(url,title,total_chunks,…)` in `digdir.rag.retrieval`),
   so the key varies per dataset and cannot be looked up by name. Identified
   structurally instead — the same way `agent/core`, `agent/tools` and
   `api/routes/endpoints/openai-compat` each already identify it."
  [chunk]
  (some (fn [v]
          (when (and (map? v)
                     (or (contains? v :title)
                         (contains? v :url)
                         (contains? v :total_chunks)))
            v))
        (vals chunk)))

(defn chunk-for-output
  "Pure: one chunk in the shape a graph's `:chunks` OUTPUT promises — the
   joined document's `:title`, `:url` and `:total_chunks` lifted to the top
   level, where the MCP output schema and every downstream consumer read them.

   Lifting rather than replacing: the nested join stays, because consumers that
   scan for it structurally still do (#460). A blank `:url` is not lifted, so a
   document without one is indistinguishable from one whose join was absent —
   which is what `chunk->resource-link`'s `digdir://doc/<n>` fallback expects."
  [chunk]
  (let [doc (joined-document chunk)]
    (cond-> chunk
      (not-empty (:title doc))  (assoc :title (:title doc))
      (not-empty (:url doc))    (assoc :url (:url doc))
      (:total_chunks doc)       (assoc :total_chunks (:total_chunks doc)))))

(defn chunks-for-output
  "Pure: the workspace's chunks as the VECTOR a graph emits on `:chunks`.

   THE SEAM. `:chunks` is stored as a MAP keyed by `chunk_id` (dedup, see
   `add-chunks-to-workspace`) and published as a VECTOR. Handing the internal
   map to a consumer is what produced `structuredContent.chunks = [{} {}]` —
   `mapv select-keys` over a map iterates `MapEntry`, and `select-keys` on one
   returns `{}` (#460). Every producer of a `:chunks` output goes through here
   so the published shape is decided in one place rather than tolerated in
   several."
  [workspace]
  (mapv chunk-for-output (workspace-chunks workspace)))

(defn record-turn
  "Pure: append an iteration-history entry capturing reasoning + tool calls,
   plus a budget snapshot for per-iteration visibility."
  [workspace iteration reasoning tool-call-results]
  (update workspace :iteration-history conj
          {:iteration iteration
           :reasoning reasoning
           :tool-calls tool-call-results
           :budget-snapshot (budget-state workspace)}))

(defn record-turn!
  [!workspace iteration reasoning tool-call-results]
  (swap! !workspace record-turn iteration reasoning tool-call-results)
  nil)

(def llm-backed-stages
  "Stages whose execution makes an LLM call and must therefore report `:usage`.

   Lives HERE, at the recorder, rather than beside the tool dispatch that
   produces the value — see `normalize-stage-timing-entry`. `tools.clj` refers
   to this var rather than keeping its own copy, so there is ONE list and not a
   second one to drift (#25, #323).

   Still a CLAIM and still able to go stale: a tool that starts calling an LLM,
   or a new tool falling through `tool-stage-info` into `:unknown`, will not be
   here. That is why it drives only the ABSENCE detector and never the
   classification — `stage-duration-totals` buckets on `(some? :usage)`, which
   needs no list."
  #{:agent-llm :agent-final-llm :read-signal-eval :citation-backfill-synthesis
    :plan_queries :generate_response
    ;; Added after the two-lane reconciliation of the eight LLM stages. Both
    ;; call sufficiency/evaluate-sufficiency with an :llm-fn — iteration_bundled
    ;; :sufficiency-gate and :response-validation — and both were absent from
    ;; this set, so they were UNCLAIMED and therefore invisible to the sentinel:
    ;; they landed in other-ms silently rather than being reported as missing.
    ;; The hand-maintained-claim limitation, biting exactly as documented.
    :sufficiency-gate :response-validation})
;; NOTE: :summarize deliberately NOT here. "summarize" appears once in
;; tools.clj — the tool-stage-info case entry — with no tool definition behind
;; it, so the model is never offered it and the stage cannot fire. Claiming it
;; would be claiming a stage that does not exist.

(defn- normalize-stage-timing-entry
  "Pure: collapse a raw timing map down to the set of keys we persist."
  [timing]
  (cond-> {:stage (:stage timing)
           :duration-ms (:duration-ms timing)
           ;; THE CLAIM LIVES HERE, NOT IN THE ENVELOPE (#25).
           ;;
           ;; It used to ride beside :usage in the tool envelope, so the
           ;; allowlist that dropped the VALUE dropped the CLAIM too — and a
           ;; sentinel with no claim reports nothing wrong. A guard that rides
           ;; on the thing it guards cannot detect that thing's loss.
           ;;
           ;; Derived from the STAGE KEYWORD at the one point every timing must
           ;; pass through (this fn is the only writer of :stage-timings). For
           ;; claim and value to be lost together now, `:stage` itself would
           ;; have to go missing — and then the row has no stage name at all,
           ;; which :other-stages shows as blank rather than as fine.
           ;; An explicit :usage-expected? from a caller still wins.
           :usage-expected? (boolean (or (:usage-expected? timing)
                                         (contains? llm-backed-stages (:stage timing))))}
    (contains? timing :iteration) (assoc :iteration (:iteration timing))
    (contains? timing :tool) (assoc :tool (:tool timing))
    (contains? timing :sub-skill) (assoc :sub-skill (:sub-skill timing))
    (contains? timing :status) (assoc :status (:status timing))
    (contains? timing :detail) (assoc :detail (:detail timing))
    (contains? timing :input-length) (assoc :input-length (:input-length timing))
    (contains? timing :output-length) (assoc :output-length (:output-length timing))
    (contains? timing :result-count) (assoc :result-count (:result-count timing))
    (contains? timing :usage) (assoc :usage (:usage timing))
    ;; #25: these two are what make a MISSING :usage legible. This fn is an
    ;; ALLOWLIST — a key not named here is dropped silently, so a collector
    ;; wired without them would report fine and measure nothing, which is the
    ;; exact failure the sentinel exists to catch.
    (contains? timing :usage-writes) (assoc :usage-writes (:usage-writes timing))
    (contains? timing :usage-expected?) (assoc :usage-expected? (:usage-expected? timing))
    (contains? timing :llm-model) (assoc :llm-model (:llm-model timing))
    (contains? timing :finish-reason) (assoc :finish-reason (:finish-reason timing))))

(defn record-stage-timing
  "Pure: append a coarse-grained execution timing for a trace-visible stage."
  [workspace timing]
  (update workspace :stage-timings (fnil conj []) (normalize-stage-timing-entry timing)))

(defn record-stage-timing!
  [!workspace timing]
  (swap! !workspace record-stage-timing timing)
  nil)

(defn chunk-content-length
  "Best-effort content length for accounting and budgeting."
  [chunk]
  (or (:content_length chunk)
      (some-> (:content_markdown chunk) count)
      0))

;; =============================================================================
;; Search & Read History
;; =============================================================================

(defn- summarize-read-source
  "Summarize how the read tool was invoked for workspace traceability."
  [{:keys [chunk-ids doc-num chunk-range default-range?]}]
  (cond
    (seq chunk-ids)
    {:mode :chunk-ids
     :chunk-ids (vec chunk-ids)}

    (and doc-num chunk-range)
    {:mode :doc-range
     :doc-num doc-num
     :chunk-range chunk-range
     :default-range? (boolean default-range?)}

    :else
    {:mode :empty}))

(defn- search-chunk-summary
  "Pure: shape a raw search chunk into the summary persisted in :chunk-summaries."
  [chunk]
  (let [doc-ref (some (fn [v]
                        (when (and (map? v)
                                   (or (contains? v :title)
                                       (contains? v :total_chunks)))
                          v))
                      (vals chunk))]
    (cond-> {:chunk-id (:chunk_id chunk)
             :doc-num (:doc_num chunk)
             :chunk-index (:chunk_index chunk)
             :total-chunks (:total-chunks doc-ref)
             :content-length (:content_length chunk)
             :title (:title doc-ref)
             :headers (:metadata chunk)
             :search-types (:search-types chunk)
             ;; Ranking-score decomposition — lets the sweep see WHICH score sets a
             ;; chunk's final position. :rerank-score is the ACTUAL sort key for the
             ;; top rerank-candidate-k (~40); past that window chunks retain lexical
             ;; order set by :retrieval-prior (= hit-count promiscuity reward +
             ;; original-rank base relevance + the per-feature boost magnitudes).
             ;; Capturing both disentangles a reranker miss (golden in-window but
             ;; scored low) from a lexical-gate miss (golden never entered the window).
             :rerank-score (:rerank-score chunk)
             :retrieval-prior (:retrieval-prior chunk)
             :hit-count (:hit-count chunk)
             :original-rank (:original-rank chunk)
             :retrieval-boosts (:retrieval-boosts chunk)}
      (seq (:matched-questions chunk))
      (assoc :matched-questions (:matched-questions chunk)))))

(defn record-search
  "Pure: append a search entry plus update seen-chunk and query indices."
  [workspace {:keys [queries filter-by chunks new-count attribution fallback?]}]
  (let [chunk-ids (mapv :chunk_id chunks)
        chunk-summaries (mapv search-chunk-summary chunks)
        entry {:queries (vec (or queries []))
               :filter-by filter-by
               :result-count (count chunks)
               :new-count new-count
               :chunk-ids chunk-ids
               :chunk-summaries chunk-summaries
               :fallback? (boolean fallback?)
               :attribution attribution}]
    (cond-> (-> workspace
                (update :search-history conj entry)
                (update :seen-search-chunk-ids into chunk-ids)
                (update :queries
                        (fn [existing]
                          (vec (distinct (concat existing (or queries [])))))))
      attribution
      (update :search-attributions (fnil conj []) attribution))))

(defn record-search!
  [!workspace args]
  (swap! !workspace record-search args)
  nil)

(defn record-search-error
  "Pure: append a search-error entry plus a corresponding backend-issue entry."
  [workspace {:keys [queries filter-by error fallback?]}]
  (let [entry {:queries (vec (or queries []))
               :filter-by filter-by
               :fallback? (boolean fallback?)
               :error-type (:error-type error)
               :error-message (:error-message error)
               :error-data (:error-data error)}]
    (-> workspace
        (update :search-errors conj entry)
        (update :backend-issues conj
                {:source :search
                 :tool "search"
                 :issue-type (:error-type error)
                 :message (:error-message error)
                 :queries (:queries entry)
                 :filter-by filter-by
                 :fallback? (boolean fallback?)
                 :details (:error-data error)}))))

(defn record-search-error!
  [!workspace args]
  (swap! !workspace record-search-error args)
  nil)

(defn record-backend-issue
  "Pure: append a backend/tool issue separate from zero-hit searches."
  [workspace issue]
  (update workspace :backend-issues conj
          (-> {:source (:source issue)
               :tool (:tool issue)
               :issue-type (:issue-type issue)
               :message (:message issue)
               :details (:details issue)}
              (cond-> (contains? issue :queries) (assoc :queries (vec (or (:queries issue) [])))
                      (contains? issue :filter-by) (assoc :filter-by (:filter-by issue))
                      (contains? issue :fallback?) (assoc :fallback? (boolean (:fallback? issue)))))))

(defn record-backend-issue!
  [!workspace issue]
  (swap! !workspace record-backend-issue issue)
  nil)

(defn record-read
  "Pure: append a read-history entry and grow :read-content-length."
  [workspace source chunks]
  (let [returned-chunk-ids (mapv :chunk_id chunks)
        content-length-sum (reduce + (map chunk-content-length chunks))
        entry (cond-> (assoc (summarize-read-source source)
                             :returned-count (count chunks)
                             :returned-chunk-ids returned-chunk-ids
                             :content-length content-length-sum)
                (:max-content-length source)
                (assoc :max-content-length (:max-content-length source)))]
    (-> workspace
        (update :read-history conj entry)
        (update :read-content-length (fnil + 0) content-length-sum))))

(defn record-read!
  [!workspace source chunks]
  (swap! !workspace record-read source chunks)
  nil)

(defn ensure-evidence-plan
  "Pure: install an evidence plan if one isn't already cached."
  [workspace query query-intent]
  (if (:evidence-plan workspace)
    workspace
    (assoc workspace :evidence-plan (read-signals/build-evidence-plan query query-intent))))

(defn ensure-evidence-plan!
  [!workspace query query-intent]
  (swap! !workspace ensure-evidence-plan query query-intent))

(defn queue-pending-eval-chunks
  "Pure: enqueue chunks for end-of-iteration read-signal evaluation."
  [workspace chunks]
  (if (seq chunks)
    (update workspace :pending-eval-chunks (fnil into []) chunks)
    workspace))

(defn queue-pending-eval-chunks!
  [!workspace chunks]
  (swap! !workspace queue-pending-eval-chunks chunks)
  nil)

(defn drain-pending-eval-chunks
  "Pure: return [pending-chunks, workspace-with-pending-cleared]."
  [workspace]
  [(or (:pending-eval-chunks workspace) [])
   (assoc workspace :pending-eval-chunks [])])

(defn drain-pending-eval-chunks!
  "Atom shim: clear the pending queue and return its contents.
   Single-writer per-invocation; the read-modify-write is uncontested."
  [!workspace]
  (let [[pending workspace'] (drain-pending-eval-chunks @!workspace)]
    (reset! !workspace workspace')
    pending))

(declare record-read-evaluation record-read-evaluation!
         aggregate-read-signals
         record-shadow-sufficiency-decision record-shadow-sufficiency-decision!
         record-stage-timing! infer-query-intent)

(defn- compute-read-signal
  "Side-effecting: run the read-signal evaluator (calls the LLM) and return the
   signal annotated with the chunk-ids it covered. Separated so that the
   workspace-update half can stay pure."
  [workspace ambient-ctx active-query pending]
  (let [conversation-history (:conversation-history ambient-ctx)
        opts (:opts ambient-ctx)
        query-intent (or (:last-query-intent workspace)
                         (infer-query-intent active-query conversation-history))
        evidence-plan (:evidence-plan workspace)
        open-claims (or (:open-evidence-gaps workspace)
                        (:required-claims evidence-plan)
                        [])]
    (assoc (read-signals/evaluate-read active-query
                                       query-intent
                                       evidence-plan
                                       open-claims
                                       pending
                                       {:llm-fn (:read-signals-llm-fn opts)
                                        :model (:read-signals-model opts)
                                        :temperature (:read-signals-temperature opts)
                                        :tenant (:tenant opts)})
           :chunk-ids (mapv :chunk_id pending))))

(defn apply-pending-eval-result
  "Pure: given a read-signal and timing, apply the read-evaluation, shadow
   sufficiency, and stage-timing updates to `workspace`. The corresponding
   `evaluate-pending-reads` orchestrator drains pending chunks and computes
   the signal; this fn handles the workspace transformations."
  [workspace read-signal iteration eval-duration-ms pending-count captured]
  (let [budget-limits (:budget-limits workspace)
        max-read-content-length (or (:max-read-content-length budget-limits) 0)
        read-len-before (:read-content-length workspace)
        workspace (record-read-evaluation workspace read-signal)
        read-len-after (:read-content-length workspace)
        shadow-decision (aggregate-read-signals workspace)
        workspace (record-shadow-sufficiency-decision workspace shadow-decision)
        workspace (record-stage-timing
                    workspace
                    ;; #25: this span calls the read-signal LLM, so a missing
                    ;; :usage here is an inconsistency rather than an absence.
                    ;; EXACTLY ONE LLM call: read-signals/evaluate-read ->
                    ;; se/evaluate, which invokes llm-fn once. This comment
                    ;; previously claimed TWO, on the theory that
                    ;; infer-query-intent fires in the same span — it does fire,
                    ;; but it is PURE HEURISTIC (workspace.clj:1668: answer-type,
                    ;; target-entity, target-year, target-metric, doc-family,
                    ;; scope-signals — string and regex, no LLM).
                    ;; So :usage-writes 2 here IS a double-write and must be
                    ;; investigated, not waved through. The old comment
                    ;; pre-authorised the exact anomaly the sentinel exists to
                    ;; catch, in the direction that flatters the fix.
                    (merge {:stage :read-signal-eval
                            :iteration iteration
                            :sub-skill :builtin/read-signals
                            :duration-ms eval-duration-ms
                            :status :ok
                            :usage-expected? true
                            :detail (str "chunks=" pending-count)}
                           (llm-client/usage-summary captured)))]
    (cond-> workspace
      (> read-len-before read-len-after)
      (record-stage-timing
        {:stage :budget-refund
         :iteration iteration
         :duration-ms 0
         :status :ok
         :detail (str "chars=" (- read-len-before read-len-after)
                      " read=" read-len-before
                      " remaining=" (- max-read-content-length read-len-after))}))))

(defn evaluate-pending-reads
  "Pure-shape orchestrator: drain pending chunks, call the read-signal LLM,
   and return the workspace with all bookkeeping applied. The LLM call is a
   side effect; everything else is a pure transform of workspace."
  [workspace ambient-ctx iteration active-query]
  (let [[pending workspace] (drain-pending-eval-chunks workspace)]
    (if (empty? pending)
      workspace
      (let [eval-start (System/currentTimeMillis)
            ;; #25: compute-read-signal makes ONE LLM call (evaluate-read; the
            ;; infer-query-intent it may also call is pure heuristic), but
            ;; structured-eval's envelope
            ;; deliberately "adds no implicit state to the result", so usage is
            ;; discarded inside it. Capture it ambiently instead of changing
            ;; that contract for every se/evaluate caller.
            captured (llm-client/capture-usage
                       #(compute-read-signal workspace ambient-ctx active-query pending))
            eval-duration-ms (- (System/currentTimeMillis) eval-start)]
        (apply-pending-eval-result workspace (:result captured) iteration
                                   eval-duration-ms (count pending) captured)))))

(defn evaluate-pending-reads!
  "Atom shim. Uses reset! (not swap!) because the LLM call inside
   `evaluate-pending-reads` cannot be retried; single-writer per-invocation."
  [!workspace ambient-ctx iteration active-query]
  (reset! !workspace (evaluate-pending-reads @!workspace ambient-ctx iteration active-query))
  nil)

(defn- merge-claim-support
  [existing support]
  (let [existing-ids (set (:chunk-ids existing))
        new-ids (remove existing-ids (:chunk-ids support))
        existing-level (:support-level existing)
        new-level (:support-level support)
        merged-level (cond
                       (= :explicit existing-level) :explicit
                       (= :explicit new-level) :explicit
                       :else (or new-level existing-level))]
    (-> (or existing {})
        (assoc :claim-id (:claim-id support))
        (assoc :support-level merged-level)
        (update :chunk-ids (fnil into []) new-ids))))

(defn compute-budget-refund
  "Return {:refunded-ids [...] :refunded-chars N} for chunks read this
   round that were not referenced by any supported-claim, subject to
   signal-quality gating and idempotency."
  [workspace read-signal]
  (let [confidence (or (:confidence read-signal) 0.0)
        degraded? (boolean (:degraded? read-signal))
        status (:status read-signal)
        already-refunded (or (:refunded-chunk-ids workspace) #{})
        eligible? (and (>= confidence 0.5)
                       (not degraded?)
                       (not= :unclear status))
        read-ids (set (or (:chunk-ids read-signal) []))
        supported-ids (->> (or (:supported-claims read-signal) [])
                           (mapcat :chunk-ids)
                           set)
        refund-candidates (when eligible?
                            (->> read-ids
                                 (remove supported-ids)
                                 (remove already-refunded)
                                 vec))
        chunks-map (:chunks workspace)
        refunded-chars (reduce (fn [acc id]
                                 (+ acc (or (chunk-content-length (get chunks-map id)) 0)))
                               0
                               (or refund-candidates []))]
    {:refunded-ids (vec (or refund-candidates []))
     :refunded-chars refunded-chars}))

(defn record-read-evaluation
  "Pure: integrate a read-signal into workspace — append it, update claim
   coverage, open-gaps, non-supporting chunks, and apply any budget refund."
  [workspace read-signal]
  (let [supported (or (:supported-claims read-signal) [])
        supported-chunk-ids (->> supported
                                 (mapcat :chunk-ids)
                                 set)
        zero-support? (empty? supported)
        contradiction-free? (empty? (or (:contradictions read-signal) []))
        ;; Only mark chunks non-supporting when the local evaluator is
        ;; confidently scoped — :unclear/:ambiguous signals shouldn't
        ;; permanently disqualify chunks the agent might want to reread
        ;; after rephrasing the query.
        confidently-scoped? (and (= :aligned (:scope-assessment read-signal))
                                 (not= :unclear (:status read-signal)))
        non-supporting-chunk-ids (if (and (not (:degraded? read-signal))
                                          confidently-scoped?
                                          zero-support?
                                          contradiction-free?)
                                   (set (or (:chunk-ids read-signal) []))
                                   #{})
        coverage (reduce (fn [acc support]
                           (update acc (:claim-id support) merge-claim-support support))
                         (or (:claim-coverage workspace) {})
                         supported)
        contradictions (vec (concat (or (:evidence-contradictions workspace) [])
                                    (or (:contradictions read-signal) [])))
        supported-ids (set (keys coverage))
        evidence-plan (or (:evidence-plan workspace) {})
        gap-by-id (into {}
                        (map (juxt :claim-id identity))
                        (or (:remaining-gaps read-signal) []))
        open-gaps (->> (or (:required-claims evidence-plan) [])
                       (remove #(contains? supported-ids (:claim-id %)))
                       (mapv (fn [{:keys [claim-id critical? text]}]
                               (merge {:claim-id claim-id
                                       :critical? (boolean critical?)
                                       :critical (boolean critical?)
                                       :text text
                                       :reason :not-yet-supported}
                                      (get gap-by-id claim-id)))))
        {:keys [refunded-ids refunded-chars]} (compute-budget-refund workspace read-signal)]
    (-> workspace
        (update :read-evaluations conj read-signal)
        (assoc :last-read-signal read-signal)
        (assoc :claim-coverage coverage)
        (assoc :open-evidence-gaps open-gaps)
        (assoc :evidence-contradictions contradictions)
        (update :non-supporting-chunk-ids
                (fn [existing]
                  (reduce disj
                          (into (or existing #{}) non-supporting-chunk-ids)
                          supported-chunk-ids)))
        (update :refunded-chunk-ids (fnil into #{}) refunded-ids)
        (update :read-content-length #(max 0 (- (or % 0) refunded-chars))))))

(defn record-read-evaluation!
  [!workspace read-signal]
  (swap! !workspace record-read-evaluation read-signal)
  nil)

(defn suppress-reread-chunk-ids
  [workspace chunk-ids]
  (let [blocked-ids (or (:non-supporting-chunk-ids workspace) #{})
        blocked? (set blocked-ids)
        filtered (remove blocked? chunk-ids)
        skipped (filter blocked? chunk-ids)]
    {:selected-chunk-ids (vec filtered)
     :skipped-non-supporting-chunk-ids (vec skipped)}))

(defn read-chunk-id-set
  "Chunk IDs that have already been read into full-content workspace state."
  [workspace]
  (->> (:read-history workspace)
       (mapcat :returned-chunk-ids)
       set))

;; =============================================================================
;; Read Suggestions & Logic
;; =============================================================================

(defn- chunk-doc-info
  "Extract the nested doc-ref that holds title/url/total_chunks from a raw
   chunk map. Returns nil if no such sub-map is present."
  [chunk]
  (when (map? chunk)
    (some (fn [v]
            (when (and (map? v)
                       (or (contains? v :title)
                           (contains? v :total_chunks)))
              v))
          (vals chunk))))

(defn range-read-suggestion
  "If the most recent read_chunks call was in chunk-ids mode and landed on
   a multi-chunk document that has not yet been range-read and still has
   unread adjacent chunks in workspace, return a concrete range-read
   suggestion. Otherwise nil.

   Shape: {:doc-num, :title, :url, :total-chunks, :read-indices,
           :unread-indices, :chunk-range {:from, :to}, :triggering-chunk-id}

   Triggered only when a gap-remaining signal is in play — that gating
   decision is the caller's responsibility. This fn stays pure."
  [workspace]
  (let [last-read (last (:read-history workspace))]
    (when (and last-read (= :chunk-ids (:mode last-read)))
      (let [returned-ids (or (:returned-chunk-ids last-read)
                             (:chunk-ids last-read))
            chunks-map (:chunks workspace)
            just-read (keep #(get chunks-map %) returned-ids)
            range-read-docs (->> (:read-history workspace)
                                 (filter #(= :doc-range (:mode %)))
                                 (map :doc-num)
                                 set)
            candidates (->> just-read
                            (keep (fn [chunk]
                                    (let [doc-num (:doc_num chunk)
                                          doc-info (chunk-doc-info chunk)
                                          total (:total_chunks doc-info)]
                                      (when (and doc-num
                                                 (integer? total)
                                                 (> total 1)
                                                 (not (contains? range-read-docs doc-num)))
                                        {:doc-num doc-num
                                         :total-chunks total
                                         :title (:title doc-info)
                                         :url (:url doc-info)
                                         :chunk-id (:chunk_id chunk)}))))
                            (group-by :doc-num))]
        (some (fn [[doc-num entries]]
                (let [total (:total-chunks (first entries))
                      read-indices (->> (vals chunks-map)
                                        (filter #(= doc-num (:doc_num %)))
                                        (keep :chunk_index)
                                        set)
                      unread-indices (->> (range total)
                                          (remove read-indices)
                                          vec)]
                  (when (seq unread-indices)
                    {:doc-num doc-num
                     :title (:title (first entries))
                     :url (:url (first entries))
                     :total-chunks total
                     :read-indices (vec (sort read-indices))
                     :unread-indices unread-indices
                     :chunk-range {:from 0 :to (dec total)}
                     :triggering-chunk-id (:chunk-id (first entries))})))
              candidates)))))

(defn range-read-hint-text
  "Build a tool-result appendix that nudges the agent toward a range read
   on a topically-live doc. The hint includes a copy-pasteable tool-call
   JSON payload so the LLM only has to echo it."
  [{:keys [doc-num title total-chunks read-indices unread-indices chunk-range]}]
  (str "\n\n[SYSTEM: The chunks you just read came from doc_num=\""
       doc-num "\""
       (when title (str " (title=\"" title "\", total_chunks=" total-chunks ")"))
       ". You have read chunk_index(es) " read-indices
       " from this doc but chunk_index(es) " unread-indices
       " are still unread in the SAME doc."
       " The answer is often in an adjacent chunk — before issuing any new"
       " search, call read_chunks with these exact args: {\"doc_num\": \""
       doc-num "\", \"chunk_range\": {\"from\": " (:from chunk-range)
       ", \"to\": " (:to chunk-range) "}}.]"))

(defn next-read-suggestions
  "Build actionable read suggestions from the latest search metadata."
  [workspace]
  (let [read-ids (read-chunk-id-set workspace)
        search-entry (last (:search-history workspace))
        unread-summaries (->> (:chunk-summaries search-entry)
                              (remove #(contains? read-ids (:chunk-id %)))
                              vec)
        chunk-id-suggestion (->> unread-summaries
                                 (take 3)
                                 (mapv :chunk-id))
        range-suggestion (some (fn [{:keys [doc-num chunk-index total-chunks]}]
                                 (when (and doc-num
                                            (integer? chunk-index)
                                            (or (nil? total-chunks) (> total-chunks 1)))
                                   (let [from (max 0 (dec chunk-index))
                                         to (if (integer? total-chunks)
                                              (min (dec total-chunks) (inc chunk-index))
                                              (inc chunk-index))]
                                     {:doc-num doc-num
                                      :chunk-range {:from from :to to}})))
                               unread-summaries)]
    {:unread-count (count unread-summaries)
     :chunk-ids chunk-id-suggestion
     :range range-suggestion}))

(def ^:private default-max-range-width 3)
(def ^:private low-budget-max-range-width 2)
(def ^:private broad-range-threshold 5)

(defn- normalize-chunk-range
  [chunk-range]
  (let [from-idx (max 0 (int (or (:from chunk-range) 0)))
        to-idx (max from-idx (int (or (:to chunk-range) (+ from-idx 4))))]
    {:from from-idx :to to-idx}))

(defn- range-width
  [{:keys [from to]}]
  (inc (- to from)))

(defn- clamp-range-around-anchor
  [anchor-index {:keys [from to]} max-width]
  (let [max-width (max 1 max-width)
        half-width (quot (dec max-width) 2)
        tentative-from (max from (- anchor-index half-width))
        tentative-to (min to (+ tentative-from (dec max-width)))
        adjusted-from (max from (- tentative-to (dec max-width)))]
    {:from adjusted-from
     :to tentative-to}))

(defn- preferred-range-anchor
  [workspace target-doc-num {:keys [from to]}]
  (let [read-ids (read-chunk-id-set workspace)
        search-entry (last (:search-history workspace))
        chunk-summaries (or (:chunk-summaries search-entry) [])]
    (some (fn [{:keys [chunk-id doc-num chunk-index]}]
            (when (and (= target-doc-num doc-num)
                       (integer? chunk-index)
                       (<= from chunk-index to)
                       (not (contains? read-ids chunk-id)))
              chunk-index))
          chunk-summaries)))

(def ^:private fallback-estimated-chunk-length 1500)

(defn- estimated-range-chunk-length
  [workspace target-doc-num {:keys [from to]}]
  (let [search-entry (last (:search-history workspace))
        read-ids (read-chunk-id-set workspace)
        matching-lengths (->> (or (:chunk-summaries search-entry) [])
                              (filter (fn [{:keys [chunk-id doc-num chunk-index content-length]}]
                                        (and (= target-doc-num doc-num)
                                             (integer? chunk-index)
                                             (<= from chunk-index to)
                                             (not (contains? read-ids chunk-id))
                                             (pos? (or content-length 0)))))
                              (map :content-length)
                              vec)
        history-lengths (->> (:read-history workspace)
                             (keep (fn [{:keys [returned-count content-length]}]
                                     (when (and (pos? (or returned-count 0))
                                                (pos? (or content-length 0)))
                                       (long (Math/ceil (/ content-length returned-count))))))
                             vec)
        samples (if (seq matching-lengths) matching-lengths history-lengths)]
    (if (seq samples)
      (long (Math/ceil (/ (reduce + samples) (count samples))))
      fallback-estimated-chunk-length)))

(defn- estimated-range-index-length
  [workspace target-doc-num target-chunk-index]
  (let [read-ids (read-chunk-id-set workspace)]
    (or (some (fn [entry]
                (some (fn [{:keys [chunk-id doc-num chunk-index content-length]}]
                        (when (and (= target-doc-num doc-num)
                                   (= target-chunk-index chunk-index)
                                   (not (contains? read-ids chunk-id))
                                   (pos? (or content-length 0)))
                          content-length))
                      (or (:chunk-summaries entry) [])))
              (reverse (:search-history workspace)))
        (estimated-range-chunk-length workspace target-doc-num {:from target-chunk-index
                                                                :to target-chunk-index}))))

(defn- budget-adjusted-range-width
  [workspace doc-num chunk-range budget base-width]
  (if-let [remaining (:read-content-length-remaining budget)]
    (if (pos? remaining)
      (let [estimated-length (max 1 (estimated-range-chunk-length workspace doc-num chunk-range))
            budget-width (max 1 (quot remaining estimated-length))]
        (min base-width budget-width))
      1)
    base-width))

(defn- candidate-read-range
  "Prefer a small local window around the best unread anchor inside a requested range."
  [workspace doc-num chunk-range budget]
  (let [normalized (normalize-chunk-range chunk-range)
        base-width (if (:low-read-budget? budget)
                     low-budget-max-range-width
                     default-max-range-width)
        max-width (budget-adjusted-range-width workspace doc-num normalized budget base-width)
        candidate-range (if (<= (range-width normalized) broad-range-threshold)
                          normalized
                          (let [anchor (or (preferred-range-anchor workspace doc-num normalized)
                                           (:from normalized))]
                            (clamp-range-around-anchor anchor normalized max-width)))]
    candidate-range))

(defn- effective-read-range
  [workspace doc-num chunk-range budget]
  (let [candidate-range (candidate-read-range workspace doc-num chunk-range budget)]
    (if-let [remaining (:read-content-length-remaining budget)]
      (if (pos? remaining)
        (let [anchor (or (preferred-range-anchor workspace doc-num candidate-range)
                         (:from candidate-range))
              affordable-range
              (->> (for [start (range (:from candidate-range) (inc anchor))
                         end (range anchor (inc (:to candidate-range)))]
                     (let [total-length (reduce + (map (fn [idx]
                                                         (max 1 (estimated-range-index-length workspace doc-num idx)))
                                                       (range start (inc end))))]
                       {:range {:from start :to end}
                        :width (inc (- end start))
                        :distance (+ (Math/abs (long (- anchor start)))
                                     (Math/abs (long (- end anchor))))
                        :total-length total-length}))
                   (filter #(<= (:total-length %) remaining))
                   (sort-by (juxt (comp - :width) :distance :total-length))
                   first)]
          (:range affordable-range))
        nil)
      candidate-range)))

(defn plan-range-read
  [workspace doc-num chunk-range budget]
  (let [requested-range (normalize-chunk-range chunk-range)
        candidate-range (candidate-read-range workspace doc-num requested-range budget)
        selected-range (effective-read-range workspace doc-num requested-range budget)
        requested-count (range-width requested-range)
        candidate-count (range-width candidate-range)
        selected-count (if selected-range (range-width selected-range) 0)]
    {:requested-range requested-range
     :candidate-range candidate-range
     :selected-range selected-range
     :heuristic-skipped-count (- requested-count candidate-count)
     :prefetch-skipped-count (- candidate-count selected-count)}))

(defn- latest-known-chunk-summary
  [workspace chunk-id]
  (some (fn [entry]
          (some (fn [summary]
                  (when (= chunk-id (:chunk-id summary))
                    summary))
                (:chunk-summaries entry)))
        (reverse (:search-history workspace))))

(def ^:private short-doc-threshold
  "Documents with `:total-chunks` ≤ this value are treated as short — when
   the agent reads any chunk_id from a short doc, the handler expands the
   read to cover the whole doc. Rationale: a 5-chunk doc averages ~2.5 KB
   total, well under read-content budgets, and adjacent chunks of short
   docs are almost always relevant to the same query. Set to 6 (matching
   the threshold that lived in the prior verbose system-prompt SMALL-DOC
   RULE) so the heuristic is calibrated against the same data the prompt
   was tested on."
  6)

(defn expand-short-doc-reads
  "Plan a read in two parts: (a) `:doc-range-reads` — one whole-doc range
   read per *distinct doc-num* whose `:total-chunks` is short (≤ threshold)
   and at least one of its chunks appears in `chunk-ids`; (b) `:chunk-id-reads`
   — the remaining chunk-ids whose docs are not short (or whose total-chunks
   is unknown).

   This implements the SMALL-DOC heuristic server-side, sidestepping the
   LLM's reluctance to use `doc_num + chunk_range`. Adjacent chunks of
   short docs land in the workspace as a free side effect of any
   `read_chunks(chunk_ids=…)` call that mentions one of their siblings.

   Returns `{:chunk-id-reads <vec>, :doc-range-reads <vec of {:doc-num
   :from :to :origin-chunk-ids}>, :short-doc-promotions <int>}`.
   `:origin-chunk-ids` records which requested chunk-ids triggered the
   expansion, for tracing/audit. `:short-doc-promotions` is the number of
   distinct docs that were expanded."
  [workspace chunk-ids]
  (let [grouped (reduce
                  (fn [acc chunk-id]
                    (let [summary (latest-known-chunk-summary workspace chunk-id)
                          total (:total-chunks summary)
                          doc-num (:doc-num summary)]
                      (if (and total doc-num
                               (<= total short-doc-threshold))
                        (update-in acc [:by-doc doc-num]
                                   (fnil
                                     (fn [{:keys [total origin-chunk-ids]} cid]
                                       {:total total
                                        :origin-chunk-ids (conj origin-chunk-ids cid)})
                                     {:total total :origin-chunk-ids []})
                                   chunk-id)
                        (update acc :leftover conj chunk-id))))
                  {:by-doc {} :leftover []}
                  chunk-ids)
        doc-range-reads (vec
                          (for [[doc-num {:keys [total origin-chunk-ids]}] (:by-doc grouped)]
                            {:doc-num doc-num
                             :from 0
                             :to (max 0 (dec total))
                             :origin-chunk-ids origin-chunk-ids}))]
    {:chunk-id-reads (:leftover grouped)
     :doc-range-reads doc-range-reads
     :short-doc-promotions (count doc-range-reads)}))

(defn- estimated-chunk-read-length
  [workspace chunk-id]
  (or (some-> (get (:chunks workspace) chunk-id) chunk-content-length)
      (some-> (latest-known-chunk-summary workspace chunk-id) :content-length)
      fallback-estimated-chunk-length))

(defn select-chunk-ids-by-estimate
  [workspace chunk-ids budget {:keys [allow-single-fallback?]}]
  (let [remaining (or (:read-content-length-remaining budget) Long/MAX_VALUE)]
    (loop [pending chunk-ids
           selected []
           consumed 0
           skipped []]
      (if-let [chunk-id (first pending)]
        (let [estimated-length (max 1 (estimated-chunk-read-length workspace chunk-id))]
          (if (<= (+ consumed estimated-length) remaining)
            (recur (rest pending)
                   (conj selected chunk-id)
                   (+ consumed estimated-length)
                   skipped)
            (recur (rest pending)
                   selected
                   consumed
                   (conj skipped chunk-id))))
        (let [fallback-single? (and allow-single-fallback?
                                    (pos? remaining)
                                    (= 1 (count chunk-ids))
                                    (empty? selected))]
          {:selected-chunk-ids (if fallback-single? [(first chunk-ids)] selected)
           :skipped-chunk-ids (if fallback-single? [] skipped)
           :estimated-content-length consumed
           :remaining-budget remaining})))))

(defn select-chunk-ids-within-budget
  [workspace chunk-ids budget]
  (select-chunk-ids-by-estimate workspace chunk-ids budget {:allow-single-fallback? true}))

(defn affordable-next-read-suggestions
  [workspace]
  (let [{:keys [unread-count chunk-ids range]} (next-read-suggestions workspace)
        budget (budget-state workspace)
        affordable-chunk-ids (-> (select-chunk-ids-by-estimate workspace chunk-ids budget {:allow-single-fallback? false})
                                 :selected-chunk-ids
                                 vec)
        affordable-range (when-let [selected-range (and range
                                                        (effective-read-range workspace
                                                                              (:doc-num range)
                                                                              (:chunk-range range)
                                                                              budget))]
                           {:doc-num (:doc-num range)
                            :chunk-range selected-range})]
    {:unread-count unread-count
     :chunk-ids affordable-chunk-ids
     :range affordable-range
     :readable? (or (seq affordable-chunk-ids)
                    affordable-range)}))

(defn trim-chunks-to-budget
  [chunks remaining-budget]
  (loop [pending chunks
         kept []
         consumed 0
         trimmed []]
    (if-let [chunk (first pending)]
      (let [chunk-length (max 1 (chunk-content-length chunk))]
        (if (<= (+ consumed chunk-length) remaining-budget)
          (recur (rest pending)
                 (conj kept chunk)
                 (+ consumed chunk-length)
                 trimmed)
          (recur (rest pending)
                 kept
                 consumed
                 (conj trimmed chunk))))
      {:kept-chunks kept
       :trimmed-chunks trimmed
       :consumed-content-length consumed})))

(defn read-budget-exhausted-message
  [budget]
  (str "Read budget exhausted ("
       (:read-operations-used budget) "/" (:max-read-operations budget)
       " read operations, "
       (:read-content-length-used budget) "/" (:max-read-content-length budget)
       " chars). Do not call read_chunks again; rerank or generate from already-read evidence."))

(defn read-budget-too-small-message
  [budget]
  (str "Read budget exhausted for the requested read. Remaining budget ("
       (:read-content-length-remaining budget) "/"
       (:max-read-content-length budget)
       " chars) is too small for the requested read. Do not repeat the same read. "
       "If you already have evidence, use rerank_results or generate_response now; otherwise start a more targeted search. "
       "Only try read_chunks again if you can make a strictly narrower request that fits."))

(defn read-budget-note
  [{:keys [returned-count prefetch-skipped-count postfetch-trimmed-count remaining-budget]}]
  (let [skipped-before-fetch (or prefetch-skipped-count 0)
        trimmed-after-fetch (or postfetch-trimmed-count 0)
        total-return-opportunities (+ returned-count skipped-before-fetch trimmed-after-fetch)]
    (when (pos? (+ skipped-before-fetch trimmed-after-fetch))
      (str "Budget limited this read. Returned "
           returned-count " of " total-return-opportunities
           " requested/retrieved chunks within the remaining "
           remaining-budget "-char budget."
           (when (pos? skipped-before-fetch)
             (str " Skipped " skipped-before-fetch " before fetch."))
           (when (pos? trimmed-after-fetch)
             (str " Trimmed " trimmed-after-fetch " after fetch because actual chunk sizes exceeded estimates."))))))

;; =============================================================================
;; Intent & Guidance Logic
;; =============================================================================

(defn latest-search-queries
  "Queries from the latest search pass, if any."
  [workspace]
  (vec (or (:queries (last (:search-history workspace))) [])))

(defn insufficiency-search-action
  [workspace]
  (if (:search-budget-exhausted? (budget-state workspace))
    :answer-with-uncertainty
    :re-search))

(def ^:private stopword-tokens
  #{"a" "an" "and" "are" "av" "be" "ble" "by" "de" "den" "det" "do" "does"
    "eller" "en" "er" "et" "for" "fra" "har" "hva" "hvilke" "hvilken" "hvor"
    "hvordan" "i" "ikke" "in" "is" "med" "no" "not" "of" "og" "om" "on" "or"
    "på" "som" "the" "til" "to" "ved" "was" "were" "what" "when" "which" "who"})

(def ^:private semantic-query-expansions
  {"årsverk" ["utførte årsverk" "antall ansatte" "fte" "full-time equivalents"]
   "ansatte" ["bemanning" "employees" "staff"]
   "årsrapport" ["årsmelding" "annual report"]
   "årsmelding" ["årsrapport" "annual report"]
   "rapport" ["report"]
   "forskrift" ["regulation"]
   "veileder" ["guidance"]})

(defn normalize-query-text
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\s-]" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- tokenize-query-text
  [s]
  (->> (str/split (normalize-query-text s) #"\s+")
       (remove str/blank?)
       vec))

(defn distinctive-tokens
  [s]
  (->> (tokenize-query-text s)
       (remove #(or (< (count %) 3)
                    (contains? stopword-tokens %)))
       distinct
       vec))

(defn extract-years
  [text]
  (->> (re-seq #"\b(19|20)\d{2}\b" (or text ""))
       (map first)
       distinct
       vec))

(defn detect-norwegian-query?
  [text]
  (let [normalized (normalize-query-text text)]
    (boolean
     (or (re-find #"[æøå]" normalized)
         (some #(str/includes? normalized %)
               [" hvordan " " hva " " hvor " " hvilke " " årsverk" " ansatte" " årsmelding" " årsrapport"])))))

(defn query-expansion-terms
  [text]
  (let [normalized (normalize-query-text text)
        expansions (->> semantic-query-expansions
                        (keep (fn [[needle terms]]
                                (when (re-find (re-pattern (str "(?<!\\p{L})"
                                                             (java.util.regex.Pattern/quote needle)
                                                             "(?!\\p{L})"))
                                               normalized)
                                  terms)))
                        (apply concat []))
        fact-focus (cond-> []
                     (re-find #"\b(hvor mange|antall|how many|number of)\b" normalized)
                     (into ["eksakt tall" "oppgitt" "reported figure"])
                     (seq (extract-years normalized))
                     (into ["årsrapport" "annual report"])
                     (re-find #"\b(når|when|date|dato)\b" normalized)
                     (into ["tidslinje" "date" "published"]))]
    (->> (concat expansions fact-focus)
         distinct
         vec)))

(defn- latest-search-context
  [workspace]
  (let [search-entry (last (:search-history workspace))
        read-ids (read-chunk-id-set workspace)]
    {:latest-queries (vec (or (:queries search-entry) []))
     :result-count (or (:result-count search-entry) 0)
     :unread-summaries (->> (:chunk-summaries search-entry)
                            (remove #(contains? read-ids (:chunk-id %)))
                            vec)
     :latest-chunk-summaries (vec (or (:chunk-summaries search-entry) []))
     :read-chunk-ids read-ids}))

(defn- response-gap-hints
  [response]
  (let [normalized (normalize-query-text response)]
    (cond-> []
      (re-find #"\b(ikke nok|insufficient|not enough|mangler|missing)\b" normalized)
      (into ["eksakt tall" "konkret fakta" "specific fact"])
      (re-find #"\b(ikke oppgitt|not stated|ukjent|unknown)\b" normalized)
      (into ["oppgitt" "reported" "tabell"])
      (re-find #"\b(conflict|motstrid|uavklart)\b" normalized)
      (into ["presisering" "disambiguation"]))))

(defn- join-query-parts
  "Join query parts into a readable search string."
  [& parts]
  (->> parts
       flatten
       (keep #(some-> % str str/trim not-empty))
       (str/join " ")))

(defn unique-normalized-queries
  "Deduplicate query strings by normalized form while preserving order."
  [queries]
  (->> queries
       (reduce (fn [{:keys [seen ordered]} query]
                 (let [normalized (normalize-query-text query)]
                   (if (or (str/blank? normalized)
                           (contains? seen normalized))
                     {:seen seen :ordered ordered}
                     {:seen (conj seen normalized)
                      :ordered (conj ordered normalized)})))
               {:seen #{} :ordered []})
       :ordered
       vec))

(declare infer-query-intent)

(defn- intent-aware-query-variants
  "Generate deterministic query variants from inferred retrieval intent."
  [query query-intent extra-terms]
  (let [{:keys [answer-type entity year-or-date metric doc-family-preference]} query-intent
        metric-expansions (->> (query-expansion-terms (or metric query))
                               (remove #(= (normalize-query-text %) (normalize-query-text metric)))
                               vec)
        metric-specific (first metric-expansions)
        disambiguating-term (or (second metric-expansions)
                                (first extra-terms))
        numeric-focus (when (= :numeric-fact answer-type)
                        (if (detect-norwegian-query? query)
                          "oppgitt tall"
                          "reported figure"))]
    (unique-normalized-queries
     [(join-query-parts metric entity year-or-date)
      (join-query-parts metric-specific entity year-or-date)
      (join-query-parts entity doc-family-preference year-or-date metric)
      (join-query-parts entity disambiguating-term metric year-or-date)
      (join-query-parts entity metric year-or-date numeric-focus)])))

(defn planned-query-batch
  "Combine planner output, intent-aware variants, and optional extra hints.

   Planner phrases come FIRST: the query-planner output (especially the
   corpus-aware modes) is the curated, high-value signal and already includes
   the original user query as its first element. The deterministic
   intent-aware variants are generic entity/metric/year joins — a fallback that
   should only fill slots the planner didn't. Previously `generated` was
   prepended, so with the `take 6` cap the generic variants crowded out almost
   all of the planner's corpus-aware phrases (e.g. only 1 of 10 survived),
   silently defeating corpus-aware expansion at the batch stage."
  [query conversation-history planner-phrases extra-terms]
  (let [intent (infer-query-intent query conversation-history)
        generated (intent-aware-query-variants query intent extra-terms)]
    (->> (concat planner-phrases generated)
         unique-normalized-queries
         (take 6)
         vec)))

(defn- doc-family-present?
  [chunk-summaries doc-family]
  (let [needle (normalize-query-text doc-family)]
    (boolean
     (some (fn [{:keys [title headers]}]
             (let [text (normalize-query-text (str title " " headers))]
               (str/includes? text needle)))
           chunk-summaries))))

(defn- insufficiency-extra-terms
  [workspace query response]
  (let [insufficiency (:last-insufficiency workspace)
        query-intent (:last-query-intent workspace)
        {:keys [latest-chunk-summaries]} (latest-search-context workspace)
        preferred-doc-family (or (:doc-family-hint insufficiency)
                                 (:doc-family-preference query-intent))
        doc-family-missing? (and preferred-doc-family
                                 (not (doc-family-present? latest-chunk-summaries preferred-doc-family)))
        base-terms (concat
                    (response-gap-hints response)
                    (query-expansion-terms query)
                    (when-let [metric (:target-metric insufficiency)]
                      [metric])
                    (when-let [year (:target-year insufficiency)]
                      [year])
                    (when preferred-doc-family
                      [preferred-doc-family]))]
    (vec
     (distinct
      (concat
       base-terms
       (case (:failure-type insufficiency)
         :missing-numeric-fact
         (if (detect-norwegian-query? query)
           ["oppgitt tall" "tabell" "hovudtal"]
           ["reported figure" "table" "summary"])

         :wrong-doc-family
         (if (detect-norwegian-query? query)
           ["årsrapport" "hovudtal" "offisiell oversikt"]
           ["annual report" "summary" "official figure"])

         :conflict
         (if (detect-norwegian-query? query)
           ["presisering" "hovudtal" "offisiell oversikt"]
           ["disambiguation" "summary" "official figure"])

         :missing-date
         (if (detect-norwegian-query? query)
           ["dato" "tidslinje" "publisert"]
           ["date" "timeline" "published"])

         [])
       (when doc-family-missing?
         (if (detect-norwegian-query? query)
           ["annen dokumenttype" "årsrapport" "årsmelding"]
         ["different document family" "annual report"])))))))

(defn- insufficiency-query-variants
  [workspace query]
  (let [insufficiency (:last-insufficiency workspace)
        query-intent (:last-query-intent workspace)
        entity (or (:target-entity insufficiency)
                   (:entity query-intent))
        metric (or (:target-metric insufficiency)
                   (:metric query-intent))
        year (or (:target-year insufficiency)
                 (:year-or-date query-intent))
        doc-family (or (:doc-family-hint insufficiency)
                       (:doc-family-preference query-intent))
        norwegian? (detect-norwegian-query? query)]
    (case (:failure-type insufficiency)
      :missing-numeric-fact
      [(join-query-parts entity metric year (if norwegian? "oppgitt tall" "reported figure"))
       (join-query-parts entity doc-family year metric)]

      :wrong-doc-family
      [(join-query-parts entity doc-family year metric)
       (join-query-parts entity (if norwegian? "hovudtal" "summary") year metric)]

      :conflict
      [(join-query-parts entity metric year (if norwegian? "presisering" "disambiguation"))
       (join-query-parts entity (if norwegian? "hovudtal" "summary") year metric)]

      :missing-date
      [(join-query-parts entity metric (if norwegian? "dato" "date"))
       (join-query-parts entity doc-family year metric)]

      [])))

(defn next-research-suggestions
  [workspace query response]
  (let [{:keys [latest-queries]} (latest-search-context workspace)
        base-query (or (not-empty (str/trim (or query "")))
                       (first latest-queries)
                       "")
        _query-intent (or (:last-query-intent workspace)
                         (infer-query-intent base-query []))
        extra-terms (->> (concat (insufficiency-extra-terms workspace base-query response)
                                 (mapcat query-expansion-terms latest-queries))
                         distinct
                         vec)
        candidate-queries (planned-query-batch base-query [] latest-queries extra-terms)
        explicit-insufficiency-queries (insufficiency-query-variants workspace base-query)
        normalized-latest (set (map normalize-query-text latest-queries))
        ;; Corpus-grounded phrases the planner produced this turn but that the
        ;; take-6 search batch never tried. Surfacing these keeps refinement
        ;; re-searches corpus-grounded; without them candidate-queries collapses
        ;; to generic deterministic variants once the latest (already-searched)
        ;; queries are removed below. Empty when no plan_queries ran this turn.
        unsearched-planner-phrases (:last-planner-phrases workspace)]
    (->> (concat explicit-insufficiency-queries unsearched-planner-phrases candidate-queries)
         ;; Normalize before the already-searched membership test: candidates
         ;; arrive in mixed case (planner phrases, insufficiency variants) while
         ;; normalized-latest is lowercased, so comparing raw strings would let
         ;; an already-searched phrase (e.g. "Altinn 3 juni 2020") slip back in.
         (remove (comp normalized-latest normalize-query-text))
         unique-normalized-queries
         (take 4)
         vec)))

(defn format-research-guidance
  [workspace query response]
  (let [latest-queries (latest-search-queries workspace)
        {:keys [search-budget-exhausted? low-search-budget?
                search-passes-used max-search-passes]} (budget-state workspace)
        suggestions (next-research-suggestions workspace query response)]
    (if search-budget-exhausted?
      (str "[SYSTEM: Search budget exhausted (" search-passes-used "/" max-search-passes
           " search passes used). Do not start a new search. Reuse current evidence, rerank existing chunks, or answer with explicit uncertainty.]")
      (str "[SYSTEM: Current evidence is insufficient and you have exhausted the readable hits from the latest search. "
           "Start a NEW targeted search now."
           (when low-search-budget?
             " Budget is low, so prefer canonical document families and exact metric phrasing.")
           (when (seq latest-queries)
             (str " Avoid repeating the same queries verbatim: " (pr-str latest-queries) "."))
           (if (seq suggestions)
             (str " Suggested next call: search "
                  (json/write-str {:queries suggestions})
                  ".")
             " Reformulate the search with narrower or alternate wording.")
           " Read new hits before generating again.]"))))

(defn format-conflict-guidance
  [workspace query response]
  (let [latest-queries (latest-search-queries workspace)
        {:keys [search-budget-exhausted? low-search-budget?
                search-passes-used max-search-passes]} (budget-state workspace)
        suggestions (next-research-suggestions workspace query response)]
    (if search-budget-exhausted?
      (str "[SYSTEM: Search budget exhausted (" search-passes-used "/" max-search-passes
           " search passes used). The evidence remains conflicting. Do not claim one value as definitive; answer with the conflict and its scope differences.]")
      (str "[SYSTEM: Current evidence is conflicting: multiple plausible values or scopes are present. "
           "Do not present one value as definitive yet. Prefer canonical summary sources and scope-disambiguating searches."
           (when low-search-budget?
             " Budget is low, so prioritize canonical summary sources over broad exploratory searches.")
           (when (seq latest-queries)
             (str " Avoid repeating the same queries verbatim: " (pr-str latest-queries) "."))
           (if (seq suggestions)
             (str " Suggested next call: search "
                  (json/write-str {:queries suggestions})
                  ".")
             " Reformulate the search to resolve the conflict explicitly.")
           " Read new hits before generating again.]"))))

(defn format-read-guidance
  "Return a concrete instruction when the agent tries to rerank/generate before
   any chunks have accumulated in the workspace.

   Three cases the LLM needs to distinguish:
   - read budget is exhausted → stop trying to read; work with what you have
   - no read_chunks calls attempted yet → use read_chunks before the target tool
   - read_chunks WAS called but no chunks accumulated (suppression, budget,
     non-existent chunk-ids) → tell the LLM that retrying the same read won't
     help and to either pivot the search or work with whatever evidence exists"
  [workspace target-tool]
  (let [{:keys [unread-count chunk-ids range]} (next-read-suggestions workspace)
        {:keys [read-operations-exhausted? read-content-budget-exhausted?
                low-read-budget? read-operations-used max-read-operations
                read-content-length-used max-read-content-length
                read-content-length-remaining]} (budget-state workspace)
        action (case target-tool
                 "rerank_results" "rerank"
                 "generate_response" "generate a response"
                 "continue")
        reads-attempted (count (or (:read-history workspace) []))
        reads-but-empty? (pos? reads-attempted)]
    (cond
      (or read-operations-exhausted? read-content-budget-exhausted?)
      (str "You only have search metadata right now, not readable content, but read budget is exhausted "
           "(" read-operations-used "/" max-read-operations " read operations, "
           read-content-length-used "/" max-read-content-length " chars). "
           "Do not call read_chunks again. Work with already-read evidence, rerank it, or answer with explicit uncertainty.")

      reads-but-empty?
      (str "You called read_chunks " reads-attempted " time"
           (when (not= 1 reads-attempted) "s")
           " but the workspace has no chunks to " action ". "
           "Likely causes: every requested chunk was suppressed as previously non-supporting, dropped by the read-content budget, or otherwise filtered before content was stored. "
           "Read budget remaining: " read-content-length-remaining "/" max-read-content-length " chars, "
           (max 0 (- max-read-operations read-operations-used)) "/" max-read-operations " read operations. "
           "Do NOT repeat the same read. Either start a more targeted search to surface different chunk_ids, "
           "or — if you have evidence in prior turns — answer with explicit uncertainty about what you couldn't retrieve."
           (when (seq chunk-ids)
             (str " If unread hits remain, try fresh chunk_ids: read_chunks {\"chunk_ids\": "
                  (json/write-str chunk-ids) "}."))
           (when range
             (str " Or expand local context with read_chunks "
                  (json/write-str {:doc_num (:doc-num range)
                                   :chunk_range (:chunk-range range)}) ".")))

      :else
      (str "You only have search metadata right now, not readable content. "
           "Use read_chunks before trying to " action ". "
           "There are " unread-count " unread search hits from the latest search."
           (when low-read-budget?
             " Read budget is low, so prefer the most targeted chunk_ids instead of broad ranges.")
           (when (seq chunk-ids)
             (str " Suggested next call: read_chunks {\"chunk_ids\": "
                  (json/write-str chunk-ids)
                  "}."))
           (when range
             (str " If you want adjacent context instead, try read_chunks "
                  (json/write-str {:doc_num (:doc-num range)
                                   :chunk_range (:chunk-range range)})))
           " After reading content, call rerank_results or generate_response again."))))

;; =============================================================================
;; Intent Inference
;; =============================================================================

(defn extract-target-year
  "Best-effort year extraction from the active query."
  [query]
  (some-> (re-find #"\b(?:19|20)\d{2}\b" (or query ""))
          str))

(defn- history-text
  "Flatten conversation history into a single searchable string."
  [conversation-history]
  (->> (or conversation-history [])
       (map (fn [message]
              (or (:message/text message)
                  (:text message)
                  (:content message)
                  "")))
       (str/join " ")))

(defn extract-target-year-from-history
  "Best-effort year extraction from prior conversation context."
  [conversation-history]
  (extract-target-year (history-text conversation-history)))

(defn- infer-failure-type
  "Deterministically classify the current evidence gap."
  [query response]
  (let [q (str/lower-case (or query ""))
        r (str/lower-case (or response ""))]
    (cond
      (or (str/includes? r "motstrid")
          (str/includes? r "conflict")
          (str/includes? r "ulike tall")
          (str/includes? r "different figures"))
      :conflict

      (or (str/includes? q "hvor mange")
          (str/includes? q "kor mange")
          (str/includes? q "how many")
          (str/includes? q "antall")
          (str/includes? q "tal ")
          (str/includes? q " tall")
          (str/includes? q "årsverk")
          (str/includes? q "employees")
          (str/includes? q "fte"))
      :missing-numeric-fact

      (or (str/includes? q "når")
          (str/includes? q "when")
          (str/includes? q "dato")
          (str/includes? q "date"))
      :missing-date

      :else
      :wrong-scope)))

(defn- infer-target-metric
  "Best-effort metric extraction from the query."
  [query]
  (let [q (str/lower-case (or query ""))]
    (cond
      (str/includes? q "årsverk") "årsverk"
      (str/includes? q "utførte årsverk") "utførte årsverk"
      (str/includes? q "avtalte årsverk") "avtalte årsverk"
      (str/includes? q "ansatte") "ansatte"
      (str/includes? q "stillinger") "stillinger"
      (str/includes? q "employees") "employees"
      (str/includes? q "fte") "fte"
      :else nil)))

(defn- infer-doc-family-hint
  "Best-effort canonical document family hint from query or recent searches."
  [workspace query]
  (let [q (str/lower-case (or query ""))
        latest-queries (map str/lower-case (or (-> workspace :search-history last :queries) []))]
    (cond
      (or (str/includes? q "årsrapport")
          (some #(str/includes? % "årsrapport") latest-queries))
      "årsrapport"

      (or (str/includes? q "annual report")
          (some #(str/includes? % "annual report") latest-queries))
      "annual-report"

      :else nil)))

(defn- infer-target-entity
  "Best-effort entity inference from the query."
  [query]
  (let [query' (or query "")
        primary-clause (first (str/split query' #"(?:\?\s+|(?<!\S)(?:Hvis|If)\b)" 2))
        question-words #{"Hva" "Hvem" "Hvor" "Hvordan" "Hvorfor" "Når"
                         "What" "Who" "Where" "How" "Why" "When"
                         "Compare" "Sammenlign"}
        words (->> (re-seq #"[A-ZÆØÅ][A-Za-zÆØÅæøå0-9.-]+" primary-clause)
                   (remove question-words))]
    (when (seq words)
      (str/join " " words))))

(defn- infer-answer-type
  "Best-effort answer type classification from the current query."
  [query]
  (let [q (str/lower-case (or query ""))]
    (cond
      (or (str/includes? q "hvor mange")
          (str/includes? q "kor mange")
          (str/includes? q "how many")
          (str/includes? q "antall")
          (str/includes? q "hva er tallet")
          (str/includes? q "what is the number"))
      :numeric-fact

      (or (str/includes? q "sammenlign")
          (str/includes? q "compare")
          (str/includes? q "forskjell")
          (str/includes? q "versus")
          (str/includes? q "vs"))
      :comparison

      (or (str/starts-with? q "hva er")
          (str/starts-with? q "what is")
          (str/includes? q "definisjon")
          (str/includes? q "definition"))
      :definition

      (or (str/starts-with? q "hvorfor")
          (str/starts-with? q "why")
          (str/starts-with? q "hvordan")
          (str/starts-with? q "how"))
      :explanation

      :else
      :lookup)))

(defn- infer-doc-family-preference
  "Best-effort canonical document family preference for retrieval."
  [query conversation-history]
  (let [q (str/lower-case (or query ""))
        h (str/lower-case (history-text conversation-history))
        combined (str q " " h)
        has-year? (boolean (extract-target-year query))
        metric (infer-target-metric query)]
    (cond
      (or (str/includes? combined "årsrapport")
          (str/includes? combined "annual report"))
      "årsrapport"

      (or (str/includes? combined "tildelingsbrev")
          (str/includes? combined "allocation letter"))
      "tildelingsbrev"

      (and has-year? metric)
      "årsrapport"

      :else
      nil)))

(defn- infer-scope-signals
  "Collect coarse scope signals from the query and recent history."
  [query conversation-history]
  (let [combined-text (str (or query "") " " (history-text conversation-history))
        combined (str/lower-case combined-text)]
    (vec
     (concat
      (when (extract-target-year combined-text) [:year-bounded])
      (when (or (str/includes? combined "digdir")
                (str/includes? combined "digitaliseringsdirektoratet"))
        [:organization-bounded])
      (when (or (str/includes? combined "2022")
                (str/includes? combined "2021")
                (str/includes? combined "2020"))
        [:time-specific])
      (when (or (str/includes? combined "konsern")
                (str/includes? combined "group")
                (str/includes? combined "hele virksomheten"))
        [:group-scope])
      (when (or (str/includes? combined "utførte")
                (str/includes? combined "avtalte")
                (str/includes? combined "faste")
                (str/includes? combined "midlertidige"))
        [:qualified-metric])))))

(defn infer-query-intent
  "Infer lightweight retrieval intent from the active query and conversation state."
  [query conversation-history]
  {:answer-type (infer-answer-type query)
   :entity (or (infer-target-entity query)
               (infer-target-entity (history-text conversation-history)))
   :year-or-date (or (extract-target-year query)
                     (extract-target-year-from-history conversation-history))
   :metric (or (infer-target-metric query)
               (infer-target-metric (history-text conversation-history)))
   :doc-family-preference (infer-doc-family-preference query conversation-history)
   :scope-signals (infer-scope-signals query conversation-history)})

(def ^:private repeated-gap-window 2)

(defn- critical-gap-ids
  [signal]
  (->> (or (:remaining-gaps signal) [])
       (filter #(or (:critical? %)
                    (:critical %)))
       (map :claim-id)
       set))

(defn- repeated-gap-stagnation
  [workspace]
  (let [recent-signals (->> (or (:read-evaluations workspace) [])
                            (take-last repeated-gap-window)
                            vec)
        recent-gap-sets (mapv critical-gap-ids recent-signals)
        last-gap-set (last recent-gap-sets)]
    (when (and (= repeated-gap-window (count recent-signals))
               (seq last-gap-set)
               (every? seq recent-gap-sets)
               (apply = recent-gap-sets)
               (every? (comp empty? :supported-claims) recent-signals)
               (every? (comp empty? :contradictions) recent-signals))
      {:gap-ids (vec last-gap-set)
       :scope-assessments (mapv :scope-assessment recent-signals)})))

(defn aggregate-read-signals
  [workspace]
  (let [evidence-plan (:evidence-plan workspace)
        critical-claims (->> (or (:required-claims evidence-plan) [])
                             (filter :critical?)
                             vec)
        supported-ids (set (keys (or (:claim-coverage workspace) {})))
        missing-critical (->> critical-claims
                              (remove #(contains? supported-ids (:claim-id %)))
                              (mapv :claim-id))
        contradictions (vec (or (:evidence-contradictions workspace) []))
        last-read-signal (:last-read-signal workspace)
        degraded? (boolean (:degraded? last-read-signal))
        scope-assessment (:scope-assessment last-read-signal)
        repeated-gap-state (repeated-gap-stagnation workspace)
        stagnant-read-loop? (boolean repeated-gap-state)
        ambiguous-signal? (or degraded?
                              (= :unclear (:status last-read-signal))
                              (= :ambiguous scope-assessment)
                              (= :wrong-scope scope-assessment))
        read-suggestions (next-read-suggestions workspace)
        suggested-strategy (cond
                             (seq contradictions) :finalize
                             ambiguous-signal? (or (:next-action-hint last-read-signal) :re-search)
                             (empty? missing-critical) :finalize
                             stagnant-read-loop? :re-search
                             (pos? (or (:unread-count read-suggestions) 0)) :read-more
                             :else :re-search)]
    {:source :read-signals
     :status (cond
               (seq contradictions) :conflicting
               ambiguous-signal? :ambiguous
               (empty? missing-critical) :sufficient
               :else :insufficient)
     :reason-code (cond
                    (seq contradictions) :contradictory-read-signals
                    degraded? :degraded-local-read-signal
                    (= :ambiguous scope-assessment) :ambiguous-scope
                    (= :wrong-scope scope-assessment) :wrong-scope
                    (= :unclear (:status last-read-signal)) :unclear-read-signal
                    (empty? missing-critical) :all-critical-claims-covered
                    stagnant-read-loop? :repeated-critical-gaps
                    :else :critical-gaps-remaining)
     :suggested-strategy suggested-strategy
     :missing-claims missing-critical
     :contradictions contradictions
     :supported-claim-count (count supported-ids)
     :stagnant-gap-claims (vec (:gap-ids repeated-gap-state))}))

(defn record-shadow-sufficiency-decision
  "Pure: append a shadow-sufficiency decision."
  [workspace decision]
  (update workspace :shadow-sufficiency-decisions conj decision))

(defn record-shadow-sufficiency-decision!
  [!workspace decision]
  (swap! !workspace record-shadow-sufficiency-decision decision)
  nil)

(defn record-query-intent
  "Pure: append a query intent and mark it as the most-recent."
  [workspace query-intent]
  (-> workspace
      (update :query-intents conj query-intent)
      (assoc :last-query-intent query-intent)))

(defn record-query-intent!
  "Track inferred query intent for trace/debug visibility."
  [!workspace query-intent]
  (swap! !workspace record-query-intent query-intent)
  nil)

;; =============================================================================
;; Sufficiency Decision Logic
;; =============================================================================

(defn legacy-insufficiency-from-decision
  [workspace {:keys [query status reasoning suggested-strategy]}]
  (when (not= :sufficient status)
    {:failure-type (cond
                     (= :conflicting status) :conflict
                     (= :off-topic status) :wrong-scope
                     :else (infer-failure-type query reasoning))
     :target-entity (infer-target-entity query)
     :target-year (extract-target-year query)
     :target-metric (infer-target-metric query)
     :doc-family-hint (infer-doc-family-hint workspace query)
     :evidence-gap-summary (or (some-> reasoning str/trim not-empty)
                               "Current evidence is insufficient for a grounded answer.")
     :recommended-action (case suggested-strategy
                           :finalize :answer-with-uncertainty
                           suggested-strategy)}))

(defn- sufficiency-decision-entry
  "Pure: shape a sufficiency-gate decision into the persisted entry."
  [workspace decision insufficiency]
  (cond-> (merge (select-keys decision [:status :reasoning :missing-info
                                        :contradiction-detected? :suggested-strategy
                                        :message :source])
                 {:action (:suggested-strategy decision)}
                 (when insufficiency
                   {:insufficiency insufficiency}))
    (:current-iteration workspace)
    (assoc :iteration (:current-iteration workspace))))

(defn record-sufficiency-decision
  "Pure: append a sufficiency-gate decision; cache the derived insufficiency."
  [workspace decision]
  (let [insufficiency (or (:insufficiency decision)
                          (legacy-insufficiency-from-decision workspace decision))
        entry (sufficiency-decision-entry workspace decision insufficiency)]
    (-> workspace
        (update :sufficiency-decisions conj entry)
        (assoc :last-insufficiency insufficiency))))

(defn record-sufficiency-decision!
  "Track sufficiency-gate decisions for trace/debug visibility."
  [!workspace decision]
  (swap! !workspace record-sufficiency-decision decision)
  nil)

(defn record-response-validation
  "Pure: append a post-generate response-validation decision.

   Writes to :last-response-validation-insufficiency rather than :last-insufficiency.
   Response validation is a different gate than the read-time sufficiency evaluator,
   and the search-strategy consumers (insufficiency-extra-terms,
   insufficiency-query-variants) rely on :last-insufficiency reflecting the
   most recent read-time gap — not a post-generate complaint."
  [workspace decision]
  (let [insufficiency (or (:insufficiency decision)
                          (legacy-insufficiency-from-decision workspace decision))
        entry (sufficiency-decision-entry workspace decision insufficiency)]
    (-> workspace
        (update :response-validations conj entry)
        (assoc :last-response-validation-insufficiency insufficiency))))

(defn record-response-validation!
  [!workspace decision]
  (swap! !workspace record-response-validation decision)
  nil)

;; =============================================================================
;; Citation Tracking
;; =============================================================================

(def ^:private citation-pattern #"\[(\d+)\]")

(defn has-citation?
  "True when text contains at least one [N]-style citation."
  [text]
  (boolean (re-find citation-pattern (or text ""))))

(defn citation-carry-through
  "Pure: decide whether to carry through citations from the last synthesis
   into the final response. Returns {:response :carried-through? :strategy}."
  [final-response workspace]
  (let [{:keys [citations citation-index last-generated-response]} workspace
        final-response (or final-response "")
        source-indices (if (seq citations)
                         (->> citations (map :index) distinct sort)
                         (sort (keys (or citation-index {}))))
        has-sources? (seq source-indices)
        final-has-citations? (has-citation? final-response)
        generated-has-citations? (has-citation? last-generated-response)]
    (cond
      (or (not has-sources?) final-has-citations?)
      {:response final-response
       :carried-through? false
       :strategy :already-cited-or-no-sources}

      generated-has-citations?
      {:response last-generated-response
       :carried-through? true
       :strategy :use-last-generated-with-citations}

      :else
      {:response final-response
       :carried-through? false
       :strategy :no-inline-citations-available})))

(defn ensure-citation-carry-through
  "Atom shim of `citation-carry-through`."
  [final-response !workspace]
  (citation-carry-through final-response @!workspace))
