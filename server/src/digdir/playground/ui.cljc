(ns digdir.playground.ui
  "UI components for the RAG Playground feature."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [digdir.auth.core :as auth])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.core :as config-core])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.playground.core :as playground])
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])
            #?(:clj [hiccup2.core :as hiccup])
            #?(:clj [clojure.edn :as edn])
            [lentes.core :as l]
            [digdir.i18n :refer [t]]))

;; =========Markdown Rendering (Server-side)=========

#?(:clj
   (defn render-markdown-to-html
     "Render markdown content to HTML string using nextjournal/markdown"
     [content]
     (when (and content (not (str/blank? content)))
       (try
         (let [parsed (md/parse content)
               hiccup-content (md.transform/->hiccup parsed)]
           (str (hiccup/html hiccup-content)))
         (catch Exception _
           ;; Fallback: escape HTML and convert newlines to <br>
           (-> content
               (str/replace "&" "&amp;")
               (str/replace "<" "&lt;")
               (str/replace ">" "&gt;")
               (str/replace "\n" "<br>")))))))

;; =========Styles=========

(def card-style
  {:margin-bottom "1rem"
   :border "1px solid #e2e8f0"
   :border-radius "8px"
   :background "white"})

(def label-style
  {:display "block"
   :font-weight "500"
   :margin-bottom "0.25rem"
   :font-size "0.875rem"
   :color "#374151"})

(def input-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"})

(def select-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :background "white"
   :font-size "0.875rem"})

(def textarea-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-family "monospace"
   :font-size "0.8rem"
   :resize "vertical"})

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

;; =========Chat UI Styles=========

(def message-bubble-base-style
  {:padding "0.75rem 1rem"
   :border-radius "12px"
   :margin-bottom "0.5rem"
   :max-width "85%"
   :line-height "1.5"})

(def user-message-style
  (merge message-bubble-base-style
         {:background "#3b82f6"
          :color "white"
          :margin-left "auto"
          :border-bottom-right-radius "4px"}))

(def assistant-message-style
  (merge message-bubble-base-style
         {:background "#f3f4f6"
          :color "#1f2937"
          :margin-right "auto"
          :border-bottom-left-radius "4px"}))

(def message-thread-style
  {:flex "1"
   :overflow-y "auto"
   :padding "1rem"
   :display "flex"
   :flex-direction "column"
   :gap "0.5rem"})

(def chat-input-style
  {:padding "0.75rem"
   :border-top "1px solid #e5e7eb"
   :background "white"})

(def conversation-sidebar-style
  {:width "280px"
   :border-right "1px solid #e5e7eb"
   :background "#fafafa"
   :overflow-y "auto"
   :display "flex"
   :flex-direction "column"})

(def sidebar-item-style
  {:padding "0.75rem 1rem"
   :cursor "pointer"
   :border-bottom "1px solid #e5e7eb"
   :font-size "0.875rem"
   :transition "background 0.15s"})

;; =========Client-side State=========
;; State for multi-message chat mode
#?(:cljs
   (defonce !playground-chat-state
     (atom {:conversation-id nil
            :selected-tenant nil      ; Selected tenant for scope
            :selected-environment nil ; Selected environment for scope
            :selected-entity-id nil
            :query ""
            :config {:model "gpt-4o"
                     :temperature 0.1
                     :max-tokens 4096
                     :rerank-top-k 40
                     :context-top-k 10
                     :rerank-threshold nil}
            :execution-id nil
            :show-sidebar true
            :active-branch-path {}    ; Map of parent-msg-id -> selected child index
            :editing-msg-id nil       ; Message ID being edited
            :editing-text ""          ; Text being edited
            :show-diagnostics #{}     ; Set of message IDs with expanded diagnostics
            :compare-mode false       ; Whether compare mode is active
            :compare-execution-ids [] ; Execution IDs to compare
            :show-config-panel false  ; Show config panel in chat
            ;; Action signals (set by callbacks, consumed by main component)
            :pending-send nil         ; Query text to send
            :pending-regenerate nil   ; {:parent-id ... :query ...} to regenerate
            :pending-edit-submit nil})))  ; {:parent-id ... :query ... :branch-idx ...}

;; =========Chunk Detail Modal=========

(def modal-backdrop-style
  {:position "fixed"
   :top "0" :left "0" :right "0" :bottom "0"
   :background "rgba(0,0,0,0.5)"
   :display "flex"
   :align-items "center"
   :justify-content "center"
   :z-index "1000"})

(def modal-content-style
  {:background "white"
   :border-radius "8px"
   :padding "1.5rem"
   :max-width "800px"
   :width "90%"
   :max-height "80vh"
   :overflow-y "auto"})

(defn search-type-badge-style [search-type]
  {:background (case search-type
                 :phrase "#dbeafe"
                 :metadata "#fef3c7"
                 :content "#dcfce7"
                 "#f3f4f6")
   :color (case search-type
            :phrase "#1e40af"
            :metadata "#92400e"
            :content "#166534"
            "#374151")
   :padding "0.25rem 0.5rem"
   :border-radius "4px"
   :font-size "0.75rem"
   :font-weight "600"})

