(ns digdir.pipeline.ui.pipelines
  "Pipeline management UI for admin interface.

   Features:
   - List all pipelines
   - Create/edit pipeline configuration
   - Execute pipelines
   - View execution history
   - Delete/duplicate pipelines"
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            [digdir.i18n :refer [t]]
            #?(:clj [digdir.pipeline.core :as pipeline])
            #?(:clj [digdir.pipeline.executor :as executor])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.accessor :as cfg])
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
  {:margin-bottom "1.5rem"})

(def tab-container-style
  {:border-bottom "1px solid #e5e7eb"
   :margin-bottom "1.5rem"})

(def tab-style
  {:padding "0.75rem 1.5rem"
   :border "none"
   :background "transparent"
   :cursor "pointer"
   :border-bottom "2px solid transparent"
   :color "#6b7280"
   :font-weight "500"})

(def tab-active-style
  (merge tab-style
         {:color "#2563eb"
          :border-bottom-color "#2563eb"}))

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

(def badge-error-style
  (merge badge-style
         {:background "#fee2e2"
          :color "#991b1b"}))

;; =============================================================================
;; Components
;; =============================================================================

(e/defn PipelineList
  "List view of all pipelines"
  [!state selected-tenant selected-environment]
  (e/client
    (dom/div
      (dom/props {:style container-style})

      ;; Header
      (dom/div
        (dom/props {:style header-style})
        (dom/h1
          (dom/props {:style {:margin "0" :font-size "1.875rem" :color "#111827"}})
          (dom/text "Pipelines"))

        (dom/button
          (dom/props {:style button-style})
          (dom/text "+ New Pipeline")
          (let [[tok err] (e/Token (dom/On "click" identity nil))]
            (when tok
              (swap! !state assoc :view :create)
              (tok)))))

      ;; Filters
      (dom/div
        (dom/props {:style (merge card-style {:margin-bottom "1.5rem"})})
        (dom/text "Tenant: " (or selected-tenant "All"))
        (dom/text " | Environment: " (or selected-environment "All")))

      ;; Pipelines table
      (dom/div
        (dom/props {:style card-style})

        (let [db (e/server (e/watch (db/get-conn)))
              master-key (e/server (cfg/get :services :config :master-key))
              tenant (e/client selected-tenant)
              env (e/client selected-environment)
              pipeline-names (e/server (pipeline/list-pipelines db tenant env))
              pipelines (e/server
                          (mapv (fn [name]
                                  (pipeline/get-pipeline db tenant env name master-key))
                               pipeline-names))]

          (e/client
            (if (empty? pipelines)
              (dom/p
                (dom/props {:style {:text-align "center" :color "#6b7280" :padding "2rem"}})
                (dom/text "No pipelines found. Create your first pipeline to get started."))

              (dom/table
                (dom/props {:style table-style})

                ;; Header
                (dom/thead
                  (dom/tr
                    (dom/th (dom/props {:style th-style}) (dom/text "Name"))
                    (dom/th (dom/props {:style th-style}) (dom/text "Type"))
                    (dom/th (dom/props {:style th-style}) (dom/text "Tenant"))
                    (dom/th (dom/props {:style th-style}) (dom/text "Environment"))
                    (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))

                ;; Body
                (dom/tbody
                  (e/for-by :id [p pipelines]
                    (dom/tr
                      (dom/td
                        (dom/props {:style td-style})
                        (dom/text (or (:name p) (:pipeline-name p))))

                      (dom/td
                        (dom/props {:style td-style})
                        (dom/text (name (or (:source-type p) :unknown))))

                      (dom/td
                        (dom/props {:style td-style})
                        (dom/text (or (:tenant p) "-")))

                      (dom/td
                        (dom/props {:style td-style})
                        (dom/text (or (:environment p) "-")))

                      (dom/td
                        (dom/props {:style td-style})

                        ;; Execute button
                        (dom/button
                          (dom/props {:style (merge button-style {:margin-right "0.5rem" :font-size "0.75rem"})})
                          (dom/text "Execute")
                          (let [[tok err] (e/Token (dom/On "click" identity nil))]
                            (when tok
                              ;; TODO: Trigger execution
                              (tok))))

                        ;; Edit button
                        (dom/button
                          (dom/props {:style (merge button-secondary-style {:margin-right "0.5rem" :font-size "0.75rem"})})
                          (dom/text "Edit")
                          (let [[tok err] (e/Token (dom/On "click" identity nil))]
                            (when tok
                              (swap! !state assoc
                                     :view :edit
                                     :editing-pipeline p)
                              (tok))))

                        ;; Delete button
                        (dom/button
                          (dom/props {:style (merge button-danger-style {:font-size "0.75rem"})})
                          (dom/text "Delete")
                          (let [[tok err] (e/Token (dom/On "click" identity nil))]
                            (when tok
                              ;; TODO: Confirm and delete
                              (tok))))))))))))))))

(e/defn PipelineForm
  "Create/edit form for pipelines"
  [!state !form-data mode]
  (e/client
    (dom/div
      (dom/props {:style container-style})

      ;; Header
      (dom/div
        (dom/props {:style header-style})
        (dom/h1
          (dom/props {:style {:margin "0" :font-size "1.875rem" :color "#111827"}})
          (dom/text (if (= mode :create) "Create Pipeline" "Edit Pipeline")))

        (dom/button
          (dom/props {:style button-secondary-style})
          (dom/text "← Back")
          (let [[tok err] (e/Token (dom/On "click" identity nil))]
            (when tok
              (swap! !state assoc :view :list)
              (tok)))))

      ;; Form
      (dom/div
        (dom/props {:style card-style})

        (let [form-data (e/watch !form-data)
              active-tab (:active-tab form-data :basic)]

          ;; Tabs
          (dom/div
            (dom/props {:style tab-container-style})

            (dom/button
              (dom/props {:style (if (= active-tab :basic) tab-active-style tab-style)})
              (dom/text "Basic")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !form-data assoc :active-tab :basic)
                  (tok))))

            (dom/button
              (dom/props {:style (if (= active-tab :source) tab-active-style tab-style)})
              (dom/text "Source")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !form-data assoc :active-tab :source)
                  (tok))))

            (dom/button
              (dom/props {:style (if (= active-tab :chunking) tab-active-style tab-style)})
              (dom/text "Chunking")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !form-data assoc :active-tab :chunking)
                  (tok))))

            (dom/button
              (dom/props {:style (if (= active-tab :retrieval) tab-active-style tab-style)})
              (dom/text "Retrieval")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !form-data assoc :active-tab :retrieval)
                  (tok))))

            (dom/button
              (dom/props {:style (if (= active-tab :generation) tab-active-style tab-style)})
              (dom/text "Generation")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (swap! !form-data assoc :active-tab :generation)
                  (tok)))))

          ;; Tab content
          (case active-tab
            :basic
            (dom/div
              (dom/div
                (dom/props {:style form-group-style})
                (dom/label (dom/props {:style label-style}) (dom/text "Pipeline Name"))
                (dom/input
                  (dom/props {:style input-style
                              :type "text"
                              :placeholder "e.g., main-pipeline"
                              :value (or (:name form-data) "")})
                  (dom/On "input"
                          (e/fn [e]
                            (swap! !form-data assoc :name (-> e .-target .-value)))
                          nil)))

              (dom/div
                (dom/props {:style form-group-style})
                (dom/label (dom/props {:style label-style}) (dom/text "Description"))
                (dom/textarea
                  (dom/props {:style (merge input-style {:min-height "80px"})
                              :placeholder "Describe this pipeline..."
                              :value (or (:description form-data) "")})
                  (dom/On "input"
                          (e/fn [e]
                            (swap! !form-data assoc :description (-> e .-target .-value)))
                          nil))))

            :source
            (dom/div
              (dom/div
                (dom/props {:style form-group-style})
                (dom/label (dom/props {:style label-style}) (dom/text "Source Type"))
                (dom/select
                  (dom/props {:style input-style
                              :value (name (or (:source-type form-data) :kudos))})
                  (dom/On "change"
                          (e/fn [e]
                            (swap! !form-data assoc :source-type (keyword (-> e .-target .-value))))
                          nil)
                  (dom/option (dom/props {:value "kudos"}) (dom/text "Kudos"))
                  (dom/option (dom/props {:value "website"}) (dom/text "Website"))
                  (dom/option (dom/props {:value "folder"}) (dom/text "Folder"))
                  (dom/option (dom/props {:value "episerver"}) (dom/text "EPiServer"))))

              (dom/text "Source-specific configuration would go here..."))

            :chunking
            (dom/div
              (dom/text "Chunking configuration..."))

            :retrieval
            (dom/div
              (dom/text "Retrieval configuration..."))

            :generation
            (dom/div
              (dom/text "Generation configuration..."))))

        ;; Save button
        (dom/div
          (dom/props {:style {:margin-top "2rem" :border-top "1px solid #e5e7eb" :padding-top "1.5rem"}})
          (dom/button
            (dom/props {:style button-style})
            (dom/text "Save Pipeline")
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                ;; TODO: Save pipeline
                (swap! !state assoc :view :list)
                (tok)))))))))

(e/defn Pipelines
  "Main pipelines management component"
  []
  (e/client
    (let [!state (atom {:view :list})
          !form-data (atom {})
          state (e/watch !state)
          selected-tenant nil  ; TODO: Connect to tenant selector
          selected-environment nil]  ; TODO: Connect to environment selector

      (case (:view state)
        :list (PipelineList !state selected-tenant selected-environment)
        :create (PipelineForm !state !form-data :create)
        :edit (PipelineForm !state !form-data :edit)
        (PipelineList !state selected-tenant selected-environment)))))
