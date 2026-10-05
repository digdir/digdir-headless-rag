(ns digdir.pipeline.ui.execution-summary-test
  "The executions panel shows a run's failure summary whenever the run has one:
   a run that COMPLETED with refused documents is marked, not only a failed run.

   The predicate lives in `digdir.pipeline.ui.execution-display` (the panel
   itself cannot be loaded on the JVM). It is resolved at run time, so this
   namespace loads, and reports red, where the predicate is absent."
  (:require [clojure.test :refer [deftest is]]))

(defn- shown? [status error-message]
  (if-let [f (try (requiring-resolve 'digdir.pipeline.ui.execution-display/failure-summary-shown?)
                  (catch Exception _ nil))]
    (f status error-message)
    ::absent))

(deftest a-completed-run-with-failures-shows-its-summary
  (is (true? (shown? :completed "completed with 1 of 3 documents failed: ..."))))

(deftest a-failed-run-shows-its-message
  (is (true? (shown? :failed "failed: the document-failure budget (10) was reached: ..."))))

(deftest a-clean-run-shows-nothing
  (is (false? (shown? :completed nil))))
