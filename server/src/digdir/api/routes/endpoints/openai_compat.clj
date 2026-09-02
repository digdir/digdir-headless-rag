(ns digdir.api.routes.endpoints.openai-compat
  "OpenAI-compatible /v1 surface — exposes each agent as a `model` so any
   OpenAI client (Open WebUI, cursor, continue, raw curl) can chat with a
   digdir agent directly. One LLM hop per turn instead of two (outer
   orchestrator + inner agent).

   Wraps the same `digdir.skills.invoke/invoke-rag` entrypoint the MCP
   transport uses. Authorization and skill-graph resolution piggybacks
   on `digdir.mcp.tools` helpers; this ns is a thin adapter that
   re-shapes inputs and outputs to OpenAI's wire format.

   Endpoints:
     GET  /v1/models             — list of agent × allowed-skill-graph pairs
     POST /v1/chat/completions   — chat-completion. Streaming via SSE when
                                   stream:true; per-token deltas from the
                                   agent's inner LLM are a follow-up — the
                                   MVP sends the assistant content as one
                                   delta chunk after the role intro."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [digdir.api.body :as request-body]
            [digdir.api.util :as api-util]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.invoke :as invoke]
            [nano-id.core :refer [nano-id]]
            [ring.core.protocols :as ring-proto]
            [ring.util.response :as res]
            [taoensso.telemere :as t])
  (:import [java.io OutputStream OutputStreamWriter]
           [java.nio.charset StandardCharsets]))

(defn- json-response
  ([body] (json-response body 200))
  ([body status]
   (-> (res/response (json/generate-string body))
       (res/status status)
       (res/content-type "application/json"))))

(defn- error-type-for-status
  "OpenAI splits error types by who is at fault. Deriving it from the HTTP
   status keeps the two from disagreeing: a 5xx labelled
   `invalid_request_error` tells a client to fix a request that was fine."
  [status]
  (if (>= (long status) 500) "server_error" "invalid_request_error"))

(defn- openai-error
  "OpenAI's error response shape — clients (incl. Open WebUI) look at
   the `error.type` + `error.code` fields, not the raw HTTP status.

   `param` is always present, null when the error is not tied to a specific
   request parameter: it is part of the documented error object, and SDKs
   read it unconditionally."
  ([code message] (openai-error code message 400))
  ([code message status]
   {:error {:message message
            :type (error-type-for-status status)
            :param nil
            :code code}}))

(defn- error-response
  "Error body and HTTP status together, so the two cannot drift apart."
  [code message status]
  (json-response (openai-error code message status) status))

(defn- client-error-kind
  "The `:digdir/client-error` tag on an ex-info, or nil.

   ⚠️ TAG-DRIVEN, NOT MESSAGE-DRIVEN, DELIBERATELY. Matching on the exception's
   text would re-break the moment someone rewords it, and the reword would look
   entirely safe — a message is prose and nothing declares that anything parses
   it. The tag is set at the throw site by the only code that can tell the cases
   apart."
  [t]
  (when (instance? clojure.lang.ExceptionInfo t)
    (:digdir/client-error (ex-data t))))

(defn- client-error-next-step
  "What the caller should actually do, per classified condition.

   Separate strings rather than one message, because the two conditions have
   genuinely different next steps — create a dataset, versus check the name you
   sent. One shared message would be a correct status that still cannot say
   which mistake was made."
  [kind]
  (case kind
    :no-datasets-configured
    "No datasets are configured yet. Create one, or run the demo corpus import, before querying."

    :dataset-ref-unknown
    "Check the dataset name in your request against GET /api/datasets."

    "See GET /api/datasets."))

