(ns digdir.sweep.dashboard-summarize-test
  "#276: an aggregate must not quietly average a run that never happened.

   #275 produced 16 runs that reached no LLM, each recorded `:complete` with
   recall 0.0. `summarize` filtered on status alone, so all 16 landed in the
   mean — and a sweep would have reported a model that answered everything
   wrong, which is the OPPOSITE conclusion from the true one.

   These drive the real `summarize` (private, reached through its var) rather
   than a reimplementation of its arithmetic, because the defect was never in
   the arithmetic — it was in WHICH ROWS REACHED IT."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.sweep.dashboard :as dashboard]))

(def ^:private summarize #'dashboard/summarize)

(defn- run
  "A CSV-shaped row: every value a string, as `parse-rows` produces."
  [{:keys [recall llm-calls] :or {llm-calls "2"}}]
  (cond-> {:status "complete" :recall-at-20 recall :recall-at-10 recall :response "an answer"}
    llm-calls (assoc :llm-calls llm-calls)))

(deftest a-run-that-never-reached-an-llm-is-excluded-from-the-mean
  (let [s (summarize [(run {:recall "1.0"})
                      (run {:recall "0.0" :llm-calls "0"})])]
    (is (= "1.00" (:recall20 s))
        "averaging the never-ran zero in would report 0.50 — a model that
         half-failed, rather than one measurement and one non-event")
    (is (= 1 (:never-ran s)) "and the exclusion is counted, not silent")))

(deftest sixteen-never-ran-runs-do-not-become-sixteen-zeros
  ;; The #275 shape at its real size.
  (let [s (summarize (into [(run {:recall "0.8"})]
                           (repeat 16 (run {:recall "0.0" :llm-calls "0"}))))]
    (is (= "0.80" (:recall20 s)))
    (is (= 16 (:never-ran s)))))

(deftest BOUNDARY-a-real-run-that-answered-badly-still-counts
  ;; This is NOT an answer-quality judge. A run that reached the model and
  ;; scored zero is a measurement and belongs in the mean; excluding it would
  ;; silently flatter every sweep.
  (let [s (summarize [(run {:recall "1.0"}) (run {:recall "0.0" :llm-calls "3"})])]
    (is (= "0.50" (:recall20 s)))
    (is (= 0 (:never-ran s)))))

(deftest BOUNDARY-rows-from-before-the-columns-existed-are-not-excluded
  ;; A runs.csv written before #254/#265 has no llm-calls header at all.
  ;; Treating absent as zero would empty the mean of every archived sweep.
  (let [s (summarize [{:status "complete" :recall-at-20 "1.0" :recall-at-10 "1.0" :response "a"}
                      {:status "complete" :recall-at-20 "0.0" :recall-at-10 "0.0" :response "b"}])]
    (is (= "0.50" (:recall20 s)) "historical rows still average normally")
    (is (= 0 (:never-ran s)))))

(deftest errors-are-still-counted-separately-from-never-ran
  (testing "the two exclusions do not collapse into one another"
    (let [s (summarize [(run {:recall "1.0"})
                        (run {:recall "0.0" :llm-calls "0"})
                        {:status "error" :recall-at-20 "" :response ""}])]
      (is (= 1 (:errors s)) "status-based exclusion, unchanged")
      (is (= 1 (:never-ran s)) "measurement-based exclusion, new")
      (is (= "1.00" (:recall20 s))))))

;; ---------------------------------------------------------------------------
;; #289 — a wrong-language answer is a broken run, not a bad one
;; ---------------------------------------------------------------------------

(defn- lang-run
  [{:keys [recall q a]}]
  {:status "complete" :recall-at-20 recall :recall-at-10 recall
   :response "an answer" :llm-calls "2"
   :question-language q :answer-language a})

(deftest an-answer-in-the-wrong-language-is-excluded-from-the-mean
  ;; A fluent, correct, well-cited answer in the wrong language scores on its
  ;; merits against the reference. Averaging it in reports a model that
  ;; answered well, when what happened is that it answered in the wrong
  ;; language and the eval could not tell.
  ;; The wrong-language row carries a DIFFERENT recall from the good one on
  ;; purpose. With both at 1.0 the mean is identical whether or not the row is
  ;; excluded, so the assertion could not fail — which a sabotage proved
  ;; before this comment existed.
  (let [s (summarize [(lang-run {:recall "1.0" :q "nb" :a "nb"})
                      (lang-run {:recall "0.0" :q "nb" :a "en"})])]
    (is (= 1 (:wrong-language s)) "counted, so the exclusion is visible")
    (is (= "1.00" (:recall20 s))
        "including it would report 0.50 — a model that half-failed, when what
         happened is that one answer was in the wrong language entirely")))

(deftest an-english-question-answered-in-english-still-counts
  ;; 39% of the golden set is English (measured 2026-08-24). Treating English
  ;; as wrong by default would silently drop two questions in five.
  (let [s (summarize [(lang-run {:recall "1.0" :q "en" :a "en"})
                      (lang-run {:recall "0.0" :q "en" :a "en"})])]
    (is (= 0 (:wrong-language s)))
    (is (= "0.50" (:recall20 s)))))

(deftest an-undetected-language-does-not-exclude-a-run
  ;; Archived runs have no language columns at all. Excluding them would empty
  ;; every historical mean.
  (let [s (summarize [{:status "complete" :recall-at-20 "1.0" :recall-at-10 "1.0"
                       :response "a" :llm-calls "2"}
                      {:status "complete" :recall-at-20 "0.0" :recall-at-10 "0.0"
                       :response "b" :llm-calls "2"}])]
    (is (= 0 (:wrong-language s)))
    (is (= "0.50" (:recall20 s)))))
