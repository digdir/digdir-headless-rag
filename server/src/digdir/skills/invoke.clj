(ns digdir.skills.invoke
  "Canonical RAG skill-graph invocation.

   Single in-process runtime contract shared by the Playground UI and the
   MCP server. Owns:
     - input shape into `run-skill-graph`
     - the `:response → :verification → :report → \"Retrieved N chunks.\"`
       output-extraction fallback (so :report-emitting graphs render correctly)
     - error normalization

   Persistence, transport, and surface-specific diagnostics shaping stay in
   the outer wrappers."
  (:require [digdir.rag.skills.core :as skills-core]
            [digdir.skills.api :as skills-api]
            [taoensso.telemere :as t]))

(defn- extract-response
  "Apply the Playground-canonical response fallback chain. Graph variants
   that emit :report (self-improve agents) must render a non-empty body."
  [outputs]
  (or (:response outputs)
      (:verification outputs)
      (:report outputs)
      (when (seq (:chunks outputs))
        (str "Retrieved and reranked " (count (:chunks outputs)) " chunks."))
      ""))

(defn- normalize-status
  [clarification-request error?]
  (cond
    error?                 :error
    clarification-request  :needs-clarification
    :else                  :complete))

(defn invoke-rag
  "Canonical RAG skill-graph invocation. Shared by Playground UI, deprecated
   custom API endpoints, and the MCP server.

   Inputs:
     :user-query           — required
     :conversation-history — vec of {:role :text}, default []
     :claim                — optional; defaults to :user-query (fact-checker)
     :collections          — {:docs-collection :chunks-collection :phrases-collection}
     :skill-graph-id       — kw, e.g. :builtin/agent-rag-graph-bundled
     :skill-params         — built via api-util/build-rag-skill-params
     :execution-scope      — {:tenant :dataset-config-key :dataset-ref :agent-id}
     :model                — optional explicit override (nil = honor per-skill cfg)
     :temperature          — optional explicit override (default 0.1)
     :progress-fn          — optional event sink (MCP server uses this for
                              notifications)
     :runtime-config-key   — optional

   Returns:
     {:status            :complete | :needs-clarification | :error
      :response          string
      :insufficient?     boolean
      :clarification     nil | {:question :options :context-summary}
      :chunks            vec
      :queries           vec
      :search-attribution map
      :diagnostics       map   — execution-metadata, agent-trace, search-history,
                                  raw outputs the caller may want
      :raw-result        map   — passthrough for callers that need internal detail
      :error             nil | {:error-type :error-message}}

   `:status` is `:error` when the graph itself failed AND when the agent
   terminated in its own error state — an agent that could not reach its model
   did not answer, whichever layer noticed (#303)."
  [{:keys [user-query conversation-history claim collections skill-graph-id
           skill-params execution-scope model temperature progress-fn
           runtime-config-key]}]
  (when-not (string? user-query)
    (throw (ex-info "invoke-rag: :user-query is required and must be a string"
                    {:user-query user-query})))
  (when-not (keyword? skill-graph-id)
    (throw (ex-info "invoke-rag: :skill-graph-id must be a keyword"
                    {:skill-graph-id skill-graph-id})))
  (let [{:keys [tenant dataset-config-key dataset-ref agent-id]} execution-scope
        ;; The agent-rag-graph-* variants read tenant/dataset-config-key
        ;; from a graph input named :ambient-ctx-opts (see
        ;; digdir.skills.builtin.agent.graphs/execute-agent-setup at
        ;; "[:opts :tenant]"). When the wrapper `execute-agent` invokes
        ;; the sub-graph it constructs this map itself (core.clj:857);
        ;; when invoke-rag drives the graph directly (MCP path, sweep
        ;; runner, etc.) we have to do the construction here or the
        ;; downstream cfg/get :services :azure-openai call sees a nil
        ;; tenant and refuses to resolve config.
        ambient-ctx-opts {:opts (cond-> {:tenant tenant
                                         :dataset-config-key dataset-config-key
                                         :skill-params (or skill-params {})}
                                  agent-id    (assoc :agent-id agent-id)
                                  dataset-ref (assoc :dataset-ref dataset-ref)
                                  progress-fn (assoc :progress-fn progress-fn))}
        ;; Agent-scoped overrides on :builtin/agent flow through
        ;; `skill-params`, but the outer agent-rag-graph reads
        ;; `:system-prompt` as a top-level graph input — not from
        ;; `ambient-ctx-opts`. Without this hand-off, an agent's
        ;; persisted `:skill-params {:builtin/agent {:system-prompt ...}}`
        ;; never reaches `build-initial-messages`, and the loop falls
        ;; through to the hardcoded `default-system-prompt`. Lift the
        ;; value here so both wiring layers see the same prompt.
        agent-system-prompt (get-in skill-params [:builtin/agent :system-prompt])
        inputs (cond-> {;; The agent skill graph's setup step references
                        ;; `:$query` (see
                        ;; digdir.skills.builtin.agent.graphs/make-outer-graph),
                        ;; which the graph runner resolves via
                        ;; `(get graph-inputs :query)`. The public
                        ;; invoke-rag arg uses `:user-query` for the
                        ;; same value (the input schema in
                        ;; digdir.skills.templates.core
                        ;; canonicalizes to :user-query for tool
                        ;; signatures), so we mirror the value under
                        ;; both keys. Without :query the agent runs
                        ;; with a nil user query and degenerates into
                        ;; a "please provide your question" reply.
                        :query user-query
                        :user-query user-query
                        :claim (or claim user-query)
                        :conversation-history (or conversation-history [])
                        :ambient-ctx-opts ambient-ctx-opts}
                 (:docs-collection collections)    (assoc :docs-collection    (:docs-collection collections))
                 (:chunks-collection collections)  (assoc :chunks-collection  (:chunks-collection collections))
                 (:phrases-collection collections) (assoc :phrases-collection (:phrases-collection collections))
                 agent-system-prompt               (assoc :system-prompt agent-system-prompt))
        opts (cond-> {:tenant tenant
                      :dataset-config-key dataset-config-key
                      :temperature (or temperature 0.1)
                      :conversation-history (or conversation-history [])
                      :skill-params (or skill-params {})}
               model               (assoc :model model)
               agent-id            (assoc :agent-id agent-id)
               dataset-ref         (assoc :dataset-ref dataset-ref)
               runtime-config-key  (assoc :runtime-config-key runtime-config-key)
               progress-fn         (assoc :progress-fn progress-fn))]
    (try
      (let [result (skills-api/run-skill-graph skill-graph-id inputs opts)]
        (if (skills-core/result-error? result)
          (let [error (skills-core/get-result-error result)]
            (t/log! :error [:invoke-rag/failed {:skill-graph skill-graph-id
                                                :error error}])
            {:status :error
             :response ""
             :insufficient? false
             :clarification nil
             :chunks []
             :queries []
             :search-attribution {}
             :diagnostics {:skills-error error}
             :raw-result result
             :error error})
          (let [outputs (skills-core/get-result-outputs result)
                clarification-request (:clarification-request outputs)
                response (extract-response outputs)
                ;; READ THE DECLARED OUTPUT, NOT THE WORKSPACE (#460).
                ;;
                ;; This used to prefer `outputs → workspace-final → :chunks`,
                ;; because the agent-rag graphs did not declare `:chunks` at
                ;; all and reading only the top level handed every caller
                ;; `:chunks []` for a run that had actually retrieved and cited
                ;; documents — worst on `:needs-clarification`, where the
                ;; visible result is a one-line question and the evidence
                ;; gathered on the way to asking it vanished.
                ;;
                ;; That fallback fixed the empty vector and introduced a worse
                ;; failure, because `workspace-final` is the INTERNAL workspace
                ;; and its `:chunks` is a MAP keyed by chunk_id. Consumers that
                ;; `mapv select-keys` over it iterate `MapEntry` and get `{}`,
                ;; so MCP published `chunks: [{} {}]` — a present-but-empty
                ;; array, which reads to a client as "I received chunks".
                ;;
                ;; Fixed at the producer instead: the outer agent graphs now
                ;; declare `:chunks` and emit the published vector via
                ;; `workspace/chunks-for-output`. So the top level is correct
                ;; again and the workspace is no longer read here — one shape
                ;; at this seam rather than two tolerated ones.
                chunks (or (:chunks outputs) (:evidence outputs) [])
                queries (:queries outputs)
                agent-trace (:trace outputs)
                search-attribution (or (:search-attribution outputs) {})
                search-attributions (or (:search-attributions outputs) [])
                ;; #303. The agent graph already records how it ended, and
                ;; `:terminal-state :error` means it produced no answer at
                ;; all — most often because its first LLM call failed, which
                ;; is what a fresh install hits (#279). That fact was dropped
                ;; here: `normalize-status` has always taken an `error?`
                ;; argument and this call site passed a literal `false`, so
                ;; the agent's error terminal reached MCP as `isError: false`
                ;; with the failure text sitting in the answer slot. The same
                ;; exception caught one layer up (the `catch` below) already
                ;; returned `:error`, so the two paths disagreed about the
                ;; same failure depending only on where it was caught.
                agent-error? (= :error (:terminal-state outputs))
                status (normalize-status clarification-request agent-error?)
                insufficient? (boolean (or (:last-insufficiency outputs)
                                           (:last-response-validation-insufficiency outputs)))]
            {:status status
             :response response
             :insufficient? insufficient?
             :clarification (when clarification-request
                              {:question (:question clarification-request)
                               :options (:options clarification-request)
                               :context-summary (:context-summary clarification-request)})
             :chunks chunks
             :queries (or queries [])
             :search-attribution search-attribution
             :diagnostics {:outputs outputs
                           :execution-metadata (:execution-metadata result)
                           :step-results (:step-results result)
                           :agent-trace agent-trace
                           :search-history (:search-history outputs)
                           :search-attributions search-attributions
                           :read-history (:read-history outputs)
                           :search-errors (:search-errors outputs)
                           :backend-issues (or (:backend-issues outputs) [])
                           :sufficiency-decisions (:sufficiency-decisions outputs)
                           :last-insufficiency (:last-insufficiency outputs)
                           :last-response-validation-insufficiency (:last-response-validation-insufficiency outputs)
                           :report-structured (:report-structured outputs)
                           :citations (or (get-in outputs [:workspace-final :citations])
                                          (:citations outputs))
                           :citation-index (or (get-in outputs [:workspace-final :citation-index])
                                               (:citation-index outputs))
                           :query-intent (:query-intent outputs)
                           :budget-state (:budget-state outputs)
                           :report (:report outputs)}
             :raw-result result
             ;; `:response` deliberately keeps the agent's error text instead
             ;; of being blanked: MCP renders it as the tool result's content
             ;; alongside `isError: true`, which is the model-facing error
             ;; channel #117 describes, and openai-compat + playground render
             ;; `:error-message`.
             :error (when agent-error?
                      {:error-type :agent-terminal-error
                       :error-message response})})))
      (catch Exception e
        (t/log! :error [:invoke-rag/exception
                        {:skill-graph skill-graph-id
                         :error (.getMessage e)
                         :ex-data (ex-data e)}])
        {:status :error
         :response ""
         :insufficient? false
         :clarification nil
         :chunks []
         :queries []
         :search-attribution {}
         :diagnostics {:exception (.getMessage e)
                       :ex-data (ex-data e)}
         :raw-result nil
         :error {:error-type :exception
                 :error-message (.getMessage e)}}))))
