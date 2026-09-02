(ns digdir.playground.diagnostics
  "Diagnostics normalization/parsing helpers for Playground chat."
  (:require [clojure.string :as str]
            [digdir.playground.action-trace :as action-trace]))

(defn- normalize-decision-status
  [status]
  (case status
    :enough :sufficient
    :conflict :conflicting
    "enough" :sufficient
    "conflict" :conflicting
    "conflicting" :conflicting
    "sufficient" :sufficient
    "insufficient" :insufficient
    "off-topic" :off-topic
    status))

(defn- normalize-decision-action
  [action]
  (case action
    :stop :finalize
    :answer-with-uncertainty :finalize
    "stop" :finalize
    "answer-with-uncertainty" :finalize
    action))

(defn- compact-query-intent
  [query-intent]
  (when (map? query-intent)
    (select-keys query-intent [:answer-type
                               :entity
                               :year-or-date
                               :metric
                               :doc-family-preference
                               :scope-signals])))

(defn- compact-budget-state
  [budget-state]
  (when (map? budget-state)
    (select-keys budget-state [:search-passes-used
                               :search-passes-remaining
                               :read-operations-used
                               :read-operations-remaining
                               :read-content-length-used
                               :read-content-length-remaining
                               :low-search-budget?
                               :low-read-budget?
                               :search-budget-exhausted?
                               :read-operations-exhausted?
                               :read-content-budget-exhausted?])))

(defn- compact-sufficiency-decision
  [decision]
  (when (map? decision)
    {:status (normalize-decision-status (:status decision))
     :source (:source decision)
     :action (normalize-decision-action (or (:suggested-strategy decision)
                                            (:action decision)))
     :reason-code (or (:reason-code decision)
                      (:reason_code decision))
     :reasoning (or (:reasoning decision)
                    (:message decision))
     :missing-info (vec (or (:missing-info decision) []))
     :missing-claims (vec (or (:missing-claims decision)
                              (:missing_claims decision)
                              []))
     :contradiction-detected? (boolean (or (:contradiction-detected? decision)
                                           (:contradiction_detected? decision)))
     :failure-type (get-in decision [:insufficiency :failure-type])
     :target-metric (get-in decision [:insufficiency :target-metric])
     :target-year (get-in decision [:insufficiency :target-year])
     :target-entity (get-in decision [:insufficiency :target-entity])
     :doc-family-hint (get-in decision [:insufficiency :doc-family-hint])
     :message (:message decision)}))

(defn- compact-evidence-plan
  [evidence-plan]
  (when (map? evidence-plan)
    {:query-intent (compact-query-intent (:query-intent evidence-plan))
     :required-claims
     (mapv (fn [claim]
             (select-keys claim [:claim-id :text :kind :critical?]))
           (or (:required-claims evidence-plan) []))}))

(defn- compact-read-signal
  [signal]
  (when (map? signal)
    {:status (:status signal)
     :scope-assessment (:scope-assessment signal)
     :next-action-hint (:next-action-hint signal)
     :confidence (:confidence signal)
     :degraded? (boolean (:degraded? signal))
     :evaluation-mode (:evaluation-mode signal)
     :supported-claims (mapv #(select-keys % [:claim-id :support-level :chunk-ids])
                             (or (:supported-claims signal) []))
     :remaining-gaps (mapv #(select-keys % [:claim-id :critical? :reason :text])
                           (or (:remaining-gaps signal) []))
     :contradictions (vec (or (:contradictions signal) []))}))

(defn- compact-search-entry
  [entry]
  (when (map? entry)
    (select-keys entry [:queries :result-count :new-count :fallback? :chunk-ids :chunk-summaries])))

(defn- positive-int-value
  [value]
  (let [parsed (cond
                 (integer? value) value
                 (string? value) (try
                                   (#?(:clj Integer/parseInt
                                       :cljs js/parseInt)
                                    value 10)
                                   (catch #?(:clj Exception :cljs :default) _
                                     nil))
                 :else nil)]
    (when (and (integer? parsed) (pos? parsed))
      parsed)))

(defn read-max-content-length
  [entry]
  (some positive-int-value
        [(:max-content-length entry)
         (:max_content_length entry)
         (get-in entry [:args :max-content-length])
         (get-in entry [:args :max_content_length])
         (get-in entry [:effective-parameters :primary :max-content-length])
         (get-in entry [:effective-parameters :primary :max_content_length])]))

(defn read-operation-kind
  [entry]
  (when (map? entry)
    (if (read-max-content-length entry)
      :skim
      :read)))

(defn read-operation-label
  [entry]
  (case (read-operation-kind entry)
    :skim "Skimmed"
    :read "Read"
    "Read"))

(defn- compact-read-entry
  [entry]
  (when (map? entry)
    (assoc (select-keys entry [:mode :chunk-ids :doc-num :chunk-range :returned-count :returned-chunk-ids :content-length :max-content-length])
           :operation-kind (read-operation-kind entry)
           :operation-label (read-operation-label entry))))

(defn- compact-stage-timing-entry
  [entry]
  (when (map? entry)
    (select-keys entry [:step-id
                        :skill-id
                        :stage
                        :iteration
                        :tool
                        :sub-skill
                        :duration-ms
                        :status
                        :detail
                        :input-length
                        :output-length
                        :result-count
                        :defaulted
                        :skipped
                        :usage
                        :llm-model
                        :finish-reason])))

(defn- normalize-run-status
  [status]
  (case status
    :needs-clarification :needs_clarification
    "needs-clarification" :needs_clarification
    "needs_clarification" :needs_clarification
    status))

