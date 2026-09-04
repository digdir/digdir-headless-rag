(ns dev
  (:require
   digdir.ui.main

   #?(:clj [shadow.cljs.devtools.api :as shadow-cljs-compiler])
   #?(:clj [shadow.cljs.devtools.server :as shadow-cljs-compiler-server])
   #?(:clj [clojure.tools.logging :as log])

   #?(:clj [clojure.edn :as edn])
   #?(:clj [clojure.java.io :as io])
   #?(:clj [clojure.string :as str])
   #?(:clj [digdir.api.http :as server])
   #?(:clj [digdir.auth.core :as auth])
   #?(:clj [digdir.boot.placeholder-secrets :as placeholder-secrets])
   #?(:clj [digdir.boot.required-env :as required-env])
   #?(:clj [digdir.e2e.seed :as e2e-seed])
   #?(:clj [digdir.skills.init :as skills-init])
   ;; Side-effect requires: each ns invokes its register! to put the
   ;; demo skills and skill graphs into the templates registry on load.
   #?(:clj [digdir.demo.altinn-authoring])
   #?(:clj [digdir.demo.altinn-release-notes])
   #?(:clj [digdir.demo.altinn-translation-drift])
   #?(:clj [clojure.repl.deps :refer [sync-deps]])
   ))

#?(:clj
   (defn- increase-shadow-cache-string-limit!
     []
     (com.fasterxml.jackson.core.StreamReadConstraints/overrideDefaultStreamReadConstraints
      (.. com.fasterxml.jackson.core.StreamReadConstraints
          builder
          (maxStringLength 50000000)
          build))))

#?(:clj
   ;; Platform-wrapped so the :cljs lint pass doesn't see :clj-only vars.
   (comment (-main) ; repl entrypoint
            (sync-deps) ; install deps from deps.edn
            ))

#?(:clj
   (def ^:private client-manifest-path "public/admin_app/js/manifest.edn"))

#?(:clj
   (defn- client-bundle-kind
     "Which build produced the current bundle: `:watch`, `:release`, or nil when
      there is none. Both builds write the SAME output directory and the same
      manifest, so only one can be current — run `bb dev-fullstack` once and the
      watch bundle becomes what `bb dev` would serve.

      The discriminator is the shadow devtools client in the module entries,
      which the watch build injects and the release build does not. A watch
      bundle served without a running watch opens a websocket that nothing
      answers, so backend-only mode names the case instead of serving it
      silently."
     []
     (when-let [manifest (io/resource client-manifest-path)]
       (let [entries (->> (edn/read-string (slurp manifest))
                          (mapcat :entries)
                          (map str)
                          set)]
         (if (some #(str/starts-with? % "shadow.cljs.devtools.client") entries)
           :watch
           :release)))))

#?(:clj
   (defn- start-http!
     "Bind Jetty. `index-path` decides which client the page boots:
      `index.prod.html` injects the fingerprinted bundle name FROM the manifest,
      `index.dev.html` hardcodes `main.js`, which only the watch build emits.
      Measured: docs/investigations/330-dev-client-decoupling-measurement.md.

      `jetty-server` is deliberately `def`d in here: it is the REPL affordance
      that makes `(.stop jetty-server)` work from the comment block at the
      bottom of this file, and both entrypoints go through this one function."
     [{:keys [index-path missing-client-message]}]
     (def jetty-server
       (server/start-server!
         (fn [ring-request] (digdir.ui.main/electric-boot ring-request))
         {:host "0.0.0.0"
          ;; HTTP_PORT lets sibling worktrees run `bb dev` in parallel
          ;; without colliding on 8081. Defaults to 8081 to preserve the
          ;; single-worktree case.
          :port (or (some-> (System/getenv "HTTP_PORT") Integer/parseInt) 8081)
          :resources-path "public"
          :index-path index-path
          :manifest-path client-manifest-path
          :missing-client-message missing-client-message}))
     jetty-server))

