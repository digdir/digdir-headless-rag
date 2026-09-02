(ns digdir.skills.builtin.agent.core
  "Agent skill - High-level entry points and registration."
  (:require [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.builtin.agent.read-signals :as read-signals]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.loop :as loop]
            [digdir.skills.graph.trace :as graph-trace]
            [digdir.skills.graph.runner :as graph-runner]
            [digdir.skills.templates.core :as templates]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.naming :as enrich-naming]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Tool Definition (for meta-agent invocation)
;; =============================================================================

(def agent-tool-definition
  "Tool definition for invoking the agent from another agent or graph."
  {:type "function"
   :function
   {:name "run_agent"
    :description "Run an agentic RAG loop that dynamically searches, reranks, and synthesizes a response"
    :parameters
    {:type "object"
     :properties
     {:query
      {:type "string"
       :description "The question to research and answer"}}
     :required ["query"]}}})

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def agent-metadata
  {:skill-id :builtin/agent
   :name "Agentic RAG"
   :description "ReAct-style agentic loop that dynamically composes skills via LLM tool use"
   :category :orchestration
   :inputs [:query :docs-collection :chunks-collection :phrases-collection :conversation-history]
   :outputs [:response :clarification-request :trace :chunks :queries :insufficient-context :search-errors :backend-issues
             :query-intent :budget-state :search-history :read-history :sufficiency-decisions :response-validations
             :search-attributions :last-insufficiency :last-response-validation-insufficiency
             :citations :citation-index :evidence-plan :claim-coverage :read-evaluations
             :open-evidence-gaps :evidence-contradictions :last-read-signal :shadow-sufficiency-decisions]
   :parameters {:model :string
                :temperature :number
                :max-iterations :number
                :max-search-passes :number
                :max-read-operations :number
                :max-read-content-length :number
                :system-prompt :string
                ;; Phase 2.5 — graph composition variant selector. Defaults
                ;; to :imperative (current loop). :bundled / :faithful are
                ;; wired up in sub-phases 2.5.C / 2.5.D respectively, then
                ;; compared via the eval gate in 2.5.E.
                :graph-variant :keyword}
   :version "1.0.0"
   :tags #{:llm :agent :orchestration :react}
   :tool-definition agent-tool-definition})

;; =============================================================================
;; Trace File Writing
;; =============================================================================

(def ^:private trace-dir "logs")

