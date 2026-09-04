(ns digdir.llm.openai
  #?(:clj (:require [clojure.core.async :as async]
                    [clojure.core.async.impl.protocols :as async-protocols]
                    [digdir.data.db :refer [transact-assistant-msg] :as db]
                    [digdir.config.accessor :as cfg]
                    [digdir.llm.model-params :as model-params]
                    [wkok.openai-clojure.api :as api])))

#?(:clj (defn process-chunk [!stream-msgs]
          (fn [convo-id data]
            (let [delta (get-in data [:choices 0 :delta])
                  content (:content delta)]
              (if content
                (swap! !stream-msgs update-in [convo-id :content] (fn [old-content] (str old-content content)))
                (do
                  (swap! !stream-msgs assoc-in [convo-id :streaming] false)
                  (let [resp (:content (get @!stream-msgs convo-id))]
                    (transact-assistant-msg (db/get-conn) convo-id resp))
                  (swap! !stream-msgs assoc-in [convo-id :content] nil)))))))

#?(:clj (defn stream-chat-completion [!stream-msgs convo-id msg-list]
          (swap! !stream-msgs assoc-in [convo-id :streaming] true)
          (let [process-chunk-fn (process-chunk !stream-msgs)]
            (try (api/create-chat-completion
                   {:model "gpt-4o"
                    :messages msg-list
                    :stream true
                    :on-next #(process-chunk-fn convo-id %)})
              (catch Exception e
                (println "This is the exception: " e))))))

#?(:clj (defn get-chat-completion [!wait? convo-id msg-list]
          (let [_ (reset! !wait? true)
                _ (println "reset wait to true")
                _ (println "the msg list: " msg-list)
                raw-resp (api/create-chat-completion {:model "gpt-4o"
                                                      :messages msg-list})
                resp (get-in raw-resp [:choices 0 :message :content])]
            (transact-assistant-msg (db/get-conn) convo-id resp)
            (reset! !wait? false)
            (println "reset wait to false"))))

#?(:clj
   (defn use-azure-openai
     "Delegates to `cfg/use-azure-openai?` — the ONE read of this switch (#500)."
     [tenant]
     (cfg/use-azure-openai? tenant)))

#?(:clj
   (defn create-chat-completion [tenant messages]
     (if (use-azure-openai tenant)
       (api/create-chat-completion
        {:model (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
         :messages messages
         :temperature 0.1}
        {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
         :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
         :impl :azure})
       (api/create-chat-completion
        {:model (cfg/get {:tenant tenant} :services :azure-openai :model-name)
         :messages messages
         :temperature 0.1
         :stream false}))))

