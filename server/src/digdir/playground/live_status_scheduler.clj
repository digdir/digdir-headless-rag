(ns digdir.playground.live-status-scheduler
  "Best-effort live-status sidecar scheduling for Playground."
  (:require [digdir.playground.core :as core]
            [digdir.playground.status :as status]
            [taoensso.telemere :as t]))

(defonce !live-status-jobs (atom {}))
(defonce !live-status-metrics
  (atom {:started 0
         :completed 0
         :dropped 0
         :errors 0}))

(defn- bump-live-status-metric!
  [metric]
  (swap! !live-status-metrics update metric (fnil inc 0)))

(defn- publish-live-status!
  [execution-id artifact]
  (core/update-execution! execution-id {:live-status artifact}))

(defn- launch-live-status-job!
  [f]
  (future (f)))

(defn- process-live-status-job!
  [execution-id]
  (loop []
    (if-let [{:keys [pending-snapshot pending-snapshot-id]}
             (get @!live-status-jobs execution-id)]
      (let [started-at (System/currentTimeMillis)]
        (bump-live-status-metric! :started)
        (t/log! :debug [:playground.live-status/started
                        {:execution-id execution-id
                         :snapshot-id pending-snapshot-id}])
        (try
          (let [artifact (-> (status/synthesize-status-summary pending-snapshot)
                             (assoc :generation-duration-ms
                                    (- (System/currentTimeMillis) started-at)))
                stale? (not= pending-snapshot-id
                             (get-in @!live-status-jobs
                                     [execution-id :pending-snapshot-id]))
                artifact (assoc artifact :stale? stale?)]
            (if stale?
              (do
                (bump-live-status-metric! :dropped)
                (t/log! :debug [:playground.live-status/dropped
                                {:execution-id execution-id
                                 :snapshot-id pending-snapshot-id
                                 :current-snapshot-id (get-in @!live-status-jobs
                                                              [execution-id :pending-snapshot-id])}]))
              (do
                (bump-live-status-metric! :completed)
                (publish-live-status! execution-id artifact)
                (t/log! :debug [:playground.live-status/completed
                                {:execution-id execution-id
                                 :snapshot-id pending-snapshot-id
                                 :duration-ms (:generation-duration-ms artifact)}]))))
          (catch Exception e
            (bump-live-status-metric! :errors)
            (t/log! :warn [:playground/live-status-synthesis-failed
                           {:execution-id execution-id
                            :snapshot-id pending-snapshot-id
                            :error (.getMessage e)}])))
        (swap! !live-status-jobs
               (fn [jobs]
                 (if (= pending-snapshot-id
                        (get-in jobs [execution-id :pending-snapshot-id]))
                   (dissoc jobs execution-id)
                   jobs)))
        (recur))
      (swap! !live-status-jobs dissoc execution-id))))

(defn schedule-live-status-summary!
  [execution-id]
  (when-let [execution (core/get-execution execution-id)]
    (let [snapshot (status/execution-status-snapshot execution)
          snapshot-id (:snapshot-id snapshot)
          start-worker? (atom false)
          new-job {:running? true
                   :pending-snapshot snapshot
                   :pending-snapshot-id snapshot-id}]
      (swap! !live-status-jobs
             (fn [jobs]
               (if-let [job (get jobs execution-id)]
                 (if (:running? job)
                   (assoc jobs execution-id
                          (assoc job
                                 :pending-snapshot snapshot
                                 :pending-snapshot-id snapshot-id))
                   (do
                     (reset! start-worker? true)
                     (assoc jobs execution-id new-job)))
                 (do
                   (reset! start-worker? true)
                   (assoc jobs execution-id new-job)))))
      (when @start-worker?
        (launch-live-status-job! #(process-live-status-job! execution-id))))))
