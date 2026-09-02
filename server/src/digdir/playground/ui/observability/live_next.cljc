(ns digdir.playground.ui.observability.live-next
  "Next-gen observability view components for the Playground 'Detailed' mode.

   A unified component tree that renders the same layout whether a run is
   live or completed, driven by a normalized view-model built from either
   the stream-view (live) or parsed diagnostics (post-run).

   This file deliberately duplicates small amounts of styling from
   live.cljc rather than extracting shared primitives — the previous
   implementation may be dropped once this settles."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])
            [digdir.playground.ui.components :as base]
            [digdir.i18n :as i18n :refer [t]]
            #?(:clj [digdir.playground.ui.observability.enrichment-inspector
                     :as enrichment-inspector])))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- status-palette
  [status]
  (case status
    :running       {:bg "#eff6ff" :border "#bfdbfe" :text "#1e40af" :dot "#3b82f6"}
    :ok            {:bg "#ecfdf5" :border "#bbf7d0" :text "#065f46" :dot "#10b981"}
    :sufficient    {:bg "#ecfdf5" :border "#bbf7d0" :text "#065f46" :dot "#10b981"}
    :defaulted     {:bg "#fef3c7" :border "#fde68a" :text "#92400e" :dot "#f59e0b"}
    :warning       {:bg "#fef3c7" :border "#fde68a" :text "#92400e" :dot "#f59e0b"}
    :insufficient  {:bg "#fff7ed" :border "#fed7aa" :text "#9a3412" :dot "#f97316"}
    :gap-remaining {:bg "#fff7ed" :border "#fed7aa" :text "#9a3412" :dot "#f97316"}
    :conflicting   {:bg "#fef2f2" :border "#fecaca" :text "#991b1b" :dot "#ef4444"}
    :error         {:bg "#fef2f2" :border "#fecaca" :text "#991b1b" :dot "#ef4444"}
    :clarification {:bg "#f5f3ff" :border "#ddd6fe" :text "#5b21b6" :dot "#8b5cf6"}
    :skipped       {:bg "#f3f4f6" :border "#e5e7eb" :text "#4b5563" :dot "#9ca3af"}
    :off-topic     {:bg "#f3f4f6" :border "#e5e7eb" :text "#4b5563" :dot "#9ca3af"}
    :pending       {:bg "#f3f4f6" :border "#e5e7eb" :text "#6b7280" :dot "#9ca3af"}
    {:bg "#f3f4f6" :border "#e5e7eb" :text "#374151" :dot "#9ca3af"}))

(defn- format-ms
  [ms]
  (when (and (number? ms) (pos? ms))
    (if (< ms 1000)
      (str ms "ms")
      (str (.toFixed (/ ms 1000.0) 1) "s"))))

(defn- first-line
  [s]
  (when (string? s)
    (let [t (str/triml s)
          nl (str/index-of t "\n")]
      (str/trimr (if nl (subs t 0 nl) t)))))

(defn- truncate
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "…")
    (or s "")))

(defn- parse-int-safe
  [s]
  #?(:clj (try (Long/parseLong s) (catch Exception _ nil))
     :cljs (let [n (js/parseInt s 10)] (when-not (js/isNaN n) n))))

