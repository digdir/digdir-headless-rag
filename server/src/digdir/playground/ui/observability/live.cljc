(ns digdir.playground.ui.observability.live
  "Live execution controls and status components for Playground observability."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [digdir.playground.diagnostics :as diagnostics]))

(defn- format-elapsed
  [elapsed-ms]
  (when (pos? (or elapsed-ms 0))
    (str (.toFixed (/ elapsed-ms 1000.0) 1) "s")))

(defn- first-line
  [s]
  (when (string? s)
    (let [trimmed (str/triml s)
          nl (str/index-of trimmed "\n")]
      (-> (if nl (subs trimmed 0 nl) trimmed)
          (str/trimr)))))

(defn- timing-source-style
  [source]
  {:padding "0.08rem 0.32rem"
   :border-radius "999px"
   :font-size "0.58rem"
   :font-weight "700"
   :text-transform "uppercase"
   :letter-spacing "0.02em"
   :background (case source
                 :agent "#ede9fe"
                 :graph "#dbeafe"
                 "#f3f4f6")
   :color (case source
            :agent "#5b21b6"
            :graph "#1d4ed8"
            "#374151")})

(defn- timing-status-style
  [status]
  {:padding "0.08rem 0.32rem"
   :border-radius "999px"
   :font-size "0.58rem"
   :font-weight "700"
   :text-transform "uppercase"
   :letter-spacing "0.02em"
   :background (case status
                 :ok "#dcfce7"
                 :defaulted "#fef3c7"
                 :skipped "#e5e7eb"
                 :error "#fee2e2"
                 "#f3f4f6")
   :color (case status
            :ok "#166534"
            :defaulted "#92400e"
            :skipped "#4b5563"
            :error "#991b1b"
            "#374151")})

(e/defn StageTimingList
  [entries]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.35rem"}})
    (e/for [entry (e/diff-by :id entries)]
      (let [{:keys [source label detail duration-ms status]} entry]
        (dom/div
         (dom/props {:style {:display "flex"
                             :align-items "flex-start"
                             :justify-content "space-between"
                             :gap "0.5rem"
                             :padding "0.42rem 0.5rem"
                             :background "#f8fafc"
                             :border "1px solid #e5e7eb"
                             :border-radius "6px"}})
         (dom/div
          (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.18rem" :min-width "0"}})
          (dom/div
           (dom/props {:style {:display "flex" :align-items "center" :gap "0.35rem" :flex-wrap "wrap"}})
           (dom/span (dom/props {:style (timing-source-style source)}) (dom/text (name source)))
           (when status
             (dom/span (dom/props {:style (timing-status-style status)}) (dom/text (name status))))
           (dom/span
            (dom/props {:style {:font-size "0.72rem"
                                :font-weight "600"
                                :color "#111827"
                                :font-family "monospace"}})
            (dom/text label)))
          (when-not (str/blank? (or detail ""))
            (dom/div
             (dom/props {:style {:font-size "0.67rem"
                                 :color "#6b7280"
                                 :white-space "pre-wrap"}})
             (dom/text detail))))
         (dom/span
          (dom/props {:style {:font-size "0.68rem"
                              :font-weight "700"
                              :font-family "monospace"
                              :color "#1f2937"
                              :white-space "nowrap"}})
          (dom/text (str (or duration-ms 0) "ms")))))))))

