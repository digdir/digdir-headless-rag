(ns smoke-health
  "Agent-loop health check for the weekend-queue smoke gate. A flaky/half-alive
   Kimi endpoint produces 'complete' runs that are actually broken: the agent
   aborts after ~1 LLM call with a tiny non-answer and zero citations. The
   planner-404 grep can't see that. This requires the smoke's agent runs to look
   like REAL multi-step runs before the queue spends the weekend.
   Exit 0 iff every run is healthy; non-zero otherwise (→ caller waits + retries).")

(defn parse-csv [s]
  (loop [i 0 field (StringBuilder.) row (transient []) rows (transient []) inq false]
    (if (>= i (count s))
      (persistent! (conj! rows (persistent! (conj! row (.toString field)))))
      (let [c (.charAt s i)]
        (cond
          inq (cond
                (and (= c \") (< (inc i) (count s)) (= (.charAt s (inc i)) \")) (do (.append field \") (recur (+ i 2) field row rows true))
                (= c \") (recur (inc i) field row rows false)
                :else (do (.append field c) (recur (inc i) field row rows true)))
          (= c \") (recur (inc i) field row rows true)
          (= c \,) (recur (inc i) (StringBuilder.) (conj! row (.toString field)) rows false)
          (= c \newline) (recur (inc i) (StringBuilder.) (transient []) (conj! rows (persistent! (conj! row (.toString field)))) false)
          (= c \return) (recur (inc i) field row rows false)
          :else (do (.append field c) (recur (inc i) field row rows false)))))))

(defn read-maps [path]
  (let [rows (remove #(= % [""]) (parse-csv (slurp path)))
        header (mapv keyword (first rows))]
    (mapv #(zipmap header %) (rest rows))))

(defn num [s] (try (Double/parseDouble (str s)) (catch Exception _ 0.0)))

;; A healthy run = the agent actually looped (≥2 LLM calls), retrieved, cited,
;; and produced a real answer (not a ~60-char abort).
(defn healthy? [r]
  (and (= "complete" (:status r))
       (not= "true" (:empty? r))
       (>= (num (:llm-calls r)) 2)
       (pos? (num (:n-retrieved r)))
       (pos? (num (:n-cited r)))
       (>= (count (str (:response r))) 200)))

(let [dir (first *command-line-args*)
      rows (read-maps (str dir "/runs.csv"))
      ok (count (filter healthy? rows))
      n (count rows)]
  (doseq [r rows]
    (println (format "    %-44s status=%s llm-calls=%s n-ret=%s n-cited=%s resp=%d -> %s"
                     (:question-id r) (:status r) (:llm-calls r)
                     (:n-retrieved r) (:n-cited r) (count (str (:response r)))
                     (if (healthy? r) "OK" "DEGRADED"))))
  (println (format "  smoke agent-health: %d/%d healthy" ok n))
  (System/exit (if (and (pos? n) (= ok n)) 0 1)))