(defn- extract-chunk-count
  [summary]
  (when (string? summary)
    (when-let [m (re-find #"(\d+)\s+chunks" summary)]
      (parse-int-safe (second m)))))

(defn- parse-search-hit-line
  [line]
  (let [kv (fn [pat] (some-> (re-find pat line) second))]
    {:rank-n (some-> (re-find #"^(\d+)\." line) second parse-int-safe)
     :chunk-id (kv #"chunk_id=(\S+)")
     :doc-num (kv #"doc_num=(\S+)")
     :chunk-index (kv #"chunk_index=(\S+)")
     :title (kv #"title=\"([^\"]*)\"")
     :url (kv #"url=(\S+)")
     :headers-raw (kv #"headers=(\{[^}]*\})")
     :rerank-score (kv #"rerank=(\S+)")
     :score (kv #"score=(\S+)")}))

(defn- parse-rerank-note
  "Extract rerank status from the headline-ish portion of a search summary.
   Returns one of
     {:state :ok   :ms N}
     {:state :failed :reason <msg>}
     {:state :disabled}
   or nil when no rerank info is present."
  [headline-block]
  (when (string? headline-block)
    (cond
      (re-find #"ColBERT rerank failed \(([^)]*)\)" headline-block)
      {:state :failed
       :reason (some-> (re-find #"ColBERT rerank failed \(([^)]*)\)" headline-block) second)}

      (re-find #"Ranking: ColBERT semantic rerank" headline-block)
      {:state :ok
       :ms (some-> (re-find #"rerank \((\d+)ms\)" headline-block) second parse-int-safe)})))

(defn- parse-omitted-count
  [headline-block]
  (when (string? headline-block)
    (some-> (re-find #"(\d+) lower-ranked chunks omitted" headline-block)
            second parse-int-safe)))

(defn- parse-auto-filter-note
  [headline-block]
  (when (string? headline-block)
    (when-let [m (re-find #"Auto-filtered by ([^:]+): ([^.]+)\." headline-block)]
      {:field (str/trim (nth m 1))
       :values (mapv str/trim (str/split (nth m 2) #","))
       :fallback? (boolean (re-find #"fell back to unfiltered" headline-block))})))

(defn- parse-search-summary
  "Parse the search-result string produced by format-search-metadata-results.

   Returns {:headline :hits :rerank :omitted-count :auto-filter}. Hits are
   structured maps with title, rerank-score, score, etc. Safe on nil/garbage."
  [full]
  (when (string? full)
    (let [lines (str/split-lines full)
          hit-lines (filter #(re-matches #"^\d+\..*" %) lines)
          non-hit-lines (remove #(re-matches #"^\d+\..*" %) lines)
          ;; The headline block is everything BEFORE the first hit line —
          ;; trailing notes (rerank/omitted/auto-filter) may be joined onto
          ;; the first line or live on separate lines. We concatenate all
          ;; non-hit lines to scan them uniformly.
          headline-block (str/join " " non-hit-lines)]
      {:headline (first lines)
       :rerank (parse-rerank-note headline-block)
       :omitted-count (parse-omitted-count headline-block)
       :auto-filter (parse-auto-filter-note headline-block)
       :hits (mapv parse-search-hit-line hit-lines)})))

(defn- parse-read-chunk-marker
  [marker]
  (let [kv (fn [pat] (some-> (re-find pat marker) second))]
    {:chunk-id (kv #"chunk_id=(\S+)")
     :doc-num (kv #"doc_num=(\S+)")
     :chunk-index (kv #"chunk_index=(\S+)")
     :title (kv #"title=\"([^\"]*)\"")
     :url (kv #"url=(\S+)")}))

(defn- parse-read-summary
  "Parse the read-result string produced by format-read-result.

   Returns {:headline ... :chunks [...]}, where each chunk has
   title, chunk-id, doc-num, chunk-index, url, and content."
  [full]
  (when (string? full)
    (let [headline (first (str/split-lines full))
          blocks (str/split full #"(?m)^--- ")
          chunk-blocks (rest blocks)
          chunks (mapv (fn [block]
                         (let [nl (str/index-of block "\n")
                               marker-raw (if nl (subs block 0 nl) block)
                               content (if nl (str/triml (subs block (inc nl))) "")
                               marker (str/replace marker-raw #"\s*---\s*$" "")]
                           (assoc (parse-read-chunk-marker marker)
                                  :content content)))
                       chunk-blocks)]
      {:headline headline
       :chunks chunks})))

(defn- read-signals-by-chunk-ids
  "Build an index {chunk-id-set → read-signal} from :read-evaluations so we
   can attach a signal to the matching read tool-call event."
  [read-evaluations]
  (reduce (fn [acc sig]
            (let [ids (set (or (:chunk-ids sig) []))]
              (if (seq ids) (assoc acc ids sig) acc)))
          {}
          (or read-evaluations [])))

(defn- retrieval-labels-from-boosts
  "Build a list of human-readable retrieval-boost labels from a chunk's
   :retrieval-boosts map. Mirrors the Classic Evidence view's 'Why' column."
  [boosts]
  (let [ordered [[:numeric-evidence "numeric evidence"]
                 [:content-overlap "content overlap"]
                 [:search-type "search type"]
                 [:org "org match"]
                 [:year "year match"]
                 [:title "title match"]]]
    (->> ordered
         (keep (fn [[k label]]
                 (when (pos? (double (or (get boosts k) 0.0)))
                   label)))
         vec)))

(defn- merged-chunks-index
  "Build {chunk-id → enrichment} from :merged-results so search hits can be
   joined with chars / state / search-types / retrieval-labels after the fact.

   `read-chunk-ids` is the union of all read + used chunk-ids used to derive
   the :read? state."
  [diagnostics]
  (let [merged (or (:merged-results diagnostics) [])
        read-chunk-ids (set (concat
                             (mapcat #(or (:returned-chunk-ids %) [])
                                     (or (:read-history diagnostics) []))
                             (map :chunk_id (or (:used-chunks diagnostics) []))))]
    (reduce (fn [acc chunk]
              (let [id (:chunk_id chunk)]
                (if id
                  (assoc acc id
                         {:content-length (:content_length chunk)
                          :search-type-labels (mapv name (sort (or (:search-types chunk) [])))
                          :read? (contains? read-chunk-ids id)
                          :retrieval-labels (retrieval-labels-from-boosts
                                             (:retrieval-boosts chunk))})
                  acc)))
            {}
            merged)))

;; =============================================================================
;; View-model builders
;; =============================================================================

(defn- tr
  "Translate using the locale captured for this detailed-status view.

   View-models are built client-side and can outlive a reactive render pass, so
   they must not depend on whatever happens to be in the global locale atom at
   some later point in the run."
  [language k & args]
  (apply i18n/translate (i18n/normalize-language language) k args))

(defn- stage-friendly-label
  ([stage tool]
   (stage-friendly-label (i18n/current-language) stage tool))
  ([language stage tool]
   (let [tool-name (cond
                     (keyword? tool) (name tool)
                     (string? tool) tool
                     :else nil)
         stage-name (cond
                      (keyword? stage) (name stage)
                      (string? stage) stage
                      :else nil)
         labels
         {"agent-setup" :playground/prepare-agent
          "agent-iter-state" :playground/update-iteration
          "agent-bundle-llm-and-tools" :playground/reason-choose-tools
          "agent-bundle-evaluate-evidence" :playground/evaluate-evidence
          "agent-bundle-sufficiency-gates" :playground/check-sufficiency
          "agent-bundle-record-and-route" :playground/choose-next-step
          "query-planning" :playground/plan-query
          "query-planner" :playground/query-planning
          "retrieval" :playground/retrieval-search
          "rerank" :playground/result-rerank
          "overview-evidence-gate" :playground/evidence-check
          "overview-synthesis" :playground/answer-synthesis
          "overview-finalize" :playground/finalize-answer
          "search_documents" :playground/retrieval-search
          "read_chunks" :playground/chunk-read
          "rerank_results" :playground/result-rerank
          "generate_response" :playground/answer-synthesis
          "plan_queries" :playground/query-planning
          "analyze_corpus" :playground/corpus-analysis
          "propose_questions_for_chunk" :playground/propose-questions
          "apply_enrichments" :playground/apply-enrichments
          "run_eval_delta" :playground/eval-delta
          "enrichment-analyze-corpus" :playground/corpus-analysis
          "enrichment-propose-questions" :playground/propose-questions
          "enrichment-propose-phrases" :playground/propose-phrases
          "enrichment-apply-questions" :playground/apply-questions
          "enrichment-apply-phrases" :playground/apply-phrases
          "enrichment-eval-suite" :playground/eval-delta}
         label-key (or (get labels tool-name)
                       (get labels stage-name))]
     (cond
       label-key (tr language label-key)
       (= stage :agent-llm) (tr language :playground/llm-turn)
       (= stage :search) (tr language :playground/retrieval-search)
       (= stage :read_chunks) (tr language :playground/chunk-read)
       (= stage :rerank_results) (tr language :playground/result-rerank)
       (= stage :generate_response) (tr language :playground/answer-synthesis)
       (= stage :summarization) (tr language :playground/summarization)
       stage-name stage-name
       tool-name tool-name
       :else (tr language :playground/step)))))

(defn- event-kind
  "Derive an event kind from tool-call :stage and :tool. Robust to both
   string and keyword forms."
  [stage tool]
  (let [tool-str (cond
                   (keyword? tool) (name tool)
                   (string? tool) tool
                   :else nil)]
    (cond
      (= stage :agent-llm) :llm
      (= stage :search) :search
      (= stage :read_chunks) :read
      (= stage :generate_response) :synthesis
      (= stage :rerank_results) :rerank
      (= stage :summarization) :summarization
      (= stage :query-planner) :plan
      (= stage :retrieval) :search
      (= stage :rerank) :rerank
      (= stage :overview-evidence-gate) :sufficiency
      (= stage :overview-synthesis) :synthesis
      (= stage :overview-finalize) :finalize
      (= tool-str "search_documents") :search
      (= tool-str "read_chunks") :read
      (= tool-str "generate_response") :synthesis
      (= tool-str "plan_queries") :plan
      (= tool-str "rerank_results") :rerank
      ;; Self-improve-agent tools get distinct :kind values so the
      ;; EventDetail dispatch downstream can render structured panels
      ;; for each instead of falling through to the generic :tool view.
      (= tool-str "analyze_corpus") :enrichment-analyze
      (= tool-str "propose_questions_for_chunk") :enrichment-propose
      (= tool-str "apply_enrichments") :enrichment-apply
      (= tool-str "run_eval_delta") :enrichment-eval
      ;; Graph-variant skill-id names (added 2026-05-19). The playground
      ;; bridge synthesizes tool-call entries from `:step/completed`
      ;; events with `:tool (some-> skill-id name)`, so the dispatch
      ;; here matches the skill-id name segments. Both questions and
      ;; phrases enrichments share the same kinds — the propose/apply
      ;; detail panels handle the per-type field naming internally.
      (= tool-str "enrichment-analyze-corpus") :enrichment-analyze
      (= tool-str "enrichment-propose-questions") :enrichment-propose
      (= tool-str "enrichment-propose-phrases") :enrichment-propose
      (= tool-str "enrichment-apply-questions") :enrichment-apply
      (= tool-str "enrichment-apply-phrases") :enrichment-apply
      (= tool-str "enrichment-eval-suite") :enrichment-eval
      :else :tool)))

(def ^:private orchestration-summaries
  {"agent-setup" :playground/status-agent-setup
   "agent-iter-state" :playground/status-iteration-updated
   "agent-bundle-llm-and-tools" :playground/status-reasoned
   "agent-bundle-evaluate-evidence" :playground/status-evidence-evaluated
   "agent-bundle-sufficiency-gates" :playground/status-sufficiency-checked
   "agent-bundle-record-and-route" :playground/status-next-step
   "query-planning" :playground/status-query-planned})

(def ^:private internal-agent-tools
  "Graph plumbing that maintains the ReAct loop but is not a user-meaningful
   action. The live trace also carries the resulting LLM, search, read, rerank,
   synthesis, and other actual tool calls, so rendering these wrappers creates
   duplicate tabs without adding useful execution detail."
  #{"agent-setup"
    "agent-iter-state"
    "agent-bundle-llm-and-tools"
    "agent-bundle-evaluate-evidence"
    "agent-bundle-sufficiency-gates"
    "agent-bundle-record-and-route"})

(defn- tool-name [tool]
  (cond
    (keyword? tool) (name tool)
    (string? tool) tool
    :else "tool"))

(defn- user-visible-tool-call?
  [tool-call]
  (or (not (contains? internal-agent-tools (tool-name (:tool tool-call))))
      (false? (:ok? tool-call))
      (= :error (:status tool-call))))

(defn- json-like?
  [s]
  (and (string? s)
       (contains? #{\{ \[} (first (str/triml s)))))

(defn- friendly-event-summary
  "Keep orchestration payloads readable in the live timeline. Raw JSON remains
   available behind the explicit raw-data disclosure."
  ([tool full-summary]
   (friendly-event-summary (i18n/current-language) tool full-summary))
  ([language tool full-summary]
   (or (some->> (get orchestration-summaries (tool-name tool))
                (tr language))
       (when-not (json-like? full-summary)
         (some-> full-summary first-line (truncate 160))))))

(defn event-kind-icon
  "Small visual marker shared by iteration tabs and action rows."
  [kind]
  (case kind
    :llm "✦"
    :plan "⌘"
    :search "⌕"
    :read "▤"
    :rerank "⇅"
    :synthesis "✎"
    :summarization "≡"
    :sufficiency "◇"
    :finalize "✓"
    :enrichment-analyze "◎"
    :enrichment-propose "+"
    :enrichment-apply "✓"
    :enrichment-eval "∆"
    "⚙"))

(defn- parse-tool-json
  "Parse a tool's JSON `:result-summary` into a Clojure map. The Phase C
   self-improve tools (and Phase A `:builtin/enrichment-eval-suite`)
   all emit JSON via `(json/write-str ...)` so a structured parse
   gives the EventDetail panels rich data to render.

   Implemented for BOTH runtimes because `stream-view->view-model` is
   invoked inside `(e/client ...)` in `NextDetailedLive` — the parse
   happens client-side. An earlier version returned nil on cljs and
   manifested as empty / zero-filled enrichment detail panels even
   though the underlying tool calls had succeeded (screenshot
   regression 2026-05-19)."
  [s]
  #?(:clj  (try (when (string? s) (json/read-str s :key-fn keyword))
                (catch Throwable _ nil))
     :cljs (try (when (string? s)
                  (js->clj (.parse js/JSON s) :keywordize-keys true))
                (catch :default _ nil))))

(def ^:private enrichment-event-kinds
  "Set of event-kind values that originate from self-improve enrichment
   tools and therefore should have their JSON `:result-summary` parsed
   into a structured map. Centralized so the kind list and the
   parse-on-build path can't drift."
  #{:enrichment-analyze :enrichment-propose :enrichment-apply :enrichment-eval})

(defn- enrich-search-hits
  "Join parsed search hits with merged-chunks enrichment (chars, state, etc.)."
  [hits merged-by-id]
  (mapv (fn [hit]
          (if-let [extra (get merged-by-id (:chunk-id hit))]
            (merge hit extra)
            hit))
        hits))

(defn- tool-call->event
  ([tc signals-by-ids merged-by-id]
   (tool-call->event (i18n/current-language) tc signals-by-ids merged-by-id))
  ([language tc signals-by-ids merged-by-id]
   (let [tool (or (:tool tc) "tool")
         kind (event-kind (:stage tc) tool)
         full-summary (:result-summary tc)
         parsed-read (when (= :read kind) (parse-read-summary full-summary))
         chunk-id-set (when parsed-read
                        (set (keep :chunk-id (:chunks parsed-read))))
         read-signal (when (and chunk-id-set (seq signals-by-ids))
                       (get signals-by-ids chunk-id-set))
         parsed-search (when (= :search kind)
                         (let [p (parse-search-summary full-summary)]
                           (cond-> p
                             (and p (seq merged-by-id))
                             (update :hits enrich-search-hits merged-by-id))))
         ;; Self-improve enrichment tools all emit JSON `:result-summary`.
         ;; Parse to a keyword-keyed map so the per-kind EventDetail
         ;; panels can render structured fields directly instead of
         ;; falling back to the raw-string blob view.
         parsed-enrichment (when (contains? enrichment-event-kinds kind)
                             (parse-tool-json full-summary))]
     (cond-> {:kind kind
              :label (stage-friendly-label language (:stage tc) tool)
              :tool tool
              :status (if (false? (:ok? tc)) :error (or (:status tc) :ok))
              :duration-ms (:duration-ms tc)
              :summary (friendly-event-summary language tool full-summary)
              :full-summary full-summary
              :args-summary (:args-summary tc)
              :effective-parameters (:effective-parameters tc)
              :sub-skill (:sub-skill tc)
              :stage (:stage tc)}
       parsed-read (assoc :parsed-read parsed-read)
       parsed-search (assoc :parsed-search parsed-search)
       read-signal (assoc :read-signal read-signal)
       parsed-enrichment (assoc :parsed-enrichment parsed-enrichment)))))

(defn- iter-stage-timings
  [stage-timings iter-idx]
  (filter #(= iter-idx (:iteration %)) stage-timings))

(defn- iter-llm-event
  [language iter-timings turn]
  (when-let [llm (first (filter #(= :agent-llm (:stage %)) iter-timings))]
    (let [tool-calls (or (:tool-calls turn) [])
          tool-names (vec (keep :tool tool-calls))
          reasoning (:reasoning turn)
          summary (cond
                    (seq tool-names)
                    (str "→ " (str/join ", " tool-names))
                    (not (str/blank? (or reasoning "")))
                    (str "→ " (tr language :playground/final-answer))
                    :else nil)]
      {:kind :llm
       :label (tr language :playground/llm-turn)
       :status (or (:status llm) :ok)
       :duration-ms (:duration-ms llm)
       :detail (:detail llm)
       :input-length (:input-length llm)
       :output-length (:output-length llm)
       :usage (:usage llm)
       :llm-model (:llm-model llm)
       :finish-reason (:finish-reason llm)
       :summary summary
       :reasoning reasoning
       :triggered-tools tool-names})))

(defn- gist-for-turn
  [language turn suff]
  (let [tcs (or (:tool-calls turn) [])
        last-tc (last tcs)
        n (count tcs)
        base (cond
               (zero? n) (tr language :playground/no-tool-calls)
               (= n 1) (stage-friendly-label language (:stage last-tc) (:tool last-tc))
               :else (str (tr language :playground/tool-calls-count n) " · "
                          (stage-friendly-label language (:stage last-tc) (:tool last-tc))))]
    (if-let [status (:status suff)]
      (str base " · " (name status))
      base)))

(defn- turn-duration
  [iter-timings]
  (let [ms (keep :duration-ms iter-timings)]
    (when (seq ms) (reduce + 0 ms))))

(defn- iteration-status
  [events suff]
  (cond
    (some #(= :error (:status %)) events) :error
    (and suff (contains? #{:insufficient :conflicting :off-topic} (:status suff)))
    (:status suff)
    (and suff (= :sufficient (:status suff))) :sufficient
    :else :ok))

(defn- build-iteration
  [language turn stage-timings suff-by-iter signals-by-ids merged-by-id]
  (let [iter-idx (:iteration turn)
        iter-timings (iter-stage-timings stage-timings iter-idx)
        llm-event (iter-llm-event language iter-timings turn)
        visible-tool-calls (filterv user-visible-tool-call?
                                    (or (:tool-calls turn) []))
        tool-events (mapv #(tool-call->event language % signals-by-ids merged-by-id)
                          visible-tool-calls)
        events (vec (concat (when llm-event [llm-event]) tool-events))
        suff (get suff-by-iter iter-idx)
        visible-turn (assoc turn :tool-calls visible-tool-calls)]
    {:index iter-idx
     :status (iteration-status events suff)
     :gist (gist-for-turn language visible-turn suff)
     :duration-ms (turn-duration iter-timings)
     :reasoning (:reasoning turn)
     :events events
     :sufficiency suff}))

(defn coalesce-trace-turns
  "Merge adjacent trace fragments from the same agent iteration.

   Graph-backed agents emit one trace record per orchestration step. Treating
   each fragment as a separate iteration produced rows of repeated `Iter 1`
   tabs with internal skill IDs. Coalescing restores the user-facing semantic
   unit: one iteration containing an ordered sequence of meaningful events."
  [trace]
  (reduce
   (fn [turns turn]
     (if (and (seq turns)
              (= (:iteration (peek turns)) (:iteration turn)))
       (update turns (dec (count turns))
               (fn [previous]
                 (cond-> (update previous :tool-calls
                                  #(vec (concat (or % []) (or (:tool-calls turn) []))))
                   (not (str/blank? (or (:reasoning turn) "")))
                   (assoc :reasoning (:reasoning turn)))))
       (conj turns (update turn :tool-calls #(vec (or % []))))))
   []
   (or trace [])))

(defn- cumulative-chunks-read
  [iterations]
  (reduce + 0
          (for [it iterations
                ev (:events it)
                :when (= :read (:kind ev))
                :let [n (extract-chunk-count (:summary ev))]
                :when n]
            n)))

(defn- derive-live-status
  [stream-view]
  (cond
    (:error stream-view) :error
    (:running? stream-view) :running
    (:waiting? stream-view) :running
    (:has-execution? stream-view) :ok
    :else :pending))

(defn- derive-postrun-status
  [diagnostics]
  (case (:status diagnostics)
    :ok :ok
    :error :error
    :failed :error
    :needs_clarification :clarification
    :ok))

(defn- first-non-empty
  "Return the first collection that actually contains data.

   Diagnostics intentionally persist empty agent collections for non-agent
   graphs. Plain `or` treats those empty vectors as truthy and used to mask the
   graph-level timing data in completed responses."
  [& collections]
  (or (some #(when (seq %) %) collections) []))

(def ^:private structured-action-kinds
  #{:plan :search :rerank :sufficiency :synthesis :finalize})

(defn- canonical-action->view
  [language action]
  (let [skill-id (:skill-id action)
        kind (or (:kind action) :tool)
        result (:result action)]
    (cond-> {:id (:id action)
             :kind kind
             :label (stage-friendly-label language nil skill-id)
             :tool (some-> skill-id name)
             :status (or (:status action) :ok)
             :duration-ms (:duration-ms action)
             :action-result result
             :error (:error action)}
      (contains? enrichment-event-kinds kind)
      (assoc :parsed-enrichment result))))

(defn- legacy-stage-timings->actions
  "Compatibility-only rendering for conversations saved before the canonical
   action trace. Timings remain visible, but missing results are not inferred
   from unrelated aggregate diagnostics."
  [language stage-timings]
  (mapv (fn [{:keys [step-id skill-id stage status duration-ms]}]
          (let [tool (or skill-id stage)]
            {:id (or step-id stage)
             :kind (event-kind stage tool)
             :label (stage-friendly-label language stage tool)
             :tool (some-> tool name)
             :status (case status :failed :error (or status :ok))
             :duration-ms duration-ms
             :legacy? true}))
        (or stage-timings [])))

(defn stream-view->view-model
  ([stream-view]
   (stream-view->view-model (i18n/current-language) stream-view))
  ([language stream-view]
   (let [trace (first-non-empty (:live-agent-trace stream-view))
         stage-timings (first-non-empty (:live-agent-stage-timings stream-view)
                                        (:execution-stage-timings stream-view))
         iterations (mapv #(build-iteration language % stage-timings {} {} {})
                          (coalesce-trace-turns trace))
         canonical-actions (mapv #(canonical-action->view language %)
                                 (or (:action-trace stream-view) []))
         actions (if (seq canonical-actions)
                   canonical-actions
                   (when (empty? iterations)
                     (legacy-stage-timings->actions language stage-timings)))
         derived-tool-calls (+ (count actions)
                               (reduce + 0 (map #(count (:events %)) iterations)))]
     {:mode :live
      :language (i18n/normalize-language language)
      :status (derive-live-status stream-view)
      :elapsed-ms (:elapsed-ms stream-view)
      :cumulative {:iterations (if (seq actions) 1 (count iterations))
                   :tool-calls (max (or (:tool-call-count stream-view) 0)
                                    derived-tool-calls)
                   :chunks-read (cumulative-chunks-read iterations)
                   :warnings (:warning-count stream-view)}
      :latest-sufficiency nil
      :cumulative-sufficiency-decisions []
      :live-thinking (:live-thinking stream-view)
      :actions actions
      :iterations iterations})))

(defn diagnostics->view-model
  ([diagnostics]
   (diagnostics->view-model (i18n/current-language) diagnostics))
  ([language diagnostics]
   (let [stage-timings (first-non-empty
                        (:agent-stage-timings diagnostics)
                        (:execution-stage-timings diagnostics)
                        (get-in diagnostics [:skill-execution-metadata :stage-timings]))
         trace (first-non-empty (:agent-trace diagnostics)
                                (:iteration-history diagnostics))
         decisions (or (:sufficiency-decisions diagnostics) [])
         suff-by-iter (reduce (fn [acc d]
                                (let [i (:iteration d)]
                                  (if (number? i) (assoc acc i d) acc)))
                              {} decisions)
         signals-by-ids (read-signals-by-chunk-ids (:read-evaluations diagnostics))
         merged-by-id (merged-chunks-index diagnostics)
         iterations (mapv #(build-iteration language % stage-timings suff-by-iter
                                            signals-by-ids merged-by-id)
                          (coalesce-trace-turns trace))
         canonical-actions (mapv #(canonical-action->view language %)
                                 (or (:action-trace diagnostics) []))
         actions (if (seq canonical-actions)
                   canonical-actions
                   (when (empty? iterations)
                     (legacy-stage-timings->actions language stage-timings)))
         chunks-read (cumulative-chunks-read iterations)
         unique-docs (count (distinct (keep :doc_num (or (:used-chunks diagnostics) []))))
         warnings (count (or (:warnings diagnostics) []))
         issues (count (or (:backend-issues diagnostics) []))]
     {:mode :post-run
      :language (i18n/normalize-language language)
      :status (derive-postrun-status diagnostics)
      :elapsed-ms (:total-duration-ms diagnostics)
      :cumulative {:iterations (if (seq actions) 1 (count iterations))
                   :tool-calls (+ (count actions)
                                  (reduce + 0 (map #(count (filter (fn [e] (not= :llm (:kind e)))
                                                                   (:events %)))
                                                   iterations)))
                   :chunks-read chunks-read
                   :unique-docs unique-docs
                   :warnings (+ warnings issues)}
      :latest-sufficiency (last decisions)
      :cumulative-sufficiency-decisions decisions
      :shadow-sufficiency-decisions (or (:shadow-sufficiency-decisions diagnostics) [])
      :response-validations (or (:response-validations diagnostics) [])
      :open-evidence-gaps (or (:open-evidence-gaps diagnostics) [])
      :actions actions
      :iterations iterations})))

(defn default-shell-expanded?
  "Keep live work visible, but collapse the detailed view once work finishes.
   A user's explicit toggle takes precedence in the shell component."
  [vm]
  (= :running (:status vm)))

(defn timeline-actions
  "Flatten iteration events into the user-visible action tabs while retaining
   the iteration context needed by the selected action's detail panel."
  [iterations]
  (->> (or iterations [])
       (mapcat (fn [iteration]
                 (map (fn [event]
                        (assoc event
                               :iteration-index (:index iteration)
                               :iteration-reasoning (:reasoning iteration)
                               :iteration-sufficiency (:sufficiency iteration)))
                      (or (:events iteration) []))))
       vec))

;; =============================================================================
;; Primitives
;; =============================================================================

(e/defn MarkdownBlock
  "Render semantic status content as the same safe Markdown used by chat replies."
  [content style]
  (e/client
   (when-not (str/blank? (or content ""))
     (let [html (e/server (base/render-markdown-to-html (e/client content)))]
       (dom/div
        (dom/props {:style style})
        (when html
          (base/set-markdown-html! dom/node html)))))))

(e/defn StatusDot
  [status pulse?]
  (e/client
   (let [pal (status-palette status)]
     (dom/span
      (dom/props {:style (cond-> {:width "8px"
                                  :height "8px"
                                  :border-radius "50%"
                                  :display "inline-block"
                                  :flex "0 0 auto"
                                  :background (:dot pal)}
                           pulse? (assoc :box-shadow
                                         (str "0 0 0 0 " (:dot pal))
                                         :animation "pulse 1.6s ease-in-out infinite"))})
      (dom/text "")))))

(e/defn Pill
  [status label]
  (e/client
   (let [pal (status-palette status)]
     (dom/span
      (dom/props {:style {:padding "0.08rem 0.4rem"
                          :background (:bg pal)
                          :color (:text pal)
                          :border (str "1px solid " (:border pal))
                          :border-radius "999px"
                          :font-size "0.6rem"
                          :font-weight "700"
                          :text-transform "uppercase"
                          :letter-spacing "0.02em"
                          :white-space "nowrap"
                          :flex "0 0 auto"}})
      (dom/text label)))))

(e/defn MetricItem
  [label value]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex"
                        :flex-direction "column"
                        :gap "0.05rem"
                        :min-width "0"}})
    (dom/span
     (dom/props {:style {:font-size "0.58rem"
                         :font-weight "700"
                         :text-transform "uppercase"
                         :letter-spacing "0.02em"
                         :color "#64748b"}})
     (dom/text label))
    (dom/span
     (dom/props {:style {:font-size "0.78rem"
                         :font-weight "700"
                         :color "#0f172a"
                         :font-variant-numeric "tabular-nums"}})
     (dom/text (str (or value "–")))))))

;; =============================================================================
;; RunHeader
;; =============================================================================

(e/defn RunHeader
  [vm expanded toggle!]
  (e/client
   (let [{:keys [status elapsed-ms cumulative latest-sufficiency mode]} vm
         language (:language vm)
         pal (status-palette status)
         elapsed (format-ms elapsed-ms)
         suff-status (:status latest-sufficiency)]
     (dom/div
      (dom/props (cond-> {:style {:display "flex"
                                  :align-items "center"
                                  :justify-content "space-between"
                                  :gap "0.75rem"
                                  :padding "0.55rem 0.75rem"
                                  :background (:bg pal)
                                  :border (str "1px solid " (:border pal))
                                  :border-radius "6px"
                                  :margin-bottom "0.5rem"}}
                   toggle! (assoc-in [:style :cursor] "pointer")
                   toggle! (assoc-in [:style :user-select] "none")))
      (when toggle!
        (dom/On "click" (fn [_] (toggle!)) nil))
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.5rem"
                           :min-width "0"}})
       (when toggle!
         (dom/span
          (dom/props {:style {:width "0.9rem"
                              :display "inline-flex"
                              :justify-content "center"
                              :font-size "1rem"
                              :color (:text pal)
                              :flex "0 0 auto"}})
          (dom/text (if expanded "▼" "▶"))))
       (StatusDot status (= status :running))
       (dom/span
        (dom/props {:style {:font-size "1rem"
                            :font-weight "700"
                            :text-transform "uppercase"
                            :letter-spacing "0.04em"
                            :color (:text pal)}})
        (dom/text (case status
                    :running (tr language :playground/status-running)
                    :ok (tr language :playground/status-complete)
                    :error (tr language :playground/status-failed)
                    :clarification (tr language :playground/status-clarification)
                    :pending (tr language :playground/status-idle)
                    (name (or status :unknown)))))
       (when suff-status
         (Pill suff-status (name suff-status))))
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "1rem"
                           :flex-wrap "wrap"}})
       (MetricItem (tr language :playground/elapsed) (or elapsed "–"))
       (MetricItem (tr language :playground/iterations) (:iterations cumulative))
       (MetricItem (tr language :playground/tool-calls) (:tool-calls cumulative))
       (MetricItem (tr language :playground/chunks-read) (:chunks-read cumulative))
       (when (= mode :post-run)
         (MetricItem (tr language :playground/unique-docs) (:unique-docs cumulative)))
       (when (pos? (or (:warnings cumulative) 0))
         (MetricItem (tr language :playground/warnings) (:warnings cumulative))))))))

;; =============================================================================
;; SufficiencyDigest
;; =============================================================================

(e/defn SufficiencyDetailList
  [title items render-item]
  (e/client
   (when (seq items)
     (dom/div
      (dom/props {:style {:margin-top "0.35rem"}})
      (dom/div
       (dom/props {:style {:font-size "0.62rem"
                           :font-weight "700"
                           :text-transform "uppercase"
                           :letter-spacing "0.02em"
                           :color "#64748b"
                           :margin-bottom "0.15rem"}})
       (dom/text title))
      (dom/ul
       (dom/props {:style {:margin "0"
                           :padding-left "1.1rem"
                           :font-size "1rem"
                           :color "#374151"}})
       (e/for [item (e/diff-by identity (vec items))]
         (dom/li
          (dom/text (render-item item)))))))))

(e/defn SufficiencyDetail
  [decision]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex"
                        :flex-direction "column"
                        :gap "0.2rem"}})
    (when-let [reason (:reasoning decision)]
      (when-not (str/blank? reason)
        (MarkdownBlock reason {:font-size "1rem"
                               :color "#1f2937"
                               :line-height "1.4"})))
    (SufficiencyDetailList (t :playground/missing-info)
                           (:missing-info decision)
                           (fn [s] (str s)))
    (SufficiencyDetailList (t :playground/missing-claims)
                           (:missing-claims decision)
                           (fn [s] (str s)))
    (when (:contradiction-detected? decision)
      (dom/div
       (dom/props {:style {:margin-top "0.3rem"
                           :padding "0.3rem 0.4rem"
                           :background "#fef2f2"
                           :border "1px solid #fecaca"
                           :color "#991b1b"
                           :border-radius "4px"
                           :font-size "0.68rem"}})
       (dom/text (t :playground/contradiction-detected)))))))

(e/defn SufficiencyDigest
  [vm]
  (e/client
   (let [latest (:latest-sufficiency vm)
         all (or (:cumulative-sufficiency-decisions vm) [])
         gaps (or (:open-evidence-gaps vm) [])
         !expanded (atom false)
         expanded (e/watch !expanded)]
     (when (or latest (seq gaps) (seq all))
       (let [status (or (:status latest) :pending)
             pal (status-palette status)
             headline (cond
                        latest
                        (str (name (or (:status latest) :pending))
                             (when-let [a (:action latest)] (str " → " (name a))))
                        (seq gaps) "open gaps"
                        :else "no sufficiency data")]
         (dom/div
          (dom/props {:style {:margin-bottom "0.5rem"
                              :padding "0.4rem 0.55rem"
                              :background (:bg pal)
                              :border (str "1px solid " (:border pal))
                              :border-radius "6px"}})
          (dom/div
           (dom/props {:style {:display "flex"
                               :align-items "center"
                               :gap "0.4rem"
                               :cursor "pointer"
                               :user-select "none"}})
           (dom/On "click" (fn [_] (swap! !expanded not)) nil)
           (dom/span
            (dom/props {:style {:width "0.9rem"
                                :display "inline-flex"
                                :justify-content "center"
                                :font-size "0.62rem"
                                :color "#64748b"}})
            (dom/text (if expanded "▼" "▶")))
           (StatusDot status false)
           (dom/span
            (dom/props {:style {:font-size "0.68rem"
                                :font-weight "700"
                                :text-transform "uppercase"
                                :letter-spacing "0.02em"
                                :color (:text pal)}})
            (dom/text (t :playground/sufficiency)))
           (dom/span
            (dom/props {:style {:font-size "1rem"
                                :color "#475569"
                                :overflow "hidden"
                                :text-overflow "ellipsis"
                                :white-space "nowrap"
                                :min-width "0"
                                :flex "1 1 auto"}})
            (dom/text headline))
           (when (seq all)
             (Pill :pending (str (count all) " decisions"))))
          (when expanded
            (dom/div
             (dom/props {:style {:margin-top "0.35rem"
                                 :padding-top "0.35rem"
                                 :border-top (str "1px solid " (:border pal))}})
             (when latest (SufficiencyDetail latest))
             (SufficiencyDetailList (t :playground/open-evidence-gaps)
                                    gaps
                                    (fn [g] (str (or (:claim g) (:description g) (pr-str g)))))))))))))

;; =============================================================================
;; EventRow
;; =============================================================================

(e/defn KeyValueRow
  [k v]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex" :gap "0.4rem" :font-size "0.66rem"}})
    (dom/span
     (dom/props {:style {:color "#64748b" :min-width "7rem" :flex "0 0 auto"}})
     (dom/text (str k)))
    (dom/span
     (dom/props {:style {:color "#1f2937"
                         :font-family "monospace"
                         :word-break "break-all"}})
     (dom/text (str v))))))

(defn- non-empty-headers?
  "Headers string is meaningful if it isn't just empty braces or whitespace."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (re-matches #"\s*\{\s*\}\s*" s))))

(e/defn SearchHitRow
  [hit on-select-chunk]
  (e/client
   (let [clickable? (and on-select-chunk (:chunk-id hit))
         read? (:read? hit)
         state-label (cond
                       (nil? read?) nil
                       read? "read"
                       :else "metadata only")
         state-color (if read? "#166534" "#92400e")
         state-bg (if read? "#dcfce7" "#fef3c7")
         search-types (or (:search-type-labels hit) [])
         why-labels (or (:retrieval-labels hit) [])
         headers (:headers-raw hit)
         has-headers? (non-empty-headers? headers)]
     (dom/div
      (dom/props (cond-> {:style {:display "flex"
                                  :align-items "flex-start"
                                  :gap "0.5rem"
                                  :padding "0.4rem 0.5rem"
                                  :font-size "0.68rem"
                                  :border-bottom "1px solid #f1f5f9"
                                  :min-width "0"}}
                   clickable? (assoc-in [:style :cursor] "pointer")))
      (when clickable?
        (dom/On "click" (fn [_] (on-select-chunk (:chunk-id hit))) nil))
      (dom/span
       (dom/props {:style {:color "#64748b"
                           :font-family "monospace"
                           :min-width "1.6rem"
                           :flex "0 0 auto"
                           :padding-top "0.05rem"}})
       (dom/text (str (:rank-n hit) ".")))
      (dom/div
       (dom/props {:style {:flex "1 1 auto"
                           :min-width "0"
                           :display "flex"
                           :flex-direction "column"
                           :gap "0.1rem"}})
       ;; Row 1: title
       (dom/div
        (dom/props {:style {:font-weight "600"
                            :color (if clickable? "#2563eb" "#0f172a")
                            :overflow "hidden"
                            :text-overflow "ellipsis"
                            :white-space "nowrap"}})
        (dom/text (or (:title hit) (:chunk-id hit) "")))
       ;; Row 2: metadata + state + search-types + why (all inline)
       (dom/div
        (dom/props {:style {:display "flex"
                            :flex-wrap "wrap"
                            :align-items "center"
                            :gap "0.35rem"
                            :font-size "0.6rem"
                            :color "#64748b"
                            :font-family "monospace"}})
        (when-let [ci (:chunk-index hit)]
          (dom/span (dom/text (str "chunk " ci))))
        (when-let [cl (:content-length hit)]
          (dom/span (dom/text (str cl " chars"))))
        (when-let [dn (:doc-num hit)]
          (dom/span (dom/text (str "doc=" (truncate dn 10)))))
        (when state-label
          (dom/span
           (dom/props {:style {:padding "0.05rem 0.35rem"
                               :background state-bg
                               :color state-color
                               :border-radius "3px"
                               :font-weight "700"}})
           (dom/text state-label)))
        (when (seq search-types)
          (e/for [[idx t] (e/diff-by first (map-indexed vector search-types))]
            (dom/span
             (dom/props {:style {:padding "0.02rem 0.3rem"
                                 :background "#eef2ff"
                                 :color "#4338ca"
                                 :border-radius "3px"
                                 :font-size "0.56rem"
                                 :font-weight "700"
                                 :text-transform "uppercase"}})
             (dom/text t))))
        (when (seq why-labels)
          (dom/span
           (dom/props {:style {:color "#64748b"
                               :font-style "italic"
                               :font-family "inherit"}})
           (dom/text (str "why: " (str/join ", " why-labels))))))
       ;; Row 3 (conditional): headers — only when non-empty
       (when has-headers?
         (dom/div
          (dom/props {:style {:font-size "0.58rem"
                              :color "#6366f1"
                              :overflow "hidden"
                              :text-overflow "ellipsis"
                              :white-space "nowrap"}})
          (dom/text (truncate headers 140)))))
      ;; Right column: rerank + score on one horizontal line
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.3rem"
                           :flex "0 0 auto"
                           :padding-top "0.05rem"}})
       (when-let [rs (:rerank-score hit)]
         (dom/span
          (dom/props {:style {:font-family "monospace"
                              :color "#7c3aed"
                              :background "#f5f3ff"
                              :padding "0.05rem 0.35rem"
                              :border-radius "3px"
                              :font-size "0.62rem"
                              :font-weight "700"
                              :white-space "nowrap"}})
          (dom/text (str "rerank " rs))))
       (when-let [score (:score hit)]
         (dom/span
          (dom/props {:style {:font-family "monospace"
                              :color "#059669"
                              :background "#ecfdf5"
                              :padding "0.05rem 0.35rem"
                              :border-radius "3px"
                              :font-size "0.6rem"
                              :font-weight "700"
                              :white-space "nowrap"}})
          (dom/text (str "score " score)))))))))

(e/defn SearchEventDetail
  [event on-select-chunk]
  (e/client
   (let [!show-all (atom false)
         show-all (e/watch !show-all)
         !show-params (atom false)
         show-params (e/watch !show-params)
         parsed (or (:parsed-search event)
                    (parse-search-summary (:full-summary event)))
         all-hits (or (:hits parsed) [])
         total (count all-hits)
         visible-hits (if show-all all-hits (vec (take 10 all-hits)))
         rerank (:rerank parsed)
         auto-filter (:auto-filter parsed)
         omitted (:omitted-count parsed)]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.3rem"}})
      (when-let [h (:headline parsed)]
        (dom/div
         (dom/props {:style {:font-size "1rem"
                             :color "#334155"
                             :font-weight "600"}})
         (dom/text h)))
      (when (or rerank auto-filter (and omitted (pos? omitted)))
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.3rem"
                             :align-items "center"}})
         (case (:state rerank)
           :ok
           (dom/span
            (dom/props {:style {:padding "0.08rem 0.4rem"
                                :background "#f5f3ff"
                                :color "#5b21b6"
                                :border "1px solid #ddd6fe"
                                :border-radius "3px"
                                :font-size "0.62rem"
                                :font-weight "700"
                                :font-family "monospace"}})
            (dom/text (str "ColBERT rerank"
                           (when-let [ms (:ms rerank)] (str " · " ms "ms")))))

           :failed
           (dom/span
            (dom/props {:style {:padding "0.08rem 0.4rem"
                                :background "#fef2f2"
                                :color "#991b1b"
                                :border "1px solid #fecaca"
                                :border-radius "3px"
                                :font-size "0.62rem"
                                :font-weight "700"}})
            (dom/text (str "rerank failed"
                           (when-let [r (:reason rerank)]
                             (str " — " (truncate r 80))))))

           nil)
         (when auto-filter
           (dom/span
            (dom/props {:style {:padding "0.08rem 0.4rem"
                                :background "#eff6ff"
                                :color "#1e40af"
                                :border "1px solid #bfdbfe"
                                :border-radius "3px"
                                :font-size "0.62rem"
                                :font-weight "600"}})
            (dom/text (str "filter: " (:field auto-filter) "="
                           (str/join "," (or (:values auto-filter) []))
                           (when (:fallback? auto-filter) " (fell back)")))))
         (when (and omitted (pos? omitted))
           (dom/span
            (dom/props {:style {:font-size "0.62rem"
                                :color "#64748b"
                                :font-style "italic"}})
            (dom/text (str omitted " lower-ranked chunks omitted"))))))
      (when (seq visible-hits)
        (dom/div
         (dom/props {:style {:border "1px solid #e5e7eb"
                             :border-radius "5px"
                             :background "white"
                             :overflow "hidden"}})
         (e/for [[idx hit] (e/diff-by first (map-indexed vector visible-hits))]
           (SearchHitRow hit on-select-chunk))))
      (when (> total 10)
        (dom/span
         (dom/props {:style {:font-size "0.64rem"
                             :color "#3b82f6"
                             :cursor "pointer"
                             :user-select "none"
                             :text-decoration "underline"
                             :text-decoration-style "dotted"
                             :align-self "flex-start"}})
         (dom/On "click" (fn [_] (swap! !show-all not)) nil)
         (dom/text (if show-all "show top 10" (str "show all " total)))))
      (when (:effective-parameters event)
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"}})
         (dom/span
          (dom/props {:style {:font-size "0.62rem"
                              :color "#3b82f6"
                              :cursor "pointer"
                              :user-select "none"
                              :text-decoration "underline"
                              :text-decoration-style "dotted"
                              :align-self "flex-start"}})
          (dom/On "click" (fn [_] (swap! !show-params not)) nil)
          (dom/text (if show-params "hide params" "show params")))
         (when show-params
           (dom/div
            (dom/props {:style {:padding "0.35rem 0.45rem"
                                :background "#f8fafc"
                                :border "1px solid #e5e7eb"
                                :border-radius "4px"
                                :font-size "0.62rem"
                                :font-family "monospace"
                                :white-space "pre-wrap"
                                :color "#1f2937"}})
            (dom/text (pr-str (:effective-parameters event)))))))))))

(e/defn ReadChunkRow
  [chunk]
  (e/client
   (let [!expanded (atom false)
         expanded (e/watch !expanded)
         content (or (:content chunk) "")
         content-len (count content)]
     (dom/div
      (dom/props {:style {:border-bottom "1px solid #f1f5f9"
                          :padding "0.3rem 0.4rem"}})
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "baseline"
                           :gap "0.4rem"
                           :cursor "pointer"
                           :user-select "none"}})
       (dom/On "click" (fn [_] (swap! !expanded not)) nil)
       (dom/span
        (dom/props {:style {:width "0.9rem"
                            :font-size "0.62rem"
                            :color "#64748b"
                            :flex "0 0 auto"}})
        (dom/text (if expanded "▼" "▶")))
       (dom/div
        (dom/props {:style {:flex "1 1 auto" :min-width "0"}})
        (dom/div
         (dom/props {:style {:font-weight "600"
                             :color "#0f172a"
                             :font-size "1rem"
                             :overflow "hidden"
                             :text-overflow "ellipsis"
                             :white-space "nowrap"}})
         (dom/text (or (:title chunk) (:chunk-id chunk) "")))
        (dom/div
         (dom/props {:style {:font-size "0.6rem"
                             :color "#64748b"
                             :font-family "monospace"
                             :overflow "hidden"
                             :text-overflow "ellipsis"
                             :white-space "nowrap"}})
         (dom/text (str (when-let [ci (:chunk-index chunk)] (str "chunk " ci))
                        (when (pos? content-len) (str " · " content-len " chars"))
                        (when-let [dn (:doc-num chunk)] (str " · doc=" (truncate dn 10)))))))
       (when-let [u (:url chunk)]
         (dom/span
          (dom/props {:style {:font-size "0.58rem"
                              :color "#6366f1"
                              :font-family "monospace"
                              :overflow "hidden"
                              :text-overflow "ellipsis"
                              :white-space "nowrap"
                              :max-width "22rem"
                              :flex "0 1 auto"}})
          (dom/text u))))
      (when expanded
        (MarkdownBlock content {:margin-top "0.35rem"
                                :padding "0.35rem 0.45rem"
                                :background "#f8fafc"
                                :border "1px solid #e5e7eb"
                                :border-radius "4px"
                                :font-size "0.66rem"
                                :color "#1f2937"
                                :max-height "24rem"
                                :overflow "auto"
                                :line-height "1.45"}))))))

(e/defn ReadSignalGapRow
  [gap]
  (e/client
   (let [critical? (:critical? gap)
         pal (status-palette (if critical? :error :warning))]
     (dom/li
      (dom/props {:style {:color (:text pal)}})
      (dom/text (str (or (:claim-id gap) "(claim)")
                     (when (:reason gap) (str " · " (name (:reason gap))))
                     (when critical? " · CRITICAL")
                     (when-let [t (:text gap)] (str " — " t))))))))

(e/defn ReadSignalSupportRow
  [claim]
  (e/client
   (dom/li
    (dom/text (str (or (:claim-id claim) "(claim)")
                   (when-let [s (:support-level claim)] (str " · " (name s)))
                   (when-let [cs (seq (:chunk-ids claim))]
                     (str " [" (count cs) " chunks]")))))))

(e/defn ReadSignalPanel
  [signal]
  (e/client
   (let [status (or (:status signal) :unclear)
         pal (status-palette (case status
                               :support-found :ok
                               :gap-remaining :warning
                               :conflicting :error
                               :unclear :skipped
                               :skipped))
         !expanded (atom false)
         expanded (e/watch !expanded)
         conf (:confidence signal)
         conf-str (when (number? conf) (.toFixed (double conf) 2))
         summary-parts (cond-> []
                         true (conj (name status))
                         (:scope-assessment signal) (conj (str "scope: " (name (:scope-assessment signal))))
                         (:next-action-hint signal) (conj (str "→ " (name (:next-action-hint signal))))
                         conf-str (conj (str "conf " conf-str))
                         (:degraded? signal) (conj "degraded"))
         headline (str/join " · " summary-parts)
         supported (or (:supported-claims signal) [])
         gaps (or (:remaining-gaps signal) [])
         contradictions (or (:contradictions signal) [])
         has-detail? (or (seq supported) (seq gaps) (seq contradictions))]
     (dom/div
      (dom/props {:style {:padding "0.3rem 0.4rem"
                          :background (:bg pal)
                          :border (str "1px dashed " (:border pal))
                          :border-radius "5px"}})
      (dom/div
       (dom/props (cond-> {:style {:display "flex"
                                   :align-items "center"
                                   :gap "0.4rem"}}
                    has-detail? (assoc-in [:style :cursor] "pointer")
                    has-detail? (assoc-in [:style :user-select] "none")))
       (when has-detail?
         (dom/On "click" (fn [_] (swap! !expanded not)) nil))
       (dom/span
        (dom/props {:style {:width "0.9rem"
                            :font-size "0.62rem"
                            :color "#64748b"
                            :flex "0 0 auto"}})
        (dom/text (if has-detail? (if expanded "▼" "▶") "·")))
       (StatusDot status false)
       (dom/span
        (dom/props {:style {:font-size "0.68rem"
                            :font-weight "700"
                            :text-transform "uppercase"
                            :letter-spacing "0.02em"
                            :color (:text pal)
                            :flex "0 0 auto"}})
        (dom/text (t :playground/read-signal)))
       (dom/span
        (dom/props {:style {:font-size "0.68rem"
                            :color "#475569"
                            :overflow "hidden"
                            :text-overflow "ellipsis"
                            :white-space "nowrap"
                            :min-width "0"
                            :flex "1 1 auto"}})
        (dom/text headline)))
      (when (and has-detail? expanded)
        (dom/div
         (dom/props {:style {:margin-top "0.3rem"
                             :padding-top "0.3rem"
                             :border-top (str "1px dashed " (:border pal))
                             :display "flex"
                             :flex-direction "column"
                             :gap "0.3rem"}})
         (when (seq supported)
           (dom/div
            (dom/div
             (dom/props {:style {:font-size "0.62rem"
                                 :font-weight "700"
                                 :text-transform "uppercase"
                                 :letter-spacing "0.02em"
                                 :color "#64748b"
                                 :margin-bottom "0.15rem"}})
             (dom/text (t :playground/supported-claims (count supported))))
            (dom/ul
             (dom/props {:style {:margin "0"
                                 :padding-left "1.1rem"
                                 :font-size "1rem"
                                 :color "#065f46"}})
             (e/for [[idx c] (e/diff-by first (map-indexed vector supported))]
               (ReadSignalSupportRow c)))))
         (when (seq gaps)
           (dom/div
            (dom/div
             (dom/props {:style {:font-size "0.62rem"
                                 :font-weight "700"
                                 :text-transform "uppercase"
                                 :letter-spacing "0.02em"
                                 :color "#64748b"
                                 :margin-bottom "0.15rem"}})
             (dom/text (t :playground/remaining-gaps (count gaps))))
            (dom/ul
             (dom/props {:style {:margin "0"
                                 :padding-left "1.1rem"
                                 :font-size "1rem"}})
             (e/for [[idx g] (e/diff-by first (map-indexed vector gaps))]
               (ReadSignalGapRow g)))))
         (when (seq contradictions)
           (dom/div
            (dom/div
             (dom/props {:style {:font-size "0.62rem"
                                 :font-weight "700"
                                 :text-transform "uppercase"
                                 :letter-spacing "0.02em"
                                 :color "#64748b"
                                 :margin-bottom "0.15rem"}})
             (dom/text (t :playground/contradictions (count contradictions))))
            (dom/ul
             (dom/props {:style {:margin "0"
                                 :padding-left "1.1rem"
                                 :font-size "1rem"
                                 :color "#991b1b"}})
             (e/for [[idx c] (e/diff-by first (map-indexed vector contradictions))]
               (dom/li
                (dom/text (str (or (:claim-id c) "(claim)")
                               (when-let [s (:summary c)] (str " — " s)))))))))))))))

(e/defn ReadEventDetail
  [event]
  (e/client
   (let [!show-params (atom false)
         show-params (e/watch !show-params)
         parsed (or (:parsed-read event)
                    (parse-read-summary (:full-summary event)))
         chunks (or (:chunks parsed) [])
         signal (:read-signal event)]
     (dom/div
      (dom/props {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.3rem"}})
      (when-let [h (:headline parsed)]
        (dom/div
         (dom/props {:style {:font-size "1rem"
                             :color "#334155"
                             :font-weight "600"}})
         (dom/text h)))
      (when (seq chunks)
        (dom/div
         (dom/props {:style {:border "1px solid #e5e7eb"
                             :border-radius "5px"
                             :background "white"
                             :overflow "hidden"}})
         (e/for [[idx ch] (e/diff-by first (map-indexed vector chunks))]
           (ReadChunkRow ch))))
      (when signal (ReadSignalPanel signal))
      (when (:effective-parameters event)
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"}})
         (dom/span
          (dom/props {:style {:font-size "0.62rem"
                              :color "#3b82f6"
                              :cursor "pointer"
                              :user-select "none"
                              :text-decoration "underline"
                              :text-decoration-style "dotted"
                              :align-self "flex-start"}})
          (dom/On "click" (fn [_] (swap! !show-params not)) nil)
          (dom/text (if show-params "hide params" "show params")))
         (when show-params
           (dom/div
            (dom/props {:style {:padding "0.35rem 0.45rem"
                                :background "#f8fafc"
                                :border "1px solid #e5e7eb"
                                :border-radius "4px"
                                :font-size "0.62rem"
                                :font-family "monospace"
                                :white-space "pre-wrap"
                                :color "#1f2937"}})
            (dom/text (pr-str (:effective-parameters event)))))))))))

(e/defn SynthesisEventDetail
  [event]
  (e/client
   (let [!show-params (atom false)
         show-params (e/watch !show-params)
         full (:full-summary event)]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.3rem"}})
      (when-not (str/blank? (or full ""))
        (MarkdownBlock full {:font-size "1rem"
                             :color "#1f2937"
                             :line-height "1.5"
                             :padding "0.35rem 0.45rem"
                             :background "#f8fafc"
                             :border "1px solid #e5e7eb"
                             :border-radius "4px"
                             :max-height "20rem"
                             :overflow "auto"}))
      (when (:effective-parameters event)
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"}})
         (dom/span
          (dom/props {:style {:font-size "0.62rem"
                              :color "#3b82f6"
                              :cursor "pointer"
                              :user-select "none"
                              :text-decoration "underline"
                              :text-decoration-style "dotted"
                              :align-self "flex-start"}})
          (dom/On "click" (fn [_] (swap! !show-params not)) nil)
          (dom/text (if show-params "hide params" "show params")))
         (when show-params
           (dom/div
            (dom/props {:style {:padding "0.35rem 0.45rem"
                                :background "#f8fafc"
                                :border "1px solid #e5e7eb"
                                :border-radius "4px"
                                :font-size "0.62rem"
                                :font-family "monospace"
                                :white-space "pre-wrap"
                                :color "#1f2937"}})
            (dom/text (pr-str (:effective-parameters event)))))))))))

;; =============================================================================
;; Enrichment-tool detail panels (self-improve-agent — Phase C/OBS)
;;
;; Each detail panel reads `(:parsed-enrichment event)` — the
;; JSON-parsed result-summary attached by tool-call->event for the
;; enrichment-event-kinds set. Layout matches the other event-detail
;; panels (KeyValueRow, Pill, monospace chips) so the drawer stays
;; visually consistent across iteration steps.
;; =============================================================================

(defn- truncate-mid
  "Compact a long string like `chunk-id` for chip display: keep the head
   and tail, elide the middle. The full value is rendered as a tooltip
   via the `title` attribute on the chip wrapper."
  [s max-len]
  (let [s (str s)]
    (if (<= (count s) max-len)
      s
      (let [keep (max 4 (quot (- max-len 1) 2))]
        (str (subs s 0 keep) "…" (subs s (- (count s) keep)))))))

(e/defn ChunkChip
  "Compact monospace pill for a chunk-id (or similar token)."
  [id]
  (e/client
   (dom/span
    (dom/props {:title (str id)
                :style {:padding "0.1rem 0.4rem"
                        :background "#f1f5f9"
                        :color "#0f172a"
                        :border "1px solid #cbd5e1"
                        :border-radius "3px"
                        :font-size "0.62rem"
                        :font-family "monospace"
                        :word-break "break-all"}})
    (dom/text (truncate-mid id 18)))))

(e/defn StatChip
  "Small label + value chip for compact metric grids
   (e.g. cases / current-pass / relaxed-pass in the eval panel)."
  [label v tone]
  (e/client
   (let [pal (status-palette (or tone :pending))]
     (dom/span
      (dom/props {:style {:display "inline-flex"
                          :align-items "baseline"
                          :gap "0.25rem"
                          :padding "0.12rem 0.45rem"
                          :background (:bg pal)
                          :color (:text pal)
                          :border (str "1px solid " (:border pal))
                          :border-radius "999px"
                          :font-size "0.62rem"}})
      (dom/span
       (dom/props {:style {:opacity 0.75 :text-transform "uppercase" :letter-spacing "0.02em"
                           :font-weight "700"}})
       (dom/text (str label)))
      (dom/span
       (dom/props {:style {:font-weight "700" :font-family "monospace"}})
       (dom/text (str v)))))))

(e/defn EnrichmentAnalyzeDetail
  [event]
  (e/client
   (let [p (or (:parsed-enrichment event) {})]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.35rem"}})
      ;; Stats line
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.35rem" :flex-wrap "wrap"}})
       (StatChip "docs" (or (:total-docs p) "?") :pending)
       (StatChip "chunks" (or (:total-chunks p) "?") :pending)
       (StatChip "enrichment rows" (or (:existing-enrichment-rows p) 0) :sufficient))
      ;; Collection names
      (KeyValueRow "docs collection" (or (:docs-collection p) "—"))
      (KeyValueRow "chunks collection" (or (:chunks-collection p) "—"))
      (KeyValueRow "enrichment collection" (or (:enrichment-collection p) "—"))
      ;; Sample chunks table
      (when (seq (:sample-chunks p))
        (dom/div
         (dom/props {:style {:margin-top "0.25rem"}})
         (dom/div
          (dom/props {:style {:font-size "0.62rem" :font-weight "700"
                              :text-transform "uppercase" :color "#64748b"
                              :margin-bottom "0.2rem"}})
          (dom/text (str "Sample chunks (" (count (:sample-chunks p)) ")")))
         (e/for [[i c] (e/diff-by first (map-indexed vector (:sample-chunks p)))]
           (dom/div
            (dom/props {:style {:display "flex" :gap "0.4rem" :font-size "0.62rem"
                                :font-family "monospace" :color "#334155"
                                :padding "0.1rem 0"}})
            (dom/span (dom/props {:style {:color "#94a3b8" :min-width "1.5rem"}})
                      (dom/text (str (inc i) ".")))
            (ChunkChip (:chunk_id c))
            (dom/span (dom/props {:style {:color "#64748b"}})
                      (dom/text (str "doc=" (:doc_num c))))
            (when (:title c)
              (dom/span (dom/text (str "\"" (:title c) "\""))))
            (when (:content_length c)
              (dom/span (dom/props {:style {:color "#94a3b8"}})
                        (dom/text (str (:content_length c) " chars")))))))) ))))

(e/defn EnrichmentProposeDetail
  [event]
  (e/client
   ;; Same panel renders both :questions (hypothetical-questions graph)
   ;; and :phrases (verified-phrases graph) — fall through whichever the
   ;; output map carries. Section header changes per enrichment type so
   ;; the operator sees "Hypothetical questions (4)" vs "Verified phrases (5)".
   (let [p (or (:parsed-enrichment event) {})
         qs (or (:questions p) [])
         phs (or (:phrases p) [])
         items (cond
                 (seq qs) qs
                 (seq phs) phs
                 :else [])
         item-label (cond
                      (seq qs) "Hypothetical questions"
                      (seq phs) "Verified phrases"
                      :else "Items")
         prov (or (:provenance p) {})]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.35rem"}})
      ;; Chunk header
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.35rem" :align-items "center"
                           :flex-wrap "wrap"}})
       (ChunkChip (:chunk_id p))
       (when (:doc_title p)
         (dom/span (dom/props {:style {:font-size "0.66rem" :color "#334155"}})
                   (dom/text (str "\"" (:doc_title p) "\""))))
       (when (:doc_num p)
         (dom/span (dom/props {:style {:font-size "0.62rem" :color "#94a3b8"
                                       :font-family "monospace"}})
                   (dom/text (str "doc " (:doc_num p))))))
      ;; Items (questions or phrases)
      (when (seq items)
        (dom/div
         (dom/div
          (dom/props {:style {:font-size "0.62rem" :font-weight "700"
                              :text-transform "uppercase" :color "#64748b"
                              :margin-bottom "0.2rem"}})
          (dom/text (str item-label " (" (count items) ")")))
         (e/for [[i item] (e/diff-by first (map-indexed vector items))]
           (dom/div
            (dom/props {:style {:display "flex" :gap "0.4rem"
                                :font-size "1rem" :line-height "1.4"
                                :padding "0.1rem 0" :color "#1f2937"}})
            (dom/span (dom/props {:style {:color "#94a3b8" :min-width "1.5rem"
                                          :font-family "monospace"
                                          :font-weight "700"}})
                      (dom/text (str (inc i) ".")))
            (dom/span (dom/text item))))))
      ;; Provenance
      (when (seq prov)
        (dom/div
         (dom/props {:style {:margin-top "0.2rem" :font-size "0.62rem"
                             :color "#64748b" :font-family "monospace"}})
         (dom/text (str "model=" (or (:model prov) "—")
                        "  prompt-hash=" (or (:prompt-hash prov) "—")
                        (when (:generated-at-ms prov)
                          (str "  at=" (:generated-at-ms prov)))))))))))

(e/defn TypesenseRowsInspector
  "Lazy fetch + render of enrichment-collection rows for a set of
   chunk-ids. Click 'Show / Hide' to toggle; the e/server query runs
   only when expanded? is true (avoids hitting Typesense on every
   drawer open even if the operator never asks for rows)."
  [collection-name chunk-ids]
  (e/client
   (let [!expanded (atom false)
         expanded (e/watch !expanded)]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.3rem"
                          :margin-top "0.3rem"
                          :padding "0.3rem 0.4rem"
                          :background "#fafaf9"
                          :border "1px dashed #d6d3d1"
                          :border-radius "4px"}})
      (dom/div
       (dom/props {:style {:display "flex" :justify-content "space-between"
                           :align-items "center" :gap "0.3rem"}})
       (dom/span
        (dom/props {:style {:font-size "0.62rem" :font-weight "700"
                            :text-transform "uppercase"
                            :color "#78716c"}})
        (dom/text (t :playground/typesense-inspector)))
       (dom/span
        (dom/props {:style {:font-size "0.62rem" :color "#1d4ed8"
                            :cursor "pointer" :user-select "none"
                            :text-decoration "underline"
                            :text-decoration-style "dotted"}})
        (dom/On "click" (fn [_] (swap! !expanded not)) nil)
        (dom/text (if expanded "Hide" "Show"))))
      (when expanded
        ;; `enrichment-inspector/fetch-enrichment-rows` always returns
        ;; a result map (`{:rows [...]}` or `{:error "..."}`).
        ;; Electric's `e/server` can't sit inside `try/catch` yet
        ;; (`try is TODO` at macroexpand), so the helper captures the
        ;; failure for us and we dispatch on the result-map shape.
        (let [result (e/server (enrichment-inspector/fetch-enrichment-rows
                                nil collection-name chunk-ids))
              err (:error result)
              rows (:rows result)]
          (cond
            err
            (dom/div (dom/props {:style {:color "#b91c1c" :font-size "0.66rem"}})
                     (dom/text (t :playground/inspector-failed err)))

            (empty? rows)
            (dom/div (dom/props {:style {:color "#78716c" :font-size "0.66rem"
                                         :font-style "italic"}})
                     (dom/text (t :playground/no-rows)))

            :else
            (dom/div
             (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"}})
             (e/for [[i r] (e/diff-by first (map-indexed vector rows))]
               (dom/div
                (dom/props {:style {:padding "0.25rem 0.35rem"
                                    :background "#ffffff"
                                    :border "1px solid #e5e7eb"
                                    :border-radius "3px"
                                    :font-size "0.66rem"
                                    :line-height "1.4"}})
                (dom/div
                 (dom/props {:style {:display "flex" :gap "0.3rem"
                                     :font-family "monospace" :color "#64748b"
                                     :font-size "0.6rem"
                                     :margin-bottom "0.1rem"}})
                 (ChunkChip (:chunk_id r))
                 (dom/span (dom/text (str "model=" (or (:model r) "—"))))
                 (when-let [h (:prompt_hash r)]
                   (dom/span (dom/text (str "hash=" h)))))
                (dom/div
                 (dom/props {:style {:color "#1f2937"}})
                 ;; A row from the hypothetical-questions schema has
                 ;; `:question`; verified-phrases has `:phrase`. Same
                 ;; rendering, different field — the inspector adapts.
                 (dom/text (str (or (:question r) (:phrase r) "—"))))))))))))) )

(e/defn EnrichmentApplyDetail
  [event]
  (e/client
   (let [p (or (:parsed-enrichment event) {})
         chunks (or (:chunk_ids p) [])]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.35rem"}})
      ;; Stats
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.35rem" :flex-wrap "wrap"}})
       (StatChip "applied" (or (:applied-count p) 0) :sufficient)
       (StatChip "chunks" (count chunks) :pending)
       (when (:dry_run p)
         (StatChip "dry-run" "true" :warning)))
      (KeyValueRow "collection" (or (:collection-name p) "—"))
      (when (seq chunks)
        (dom/div
         (dom/div
          (dom/props {:style {:font-size "0.62rem" :font-weight "700"
                              :text-transform "uppercase" :color "#64748b"
                              :margin-bottom "0.2rem"}})
          (dom/text (str "Affected chunk_ids (" (count chunks) ")")))
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.25rem"}})
          (e/for [[i c] (e/diff-by first (map-indexed vector chunks))]
            (ChunkChip c)))))
      ;; Affordance: live Typesense-rows inspector.
      (when (and (seq chunks) (:collection-name p))
        (TypesenseRowsInspector (:collection-name p) (vec chunks)))))))

(e/defn EnrichmentEvalDetail
  [event]
  (e/client
   (let [p (or (:parsed-enrichment event) {})
         s (or (:summary p) {})
         gate? (:gate-pass s)
         gate-tone (cond
                     (true? gate?) :sufficient
                     (false? gate?) :error
                     :else :pending)]
     (dom/div
      (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.35rem"}})
      ;; Headline
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.35rem" :flex-wrap "wrap"
                           :align-items "center"}})
       (Pill gate-tone (str "gate " (if (true? gate?) "PASS" "FAIL")))
       (when (:enrichment-active? p)
         (Pill :pending "enrichment ON"))
       (when (:gate-failed? p)
         (Pill :error "gate-failed exception")))
      ;; Suite identification
      (KeyValueRow "suite" (or (:suite p) "—"))
      ;; Counters grid
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.35rem" :flex-wrap "wrap"
                           :margin-top "0.1rem"}})
       (StatChip "cases" (or (:cases s) 0) :pending)
       (StatChip "current-pass" (or (:current-pass s) 0) :sufficient)
       (StatChip "relaxed-pass" (or (:relaxed-pass s) 0) :sufficient)
       (StatChip "improved" (or (:improved s) 0)
                 (if (pos? (or (:improved s) 0)) :sufficient :pending))
       (StatChip "regressed" (or (:regressed s) 0)
                 (if (pos? (or (:regressed s) 0)) :error :pending))
       (StatChip "errors" (or (:error-count s) 0)
                 (if (pos? (or (:error-count s) 0)) :error :pending))
       (when (number? (:stable-pass s))
         (StatChip "stable-pass" (:stable-pass s) :sufficient))
       (when (number? (:stable-fail s))
         (StatChip "stable-fail" (:stable-fail s)
                   (if (pos? (:stable-fail s)) :error :pending))))
      (when-let [note (:note p)]
        (dom/div
         (dom/props {:style {:font-size "0.62rem" :color "#64748b"
                             :font-style "italic" :margin-top "0.2rem"}})
         (dom/text note)))))))

(e/defn EventDetail
  [event !show-raw show-raw on-select-chunk]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex"
                        :flex-direction "column"
                        :gap "0.25rem"}})
    ;; Kind-specific top details
    (case (:kind event)
      :llm
      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.3rem"}})
       (when-not (str/blank? (or (:reasoning event) ""))
         (dom/div
          (dom/div
           (dom/props {:style {:font-size "0.62rem"
                               :font-weight "700"
                               :text-transform "uppercase"
                               :letter-spacing "0.02em"
                               :color "#64748b"
                               :margin-bottom "0.15rem"}})
           (dom/text (t :playground/reasoning)))
          (MarkdownBlock (:reasoning event)
                         {:font-size "1rem"
                          :color "#1f2937"
                          :line-height "1.5"
                          :padding "0.35rem 0.45rem"
                          :background "#f8fafc"
                          :border "1px solid #e5e7eb"
                          :border-left "3px solid #cbd5e1"
                          :border-radius "4px"
                          :max-height "18rem"
                          :overflow "auto"})))
       (when (seq (:triggered-tools event))
         (dom/div
          (dom/div
           (dom/props {:style {:font-size "0.62rem"
                               :font-weight "700"
                               :text-transform "uppercase"
                               :letter-spacing "0.02em"
                               :color "#64748b"
                               :margin-bottom "0.15rem"}})
           (dom/text (t :playground/triggered-tools (count (:triggered-tools event)))))
          (dom/div
           (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.25rem"}})
           (e/for [[idx t] (e/diff-by first (map-indexed vector (:triggered-tools event)))]
             (dom/span
              (dom/props {:style {:padding "0.1rem 0.4rem"
                                  :background "#eff6ff"
                                  :color "#1e40af"
                                  :border "1px solid #bfdbfe"
                                  :border-radius "3px"
                                  :font-size "0.64rem"
                                  :font-family "monospace"
                                  :font-weight "600"}})
              (dom/text t))))))
       (when-let [usage (:usage event)]
         (dom/div
          (dom/div
           (dom/props {:style {:font-size "0.62rem"
                               :font-weight "700"
                               :text-transform "uppercase"
                               :letter-spacing "0.02em"
                               :color "#64748b"
                               :margin-bottom "0.15rem"}})
           (dom/text (t :playground/token-usage)))
          (dom/div
           (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem"}})
           (when-let [pt (:prompt_tokens usage)]
             (dom/span
              (dom/props {:style {:padding "0.1rem 0.4rem"
                                  :background "#f1f5f9"
                                  :color "#1e293b"
                                  :border-radius "3px"
                                  :font-size "0.64rem"
                                  :font-family "monospace"}})
              (dom/text (str "prompt " pt))))
           (when-let [ct (:completion_tokens usage)]
             (dom/span
              (dom/props {:style {:padding "0.1rem 0.4rem"
                                  :background "#f1f5f9"
                                  :color "#1e293b"
                                  :border-radius "3px"
                                  :font-size "0.64rem"
                                  :font-family "monospace"}})
              (dom/text (str "completion " ct))))
           (when-let [cached (get-in usage [:prompt_tokens_details :cached_tokens])]
             (when (pos? cached)
               (let [prompt (:prompt_tokens usage)
                     pct (when (and prompt (pos? prompt))
                           (Math/round (double (* 100.0 (/ cached prompt)))))]
                 (dom/span
                  (dom/props {:style {:padding "0.1rem 0.4rem"
                                      :background "#ecfdf5"
                                      :color "#047857"
                                      :border-radius "3px"
                                      :font-size "0.64rem"
                                      :font-family "monospace"
                                      :font-weight "600"}})
                  (dom/text (str "cached " cached
                                 (when pct (str " (" pct "%)"))))))))
           (when-let [tt (:total_tokens usage)]
             (dom/span
              (dom/props {:style {:padding "0.1rem 0.4rem"
                                  :background "#eff6ff"
                                  :color "#1e40af"
                                  :border-radius "3px"
                                  :font-size "0.64rem"
                                  :font-family "monospace"
                                  :font-weight "700"}})
              (dom/text (str "total " tt)))))))
       (dom/div
        (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.1rem"}})
        (when-let [m (:llm-model event)] (KeyValueRow "model" m))
        (when-let [fr (:finish-reason event)] (KeyValueRow "finish reason" fr))
        (when-let [il (:input-length event)] (KeyValueRow "input chars" il))
        (when-let [ol (:output-length event)] (KeyValueRow "output chars" ol))
        (when-let [d (:detail event)]
          (when-not (str/blank? d) (KeyValueRow "detail" d)))))

      :search
      (SearchEventDetail event on-select-chunk)

      :read
      (ReadEventDetail event)

      :synthesis
      (SynthesisEventDetail event)

      :enrichment-analyze
      (EnrichmentAnalyzeDetail event)

      :enrichment-propose
      (EnrichmentProposeDetail event)

      :enrichment-apply
      (EnrichmentApplyDetail event)

      :enrichment-eval
      (EnrichmentEvalDetail event)

      ;; Generic orchestration steps get a readable explanation. Their full
      ;; machine payload remains available under the raw disclosure below.
      (when-let [summary (:summary event)]
        (MarkdownBlock summary {:font-size "1rem"
                                :line-height "1.55"
                                :color "#334155"})))
    ;; Raw payload toggle — always available when raw data exists
    (when (or (:full-summary event) (:effective-parameters event))
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.3rem"}})
       (dom/span
        (dom/props {:style {:font-size "0.875rem"
                            :color "#3b82f6"
                            :cursor "pointer"
                            :user-select "none"
                            :text-decoration "underline"
                            :text-decoration-style "dotted"}})
        (dom/On "click" (fn [_] (swap! !show-raw not)) nil)
        (dom/text (t (if show-raw :playground/hide-raw :playground/show-raw))))))
    (when show-raw
      (dom/div
       (dom/props {:style {:padding "0.3rem 0.4rem"
                           :background "#0f172a"
                           :color "#e2e8f0"
                           :border-radius "4px"
                           :font-size "0.875rem"
                           :font-family "monospace"
                           :white-space "pre-wrap"
                           :max-height "20rem"
                           :overflow "auto"}})
       (when-let [ep (:effective-parameters event)]
         (dom/div
          (dom/props {:style {:color "#94a3b8" :margin-bottom "0.2rem"}})
          (dom/text (str "params: " (pr-str ep)))))
       (when-let [fs (:full-summary event)]
         (dom/div (dom/text fs))))))))

(e/defn EventRow
  [event on-select-chunk]
  (e/client
   (let [!expanded (atom false)
         expanded (e/watch !expanded)
         !show-raw (atom false)
         show-raw (e/watch !show-raw)
         status (:status event)
         pal (status-palette status)
         label (:label event)
         summary (:summary event)
         dur (format-ms (:duration-ms event))
         clickable? (or (not (str/blank? (or summary "")))
                        (not (str/blank? (or (:args-summary event) "")))
                        (:full-summary event))]
     (dom/div
      (dom/props {:style {:padding "0.3rem 0.4rem"
                          :background (:bg pal)
                          :border (str "1px solid " (:border pal))
                          :border-radius "5px"}})
      (dom/div
       (dom/props (cond-> {:style {:display "flex"
                                   :align-items "center"
                                   :gap "0.4rem"}}
                    clickable? (assoc-in [:style :cursor] "pointer")
                    clickable? (assoc-in [:style :user-select] "none")))
       (when clickable?
         (dom/On "click" (fn [_] (swap! !expanded not)) nil))
       (dom/span
        (dom/props {:style {:width "1rem"
                            :display "inline-flex"
                            :justify-content "center"
                            :font-size "0.8rem"
                            :color "#64748b"
                            :flex "0 0 auto"}})
        (dom/text (if clickable? (if expanded "▼" "▶") "·")))
       (StatusDot status (= status :running))
       (dom/span
        (dom/props {:style {:width "1.35rem"
                            :height "1.35rem"
                            :display "inline-flex"
                            :align-items "center"
                            :justify-content "center"
                            :border-radius "999px"
                            :background "rgba(255,255,255,0.72)"
                            :font-size "1rem"
                            :color (:text pal)
                            :flex "0 0 auto"}
                    :aria-hidden "true"})
        (dom/text (event-kind-icon (:kind event))))
       (dom/span
        (dom/props {:style {:font-size "1rem"
                            :font-weight "700"
                            :color (:text pal)
                            :flex "0 0 auto"}})
        (dom/text label))
       (when-not (str/blank? (or summary ""))
         (dom/span
          (dom/props {:style {:font-size "1rem"
                              :color "#475569"
                              :overflow "hidden"
                              :text-overflow "ellipsis"
                              :white-space "nowrap"
                              :min-width "0"
                              :flex "1 1 auto"}})
          (dom/text summary)))
       (when dur
         (dom/span
          (dom/props {:style {:font-size "0.875rem"
                              :font-weight "700"
                              :font-family "monospace"
                              :color "#374151"
                              :white-space "nowrap"
                              :flex "0 0 auto"}})
          (dom/text dur))))
      (when expanded
        (dom/div
         (dom/props {:style {:margin-top "0.3rem"
                             :padding-top "0.3rem"
                             :border-top (str "1px solid " (:border pal))}})
         (EventDetail event !show-raw show-raw on-select-chunk)))))))

;; =============================================================================
;; IterationCard
;; =============================================================================

(e/defn SufficiencyRow
  [suff]
  (e/client
   (when suff
     (let [!expanded (atom false)
           expanded (e/watch !expanded)
           status (or (:status suff) :pending)
           pal (status-palette status)
           action (:action suff)
           headline (first-line (or (:reasoning suff) ""))]
       (dom/div
        (dom/props {:style {:padding "0.3rem 0.4rem"
                            :background (:bg pal)
                            :border (str "1px dashed " (:border pal))
                            :border-radius "5px"}})
        (dom/div
         (dom/props {:style {:display "flex"
                             :align-items "center"
                             :gap "0.4rem"
                             :cursor "pointer"
                             :user-select "none"}})
         (dom/On "click" (fn [_] (swap! !expanded not)) nil)
         (dom/span
          (dom/props {:style {:width "0.9rem"
                              :display "inline-flex"
                              :justify-content "center"
                              :font-size "0.62rem"
                              :color "#64748b"}})
          (dom/text (if expanded "▼" "▶")))
         (StatusDot status false)
         (dom/span
          (dom/props {:style {:font-size "1rem"
                              :font-weight "700"
                              :color (:text pal)
                              :text-transform "uppercase"
                              :letter-spacing "0.02em"
                              :flex "0 0 auto"}})
          (dom/text (t :playground/sufficiency)))
         (dom/span
          (dom/props {:style {:font-size "0.68rem"
                              :color "#475569"
                              :overflow "hidden"
                              :text-overflow "ellipsis"
                              :white-space "nowrap"
                              :min-width "0"
                              :flex "1 1 auto"}})
          (dom/text (str (name status)
                         (when action (str " → " (name action)))
                         (when-not (str/blank? headline) (str " · " (truncate headline 80)))))))
        (when expanded
          (dom/div
           (dom/props {:style {:margin-top "0.3rem"
                               :padding-top "0.3rem"
                               :border-top (str "1px dashed " (:border pal))}})
           (SufficiencyDetail suff))))))))

(e/defn EventTab
  [event selected? on-click]
  (e/client
   (let [status (:status event)
         pal (status-palette status)
         dur (format-ms (:duration-ms event))
         label (:label event)]
     (dom/button
      (dom/props {:style {:display "inline-flex"
                          :align-items "center"
                          :justify-content "center"
                          :gap "0.3rem"
                          :width "2.65rem"
                          :height "2.65rem"
                          :padding "0"
                          :background (if selected? (:bg pal) "white")
                          :border (str "1px solid " (:border pal))
                          :border-bottom (if selected?
                                           (str "2px solid " (:dot pal))
                                           (str "1px solid " (:border pal)))
                          :border-radius "5px 5px 0 0"
                          :cursor "pointer"
                          :user-select "none"
                          :font-family "inherit"
                          :opacity (if selected? "1" "0.75")
                          :flex "0 0 auto"}
                  :role "tab"
                  :aria-selected (boolean selected?)
                  :aria-label (str label (when dur (str ", " dur)))
                  :title (str label (when dur (str " · " dur)))})
      (dom/On "click" (fn [_] (on-click)) nil)
      (StatusDot status (= status :running))
      (dom/span
       (dom/props {:style {:font-size "1.05rem"
                           :font-weight "700"
                           :color (:text pal)
                           :line-height "1"}
                   :aria-hidden "true"})
       (dom/text (event-kind-icon (:kind event))))))))

(e/defn GraphActionDetail
  "Render the structured result already carried by a canonical action record."
  [language kind detail on-select-chunk]
  (e/client
   (dom/div
      (dom/props {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.45rem"
                          :font-size "1rem"
                          :color "#334155"}})
      (case kind
        :plan
        (dom/div
         (dom/div
          (dom/props {:style {:font-size "0.75rem"
                              :font-weight "700"
                              :text-transform "uppercase"
                              :letter-spacing "0.03em"
                              :color "#64748b"
                              :margin-bottom "0.3rem"}})
          (dom/text (tr language :playground/planned-queries)))
         (if (seq (:queries detail))
           (dom/div
            (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.3rem"}})
            (e/for [[idx query] (e/diff-by first (map-indexed vector (:queries detail)))]
              (dom/span
               (dom/props {:style {:padding "0.25rem 0.5rem"
                                   :background "#eff6ff"
                                   :border "1px solid #bfdbfe"
                                   :border-radius "5px"
                                   :color "#1e40af"}})
               (dom/text query))))
           (dom/span (dom/text (tr language :playground/no-execution-data))))
         (when-let [intent (:user-intent detail)]
           (KeyValueRow (tr language :playground/user-intent) (pr-str intent))))

        :search
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.4rem"}})
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem"}})
          (StatChip (tr language :playground/candidates) (:candidate-count detail) :pending)
          (StatChip (tr language :playground/phrase-matches) (:phrase-count detail) :sufficient)
          (StatChip (tr language :playground/content-matches) (:content-count detail) :sufficient)
          (StatChip (tr language :playground/metadata-matches) (:metadata-count detail) :sufficient))
         (when (:auto-filter-applied detail)
           (KeyValueRow (tr language :playground/filter-label)
                        (pr-str (:auto-filter-applied detail))))
         (when (:auto-filter-fallback detail)
           (Pill :warning (tr language :playground/filter-fallback))))

        :rerank
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.4rem"}})
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem"}})
          (StatChip (tr language :playground/selected-chunks) (:selected-count detail) :sufficient)
          (StatChip (tr language :playground/distinct-documents)
                    (:distinct-document-count detail) :pending))
         (when (seq (:chunks detail))
           (dom/div
            (dom/props {:style {:display "flex" :flex-direction "column"
                                :border "1px solid #e2e8f0" :border-radius "5px"
                                :overflow "hidden"}})
            (e/for [[idx chunk] (e/diff-by first
                                           (map-indexed vector (take 5 (:chunks detail))))]
              (let [chunk-id (or (:chunk_id chunk) (:chunk-id chunk))
                    score (or (:rerank-score chunk) (:score chunk))]
                (dom/div
                 (dom/props (cond-> {:style {:display "flex" :align-items "center"
                                             :gap "0.5rem" :padding "0.35rem 0.5rem"
                                             :border-bottom "1px solid #f1f5f9"}}
                              (and on-select-chunk chunk-id)
                              (assoc-in [:style :cursor] "pointer")))
                 (when (and on-select-chunk chunk-id)
                   (dom/On "click" (fn [_] (on-select-chunk chunk-id)) nil))
                 (dom/span
                  (dom/props {:style {:font-family "monospace" :color "#64748b"}})
                  (dom/text (str (inc idx) ".")))
                 (dom/span
                  (dom/props {:style {:flex "1 1 auto" :min-width "0"
                                      :overflow "hidden" :text-overflow "ellipsis"
                                      :white-space "nowrap"}})
                  (dom/text (or (:title chunk) chunk-id
                                (str (tr language :playground/document-label) " "
                                     (or (:doc_num chunk) (:doc-num chunk) (inc idx))))))
                 (when score
                   (dom/span
                    (dom/props {:style {:font-family "monospace" :color "#475569"}})
                    (dom/text (str score))))))))))

        :sufficiency
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.4rem"}})
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem"
                              :align-items "center"}})
          (Pill (if (:sufficient? detail) :sufficient :error)
                (tr language (if (:sufficient? detail)
                               :playground/evidence-passed
                               :playground/evidence-declined)))
          (StatChip (tr language :playground/context-documents)
                    (:context-document-count detail) :pending)
          (StatChip (tr language :playground/distinct-documents)
                    (:distinct-document-count detail) :pending))
         (when-let [reason (:decline-reason detail)]
           (MarkdownBlock reason {:font-size "1rem" :line-height "1.5" :color "#475569"})))

        :synthesis
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.4rem"}})
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem"}})
          (StatChip (tr language :playground/answer-characters)
                    (:character-count detail) :pending)
          (StatChip (tr language :playground/citations-label)
                    (:citation-count detail) :sufficient))
         (when (:insufficient-context detail)
           (Pill :warning (tr language :playground/insufficient-context)))
         (when-not (str/blank? (or (:response detail) ""))
           (dom/div
            (dom/div
             (dom/props {:style {:font-size "0.75rem" :font-weight "700"
                                 :text-transform "uppercase" :color "#64748b"
                                 :margin-bottom "0.2rem"}})
             (dom/text (tr language :playground/generated-answer)))
            (MarkdownBlock (:response detail)
                           {:font-size "1rem" :line-height "1.55" :color "#1f2937"
                            :padding "0.45rem 0.55rem" :background "#f8fafc"
                            :border-radius "5px" :max-height "16rem" :overflow "auto"}))))

        :finalize
        (dom/div
         (dom/props {:style {:display "flex" :align-items "center"
                             :flex-wrap "wrap" :gap "0.35rem"}})
         (Pill (if (:published? detail) :sufficient :error)
               (tr language (if (:published? detail)
                              :playground/answer-published
                              :playground/answer-not-published)))
         (StatChip (tr language :playground/answer-characters)
                   (:character-count detail) :pending)
         (StatChip (tr language :playground/citations-label)
                   (:citation-count detail) :sufficient)
         (when-let [reason (:decline-reason detail)]
           (dom/span (dom/text reason))))

        nil))))

(e/defn ActionBody
  [language action on-select-chunk]
  (e/client
   (let [status (:status action)
         pal (status-palette status)
         reasoning (:iteration-reasoning action)
         suff (:iteration-sufficiency action)
         dur (format-ms (:duration-ms action))
         !show-raw (atom false)
         show-raw (e/watch !show-raw)]
     (dom/div
      (dom/props {:style {:padding "0.55rem 0.7rem"
                          :background "white"
                          :border (str "1px solid " (:border pal))
                          :border-radius "0 5px 5px 5px"
                          :display "flex"
                          :flex-direction "column"
                          :gap "0.4rem"}})
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.45rem"
                           :color (:text pal)}})
       (StatusDot status (= status :running))
       (dom/span
        (dom/props {:style {:width "1.5rem"
                            :height "1.5rem"
                            :display "inline-flex"
                            :align-items "center"
                            :justify-content "center"
                            :border-radius "999px"
                            :background (:bg pal)
                            :font-size "1rem"}
                    :aria-hidden "true"})
        (dom/text (event-kind-icon (:kind action))))
       (dom/span
        (dom/props {:style {:font-size "1rem"
                            :font-weight "700"}})
        (dom/text (:label action)))
       (when dur
         (dom/span
          (dom/props {:style {:font-size "0.875rem"
                              :font-weight "700"
                              :font-family "monospace"
                              :color "#475569"}})
          (dom/text dur))))
      (when (and (= :llm (:kind action))
                 (not (str/blank? (or reasoning ""))))
        (MarkdownBlock reasoning {:color "#475569"
                                  :font-size "1rem"
                                  :line-height "1.45"
                                  :padding "0.4rem 0.55rem"
                                  :background "#f8fafc"
                                  :border-left "3px solid #94a3b8"
                                  :border-radius "4px"}))
      (when (and (= :sufficiency (:kind action)) suff)
        (SufficiencyRow suff))
      (cond
        (and (contains? structured-action-kinds (:kind action))
             (map? (:action-result action)))
        (GraphActionDetail language (:kind action) (:action-result action) on-select-chunk)

        (:legacy? action)
        (dom/div
         (dom/props {:style {:font-size "1rem" :color "#64748b"
                             :font-style "italic"}})
         (dom/text (tr language :playground/legacy-action-details-unavailable)))

        :else
        (EventDetail action !show-raw show-raw on-select-chunk))))))