(e/defn ViewModeToggle
  "Segmented control toggle for Detailed/Focused/Classic view modes.
   :detailed (default) renders the next-gen observability components;
   :classic renders the previous detailed implementation for A/B comparison."
  [current-mode on-change]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex"
                        :border "1px solid #d1d5db"
                        :border-radius "6px"
                        :overflow "hidden"}})
    (dom/button
     (dom/props {:style {:padding "0.25rem 0.5rem"
                         :font-size "0.7rem"
                         :background (if (= current-mode :detailed) "#3b82f6" "white")
                         :color (if (= current-mode :detailed) "white" "#374151")
                         :border "none"
                         :cursor "pointer"}})
     (dom/text "Detailed")
     (let [[t err] (e/Token (dom/On "click" identity nil))]
       (when t
         (on-change :detailed)
         (t))))
    (dom/button
     (dom/props {:style {:padding "0.25rem 0.5rem"
                         :font-size "0.7rem"
                         :background (if (= current-mode :focused) "#3b82f6" "white")
                         :color (if (= current-mode :focused) "white" "#374151")
                         :border "none"
                         :cursor "pointer"
                         :border-left "1px solid #d1d5db"}})
     (dom/text "Focused")
     (let [[t err] (e/Token (dom/On "click" identity nil))]
       (when t
         (on-change :focused)
         (t))))
    (dom/button
     (dom/props {:style {:padding "0.25rem 0.5rem"
                         :font-size "0.7rem"
                         :background (if (= current-mode :classic) "#3b82f6" "white")
                         :color (if (= current-mode :classic) "white" "#374151")
                         :border "none"
                         :cursor "pointer"
                         :border-left "1px solid #d1d5db"}})
     (dom/text "Classic")
     (let [[t err] (e/Token (dom/On "click" identity nil))]
       (when t
         (on-change :classic)
         (t)))))))

(e/defn FocusedLiveStatus
  "Compact live status for Focused mode."
  [stream-view]
  (e/client
   (let [elapsed (format-elapsed (:elapsed-ms stream-view))]
    (dom/div
    (dom/props {:style {:display "flex"
                        :align-items "center"
                        :gap "0.4rem"
                        :margin-bottom "0.5rem"
                        :font-size "0.75rem"
                        :color "#6b7280"
                        :flex-wrap "wrap"}})
    (dom/span
     (dom/props {:style {:width "7px"
                         :height "7px"
                         :border-radius "50%"
                         :background "#f59e0b"}})
     (dom/text ""))
    (dom/span
     (dom/props {:style {:font-weight "500" :color "#374151"}})
     (dom/text (or (:live-summary stream-view) "Working...")))
    (when (not (str/blank? (or elapsed "")))
      (dom/span
       (dom/props {:style {:padding "0.1rem 0.35rem"
                           :background "#eef2ff"
                           :color "#4338ca"
                           :border-radius "3px"}})
       (dom/text (str "elapsed " elapsed))))
    (dom/span
     (dom/props {:style {:padding "0.1rem 0.35rem"
                         :background "#eff6ff"
                         :color "#1e40af"
                         :border-radius "3px"}})
     (dom/text (str (or (:tool-call-count stream-view) 0) " tool calls")))
    (dom/span
     (dom/props {:style {:padding "0.1rem 0.35rem"
                         :background "#ecfdf5"
                         :color "#065f46"
                         :border-radius "3px"}})
     (dom/text (str (or (:tool-result-count stream-view) 0) " results")))
    (when (pos? (or (:warning-count stream-view) 0))
      (dom/span
       (dom/props {:style {:padding "0.1rem 0.35rem"
                           :background "#fef3c7"
                           :color "#92400e"
                           :border-radius "3px"}})
       (dom/text (str (:warning-count stream-view) " warnings"))))))))

