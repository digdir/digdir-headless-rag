(ns digdir.pipeline.ui.pipelines
  "Dataset-first operator console for ingestion management."
  {:clj-kondo/ignore true}
  (:require [clojure.string :as str]
            [com.itonomi.komponentkassen.shell :as ks]
            [digdir.config.ui.inheritance :refer [FocusedInheritanceEditor]]
            [digdir.i18n :refer [t]]
            [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.electric3 :as e]
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.core :as config-core])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.pipeline.core :as pipeline])
            #?(:clj [digdir.pipeline.executor :as executor])
            #?(:clj [digdir.pipeline.materialization :as materialization])))

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
   :margin-bottom "1.5rem"
   :gap "1rem"})

(def button-style
  {:padding "0.5rem 1rem"
   :border "none"
   :border-radius "4px"
   :background "#2563eb"
   :color "white"
   :font-size "0.875rem"
   :cursor "pointer"
   :font-weight "500"})

(def button-secondary-style
  (merge button-style
         {:background "#6b7280"}))

(def button-danger-style
  (merge button-style
         {:background "#dc2626"}))

(def button-disabled-style
  (merge button-secondary-style
         {:opacity 0.6
          :cursor "not-allowed"}))

(def button-link-style
  {:display "inline-block"
   :text-decoration "none"})

(def card-style
  {:background "white"
   :border "1px solid #e5e7eb"
   :border-radius "8px"
   :padding "1.25rem"
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
   :border-bottom "1px solid #e5e7eb"
   :vertical-align "top"})

(def input-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"})

(def label-style
  {:display "block"
   :margin-bottom "0.5rem"
   :font-weight "500"
   :color "#374151"})

(def form-group-style
  {:margin-bottom "1rem"})

(def badge-style
  {:padding "0.25rem 0.75rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"
   :display "inline-block"})

(def badge-success-style
  (merge badge-style
         {:background "#dcfce7"
          :color "#166534"}))

(def badge-warning-style
  (merge badge-style
         {:background "#fef3c7"
          :color "#92400e"}))

(def badge-danger-style
  (merge badge-style
         {:background "#fee2e2"
          :color "#991b1b"}))

(def badge-info-style
  (merge badge-style
         {:background "#dbeafe"
          :color "#1d4ed8"}))

(def helper-text-style
  {:font-size "0.75rem"
   :color "#6b7280"
   :margin-top "0.25rem"})

(def submetric-style
  {:font-size "0.75rem"
   :color "#475569"
   :margin-top "0.25rem"})

(def progress-bar-track-style
  {:width "100%"
   :height "6px"
   :background "#e5e7eb"
   :border-radius "3px"
   :overflow "hidden"
   :margin "0.35rem 0"})

(def progress-bar-fill-style
  {:height "100%"
   :background "#2563eb"
   :transition "width 0.3s ease"})

(def progress-bar-fill-indeterminate-style
  {:height "100%"
   :width "40%"
   :background "linear-gradient(90deg, transparent, #2563eb, transparent)"
   :animation "progress-indeterminate 1.4s ease-in-out infinite"})

(def stat-grid-style
  "3-column grid that holds per-execution stat cells. Each cell is
   `(label, value)` rendered inline. Tight gap so the grid uses the
   minimum vertical space necessary to fit 3 rows × 3 cols."
  {:display "grid"
   :grid-template-columns "repeat(3, minmax(0, 1fr))"
   :gap "0.15rem 1rem"
   :margin-top "0.4rem"
   :padding "0.4rem 0.5rem"
   :background "#f8fafc"
   :border "1px solid #e2e8f0"
   :border-radius "4px"
   :font-size "0.72rem"
   :line-height "1.3"})

(def stat-cell-style
  {:display "flex"
   :justify-content "space-between"
   :align-items "baseline"
   :gap "0.5rem"
   :min-width 0})

(def stat-label-style
  {:color "#64748b"
   :white-space "nowrap"
   :overflow "hidden"
   :text-overflow "ellipsis"})

(def stat-value-style
  {:color "#0f172a"
   :font-variant-numeric "tabular-nums"
   :font-weight "500"
   :white-space "nowrap"})

(def stat-value-muted-style
  (assoc stat-value-style :color "#94a3b8" :font-weight "400"))

(def stat-value-danger-style
  (assoc stat-value-style :color "#b91c1c"))

(def stat-value-warning-style
  (assoc stat-value-style :color "#a16207"))

(def callout-style
  {:padding "0.875rem"
   :background "#eff6ff"
   :border "1px solid #bfdbfe"
   :border-radius "8px"
   :margin-bottom "1rem"})

(def section-title-style
  {:font-size "0.95rem"
   :font-weight "600"
   :color "#1e3a8a"
   :margin-bottom "0.35rem"})

(def dataset-detail-card-style
  (merge card-style
         {:padding "1rem"}))

(def dataset-runtime-callout-style
  (merge callout-style
         {:margin-top "0.75rem"
          :padding "0.875rem"
          :margin-bottom "0.75rem"}))

(def dataset-runtime-selector-style
  {:margin-top "0.625rem"
   :padding "0.625rem"
   :background "white"
   :border "1px solid #dbe3f0"
   :border-radius "8px"})

(def dataset-summary-grid-style
  {:display "grid"
   :grid-template-columns "repeat(auto-fit, minmax(180px, 1fr))"
   :gap "0.5rem"
   :margin-top "0.75rem"})

#?(:cljs
   (defn format-display-timestamp
     [ts]
     (when ts
       (let [date (js/Date. ts)]
         (if (js/isNaN (.valueOf date))
           (str ts)
           (.toLocaleString date))))))

(defn status-label
  [status]
      (or (case status
        :needs-pipeline "Needs pipeline"
        :configured "Configured"
        :ready "Ready"
        :running "Running"
        :completed "Completed"
        :failed "Failed"
        :cancelled "Cancelled"
        :missing-config "Missing config"
        :multiple-contexts "Multiple contexts"
        :disabled "Disabled"
        :unbound "Unbound"
        nil)
      (some-> status name str/capitalize)
      "Unknown"))

(defn status-badge-style
  [status]
  (case status
    :ready badge-success-style
    :running badge-info-style
    :completed badge-success-style
    :failed badge-danger-style
    :cancelled badge-warning-style
    :disabled badge-warning-style
    :configured badge-warning-style
    :needs-pipeline badge-warning-style
    :missing-config badge-warning-style
    :multiple-contexts badge-warning-style
    :unbound badge-warning-style
    badge-warning-style))

(defn branch-key-label
  [tenant-config-key]
  (if (str/blank? (or tenant-config-key ""))
    "unkeyed (_)"
    tenant-config-key))

(defn format-compact-number
  "Render a count compactly for the stats grid: 1234 -> \"1.2k\",
   1_234_567 -> \"1.2M\", anything under 1000 -> exact. Negative or nil
   safe (nil/missing renders as 0, negatives render with the minus
   sign).

   Hand-rolled rounding instead of `clojure.core/format` because this
   ns is .cljc and ClojureScript doesn't ship `format`. Math/abs and
   Math/round both work cross-platform (java.lang.Math on the JVM,
   js/Math in the browser)."
  [n]
  (let [n (or n 0)
        abs-n (Math/abs n)
        sign (if (neg? n) "-" "")
        round-tenth (fn [v] (/ (Math/round (* v 10.0)) 10.0))]
    (cond
      (< abs-n 1000) (str n)
      (< abs-n 1000000) (str sign (round-tenth (/ abs-n 1000.0)) "k")
      :else (str sign (round-tenth (/ abs-n 1000000.0)) "M"))))

(defn- dataset-enabled-label
  [enabled?]
  (if enabled? "Enabled" "Disabled"))

(defn materialization-node-id
  [tenant dataset-id tenant-config-key pipeline-id]
  (str "dataset/"
       tenant
       "/"
       dataset-id
       "/"
       (if (str/blank? (or tenant-config-key ""))
         "_"
         tenant-config-key)
       "/"
       pipeline-id
       "/materialization"))

(defn dataset-base-node-id
  [tenant dataset-id]
  (str "dataset/" tenant "/" dataset-id "/default"))

(defn diagnostics-url
  [_]
  "/config/diagnostics")

(defn- dataset-tenant-ids
  [dataset]
  (->> (:pipelines dataset)
       (keep :tenant)
       distinct
       vec))

(defn- dataset-diagnostics-url
  [_dataset]
  "/config/diagnostics")

(defn- dataset-runtime-selections
  [dataset]
  (->> (dataset-tenant-ids dataset)
       (keep (fn [tenant]
               (when (and (seq (str/trim (or tenant "")))
                          (seq (str/trim (or (:id dataset) ""))))
                 {:tenant tenant
                  :root :dataset
                  :node-id (dataset-base-node-id tenant (:id dataset))})))
       vec))

