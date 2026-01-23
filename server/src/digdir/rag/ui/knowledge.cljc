(ns digdir.rag.ui.knowledge
  "Knowledge base search and browse components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [medley.core :as y]
            #?(:clj [typesense.client :as ts])
            #?(:clj [digdir.rag.typesense-admin :refer [ts-search-all-pages]])))

;; =========Knowledge Components=========

(e/defn SearchPhraseTag [phrase]
  (dom/span
   (dom/props {:style {:background "#dbeafe"
                       :color "#1e40af"
                       :padding "0.25rem 0.5rem"
                       :border-radius "4px"
                       :font-size "0.75rem"
                       :font-weight "500"}})
   (dom/text phrase)))

(e/defn ChunkView [chunk]
  (dom/div
   (dom/props {:style {:margin-bottom "1.5rem"
                       :padding "1rem"
                       :border "1px solid #e5e7eb"
                       :border-radius "6px"
                       :background "#f9fafb"}})

   ;; Display headers from metadata if available
   (when-let [metadata (:metadata chunk)]
     (dom/div
      (dom/props {:style {:margin-bottom "1rem"
                          :padding "0.75rem"
                          :background "#e0e7ff"
                          :border-radius "4px"}})

      ;; Display each header level
      (e/for [[header-key header-value] (e/diff-by identity (sort-by key metadata))]
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :font-weight (case header-key
                                            "Header 1" "700"
                                            "Header 2" "600"
                                            "Header 3" "500"
                                            "400")
                             :font-size (case header-key
                                          "Header 1" "1.125rem"
                                          "Header 2" "1rem"
                                          "Header 3" "0.9375rem"
                                          "0.875rem")
                             :color "#1e3a8a"}})
         (dom/text header-value)))))

   ;; Chunk content
   (dom/div
    (dom/props {:style {:margin-bottom "1rem"
                        :line-height "1.6"}})
    (dom/text (:content chunk)))

   ;; Search phrases
   (dom/div
    (dom/div
     (dom/props {:style {:font-weight "600"
                         :margin-bottom "0.5rem"
                         :font-size "0.875rem"
                         :color "#374151"}})
     (dom/text "Search Phrases:"))

    (dom/div
     (dom/props {:style {:display "flex"
                         :flex-wrap "wrap"
                         :gap "0.5rem"}})
     (e/for [phrase (e/diff-by identity (:search-phrases chunk))]
       (SearchPhraseTag phrase))))))

(e/defn DocumentPaginationControls [{:keys [current-page total-pages on-page-change has-more is-loading]}]
  (dom/div
   (dom/props {:style {:display "flex"
                       :justify-content "space-between"
                       :align-items "center"
                       :padding "1rem 0"
                       :border-top "1px solid #e5e7eb"
                       :margin-top "1rem"}})

   ;; Page info
   (dom/span
    (dom/props {:style {:font-size "0.875rem"
                        :color "#6b7280"}})
    (dom/text (if is-loading
                "Loading..."
                (str "Page " current-page (when total-pages (str " of " total-pages))))))

   ;; Navigation controls
   (dom/div
    (dom/props {:style {:display "flex"
                        :align-items "center"
                        :gap "1rem"}})

    ;; Previous button
    (dom/button
     (dom/props {:style {:padding "0.5rem 1rem"
                         :border "1px solid #d1d5db"
                         :border-radius "4px"
                         :background (if (or (= current-page 1) is-loading) "#f3f4f6" "white")
                         :color (if (or (= current-page 1) is-loading) "#9ca3af" "#374151")
                         :cursor (if (or (= current-page 1) is-loading) "not-allowed" "pointer")
                         :opacity (if is-loading "0.7" "1")}
                 :disabled (or (= current-page 1) is-loading)})
     (dom/On "click" #(when (and (> current-page 1) (not is-loading))
                        (on-page-change (dec current-page))
                        ;; Schedule scroll after loading completes
                        (js/setTimeout
                         (fn schedule-scroll []
                           (if (.querySelector js/document ".document-list-container")
                             (let [container (.querySelector js/document ".document-list-container")]
                               (when container
                                 (set! (.-scrollTop container) 0)))
                             (js/setTimeout schedule-scroll 100)))
                         200)) nil)
     (dom/text "Previous"))

    ;; Next button
    (dom/button
     (dom/props {:style {:padding "0.5rem 1rem"
                         :border "1px solid #d1d5db"
                         :border-radius "4px"
                         :background (if (or (not has-more) is-loading) "#f3f4f6" "white")
                         :color (if (or (not has-more) is-loading) "#9ca3af" "#374151")
                         :cursor (if (or (not has-more) is-loading) "not-allowed" "pointer")
                         :opacity (if is-loading "0.7" "1")}
                 :disabled (or (not has-more) is-loading)})
     (dom/On "click" #(when (and has-more (not is-loading))
                        (on-page-change (inc current-page))
                        ;; Schedule scroll after loading completes
                        (js/setTimeout
                         (fn schedule-scroll []
                           (if (.querySelector js/document ".document-list-container")
                             (let [container (.querySelector js/document ".document-list-container")]
                               (when container
                                 (set! (.-scrollTop container) 0)))
                             (js/setTimeout schedule-scroll 100)))
                         200)) nil)
     (dom/text "Next")))))