(e/defn ChunkDetailModal [!modal-chunk chunk docs-collection-name]
  (dom/div
   (dom/props {:style modal-backdrop-style})
   (dom/On "click" #(reset! !modal-chunk nil) nil)
   ;; Modal content
   (dom/div
    (dom/props {:style modal-content-style})
    (dom/On "click" #(.stopPropagation %) nil)
    ;; Header with close button
    (dom/div
     (dom/props {:style {:display         "flex"
                         :justify-content "space-between"
                         :align-items     "flex-start"
                         :margin-bottom   "1rem"}})
     (dom/div
      ;; Chunk ID
      (dom/div
       (dom/props {:style {:font-weight   "600"
                           :font-size     "1rem"
                           :margin-bottom "0.5rem"}})
       (dom/text (str "Chunk: " (or (:chunk_id chunk) "Unknown"))))
      ;; Score badges with percentages
      (dom/div
       (dom/props {:style {:display   "flex"
                           :gap       "0.5rem"
                           :flex-wrap "wrap"}})
       (let [search-types (or (:search-types chunk) #{})
             rank         (or (:rank chunk) 0)
             percentage   (Math/round (* rank 100))]
         (e/for [search-type (e/diff-by identity (vec search-types))]
           (dom/span
            (dom/props {:style (search-type-badge-style search-type)})
            (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
     ;; Close button
     (dom/button
      (dom/props {:style {:background  "none"
                          :border      "none"
                          :font-size   "1.5rem"
                          :cursor      "pointer"
                          :color       "#6b7280"
                          :padding     "0"
                          :line-height "1"}})
      (dom/text "×")
      (let [[t err] (e/Token (dom/On "click" identity nil))]
        (when t
          (reset! !modal-chunk nil)
          (t)))))
    ;; Title if available
    (when-let [title (get-in chunk [(keyword docs-collection-name) :title])]
      (dom/div
       (dom/props {:style {:font-weight   "600"
                           :font-size     "1.125rem"
                           :margin-bottom "0.75rem"
                           :color         "#1f2937"}})
       (dom/text title)))
    ;; Full content rendered as markdown
    (let [content (or (:content_markdown chunk) "No content available")
          html    (e/server (render-markdown-to-html content))]
      (dom/div
       (dom/props {:style {:line-height "1.6"
                           :font-size   "0.9rem"}})
       (when html
         (set! (.-innerHTML dom/node) html)))))))

;; =========Search Results Components=========

(e/defn SearchResultsTable [results title !selected-chunk-id]
  (dom/div
   (dom/props {:style {:margin-bottom "0.75rem"}})
   (dom/div
    (dom/props {:style section-header-style})
    (dom/text (str title " (" (count results) " results)")))
   (if (empty? results)
     (dom/div
      (dom/props {:style {:color     "#6b7280"
                          :font-size "0.875rem"
                          :padding   "0.5rem"}})
      (dom/text (t :playground/no-results)))
     (dom/div
      (dom/props {:style {:max-height "200px"
                          :overflow-y "auto"}})
      (dom/table
       (dom/props {:style {:width           "100%"
                           :font-size       "0.75rem"
                           :border-collapse "collapse"}})
       (dom/thead
        (dom/tr
         (dom/props {:style {:background "#f9fafb"}})
         (dom/th (dom/props {:style {:padding    "0.25rem 0.5rem"
                                     :text-align "left"}}) (dom/text "#"))
         (dom/th (dom/props {:style {:padding    "0.25rem 0.5rem"
                                     :text-align "left"}}) (dom/text (t :playground/chunk-id)))
         (dom/th (dom/props {:style {:padding    "0.25rem 0.5rem"
                                     :text-align "right"}}) (dom/text (t :playground/score)))
         (dom/th (dom/props {:style {:padding    "0.25rem 0.5rem"
                                     :text-align "center"}}) (dom/text (t :playground/hits)))))
       (dom/tbody
        (e/for [[idx result] (e/diff-by first (map-indexed vector (take 20 results)))]
          (dom/tr
           (dom/props {:style {:border-bottom "1px solid #e5e7eb"}})
           (dom/td (dom/props {:style {:padding "0.25rem 0.5rem"}}) (dom/text (str (inc idx))))
           (dom/td
            (dom/props {:style {:padding     "0.25rem 0.5rem"
                                :font-family "monospace"
                                :color       "#3b82f6"
                                :cursor      "pointer"}})
            (dom/text (if-let [chunk-id (:chunk_id result)]
                        (subs chunk-id 0 (min 20 (count chunk-id)))
                        "-"))
            (when-let [chunk-id (:chunk_id result)]
              (let [[t err] (e/Token (dom/On "click" identity nil))]
                (when t
                  (reset! !selected-chunk-id {:chunk_id     chunk-id
                                              :rank         (:rank result)
                                              :search-types (:search-types result)})
                  (t)))))
           (dom/td (dom/props {:style {:padding    "0.25rem 0.5rem"
                                       :text-align "right"}})
                   (dom/text (if-let [rank (:rank result)]
                               (if (number? rank)
                                 (.toFixed rank 2)
                                 (str rank))
                               "-")))
           (dom/td (dom/props {:style {:padding    "0.25rem 0.5rem"
                                       :text-align "center"}})
                   (dom/text (str (or (:hit-count result) 1))))))))))))

(e/defn SearchDiagnosticsPanel [results chunks-collection docs-collection]
  (let [!active-tab          (atom :phrase)
        active-tab           (e/watch !active-tab)
        !selected-chunk-info (atom nil)
        selected-chunk-info  (e/watch !selected-chunk-info)
        ;; Fetch chunk from server when selected
        fetched-chunk        (when selected-chunk-info
                               (let [chunk-id    (:chunk_id selected-chunk-info)
                                     chunks-coll chunks-collection
                                     docs-coll   docs-collection]
                                 (e/server
                                  (e/Offload
                                   #(playground/fetch-chunk-by-id chunks-coll docs-coll chunk-id)))))]
    (dom/div
     ;; Tab buttons
     (dom/div
      (dom/props {:style {:display       "flex"
                          :gap           "0.25rem"
                          :margin-bottom "0.75rem"}})
      (e/for [[tab-key label] (e/diff-by first [[:phrase "Phrase"] [:metadata "Metadata"] [:content "Content"] [:merged "Merged"]])]
        (dom/button
         (dom/props {:style {:padding       "0.375rem 0.75rem"
                             :font-size     "0.75rem"
                             :background    (if (= tab-key active-tab) "#3b82f6" "#f3f4f6")
                             :color         (if (= tab-key active-tab) "white" "#374151")
                             :border        "none"
                             :border-radius "4px"
                             :cursor        "pointer"}})
         (dom/text label)
         (let [[token err] (e/Token (dom/On "click" identity nil))]
           (when token
             (reset! !active-tab tab-key)
             (token))))))
     ;; Results for active tab
     (case active-tab
       :phrase (SearchResultsTable (:phrase-search results) "Phrase Search (Hybrid)" !selected-chunk-info)
       :metadata (SearchResultsTable (:metadata-search results) "Metadata Search (BM25)" !selected-chunk-info)
       :content (SearchResultsTable (:content-search results) "Content Search (BM25)" !selected-chunk-info)
       :merged (SearchResultsTable (:merged-results results) "Merged Results" !selected-chunk-info))
     ;; Modal for fetched chunk
     (when (and selected-chunk-info fetched-chunk)
       (let [;; Merge fetched data with selection info (rank, search-types)
             chunk-with-info (merge fetched-chunk selected-chunk-info)]
         (ChunkDetailModal !selected-chunk-info chunk-with-info docs-collection))))))

;; =========Query Relaxation Results=========

(e/defn QueryRelaxationResults [phrases]
  (dom/div
   (dom/props {:style {:margin-bottom "0.75rem"}})
   (dom/div
    (dom/props {:style section-header-style})
    (dom/text (t :playground/search-phrases (count phrases))))
   (if (empty? phrases)
     (dom/div
      (dom/props {:style {:color     "#6b7280"
                          :font-size "0.875rem"}})
      (dom/text (t :playground/no-phrases)))
     (dom/div
      (dom/props {:style {:display   "flex"
                          :flex-wrap "wrap"
                          :gap       "0.5rem"}})
      (e/for [phrase (e/diff-by identity phrases)]
        (dom/span
         (dom/props {:style tag-style})
         (dom/text phrase)))))))

;; =========Used Chunks View=========

(e/defn UsedChunksView [chunks docs-collection-name]
  (let [!modal-chunk (atom nil)
        modal-chunk  (e/watch !modal-chunk)]
    (dom/div
     (dom/div
      (dom/props {:style section-header-style})
      (dom/text (t :playground/used-chunks (count chunks))))
     (if (empty? chunks)
       (dom/div
        (dom/props {:style {:color     "#6b7280"
                            :font-size "0.875rem"}})
        (dom/text (t :playground/no-chunks-used)))
       (dom/div
        (dom/props {:style {:max-height "400px"
                            :overflow-y "auto"}})
        (e/for [[idx chunk] (e/diff-by first (map-indexed vector chunks))]
          (dom/div
           (dom/props {:style {:padding       "0.75rem"
                               :margin-bottom "0.5rem"
                               :border        "1px solid #e5e7eb"
                               :border-radius "6px"
                               :background    "#fafafa"}})
           ;; Header row
           (dom/div
            (dom/props {:style {:display         "flex"
                                :justify-content "space-between"
                                :align-items     "center"
                                :margin-bottom   "0.5rem"}})
            (dom/span
             (dom/props {:style {:font-weight "600"
                                 :font-size   "0.875rem"}})
             (dom/text (str "#" (inc idx))))
            (dom/div
             (dom/props {:style {:display "flex"
                                 :gap     "0.25rem"}})
             (let [search-types (or (:search-types chunk) #{})
                   rank         (or (:rank chunk) 0)
                   percentage   (Math/round (* rank 100))]
               (e/for [search-type (e/diff-by identity (vec search-types))]
                 (dom/span
                  (dom/props {:style (search-type-badge-style search-type)})
                  (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
           ;; Title
           (when-let [title (get-in chunk [(keyword docs-collection-name) :title])]
             (dom/div
              (dom/props {:style {:font-weight   "500"
                                  :font-size     "0.875rem"
                                  :margin-bottom "0.25rem"
                                  :color         "#1f2937"}})
              (dom/text title)))
           ;; Content preview
           (dom/div
            (dom/props {:style {:font-size   "0.75rem"
                                :color       "#6b7280"
                                :max-height  "60px"
                                :overflow    "hidden"
                                :line-height "1.4"}})
            (dom/text (let [content (or (:content_markdown chunk) "")]
                        (if (> (count content) 200)
                          (str (subs content 0 200) "...")
                          content))))
           ;; Show more link
           (dom/button
            (dom/props {:style {:background "none"
                                :border     "none"
                                :color      "#3b82f6"
                                :cursor     "pointer"
                                :font-size  "0.75rem"
                                :padding    "0.25rem 0"
                                :margin-top "0.25rem"}})
            (dom/text (t :playground/show-more))
            (let [[t err] (e/Token (dom/On "click" identity nil))]
              (when t
                (reset! !modal-chunk chunk)
                (t))))))))
     ;; Modal
     (when modal-chunk
       (ChunkDetailModal !modal-chunk modal-chunk docs-collection-name)))))

;; =========Streaming Response Display=========

;; Markdown content styles (applied via class)
(def markdown-content-style
  {:padding       "1rem"
   :background    "#f9fafb"
   :border-radius "6px"
   :min-height    "150px"
   :max-height    "400px"
   :overflow-y    "auto"
   :font-size     "0.875rem"
   :line-height   "1.6"})

(e/defn ExecutionDebugPanel
  "Debug panel showing execution state, errors, and search results during pipeline execution.
   Auto-expands while running, auto-collapses on success, stays expanded on error."
  [execution]
  (e/client
   (let [status  (:status execution)
         stage   (:stage execution)
         error   (:error execution)
         results (:results execution)
         query-relaxation   (get results :query-relaxation [])
         phrase-search      (get results :phrase-search [])
         metadata-search    (get results :metadata-search [])
         content-search     (get results :content-search [])
         merged-results     (get results :merged-results [])
         retrieved-chunks   (get results :retrieved-chunks [])
         ;; Track user's manual override separately from auto-expand behavior
         !user-toggled      (atom false)
         !user-expanded     (atom true)
         user-toggled       (e/watch !user-toggled)
         user-expanded      (e/watch !user-expanded)
         ;; Auto-expand when running or error, collapse on complete (unless user overrode)
         auto-expanded      (case status
                              :running true
                              :error true
                              :complete false
                              true)
         expanded           (if user-toggled user-expanded auto-expanded)]
     (dom/div
      (dom/props {:style {:margin        "0.5rem 1rem"
                          :padding       "0.75rem"
                          :background    (case status
                                           :error "#fef2f2"
                                           :running "#fffbeb"
                                           "#f0fdf4")
                          :border        (str "1px solid "
                                              (case status
                                                :error "#fecaca"
                                                :running "#fde68a"
                                                "#bbf7d0"))
                          :border-radius "8px"
                          :font-size     "0.8rem"}})
      ;; Header with toggle
      (dom/div
       (dom/props {:style {:display         "flex"
                           :justify-content "space-between"
                           :align-items     "center"
                           :cursor          "pointer"}})
       (dom/On "click" (fn [_]
                         (reset! !user-toggled true)
                         (swap! !user-expanded not)) nil)
       (dom/div
        (dom/props {:style {:display     "flex"
                            :align-items "center"
                            :gap         "0.5rem"}})
        ;; Status indicator
        (dom/span
         (dom/props {:style {:width         "10px"
                             :height        "10px"
                             :border-radius "50%"
                             :background    (case status
                                              :running "#f59e0b"
                                              :complete "#22c55e"
                                              :error "#ef4444"
                                              "#9ca3af")}})
         (dom/text ""))
        (dom/span
         (dom/props {:style {:font-weight "600"
                             :color       (case status
                                            :error "#991b1b"
                                            :running "#92400e"
                                            "#166534")}})
         (dom/text (str "Pipeline: " (name (or status :unknown)) " - Stage: " (name (or stage :init))))))
       (dom/span
        (dom/props {:style {:color "#6b7280"}})
        (dom/text (if expanded "▼" "▶"))))

      ;; Expanded content
      (when expanded
        (dom/div
         (dom/props {:style {:margin-top "0.75rem"}})

         ;; Error message (if any)
         (when error
           (dom/div
            (dom/props {:style {:padding       "0.5rem"
                                :background    "#fee2e2"
                                :border-radius "4px"
                                :margin-bottom "0.75rem"
                                :color         "#991b1b"}})
            (dom/div
             (dom/props {:style {:font-weight   "600"
                                 :margin-bottom "0.25rem"}})
             (dom/text "Error:"))
            (dom/div
             (dom/props {:style {:font-family "monospace"
                                 :font-size   "0.75rem"
                                 :word-break  "break-all"}})
             (dom/text error))))

         ;; Typesense diagnostics (when available, usually on error)
         (when-let [ts-diag (:typesense-diagnostics execution)]
           (dom/div
            (dom/props {:style {:padding       "0.75rem"
                                :background    "#fef3c7"
                                :border        "1px solid #fde68a"
                                :border-radius "4px"
                                :margin-bottom "0.75rem"}})
            (dom/div
             (dom/props {:style {:font-weight   "600"
                                 :margin-bottom "0.5rem"
                                 :color         "#92400e"}})
             (dom/text "Typesense Diagnostics"))

            ;; Connection status
            (dom/div
             (dom/props {:style {:display       "flex"
                                 :align-items   "center"
                                 :gap           "0.5rem"
                                 :margin-bottom "0.5rem"}})
             (dom/span
              (dom/props {:style {:width         "8px"
                                  :height        "8px"
                                  :border-radius "50%"
                                  :background    (if (:connected ts-diag) "#22c55e" "#ef4444")}})
              (dom/text ""))
             (dom/span
              (dom/props {:style {:font-size "0.75rem"
                                  :color     "#374151"}})
              (dom/text (str "Connection: " (if (:connected ts-diag) "OK" "FAILED"))))
             (when (:uri ts-diag)
               (dom/span
                (dom/props {:style {:font-size   "0.7rem"
                                    :color       "#6b7280"
                                    :font-family "monospace"}})
                (dom/text (str " (" (:uri ts-diag) ")")))))

            ;; Missing collections warning
            (when (seq (:missing-collections ts-diag))
              (dom/div
               (dom/props {:style {:padding       "0.5rem"
                                   :background    "#fee2e2"
                                   :border-radius "4px"
                                   :margin-bottom "0.5rem"}})
               (dom/div
                (dom/props {:style {:font-weight   "500"
                                    :font-size     "0.75rem"
                                    :color         "#991b1b"
                                    :margin-bottom "0.25rem"}})
                (dom/text "Missing Collections:"))
               (dom/div
                (dom/props {:style {:display   "flex"
                                    :flex-wrap "wrap"
                                    :gap       "0.25rem"}})
                (e/for [coll (e/diff-by identity (:missing-collections ts-diag))]
                  (dom/span
                   (dom/props {:style {:background    "#fecaca"
                                       :color         "#991b1b"
                                       :padding       "0.125rem 0.375rem"
                                       :border-radius "3px"
                                       :font-size     "0.7rem"
                                       :font-family   "monospace"}})
                   (dom/text coll))))))

            ;; Expected collections
            (when (seq (:expected-collections ts-diag))
              (dom/div
               (dom/props {:style {:margin-bottom "0.5rem"}})
               (dom/div
                (dom/props {:style {:font-weight   "500"
                                    :font-size     "0.7rem"
                                    :color         "#6b7280"
                                    :margin-bottom "0.25rem"}})
                (dom/text "Expected Collections:"))
               (dom/div
                (dom/props {:style {:display   "flex"
                                    :flex-wrap "wrap"
                                    :gap       "0.25rem"}})
                (e/for [coll (e/diff-by identity (:expected-collections ts-diag))]
                  (let [exists (not (some #{coll} (:missing-collections ts-diag)))]
                    (dom/span
                     (dom/props {:style {:background    (if exists "#dcfce7" "#fef3c7")
                                         :color         (if exists "#166534" "#92400e")
                                         :padding       "0.125rem 0.375rem"
                                         :border-radius "3px"
                                         :font-size     "0.7rem"
                                         :font-family   "monospace"}})
                     (dom/text (str coll (if exists " ✓" " ✗")))))))))

            ;; Available collections (collapsible)
            (when (seq (:available-collections ts-diag))
              (let [!show-available (atom false)
                    show-available  (e/watch !show-available)]
                (dom/div
                 (dom/div
                  (dom/props {:style {:display     "flex"
                                      :align-items "center"
                                      :gap         "0.25rem"
                                      :cursor      "pointer"
                                      :font-size   "0.7rem"
                                      :color       "#6b7280"}})
                  (dom/On "click" #(swap! !show-available not) nil)
                  (dom/text (str (if show-available "▼" "▶") " Available Collections (" (count (:available-collections ts-diag)) ")")))
                 (when show-available
                   (dom/div
                    (dom/props {:style {:display    "flex"
                                        :flex-wrap  "wrap"
                                        :gap        "0.25rem"
                                        :margin-top "0.25rem"}})
                    (e/for [coll (e/diff-by identity (sort (:available-collections ts-diag)))]
                      (dom/span
                       (dom/props {:style {:background    "#f3f4f6"
                                           :color         "#374151"
                                           :padding       "0.125rem 0.375rem"
                                           :border-radius "3px"
                                           :font-size     "0.65rem"
                                           :font-family   "monospace"}})
                       (dom/text coll))))))))))

         ;; Config resolution debug info (when available)
         (when-let [debug-info (:debug-info execution)]
           (dom/div
            (dom/props {:style {:padding       "0.75rem"
                                :background    "#eff6ff"
                                :border        "1px solid #bfdbfe"
                                :border-radius "4px"
                                :margin-bottom "0.75rem"}})
            (dom/div
             (dom/props {:style {:font-weight   "600"
                                 :margin-bottom "0.5rem"
                                 :color         "#1e40af"}})
             (dom/text "Config Resolution Debug"))

            ;; Scope info
            (dom/div
             (dom/props {:style {:display               "grid"
                                 :grid-template-columns "auto 1fr"
                                 :gap                   "0.25rem 0.75rem"
                                 :font-size             "0.75rem"
                                 :margin-bottom         "0.5rem"}})
             (dom/span (dom/props {:style {:color "#6b7280"}}) (dom/text "Entity ID:"))
             (dom/span (dom/props {:style {:font-family "monospace"}})
                       (dom/text (str (:entity-id debug-info))))
             (dom/span (dom/props {:style {:color "#6b7280"}}) (dom/text "Tenant:"))
             (dom/span (dom/props {:style {:font-family "monospace"}})
                       (dom/text (str (:effective-tenant debug-info))))
             (dom/span (dom/props {:style {:color "#6b7280"}}) (dom/text "Environment:"))
             (dom/span (dom/props {:style {:font-family "monospace"}})
                       (dom/text (str (:effective-env debug-info)))))

            ;; Entity config values
            (when-let [entity-config (:entity-config debug-info)]
              (dom/div
               (dom/div
                (dom/props {:style {:font-weight   "500"
                                    :font-size     "0.7rem"
                                    :color         "#6b7280"
                                    :margin-bottom "0.25rem"}})
                (dom/text "Resolved Entity Config:"))
               (dom/div
                (dom/props {:style {:display               "grid"
                                    :grid-template-columns "auto 1fr"
                                    :gap                   "0.125rem 0.5rem"
                                    :font-size             "0.7rem"
                                    :background            "white"
                                    :padding               "0.5rem"
                                    :border-radius         "4px"}})
                (e/for [[k v] (e/diff-by first (sort-by first entity-config))]
                  (e/client
                   (dom/span (dom/props {:style {:color "#6b7280"}}) (dom/text (name k)))
                   (dom/span (dom/props {:style {:font-family "monospace"
                                                 :word-break  "break-all"
                                                 :color       (if v "#374151" "#9ca3af")}})
                             (dom/text (str (or v "(nil)")))))))))))

         ;; Search pipeline results
         (dom/div
          (dom/props {:style {:display               "grid"
                              :grid-template-columns "repeat(auto-fit, minmax(140px, 1fr))"
                              :gap                   "0.5rem"
                              :margin-bottom         "0.5rem"}})

          ;; Query Relaxation
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Query Relaxation"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq query-relaxation) "#3b82f6" "#9ca3af")}})
            (dom/text (str (count query-relaxation) " phrases"))))

          ;; Phrase Search
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Phrase Search"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq phrase-search) "#3b82f6" "#9ca3af")}})
            (dom/text (str (count phrase-search) " hits"))))

          ;; Metadata Search
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Metadata Search"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq metadata-search) "#3b82f6" "#9ca3af")}})
            (dom/text (str (count metadata-search) " hits"))))

          ;; Content Search
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Content Search"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq content-search) "#3b82f6" "#9ca3af")}})
            (dom/text (str (count content-search) " hits"))))

          ;; Merged Results
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Merged"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq merged-results) "#22c55e" "#9ca3af")}})
            (dom/text (str (count merged-results) " unique"))))

          ;; Retrieved Chunks
          (dom/div
           (dom/props {:style {:padding       "0.5rem"
                               :background    "white"
                               :border-radius "4px"
                               :border        "1px solid #e5e7eb"}})
           (dom/div
            (dom/props {:style {:font-weight   "500"
                                :font-size     "0.7rem"
                                :color         "#6b7280"
                                :margin-bottom "0.25rem"}})
            (dom/text "Retrieved"))
           (dom/div
            (dom/props {:style {:font-size   "1.25rem"
                                :font-weight "600"
                                :color       (if (seq retrieved-chunks) "#22c55e" "#9ca3af")}})
            (dom/text (str (count retrieved-chunks) " chunks")))))

         ;; Show search phrases if available
         (when (seq query-relaxation)
           (dom/div
            (dom/props {:style {:margin-top "0.5rem"}})
            (dom/div
             (dom/props {:style {:font-weight   "500"
                                 :font-size     "0.7rem"
                                 :color         "#6b7280"
                                 :margin-bottom "0.25rem"}})
             (dom/text "Generated search phrases:"))
            (dom/div
             (dom/props {:style {:display   "flex"
                                 :flex-wrap "wrap"
                                 :gap       "0.25rem"}})
             (e/for [phrase (e/diff-by identity query-relaxation)]
               (dom/span
                (dom/props {:style {:background    "#dbeafe"
                                    :color         "#1e40af"
                                    :padding       "0.125rem 0.375rem"
                                    :border-radius "3px"
                                    :font-size     "0.7rem"}})
                (dom/text phrase))))))))))))

(e/defn StreamingResponseDisplay [execution]
  (let [content (or (:streaming-content execution) "")
        status  (:status execution)
        stage   (:stage execution)]
    (dom/div
     ;; Status indicator
     (dom/div
      (dom/props {:style {:display       "flex"
                          :align-items   "center"
                          :gap           "0.5rem"
                          :margin-bottom "0.5rem"}})
      (dom/span
       (dom/props {:style {:width         "8px"
                           :height        "8px"
                           :border-radius "50%"
                           :background    (case status
                                            :running "#f59e0b"
                                            :complete "#22c55e"
                                            :error "#ef4444"
                                            "#9ca3af")}})
       (dom/text ""))
      (dom/span
       (dom/props {:style {:font-size "0.75rem"
                           :color     "#6b7280"}})
       (dom/text (case status
                   :running (t :playground/status-processing (name (or stage :init)))
                   :complete (t :playground/status-complete)
                   :error (t :playground/status-error)
                   (t :playground/status-ready))))))
     ;; Response content - rendered as markdown
    (dom/div
     (dom/props {:style markdown-content-style})
     (if (str/blank? content)
       (dom/span
        (dom/props {:style {:color "#9ca3af"}})
        (dom/text (if (= status :running) (t :playground/generating) (t :playground/response-placeholder))))
        ;; Render markdown content on server
       (let [html (e/server (render-markdown-to-html content))]
         (when html
           (set! (.-innerHTML dom/node) html)))))))

;; =========Multi-Message Chat Components=========

(e/defn DiagnosticsResultsTable
  "Table showing search results for diagnostics."
  [results title on-select-chunk]
  (e/client
   (dom/div
    (dom/props {:style {:margin-bottom "0.5rem"}})
    (if (empty? results)
      (dom/div
       (dom/props {:style {:color     "#6b7280"
                           :font-size "0.75rem"
                           :padding   "0.25rem"}})
       (dom/text (t :playground/no-results)))
      (dom/div
       (dom/props {:style {:max-height "200px"
                           :overflow-y "auto"}})
       (dom/table
        (dom/props {:style {:width           "100%"
                            :font-size       "0.7rem"
                            :border-collapse "collapse"}})
        (dom/thead
         (dom/tr
          (dom/props {:style {:background "#f3f4f6"}})
          (dom/th (dom/props {:style {:padding    "0.2rem 0.4rem"
                                      :text-align "left"
                                      :width      "2rem"}}) (dom/text "#"))
          (dom/th (dom/props {:style {:padding    "0.2rem 0.4rem"
                                      :text-align "left"}}) (dom/text (t :playground/title)))
          (dom/th (dom/props {:style {:padding    "0.2rem 0.4rem"
                                      :text-align "left"
                                      :width      "6rem"}}) (dom/text (t :playground/search-metadata)))
          (dom/th (dom/props {:style {:padding    "0.2rem 0.4rem"
                                      :text-align "right"
                                      :width      "3rem"}}) (dom/text (t :playground/score)))))
        (dom/tbody
         (e/for [[idx result] (e/diff-by first (map-indexed vector (take 15 results)))]
           (dom/tr
            (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                                :cursor        "pointer"}})
            (dom/On "click" #(on-select-chunk (:chunk_id result)) nil)
            (dom/td (dom/props {:style {:padding        "0.2rem 0.4rem"
                                        :vertical-align "top"}})
                    (dom/text (str (inc idx))))
            (dom/td
             (dom/props {:style {:padding       "0.2rem 0.4rem"
                                 :max-width     "200px"
                                 :overflow      "hidden"
                                 :text-overflow "ellipsis"
                                 :white-space   "nowrap"
                                 :color         "#3b82f6"}})
             (dom/text (or (:title result) (:chunk_id result) "-")))
            (dom/td
             (dom/props {:style {:padding       "0.2rem 0.4rem"
                                 :color         "#6b7280"
                                 :font-size     "0.65rem"
                                 :max-width     "100px"
                                 :overflow      "hidden"
                                 :text-overflow "ellipsis"
                                 :white-space   "nowrap"}})
             (dom/text (or (:metadata result) "-")))
            (dom/td (dom/props {:style {:padding    "0.2rem 0.4rem"
                                        :text-align "right"}})
                    (dom/text (if-let [rank (:rank result)]
                                (if (number? rank)
                                  (.toFixed rank 2)
                                  (str rank))
                                "-"))))))))))))

(e/defn DiagnosticsUsedChunks
  "Compact used chunks display for diagnostics."
  [chunks on-select-chunk]
  (e/client
   (dom/div
    (if (empty? chunks)
      (dom/div
       (dom/props {:style {:color     "#6b7280"
                           :font-size "0.75rem"}})
       (dom/text (t :playground/no-chunks-used)))
      (dom/div
       (dom/props {:style {:max-height "250px"
                           :overflow-y "auto"}})
       (e/for [[idx chunk] (e/diff-by first (map-indexed vector chunks))]
         (dom/div
          (dom/props {:style {:padding       "0.5rem"
                              :margin-bottom "0.25rem"
                              :border        "1px solid #e5e7eb"
                              :border-radius "4px"
                              :background    "white"
                              :font-size     "0.7rem"
                              :cursor        "pointer"}})
          (dom/On "click" #(on-select-chunk (:chunk_id chunk)) nil)
          (dom/div
           (dom/props {:style {:display         "flex"
                               :justify-content "space-between"
                               :align-items     "center"
                               :margin-bottom   "0.25rem"}})
           (dom/span
            (dom/props {:style {:font-weight "600"
                                :color       "#374151"}})
            (dom/text (str "#" (inc idx))))
           (dom/div
            (dom/props {:style {:display "flex"
                                :gap     "0.2rem"}})
            (let [search-types (or (:search-types chunk) #{})
                  rank         (or (:rank chunk) 0)
                  percentage   (Math/round (* rank 100))]
              (e/for [search-type (e/diff-by identity (vec search-types))]
                (dom/span
                 (dom/props {:style (search-type-badge-style search-type)})
                 (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
          ;; Title
          (when-let [title (:title chunk)]
            (dom/div
             (dom/props {:style {:font-weight   "500"
                                 :color         "#3b82f6"
                                 :margin-bottom "0.2rem"}})
             (dom/text title)))
          ;; Metadata
          (when-let [metadata (:metadata chunk)]
            (dom/div
             (dom/props {:style {:font-size     "0.65rem"
                                 :color         "#6b7280"
                                 :margin-bottom "0.2rem"}})
             (dom/text metadata)))
          ;; Chunk ID
          (dom/div
           (dom/props {:style {:font-family "monospace"
                               :font-size   "0.6rem"
                               :color       "#9ca3af"}})
           (dom/text (or (:chunk_id chunk) "Unknown"))))))))))

(e/defn DiagnosticsChunkModal
  "Modal for viewing full chunk content in diagnostics."
  [chunk-id docs-collection chunks-collection on-close]
  (e/client
   (when chunk-id
     (let [chunk         (e/server (playground/fetch-chunk-by-id chunks-collection docs-collection (e/client chunk-id)))
           docs-coll-key (keyword docs-collection)]
       (dom/div
        (dom/props {:style modal-backdrop-style})
        (dom/On "click" #(on-close) nil)
        (dom/div
         (dom/props {:style modal-content-style})
         (dom/On "click" #(.stopPropagation %) nil)
         ;; Header
         (dom/div
          (dom/props {:style {:display         "flex"
                              :justify-content "space-between"
                              :align-items     "flex-start"
                              :margin-bottom   "1rem"}})
          (dom/div
           (dom/div
            (dom/props {:style {:font-weight   "600"
                                :font-size     "1rem"
                                :margin-bottom "0.5rem"}})
            (dom/text (t :playground/chunk-title (or chunk-id "Unknown"))))
           ;; Score badges
           (dom/div
            (dom/props {:style {:display   "flex"
                                :gap       "0.5rem"
                                :flex-wrap "wrap"}})
            (let [search-types (or (:search-types chunk) #{})
                  rank         (or (:rank chunk) 0)
                  percentage   (Math/round (* rank 100))]
              (e/for [search-type (e/diff-by identity (vec search-types))]
                (dom/span
                 (dom/props {:style (search-type-badge-style search-type)})
                 (dom/text (str (str/upper-case (name search-type)) " (" percentage "%)")))))))
          ;; Close button
          (dom/button
           (dom/props {:style {:background  "none"
                               :border      "none"
                               :font-size   "1.5rem"
                               :cursor      "pointer"
                               :color       "#6b7280"
                               :padding     "0"
                               :line-height "1"}})
           (dom/text "×")
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-close)
               (t)))))
         ;; Title
         (when-let [title (get-in chunk [docs-coll-key :title])]
           (dom/div
            (dom/props {:style {:font-weight   "600"
                                :font-size     "1.125rem"
                                :margin-bottom "0.75rem"
                                :color         "#1f2937"}})
            (dom/text title)))
         ;; Metadata
         (when-let [metadata (:metadata chunk)]
           (dom/div
            (dom/props {:style {:font-size     "0.85rem"
                                :color         "#6b7280"
                                :margin-bottom "0.75rem"
                                :padding       "0.5rem"
                                :background    "#f3f4f6"
                                :border-radius "4px"}})
            (dom/text metadata)))
         ;; Full content
         (let [content (or (:content_markdown chunk) "No content available")
               html    (e/server (render-markdown-to-html content))]
           (dom/div
            (dom/props {:style {:line-height "1.6"
                                :font-size   "0.9rem"}})
            (when html
              (set! (.-innerHTML dom/node) html))))))))))

(e/defn MessageDiagnosticsPanel
  "Inline expandable diagnostics panel for a message."
  [diagnostics docs-collection chunks-collection]
  (e/client
   (let [parsed-diagnostics (e/server (when diagnostics (edn/read-string diagnostics)))
         !active-tab        (atom :phrases)
         active-tab         (e/watch !active-tab)
         !selected-chunk-id (atom nil)
         selected-chunk-id  (e/watch !selected-chunk-id)
         on-select-chunk    (fn [chunk-id] (reset! !selected-chunk-id chunk-id))]
     (when parsed-diagnostics
       (dom/div
        (dom/props {:style {:margin-top    "0.75rem"
                            :padding       "0.75rem"
                            :background    "#fafafa"
                            :border-radius "6px"
                            :border        "1px solid #e5e7eb"
                            :font-size     "0.8rem"}})

        ;; Tab buttons
        (dom/div
         (dom/props {:style {:display       "flex"
                             :gap           "0.25rem"
                             :margin-bottom "0.5rem"
                             :flex-wrap     "wrap"}})
         (e/for [[tab-key label count-key] (e/diff-by first
                                                      [[:phrases "Phrases" :query-relaxation]
                                                       [:search "Search" :merged-count]
                                                       [:chunks "Used Chunks" :used-chunks-count]])]
           (let [cnt (if (= count-key :query-relaxation)
                       (count (:query-relaxation parsed-diagnostics))
                       (get parsed-diagnostics count-key 0))]
             (dom/button
              (dom/props {:style {:padding       "0.25rem 0.5rem"
                                  :font-size     "0.7rem"
                                  :background    (if (= tab-key active-tab) "#3b82f6" "#e5e7eb")
                                  :color         (if (= tab-key active-tab) "white" "#374151")
                                  :border        "none"
                                  :border-radius "4px"
                                  :cursor        "pointer"}})
              (dom/text (str label " (" cnt ")"))
              (let [[token err] (e/Token (dom/On "click" identity nil))]
                (when token
                  (reset! !active-tab tab-key)
                  (token)))))))

        ;; Tab content
        (case active-tab
          :phrases
          (when-let [phrases (:query-relaxation parsed-diagnostics)]
            (dom/div
             (dom/props {:style {:display   "flex"
                                 :flex-wrap "wrap"
                                 :gap       "0.25rem"}})
             (e/for [phrase (e/diff-by identity phrases)]
               (dom/span
                (dom/props {:style tag-style})
                (dom/text phrase)))))

          :search
          (let [!search-tab (atom :merged)
                search-tab  (e/watch !search-tab)]
            (dom/div
             ;; Search sub-tabs
             (dom/div
              (dom/props {:style {:display       "flex"
                                  :gap           "0.2rem"
                                  :margin-bottom "0.4rem"}})
              (e/for [[stab label results-key] (e/diff-by first
                                                          [[:phrase "Phrase" :phrase-search]
                                                           [:metadata "Metadata" :metadata-search]
                                                           [:content "Content" :content-search]
                                                           [:merged "Merged" :merged-results]])]
                (dom/button
                 (dom/props {:style {:padding       "0.15rem 0.4rem"
                                     :font-size     "0.65rem"
                                     :background    (if (= stab search-tab) "#6b7280" "#f3f4f6")
                                     :color         (if (= stab search-tab) "white" "#374151")
                                     :border        "none"
                                     :border-radius "3px"
                                     :cursor        "pointer"}})
                 (dom/text label)
                 (let [[token err] (e/Token (dom/On "click" identity nil))]
                   (when token
                     (reset! !search-tab stab)
                     (token))))))
             ;; Search results table
             (case search-tab
               :phrase (DiagnosticsResultsTable (:phrase-search parsed-diagnostics) "Phrase" on-select-chunk)
               :metadata (DiagnosticsResultsTable (:metadata-search parsed-diagnostics) "Metadata" on-select-chunk)
               :content (DiagnosticsResultsTable (:content-search parsed-diagnostics) "Content" on-select-chunk)
               :merged (DiagnosticsResultsTable (:merged-results parsed-diagnostics) "Merged" on-select-chunk))))

          :chunks
          (DiagnosticsUsedChunks (:used-chunks parsed-diagnostics) on-select-chunk))

        ;; Chunk detail modal
        (DiagnosticsChunkModal
         selected-chunk-id
         docs-collection
         chunks-collection
         (fn [] (reset! !selected-chunk-id nil))))))))

(e/defn MessageBubble
  "Single message bubble with optional diagnostics."
  [message entity show-diagnostics-ids on-toggle-diagnostics]
  (e/client
   (let [is-user     (= :user (:message/role message))
         msg-id      (:message/id message)
         diagnostics (:message/diagnostics message)
         config-str  (:message/config message)
         config      (when config-str (e/server (edn/read-string config-str)))
         show-diag   (contains? show-diagnostics-ids msg-id)]
     (dom/div
      (dom/props {:style {:display        "flex"
                          :flex-direction "column"
                          :align-items    (if is-user "flex-end" "flex-start")}})
      ;; Message content
      (dom/div
       (dom/props {:style (if is-user user-message-style assistant-message-style)})
       (if is-user
         (dom/text (:message/text message))
         (let [html (e/server (render-markdown-to-html (:message/text message)))]
           (dom/div
            (dom/props {:style {:line-height "1.6"}})
            (when html
              (set! (.-innerHTML dom/node) html))))))

      ;; Action row for assistant messages
      (when (and (not is-user) diagnostics)
        (dom/div
         (dom/props {:style {:display     "flex"
                             :gap         "0.5rem"
                             :margin-top  "0.25rem"
                             :align-items "center"}})
         ;; Toggle diagnostics button
         (dom/button
          (dom/props {:style {:background    "none"
                              :border        "1px solid #d1d5db"
                              :border-radius "4px"
                              :padding       "0.25rem 0.5rem"
                              :font-size     "0.7rem"
                              :cursor        "pointer"
                              :color         "#6b7280"}})
          (dom/text (if show-diag (t :playground/hide-details) (t :playground/show-details)))
          (let [[t err] (e/Token (dom/On "click" identity nil))]
            (when t
              (on-toggle-diagnostics msg-id)
              (t))))
         ;; Config badge if non-default
         (when (and config (:model config))
           (dom/span
            (dom/props {:style {:font-size     "0.65rem"
                                :color         "#9ca3af"
                                :padding       "0.125rem 0.375rem"
                                :background    "#f3f4f6"
                                :border-radius "3px"}})
            (dom/text (:model config))))))

      ;; Diagnostics panel
      (when (and show-diag diagnostics)
        (MessageDiagnosticsPanel diagnostics
                                 (:docs-collection entity)
                                 (:chunks-collection entity)))))))

;; =========Branching Support=========

(defn find-children
  "Find all messages that have the given message as parent."
  [messages parent-id]
  (if parent-id
    (filter #(= parent-id (get-in % [:message/parent-message :message/id])) messages)
    ;; Root messages (no parent) - filter messages with no parent that are user messages
    (filter #(and (nil? (:message/parent-message %))
                  (= :user (:message/role %))) messages)))

(defn has-branches?
  "Check if a message has multiple children (is a branch point)."
  [messages msg-id]
  (> (count (find-children messages msg-id)) 1))

(defn get-visible-messages
  "Filter messages to show only the active branch path.
   Returns messages in the currently selected branch."
  [messages active-branch-path]
  (if (empty? messages)
    []
    (loop [result []
           current-parent-id nil]
      (let [children (find-children messages current-parent-id)
            ;; If multiple children, select based on active-branch-path
            selected-child (if (> (count children) 1)
                             (let [selected-idx (get active-branch-path current-parent-id 0)]
                               (nth (sort-by :message/created children)
                                    (min selected-idx (dec (count children)))))
                             (first (sort-by :message/created children)))]
        (if selected-child
          (recur (conj result selected-child) (:message/id selected-child))
          result)))))

(e/defn BranchSelector
  "Shows branch options when a message has multiple children."
  [parent-msg-id children active-branch-path on-branch-select]
  (e/client
   (when (> (count children) 1)
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.25rem"
                          :padding "0.25rem 0.5rem"
                          :margin "0.25rem 0"
                          :background "#fef3c7"
                          :border-radius "4px"
                          :align-items "center"
                          :font-size "0.75rem"}})
      (dom/span
       (dom/props {:style {:color "#92400e" :margin-right "0.25rem"}})
       (dom/text "Branches:"))
      (let [selected-idx (get active-branch-path parent-msg-id 0)]
        (e/for-by identity [idx (range (count children))]
          (dom/button
           (dom/props {:style {:padding "0.125rem 0.5rem"
                               :background (if (= idx selected-idx) "#3b82f6" "#e5e7eb")
                               :color (if (= idx selected-idx) "white" "#374151")
                               :border "none"
                               :border-radius "3px"
                               :cursor "pointer"
                               :font-size "0.7rem"}})
           (dom/text (str (inc idx)))
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-branch-select parent-msg-id idx)
               (t))))))))))

(e/defn MessageBubbleWithBranching
  "Message bubble with branching support, edit, and regenerate."
  [message entity all-messages show-diagnostics-ids on-toggle-diagnostics on-edit on-regenerate active-branch-path on-branch-select]
  (e/client
   (let [is-user (= :user (:message/role message))
         msg-id (:message/id message)
         parent-id (get-in message [:message/parent-message :message/id])
         diagnostics (:message/diagnostics message)
         config-str (:message/config message)
         config (when config-str (e/server (edn/read-string config-str)))
         show-diag (contains? show-diagnostics-ids msg-id)
         ;; Check if this message's parent has multiple children (branch point)
         siblings (find-children all-messages parent-id)
         is-branch-point (> (count siblings) 1)
         ;; Check if this message has multiple children
         children (find-children all-messages msg-id)
         has-child-branches (> (count children) 1)]
     (dom/div
      ;; Show branch selector before this message if parent has multiple children
      (when (and is-branch-point is-user)
        (BranchSelector parent-id siblings active-branch-path on-branch-select))

      (dom/div
       (dom/props {:style {:display "flex"
                           :flex-direction "column"
                           :align-items (if is-user "flex-end" "flex-start")}})
       ;; Message content
       (dom/div
        (dom/props {:style (if is-user user-message-style assistant-message-style)})
        (if is-user
          (dom/text (:message/text message))
          (let [html (e/server (render-markdown-to-html (:message/text message)))]
            (dom/div
             (dom/props {:style {:line-height "1.6"}})
             (when html
               (set! (.-innerHTML dom/node) html))))))

       ;; Action row
       (dom/div
        (dom/props {:style {:display "flex"
                            :gap "0.5rem"
                            :margin-top "0.25rem"
                            :align-items "center"}})

        ;; Edit button (for user messages)
        (when is-user
          (dom/button
           (dom/props {:style {:background "none"
                               :border "1px solid #d1d5db"
                               :border-radius "4px"
                               :padding "0.25rem 0.5rem"
                               :font-size "0.7rem"
                               :cursor "pointer"
                               :color "#6b7280"}})
           (dom/text "Edit")
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-edit msg-id (:message/text message))
               (t)))))

        ;; Regenerate button (for assistant messages)
        (when (and (not is-user) diagnostics)
          (dom/button
           (dom/props {:style {:background "none"
                               :border "1px solid #d1d5db"
                               :border-radius "4px"
                               :padding "0.25rem 0.5rem"
                               :font-size "0.7rem"
                               :cursor "pointer"
                               :color "#6b7280"}})
           (dom/text "Regenerate")
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               ;; Find the parent user message to regenerate from
               (on-regenerate parent-id)
               (t)))))

        ;; Toggle diagnostics button (for assistant messages)
        (when (and (not is-user) diagnostics)
          (dom/button
           (dom/props {:style {:background "none"
                               :border "1px solid #d1d5db"
                               :border-radius "4px"
                               :padding "0.25rem 0.5rem"
                               :font-size "0.7rem"
                               :cursor "pointer"
                               :color "#6b7280"}})
           (dom/text (if show-diag (t :playground/hide-details) (t :playground/show-details)))
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-toggle-diagnostics msg-id)
               (t)))))

        ;; Config badge if non-default
        (when (and config (:model config))
          (dom/span
           (dom/props {:style {:font-size "0.65rem"
                               :color "#9ca3af"
                               :padding "0.125rem 0.375rem"
                               :background "#f3f4f6"
                               :border-radius "3px"}})
           (dom/text (:model config))))

        ;; Branch indicator
        (when has-child-branches
          (dom/span
           (dom/props {:style {:font-size "0.65rem"
                               :color "#92400e"
                               :padding "0.125rem 0.375rem"
                               :background "#fef3c7"
                               :border-radius "3px"}})
           (dom/text (str (count children) " branches")))))

       ;; Diagnostics panel
       (when (and show-diag diagnostics)
         (MessageDiagnosticsPanel diagnostics
                                  (:docs-collection entity)
                                  (:chunks-collection entity))))))))

(e/defn MessageThread
  "Scrollable list of messages."
  [messages entity show-diagnostics-ids on-toggle-diagnostics on-edit on-regenerate active-branch-path on-branch-select]
  (e/client
   (dom/div
    (dom/props {:style message-thread-style})
    (if (empty? messages)
      (dom/div
       (dom/props {:style {:color "#9ca3af"
                           :text-align "center"
                           :padding "2rem"
                           :font-size "0.875rem"}})
       (dom/text "Start a conversation by typing a message below."))
      (e/for [msg (e/diff-by :message/id messages)]
        (MessageBubbleWithBranching msg entity messages show-diagnostics-ids
                                    on-toggle-diagnostics on-edit on-regenerate
                                    active-branch-path on-branch-select))))))

(e/defn EditMessageModal
  "Modal for editing a message and creating a branch."
  [editing-msg-id editing-text on-cancel on-submit]
  (e/client
   (when editing-msg-id
     (dom/div
      (dom/props {:style modal-backdrop-style})
      (dom/div
       (dom/props {:style (merge modal-content-style {:max-width "600px"})})
       ;; Header
       (dom/div
        (dom/props {:style {:display "flex"
                            :justify-content "space-between"
                            :align-items "center"
                            :margin-bottom "1rem"}})
        (dom/h3
         (dom/props {:style {:margin "0" :font-size "1rem" :font-weight "600"}})
         (dom/text "Edit Message (Creates Branch)"))
        (dom/button
         (dom/props {:style {:background "none"
                             :border "none"
                             :font-size "1.25rem"
                             :cursor "pointer"
                             :color "#6b7280"}})
         (dom/text "×")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-cancel)
             (t)))))
       ;; Textarea
       (dom/textarea
        (dom/props {:style (merge textarea-style {:height "120px" :margin-bottom "1rem"})
                    :value editing-text
                    :autofocus true})
        (dom/On "input" #(swap! !playground-chat-state assoc :editing-text (.. % -target -value)) nil))
       ;; Buttons
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})
        (dom/button
         (dom/props {:style {:padding "0.5rem 1rem"
                             :background "#e5e7eb"
                             :border "none"
                             :border-radius "6px"
                             :cursor "pointer"}})
         (dom/text "Cancel")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-cancel)
             (t))))
        (dom/button
         (dom/props {:style {:padding "0.5rem 1rem"
                             :background "#3b82f6"
                             :color "white"
                             :border "none"
                             :border-radius "6px"
                             :cursor "pointer"}})
         (dom/text "Send as Branch")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-submit editing-text)
             (t))))))))))

