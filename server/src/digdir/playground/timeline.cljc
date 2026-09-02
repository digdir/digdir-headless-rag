(ns digdir.playground.timeline
  "Tool/timeline shaping for Playground chat diagnostics.")

(defn append-tool-call
  "Append tool call metadata to timeline preserving order."
  [timeline tool-call]
  (let [entry (assoc tool-call :seq (count timeline))]
    (conj (vec (or timeline [])) entry)))

(defn extract-search-phrases-from-trace
  "Extract search phrases from agent trace tool calls when :search-phrases is empty."
  [trace]
  (->> trace
       (mapcat :tool-calls)
       (filter #(= "search_documents" (:tool %)))
       (mapcat #(get-in % [:args :queries]))
       (remove nil?)
       distinct
       vec))

(defn timeline-from-agent-trace
  "Create a flat timeline from trace turns and nested tool calls.
   Kept as a reusable helper for ongoing chat-session migration."
  [agent-trace]
  (reduce (fn [timeline turn]
            (reduce append-tool-call
                    timeline
                    (or (:tool-calls turn) [])))
          []
          (or agent-trace [])))