(defn- summarize-message
  "Build a compact, single-line summary of a chat message for trace logging."
  [msg]
  (let [role (or (:role msg) "unknown")
        content (-> (or (:content msg) "")
                    (str/replace #"\s+" " ")
                    str/trim)
        preview (if (> (count content) 180)
                  (str (subs content 0 180) "...")
                  content)]
    {:role role
     :content-preview preview}))

(defn- content-preview
  "Extract and truncate content_markdown for trace display."
  [chunk max-len]
  (let [content (or (:content_markdown chunk) "")]
    (if (> (count content) max-len)
      (str (subs content 0 max-len) "...")
      content)))

(defn- extract-source-metadata
  "Extract compact source metadata from a retrieved chunk."
  [chunk]
  (let [doc-entry (some (fn [v]
                          (when (and (map? v)
                                     (or (contains? v :title)
                                         (contains? v :url)))
                            v))
                        (vals chunk))]
    (cond-> {:chunk-id (:chunk_id chunk)
             :doc-num (:doc_num chunk)
             :metadata (:metadata chunk)}
      (:title doc-entry) (assoc :title (:title doc-entry))
      (:url doc-entry) (assoc :url (:url doc-entry)))))

(defn- cited-source-rows
  "Build ordered citation rows (index -> chunk id + metadata)."
  [citations citation-index workspace-chunks]
  (let [chunks-by-id (into {} (map (juxt :chunk_id identity) workspace-chunks))
        cited-indices (if (seq citations)
                        (->> citations (map :index) distinct sort)
                        (sort (keys citation-index)))]
    (mapv (fn [idx]
            (let [chunk-id (get citation-index idx)
                  chunk (get chunks-by-id chunk-id)]
              {:index idx
               :chunk-id chunk-id
               :chunk chunk
               :source-metadata (when chunk (extract-source-metadata chunk))}))
          cited-indices)))

(def ^:private found-claim-pattern
  #"(?iu)\b(jeg har funnet|har funnet informasjon|jeg fant|found the answer|i found)\b")

(def ^:private missing-answer-pattern
  #"(?iu)(ikke\s+.*?(oppgitt|spesifisert|funnet)|ingen spesifikk informasjon|not\s+specified|not\s+found|could not find)")

(defn- trace-quality-warnings
  "Detect basic reasoning/response consistency issues for trace debugging."
  [trace response]
  (let [found-claim-iters (->> (or trace [])
                               (keep (fn [{:keys [iteration reasoning tool-calls]}]
                                       (when (and (string? reasoning)
                                                  (re-find found-claim-pattern reasoning)
                                                  (some #(= "generate_response" (:tool %)) (or tool-calls [])))
                                         iteration)))
                               vec)
        response-missing? (boolean (re-find missing-answer-pattern (or response "")))]
    (cond-> []
      (and response-missing? (seq found-claim-iters))
      (conj {:code :found-vs-not-found
             :message (str "Iteration(s) "
                           (str/join ", " found-claim-iters)
                           " claim answer found, but final response indicates answer not found.")}))))

(defn- joined-keywords
  [values]
  (if (seq values)
    (str/join ", "
              (map (fn [value]
                     (cond
                       (keyword? value) (name value)
                       (string? value) value
                       :else (pr-str value)))
                   values))
    "(none)"))

(defn- format-read-signal-summary
  [label signal]
  (str "[" label "] "
       "status=" (name (or (:status signal) :unknown))
       (when-let [scope (:scope-assessment signal)]
         (str " scope=" (name scope)))
       (when-let [hint (:next-action-hint signal)]
         (str " hint=" (name hint)))
       (when (contains? signal :confidence)
         (str " confidence=" (:confidence signal)))
       " degraded=" (boolean (:degraded? signal))
       (when-let [mode (:evaluation-mode signal)]
         (str " mode=" (name mode)))
       " supported=" (count (or (:supported-claims signal) []))
       " gaps=" (count (or (:remaining-gaps signal) []))
       " contradictions=" (count (or (:contradictions signal) []))
       "\n"))

(defn- append-read-signal-details!
  [sb signal]
  (doseq [[idx {:keys [claim-id support-level support chunk-ids notes]}]
          (map-indexed vector (or (:supported-claims signal) []))]
    (.append sb (str "  [supported " (inc idx) "] "
                     "claim=" (name (or claim-id :unknown))
                     " support=" (name (or support-level support :unknown))
                     (when (seq chunk-ids)
                       (str " chunks=" (pr-str (vec chunk-ids))))
                     (when (seq notes)
                       (str " notes=" notes))
                     "\n")))
  (doseq [[idx {:keys [claim-id critical critical? reason]}]
          (map-indexed vector (or (:remaining-gaps signal) []))]
    (.append sb (str "  [gap " (inc idx) "] "
                     "claim=" (name (or claim-id :unknown))
                     " "
                     " critical=" (boolean (or critical critical?))
                     (when reason
                       (str " reason=" (name reason)))
                     "\n")))
  (doseq [[idx {:keys [claim-id chunk-ids summary]}]
          (map-indexed vector (or (:contradictions signal) []))]
    (.append sb (str "  [contradiction " (inc idx) "] "
                     (when claim-id
                       (str "claim=" (name claim-id) " "))
                     (when (seq chunk-ids)
                       (str "chunks=" (pr-str (vec chunk-ids)) " "))
                     (when (seq summary)
                       (str "summary=" summary))
                     "\n"))))

(defn- append-shadow-decision!
  [sb idx decision]
  (.append sb (str "[" (inc idx) "] "
                   "status=" (name (or (:status decision) :unknown))
                   (when-let [action (or (:action decision) (:suggested-strategy decision))]
                     (str " action=" (name action)))
                   (when-let [source (:source decision)]
                     (str " source=" (name source)))
                   (when-let [reason-code (:reason-code decision)]
                     (str " reason-code=" (name reason-code)))
                   (when (contains? decision :supported-claim-count)
                     (str " supported-claims=" (:supported-claim-count decision)))
                   (when-let [missing (seq (:missing-claims decision))]
                     (str " missing-claims=" (joined-keywords missing)))
                   (when-let [stagnant (seq (:stagnant-gap-claims decision))]
                     (str " stagnant-gap-claims=" (joined-keywords stagnant)))
                   "\n"))
  (when-let [contradictions (seq (:contradictions decision))]
    (.append sb (str "  [contradictions] " (pr-str contradictions) "\n"))))

;; =============================================================================
;; Phase 2.0: Synthesize a graph-shaped trace from agent iteration-history.
;; =============================================================================
;;
;; The agent loop is a bespoke imperative ReAct loop, but its trace can adopt
;; the graph-runner trace format. Each iteration becomes a synthetic step.
;; Agent-specific cross-cutting blocks (stage timings, search history,
;; sufficiency decisions, citations, etc.) ride along as :extra-sections that
;; graph-trace appends after the final outputs.

(defn- iteration-step-id
  [iteration]
  (keyword (str "iteration-" iteration)))

(defn- iteration-as-step-def
  "Synthesize a graph-step definition for one ReAct iteration. The step's
   inputs map is empty (agent iterations don't receive graph-shaped inputs);
   parameters are also empty. The skill id is :builtin/agent so trace readers
   see a familiar identifier in the [skill] field."
  [iteration-entry]
  {:id (iteration-step-id (:iteration iteration-entry))
   :skill :builtin/agent
   :inputs {}
   :parameters {}})

(defn- compact-tool-call-for-trace
  "Trim a tool-call entry to the fields the trace reader cares about."
  [tc]
  (cond-> (select-keys tc [:tool :args :result-summary :duration-ms :stage :sub-skill :ok?])
    (:effective-parameters tc) (assoc :effective-parameters (:effective-parameters tc))))

(defn- iteration-as-step-result
  "Synthesize a success-result map so format-step-block can render the
   iteration's reasoning + tool calls as the step's [outputs]."
  [{:keys [reasoning tool-calls budget-snapshot]}]
  (skills/success-result
   (cond-> {}
     (not (str/blank? reasoning)) (assoc :reasoning reasoning)
     (seq tool-calls) (assoc :tool-calls (mapv compact-tool-call-for-trace tool-calls))
     budget-snapshot (assoc :budget-snapshot budget-snapshot))
   {}))

(defn- iteration-as-step-timing
  "Aggregate the stage-timings entries from one iteration into a single timing
   record for the synthesized step. The total duration is the sum of every
   stage-timing tagged with this iteration."
  [stage-timings iteration]
  (let [iter-timings (filter #(= iteration (:iteration %)) stage-timings)
        total (reduce + 0 (keep :duration-ms iter-timings))
        any-error? (some #(= :error (:status %)) iter-timings)]
    {:step-id (iteration-step-id iteration)
     :skill-id :builtin/agent
     :duration-ms total
     :status (if any-error? :error :ok)}))

(defn- synthesize-agent-graph-skeleton
  "Build the {:step-defs :step-results :step-timings} shape that graph-trace
   expects, from the agent's iteration-history."
  [{:keys [trace stage-timings]}]
  (let [step-defs (mapv iteration-as-step-def (or trace []))
        step-results (into {}
                           (map (fn [entry]
                                  [(iteration-step-id (:iteration entry))
                                   (iteration-as-step-result entry)]))
                           (or trace []))
        step-timings (into {}
                           (map (fn [entry]
                                  [(iteration-step-id (:iteration entry))
                                   (iteration-as-step-timing (or stage-timings [])
                                                             (:iteration entry))]))
                           (or trace []))]
    {:step-defs step-defs
     :step-results step-results
     :step-timings step-timings}))

(defn- ->extra-section
  "Render a section body using clojure.string ops, returning either nil (skip)
   or `{:title ... :content ...}` for graph-trace's :extra-sections."
  [title body-str]
  (when (and body-str (not (str/blank? body-str)))
    {:title title
     :content (str/trim-newline body-str)}))

(defn- render-stage-timings-section
  [stage-timings]
  (when (seq stage-timings)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [stage iteration tool sub-skill duration-ms status detail
                           input-length output-length result-count usage llm-model finish-reason]}]
              (map-indexed vector stage-timings)]
        (let [prompt-tokens (:prompt_tokens usage)
              completion-tokens (:completion_tokens usage)
              cached-tokens (get-in usage [:prompt_tokens_details :cached_tokens])
              cache-hit-pct (when (and prompt-tokens cached-tokens (pos? prompt-tokens))
                              (Math/round (double (* 100.0 (/ cached-tokens prompt-tokens)))))]
          (.append sb (str "[" (inc idx) "] "
                           "stage=" (or (some-> stage name) "unknown")
                           (when iteration (str " iteration=" iteration))
                           (when tool (str " tool=" tool))
                           (when sub-skill (str " sub-skill=" (some-> sub-skill name)))
                           (when (some? duration-ms) (str " duration-ms=" duration-ms))
                           (when status (str " status=" (name status)))
                           (when input-length (str " input-length=" input-length))
                           (when output-length (str " output-length=" output-length))
                           (when result-count (str " result-count=" result-count))
                           (when prompt-tokens (str " prompt-tokens=" prompt-tokens))
                           (when cached-tokens (str " cached-tokens=" cached-tokens))
                           (when cache-hit-pct (str " cache-hit=" cache-hit-pct "%"))
                           (when completion-tokens (str " completion-tokens=" completion-tokens))
                           (when llm-model (str " llm-model=" llm-model))
                           (when finish-reason (str " finish-reason=" finish-reason))
                           (when detail (str " detail=" detail))
                           "\n"))))
      (str sb))))

(defn- render-effective-parameters-section
  [agent-effective-parameters]
  (when agent-effective-parameters
    (str "[agent] " (pr-str agent-effective-parameters) "\n")))

(defn- render-synthesis-prompts-section
  [synthesis-prompts]
  (when (seq synthesis-prompts)
    (let [sb (StringBuilder.)]
      (when-let [system-prompt (:system synthesis-prompts)]
        (.append sb (str "[system] "
                         (if (> (count system-prompt) 1200)
                           (str (subs system-prompt 0 1200) "\n[truncated]")
                           system-prompt)
                         "\n")))
      (when-let [full-prompt (:full synthesis-prompts)]
        (.append sb (str "[full] "
                         (if (> (count full-prompt) 12000)
                           (str (subs full-prompt 0 12000) "\n[truncated]")
                           full-prompt)
                         "\n")))
      (str sb))))

(defn- render-auto-filter-section
  [search-attributions]
  (let [auto-filters (->> (or search-attributions [])
                          (keep :auto-filter-applied)
                          distinct)]
    (when (seq auto-filters)
      (let [sb (StringBuilder.)]
        (doseq [af auto-filters]
          (let [field (get-in af [:fields 0 :field])
                orgs (get-in af [:fields 0 :selected-options])]
            (.append sb (str "[detected] " field ": " (str/join ", " orgs) "\n"))))
        (when (some :auto-filter-fallback (or search-attributions []))
          (.append sb "[fallback] Filter returned 0 results on at least one search; retried unfiltered\n"))
        (str sb)))))

(defn- render-search-history-section
  [search-history]
  (when (seq search-history)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [queries filter-by result-count new-count fallback?]}] (map-indexed vector search-history)]
        (.append sb (str "[" (inc idx) "] "
                         "queries=" (pr-str queries)
                         " result-count=" result-count
                         " new-count=" new-count
                         (when filter-by (str " filter-by=" (pr-str filter-by)))
                         (when fallback? " fallback=true")
                         "\n")))
      (str sb))))

