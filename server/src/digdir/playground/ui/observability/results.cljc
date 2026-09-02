(ns digdir.playground.ui.observability.results
  "Result, evidence, and source table components for Playground observability."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [digdir.playground.diagnostics :as diagnostics]
            [digdir.playground.ui.components :as base]
            [digdir.playground.ui.observability.panels :as panels]
            #?(:clj [digdir.playground.core :as playground])
            [digdir.i18n :refer [t]]))

(e/defn ResultsTable
  "Shared results table used by search diagnostics and message diagnostics."
  [results opts]
  (let [compact? (get opts :compact?)
        show-headings? (get opts :show-headings?)
        show-hits? (get opts :show-hits?)
        on-row-click (get opts :on-row-click)
        on-source-click (get opts :on-source-click)]
    (e/client
     (let [cell-padding      (if compact? "0.2rem 0.4rem" "0.25rem 0.5rem")
           table-font-size   (if compact? "0.7rem" "0.75rem")
           header-bg         (if compact? "#f3f4f6" "#f9fafb")
           empty-font-size   (if compact? "0.75rem" "0.875rem")
           empty-padding     (if compact? "0.25rem" "0.5rem")
           max-results       (if compact? 15 20)
           source-max-width  (if compact? "250px" "360px")
           heading-font-size (if compact? "0.64rem" "0.65rem")]
       (if (empty? results)
         (dom/div
          (dom/props {:style {:color "#6b7280"
                              :font-size empty-font-size
                              :padding empty-padding}})
          (dom/text (t :playground/no-results)))
         (dom/div
          (dom/props {:style {:max-height "200px"
                              :overflow-y "auto"}})
          (dom/table
           (dom/props {:style {:width "100%"
                               :font-size table-font-size
                               :border-collapse "collapse"}})
           (dom/thead
            (dom/tr
             (dom/props {:style {:background header-bg}})
             (dom/th
              (dom/props {:style {:padding cell-padding
                                  :text-align "left"
                                  :width (when compact? "2rem")}})
              (dom/text "#"))
             (dom/th
              (dom/props {:style {:padding cell-padding
                                  :text-align "left"}})
              (dom/text (if compact? (t :playground/title) "Source")))
             (when show-headings?
               (dom/th
                (dom/props {:style {:padding cell-padding
                                    :text-align "left"
                                    :width "9rem"}})
                (dom/text "Headings")))
             (dom/th
              (dom/props {:style {:padding cell-padding
                                  :text-align "right"
                                  :width (when compact? "3rem")}})
              (dom/text (t :playground/score)))
             (when show-hits?
               (dom/th
                (dom/props {:style {:padding cell-padding
                                    :text-align "center"}})
                (dom/text (t :playground/hits))))))
           (dom/tbody
            (e/for [[idx result] (e/diff-by first (map-indexed vector (take max-results results)))]
              (let [source (base/source-display-data result)]
                (dom/tr
                 (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                                     :cursor (when on-row-click "pointer")}})
                 (when on-row-click
                   (dom/On "click" #(on-row-click result) nil))
                 (dom/td
                  (dom/props {:style {:padding cell-padding
                                      :vertical-align "top"}})
                  (dom/text (str (inc idx))))
                 (dom/td
                  (dom/props {:style {:padding cell-padding
                                      :max-width source-max-width
                                      :overflow "hidden"
                                      :color "#3b82f6"
                                      :cursor (when on-source-click "pointer")}})
                  (base/SourceTitleMarkdown
                   (:title source)
                   (merge {:font-weight "500"} base/source-title-ellipsis-style))
                  (when-let [heading-line (:heading-line source)]
                    (base/HeadingLineMarkdown
                     heading-line
                     (merge {:font-size heading-font-size
                             :color "#6b7280"}
                            base/source-heading-ellipsis-style)))
                  (when on-source-click
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (on-source-click result)
                        (t)))))
                 (when show-headings?
                   (dom/td
                    (dom/props {:style {:padding cell-padding
                                        :color "#6b7280"
                                        :font-size "0.65rem"
                                        :max-width "160px"
                                        :overflow "hidden"
                                        :text-overflow "ellipsis"
                                        :white-space "nowrap"}})
                    (if-let [heading-line (:heading-line source)]
                      (base/HeadingLineMarkdown heading-line {})
                      (dom/text "-"))))
                 (dom/td
                  (dom/props {:style {:padding cell-padding
                                      :text-align "right"}})
                  (dom/text (if-let [rank (:rank result)]
                              (if (number? rank) (.toFixed rank 2) (str rank))
                              "-")))
                 (when show-hits?
                   (dom/td
                    (dom/props {:style {:padding cell-padding
                                        :text-align "center"}})
                    (dom/text (str (or (:hit-count result) 1))))))))))))))))

