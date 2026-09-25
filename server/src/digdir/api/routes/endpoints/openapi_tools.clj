(ns digdir.api.routes.endpoints.openapi-tools
  "OpenAPI tool-server surface — exposes each (agent × mode) pair as an
   OpenAPI operation so Open WebUI (and anything else that consumes an
   OpenAPI \"tool server\") can tool-call a digdir agent directly.

   WHY THIS EXISTS, given that /api/mcp already advertises these tools.

   Open WebUI reaches MCP two ways and BOTH ARE BROKEN AGAINST US, for the
   same reason: MCPO and Open WebUI's native client are built on the v1-era
   Python MCP SDK, whose `ClientSession.initialize()` is a mandatory opening.
   This server implements MCP `2026-07-28`, which has no handshake, so it
   answers 400/-32022 and their client crashes. That is client lag, not a
   defect here — the SDK's own v2 reference client negotiates `2026-07-28`
   and round-trips `tools/call` against this server fine (measured).

   The bridge that was supposed to close the gap, MCPO, cannot: a dependency
   bump does not fix it (two renames, a transport signature change, and then
   it still calls `initialize`), and MCPO has had no commit since 2026-02-27.

   So rather than depend on an unmaintained proxy to translate MCP -> OpenAPI,
   we serve the OpenAPI ourselves. It removes a container, a dependency, and
   an entire class of protocol-era problem — see server/e2e/README.md.

   WHAT THIS IS NOT. It is not a second source of truth. Every tool here comes
   from `digdir.mcp.tools/list-tools` and every call goes through
   `digdir.mcp.tools/invoke-tool`, the same two functions /api/mcp uses. This
   namespace is a wire-format adapter and nothing else, exactly like
   `digdir.api.routes.endpoints.openai-compat`. A tool cannot appear on one
   surface and not the other.

   Endpoints:
     GET  /api/tools/openapi.json    — OpenAPI 3.1 doc, filtered per API key
     POST /api/tools/call/:tool-name — invoke one tool"
  (:require [clojure.string :as str]
            [digdir.mcp.tools :as mcp-tools]
            [taoensso.telemere :as t]
            [cheshire.core :as json]
            [ring.util.response :as res]))

(defn- json-response
  ([body] (json-response body 200))
  ([body status]
   (-> (res/response (json/generate-string body))
       (res/status status)
       (res/content-type "application/json"))))

(defn read-arguments
  "Parse the tool-call body into the string-keyed argument map `invoke-tool`
   expects — the same shape MCP hands it.

   ⚠️ NOT `digdir.api.util/request-body-params`, and not a Malli `:body`
   schema, for two independent reasons:

   1. The shared reader runs `normalize-request-keys`, which kebab-cases every
      key: `conversation_id` becomes `:conversation-id`. `invoke-tool` looks
      for `\"conversation_id\"` / `:conversation_id`, so multi-turn would
      silently stop working — the argument would be dropped with no error, on
      the exact surface #116 added it to.
   2. Declaring a `:body` schema would put these through reitit coercion,
      which strips undeclared fields (#174). Tool arguments are per-tool and
      open-ended by definition, so there is no closed schema to declare."
  [request]
  (let [body (:body request)
        body-str (cond
                   (nil? body) ""
                   (string? body) body
                   :else (slurp body))]
    (if (str/blank? body-str)
      {}
      (json/parse-string body-str))))

(def ^:private tool-path-prefix "/api/tools/call/")

(defn tool-path
  "The OpenAPI path a tool is advertised at.

   Absolute from the server root on purpose. Open WebUI executes a tool as
   `connection-url + route-path` (`execute_tool_server`), so a relative path
   here would produce a URL missing the `/api/tools` segment and every call
   would 404.

   The `/call/` segment is not decoration: without it the tool route is
   `/api/tools/:tool-name`, which CONFLICTS with `/api/tools/openapi.json` and
   reitit refuses to build the router at all — a boot failure, not a routing
   quirk. Method does not disambiguate; reitit compares paths."
  [tool-name]
  (str tool-path-prefix tool-name))

(defn- request-base-url
  "Best-effort absolute base URL for the `servers` block.

   Open WebUI ignores `servers` entirely — it joins the configured connection
   URL with the path itself — so this exists for Swagger UI and for humans
   reading the spec. Derived from the request rather than configured, because
   a configured value silently goes stale behind a proxy."
  [{:keys [scheme headers server-name server-port]}]
  (let [host (get headers "host")]
    (if (not-empty host)
      (str (name (or scheme :http)) "://" host)
      (str (name (or scheme :http)) "://" server-name
           (when (and server-port (not (#{80 443} server-port)))
             (str ":" server-port))))))

(defn tool->operation
  "One OpenAPI operation object for an MCP tool.

   `operationId` IS the tool name: Open WebUI uses `operationId` as the
   function name it hands the model, and matches an incoming call back to a
   route by scanning for it (`convert_openapi_to_tool_payload` /
   `execute_tool_server`). Anything else here would rename the tool on one
   surface and not the other."
  [{:keys [name title description inputSchema]}]
  {:post
   {:operationId name
    :summary (or title name)
    ;; Open WebUI prefers `description` and falls back to `summary`; the
    ;; description is what the model actually reads when deciding to call.
    :description (or description title name)
    :requestBody
    {:required true
     :content
     {"application/json"
      ;; The MCP input schema, verbatim. It is already JSON Schema with
      ;; string keys, and reusing it is what keeps the two surfaces from
      ;; advertising different arguments for the same tool.
      {:schema inputSchema}}}
    :responses
    {"200" {:description "Tool result. `status` is \"error\" for a failure the caller can act on."
            :content {"application/json" {:schema {:type "object"}}}}
     "401" {:description "Missing or invalid API key."}
     "403" {:description "The API key may not reach this agent or mode."}
     "404" {:description "No such tool for this API key."}}}})

(defn openapi-spec
  "Build the OpenAPI document for the tools `principal` can reach."
  [principal]
  (let [tools (mcp-tools/list-tools principal)]
    {:openapi "3.1.0"
     :info {:title "digdir-rag"
            :version "1.0"
            :description
            (str "Agentic RAG over Norwegian public-sector documentation. "
                 "One operation per (agent, mode) pair; the set is filtered "
                 "to what this API key can reach.")}
     :servers [{:url (request-base-url principal)}]
     :paths (into {} (map (juxt #(tool-path (:name %)) tool->operation)) tools)}))

(defn openapi-spec-handler
  "GET /api/tools/openapi.json"
  [ring-req]
  (try
    (json-response (openapi-spec ring-req))
    (catch Throwable e
      (t/log! :error [:openapi-tools/spec-failed {:error (.getMessage e)}])
      (json-response {:error {:code "spec_failed" :message (.getMessage e)}} 500))))

(def ^:private dispatch-error-status
  "HTTP status for errors the CALLER got wrong — a bad tool name, or a grant
   the API key does not hold. A model cannot fix these by retrying with better
   arguments, so they belong in the status line where a client's error handling
   can see them.

   Everything NOT listed here is deliberately returned as HTTP 200 with
   `status: \"error\"` — see `tool-call-handler`."
  {"invalid_tool_name" 404
   "agent_not_found" 404
   "mode_not_allowed" 404
   "invalid_overrides" 400
   "agent_disabled" 403
   "agent_not_authorized" 403
   "mode_not_authorized" 403})

(defn- text-content
  "Flatten the MCP content blocks to the plain answer text."
  [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn- sources
  "The cited documents, from the result's `resource_link` blocks."
  [content]
  (->> content
       (filter #(= "resource_link" (:type %)))
       (mapv #(select-keys % [:uri :name]))))

(defn tool-result->body
  "Reshape an MCP tool result into the JSON an OpenAPI tool client gets.

   `answer` leads because this payload is read by a model, not by code, and
   the first field is the one that survives truncation."
  [result]
  (let [content (:content result)
        structured (:structuredContent result)
        meta (:_meta result)]
    (cond-> {:answer (text-content content)
             :status (if (:isError result) "error" "ok")}
      (:conversation_id meta) (assoc :conversation_id (:conversation_id meta))
      (seq (sources content)) (assoc :sources (sources content))
      (seq (:chunks structured)) (assoc :chunks (:chunks structured))
      (seq (:queries structured)) (assoc :queries (:queries structured))
      (:clarification structured) (assoc :clarification (:clarification structured)))))

(defn tool-call-handler
  "POST /api/tools/:tool-name — invoke one tool.

   ⚠️ A TOOL-LEVEL FAILURE IS AN HTTP 200 HERE, ON PURPOSE.

   Open WebUI turns any status >= 400 into an exception string
   (`execute_tool_server`), which reaches the model as opaque text rather than
   as something it can act on. `missing_query` and `no_dataset_scope` are
   exactly the errors a model CAN recover from — the second carries the most
   actionable message in this codebase — so they come back as a normal result
   with `status: \"error\"`, mirroring how /api/mcp delivers them as
   `isError: true` rather than as a JSON-RPC error.

   Errors the caller cannot retry their way out of — unknown tool, missing
   grant — still get a 4xx, because those are about the request, not the
   query. `dispatch-error-status` is the split."
  [ring-req]
  (let [tool-name (get-in ring-req [:path-params :tool-name])]
    (try
      (let [{:keys [error result]} (mcp-tools/invoke-tool ring-req tool-name
                                                          (read-arguments ring-req)
                                                          nil)]
        (cond
          (and error (dispatch-error-status (:code error)))
          (json-response {:error error :status "error"}
                         (dispatch-error-status (:code error)))

          error
          (json-response {:answer (:message error)
                          :status "error"
                          :error error})

          :else
          (json-response (tool-result->body result))))
      (catch Throwable e
        (t/log! :error [:openapi-tools/call-failed {:tool tool-name
                                                    :error (.getMessage e)}])
        (json-response {:error {:code "internal_error" :message (.getMessage e)}
                        :status "error"}
                       500)))))