(e/defn CompareRunsPanel
  "Side-by-side comparison of two execution runs."
  [execution-ids on-close]
  (e/client
   (let [executions (e/server
                     (mapv #(get @playground/!playground-executions %) (e/client execution-ids)))]
     (dom/div
      (dom/props {:style modal-backdrop-style})
      (dom/div
       (dom/props {:style (merge modal-content-style {:max-width "1200px"
                                                      :width     "95%"})})
       ;; Header
       (dom/div
        (dom/props {:style {:display         "flex"
                            :justify-content "space-between"
                            :align-items     "center"
                            :margin-bottom   "1rem"}})
        (dom/h3
         (dom/props {:style {:margin      "0"
                             :font-size   "1rem"
                             :font-weight "600"}})
         (dom/text "Compare Runs"))
        (dom/button
         (dom/props {:style {:background "none"
                             :border     "none"
                             :font-size  "1.25rem"
                             :cursor     "pointer"
                             :color      "#6b7280"}})
         (dom/text "×")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-close)
             (t)))))
       ;; Comparison grid
       (dom/div
        (dom/props {:style {:display               "grid"
                            :grid-template-columns "1fr 1fr"
                            :gap                   "1rem"}})
        (e/for-by identity [idx (range (min 2 (count executions)))]
                  (let [exec (nth executions idx nil)]
                    (dom/div
                     (dom/props {:style {:border        "1px solid #e5e7eb"
                                         :border-radius "6px"
                                         :padding       "1rem"}})
                     (dom/div
                      (dom/props {:style {:font-weight    "600"
                                          :margin-bottom  "0.75rem"
                                          :padding-bottom "0.5rem"
                                          :border-bottom  "1px solid #e5e7eb"}})
                      (dom/text (str "Run " (inc idx))))
                     (if exec
                       (dom/div
                ;; Query
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text "Query: "))
                         (dom/span (dom/text (or (:query exec) "N/A"))))
                ;; Status
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text "Status: "))
                         (dom/span (dom/text (name (or (:status exec) :unknown)))))
                ;; Search phrases
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text "Search Phrases: "))
                         (dom/span (dom/text (str (count (get-in exec [:results :query-relaxation]))))))
                ;; Chunks used
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text "Chunks Used: "))
                         (dom/span (dom/text (str (count (get-in exec [:results :used-chunks]))))))
                ;; Response preview
                        (dom/div
                         (dom/props {:style {:margin-top "0.75rem"}})
                         (dom/div
                          (dom/props {:style {:font-weight   "500"
                                              :color         "#6b7280"
                                              :margin-bottom "0.25rem"}})
                          (dom/text "Response:"))
                         (dom/div
                          (dom/props {:style {:background    "#f9fafb"
                                              :padding       "0.5rem"
                                              :border-radius "4px"
                                              :max-height    "200px"
                                              :overflow-y    "auto"
                                              :font-size     "0.8rem"}})
                          (let [content (or (:streaming-content exec) "No response")
                                html    (e/server (render-markdown-to-html content))]
                            (when html
                              (set! (.-innerHTML dom/node) html))))))
                       (dom/div
                        (dom/props {:style {:color "#9ca3af"}})
                        (dom/text "No execution data"))))))))))))