(e/defn SearchResultsTable [results title !selected-chunk-id]
  (dom/div
   (dom/props {:style {:margin-bottom "0.75rem"}})
   (dom/div
    (dom/props {:style panels/section-header-style})
    (dom/text (str title " (" (count results) " results)")))
   (ResultsTable
    results
    {:compact? false
     :show-headings? false
     :show-hits? true
     :on-source-click (fn [result]
                        (when-let [chunk-id (:chunk_id result)]
                          (reset! !selected-chunk-id {:chunk_id chunk-id
                                                      :rank (:rank result)
                                                      :search-types (:search-types result)})))})))

(e/defn SearchDiagnosticsPanel [results chunks-collection docs-collection]
  (let [!active-tab (atom :phrase)
        active-tab (e/watch !active-tab)
        !selected-chunk-info (atom nil)
        selected-chunk-info (e/watch !selected-chunk-info)
        fetched-chunk (when selected-chunk-info
                        (let [chunk-id (:chunk_id selected-chunk-info)
                              chunks-coll chunks-collection
                              docs-coll docs-collection]
                          (e/server
                           (e/Offload
                            #(playground/fetch-chunk-by-id chunks-coll docs-coll chunk-id)))))]
    (dom/div
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.25rem"
                          :margin-bottom "0.75rem"}})
      (e/for [[tab-key label] (e/diff-by first [[:phrase "Phrase"] [:metadata "Metadata"] [:content "Content"] [:merged "Merged"]])]
        (dom/button
         (dom/props {:style {:padding "0.375rem 0.75rem"
                             :font-size "0.75rem"
                             :background (if (= tab-key active-tab) "#3b82f6" "#f3f4f6")
                             :color (if (= tab-key active-tab) "white" "#374151")
                             :border "none"
                             :border-radius "4px"
                             :cursor "pointer"}})
         (dom/text label)
         (let [[token err] (e/Token (dom/On "click" identity nil))]
           (when token
             (reset! !active-tab tab-key)
             (token))))))
     (case active-tab
       :phrase (SearchResultsTable (:phrase-search results) "Phrase Search (Hybrid)" !selected-chunk-info)
       :metadata (SearchResultsTable (:metadata-search results) "Metadata Search (BM25)" !selected-chunk-info)
       :content (SearchResultsTable (:content-search results) "Content Search (BM25)" !selected-chunk-info)
       :merged (SearchResultsTable (:merged-results results) "Merged Results" !selected-chunk-info))
     (when (and selected-chunk-info fetched-chunk)
       (let [chunk-with-info (merge fetched-chunk selected-chunk-info)]
         (base/ChunkModalHeader chunk-with-info chunk-with-info (fn [] (reset! !selected-chunk-info nil))))))))

(e/defn QueryRelaxationResults [phrases]
  (dom/div
   (dom/props {:style {:margin-bottom "0.75rem"}})
   (dom/div
    (dom/props {:style panels/section-header-style})
    (dom/text (t :playground/search-phrases (count phrases))))
   (if (empty? phrases)
     (dom/div
      (dom/props {:style {:color "#6b7280"
                          :font-size "0.875rem"}})
      (dom/text (t :playground/no-phrases)))
     (dom/div
      (dom/props {:style {:display "flex"
                          :flex-wrap "wrap"
                          :gap "0.5rem"}})
      (e/for [phrase (e/diff-by identity phrases)]
        (dom/span
         (dom/props {:style panels/tag-style})
         (dom/text phrase)))))))

(e/defn UsedChunksView [chunks docs-collection-name]
  (let [!modal-chunk (atom nil)
        modal-chunk (e/watch !modal-chunk)]
    (dom/div
     (dom/div
      (dom/props {:style panels/section-header-style})
      (dom/text (t :playground/used-chunks (count chunks))))
     (if (empty? chunks)
       (dom/div
        (dom/props {:style {:color "#6b7280"
                            :font-size "0.875rem"}})
        (dom/text (t :playground/no-chunks-used)))
       (dom/div
        (dom/props {:style {:max-height "400px"
                            :overflow-y "auto"}})
        (e/for [[idx chunk] (e/diff-by first (map-indexed vector chunks))]
          (dom/div
           (dom/props {:style {:padding "0.75rem"
                               :margin-bottom "0.5rem"
                               :border "1px solid #e5e7eb"
                               :border-radius "6px"
                               :background "#fafafa"}})
           (dom/div
            (dom/props {:style {:display "flex"
                                :justify-content "space-between"
                                :align-items "center"
                                :margin-bottom "0.5rem"}})
            (dom/span
             (dom/props {:style {:font-weight "600"
                                 :font-size "0.875rem"}})
             (dom/text (str "#" (inc idx))))
            (dom/div
             (dom/props {:style {:display "flex"
                                 :gap "0.25rem"}})
             (let [search-types (or (:search-types chunk) #{})
                   rank (or (:rank chunk) 0)
                   percentage (Math/round (* rank 100))]
               (e/for [search-type (e/diff-by identity (vec search-types))]
                 (dom/span
                  (dom/props {:style (base/search-type-badge-style search-type)})
                  (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
           (let [source (base/source-display-data chunk docs-collection-name)]
             (base/SourceTitleAndHeading
              source
              {:font-weight "500"
               :font-size "0.875rem"
               :margin-bottom "0.25rem"
               :color "#1f2937"}
              {:font-size "0.7rem"
               :color "#6b7280"
               :margin-bottom "0.25rem"}))
           (dom/div
            (dom/props {:style {:font-size "0.75rem"
                                :color "#6b7280"
                                :max-height "60px"
                                :overflow "hidden"
                                :line-height "1.4"}})
            (dom/text (let [content (or (:content_markdown chunk) "")]
                        (if (> (count content) 200)
                          (str (subs content 0 200) "...")
                          content))))
           (dom/button
            (dom/props {:style {:background "none"
                                :border "none"
                                :color "#3b82f6"
                                :cursor "pointer"
                                :font-size "0.75rem"
                                :padding "0.25rem 0"
                                :margin-top "0.25rem"}})
            (dom/text (t :playground/show-more))
            (let [[t err] (e/Token (dom/On "click" identity nil))]
              (when t
                (reset! !modal-chunk chunk)
                (t))))))))
     (when modal-chunk
       (let [source (base/source-display-data modal-chunk docs-collection-name)]
         (base/ChunkDetailPanel
          source modal-chunk #(reset! !modal-chunk nil) 0 1 nil nil))))))

(e/defn DiagnosticsResultsTable
  "Table showing search results for diagnostics."
  [results _title on-select-chunk]
  (e/client
   (dom/div
    (dom/props {:style {:margin-bottom "0.5rem"}})
    (ResultsTable
     results
     {:compact? true
      :show-headings? true
      :show-hits? false
      :on-row-click (fn [result]
                      (on-select-chunk (:chunk_id result)))}))))

(e/defn DiagnosticsUsedChunks
  "Compact used chunks display for diagnostics."
  [chunks on-select-chunk]
  (e/client
   (dom/div
    (if (empty? chunks)
      (dom/div
       (dom/props {:style {:color "#6b7280"
                           :font-size "0.75rem"}})
       (dom/text (t :playground/no-chunks-used)))
      (dom/div
       (dom/props {:style {:max-height "250px"
                           :overflow-y "auto"}})
       (e/for [[idx chunk] (e/diff-by first (map-indexed vector chunks))]
         (let [source (base/source-display-data chunk)]
           (dom/div
            (dom/props {:style {:padding "0.5rem"
                                :margin-bottom "0.25rem"
                                :border "1px solid #e5e7eb"
                                :border-radius "4px"
                                :background "white"
                                :font-size "0.7rem"
                                :cursor "pointer"}})
            (dom/On "click" #(on-select-chunk (:chunk_id chunk)) nil)
            (dom/div
             (dom/props {:style {:display "flex"
                                 :justify-content "space-between"
                                 :align-items "center"
                                 :margin-bottom "0.25rem"}})
             (dom/span
              (dom/props {:style {:font-weight "600"
                                  :color "#374151"}})
              (dom/text (str "#" (inc idx))))
             (dom/div
              (dom/props {:style {:display "flex"
                                  :gap "0.2rem"}})
              (let [search-types (or (:search-types chunk) #{})
                    rank (or (:rank chunk) 0)
                    percentage (Math/round (* rank 100))]
                (e/for [search-type (e/diff-by identity (vec search-types))]
                  (dom/span
                   (dom/props {:style (base/search-type-badge-style search-type)})
                   (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)"))))))))
            (base/SourceTitleAndHeading
             source
             {:font-weight "500"
              :color "#3b82f6"
              :margin-bottom "0.2rem"}
             {:font-size "0.65rem"
              :color "#6b7280"
              :margin-bottom "0.2rem"}))))))))

(e/defn RetrievedEvidenceTable
  "Table showing retrieved chunk metadata before/alongside reading."
  [diagnostics docs-collection-name on-select-chunk]
  (e/client
   (let [entries (diagnostics/retrieved-evidence-entries diagnostics)]
     (dom/div
      (if (empty? entries)
        (dom/div
         (dom/props {:style {:color "#6b7280" :font-size "0.75rem"}})
         (dom/text "No retrieved candidates available."))
        (dom/div
         (dom/props {:style {:max-height "320px" :overflow-y "auto"}})
         (dom/table
          (dom/props {:style {:width "100%"
                              :font-size "0.7rem"
                              :border-collapse "collapse"}})
          (dom/thead
           (dom/tr
            (dom/props {:style {:background "#f3f4f6"}})
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "2rem"}}) (dom/text "#"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left"}}) (dom/text "Source"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "5.5rem"}}) (dom/text "State"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "5rem"}}) (dom/text "Position"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "right" :width "4rem"}}) (dom/text "Chars"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "7rem"}}) (dom/text "Search types"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "12rem"}}) (dom/text "Why"))))
          (dom/tbody
           (e/for [[idx chunk] (e/diff-by first (map-indexed vector entries))]
             (let [chunk-id (:chunk_id chunk)
                   source (base/source-display-data chunk docs-collection-name)
                   state-label (if (:read? chunk) "read" "metadata only")
                   position-label (when-let [chunk-index (:chunk_index chunk)]
                                    (str (inc chunk-index)
                                         (when-let [total (:total_chunks chunk)]
                                           (str "/" total))))
                   search-type-labels (or (:search-type-labels chunk) [])]
               (dom/tr
                (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                                    :cursor "pointer"}})
                (dom/On "click" #(on-select-chunk chunk-id) nil)
                (dom/td (dom/props {:style {:padding "0.3rem 0.4rem" :vertical-align "top"}}) (dom/text (str (inc idx))))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :max-width "220px"
                                     :overflow "hidden"}})
                 (base/SourceTitleAndHeading
                  source
                  (merge {:color "#3b82f6"
                          :font-weight "500"}
                         base/source-title-ellipsis-style)
                  (merge {:font-size "0.62rem"
                          :color "#6b7280"}
                         base/source-heading-ellipsis-style)))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :color (if (:read? chunk) "#166534" "#92400e")
                                     :font-weight "500"}})
                 (dom/text state-label))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :color "#4b5563"}})
                 (dom/text (or position-label "-")))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :text-align "right"
                                     :color "#4b5563"}})
                 (dom/text (or (some-> (:content_length chunk) str) "-")))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"}})
                 (if (seq search-type-labels)
                   (dom/div
                    (dom/props {:style {:display "flex"
                                        :flex-wrap "wrap"
                                        :gap "0.2rem"}})
                    (e/for [search-type (e/diff-by identity search-type-labels)]
                      (dom/span
                       (dom/props {:style (base/search-type-badge-style (keyword search-type))})
                       (dom/text (str/upper-case search-type)))))
                   (dom/text "-")))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :color "#4b5563"
                                     :max-width "220px"}})
                 (dom/text (or (:retrieval-explanation chunk) "-"))))))))))))))

