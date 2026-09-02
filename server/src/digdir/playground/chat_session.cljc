(ns digdir.playground.chat-session
  "Headless chat session state transitions for Playground chat.

   Consumes canonical execution events keyed by `:event`.
   Event constructors and shared stage metadata live in `digdir.skills.events`."
  (:require [digdir.playground.citations :as citations]
            [digdir.playground.diagnostics :as diagnostics]
            [digdir.playground.status :as status]
            [digdir.playground.timeline :as timeline]
            [digdir.skills.events :as skill-events]
            [clojure.string :as str]))

(defn- initial-flags
  []
  {:insufficient-context? false
   :fallback-used? false
   :retried? false})

(defn- resettable-session-state
  []
  {:diagnostics nil
   :events []
   :flags (initial-flags)
   :error nil})

(defn initial-state
  []
  {:status :idle
   :request nil
   :messages []
   :assistant nil
   :diagnostics nil
   :events []
   :flags (initial-flags)
   :error nil})

(defn- assistant-id
  [request-id]
  (str "assistant/" request-id))

(defn- find-assistant-index
  [messages id]
  (first (keep-indexed (fn [idx message]
                         (when (= id (:id message)) idx))
                       messages)))

(defn- update-assistant-message
  [state f]
  (if-let [assistant (:assistant state)]
    (let [id (:id assistant)
          idx (find-assistant-index (:messages state) id)]
      (if (some? idx)
        (let [updated (f (get-in state [:messages idx]))]
          (-> state
              (assoc :assistant updated)
              (assoc-in [:messages idx] updated)))
        state))
    state))

(defn start-request
  [state {:keys [request-id query user-message-id parent-message-id branch-index]}]
  (let [user-id (or user-message-id (str "user/" request-id))
        assistant {:id (assistant-id request-id)
                   :role :assistant
                   :status :streaming
                   :text ""
                   :parent-id user-id}
        user-message {:id user-id
                      :role :user
                      :status :complete
                      :text query
                      :parent-id parent-message-id
                      :branch-index branch-index}]
    (-> state
        (assoc :status :running)
        (assoc :request {:id request-id
                         :query query
                         :parent-id parent-message-id
                         :branch-index branch-index})
        (assoc :assistant assistant)
        (update :messages into [user-message assistant])
        (merge (resettable-session-state)))))

(defn append-stream
  [state {:keys [delta]}]
  (update-assistant-message state
                            (fn [assistant]
                              (update assistant :text str (or delta "")))))

(defn mark-insufficient-context
  [state]
  (-> state
      (assoc-in [:flags :insufficient-context?] true)
      (update-assistant-message (fn [assistant]
                                  (assoc assistant :insufficient-context? true)))))

(defn mark-fallback-used
  [state]
  (-> state
      (assoc-in [:flags :fallback-used?] true)
      (update-assistant-message (fn [assistant]
                                  (assoc assistant :fallback-used? true)))))

(defn mark-retry
  [state]
  (-> state
      (assoc-in [:flags :retried?] true)
      (assoc :status :running)
      (update-assistant-message (fn [assistant]
                                  (assoc assistant :status :retried)))))

(defn finalize-response
  [state {:keys [text diagnostics used-chunks docs-collection-name status clarification-request]}]
  (let [normalized (diagnostics/normalize-diagnostics
                    (cond-> diagnostics
                      status (assoc :status status)
                      clarification-request (assoc :clarification-request clarification-request)))
        bound-citations (citations/bind-citations (:citations normalized)
                                                  used-chunks
                                                  docs-collection-name)]
    (-> state
        (assoc :status (if (= :needs_clarification (:status normalized))
                         :needs-clarification
                         :complete))
        (assoc :diagnostics (assoc normalized :citations bound-citations))
        (update-assistant-message
         (fn [assistant]
           (-> assistant
               (assoc :status (if (= :needs_clarification (:status normalized))
                                :needs-clarification
                                :complete))
               (assoc :diagnostics (assoc normalized :citations bound-citations))
               (assoc :citations bound-citations)
               (assoc :clarification-request (:clarification-request normalized))
               (assoc :text (or text (:text assistant) ""))))))))