(defn- compact-clarification-request
  [request]
  (when (map? request)
    (cond-> {:question (or (:question request) "")
             :context-summary (or (:context-summary request)
                                  (:context_summary request)
                                  "")}
      (seq (:options request))
      (assoc :options (vec (keep not-empty (:options request)))))))

(defn run-summary
  "Compact UI-facing summary of agent state."
  [diagnostics]
  (let [diagnostics (or diagnostics {})
        decisions (mapv compact-sufficiency-decision (or (:sufficiency-decisions diagnostics) []))
        latest-decision (last decisions)
        response-validations (mapv compact-sufficiency-decision (or (:response-validations diagnostics) []))
        latest-response-validation (last response-validations)
        shadow-decisions (mapv compact-sufficiency-decision (or (:shadow-sufficiency-decisions diagnostics) []))
        latest-shadow-decision (last shadow-decisions)
        last-read-signal (compact-read-signal (:last-read-signal diagnostics))
        claim-coverage (or (:claim-coverage diagnostics) {})]
    {:query-intent (compact-query-intent (:query-intent diagnostics))
     :budget-state (compact-budget-state (:budget-state diagnostics))
     :latest-decision latest-decision
     :latest-response-validation latest-response-validation
     :latest-shadow-decision latest-shadow-decision
     :last-insufficiency (:last-insufficiency diagnostics)
     :last-response-validation-insufficiency (:last-response-validation-insufficiency diagnostics)
     :read-signal-status (:status last-read-signal)
     :open-evidence-gap-count (count (or (:open-evidence-gaps diagnostics) []))
     :supported-claim-count (count claim-coverage)
     :search-passes (count (or (:search-history diagnostics) []))
     :backend-issue-count (count (or (:backend-issues diagnostics) []))
     :read-operations (count (or (:read-history diagnostics) []))
     :sufficiency-count (count decisions)}))

(declare conflict-summary)

(defn budget-mode
  [budget-state]
  (cond
    (or (:search-budget-exhausted? budget-state)
        (:read-operations-exhausted? budget-state)
        (:read-content-budget-exhausted? budget-state))
    :exhausted

    (or (:low-search-budget? budget-state)
        (:low-read-budget? budget-state))
    :low

    budget-state
    :normal

    :else nil))

(defn run-summary-lines
  [diagnostics]
  (let [{:keys [query-intent budget-state latest-decision latest-shadow-decision
                latest-response-validation read-signal-status
                open-evidence-gap-count supported-claim-count]} (run-summary diagnostics)
        last-read-signal (compact-read-signal (:last-read-signal diagnostics))
        conflict (conflict-summary diagnostics)
        budget-mode (budget-mode budget-state)
        lines (cond-> []
                (:answer-type query-intent)
                (conj {:label "Answer type" :value (name (:answer-type query-intent))})
                (:entity query-intent)
                (conj {:label "Entity" :value (:entity query-intent)})
                (:year-or-date query-intent)
                (conj {:label "Year/date" :value (str (:year-or-date query-intent))})
                (:metric query-intent)
                (conj {:label "Metric" :value (:metric query-intent)})
                (:doc-family-preference query-intent)
                (conj {:label "Preferred source" :value (:doc-family-preference query-intent)})
                (:failure-type latest-decision)
                (conj {:label "Decision reason" :value (name (:failure-type latest-decision))})
                (:action latest-decision)
                (conj {:label "Next action" :value (name (:action latest-decision))})
                (:status latest-shadow-decision)
                (conj {:label "Shadow status" :value (name (:status latest-shadow-decision))})
                (:action latest-shadow-decision)
                (conj {:label "Shadow action" :value (name (:action latest-shadow-decision))})
                (:status latest-response-validation)
                (conj {:label "Response validation" :value (name (:status latest-response-validation))})
                (:action latest-response-validation)
                (conj {:label "Response action" :value (name (:action latest-response-validation))})
                read-signal-status
                (conj {:label "Read signal" :value (name read-signal-status)})
                (:scope-assessment last-read-signal)
                (conj {:label "Read scope" :value (name (:scope-assessment last-read-signal))})
                (:next-action-hint last-read-signal)
                (conj {:label "Read hint" :value (name (:next-action-hint last-read-signal))})
                (contains? last-read-signal :degraded?)
                (conj {:label "Read degraded" :value (str (:degraded? last-read-signal))})
                (number? (:confidence last-read-signal))
                (conj {:label "Read confidence" :value (str (double (:confidence last-read-signal)))})
                (pos? (or supported-claim-count 0))
                (conj {:label "Supported claims" :value (str supported-claim-count)})
                (contains? (run-summary diagnostics) :open-evidence-gap-count)
                (conj {:label "Open gaps" :value (str open-evidence-gap-count)})
                (pos? (or (:backend-issue-count (run-summary diagnostics)) 0))
                (conj {:label "Backend issues"
                       :value (str (:backend-issue-count (run-summary diagnostics)))})
                budget-state
                (conj {:label "Search budget"
                       :value (str (or (:search-passes-used budget-state) 0) "/"
                                   (+ (or (:search-passes-used budget-state) 0)
                                      (or (:search-passes-remaining budget-state) 0)))}))
        lines (cond-> lines
                budget-state
                (conj {:label "Read budget"
                       :value (str (or (:read-operations-used budget-state) 0) "/"
                                   (+ (or (:read-operations-used budget-state) 0)
                                      (or (:read-operations-remaining budget-state) 0)))})
                budget-mode
                (conj {:label "Budget mode" :value (name budget-mode)})
                budget-state
                (conj {:label "Read chars"
                       :value (str (or (:read-content-length-used budget-state) 0) "/"
                                   (+ (or (:read-content-length-used budget-state) 0)
                                      (or (:read-content-length-remaining budget-state) 0)))})
                (:metric conflict)
                (conj {:label "Conflict metric" :value (:metric conflict)})
                (:source conflict)
                (conj {:label "Conflict source" :value (:source conflict)})
                (:status latest-decision)
                (conj {:label "Status" :value (name (:status latest-decision))}))]
    (vec lines)))

