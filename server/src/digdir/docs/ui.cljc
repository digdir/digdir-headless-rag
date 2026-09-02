(ns digdir.docs.ui
  "Data source import UI components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [com.itonomi.komponentkassen.hyperfiddle.electric-forms5 :as forms]
            [clojure.string :as str]
            [digdir.util.ui :refer [pprint-str]]
            [digdir.util.ui :as util]
            [digdir.ui.components :refer [DocumentLoadingJobStatus KVTable SignalWindow]]
            #?(:clj [clj-http.client :as http])
            #?(:clj [digdir.docs.loader :as document-loading])
            #?(:clj [digdir.docs.website :as website-loading])
            #?(:clj [digdir.docs.folder :as folder-loading])
            #?(:clj [digdir.docs.episerver :as episerver-loading])))

;; =========Start Job Button State=========
#?(:clj
   (defonce !start-job-button-disabled?
     (atom false)))

(comment
  (reset! !start-job-button-disabled? false))

;; =========KUDOS Documents Import=========

(e/defn KudosDocuments [ts-settings]
  (let [sigs (e/server (e/watch document-loading/!signal-window))]
    (dom/div
     (dom/div (dom/b (dom/text "Store threads: ")) (dom/text (e/server (e/watch document-loading/!store-threads))))
     (dom/div (dom/b (dom/text "Buffer size: ")) (dom/text (e/server (e/watch document-loading/!buffer-before-store-documents-size))))

     (KVTable (e/server (select-keys
                         ;; It takes a while for a cache miss to occur but it's a very important piece of information
                         (merge {:document-loading/search-phrases-distillation-cache-miss 0
                                 :document-loading/markdown-conversion-cache-miss 0}

                                (:id-counts (e/watch document-loading/!transient-telemetry-aggregate)))
                         [:document-loading/document-prepared
                          :document-loading/document-upserted
                          :document-loading/search-phrases-distillation-cache-hit
                          :document-loading/search-phrases-distillation-cache-miss
                          :document-loading/markdown-conversion-cache-hit
                          :document-loading/markdown-conversion-cache-miss
                          :document-loading/prepare-document-failure
                          :document-loading/upserting-to-documents-typesense-collection])))
     (dom/hr)
     (KVTable (e/server (:id-counts (e/watch document-loading/!transient-telemetry-aggregate))))

     (let [terminal-failure? (e/server (e/watch document-loading/!terminal-failure?))
           failed-count (e/server (count (e/watch document-loading/!failed-documents)))]
       (when terminal-failure?
         (dom/div
          (dom/props {:style {:background-color "#fee2e2"
                              :border "1px solid #ef4444"
                              :border-radius "0.375rem"
                              :padding "0.75rem 1rem"
                              :margin-bottom "1rem"
                              :color "#991b1b"}})
          (dom/text (str "Warning: Import stopped: " failed-count " documents failed (max failures reached). "
                         "You can retry failed documents or restart the job.")))))

     (dom/div
      (dom/props {:style {:display "flex"
                          :justify-content "flex-start"
                          :gap "1rem"
                          :margin-bottom "1rem"}})
      (let [start-job-button-disabled? (e/server (e/watch !start-job-button-disabled?))
            terminal-failure? (e/server (e/watch document-loading/!terminal-failure?))
            can-start? (or (not start-job-button-disabled?) terminal-failure?)]
        (ks/Button {:data-size :sm
                    :disabled (not can-start?)}
                   (e/fn []
                     (dom/text "Start Document Loading Job")
                     (when can-start?
                       (let [[t err] (e/Token (dom/On "click" identity nil))]
                         (when t
                           (case (e/server
                                  (reset! document-loading/!terminal-failure? false)
                                  (util/thread (document-loading/-main))
                                  nil)
                             (case (e/server (reset! !start-job-button-disabled? true))
                               (t)))))))))

      (ks/Button {:data-size :sm
                  :data-variant :secondary}
                 (e/fn []
                   (dom/text "Reset failed docs list")
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (e/server (document-loading/reset-failed-documents!))
                         (t))))))

      (let [has-failed-docs? (e/server (seq (e/watch document-loading/!failed-documents)))]
        (ks/Button {:data-size :sm
                    :data-variant :secondary
                    :disabled (not has-failed-docs?)}
                   (e/fn []
                     (dom/text "Re-try failed documents")
                     (when has-failed-docs?
                       (let [[t err] (e/Token (dom/On "click" identity nil))]
                         (when t
                           (case (e/server
                                  (reset! document-loading/!terminal-failure? false)
                                  (util/thread (document-loading/retry-failed-documents! document-loading/kview))
                                  nil)
                             (t)))))))))

     ;; Failed documents list
     (let [failed-docs (e/server (e/watch document-loading/!failed-documents))]
       (when (seq failed-docs)
         (dom/div
          (dom/props {:style {:background-color "#fef3c7"
                              :border "1px solid #f59e0b"
                              :border-radius "0.375rem"
                              :padding "0.75rem 1rem"
                              :margin-bottom "1rem"}})
          (dom/div
           (dom/props {:style {:font-weight "600"
                               :margin-bottom "0.5rem"
                               :color "#92400e"}})
           (dom/text (str "Failed documents (" (count failed-docs) "):")))
          (dom/div
           (dom/props {:style {:max-height "200px"
                               :overflow-y "auto"
                               :font-family "monospace"
                               :font-size "0.875rem"}})
           (e/for [doc-id (e/server (e/diff-by identity failed-docs))]
             (dom/div
              (dom/props {:style {:padding "0.25rem 0"
                                  :border-bottom "1px solid #fcd34d"}})
              (dom/text (str doc-id))))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Import configuration"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (DocumentLoadingJobStatus document-loading/kview)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Log messages"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (dom/div
                                 (dom/props {:style {:height "300px"
                                                     :overflow-y :scroll}})

                                 (e/for [sig (e/diff-by identity (SignalWindow))]
                                   (dom/p
                                    (dom/text (str (:id sig) " " (:msg_ sig) " " (:inst sig)))
                                    (dom/b (dom/text " Chars: " (count (str sig))))))))))))))))