(e/defn DetailedLiveTimeline
  "Event timeline for Detailed mode while execution is running."
  [stream-view]
  (e/client
   (let [timing-entries (or (:execution-timing-entries stream-view) [])
         elapsed (format-elapsed (:elapsed-ms stream-view))
         !expanded-entries (atom #{})
         expanded-entries (e/watch !expanded-entries)
         toggle-entry! (fn [entry-id]
                         (swap! !expanded-entries
                                (fn [s] (if (contains? s entry-id)
                                          (disj s entry-id)
                                          (conj s entry-id)))))]
    (dom/div
    (dom/props {:style {:margin-bottom "0.5rem"
                        :padding "0.4rem 0.55rem"
                        :background "#f8fafc"
                        :border "1px solid #e5e7eb"
                        :border-radius "6px"}})
    (dom/div
     (dom/props {:style {:display "flex"
                         :align-items "center"
                         :justify-content "space-between"
                         :gap "0.5rem"
                         :margin-bottom "0.45rem"}})
     (dom/div
      (dom/props {:style {:font-size "0.72rem"
                          :font-weight "700"
                          :text-transform "uppercase"
                          :letter-spacing "0.02em"
                          :color "#475569"}})
      (dom/text "Timeline"))
     (when (not (str/blank? (or elapsed "")))
       (dom/span
        (dom/props {:style {:padding "0.12rem 0.36rem"
                            :background "#eef2ff"
                            :border-radius "999px"
                            :font-size "0.64rem"
                            :font-weight "700"
                            :color "#4338ca"}})
        (dom/text (str "Elapsed " elapsed)))))
    (when (seq timing-entries)
      (dom/div
       (dom/props {:style {:margin-bottom "0.65rem"}})
       (dom/div
        (dom/props {:style {:font-size "0.68rem"
                            :font-weight "700"
                            :text-transform "uppercase"
                            :letter-spacing "0.02em"
                            :color "#64748b"
                            :margin-bottom "0.3rem"}})
        (dom/text "Execution timings"))
       (StageTimingList timing-entries)))
    (if (seq (:timeline stream-view))
      (do
        (dom/div
         (dom/props {:style {:font-size "0.68rem"
                             :font-weight "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color "#64748b"
                             :margin-bottom "0.3rem"}})
         (dom/text "Event stream"))
        (e/for [entry (e/diff-by :id (:timeline stream-view))]
          (let [has-detail? (not (str/blank? (or (:detail entry) "")))
                expanded? (contains? expanded-entries (:id entry))
                dot-color (case (:type entry)
                            :warning/raised "#f59e0b"
                            :request/failed "#ef4444"
                            :tool/call "#3b82f6"
                            :tool/result "#10b981"
                            "#9ca3af")]
            (dom/div
             (dom/props {:style {:margin-bottom "0.2rem"}})
             (dom/div
              (dom/props (cond-> {:style {:display "flex"
                                          :align-items "center"
                                          :gap "0.4rem"
                                          :font-size "0.72rem"
                                          :color "#4b5563"}}
                           has-detail? (assoc-in [:style :cursor] "pointer")
                           has-detail? (assoc-in [:style :user-select] "none")))
              (when has-detail?
                (dom/On "click" (fn [_] (toggle-entry! (:id entry))) nil))
              (dom/span
               (dom/props {:style {:width "5px"
                                   :height "5px"
                                   :border-radius "50%"
                                   :background dot-color
                                   :flex "0 0 auto"}})
               (dom/text ""))
              (when has-detail?
                (dom/span
                 (dom/props {:style {:width "0.9rem"
                                     :display "inline-flex"
                                     :justify-content "center"
                                     :font-size "0.62rem"
                                     :color "#64748b"
                                     :flex "0 0 auto"}})
                 (dom/text (if expanded? "▼" "▶"))))
              (dom/span
               (dom/props {:style {:overflow "hidden"
                                   :text-overflow "ellipsis"
                                   :white-space "nowrap"
                                   :min-width "0"
                                   :flex "1 1 auto"}})
               (dom/text (:text entry))))
             (when (and has-detail? expanded?)
               (dom/div
                (dom/props {:style {:margin-top "0.25rem"
                                    :margin-left "1.3rem"
                                    :padding "0.35rem 0.5rem"
                                    :background "#f8fafc"
                                    :border "1px solid #e5e7eb"
                                    :border-radius "4px"
                                    :font-size "0.68rem"
                                    :color "#374151"
                                    :white-space "pre-wrap"
                                    :font-family "monospace"}})
                (dom/text (:detail entry))))))))
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.5rem"
                           :font-size "0.7rem"
                           :color "#6b7280"}})
       (e/for [step (e/diff-by :stage (:steps stream-view))]
         (let [is-current (:current? step)
               is-past (:past? step)]
           (dom/span
            (dom/props {:style {:display "flex" :align-items "center" :gap "0.2rem"}})
           (dom/span
            (dom/props {:style {:width "6px" :height "6px" :border-radius "50%"
                                 :background (cond is-current "#f59e0b"
                                                   is-past "#22c55e"
                                                   :else "#d1d5db")}})
             (dom/text ""))
            (dom/text (:label step)))))))))))