;; =============================================================================
;; Shell + entry points
;; =============================================================================

(e/defn LiveThinkingPanel
  "Live-only panel showing the LLM's latest reasoning while tool calls are
   still running. Cleared when the turn completes (reasoning then lives in
   the iteration body)."
  [live-thinking]
  (e/client
   (when (and live-thinking
              (not (str/blank? (or (:reasoning live-thinking) ""))))
     (dom/div
      (dom/props {:style {:margin-bottom "0.5rem"
                          :padding "0.45rem 0.6rem"
                          :background "#f5f3ff"
                          :border "1px solid #ddd6fe"
                          :border-left "3px solid #8b5cf6"
                          :border-radius "6px"}})
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.4rem"
                           :margin-bottom "0.3rem"}})
       (StatusDot :running true)
       (dom/span
        (dom/props {:style {:font-size "0.64rem"
                            :font-weight "700"
                            :text-transform "uppercase"
                            :letter-spacing "0.04em"
                            :color "#5b21b6"}})
        (dom/text (t :playground/thinking)))
       (when-let [i (:iteration live-thinking)]
         (dom/span
          (dom/props {:style {:font-size "0.62rem"
                              :color "#7c3aed"
                              :font-family "monospace"}})
          (dom/text (t :playground/iteration-label (inc i))))))
      (MarkdownBlock (:reasoning live-thinking)
                     {:font-size "1rem"
                      :color "#1f2937"
                      :line-height "1.5"
                      :max-height "18rem"
                      :overflow "auto"})))))

