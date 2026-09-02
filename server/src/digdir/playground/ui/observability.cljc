(ns digdir.playground.ui.observability
  "Observability and diagnostics UI components for Playground."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [digdir.playground.diagnostics :as diagnostics]
            [digdir.playground.ui.components :as base]
            [digdir.playground.ui.observability.live :as live]
            [digdir.playground.ui.observability.live-next :as live-next]
            [digdir.playground.ui.observability.panels :as panels]
            [digdir.playground.ui.observability.results :as results]
            #?(:clj [digdir.playground.core :as playground])
            [digdir.i18n :refer [t]]))

;; =========Styles=========

(def tag-style panels/tag-style)

(def section-header-style panels/section-header-style)

(defn status-badge-style
  [status]
  (panels/status-badge-style status))

(defn agent-status-messages
  "Return explanatory agent status messages from diagnostics."
  [diagnostics]
  (panels/agent-status-messages diagnostics))

(defn retrieval-filter-entries
  "Get retrieval filter entries from diagnostics, with backward compatibility."
  [diagnostics]
  (panels/retrieval-filter-entries diagnostics))

(defn normalize-debug-playground-mode
  "Limit temporary playground isolation mode values to a known set."
  [mode]
  (panels/normalize-debug-playground-mode mode))

;; =========Components=========

(e/defn ExpandableSection
  "A collapsible section with header and content."
  [title Content]
  (panels/ExpandableSection title Content))

(e/defn AgentStatusCallout
  "Primary explanatory status from agent trace/workspace."
  [diagnostics]
  (panels/AgentStatusCallout diagnostics))

(e/defn BackendIssuesPanel
  "Structured backend issues captured during retrieval/tool execution."
  [diagnostics]
  (panels/BackendIssuesPanel diagnostics))

(e/defn ConflictPanel
  "Rich display for conflicting evidence or ambiguous scopes."
  [diagnostics]
  (panels/ConflictPanel diagnostics))

(e/defn ReadSignalsPanel
  "Compact read-signal and shadow sufficiency summary."
  [diagnostics]
  (panels/ReadSignalsPanel diagnostics))

(e/defn RetrievalFiltersRow
  "Compact retrieval filter display used by Focused/Detailed views."
  [diagnostics]
  (panels/RetrievalFiltersRow diagnostics))

(e/defn ResultsTable
  "Shared results table used by search diagnostics and message diagnostics."
  [results opts]
  (results/ResultsTable results opts))


(e/defn SearchResultsTable [results title !selected-chunk-id]
  (results/SearchResultsTable results title !selected-chunk-id))

(e/defn SearchDiagnosticsPanel [results chunks-collection docs-collection]
  (results/SearchDiagnosticsPanel results chunks-collection docs-collection))

(e/defn QueryRelaxationResults [phrases]
  (results/QueryRelaxationResults phrases))

(e/defn UsedChunksView [chunks docs-collection-name]
  (results/UsedChunksView chunks docs-collection-name))

(e/defn DiagnosticsResultsTable
  "Table showing search results for diagnostics."
  [results _title on-select-chunk]
  (results/DiagnosticsResultsTable results _title on-select-chunk))

(e/defn DiagnosticsUsedChunks
  "Compact used chunks display for diagnostics."
  [chunks on-select-chunk]
  (results/DiagnosticsUsedChunks chunks on-select-chunk))

(e/defn RetrievedEvidenceTable
  "Table showing retrieved chunk metadata before/alongside reading."
  [diagnostics docs-collection-name on-select-chunk]
  (results/RetrievedEvidenceTable diagnostics docs-collection-name on-select-chunk))

(e/defn DetailedSourcesTable
  "Table showing used chunks with per-search-type score columns."
  [diagnostics docs-collection-name on-select-chunk]
  (results/DetailedSourcesTable diagnostics docs-collection-name on-select-chunk))