(e/defn ThinkingStepsTimeline
  "Collapsible process narrative showing the agent's search-rank-generate steps."
  [diagnostics]
  (e/client
   (let [!expanded (atom false)
         expanded (e/watch !expanded)
         trace (or (:iteration-history diagnostics) (:agent-trace diagnostics) [])
         is-agentic (seq trace)
         query-count (count (or (:query-relaxation diagnostics) []))
         merged-count (or (:merged-count diagnostics) 0)
         total-ms (or (:total-duration-ms diagnostics) 0)
         duration-str (if (> total-ms 0) (str (.toFixed (/ total-ms 1000.0) 1) "s") "")
         summary-text (str "Searched " query-count " queries across " merged-count " docs"
                           (when (not (str/blank? duration-str))
                             (str " (" duration-str ")")))]
     (dom/div
      (dom/props {:style {:margin-bottom "0.75rem"
                          :padding "0.5rem 0.75rem"
                          :background "#f0f4ff"
                          :border "1px solid #dbeafe"
                          :border-radius "6px"
                          :font-size "0.8rem"}})
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.5rem"
                           :cursor "pointer"
                           :font-weight "600"
                           :color "#1e40af"}})
       (dom/On "click" (fn [_] (swap! !expanded not)) nil)
       (dom/span
        (dom/props {:style {:width "0.9rem"
                            :display "inline-flex"
                            :justify-content "center"}})
        (dom/text (if @!expanded "▼" "▶")))
       (dom/text (if is-agentic "Thinking steps" "Execution summary"))
       (when (not (str/blank? summary-text))
         (dom/span
          (dom/props {:style {:font-weight "400"
                              :color "#475569"}})
          (dom/text summary-text))))
      (when @!expanded
        (dom/div
         (dom/props {:style {:margin-top "0.5rem"
                             :padding-top "0.5rem"
                             :border-top "1px solid #dbeafe"}})
         (if is-agentic
           (do
             (when (seq trace)
               (dom/div
                (dom/props {:style {:margin-bottom "0.4rem"
                                    :font-size "0.72rem"
                                    :color "#334155"}})
                (dom/text (str "Agent iterations: " (count trace)))))
             (e/for [turn (e/diff-by :iteration trace)]
               (dom/div
                (dom/props {:style {:margin-bottom "0.4rem"
                                    :padding "0.35rem 0.45rem"
                                    :background "rgba(255,255,255,0.65)"
                                    :border-radius "4px"}})
                (dom/div
                 (dom/props {:style {:display "flex"
                                     :justify-content "space-between"
                                     :gap "0.5rem"
                                     :font-size "0.68rem"
                                     :font-weight "600"
                                     :color "#1e40af"}})
                 (dom/span (dom/text (str "Iteration " (inc (or (:iteration turn) 0)))))
                 (dom/span (dom/text (str (count (:tool-calls turn)) " tool calls"))))
                (when-let [reasoning (:reasoning turn)]
                  (when-not (str/blank? reasoning)
                    (dom/div
                     (dom/props {:style {:margin-top "0.25rem"
                                         :padding-left "0.4rem"
                                         :border-left "2px solid #bfdbfe"
                                         :font-style "italic"
                                         :color "#475569"
                                         :white-space "pre-wrap"
                                         :font-size "0.68rem"}})
                     (dom/text reasoning))))
                (when (seq (:tool-calls turn))
                  (dom/div
                   (dom/props {:style {:margin-top "0.25rem"
                                       :display "flex"
                                       :flex-wrap "wrap"
                                       :gap "0.3rem"}})
                   (e/for [[idx tc] (e/diff-by first (map-indexed vector (:tool-calls turn)))]
                     (dom/span
                      (dom/props {:style {:padding "0.12rem 0.35rem"
                                          :background "#dbeafe"
                                          :color "#1e40af"
                                          :border-radius "999px"
                                          :font-size "0.62rem"
                                          :font-family "monospace"}})
                      (dom/text (or (:tool tc) (str "tool-" idx)))))))))
           (dom/div
            (dom/props {:style {:color "#64748b"
                                :font-size "0.72rem"}})
            (dom/text "No agent trace available."))))))))))

