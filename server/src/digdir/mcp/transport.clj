(ns digdir.mcp.transport
  "MCP Streamable HTTP transport — JSON-RPC 2.0 over a single POST endpoint,
   protocol revision 2026-07-28.

   This revision is *modern-era*: there is no `initialize` handshake, no
   protocol session, and no standalone GET SSE stream. Every request declares
   its own protocol version in `_meta` and mirrors it into the
   `MCP-Protocol-Version` header, and the server accepts or rejects each
   request independently.

   Supports `server/discover`, `tools/list`, `tools/call` and `ping`. When a
   `tools/call` request includes a `progressToken` in `_meta`, the response
   opens as `text/event-stream` and streams `notifications/progress` frames,
   terminated by the final JSON-RPC response frame. Response-scoped SSE
   streams are unchanged in this revision; only the *standalone* GET stream
   was removed.

   We target the latest revision only (#146). That is a deliberate reversal of
   the earlier decision to sit on 2025-03-26, and it deletes the bug class in
   #139 rather than patching it: a legacy server has to get the dual-era
   fallback trigger right, and a modern-only server has no fallback path to
   get wrong. The cost is that legacy-only clients no longer work — see
   `server/docs/api/endpoints/mcp.md` for the measured client compatibility."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.api.body :as request-body]
            [digdir.mcp.streaming :as streaming]
            [digdir.mcp.tools :as mcp-tools])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)
           (java.util.concurrent Executors)))

(def ^:private mcp-protocol-version "2026-07-28")

(def ^:private supported-protocol-versions
  "Every revision this server accepts. Modern-era only, deliberately: adding a
   legacy revision here would re-open the #139 fallback class."
  [mcp-protocol-version])

(def ^:private server-info {:name "digdir-rag" :version "1.0"})

(def ^:private protocol-version-meta-key
  "io.modelcontextprotocol/protocolVersion")

(def ^:private discover-ttl-ms
  "Our identity, capabilities and supported versions change only on deploy."
  3600000)

(def ^:private tools-list-ttl-ms
  "Short on purpose. We declare no `listChanged` notifications, so TTL is the
   client's ONLY invalidation signal, and an operator enabling or disabling an
   agent changes this list. A minute bounds how long a client can keep serving
   a tool set the operator has already changed."
  60000)

(def ^:private name-bearing-methods
  "Methods whose `Mcp-Name` header mirrors a body field. We implement only
   `tools/call` of these, but validating the whole set keeps the rule honest
   if a sibling method is added later."
  #{"tools/call" "resources/read" "prompts/get"})

(def request-metadata-contract
  "The request-metadata headers 2026-07-28 requires, AS DATA.

   Lifted out of `validate-request`'s `cond` (#344) because three consumers
   need this contract and only one of them can read Clojure control flow:

     - `validate-request`, below, executes it
     - the drift test asserts `openapi.yaml` declares exactly these
     - a spec generator (#360) would emit them

   Before this, the only statement of the contract was the `cond`, so the
   documentation could not be checked against it and a generator could not see
   it at all. `openapi.yaml` went a full protocol migration without mentioning
   any of these headers for exactly that reason.

   EVERY VALUE HERE IS PLAIN DATA. No functions, no predicates: a function is
   not readable by a generator or comparable by a drift test, and a contract
   half-expressed as code is the thing this lift exists to remove.

     :header              the wire name, as it appears in documentation
     :ring-key            the lower-cased form Ring supplies
     :required            `:always`, or absent when conditional
     :required-for        methods that require it, when conditional
     :mirrors             body paths, in precedence order; first non-nil wins
     :mirror-optional?    true when a body value absent is NOT a mismatch
     :mirror-description  how the body source is named in the error message
     :allowed-values      when present, the header value must be one of these
     :decode              `:base64-sentinel` for values wrapped as `=?base64?..?=`"
  [{:header "MCP-Protocol-Version"
    :ring-key "mcp-protocol-version"
    :required :always
    :mirrors [["params" "_meta" protocol-version-meta-key]]
    ;; The BODY field is optional and the header is not. mcp.md describes the
    ;; two as mirrored, which reads as both being required; only a disagreement
    ;; is an error.
    :mirror-optional? true
    :mirror-description (str "body _meta '" protocol-version-meta-key "' value")
    :allowed-values supported-protocol-versions}
   {:header "Mcp-Method"
    :ring-key "mcp-method"
    :required :always
    :mirrors [["method"]]
    :mirror-description "body method"}
   {:header "Mcp-Name"
    :ring-key "mcp-name"
    :required-for name-bearing-methods
    ;; Two mirrors: `tools/call` names a tool, `resources/read` a uri.
    :mirrors [["params" "name"] ["params" "uri"]]
    :mirror-description "body value"
    :decode :base64-sentinel}])

(defn- json-response
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             ;; Echo the revision we answered under. The request carries it
             ;; and the response should declare it, so an intermediary or a
             ;; client can tell which era served the response without parsing
             ;; the body.
             "MCP-Protocol-Version" mcp-protocol-version}
   :body (json/generate-string body)})

