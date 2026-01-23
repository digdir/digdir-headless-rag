(ns digdir.docs.pipeline.telemetry
  "Shared telemetry infrastructure for document processing pipelines.

   This namespace consolidates telemetry state and handlers that were
   previously duplicated across all pipeline files. It provides:
   - Centralized telemetry aggregation
   - Signal window for recent events
   - Job state management (running/cancelled)"
  (:require [taoensso.telemere :as t]))

;; ============================================================================
;; Telemetry State
;; ============================================================================

;; Accumulates telemetry signals by ID for reporting.
;; Structure: {:id-counts {:signal-id count ...}}
(defonce !transient-telemetry-aggregate (atom {}))

;; Sliding window of recent telemetry signals (max 400).
;; Each signal is a map with :msg_, :inst, :end-inst, :error forced to strings.
(defonce !signal-window (atom '()))

;; ============================================================================
;; Aggregation
;; ============================================================================

(def telemetry-aggregator
  "Reducer function that counts signals by :id.
   Used with swap! on !transient-telemetry-aggregate."
  (fn [agg sig]
    (update-in agg [:id-counts (:id sig)] (fnil inc 0))))

;; ============================================================================
;; Signal Handler
;; ============================================================================

(defn setup-signal-handler!
  "Sets up a telemere signal handler with the given handler-id.
   The handler:
   - Aggregates signals into !transient-telemetry-aggregate
   - Maintains a sliding window of recent signals in !signal-window

   Call once at startup. Safe to call multiple times with same handler-id."
  [handler-id]
  (t/add-handler! handler-id
                  (fn
                    ([signal]
                     (swap! !transient-telemetry-aggregate telemetry-aggregator signal)
                     (swap! !signal-window #(take 400 (conj % (-> signal
                                                                  (update :msg_ force)
                                                                  (update :inst str)
                                                                  (update :end-inst str)
                                                                  (update :error str))))))
                    ([]))))

;; ============================================================================
;; Job State Management
;; ============================================================================

;; UI state: whether the start job button should be disabled.
(defonce !start-job-button-disabled? (atom false))

;; Holds the cancel function for the currently running job, or nil.
(defonce !job-canceller (atom nil))

;; Tracks number of concurrent store operations.
(defonce !store-threads (atom 0))

(defn start-job!
  "Marks a job as started with the given cancel function.
   Sets button state to disabled."
  [cancel-fn]
  (reset! !job-canceller cancel-fn)
  (reset! !start-job-button-disabled? true))

(defn stop-job!
  "Cancels the current job if running and resets state."
  []
  (when-let [cancel @!job-canceller]
    (cancel)
    (reset! !job-canceller nil)
    (reset! !start-job-button-disabled? false)
    true))

(defn job-running?
  "Returns true if a job is currently running."
  []
  (some? @!job-canceller))

;; ============================================================================
;; Telemetry Query Functions
;; ============================================================================

(defn get-signal-counts
  "Returns a map of signal-id -> count from aggregated telemetry."
  []
  (:id-counts @!transient-telemetry-aggregate {}))

(defn get-recent-signals
  "Returns the n most recent signals from the window."
  ([] @!signal-window)
  ([n] (take n @!signal-window)))

(defn reset-telemetry!
  "Resets all telemetry state. Use between pipeline runs."
  []
  (reset! !transient-telemetry-aggregate {})
  (reset! !signal-window '()))