(defn fail-request
  [state {:keys [error]}]
  (-> state
      (assoc :status :error)
      (assoc :error error)
      (update-assistant-message
       (fn [assistant]
         (-> assistant
             (assoc :status :error)
             (assoc :error error))))))

(defn- event-kind
  [event]
  (:event event))

(defn- event-stage-label
  [event stage]
  (or (:label event)
      (skill-events/stage-label stage)
      (name (or stage :unknown))))

(defn- event-text
  [event variant]
  (let [t (event-kind event)]
    (case t
      :stage/started (str "Started " (event-stage-label event (:stage event)))
      :stage/completed (str "Completed " (event-stage-label event (:stage event))
                            (when-let [ms (:duration-ms event)]
                              (str " (" ms "ms)")))
      :step/skipped (str "Skipped " (skill-events/stage-label (:step-id event)))
      :step/defaulted (str "Defaulted " (skill-events/stage-label (:step-id event))
                           (when (and (= :timeline variant) (:duration-ms event))
                             (str " (" (:duration-ms event) "ms)")))
      :graph/completed (str "Completed graph"
                            (when (and (= :timeline variant) (:duration-ms event))
                              (str " (" (:duration-ms event) "ms)")))
      :tool/call (if (= :timeline variant)
                   (str "Tool call: " (get-in event [:tool-call :tool] "unknown"))
                   (str "Calling " (get-in event [:tool-call :tool] "tool")))
      :tool/result (if (= :timeline variant)
                     (str "Tool result: " (get-in event [:tool-result :tool] "unknown")
                          (when-let [summary (get-in event [:tool-result :summary])]
                            (str " - " summary)))
                     (or (get-in event [:tool-result :summary])
                         (str "Completed " (get-in event [:tool-result :tool] "tool"))))
      :warning/raised (if (= :timeline variant)
                        (str "Warning: " (or (get-in event [:warning :message]) "Unknown warning"))
                        (or (get-in event [:warning :message]) "Warning raised"))
      :request/failed (if (= :timeline variant)
                        (str "Error: " (or (:error event) "Request failed"))
                        (or (:error event) "Request failed"))
      :response/chunk (if (= :timeline variant) "Response updated" "Generating response")
      :response/finalized (if (= :needs_clarification (:status event))
                            "Clarification requested"
                            (if (= :timeline variant) "Response finalized" "Finalizing response"))
      :request/started "Request started"
      :agent/iteration-started (str "Agent iteration " (or (:iteration event) "?") " started")
      :agent/turn-completed (str "Agent iteration " (or (:iteration event) "?") " completed"
                                 (when (seq (:tool-calls event))
                                   (str " (" (count (:tool-calls event)) " tool calls)")))
      :agent/finalized (str "Agent finalized (iteration " (or (:iteration event) "?") ")")
      nil)))

(declare event->timeline-entry)