(defn- jsonrpc-response
  "Wrap a result in a JSON-RPC envelope.

   Every `Result` in 2026-07-28 carries a `resultType` discriminator — MRTR
   made `complete` vs `input_required` a property of the result itself, so
   `tools/list` and `tools/call` need it just as much as `server/discover`
   does. Defaulted here rather than at each call site so a new method cannot
   forget it; an explicit `:resultType` in `result` still wins.

   Found by driving a real client rather than by reading: Claude Code 2.1.238
   silently discarded a `tools/list` response without it, retried four times,
   and reported the server as having no tools. The HTTP status was 200 and the
   body looked correct."
  [request-id result]
  {:jsonrpc "2.0"
   :id request-id
   :result (if (map? result)
             (merge {:resultType "complete"} result)
             result)})

(defn- jsonrpc-error
  [request-id code message data]
  {:jsonrpc "2.0"
   :id request-id
   :error (cond-> {:code code :message message}
            data (assoc :data data))})

(defn- parse-error [request-id message]
  (jsonrpc-error request-id -32700 (or message "Parse error") nil))

(defn- method-not-found [request-id method]
  (jsonrpc-error request-id -32601 "Method not found" {:method method}))

(defn- invalid-params
  ([request-id message] (invalid-params request-id message nil))
  ([request-id message data]
   (jsonrpc-error request-id -32602 (or message "Invalid params") data)))

(defn- internal-error [request-id message data]
  (jsonrpc-error request-id -32603 (or message "Internal error") data))

(defn- header-mismatch
  "`HeaderMismatch` (-32020). Covers a missing required standard header as
   well as a header whose value disagrees with the body — the spec groups
   both under this code."
  [request-id message]
  (jsonrpc-error request-id -32020 message nil))

(defn- unsupported-protocol-version
  "`UnsupportedProtocolVersionError` (-32022). Carries the versions we do
   support so a client can retry rather than guess."
  [request-id requested]
  (jsonrpc-error request-id -32022 "Unsupported protocol version"
                 {:supported supported-protocol-versions
                  :requested requested}))

;;; Request metadata — header/body mirroring and validation

(def ^:private base64-sentinel-prefix "=?base64?")
(def ^:private base64-sentinel-suffix "?=")

(defn- decode-header-value
  "Decode the `=?base64?<b64>?=` sentinel form clients use for header values
   that are not safely representable as plain ASCII. Values not in sentinel
   form are returned unchanged."
  [v]
  (if (and (string? v)
           (str/starts-with? v base64-sentinel-prefix)
           (str/ends-with? v base64-sentinel-suffix)
           (> (count v) (+ (count base64-sentinel-prefix)
                           (count base64-sentinel-suffix))))
    (try
      (-> (Base64/getDecoder)
          (.decode ^String (subs v (count base64-sentinel-prefix)
                                 (- (count v) (count base64-sentinel-suffix))))
          (String. StandardCharsets/UTF_8))
      (catch Exception _
        ;; Undecodable sentinel — hand back the raw value so validation
        ;; fails loudly on mismatch rather than silently accepting.
        v))
    v))

(defn- header
  "Ring lower-cases header names; header names are case-insensitive per RFC 9110."
  [ring-request name]
  (get-in ring-request [:headers name]))

(defn- notification?
  "A JSON-RPC notification carries no `id`."
  [body]
  (not (contains? body "id")))

(defn- decoded-header-for
  [ring-request {:keys [ring-key decode]}]
  (let [v (header ring-request ring-key)]
    (if (= :base64-sentinel decode)
      (some-> v decode-header-value)
      v)))

(defn- mirror-value
  "The body value this header mirrors: first non-nil path wins."
  [body {:keys [mirrors]}]
  (some (fn [path] (get-in body path)) mirrors))

