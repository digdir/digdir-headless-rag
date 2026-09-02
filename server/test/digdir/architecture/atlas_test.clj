(ns digdir.architecture.atlas-test
  "The System Atlas describes the codebase. This checks that what it describes
   still exists.

   Every module names the source files it covers. Those paths are the only part
   of the Atlas that can be mechanically falsified — prose about a module can go
   stale without any signal, but a path either resolves or it does not. So this
   is the one claim the document makes that a test can hold.

   IT PASSES TODAY — all 46 references resolve — which is exactly why it was
   sabotaged before being trusted: a guard never observed red is an assertion,
   not a test. Pointing one module at a non-existent path was confirmed to fail
   with that module's id and the offending path in the message.

   WHAT THIS DOES NOT CHECK, and #398 carries the rest: whether the files a
   module names are the RIGHT files, whether a module's description matches what
   its files do, and whether files exist that NO module claims — 152 of 198 under
   `server/src` are unclaimed today. Coverage is a separate guard and is not
   built here."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [digdir.architecture.schemas :as schemas]))

;; Tests run with `server/` as the working directory; the Atlas data and the
;; paths it names are both relative to the REPO ROOT, one level up.
(def ^:private repo-root "..")
(def ^:private modules-dir (io/file repo-root "docs/architecture/modules"))

(defn- module-files-on-disk []
  (->> (.listFiles modules-dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
       (remove #(= "order.edn" (.getName %)))
       sort))

(defn- modules []
  (mapv #(edn/read-string (slurp %)) (module-files-on-disk)))

(def ^:private expected-module-count 18)
(def ^:private expected-file-reference-count 46)

(deftest the-atlas-data-is-readable-and-the-expected-size
  ;; Pinned counts, so an extractor that silently stops finding modules reads as
  ;; a failure rather than as a clean run over nothing — every assertion below
  ;; is inside a doseq over these.
  (let [ms (modules)]
    (is (= expected-module-count (count ms))
        (str "module count changed — " (count ms) " module files under "
             modules-dir ". Update the pinned count in the same commit and say why."))
    (is (= expected-file-reference-count (reduce + (map (comp count :files) ms)))
        (str "file-reference count changed — "
             (reduce + (map (comp count :files) ms))
             " references across " (count ms) " modules."))
    (is (every? :id ms) "every module must carry an :id, or failures cannot name it")))

(deftest every-file-a-module-claims-exists
  (let [ms (modules)]
    (is (seq ms) "no module data found — the check below would be vacuous")
    (doseq [m ms
            path (:files m)]
      (is (.exists (io/file repo-root path))
          (str "module " (pr-str (:id m)) " claims " (pr-str path)
               ", which does not exist. Either the file moved and the module "
               "must be updated, or the module is describing something that is gone.")))))

(deftest the-display-order-and-the-module-files-agree
  ;; Both directions. An unlisted module would silently vanish from the built
  ;; page; a listed id with no file would silently shrink it. Neither shows up
  ;; as an error at build time unless something asserts it.
  (let [order (set (edn/read-string (slurp (io/file modules-dir "order.edn"))))
        present (set (map :id (modules)))]
    (is (= order present)
        (str "order.edn and the module files disagree.\n"
             "  listed but no file : " (pr-str (sort (remove present order))) "\n"
             "  file but not listed: " (pr-str (sort (remove order present)))))))

;; ---------------------------------------------------------------------------
;; #398 slice 2: typed edges, their evidence, and the generated artifacts.
;;
;; WHY EDGES ARE AUTHORED RATHER THAN DERIVED, since a reader will ask: the
;; TARGET state cannot be derived — it does not exist in the code yet. And
;; derivation of the CURRENT state was measured before this was built: of 39
;; module-to-module edges recoverable from namespace requires, 18 point at
;; config or data (true, and not migration candidates) and 8 are already HTTP,
;; so 67% either will not migrate or already have. The flagship edge — the API
;; surface reaching RAG — is not derivable at all, because the call is a
;; skill-graph id resolved by the graph runner at runtime and the namespaces
;; that would carry it are unclaimed.
;;
;; So the edges are hand-authored, and the point of THIS test is that a
;; hand-authored edge still has to be falsifiable: every edge carries either a
;; :witness (a real static require, checked below) or a :via naming the runtime
;; mechanism. A witness that stops resolving turns this red.

(def ^:private valid-kinds #{:call :http})
(def ^:private expected-edge-count 31)
(def ^:private expected-changing-count 7)
(def ^:private expected-witness-count 13)

(defn- all-edges []
  (for [m (modules) e (:edges m)] (assoc e :from (:id m))))

(deftest every-edge-is-well-formed-and-points-at-a-real-module
  (let [ms (modules)
        ids (set (map :id ms))
        es (all-edges)]
    (is (= expected-edge-count (count es))
        (str "authored edge count changed — " (count es)
             ". Update the pinned count in the same commit and say why."))
    (doseq [e es]
      (is (contains? ids (:to e))
          (str "edge " (:from e) " -> " (pr-str (:to e)) " points at no known module"))
      (is (contains? valid-kinds (:kind e))
          (str "edge " (:from e) " -> " (:to e) " has :kind " (pr-str (:kind e))
               ", not one of " (pr-str valid-kinds)))
      (is (contains? valid-kinds (:target e))
          (str "edge " (:from e) " -> " (:to e) " has :target " (pr-str (:target e))))
      (is (not (str/blank? (:label e)))
          (str "edge " (:from e) " -> " (:to e) " has no :label"))
      (is (or (:witness e) (not (str/blank? (:via e))))
          (str "edge " (:from e) " -> " (:to e)
               " has NEITHER a :witness nor a :via. Every edge must carry its "
               "evidence: a static require that supports it, or a named runtime "
               "mechanism. An edge with neither is an unfalsifiable claim.")))))

(deftest the-migration-set-is-the-pinned-size
  ;; The set where :kind differs from :target IS the migration plan and is the
  ;; artifact the tool exists to render. Pinned so it cannot drift silently.
  (let [changing (filter #(not= (:kind %) (:target %)) (all-edges))]
    (is (= expected-changing-count (count changing))
        (str "the migration set changed size — " (count changing) " edges now change type: "
             (pr-str (mapv (juxt :from :to) changing))))))

(deftest every-witness-actually-resolves
  ;; THE FALSIFIABLE PART. An authored edge claiming a static require must be
  ;; able to point at one. This is what stops the edge data from becoming the
  ;; hand-maintained surface that goes stale in silence.
  (let [witnessed (filter :witness (all-edges))]
    (is (= expected-witness-count (count witnessed))
        (str "witnessed-edge count changed — " (count witnessed)))
    (is (seq witnessed) "no witnessed edges — the check below would be vacuous")
    (doseq [e witnessed
            :let [{:keys [file requires]} (:witness e)
                  f (io/file repo-root file)]]
      (is (.exists f)
          (str "edge " (:from e) " -> " (:to e) " names witness file "
               (pr-str file) ", which does not exist"))
      (when (.exists f)
        (is (re-find (re-pattern (str "\\[" (java.util.regex.Pattern/quote (str requires)) "\\b"))
                     (slurp f))
            (str "edge " (:from e) " -> " (:to e) " claims " (pr-str file)
                 " requires " (pr-str requires) ", and it does not. Either the "
                 "code changed and the edge is now unsupported, or the witness "
                 "was wrong when it was written."))))))

(deftest the-generated-json-schemas-match-their-authored-contracts
  ;; The JSON is generated from malli on the JVM because babashka cannot load
  ;; malli. A generated artifact that is checked in can go stale; this is the
  ;; guard that says it has not.
  (let [with-contract (filter :contract (modules))]
    (is (seq with-contract) "no module carries a :contract — this check would be vacuous")
    (doseq [m with-contract
            :let [f (schemas/schema-file m)]]
      (is (.exists f)
          (str "module " (pr-str (:id m)) " has a :contract but no generated schema at "
               (.getPath f) " — run `bb atlas-schemas`"))
      (when (.exists f)
        (is (= (schemas/module-schema-json m) (slurp f))
            (str "the checked-in JSON Schema for " (pr-str (:id m))
                 " does not match its authored malli contract — run `bb atlas-schemas`"))))))

(deftest the-built-page-carries-the-current-module-data
  ;; The checked-in page is generated from the template plus the module data,
  ;; and nothing previously asserted the two agree — so a data edit with no
  ;; rebuild would ship a page that looks current and is not.
  (let [html (slurp (io/file repo-root "docs/architecture/system-explorer.html"))
        inlined (second (re-find #"const modules = (\[.*?\]);\n" html))
        parsed (json/parse-string inlined true)
        ms (modules)]
    (is (some? inlined) "no inlined module data found in the built page")
    ;; Compared as SETS: the page inlines modules in display order (order.edn),
    ;; while `modules` reads the files alphabetically. Ordering is order.edn's
    ;; job and is already asserted by the-display-order-and-the-module-files-agree.
    (is (= (set (map :id ms)) (set (map :id parsed)))
        "the built page's module ids differ from the module data — run `bb atlas-build`")
    (is (= (reduce + (map (comp count :edges) ms))
           (reduce + (map (comp count :edges) parsed)))
        "the built page's edge count differs from the module data — run `bb atlas-build`")
    (doseq [m (filter :contract ms)]
      (is (some? (:schema (first (filter #(= (:id m) (:id %)) parsed))))
          (str "module " (pr-str (:id m)) " declares a :contract but the built page "
               "carries no :schema for it — run `bb atlas-schemas` then `bb atlas-build`")))))

(deftest the-shipped-copy-is-byte-identical-to-the-reviewable-one
  ;; The page is written to two places by one build: docs/architecture (the
  ;; reviewable, file://-openable copy) and server/resources/public (the copy
  ;; that ships in the image and is served behind admin auth). One build writes
  ;; both from the same bytes, so they cannot diverge through human action — but
  ;; a partial rebuild, a hand-edit, or a merge resolving only one side would.
  ;;
  ;; TWO COPIES THAT CAN DRIFT IS THE FAILURE THIS PROJECT KEEPS FINDING, so the
  ;; identity is asserted rather than assumed.
  (let [reviewable (io/file repo-root "docs/architecture/system-explorer.html")
        shipped (io/file repo-root "server/resources/public/system-explorer.html")]
    (is (.exists reviewable) "the reviewable page is missing — run `bb atlas-build`")
    (is (.exists shipped)
        (str "the SHIPPED page is missing at " (.getPath shipped)
             " — run `bb atlas-build`. Without it the deployed image serves nothing "
             "at /system-explorer.html and the Admin UI link 404s."))
    (when (and (.exists reviewable) (.exists shipped))
      (is (= (slurp reviewable) (slurp shipped))
          "the shipped copy of the System Atlas differs from the reviewable one — run `bb atlas-build`"))))

(deftest the-shipped-atlas-is-not-in-the-static-auth-bypass
  ;; `digdir.api.http/wrap-admin-auth` lets a request through WITH NO TOKEN CHECK
  ;; when the URI ends in a static extension. Verified empirically against the
  ;; real middleware chain: an unauthenticated GET of /system-explorer.html
  ;; returns 302 -> /auth, while /admin_app/styles.css returns 200 with a file
  ;; body. Same directory, opposite outcome, decided entirely by extension.
  ;;
  ;; So the Atlas is protected ONLY because it is a .html. If `html` were ever
  ;; added to that list, or if this page's CSS/JS were split into sidecars, the
  ;; content would become publicly readable — silently, with no error anywhere.
  ;; This asserts the property the packaging depends on.
  ;; ⚠️ THIS EXTRACTION HAS ALREADY BEEN BROKEN ONCE, BY #406, AND THAT IS THE
  ;; GUARD WORKING. It used to match the regex inline in `wrap-admin-auth`
  ;; (`static-url? (re-find #"..."`). #406 extracted that regex to the
  ;; `static-asset-uri?` def so the auth bypass and the index-page fallback
  ;; could not drift apart — and this test went RED with "could not find the
  ;; pattern" rather than passing over a subject it could no longer see.
  ;;
  ;; That is the whole point of the `some?` assertion below: a guard whose
  ;; subject moves must fail loudly, not silently check nothing. It is anchored
  ;; on the def now, which is a better anchor because the pattern finally has
  ;; one named home instead of being an inline literal.
  (let [src (slurp (io/file repo-root "server/src/digdir/api/http.clj"))
        pattern (second (re-find #"(?s)static-asset-uri\?.*?#\"([^\"]+)\"" src))]
    (is (some? pattern)
        "could not find the static-extension bypass pattern in http.clj — if that
         middleware was restructured, this guard is no longer checking anything
         and must be rewritten rather than deleted")
    (when pattern
      (let [re (re-pattern pattern)]
        ;; Positive control: the bypass must actually match something, or the
        ;; assertion below would pass vacuously against a pattern matching nothing.
        (is (re-find re "/admin_app/styles.css")
            (str "the extension bypass " (pr-str pattern) " no longer matches a .css "
                 "path — this check cannot fail as written and must be rewritten"))
        (is (not (re-find re "/system-explorer.html"))
            (str "THE SHIPPED SYSTEM ATLAS WOULD NOW BE SERVED WITHOUT AUTHENTICATION. "
                 "The static-extension bypass in wrap-admin-auth is " (pr-str pattern)
                 " and it now matches /system-explorer.html. Either .html was added to "
                 "the bypass, or the pattern changed shape. The Atlas describes the "
                 "internal architecture and is not public."))))))