#?(:clj
   (defn- merge-tool-call-delta
     "Tool calls stream in fragments across many chunks. The first chunk
      typically carries `:id`, `:type`, and `:function.name`; later chunks
      append `:function.arguments` fragments. We accumulate by `:index`."
     [acc {:keys [index] :as delta}]
     (let [existing (get acc index {})
           f-existing (:function existing {})
           f-delta (:function delta)
           merged-function (cond-> f-existing
                             (:name f-delta) (assoc :name (:name f-delta))
                             (:arguments f-delta) (update :arguments
                                                          #(str (or % "") (:arguments f-delta))))]
       (assoc acc index
              (cond-> (merge existing (dissoc delta :function :index))
                (or (:function existing) (:function delta))
                (assoc :function merged-function))))))

#?(:clj
   (defn- assemble-stream-state
     "Translate the accumulated stream state into the same shape
      `create-chat-completion` would return for a non-streaming call."
     [state]
     (let [tool-calls (->> (:tool-calls state)
                           (into (sorted-map))
                           vals
                           (mapv (fn [tc]
                                   (cond-> tc
                                     (:index tc) (dissoc :index)))))]
       {:choices [{:message (cond-> {:role (or (:role state) "assistant")}
                              (:content state) (assoc :content (:content state))
                              (seq tool-calls) (assoc :tool_calls tool-calls))
                  :finish_reason (:finish-reason state)
                  :index 0}]
        :usage (:usage state)})))

#?(:clj
   (defn- stream-events
     "The SSE event channel out of wkok, whichever shape the client returned.

      wkok has two client implementations and they disagree: the Azure path
      returns `{:body <channel>}`, the OpenAI-compatible path (local models
      included) returns the **channel itself**, with no `:body`. Assuming the
      Azure shape made `(:body resp)` nil for every non-Azure provider, and
      `(async/<!! nil)` then failed with a core.async protocol error that
      named neither the LLM, the provider, streaming, nor configuration (#305).

      Throws with a message that names this path if the response is neither."
     [resp]
     (or (cond
           (satisfies? async-protocols/ReadPort resp) resp
           (satisfies? async-protocols/ReadPort (:body resp)) (:body resp))
         (throw (ex-info (str "LLM streaming: the chat-completion client returned no event "
                              "channel. Expected a core.async channel, or a map with :body "
                              "holding one; got " (pr-str (type resp)) ".")
                         {:llm/path :streaming
                          :response-type (str (type resp))
                          :body-type (str (type (:body resp)))})))))

#?(:clj
   (defn stream-idle-timeout-ms
     "How long the drain loop waits for the NEXT stream event before giving up.

      An IDLE bound, not a total one: a long answer may stream for minutes, but
      a gap this large means the stream is not coming back.

      30s deliberately, not a generous multiple of it. A healthy provider does
      not go half a minute between SSE events, so a gap that long is
      infrastructure instability, and the useful response is to fail loudly
      while the cause is still visible rather than to absorb it and report a
      slow request. Override with `LLM_STREAM_IDLE_TIMEOUT_MS` — raise it for a
      backend whose time-to-first-token legitimately exceeds this."
     []
     (or (try (some-> (System/getenv "LLM_STREAM_IDLE_TIMEOUT_MS") Long/parseLong)
              (catch Exception _ nil))
         30000)))