(e/defn ExecutionSummaryBar
  "Horizontal summary of key run metrics (Model, Iterations, Duration, Budget)."
  [diagnostics]
  (e/client
   (let [summary (:run-summary diagnostics)
         model (get-in diagnostics [:query-intent :selected-model] "unknown")
         iterations (count (or (:agent-trace diagnostics) []))
         total-ms (or (:total-duration-ms diagnostics) 0)
         duration-str (if (pos? total-ms) (str (.toFixed (/ total-ms 1000.0) 1) "s") "-")
         budget-state (:budget-state summary)
         budget-mode (or (:mode budget-state) :normal)]
     (dom/div
      (dom/props {:style {:display "flex"
                          :align-items "center"
                          :gap "1rem"
                          :padding "0.5rem 0.75rem"
                          :background "#f9fafb"
                          :border "1px solid #e5e7eb"
                          :border-radius "6px"
                          :margin-bottom "0.75rem"
                          :font-size "0.75rem"
                          :color "#4b5563"}})
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Model:"))
       (dom/span (dom/text model)))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Iterations:"))
       (dom/span (dom/text (str iterations))))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Duration:"))
       (dom/span (dom/text duration-str)))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem" :align-items "center"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Budget:"))
       (dom/span
        (dom/props {:style (case budget-mode
                             :exhausted {:color "#dc2626" :font-weight "700"}
                             :low {:color "#d97706" :font-weight "600"}
                             {:color "#059669"})})
        (dom/text (name budget-mode))))))))

(e/defn TabButton [label active? on-click]
  (dom/button
   (dom/props {:style {:padding "0.4rem 0.75rem"
                       :font-size "0.75rem"
                       :font-weight "600"
                       :background (if active? "#3b82f6" "transparent")
                       :color (if active? "white" "#6b7280")
                       :border "none"
                       :border-radius "4px"
                       :cursor "pointer"
                       :transition "all 0.15s"}})
   (dom/On "click" on-click nil)
   (dom/text label)))

