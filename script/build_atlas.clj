#!/usr/bin/env bb
(ns build-atlas
  "Inline the per-module data files into the self-contained System Atlas page.

   WHY THE OUTPUT IS A SINGLE FILE. There are now TWO INDEPENDENT REASONS, and
   either one alone is enough to forbid splitting the CSS or JS into sidecars.

   1. A page opened over `file://` cannot `fetch()` its own data — browsers block
      it — so an explorer that loads its modules at runtime stops working on
      double-click, which is most of how this tool actually gets used.

   2. ⚠️ THE SHIPPED COPY IS SERVED FROM BEHIND ADMIN AUTH, AND THE AUTH BYPASS
      IS BY FILE EXTENSION. `digdir.api.http/wrap-admin-auth` matches
      `\\.(css|js|png|jpg|jpeg|gif|ico|svg)$` and hands those straight to the
      next handler WITH NO TOKEN CHECK, while any other path falls through to a
      redirect to /auth. So the `.html` is protected and a sidecar `.css` or
      `.js` WOULD BE PUBLICLY READABLE. Splitting this file would silently move
      the page's content outside the auth boundary.

   The data therefore lives in version-controlled EDN (diffable, reviewable) and
   is INLINED here, so the artifact stays openable with no server and stays
   entirely inside the auth boundary when served.

   TWO OUTPUTS, ONE BUILD. The page is written to `docs/architecture/` (the
   reviewable, `file://`-openable copy) and to `server/resources/public/` (the
   copy that ships — `server.Dockerfile` copies `server/resources`, and
   `build.clj` puts `resources` on the uberjar classpath, where
   `wrap-resource \"public\"` serves it). They are written by the same build from
   the same bytes so they cannot diverge through human action, and
   `atlas-test` asserts they are byte-identical so a partial rebuild or a
   hand-edit of either copy reads as a failure."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private arch-dir "docs/architecture")
(def ^:private modules-dir (str arch-dir "/modules"))
(def ^:private schemas-dir (str arch-dir "/schemas"))
(def ^:private template (str arch-dir "/system-explorer.template.html"))
(def ^:private output (str arch-dir "/system-explorer.html"))
;; The shipped copy. Same bytes, written by the same build — see the ns docstring.
(def ^:private shipped-output "server/resources/public/system-explorer.html")
(def ^:private marker "/*{{MODULES}}*/[]")

(defn display-order []
  (edn/read-string (slurp (str modules-dir "/order.edn"))))

(defn module-files []
  (->> (file-seq (io/file modules-dir))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (remove #(= "order.edn" (.getName %)))))

(defn load-modules
  "Modules in display order. Throws when `order.edn` and the files on disk
   disagree in EITHER direction — an unlisted module would silently vanish from
   the page, and a listed-but-absent id would silently shrink it."
  []
  (let [order (display-order)
        by-id (into {} (map (fn [f] [(str/replace (.getName f) #"\.edn$" "")
                                     (edn/read-string (slurp f))])
                            (module-files)))
        listed (set order)
        present (set (keys by-id))]
    (when (seq (remove listed present))
      (throw (ex-info (str "module file(s) not listed in order.edn: "
                           (str/join ", " (sort (remove listed present)))
                           " — add them to order.edn so their position is deliberate")
                      {:unlisted (sort (remove listed present))})))
    (when (seq (remove present listed))
      (throw (ex-info (str "order.edn lists id(s) with no module file: "
                           (str/join ", " (sort (remove present listed))))
                      {:missing (sort (remove present listed))})))
    (mapv by-id order)))

(defn attach-schema
  "Swap a module's authored malli `:contract` for the GENERATED JSON Schema.

   The page shows JSON Schema because that is the form a contract is argued and
   implemented in; malli stays the authoring and validation form. babashka
   cannot load malli, so the conversion cannot happen here — it is done by
   `bb atlas-schemas` on the JVM and read back off disk.

   A missing file THROWS rather than silently omitting the schema: a module that
   declares a contract and renders without one is the failure this whole slice
   exists to prevent — a page that looks complete and is not."
  [m]
  (if-not (:contract m)
    m
    (let [f (io/file schemas-dir (str (:id m) ".json"))]
      (when-not (.exists f)
        (throw (ex-info (str "module " (pr-str (:id m)) " carries a :contract but "
                             (.getPath f) " does not exist — run `bb atlas-schemas`")
                        {:module (:id m) :expected (.getPath f)})))
      (-> m
          (dissoc :contract)
          (assoc :schema (json/parse-string (slurp f) true))))))

(defn build []
  (let [tpl (slurp template)
        _ (when-not (str/includes? tpl marker)
            (throw (ex-info (str "template has no " marker " marker — nothing would be inlined")
                            {:template template})))
        modules (mapv attach-schema (load-modules))
        js (json/generate-string modules)
        out (str/replace-first tpl marker js)]
    (spit output out)
    (io/make-parents shipped-output)
    (spit shipped-output out)
    {:modules (count modules)
     :files (reduce + (map (comp count :files) modules))
     :edges (reduce + (map (comp count :edges) modules))
     :changing (count (for [m modules e (:edges m)
                           :when (not= (:kind e) (:target e))] e))
     :schemas (count (filter :schema modules))
     :bytes (count (.getBytes ^String out "UTF-8"))
     :outputs [output shipped-output]}))

(defn -main [& _]
  (let [{:keys [modules files edges changing schemas bytes]} (build)]
    (println (format "System Atlas built: %s modules, %s file references, %s edges (%s changing type), %s schemas, %s bytes"
                     modules files edges changing schemas bytes))
    (println (format "  -> %s (reviewable, file://-openable)" output))
    (println (format "  -> %s (shipped in the image, served behind admin auth)" shipped-output))))

;; Called unconditionally so the two invocation paths behave identically:
;; `bb script/build_atlas.clj` and the `bb atlas-build` task, which load-files
;; this. A `*file*` guard silently does nothing under load-file — the task ran
;; and printed nothing, which is the failure mode this whole codebase keeps
;; finding: a no-op that looks like a success.
(-main)