#?(:clj
   (defn streaming-chat-completion
     "Streaming counterpart to `wkok.openai-clojure.api/create-chat-completion`.

      Drains the SSE channel synchronously on the calling thread, invokes
      `:on-content-delta` for each `delta.content` fragment, accumulates
      tool-call deltas indexed by `:index`, and returns the assembled
      response in the same shape the blocking call produces:

          {:choices [{:message {...} :finish_reason \"...\"}]
           :usage   {...}}

      Args:
        params  — chat-completion params (`:stream true` is added internally).
        opts    — map with `:on-content-delta` plus any wkok options
                  (`:api-key`, `:api-endpoint`, `:impl`, ...) the underlying
                  call needs."
     ([params] (streaming-chat-completion params {}))
     ([params {:keys [on-content-delta] :as opts}]
      (let [wkok-opts (dissoc opts :on-content-delta)
            ;; A streaming response carries no token counts unless asked: the
            ;; server sends a final `choices: []` chunk with `:usage` only when
            ;; `stream_options.include_usage` is set. Without it every LLM stage
            ;; reports nil usage, and the #25 latency decomposition classifies
            ;; LLM calls by exactly that property — so :llm-ms and every token
            ;; column silently read 0 on the streaming path.
            ;;
            ;; Sent on BOTH branches. It was previously withheld from Azure
            ;; on the grounds that it needs api-version >= 2024-06-01 — but
            ;; wkok's Azure impl hardcodes exactly `2024-06-01`
            ;; (azure.clj `patch-params`), so that condition was already met
            ;; and the exclusion only cost us the data. Verified on the wire
            ;; against the live deployment at that api-version: `stream` alone
            ;; returns 200 with no prompt_tokens anywhere in the stream;
            ;; `stream` + `include_usage` returns 200 WITH them. Until this,
            ;; every streamed call reported nil usage, so #25 classified
            ;; Playground LLM calls as missing-usage and every token column
            ;; read 0 there.
            ;;
            ;; `:stream/close?` is likewise non-Azure ONLY. It is a documented
            ;; wkok parameter (doc/03-streaming.md; added 0.21.0 / PR 63) that
            ;; closes the core.async event channel at end-of-stream, but wkok's
            ;; Azure impl — added two releases LATER in 0.21.2 / PR 73 — never
            ;; learned about it. `azure/patch-params` names neither
            ;; `:stream/close?` in the keys it strips from
            ;; `:martian.core/body`, nor in the `(select-keys params [:stream
            ;; :on-next])` it forwards, so on Azure the key fails twice: it is
            ;; serialized into the request JSON, where Azure rejects it with
            ;; 400 "Unrecognized request argument supplied: stream/close?",
            ;; AND it never reaches `sse/sse-events`, so it would not have
            ;; closed the channel anyway. Dropping it on Azure therefore costs
            ;; nothing: the drain loop below already terminates on the `:done`
            ;; event that `sse/parse-event` emits for the `[DONE]` terminator.
            ;; The OpenAI impl coerces the body against its OpenAPI schema,
            ;; which strips the key from the JSON while leaving it visible in
            ;; params — which is why it works there and only there.
            ;; Reasoning-class models (GPT-5 family, e.g. the `gpt-5.6-sol`
            ;; deployment) hard-400 on `temperature` != 1 and on `max_tokens`.
            ;; `digdir.llm.client` normalizes both away at its own chokepoint,
            ;; but that client is NON-streaming only — this fn is the streaming
            ;; one, and the agent loop reaches it whenever a `progress-fn` is
            ;; supplied (i.e. every playground run). Without this the agent's
            ;; hardcoded `temperature 0.3` reached the wire and every streamed
            ;; request to a GPT-5 deployment failed at iteration 0. Applied
            ;; LAST so it also sees the keys added just above, and keyed off
            ;; `:model` — which on the Azure path carries the deployment name,
            ;; the only family signal available there.
            stream-params (-> (cond-> (assoc params
                                             :stream true
                                             :stream_options {:include_usage true})
                                (not= :azure (:impl wkok-opts))
                                (assoc :stream/close? true))
                              model-params/normalize-request)
            resp (if (seq wkok-opts)
                   (api/create-chat-completion stream-params wkok-opts)
                   (api/create-chat-completion stream-params))
            events (stream-events resp)
            state (atom {:content nil
                         :tool-calls {}
                         :finish-reason nil
                         :role nil
                         :usage nil})]
        (loop []
          ;; A fresh timeout per iteration, so this bounds the gap BETWEEN
          ;; events rather than the whole stream — a long answer may legitimately
          ;; stream for minutes.
          ;;
          ;; Needed because on Azure the events channel is never closed: wkok's
          ;; `sse/sse-events` closes it only `(when close? ...)`, and
          ;; `:stream/close?` cannot reach it there (its Azure `patch-params`
          ;; neither forwards the key nor strips it from the request body, where
          ;; it 400s). Termination therefore rests entirely on a `[DONE]` event,
          ;; and a stream cut short without one — provider error mid-stream,
          ;; content-filter abort, dropped connection — blocked `<!!` forever,
          ;; hanging the agent iteration with no timeout anywhere above it.
          (let [idle (async/timeout (stream-idle-timeout-ms))
                [event port] (async/alts!! [events idle])]
            (cond
              (= port idle)
              (throw (ex-info (str "LLM streaming: no event received for "
                                   (stream-idle-timeout-ms) "ms. The provider "
                                   "stream stalled, or ended without a [DONE] "
                                   "terminator. Raise LLM_STREAM_IDLE_TIMEOUT_MS "
                                   "if a healthy stream legitimately pauses this long.")
                              {:llm/path :streaming
                               :idle-timeout-ms (stream-idle-timeout-ms)
                               :partial-content-length (count (or (:content @state) ""))}))

              (nil? event) nil
              (= :done event) nil
              :else
              (do
                (let [choice (get-in event [:choices 0])
                      {:keys [delta finish_reason]} choice
                      content (:content delta)
                      tool-calls (:tool_calls delta)]
                  (when (:role delta)
                    (swap! state assoc :role (:role delta)))
                  (when content
                    (swap! state update :content #(str (or % "") content))
                    (when on-content-delta
                      (on-content-delta content)))
                  (when (seq tool-calls)
                    (swap! state update :tool-calls
                           (fn [acc] (reduce merge-tool-call-delta acc tool-calls))))
                  (when finish_reason
                    (swap! state assoc :finish-reason finish_reason))
                  (when-let [usage (:usage event)]
                    (swap! state assoc :usage usage)))
                (recur)))))
        (assemble-stream-state @state)))))
