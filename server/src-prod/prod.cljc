(ns prod
  #?(:cljs (:require-macros [prod :refer [comptime-resource]]))
  (:require
   digdir.ui.main

   #?(:clj [digdir.api.http :as server])
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
       (server/start-server!
         (fn [ring-request] (digdir.ui.main/electric-boot ring-request))
         config))))

#?(:cljs ; client entrypoint
   (defn ^:export -main []
     ;; client-side electric process boot happens here
     ((digdir.ui.main/electric-boot nil)  ; boot client-side Electric process
      #(js/console.log "Reactor success:" %)
      #(js/console.error "Reactor failure:" %))))