(defn- budget-total
  [used remaining]
  (+ (or used 0) (or remaining 0)))

(defn budget-snapshot-items
  [budget-state]
  (when (map? budget-state)
    [{:key :search
      :label "Search"
      :used (or (:search-passes-used budget-state) 0)
      :total (budget-total (:search-passes-used budget-state)
                           (:search-passes-remaining budget-state))
      :status (cond
                (:search-budget-exhausted? budget-state) :exhausted
                (:low-search-budget? budget-state) :low
                :else :normal)}
     {:key :reads
      :label "Reads"
      :used (or (:read-operations-used budget-state) 0)
      :total (budget-total (:read-operations-used budget-state)
                           (:read-operations-remaining budget-state))
      :status (cond
                (:read-operations-exhausted? budget-state) :exhausted
                (:low-read-budget? budget-state) :low
                :else :normal)}
     {:key :chars
      :label "Chars"
      :used (or (:read-content-length-used budget-state) 0)
      :total (budget-total (:read-content-length-used budget-state)
                           (:read-content-length-remaining budget-state))
      :status (cond
                (:read-content-budget-exhausted? budget-state) :exhausted
                (:low-read-budget? budget-state) :low
                :else :normal)}]))

(defn budget-comparison-lines
  "Compact comparison lines for baseline vs relaxed budget runs."
  [baseline-diagnostics relaxed-diagnostics]
  (let [baseline-summary (run-summary baseline-diagnostics)
        relaxed-summary (run-summary relaxed-diagnostics)
        baseline-budget (:budget-state baseline-summary)
        relaxed-budget (:budget-state relaxed-summary)
        baseline-status (some-> baseline-summary :latest-decision :status name)
        relaxed-status (some-> relaxed-summary :latest-decision :status name)]
    (vec
     (remove nil?
             [(when (or baseline-status relaxed-status)
                (str "Status: " (or baseline-status "unknown")
                     " -> " (or relaxed-status "unknown")))
              (when (and baseline-budget relaxed-budget)
                (let [baseline-total (budget-total (:search-passes-used baseline-budget)
                                                   (:search-passes-remaining baseline-budget))
                      relaxed-total (budget-total (:search-passes-used relaxed-budget)
                                                  (:search-passes-remaining relaxed-budget))
                      delta (- relaxed-total baseline-total)]
                  (str "Search budget: "
                       (or (:search-passes-used baseline-budget) 0) "/" baseline-total
                       " -> "
                       (or (:search-passes-used relaxed-budget) 0) "/" relaxed-total
                       " (" (if (neg? delta) "" "+") delta " total)")))
              (when (and baseline-budget relaxed-budget)
                (let [baseline-total (budget-total (:read-operations-used baseline-budget)
                                                   (:read-operations-remaining baseline-budget))
                      relaxed-total (budget-total (:read-operations-used relaxed-budget)
                                                  (:read-operations-remaining relaxed-budget))
                      delta (- relaxed-total baseline-total)]
                  (str "Read budget: "
                       (or (:read-operations-used baseline-budget) 0) "/" baseline-total
                       " -> "
                       (or (:read-operations-used relaxed-budget) 0) "/" relaxed-total
                       " (" (if (neg? delta) "" "+") delta " total)")))
              (when (and baseline-budget relaxed-budget)
                (let [baseline-total (budget-total (:read-content-length-used baseline-budget)
                                                   (:read-content-length-remaining baseline-budget))
                      relaxed-total (budget-total (:read-content-length-used relaxed-budget)
                                                  (:read-content-length-remaining relaxed-budget))
                      delta (- relaxed-total baseline-total)]
                  (str "Read chars: "
                       (or (:read-content-length-used baseline-budget) 0) "/" baseline-total
                       " -> "
                       (or (:read-content-length-used relaxed-budget) 0) "/" relaxed-total
                       " (" (if (neg? delta) "" "+") delta " total)")))]))))

(defn format-search-history-entry
  [entry]
  (let [queries (or (:queries entry) [])
        query-text (if (seq queries)
                     (str/join ", " queries)
                     "no queries captured")
        details (cond-> [(str (or (:result-count entry) 0) " hits")]
                  (contains? entry :new-count)
                  (conj (str (or (:new-count entry) 0) " new"))
                  (:fallback? entry)
                  (conj "fallback"))]
    (str "Search: " query-text " (" (str/join ", " details) ")")))

(defn- chunk-range-bounds
  [chunk-range]
  (cond
    (vector? chunk-range)
    [(first chunk-range) (second chunk-range)]

    (map? chunk-range)
    [(or (:from chunk-range) (get chunk-range "from"))
     (or (:to chunk-range) (get chunk-range "to"))]

    :else
    [nil nil]))

(defn format-read-history-entry
  [entry]
  (let [operation-label (read-operation-label entry)
        mode (name (or (:mode entry) :unknown))
        [range-from range-to] (chunk-range-bounds (:chunk-range entry))
        target (cond
                 (seq (:chunk-ids entry))
                 (str (count (:chunk-ids entry)) " requested chunks")
                 (and (:doc-num entry) (or range-from range-to))
                 (str "doc " (:doc-num entry) " "
                      range-from "-" range-to)
                 (:doc-num entry)
                 (str "doc " (:doc-num entry))
                 :else nil)
        details (cond-> [(str (or (:returned-count entry) 0) " chunks")
                         (str (or (:content-length entry) 0) " chars")]
                  target
                  (conj target))]
    (str operation-label ": " mode " (" (str/join ", " details) ")")))