;; =========Experimentation & RAG Testing=========

(e/defn Experimentation []
  (let [x (dom/div (ks/Field {:style {:max-width "80ch"}}
                             (e/fn []
                               (ks/Label {}
                                         (e/fn [] (dom/text "Dokument ID")))
                               (ks/FieldDescription {}
                                                    (e/fn []
                                                      (dom/text "Du finner ID-er i URLer til dokumenter i Kudos.")
                                                      (dom/p (dom/text "For eksempel: i ")
                                                             (ks/Link {:href "https://kudos.dfo.no/dokument/379135"}
                                                                      (e/fn [] (dom/text "https://kudos.dfo.no/dokument/379135")))
                                                             (dom/text " er ID-en 379135"))))
                               (forms/Input "379135"))))]

    (dom/pre (dom/text (e/server (e/Offload #(:body (http/get "https://kudos.dfo.no/api/v0/documents/379135"))))))))

(e/defn RAG []
  (when (forms/Button {:label "Run RAG"})
    (js/alert "clicked")))

;; =========Data Source Loaders=========

(e/defn WebsiteLoading [ts-settings]
  (let [sigs (e/server (e/watch website-loading/!signal-window))]
    (dom/div
     (dom/div (dom/b (dom/text "Store threads: ")) (dom/text (e/server (e/watch website-loading/!store-threads))))

     (KVTable (e/server (select-keys
                         (merge {:website-loading/search-phrases-cache-miss 0
                                 :website-loading/markdown-cache-miss       0}
                                (:id-counts (e/watch website-loading/!transient-telemetry-aggregate)))
                         [:website-loading/document-prepared
                          :website-loading/document-upserted
                          :website-loading/search-phrases-cache-hit
                          :website-loading/search-phrases-cache-miss
                          :website-loading/markdown-cache-hit
                          :website-loading/markdown-cache-miss
                          :website-loading/upserting-document])))

     (dom/hr)
     (KVTable (e/server (:id-counts (e/watch website-loading/!transient-telemetry-aggregate))))

     (dom/div
      (dom/props {:style {:display         "flex"
                          :justify-content "flex-start"
                          :gap             "1rem"
                          :margin-bottom   "1rem"}})

      (let [start-job-button-disabled? (e/server (e/watch website-loading/!start-job-button-disabled?))]
        (ks/Button {:data-size :sm
                    :disabled  start-job-button-disabled?}
                   (e/fn []
                     (dom/text "Start Website Loading Job")
                     (when-not start-job-button-disabled?
                       (let [[t err] (e/Token (dom/On "click" identity nil))]
                         (when t
                           (case (e/server (util/thread (website-loading/-main))
                                           nil)
                             (case (e/server (reset! website-loading/!start-job-button-disabled? true))
                               (t)))))))))

      (ks/Button {:data-size    :sm
                  :data-variant :secondary
                  :disabled     (e/server (not (e/watch website-loading/!job-canceller)))}
                 (e/fn []
                   (dom/text "Cancel Job")
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (e/server (website-loading/stop-job))
                         (t)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Import configuration"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (DocumentLoadingJobStatus website-loading/wview)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Log messages"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (dom/div
                                 (dom/props {:style {:height     "300px"
                                                     :overflow-y :scroll}})

                                 (e/for [sig (e/diff-by identity sigs)]
                                   (dom/p
                                    (dom/text (str (:id sig) " " (:msg_ sig) " " (:inst sig)))
                                    (dom/b (dom/text " Chars: " (count (str sig))))))))))))))))

(e/defn FolderLoading [ts-settings]
  (let [sigs (e/server (e/watch folder-loading/!signal-window))]
    (dom/div
     (dom/div (dom/b (dom/text "Store threads: ")) (dom/text (e/server (e/watch folder-loading/!store-threads))))

     (KVTable (e/server (select-keys
                         (merge {:folder-loading/search-phrases-cache-miss 0
                                 :folder-loading/markdown-cache-miss 0}
                                (:id-counts (e/watch folder-loading/!transient-telemetry-aggregate)))
                         [:folder-loading/document-prepared
                          :folder-loading/document-upserted
                          :folder-loading/search-phrases-cache-hit
                          :folder-loading/search-phrases-cache-miss
                          :folder-loading/markdown-cache-hit
                          :folder-loading/markdown-cache-miss
                          :folder-loading/upserting-document])))

     (dom/hr)
     (KVTable (e/server (:id-counts (e/watch folder-loading/!transient-telemetry-aggregate))))

     (dom/div
      (dom/props {:style {:display "flex"
                          :justify-content "flex-start"
                          :gap "1rem"
                          :margin-bottom "1rem"}})

      (let [start-job-button-disabled? (e/server (e/watch folder-loading/!start-job-button-disabled?))]
        (ks/Button {:data-size :sm
                    :disabled start-job-button-disabled?}
                   (e/fn []
                     (dom/text "Start Folder Loading Job")
                     (when-not start-job-button-disabled?
                       (let [[t err] (e/Token (dom/On "click" identity nil))]
                         (when t
                           (case (e/server (util/thread (folder-loading/-main))
                                           nil)
                             (case (e/server (reset! folder-loading/!start-job-button-disabled? true))
                               (t)))))))))

      (ks/Button {:data-size :sm
                  :data-variant :secondary
                  :disabled (e/server (not (e/watch folder-loading/!job-canceller)))}
                 (e/fn []
                   (dom/text "Cancel Job")
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (e/server (folder-loading/stop-job))
                         (t)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Import configuration"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (DocumentLoadingJobStatus folder-loading/wview)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Log messages"))))
           (ks/DetailsContent
            {} (e/fn []
                 (dom/div
                  (dom/props {:style {:height     "300px"
                                      :overflow-y :scroll}})

                  (e/for [sig (e/diff-by identity sigs)]
                    (dom/p
                     (dom/text (str (:id sig) " " (:msg_ sig) " " (:inst sig)))
                     (dom/b (dom/text " Chars: " (count (str sig))))))))))))))))

