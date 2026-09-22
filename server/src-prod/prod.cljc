(ns prod
  #?(:cljs (:require-macros [prod :refer [comptime-resource]]))
  (:require
   digdir.ui.main

   #?(:clj [digdir.api.http :as server])
   #?(:clj [digdir.auth.core :as auth])
   #?(:clj [digdir.boot.phrase-cache :as phrase-cache])
   #?(:clj [digdir.boot.placeholder-secrets :as placeholder-secrets])
   #?(:clj [digdir.boot.provider-switch :as provider-switch])
   #?(:clj [digdir.boot.required-env :as required-env])
   #?(:clj [digdir.boot.agents :as boot-agents])
   #?(:clj [digdir.e2e.seed :as e2e-seed])
   ;; NOT digdir.skills.api. The two are different initialisers: api's
   ;; registers the builtin skills and graphs only (5 graphs), while
   ;; init/ensure-initialized! also runs the demo and promoted
   ;; registrations (10). Same distinction agents/db.clj documents.
   #?(:clj [digdir.skills.init :as skills-init])
   #?(:clj clojure.java.io)
   #?(:clj [clojure.tools.logging :as log])
   ))

(defmacro comptime-resource [filename] (some-> filename clojure.java.io/resource slurp clojure.edn/read-string))

#?(:clj ; server entrypoint
   (defn -main [& {:strs [] :as args}] ; clojure.main entrypoint, args are strings
     (let [config
           ;; Client and server versions must match in prod (dev is not concerned)
           ;; `src-build/build.clj` will compute the common version and store it in `resources/electric-manifest.edn`
           ;; On prod boot, `electric-manifest.edn`'s content is injected here.
           ;; Server is therefore aware of the program version.
           ;; The client's version is injected in the compiled .js file.
           (merge
             (comptime-resource "electric-manifest.edn")
             {:host "0.0.0.0", :port 8080,
              :resources-path "public"
              :index-path "public/admin_app/index.prod.html"
              ;; shadow-cljs build manifest path, to get the fingerprinted main.sha1.js file to ensure cache invalidation
              :manifest-path "public/admin_app/js/manifest.edn"})]
       (log/info (pr-str config))
       (assert (string? (:hyperfiddle/electric-user-version config)))
       ;; FIRST, before the skill registry, the seed and the server (#489).
       ;; A placeholder secret is not a degraded start, it is a start with
       ;; published credentials — so nothing else should have happened by the
       ;; time we refuse. Throws unless DIGDIR_ALLOW_PLACEHOLDER_SECRETS=true,
       ;; which logs every offending variable rather than passing quietly.
       (log/info (str "Placeholder-secret check: "
                      (pr-str (placeholder-secrets/check!))))
       ;; And the other half of the same promise (#521): the variables
       ;; declared :tier :boot must actually be present. Derived from that
       ;; declaration rather than re-listed here, so this covers a boot
       ;; variable added after this line was written.
       ;;
       ;; Ordered with the placeholder check and BEFORE the warm cache below,
       ;; on that block's own rule: refusals first, optimisations after. This
       ;; is a refusal.
       (log/info (str "Required boot environment: "
                      (pr-str (required-env/check!))))
       ;; Unpack the committed warm phrase cache, if the volume is empty
       ;; (yardarm-warmcache). After the secret check because it is an
       ;; optimisation and that is a refusal; before the seed and the server
       ;; because materialisation reads the directory it fills. Cannot fail the
       ;; boot: every path returns a map and is logged.
       (phrase-cache/warm!)
       ;; Initialise the skill system at boot, as dev.cljc does.
       ;;
       ;; Without this, production registered 5 skill graphs instead of 10:
       ;; nothing in the prod boot path called this, and the request path
       ;; reaches skills.api/initialize! — the smaller one — via
       ;; run-skill-graph. So :docs/enrich-one-chunk and the docs/* graphs
       ;; were on the classpath and correct, but absent from a live
       ;; registry, and the boot cross-check added in #71 never fired in
       ;; production at all. Measured, before and after, in the PR.
       ;;
       ;; Ordered before the E2E seed deliberately: seeding filters builtin
       ;; agents to the graphs that resolve, so it must see the full
       ;; registry or it silently seeds a smaller agent set.
       (skills-init/ensure-initialized!)
       ;; Confirmation-code fallback, OFF unless explicitly asked for (#436).
       ;;
       ;; Gate 2 of the first-run lockout: `deliver-confirmation-code!` has a
       ;; fallback that writes the code to the server log when no email service
       ;; is configured, but it was armed only by `dev.cljc`, and `src-dev` is
       ;; not in the uberjar (`build.clj` copies src, src-prod, resources). So
       ;; in a container the fallback existed and could never be reached: past
       ;; the missing-admin gate, login still had nowhere to send the code.
       ;;
       ;; ⚠️ THIS WIDENS SOMETHING REAL, so it is opt-in and loud. An armed
       ;; instance writes login codes to its own log, where anyone who can read
       ;; logs can use them. That is acceptable for a laptop stack with no mail
       ;; service and unacceptable on a deployment serving anyone else — hence
       ;; a variable an operator must set deliberately, never a default, and a
       ;; warning on every boot rather than a single line at arming time.
       ;;
       ;; Same shape as DIGDIR_ALLOW_PLACEHOLDER_SECRETS above: the unsafe
       ;; state is reachable, named, and announces itself.
       (when (= "true" (System/getenv "DIGDIR_LOG_CONFIRMATION_CODES"))
         (auth/set-dev-confirmation-code-logging! true)
         (log/warn (str "DIGDIR_LOG_CONFIRMATION_CODES=true — login confirmation codes "
                        "will be WRITTEN TO THIS LOG when no email service is configured. "
                        "Anyone who can read these logs can complete a login as any "
                        "permitted user. Intended for a local stack without mail; unset "
                        "it and configure Scaleway TEM for any deployment others reach.")))
       (boot-agents/seed!)
       ;; Boot-time E2E auto-seed. No-op unless E2E_API_KEY is set,
       ;; so production starts unchanged.
       (e2e-seed/maybe-seed!)
       ;; LAST, and deliberately not up with the other two refusals: this one
       ;; reads the CONFIG DATABASE rather than the environment, so it has to
       ;; run after everything that can write config. Refusing here still
       ;; refuses before the first request is served, which is what matters.
       (log/info (str "Provider switch check: "
                      (pr-str (provider-switch/check!))))
       (server/start-server!
         (fn [ring-request] (digdir.ui.main/electric-boot ring-request))
         config))))

#?(:cljs ; client entrypoint
   (defn ^:export -main []
     ;; client-side electric process boot happens here
     ((digdir.ui.main/electric-boot nil)  ; boot client-side Electric process
      #(js/console.log "Reactor success:" %)
      #(js/console.error "Reactor failure:" %))))