(e/defn DetailedSourcesTable
  "Table showing used chunks with per-search-type score columns."
  [diagnostics docs-collection-name on-select-chunk]
  (e/client
   (let [used-chunks (or (:used-chunks diagnostics) [])]
     (dom/div
      (if (empty? used-chunks)
        (dom/div
         (dom/props {:style {:color "#6b7280" :font-size "0.75rem"}})
         (dom/text "No sources available."))
        (dom/div
         (dom/props {:style {:max-height "300px" :overflow-y "auto"}})
         (dom/table
          (dom/props {:style {:width "100%"
                              :font-size "0.7rem"
                              :border-collapse "collapse"}})
          (dom/thead
           (dom/tr
            (dom/props {:style {:background "#f3f4f6"}})
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left" :width "2rem"}}) (dom/text "#"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "left"}}) (dom/text "Source"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "right" :width "4.5rem"}}) (dom/text "Phrase"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "right" :width "4.5rem"}}) (dom/text "Meta"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "right" :width "4.5rem"}}) (dom/text "Content"))
            (dom/th (dom/props {:style {:padding "0.3rem 0.4rem" :text-align "right" :width "4rem"}}) (dom/text "Best"))))
          (dom/tbody
           (e/for [[idx chunk] (e/diff-by first (map-indexed vector used-chunks))]
             (let [chunk-id (:chunk_id chunk)
                   source (base/source-display-data chunk docs-collection-name)
                   type-ranks (or (:type-ranks chunk) {})
                   p-rank (get type-ranks :phrase)
                   m-rank (get type-ranks :metadata)
                   c-rank (get type-ranks :content)
                   best-rank (or (:rank chunk) 0)
                   fmt (fn [r] (if (and r (number? r) (pos? r)) (.toFixed r 2) "-"))]
               (dom/tr
                (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                                    :cursor "pointer"}})
                (dom/On "click" #(on-select-chunk chunk-id) nil)
                (dom/td (dom/props {:style {:padding "0.3rem 0.4rem" :vertical-align "top"}}) (dom/text (str (inc idx))))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :max-width "200px"
                                     :overflow "hidden"}})
                 (base/SourceTitleAndHeading
                  source
                  (merge {:color "#3b82f6"
                          :font-weight "500"}
                         base/source-title-ellipsis-style)
                  (merge {:font-size "0.62rem"
                          :color "#6b7280"}
                         base/source-heading-ellipsis-style)))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :text-align "right"
                                     :color (if (and p-rank (pos? p-rank)) "#1e40af" "#d1d5db")}})
                 (dom/text (fmt p-rank)))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :text-align "right"
                                     :color (if (and m-rank (pos? m-rank)) "#92400e" "#d1d5db")}})
                 (dom/text (fmt m-rank)))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :text-align "right"
                                     :color (if (and c-rank (pos? c-rank)) "#166534" "#d1d5db")}})
                 (dom/text (fmt c-rank)))
                (dom/td
                 (dom/props {:style {:padding "0.3rem 0.4rem"
                                     :text-align "right"
                                     :font-weight "600"}})
                 (dom/text (fmt best-rank))))))))))))))