(defn- selectable-runtime-pipelines
  [dataset]
  (->> (:pipelines dataset)
       (filter #(= 1 (:context-count %)))
       (sort-by (juxt :tenant :tenant-config-key :name :id))
       vec))

(defn- runtime-pipeline-selection-id
  [pipeline]
  (str (:tenant pipeline)
       "::"
       (or (:tenant-config-key pipeline) "_")
       "::"
       (:id pipeline)))

(defn- runtime-pipeline-selection-label
  [pipeline]
  (str (or (:name pipeline) (:id pipeline))
       " ["
       (or (:tenant pipeline) "_")
       "/"
       (branch-key-label (:tenant-config-key pipeline))
       "]"))

(defn- pipeline-selection-node-id
  [{:keys [dataset-id tenant tenant-config-key id pipeline-id contexts]
    existing-node-id :materialization-node-id}]
  (or existing-node-id
      (:node-id (first contexts))
      (materialization-node-id tenant
                               dataset-id
                               tenant-config-key
                               (or pipeline-id id))))

(defn- encode-runtime-pipeline-selection-ids
  [selection-ids]
  (->> (or selection-ids [])
       (map str)
       (remove str/blank?)
       distinct
       sort
       (str/join ",")))

(defn- normalize-runtime-pipeline-selection-ids
  [selection-ids]
  (->> (or selection-ids [])
       (map str)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn- decode-runtime-pipeline-selection-ids
  [selection-ids]
  (normalize-runtime-pipeline-selection-ids
   (str/split (or selection-ids "") #",")))

(defn- dataset-scoped-view?
  [view]
  (or (= view :dataset-detail)
      (= view :create-pipeline)
      (= view :edit-pipeline)))

(defn- pipeline-console-url-state
  [query-params]
  (let [dataset-id (some-> (get query-params "dataset") str str/trim)
        runtime-selected-pipeline-ids (decode-runtime-pipeline-selection-ids
                                       (get query-params "pipelines"))]
    (cond-> {:view :list}
      (seq dataset-id) (assoc :view :dataset-detail
                              :selected-dataset-id dataset-id)
      (seq runtime-selected-pipeline-ids) (assoc :runtime-selected-pipeline-ids
                                                 runtime-selected-pipeline-ids))))

(defn- runtime-pipeline-selected?
  [selected-selection-ids selection-id]
  (boolean (some #(= (str %) selection-id) (or selected-selection-ids []))))

(defn- pipeline-console-query-params
  [{:keys [view selected-dataset-id runtime-selected-pipeline-ids]}]
  (let [dataset-id (some-> selected-dataset-id str str/trim)
        encoded-runtime-pipeline-ids (encode-runtime-pipeline-selection-ids
                                      runtime-selected-pipeline-ids)]
    (cond-> {}
      (and (dataset-scoped-view? view)
           (seq dataset-id))
      (assoc "dataset" dataset-id)

      (and (dataset-scoped-view? view)
           (seq dataset-id)
           (seq encoded-runtime-pipeline-ids))
      (assoc "pipelines" encoded-runtime-pipeline-ids))))

#?(:cljs
   (def ^:private pipeline-console-query-keys
     ["dataset" "pipelines"]))

#?(:cljs
   (defn- current-query-params
     []
     (let [search-params (js/URLSearchParams. (.-search js/window.location))]
       (into {}
             (map (fn [[k v]] [(str k) (str v)]))
             (.entries search-params)))))

#?(:cljs
   (defn- replace-pipeline-console-query-params!
     [query-params]
     (let [url (js/URL. (.-href js/window.location))]
       (doseq [k pipeline-console-query-keys]
         (.delete (.-searchParams url) k))
       (doseq [[k v] query-params]
         (when (seq (str/trim (or v "")))
           (.set (.-searchParams url) k v)))
       (.replaceState js/window.history nil "" (str (.-pathname url)
                                                    (.-search url)
                                                    (.-hash url))))))

(defn- sync-pipeline-console-url-from-state!
  [state]
  #?(:cljs (replace-pipeline-console-query-params!
            (pipeline-console-query-params state))
     :clj nil))

(defn- assoc-pipeline-console-state!
  [!state & kvs]
  (let [new-state (apply assoc @!state kvs)]
    (reset! !state new-state)
    (sync-pipeline-console-url-from-state! new-state)
    new-state))

(defn- update-pipeline-console-state!
  [!state key f & args]
  (let [new-state (apply update @!state key f args)]
    (reset! !state new-state)
    (sync-pipeline-console-url-from-state! new-state)
    new-state))

(defn- restore-pipeline-console-url-state!
  [!state !restored?]
  ;; Keep the reader conditional inside the helper body. Electric needs the same
  ;; form shape on the :clj and :cljs sides, or frame slot allocation diverges.
  #?(:cljs
     (when-not @!restored?
       (reset! !restored? true)
       (js/setTimeout
        (fn []
          (let [url-state (pipeline-console-url-state (current-query-params))]
            (when (not= {:view :list} url-state)
              (let [new-state (merge @!state url-state)]
                (reset! !state new-state)
                (sync-pipeline-console-url-from-state! new-state)))))
        0))
     :clj nil))

(defn- dataset-runtime-comparison-selections
  [dataset selected-selection-ids]
  (vec
   (concat
    (dataset-runtime-selections dataset)
    (->> (selectable-runtime-pipelines dataset)
         (keep (fn [pipeline]
                 (let [selection-id (runtime-pipeline-selection-id pipeline)]
                   (when (runtime-pipeline-selected? selected-selection-ids selection-id)
                     {:tenant (:tenant pipeline)
                      :root :dataset
                      :node-id (pipeline-selection-node-id pipeline)
                      :column-label (runtime-pipeline-selection-label pipeline)
                      :selection-id selection-id}))))))))

(defn- pipeline-editor-selection
  [{:keys [tenant] :as pipeline}]
  (when (and (seq (str/trim (or tenant "")))
             (seq (str/trim (or (:dataset-id pipeline) "")))
             (seq (str/trim (or (:pipeline-id pipeline) ""))))
    {:tenant tenant
     :root :dataset
     :node-id (pipeline-selection-node-id pipeline)
     :selection-id (str tenant
                        "::"
                        (or (:tenant-config-key pipeline) "_")
                        "::"
                        (:pipeline-id pipeline))}))

;; =============================================================================
;; Server Helpers
;; =============================================================================

#?(:clj
   (do
     (defn- ensure-operator-admin!
       [conn user-id]
       (when-not (perms/is-admin? @conn user-id)
         (throw (ex-info "Permission denied - admin required" {:user-id user-id}))))

     (defn- execution-sort-key
       [ts]
       (cond
         (instance? java.util.Date ts) (.toString (.toInstant ^java.util.Date ts))
         (string? ts) ts
         :else nil))

     (defn- serialize-timestamp
       [ts]
       (cond
         (instance? java.util.Date ts) (.toString (.toInstant ^java.util.Date ts))
         (string? ts) ts
         (some? ts) (str ts)
         :else nil))

     (defn- summarize-execution
       [execution]
       {:id (:pipeline-execution/id execution)
        :pipeline-id (:pipeline-execution/pipeline-id execution)
        :status (:pipeline-execution/status execution)
        :started-at (serialize-timestamp (:pipeline-execution/started-at execution))
        :completed-at (serialize-timestamp (:pipeline-execution/completed-at execution))
        :documents-processed (:pipeline-execution/documents-processed execution)
        :documents-failed (:pipeline-execution/documents-failed execution)
        :documents-total (:pipeline-execution/documents-total execution)
        :last-progress-at (serialize-timestamp (:pipeline-execution/last-progress-at execution))
        :error-message (:pipeline-execution/error-message execution)
        :started-by (:pipeline-execution/started-by execution)
        :sort-key (execution-sort-key (:pipeline-execution/started-at execution))})

     (defn- summarize-pipeline
       [db master-key pipeline-record contexts]
       (let [primary-context (first contexts)
             resolved-config (when primary-context
                               (pipeline/get-dataset db
                                                     (:tenant primary-context)
                                                     (:tenant-config-key primary-context)
                                                     (:dataset.pipeline/id pipeline-record)
                                                     master-key))
             effective-record (config-db/effective-dataset-pipeline-record
                               db
                               pipeline-record
                               {:master-key master-key
                                :contexts contexts})
             missing-properties (when resolved-config
                                  (->> (materialization/required-execution-properties resolved-config)
                                       keys
                                       (remove #(contains? resolved-config %))
                                       sort
                                       vec))
             execution-contexts (mapv (fn [{:keys [tenant tenant-config-key] :as context}]
                                        (let [external-pipeline-id (pipeline/make-pipeline-id tenant
                                                                                              tenant-config-key
                                                                                              (:dataset.pipeline/id pipeline-record))
                                              recent-executions (->> (executor/list-executions db external-pipeline-id)
                                                                     (take 3)
                                                                     (mapv summarize-execution))]
                                          (assoc context
                                                 :external-pipeline-id external-pipeline-id
                                                 :latest-execution (first recent-executions)
                                                 :recent-executions recent-executions)))
                                      contexts)
             recent-executions (->> execution-contexts
                                    (mapcat (fn [{:keys [tenant tenant-config-key external-pipeline-id recent-executions]}]
                                              (map #(assoc %
                                                           :tenant tenant
                                                           :tenant-config-key tenant-config-key
                                                           :external-pipeline-id external-pipeline-id)
                                                   recent-executions)))
                                    (sort-by :sort-key #(compare %2 %1))
                                    (take 3)
                                    vec)
             latest-execution (first recent-executions)
             status (let [execution-statuses (keep :status (map :latest-execution execution-contexts))]
                      (cond
                        (false? (:dataset.pipeline/enabled? pipeline-record)) :disabled
                        (> (count contexts) 1) :multiple-contexts
                        (seq missing-properties) :missing-config
                        (some #{:failed} execution-statuses) :failed
                        (some #{:running} execution-statuses) :running
                        (some #{:completed} execution-statuses) :ready
                        (empty? contexts) :unbound
                        :else :configured))]
         (cond-> {:id (:dataset.pipeline/id pipeline-record)
                  :dataset-id (get-in pipeline-record [:dataset.pipeline/dataset :dataset/id])
                  :name (or (:dataset.pipeline/effective-name effective-record)
                            (:dataset.pipeline/id pipeline-record))
                  :description (:description resolved-config)
                  :source-type (:dataset.pipeline/effective-source-type effective-record)
                  :enabled? (:dataset.pipeline/enabled? pipeline-record)
                  :tenant (:tenant primary-context)
                  :tenant-config-key (:tenant-config-key primary-context)
                  :materialization-node-id (:node-id primary-context)
                  :context-count (count contexts)
                  :contexts execution-contexts
                  :missing-contract-properties missing-properties
                  :status status
                  :latest-execution latest-execution
                  :recent-executions recent-executions
                  :last-run-at (:started-at latest-execution)
                  :changed-record-count (:documents-processed latest-execution)
                  :failed-record-count (:documents-failed latest-execution)}
           (:dataset.pipeline/effective-name-ambiguous? effective-record)
           (assoc :name-ambiguous? true)

           (:dataset.pipeline/effective-source-type-ambiguous? effective-record)
           (assoc :source-type-ambiguous? true))))

     (defn- summarize-dataset-status
       [pipelines]
       (cond
         (empty? pipelines) :needs-pipeline
         (some #(= :failed (:status %)) pipelines) :failed
         (some #(= :running (:status %)) pipelines) :running
         (every? #(contains? #{:ready :disabled} (:status %)) pipelines) :ready
         :else :configured))

     (defn- summarize-dataset
       [db master-key contexts-by-pipeline-id dataset-record]
       (let [dataset-id (:dataset/id dataset-record)
             pipelines (->> (config-db/list-dataset-pipelines db dataset-id)
                            (mapv (fn [pipeline-record]
                                    (summarize-pipeline db
                                                        master-key
                                                        pipeline-record
                                                        (get contexts-by-pipeline-id
                                                             (:dataset.pipeline/id pipeline-record)
                                                             [])))))
             latest-run-at (->> pipelines
                                (keep :latest-execution)
                                (sort-by :sort-key #(compare %2 %1))
                                first
                                :started-at)
             active-pipeline-count (count (filter #(= :running (:status %)) pipelines))
             failed-pipeline-count (count (filter #(= :failed (:status %)) pipelines))]
         {:id dataset-id
          :name (:dataset/name dataset-record)
          :description (:dataset/description dataset-record)
          :enabled? (:dataset/enabled? dataset-record)
          :pipeline-count (count pipelines)
          :active-pipeline-count active-pipeline-count
          :failed-pipeline-count failed-pipeline-count
          :last-run-at latest-run-at
          :status (summarize-dataset-status pipelines)
          :pipelines pipelines}))

     (defn- load-operator-datasets
       ;; The `_trigger` arg lets Electric track reactive dependencies (the
       ;; `(let [_ x] body)` pattern doesn't reliably create deps because
       ;; the compiler treats `_` bindings as unused). Callers pass a vector
       ;; of the values they want the load to react to.
       ([] (load-operator-datasets nil))
       ([_trigger]
        (let [conn (db/get-conn)
              db @conn
              master-key (config-core/get-master-key)
              contexts-by-pipeline-id (config-db/materialization-contexts-by-pipeline-id db)]
          (->> (pipeline/list-dataset-records db)
               (mapv #(summarize-dataset db master-key contexts-by-pipeline-id %))))))

     (defn- load-operator-dataset
       ;; `_trigger` participates in Electric reactive deps — see
       ;; load-operator-datasets above for rationale.
       ([dataset-id] (load-operator-dataset dataset-id nil))
       ([dataset-id _trigger]
        (let [conn (db/get-conn)
              db @conn
              master-key (config-core/get-master-key)
              contexts-by-pipeline-id (config-db/materialization-contexts-by-pipeline-id db)]
          (when-let [dataset-record (pipeline/get-dataset-record db dataset-id)]
            (summarize-dataset db master-key contexts-by-pipeline-id dataset-record)))))

     (defn- create-dataset!
       [user-id name description]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (pipeline/create-dataset! conn (cond-> {:name name}
                                          (seq (str/trim (or description "")))
                                          (assoc :description description)))))

     (defn- update-dataset!
       [user-id {:keys [dataset-id name description enabled?]}]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (pipeline/update-dataset! conn {:dataset-id dataset-id
                                         :name name
                                         :description description
                                         :enabled? enabled?})))

     (defn- create-pipeline!
       [user-id {:keys [tenant tenant-config-key dataset-id pipeline-id name description source-type]}]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (pipeline/create-pipeline! conn {:tenant tenant
                                          :tenant-config-key tenant-config-key
                                          :dataset-id dataset-id
                                          :pipeline-name pipeline-id
                                          :properties (cond-> {:name name}
                                                        (seq (str/trim (or description ""))) (assoc :description description)
                                                        source-type (assoc :source-type source-type))
                                          :master-key (config-core/get-master-key)})))

     (defn- update-pipeline!
       [user-id {:keys [tenant tenant-config-key pipeline-id name description source-type]}]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (pipeline/update-pipeline! conn {:tenant tenant
                                          :tenant-config-key tenant-config-key
                                          :pipeline-name pipeline-id
                                          :properties (cond-> {}
                                                        (some? name) (assoc :name name)
                                                        (some? description) (assoc :description description)
                                                        source-type (assoc :source-type source-type))
                                          :master-key (config-core/get-master-key)})))

     (defn- delete-pipeline!
       [user-id tenant tenant-config-key pipeline-id]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (pipeline/soft-delete-pipeline! conn tenant tenant-config-key pipeline-id)))

     (defn- execute-pipeline!
       [user-id tenant tenant-config-key pipeline-id]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (executor/execute-pipeline-async! conn
                                           tenant
                                           tenant-config-key
                                           pipeline-id
                                           (config-core/get-master-key)
                                           user-id)))

     (defn- stop-pipeline-execution!
       "Cancel a running pipeline execution. Returns true if a running
        execution was found and cancelled, false otherwise (e.g. the
        execution already completed by the time the user clicked Stop)."
       [user-id execution-id]
       (let [conn (db/get-conn)]
         (ensure-operator-admin! conn user-id)
         (executor/cancel-execution! conn execution-id)))))

;; =============================================================================
;; Components
;; =============================================================================

(e/defn DatasetList
  [!state !refresh-counter]
  (let [refresh-counter (e/watch !refresh-counter)
        ;; Auto-refresh on every execution lifecycle transition (start,
        ;; complete, fail, cancel). The executor bumps this on each one.
        ;; Counter-only — far cheaper than watching the whole DB conn,
        ;; which would re-fire on every progress-flush transaction too.
        lifecycle-version (e/server (e/watch executor/!execution-events-version))
        ;; Pass the trigger as a real arg so Electric's static dependency
        ;; analysis tracks it. (The `(let [_ x] body)` pattern doesn't
        ;; reliably create reactive deps — `_` bindings get optimized out.)
        datasets (e/server
                  (load-operator-datasets
                   [(e/client refresh-counter) lifecycle-version]))]
    (e/client
      (dom/div
        (dom/props {:style container-style})

        (dom/div
          (dom/props {:style header-style})
          (dom/div
            (ks/Heading {:level 2} (e/fn [] (dom/text "Datasets")))
            (dom/div
              (dom/props {:style {:color "#6b7280" :font-size "0.875rem"}})
              (dom/text "Datasets are the read targets. Pipelines feed data into them.")))
          (dom/div
            (dom/button
              (dom/props {:style (merge button-secondary-style {:margin-right "0.5rem"})})
              (dom/text "Refresh")
              (let [[tok _] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !refresh-counter inc)
                  (tok))))
            (dom/button
              (dom/props {:style button-style})
              (dom/text "+ New Dataset")
              (let [[tok _] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !state assoc
                         :view :create-dataset
                         :dataset-form {:name "" :description ""})
                  (tok))))))

        (if (empty? datasets)
          (dom/div
            (dom/props {:style card-style})
            (dom/text "No datasets found. Create a dataset, then add at least one pipeline to feed it."))
          (dom/div
            (dom/props {:style card-style})
            (dom/table
              (dom/props {:style table-style})
              (dom/thead
                (dom/tr
                  (dom/th (dom/props {:style th-style}) (dom/text "Dataset"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Description"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Pipelines"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Activity"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))
              (dom/tbody
                (e/for-by :id [dataset datasets]
                  (dom/tr
                    (dom/td
                      (dom/props {:style td-style})
                      (dom/div (dom/text (:name dataset)))
                      (dom/div
                        (dom/props {:style helper-text-style})
                        (dom/text (:id dataset))))
                    (dom/td
                      (dom/props {:style td-style})
                      (dom/text (or (:description dataset) "-")))
                    (dom/td
                      (dom/props {:style td-style})
                      (dom/div (dom/text (str (:pipeline-count dataset) " total")))
                      (dom/div
                        (dom/props {:style submetric-style})
                        (dom/text (str (:active-pipeline-count dataset) " running, "
                                       (:failed-pipeline-count dataset) " failed"))))
                    (dom/td
                      (dom/props {:style td-style})
                      (dom/span
                        (dom/props {:style (status-badge-style (:status dataset))})
                        (dom/text (status-label (:status dataset))))
                      (dom/div
                        (dom/props {:style helper-text-style})
                        (dom/text (if-let [last-run-at (:last-run-at dataset)]
                                    (str "Last run " (format-display-timestamp last-run-at))
                                    "No pipeline runs yet."))))
                    (dom/td
                      (dom/props {:style td-style})
                      (dom/button
                        (dom/props {:style button-secondary-style})
                        (dom/text "Open")
                        (let [[tok _] (e/Token (dom/On "click" identity nil))]
                          (when tok
                    (assoc-pipeline-console-state! !state
                                                   :view :dataset-detail
                                                   :selected-dataset-id (:id dataset)
                                                   :runtime-selected-pipeline-ids []
                                                   :editing-dataset? false
                                                   :dataset-form nil
                                                   :pipeline-form nil)
                            (tok)))))))))))))))

(e/defn DatasetForm
  [!state !refresh-counter user-id]
  (e/client
    (let [form (or (:dataset-form (e/watch !state)) {})
          name (or (:name form) "")
          description (or (:description form) "")]
      (dom/div
        (dom/props {:style container-style})

        (dom/div
          (dom/props {:style header-style})
          (ks/Heading {:level 2} (e/fn [] (dom/text "Create Dataset")))
          (dom/button
            (dom/props {:style button-secondary-style})
            (dom/text "Back")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (assoc-pipeline-console-state! !state :view :list)
                (tok)))))

        (dom/div
          (dom/props {:style card-style})

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Dataset Name"))
            (dom/input
              (dom/props {:style input-style
                          :type "text"
                          :value name
                          :placeholder "e.g., Public Docs"})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:dataset-form :name] (.. ev -target -value)))
                      nil)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Description"))
            (dom/textarea
              (dom/props {:style (merge input-style {:min-height "90px"})
                          :value description
                          :placeholder "What this dataset is for..."})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:dataset-form :description] (.. ev -target -value)))
                      nil)))

          (dom/button
            (dom/props {:style (if (str/blank? (str/trim name))
                                 button-disabled-style
                                 button-style)})
            (dom/text "Create Dataset")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (when-not (str/blank? (str/trim name))
                  (let [created (e/server
                                  (let [uid (e/client user-id)
                                        dataset-name (e/client name)
                                        dataset-description (e/client description)]
                                    (create-dataset! uid dataset-name dataset-description)))]
                    (swap! !refresh-counter inc)
                    (assoc-pipeline-console-state! !state
                                                   :view :dataset-detail
                                                   :selected-dataset-id (:dataset/id created)
                                                   :runtime-selected-pipeline-ids []
                                                   :dataset-form {:name "" :description ""})))
                (tok)))))))))

(defn ^:no-doc derive-dataset-detail-state
  "Pure-CLJ derivation of dataset-detail view state from `state` and `dataset`."
  [state dataset]
  (let [runtime-pipeline-options (selectable-runtime-pipelines dataset)
        selected-runtime-pipeline-ids
        (->> (or (:runtime-selected-pipeline-ids state) [])
             (filter (fn [selection-id]
                       (some #(= selection-id (runtime-pipeline-selection-id %))
                             runtime-pipeline-options)))
             normalize-runtime-pipeline-selection-ids)
        runtime-selections (dataset-runtime-comparison-selections dataset selected-runtime-pipeline-ids)
        editing-dataset? (true? (:editing-dataset? state))
        dataset-form (or (:dataset-form state) {})
        form-name (if (contains? dataset-form :name)
                    (or (:name dataset-form) "")
                    (or (:name dataset) ""))
        form-description (if (contains? dataset-form :description)
                           (or (:description dataset-form) "")
                           (or (:description dataset) ""))
        form-enabled? (if (contains? dataset-form :enabled?)
                        (:enabled? dataset-form)
                        (:enabled? dataset))
        dirty? (or (not= form-name (or (:name dataset) ""))
                   (not= form-description (or (:description dataset) ""))
                   (not= form-enabled? (:enabled? dataset)))]
    {:runtime-pipeline-options runtime-pipeline-options
     :selected-runtime-pipeline-ids selected-runtime-pipeline-ids
     :has-runtime-pipeline-options? (seq runtime-pipeline-options)
     :runtime-selections runtime-selections
     :editing-dataset? editing-dataset?
     :form-name form-name
     :form-description form-description
     :form-enabled? form-enabled?
     :save-disabled? (or (str/blank? (str/trim form-name)) (not dirty?))}))

(e/defn DatasetDetailHeader
  "Top header for the dataset-detail view: title, id, action buttons."
  [{:keys [dataset dataset-id editing-dataset? !state !refresh-counter]}]
  (e/client
   (dom/div
    (dom/props {:style header-style})
    (dom/div
     (ks/Heading {:level 2} (e/fn [] (dom/text (or (:name dataset) "Dataset"))))
     (dom/div
      (dom/props {:style helper-text-style})
      (dom/text (or (:id dataset) ""))))
    (dom/div
     (dom/button
      (dom/props {:style (merge button-secondary-style {:margin-right "0.5rem"})})
      (dom/text "Refresh")
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok (swap! !refresh-counter inc) (tok))))
     (dom/button
      (dom/props {:style (merge button-secondary-style {:margin-right "0.5rem"})})
      (dom/text (if editing-dataset? "Cancel Edit" "Edit Dataset"))
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok
          (if editing-dataset?
            (swap! !state assoc :editing-dataset? false :dataset-form nil)
            (swap! !state assoc
                   :editing-dataset? true
                   :dataset-form {:name (or (:name dataset) "")
                                  :description (or (:description dataset) "")
                                  :enabled? (:enabled? dataset)}))
          (tok))))
     (dom/button
      (dom/props {:style (merge button-style {:margin-right "0.5rem"})})
      (dom/text "+ New Pipeline")
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok
          (assoc-pipeline-console-state! !state
                                         :view :create-pipeline
                                         :pipeline-form {:dataset-id dataset-id
                                                         :tenant ""
                                                         :tenant-config-key ""
                                                         :pipeline-id ""
                                                         :name ""
                                                         :description ""
                                                         :source-type :website})
          (tok))))
     (dom/button
      (dom/props {:style button-secondary-style})
      (dom/text "Back")
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok
          (assoc-pipeline-console-state! !state
                                         :view :list
                                         :selected-dataset-id nil
                                         :runtime-selected-pipeline-ids []
                                         :editing-dataset? false
                                         :dataset-form nil)
          (tok))))))))