#?(:clj
   (defn- init-backend!
     "Everything both modes need, in one place so the two entrypoints cannot
      drift apart."
     []
     ;; FIRST, and in dev too (#489). A check that only runs in production is
     ;; never exercised by the people who would notice it misbehaving, and a
     ;; placeholder secret on a dev box is the same published credential it is
     ;; anywhere else. DIGDIR_ALLOW_PLACEHOLDER_SECRETS=true boots anyway and
     ;; says so; that is the supported way to keep a placeholder locally.
     (log/info (str "Placeholder-secret check: "
                    (pr-str (placeholder-secrets/check!))))

     ;; And the other half of the same promise (#521). `:tier :boot` in
     ;; `digdir.config.env-bridge` says the server does not start without
     ;; these; until now nothing derived a presence check from that, so the
     ;; sentence was true only by luck of each consumer. Derived from the
     ;; table, so it covers a boot variable added after this line was written.
     ;; DIGDIR_ALLOW_MISSING_BOOT_ENV=true boots anyway and says so.
     (log/info (str "Required boot environment: "
                    (pr-str (required-env/check!))))

     (increase-shadow-cache-string-limit!)

     ;; Initialize the skills system
     (log/info "Initializing skills system...")
     (let [result (skills-init/initialize!)]
       (log/info (str "Skills initialized: " (:skills-registered result) " skills, "
                      (:skill-graphs-registered result) " skill graphs")))

     ;; Skill-graph registration has exactly one home: `skills-init/initialize!`
     ;; above. That includes the src-dev-only self-improve graphs, which it
     ;; registers through `digdir.agents.dev` — where `builtin/docs-agent` and
     ;; the requires that satisfy it are deliberately co-located (issue #71) —
     ;; and the self-improve-agent tools, which it requires directly. This file
     ;; used to re-require them here as well; that second path is gone on
     ;; purpose, so a registration problem has one place to look.

     ;; Boot-time E2E auto-seed (mirrors prod.cljc -main). No-op unless
     ;; E2E_API_KEY is set, so a plain `bb dev` is unchanged.
     (e2e-seed/maybe-seed!)

     ;; Dev-only: when no email service is configured, admin-login
     ;; confirmation codes go to this log instead of throwing, so first
     ;; login works without Scaleway credentials. Armed here and only
     ;; here — src-dev is not on a production build's classpath.
     (auth/set-dev-confirmation-code-logging! true)
     (log/info "Dev login fallback armed: confirmation codes will be logged if no email service is configured.")))

;; ---------------------------------------------------------------------------
;; Two entrypoints (#330).
;;
;; The Electric client build needs the Hyperfiddle activation token, which lives
;; INSIDE the shadow build cache and cannot be made persistent — only
;; re-established (see `-ensure-electric-token` in bb.edn). A newcomer has none,
;; which made the client build the gate on doing ANY backend work.
;;
;; Measured before this split (docs/investigations/330-...-measurement.md):
;;   - the token gates the :dev WATCH build only; the :prod release build has no
;;     Electric build-hook and ran token-less in 99s in a worktree with no token
;;     slot at all
;;   - the API serves fine with no client build present
;;   - a prebuilt client IS servable with no watch running — through
;;     index.prod.html, which injects the hashed name from the manifest
;; ---------------------------------------------------------------------------

#?(:clj ; server entrypoint — BACKEND ONLY (`bb dev`)
   (defn -main [& _args]
     (log/info "▶ bb dev — BACKEND ONLY: server + REPL, no Electric client build, no activation token needed.")
     (log/info "  For UI work with hot reload, run `bb dev-fullstack` instead.")
     (init-backend!)

     ;; The shadow SERVER, not the watch. It is what provides the nREPL that
     ;; `bb dev` has always given you, and starting it needs no token — the
     ;; activation gate fires later, at `[:dev] Compiling`, which is exactly the
     ;; step this mode skips. Verified in the measurement above.
     (shadow-cljs-compiler-server/start!)

     (start-http!
       {:index-path "public/admin_app/index.prod.html"
        :missing-client-message
        (str "No client build present, so the admin UI cannot be served.\n"
             "The API is fully functional — this affects the UI only.\n\n"
             "  bb build-client    build it once (~100s, no activation token needed)\n"
             "  bb dev-fullstack   run the coupled server + client watch instead")})

     (case (client-bundle-kind)
       :release
       (log/info "Serving the prebuilt client from resources/public/admin_app/js.")

       :watch
       (log/warn (str "The client bundle in resources/public/admin_app/js was produced by the "
                      "WATCH build (`bb dev-fullstack`), not `bb build-client`. It carries the "
                      "shadow devtools client and expects a watch process that this mode does "
                      "not run, so hot reload will fail to connect. Run `bb build-client` for a "
                      "standalone bundle. Both builds write the same directory, so the last one "
                      "to run wins."))

       (log/warn (str "No client build found (" client-manifest-path " is absent). "
                      "The API is fully functional; the admin UI will answer "
                      ":digdir.api.http/missing-shadow-build-manifest with instructions "
                      "until you run `bb build-client`.")))

     ;; Nothing below blocks in this mode — the watch is what used to hold the
     ;; process open — so hold it here explicitly.
     @(promise)))

#?(:clj ; server entrypoint — FULLSTACK (`bb dev-fullstack`), unchanged behaviour
   (defn -main-fullstack [& _args]
     (log/info "▶ bb dev-fullstack — server + Electric client watch build.")
     (log/info "  The client build needs the Hyperfiddle activation token; a new worktree may prompt you to activate.")
     (log/info "  For backend-only work with no token, run `bb dev` instead.")
     (init-backend!)

     (shadow-cljs-compiler-server/start!)

     ;; Bind jetty BEFORE the client build (#165).
     ;;
     ;; `(shadow-cljs-compiler/watch :dev)` does not return while the Electric
     ;; compile is gated on the Hyperfiddle activation login, so starting jetty
     ;; after it meant the API never started at all on a clean checkout: healthy
     ;; startup lines, then nothing listening, with no error to search for.
     ;; Verified in a clean clone outside the worktree tree — cold
     ;; .shadow-cljs, no inherited config — where the API never bound within
     ;; 240s before this reordering and bound in ~50s after it, answering
     ;; /up with 200 while the client build was still gated.
     ;;
     ;; The API does not depend on the CLJS bundle, so it has no reason to wait
     ;; for it. Serving the API while the client compiles is strictly better
     ;; than serving nothing, and it decouples the API surface — what
     ;; integrators actually use — from the UI build entirely.
     ;; index.dev.html, because this mode runs the watch build, whose output is
     ;; the unfingerprinted `main.js` that this index hardcodes.
     (start-http!
       {:index-path "public/admin_app/index.dev.html"
        :missing-client-message
        (str "The client watch build has not emitted a bundle yet.\n"
             "If it is still compiling, reload in a moment. If it is waiting on\n"
             "Hyperfiddle activation, this terminal is showing you the login URL.\n\n"
             "  bb dev    backend-only, needs no activation token")})

     ;; Blocking, and deliberately last: everything above is already serving.
     (shadow-cljs-compiler/watch :dev)))

(declare browser-process)
#?(:cljs ; client entrypoint
   (defn ^:dev/after-load ^:export -main []
     (set! browser-process
       ((digdir.ui.main/electric-boot nil) ; boot client-side Electric process
        #(js/console.log "Reactor success:" %)
        #(js/console.error "Reactor failure:" %)))))

#?(:cljs
   (defn ^:dev/before-load stop! [] ; for hot code reload at dev time
     (when browser-process (browser-process)) ; tear down electric browser process
     (set! browser-process nil)))

#?(:clj
   ;; Platform-wrapped so the :cljs lint pass doesn't see :clj-only vars.
   (comment
     (shadow-cljs-compiler-server/stop!)
     (.stop jetty-server) ; stop jetty server
     ))
