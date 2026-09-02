(ns digdir.playground.status
  "Optional live-status snapshot and synthesis helpers for Playground."
  (:require [digdir.skills.events :as skill-events]))

(defn- event-kind
  [event]
  (:event event))

(defn- event-stage-label
  [event stage]
  (or (:label event)
      (skill-events/stage-label stage)
      (name (or stage :unknown))))

(defn- event-text
  [event]
  (let [t (event-kind event)]
    (case t
      :stage/started (str "Started " (event-stage-label event (:stage event)))
      :stage/completed (str "Completed " (event-stage-label event (:stage event))
                            (when-let [ms (:duration-ms event)]
                              (str " (" ms "ms)")))
      :tool/call (str "Calling " (get-in event [:tool-call :tool] "tool"))
      :tool/result (or (get-in event [:tool-result :summary])
                       (str "Completed " (get-in event [:tool-result :tool] "tool")))
      :warning/raised (or (get-in event [:warning :message]) "Warning raised")
      :request/started "Request started"
      :request/failed (or (:error event) "Request failed")
      :response/chunk "Generating response"
      :response/finalized "Finalizing response"
      nil)))

(defn- latest-stage-start
  [events]
  (last (filter #(= :stage/started (event-kind %)) events)))

(defn- latest-tool-event
  [events]
  (or (last (filter #(= :tool/result (event-kind %)) events))
      (last (filter #(= :tool/call (event-kind %)) events))
      (last (filter #(= :warning/raised (event-kind %)) events))))

(defn- now-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn execution-status-snapshot
  "Build a compact, immutable snapshot for optional live-status synthesis."
  [execution]
  (let [execution (or execution {})
        events (vec (or (:events execution) []))
        latest-event (last events)
        latest-stage (latest-stage-start events)
        current-stage (or (:stage execution)
                          (:stage latest-stage)
                          :init)
        latest-event-text (some-> latest-event event-text)
        current-stage-label (or (when latest-stage
                                  (event-stage-label latest-stage
                                                     (:stage latest-stage)))
                                (skill-events/stage-label current-stage)
                                "Initializing")
        summary (or latest-event-text
                    current-stage-label
                    "Working")
        summary-source (cond
                         latest-event-text :event
                         (some? current-stage) :stage
                         :else :working)]
    {:snapshot-id (count events)
     :captured-at (or (:completed-at execution)
                      (:started-at execution))
     :current-stage current-stage
     :current-stage-label current-stage-label
     :summary summary
     :summary-source summary-source
     :tool-narrative (some-> (latest-tool-event events) event-text)
     :tool-call-count (count (filter #(= :tool/call (event-kind %)) events))
     :tool-result-count (count (filter #(= :tool/result (event-kind %)) events))
     :warning-count (count (filter #(= :warning/raised (event-kind %)) events))
     :event-count (count events)}))

(defn synthesize-status-summary
  "Create the best-effort status artifact from a stable snapshot."
  [snapshot]
  (let [snapshot (or snapshot {})
        started-at (now-ms)
        summary (or (:summary snapshot)
                    (:current-stage-label snapshot)
                    "Working")]
    {:artifact-type :playground/live-status-summary
     :source-snapshot-id (:snapshot-id snapshot)
     :captured-at (:captured-at snapshot)
     :generated-at started-at
     :current-stage (:current-stage snapshot)
     :current-stage-label (:current-stage-label snapshot)
     :summary summary
     :tool-narrative (:tool-narrative snapshot)
     :stale? false
     :generation-duration-ms 0
     :fallback-used? (not= :event (:summary-source snapshot))
     :summary-source (:summary-source snapshot)}))

(defn fresh-status-artifact?
  "Check whether a synthesized status artifact still matches the current snapshot."
  [snapshot artifact]
  (and snapshot
       artifact
       (= (:snapshot-id snapshot)
          (:source-snapshot-id artifact))))

(defn resolve-live-status
  "Merge event-derived live state with an optional synthesized status artifact.

   The returned map preserves existing live-view keys and adds explicit source
   metadata so callers can prefer fresh synthesized status without blocking on it."
  [execution live-view]
  (let [execution (or execution {})
        live-view (or live-view {})
        snapshot (execution-status-snapshot execution)
        artifact (:live-status execution)
        fresh? (fresh-status-artifact? snapshot artifact)
        selected-artifact (when artifact
                            (assoc artifact :stale? (not fresh?)))
        live-summary (or (when fresh?
                           (:summary artifact))
                         (:summary live-view)
                         (:current-stage-label live-view)
                         "Working")]
    (assoc live-view
           :live-summary live-summary
           :live-status selected-artifact
           :live-status-source (cond
                                 fresh? :synthesized
                                 artifact :stale
                                 :else :event-derived)
           :live-status-stale? (boolean (and artifact (not fresh?)))
           :live-status-snapshot snapshot)))