(e/defn ExecutionMetadataPanel
  "Display skill execution metadata: timing, steps, and key-value details."
  [metadata]
  (e/client
   (dom/div
    (if (or (nil? metadata) (empty? metadata))
      (dom/div
       (dom/props {:style {:color     "#6b7280"
                           :font-size "0.75rem"}})
       (dom/text "No execution metadata available."))
      (dom/div
       (dom/props {:style {:max-height "400px"
                           :overflow-y "auto"}})
       ;; Total duration
       (when-let [total-ms (:total-duration-ms metadata)]
         (dom/div
          (dom/props {:style {:padding       "0.4rem 0.6rem"
                              :margin-bottom "0.4rem"
                              :background    "#f0fdf4"
                              :border        "1px solid #bbf7d0"
                              :border-radius "4px"
                              :font-size     "0.75rem"
                              :font-weight   "600"
                              :color         "#166534"}})
          (dom/text (str "Total: " total-ms "ms (" (:steps-executed metadata) " steps)"))))
       ;; Step timings
       (when-let [timing-source (or (:stage-timings metadata)
                                    (:step-timings metadata))]
         (let [timing-entries (cond
                                (map? timing-source)
                                (sort-by (comp :duration-ms val) > (vec timing-source))

                                (sequential? timing-source)
                                (map-indexed vector timing-source)

                                :else [])]
           (dom/div
            (dom/props {:style {:font-size "0.7rem"}})
            (e/for [[entry-id timing] (e/diff-by first timing-entries)]
              (let [step-id (or (:step-id timing) entry-id)
                    step-name (some-> step-id name)
                    skill-name (some-> (:skill-id timing) name)
                    label (if skill-name
                            (str step-name " (" skill-name ")")
                            step-name)]
                (dom/div
                 (dom/props {:style {:display       "flex"
                                     :align-items   "center"
                                     :gap           "0.5rem"
                                     :padding       "0.3rem 0.5rem"
                                     :margin-bottom "0.2rem"
                                     :background    "#f9fafb"
                                     :border-radius "3px"}})
                 ;; Step name
                 (dom/span
                  (dom/props {:style {:font-family "monospace"
                                      :font-weight "600"
                                      :color       "#374151"
                                      :min-width   "140px"}})
                  (dom/text (or label "step")))
                 ;; Duration bar
                 (let [max-ms (or (:total-duration-ms metadata) 1)
                       pct (min 100 (* 100 (/ (or (:duration-ms timing) 0) (max max-ms 1))))]
                   (dom/div
                    (dom/props {:style {:flex       "1"
                                        :height     "6px"
                                        :background "#e5e7eb"
                                        :border-radius "3px"
                                        :overflow   "hidden"}})
                    (dom/div
                     (dom/props {:style {:width      (str pct "%")
                                         :height     "100%"
                                         :background (cond
                                                       (:skipped timing) "#d1d5db"
                                                       (:defaulted timing) "#fbbf24"
                                                       :else "#3b82f6")
                                         :border-radius "3px"}}))))
                 ;; Duration text
                 (dom/span
                  (dom/props {:style {:font-family "monospace"
                                      :font-size   "0.65rem"
                                      :color       (cond
                                                     (:skipped timing) "#9ca3af"
                                                     :else "#6b7280")
                                      :min-width   "60px"
                                      :text-align  "right"}})
                  (dom/text (if (:skipped timing)
                              "skipped"
                              (str (or (:duration-ms timing) 0) "ms")))))))))))))))

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
                 :error "#fee2e2"
                 :skipped "#e5e7eb"
                 :defaulted "#fef3c7"
                 "#ecfdf5")
   :color (case status
            :error "#b91c1c"
            :skipped "#6b7280"
            :defaulted "#92400e"
            "#047857")})

(e/defn StageTimingList
  "Compact list of execution timing entries used across Detailed tabs."
  [entries]
  (e/client
   (if (seq entries)
     (dom/div
      (dom/props {:style {:display "flex"
                          :flex-direction "column"
                          :gap "0.3rem"}})
      (e/for [entry (e/diff-by :id entries)]
        (let [{:keys [source label detail duration-ms status]} entry]
          (dom/div
           (dom/props {:style {:padding "0.35rem 0.45rem"
                               :background "#f8fafc"
                               :border "1px solid #e5e7eb"
                               :border-radius "5px"}})
           (dom/div
            (dom/props {:style {:display "flex"
                                :justify-content "space-between"
                                :gap "0.45rem"
                                :align-items "baseline"}})
            (dom/div
             (dom/props {:style {:display "flex"
                                 :flex-wrap "wrap"
                                 :align-items "baseline"
                                 :gap "0.25rem"}})
             (dom/span
              (dom/props {:style (timing-source-style source)})
              (dom/text (case source
                          :agent "Agent"
                          :graph "Graph"
                          "Timing")))
             (when status
               (dom/span
                (dom/props {:style (timing-status-style status)})
                (dom/text (name status))))
             (dom/span
              (dom/props {:style {:font-size "0.72rem"
                                  :font-weight "600"
                                  :color "#0f172a"}})
              (dom/text label)))
            (dom/span
             (dom/props {:style {:font-family "monospace"
                                 :font-size "0.66rem"
                                 :font-weight "700"
                                 :color "#334155"
                                 :white-space "nowrap"}})
             (dom/text (str (or duration-ms 0) "ms"))))
           (when (seq (str/trim (or detail "")))
             (dom/div
              (dom/props {:style {:margin-top "0.18rem"
                                  :font-size "0.64rem"
                                  :color "#475569"
                                  :white-space "nowrap"
                                  :overflow "hidden"
                                  :text-overflow "ellipsis"
                                  :font-family "monospace"}})
              (dom/text detail))))))
     (dom/div
      (dom/props {:style {:color "#6b7280"
                          :font-size "0.75rem"}})
      (dom/text "No execution timings available."))))))