(defn- project-execution-state
  [events]
  (let [events (vec (or events []))
        latest-stage-start (last (filter #(= :stage/started (event-kind %)) events))
        latest-event (last events)
        current-stage (:stage latest-stage-start)
        current-stage-label (when current-stage
                              (event-stage-label latest-stage-start current-stage))
        stage-history (reduce (fn [result event]
                                (case (event-kind event)
                                  :stage/started (conj result {:stage (:stage event)
                                                               :label (:label event)
                                                               :status :started
                                                               :ts (:ts event)})
                                  :stage/completed (conj result {:stage (:stage event)
                                                                 :label (:label event)
                                                                 :duration-ms (:duration-ms event)
                                                                 :status :completed
                                                                 :ts (:ts event)})
                                  result))
                              []
                              events)
        tool-timeline (reduce (fn [result event]
                                (if (= :tool/call (event-kind event))
                                  (timeline/append-tool-call result (:tool-call event))
                                  result))
                              []
                              events)
        tool-results (reduce (fn [result event]
                               (if (= :tool/result (event-kind event))
                                 (conj result (:tool-result event))
                                 result))
                             []
                             events)
        warnings (reduce (fn [result event]
                           (if (= :warning/raised (event-kind event))
                             (conj result (:warning event))
                             result))
                         []
                         events)
        timeline (->> events
                      (map-indexed event->timeline-entry)
                      (remove nil?)
                      vec)
        progress {:current-stage (or current-stage :init)
                  :current-stage-label (or current-stage-label "Initializing")
                  :stage-count (count (filter #(= :stage/started (event-kind %)) events))
                  :tool-call-count (count tool-timeline)
                  :tool-result-count (count tool-results)
                  :warning-count (count warnings)}
        summary (or (some-> latest-event (event-text :summary))
                    (:current-stage-label progress)
                    "Working")]
    {:stage-history stage-history
     :tool-timeline tool-timeline
     :tool-results tool-results
     :warnings warnings
     :timeline timeline
     :summary summary
     :progress progress}))

(defn- record-event
  [state event]
  (let [updated-events (conj (vec (:events state)) event)]
    (assoc state :events updated-events)))

(defn apply-event
  [state event]
  (record-event
    (case (event-kind event)
      :request/started (start-request state event)
      :response/chunk (append-stream state event)
      :response/insufficient-context (mark-insufficient-context state)
      :response/fallback-used (mark-fallback-used state)
      :request/retry (mark-retry state)
      :response/finalized (finalize-response state event)
      :request/failed (fail-request state event)
      state)
    event))

(defn replay
  "Replay events through the headless chat-session reducer."
  [events]
  (reduce apply-event (initial-state) events))

(defn find-children
  "Find all messages that have the given message as parent."
  [messages parent-id]
  (if parent-id
    (filter #(= parent-id (get-in % [:message/parent-message :message/id])) messages)
    ;; Root messages: user messages with no parent.
    (filter #(and (nil? (:message/parent-message %))
                  (= :user (:message/role %))) messages)))

(defn has-branches?
  "Check if a message has multiple children (is a branch point)."
  [messages msg-id]
  (> (count (find-children messages msg-id)) 1))

(defn get-visible-messages
  "Filter messages to show only the active branch path.
   Returns messages in the currently selected branch."
  [messages active-branch-path]
  (if (empty? messages)
    []
    (loop [result []
           current-parent-id nil]
      (let [children (find-children messages current-parent-id)
            selected-child (if (> (count children) 1)
                             (let [selected-idx (get active-branch-path current-parent-id 0)]
                               (nth (sort-by :message/created children)
                                    (min selected-idx (dec (count children)))))
                             (first (sort-by :message/created children)))]
        (if selected-child
          (recur (conj result selected-child) (:message/id selected-child))
          result)))))

(defn stream-step-state
  "Build step status model for the current execution stage."
  [current-stage]
  (let [steps (skill-events/flow-steps current-stage)
        stage-index (zipmap (map :stage steps) (range))]
    (mapv (fn [{:keys [label stage] :as step}]
            (let [current? (= current-stage stage)
                  current-idx (get stage-index current-stage -1)
                  step-idx (get stage-index stage -1)
                  past? (and (>= current-idx 0)
                             (>= step-idx 0)
                             (< step-idx current-idx))]
            (assoc step
                   :label label
                   :current? current?
                   :past? past?)))
          steps)))

(defn- split-headline-detail
  "Split multi-line event text into a one-line headline and optional detail body.
   Keeps simple bullets single-line in the UI while letting verbose tool results
   hide their body behind an expand toggle."
  [text]
  (when text
    (let [nl (str/index-of text "\n")]
      (if nl
        (let [headline (str/trimr (subs text 0 nl))
              rest (str/triml (subs text (inc nl)))]
          [headline (when-not (str/blank? rest) rest)])
        [text nil]))))

(defn- event->timeline-entry
  [idx event]
  (let [t (event-kind event)
        [headline detail] (split-headline-detail (event-text event :timeline))]
    (when headline
      {:id idx
       :type t
       :stage (:stage event)
       :text headline
       :detail detail
       :ts (:ts event)})))

(defn- compact-live-stage-timing
  [event]
  (case (event-kind event)
    :stage/completed {:stage (:stage event)
                      :duration-ms (:duration-ms event)
                      :status :ok
                      :detail (:label event)}
    :step/skipped {:step-id (:step-id event)
                   :skill-id (:skill-id event)
                   :duration-ms (or (:duration-ms event) 0)
                   :status :skipped}
    :step/defaulted {:step-id (:step-id event)
                     :skill-id (:skill-id event)
                     :duration-ms (:duration-ms event)
                     :status :defaulted}
    :step/failed {:step-id (:step-id event)
                  :skill-id (:skill-id event)
                  :duration-ms (:duration-ms event)
                  :status :error
                  :detail (:error event)}
    :graph/completed {:step-id :graph
                      :duration-ms (:duration-ms event)
                      :status :ok
                      :detail (str (or (:steps-executed event) 0) " steps")}
    nil))

(defn- execution-events->stage-timings
  [events]
  (->> (or events [])
       (keep compact-live-stage-timing)
       vec))

(defn- now-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- execution-elapsed-ms
  [execution events]
  (let [events (vec (or events []))
        start-ts (or (:ts (first events))
                     (when (number? (:started-at execution))
                       (:started-at execution)))
        end-ts (cond
                 (number? (:completed-at execution))
                 (:completed-at execution)

                 (= :running (:status execution))
                 (now-ms)

                 :else
                 (or (:ts (last events))
                     start-ts))]
    (when (and start-ts end-ts)
      (max 0 (- end-ts start-ts)))))

(defn execution-events->live-view
  "Project raw execution events into a render-friendly live status model."
  [events]
  (let [events (vec (or events []))
        {:keys [progress] :as projection} (project-execution-state events)]
    (assoc projection
           :has-events? (seq events)
           :current-stage (:current-stage progress)
           :current-stage-label (:current-stage-label progress)
           :tool-call-count (:tool-call-count progress)
           :tool-result-count (:tool-result-count progress)
           :warning-count (:warning-count progress))))

(defn execution-stream-view
  "Normalize execution atom payload into a render-ready streaming state."
  [execution]
  (if (nil? execution)
    {:has-execution? false
     :running? false
     :stage :init
     :steps (stream-step-state :init)
     :content ""
     :waiting? false
     :error nil}
    (let [status (:status execution)
          running? (= :running status)
          stage (or (:stage execution) :init)
          content (or (:streaming-content execution) "")
          waiting? (and running? (str/blank? content))
          events (vec (or (:events execution) []))
          live-view (execution-events->live-view events)
          live-view (status/resolve-live-status execution live-view)
          execution-stage-timings (execution-events->stage-timings events)
          agent-stage-timings (vec (or (:live-agent-stage-timings execution) []))
          execution-timing-entries (diagnostics/execution-stage-timing-entries
                                    {:execution-stage-timings execution-stage-timings
                                     :agent-stage-timings agent-stage-timings})
          view-stage (if (:has-events? live-view)
                       (or (:current-stage live-view) stage)
                       stage)
          elapsed-ms (execution-elapsed-ms execution events)]
      {:has-execution? true
       :running? running?
       :stage view-stage
       :steps (stream-step-state view-stage)
       :content content
       :waiting? waiting?
       :error (:error execution)
       :events events
       :elapsed-ms elapsed-ms
       :live-summary (:live-summary live-view)
       :live-status (:live-status live-view)
       :live-status-source (:live-status-source live-view)
       :live-status-stale? (:live-status-stale? live-view)
       :timeline (:timeline live-view)
       :execution-stage-timings execution-stage-timings
       :execution-timing-entries execution-timing-entries
       :tool-call-count (:tool-call-count live-view)
       :tool-result-count (:tool-result-count live-view)
       :warning-count (:warning-count live-view)
       :action-trace (vec (or (:action-trace execution) []))
       :live-agent-trace (:live-agent-trace execution)
       :live-agent-stage-timings agent-stage-timings
       :live-thinking (:live-thinking execution)})))