(defn- render-search-errors-section
  [search-errors]
  (when (seq search-errors)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [queries filter-by fallback? error-type error-message]}]
              (map-indexed vector search-errors)]
        (.append sb (str "[" (inc idx) "] "
                         "type=" (or (some-> error-type name) "unknown")
                         " message=" (or error-message "(none)")
                         " queries=" (pr-str queries)
                         (when filter-by (str " filter-by=" (pr-str filter-by)))
                         (when fallback? " fallback=true")
                         "\n")))
      (str sb))))

(defn- render-backend-issues-section
  [backend-issues]
  (when (seq backend-issues)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [source tool issue-type message queries filter-by fallback? details]}]
              (map-indexed vector backend-issues)]
        (.append sb (str "[" (inc idx) "] "
                         "source=" (or (some-> source name) "unknown")
                         " tool=" (or tool "unknown")
                         " type=" (or (some-> issue-type name) "unknown")
                         " message=" (or message "(none)")
                         (when (seq queries) (str " queries=" (pr-str queries)))
                         (when filter-by (str " filter-by=" (pr-str filter-by)))
                         (when fallback? " fallback=true")
                         (when (seq details) (str " details=" (pr-str details)))
                         "\n")))
      (str sb))))

(defn- render-read-history-section
  [read-history]
  (when (seq read-history)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [mode chunk-ids doc-num chunk-range returned-count content-length default-range? max-content-length]}]
              (map-indexed vector read-history)]
        (.append sb (str "[" (inc idx) "] "
                         "mode=" (name mode)
                         (when max-content-length " skim=true")
                         (when (seq chunk-ids) (str " chunk-ids=" (pr-str chunk-ids)))
                         (when doc-num (str " doc-num=" doc-num))
                         (when chunk-range (str " chunk-range=" (pr-str chunk-range)))
                         (when default-range? " default-range=true")
                         (when max-content-length (str " max-content-length=" max-content-length))
                         " returned-count=" returned-count
                         " content-length=" content-length
                         "\n")))
      (str sb))))