(e/defn NextDetailedShell
  [vm on-select-chunk]
  (e/client
   (let [iterations (or (:iterations vm) [])
         actions (or (seq (:actions vm))
                     (timeline-actions iterations))
         latest-action-idx (dec (count actions))
         ;; Live runs stay open and collapse at completion. Once the user
         ;; clicks the toggle, their explicit choice takes precedence.
         !user-toggled? (atom false)
         !user-pref-expanded (atom true)
         user-toggled? (e/watch !user-toggled?)
         user-pref-expanded (e/watch !user-pref-expanded)
         top-expanded (if user-toggled?
                        user-pref-expanded
                        (default-shell-expanded? vm))
         toggle-top! (fn []
                       (let [next-val (not top-expanded)]
                         (reset! !user-toggled? true)
                         (reset! !user-pref-expanded next-val)))
         !user-selected-action (atom nil)
         user-selected-action (e/watch !user-selected-action)
         selected-action-idx (cond
                               (and (number? user-selected-action)
                                    (< user-selected-action (count actions)))
                               user-selected-action

                               (nat-int? latest-action-idx)
                               latest-action-idx

                               :else nil)
         selected-action (when (and selected-action-idx
                                    (>= selected-action-idx 0))
                           (nth actions selected-action-idx nil))]
     (dom/div
      (dom/props {:style {:margin-bottom "0.5rem"}})
      (RunHeader vm top-expanded toggle-top!)
      (when top-expanded
        (dom/div
         (SufficiencyDigest vm)
         (when (seq actions)
           (dom/div
            (dom/props {:style {:display "flex"
                                :flex-direction "column"
                                :margin-top "0.35rem"}})
            (dom/div
             (dom/props {:style {:font-size "0.875rem"
                                 :font-weight "700"
                                 :text-transform "uppercase"
                                 :letter-spacing "0.04em"
                                 :color "#64748b"
                                 :margin-bottom "0.35rem"}})
             (dom/text (tr (:language vm) :playground/what-happened)))
            (dom/div
             (dom/props {:style {:display "flex"
                                 :flex-wrap "wrap"
                                 :gap "0.25rem"
                                 :align-items "flex-end"}
                          :role "tablist"})
             (e/for [[idx action] (e/diff-by first (map-indexed vector actions))]
               (EventTab action (= idx selected-action-idx)
                         (fn [] (reset! !user-selected-action idx)))))
            (when selected-action
              (ActionBody (:language vm) selected-action on-select-chunk))))))))))

(e/defn NextDetailedLive
  "Entry point for the new Detailed view during live execution.
   Live view has no chunk-modal click-through — passes nil downstream."
  [stream-view]
  (e/client
   (let [vm (stream-view->view-model (i18n/current-language) stream-view)]
     (NextDetailedShell vm nil))))

(e/defn NextDetailedResponse
  "Entry point for the new Detailed view in the post-run message view.
   on-select-chunk (optional) receives a chunk-id when a search hit is clicked."
  [diagnostics on-select-chunk]
  (e/client
   (let [vm (diagnostics->view-model (i18n/current-language) diagnostics)]
     (NextDetailedShell vm on-select-chunk))))