(e/defn DatasetInfoCardEditForm
  "Edit form for dataset name / description / lifecycle."
  [{:keys [dataset-id form-name form-description form-enabled? save-disabled?
           !state !refresh-counter user-id]}]
  (e/client
   (dom/div
    (dom/props {:style form-group-style})
    (dom/label (dom/props {:style label-style}) (dom/text "Dataset Name"))
    (dom/input
     (dom/props {:style input-style
                 :type "text"
                 :value form-name
                 :placeholder "e.g., Public Docs"})
     (dom/On "input"
             (fn [ev] (swap! !state assoc-in [:dataset-form :name] (.. ev -target -value)))
             nil)))
   (dom/div
    (dom/props {:style form-group-style})
    (dom/label (dom/props {:style label-style}) (dom/text "Description"))
    (dom/textarea
     (dom/props {:style (merge input-style {:min-height "90px"})
                 :value form-description
                 :placeholder "What this dataset is for..."})
     (dom/On "input"
             (fn [ev] (swap! !state assoc-in [:dataset-form :description] (.. ev -target -value)))
             nil)))
   (dom/div
    (dom/props {:style form-group-style})
    (dom/label (dom/props {:style label-style}) (dom/text "Lifecycle State"))
    (dom/select
     (dom/props {:style input-style
                 :value (if form-enabled? "enabled" "disabled")})
     (dom/On "change"
             (fn [ev] (swap! !state assoc-in [:dataset-form :enabled?]
                             (= "enabled" (.. ev -target -value))))
             nil)
     (dom/option (dom/props {:value "enabled"}) (dom/text "Enabled"))
     (dom/option (dom/props {:value "disabled"}) (dom/text "Disabled")))
    (dom/div
     (dom/props {:style helper-text-style})
     (dom/text "Controls whether this dataset is available as an active parent resource.")))
   (dom/div
    (dom/button
     (dom/props {:style (if save-disabled?
                          (merge button-disabled-style {:margin-right "0.5rem"})
                          (merge button-style {:margin-right "0.5rem"}))
                 :disabled save-disabled?})
     (dom/text "Save Dataset")
     (let [[tok _] (e/Token (dom/On "click" identity nil))]
       (when tok
         (when-not save-disabled?
           (let [payload {:dataset-id dataset-id
                          :name form-name
                          :description form-description
                          :enabled? form-enabled?}]
             (e/server
              (let [uid (e/client user-id)
                    update-payload (e/client payload)]
                (update-dataset! uid update-payload)))
             (swap! !refresh-counter inc)
             (swap! !state assoc :editing-dataset? false :dataset-form nil)))
         (tok))))
    (dom/button
     (dom/props {:style button-secondary-style})
     (dom/text "Cancel")
     (let [[tok _] (e/Token (dom/On "click" identity nil))]
       (when tok
         (swap! !state assoc :editing-dataset? false :dataset-form nil)
         (tok)))))))