(e/defn TimingSummaryCallout
  "Compact wall-clock summary for detailed diagnostics."
  [diagnostics]
  (e/client
   (let [{:keys [total-ms recorded-ms unexplained-ms top-entries]}
         (diagnostics/execution-timing-summary diagnostics)]
     (when (or (pos? total-ms)
               (seq top-entries))
       (dom/div
        (dom/props {:style {:margin-bottom "0.75rem"
                            :padding "0.55rem 0.65rem"
                            :background "#f8fafc"
                            :border "1px solid #e5e7eb"
                            :border-radius "6px"}})
        (dom/div
         (dom/props {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.35rem"
                             :margin-bottom "0.45rem"}})
         (when (pos? total-ms)
           (dom/span
            (dom/props {:style {:padding "0.12rem 0.4rem"
                                :background "#dbeafe"
                                :color "#1d4ed8"
                                :border-radius "999px"
                                :font-size "0.68rem"
                                :font-weight "700"}})
            (dom/text (str "Total "
                           (if (>= total-ms 1000)
                             (str (.toFixed (/ total-ms 1000.0) 1) "s")
                             (str total-ms "ms"))))))
         (when (pos? recorded-ms)
           (dom/span
            (dom/props {:style {:padding "0.12rem 0.4rem"
                                :background "#dcfce7"
                                :color "#166534"
                                :border-radius "999px"
                                :font-size "0.68rem"
                                :font-weight "700"}})
            (dom/text (str "Recorded "
                           (if (>= recorded-ms 1000)
                             (str (.toFixed (/ recorded-ms 1000.0) 1) "s")
                             (str recorded-ms "ms"))))))
         (when (pos? unexplained-ms)
           (dom/span
            (dom/props {:style {:padding "0.12rem 0.4rem"
                                :background "#fef3c7"
                                :color "#92400e"
                                :border-radius "999px"
                                :font-size "0.68rem"
                                :font-weight "700"}})
            (dom/text (str "Gap "
                           (if (>= unexplained-ms 1000)
                             (str (.toFixed (/ unexplained-ms 1000.0) 1) "s")
                             (str unexplained-ms "ms")))))))
        (when (seq top-entries)
          (dom/div
           (dom/props {:style {:margin-bottom "0.45rem"
                               :font-size "0.66rem"
                               :font-weight "700"
                               :text-transform "uppercase"
                               :letter-spacing "0.02em"
                               :color "#64748b"}})
           (dom/text "Top recorded stages"))
          (StageTimingList top-entries)))))))

