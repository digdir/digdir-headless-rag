(ns digdir.config.ui.audit
  "Audit log viewer UI component for configuration changes."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [digdir.config.audit :as audit])
            #?(:clj [digdir.config.db :as config-db])))

;; =============================================================================
;; Styles
;; =============================================================================

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
   :border-bottom "1px solid #e5e7eb"
   :vertical-align "top"})

(def filter-input-style
  {:padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"})

(def action-colors
  {:create {:bg "#dcfce7" :text "#166534"}
   :update {:bg "#dbeafe" :text "#1e40af"}
   :delete {:bg "#fee2e2" :text "#991b1b"}})

;; =============================================================================
;; Helper Functions
;; =============================================================================

#?(:clj
   (defn format-timestamp [epoch-ms]
     (when epoch-ms
       (let [inst (java.time.Instant/ofEpochMilli epoch-ms)
             formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             zoned (.atZone inst (java.time.ZoneId/of "Europe/Oslo"))]
         (.format formatter zoned)))))

#?(:clj
   (defn time-ago [epoch-ms]
     (when epoch-ms
       (let [now (System/currentTimeMillis)
             diff-ms (- now epoch-ms)
             minutes (/ diff-ms 1000 60)
             hours (/ minutes 60)
             days (/ hours 24)]
         (cond
           (< minutes 1) "just now"
           (< minutes 60) (str (int minutes) " min ago")
           (< hours 24) (str (int hours) " hours ago")
           (< days 7) (str (int days) " days ago")
           :else (format-timestamp epoch-ms))))))

;; =============================================================================
;; Server-side Data Functions
;; =============================================================================

#?(:clj
   (defn get-audit-data
     "Get audit log data for the UI."
     [opts]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             changes (audit/get-recent-changes db opts)
             stats (audit/get-change-stats db {:since (:since opts)})]
         {:changes (map (fn [change]
                          (assoc change
                                 :formatted-time (format-timestamp (:audit/timestamp change))
                                 :time-ago (time-ago (:audit/timestamp change))))
                        changes)
          :stats stats}))))

#?(:clj
   (defn get-path-history
     "Get audit history for a specific config path."
     [path opts]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             changes (audit/get-audit-history db path opts)]
         (map (fn [change]
                (assoc change
                       :formatted-time (format-timestamp (:audit/timestamp change))
                       :time-ago (time-ago (:audit/timestamp change))))
              changes)))))

;; =============================================================================
;; UI Components
;; =============================================================================

(e/defn ActionBadge [action]
  (let [colors (get action-colors action {:bg   "#e5e7eb"
                                          :text "#374151"})]
    (dom/span
     (dom/props {:style {:padding       "0.125rem 0.5rem"
                         :border-radius "9999px"
                         :font-size     "0.75rem"
                         :font-weight   "500"
                         :background    (:bg colors)
                         :color         (:text colors)}})
     (dom/text (name (or action :unknown))))))

(e/defn StatCard [label value color]
  (dom/div
   (dom/props {:style {:background "white"
                       :border (str "1px solid " color)
                       :border-radius "8px"
                       :padding "1rem"
                       :text-align "center"
                       :min-width "100px"}})
   (dom/div
    (dom/props {:style {:font-size "1.5rem"
                        :font-weight "700"
                        :color color}})
    (dom/text (str value)))
   (dom/div
    (dom/props {:style {:font-size "0.75rem"
                        :color "#6b7280"
                        :margin-top "0.25rem"}})
    (dom/text label))))

(e/defn AuditStats [stats]
  (let [by-action (:by-action stats)
        total (:total stats)]
    (dom/div
     (dom/props {:style {:display "flex"
                         :gap "1rem"
                         :margin-bottom "1rem"
                         :flex-wrap "wrap"}})
     (StatCard "Total" total "#6b7280")
     (StatCard "Creates" (get by-action :create 0) "#166534")
     (StatCard "Updates" (get by-action :update 0) "#1e40af")
     (StatCard "Deletes" (get by-action :delete 0) "#991b1b"))))