(e/defn DatasetInfoCardView
  "Read-only dataset info: enabled badge + description."
  [{:keys [dataset]}]
  (e/client
   (dom/div
    (dom/span
     (dom/props {:style (if (:enabled? dataset)
                          badge-success-style
                          badge-danger-style)})
     (dom/text (dataset-enabled-label (:enabled? dataset)))))
   (dom/div
    (dom/props {:style (merge helper-text-style {:margin-top "0.5rem"})})
    (dom/text "Agents read this dataset. Pipelines below feed data into it."))
   (dom/div
    (dom/props {:style {:margin-top "0.5rem"}})
    (dom/text (or (:description dataset) "No description.")))))

(e/defn DatasetRuntimeConfig
  "Runtime Configuration callout: selector grid + FocusedInheritanceEditor."
  [{:keys [dataset runtime-pipeline-options has-runtime-pipeline-options?
           selected-runtime-pipeline-ids runtime-selections
           !state !refresh-counter user-id]}]
  (e/client
   (dom/div
    (dom/props {:style dataset-runtime-callout-style})
    (dom/div
     (dom/props {:style section-title-style})
     (dom/text "Runtime Configuration"))
    (if (seq (dataset-runtime-selections dataset))
      (do
        (dom/div
         (dom/props {:style dataset-runtime-selector-style})
         (if has-runtime-pipeline-options?
           (dom/div
            (dom/props {:style helper-text-style})
            (dom/text "Select one or more child pipelines to add their materialization nodes as comparison columns beside the dataset root."))
           (dom/div
            (dom/props {:style helper-text-style})
            (dom/text "No single-context child pipelines are available for comparison yet.")))
         (when has-runtime-pipeline-options?
           (dom/div
            (dom/props {:style {:display "grid"
                                :grid-template-columns "repeat(auto-fit, minmax(220px, 1fr))"
                                :gap "0.5rem"
                                :margin-top "0.75rem"}})
            (e/for-by runtime-pipeline-selection-id [pipeline runtime-pipeline-options]
                      (let [selection-id (runtime-pipeline-selection-id pipeline)
                            selected? (runtime-pipeline-selected? selected-runtime-pipeline-ids selection-id)]
                        (dom/label
                         (dom/props {:style {:display "flex"
                                             :align-items "flex-start"
                                             :gap "0.5rem"
                                             :padding "0.625rem 0.75rem"
                                             :border-radius "6px"
                                             :border (if selected? "1px solid #60a5fa" "1px solid #d1d5db")
                                             :background (if selected? "#eff6ff" "#ffffff")
                                             :cursor "pointer"}})
                         (dom/input
                          (dom/props {:type "checkbox"
                                      :checked selected?})
                          (dom/On "change"
                                  (fn [ev]
                                    (let [checked? (.. ev -target -checked)]
                                      (update-pipeline-console-state! !state
                                                                      :runtime-selected-pipeline-ids
                                                                      (fn [selected]
                                                                        (let [selected (normalize-runtime-pipeline-selection-ids selected)
                                                                              selection-id (str selection-id)]
                                                                          (if checked?
                                                                            (normalize-runtime-pipeline-selection-ids
                                                                             (conj selected selection-id))
                                                                            (vec (remove #(= % selection-id) selected))))))))
                                  nil))
                         (dom/div
                          (dom/div (dom/text (or (:name pipeline) (:id pipeline))))
                          (dom/div
                           (dom/props {:style helper-text-style})
                           (dom/text (str (:tenant pipeline)
                                          " / "
                                          (branch-key-label (:tenant-config-key pipeline))
                                          " / "
                                          (:id pipeline)))))))))))
        (FocusedInheritanceEditor runtime-selections
                                  !refresh-counter
                                  user-id
                                  "No dataset runtime nodes were available for the current tenant contexts."
                                  :selected-nodes))
      (dom/div
       (dom/props {:style (merge helper-text-style {:margin-top "0.5rem"})})
       (dom/text "Add a child pipeline first so the dataset has a tenant context and canonical dataset root to edit."))))))

(e/defn DatasetSummaryGrid
  "Pipeline-count / running / failed / last-run summary grid."
  [{:keys [dataset]}]
  (e/client
   (dom/div
    (dom/props {:style dataset-summary-grid-style})
    (dom/div
     (dom/props {:style {:padding "0.75rem" :background "#f8fafc" :border-radius "6px"}})
     (dom/div (dom/props {:style helper-text-style}) (dom/text "Pipelines"))
     (dom/div (dom/text (str (:pipeline-count dataset)))))
    (dom/div
     (dom/props {:style {:padding "0.75rem" :background "#f8fafc" :border-radius "6px"}})
     (dom/div (dom/props {:style helper-text-style}) (dom/text "Running"))
     (dom/div (dom/text (str (:active-pipeline-count dataset)))))
    (dom/div
     (dom/props {:style {:padding "0.75rem" :background "#f8fafc" :border-radius "6px"}})
     (dom/div (dom/props {:style helper-text-style}) (dom/text "Failed"))
     (dom/div (dom/text (str (:failed-pipeline-count dataset)))))
    (dom/div
     (dom/props {:style {:padding "0.75rem" :background "#f8fafc" :border-radius "6px"}})
     (dom/div (dom/props {:style helper-text-style}) (dom/text "Last run"))
     (dom/div (dom/text (or (format-display-timestamp (:last-run-at dataset)) "No runs yet")))))))

(defn- format-error-entry
  "Render one error event as a short single-line summary. Kept around
   as a fallback (copy-paste friendly); the live UI uses the structured
   ErrorEntry component below instead."
  [{:keys [kind chunk-id doc-num chunk-index location model triggered message]}]
  (let [parts (cond-> []
                kind (conj (name kind))
                location (conj (str location))
                (or chunk-id doc-num)
                (conj (str "chunk=" (or chunk-id "?")
                           (when doc-num (str " doc=" doc-num))
                           (when chunk-index (str "#" chunk-index))))
                model (conj (str "model=" model))
                (seq triggered) (conj (str "categories="
                                           (str/join ", "
                                                     (map (fn [{:keys [category severity]}]
                                                            (str category
                                                                 (when severity (str ":" severity))))
                                                          triggered))))
                message (conj (str "msg=" message)))]
    (str/join " · " parts)))

(def ^:private error-kind-color
  "Color map for the error-kind tag chip. Falls back to the generic
   failure color when an unknown :kind shows up — surface the unknown
   string so an operator can identify the new event class."
  {:recoverable-failure   "#b91c1c"
   :terminal-failure      "#991b1b"
   :store-error           "#b91c1c"
   :primary-model-error   "#dc2626"
   :fallback-model-error  "#dc2626"
   :content-filter        "#b45309"})

(def ^:private error-kind-label
  "Short human label for the error-kind tag chip."
  {:recoverable-failure   "recoverable"
   :terminal-failure      "TERMINAL"
   :store-error           "store error"
   :primary-model-error   "primary model"
   :fallback-model-error  "fallback model"
   :content-filter        "content filter"})

(defn- short-location
  "Trim a URL or path to its tail segment(s) for inline display. The full
   value is kept in the title attribute so hover reveals it."
  [loc]
  (when loc
    (let [s (str loc)]
      (if (<= (count s) 60)
        s
        (str "…" (subs s (- (count s) 59)))))))

(defn- truncate-message
  "Cap an error message at ~220 chars. Long Clojure stack-trace dumps in
   :msg make the error list unreadable otherwise."
  [msg]
  (let [s (str (or msg ""))]
    (if (<= (count s) 220)
      s
      (str (subs s 0 218) "…"))))

(e/defn StatCell
  "Render one cell of the stat grid: a dim label and a value, justified
   to opposite edges of the cell. `value-style` overrides the default
   neutral color when truthy (pass `nil` to keep the default).

   Must be an Electric component (not a plain defn) because the body
   uses Electric DOM macros — they expand into `$` calls and the
   compiler rejects them inside a regular Clojure function."
  [label value value-style]
  (dom/div
   (dom/props {:style stat-cell-style})
   (dom/span (dom/props {:style stat-label-style}) (dom/text label))
   (dom/span (dom/props {:style (or value-style stat-value-style)}) (dom/text value))))

(e/defn ExecutionDetails
  "Render execution counters, progress bar (when :running), and an
   expandable error list. Live-updates from the per-execution progress
   atom on the server; falls back to persisted DB counts when the
   in-memory entry isn't present (e.g., after a server restart, or for
   older completed executions that have aged out of the atom).

   Stats live in a 3×3 grid (docs · chunks/phrases · cache) so the row
   fits more signal in less vertical space than the old stacked
   sentences. Two cache layers are shown side-by-side: the markdown
   fetch cache (per document) and the search-phrases cache (per chunk)."
  [execution]
  (let [execution-id (:id execution)
        ;; Only ship this one execution's entry across the wire, not the
        ;; whole atom — `e/watch` makes the let re-evaluate on each tick.
        entry (e/server
               (when execution-id
                 (get (e/watch executor/!executions-progress) execution-id)))
        ;; Prefer the in-memory live numbers, fall back to last persisted.
        stored (or (:stored entry)
                   (:documents-processed execution)
                   0)
        prepared (or (:prepared entry) 0)
        failures (or (:failures entry)
                     (:documents-failed execution)
                     0)
        already-exists (or (:already-exists entry) 0)
        cache-hits (or (:cache-hits entry) 0)
        cache-misses (or (:cache-misses entry) 0)
        phrase-cache-hits (or (:phrase-cache-hits entry) 0)
        phrase-cache-misses (or (:phrase-cache-misses entry) 0)
        phrases-reused (or (:phrases-reused entry) 0)
        phrases-generated (or (:phrases-generated entry) 0)
        content-filtered (or (:content-filtered entry) 0)
        errors (or (:errors entry) [])
        total (or (:total-urls entry)
                  (:documents-total execution))
        completed (+ stored already-exists)
        chunks-processed (+ phrase-cache-hits phrase-cache-misses)
        running? (= :running (:status execution))
        pct (when (and total (pos? total))
              (let [raw (/ (* 100 completed) total)]
                (min 100 (long raw))))]
    (e/client
     (dom/div
      (dom/props {:style {:margin-top "0.35rem"}})
      ;; Progress bar only while running — determinate when we know the
      ;; total, indeterminate otherwise.
      (when running?
        (dom/div
         (dom/props {:style progress-bar-track-style})
         (if pct
           (dom/div
            (dom/props {:style (merge progress-bar-fill-style
                                      {:width (str pct "%")})}))
           (dom/div
            (dom/props {:style progress-bar-fill-indeterminate-style})))))

      ;; Single-line numeric headline above the grid: progress %, totals,
      ;; and prepared-but-not-yet-stored backlog. The grid carries the
      ;; rest of the detail.
      (dom/div
       (dom/props {:style submetric-style})
       (dom/text
        (cond
          (and total running?)
          (str completed " / " total " (" pct "%)"
               (when (pos? prepared) (str " · prepared " prepared)))

          running?
          (str completed " stored"
               (when (pos? prepared) (str " · " prepared " prepared"))
               " · awaiting total…")

          total
          (str "Processed " completed " / " total)

          :else
          (str "Processed " completed))))

      ;; 3×3 stat grid. Cells use compact-number formatting so 8,247
      ;; renders as 8.2k. Failure / content-filter cells get colored
      ;; values so they pop without needing icons.
      (dom/div
       (dom/props {:style stat-grid-style})
       ;; Row 1
       (StatCell "docs stored" (format-compact-number stored) nil)
       (StatCell "chunks proc'd" (format-compact-number chunks-processed) nil)
       (StatCell "phrases cached" (format-compact-number phrase-cache-hits) nil)
       ;; Row 2
       (StatCell "docs failed" (format-compact-number failures)
                 (if (pos? failures) stat-value-danger-style stat-value-muted-style))
       (StatCell "phrases gen" (format-compact-number phrase-cache-misses) nil)
       (StatCell "phrases reused" (format-compact-number phrases-reused) nil)
       ;; Row 3
       (StatCell "doc cache h/m"
                 (str (format-compact-number cache-hits) " / "
                      (format-compact-number cache-misses))
                 (when (zero? (+ cache-hits cache-misses)) stat-value-muted-style))
       (StatCell "new phrases" (format-compact-number phrases-generated) nil)
       (StatCell "filtered" (format-compact-number content-filtered)
                 (if (pos? content-filtered)
                   stat-value-warning-style
                   stat-value-muted-style)))

      ;; Expandable error list — shows the most-recent N error events with
      ;; structured detail (chunk-id, URL, category/severity, model, message).
      ;; Capped server-side at 50 events per execution.
      (when (seq errors)
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary
            {}
            (e/fn []
              (dom/span
               (dom/props {:style {:cursor "pointer" :font-weight "500"
                                   :color "#7f1d1d"}})
               (dom/text (str "View errors (" (count errors) ")")))))
           (ks/DetailsContent
            {}
            (e/fn []
              (dom/div
               (dom/props {:style {:max-height "320px" :overflow-y "auto"
                                   :margin-top "0.35rem"
                                   :display "flex"
                                   :flex-direction "column"
                                   :gap "0.35rem"}})
               (e/for [[idx err] (e/diff-by first (map-indexed vector errors))]
                 (let [kind (:kind err)
                       kind-color (or (error-kind-color kind) "#7f1d1d")
                       kind-label (or (error-kind-label kind)
                                      (some-> kind name))
                       loc (:location err)
                       loc-short (short-location loc)
                       chunk-id (:chunk-id err)
                       doc-num (:doc-num err)
                       chunk-index (:chunk-index err)
                       model (:model err)
                       triggered (:triggered err)
                       msg (truncate-message (:message err))]
                   ;; One mini-card per error, with a colored kind tag,
                   ;; the short location, a tight meta row of chunk/model
                   ;; details, the (potentially truncated) message, and
                   ;; — for content-filter events — the triggered categories.
                   (dom/div
                    (dom/props {:style {:padding "0.4rem 0.55rem"
                                        :background "#fef2f2"
                                        :border "1px solid #fecaca"
                                        :border-left (str "3px solid " kind-color)
                                        :border-radius "4px"
                                        :font-size "0.72rem"
                                        :line-height "1.35"}})
                    ;; Header line: kind chip + location
                    (dom/div
                     (dom/props {:style {:display "flex" :align-items "center"
                                         :gap "0.5rem" :margin-bottom "0.15rem"}})
                     (dom/span
                      (dom/props {:style {:padding "0.05rem 0.4rem"
                                          :background kind-color
                                          :color "#fff"
                                          :border-radius "3px"
                                          :font-size "0.65rem"
                                          :font-weight "600"
                                          :text-transform "uppercase"
                                          :letter-spacing "0.02em"
                                          :white-space "nowrap"}})
                      (dom/text (or kind-label "error")))
                     (when loc
                       (dom/span
                        (dom/props {:style {:color "#475569"
                                            :font-family "monospace"
                                            :font-size "0.7rem"
                                            :overflow "hidden"
                                            :text-overflow "ellipsis"
                                            :white-space "nowrap"
                                            :title (str loc)}})
                        (dom/text loc-short))))
                    ;; Meta row: chunk-id / doc#index / model (only when present)
                    (when (or chunk-id doc-num model)
                      (dom/div
                       (dom/props {:style {:color "#64748b"
                                           :font-family "monospace"
                                           :font-size "0.68rem"
                                           :margin-bottom "0.2rem"}})
                       (dom/text
                        (str/join
                         "  "
                         (cond-> []
                           chunk-id (conj (str "chunk " chunk-id))
                           doc-num  (conj (str "doc " doc-num
                                               (when chunk-index (str "#" chunk-index))))
                           model    (conj (str "model " model)))))))
                    ;; Triggered Azure categories (content-filter only)
                    (when (seq triggered)
                      (dom/div
                       (dom/props {:style {:color "#92400e"
                                           :font-size "0.68rem"
                                           :margin-bottom "0.2rem"}})
                       (dom/text
                        (str "categories: "
                             (str/join ", "
                                       (map (fn [{:keys [category severity]}]
                                              (str category
                                                   (when severity
                                                     (str " (" severity ")"))))
                                            triggered))))))
                    ;; Message body — monospace for easier scanning of
                    ;; quoted strings / paths; wraps so it doesn't blow
                    ;; out the column width.
                    (when (seq msg)
                      (dom/div
                       (dom/props {:style {:color "#7f1d1d"
                                           :font-family "monospace"
                                           :font-size "0.7rem"
                                           :white-space "pre-wrap"
                                           :word-break "break-word"}})
                       (dom/text msg))))))))))))))))

(e/defn DatasetPipelineRow
  "One row of the child-pipelines table."
  [{:keys [p !state !refresh-counter user-id]}]
  (e/client
   (let [has-single-context? (= 1 (:context-count p))
         execute-style (if has-single-context?
                         (merge button-style {:margin-right "0.5rem" :font-size "0.75rem"})
                         (merge button-disabled-style {:margin-right "0.5rem" :font-size "0.75rem"}))
         config-style (merge button-disabled-style {:margin-right "0.5rem" :font-size "0.75rem"})
         edit-style (if has-single-context?
                      (merge button-secondary-style {:margin-right "0.5rem" :font-size "0.75rem"})
                      (merge button-disabled-style {:margin-right "0.5rem" :font-size "0.75rem"}))
         delete-style (if has-single-context?
                        (merge button-danger-style {:font-size "0.75rem"})
                        (merge button-disabled-style {:font-size "0.75rem"}))
         latest-execution (:latest-execution p)
         ;; Stop is enabled only when the latest execution is actually
         ;; running. The row-level :status field aggregates across
         ;; multiple contexts, but :latest-execution :status is the
         ;; precise signal we need to find the execution-id to cancel.
         can-stop? (and has-single-context?
                        (= :running (:status latest-execution))
                        (:id latest-execution))
         stop-style (if can-stop?
                      (merge button-danger-style {:margin-right "0.5rem" :font-size "0.75rem"})
                      (merge button-disabled-style {:margin-right "0.5rem" :font-size "0.75rem"}))]
     (dom/tr
      (dom/td
       (dom/props {:style td-style})
       (dom/div (dom/text (:name p)))
       (dom/div (dom/props {:style helper-text-style}) (dom/text (:id p))))
      (dom/td (dom/props {:style td-style}) (dom/text (or (:tenant p) "-")))
      (dom/td (dom/props {:style td-style}) (dom/text (branch-key-label (:tenant-config-key p))))
      (dom/td (dom/props {:style td-style}) (dom/text (name (or (:source-type p) :unknown))))
      (dom/td
       (dom/props {:style td-style})
       (if latest-execution
         (do
           (dom/div
            (dom/span
             (dom/props {:style (status-badge-style (:status latest-execution))})
             (dom/text (status-label (:status latest-execution)))))
           (dom/div
            (dom/text (str "Last run " (format-display-timestamp (:started-at latest-execution)))))
           ;; Stat grid + (when running) progress bar + (when present)
           ;; expandable error list. Live-updates from the per-execution
           ;; progress atom; falls back to persisted DB counts otherwise.
           ;;
           ;; Recent-execution history was previously rendered here as
           ;; three sentence-per-line entries that ate ~80px of vertical
           ;; space. Dropped in the stat-grid redesign — the timestamp
           ;; of the latest run is already shown above; older runs are
           ;; only interesting when debugging a specific failure, which
           ;; the operator can do from the per-pipeline detail view.
           (ExecutionDetails latest-execution))
         (dom/div (dom/text "No executions yet"))))
      (dom/td
       (dom/props {:style td-style})
       (dom/span
        (dom/props {:style (status-badge-style (:status p))})
        (dom/text (status-label (:status p))))
       (when (seq (:missing-contract-properties p))
         (dom/div
          (dom/props {:style helper-text-style})
          (dom/text (str "Missing: " (str/join ", " (map name (:missing-contract-properties p)))))))
       (when (= :failed (:status p))
         (when-let [error-message (:error-message latest-execution)]
           (dom/div (dom/props {:style helper-text-style}) (dom/text error-message))))
       (when (> (:context-count p) 1)
         (dom/div
          (dom/props {:style helper-text-style})
          (dom/text "Use the API until contexts are normalized."))))
      (dom/td
       (dom/props {:style td-style})
       (dom/button
        (dom/props {:style execute-style :disabled (not has-single-context?)})
        (dom/text "Execute")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (when has-single-context?
              (e/server
               (let [uid (e/client user-id)
                     tenant (e/client (:tenant p))
                     tenant-config-key (e/client (:tenant-config-key p))
                     pipeline-id (e/client (:id p))]
                 (execute-pipeline! uid tenant tenant-config-key pipeline-id)))
              (swap! !refresh-counter inc))
            (tok))))
       (dom/button
        (dom/props {:style stop-style :disabled (not can-stop?)})
        (dom/text "Stop")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (when can-stop?
              (e/server
               (let [uid (e/client user-id)
                     execution-id (e/client (:id latest-execution))]
                 (stop-pipeline-execution! uid execution-id)))
              (swap! !refresh-counter inc))
            (tok))))
       (dom/button
        (dom/props {:style config-style :disabled true})
        (dom/text "Config")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok (tok))))
       (dom/button
        (dom/props {:style edit-style :disabled (not has-single-context?)})
        (dom/text "Edit")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (when has-single-context?
              (assoc-pipeline-console-state! !state
                                             :view :edit-pipeline
                                             :pipeline-form {:dataset-id (:dataset-id p)
                                                             :tenant (:tenant p)
                                                             :tenant-config-key (:tenant-config-key p)
                                                             :materialization-node-id (:materialization-node-id p)
                                                             :contexts (:contexts p)
                                                             :pipeline-id (:id p)
                                                             :name (:name p)
                                                             :description (:description p)
                                                             :source-type (:source-type p)}))
            (tok))))
       (dom/button
        (dom/props {:style delete-style :disabled (not has-single-context?)})
        (dom/text "Delete")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (when has-single-context?
              (e/server
               (let [uid (e/client user-id)
                     tenant (e/client (:tenant p))
                     tenant-config-key (e/client (:tenant-config-key p))
                     pipeline-id (e/client (:id p))]
                 (delete-pipeline! uid tenant tenant-config-key pipeline-id)))
              (swap! !refresh-counter inc))
            (tok)))))))))