(e/defn AgentTracePanel
  "Display the agent's iteration trace: reasoning and tool calls per turn."
  [diagnostics]
  (e/client
   (let [trace (or (:iteration-history diagnostics) (:agent-trace diagnostics) [])]
     (dom/div
      (if (empty? trace)
        (dom/div
         (dom/props {:style {:color     "#6b7280"
                             :font-size "0.75rem"}})
         (dom/text "No agent trace available (non-agentic skill graph)."))
        (dom/div
         (dom/props {:style {:max-height "600px"
                             :overflow-y "auto"}})
         (e/for [turn (e/diff-by :iteration trace)]
           (let [turn-timings (diagnostics/agent-iteration-stage-timings diagnostics (:iteration turn))]
             (dom/div
              (dom/props {:style {:padding       "0.4rem"
                                  :margin-bottom "0.3rem"
                                  :border        "1px solid #e5e7eb"
                                  :border-radius "4px"
                                  :background    "white"
                                  :font-size     "0.7rem"}})
              (dom/div
               (dom/props {:style {:font-weight   "600"
                                   :color         "#374151"
                                   :margin-bottom "0.2rem"
                                   :display       "flex"
                                   :justify-content "space-between"}})
               (dom/div
                (dom/text (str "Iteration " (inc (or (:iteration turn) 0))))
                (dom/span
                 (dom/props {:style {:margin-left "0.5rem" :color "#9ca3af" :font-weight "400"}})
                 (dom/text (str "(" (count (:tool-calls turn)) " tool calls)"))))
               (when (seq turn-timings)
                 (dom/div
                  (dom/props {:style {:margin-bottom "0.3rem"}})
                  (dom/div
                   (dom/props {:style {:font-size "0.64rem"
                                       :font-weight "700"
                                       :text-transform "uppercase"
                                       :letter-spacing "0.02em"
                                       :color "#64748b"
                                       :margin-bottom "0.2rem"}})
                   (dom/text "Recorded timings"))
                  (StageTimingList turn-timings)))

               (when-let [summary (:summary turn)]
                 (dom/div
                  (dom/props {:style {:color "#111827"
                                      :font-weight "600"
                                      :margin-bottom "0.15rem"}})
                  (dom/text summary)))

               (when-let [narrative (:narrative turn)]
                 (dom/div
                  (dom/props {:style {:color "#475569"
                                      :margin-bottom "0.2rem"
                                      :white-space "pre-wrap"}})
                  (dom/text narrative)))

               (when-let [reasoning (:reasoning turn)]
                 (dom/div
                  (dom/props {:style {:color         "#6b7280"
                                      :font-style    "italic"
                                      :margin-bottom "0.3rem"
                                      :padding       "0.3rem"
                                      :background    "#f9fafb"
                                      :border-left   "2px solid #d1d5db"
                                      :white-space   "pre-wrap"}})
                  (dom/text reasoning)))

               (e/for [[idx tc] (e/diff-by first (map-indexed vector (:tool-calls turn)))]
                 (dom/div
                  (dom/props {:style {:margin-top  "0.2rem"
                                      :padding     "0.35rem 0.45rem"
                                      :background  "#f8fafc"
                                      :border     "1px solid #e5e7eb"
                                      :border-radius "4px"}})
                  (dom/div
                   (dom/props {:style {:display "flex"
                                       :gap "0.4rem"
                                       :align-items "center"
                                       :flex-wrap "wrap"}})
                   (dom/span
                    (dom/props {:style {:padding       "0.1rem 0.3rem"
                                        :background    (case (:tool tc)
                                                         "search_documents" "#dbeafe"
                                                         "plan_queries" "#fef3c7"
                                                         "read_chunks" (if (= :skim (:operation-kind tc)) "#fef3c7" "#dcfce7")
                                                         "rerank_results" "#d1fae5"
                                                         "generate_response" "#ede9fe"
                                                         "#f3f4f6")
                                        :color         (case (:tool tc)
                                                         "search_documents" "#1d4ed8"
                                                         "plan_queries" "#92400e"
                                                         "read_chunks" (if (= :skim (:operation-kind tc)) "#92400e" "#166534")
                                                         "rerank_results" "#065f46"
                                                         "generate_response" "#5b21b6"
                                                         "#374151")
                                        :border-radius "3px"
                                       :font-size     "0.65rem"
                                       :font-weight   "600"
                                       :font-family   "monospace"}})
                    (dom/text (:tool tc)))
                   (when-let [stage-name (some-> (:stage tc) name)]
                     (dom/span
                      (dom/props {:style {:padding "0.08rem 0.28rem"
                                          :background "#e0f2fe"
                                          :color "#075985"
                                          :border-radius "999px"
                                          :font-size "0.58rem"
                                          :font-weight "700"
                                          :font-family "monospace"}})
                      (dom/text stage-name)))
                   (when-let [duration-ms (:duration-ms tc)]
                     (dom/span
                      (dom/props {:style {:padding "0.08rem 0.28rem"
                                          :background "#eef2ff"
                                          :color "#4338ca"
                                          :border-radius "999px"
                                          :font-size "0.58rem"
                                          :font-weight "700"
                                          :font-family "monospace"}})
                      (dom/text (str duration-ms "ms")))))

                  (when-let [summary (:summary tc)]
                    (dom/div
                     (dom/props {:style {:font-size "0.7rem"
                                         :font-weight "700"
                                         :color "#0f172a"
                                         :margin-top "0.15rem"}})
                     (dom/text summary)))

                  (when-let [narrative (:narrative tc)]
                    (dom/div
                     (dom/props {:style {:font-size "0.66rem"
                                         :color "#475569"
                                         :margin-top "0.12rem"
                                         :white-space "pre-wrap"}})
                     (dom/text narrative)))

                  (let [input-summary (diagnostics/tool-call-input-summary tc)
                        params-summary (diagnostics/tool-call-effective-parameters-summary tc)
                        snippets (->> [(when input-summary (str "Inputs: " input-summary))
                                       (when params-summary (str "Params: " params-summary))]
                                      (remove nil?)
                                      seq)]
                    (when snippets
                      (dom/div
                       (dom/props {:style {:display "flex"
                                           :flex-wrap "wrap"
                                           :gap "0.5rem"
                                           :margin-top "0.12rem"
                                           :font-size "0.66rem"
                                           :color "#475569"}})
                       (e/for [snippet (e/diff-by identity snippets)]
                         (dom/span (dom/text snippet)))))
                  )

                  (dom/div
                   (dom/props {:style {:font-size   "0.66rem"
                                       :color       "#374151"
                                       :margin-top  "0.16rem"
                                       :white-space "pre-wrap"
                                       :max-height  "72px"
                                       :overflow-y  "auto"
                                       :background  "rgba(255,255,255,0.7)"
                                       :padding     "0.25rem"
                                       :border-radius "4px"
                                       :border      "1px solid #e5e7eb"}})
                   (dom/text (or (:result-summary tc) "")))))))))))))))

