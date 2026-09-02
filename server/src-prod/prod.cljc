(ns prod
  #?(:cljs (:require-macros [prod :refer [comptime-resource]]))
  (:require
   digdir.ui.main

   #?(:clj [digdir.api.http :as server])
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
       ;; Boot-time E2E auto-seed. No-op unless E2E_API_KEY is set,
       ;; so production starts unchanged.
       (e2e-seed/maybe-seed!)
       (server/start-server!
         (fn [ring-request] (digdir.ui.main/electric-boot ring-request))
         config))))

#?(:cljs ; client entrypoint
   (defn ^:export -main []
     ;; client-side electric process boot happens here
     ((digdir.ui.main/electric-boot nil)  ; boot client-side Electric process
      #(js/console.log "Reactor success:" %)
      #(js/console.error "Reactor failure:" %))))