(e/defn DatasetPipelinesTable
  "Child-pipelines table for the dataset."
  [{:keys [dataset !state !refresh-counter user-id]}]
  (e/client
   (dom/div
    (dom/props {:style card-style})
    (if (empty? (:pipelines dataset))
      (dom/text "No child pipelines yet. Create the first pipeline to feed this dataset.")
      (dom/table
       (dom/props {:style table-style})
       (dom/thead
        (dom/tr
         (dom/th (dom/props {:style th-style}) (dom/text "Pipeline"))
         (dom/th (dom/props {:style th-style}) (dom/text "Tenant"))
         (dom/th (dom/props {:style th-style}) (dom/text "Branch"))
         (dom/th (dom/props {:style th-style}) (dom/text "Type"))
         (dom/th (dom/props {:style th-style}) (dom/text "Execution"))
         (dom/th (dom/props {:style th-style}) (dom/text "Status"))
         (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))
       (dom/tbody
        (e/for-by :id [p (:pipelines dataset)]
                  (DatasetPipelineRow {:p p
                                       :!state !state
                                       :!refresh-counter !refresh-counter
                                       :user-id user-id}))))))))

(e/defn DatasetDetail
  [!state !refresh-counter user-id]
  (let [state (e/watch !state)
        refresh-counter (e/watch !refresh-counter)
        dataset-id (:selected-dataset-id state)
        lifecycle-version (e/server (e/watch executor/!execution-events-version))
        dataset (e/server
                 (load-operator-dataset (e/client dataset-id)
                                        [(e/client refresh-counter) lifecycle-version]))]
    (e/client
      (let [derived (derive-dataset-detail-state state dataset)
            {:keys [editing-dataset? form-name form-description form-enabled?
                    save-disabled? runtime-pipeline-options
                    has-runtime-pipeline-options? selected-runtime-pipeline-ids
                    runtime-selections]} derived]
        (when (not= selected-runtime-pipeline-ids
                    (normalize-runtime-pipeline-selection-ids
                     (:runtime-selected-pipeline-ids state)))
          (assoc-pipeline-console-state! !state
                                         :runtime-selected-pipeline-ids selected-runtime-pipeline-ids))
        (dom/div
          (dom/props {:style container-style})
          (DatasetDetailHeader {:dataset dataset
                                :dataset-id dataset-id
                                :editing-dataset? editing-dataset?
                                :!state !state
                                :!refresh-counter !refresh-counter})
          (dom/div
           (dom/props {:style dataset-detail-card-style})
           (if editing-dataset?
             (DatasetInfoCardEditForm {:dataset-id dataset-id
                                       :form-name form-name
                                       :form-description form-description
                                       :form-enabled? form-enabled?
                                       :save-disabled? save-disabled?
                                       :!state !state
                                       :!refresh-counter !refresh-counter
                                       :user-id user-id})
             (DatasetInfoCardView {:dataset dataset}))
           (DatasetRuntimeConfig {:dataset dataset
                                  :runtime-pipeline-options runtime-pipeline-options
                                  :has-runtime-pipeline-options? has-runtime-pipeline-options?
                                  :selected-runtime-pipeline-ids selected-runtime-pipeline-ids
                                  :runtime-selections runtime-selections
                                  :!state !state
                                  :!refresh-counter !refresh-counter
                                  :user-id user-id})
           (DatasetSummaryGrid {:dataset dataset})
           (DatasetPipelinesTable {:dataset dataset
                                   :!state !state
                                   :!refresh-counter !refresh-counter
                                   :user-id user-id})))))))


(e/defn PipelineForm
  [!state !refresh-counter user-id mode]
  (e/client
    (let [form (or (:pipeline-form (e/watch !state)) {})
          dataset-id (:dataset-id form)
          tenant (or (:tenant form) "")
          tenant-config-key (or (:tenant-config-key form) "")
          pipeline-id (or (:pipeline-id form) "")
          name (or (:name form) "")
          description (or (:description form) "")
          source-type (or (:source-type form) :website)
          editor-selection (pipeline-editor-selection form)
          materialization-node (when (and (not (str/blank? (str/trim tenant)))
                                          (not (str/blank? (str/trim dataset-id)))
                                          (not (str/blank? (str/trim pipeline-id))))
                                 (materialization-node-id tenant dataset-id tenant-config-key pipeline-id))
          save-disabled? (or (str/blank? (str/trim tenant))
                             (str/blank? (str/trim pipeline-id))
                             (str/blank? (str/trim name)))]
      (dom/div
        (dom/props {:style container-style})

        (dom/div
          (dom/props {:style header-style})
          (ks/Heading {:level 2}
                      (e/fn [] (dom/text (if (= mode :create)
                                           "Create Pipeline"
                                           "Edit Pipeline"))))
          (dom/button
            (dom/props {:style button-secondary-style})
            (dom/text "Back")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (assoc-pipeline-console-state! !state :view :dataset-detail)
                (tok)))))

        (dom/div
          (dom/props {:style card-style})

          (dom/div
            (dom/props {:style callout-style})
            (dom/div
              (dom/props {:style section-title-style})
              (dom/text "Configuration Model"))
            (dom/div
              (dom/text "Full pipeline configuration lives in the hierarchical dataset editor. This form only edits common pipeline metadata and chooses the materialization branch."))
            (dom/div
              (dom/props {:style helper-text-style})
              (dom/text "Use the focused editor below for source, chunking, storage, and other materialization settings."))
            (when materialization-node
              (dom/div
                (dom/props {:style (merge helper-text-style
                                          {:margin-top "0.5rem"
                                           :font-family "monospace"
                                           :word-break "break-all"})})
                (dom/text (str "Materialization node: " materialization-node))))))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Parent Dataset"))
            (dom/div (dom/text dataset-id)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Tenant"))
            (dom/input
              (dom/props {:style input-style
                          :type "text"
                          :value tenant
                          :placeholder "e.g., digdir"})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :tenant] (.. ev -target -value)))
                      nil)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Materialization Branch"))
            (dom/input
              (dom/props {:style input-style
                          :type "text"
                          :value tenant-config-key
                          :placeholder "Optional branch key, e.g., prod"})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :tenant-config-key] (.. ev -target -value)))
                      nil))
            (dom/div
              (dom/props {:style helper-text-style})
              (dom/text "Chooses where this pipeline materialization lives in the dataset tree. It does not edit runtime config."))
            (dom/div
              (dom/props {:style helper-text-style})
              (dom/text (str "Current branch label: " (branch-key-label tenant-config-key) "."))))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Pipeline ID"))
            (dom/input
              (dom/props {:style input-style
                          :type "text"
                          :value pipeline-id
                          :disabled (= mode :edit)
                          :placeholder "e.g., assistant"})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :pipeline-id] (.. ev -target -value)))
                      nil)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Display Name"))
            (dom/input
              (dom/props {:style input-style
                          :type "text"
                          :value name
                          :placeholder "e.g., Assistant"})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :name] (.. ev -target -value)))
                      nil)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Description"))
            (dom/textarea
              (dom/props {:style (merge input-style {:min-height "90px"})
                          :value description
                          :placeholder "What this pipeline ingests..."})
              (dom/On "input"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :description] (.. ev -target -value)))
                      nil)))

          (dom/div
            (dom/props {:style form-group-style})
            (dom/label (dom/props {:style label-style}) (dom/text "Source Type"))
            (dom/select
              (dom/props {:style input-style
                          :value (clojure.core/name source-type)})
              (dom/On "change"
                      (fn [ev]
                        (swap! !state assoc-in [:pipeline-form :source-type]
                               (keyword (.. ev -target -value))))
                      nil)
              (dom/option (dom/props {:value "website"}) (dom/text "Website"))
              (dom/option (dom/props {:value "kudos"}) (dom/text "Kudos"))
              (dom/option (dom/props {:value "folder"}) (dom/text "Folder"))
              (dom/option (dom/props {:value "episerver"}) (dom/text "EPiServer"))))

          (when (= mode :edit)
            (dom/div
              (dom/props {:style (merge card-style {:margin-top "1.5rem"
                                                    :padding "1rem"})})
              (dom/div
                (dom/props {:style section-title-style})
                (dom/text "Hierarchical Settings"))
              (dom/div
                (dom/props {:style helper-text-style})
                (dom/text "These values are edited directly on the selected materialization node and save per field."))
              (if editor-selection
                (FocusedInheritanceEditor [editor-selection]
                                          !refresh-counter
                                          user-id
                                          "The selected materialization node could not be loaded."
                                          :selected-nodes)
                (dom/div
                  (dom/props {:style (merge helper-text-style {:margin-top "0.75rem"})})
                  (dom/text "This editor becomes available once the pipeline has a tenant, dataset, and pipeline ID.")))))

          (dom/button
            (dom/props {:style (if save-disabled? button-disabled-style button-style)
                        :disabled save-disabled?})
            (dom/text (if (= mode :create) "Create Pipeline" "Save Pipeline"))
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (when-not save-disabled?
                  (let [payload {:dataset-id dataset-id
                                 :tenant tenant
                                 :tenant-config-key (when (seq (str/trim tenant-config-key)) tenant-config-key)
                                 :pipeline-id pipeline-id
                                 :name name
                                 :description description
                                 :source-type source-type}]
                    (e/server
                      (let [uid (e/client user-id)
                            save-payload (e/client payload)]
                        (if (= (e/client mode) :create)
                          (create-pipeline! uid save-payload)
                          (update-pipeline! uid save-payload))))
                    (swap! !refresh-counter inc)
                    (assoc-pipeline-console-state! !state
                                                   :view :dataset-detail
                                                   :pipeline-form nil)))
                (tok))))))))

(e/defn Pipelines
  []
  (let [user-id (e/server (:user/id e/http-request))]
    (e/client
      (let [!state (atom {:view :list})
            !url-state-restored? (atom false)
            !refresh-counter (atom 0)
            state (e/watch !state)]
        (restore-pipeline-console-url-state! !state !url-state-restored?)
        (case (:view state)
          :list (DatasetList !state !refresh-counter)
          :create-dataset (DatasetForm !state !refresh-counter user-id)
          :dataset-detail (DatasetDetail !state !refresh-counter user-id)
          :create-pipeline (PipelineForm !state !refresh-counter user-id :create)
          :edit-pipeline (PipelineForm !state !refresh-counter user-id :edit)
          (DatasetList !state !refresh-counter))))))
