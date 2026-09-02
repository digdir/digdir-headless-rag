(ns digdir.eval.run-validity
  "Did the measurement happen at all? (#276)

   ## WHAT THIS IS FOR, AND WHAT IT IS NOT

   It answers exactly one question: **did this run reach an LLM?** A run that
   did not is not a bad result — it is *not a result*, and it must not be
   averaged with results.

   It is **NOT** for judging answer quality — a run that reached the model and
   answered badly is a real measurement and belongs in the mean. It is **NOT**
   a slow-run or performance detector; duration is not consulted, deliberately
   (see below). It is **NOT** an error detector: `:error` and `:timeout` runs
   already carry a status that excludes them. This exists for the one case
   those miss — a run that looks *complete*.

   ## Why it is needed

   #275 produced 16 runs that never reached an LLM. Every one was recorded
   `status :complete` with recall 0.0, and **the only thing that gave it away
   was `elapsed-ms 34`**. Nothing in the row said \"this never ran\"; it said
   the model answered and got everything wrong. Those are opposite
   conclusions and the artifact could not distinguish them — so a quality
   sweep would have recorded sixteen legitimate-looking zeros.

   This repository has already lost an arc to that shape: a judge scoring
   answers truncated to 800 characters manufactured a \"model ceiling\" that
   survived weeks, because nobody checked what the scorer actually saw.

   ## Why duration is not the signal

   `elapsed-ms 34` is what a human noticed, but it is the wrong thing to
   assert on: it is a threshold, and a threshold needs a magic number that is
   wrong on a fast machine and wrong again on a slow one. `llm-calls` is
   categorical — zero calls is zero calls on any hardware. The acceptance for
   #276 says so directly: distinguishable **without reading elapsed-ms**.

   ## Three outcomes, and the third one matters

     :measured     — reached an LLM; a real data point
     :no-llm-call  — reached none; not a data point
     :unknown      — the row predates the columns that would tell us

   `:unknown` exists so this cannot retroactively condemn history. The
   decomposition columns arrived in #254/#265, and a runs.csv written before
   them has no `llm-calls` header at all. Treating absent as zero would flag
   every archived sweep in `server/results/`, which is how a guard earns a
   reputation for noise and gets switched off before it reports anything
   true."
  (:require [clojure.string :as str]))

(defn- ->num
  "A CSV cell as a number, or nil. Rows arrive from `zipmap`ped CSV, so every
   value is a string; rows built in-process carry real numbers."
  [v]
  (cond
    (number? v) v
    (and (string? v) (not (str/blank? v)))
    #?(:clj (try (Double/parseDouble (str/trim v)) (catch Exception _ nil))
       :cljs (let [n (js/parseFloat v)] (when-not (js/isNaN n) n)))
    :else nil))

(defn- cell
  "A column, whether the row came back with KEYWORD or STRING keys.

   This is not defensive tidiness — it decides whether the guard ever fires.
   `dashboard/parse-rows` keywordises the header, but a runs.csv read back by
   anything else keeps string keys, and a keyword-only lookup would return
   `:unknown` for every row of a real artifact: green forever in precisely
   the place it is needed. Measured against the two #275 artifacts, a
   keyword-only version scored {:unknown 16} on both files while the same
   rows keyed by keyword scored {:no-llm-call 16}."
  [row k]
  (let [v (get row k)]
    (if (some? v) v (get row (name k)))))

(defn classify
  "One of :measured, :no-llm-call, :unknown for a run row.

   Keyed on `llm-calls` first because it is the most direct statement of the
   fact. `llm-ms` is the fallback, and it is NOT decorative: in both real
   #275 artifacts `llm-calls` is BLANK and `llm-ms` is \"0\", so a
   call-count-only check would have returned `:unknown` for all 32 rows and
   been counted as coverage. A row carrying neither is `:unknown` — absent is
   not zero."
  [row]
  (let [calls (->num (cell row :llm-calls))
        ms (->num (cell row :llm-ms))]
    (cond
      (some? calls) (if (zero? calls) :no-llm-call :measured)
      (some? ms) (if (zero? ms) :no-llm-call :measured)
      :else :unknown)))

(defn never-ran?
  "True when this row is positively known to have reached no LLM.

   False for `:unknown`, on purpose: an unproven suspicion must not be
   reported with the same confidence as a fact."
  [row]
  (= :no-llm-call (classify row)))

(defn partition-runs
  "{:measured [...] :no-llm-call [...] :unknown [...]} — every row placed.

   Returned as a partition rather than a filter so a caller cannot silently
   drop a category by forgetting it exists."
  [rows]
  (merge {:measured [] :no-llm-call [] :unknown []}
         (group-by classify rows)))
