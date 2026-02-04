(ns dev
  (:require
   digdir.ui.main

   #?(:clj [shadow.cljs.devtools.api :as shadow-cljs-compiler])
   #?(:clj [shadow.cljs.devtools.server :as shadow-cljs-compiler-server])
   #?(:clj [clojure.tools.logging :as log])

   #?(:clj [digdir.api.http :as server])
   #?(:clj [digdir.pipeline.skills.init :as skills-init])
   #?(:clj [clojure.repl.deps :refer [sync-deps add-lib]])
   ))

(comment (-main) ; repl entrypoint
         (sync-deps) ; install deps from deps.edn
         )

#?(:clj ; server entrypoint
   (defn -main [& args]
     (log/info "Starting Electric compiler and server...")

     ;; Initialize the skills system
     (log/info "Initializing skills system...")
     (let [result (skills-init/initialize!)]
       (log/info (str "Skills initialized: " (:skills-registered result) " skills, "
                      (:templates-registered result) " templates")))

     (shadow-cljs-compiler-server/start!)
     (shadow-cljs-compiler/watch :dev)

     (def jetty-server
       (server/start-server!
         (fn [ring-request] (digdir.ui.main/electric-boot ring-request))
         {:host "0.0.0.0"
          :port 8081
          :resources-path "public"
          :index-path "public/admin_app/index.dev.html"
          :manifest-path "public/admin_app/js/manifest.edn"}))))

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

(comment
  (shadow-cljs-compiler-server/stop!)
  (.stop jetty-server) ; stop jetty server
  )