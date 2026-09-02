(ns digdir.sweep.v3-cites
  "Load the v3-baseline cited chunk set from
   `plans/in-progress/target-optimal-baseline-v3/` and provide
   per-question scoring helpers for sweep runs.

   The cite set is the ground-truth target for slice-3 sweep
   validation: a retrieval config's hit rate is the fraction of
   v3-cited chunks that appear in its top-N results. See
   `plans/proposed/retrieval-dynamic-filter-generation-experiment.md`."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private default-v3-dir
  ;; The repo-root-relative path is the canonical home of the v3 trail.
  ;; Resolution lives at the caller; we expect a usable java.io.File.
  "plans/in-progress/target-optimal-baseline-v3")

(def ^:private question-file-pattern
  #"^(0[1-7])-.*\.md$")

(def ^:private chunk-id-pattern
  ;; 12-char hex ids in backticks. Matches the convention used
  ;; across the v3 trail (`ea7de904e1aa`, etc.).
  #"`([0-9a-f]{12})`")

(def ^:private cited-chunks-section-pattern
  ;; Section between "## Cited chunks" and the next "## " heading
  ;; (or EOF). Multi-line + dot-matches-all flags.
  #"(?ms)^## Cited chunks\s*$(.*?)(?=^## |\z)")

(def ^:private h1-question-pattern
  ;; "# Q1 v3 — What is..." — captures the question text after the dash.
  ;; Tolerates both `—` (em dash) and `-`, and `Q1` / `Q1 v3` / `Q1 v3 —`.
  #"(?m)^#\s+Q\d+\s+v\d+\s+[—-]\s+(.+)$")

(defn- extract-cited-chunks
  "Parse the 'Cited chunks' section out of a v3 question file and
   return a set of chunk_ids referenced there. Returns #{} when the
   section is missing or contains no chunk ids."
  [content]
  (or (when-let [match (re-find cited-chunks-section-pattern content)]
        (let [section (second match)]
          (->> (re-seq chunk-id-pattern section)
               (map second)
               set)))
      #{}))

(defn- extract-question-text
  "Parse the H1 question text. Returns nil when the H1 doesn't match
   the expected v3 format."
  [content]
  (some-> (re-find h1-question-pattern content)
          second
          str/trim))

(defn load-v3-cites
  "Load the v3 cited-chunks ground-truth from the markdown trail.

   Returns a vector (ordered by question number) of maps:
     {:question-num \"01\"
      :file-name \"01-dialogporten-definition.md\"
      :question  \"What is Dialogporten and what problem does it solve?\"
      :cites     #{\"ea7de904e1aa\" \"a233d1c22ebe\" \"b8ddca7bace0\"}}

   Throws if the directory doesn't exist. Silently includes a question
   even if its file has no Cited-chunks section (returns cites = #{}),
   so callers can detect parse failures by inspecting cite counts."
  ([] (load-v3-cites default-v3-dir))
  ([dir]
   (let [dir-file (io/file dir)]
     (when-not (.isDirectory dir-file)
       (throw (ex-info "v3-cites dir not found"
                       {:dir dir
                        :resolved (.getAbsolutePath dir-file)})))
     (->> (.listFiles dir-file)
          (filter #(re-matches question-file-pattern (.getName %)))
          (sort-by #(.getName %))
          (mapv (fn [file]
                  (let [content (slurp file)
                        name (.getName file)
                        [_ qnum] (re-find question-file-pattern name)]
                    {:question-num qnum
                     :file-name name
                     :question (extract-question-text content)
                     :cites (extract-cited-chunks content)})))))))

(defn score-retrieval
  "Score a sequence of retrieved chunk_ids against a v3 cite set.

   Arguments:
     v3-cites      — set of chunk_ids expected (the ground truth for one question)
     retrieved     — sequence of retrieved chunk_ids, ordered top-first
     top-k         — only the first top-k retrieved are considered hits

   Returns:
     {:cites set
      :retrieved-top-k vec
      :hits set
      :misses set
      :hit-count long
      :total long
      :hit-rate double                       — hits/total, 0.0 when total=0
      :hit-positions {chunk-id -> 1-based-rank}}"
  [v3-cites retrieved top-k]
  (let [retrieved-vec (vec (take top-k retrieved))
        retrieved-set (set retrieved-vec)
        hits (set/intersection v3-cites retrieved-set)
        misses (set/difference v3-cites retrieved-set)
        index-map (into {} (map-indexed (fn [i id] [id (inc i)]) retrieved-vec))]
    {:cites v3-cites
     :retrieved-top-k retrieved-vec
     :hits hits
     :misses misses
     :hit-count (count hits)
     :total (count v3-cites)
     :hit-rate (if (zero? (count v3-cites))
                 0.0
                 (double (/ (count hits) (count v3-cites))))
     :hit-positions (select-keys index-map (seq hits))}))

(defn aggregate-scores
  "Aggregate a sequence of per-question scoring maps into overall stats.

   Returns:
     {:total-cites long
      :total-hits long
      :overall-hit-rate double
      :per-question vec    — slimmed-down per-question summary}"
  [per-question-scores]
  (let [total-cites (reduce + (map :total per-question-scores))
        total-hits (reduce + (map :hit-count per-question-scores))]
    {:total-cites total-cites
     :total-hits total-hits
     :overall-hit-rate (if (zero? total-cites)
                         0.0
                         (double (/ total-hits total-cites)))
     :per-question (mapv #(select-keys % [:hit-count :total :hit-rate
                                          :hit-positions :misses])
                         per-question-scores)}))