(defn format-search-timeline-detail
  [entry]
  (let [queries (or (:queries entry) [])
        details (cond-> [(str (or (:result-count entry) 0) " hits")
                         (str (count queries) " queries")]
                  (contains? entry :new-count)
                  (conj (str (or (:new-count entry) 0) " new"))
                  (:fallback? entry)
                  (conj "fallback")
                  (seq queries)
                  (conj (str "first: " (first queries))))]
    (str/join " · " details)))

(defn- search-budget-note
  [budget-state]
  (case (budget-mode budget-state)
    :low "budget low"
    :exhausted "budget exhausted"
    nil))

(defn- read-budget-note
  [budget-state]
  (case (budget-mode budget-state)
    :low (when (:low-read-budget? budget-state) "budget low")
    :exhausted (cond
                 (:read-operations-exhausted? budget-state) "read ops exhausted"
                 (:read-content-budget-exhausted? budget-state) "read chars exhausted"
                 :else "budget exhausted")
    nil))

(defn format-read-timeline-detail
  [entry]
  (let [formatted (format-read-history-entry entry)]
    (if (= :skim (read-operation-kind entry))
      formatted
      (subs formatted 6))))

(defn format-decision-timeline-detail
  [entry]
  (let [entry (compact-sufficiency-decision entry)
        details (cond-> [(name (or (:status entry) :unknown))]
                  (:source entry)
                  (conj (str "via " (name (:source entry))))
                  (:action entry)
                  (conj (str "next " (name (:action entry))))
                  (:reason-code entry)
                  (conj (name (:reason-code entry)))
                  (seq (:missing-claims entry))
                  (conj (str "missing " (str/join "," (map name (:missing-claims entry)))))
                  (:failure-type entry)
                  (conj (name (:failure-type entry)))
                  (:target-metric entry)
                  (conj (str "metric " (:target-metric entry)))
                  (:target-year entry)
                  (conj (str "year " (:target-year entry)))
                  (:doc-family-hint entry)
                  (conj (str "source " (:doc-family-hint entry))))]
    (str/join " · " details)))

(defn- format-shadow-decision-timeline-detail
  [entry]
  (let [entry (compact-sufficiency-decision entry)
        details (cond-> ["shadow"]
                  (:action entry)
                  (conj (str "next " (name (:action entry))))
                  (:reason-code entry)
                  (conj (name (:reason-code entry)))
                  (seq (:missing-claims entry))
                  (conj (str "missing " (str/join "," (map name (:missing-claims entry))))))]
    (str/join " · " details)))

