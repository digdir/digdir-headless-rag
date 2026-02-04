(ns digdir.pipeline.ui.executions
  "Pipeline execution monitoring UI.

   Features:
   - List executions for a pipeline
   - View execution status and progress
   - Monitor running executions
   - View execution logs/telemetry
   - Cancel running executions"
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            #?(:clj [digdir.pipeline.executor :as executor])
            #?(:clj [digdir.data.db :as db])))

;; =============================================================================
;; Styles
;; =============================================================================

(def container-style
  {:max-width "1200px"
   :margin "0 auto"
   :padding "2rem"})

(def header-style
  {:display "flex"
   :justify-content "space-between"
   :align-items "center"
   :margin-bottom "2rem"})

(def card-style
  {:background "white"
   :border "1px solid #e5e7eb"
   :border-radius "8px"
   :padding "1.5rem"
   :margin-bottom "1rem"})

(def table-style
  {:width "100%"
   :border-collapse "collapse"
   :font-size "0.875rem"})

(def th-style
  {:padding "0.75rem"
   :text-align "left"
   :background "#f9fafb"
   :border-bottom "2px solid #e5e7eb"
   :font-weight "600"
   :color "#374151"})

(def td-style
  {:padding "0.75rem"
   :border-bottom "1px solid #e5e7eb"})

(def badge-style
  {:padding "0.25rem 0.75rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"
   :display "inline-block"})

(def status-styles
  {:running {:background "#dbeafe" :color "#1e40af"}
   :completed {:background "#dcfce7" :color "#166534"}
   :failed {:background "#fee2e2" :color "#991b1b"}
   :cancelled {:background "#f3f4f6" :color "#6b7280"}})

(def button-style
  {:padding "0.5rem 1rem"
   :border "none"
   :border-radius "4px"
   :background "#2563eb"
   :color "white"
   :font-size "0.875rem"
   :cursor "pointer"
   :font-weight "500"})

(def button-danger-style
  (merge button-style
         {:background "#dc2626"}))

(def progress-bar-container-style
  {:width "100%"
   :height "8px"
   :background "#e5e7eb"
   :border-radius "4px"
   :overflow "hidden"})

(def progress-bar-fill-style
  {:height "100%"
   :background "#2563eb"
   :transition "width 0.3s ease"})

;; =============================================================================
;; Utilities
;; =============================================================================

(defn format-timestamp
  "Format a timestamp for display"
  [ts]
  (when ts
    (let [date (js/Date. ts)]
      (.toLocaleString date))))

(defn calculate-progress
  "Calculate progress percentage"
  [processed failed]
  (let [total (+ (or processed 0) (or failed 0))]
    (if (pos? total)
      (* 100 (/ (or processed 0) total))
      0)))

;; =============================================================================
;; Components
;; =============================================================================

(e/defn StatusBadge
  "Display execution status as a badge"
  [status]
  (e/client
    (let [status-kw (keyword status)
          style (merge badge-style (get status-styles status-kw status-styles))]
      (dom/span
        (dom/props {:style style})
        (dom/text (str/upper-case (name status-kw)))))))

(e/defn ExecutionRow
  "Single execution row in the table"
  [execution !selected-execution]
  (e/client
    (let [id (:pipeline-execution/id execution)
          status (:pipeline-execution/status execution)
          started-at (:pipeline-execution/started-at execution)
          completed-at (:pipeline-execution/completed-at execution)
          docs-processed (or (:pipeline-execution/documents-processed execution) 0)
          docs-failed (or (:pipeline-execution/documents-failed execution) 0)
          started-by (:pipeline-execution/started-by execution)]

      (dom/tr
        (dom/td
          (dom/props {:style td-style})
          (dom/text (subs id 0 8) "..."))

        (dom/td
          (dom/props {:style td-style})
          (StatusBadge status))

        (dom/td
          (dom/props {:style td-style})
          (dom/text (str docs-processed)))

        (dom/td
          (dom/props {:style td-style})
          (dom/text (str docs-failed)))

        (dom/td
          (dom/props {:style td-style})
          (dom/text (format-timestamp started-at)))

        (dom/td
          (dom/props {:style td-style})
          (dom/text (if completed-at
                      (format-timestamp completed-at)
                      "—")))

        (dom/td
          (dom/props {:style td-style})
          (dom/text (or started-by "—")))

        (dom/td
          (dom/props {:style td-style})

          ;; View details button
          (dom/button
            (dom/props {:style (merge button-style {:font-size "0.75rem" :margin-right "0.5rem"})})
            (dom/text "Details")
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !selected-execution execution)
                (tok))))

          ;; Cancel button (only for running executions)
          (when (= status :running)
            (dom/button
              (dom/props {:style (merge button-danger-style {:font-size "0.75rem"})})
              (dom/text "Cancel")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  ;; TODO: Call cancel API
                  (tok))))))))))

