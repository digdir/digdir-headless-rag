(ns digdir.skills.ui
  "Skills management UI for the Operator Console.

   Features:
   - List all available skills with metadata
   - View skill details (inputs, outputs, parameters)
   - View skill tool definitions for agent invocation
   - View registered skill graphs"
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            #?(:clj [digdir.skills.api :as skills-api])))

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
   :font-weight "500"
   :display "inline-block"})

(def tab-active-style
  (merge tab-style
         {:color "#2563eb"
          :border-bottom-color "#2563eb"}))

(def badge-style
  {:padding "0.25rem 0.75rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"
   :display "inline-block"
   :margin-right "0.5rem"})

(def category-colors
  {:query-transformation {:background "#dbeafe" :color "#1e40af"}
   :retrieval {:background "#dcfce7" :color "#166534"}
   :reranking {:background "#fef3c7" :color "#92400e"}
   :generation {:background "#f3e8ff" :color "#6b21a8"}
   :augmentation {:background "#fce7f3" :color "#9d174d"}
   :validation {:background "#fee2e2" :color "#991b1b"}
   :orchestration {:background "#e0e7ff" :color "#3730a3"}})

(def modal-backdrop-style
  {:position "fixed"
   :top "0"
   :left "0"
   :right "0"
   :bottom "0"
   :background "rgba(0, 0, 0, 0.5)"
   :display "flex"
   :align-items "center"
   :justify-content "center"
   :z-index "1000"})

(def modal-content-style
  {:background "white"
   :border-radius "8px"
   :padding "2rem"
   :max-width "800px"
   :max-height "80vh"
   :overflow-y "auto"
   :width "100%"
   :box-shadow "0 20px 25px -5px rgba(0, 0, 0, 0.1)"})

(def code-style
  {:background "#f3f4f6"
   :padding "1rem"
   :border-radius "4px"
   :display "block"
   :max-width "100%"
   :box-sizing "border-box"
   :font-family "monospace"
   :font-size "0.875rem"
   :overflow-x "auto"
   :white-space "pre-wrap"
   :overflow-wrap "anywhere"
   :word-break "break-word"})

;; =============================================================================
;; Components
;; =============================================================================

(e/defn CategoryBadge
  "Display a colored badge for skill category"
  [category]
  (e/client
    (let [colors (get category-colors category {:background "#e5e7eb" :color "#374151"})]
      (dom/span
        (dom/props {:style (merge badge-style colors)})
        (dom/text (if category (name category) "uncategorized"))))))

(e/defn SkillDetailModal
  "Modal showing detailed skill information"
  [skill !show-detail]
  (e/client
    (dom/div
      (dom/props {:style modal-backdrop-style})

      (dom/div
        (dom/props {:style modal-content-style})
        (dom/On "click" #(.stopPropagation %) nil)

        ;; Header
        (dom/div
          (dom/props {:style {:display "flex"
                              :justify-content "space-between"
                              :align-items "center"
                              :margin-bottom "1.5rem"}})
          (dom/h2
            (dom/props {:style {:margin "0" :font-size "1.5rem"}})
            (dom/text (:name skill)))
          (dom/button
            (dom/props {:style {:background "transparent"
                                :border "none"
                                :font-size "1.5rem"
                                :cursor "pointer"
                                :color "#6b7280"}})
            (dom/text "×")
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-detail nil)
                (tok)))))

        ;; Skill ID
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "ID: "))
          (dom/code
            (dom/props {:style {:background "#f3f4f6"
                                :padding "0.25rem 0.5rem"
                                :border-radius "4px"}})
            (dom/text (str (:skill-id skill)))))

        ;; Category
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Category: "))
          (CategoryBadge (:category skill)))

        ;; Description
        (dom/div
          (dom/props {:style {:margin-bottom "1.5rem"}})
          (dom/strong (dom/text "Description"))
          (dom/p
            (dom/props {:style {:margin "0.5rem 0" :color "#4b5563"}})
            (dom/text (:description skill))))

        ;; Inputs
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Inputs"))
          (dom/div
            (dom/props {:style {:display "flex"
                                :flex-wrap "wrap"
                                :gap "0.5rem"
                                :margin-top "0.5rem"}})
            (e/for-by identity [input (vec (:inputs skill))]
              (dom/span
                (dom/props {:style {:background "#e0f2fe"
                                    :color "#0369a1"
                                    :padding "0.25rem 0.75rem"
                                    :border-radius "4px"
                                    :font-size "0.875rem"}})
                (dom/text (name input))))))

        ;; Outputs
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Outputs"))
          (dom/div
            (dom/props {:style {:display "flex"
                                :flex-wrap "wrap"
                                :gap "0.5rem"
                                :margin-top "0.5rem"}})
            (e/for-by identity [output (vec (:outputs skill))]
              (dom/span
                (dom/props {:style {:background "#dcfce7"
                                    :color "#166534"
                                    :padding "0.25rem 0.75rem"
                                    :border-radius "4px"
                                    :font-size "0.875rem"}})
                (dom/text (name output))))))

        ;; Parameters
        (when (seq (:parameters skill))
          (dom/div
            (dom/props {:style {:margin-bottom "1rem"}})
            (dom/strong (dom/text "Parameters"))
            (dom/div
              (dom/props {:style {:margin-top "0.5rem"}})
              (e/for-by first [param-entry (vec (:parameters skill))]
                (let [[param-name param-type] param-entry]
                  (dom/div
                    (dom/props {:style {:display "flex"
                                        :gap "0.5rem"
                                        :margin-bottom "0.25rem"}})
                    (dom/code
                      (dom/props {:style {:background "#f3f4f6"
                                          :padding "0.125rem 0.5rem"
                                          :border-radius "4px"}})
                      (dom/text (name param-name)))
                    (dom/span
                      (dom/props {:style {:color "#6b7280"}})
                      (dom/text (str "(" (name param-type) ")")))))))))

        ;; Required Services
        (when (seq (:required-services skill))
          (dom/div
            (dom/props {:style {:margin-bottom "1rem"}})
            (dom/strong (dom/text "Required Services"))
            (dom/div
              (dom/props {:style {:display "flex"
                                  :flex-wrap "wrap"
                                  :gap "0.5rem"
                                  :margin-top "0.5rem"}})
              (e/for-by identity [service (vec (:required-services skill))]
                (dom/span
                  (dom/props {:style {:background "#fef3c7"
                                      :color "#92400e"
                                      :padding "0.25rem 0.75rem"
                                      :border-radius "4px"
                                      :font-size "0.875rem"}})
                  (dom/text (name service)))))))))))