(e/defn ConfigPanel
  "Collapsible configuration panel for chat."
  [config on-config-change show-panel on-toggle-panel]
  (e/client
   (dom/div
    (dom/props {:style {:border-top "1px solid #e5e7eb"
                        :background "#f9fafb"}})
    ;; Toggle header
    (dom/div
     (dom/props {:style {:padding "0.5rem 1rem"
                         :cursor "pointer"
                         :display "flex"
                         :justify-content "space-between"
                         :align-items "center"}})
     (dom/span
      (dom/props {:style {:font-weight "500" :font-size "0.875rem"}})
      (dom/text (t :playground/configuration)))
     (dom/span
      (dom/props {:style {:color "#6b7280" :font-size "0.75rem"}})
      (dom/text (if show-panel "▼" "▶")))
     (let [[t err] (e/Token (dom/On "click" identity nil))]
       (when t
         (on-toggle-panel)
         (t))))
    ;; Config fields
    (when show-panel
      (dom/div
       (dom/props {:style {:padding "0.75rem 1rem"
                           :display "grid"
                           :grid-template-columns "repeat(auto-fit, minmax(150px, 1fr))"
                           :gap "0.75rem"}})
       ;; Model
       (dom/div
        (dom/label (dom/props {:style label-style}) (dom/text "Model"))
        (dom/select
         (dom/props {:style select-style :value (:model config)})
         (dom/option (dom/props {:value "gpt-4o"}) (dom/text "gpt-4o"))
         (dom/option (dom/props {:value "gpt-4o-mini"}) (dom/text "gpt-4o-mini"))
         (dom/option (dom/props {:value "gpt-4"}) (dom/text "gpt-4"))
         (dom/On "change" #(on-config-change (assoc config :model (.. % -target -value))) nil)))
       ;; Temperature
       (dom/div
        (dom/label (dom/props {:style label-style}) (dom/text (str "Temp: " (:temperature config))))
        (dom/input
         (dom/props {:type "range" :min "0" :max "1" :step "0.1"
                     :value (str (:temperature config))
                     :style {:width "100%"}})
         (dom/On "input" #(on-config-change (assoc config :temperature (js/parseFloat (.. % -target -value)))) nil)))
       ;; Context Top-K
       (dom/div
        (dom/label (dom/props {:style label-style}) (dom/text "Context Top-K"))
        (dom/input
         (dom/props {:type "number" :style input-style
                     :value (str (:context-top-k config))
                     :min "1" :max "50"})
         (dom/On "input" #(on-config-change (assoc config :context-top-k (js/parseInt (.. % -target -value) 10))) nil))))))))

(e/defn ConversationSidebar
  "Sidebar listing playground conversations."
  [conversations current-convo-id on-select-convo on-delete-convo on-clear-all entity-names]
  (e/client
   (dom/div
    (dom/props {:style conversation-sidebar-style})
    ;; Header with Clear all button
    (dom/div
     (dom/props {:style {:padding       "0.75rem 1rem"
                         :border-bottom "1px solid #e5e7eb"
                         :display       "flex"
                         :justify-content "space-between"
                         :align-items   "center"}})
     (dom/span
      (dom/props {:style {:font-weight "600"
                          :font-size   "0.875rem"
                          :color       "#374151"}})
      (dom/text "Conversations"))
     (when (seq conversations)
       (dom/button
        (dom/props {:style {:padding       "0.25rem 0.5rem"
                            :background    "transparent"
                            :border        "1px solid #fecaca"
                            :border-radius "4px"
                            :font-size     "0.7rem"
                            :color         "#dc2626"
                            :cursor        "pointer"}})
        (dom/text "Clear all")
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (on-clear-all)
            (t))))))
    ;; Conversation list
    (dom/div
     (dom/props {:style {:flex "1" :overflow-y "auto"}})
     (if (empty? conversations)
       (dom/div
        (dom/props {:style {:padding "1rem" :color "#9ca3af" :font-size "0.8rem"}})
        (dom/text "No conversations yet"))
       (e/for [conv (e/diff-by :conversation/id conversations)]
         (let [conv-id (:conversation/id conv)
               is-selected (= conv-id current-convo-id)]
           (dom/div
            (dom/props {:style (merge sidebar-item-style
                                      {:display     "flex"
                                       :align-items "flex-start"
                                       :gap         "0.5rem"}
                                      (when is-selected
                                        {:background "#dbeafe"}))})
          ;; Conversation info (clickable)
          (dom/div
           (dom/props {:style {:flex   "1"
                               :cursor "pointer"}})
           (dom/On "click" (fn [_] (on-select-convo conv)) nil)
           (dom/div
            (dom/props {:style {:font-weight "500" :margin-bottom "0.25rem"}})
            (dom/text (or (:conversation/topic conv) "Untitled")))
           (dom/div
            (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
            (let [entity-id (:conversation/entity-id conv)
                  entity-name (clojure.core/get entity-names entity-id)]
              (dom/text (or entity-name entity-id)))))
          ;; Delete button
          (dom/button
           (dom/props {:style {:padding    "0.25rem"
                               :background "transparent"
                               :border     "none"
                               :color      "#9ca3af"
                               :cursor     "pointer"
                               :font-size  "0.875rem"}})
           (dom/text "×")
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-delete-convo conv-id)
               (t))))))))))))

(e/defn PlaygroundChatInput
  "Chat input area with send button."
  [!query !config entity-id is-running on-send]
  (e/client
   (let [query (e/watch !query)]
     (dom/div
      (dom/props {:style chat-input-style})
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/textarea
        (dom/props {:style (merge textarea-style {:flex "1" :height "60px" :resize "none"})
                    :placeholder "Type your message..."
                    :value query})
        (dom/On "input" #(reset! !query (.. % -target -value)) nil)
        (dom/On "keydown" #(when (and (= "Enter" (.-key %))
                                      (not (.-shiftKey %))
                                      (not (str/blank? query))
                                      (not is-running))
                            (.preventDefault %)
                            (on-send)) nil))
       (dom/button
        (dom/props {:style {:padding "0.5rem 1rem"
                            :background (if (and (not (str/blank? query))
                                                 (not is-running))
                                          "#3b82f6" "#9ca3af")
                            :color "white"
                            :border "none"
                            :border-radius "6px"
                            :font-weight "500"
                            :cursor (if (and (not (str/blank? query))
                                             (not is-running))
                                      "pointer" "not-allowed")}
                    :disabled (or (str/blank? query) is-running)})
        (dom/text (if is-running "Sending..." "Send"))
        (when (and (not (str/blank? query)) (not is-running))
          (let [[t err] (e/Token (dom/On "click" identity nil))]
            (when t
              (on-send)
              (t))))))))))

(e/defn PlaygroundChat
  "Multi-message chat playground with persistence and diagnostics."
  [ts-settings]
  (e/client
   (let [state                 (e/watch !playground-chat-state)
         conversation-id       (:conversation-id state)
         selected-tenant       (:selected-tenant state)
         selected-environment  (:selected-environment state)
         selected-entity-id    (:selected-entity-id state)
         config                (:config state)
         show-diagnostics-ids  (:show-diagnostics state)
         active-branch-path    (:active-branch-path state)
         editing-msg-id        (:editing-msg-id state)
         editing-text          (:editing-text state)
         compare-mode          (:compare-mode state)
         compare-execution-ids (:compare-execution-ids state)
         show-config-panel     (:show-config-panel state)
         pending-send          (:pending-send state)
         pending-regenerate    (:pending-regenerate state)
         pending-edit-submit   (:pending-edit-submit state)

         ;; Server data - fetch accessible tenants and environments
         user-id               (e/server (auth/current-user-id e/http-request))
         all-tenants           (e/server (config-db/list-tenants @(db/get-conn)))
         tenant-names          (e/server (config-db/get-all-tenant-names @(db/get-conn)))
         accessible-tenants    (e/server
                                (when user-id
                                  (perms/get-user-accessible-tenants
                                   @(db/get-conn) user-id all-tenants)))
         accessible-environments (e/server
                                  (when user-id
                                    (perms/get-user-accessible-environments
                                     @(db/get-conn) user-id)))

         ;; Entities are independent of tenant selection (multi-dimensional model)
         all-entity-ids        (e/server
                                (when user-id
                                  (config-db/list-all-entities @(db/get-conn))))
         entity-names          (e/server
                                (when (seq all-entity-ids)
                                  ;; Get names for all entities (use nil tenant for global resolution)
                                  (config-db/get-entity-names @(db/get-conn) nil all-entity-ids)))
         effective-entity-id   selected-entity-id
         ;; For the selected entity, get full config if tenant is selected
         selected-entity       (e/server
                                (when (and effective-entity-id selected-tenant)
                                  (let [env (e/client selected-environment)
                                        master-key (config-core/get-master-key)]
                                    (config-db/get-entity @(db/get-conn)
                                                          (e/client selected-tenant)
                                                          env
                                                          (e/client effective-entity-id)
                                                          master-key))))

         ;; Resolution level based on current selections
         ;; Levels: 1=all, 2=entity+tenant, 3=entity+env, 4=tenant+env, 5=entity, 6=tenant, 7=env, 8=global
         has-entity?           (not (str/blank? selected-entity-id))
         has-tenant?           (not (str/blank? selected-tenant))
         has-env?              (not (str/blank? selected-environment))
         resolution-level      (cond
                                 (and has-entity? has-tenant? has-env?) 1
                                 (and has-entity? has-tenant?) 2
                                 (and has-entity? has-env?) 3
                                 (and has-tenant? has-env?) 4
                                 has-entity? 5
                                 has-tenant? 6
                                 has-env? 7
                                 :else 8)

         ;; Check if all scope selections are made
         scope-complete?       (and (not (str/blank? selected-tenant))
                                    (not (str/blank? selected-environment))
                                    (not (str/blank? selected-entity-id)))

         ;; Fetch conversations from server (use e/watch to react to DB changes)
         conversations         (e/server
                                (let [db (e/watch (db/get-conn))]
                                  (e/Offload #(db/playground-conversations db))))

         ;; Fetch all messages for current conversation
         ;; Use e/watch on the connection to reactively re-fetch when database changes
         all-messages          (when conversation-id
                                 (e/server
                                  (let [db (e/watch (db/get-conn))
                                        convo-id (e/client conversation-id)]
                                    (e/Offload #(db/fetch-conversation-tree db convo-id)))))

         ;; Filter to visible messages based on active branch
         visible-messages      (get-visible-messages (or all-messages []) active-branch-path)

         ;; Current execution state
         execution-id          (:execution-id state)
         execution             (when execution-id
                                 (e/server
                                  (e/watch (l/derive (l/key (e/client execution-id))
                                                     playground/!playground-executions))))
         is-running            (= :running (:status execution))

         ;; Get last message for parent linking
         last-msg              (last visible-messages)
         last-msg-id           (:message/id last-msg)]

     ;; Handle pending send action (reactive)
     (when (and pending-send (not is-running) scope-complete?)
       (let [result (e/server
                     (playground/execute-playground-chat-pipeline
                      {:conversation-id (e/client conversation-id)
                       :tenant          (e/client selected-tenant)
                       :environment     (e/client selected-environment)
                       :entity-id       (e/client effective-entity-id)
                       :query           (e/client (:query pending-send))
                       :config          (e/client config)
                       :parent-msg-id   (e/client (:parent-msg-id pending-send))
                       :branch-index    0
                       :user-id         nil}))]
         (swap! !playground-chat-state assoc
                :conversation-id (:conversation-id result)
                :execution-id (:execution-id result)
                :pending-send nil)))

     ;; Handle pending regenerate action (reactive)
     (when (and pending-regenerate (not is-running) scope-complete?)
       (let [result (e/server
                     (playground/execute-playground-chat-pipeline
                      {:conversation-id (e/client conversation-id)
                       :tenant          (e/client selected-tenant)
                       :environment     (e/client selected-environment)
                       :entity-id       (e/client effective-entity-id)
                       :query           (e/client (:query pending-regenerate))
                       :config          (e/client config)
                       :parent-msg-id   (e/client (:parent-id pending-regenerate))
                       :branch-index    (e/client (:branch-idx pending-regenerate))
                       :user-id         nil}))]
         (swap! !playground-chat-state assoc
                :execution-id (:execution-id result)
                :pending-regenerate nil)))

     ;; Handle pending edit submit action (reactive)
     (when (and pending-edit-submit (not is-running) scope-complete?)
       (let [result (e/server
                     (playground/execute-playground-chat-pipeline
                      {:conversation-id (e/client conversation-id)
                       :tenant          (e/client selected-tenant)
                       :environment     (e/client selected-environment)
                       :entity-id       (e/client effective-entity-id)
                       :query           (e/client (:query pending-edit-submit))
                       :config          (e/client config)
                       :parent-msg-id   (e/client (:parent-id pending-edit-submit))
                       :branch-index    (e/client (:branch-idx pending-edit-submit))
                       :user-id         nil}))]
         (swap! !playground-chat-state assoc
                :conversation-id (:conversation-id result)
                :execution-id (:execution-id result)
                :editing-msg-id nil
                :editing-text ""
                :pending-edit-submit nil)))

     (dom/div
      (dom/props {:style {:display    "flex"
                          :height     "calc(100vh - 140px)"
                          :background "#fff"}})

      ;; Sidebar
      (when (:show-sidebar state)
        (ConversationSidebar conversations conversation-id
                             ;; on-select-convo - updates state and signals to load execution
                             (fn [conv]
                               ;; Restore full context from conversation
                               (swap! !playground-chat-state assoc
                                      :conversation-id (:conversation/id conv)
                                      :selected-entity-id (:conversation/entity-id conv)
                                      :selected-tenant (:conversation/tenant conv)
                                      :selected-environment (:conversation/environment conv)
                                      :active-branch-path {}
                                      :show-diagnostics #{}
                                      :pending-load-execution-for-convo (:conversation/id conv)))
                             ;; on-delete-convo - signals intent, handled below
                             (fn [convo-id]
                               (swap! !playground-chat-state assoc :pending-delete-convo-id convo-id))
                             ;; on-clear-all - signals intent, handled below
                             (fn []
                               (swap! !playground-chat-state assoc :pending-clear-all true))
                             entity-names))

      ;; Handle pending delete conversation (server operation)
      (when-some [delete-id (:pending-delete-convo-id state)]
        (e/server
         (let [id-to-delete (e/client delete-id)]
           (e/Offload #(db/delete-playground-conversation (db/get-conn) id-to-delete))))
        (e/client
         (swap! !playground-chat-state assoc
                :pending-delete-convo-id nil
                :conversation-id (when-not (= delete-id conversation-id) conversation-id)
                :active-branch-path (when-not (= delete-id conversation-id) active-branch-path))))

      ;; Handle pending clear all (server operation)
      (when (:pending-clear-all state)
        (e/server
         (e/Offload #(db/clear-all-playground-conversations (db/get-conn))))
        (e/client
         (swap! !playground-chat-state assoc
                :pending-clear-all nil
                :conversation-id nil
                :active-branch-path {})))

      ;; Handle pending load execution for conversation (server lookup)
      (when-some [convo-id (:pending-load-execution-for-convo state)]
        (let [latest-exec-id (e/server
                              (playground/get-latest-execution-id-for-conversation
                               (e/client convo-id)))]
          (e/client
           (swap! !playground-chat-state assoc
                  :pending-load-execution-for-convo nil
                  :execution-id latest-exec-id))))

      ;; Handle pending new conversation (server operation)
      (when (:pending-new-conversation state)
        (let [new-convo-id (e/server
                            (let [entity-id (e/client effective-entity-id)
                                  tenant (e/client selected-tenant)
                                  env (e/client selected-environment)]
                              (e/Offload
                               #(let [result (db/create-playground-conversation
                                              (db/get-conn)
                                              entity-id
                                              {:tenant tenant :environment env})]
                                  (:conversation-id result)))))]
          (e/client
           (swap! !playground-chat-state assoc
                  :pending-new-conversation nil
                  :conversation-id new-convo-id
                  :execution-id nil
                  :active-branch-path {}
                  :show-diagnostics #{}))))

      ;; Main chat area
      (dom/div
       (dom/props {:style {:flex           "1"
                           :display        "flex"
                           :flex-direction "column"
                           :min-width      "0"}})

       ;; Header
       (dom/div
        (dom/props {:style {:padding         "0.75rem 1rem"
                            :border-bottom   "1px solid #e5e7eb"
                            :display         "flex"
                            :justify-content "space-between"
                            :align-items     "center"}})
        (dom/div
         (dom/props {:style {:display     "flex"
                             :gap         "1rem"
                             :align-items "center"}})
         ;; Toggle sidebar button
         (dom/button
          (dom/props {:style {:background    "none"
                              :border        "1px solid #d1d5db"
                              :border-radius "4px"
                              :padding       "0.25rem 0.5rem"
                              :cursor        "pointer"}})
          (dom/text (if (:show-sidebar state) "◀" "▶"))
          (let [[t err] (e/Token (dom/On "click" identity nil))]
            (when t
              (swap! !playground-chat-state update :show-sidebar not)
              (t))))
         ;; Title
         (dom/h2
          (dom/props {:style {:margin      "0"
                              :font-size   "1.125rem"
                              :font-weight "600"}})
          (dom/text (t :playground/chat-header))))

        ;; Scope selectors with hierarchy indicator
        (dom/div
         (dom/props {:style {:display     "flex"
                             :gap         "0.5rem"
                             :align-items "center"
                             :flex-wrap   "wrap"}})

         ;; Entity selector (independent - always enabled)
         (dom/select
          (dom/props {:style (merge select-style
                                    {:width "180px"}
                                    (when (not (str/blank? effective-entity-id))
                                      {:border-color "#3b82f6"
                                       :background   "#eff6ff"}))
                      :value (or effective-entity-id "")})
          (dom/option (dom/props {:value ""}) (dom/text "Entity..."))
          (e/for [entity-id (e/diff-by identity (sort (or all-entity-ids [])))]
            (dom/option
             (dom/props {:value entity-id})
             (dom/text (or (clojure.core/get entity-names entity-id) entity-id))))
          (dom/On "change" #(swap! !playground-chat-state
                                   assoc :selected-entity-id (.. % -target -value)) nil))

         ;; Tenant selector (independent)
         (dom/select
          (dom/props {:style (merge select-style
                                    {:width "140px"}
                                    (when (not (str/blank? selected-tenant))
                                      {:border-color "#3b82f6"
                                       :background   "#eff6ff"}))
                      :value (or selected-tenant "")})
          (dom/option (dom/props {:value ""}) (dom/text "Tenant..."))
          (e/for [tenant (e/diff-by identity (sort (or accessible-tenants [])))]
            (dom/option
             (dom/props {:value tenant})
             (dom/text (clojure.core/get tenant-names tenant tenant))))
          (dom/On "change" #(swap! !playground-chat-state
                                   assoc :selected-tenant (.. % -target -value)) nil))

         ;; Environment selector (independent)
         (dom/select
          (dom/props {:style (merge select-style
                                    {:width "120px"}
                                    (when (not (str/blank? selected-environment))
                                      {:border-color "#3b82f6"
                                       :background   "#eff6ff"}))
                      :value (or selected-environment "")})
          (dom/option (dom/props {:value ""}) (dom/text "Env..."))
          (e/for [env (e/diff-by identity (sort (or accessible-environments [])))]
            (dom/option
             (dom/props {:value env})
             (dom/text env)))
          (dom/On "change" #(swap! !playground-chat-state
                                   assoc :selected-environment (.. % -target -value)) nil))

         ;; Resolution level indicator
         (dom/span
          (dom/props {:style {:font-size   "0.75rem"
                              :padding     "0.25rem 0.5rem"
                              :border-radius "4px"
                              :background  (case resolution-level
                                             1 "#dcfce7" ; green - most specific
                                             (2 3 4) "#dbeafe" ; blue - 2 dims
                                             (5 6 7) "#fef3c7" ; yellow - 1 dim
                                             "#f3f4f6") ; gray - global
                              :color       (case resolution-level
                                             1 "#166534"
                                             (2 3 4) "#1e40af"
                                             (5 6 7) "#92400e"
                                             "#6b7280")}})
          (dom/text (case resolution-level
                      1 "E+T+Env"
                      2 "E+T"
                      3 "E+Env"
                      4 "T+Env"
                      5 "Entity"
                      6 "Tenant"
                      7 "Env"
                      "Global")))
         ;; New conversation button - signals intent, handled by reactive block below
         (dom/button
          (dom/props {:style {:padding       "0.5rem 1rem"
                              :background    (if scope-complete? "#10b981" "#9ca3af")
                              :color         "white"
                              :border        "none"
                              :border-radius "6px"
                              :font-weight   "500"
                              :cursor        (if scope-complete? "pointer" "not-allowed")}
                      :disabled (not scope-complete?)})
          (dom/text (t :playground/new-chat))
          (when scope-complete?
            (let [[tok err] (e/Token (dom/On "click" identity nil))]
              (when tok
                (swap! !playground-chat-state assoc :pending-new-conversation true)
                (tok)))))))

       ;; Message thread with branching support
       (MessageThread
        visible-messages
        selected-entity
        show-diagnostics-ids
        ;; on-toggle-diagnostics
        (fn [msg-id]
          (swap! !playground-chat-state update :show-diagnostics
                 (fn [s] (if (contains? s msg-id)
                           (disj s msg-id)
                           (conj s msg-id)))))
        ;; on-edit
        (fn [msg-id text]
          (swap! !playground-chat-state assoc
                 :editing-msg-id msg-id
                 :editing-text text))
        ;; on-regenerate - just signals the action, main component handles it
        (fn [parent-user-msg-id]
          (when parent-user-msg-id
            (let [user-msg (first (filter #(= parent-user-msg-id (:message/id %)) all-messages))]
              (when user-msg
                (let [user-parent-id (get-in user-msg [:message/parent-message :message/id])
                      siblings       (find-children all-messages user-parent-id)
                      new-branch-idx (count siblings)]
                  (swap! !playground-chat-state assoc
                         :pending-regenerate {:parent-id  user-parent-id
                                              :query      (:message/text user-msg)
                                              :branch-idx new-branch-idx}))))))
        ;; active-branch-path
        active-branch-path
        ;; on-branch-select
        (fn [parent-id idx]
          (swap! !playground-chat-state assoc-in [:active-branch-path parent-id] idx)))

       ;; Streaming response (if in progress)
       (when (and is-running (:streaming-content execution))
         (dom/div
          (dom/props {:style {:padding "0 1rem"}})
          (dom/div
           (dom/props {:style (merge assistant-message-style {:opacity "0.8"})})
           (let [html (e/server (render-markdown-to-html
                                 (or (:streaming-content execution) "Thinking...")))]
             (dom/div
              (dom/props {:style {:line-height "1.6"}})
              (when html
                (set! (.-innerHTML dom/node) html)))))))

       ;; Debug panel - show during execution or when there's an error
       (when execution
         (ExecutionDebugPanel execution))

       ;; Configuration panel
       (ConfigPanel
        config
        (fn [new-config] (swap! !playground-chat-state assoc :config new-config))
        show-config-panel
        (fn [] (swap! !playground-chat-state update :show-config-panel not)))

       ;; Input area with send button using e/Token
       (dom/div
        (dom/props {:style chat-input-style})
        (let [!local-query (atom "")
              local-query  (e/watch !local-query)]
          (dom/div
           (dom/props {:style {:display "flex"
                               :gap     "0.5rem"}})
           (dom/textarea
            (dom/props {:style       (merge textarea-style {:flex   "1"
                                                            :height "60px"
                                                            :resize "none"})
                        :placeholder "Type your message..."
                        :value       local-query})
            (dom/On "input" #(reset! !local-query (.. % -target -value)) nil)
            (dom/On "keydown"
                    #(when (and (= "Enter" (.-key %))
                                (not (.-shiftKey %))
                                (not (str/blank? local-query))
                                (not is-running)
                                scope-complete?)
                       (.preventDefault %)
                       (swap! !playground-chat-state assoc
                              :pending-send {:query         local-query
                                             :parent-msg-id last-msg-id})
                       (reset! !local-query ""))
                    nil))
           (dom/button
            (dom/props {:style    {:padding       "0.5rem 1rem"
                                   :background    (if (and (not (str/blank? local-query))
                                                           (not is-running))
                                                    "#3b82f6" "#9ca3af")
                                   :color         "white"
                                   :border        "none"
                                   :border-radius "6px"
                                   :font-weight   "500"
                                   :cursor        (if (and (not (str/blank? local-query))
                                                           (not is-running))
                                                    "pointer" "not-allowed")}
                        :disabled (or (str/blank? local-query) is-running)})
            (dom/text (if is-running "Sending..." "Send"))
            (when (and (not (str/blank? local-query)) (not is-running))
              (let [[t err] (e/Token (dom/On "click" identity nil))]
                (when t
                  (swap! !playground-chat-state assoc
                         :pending-send {:query         local-query
                                        :parent-msg-id last-msg-id})
                  (reset! !local-query "")
                  (t)))))))))

      ;; Edit message modal
      (EditMessageModal
       editing-msg-id
       editing-text
       ;; on-cancel
       (fn []
         (swap! !playground-chat-state assoc :editing-msg-id nil :editing-text ""))
       ;; on-submit - just signals the action
       (fn [new-text]
         (when (and editing-msg-id (not (str/blank? new-text)))
           (let [edited-msg     (first (filter #(= editing-msg-id (:message/id %)) all-messages))
                 parent-id      (get-in edited-msg [:message/parent-message :message/id])
                 siblings       (find-children all-messages parent-id)
                 new-branch-idx (count siblings)]
             (swap! !playground-chat-state assoc
                    :pending-edit-submit {:parent-id  parent-id
                                          :query      new-text
                                          :branch-idx new-branch-idx})))))

      ;; Compare runs modal
      (when (and compare-mode (seq compare-execution-ids))
        (CompareRunsPanel
         compare-execution-ids
         (fn []
           (swap! !playground-chat-state assoc
                  :compare-mode false
                  :compare-execution-ids []))))))))