(e/defn ThinkingStepsTimeline
  "Collapsible process narrative showing the agent's search-rank-generate steps."
  [diagnostics]
  (live/ThinkingStepsTimeline diagnostics))

(e/defn ViewModeToggle
  "Segmented control toggle for Focused/Detailed view modes."
  [current-mode on-change]
  (live/ViewModeToggle current-mode on-change))

(e/defn FocusedLiveStatus
  "Compact live status for Focused mode."
  [stream-view]
  (live/FocusedLiveStatus stream-view))

(e/defn DetailedLiveTimeline
  "Event timeline for Detailed mode while execution is running."
  [stream-view]
  (live/DetailedLiveTimeline stream-view))

(e/defn LiveAgentLoop
  "Live rendering of agent iterations during execution.
   Shows truncated reasoning and tool call pills for each completed iteration."
  [stream-view]
  (live/LiveAgentLoop stream-view))

(e/defn NextDetailedLive
  "Entry point for the next-gen Detailed view during live execution."
  [stream-view]
  (live-next/NextDetailedLive stream-view))

(e/defn NextDetailedResponse
  "Entry point for the next-gen Detailed view in the post-run message view."
  [diagnostics on-select-chunk]
  (live-next/NextDetailedResponse diagnostics on-select-chunk))

(e/defn FocusedResponseView
  "Dataset-focused cited response with source cards."
  [message diagnostics dataset-config on-select-chunk]
  (e/client
  (let [parsed-diag diagnostics]
     (dom/div
      ;; Thinking steps timeline
      (live/ThinkingStepsTimeline parsed-diag)

      ;; Agent status summary
      (panels/AgentStatusCallout parsed-diag)
      (panels/BackendIssuesPanel parsed-diag)
      (panels/ReadSignalsPanel parsed-diag)
      (panels/ConflictPanel parsed-diag)

      ;; Response text with inline [N] click delegation.
      ;; render-markdown-with-citations injects <span class="citation"
      ;; data-index="N"> into the innerHTML. Clicks bubble up to this
      ;; div; .closest('.citation') finds the span, the citation list
      ;; resolves N → chunk-id, and we dispatch the same on-select-chunk
      ;; the source-cards panel below uses.
      ;;
      ;; Citation lookup falls back to workspace-final because
      ;; agent-rag graphs stash them there, not at the diagnostics top
      ;; level — same bug found in openai-compat.
      (let [html (e/server (base/render-markdown-with-citations (:message/text (e/client message))))
            citations (or (get-in parsed-diag [:outputs :workspace-final :citations])
                          (:citations parsed-diag)
                          [])
            index->chunk-id (into {} (map (juxt :index :chunk-id)) citations)]
        (dom/div
         (dom/props {:style {:line-height "1.6"}})
         (when html
           (base/set-markdown-html! dom/node html))
         (dom/On "click"
                 (fn [event]
                   (let [target (.-target event)
                         citation-el (when (and target (.-closest target))
                                       (.closest target ".citation"))
                         idx-str (when citation-el (.getAttribute citation-el "data-index"))]
                     (when (and idx-str on-select-chunk)
                       (when-let [chunk-id (get index->chunk-id (js/parseInt idx-str 10))]
                         (on-select-chunk chunk-id)))))
                 nil)))

      ;; Source cards
      (base/SourceCardsPanel parsed-diag on-select-chunk)))))

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
      ;; Model
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Model:"))
       (dom/span (dom/text model)))
      ;; Iterations
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Iterations:"))
       (dom/span (dom/text (str iterations))))
      ;; Duration
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Duration:"))
       (dom/span (dom/text duration-str)))
      ;; Budget
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.3rem" :align-items "center"}})
       (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text "Budget:"))
       (dom/span
        (dom/props {:style (case budget-mode
                             :exhausted {:color "#dc2626" :font-weight "700"}
                             :low {:color "#d97706" :font-weight "600"}
                             {:color "#059669"})})
        (dom/text (name budget-mode))))))))

