(ns digdir.skills.builtin.agent.streaming
  "Paragraph-or-timeout token chunker.

   Streaming-chat-completion emits one OpenAI content delta per token
   (~30ms each). Per-token notifications/progress over MCP would mean
   ~1000 frames per long answer and a typing-effect that adds little
   value for our `:report`-emitting graphs.

   This chunker accumulates deltas server-side and flushes a coherent
   unit when either:
     - a paragraph boundary (\\n\\n) lands in the buffer, OR
     - `timeout-ms` (default 250ms) has elapsed since the buffer
       became non-empty.

   The result is ~5–20 chunks per typical answer with bounded
   worst-case latency on long unbroken paragraphs (code blocks, tables)."
  (:require [clojure.string :as str]))

(def ^:private default-timeout-ms 250)
(def ^:private paragraph-boundary "\n\n")

(defn- now-ms [] (System/currentTimeMillis))

(defn- split-on-last-boundary
  "Return [flushable remainder] where flushable ends at the last \\n\\n
   in `buf` and remainder is whatever comes after. nil flushable means
   no boundary was found."
  [buf]
  (let [idx (str/last-index-of buf paragraph-boundary)]
    (if (nil? idx)
      [nil buf]
      (let [cut (+ idx (count paragraph-boundary))]
        [(subs buf 0 cut) (subs buf cut)]))))

(defn make-chunker
  "Create a paragraph-or-timeout chunker.

   Returns a map with:
     :on-delta — (fn [content-delta]) — call for each streamed content fragment.
                 Flushes to `on-chunk` when a paragraph boundary lands or
                 the timeout elapses since the buffer became non-empty.
     :close    — (fn []) — flush any remaining buffered content as a final
                 chunk. Call once when the stream completes.

   Options:
     :on-chunk    (required) — (fn [chunk-string]) — sink for each emitted chunk.
     :timeout-ms  (default 250) — max time a buffered fragment is held before
                  a forced flush.
     :now-fn      (default System/currentTimeMillis) — overridable for tests."
  [{:keys [on-chunk timeout-ms now-fn]
    :or {timeout-ms default-timeout-ms
         now-fn now-ms}}]
  (when-not (fn? on-chunk)
    (throw (ex-info "make-chunker: :on-chunk fn is required" {})))
  (let [state (atom {:buf "" :buffered-at nil})]
    {:on-delta
     (fn [delta]
       (when (and (string? delta) (pos? (count delta)))
         (let [{:keys [buf buffered-at]} @state
               next-buf (str buf delta)
               next-buffered-at (or buffered-at (now-fn))
               [flushable remainder] (split-on-last-boundary next-buf)
               age-ms (- (now-fn) next-buffered-at)]
           (cond
             ;; Paragraph boundary — flush up to and including the last \n\n.
             flushable
             (do
               (on-chunk flushable)
               (reset! state {:buf remainder
                              :buffered-at (when (pos? (count remainder))
                                             (now-fn))}))

             ;; Timeout — flush whatever we have.
             (>= age-ms timeout-ms)
             (do
               (on-chunk next-buf)
               (reset! state {:buf "" :buffered-at nil}))

             :else
             (reset! state {:buf next-buf :buffered-at next-buffered-at})))))

     :close
     (fn []
       (let [{:keys [buf]} @state]
         (when (pos? (count buf))
           (on-chunk buf)
           (reset! state {:buf "" :buffered-at nil}))))}))
