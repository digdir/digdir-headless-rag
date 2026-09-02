(ns digdir.playground.ui.observability.panels
  "Status and helper panels for Playground observability."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [digdir.playground.diagnostics :as diagnostics]
            [digdir.playground.ui.components :as base]))

(def tag-style
  {:background "#dbeafe"
   :color "#1e40af"
   :padding "0.25rem 0.5rem"
   :border-radius "4px"
   :font-size "0.75rem"
   :display "inline-block"})

(def section-header-style
  {:font-weight "600"
   :font-size "0.875rem"
   :color "#374151"
   :margin-bottom "0.5rem"
   :padding-bottom "0.25rem"
   :border-bottom "1px solid #e5e7eb"})

(defn status-badge-style
  [status]
  (case status
    :sufficient (base/badge-style "#dcfce7" "#166534")
    :insufficient (base/badge-style "#fef3c7" "#92400e")
    :conflicting (base/badge-style "#fee2e2" "#991b1b")
    :off-topic (base/badge-style "#e0f2fe" "#075985")
    (base/badge-style "#e5e7eb" "#374151")))

(defn- non-blank-text
  [value]
  (let [s (some-> value str str/trim)]
    (when-not (str/blank? s)
      s)))

(defn- truncate-text
  [s max-chars]
  (if (and s (> (count s) max-chars))
    (str (subs s 0 max-chars) "...")
    s))

(defn agent-status-messages
  "Return explanatory agent status messages from diagnostics."
  [diagnostics]
  (let [explicit-statuses (->> (or (:agent-status diagnostics)
                                   (:agent-status-messages diagnostics))
                               (keep non-blank-text)
                               vec)]
    (if (seq explicit-statuses)
      explicit-statuses
      (->> (or (:agent-trace diagnostics) [])
           (sort-by :iteration)
           (keep (comp non-blank-text :reasoning))
           vec))))

(defn retrieval-filter-entries
  "Get retrieval filter entries from diagnostics, with backward compatibility."
  [diagnostics]
  (diagnostics/retrieval-filter-entries diagnostics))

(defn normalize-debug-playground-mode
  "Limit temporary playground isolation mode values to a known set."
  [mode]
  (let [mode (some-> mode str/trim str/lower-case)]
    (if (#{"bare" "scope" "inspect" "data" "no-effects" "full"} mode)
      mode
      "full")))

(e/defn ExpandableSection
  "A collapsible section with header and content."
  [title Content]
  (e/client
   (let [!expanded (atom false)
         expanded (e/watch !expanded)]
     (dom/div
      (dom/props {:style {:margin-top "0.25rem"}})
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.4rem"
                           :cursor "pointer"
                           :padding "0.3rem 0"
                           :font-size "0.75rem"
                           :color "#6b7280"}})
       (dom/On "click" #(swap! !expanded not) nil)
       (dom/span (dom/text (if expanded "▾" "▸")))
       (dom/span (dom/text title)))
      (when expanded
        (dom/div
         (dom/props {:style {:padding "0.5rem 0 0.5rem 1rem"}})
         (Content)))))))

(e/defn AgentStatusCallout
  "Primary explanatory status from agent trace/workspace."
  [diagnostics]
  (e/client
   (let [statuses (agent-status-messages diagnostics)
         status   (first statuses)
         more     (max 0 (dec (count statuses)))]
     (when status
       (dom/div
        (dom/props {:style {:margin-top    "0.5rem"
                            :margin-bottom "0.5rem"
                            :padding       "0.55rem 0.7rem"
                            :background    "#f8fafc"
                            :border        "1px solid #dbeafe"
                            :border-radius "6px"}})
        (dom/div
         (dom/props {:style {:font-size     "0.68rem"
                             :font-weight   "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color         "#1e40af"
                             :margin-bottom "0.2rem"}})
         (dom/text "Agent status"))
        (dom/div
         (dom/props {:style {:font-size   "0.76rem"
                             :color       "#374151"
                             :line-height "1.4"
                             :white-space "pre-wrap"}})
         (dom/text (truncate-text status 420)))
        (when (pos? more)
          (dom/div
           (dom/props {:style {:margin-top "0.25rem"
                               :font-size  "0.66rem"
                               :color      "#6b7280"}})
           (dom/text (str "+" more " additional status update"
                          (when (> more 1) "s")
                          " in Agent reasoning")))))))))