(e/defn AuditFilters [!tenant !tenant-config-key !user-filter !path-filter]
  (e/client
   (let [tenant (e/watch !tenant)
         tenant-config-key (e/watch !tenant-config-key)
         user-filter (e/watch !user-filter)
         path-filter (e/watch !path-filter)
         tenants (into [""] (e/server (audit/distinct-audit-tenants @(config-db/get-conn))))
         tenant-config-keys (into [""] (e/server (audit/distinct-audit-tenant-config-keys @(config-db/get-conn))))]
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "1rem"
                          :margin-bottom "1rem"
                          :padding "1rem"
                          :background "#f9fafb"
                          :border-radius "8px"
                          :flex-wrap "wrap"}})

      ;; Tenant filter
      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.25rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
        (dom/text "Tenant"))
       (dom/select
        (dom/props {:style filter-input-style :value (or tenant "")})
        (e/for [t (e/diff-by identity tenants)]
          (dom/option
           (dom/props {:value t :selected (= t tenant)})
           (dom/text (if (empty? t) "All tenants" t))))
        (dom/On "change" #(reset! !tenant (let [v (.. % -target -value)]
                                            (when (seq v) v))) nil)))

      ;; Config Key filter
      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.25rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
        (dom/text "Config Key"))
       (dom/select
        (dom/props {:style filter-input-style :value (or tenant-config-key "")})
        (e/for [env (e/diff-by identity tenant-config-keys)]
          (dom/option
           (dom/props {:value env :selected (= env tenant-config-key)})
           (dom/text (if (empty? env) "All tenant-config-keys" env))))
        (dom/On "change" #(reset! !tenant-config-key (let [v (.. % -target -value)]
                                                  (when (seq v) v))) nil)))

      ;; User filter
      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.25rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
        (dom/text "User"))
       (dom/input
        (dom/props {:type "text"
                    :placeholder "Filter by email..."
                    :value (or user-filter "")
                    :style (merge filter-input-style {:width "180px"})})
        (dom/On "input" #(reset! !user-filter (let [v (.. % -target -value)]
                                                 (when (seq v) v))) nil)))

      ;; Path filter
      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.25rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
        (dom/text "Config Path"))
       (dom/input
        (dom/props {:type "text"
                    :placeholder "Filter by path..."
                    :value (or path-filter "")
                    :style (merge filter-input-style {:width "220px"})})
        (dom/On "input" #(reset! !path-filter (let [v (.. % -target -value)]
                                                 (when (seq v) v))) nil)))))))

(e/defn AuditRow [entry]
  (dom/tr
   ;; Timestamp
   (dom/td
    (dom/props {:style td-style})
    (dom/div
     (dom/props {:style {:font-weight "500"}})
     (dom/text (:time-ago entry)))
    (dom/div
     (dom/props {:style {:font-size "0.75rem"
                         :color     "#6b7280"}})
     (dom/text (:formatted-time entry))))

   ;; Action
   (dom/td
    (dom/props {:style td-style})
    (ActionBadge (:audit/action entry)))

   ;; Config Path
   (dom/td
    (dom/props {:style td-style})
    (dom/div
     (dom/props {:style {:font-family "monospace"
                         :font-size   "0.875rem"}})
     (dom/text (:audit/config-path entry)))
    (dom/div
     (dom/props {:style {:font-size  "0.75rem"
                         :color      "#6b7280"
                         :margin-top "0.25rem"}})
     (when-let [tenant (:audit/tenant entry)]
       (dom/span (dom/text (str tenant " / "))))
     (when-let [env (:audit/tenant-config-key entry)]
       (dom/span (dom/text env)))))

   ;; User
   (dom/td
    (dom/props {:style td-style})
    (dom/text (or (:audit/user-email entry) "-")))

   ;; Changes
   (dom/td
    (dom/props {:style (merge td-style {:max-width "300px"})})
    (let [prev    (:audit/previous-value entry)
          new-val (:audit/new-value entry)]
      (dom/div
       (dom/props {:style {:font-size "0.75rem"}})
       (when (and prev (not= prev ""))
         (dom/div
          (dom/props {:style {:color         "#991b1b"
                              :background    "#fee2e2"
                              :padding       "0.25rem 0.5rem"
                              :border-radius "4px"
                              :margin-bottom "0.25rem"
                              :font-family   "monospace"
                              :word-break    "break-all"}})
          (dom/text (str "- " (if (= prev "[REDACTED]")
                                prev
                                (subs (str prev) 0 (min 50 (count (str prev)))))))))
       (when new-val
         (dom/div
          (dom/props {:style {:color         "#166534"
                              :background    "#dcfce7"
                              :padding       "0.25rem 0.5rem"
                              :border-radius "4px"
                              :font-family   "monospace"
                              :word-break    "break-all"}})
          (dom/text (str "+ " (if (= new-val "[REDACTED]")
                                new-val
                                (subs (str new-val) 0 (min 50 (count (str new-val))))))))))))))