(defn- render-read-signals-section
  [{:keys [evidence-plan claim-coverage read-evaluations last-read-signal
           open-evidence-gaps evidence-contradictions]}]
  (when (or evidence-plan
            (seq claim-coverage)
            (seq read-evaluations)
            last-read-signal
            (seq open-evidence-gaps)
            (seq evidence-contradictions))
    (let [sb (StringBuilder.)]
      (when-let [required-claims (seq (:required-claims evidence-plan))]
        (.append sb (str "[required-claims] "
                         (joined-keywords (map :claim-id required-claims))
                         "\n")))
      (when (seq claim-coverage)
        (.append sb (str "[covered-claims] "
                         (joined-keywords (keys claim-coverage))
                         "\n")))
      (when (seq open-evidence-gaps)
        (.append sb (str "[open-gaps] "
                         (joined-keywords (map :claim-id open-evidence-gaps))
                         "\n")))
      (when (seq evidence-contradictions)
        (.append sb (str "[open-contradictions] "
                         (count evidence-contradictions)
                         "\n")))
      (when last-read-signal
        (.append sb (format-read-signal-summary "last" last-read-signal))
        (append-read-signal-details! sb last-read-signal))
      (doseq [[idx signal] (map-indexed vector read-evaluations)]
        (.append sb (format-read-signal-summary (str "eval " (inc idx)) signal))
        (append-read-signal-details! sb signal))
      (str sb))))

(defn- render-shadow-sufficiency-section
  [shadow-sufficiency-decisions]
  (when (seq shadow-sufficiency-decisions)
    (let [sb (StringBuilder.)]
      (doseq [[idx decision] (map-indexed vector shadow-sufficiency-decisions)]
        (append-shadow-decision! sb idx decision))
      (str sb))))

(defn- render-decision-section
  "Shared renderer for SUFFICIENCY DECISIONS / RESPONSE VALIDATIONS."
  [decisions]
  (when (seq decisions)
    (let [sb (StringBuilder.)]
      (doseq [[idx {:keys [status action source message insufficiency iteration]}] (map-indexed vector decisions)]
        (.append sb (str "[" (inc idx) "] "
                         "status=" (name status)
                         (when action (str " action=" (name action)))
                         (when source (str " source=" (name source)))
                         (when iteration (str " iteration=" iteration))
                         "\n"))
        (when insufficiency
          (.append sb (str "  [insufficiency] " (pr-str insufficiency) "\n")))
        (when message
          (.append sb (str "  [message] "
                           (if (> (count message) 400)
                             (str (subs message 0 400) "...")
                             message)
                           "\n"))))
      (str sb))))

(defn- render-initial-messages-section
  [initial-summaries]
  (let [sb (StringBuilder.)]
    (if (seq initial-summaries)
      (doseq [[idx m] (map-indexed vector initial-summaries)]
        (.append sb (str "[" idx "] " (:role m) ": " (:content-preview m) "\n")))
      (.append sb "[none]\n"))
    (str sb)))

(defn- render-quality-warnings-section
  [quality-warnings]
  (when (seq quality-warnings)
    (let [sb (StringBuilder.)]
      (doseq [{:keys [code message]} quality-warnings]
        (.append sb (str "[warning] " (name code) ": " message "\n")))
      (str sb))))

(defn- render-citation-validation-section
  [citation-validation]
  (when citation-validation
    (let [sb (StringBuilder.)]
      (.append sb (str "[valid-indices] "
                       (if (seq (:valid-indices citation-validation))
                         (str/join ", " (sort (:valid-indices citation-validation)))
                         "(none)")
                       "\n"))
      (.append sb (str "[invalid-indices] "
                       (if (seq (:invalid-indices citation-validation))
                         (str/join ", " (sort (:invalid-indices citation-validation)))
                         "(none)")
                       "\n"))
      (.append sb (str "[total-references] " (:total-references citation-validation) "\n"))
      (.append sb (str "[all-valid] " (:all-valid? citation-validation) "\n"))
      (when (contains? citation-validation :verification-skipped?)
        (.append sb (str "[verification-skipped] " (:verification-skipped? citation-validation) "\n")))
      (when (contains? citation-validation :response-changed?)
        (.append sb (str "[response-changed] " (:response-changed? citation-validation) "\n")))
      (when (:response-changed? citation-validation)
        (let [pre-resp (or (:pre-verification-response citation-validation) "")
              truncated (if (> (count pre-resp) 500)
                          (str (subs pre-resp 0 500) "...")
                          pre-resp)]
          (.append sb (str "[pre-verification-response] " truncated "\n"))))
      (str sb))))

(defn- render-cited-sources-section
  [source-rows]
  (let [sb (StringBuilder.)]
    (if (seq source-rows)
      (doseq [{:keys [index chunk-id chunk source-metadata]} source-rows]
        (.append sb (str "[" index "] " chunk-id "\n"))
        (.append sb (str "  [source] " (pr-str source-metadata) "\n"))
        (.append sb (str "  [content] " (content-preview chunk 200) "\n")))
      (.append sb "[none]\n"))
    (str sb)))

(defn- render-workspace-chunks-section
  [workspace-chunks]
  (let [sb (StringBuilder.)]
    (if (seq workspace-chunks)
      (doseq [chunk workspace-chunks]
        (.append sb (str "[chunk] " (:chunk_id chunk) "\n"))
        (.append sb (str "  [source] " (pr-str (extract-source-metadata chunk)) "\n"))
        (.append sb (str "  [content] " (content-preview chunk 200) "\n")))
      (.append sb "[none]\n"))
    (str sb)))