(defn list-models-handler
  "GET /v1/models — returns one model per (agent × allowed-skill-graph)
   pair visible to the authenticated API key. Mirrors MCP's tools/list
   shape so a client sees the same surface across both protocols."
  [ring-req]
  (try
    (let [tools (mcp-tools/list-tools ring-req)
          now-seconds (quot (System/currentTimeMillis) 1000)]
      (json-response
        {:object "list"
         :data (mapv (fn [tool]
                       (let [meta (:_meta tool)]
                         (cond-> {:id (:name tool)
                                  :object "model"
                                  :created now-seconds
                                  :owned_by "digdir-rag"}
                           (:description tool) (assoc :description (:description tool))
                           (:agent-id meta) (assoc :_agent_id (:agent-id meta))
                           (:mode meta) (assoc :_mode (str (:mode meta)))
                           (:default meta) (assoc :_default true))))
                     tools)}))
    (catch Throwable t
      (t/log! :error [:openai-compat/list-models-failed {:error (.getMessage t)}])
      (error-response "list_models_failed" (.getMessage t) 500))))

(defn- err-code->status
  "Map a code coming out of mcp-tools' resolution chain to an HTTP status."
  [code]
  (case code
    "agent_not_found" 404
    "mode_not_allowed" 404
    "model_not_found" 404
    "agent_disabled" 403
    "agent_not_authorized" 403
    "mode_not_authorized" 403
    ;; An authorization refusal, so it joins the 403s rather than falling
    ;; through to the 500 default — the key asked for a dataset it was not
    ;; granted (#464). `no_dataset_scope` stays 400: that one means the caller
    ;; supplied no scope at all, which is a malformed request, not a refusal.
    "dataset_not_authorized" 403
    "no_dataset_scope" 400
    "missing_messages" 400
    "missing_model" 400
    "invalid_messages" 400
    500))

(defn- resolve-invocation
  "Run the same agent + skill-graph + dataset-scope resolution chain
   `mcp.tools/invoke-tool` does, without the conversation-persistence
   layer (which we skip — OpenAI clients carry their own history).

   `body` is the parsed request body, used the way MCP uses a tool call's
   `arguments`: a caller may name an explicit `tenant` / `dataset_config_key`
   to pick among the scopes its API key allows. Passing `{}` here (as this
   adapter used to) silently discarded that choice.

   Returns `{:agent-id, :skill-graph-id, :scope, :dataset-config,
   :skill-params}` on success or `{:error {:code :message}}` on failure."
  ([ring-req model] (resolve-invocation ring-req model {}))
  ([ring-req model body]
  (let [parsed (mcp-tools/parse-tool-name model)]
    (if-not parsed
      {:error {:code "model_not_found"
               :message (str "Model not found: " model
                             ". Use the id from GET /v1/models.")}}
      (let [[agent-id skill-graph-short] parsed
            {agent-err :error :keys [agent]}
            (mcp-tools/resolve-agent agent-id ring-req)]
        (if agent-err
          {:error agent-err}
          (let [{sg-err :error :keys [skill-graph-id]}
                (mcp-tools/resolve-skill-graph-id agent skill-graph-short ring-req)]
            (if sg-err
              {:error sg-err}
              (let [{scope-err :error :keys [scope]}
                    (mcp-tools/pick-dataset-scope agent ring-req (or body {}))]
                (if scope-err
                  {:error scope-err}
                  (let [dataset-config (mcp-tools/load-dataset-config scope)]
                    (if (nil? dataset-config)
                      {:error {:code "dataset_not_found"
                               :message (str "Dataset not found: " scope)}}
                      {:agent-id agent-id
                       :skill-graph-id skill-graph-id
                       :scope scope
                       :dataset-config dataset-config
                       ;; THREE-arity: the resolved agent's :skill-params are
                       ;; the middle layer (params > agent > dataset config >
                       ;; defaults). The two-arity form drops that layer, so
                       ;; every per-agent tuning decision evaporated here while
                       ;; the MCP path (mcp/tools.clj) honoured it — the same
                       ;; agent answered differently depending on the surface.
                       ;; The params layer stays `{}`: /v1 has no per-call
                       ;; override channel, which matches an MCP call made
                       ;; without overrides.
                       :skill-params (api-util/build-rag-skill-params
                                       dataset-config
                                       {}
                                       (or (:skill-params agent) {}))}))))))))))))

(defn- run-agent
  "Call invoke/invoke-rag with the resolved context + OpenAI conversation
   history. Returns the raw invoke-rag map (status, response, etc.) — the
   handler decides how to render it (blocking JSON vs SSE stream)."
  [{:keys [agent-id skill-graph-id scope dataset-config skill-params]}
   user-query history]
  (invoke/invoke-rag
    {:user-query user-query
     :claim user-query
     :conversation-history history
     :collections {:docs-collection (:docs-collection dataset-config)
                   :chunks-collection (:chunks-collection dataset-config)
                   :phrases-collection (:phrases-collection dataset-config)}
     :skill-graph-id skill-graph-id
     :skill-params skill-params
     :execution-scope {:tenant (:tenant scope)
                       :dataset-config-key (:dataset-config-key scope)
                       :agent-id agent-id}}))

(defn- messages->history+query
  "Split an OpenAI `messages` array into (conversation-history, user-query).
   The last user message becomes the query; everything before becomes the
   history in the agent loop's `{:role :text}` shape.

   System messages are folded into the history as `:role :system` entries —
   the agent loop typically constructs its own system prompt from skill
   config, but preserving caller-supplied system context is the
   least-surprising behavior."
  [messages]
  (let [last-user-idx (->> (map-indexed vector messages)
                           (filter (fn [[_ m]] (= "user" (:role m))))
                           last
                           first)]
    (cond
      (nil? last-user-idx)
      {:error "No user message found in `messages`"}

      :else
      (let [history-msgs (vec (concat (subvec (vec messages) 0 last-user-idx)
                                      (subvec (vec messages) (inc last-user-idx))))
            user-query (:content (nth messages last-user-idx))]
        {:user-query (str user-query)
         :history (mapv (fn [m]
                          {:role (keyword (:role m))
                           :text (str (:content m))})
                        history-msgs)}))))

(defn- truncate
  "Trim a string to at most `n` chars (used for citation snippets)."
  [^String s ^long n]
  (cond
    (nil? s) nil
    (<= (count s) n) s
    :else (str (subs s 0 n) "…")))

(defn- chunk-doc-info
  "Workspace chunks carry their title + url inside a nested sub-map keyed
   by the docs-collection name (`:website_documents_<hash>`). We don't
   know that key statically, so walk the chunk's values and pick the
   first map that looks like a doc-ref (has :title or :total_chunks).
   Mirrors `digdir.skills.builtin.agent.workspace/chunk-doc-info` —
   private there so we keep a local copy rather than promote it for a
   single sibling caller."
  [chunk]
  (when (map? chunk)
    (some (fn [v]
            (when (and (map? v)
                       (or (contains? v :title)
                           (contains? v :total_chunks)))
              v))
          (vals chunk))))

(defn- build-citations
  "Walk the agent's :citations / :chunks output and produce two
   client-friendly views of the same data:

     - `:perplexity` — vec of URL/source strings, one per citation,
       indexed positionally. OWUI's Perplexity-compatible path lifts
       this top-level field and renders inline `[N]` markers as
       clickable footnotes pointing at the matching index.
     - `:owui-sources` — richer vec of
       `{:source {:name :url} :document [snippet]}` maps. OWUI's
       native RAG-source panel below the message uses this shape.

   Both are derived from the same lookup of citation chunk-ids in the
   :chunks list. Chunks not in :chunks (orphan references) are
   skipped. Returns `{:perplexity [] :owui-sources []}` when the
   agent produced no citations."
  [invoke-result]
  (let [outputs (get-in invoke-result [:diagnostics :outputs])
        workspace-final (:workspace-final outputs)
        ;; Citations are set on the workspace by the generate_response
        ;; tool (agent/tools.clj:1092). For graphs that surface them as
        ;; a top-level output instead (rare), fall back to that.
        citations (or (:citations workspace-final)
                      (:citations outputs)
                      [])
        ;; Workspace :chunks is a map keyed by chunk_id — direct lookup
        ;; matches the citation-index values.
        chunks-by-id (or (:chunks workspace-final) {})
        ;; Citations are already 1-indexed and ordered by build-citation-index.
        resolved (->> citations
                      (keep (fn [{:keys [chunk-id]}]
                              (when-let [chunk (get chunks-by-id chunk-id)]
                                (let [doc-info (chunk-doc-info chunk)
                                      title (or (:title doc-info)
                                                (some-> chunk :headers vals first)
                                                "Untitled source")
                                      url (or (:url doc-info)
                                              (str "chunk:" chunk-id))
                                      snippet (truncate
                                                (or (:content_markdown chunk)
                                                    (:content chunk))
                                                240)]
                                  {:title (str title)
                                   :url (str url)
                                   :snippet snippet}))))
                      vec)]
    {:perplexity (mapv :url resolved)
     :owui-sources (mapv (fn [{:keys [title url snippet]}]
                           {:source (cond-> {:name title}
                                      url (assoc :url url))
                            :document (if snippet [snippet] [])})
                         resolved)}))

(defn- stage-timings
  "The agent surfaces per-LLM-call stage timings in a few places depending on
   skill-graph topology. Mirrors `digdir.sweep.runner/find-stage-timings`,
   which lives in src-dev and so is not on a production classpath."
  [result]
  (or (not-empty (get-in result [:diagnostics :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :trace :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :workspace-final :stage-timings]))
      (not-empty (get-in result [:trace :stage-timings]))
      (not-empty (:stage-timings result))))

(defn- token-count
  "Read a usage number under either casing — providers return snake_case,
   internal code sometimes normalises to kebab."
  [usage snake kebab]
  (or (get usage snake) (get usage kebab) 0))

(defn- agent-usage
  "Token usage summed across the agent's LLM calls, or **nil** when the agent
   reported none.

   nil means the caller omits `usage` entirely. Reporting zeros instead would
   be a fabricated value that a cost-tracking client believes and acts on —
   `0` and `unknown` are not the same claim. Only emitted when at least one
   call actually carried a token count."
  [result]
  (when-let [timings (stage-timings result)]
    (let [usages (keep :usage timings)
          reported? (fn [u] (or (contains? u :prompt_tokens) (contains? u :prompt-tokens)
                                (contains? u :completion_tokens) (contains? u :completion-tokens)))]
      (when (some reported? usages)
        (let [prompt (reduce + 0 (map #(token-count % :prompt_tokens :prompt-tokens) usages))
              completion (reduce + 0 (map #(token-count % :completion_tokens :completion-tokens) usages))]
          {:prompt_tokens prompt
           :completion_tokens completion
           :total_tokens (reduce + 0 (map (fn [u]
                                            (or (get u :total_tokens)
                                                (get u :total-tokens)
                                                (+ (token-count u :prompt_tokens :prompt-tokens)
                                                   (token-count u :completion_tokens :completion-tokens))))
                                          usages))})))))

(defn- chat-completion-response
  "Wrap an agent's response text in OpenAI's chat.completion shape.
   Appends Perplexity-style `:citations` (URL strings) and
   OWUI-native `:sources` (rich metadata) when the agent produced
   citation references."
  [model response-text status citation-bundle usage]
  (let [{:keys [perplexity owui-sources]} citation-bundle]
    (cond-> {:id (str "chatcmpl-" (nano-id))
             :object "chat.completion"
             :created (quot (System/currentTimeMillis) 1000)
             :model model
             :choices [{:index 0
                        :message {:role "assistant"
                                  :content (or response-text "")}
                        ;; Schema-required, and null rather than absent:
                        ;; SDKs read it unconditionally. We do not produce
                        ;; logprobs, so null is the honest value.
                        :logprobs nil
                        :finish_reason (case status
                                         :error "stop"
                                         :needs-clarification "stop"
                                         "stop")}]}
      ;; Present only when the agent actually reported tokens — see agent-usage.
      usage (assoc :usage usage)
      (seq perplexity) (assoc :citations perplexity)
      (seq owui-sources) (assoc :sources owui-sources))))

(defn- read-json-body
  "Read and parse the JSON body off a Ring request. The /v1 routes
   don't declare a Malli body schema (OpenAI's payload is open-ended
   and clients add ad-hoc fields), so reitit doesn't auto-parse —
   we do it ourselves like the MCP transport does."
  [request]
  (let [body-str (request-body/read-body-string request)]
    (if (str/blank? body-str)
      {}
      (json/parse-string body-str true))))

(def ^:private error-finish-reason
  "`finish_reason` for a stream whose agent run failed.

   Not in OpenAI's enum (`stop` / `length` / `tool_calls` / `content_filter` /
   `function_call`), which has no value for \"the upstream call failed\". The
   blocking path answers 5xx and needs no such value; the streaming path
   cannot, because the 200 and its headers are already on the wire by the time
   the agent runs — the alternative would be buffering the whole run before
   responding, which defeats streaming.

   So the choice is between a value outside the enum and `\"stop\"`, which
   asserts the model finished normally and is what an eval harness, cost
   tracker or retry policy reads. Verified against the stock `openai` Python
   SDK 1.109.1 pointed at a local server: an out-of-enum `finish_reason`
   parses without error and arrives verbatim, so a client can branch on it;
   whereas `\"stop\"` plus a vendor extension leaves every stock client
   believing the call succeeded.

   Recorded as a considered divergence (F19) in
   docs/standards-alignment-findings.md."
  "error")

(defn- chunk-delta
  "OpenAI streaming chunk shape for an intermediate delta."
  [id created model delta finish-reason]
  {:id id
   :object "chat.completion.chunk"
   :created created
   :model model
   ;; `logprobs` and `finish_reason` are schema-required on every chunk and
   ;; carry null until the final frame sets a reason — omitting them makes a
   ;; strict SDK reject the stream.
   :choices [{:index 0
              :delta delta
              :logprobs nil
              :finish_reason finish-reason}]})

(defn- write-sse-line!
  "Write a single `data: {json}\\n\\n` SSE frame, then flush so the client
   sees the chunk immediately. The trailing blank line is the SSE event
   delimiter."
  [^OutputStreamWriter w event-data]
  (.write w "data: ")
  (.write w (json/generate-string event-data))
  (.write w "\n\n")
  (.flush w))

(defn- stream-chat-completion
  "Build a ring StreamableResponseBody that writes the agent's response
   as OpenAI-compatible SSE chunks. Runs the agent synchronously, then
   sends three frames: role intro, full content as one delta, and the
   stop terminator. Per-token streaming from the agent's inner LLM is a
   follow-up — for now this is the simplest shape OWUI renders cleanly."
  [ctx user-query history model]
  (let [id (str "chatcmpl-" (nano-id))
        created (quot (System/currentTimeMillis) 1000)]
    (reify ring-proto/StreamableResponseBody
      (write-body-to-stream [_ _response output-stream]
        (let [w (OutputStreamWriter. ^OutputStream output-stream
                                     StandardCharsets/UTF_8)]
          (try
            ;; Frame 1: open the assistant turn.
            (write-sse-line! w (chunk-delta id created model {:role "assistant"} nil))
            ;; Run the agent. invoke-rag returns :status :error on failure;
            ;; surface that as a content delta so OWUI renders something
            ;; instead of hanging.
            (let [result (run-agent ctx user-query history)
                  {:keys [perplexity owui-sources]} (build-citations result)
                  failed? (= :error (:status result))
                  body-text
                  (cond
                    failed?
                    (str "Error: " (or (some-> result :error :error-message)
                                       (some-> result :error pr-str)
                                       "agent invocation failed"))
                    :else
                    (or (:response result) ""))]
              ;; Frame 2 (optional): emit citations + sources BEFORE the
              ;; content delta so OWUI has the metadata ready to render
              ;; inline `[N]` markers as footnotes. We use a hybrid frame
              ;; that carries the standard chat-completion-chunk shape
              ;; PLUS the top-level `citations` (Perplexity) and `sources`
              ;; (OWUI native) fields — different OWUI builds look at
              ;; different positions, so populate both.
              (when (or (seq perplexity) (seq owui-sources))
                (write-sse-line! w
                  (cond-> (chunk-delta id created model {} nil)
                    (seq perplexity) (assoc :citations perplexity)
                    (seq owui-sources) (assoc :sources owui-sources))))
              ;; Frame 3: full response as one content delta.
              (when-not (str/blank? body-text)
                (write-sse-line! w (chunk-delta id created model {:content body-text} nil)))
              ;; Frame 4: terminator. A failed agent run reports
              ;; `finish_reason: "error"`, not `"stop"` — see `error-finish-reason`.
              (write-sse-line! w (chunk-delta id created model {}
                                              (if failed? error-finish-reason "stop"))))
            (.write w "data: [DONE]\n\n")
            (.flush w)
            (catch Throwable t
              (t/log! :error [:openai-compat/stream-write-failed
                              {:error (.getMessage t)}])
              (try
                (write-sse-line! w (chunk-delta id created model
                                                {:content (str "\n\n[stream error: " (.getMessage t) "]")}
                                                error-finish-reason))
                (.write w "data: [DONE]\n\n")
                (.flush w)
                (catch Exception _ nil)))
            (finally
              (try (.close w) (catch Exception _ nil)))))))))

(defn chat-completions-handler
  "POST /v1/chat/completions — streaming or blocking. Maps:
     :model    → (agent-id, skill-graph-id) via MCP's tool-name parsing
     :messages → (conversation-history, user-query)
   Invokes the agent via `invoke/invoke-rag` and re-shapes the response."
  [ring-req]
  (try
    (let [body (read-json-body ring-req)
          model (or (get body :model) (get body "model"))
          messages (or (get body :messages) (get body "messages") [])
          stream? (boolean (or (get body :stream) (get body "stream")))]
      (cond
        (str/blank? (str model))
        (error-response "missing_model" "`model` is required." 400)

        (empty? messages)
        (error-response "missing_messages" "`messages` must be non-empty." 400)

        :else
        (let [{:keys [user-query history error]} (messages->history+query messages)]
          (if error
            (error-response "invalid_messages" error 400)
            (let [{ctx-err :error :as ctx} (resolve-invocation ring-req model body)]
              (if ctx-err
                (let [status (err-code->status (:code ctx-err))]
                  (error-response (:code ctx-err) (:message ctx-err) status))
                (do
                  (t/log! :debug [:openai-compat/chat-completion
                                  {:agent-id (:agent-id ctx)
                                   :model model
                                   :history-count (count history)
                                   :stream? stream?}])
                  (if stream?
                    ;; SSE streaming path. The agent runs inside
                    ;; write-body-to-stream; the client sees role intro,
                    ;; one content delta with the full body, then the
                    ;; stop terminator. Per-token deltas are a follow-up.
                    {:status 200
                     :headers {"Content-Type" "text/event-stream"
                               "Cache-Control" "no-cache, no-transform"
                               "X-Accel-Buffering" "no"
                               "Connection" "keep-alive"}
                     :body (stream-chat-completion ctx user-query history model)}
                    ;; Blocking JSON path.
                    (let [result (run-agent ctx user-query history)]
                      (if (= :error (:status result))
                        (error-response "invoke_failed"
                                        (or (some-> result :error :error-message)
                                            (some-> result :error pr-str)
                                            "Agent invocation failed.")
                                        500)
                        (json-response (chat-completion-response model
                                                                  (:response result)
                                                                  (:status result)
                                                                  (build-citations result)
                                                                  (agent-usage result)))))))))))))
    (catch Throwable t
      (t/log! :error [:openai-compat/chat-completion-failed
                      {:error (.getMessage t)}])
      (cond
        (request-body/body-too-large? t)
        (error-response "request_too_large" (.getMessage t) 413)

        ;; #434: a first-run condition arriving here as a Throwable is not an
        ;; internal error, and reporting it as one sends a newcomer to the issue
        ;; tracker instead of to their next step. The classification is made
        ;; where it can be — at the throw — and only read here; this branch
        ;; invents nothing.
        (client-error-kind t)
        (let [kind (client-error-kind t)]
          (error-response (name kind)
                          (str (.getMessage t) ". " (client-error-next-step kind))
                          404))

        :else
        (error-response "internal_error" (.getMessage t) 500)))))
