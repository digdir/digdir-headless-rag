(ns repair-util
  "Repair-loop helpers for the weekend queue. A flaky endpoint corrupts runs
   MID-STAGE: they come back status=complete but aborted (≤1 LLM call, ~60 chars).
   These helpers let the bash loop re-run ONLY the damaged (config×question) combos
   until every combo has N healthy runs, then merge them into one clean dir to judge.

   degraded run = endpoint-abort signature (NOT a legit wrong answer):
     status≠complete  OR  empty?=true  OR  llm-calls<2  OR  response<200 chars.
   (We deliberately do NOT require n-cited>0 here — a substantive but uncited answer
    is a real model output, not endpoint damage, and must not loop forever.)

   Modes:
     shortq    N <round0-dir> <dir>...            -> prints question-ids still short of N healthy
     emit-repair <base-matrix> <out-matrix> <qid>... -> writes a repair matrix filtered to those qids
     merge     N <out-dir> <round0-dir> <dir>...   -> writes <out-dir>/runs.csv (≤N healthy per combo) + matrix.edn
     report    N <dir>...                          -> prints healthy/short summary")

(require '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])

;; ---- CSV (quote/newline aware, mirrors runner/parse-csv) ----
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

(defn read-rows [dir]
  (let [p (str dir "/runs.csv")]
    (if (.exists (io/file p))
      (let [rows (remove #(= % [""]) (parse-csv (slurp p)))
            header (mapv keyword (first rows))]
        {:header header :maps (mapv #(zipmap header %) (rest rows))})
      {:header nil :maps []})))

(defn csv-field [s]
  (let [s (str s)]
    (if (re-find #"[\",\n\r]" s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn write-rows [path header maps]
  (spit path (str (str/join "," (map name header)) "\n"
                  (str/join "\n" (map (fn [m] (str/join "," (map #(csv-field (get m %)) header))) maps))
                  "\n")))

(defn num [s] (try (Double/parseDouble (str s)) (catch Exception _ nil)))

(defn degraded? [r]
  (or (not= "complete" (:status r))
      (= "true" (:empty? r))
      (< (or (num (:llm-calls r)) 0) 2)
      (< (count (str (:response r))) 200)))
(def healthy? (complement degraded?))

(defn combo [r] [(:config-id r) (:question-id r)])

(let [[mode & args] *command-line-args*]
  (case mode
    "shortq"   ; writes short question-ids (one per line) to <out-file>; prints the count
    (let [[n outf round0 & dirs] args
          n (long (Double/parseDouble n))
          universe (->> (:maps (read-rows round0)) (map combo) distinct)
          all (mapcat (comp :maps read-rows) (cons round0 dirs))
          healthy-by (->> all (filter healthy?) (group-by combo))
          short-q (->> universe (filter #(< (count (get healthy-by % [])) n)) (map second) distinct)]
      (spit outf (str/join "\n" short-q))
      (println (count short-q)))

    "report"
    (let [[n & dirs] args
          n (long (Double/parseDouble n))
          all (mapcat (comp :maps read-rows) dirs)
          universe (->> (:maps (read-rows (first dirs))) (map combo) distinct)
          healthy-by (->> all (filter healthy?) (group-by combo))
          satisfied (count (filter #(>= (count (get healthy-by % [])) n) universe))]
      (println (format "combos %d | satisfied(>=%d healthy) %d | short %d | total healthy rows %d/%d"
                       (count universe) n satisfied (- (count universe) satisfied)
                       (count (filter healthy? all)) (count all))))

    "emit-repair"   ; reads short question-ids (one per line) from <qids-file>
    (let [[base out qids-file] args
          qids (->> (str/split-lines (slurp qids-file)) (remove str/blank?) set)
          m (assoc (edn/read-string (slurp base)) :question-filter {:ids qids} :judge? false)]
      (spit out (binding [*print-namespace-maps* true] (pr-str m)))
      (println (str "wrote " out " (" (count qids) " questions)")))

    "merge"
    (let [[n out round0 & dirs] args
          n (long (Double/parseDouble n))
          {:keys [header]} (read-rows round0)
          universe (->> (:maps (read-rows round0)) (map combo) distinct)   ; FULL universe from round0
          all (mapcat (comp :maps read-rows) (cons round0 dirs))
          by (->> all (filter healthy?) (group-by combo))
          chosen (mapcat (fn [[_ rs]] (take n rs)) by)
          kept (fn [k] (min n (count (get by k []))))
          short (remove #(>= (kept %) n) universe)]
      (io/make-parents (str out "/runs.csv"))
      (write-rows (str out "/runs.csv") header chosen)
      (when (.exists (io/file (str round0 "/matrix.edn")))
        (io/copy (io/file (str round0 "/matrix.edn")) (io/file (str out "/matrix.edn"))))
      (println (format "merged %d healthy rows -> %s/runs.csv" (count chosen) out))
      (println (format "coverage: %d/%d combos have %d healthy run(s); %d short%s"
                       (- (count universe) (count short)) (count universe) n (count short)
                       (if (seq short) (str " -> short qids: " (str/join " " (distinct (map second short)))) ""))))

    (do (println "unknown mode:" mode) (System/exit 2))))