(e/defn OptimizelyLoading [ts-settings]
  (let [sigs (e/server (e/watch episerver-loading/!signal-window))]
    (dom/div
     (dom/div (dom/b (dom/text "Store threads: ")) (dom/text (e/server (e/watch episerver-loading/!store-threads))))

     (KVTable (e/server (select-keys
                         (merge {:episerver-loading/search-phrases-cache-miss 0}
                                (:id-counts (e/watch episerver-loading/!transient-telemetry-aggregate)))
                         [:episerver-loading/document-prepared
                          :episerver-loading/document-upserted
                          :episerver-loading/search-phrases-cache-hit
                          :episerver-loading/search-phrases-cache-miss
                          :episerver-loading/upserting-document])))

     (dom/hr)
     (KVTable (e/server (:id-counts (e/watch episerver-loading/!transient-telemetry-aggregate))))

     (dom/div
      (dom/props {:style {:display         "flex"
                          :justify-content "flex-start"
                          :gap             "1rem"
                          :margin-bottom   "1rem"}})

      (let [start-job-button-disabled? (e/server (e/watch episerver-loading/!start-job-button-disabled?))]
        (ks/Button {:data-size :sm
                    :disabled  start-job-button-disabled?}
                   (e/fn []
                     (dom/text "Start EPiServer Loading Job")
                     (when-not start-job-button-disabled?
                       (let [[t err] (e/Token (dom/On "click" identity nil))]
                         (when t
                           (case (e/server (util/thread (episerver-loading/-main))
                                           nil)
                             (case (e/server (reset! episerver-loading/!start-job-button-disabled? true))
                               (t)))))))))

      (ks/Button {:data-size    :sm
                  :data-variant :secondary
                  :disabled     (e/server (not (e/watch episerver-loading/!job-canceller)))}
                 (e/fn []
                   (dom/text "Cancel Job")
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (e/server (episerver-loading/stop-job))
                         (t)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Import configuration"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (DocumentLoadingJobStatus episerver-loading/wview)))))))

     (ks/Card
      {:data-size :sm}
      (e/fn []
        (ks/Details
         {}
         (e/fn []
           (ks/DetailsSummary {} (e/fn [] (dom/b (dom/text "Log messages"))))
           (ks/DetailsContent {}
                              (e/fn []
                                (dom/div
                                 (dom/props {:style {:height     "300px"
                                                     :overflow-y :scroll}})

                                 (e/for [sig (e/diff-by identity sigs)]
                                   (dom/p
                                    (dom/text (str (:id sig) " " (:msg_ sig) " " (:inst sig)))
                                    (dom/b (dom/text " Chars: " (count (str sig))))))))))))))))