(defn- unescape-json-string
  [s]
  (-> s
      (str/replace #"\\\"" "\"")
      (str/replace #"\\n" "\n")
      (str/replace #"\\t" "\t")
      (str/replace #"\\/" "/")
      (str/replace #"\\\\" "\\")))

(defn suggested-search-queries
  [diagnostics]
  (let [message (or (:message (last (or (:sufficiency-decisions diagnostics) [])))
                    "")]
    (if-let [[_ queries-body] (re-find #"Suggested next call: search \{\"queries\":\[(.*?)\]\}\." message)]
      (->> (re-seq #"\"((?:\\.|[^\"])*)\"" queries-body)
           (map second)
           (map unescape-json-string)
           vec)
      [])))

(defn- timing-name
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (some? value) (str value)
    :else nil))

(defn- stage-timing-friendly-name
  [entry]
  (let [stage (:stage entry)
        tool (:tool entry)
        sub-skill (:sub-skill entry)]
    (cond
      (= stage :agent-llm) "LLM turn"
      (= stage :search) "Retrieval search"
      (= stage :read_chunks) "Chunk read"
      (= stage :rerank_results) "Result rerank"
      (= stage :generate_response) "Answer synthesis"
      (= stage :summarization) "Summarization"
      (= sub-skill :builtin/retrieval) "Retrieval search"
      (= sub-skill :builtin/read-chunks) "Chunk read"
      (= sub-skill :builtin/rerank) "Result rerank"
      (= sub-skill :builtin/synthesis) "Answer synthesis"
      (= tool "search_documents") "Retrieval search"
      (= tool "read_chunks") "Chunk read"
      (= tool "rerank_results") "Result rerank"
      (= tool "generate_response") "Answer synthesis"
      (= tool "plan_queries") "Query planning"
      :else nil)))

(defn format-stage-timing-label
  [entry]
  (let [step-name (timing-name (:step-id entry))
        skill-name (timing-name (:skill-id entry))
        tool-name (:tool entry)
        stage-name (or (stage-timing-friendly-name entry)
                       (timing-name (:stage entry)))]
    (cond
      (and step-name skill-name) (str step-name " (" skill-name ")")
      step-name step-name
      stage-name stage-name
      tool-name tool-name
      :else "timing")))

(defn- summarize-stage-quantity
  [entry]
  (cond
    (and (:result-count entry) (pos? (:result-count entry)))
    (str (:result-count entry) " results")

    (and (:input-length entry) (pos? (:input-length entry)))
    (str (:input-length entry) " input chars")

    (and (:output-length entry) (pos? (:output-length entry)))
    (str (:output-length entry) " output chars")

    (and (:iteration entry) (integer? (:iteration entry)))
    (str "iteration " (:iteration entry))

    :else
    nil))

(defn- summarize-stage-tokens
  "Summarize LLM token usage for a stage timing entry when :usage is present.
   Surfaces prompt / cached-hit / completion so cache utilization is visible
   directly in the Detailed view timing list."
  [entry]
  (when-let [usage (:usage entry)]
    (let [prompt (:prompt_tokens usage)
          completion (:completion_tokens usage)
          cached (get-in usage [:prompt_tokens_details :cached_tokens])
          cache-pct (when (and prompt cached (pos? prompt))
                      (Math/round (double (* 100.0 (/ cached prompt)))))
          parts (cond-> []
                  prompt (conj (str prompt " prompt"))
                  (and cached (pos? cached))
                  (conj (str cached " cached" (when cache-pct (str " (" cache-pct "%)"))))
                  completion (conj (str completion " completion")))]
      (when (seq parts)
        (str/join " / " parts)))))

(defn format-stage-timing-detail
  [entry]
  (let [quantity (summarize-stage-quantity entry)
        tokens (summarize-stage-tokens entry)
        parts (cond-> []
                quantity
                (conj quantity)
                tokens
                (conj tokens)
                (:status entry)
                (conj (timing-name (:status entry)))
                (:defaulted entry)
                (conj "defaulted")
                (:skipped entry)
                (conj "skipped"))]
    (str/join " · " parts)))

(defn- tool-call-args-summary
  [tc]
  (case (:tool tc)
    "search_documents"
    (let [queries (or (get-in tc [:args :queries]) [])]
      (if (seq queries)
        (str (count queries) " query variants"
             (when (seq queries)
               (str ": " (str/join " | " (take 3 queries))))
             (when (> (count queries) 3)
               " ..."))
        "No search queries captured"))

    "plan_queries"
    (str "Planning follow-up queries for \""
         (or (get-in tc [:args :query]) "")
         "\"")

    "rerank_results"
    (str "Ranking results for \""
         (or (get-in tc [:args :query]) "")
         "\"")

    "read_chunks"
    (let [operation-label (read-operation-label tc)
          chunk-ids (or (get-in tc [:args :chunk_ids]) [])
          doc-num (get-in tc [:args :doc_num])
          [range-from range-to] (chunk-range-bounds (get-in tc [:args :chunk_range]))]
      (cond
        (seq chunk-ids)
        (str operation-label " " (count chunk-ids) " chunk"
             (when (not= 1 (count chunk-ids)) "s")
             " from search results")

        (and doc-num (or range-from range-to))
        (str operation-label " doc " doc-num " chunks " range-from "-" range-to)

        doc-num
        (str operation-label " doc " doc-num)

        :else
        (str operation-label " retrieved context")))

    "generate_response"
    "Generate an answer from the current evidence"

    (let [args (:args tc)]
      (cond
        (map? args)
        (let [keys-to-show (->> args
                                keys
                                sort
                                (take 4))]
          (if (seq keys-to-show)
            (str "Inputs: "
                 (str/join ", "
                           (map (fn [k]
                                  (str (name k) "="
                                       (let [v (get args k)]
                                         (cond
                                           (vector? v) (str (count v) " items")
                                           (map? v) (str (count v) " fields")
                                           :else (str v)))))
                                keys-to-show)))
            "No inputs captured"))
        :else
        "No inputs captured"))))

(defn- summarize-effective-parameters
  [tc]
  (let [params (:effective-parameters tc)
        primary (:primary params)]
    (case (:tool tc)
      "search_documents"
      (str "retrieve-top-k " (or (:retrieve-top-k primary) "?")
           ", max-per-document " (or (:max-per-document primary) "?")
           ", query-aware-boost " (if (true? (:query-aware-boost primary)) "on" "off"))

      "read_chunks"
      (str "local read signals " (if (true? (get-in params [:primary :local-read-signals?])) "on" "off"))

      "rerank_results"
      (str "context-top-k " (or (:context-top-k primary) "?")
           ", min-chunks " (or (:context-min-chunks primary) "?")
           ", threshold " (or (:context-relative-score-threshold primary) "?"))

      "generate_response"
      (str "generation prompt " (if (seq (:generation-prompt primary)) "custom" "default"))

      "plan_queries"
      (str "planning prompt " (if (seq (:prompt primary)) "custom" "default"))

      (if (map? params)
        (let [pairs (->> params
                         (take 4)
                         (map (fn [[k v]]
                                (str (name k) "="
                                     (cond
                                       (map? v) (str (count v) " fields")
                                       (vector? v) (str (count v) " items")
                                       :else (str v)))))
                         vec)
              summary (str/join ", " pairs)]
          (if (seq summary)
            summary
            "No effective parameter overrides"))
        "No effective parameter overrides"))))

(defn execution-stage-timing-entries
  [diagnostics]
  (let [graph-timings (or (:execution-stage-timings diagnostics)
                          (get-in diagnostics [:skill-execution-metadata :stage-timings])
                          [])
        agent-timings (or (:agent-stage-timings diagnostics) [])]
    (vec
     (concat
      (map-indexed (fn [idx entry]
                     (let [entry (compact-stage-timing-entry entry)]
                       {:id [:graph idx]
                        :source :graph
                        :label (format-stage-timing-label entry)
                        :detail (format-stage-timing-detail entry)
                        :duration-ms (:duration-ms entry)
                        :status (:status entry)
                        :raw entry}))
                   graph-timings)
      (map-indexed (fn [idx entry]
                     (let [entry (compact-stage-timing-entry entry)]
                       {:id [:agent idx]
                        :source :agent
                        :label (format-stage-timing-label entry)
                        :detail (format-stage-timing-detail entry)
                        :duration-ms (:duration-ms entry)
                        :status (:status entry)
                        :raw entry}))
                   agent-timings)))))

(defn agent-iteration-stage-timings
  [diagnostics iteration]
  (->> (or (:agent-stage-timings diagnostics) [])
       (keep-indexed (fn [idx entry]
                       (let [entry (compact-stage-timing-entry entry)]
                         (when (= iteration (:iteration entry))
                           {:id [:agent iteration idx]
                            :source :agent
                            :label (format-stage-timing-label entry)
                            :detail (format-stage-timing-detail entry)
                            :duration-ms (:duration-ms entry)
                            :status (:status entry)
                            :raw entry}))))
       vec))

(defn execution-timing-summary
  [diagnostics]
  (let [entries (execution-stage-timing-entries diagnostics)
        total-ms (or (:total-duration-ms diagnostics)
                     (get-in diagnostics [:skill-execution-metadata :total-duration-ms])
                     0)
        recorded-ms (reduce + (map (fn [{:keys [duration-ms]}]
                                     (or duration-ms 0))
                                   entries))
        unexplained-ms (max 0 (- total-ms recorded-ms))]
    {:total-ms total-ms
     :recorded-ms recorded-ms
     :unexplained-ms unexplained-ms
     :entry-count (count entries)
     :top-entries (->> entries
                       (sort-by (comp (fnil identity 0) :duration-ms) >)
                       (take 4)
                       vec)}))

(defn conflict-summary
  [diagnostics]
  (let [{:keys [latest-decision latest-response-validation last-insufficiency
                last-response-validation-insufficiency]}
        (run-summary diagnostics)
        conflict-decision (or (when (= :conflicting (:status latest-response-validation))
                                latest-response-validation)
                              (when (= :conflicting (:status latest-decision))
                                latest-decision))
        insufficiency (or (when (= conflict-decision latest-response-validation)
                            last-response-validation-insufficiency)
                          last-insufficiency
                          last-response-validation-insufficiency)
        status (:status conflict-decision)
        action (:action conflict-decision)
        failure-type (or (:failure-type conflict-decision)
                         (:failure-type insufficiency))]
    (when (= :conflicting status)
      {:headline "Conflicting evidence"
       :reason (or (some-> failure-type name)
                   "conflict")
       :metric (or (:target-metric conflict-decision)
                   (:target-metric insufficiency))
       :year (or (:target-year conflict-decision)
                 (:target-year insufficiency))
       :source (or (:doc-family-hint conflict-decision)
                   (:doc-family-hint insufficiency))
       :action (or action
                   (:recommended-action insufficiency))
       :gap-summary (:evidence-gap-summary insufficiency)})))

(defn conflict-summary-lines
  [diagnostics]
  (let [summary (conflict-summary diagnostics)]
    (vec
     (remove nil?
             [(when summary
                (str "Reason: " (:reason summary)))
              (when summary
                (str "Recommended action: " (name (or (:action summary) :unknown))))
              (when-let [metric (:metric summary)]
                (str "Target metric: " metric))
              (when-let [year (:year summary)]
                (str "Target year: " year))
              (when-let [source (:source summary)]
                (str "Preferred source: " source))
              (:gap-summary summary)]))))

(defn retrieved-evidence-entries
  [diagnostics]
  (let [merged-results (or (:merged-results diagnostics) [])
        read-chunk-ids (set (concat
                             (mapcat #(or (:returned-chunk-ids %) []) (or (:read-history diagnostics) []))
                             (map :chunk_id (or (:used-chunks diagnostics) []))))]
    (mapv (fn [chunk]
            (assoc chunk
                   :read? (contains? read-chunk-ids (:chunk_id chunk))
                   :search-type-labels (mapv name (sort (or (:search-types chunk) [])))))
          merged-results)))

(defn retrieval-explanation-labels
  [chunk]
  (let [boosts (or (:retrieval-boosts chunk) {})
        ordered-keys [:numeric-evidence :content-overlap :search-type :org :year :title]
        labels {:numeric-evidence "numeric evidence"
                :content-overlap "content overlap"
                :search-type "search type"
                :org "org match"
                :year "year match"
                :title "title match"}]
    (->> ordered-keys
         (keep (fn [k]
                 (let [score (double (or (get boosts k) 0.0))]
                   (when (pos? score)
                     (get labels k)))))
         vec)))

(defn retrieval-explanation-text
  [chunk]
  (let [labels (retrieval-explanation-labels chunk)]
    (when (seq labels)
      (str/join ", " labels))))

(defn decision-timeline-entries
  [diagnostics]
  (vec
   (concat
    (map-indexed (fn [idx entry]
                   {:kind :search
                    :label (str "search " (inc idx))
                    :detail (str (format-search-timeline-detail entry)
                                 (when-let [note (search-budget-note (:budget-state diagnostics))]
                                   (str " · " note)))})
                 (or (:search-history diagnostics) []))
    (map-indexed (fn [idx entry]
                   {:kind :read
                    :label (str "read " (inc idx))
                    :detail (str (format-read-timeline-detail entry)
                                 (when-let [note (read-budget-note (:budget-state diagnostics))]
                                   (str " · " note)))})
                 (or (:read-history diagnostics) []))
    (map-indexed (fn [idx entry]
                   {:kind (normalize-decision-status (:status entry))
                    :label (str "shadow " (inc idx))
                    :detail (format-shadow-decision-timeline-detail entry)})
                 (or (:shadow-sufficiency-decisions diagnostics) []))
    (map-indexed (fn [idx entry]
                   {:kind (normalize-decision-status (:status entry))
                    :label (str (case (:source (compact-sufficiency-decision entry))
                                  :shadow-read-signals "shadow"
                                  :llm-sufficiency-gate "gate"
                                  "decision")
                                " "
                                (inc (+ idx (count (or (:shadow-sufficiency-decisions diagnostics) [])))))
                    :detail (format-decision-timeline-detail entry)})
                 (or (:sufficiency-decisions diagnostics) []))
    (map-indexed (fn [idx entry]
                   {:kind (normalize-decision-status (:status entry))
                    :label (str "validation " (inc idx))
                    :detail (format-decision-timeline-detail entry)})
                 (or (:response-validations diagnostics) []))
    )))

(defn agent-loop-lines
  [diagnostics]
  (let [summary-lines (run-summary-lines diagnostics)
        timeline-entries (decision-timeline-entries diagnostics)
        search-history (or (:search-history diagnostics) [])
        read-history (or (:read-history diagnostics) [])
        suggested-queries (suggested-search-queries diagnostics)
        conflict (conflict-summary diagnostics)]
    (vec
     (concat
      (map (fn [{:keys [label value]}]
             (str label ": " value))
           summary-lines)
      (when (seq timeline-entries)
        ["" "Timeline:"])
      (map (fn [{:keys [kind label detail]}]
             (str "- " (name kind) " · " label " · " detail))
           timeline-entries)
      (when (seq search-history)
        [(str "" )
         (str "Searches: " (count search-history) " · Reads: " (count read-history))])
      (map (fn [entry]
             (str "- " (format-search-history-entry entry)))
           search-history)
      (map (fn [entry]
             (str "- " (format-read-history-entry entry)))
           read-history)
      (when (seq suggested-queries)
        [(str "")
         "Suggested next search:"])
      (map (fn [query]
             (str "- " query))
           suggested-queries)
      (when conflict
        [(str "")
         (str "Conflict: " (:reason conflict))
         (str "Recommended action: " (name (or (:action conflict) :unknown)))])
      (when-let [metric (:metric conflict)]
        [(str "Target metric: " metric)])
      (when-let [year (:year conflict)]
        [(str "Target year: " year)])
      (when-let [source (:source conflict)]
        [(str "Preferred source: " source)])))))

(defn agent-loop-text
  [diagnostics]
  (let [lines (remove str/blank? (agent-loop-lines diagnostics))]
    (when (seq lines)
      (str/join "\n" lines))))

(defn normalize-filter-map
  "Normalize retrieval filter maps to a compact diagnostics-friendly shape."
  [filter-map]
  (when (map? filter-map)
    (let [fields (->> (:fields filter-map)
                      (keep (fn [f]
                              (when (map? f)
                                (let [field (or (:field f) (:name f))
                                      selected-options (or (:selected-options f) (:selected_options f))
                                      value-type (or (:value-type f) (:value_type f) :string)
                                      options (->> selected-options
                                                   (map str)
                                                   sort
                                                   vec)]
                                  (when (and (string? field) (seq options))
                                    {:field field
                                     :value-type (keyword (name value-type))
                                     :selected-options options})))))
                      vec)]
      (when (seq fields)
        {:fields fields}))))

(defn filter-entry
  [source filter-type filter-map fallback?]
  (when-let [normalized-filter (normalize-filter-map filter-map)]
    {:source source
     :type filter-type
     :filter normalized-filter
     :fallback (boolean fallback?)}))

(defn filters-from-attribution
  "Extract retrieval filter diagnostics from one search-attribution map."
  [source attribution]
  (when (map? attribution)
    (let [fallback? (:auto-filter-fallback attribution)
          filter-applied (:filter-applied attribution)
          filter-source (or (:filter-source attribution)
                            (when (:auto-filter-applied attribution) :auto)
                            :explicit)
          auto-filter (:auto-filter-applied attribution)]
      (->> [(filter-entry source filter-source filter-applied fallback?)
            ;; Backward compatibility for old attribution payloads.
            (when (and auto-filter (nil? filter-applied))
              (filter-entry source :auto auto-filter fallback?))]
           (remove nil?)
           vec))))

(defn filters-from-agent-trace
  "Extract explicit filter usage from agent search_documents tool calls."
  [agent-trace]
  (->> (or agent-trace [])
       (mapcat :tool-calls)
       (filter #(= "search_documents" (:tool %)))
       (keep (fn [tool-call]
               (let [args (:args tool-call)
                     filter-map (or (:filter-by args) (:filter_by args))]
                 (filter-entry :agent-trace :explicit filter-map false))))
       vec))

(defn collect-retrieval-filters
  "Collect and deduplicate retrieval filters used during execution."
  [search-attribution search-attributions agent-trace]
  (let [filters (concat
                 (filters-from-attribution :search-attribution search-attribution)
                 (mapcat (fn [attr]
                           (filters-from-attribution :search-attributions attr))
                         (or search-attributions []))
                 (filters-from-agent-trace agent-trace))]
    (->> filters
         distinct
         vec)))

(defn format-retrieval-filter-label
  "Render a retrieval filter diagnostics entry as compact text."
  [entry]
  (let [source-label (case (:source entry)
                       :agent-trace "Agent"
                       :search-attribution "Search"
                       :search-attributions "Search"
                       "Search")
        type-label (case (:type entry)
                     :auto "auto"
                     :explicit "explicit"
                     (name (or (:type entry) :explicit)))
        fallback-label (when (:fallback entry) " (fallback)")
        field-str (->> (get-in entry [:filter :fields])
                       (map (fn [{:keys [field selected-options]}]
                              (str field "=" (str/join "," selected-options))))
                       (str/join " | "))]
    (str source-label " " type-label fallback-label ": " field-str)))

(defn retrieval-filter-entries
  "Get retrieval filter entries from diagnostics, with backward compatibility."
  [diagnostics]
  (let [entries (vec (or (:retrieval-filters diagnostics) []))
        auto-filter (:auto-filter-applied diagnostics)
        fallback (:auto-filter-fallback diagnostics)]
    (if (seq entries)
      entries
      (cond-> []
        auto-filter (conj {:source :search-attribution
                           :type :auto
                           :filter (normalize-filter-map auto-filter)
                           :fallback (boolean fallback)})))))

(defn- parse-int-safe [s]
  (when s
    (#?(:clj Integer/parseInt
        :cljs js/parseInt)
     s 10)))

(defn- normalize-tool-result
  "Create a human-readable summary of tool execution results."
  [tc]
  (let [tool (:tool tc)
        args (:args tc)
        result (:result-summary tc)]
    (cond
      (= tool "search_documents")
      (let [query-count (count (or (:queries args) []))
            hit-count (or (some-> (re-find #"returned (\d+) hits" (or result "")) second parse-int-safe) 0)]
        (str "Searched " query-count " phrasings; found " hit-count " hits."))

      (= tool "read_chunks")
      (let [chunk-ids (:chunk_ids args)
            doc-num (:doc_num args)
            operation-label (read-operation-label tc)
            char-count (or (some-> (re-find #"Read (\d+) chars" (or result "")) second parse-int-safe) 0)]
        (if (seq chunk-ids)
          (str operation-label " " (count chunk-ids) " specific chunks (" char-count " chars).")
          (str operation-label " document " doc-num " range (" char-count " chars).")))

      (= tool "rerank_results")
      (let [candidate-count (or (some-> (re-find #"Reranked (\d+) candidates" (or result "")) second parse-int-safe) 0)]
        (str "Reranked " candidate-count " candidates for relevance."))

      (= tool "generate_response")
      (if (some-> result (str/includes? "insufficient"))
        "Attempted answer; evidence was insufficient."
        "Generated candidate answer from current evidence.")

      :else (or result "Execution completed."))))

(defn tool-call-narrative
  [tc]
  (case (:tool tc)
    "search_documents"
    (let [queries (or (get-in tc [:args :queries]) [])]
      (if (seq queries)
        (str "Searched for " (str/join ", " (map #(str "\"" % "\"") queries)))
        "Searched for additional evidence"))

    "plan_queries"
    (str "Planned follow-up queries for \"" (get-in tc [:args :query] "") "\"")

    "rerank_results"
    (str "Ranked results for \"" (get-in tc [:args :query] "") "\"")

    "read_chunks"
    (let [operation-label (read-operation-label tc)
          chunk-ids (or (get-in tc [:args :chunk_ids]) [])
          doc-num (get-in tc [:args :doc_num])
          [range-from range-to] (chunk-range-bounds (get-in tc [:args :chunk_range]))]
      (cond
        (seq chunk-ids)
        (str operation-label " " (count chunk-ids) " chunk"
             (when (not= 1 (count chunk-ids)) "s")
             " from the search results")

        (and doc-num (or range-from range-to))
        (str operation-label " doc " doc-num " chunks " range-from "-" range-to)

        doc-num
        (str operation-label " doc " doc-num)

        :else
        (str operation-label " retrieved context")))

    "generate_response"
    "Generated answer from the current evidence"

    (str "Called " (:tool tc))))

(defn tool-call-input-summary
  [tc]
  (tool-call-args-summary tc))

(defn tool-call-effective-parameters-summary
  [tc]
  (summarize-effective-parameters tc))

(defn compact-iteration-history
  "Prepare iteration history for the UI trace view."
  [history]
  (mapv (fn [turn]
          (let [tool-calls (mapv (fn [tc]
                                   (assoc tc
                                          :summary (normalize-tool-result tc)
                                          :narrative (tool-call-narrative tc)
                                          :operation-kind (when (= "read_chunks" (:tool tc))
                                                            (read-operation-kind tc))
                                          :operation-label (when (= "read_chunks" (:tool tc))
                                                             (read-operation-label tc))))
                                 (or (:tool-calls turn) []))]
            (assoc turn
                   :tool-calls tool-calls
                   :budget-snapshot (compact-budget-state (:budget-snapshot turn)))))
        (or history [])))

(defn normalize-diagnostics
  "Normalize diagnostics payload into a stable shape for headless consumers."
  [diagnostics]
  (let [diagnostics (or diagnostics {})
        retrieval-filters (retrieval-filter-entries diagnostics)
        summary (run-summary diagnostics)]
    (-> diagnostics
        (assoc :retrieval-filters retrieval-filters)
        (assoc :run-summary summary)
        (update :status normalize-run-status)
        (update :clarification-request compact-clarification-request)
        (update :query-relaxation #(vec (or % [])))
        (update :citations #(vec (or % [])))
        (update :action-trace action-trace/normalize-action-trace)
        (update :agent-trace #(vec (or % [])))
        (update :execution-stage-timings #(mapv compact-stage-timing-entry (or % [])))
        (update :agent-stage-timings #(mapv compact-stage-timing-entry (or % [])))
        (update :iteration-history #(compact-iteration-history (or % [])))
        (update :evidence-plan compact-evidence-plan)
        (update :read-evaluations #(mapv compact-read-signal (or % [])))
        (update :last-read-signal compact-read-signal)
        (update :search-history #(mapv compact-search-entry (or % [])))
        (update :read-history #(mapv compact-read-entry (or % [])))
        (update :shadow-sufficiency-decisions #(mapv compact-sufficiency-decision (or % [])))
        (update :sufficiency-decisions #(mapv compact-sufficiency-decision (or % [])))
        (update :response-validations #(mapv compact-sufficiency-decision (or % []))))))
