(ns digdir.ui.main
  "Main entry point for the UI application."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests tap %]]
            #?(:clj [taoensso.telemere :as t])
            #?(:clj [digdir.rag.typesense :as ts-utils])
            ;; Import UI modules
            [digdir.ui.components :refer [StatusBar] :refer-macros [routed-tabs]]
            [digdir.docs.ui :refer [KudosDocuments FolderLoading OptimizelyLoading WebsiteLoading]]
            [digdir.config.ui :refer [Config]]
            [digdir.pipeline.ui.pipelines :refer [Pipelines]]
            [digdir.playground.ui :refer [PlaygroundChat]]
            [digdir.sweep.dashboard :refer [Sweep-dashboard]]
            [digdir.i18n :refer [t] :as i18n]))

;; =========Config=========
(hyperfiddle.rcf/enable!)

#?(:clj
   (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (t/error! {:data {:thread-name (.getName thread)}
                   :id :uncaught-exception}
                  ex)))))

(defn normalize-debug-ui-mode
  "Limit temporary UI isolation mode values to a known set."
  [mode]
  (let [mode (some-> mode str/trim str/lower-case)]
    (if (#{"bare" "shell" "playground" "config" "import" "full"} mode)
      mode
      "full")))

(e/defn DebugUiNotice [debug-ui]
  (e/client
   (when (not= debug-ui "full")
     (dom/div
      (dom/props {:style {:padding "0.75rem 1rem"
                          :background "#fff7ed"
                          :border-bottom "1px solid #fdba74"
                          :color "#9a3412"
                          :font-size "0.875rem"
                          :font-weight "600"}})
      (dom/text (str "UI isolation mode: " debug-ui))))))

;; =========Main Entry Point=========

(e/defn Main [ring-request]
  (e/client
   (binding [dom/node       js/document.body
             e/http-request (e/server ring-request)]
     ;; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74
     (dom/div
      (dom/props {:style {:display "contents"}})
      (let [;; Authentication is now handled by middleware
            ;; The user's email is available in the ring-request if needed
            user-email  (e/server (:user/email ring-request))
            user-language (e/server (:user/preferred-language ring-request))
            server-translations (e/server @i18n/translations)
            translations-loaded? (i18n/load-translations-cljs! server-translations)
            active-language (i18n/normalize-language user-language)
            _language-installed? (i18n/set-language-cljs! active-language)
            ;; always use test instance if specified in config file
            ts-settings (e/server (ts-utils/make-ts-settings {}))
            debug-ui    (e/server
                         (normalize-debug-ui-mode
                          (get-in ring-request [:query-params "debug-ui"])))]
        ;; Render only after both the translations and persisted user locale
        ;; have been installed. Language changes reload after their database
        ;; transaction is acknowledged, avoiding a whole-app reactive remount.
        (when translations-loaded?
          ;; Combined status bar with Typesense status and user info/logout
          (when (not= debug-ui "bare")
            (StatusBar ts-settings user-email user-language)
            (DebugUiNotice debug-ui))
          (case debug-ui
          "bare"
          (dom/div
           (dom/props {:style {:padding "1rem"}})
           (dom/text "Bare mode"))

          "shell"
          (dom/div
           (dom/props {:style {:padding "1rem"}})
           (dom/text "Shell-only mode"))

          "playground"
          (PlaygroundChat ts-settings)

          "config"
          (Config)

          "import"
          (routed-tabs :import 1
           (dom/text (t :nav/kudos))
           (KudosDocuments ts-settings)

           (dom/text (t :nav/folder))
           (FolderLoading ts-settings)

           (dom/text (t :nav/optimizely))
           (OptimizelyLoading ts-settings)

           (dom/text (t :nav/website))
           (WebsiteLoading ts-settings))

          (routed-tabs :main 0
           (dom/text (i18n/translate active-language :nav/chat))
           (PlaygroundChat ts-settings)

           (dom/text (i18n/translate active-language :nav/datasets))
           (Pipelines)

           (dom/text (i18n/translate active-language :nav/config))
           (Config)

           (dom/text (i18n/translate active-language :nav/import))
           (routed-tabs :import 1
            (dom/text (t :nav/kudos))
            (KudosDocuments ts-settings)

            (dom/text (t :nav/folder))
            (FolderLoading ts-settings)

            (dom/text (t :nav/optimizely))
            (OptimizelyLoading ts-settings)

            (dom/text (t :nav/website))
            (WebsiteLoading ts-settings))

           (dom/text (i18n/translate active-language :nav/sweeps))
           (Sweep-dashboard)))))))))

(defn electric-boot [ring-request]
  #?(:clj  (e/boot-server {} Main (e/server ring-request))  ; inject server-only ring-request
     :cljs (e/boot-client {} Main (e/server (e/amb)))))     ; symmetric – same arity – no-value hole in place of server-only ring-request
