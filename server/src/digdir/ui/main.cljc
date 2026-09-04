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

#?(:clj
   (defn admin-console-typesense-settings
     "Typesense settings for the admin console, or nil.

      ## Why this exists (#479, and why the console stopped loading)

      This call site used to pass no tenant at all, and
      `digdir.rag.typesense/make-ts-settings` resolved that through a
      library-level fallback list whose first entry was `digdir`. #476 removed
      that fallback — correctly, because the ingest path was silently borrowing
      another tenant's credentials — and #486 removed the `digdir` tenant it
      resolved to. After both, a tenant-less call throws, and because this value
      is computed inside the Electric session it took the WHOLE ADMIN CONSOLE
      down: websocket crash in the browser, dead process on the server.

      ## Where the tenant comes from, and what is deliberately NOT decided here

      The deployment tenant, `TENANT` — the same variable `mcp/tools` already
      reads for its scope fallback and the compose files already set. That is a
      statement about which tenant THIS DEPLOYMENT serves, not an answer to
      #479's actual design question, which is where a MULTI-tenant operator
      chooses the tenant they are looking at. That question stays open: this
      makes the console load for a single-tenant deployment and takes no
      position on the picker, the route, or the session.

      ## Why nil rather than a throw, and why that is not #476 again

      Returning nil is an ABSENCE, not a default. It never borrows another
      tenant's credentials — the thing #476 removed and the thing that made this
      fail silently. The failure is logged loudly with its cause, and the status
      bar shows Typesense as unreachable rather than the console failing to
      render at all.

      One unresolvable value must not take down every other panel. Config,
      import, and the pipeline views do not need Typesense, and before this they
      died with it — which is also what a fresh deployment hits before anything
      is seeded."
     ;; Two arities so the behaviour is testable. The 0-arity reads the
     ;; deployment environment; the 1-arity is the whole decision and takes the
     ;; tenant as a value, because `System/getenv` cannot be set from a test and
     ;; a rule nothing can exercise is the one that regresses.
     ([] (admin-console-typesense-settings
          (some-> (System/getenv "TENANT") str/trim not-empty)))
     ([tenant]
      (if (nil? tenant)
        (do (t/error! {:id :admin-ui/no-deployment-tenant
                       :data {:reason "TENANT is unset, so the admin console cannot resolve Typesense settings. Set TENANT to the tenant this deployment serves."}})
            nil)
        (try
          (ts-utils/make-ts-settings {:tenant tenant})
          (catch clojure.lang.ExceptionInfo e
            (t/error! {:id :admin-ui/typesense-settings-unresolved
                       :data (assoc (ex-data e) :tenant tenant)}
                      e)
            nil))))))

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
            ;; Resolved for the DEPLOYMENT TENANT, and nil rather than fatal when
            ;; it cannot be resolved — see `admin-console-typesense-settings`.
            ;; Passing `{}` here is what took the whole console down (#479).
            ts-settings (e/server (admin-console-typesense-settings))
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