(e/defn AuditTable [changes]
  (dom/table
   (dom/props {:style table-style})
   (dom/thead
    (dom/tr
     (dom/th (dom/props {:style (merge th-style {:width "150px"})}) (dom/text "When"))
     (dom/th (dom/props {:style (merge th-style {:width "80px"})}) (dom/text "Action"))
     (dom/th (dom/props {:style th-style}) (dom/text "Config Path"))
     (dom/th (dom/props {:style (merge th-style {:width "180px"})}) (dom/text "User"))
     (dom/th (dom/props {:style th-style}) (dom/text "Changes"))))
   (dom/tbody
    (e/for [entry (e/diff-by :audit/id changes)]
      (AuditRow entry)))))

;; =============================================================================
;; Main Audit Log Component
;; =============================================================================

(e/defn AuditLogViewer []
  (e/client
   (let [;; Filter state
         !tenant (atom nil)
         !tenant-config-key (atom nil)
         !user-filter (atom nil)
         !path-filter (atom nil)
         !refresh-counter (atom 0)

         tenant (e/watch !tenant)
         tenant-config-key (e/watch !tenant-config-key)
         user-filter (e/watch !user-filter)
         path-filter (e/watch !path-filter)
         refresh-counter (e/watch !refresh-counter)

         ;; Fetch data from server
         _ refresh-counter
         audit-data (e/server (get-audit-data {:tenant tenant
                                               :tenant-config-key tenant-config-key
                                               :user-email user-filter
                                               :limit 100}))
         all-changes (:changes audit-data)
         stats (:stats audit-data)

         ;; Client-side path filtering (for instant filtering)
         filtered-changes (if path-filter
                            (filter #(str/includes? (str (:audit/config-path %)) path-filter)
                                    all-changes)
                            all-changes)]

     (dom/div
      (dom/props {:style {:padding "1rem" :max-width "1400px"}})

      ;; Header
      (dom/div
       (dom/props {:style {:display "flex"
                           :justify-content "space-between"
                           :align-items "center"
                           :margin-bottom "1rem"}})
       (ks/Heading {:level 2} (e/fn [] (dom/text "Audit Log")))
       (dom/button
        (dom/props {:style {:padding "0.5rem 1rem"
                            :background "#3b82f6"
                            :color "white"
                            :border "none"
                            :border-radius "4px"
                            :cursor "pointer"}})
        (dom/text "Refresh")
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (swap! !refresh-counter inc)
            (t)))))

      ;; Stats
      (when stats
        (AuditStats stats))

      ;; Filters
      (AuditFilters !tenant !tenant-config-key !user-filter !path-filter)

      ;; Results count
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"
                           :font-size "0.875rem"
                           :color "#6b7280"}})
       (dom/text (str "Showing " (count filtered-changes) " audit entries")))

      ;; Table
      (ks/Card {}
               (e/fn []
                 (ks/CardBlock {}
                               (e/fn []
                                 (if (empty? filtered-changes)
                                   (dom/div
                                    (dom/props {:style {:padding "2rem"
                                                        :text-align "center"
                                                        :color "#6b7280"}})
                                    (dom/text "No audit entries found."))
                                   (AuditTable filtered-changes))))))))))

(e/defn AuditLog []
  (AuditLogViewer))