(e/defn BackendIssuesPanel
  "Structured backend issues captured during retrieval/tool execution."
  [diagnostics]
  (e/client
   (let [issues (vec (or (:backend-issues diagnostics) []))]
     (when (seq issues)
       (dom/div
        (dom/props {:style {:margin-top "0.5rem"
                            :margin-bottom "0.5rem"
                            :padding "0.55rem 0.7rem"
                            :background "#fff7ed"
                            :border "1px solid #fdba74"
                            :border-radius "6px"}})
        (dom/div
         (dom/props {:style {:font-size "0.68rem"
                             :font-weight "700"
                             :text-transform "uppercase"
                             :letter-spacing "0.02em"
                             :color "#9a3412"
                             :margin-bottom "0.3rem"}})
         (dom/text "Backend issues"))
        (dom/div
         (dom/props {:style {:display "flex"
                             :flex-direction "column"
                             :gap "0.35rem"}})
         (e/for [[idx issue] (e/diff-by first (map-indexed vector issues))]
           (let [prefix (str (inc idx) ". "
                             (or (:message issue) "Unknown backend issue"))
                 meta-parts (remove nil?
                                    [(some-> (:source issue) name)
                                     (:tool issue)
                                     (some-> (:issue-type issue) name)])
                 meta-text (when (seq meta-parts)
                             (str/join " · " meta-parts))]
             (dom/div
              (dom/props {:style {:font-size "0.74rem"
                                  :color "#7c2d12"
                                  :line-height "1.4"}})
              (dom/div
               (dom/props {:style {:font-weight "600"}})
               (dom/text prefix))
              (when meta-text
                (dom/div
                 (dom/props {:style {:font-size "0.68rem"
                                     :color "#9a3412"}})
                 (dom/text meta-text)))
              (when-let [details (:details issue)]
                (dom/div
                 (dom/props {:style {:font-size "0.68rem"
                                     :color "#9a3412"
                                     :white-space "pre-wrap"}})
                 (dom/text (pr-str details)))))))))))))

(e/defn ConflictPanel
  "Rich display for conflicting evidence or ambiguous scopes."
  [diagnostics]
  (e/client
   (let [conflict (diagnostics/conflict-summary diagnostics)]
     (when conflict
       (dom/div
        (dom/props {:style {:margin-top "0.5rem"
                            :margin-bottom "0.5rem"
                            :padding "0.75rem"
                            :background "#fef2f2"
                            :border "1px solid #fee2e2"
                            :border-radius "8px"}})
        (dom/div
         (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :margin-bottom "0.5rem"}})
         (dom/span
          (dom/props {:style {:background "#dc2626" :color "white" :padding "0.1rem 0.4rem"
                              :border-radius "4px" :font-size "0.65rem" :font-weight "700"
                              :text-transform "uppercase"}})
          (dom/text "Conflict"))
         (dom/span
          (dom/props {:style {:font-size "0.875rem" :font-weight "600" :color "#991b1b"}})
          (dom/text (:headline conflict))))
        (dom/div
         (dom/props {:style {:display "grid" :grid-template-columns "repeat(auto-fill, minmax(180px, 1fr))"
                             :gap "0.75rem" :margin-bottom "0.75rem"}})
         (when-let [reason (:reason conflict)]
           (dom/div
            (dom/div (dom/props {:style {:font-size "0.65rem" :color "#991b1b" :font-weight "600"}}) (dom/text "Reason"))
            (dom/div (dom/props {:style {:font-size "0.75rem" :color "#7f1d1d"}}) (dom/text reason))))
         (when-let [metric (:metric conflict)]
           (dom/div
            (dom/div (dom/props {:style {:font-size "0.65rem" :color "#991b1b" :font-weight "600"}}) (dom/text "Target Metric"))
            (dom/div (dom/props {:style {:font-size "0.75rem" :color "#7f1d1d"}}) (dom/text metric))))
         (when-let [year (:year conflict)]
           (dom/div
            (dom/div (dom/props {:style {:font-size "0.65rem" :color "#991b1b" :font-weight "600"}}) (dom/text "Target Year"))
            (dom/div (dom/props {:style {:font-size "0.75rem" :color "#7f1d1d"}}) (dom/text (str year))))))
        (when-let [gap (:gap-summary conflict)]
          (dom/div
           (dom/props {:style {:padding "0.6rem" :background "rgba(255,255,255,0.5)" :border-radius "4px"
                               :margin-bottom "0.75rem" :font-size "0.75rem" :color "#7f1d1d"
                               :line-height "1.4" :border-left "3px solid #f87171"}})
           (dom/text gap)))
        (when-let [action (:action conflict)]
          (dom/div
           (dom/props {:style {:display "flex" :align-items "center" :gap "0.4rem" :font-size "0.75rem"}})
           (dom/span (dom/props {:style {:font-weight "600" :color "#991b1b"}}) (dom/text "Recommended:"))
          (dom/span (dom/props {:style {:background "#fff" :border "1px solid #fee2e2" :padding "0.1rem 0.4rem"
                                         :border-radius "4px" :color "#b91c1c" :font-weight "500"}})
                     (dom/text (name action))))))))))