(e/defn DocumentListItem [doc selected-doc-id on-select]
  (dom/div
   (dom/props {:style {:padding "0.75rem"
                       :margin-bottom "0.5rem"
                       :border-radius "6px"
                       :cursor "pointer"
                       :border (if (= (:id doc) selected-doc-id)
                                 "2px solid #3b82f6"
                                 "1px solid #e5e7eb")
                       :background (if (= (:id doc) selected-doc-id)
                                     "#eff6ff"
                                     "#ffffff")}})
   (dom/On "click" #(on-select (:id doc)) nil)

   (dom/div
    (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem"}})
    (dom/text (:title doc)))

   (dom/div
    (dom/props {:style {:font-size "0.875rem" :color "#6b7280" :margin-bottom "0.25rem"}})
    (dom/text (:type doc)))

   (dom/div
    (dom/props {:style {:font-size "0.75rem" :color "#9ca3af" :margin-bottom "0.25rem"}})
    (dom/text (str "Doc ID: " (:id doc))))

   (when (seq (:orgs doc))
     (dom/div
      (dom/props {:style {:font-size "0.75rem" :color "#9ca3af"}})
      (dom/text (str/join ", " (take 2 (:orgs doc))))))))

(e/defn DocumentList [{:keys [documents selected-doc-id on-select search-query on-search-change current-page on-page-change has-more is-loading]}]
  (dom/div
   (dom/props {:style {:flex "0 0 300px"
                       :border "1px solid #e5e7eb"
                       :border-radius "8px"
                       :padding "1rem"
                       :overflow-y "auto"}
               :class "document-list-container"})
   (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
               (e/fn [] (dom/text "Documents")))

   ;; Search input
   (dom/div
    (dom/props {:style {:margin-bottom "1rem"}})
    (dom/input
     (dom/props {:type "text"
                 :placeholder "Search document titles..."
                 :value search-query
                 :style {:width "100%"
                         :padding "0.5rem"
                         :border "1px solid #d1d5db"
                         :border-radius "4px"
                         :font-size "0.875rem"}})
     (dom/On "input" #(on-search-change (.. % -target -value)) nil)
     (dom/On "keydown" #(when (= (.-key %) "Escape")
                          (on-search-change "")) nil)))

   ;; Document list with loading overlay
   (dom/div
    (dom/props {:style {:position "relative"}})

    ;; Loading overlay
    (when is-loading
      (dom/div
       (dom/props {:style {:position "absolute"
                           :top "0"
                           :left "0"
                           :right "0"
                           :bottom "0"
                           :background "rgba(255, 255, 255, 0.8)"
                           :display "flex"
                           :align-items "center"
                           :justify-content "center"
                           :z-index "10"
                           :border-radius "4px"}})
       (dom/div
        (dom/props {:style {:font-size "0.875rem"
                            :color "#6b7280"}})
        (dom/text "Loading documents..."))))

    ;; Document list
    (e/for [doc (e/diff-by :id documents)]
      (DocumentListItem doc selected-doc-id on-select)))

   ;; Pagination controls
   (DocumentPaginationControls {:current-page current-page
                                :on-page-change on-page-change
                                :has-more has-more
                                :is-loading is-loading})))

(e/defn DocumentView [selected-doc chunks]
  (dom/div
   (dom/props {:style {:flex "1"
                       :border "1px solid #e5e7eb"
                       :border-radius "8px"
                       :padding "1rem"
                       :overflow-y "auto"}})

   (when selected-doc
     (dom/div
      ;; Document header
      (dom/div
       (dom/props {:style {:margin-bottom "1.5rem"
                           :padding-bottom "1rem"
                           :border-bottom "1px solid #e5e7eb"}})
       (ks/Heading {:level 2 :style {:margin-bottom "0.5rem"}}
                   (e/fn [] (dom/text (:title selected-doc))))
       (dom/div
        (dom/props {:style {:display "flex" :gap "1rem" :font-size "0.875rem" :color "#6b7280"}})
        (dom/span (dom/text "Type: " (:type selected-doc)))
        (dom/span (dom/text "Date: " (:date selected-doc)))))

      ;; Chunks section
      (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
                  (e/fn [] (dom/text "Document Chunks")))

      (e/for [chunk (e/diff-by :id chunks)]
        (ChunkView chunk))))))

(e/defn Knowledge [ts-settings]
  (let [;; Info display state
        !show-info (atom false)
        show-info (e/watch !show-info)

        ;; Search and pagination state
        !search-query (atom "")
        search-query (e/watch !search-query)

        !current-page (atom 1)
        current-page (e/watch !current-page)

        documents-per-page 10

        ;; Fetch documents from Typesense based on search query and pagination
        documents-result (e/server
                          (e/Offload
                           #(let [query (if (str/blank? search-query) "*" search-query)]
                              (ts/search ts-settings "KUDOS_preprod_v2_documents_ab897fbdedfa"
                                         {:q query
                                          :query_by "title"
                                          :per_page documents-per-page
                                          :page current-page
                                          :sort_by "title:asc"}))))

        documents (map-indexed (fn [idx hit]
                                 (let [doc (:document hit)]
                                   {:id (:doc_num doc)
                                    :title (or (:title doc) "Untitled Document")
                                    :type (or (:type doc) "Unknown")
                                    :date (or (:publish_date doc) "Unknown")
                                    :orgs (or (:orgs_long doc) [])}))
                               (:hits documents-result))

        ;; Check if there are more documents
        total-found (:found documents-result)
        has-more (> total-found (* current-page documents-per-page))

        ;; State for selected document
        !selected-doc-id (atom (when (seq documents) (:id (first documents))))
        selected-doc-id (e/watch !selected-doc-id)
        selected-doc (first (filter #(= (:id %) selected-doc-id) documents))

        ;; Reset to first document when page changes or search changes
        _ (e/watch (when (seq documents)
                     (reset! !selected-doc-id (:id (first documents)))))

        ;; Functions to handle pagination
        on-page-change (fn [new-page]
                         (reset! !current-page new-page))

        on-search-change (fn [new-query]
                           (reset! !search-query new-query)
                           (reset! !current-page 1)) ; Reset to page 1 when searching

        ;; Fetch chunks for selected document
        chunks (e/server
                (when selected-doc-id
                  (e/Offload
                   #(->> (ts-search-all-pages ts-settings "KUDOS_preprod_v2_chunks_ab897fbdedfa"
                                                    {:q "*"
                                                     :filter_by (str "doc_num:" selected-doc-id)
                                                     :sort_by "chunk_index:asc"})
                         :hits
                         (map (fn [hit]
                                (let [chunk (:document hit)
                                      metadata-str (:metadata chunk)
                                      parsed-metadata (when metadata-str
                                                        (try
                                                          (edn/read-string metadata-str)
                                                          (catch Exception e nil)))]
                                  {:id (:chunk_id chunk)
                                   :content (:content_markdown chunk)
                                   :chunk_index (:chunk_index chunk)
                                   :metadata parsed-metadata})))))))

        ;; Fetch search phrases for selected document chunks
        search-phrases-by-chunk (e/server
                                 (when selected-doc-id
                                   (e/Offload
                                    #(->> (ts-search-all-pages ts-settings "KUDOS_preprod_v2_phrases_ab897fbdedfa"
                                                                     {:q "*"
                                                                      :filter_by (str "doc_num:" selected-doc-id)})
                                          :hits
                                          (map (fn [hit]
                                                 (let [phrase (:document hit)]
                                                   {:chunk_id (:chunk_id phrase)
                                                    :search_phrase (:search_phrase phrase)})))
                                          (group-by :chunk_id)
                                          (y/map-vals (fn [phrases]
                                                        (map :search_phrase phrases)))))))

        ;; Combine chunks with their search phrases
        enriched-chunks (when chunks
                          (map (fn [chunk]
                                 (assoc chunk :search-phrases
                                        (get search-phrases-by-chunk (:id chunk) [])))
                               chunks))

        ;; Fetch collection document counts when info is shown
        collection-stats (when show-info
                          (e/server
                           (e/Offload
                            #(try
                               (let [docs-result (try
                                                   (ts/search ts-settings "KUDOS_preprod_v2_documents_ab897fbdedfa" {:q "*" :per_page 0})
                                                   (catch Exception e nil))
                                     chunks-result (try
                                                     (ts/search ts-settings "KUDOS_preprod_v2_chunks_ab897fbdedfa" {:q "*" :per_page 0})
                                                     (catch Exception e nil))
                                     phrases-result (try
                                                      (ts/search ts-settings "KUDOS_preprod_v2_phrases_ab897fbdedfa" {:q "*" :per_page 0})
                                                      (catch Exception e nil))]
                                 {:documents (or (:found docs-result) "N/A")
                                  :chunks (or (:found chunks-result) "N/A")
                                  :phrases (or (:found phrases-result) "N/A")})
                               (catch Exception e
                                 {:error (str "Failed to fetch collection stats: " (.getMessage e))})))))]

    (dom/div
     ;; Header with info button
     (dom/div
      (dom/props {:style {:display "flex"
                          :justify-content "space-between"
                          :align-items "center"
                          :margin-bottom "1rem"
                          :padding-bottom "0.5rem"
                          :border-bottom "1px solid #e5e7eb"}})

      (ks/Heading {:level 2}
                  (e/fn [] (dom/text "Knowledge Base")))

      (ks/Button {:data-size "sm"
                  :data-variant "tertiary"}
                 (e/fn []
                   (dom/text (if show-info "Hide Info" "Show Info"))
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (swap! !show-info not)
                         (t)))))))

     ;; Info panel (conditionally shown)
     (when show-info
       (ks/Card {:style {:margin-bottom "1rem"}}
                (e/fn []
                  (ks/CardBlock {}
                                (e/fn []
                                  (ks/Heading {:level 3 :style {:margin-bottom "0.5rem"}}
                                              (e/fn [] (dom/text "Typesense Configuration")))

                                  (dom/div
                                   (dom/props {:style {:font-size "0.875rem"
                                                       :color "#6b7280"}})
                                   (dom/div
                                    (dom/props {:style {:margin-bottom "0.5rem"}})
                                    (dom/text "API Host: ")
                                    (dom/span
                                     (dom/props {:style {:font-family "monospace"
                                                         :background "#f3f4f6"
                                                         :padding "0.25rem 0.5rem"
                                                         :border-radius "4px"
                                                         :font-weight "600"}})
                                     (dom/text (e/server (:uri ts-settings)))))

                                   ;; Document counts section
                                   (dom/div
                                    (dom/props {:style {:margin-top "1rem"}})
                                    (ks/Heading {:level 4 :style {:margin-bottom "0.5rem"}}
                                                (e/fn [] (dom/text "Collection Counts")))

                                    (cond
                                      (not collection-stats)
                                      (dom/div
                                       (dom/props {:style {:color "#6b7280"}})
                                       (dom/text "Loading..."))

                                      (:error collection-stats)
                                      (dom/div
                                       (dom/props {:style {:color "#dc2626"}})
                                       (dom/text (:error collection-stats)))

                                      :else
                                      (dom/div
                                       (dom/div
                                        (dom/props {:style {:margin-bottom "0.25rem"}})
                                        (dom/text "Documents: ")
                                        (dom/span
                                         (dom/props {:style {:font-weight "600"}})
                                         (dom/text (str (:documents collection-stats)))))
                                       (dom/div
                                        (dom/props {:style {:margin-bottom "0.25rem"}})
                                        (dom/text "Chunks: ")
                                        (dom/span
                                         (dom/props {:style {:font-weight "600"}})
                                         (dom/text (str (:chunks collection-stats)))))
                                       (dom/div
                                        (dom/text "Search Phrases: ")
                                        (dom/span
                                         (dom/props {:style {:font-weight "600"}})
                                         (dom/text (str (:phrases collection-stats))))))))))))))

     (dom/div
      (dom/props {:style {:display "flex"
                          :height "calc(100vh - 200px)"
                          :gap "1rem"}})

      ;; Left pane - Document list with search and pagination
     (DocumentList {:documents documents
                    :selected-doc-id selected-doc-id
                    :on-select #(reset! !selected-doc-id %)
                    :search-query search-query
                    :on-search-change on-search-change
                    :current-page current-page
                    :on-page-change on-page-change
                    :has-more has-more
                    :is-loading false}) ; Simple static value for now

     ;; Right pane - Document view with chunks
     (DocumentView selected-doc enriched-chunks)))))
