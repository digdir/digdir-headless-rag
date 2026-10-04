(ns digdir.config.ui.console-guard-census-test
  "the STRUCTURAL pins of the
   console's server-side admin gate. Each census reads CODE (reader conditionals
   allowed, any alias accepted), so a name in a docstring or a comment is not a
   reference, and each has planted controls that must go red.

   - G7, ONE guard: the admin predicate (`is-admin?`) is called only inside
     `common/ensure-config-ui-admin!` (and the CLI setup, which is not the
     console).
   - G3, the ACTOR: every Electric call of a gated helper passes an actor read
     from `e/http-request` inside the SAME `e/server` form, never a value from
     client scope.
   - the master key is never an `e/server` form's RESULT, that is, it is
     never bound in client scope.
   - the deleted, uncalled Group B handlers and modals are referenced
     nowhere, by any door."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- code-forms
  "Every top-level form of the Clojure source `text`, read as CODE."
  [text]
  (binding [*read-eval* false
            ;; a tagged literal is read as data, never handed to its reader fn
            *data-readers* {}
            *default-data-reader-fn* (fn [tag v] (list 'tagged-literal tag v))
            *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                (currentNS [_] 'user)
                                (resolveClass [_ s] s)
                                (resolveAlias [_ s] s)
                                (resolveVar [_ s] s))]
    (with-open [r (java.io.PushbackReader. (java.io.StringReader. text))]
      (doall (take-while #(not= ::eof %) (repeatedly #(read {:read-cond :allow :eof ::eof} r)))))))

(def ^:private roots ["src" "src-dev" "src-prod"])

(defn- source-files []
  (for [root roots
        f (file-seq (io/file root))
        :when (re-find #"\.clj[cs]?$" (str f))]
    f))

(defn- named? [x nm] (and (symbol? x) (= nm (name x))))

(def ^:private def-heads #{"def" "defn" "defn-" "defmacro" "defonce"})

(defn- top-level-name [ns-sym form]
  (let [head (when (seq? form) (first form))]
    (symbol (str ns-sym)
            (str (if (and (symbol? head) (def-heads (name head)) (symbol? (second form)))
                   (second form)
                   (str "<" head ">"))))))

(defn- forms-by-site
  "[site-name form] for each top-level form of `text` (a `(do ...)` wrapper, as
   in `#?(:clj (do (defn ...) ...))`, is opened)."
  [text]
  (let [forms (code-forms text)
        ns-sym (or (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms) 'unknown-ns)]
    (for [form forms
          f (if (and (seq? form) (= 'do (first form))) (rest form) [form])
          :when (not (and (seq? f) (= 'ns (first f))))]
      [(top-level-name ns-sym f) f])))

;; =============================================================================
;; G7: ONE admin predicate call
;; =============================================================================

(defn- predicate-callers
  "Sites whose code names the admin predicate `is-admin?` (any alias), other
   than its own definition."
  [text]
  (set (for [[site form] (forms-by-site text)
             :when (some #(named? % "is-admin?") (tree-seq coll? seq form))
             :when (not= site 'digdir.config.permissions/is-admin?)]
         site)))

(def ^:private allowed-predicate-callers
  {'digdir.config.ui.common/ensure-config-ui-admin! "THE one guard"
   'digdir.setup.workflow/add-admin-user-interactive! "the interactive CLI setup, not the console: it reports whether an existing user is already an admin"})

(deftest g7-the-admin-predicate-is-called-only-by-the-one-guard
  (testing "CONTROL: an inline predicate is found; a docstring or comment is not"
    (is (= #{'x/inline} (predicate-callers "(ns x) (defn inline [db u] (when-not (perms/is-admin? db u) (throw (ex-info \"no\" {}))))")))
    (is (= #{'x/display} (predicate-callers "(ns x) #?(:clj (do (defn display [db u] (boolean (is-admin? db u))))) ")))
    (is (= #{} (predicate-callers "(ns x) (defn doc \"calls is-admin?\" [] nil) ; perms/is-admin?"))))
  (let [sites (apply set/union (map #(predicate-callers (slurp %)) (source-files)))]
    (is (< 250 (count (source-files))) "PREMISE: the census reads the source roots")
    (is (= (set (keys allowed-predicate-callers)) sites)
        "a second admin predicate call: route it through common/ensure-config-ui-admin! (or config-ui-admin? for display)")))

;; =============================================================================
;; G3: every Electric call of a gated helper reads its actor server-side
;; =============================================================================

(def ^:private gated-helpers
  "helper name -> the index of its ACTOR argument (0-based, after the head), or
   :user-id-key for helpers that take the actor as `:user-id` in a map."
  {"panel-read" 1 "panel-create-key!" 1 "panel-revoke-key!" 1
   "panel-replace-allowed-config-keys!" 1 "set-all-tenants-as-admin!" 1
   "config-ui-admin?" 1
   "mutate-config-tree!" :user-id-key
   "export-preview" 2 "do-export!" 3 "import-preview" 2 "do-import!" 3
   "preview-clone-tenant" 3 "do-clone-tenant!" 3
   "preview-tenant-retirement" 2 "do-retire-source-tenants!" 3
   "do-bootstrap-deployment-target-topology!" 1
   "create-dataset!" 0 "update-dataset!" 0 "create-pipeline!" 0 "update-pipeline!" 0
   "delete-pipeline!" 0 "execute-pipeline!" 0 "stop-pipeline-execution!" 0
   "grant-permission-to-user!" 2 "revoke-permission-from-user!" 2 "create-user-and-grant!" 2
   ;; Digdir #11's agents panel
   "reseed-agent!" 0 "save-agent!" 0 "delete-agent!" 0 "reseed-all-agents!" 0})

(def ^:private request-actor '(:user/id e/http-request))

(defn- request-actor? [x actor-syms]
  (or (= request-actor x) (and (symbol? x) (contains? actor-syms x))))

(defn- let-actor-syms [bindings]
  (set (for [[sym v] (partition 2 bindings) :when (and (symbol? sym) (= request-actor v))] sym)))

(defn- actor-violations
  "Each gated-helper call inside an `e/server` form whose actor is not read from
   `e/http-request` in that form: [site helper actor-form]."
  [text]
  (let [out (atom []) seen (atom 0)]
    (letfn [(walk [site x in-server? actor-syms]
              (cond
                (seq? x)
                (let [head (first x)
                      hname (when (symbol? head) (name head))]
                  (cond
                    (= "server" hname) (doseq [c (rest x)] (walk site c true #{}))
                    (= "client" hname) (doseq [c (rest x)] (walk site c false #{}))
                    (and in-server? (named? head "let") (vector? (second x)))
                    (let [syms (into actor-syms (let-actor-syms (second x)))]
                      (doseq [[_ v] (partition 2 (second x))] (walk site v in-server? actor-syms))
                      (doseq [c (drop 2 x)] (walk site c in-server? syms)))
                    :else
                    (do
                      (when (and in-server? hname (contains? gated-helpers hname))
                        (swap! seen inc)
                        (let [pos (gated-helpers hname)
                              actors (if (= :user-id-key pos)
                                       ;; `{:user-id a}` or `(assoc m :user-id a)`, at any depth
                                       (for [m (tree-seq coll? seq (rest x))
                                             v (cond
                                                 (map? m) (when (contains? m :user-id) [(:user-id m)])
                                                 (seq? m) (keep (fn [[k v]] (when (= :user-id k) v))
                                                                       (partition 2 1 m))
                                                 :else nil)]
                                         v)
                                       [(nth (vec (rest x)) pos ::missing)])]
                          (when (or (empty? actors) (not-every? #(request-actor? % actor-syms) actors))
                            (swap! out conj [site hname (vec actors)]))))
                      (doseq [c (rest x)] (walk site c in-server? actor-syms)))))
                (coll? x) (doseq [c x] (walk site c in-server? actor-syms))))]
      (doseq [[site form] (forms-by-site text)] (walk site form false #{})))
    {:violations @out :calls @seen}))

(deftest g3-every-gated-call-reads-its-actor-from-the-request-in-the-same-server-form
  (testing "CONTROL: a client-scope actor is refused, a server-read actor is accepted"
    (is (= [['x/C "do-export!" ['uid]]]
           (:violations (actor-violations "(ns x) (e/defn C [user-id] (e/server (let [uid (e/client user-id)] (e/Offload #(do-export! t i p uid)))))"))))
    (is (= [['x/C "mutate-config-tree!" ['user-id]]]
           (:violations (actor-violations "(ns x) (e/defn C [user-id m] (e/server (common/mutate-config-tree! (merge m {:user-id user-id}))))"))))
    (is (= [] (:violations (actor-violations "(ns x) (e/defn C [] (e/server (let [actor (:user/id e/http-request)] (e/Offload #(do-export! t i p actor)))))"))))
    (is (= [] (:violations (actor-violations "(ns x) (e/defn C [] (e/server (panel-read db (:user/id e/http-request) f)))")))))
  (let [results (map #(actor-violations (slurp %)) (filter #(str/ends-with? (str %) ".cljc") (source-files)))]
    (is (<= 38 (reduce + (map :calls results)))
        "PREMISE: the census reaches the gated call sites (38, measured when it was written)")
    (is (= [] (vec (mapcat :violations results)))
        "a gated helper called from Electric with an actor that is not read from e/http-request in the same e/server form")))

;; =============================================================================
;; the master key is never an e/server form's result
;; =============================================================================

(defn- master-key-results
  "Sites with an `(e/server …)` form whose RESULT is a `get-master-key` call:
   that value then lives in client scope."
  [text]
  (set (for [[site form] (forms-by-site text)
             x (tree-seq coll? seq form)
             :when (and (seq? x) (named? (first x) "server"))
             :let [result (last x)]
             :when (and (seq? result) (named? (first result) "get-master-key"))]
         site)))

(deftest the-master-key-is-never-bound-in-client-scope
  (testing "CONTROL"
    (is (= #{'x/P} (master-key-results "(ns x) (e/defn P [] (let [mk (e/server (common/get-master-key))] (e/server (f mk))))")))
    (is (= #{} (master-key-results "(ns x) (e/defn P [v d] (e/server (common/decode-node-value v d (common/get-master-key))))"))))
  (is (= #{} (apply set/union (map #(master-key-results (slurp %)) (source-files))))))

;; =============================================================================
;; the deleted Group B handlers and modals are referenced by NO door
;; =============================================================================

(def ^:private deleted-names
  ["create-dataset-handler!" "create-pipeline-handler!" "create-tenant-handler!"
   "duplicate-pipeline-handler!" "soft-delete-pipeline-handler!"
   "NewTenantModal" "NewPipelineModal" "DuplicatePipelineModal"])

(defn- munged [nm] (-> nm (str/replace "-" "_") (str/replace "!" "_BANG_")))

(defn- mentions
  "Every mention of `nm` in `text`, as text: a symbol, a var-quote, a quoted
   symbol for resolve/ns-resolve/requiring-resolve, a string, an Electric call,
   a route-table entry, or the munged JS name. The widest needle: it counts
   docstrings and comments too, so a hit is DOUBT, never proof of absence."
  [text nm]
  (+ (count (re-seq (re-pattern (str "(?<![\\w!?*-])" (java.util.regex.Pattern/quote nm) "(?![\\w!?*-])")) text))
     (count (re-seq (re-pattern (java.util.regex.Pattern/quote (munged nm))) text))))

(def ^:private skipped-dirs
  "Generated or vendored trees, not source: the compiled client bundle and the
   linter's cache still carry names from before a deletion."
  #{".git" "node_modules" ".cpcache" ".shadow-cljs" "target" ".cache" ".lsp" "dumps"})

(defn- repo-text-files
  "Every text file of the repository (run from server/, so its parent), walked on
   disk rather than listed by git: a working-tree copy has no .git."
  []
  (letfn [(walk [^java.io.File d]
            (lazy-seq
             (mapcat (fn [^java.io.File f]
                       (cond
                         (.isDirectory f) (when-not (or (skipped-dirs (.getName f))
                                                        (str/includes? (str f) "resources/public/admin_app/js"))
                                            (walk f))
                         (re-find #"\.(png|jpg|jpeg|gif|ico|woff2?|ttf|pdf|jar|zip|gz|db|ldb|log)$" (.getName f)) nil
                         (< 5000000 (.length f)) nil
                         (str/ends-with? (.getName f) "console_guard_census_test.clj") nil
                         :else [f]))
                     (sort (.listFiles d)))))]
    (walk (io/file ".."))))

(deftest the-deleted-handlers-are-referenced-by-no-door
  (testing "CONTROL: every door's form of a reference is found"
    (doseq [plant ["(create-tenant-handler! t n u)"
                   "(#'common/create-tenant-handler! t n u)"
                   "(resolve 'digdir.config.ui/create-tenant-handler!)"
                   "(ns-resolve 'digdir.config.ui.common 'create-tenant-handler!)"
                   "{:handler create-tenant-handler!}"
                   "(e/server (create-tenant-handler! t n (:user/id e/http-request)))"
                   "\"create-tenant-handler!\""
                   "digdir.config.ui.create_tenant_handler_BANG_.call(null)"]]
      (is (pos? (mentions plant "create-tenant-handler!")) plant))
    (is (zero? (mentions "(create-tenant-handler-v2! x)" "create-tenant-handler!"))
        "a longer name is not a mention"))
  (let [files (repo-text-files)
        hits (for [f files nm deleted-names
                   :let [n (mentions (slurp f) nm)] :when (pos? n)]
               [(str f) nm n])]
    (is (< 1000 (count files)) "PREMISE: the census reads the whole repository")
    (is (some #(str/ends-with? (str %) "server/src/digdir/config/ui/common.cljc") files)
        "PREMISE: the census reads the file the handlers lived in")
    (is (= [] (vec hits)) "a deleted handler or modal is still referenced")))