(defn- render-summary-section
  "Top-of-trace agent summary lines that don't fit the graph envelope's
   built-in fields. Rendered as the first extra-section so the agent-specific
   identity (query, model, query-intent) is visible before iteration steps."
  [{:keys [query model skill-id skill-graph-id query-intent queries
           search-history read-history read-content-length budget-state
           sufficiency-decisions source-rows initial-summaries
           trace chunks-count]}]
  (let [sb (StringBuilder.)]
    (.append sb (str "[query] " query "\n"))
    (.append sb (str "[model] " (or model "default") "\n"))
    (.append sb (str "[skill] " (or skill-id "unknown") "\n"))
    (.append sb (str "[skill-graph] " (or skill-graph-id "unknown") "\n"))
    (.append sb (str "[iterations] " (count trace) "\n"))
    (.append sb (str "[total-chunks] " chunks-count "\n"))
    (.append sb (str "[query-intent] " (pr-str query-intent) "\n"))
    (.append sb (str "[queries] " (pr-str queries) "\n"))
    (.append sb (str "[search-passes] " (count (or search-history [])) "\n"))
    (.append sb (str "[read-operations] " (count (or read-history [])) "\n"))
    (.append sb (str "[read-content-length] " (or read-content-length 0) "\n"))
    (.append sb (str "[budget-state] " (pr-str budget-state) "\n"))
    (.append sb (str "[sufficiency-decisions] " (count (or sufficiency-decisions [])) "\n"))
    (.append sb (str "[citation-count] " (count source-rows) "\n"))
    (.append sb (str "[initial-messages-count] " (count initial-summaries) "\n"))
    (str sb)))

(defn- format-trace-file
  "Format the agent trace using the graph-runner envelope.

   Iterations become synthesized steps (one per ReAct iteration); each
   step's [outputs] block contains the iteration's :reasoning, :tool-calls,
   and :budget-snapshot. Agent-specific cross-cutting blocks (stage timings,
   search history, sufficiency decisions, citations, etc.) ride along as
   :extra-sections appended after the final outputs.

   Filename stays agent-trace-* (preserves grep discoverability); the format
   inside is graph-trace-shaped."
  [{:keys [query model trace queries chunks-count status response
           skill-id skill-graph-id citations citation-index citation-validation
           workspace-chunks initial-messages search-attributions
           search-history search-errors backend-issues read-history read-content-length
           evidence-plan claim-coverage read-evaluations open-evidence-gaps evidence-contradictions
           last-read-signal shadow-sufficiency-decisions sufficiency-decisions response-validations
           query-intent budget-state agent-effective-parameters synthesis-prompts
           duration-ms stage-timings] :as trace-data}]
  (let [citation-index (or citation-index {})
        citations (or citations [])
        workspace-chunks (or workspace-chunks [])
        initial-messages (or initial-messages [])
        initial-summaries (mapv summarize-message initial-messages)
        source-rows (cited-source-rows citations citation-index workspace-chunks)
        quality-warnings (trace-quality-warnings trace response)
        skeleton (synthesize-agent-graph-skeleton trace-data)
        summary-data {:query query :model model :skill-id skill-id
                      :skill-graph-id skill-graph-id :query-intent query-intent
                      :queries queries :search-history search-history
                      :read-history read-history :read-content-length read-content-length
                      :budget-state budget-state :sufficiency-decisions sufficiency-decisions
                      :source-rows source-rows :initial-summaries initial-summaries
                      :trace trace :chunks-count chunks-count}
        extra-sections (->> [(->extra-section "AGENT SUMMARY" (render-summary-section summary-data))
                             (->extra-section "STAGE TIMINGS" (render-stage-timings-section stage-timings))
                             (->extra-section "EFFECTIVE PARAMETERS" (render-effective-parameters-section agent-effective-parameters))
                             (->extra-section "SYNTHESIS PROMPTS" (render-synthesis-prompts-section synthesis-prompts))
                             (->extra-section "AUTO-FILTER" (render-auto-filter-section search-attributions))
                             (->extra-section "SEARCH HISTORY" (render-search-history-section search-history))
                             (->extra-section "SEARCH ERRORS" (render-search-errors-section search-errors))
                             (->extra-section "BACKEND ISSUES" (render-backend-issues-section backend-issues))
                             (->extra-section "READ HISTORY" (render-read-history-section read-history))
                             (->extra-section "READ SIGNALS"
                                              (render-read-signals-section
                                               {:evidence-plan evidence-plan
                                                :claim-coverage claim-coverage
                                                :read-evaluations read-evaluations
                                                :last-read-signal last-read-signal
                                                :open-evidence-gaps open-evidence-gaps
                                                :evidence-contradictions evidence-contradictions}))
                             (->extra-section "SHADOW SUFFICIENCY DECISIONS" (render-shadow-sufficiency-section shadow-sufficiency-decisions))
                             (->extra-section "SUFFICIENCY DECISIONS" (render-decision-section sufficiency-decisions))
                             (->extra-section "RESPONSE VALIDATIONS" (render-decision-section response-validations))
                             (->extra-section "INITIAL MESSAGES" (render-initial-messages-section initial-summaries))
                             (->extra-section "TRACE QUALITY CHECKS" (render-quality-warnings-section quality-warnings))
                             (->extra-section "CITATION VALIDATION" (render-citation-validation-section citation-validation))
                             (->extra-section "CITED SOURCES" (render-cited-sources-section source-rows))
                             (->extra-section "WORKSPACE CHUNKS" (render-workspace-chunks-section workspace-chunks))]
                            (filterv some?))
        truncated-response (if (and response (> (count response) 2000))
                             (str (subs response 0 2000) "\n[truncated]")
                             response)]
    (graph-trace/format-trace
     {:graph-id :builtin/agent-rag
      :inputs {:query query :model model :skill-id skill-id}
      :run-status status
      :duration-ms duration-ms
      :step-defs (:step-defs skeleton)
      :step-results (:step-results skeleton)
      :step-timings (:step-timings skeleton)
      :outputs (cond-> {}
                 truncated-response (assoc :response truncated-response))
      :extra-sections extra-sections})))