(e/defn UnifiedDiagnosticsTabBar [!active-tab active-tab has-execution-metadata? has-agent-trace?]
  (dom/div
   (dom/props {:style {:display "flex"
                       :gap "0.25rem"
                       :padding "0.4rem"
                       :background "#f3f4f6"
                       :border-bottom "1px solid #e5e7eb"}})
   (live/TabButton "Timeline" (= active-tab :timeline) #(reset! !active-tab :timeline))
   (live/TabButton "Evidence" (= active-tab :evidence) #(reset! !active-tab :evidence))
   (live/TabButton "Sources" (= active-tab :sources) #(reset! !active-tab :sources))
   (when has-execution-metadata?
     (live/TabButton "Execution" (= active-tab :execution) #(reset! !active-tab :execution)))
   (when has-agent-trace?
     (live/TabButton "Agent Trace" (= active-tab :trace) #(reset! !active-tab :trace)))))

(e/defn UnifiedDiagnosticsTimelineTab [diagnostics]
  (let [summary-lines (diagnostics/run-summary-lines diagnostics)
        timing-entries (diagnostics/execution-stage-timing-entries diagnostics)
        timeline-entries (diagnostics/decision-timeline-entries diagnostics)]
    (dom/div
     (when (seq summary-lines)
       (dom/div
        (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.35rem" :margin-bottom "0.75rem"}})
        (e/for [line (e/diff-by identity summary-lines)]
          (dom/span
           (dom/props {:style {:padding "0.15rem 0.4rem"
                               :background "#f8fafc"
                               :border "1px solid #e5e7eb"
                               :border-radius "999px"
                               :font-size "0.68rem"
                               :color "#374151"}})
           (dom/text (str (:label line) ": " (:value line)))))))
     (when (seq timing-entries)
       (dom/div
        (dom/props {:style {:margin-bottom "0.85rem"}})
        (dom/div
         (dom/props {:style {:font-size "0.7rem"
                             :font-weight "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color "#64748b"
                             :margin-bottom "0.35rem"}})
         (dom/text "Execution timings"))
        (StageTimingList timing-entries)))
     (if (seq timeline-entries)
       (dom/div
        (dom/div
         (dom/props {:style {:font-size "0.7rem"
                             :font-weight "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color "#64748b"
                             :margin-bottom "0.35rem"}})
         (dom/text "Decision timeline"))
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.4rem"}})
         (e/for [entry (e/diff-by identity timeline-entries)]
           (dom/div
            (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :font-size "0.75rem"}})
            (dom/span (dom/props {:style (status-badge-style (:kind entry))})
                      (dom/text (name (:kind entry))))
            (dom/span (dom/props {:style {:font-weight "600"}}) (dom/text (:label entry)))
            (dom/span (dom/props {:style {:color "#6b7280"}}) (dom/text (:detail entry)))))))
       (dom/text "No timeline data available.")))))

(e/defn UnifiedDiagnosticsExecutionTab [diagnostics]
  (dom/div
   (TimingSummaryCallout diagnostics)
   (ExecutionMetadataPanel (:skill-execution-metadata diagnostics))))

(e/defn UnifiedDiagnosticsTraceTab [diagnostics]
  (dom/div
   (TimingSummaryCallout diagnostics)
   (AgentTracePanel diagnostics)))

(e/defn UnifiedDiagnosticsPanel
  "Unified tabbed interface for dataset-run diagnostics."
  [diagnostics dataset-config on-select-chunk]
  (e/client
   (let [!active-tab (atom :timeline)
         active-tab (e/watch !active-tab)
         has-agent-trace? (seq (or (:agent-trace diagnostics) []))
         has-execution-metadata? (some? (:skill-execution-metadata diagnostics))]
     (dom/div
      (dom/props {:style {:margin-top "1rem"
                          :border "1px solid #e5e7eb"
                          :border-radius "8px"
                          :overflow "hidden"
                          :background "white"}})
      (UnifiedDiagnosticsTabBar !active-tab active-tab has-execution-metadata? has-agent-trace?)
      (dom/div
       (dom/props {:style {:padding "0.75rem"}})
       (case active-tab
         :timeline (UnifiedDiagnosticsTimelineTab diagnostics)
         :evidence (results/RetrievedEvidenceTable diagnostics (:docs-collection dataset-config) on-select-chunk)
         :sources (results/DetailedSourcesTable diagnostics (:docs-collection dataset-config) on-select-chunk)
         :execution (UnifiedDiagnosticsExecutionTab diagnostics)
         :trace (UnifiedDiagnosticsTraceTab diagnostics)))))))

(e/defn DetailedResponseView
  "Thinking-steps timeline + response with unified tabbed diagnostics for the selected dataset."
  [message diagnostics dataset-config on-select-chunk]
  (e/client
   (let [parsed-diag diagnostics]
     (dom/div
      ;; Thinking steps timeline (narrative)
      (live/ThinkingStepsTimeline parsed-diag)

      ;; Agent status summary (critical status/issues)
      (panels/AgentStatusCallout parsed-diag)
      (panels/BackendIssuesPanel parsed-diag)
      (panels/ReadSignalsPanel parsed-diag)
      (panels/ConflictPanel parsed-diag)

      ;; New Execution Summary Bar
      (live/ExecutionSummaryBar parsed-diag)

      ;; Response text with citations + inline [N] click delegation.
      ;; Mirrors the same handler used in FocusedResponseView above —
      ;; walks event.target to .closest('.citation') and resolves
      ;; data-index → chunk-id via the citation list.
      (let [html (e/server (base/render-markdown-with-citations (:message/text (e/client message))))
            index->chunk-id (into {} (map (juxt :index :chunk-id))
                                  (or (:citations parsed-diag) []))]
        (dom/div
         (dom/props {:style {:line-height "1.6"}})
         (when html
           (base/set-markdown-html! dom/node html))
         (dom/On "click"
                 (fn [event]
                   (let [target (.-target event)
                         tag (some-> target .-tagName)
                         class (some-> target .-className)
                         citation-el (when (and target (.-closest target))
                                       (.closest target ".citation"))
                         idx-str (when citation-el (.getAttribute citation-el "data-index"))]
                     (js/console.log "[digdir] response-div click | target=" tag
                                     "class=" class
                                     "citation-el?" (boolean citation-el)
                                     "data-index=" idx-str
                                     "index->chunk-id keys=" (pr-str (keys index->chunk-id)))
                     (when (and idx-str on-select-chunk)
                       (when-let [chunk-id (get index->chunk-id (js/parseInt idx-str 10))]
                         (js/console.log "[digdir] dispatching on-select-chunk for" chunk-id)
                         (on-select-chunk chunk-id)))))
                 nil)))

      ;; Retrieval filters
      (panels/RetrievalFiltersRow parsed-diag)

      ;; Inline citation bar
      (when (seq (:citations parsed-diag))
         (dom/div
         (dom/props {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.25rem"
                             :margin-top "0.5rem"
                             :font-size "0.7rem"}})
         (e/for [citation (e/diff-by :index (:citations parsed-diag))]
           (let [chunk-id (:chunk-id citation)
                 fetched-chunk (when (and chunk-id (:docs-collection dataset-config) (:chunks-collection dataset-config))
                                 (e/server
                                  (playground/fetch-chunk-by-id
                                   (:chunks-collection dataset-config)
                                   (:docs-collection dataset-config)
                                   (e/client chunk-id))))
                 source (base/citation-source-display-data citation
                                                     (:used-chunks parsed-diag)
                                                     (:docs-collection dataset-config)
                                                     fetched-chunk)]
             (dom/span
              (dom/props {:style {:color "#3b82f6"
                                  :cursor "pointer"
                                  :padding "0.1rem 0.3rem"
                                  :background "#eff6ff"
                                  :border-radius "3px"}})
              (dom/On "click" #(on-select-chunk (:chunk-id citation)) nil)
              (dom/text (str "[" (:index citation) "] "))
              (base/SourceTitleMarkdown
               (:title source)
               {:display "inline"
                :color "inherit"}))))))

      ;; Unified Tabbed Diagnostics
      (digdir.playground.ui.observability/UnifiedDiagnosticsPanel parsed-diag dataset-config on-select-chunk)

      ;; Search queries (kept as separate expandable for quick visibility)
      (panels/ExpandableSection
       (str "Search queries (" (count (or (:query-relaxation parsed-diag) [])) ")")
       (e/fn []
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.25rem"}})
          (e/for [phrase (e/diff-by identity (or (:query-relaxation parsed-diag) []))]
            (dom/span
             (dom/props {:style tag-style})
             (dom/text phrase))))))))))

(e/defn NextDetailedResponseView
  "Next-gen Detailed post-run view: observability tree + response text + citations.
   Intended to replace DetailedResponseView once the new view settles."
  [message diagnostics dataset-config on-select-chunk]
  (e/client
   (let [parsed-diag diagnostics]
     (dom/div
      (live-next/NextDetailedResponse parsed-diag on-select-chunk)
      ;; Response text + inline-[N] click delegation. See the matching
      ;; handler in DetailedResponseView / FocusedResponseView above —
      ;; same flow: walk to .closest('.citation'), resolve data-index
      ;; via the citation list, dispatch on-select-chunk.
      ;;
      ;; Citation source-of-truth fallback chain (same bug found in
      ;; openai-compat): for agent-rag graphs the citations land on
      ;; workspace-final, not at the top-level of diagnostics — older
      ;; views that just read (:citations parsed-diag) saw nil. Look
      ;; up workspace-final first, then top-level.
      (let [html (e/server (base/render-markdown-with-citations (:message/text (e/client message))))
            citations (or (get-in parsed-diag [:outputs :workspace-final :citations])
                          (:citations parsed-diag)
                          [])
            index->chunk-id (into {} (map (juxt :index :chunk-id)) citations)]
        (dom/div
         (dom/props {:style {:line-height "1.6" :margin-top "0.5rem"}})
         (when html
           (base/set-markdown-html! dom/node html))
         (dom/On "click"
                 (fn [event]
                   (let [target (.-target event)
                         citation-el (when (and target (.-closest target))
                                       (.closest target ".citation"))
                         idx-str (when citation-el (.getAttribute citation-el "data-index"))]
                     (when (and idx-str on-select-chunk)
                       (when-let [chunk-id (get index->chunk-id (js/parseInt idx-str 10))]
                         (on-select-chunk chunk-id)))))
                 nil)))
      (when (seq (:citations parsed-diag))
        (dom/div
         (dom/props {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.25rem"
                             :margin-top "0.5rem"
                             :font-size "0.7rem"}})
         (e/for [citation (e/diff-by :index (:citations parsed-diag))]
           (let [chunk-id (:chunk-id citation)
                 fetched-chunk (when (and chunk-id
                                          (:docs-collection dataset-config)
                                          (:chunks-collection dataset-config))
                                 (e/server
                                  (playground/fetch-chunk-by-id
                                   (:chunks-collection dataset-config)
                                   (:docs-collection dataset-config)
                                   (e/client chunk-id))))
                 source (base/citation-source-display-data citation
                                                           (:used-chunks parsed-diag)
                                                           (:docs-collection dataset-config)
                                                           fetched-chunk)]
             (dom/span
              (dom/props {:style {:color "#3b82f6"
                                  :cursor "pointer"
                                  :padding "0.1rem 0.3rem"
                                  :background "#eff6ff"
                                  :border-radius "3px"}})
              (dom/On "click" #(on-select-chunk (:chunk-id citation)) nil)
              (dom/text (str "[" (:index citation) "] "))
              (base/SourceTitleMarkdown
               (:title source)
               {:display "inline"
                :color "inherit"}))))))))))
