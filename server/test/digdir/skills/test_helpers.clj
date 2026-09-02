(ns digdir.skills.test-helpers
  "Shared test stubs for LLM-backed skill tests.

   Provides deterministic LLM replacements so tests don't need real credentials
   and produce repeatable results.")

(defn llm-returning-json
  "Chat-completion stub that returns a canned JSON string.
   Matches the tenant-prefixed chat-completion signature used by
   `loop/call-llm` and `read-signals/default-llm-fn`. Accepts both the
   legacy 5-arg form and the 6-arg form that carries `:progress-fn`
   for the per-paragraph streaming branch."
  [json-str]
  (fn [& _args]
    {:choices [{:message {:content json-str}}]}))

(defn llm-throwing
  "Chat-completion stub that throws, forcing degraded-fallback paths."
  ([] (llm-throwing "Stubbed LLM exception"))
  ([message] (llm-throwing message {}))
  ([message data]
   (fn [& _]
     (throw (ex-info message (or data {}))))))

(defn llm-capturing
  "Chat-completion stub that captures all call args into `!captured` (atom of vector)
   and returns a canned JSON string. Accepts both the legacy 5-arg form
   and the 6-arg form that carries `:progress-fn` for streaming."
  [!captured json-str]
  (fn [& args]
    (let [n (count args)
          [tenant messages tools model temperature]
          (cond
            (>= n 5) (take 5 args)
            :else (cons nil args))]
      (swap! !captured conj {:tenant tenant
                             :messages messages
                             :tools tools
                             :model model
                             :temperature temperature})
      {:choices [{:message {:content json-str}}]})))

(defn make-scripted-llm
  "Returns a fn that replays LLM responses from a vector in order.
   Throws when the script runs out. Each entry is a raw API-shaped response map,
   or a map with `:throw {:message ... :data ...}` to simulate an LLM error."
  [responses]
  (let [idx (atom -1)]
    (fn [& _args]
      (let [i (swap! idx inc)]
        (when (>= i (count responses))
          (throw (ex-info "Ran out of scripted LLM responses"
                          {:index i :total (count responses)})))
        (let [step (nth responses i)]
          (if-let [{:keys [message data]} (:throw step)]
            (throw (ex-info (or message "Scripted LLM exception")
                            (or data {})))
            step))))))
