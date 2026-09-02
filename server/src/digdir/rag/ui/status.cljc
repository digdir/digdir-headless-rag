(ns digdir.rag.ui.status
  "Typesense health and metrics display components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            #?(:clj [typesense.client :as ts])))

;; Helper functions for Typesense health and metrics
(defn vibed-get-typesense-health
  "Fetch Typesense health information"
  [ts-settings]
  #?(:clj
     (try
       (ts/health ts-settings)
       (catch Exception e
         {:error (str "Failed to fetch health: " (.getMessage e))}))
     :cljs
     {:error "Health check only available on server"}))

(defn vibed-get-typesense-metrics
  "Fetch Typesense metrics information"
  [ts-settings]
  #?(:clj
     (try
       (ts/metrics ts-settings)
       (catch Exception e
         {:error (str "Failed to fetch metrics: " (.getMessage e))}))
     :cljs
     {:error "Metrics only available on server"}))

(comment
  :vibed
  ;; Test health and metrics functions
  (vibed-get-typesense-health ts-settings)
  (vibed-get-typesense-metrics ts-settings))

(e/defn Typesense-status [ts-settings]
  (let [api-host (e/server (:uri ts-settings))
        health-data (e/server (vibed-get-typesense-health ts-settings))
        metrics-data (e/server (vibed-get-typesense-metrics ts-settings))]
    (dom/div
     (dom/props {:style {:display "flex"
                         :flex-wrap "wrap"
                         :align-items "center"
                         :gap "1.5rem"
                         :padding "0.75rem 1rem"
                         :background "#f8fafc"
                         :border "1px solid #e2e8f0"
                         :border-radius "6px"
                         :margin-bottom "1rem"
                         :font-size "0.875rem"}})
     ;; Typesense label and host
     (dom/div
      (dom/props {:style {:display "flex"
                          :align-items "center"
                          :gap "0.5rem"}})
      (dom/span
       (dom/props {:style {:font-weight "600"
                           :color "#475569"}})
       (dom/text "Typesense"))
      (dom/span
       (dom/props {:style {:font-family "monospace"
                           :background "#e2e8f0"
                           :padding "0.25rem 0.5rem"
                           :border-radius "4px"
                           :font-size "0.8rem"}})
       (dom/text (or api-host "Not set"))))

     ;; Health status
     (if (:error health-data)
       (dom/span
        (dom/props {:style {:color "#dc2626"}})
        (dom/text (str "Error: " (:error health-data))))
       (dom/span
        (dom/props {:style {:display "flex"
                            :align-items "center"
                            :gap "0.25rem"}})
        (dom/span
         (dom/props {:style {:width "8px"
                             :height "8px"
                             :border-radius "50%"
                             :background (if (:ok health-data) "#22c55e" "#ef4444")}})
         (dom/text ""))
        (dom/span
         (dom/props {:style {:color (if (:ok health-data) "#16a34a" "#dc2626")
                             :font-weight "500"}})
         (dom/text (if (:ok health-data) "Healthy" "Unhealthy")))))

     ;; Metrics
     (when-not (:error metrics-data)
       (dom/div
        (dom/props {:style {:display "flex"
                            :align-items "center"
                            :gap "1rem"
                            :color "#64748b"}})
        (when-let [memory (:system_memory_used_bytes metrics-data)]
          (dom/span
           (dom/text (str "Mem: " (Math/round (/ memory 1024 1024)) " MB"))))
        (when-let [disk (:system_disk_used_bytes metrics-data)]
          (dom/span
           (dom/text (str "Disk: " (Math/round (/ disk 1024 1024 1024)) " GB"))))
        (when-let [ts-memory (:typesense_memory_active_bytes metrics-data)]
          (dom/span
           (dom/text (str "TS Mem: " (Math/round (/ ts-memory 1024 1024)) " MB")))))))))