(defn- rule-applies?
  [{:keys [required required-for]} method]
  (or (= :always required)
      (contains? (or required-for #{}) method)))

(defn- validate-metadata-rule
  "nil when this header is satisfied, else `[status jsonrpc-error]`.

   The order inside a rule is deliberate and preserved from the `cond` this
   replaced: missing, then disagreement with the body, then an unsupported
   value. Mismatch before support means a client that contradicts ITSELF is
   told that, rather than being told its version is unsupported — which would
   be true and useless."
  [ring-request body id method
   {header-name :header
    :keys [mirror-optional? mirror-description allowed-values required-for]
    :as rule}]
  (let [hv (decoded-header-for ring-request rule)
        bv (mirror-value body rule)]
    (cond
      (nil? hv)
      [400 (header-mismatch
             id (str "Missing required header: " header-name
                     (when required-for (str " (required for " method ")"))))]

      ;; An absent body value is a mismatch unless the mirror is optional.
      (and (not (and mirror-optional? (nil? bv)))
           (not= hv bv))
      [400 (header-mismatch
             id (str "Header mismatch: " header-name " header '" hv
                     "' does not match " mirror-description " '" bv "'"))]

      (and allowed-values (not (contains? (set allowed-values) hv)))
      [400 (unsupported-protocol-version id hv)]

      :else nil)))

(defn- validate-request
  "Server Validation (2026-07-28, Streamable HTTP §Request Metadata).

   Returns nil when the request may proceed, or `[status jsonrpc-error]`.

   The header rules are `request-metadata-contract`, above, rather than a
   `cond` here (#344): the same contract is executed by this function, checked
   against `openapi.yaml` by the drift test, and readable by a generator. It
   was previously stated only as control flow, which is why the spec could go a
   protocol migration without mentioning any of these headers.

   Header requirements are not defined for notification POSTs in this
   revision, so notifications skip validation entirely."
  [ring-request body]
  (let [id     (get body "id")
        method (get body "method")]
    (if (= "initialize" method)
      ;; NOT a header rule, which is why it stays here rather than joining the
      ;; contract. `initialize` is a legacy client opening a handshake we no
      ;; longer answer, and the spec asks a modern-only server to NAME its
      ;; supported versions in the reply: that client has no fall-forward
      ;; mechanism, so this message may be the only diagnostic its user ever
      ;; sees. Checked before the headers because a legacy client will not have
      ;; sent them either, and "Missing required header" would be true but
      ;; useless.
      [400 (jsonrpc-error
             id -32022
             (str "This server implements MCP "
                  (str/join ", " supported-protocol-versions)
                  " only, which has no `initialize` handshake. Send requests "
                  "directly with an MCP-Protocol-Version header instead.")
             {:supported supported-protocol-versions
              :requested "initialize (handshake-based revision)"})]
      (some (fn [rule]
              (when (rule-applies? rule method)
                (validate-metadata-rule ring-request body id method rule)))
            request-metadata-contract))))

(defn- handle-server-discover
  "`server/discover` — mandatory in this revision. Returns the versions we
   support, our capabilities and our identity in one round trip, so a client
   never has to probe with `tools/list` to find out what we are."
  [request-id _params]
  (jsonrpc-response request-id
                    {:resultType "complete"
                     :supportedVersions supported-protocol-versions
                     :capabilities {:tools {}}
                     :_meta {"io.modelcontextprotocol/serverInfo" server-info}
                     ;; Caching hints are REQUIRED on every complete result of a
                     ;; cacheable operation. Public: identity, capabilities and
                     ;; supported versions are identical for every caller.
                     :ttlMs discover-ttl-ms
                     :cacheScope "public"
                     ;; How to use this server at all (#123). `server/discover`
                     ;; is where this belongs in 2026-07-28 — DiscoverResult
                     ;; has a first-class `instructions` field, and it is the
                     ;; one request a client makes before doing anything else.
                     ;;
                     ;; Deliberately the shared var rather than a copy: it is
                     ;; the only place on the wire that documents the tool
                     ;; naming scheme, the conversation_id handle and the
                     ;; isError vs -32603 split, and #122 has already corrected
                     ;; its naming sentence. A local copy would have silently
                     ;; re-described a scheme we abandoned this morning.
                     :instructions mcp-tools/server-instructions}))

(defn- handle-tools-list
  [request-id principal _params]
  (try
    (jsonrpc-response request-id
                      (assoc (mcp-tools/list-tools-response principal)
                             :ttlMs tools-list-ttl-ms
                             ;; PRIVATE, and this is a security decision rather
                             ;; than a default. The tool list is filtered per
                             ;; API key (`:agent-refs`, `:skill-graphs`), so it
                             ;; is caller-specific. The spec is explicit that a
                             ;; "public" result MAY be shared across
                             ;; authorization contexts even from an
                             ;; authenticated endpoint — which would serve one
                             ;; key's tools to the holder of another.
                             :cacheScope "private"))
    (catch Throwable e
      (log/error e "MCP tools/list failed")
      (internal-error request-id (.getMessage e) nil))))

(defn- progress-token
  [params]
  (when-let [meta (get params "_meta")]
    (or (get meta "progressToken") (get meta "progress_token"))))

(defn- run-tools-call
  [request-id principal tool-name arguments progress-fn]
  (try
    (let [{:keys [result error]}
          (mcp-tools/invoke-tool principal tool-name arguments progress-fn)]
      (cond
        (nil? error) (jsonrpc-response request-id result)

        ;; Named something that does not exist: JSON-RPC has a code for that.
        (= :invalid-params (mcp-tools/error-channel error))
        (invalid-params request-id (:message error) {:code (:code error)})

        ;; Everything else the caller can act on goes back to the MODEL as a
        ;; successful call carrying isError:true, text intact (#117). Only a
        ;; thrown exception - caught below - means the server broke.
        :else
        (jsonrpc-response request-id (mcp-tools/error->tool-result error))))
    (catch Throwable e
      (log/error e (str "MCP tools/call failed: " tool-name))
      (internal-error request-id (.getMessage e) {:tool tool-name}))))

(defonce ^:private streaming-executor
  (delay (Executors/newCachedThreadPool)))

(defn- streaming-tools-call
  "Return an SSE response that streams notifications/progress followed by
   the final JSON-RPC tools/call result.

   Cancellation: SseBody's writer thread flips `cancel?` to true when the
   client disconnects (IOException/EOFException on write). A watch on the
   atom fires `Future.cancel(true)`, which interrupts the worker thread.
   The agent loop checks `Thread/interrupted` between iterations and
   raises `InterruptedException`; in-flight LLM HTTP calls cancel via
   hato's interrupt support. This stops work that the client will never
   read."
  [request-id principal tool-name arguments token]
  (let [queue (streaming/make-queue)
        cancel? (atom false)
        progress-fn (streaming/make-progress-fn queue token)
        work-fn (fn []
                  (try
                    (let [response (run-tools-call request-id principal
                                                    tool-name arguments
                                                    progress-fn)]
                      (when-not @cancel?
                        (streaming/push! queue response)))
                    (catch InterruptedException _
                      (log/info "MCP streaming tools/call cancelled (client disconnect)")
                      (.interrupt (Thread/currentThread)))
                    (catch Throwable e
                      (when-not @cancel?
                        (log/error e "MCP streaming tools/call work failed")
                        (streaming/push! queue
                                          (internal-error request-id
                                                           (.getMessage e)
                                                           nil))))
                    (finally
                      (streaming/end-stream! queue))))
        ^java.util.concurrent.ExecutorService executor @streaming-executor
        work-future (.submit executor ^Runnable work-fn)]
    (add-watch cancel? ::cancel-on-disconnect
               (fn [_ _ _ new-val]
                 (when (true? new-val)
                   (try
                     (.cancel ^java.util.concurrent.Future work-future true)
                     (catch Throwable _ nil)))))
    {:status 200
     :headers {"Content-Type" "text/event-stream; charset=utf-8"
               "Cache-Control" "no-cache, no-transform"
               "X-Accel-Buffering" "no"}
     :body (streaming/->SseBody queue cancel?)}))

(defn- handle-tools-call
  [request-id principal params]
  (let [tool-name (get params "name")
        arguments (or (get params "arguments") {})
        token (progress-token params)]
    (cond
      (nil? tool-name)
      (invalid-params request-id "tools/call requires :name")

      token
      ;; Streaming branch — return SSE response. The caller composes the
      ;; full HTTP response, so we tag this with a marker the outer
      ;; dispatch picks up.
      {::http-response (streaming-tools-call request-id principal tool-name
                                              arguments token)}

      :else
      (run-tools-call request-id principal tool-name arguments nil))))

(def implemented-methods
  "Every JSON-RPC method this server implements, as a map from the wire name to
   its handler (#368).

   THE KEY SET IS THE CONTRACT. It is stated three times — here, in
   `openapi.yaml`'s `Mcp-Method` enum, and in that operation's description — and
   until now only this one was executable, so the other two were hand-maintained
   copies with nothing to check them against. That is the same shape #344 removed
   from the request-metadata headers, one noun over.

   DELIBERATELY LOOSER THAN `request-metadata-contract`, which forbids functions
   in the structure. Here the VALUES are handlers and only the KEYS are the
   contract: a generator or a drift test needs the method names, not the code
   behind them, so `(keys implemented-methods)` is the readable artifact and the
   functions are an implementation detail hanging off it.

   Unordered on purpose — dispatch is a lookup. The header contract had to stay a
   vector because its rules are evaluated in sequence."
  {"server/discover" (fn [id _principal params] (handle-server-discover id params))
   "tools/list"      (fn [id principal params] (handle-tools-list id principal params))
   "tools/call"      (fn [id principal params] (handle-tools-call id principal params))
   "ping"            (fn [id _principal _params] (jsonrpc-response id {}))})

(defn- dispatch
  "Route one JSON-RPC request. Returns either a JSON-RPC response map (sent
   with HTTP 200), a `::http-response` marker carrying a raw ring response
   (SSE), or `[status jsonrpc-error]` when the status is not 200.

   The method set is `implemented-methods` above rather than a `cond` here, so
   the contract can be read by the drift test and by a spec generator instead of
   existing only as control flow.

   Unknown methods return **404**, not 200. That is required by this revision
   and it is the direct repair of #139: a JSON-RPC error inside a non-2xx
   response is what lets a dual-era client tell a modern server from a legacy
   one. Returning 200 here is what made our old fallback behaviour depend on
   client leniency."
  [principal {:strs [method id] :as request}]
  (let [params (get request "params" {})]
    (if-let [handler (get implemented-methods method)]
      (handler id principal params)
      [404 (method-not-found id method)])))

(defn- read-body
  [request]
  (try
    (let [body-str (request-body/read-body-string request)]
      {:ok (json/parse-string body-str)})
    (catch Exception e
      {:error (.getMessage e)
       :status (when (request-body/body-too-large? e) 413)})))

(defn handle-mcp-request
  "Top-level MCP handler. Validates the request metadata, dispatches the
   method, and serializes the response. Accepts a single request object;
   batch requests are not supported.

   `Mcp-Session-Id` and `Last-Event-ID` are deliberately ignored rather than
   rejected: this revision has no sessions and no resumable streams, and the
   spec asks a modern-only server to ignore both rather than fail on them."
  [request]
  (let [{:keys [ok error status]} (read-body request)]
    (cond
      error
      (json-response (or status 400) (parse-error nil error))

      (nil? ok)
      (json-response 400 (parse-error nil "Empty request body"))

      (map? ok)
      ;; A notification gets 202 and no body. Header requirements are not
      ;; defined for notification POSTs in this revision, so they are not
      ;; validated. This core protocol defines no client-to-server
      ;; notifications over Streamable HTTP, so in practice this is the
      ;; politeness path for a client that sends one anyway.
      (if (notification? ok)
        {:status 202 :headers {} :body ""}
        (if-let [[status err] (validate-request request ok)]
          (json-response status err)
          (let [response (dispatch request ok)]
            (cond
              (nil? response) {:status 202 :headers {} :body ""}
              (::http-response response) (::http-response response)
              (vector? response) (json-response (first response) (second response))
              :else (json-response 200 response)))))

      :else
      (json-response 400 (parse-error nil "Batched requests are not supported")))))

(defn handle-mcp-method-not-allowed
  "GET and DELETE on the MCP endpoint belonged to the pre-2026-07-28 shape —
   the standalone SSE stream and session termination respectively. Neither
   exists now, and the spec asks a modern-only server to answer 405 rather
   than 404 so a client can tell \"wrong verb\" from \"wrong endpoint\"."
  [_request]
  {:status 405
   :headers {"Content-Type" "application/json"
             "Allow" "POST"}
   :body (json/generate-string
           (jsonrpc-error nil -32601
                          "Method Not Allowed: the MCP endpoint accepts POST only"
                          {:allow ["POST"]}))})
