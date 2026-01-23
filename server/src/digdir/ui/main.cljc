(ns digdir.ui.main
  "Main entry point for the UI application."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.rcf :refer [tests tap %]]
            #?(:clj [taoensso.telemere :as t])
            #?(:clj [digdir.rag.typesense :as ts-utils])
            ;; Import UI modules
            [digdir.ui.components :refer [StatusBar] :refer-macros [routed-tabs]]
            [digdir.docs.ui :refer [KudosDocuments FolderLoading OptimizelyLoading WebsiteLoading]]
            [digdir.config.ui :refer [Config]]
            [digdir.playground.ui :refer [PlaygroundChat]]
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

;; =========Main Entry Point=========

(e/defn Main [ring-request]
  (e/client
   ;; Load i18n translations from server to client before loading the rest of the app.
   (case
    (i18n/load-translations-cljs! (e/server @i18n/translations))
     (binding [dom/node       js/document.body
               e/http-request (e/server ring-request)]
                                        ; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74
       (dom/div (dom/props {:style {:display "contents"}})
                (let [;; Authentication is now handled by middleware
                      ;; The user's email is available in the ring-request if needed
                      user-email  (e/server (:user/email ring-request))
                      ;; always use test instance if specified in config file
                      ts-settings (e/server (ts-utils/make-ts-settings {}))]
                  ;; Combined status bar with Typesense status and user info/logout
                  (StatusBar ts-settings user-email)
                  (routed-tabs :main 0

                   (dom/text (t :nav/chat))
                   (PlaygroundChat ts-settings)

                   (dom/text (t :nav/config))
                   (Config)

                   (dom/text (t :nav/import))
                   (routed-tabs :import 1
                    (dom/text (t :nav/kudos))
                    (KudosDocuments ts-settings)

                    (dom/text (t :nav/folder))
                    (FolderLoading ts-settings)

                    (dom/text (t :nav/optimizely))
                    (OptimizelyLoading ts-settings)

                    (dom/text (t :nav/website))
                    (WebsiteLoading ts-settings))

)))))))

(defn electric-boot [ring-request]
  #?(:clj  (e/boot-server {} Main (e/server ring-request))  ; inject server-only ring-request
     :cljs (e/boot-client {} Main (e/server (e/amb)))))     ; symmetric – same arity – no-value hole in place of server-only ring-request