(e/defn ReadSignalsPanel
  "Compact display of read-time evidence coverage and shadow sufficiency routing."
  [diagnostics]
  (e/client
   (let [last-read-signal (:last-read-signal diagnostics)
         shadow-decision (last (or (:shadow-sufficiency-decisions diagnostics) []))
         evidence-plan (:evidence-plan diagnostics)
         supported-count (count (or (:supported-claims last-read-signal) []))
         gap-count (count (or (:remaining-gaps last-read-signal) []))
         required-count (count (or (:required-claims evidence-plan) []))
         missing-claims (vec (or (:missing-claims shadow-decision) []))]
     (when (or last-read-signal shadow-decision evidence-plan)
       (dom/div
        (dom/props {:style {:margin-top "0.5rem"
                            :margin-bottom "0.5rem"
                            :padding "0.6rem 0.7rem"
                            :background "#f8fafc"
                            :border "1px solid #cbd5e1"
                            :border-radius "6px"}})
        (dom/div
         (dom/props {:style {:display "flex"
                             :align-items "center"
                             :justify-content "space-between"
                             :gap "0.5rem"
                             :margin-bottom "0.35rem"}})
         (dom/div
          (dom/props {:style {:font-size "0.68rem"
                              :font-weight "700"
                              :text-transform "uppercase"
                              :letter-spacing "0.02em"
                              :color "#334155"}})
          (dom/text "Read signals"))
         (when-let [status (:status shadow-decision)]
           (dom/span
            (dom/props {:style (status-badge-style status)})
            (dom/text (name status)))))
        (dom/div
         (dom/props {:style {:display "flex"
                             :flex-wrap "wrap"
                             :gap "0.35rem"
                             :margin-bottom "0.35rem"}})
         (when (pos? required-count)
           (dom/span
            (dom/props {:style tag-style})
            (dom/text (str "claims " supported-count "/" required-count))))
         (when (some? gap-count)
           (dom/span
            (dom/props {:style tag-style})
            (dom/text (str "gaps " gap-count))))
         (when-let [hint (:next-action-hint last-read-signal)]
           (dom/span
            (dom/props {:style tag-style})
            (dom/text (str "read hint " (name hint)))))
         (when-let [action (:action shadow-decision)]
           (dom/span
            (dom/props {:style tag-style})
            (dom/text (str "shadow action " (name action))))))
        (when (seq missing-claims)
          (dom/div
           (dom/props {:style {:font-size "0.72rem"
                               :color "#475569"
                               :line-height "1.4"
                               :margin-bottom "0.25rem"}})
           (dom/text (str "Missing claims: " (str/join ", " (map name missing-claims))))))
        (when-let [supported (seq (:supported-claims last-read-signal))]
          (dom/div
           (dom/props {:style {:font-size "0.72rem"
                               :color "#475569"
                               :line-height "1.4"}})
           (dom/text
            (str "Supported: "
                 (str/join ", "
                           (map (fn [entry]
                                  (name (:claim-id entry)))
                                supported)))))))))))

(e/defn RetrievalFiltersRow
  "Compact retrieval filter display used by Focused/Detailed views."
  [diagnostics]
  (e/client
   (let [entries (retrieval-filter-entries diagnostics)]
     (when (seq entries)
       (dom/div
        (dom/props {:style {:margin-top "0.5rem"
                            :display "flex"
                            :flex-wrap "wrap"
                            :gap "0.35rem"
                            :align-items "center"}})
        (dom/span
         (dom/props {:style {:font-size "0.7rem"
                             :font-weight "600"
                             :color "#6b7280"}})
         (dom/text "Filters: "))
        (e/for [entry (e/diff-by identity (vec entries))]
          (dom/span
           (dom/props {:style {:padding "0.1rem 0.35rem"
                               :background "#f3f4f6"
                               :border "1px solid #e5e7eb"
                               :border-radius "4px"
                               :font-size "0.68rem"
                               :color "#374151"}})
           (dom/text (diagnostics/format-retrieval-filter-label entry)))))))))