(e/defn LiveAgentLoop
  "Live rendering of agent iterations during execution.
   Shows truncated reasoning and tool call pills for each completed iteration.
   Each tool-call row is a clickable header with a collapsible body; only the
   latest call on the latest iteration is expanded by default."
  [stream-view]
  (e/client
   (let [trace (or (:live-agent-trace stream-view) [])
         agent-stage-timings (or (:live-agent-stage-timings stream-view) [])
         !user-toggles (atom {})
         user-toggles (e/watch !user-toggles)
         latest-turn (last trace)
         latest-iter (:iteration latest-turn)
         latest-call (when latest-turn
                       (dec (count (:tool-calls latest-turn))))
         default-open? (fn [iter-idx call-idx]
                         (and (= iter-idx latest-iter)
                              (= call-idx latest-call)))
         expanded? (fn [iter-idx call-idx]
                     (case (get user-toggles [iter-idx call-idx])
                       :expanded true
                       :collapsed false
                       (default-open? iter-idx call-idx)))
         toggle-call! (fn [iter-idx call-idx]
                        (swap! !user-toggles assoc [iter-idx call-idx]
                               (if (expanded? iter-idx call-idx)
                                 :collapsed :expanded)))]
     (when (seq trace)
       (dom/div
        (dom/props {:style {:margin-bottom "0.5rem"
                            :padding "0.4rem 0.55rem"
                            :background "#f0f4ff"
                            :border "1px solid #dbeafe"
                            :border-radius "6px"}})
        (dom/div
         (dom/props {:style {:font-size "0.72rem"
                             :font-weight "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color "#1e40af"
                             :margin-bottom "0.35rem"}})
         (dom/text (str "Agent loop (" (count trace) " iterations)")))
        (e/for [turn (e/diff-by :iteration trace)]
          (let [turn-timings (diagnostics/agent-iteration-stage-timings
                              {:agent-stage-timings agent-stage-timings}
                              (:iteration turn))]
            (dom/div
             (dom/props {:style {:margin-bottom "0.35rem"
                                 :padding-left "0.5rem"
                                 :border-left "2px solid #bfdbfe"}})
             (dom/div
              (dom/props {:style {:display "flex"
                                  :justify-content "space-between"
                                  :gap "0.5rem"
                                  :align-items "center"
                                  :margin-bottom "0.15rem"}})
              (dom/div
               (dom/props {:style {:font-size "0.68rem"
                                   :font-weight "600"
                                   :color "#1e40af"}})
               (dom/text (str "Iteration " (inc (:iteration turn)))))
              (dom/div
               (dom/props {:style {:display "flex" :gap "0.3rem" :flex-wrap "wrap"}})
               (dom/span
                (dom/props {:style {:padding "0.08rem 0.32rem"
                                    :background "#dbeafe"
                                    :color "#1e40af"
                                    :border-radius "999px"
                                    :font-size "0.6rem"
                                    :font-weight "700"}})
                (dom/text (str (count (:tool-calls turn)) " tools")))
               (when (seq turn-timings)
                 (dom/span
                  (dom/props {:style {:padding "0.08rem 0.32rem"
                                      :background "#ede9fe"
                                      :color "#6d28d9"
                                      :border-radius "999px"
                                      :font-size "0.6rem"
                                      :font-weight "700"}})
                  (dom/text (str (count turn-timings) " timings"))))))
             (when-let [reasoning (:reasoning turn)]
               (when (not (str/blank? reasoning))
                 (dom/div
                  (dom/props {:style {:font-style "italic"
                                      :color "#6b7280"
                                      :font-size "0.68rem"
                                      :max-height "40px"
                                      :overflow-y "hidden"
                                      :margin-bottom "0.2rem"}})
                  (dom/text (if (> (count reasoning) 150)
                              (str (subs reasoning 0 150) "...")
                              reasoning)))))
             (when (seq turn-timings)
               (dom/div
                (dom/props {:style {:margin-bottom "0.25rem"}})
                (dom/div
                 (dom/props {:style {:font-size "0.64rem"
                                     :font-weight "700"
                                     :text-transform "uppercase"
                                     :letter-spacing "0.02em"
                                     :color "#64748b"
                                     :margin-bottom "0.2rem"}})
                 (dom/text "Recorded timings"))
                (StageTimingList turn-timings)))
             (when (seq (:tool-calls turn))
               (dom/div
                (dom/props {:style {:display "flex"
                                    :flex-direction "column"
                                    :gap "0.25rem"}})
                (e/for [[call-idx tc] (e/diff-by first (map-indexed vector (:tool-calls turn)))]
                  (let [iter-idx (:iteration turn)
                        is-expanded? (expanded? iter-idx call-idx)
                        headline (first-line (:result-summary tc))]
                    (dom/div
                     (dom/props {:style {:padding "0.3rem 0.4rem"
                                         :background (if (:ok? tc) "#eff6ff" "#fef2f2")
                                         :border (str "1px solid " (if (:ok? tc) "#bfdbfe" "#fecaca"))
                                         :border-radius "6px"}})
                     (dom/div
                      (dom/props {:style {:display "flex"
                                          :align-items "center"
                                          :justify-content "space-between"
                                          :gap "0.4rem"
                                          :cursor "pointer"
                                          :user-select "none"}})
                      (dom/On "click" (fn [_] (toggle-call! iter-idx call-idx)) nil)
                      (dom/div
                       (dom/props {:style {:display "flex"
                                           :align-items "center"
                                           :gap "0.3rem"
                                           :flex-wrap "wrap"
                                           :min-width "0"
                                           :flex "1 1 auto"}})
                       (dom/span
                        (dom/props {:style {:width "0.9rem"
                                            :display "inline-flex"
                                            :justify-content "center"
                                            :font-size "0.62rem"
                                            :color "#64748b"}})
                        (dom/text (if is-expanded? "▼" "▶")))
                       (dom/span
                        (dom/props {:style {:font-size "0.68rem"
                                            :font-weight "700"
                                            :color (if (:ok? tc) "#1e40af" "#991b1b")
                                            :font-family "monospace"}})
                        (dom/text (or (:tool tc) (str "tool-" call-idx))))
                       (when-let [stage (:stage tc)]
                         (dom/span
                          (dom/props {:style {:padding "0.08rem 0.3rem"
                                              :background "#e0f2fe"
                                              :color "#075985"
                                              :border-radius "999px"
                                              :font-size "0.58rem"
                                              :font-weight "700"}})
                          (dom/text (name stage))))
                       (when-let [sub-skill (:sub-skill tc)]
                         (dom/span
                          (dom/props {:style {:padding "0.08rem 0.3rem"
                                              :background "#f3e8ff"
                                              :color "#7e22ce"
                                              :border-radius "999px"
                                              :font-size "0.58rem"
                                              :font-weight "700"}})
                          (dom/text (some-> sub-skill name))))
                       (when (and (not is-expanded?)
                                  (not (str/blank? (or headline ""))))
                         (dom/span
                          (dom/props {:style {:font-size "0.64rem"
                                              :color "#475569"
                                              :overflow "hidden"
                                              :text-overflow "ellipsis"
                                              :white-space "nowrap"
                                              :min-width "0"
                                              :flex "1 1 auto"}})
                          (dom/text headline))))
                      (when-let [duration-ms (:duration-ms tc)]
                        (dom/span
                         (dom/props {:style {:font-size "0.64rem"
                                             :font-weight "700"
                                             :font-family "monospace"
                                             :color "#374151"
                                             :white-space "nowrap"}})
                         (dom/text (str duration-ms "ms")))))
                     (when is-expanded?
                       (dom/div
                        (dom/props {:style {:margin-top "0.3rem"
                                            :padding-top "0.3rem"
                                            :border-top (str "1px solid "
                                                             (if (:ok? tc) "#bfdbfe" "#fecaca"))
                                            :display "flex"
                                            :flex-direction "column"
                                            :gap "0.18rem"}})
                        (when-let [args-summary (:args-summary tc)]
                          (when-not (str/blank? args-summary)
                            (dom/div
                             (dom/props {:style {:font-size "0.63rem"
                                                 :color "#475569"
                                                 :font-family "monospace"
                                                 :white-space "pre-wrap"}})
                             (dom/text args-summary))))
                        (when-let [effective-parameters (:effective-parameters tc)]
                          (dom/div
                           (dom/props {:style {:font-size "0.63rem"
                                               :color "#475569"
                                               :font-family "monospace"
                                               :white-space "pre-wrap"}})
                           (dom/text (pr-str effective-parameters))))
                        (when-let [result-summary (:result-summary tc)]
                          (when-not (str/blank? result-summary)
                            (dom/div
                             (dom/props {:style {:font-size "0.64rem"
                                                 :color "#374151"
                                                 :white-space "pre-wrap"}})
                             (dom/text result-summary)))))))))))))))))))
