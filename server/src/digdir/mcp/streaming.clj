(ns digdir.mcp.streaming
  "MCP progress streaming via Streamable HTTP / Server-Sent Events.

   When a `tools/call` request carries a `progressToken` in `_meta`, the
   transport returns `Content-Type: text/event-stream` and the handler
   emits JSON-RPC `notifications/progress` frames as the skill graph
   runs, terminated by the final JSON-RPC result frame.

   The SSE response body uses `ring.core.protocols/StreamableResponseBody`
   so Jetty keeps a synchronous request thread — no Ring async needed.
   A bounded `LinkedBlockingQueue` bridges the executor thread (which
   runs `invoke-rag`) and the SSE writer thread; an `::end` sentinel
   tells the writer to flush and close.

   This supersedes the structured-event half of
   `plans/proposed/streaming-public-api-plan.md`. Per-token deltas land
   when the agent loop's `call-llm` gains a streaming branch."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [ring.core.protocols :as ring-protocols])
  (:import (java.io OutputStream OutputStreamWriter Writer EOFException IOException)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private queue-capacity 1024)
(def ^:private end-sentinel ::end)
(def ^:private heartbeat-interval-ms 15000)

(defn- write-sse!
  [^Writer w event-name data]
  (when event-name
    (.write w "event: ")
    (.write w ^String (name event-name))
    (.write w "\n"))
  (.write w "data: ")
  (.write w ^String (json/generate-string data))
  (.write w "\n\n")
  (.flush w))

(defn- write-heartbeat!
  [^Writer w]
  (.write w ": ping\n\n")
  (.flush w))

;; ----------------------------------------------------------------------------
;; Event → notifications/progress mapping
;; ----------------------------------------------------------------------------

(defn- nilable
  [m]
  (into {} (remove #(nil? (val %)) m)))

(defn- event->progress-params
  "Translate a skills/event payload into the `params` map of a JSON-RPC
   `notifications/progress` notification. Returns nil for events the MCP
   server does not surface (internal noise, allowlist-suppressed)."
  [progress-token counter event]
  (let [kind (:event event)
        next-progress (fn [] (swap! counter inc))
        base {:progressToken progress-token}]
    (case kind
      :request/started
      (assoc base
             :progress (next-progress)
             :message "Request started"
             :_meta (nilable {:event "request/started"
                              :request-id (:request-id event)
                              :query (:query event)}))

      :stage/started
      (assoc base
             :progress (next-progress)
             :message (or (:label event) (str "Stage " (:stage event)))
             :_meta (nilable {:event "stage/started"
                              :stage (some-> (:stage event) name)}))

      :stage/completed
      (assoc base
             :progress (next-progress)
             :message (or (:label event) (str "Stage " (:stage event) " complete"))
             :_meta (nilable {:event "stage/completed"
                              :stage (some-> (:stage event) name)
                              :duration-ms (:duration-ms event)}))

      (:step/skipped :step/defaulted)
      (assoc base
             :progress (next-progress)
             :message (str "Step " (or (some-> (:skill-id event) name)
                                       (some-> (:step-id event) name))
                           " " (name kind))
             :_meta (nilable {:event (name kind)
                              :step-id (some-> (:step-id event) name)
                              :skill-id (some-> (:skill-id event) name)
                              :duration-ms (:duration-ms event)}))

      :tool/call
      (assoc base
             :progress (next-progress)
             :message (str "Tool call: " (some-> event :tool-call :tool))
             :_meta {:event "tool/call"
                     :tool-call (:tool-call event)})

      :tool/result
      (assoc base
             :progress (next-progress)
             :message (str "Tool result: " (some-> event :tool-result :tool))
             :_meta {:event "tool/result"
                     :tool-result (:tool-result event)})

      :agent/iteration-started
      (assoc base
             :progress (next-progress)
             :total (:max-iterations event)
             :message (str "Iteration " (:iteration event)
                           " of " (:max-iterations event))
             :_meta {:event "agent/iteration-started"
                     :iteration (:iteration event)
                     :max-iterations (:max-iterations event)})

      :agent/thinking
      (assoc base
             :progress (next-progress)
             :message "Reasoning…"
             :_meta (nilable {:event "agent/thinking"
                              :iteration (:iteration event)
                              :reasoning (:reasoning event)}))

      :agent/turn-completed
      (assoc base
             :progress (next-progress)
             :message (str "Turn " (:iteration event) " complete")
             :_meta {:event "agent/turn-completed"
                     :iteration (:iteration event)
                     :tool-calls (:tool-calls event)})

      :agent/finalized
      (assoc base
             :progress (next-progress)
             :message "Response finalized"
             :_meta (nilable {:event "agent/finalized"
                              :iteration (:iteration event)
                              :response-length (:response-length event)}))

      :warning/raised
      (assoc base
             :progress (next-progress)
             :message (or (some-> event :warning :message) "Warning")
             :_meta {:event "warning/raised"
                     :warning (:warning event)})

      :response/chunk
      ;; Token deltas — surface delta in `:message` and the cumulative
      ;; assembled string lives in `_meta.partial`. Callers that don't
      ;; ship deltas (current default) simply never emit this event.
      (assoc base
             :progress (next-progress)
             :message (:delta event)
             :_meta {:event "response/chunk"
                     :delta (:delta event)})

      ;; Suppressed: :step/started/completed/failed, :graph/completed,
      ;; :response/finalized, :request/failed — the final JSON-RPC
      ;; response carries terminal state.
      nil)))

(defn make-progress-fn
  "Build a progress-fn that pushes JSON-RPC `notifications/progress`
   payloads onto `queue` keyed against `progress-token`. Drops with a
   WARN log when the queue overflows so the executor never blocks."
  [^LinkedBlockingQueue queue progress-token]
  (let [counter (atom 0)]
    (fn [event]
      (try
        (when-let [params (event->progress-params progress-token counter event)]
          (let [notification {:jsonrpc "2.0"
                              :method "notifications/progress"
                              :params params}]
            (when-not (.offer queue notification 100 TimeUnit/MILLISECONDS)
              (log/warn "MCP SSE queue overflow — dropping event"
                        (:event event)))))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          nil)
        (catch Throwable e
          (log/warn e "MCP progress translation failed for"
                    (:event event)))))))

;; ----------------------------------------------------------------------------
;; SSE response body
;; ----------------------------------------------------------------------------

(deftype SseBody [^LinkedBlockingQueue queue cancel-atom]
  ring-protocols/StreamableResponseBody
  (write-body-to-stream [_ _response output-stream]
    (let [writer (OutputStreamWriter. ^OutputStream output-stream
                                       StandardCharsets/UTF_8)]
      (try
        (loop []
          (when-not @cancel-atom
            (let [item (.poll queue heartbeat-interval-ms TimeUnit/MILLISECONDS)]
              (cond
                (nil? item)
                (do (write-heartbeat! writer) (recur))

                (= end-sentinel item)
                nil

                :else
                (do (write-sse! writer nil item) (recur))))))
        (catch EOFException _ (reset! cancel-atom true))
        (catch IOException _ (reset! cancel-atom true))
        (catch Throwable e
          (log/warn e "MCP SSE writer failed"))
        (finally
          (try (.close writer) (catch Throwable _ nil)))))))

(defn end-stream!
  "Signal the SSE writer to drain and close."
  [^LinkedBlockingQueue queue]
  (.put queue end-sentinel))

(defn make-queue
  "Allocate the queue that bridges the executor thread and the SSE
   writer. Exposed for testing."
  []
  (LinkedBlockingQueue. ^Integer (int queue-capacity)))

(defn push!
  "Enqueue a JSON-RPC message for the writer thread. Used by the
   transport to push the final tools/call response after the work
   thread finishes."
  [^LinkedBlockingQueue queue message]
  (.offer queue message 200 TimeUnit/MILLISECONDS))