(e/defn SkillRow
  "Table row for a single skill"
  [skill !show-detail]
  (e/client
    (dom/tr
      ;; Name
      (dom/td
        (dom/props {:style td-style})
        (dom/text (:name skill)))

      ;; ID
      (dom/td
        (dom/props {:style td-style})
        (dom/code
          (dom/props {:style {:font-size "0.75rem"
                              :background "#f3f4f6"
                              :padding "0.125rem 0.5rem"
                              :border-radius "4px"}})
          (dom/text (str (:skill-id skill)))))

      ;; Category
      (dom/td
        (dom/props {:style td-style})
        (CategoryBadge (:category skill)))

      ;; Inputs/Outputs count
      (dom/td
        (dom/props {:style td-style})
        (dom/text (str (count (:inputs skill)) " → " (count (:outputs skill)))))

      ;; Actions
      (dom/td
        (dom/props {:style td-style})
        (dom/button
          (dom/props {:style button-secondary-style})
          (dom/text "View")
          (let [[tok err] (e/Token (dom/On "click" identity nil))]
            (when tok
              (reset! !show-detail skill)
              (tok))))))))

(e/defn SkillsList
  "List view of all skills"
  []
  (e/client
    (let [!show-detail (atom nil)
          show-detail (e/watch !show-detail)
          skills (e/server
                   (do
                     (skills-api/initialize!)
                     (->> (skills-api/list-skills)
                          (mapv (fn [s]
                                  (update s :required-services #(vec (or % []))))))))]

      (dom/div
        (dom/props {:style container-style})

        ;; Header
        (dom/div
          (dom/props {:style header-style})
          (dom/h1
            (dom/props {:style {:margin "0" :font-size "1.875rem" :color "#111827"}})
            (dom/text "Skills"))
          (dom/span
            (dom/props {:style {:color "#6b7280"}})
            (dom/text (str (count skills) " registered"))))

        ;; Skills table
        (dom/div
          (dom/props {:style card-style})

          (if (empty? skills)
            (dom/p
              (dom/props {:style {:text-align "center" :color "#6b7280" :padding "2rem"}})
              (dom/text "No skills registered."))

            (dom/table
              (dom/props {:style table-style})

              (dom/thead
                (dom/tr
                  (dom/th (dom/props {:style th-style}) (dom/text "Name"))
                  (dom/th (dom/props {:style th-style}) (dom/text "ID"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Category"))
                  (dom/th (dom/props {:style th-style}) (dom/text "I/O"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))

              (dom/tbody
                (e/for-by :skill-id [skill skills]
                  (SkillRow skill !show-detail))))))

        ;; Detail modal
        (when show-detail
          (SkillDetailModal show-detail !show-detail))))))

(e/defn SkillGraphRow
  "Table row for a single skill graph"
  [template !show-detail]
  (e/client
    (dom/tr
      ;; Name
      (dom/td
        (dom/props {:style td-style})
        (dom/text (:name template)))

      ;; ID
      (dom/td
        (dom/props {:style td-style})
        (dom/code
          (dom/props {:style {:font-size "0.75rem"
                              :background "#f3f4f6"
                              :padding "0.125rem 0.5rem"
                              :border-radius "4px"}})
          (dom/text (str (:id template)))))

      ;; Description
      (dom/td
        (dom/props {:style (merge td-style {:max-width "300px"})})
        (dom/text (or (:description template) "-")))

      ;; Steps count
      (dom/td
        (dom/props {:style td-style})
        (dom/text (str (count (get-in template [:graph :steps])) " steps")))

      ;; Actions
      (dom/td
        (dom/props {:style td-style})
        (dom/button
          (dom/props {:style button-secondary-style})
          (dom/text "View")
          (let [[tok err] (e/Token (dom/On "click" identity nil))]
            (when tok
              (reset! !show-detail template)
              (tok))))))))

(e/defn SkillGraphDetailModal
  "Modal showing skill graph details"
  [template !show-detail]
  (e/client
    (dom/div
      (dom/props {:style modal-backdrop-style})

      (dom/div
        (dom/props {:style modal-content-style})
        (dom/On "click" #(.stopPropagation %) nil)

        ;; Header
        (dom/div
          (dom/props {:style {:display "flex"
                              :justify-content "space-between"
                              :align-items "center"
                              :margin-bottom "1.5rem"}})
          (dom/h2
            (dom/props {:style {:margin "0" :font-size "1.5rem"}})
            (dom/text (:name template)))
          (dom/button
            (dom/props {:style {:background "transparent"
                                :border "none"
                                :font-size "1.5rem"
                                :cursor "pointer"
                                :color "#6b7280"}})
            (dom/text "×")
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-detail nil)
                (tok)))))

        ;; Skill Graph ID
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "ID: "))
          (dom/code
            (dom/props {:style {:background "#f3f4f6"
                                :padding "0.25rem 0.5rem"
                                :border-radius "4px"}})
            (dom/text (str (:id template)))))

        ;; Description
        (when (:description template)
          (dom/div
            (dom/props {:style {:margin-bottom "1.5rem"}})
            (dom/strong (dom/text "Description"))
            (dom/p
              (dom/props {:style {:margin "0.5rem 0" :color "#4b5563"}})
              (dom/text (:description template)))))

        ;; Graph Inputs
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Graph Inputs"))
          (dom/div
            (dom/props {:style {:display "flex"
                                :flex-wrap "wrap"
                                :gap "0.5rem"
                                :margin-top "0.5rem"}})
            (e/for-by identity [input (vec (get-in template [:graph :inputs]))]
              (dom/span
                (dom/props {:style {:background "#e0f2fe"
                                    :color "#0369a1"
                                    :padding "0.25rem 0.75rem"
                                    :border-radius "4px"
                                    :font-size "0.875rem"}})
                (dom/text (name input))))))

        ;; Graph Steps
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Skill Graph Steps"))
          (dom/div
            (dom/props {:style {:margin-top "0.5rem"}})
            (e/for-by :id [step (vec (get-in template [:graph :steps]))]
              (dom/div
                (dom/props {:style {:display "flex"
                                    :align-items "center"
                                    :gap "0.5rem"
                                    :margin-bottom "0.5rem"
                                    :padding "0.75rem"
                                    :background "#f9fafb"
                                    :border-radius "4px"}})
                (dom/span
                  (dom/props {:style {:font-weight "600"
                                      :color "#374151"}})
                  (dom/text (str (name (:id step)) ":")))
                (dom/code
                  (dom/props {:style {:background "#e5e7eb"
                                      :padding "0.125rem 0.5rem"
                                      :border-radius "4px"
                                      :font-size "0.875rem"}})
                  (dom/text (str (:skill step))))))))

        ;; Graph Outputs
        (dom/div
          (dom/props {:style {:margin-bottom "1rem"}})
          (dom/strong (dom/text "Graph Outputs"))
          (dom/div
            (dom/props {:style {:display "flex"
                                :flex-wrap "wrap"
                                :gap "0.5rem"
                                :margin-top "0.5rem"}})
            (e/for-by identity [output (vec (get-in template [:graph :outputs]))]
              (dom/span
                (dom/props {:style {:background "#dcfce7"
                                    :color "#166534"
                                    :padding "0.25rem 0.75rem"
                                    :border-radius "4px"
                                    :font-size "0.875rem"}})
                (dom/text (name output))))))))))

(e/defn SkillGraphsList
  "List view of all skill graphs"
  []
  (e/client
    (let [!show-detail (atom nil)
          show-detail (e/watch !show-detail)
          templates (e/server
                      (do
                        (skills-api/initialize!)
                        (vec (skills-api/list-skill-graphs))))]

      (dom/div
        (dom/props {:style container-style})

        ;; Header
        (dom/div
          (dom/props {:style header-style})
          (dom/h1
            (dom/props {:style {:margin "0" :font-size "1.875rem" :color "#111827"}})
            (dom/text "Skill Graphs"))
          (dom/span
            (dom/props {:style {:color "#6b7280"}})
            (dom/text (str (count templates) " registered"))))

        ;; Skill graphs table
        (dom/div
          (dom/props {:style card-style})

          (if (empty? templates)
            (dom/p
              (dom/props {:style {:text-align "center" :color "#6b7280" :padding "2rem"}})
              (dom/text "No skill graphs registered."))

            (dom/table
              (dom/props {:style table-style})

              (dom/thead
                (dom/tr
                  (dom/th (dom/props {:style th-style}) (dom/text "Name"))
                  (dom/th (dom/props {:style th-style}) (dom/text "ID"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Description"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Steps"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Actions"))))

              (dom/tbody
                (e/for-by :id [template templates]
                  (SkillGraphRow template !show-detail))))))

        ;; Detail modal
        (when show-detail
          (SkillGraphDetailModal show-detail !show-detail))))))

(e/defn ToolDefinitionsList
  "List view of tool definitions for agent invocation"
  []
  (e/client
    (let [tools (e/server
                  (do
                    (skills-api/initialize!)
                    (vec (skills-api/get-all-tool-definitions))))]

      (dom/div
        (dom/props {:style container-style})

        ;; Header
        (dom/div
          (dom/props {:style header-style})
          (dom/h1
            (dom/props {:style {:margin "0" :font-size "1.875rem" :color "#111827"}})
            (dom/text "Tools"))
          (dom/span
            (dom/props {:style {:color "#6b7280"}})
            (dom/text "OpenAI function calling format")))

        ;; Tool cards
        (e/for-by #(get-in % [:function :name]) [tool tools]
          (e/client
            (dom/div
              (dom/props {:style card-style})

              ;; Tool name
              (dom/h3
                (dom/props {:style {:margin "0 0 0.5rem 0"
                                    :font-size "1.125rem"
                                    :color "#111827"}})
                (dom/text (get-in tool [:function :name])))

              ;; Description
              (dom/p
                (dom/props {:style {:margin "0 0 1rem 0"
                                    :color "#4b5563"}})
                (dom/text (get-in tool [:function :description])))

              ;; Parameters schema (simplified)
              (dom/div
                (dom/props {:style {:margin-bottom "0.5rem"}})
                (dom/strong (dom/text "Parameters: "))
                (dom/code
                  (dom/props {:style code-style})
                  (dom/text (pr-str (get-in tool [:function :parameters]))))))))))))

(e/defn SkillsUI
  "Main skills management component with tabs"
  []
  (e/client
    (let [!active-tab (atom :skill-graphs)
          active-tab (e/watch !active-tab)]

      (dom/div
        (dom/props {:style {:min-height "100vh"
                            :background "#f9fafb"}})

        ;; Tabs
        (dom/div
          (dom/props {:style {:background "white"
                              :border-bottom "1px solid #e5e7eb"
                              :padding "0 2rem"}})

          (dom/div
            (dom/props {:style {:max-width "1200px"
                                :margin "0 auto"}})

            ;; Skill graphs tab
            (dom/button
              (dom/props {:style (if (= active-tab :skill-graphs)
                                   tab-active-style
                                   tab-style)})
              (dom/text "Skill Graphs")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (reset! !active-tab :skill-graphs)
                  (tok))))

            ;; Skills tab
            (dom/button
              (dom/props {:style (if (= active-tab :skills)
                                   tab-active-style
                                   tab-style)})
              (dom/text "Skills")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (reset! !active-tab :skills)
                  (tok))))

            ;; Tools tab
            (dom/button
              (dom/props {:style (if (= active-tab :tools)
                                   tab-active-style
                                   tab-style)})
              (dom/text "Tools")
              (let [[tok err] (e/Token (dom/On "click" identity nil))]
                (when tok
                  (reset! !active-tab :tools)
                  (tok))))))

        ;; Tab content
        (case active-tab
          :skill-graphs (SkillGraphsList)
          :skills (SkillsList)
          :tools (ToolDefinitionsList))))))
