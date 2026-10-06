(ns digdir.pipeline.ui.execution-display
  "Plain decisions the executions panel makes about what to show, kept out of
   the Electric component so they can be tested on the JVM.")

(defn failure-summary-shown?
  "Whether a run's `error-message` is shown: whenever it has one. A run that
   COMPLETED with refused or unprepared documents carries its failure summary
   there, and must be visibly marked, not only a failed run."
  [_status error-message]
  (boolean (seq error-message)))