(e/defn ExecutionDetails
  "Detailed view of a single execution"
  [execution !selected-execution]
  (e/client
    (let [status (:pipeline-execution/status execution)
          docs-processed (or (:pipeline-execution/documents-processed execution) 0)
          docs-failed (or (:pipeline-execution/documents-failed execution) 0)
          error-message (:pipeline-execution/error-message execution)
          progress (calculate-progress docs-processed docs-failed)]

      (dom/div
        (dom/props {:style card-style})

        ;; Header
        (dom/div
          (dom/props {:style {:display "flex" :justify-content "space-between" :margin-bottom "1.5rem"}})

          (dom/h3
            (dom/props {:style {:margin "0" :font-size "1.25rem"}})
            (dom/text "Execution Details"))

          (dom/button
            (dom/props {:style (merge button-style {:background "#6b7280"})})
            (dom/text "← Back")
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !selected-execution nil)
                (tok)))))

        ;; Status
        (dom/div
          (dom/props {:style {:margin-bottom "1.5rem"}})
          (dom/div
            (dom/props {:style {:margin-bottom "0.5rem" :font-weight "500"}})
            (dom/text "Status"))
          (StatusBadge status))

        ;; Progress bar (for running executions)
        (when (= status :running)
          (dom/div
            (dom/props {:style {:margin-bottom "1.5rem"}})
            (dom/div
              (dom/props {:style {:margin-bottom "0.5rem" :font-weight "500"}})
              (dom/text "Progress"))
            (dom/div
              (dom/props {:style progress-bar-container-style})
              (dom/div
                (dom/props {:style (merge progress-bar-fill-style
                                         {:width (str progress "%")})})))
            (dom/div
              (dom/props {:style {:margin-top "0.25rem" :font-size "0.75rem" :color "#6b7280"}})
              (dom/text (str docs-processed " documents processed, " docs-failed " failed")))))

        ;; Statistics
        (dom/div
          (dom/props {:style {:display "grid" :grid-template-columns "repeat(2, 1fr)" :gap "1rem" :margin-bottom "1.5rem"}})

          (dom/div
            (dom/div
              (dom/props {:style {:font-weight "500" :margin-bottom "0.25rem"}})
              (dom/text "Documents Processed"))
            (dom/div
              (dom/props {:style {:font-size "1.5rem" :font-weight "700" :color "#2563eb"}})
              (dom/text (str docs-processed))))

          (dom/div
            (dom/div
              (dom/props {:style {:font-weight "500" :margin-bottom "0.25rem"}})
              (dom/text "Documents Failed"))
            (dom/div
              (dom/props {:style {:font-size "1.5rem" :font-weight "700" :color "#dc2626"}})
              (dom/text (str docs-failed)))))

        ;; Error message (if failed)
        (when (and (= status :failed) error-message)
          (dom/div
            (dom/props {:style {:padding "1rem"
                               :background "#fee2e2"
                               :border "1px solid #fecaca"
                               :border-radius "4px"
                               :margin-bottom "1.5rem"}})
            (dom/div
              (dom/props {:style {:font-weight "500" :color "#991b1b" :margin-bottom "0.5rem"}})
              (dom/text "Error"))
            (dom/div
              (dom/props {:style {:font-family "monospace" :font-size "0.875rem" :color "#7f1d1d"}})
              (dom/text error-message))))

        ;; Timestamps
        (dom/div
          (dom/props {:style {:border-top "1px solid #e5e7eb" :padding-top "1rem"}})

          (dom/div
            (dom/props {:style {:margin-bottom "0.5rem"}})
            (dom/span
              (dom/props {:style {:font-weight "500" :margin-right "0.5rem"}})
              (dom/text "Started:"))
            (dom/text (format-timestamp (:pipeline-execution/started-at execution))))

          (when-let [completed (:pipeline-execution/completed-at execution)]
            (dom/div
              (dom/props {:style {:margin-bottom "0.5rem"}})
              (dom/span
                (dom/props {:style {:font-weight "500" :margin-right "0.5rem"}})
                (dom/text "Completed:"))
              (dom/text (format-timestamp completed))))

          (when-let [started-by (:pipeline-execution/started-by execution)]
            (dom/div
              (dom/span
                (dom/props {:style {:font-weight "500" :margin-right "0.5rem"}})
                (dom/text "Started by:"))
              (dom/text started-by))))))))

(e/defn ExecutionsList
  "List of executions for a pipeline"
  [pipeline-id]
  (e/client
    (let [!selected-execution (atom nil)
          selected-execution (e/watch !selected-execution)]

      (if selected-execution
        ;; Show details view
        (ExecutionDetails selected-execution !selected-execution)

        ;; Show list view
        (dom/div
          (dom/props {:style container-style})

          ;; Header
          (dom/div
            (dom/props {:style header-style})
            (dom/h2
              (dom/props {:style {:margin "0" :font-size "1.5rem" :color "#111827"}})
              (dom/text "Execution History")))

          ;; Executions table
          (dom/div
            (dom/props {:style card-style})

            (let [db (e/server (e/watch (db/get-conn)))
                  pid (e/client pipeline-id)
                  executions (e/server (executor/list-executions db pid))]

              (e/client
                (if (empty? executions)
                  (dom/p
                    (dom/props {:style {:text-align "center" :color "#6b7280" :padding "2rem"}})
                    (dom/text "No executions found for this pipeline."))

                  (dom/table
                    (dom/props {:style table-style})

                    ;; Header
                    (dom/thead
                      (dom/tr
                        (dom/th (dom/props {:style th-style}) (dom/text "ID"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Status"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Processed"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Failed"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Started"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Completed"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Started By"))
                        (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))

                    ;; Body
                    (dom/tbody
                      (e/for-by :pipeline-execution/id [exec executions]
                        (ExecutionRow exec !selected-execution))))))))))))

(e/defn Executions
  "Main executions monitoring component"
  [pipeline-id]
  (ExecutionsList pipeline-id))