(defn- write-trace-file!
  "Write the agent trace to a timestamped file.
   Returns the file path or nil on error."
  [trace-data]
  (try
    (let [ts (-> (java.time.Instant/now) str (str/replace ":" "-") (str/replace "." "-"))
          filename (str "agent-trace-" ts ".txt")
          file (io/file trace-dir filename)]
      (io/make-parents file)
      (spit file (format-trace-file trace-data))
      (.getPath file))
    (catch Exception e
      (println "Warning: Failed to write agent trace file:" (.getMessage e))
      nil)))

;; =============================================================================
;; Skill Implementation Helpers
;; =============================================================================

(defn- maybe-backfill-citations-via-synthesis!
  "If the agent finalizes without explicit citation data, run one synthesis pass
   over current workspace chunks to produce citation-index/citations.
   Returns {:response string :backfilled? boolean :strategy keyword}."
  [raw-response query !workspace ambient-ctx]
  (let [raw-response (or raw-response "")
        workspace-snapshot @!workspace
        workspace-chunks (workspace/get-workspace-chunks !workspace)
        has-inline-citations? (workspace/has-citation? raw-response)
        has-citation-data? (or (seq (:citations workspace-snapshot))
                               (seq (:citation-index workspace-snapshot)))
        opts (:opts ambient-ctx)]
    (cond
      has-inline-citations?
      {:response raw-response
       :backfilled? false
       :strategy :already-inline-citations}

      has-citation-data?
      {:response raw-response
       :backfilled? false
       :strategy :citation-data-already-present}

      (empty? workspace-chunks)
      {:response raw-response
       :backfilled? false
       :strategy :no-workspace-chunks}

      :else
      (try
        (let [backfill-start (System/currentTimeMillis)
              context-docs (mapv (fn [chunk]
                                   {:page_content (tools/strip-page-markers (:content_markdown chunk))
                                    :metadata {:source (:chunk_id chunk)}})
                                 (take 10 workspace-chunks))
              synthesis-result (tools/execute-sub-skill
                                :builtin/synthesis
                                {:query query
                                 :context-docs context-docs}
                                opts)
              backfill-duration-ms (- (System/currentTimeMillis) backfill-start)
              synthesis-response (get-in synthesis-result [:outputs :response])]
          (workspace/record-stage-timing! !workspace
                                          {:stage :citation-backfill-synthesis
                                           :duration-ms backfill-duration-ms
                                           :tool "backfill_citations"
                                           :sub-skill :builtin/synthesis
                                           :status (if (or (:error synthesis-result)
                                                           (str/blank? synthesis-response))
                                                     :error
                                                     :ok)
                                           :input-length (count (apply str (map :content_markdown workspace-chunks)))
                                           :output-length (count (or synthesis-response ""))})
          (when synthesis-response
            (swap! !workspace assoc :last-generated-response synthesis-response))
          (when-let [citations (get-in synthesis-result [:outputs :citations])]
            (swap! !workspace assoc :citations citations))
          (when-let [citation-index (get-in synthesis-result [:outputs :citation-index])]
            (swap! !workspace assoc :citation-index citation-index))
          (when-let [cv (get-in synthesis-result [:outputs :citation-validation])]
            (swap! !workspace assoc :citation-validation cv))
          (if (or (:error synthesis-result) (str/blank? synthesis-response))
            {:response raw-response
             :backfilled? false
             :strategy :synthesis-backfill-failed}
            {:response synthesis-response
             :backfilled? true
             :strategy :synthesis-backfill}))
        (catch Exception _
          {:response raw-response
           :backfilled? false
           :strategy :synthesis-backfill-exception})))))

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn execute-agent
  "Execute the agent skill.

   Inputs:
     :query - User query to answer
     :docs-collection - TypeSense documents collection name
     :chunks-collection - TypeSense chunks collection name
     :phrases-collection - TypeSense phrases collection name
     :conversation-history - Vector of previous messages (optional)

   Parameters:
     :model - Model to use (default from config)
     :temperature - Temperature (default 0.3)
     :max-iterations - Max loop iterations (default 10)
     :system-prompt - Custom system prompt (optional)

   Returns:
     :response - Generated response text
     :trace - Vector of iteration history maps"
  [{:keys [inputs parameters skill-params]}]
  (let [execution-start (System/currentTimeMillis)
        {:keys [query docs-collection chunks-collection phrases-collection
                conversation-history]} inputs
        {:keys [model temperature max-iterations system-prompt graph-variant]} parameters
        ;; Phase 2.5 variant selector. As of 2.5.F the default is :bundled
        ;; — the 4-bundle graph composition that posted the highest mean
        ;; total-pass and lowest mean regressed-count in the n=3 stability
        ;; batch (plans/in-progress/2.5-eval-gate-2026-05-16/report.md).
        ;; :imperative (legacy loop) and :faithful (10-step graph,
        ;; observability variant) remain registered as opt-in fallbacks.
        ;; Falls back to :graph-variant in skill-params so eval-time CLI
        ;; overrides can switch variants without re-registering the skill.
        graph-variant (or graph-variant (:graph-variant skill-params) :bundled)
        progress-fn (:progress-fn skill-params)

        ;; Create workspace
        !workspace (workspace/create-workspace)
        ;; Budget limits resolve from the graph's `parameters`, but the sweep
        ;; runner (and any invoke-rag caller) can only reach the agent through
        ;; `:skill-params` — `parameters` is graph-internal. The helper lifts
        ;; the budget keys out of `[:skill-params :builtin/agent]` so a matrix
        ;; config can sweep them (mirrors the `:system-prompt` hand-off in
        ;; invoke.clj).
        budget-limits (workspace/resolve-budget-limits-from-skill-params
                       parameters skill-params)
        _ (swap! !workspace assoc :budget-limits budget-limits)
        query-intent (workspace/infer-query-intent query conversation-history)
        _ (workspace/record-query-intent! !workspace query-intent)

        ;; Enrichment lever: resolve configured enrichment-types → collection
        ;; targets from the already-resolved chunks-collection (shares
        ;; prefix+hash), and thread them to the retrieval sub-skill via
        ;; skill-params. OFF unless skills.retrieval.enrichment-types is set for
        ;; this dataset (default empty) — only enable where the enrichment
        ;; collections exist, else retrieval would search a missing collection.
        enrichment-types (get-in skill-params [:builtin/retrieval :enrichment-types])
        skill-params (cond-> skill-params
                       (and (seq enrichment-types) chunks-collection)
                       (assoc-in [:builtin/retrieval :enrichment-search-targets]
                                 (enrich-naming/enrichment-collection-names-from-base
                                  chunks-collection enrichment-types)))

        ;; Build ambient context for sub-skill execution
        ambient-ctx {:docs-collection docs-collection
                     :chunks-collection chunks-collection
                     :phrases-collection phrases-collection
                     :query query
                     :conversation-history conversation-history
                     :agent-id (:agent-id skill-params)
                     :dataset-ref (:dataset-ref skill-params)
                     :allowed-dataset-scopes (or (:allowed-dataset-scopes skill-params)
                                                 (some-> (:dataset-ref skill-params) vector))
                     :opts {:tenant (:tenant skill-params)
                            :dataset-config-key (:dataset-config-key skill-params)
                            :agent-id (:agent-id skill-params)
                            :dataset-ref (:dataset-ref skill-params)
                            :skill-params skill-params
                            :progress-fn progress-fn
                            :read-signals-llm-fn (partial read-signals/default-llm-fn (:tenant skill-params))
                            :read-signals-model model
                            :read-signals-temperature 0.0}}
        ;; Build initial messages with prior conversation context
        initial-messages (loop/build-initial-messages system-prompt query conversation-history)

        ;; Run the agentic loop. Phase 2.5 dispatch — :imperative runs the
        ;; legacy loop; :bundled runs the 4-bundle graph (2.5.C); :faithful
        ;; runs the 10-step graph (2.5.D, pending). For graph variants we
        ;; reconstruct the legacy result shape so the post-loop pipeline
        ;; (citation backfill, trace file, output assembly) works unchanged.
        run-graph-variant
        (fn [skill-graph-id]
          (let [skill-graph (templates/get-skill-graph skill-graph-id)
                _ (when-not skill-graph
                    (throw (ex-info (str "Skill graph " skill-graph-id " not registered")
                                    {:skill-graph-id skill-graph-id})))
                graph-inputs {:query query
                              :conversation-history (or conversation-history [])
                              :docs-collection docs-collection
                              :chunks-collection chunks-collection
                              :phrases-collection phrases-collection
                              :system-prompt system-prompt
                              :budget-limits budget-limits
                              :ambient-ctx-opts ambient-ctx
                              :model model
                              :temperature temperature
                              :max-iterations (or max-iterations 10)}
                graph-result (graph-runner/run-graph
                               (:graph skill-graph)
                               graph-inputs
                               {:tenant (:tenant skill-params)
                                :dataset-config-key (:dataset-config-key skill-params)
                                :skill-params skill-params
                                :progress-fn progress-fn})
                outputs (:outputs graph-result)
                workspace-final (:workspace-final outputs)]
            ;; Reflect the graph's final workspace back into !workspace so
            ;; the existing post-loop pipeline keeps reading the same atom.
            (when workspace-final (reset! !workspace workspace-final))
            (cond-> {:response (:response outputs)
                     :trace (:trace outputs)
                     :terminal-state (:terminal-state outputs)}
              (:clarification-request outputs)
              (assoc :clarification-request (:clarification-request outputs))
              (:exhausted? outputs)
              (assoc :exhausted (:exhausted? outputs))
              (= :error (:terminal-state outputs))
              (assoc :error (:response outputs)))))
        result (case graph-variant
                 :imperative
                 (loop/agentic-loop initial-messages !workspace ambient-ctx
                                    {:model model
                                     :temperature temperature
                                     :max-iterations max-iterations
                                     :progress-fn progress-fn})

                 :bundled
                 (run-graph-variant :builtin/agent-rag-graph-bundled)

                 :faithful
                 (run-graph-variant :builtin/agent-rag-graph-faithful)

                 (throw (ex-info (str "Unknown :graph-variant " (pr-str graph-variant))
                                 {:requested-variant graph-variant
                                  :supported [:imperative :bundled :faithful]})))
        clarification-request (:clarification-request result)
        workspace-chunks (workspace/get-workspace-chunks !workspace)
        trace (:trace result)
        queries (:queries @!workspace)
        status (cond (:error result) :error
                     clarification-request :clarify
                     (:exhausted result) :exhausted
                     :else :success)
        workspace-budget-state (workspace/budget-state @!workspace)
        raw-response (or (:response result) (:error result) "")
        citation-backfill (cond
                            clarification-request
                            {:response raw-response
                             :backfilled? false
                             :strategy :clarification-request}

                            ;; On :error, the raw-response is a fallback
                            ;; error string (e.g. final-LLM exception with
                            ;; no stored response). Re-synthesizing from
                            ;; the same workspace chunks the agent already
                            ;; failed to answer from costs ~2s and rarely
                            ;; produces useful output. Return raw.
                            (:error result)
                            {:response raw-response
                             :backfilled? false
                             :strategy :error-skip}

                            :else
                            (maybe-backfill-citations-via-synthesis!
                             raw-response
                             query
                             !workspace
                             ambient-ctx))
        response-after-backfill (:response citation-backfill)
        citation-carry-through (if clarification-request
                                 {:response response-after-backfill
                                  :carried-through? false
                                  :strategy :clarification-request}
                                 (workspace/ensure-citation-carry-through response-after-backfill !workspace))
        response (:response citation-carry-through)
        execution-duration-ms (- (System/currentTimeMillis) execution-start)
        agent-effective-parameters
        {:model (or model "default")
         :temperature (or temperature 0.3)
         :max-iterations (or max-iterations 10)
         :budget-limits budget-limits
         :system-prompt-custom? (boolean system-prompt)
         ;; Surface the agent's identity in the trace so playground
         ;; smoke runs can be unambiguously attributed and we can spot
         ;; when ambient-ctx loses the agent-id (the loop's
         ;; enrichment-mode-agent? predicate keys on this field).
         :agent-id (:agent-id skill-params)
         :dataset-ref (:dataset-ref skill-params)
         :enrichment-mode-active?
         (loop/enrichment-mode-agent? ambient-ctx)
         :configured-sub-skill-params
         {:builtin/retrieval (get skill-params :builtin/retrieval)
          :builtin/query-planner (get skill-params :builtin/query-planner)
          :builtin/rerank (get skill-params :builtin/rerank)
          :builtin/synthesis (get skill-params :builtin/synthesis)}}
        trace-file (write-trace-file!
                     {:query query
                      :model model
                      :trace trace
                      :queries queries
                      :search-history (:search-history @!workspace)
                      :search-errors (:search-errors @!workspace)
                      :backend-issues (:backend-issues @!workspace)
                      :read-history (:read-history @!workspace)
                      :read-content-length (:read-content-length @!workspace)
                      :evidence-plan (:evidence-plan @!workspace)
                      :claim-coverage (:claim-coverage @!workspace)
                      :read-evaluations (:read-evaluations @!workspace)
                      :open-evidence-gaps (:open-evidence-gaps @!workspace)
                      :evidence-contradictions (:evidence-contradictions @!workspace)
                      :last-read-signal (:last-read-signal @!workspace)
                      :shadow-sufficiency-decisions (:shadow-sufficiency-decisions @!workspace)
                      :budget-state workspace-budget-state
                      :duration-ms execution-duration-ms
                      :sufficiency-decisions (:sufficiency-decisions @!workspace)
                      :response-validations (:response-validations @!workspace)
                      :query-intent (:last-query-intent @!workspace)
                      :chunks-count (count workspace-chunks)
                      :status status
                      :response response
                      :skill-id ":builtin/agent"
                      :skill-graph-id (some-> (:skill-graph-id skill-params) name)
                      :citations (:citations @!workspace)
                      :citation-index (:citation-index @!workspace)
                      :citation-validation (:citation-validation @!workspace)
                      :synthesis-prompts (:last-synthesis-prompts @!workspace)
                      :initial-messages initial-messages
                      :workspace-chunks workspace-chunks
                      :search-attributions (:search-attributions @!workspace)
                      :stage-timings (:stage-timings @!workspace)
                      :agent-effective-parameters agent-effective-parameters})]
    (when trace-file
      (println "Agent trace written to:" trace-file))
    (if (:error result)
      (skills/error-result
        :agent-execution-failed
        (:error result)
        {:trace trace
         :trace-file trace-file
         :workspace-state {:chunk-count (count workspace-chunks)
                           :queries queries}})
      (skills/success-result
        {:response response
         :clarification-request clarification-request
         :trace trace
         ;; Published shape, not the internal one — see
         ;; `workspace/chunks-for-output`. Without the lift, `:title` and
         ;; `:url` are nil on every chunk because the document join lands
         ;; under a collection-named key (#460).
         :chunks (mapv workspace/chunk-for-output workspace-chunks)
         :queries queries
         :query-intent (:last-query-intent @!workspace)
         :budget-state workspace-budget-state
         :search-history (:search-history @!workspace)
         :search-errors (:search-errors @!workspace)
         :backend-issues (:backend-issues @!workspace)
         :read-history (:read-history @!workspace)
         :sufficiency-decisions (:sufficiency-decisions @!workspace)
         :response-validations (:response-validations @!workspace)
         :search-attributions (:search-attributions @!workspace)
         :insufficient-context (boolean (:last-generate-insufficient-context @!workspace))
         :last-insufficiency (:last-insufficiency @!workspace)
         :last-response-validation-insufficiency (:last-response-validation-insufficiency @!workspace)
         :citations (:citations @!workspace)
         :citation-index (:citation-index @!workspace)
         :evidence-plan (:evidence-plan @!workspace)
         :claim-coverage (:claim-coverage @!workspace)
         :read-evaluations (:read-evaluations @!workspace)
         :open-evidence-gaps (:open-evidence-gaps @!workspace)
         :evidence-contradictions (:evidence-contradictions @!workspace)
         :last-read-signal (:last-read-signal @!workspace)
         :shadow-sufficiency-decisions (:shadow-sufficiency-decisions @!workspace)}
        {:iterations (count trace)
         :chunks-retrieved (count workspace-chunks)
         :queries-used queries
         :query-intent (:last-query-intent @!workspace)
         :budget-state workspace-budget-state
         :search-passes (count (:search-history @!workspace))
         :search-error-count (count (:search-errors @!workspace))
         :backend-issue-count (count (:backend-issues @!workspace))
         :read-operations (count (:read-history @!workspace))
         :read-content-length (:read-content-length @!workspace)
         :duration-ms execution-duration-ms
         :stage-timings (:stage-timings @!workspace)
         :agent-effective-parameters agent-effective-parameters
         :last-insufficiency (:last-insufficiency @!workspace)
         :exhausted (:exhausted result false)
         :citation-backfill citation-backfill
         :citation-carry-through citation-carry-through
         :trace-file trace-file}))))

(def agent-skill
  {:metadata agent-metadata
   :execute execute-agent})

(defn register!
  "Register the agent skill."
  []
  (skills/register-skill! agent-skill))
