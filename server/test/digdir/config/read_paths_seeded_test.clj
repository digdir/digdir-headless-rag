(ns digdir.config.read-paths-seeded-test
  "every config path the code READS is seeded on a fresh install, under
   the root it is read from.

   WHY THE GUARD READS SOURCE AND NO VALUE (measured at 8015ea0c):
   - A missing definition is mostly QUIET. Four of the five read shapes return
     a default or drop the path: the `-with-trace` functions, the root load's
     synthetic `:definition-not-found` trace, config.db's table readers (which
     filter a missing definition out before resolving), and a whole-root load.
     The loud one, `cfg/get`, sits lexically inside a catching `try` at 14
     of its 47 call sites. So asking the API would not make a missing path loud. The
     guard resolves each read SITE to (path, root) and checks the fresh
     catalogue directly.
   - A guard driven by the suite sees only what the suite reaches. In the
     instrumented suite the real skill bulk reader never ran (the test files
     that name it redefine it), and the table readers drop a missing path
     before any chokepoint could record it. So the guard counts every read
     site in source, reachable or not. Dead code that reads an unseeded path
     goes red: over-firing, the deliberate direction.

   HOW IT AVOIDS PASSING TRIVIALLY
   - Resolved, not matched: forms are parsed (rewrite-clj; comments,
     docstrings and `(comment ...)` are not code), aliases resolved per
     namespace, symbols resolved in their namespace and their VALUES read,
     tables read from their vars.
   - Forcing: every site is resolved, reads a config.db table, or is listed in
     `classified-sites`. An unclassified site is red, and so is a
     classification with no site. The read-function sets and config.db's
     path tables are forced the same way, so a new reader cannot go unseen.
   - Controls: planted needles (in-memory source, one per shape, each reading
     an unseeded path) must come back red through the same census and check,
     and named real sites must resolve to their known paths.

   WHAT THIS DOES NOT ESTABLISH
   - A read hidden behind a whole-root load plus a consumer's map lookup, e.g.
     `(get-in config [:client :ui :name])` on a load with no `:paths`. The load
     cannot miss; the consumer's key is outside any census of read sites. The
     skill-config consumers are the same shape: they read flat keys
     (`:retrieval-top-k`) from the projected table, and a key missing from the
     TABLE is a different defect (table completeness), not seeding.
   - Readers outside the repo (API clients of `client.*`).
   - Paths built by string concatenation, or a read function reached through
     `resolve` of a computed symbol. None found; a site the census sees but
     cannot resolve is forced into a classification, a site it cannot see is
     not.
   - That a var-driven classification (`:reads-from`) still names every var
     the site maps over: `:uses` pins that the site's def still references the
     var, not that it references no other.
   - That a `:generic` classification is TRUE. It is the one hand-typed
     judgement the guard trusts: forced to exist and stay current, never
     verified. Classifying a real read `:generic` silences the guard for that
     site."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.accessor]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.data.db :as db]
            [digdir.secrets :as secrets]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; =============================================================================
;; The read API: what reads, where its path is, and which root it reads
;; =============================================================================

(def ^:private accessor-read-fns
  "Every accessor function that reads config by path. `:path-from n` = the
   path is every argument from n on; `:path-at n` = argument n; `:opts-paths n`
   = the `:paths` of the opts map at n; `:table` = reads one of config.db's
   path tables, every one of which `config-db-path-tables` checks in full."
  {"get"                                       {:root :platform :path-from 1}
   "get-if-allowed"                            {:root :platform :path-from 2}
   "get-platform-value"                        {:root :platform :path-at 0}
   "get-platform-value-with-trace"             {:root :platform :path-at 0}
   "get-runtime-value"                         {:root :runtime :path-at 0}
   "get-runtime-value-with-trace"              {:root :runtime :path-at 0}
   "get-dataset-value"                         {:root :dataset :path-at 0}
   "get-dataset-value-with-trace"              {:root :dataset :path-at 0}
   "get-runtime-skill-config-v2"               {:root :runtime :table true}
   "get-runtime-skill-config-v2-with-trace"    {:root :runtime :table true}
   "get-dataset-pipeline-config-v2"            {:root :dataset :table true}
   "get-dataset-pipeline-config-v2-with-trace" {:root :dataset :table true}
   "load-runtime-config-v2"                    {:root :runtime :opts-paths 0}
   "load-runtime-config-v2-with-trace"         {:root :runtime :opts-paths 0}
   "load-dataset-config-v2"                    {:root :dataset :opts-paths 0}
   "load-dataset-config-v2-with-trace"         {:root :dataset :opts-paths 0}})

(def ^:private accessor-publics-that-do-not-read
  {"evaluate-access"               "a permission decision over grants; reads no config value"
   "resolve-dataset-runtime-node!" "resolves a node; reads no path"})

(def ^:private config-db-read-fns
  "The other door: config.db functions called directly with a path, bypassing
   the accessor. `get-definition` implies no root, so only membership is
   checked there."
  {"get-definition"                  {:path-at 1 :any-root true}
   "get-node-value"                  {:root-at 1 :path-at 4}
   "resolve-node-value"              {:root-at 1 :path-at 4}
   "resolve-node-value-with-trace"   {:root-at 1 :path-at 4}
   "resolve-node-values-batch"       {:root-at 1 :path-at 4}
   "resolve-global-value-with-trace" {:root-at 1 :path-at 2}})

(def ^:private config-db-path-fns-that-do-not-read
  {"set-node-value!"                 "a write; it refuses an undefined path itself"
   "delete-node-value!"              "a write"
   "ensure-definition-root!"         "registration: sets a definition's root"
   "make-config-id"                  "builds an id string; reads nothing"
   "make-node-value-id"              "builds an id string; reads nothing"
   "preview-node-parent-change"      "previews a write, over caller-supplied paths"
   "preview-node-value-reset-change" "previews a write, over a caller-supplied path"})

(def ^:private watched-libs '#{digdir.config.accessor digdir.config.db})

(def ^:private config-db-path-tables
  "Every var in config.db whose VALUE is a table of config paths: the root its
   paths are read under, or why it is not read. Forced against the
   namespace's vars by the shape of their values, not their names. Derived
   and reverse tables are checked too: same paths, and checking them is free."
  {'skill-property-to-path                          {:root :runtime}
   'skill-property-paths                            {:root :runtime}
   'path-to-skill-property                          {:root :runtime}
   'pipeline-property-to-path                       {:root :dataset}
   'pipeline-property-paths                         {:root :dataset}
   'path-to-pipeline-property                       {:root :dataset}
   'dataset-runtime-property-to-path                {:root :dataset}
   'dataset-runtime-property-paths                  {:root :dataset}
   'path-to-dataset-runtime-property                {:root :dataset}
   'env-migrated-paths                              {:not-read "the paths boot RETRACTS: moved to environment variables"}
   'legacy-dataset-pipeline-projection-definitions  {:not-read "definitions config.db upserts on demand: a registration table"}})

;; =============================================================================
;; The fresh catalogue: boot's own code on an empty store, no snapshot import
;; =============================================================================

(def ^:private fresh-catalogue
  "path -> root, as a fresh install holds them after boot. Built by
   `db/prepare-store!`, the function `init-db!` calls, so it cannot drift from
   boot. The environment is blanked: a newcomer's store, not this machine's."
  (delay
    (let [cfg {:store {:backend :mem :id (str "read-paths-seeded-" (random-uuid))}
               :schema-flexibility :read
               :keep-history? false}]
      (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (try
          (binding [secrets/*env-lookup* (constantly nil)]
            (db/prepare-store! conn))
          (into (sorted-map)
                (map (juxt :config-def/path :config-def/root))
                (config-db/get-all-definitions @conn))
          (finally
            (d/release conn)
            (d/delete-database cfg)))))))

;; =============================================================================
;; The census: parsed source, not text
;; =============================================================================

(def ^:private source-roots ["src" "src-prod" "src-dev"])

(def ^:private skipped-tags #{:whitespace :newline :comma :comment :uneval})

(defn- code-children [node] (remove #(skipped-tags (n/tag %)) (n/children node)))

(defn- sexpr-of [node] (try (n/sexpr node) (catch Exception _ ::unreadable)))

(defn- form-head
  "The head of a call form: a list, or `#(...)`, whose first child is its head."
  [node]
  (when (#{:list :fn} (n/tag node))
    (some-> (first (code-children node)) sexpr-of)))

(defn- dotted [ks] (str/join "." (map name ks)))

(defn- config-path? [s]
  (and (string? s) (boolean (re-matches #"[a-z][a-z0-9-]*(\.[a-z0-9?-]+)+" s))))

(defn- ns-info
  "This file's namespace, how it aliases libs, and what it `:refer`s from the
   watched libs."
  [tops]
  (when-let [ns-node (first (filter #(= 'ns (form-head %)) tops))]
    (let [specs (->> (tree-seq n/inner? code-children ns-node)
                     (filter #(= :vector (n/tag %)))
                     (map #(mapv sexpr-of (code-children %)))
                     (filter #(symbol? (first %))))]
      {:ns (sexpr-of (second (code-children ns-node)))
       :aliases (into {} (for [[lib & opts] specs
                               [k v] (partition 2 opts)
                               :when (#{:as :as-alias} k)]
                           [v lib]))
       :refers (into {} (for [[lib & opts] specs
                              [k v] (partition 2 opts)
                              :when (and (= :refer k) (watched-libs lib))]
                          [lib v]))})))

(defn- watched-fn
  "[lib fn-name] when `sym` names a watched read function under `aliases`."
  [{:keys [aliases]} sym]
  (when (and (symbol? sym) (namespace sym))
    (let [q (symbol (namespace sym))
          lib (get aliases q q)
          f (name sym)]
      (when (or (and (= lib 'digdir.config.accessor) (contains? accessor-read-fns f))
                (and (= lib 'digdir.config.db) (contains? config-db-read-fns f)))
        [lib f]))))

(defn- site-nodes
  "Every use of a watched read function under `node`, outside `(comment ...)`.
   A use that is not the head of a call (nor the function of an `apply`) is a
   `:value` use: `partial`, a higher-order argument, a quoted symbol."
  [info node]
  (let [tag (n/tag node)]
    (cond
      (skipped-tags tag) []
      (#{:list :fn} tag)
      (let [[h & more] (code-children node)
            hs (some-> h sexpr-of)]
        (if (= 'comment hs)
          []
          (let [applied? (= 'apply hs)
                fnode (if applied? (first more) h)
                api (watched-fn info (some-> fnode sexpr-of))]
            (concat
             (when api
               [{:lib (first api) :fn (second api) :row (:row (meta node)) :node node
                 :shape (if applied? :apply :call)
                 :args (vec (if applied? (rest more) more))}])
             (mapcat #(site-nodes info %)
                     (if api
                       (remove #(identical? % fnode) (code-children node))
                       (code-children node)))))))
      (= :token tag)
      (if-let [api (watched-fn info (sexpr-of node))]
        [{:lib (first api) :fn (second api) :row (:row (meta node)) :node node :shape :value :args []}]
        [])
      (n/inner? node) (mapcat #(site-nodes info %) (code-children node))
      :else [])))

(defn- def-name
  "The name of the first def-like form in `node` (through reader conditionals)."
  [node]
  (some (fn [x]
          (when ('#{def defn defn- defmacro defonce defmulti defmethod} (form-head x))
            (some-> (second (code-children x)) sexpr-of str)))
        (tree-seq n/inner? code-children node)))

(def ^:private binding-heads
  '#{let let* loop when-let if-let when-some if-some binding for doseq dotimes with-open letfn})

(def ^:private fn-heads '#{fn fn* defn defn- defmacro defmethod})

(defn- symbols-under [node]
  (->> (tree-seq n/inner? code-children node) (map sexpr-of) (filter symbol?)))

(defn- binding-targets
  "The binding targets of a binding vector: its even positions, and the even
   positions of any `:let` vector inside it (`for`, `doseq`)."
  [bv]
  (let [kids (vec (code-children bv))]
    (concat (take-nth 2 kids)
            (for [[k v] (partition 2 1 kids)
                  :when (and (= :let (sexpr-of k)) (= :vector (n/tag v)))
                  t (take-nth 2 (code-children v))]
              t))))

(defn- bound-symbols
  "Every symbol bound anywhere in `node`, over-approximated (a binding in one
   fn counts for the whole top-level form): a local that shadows a var must
   never be resolved AS the var. Over-approximating leaves a site unresolved,
   and an unresolved site must be classified: the safe direction. A qualified
   symbol is never a local."
  [node]
  (->> (tree-seq n/inner? code-children node)
       (mapcat (fn [x]
                 (let [h (form-head x)
                       kids (when h (rest (code-children x)))]
                   (cond
                     (binding-heads h)
                     (some->> (first (filter #(= :vector (n/tag %)) kids))
                              binding-targets
                              (mapcat symbols-under))
                     (fn-heads h)
                     (concat (mapcat symbols-under (filter #(= :vector (n/tag %)) kids))
                             (for [arity kids
                                   :when (= :list (n/tag arity))
                                   :let [argv (first (code-children arity))]
                                   :when (and argv (= :vector (n/tag argv)))
                                   s (symbols-under argv)]
                               s))
                     :else nil))))
       (remove namespace)
       set))

(def ^:private iteration-heads '#{for doseq})

(defn- literal-value? [x]
  (or (string? x) (and (vector? x) (next x) (every? keyword? x))))

(defn- own-bindings
  "How the form `x` itself (not its descendants) binds the plain symbol `sym`,
   as [{:kind :let|:each|:param :init sexpr}]. `:each` binds one element at a
   time of `:init` (a `for`/`doseq` binding). `:param` is a parameter or a
   destructured target: never a literal."
  [x sym]
  (let [h (form-head x)
        kids (when h (rest (code-children x)))]
    (cond
      (binding-heads h)
      (when-let [bv (first (filter #(= :vector (n/tag %)) kids))]
        (let [pairs (partition 2 (code-children bv))
              let-pairs (for [[k v] pairs
                              :when (and (= :let (sexpr-of k)) (= :vector (n/tag v)))
                              pair (partition 2 (code-children v))]
                          pair)
              own (fn [kind] (fn [[t init]] (when (= sym (sexpr-of t)) {:kind kind :init (sexpr-of init)})))]
          (concat (keep (own (if (iteration-heads h) :each :let)) pairs)
                  (keep (own :let) let-pairs)
                  (for [[t _] (concat pairs let-pairs)
                        :when (and (not= sym (sexpr-of t)) (some #{sym} (symbols-under t)))]
                    {:kind :param}))))
      (fn-heads h)
      (when (some #{sym} (concat (mapcat symbols-under (filter #(= :vector (n/tag %)) kids))
                                 (for [arity kids
                                       :when (= :list (n/tag arity))
                                       :let [argv (first (code-children arity))]
                                       :when (and argv (= :vector (n/tag argv)))
                                       s (symbols-under argv)]
                                   s)))
        [{:kind :param}])
      :else nil)))

(defn- ancestors-of
  "The chain of nodes from `node` down to `target` (by identity), or nil."
  [node target]
  (cond (identical? node target) [node]
        (n/inner? node) (some (fn [c] (some->> (ancestors-of c target) (cons node))) (code-children node))
        :else nil))

(defn- local-literal-paths
  "The config paths the LOCAL `sym` can hold at `node`, decided by the
   innermost form around `node` that binds it: a `let` of a literal path, or
   a `for`/`doseq` over a literal collection of them. nil when that binding is
   a parameter or computed, or when no enclosing form binds `sym` (the safe
   direction: the site stays unresolved and must be classified). This is leg 2
   of the `:generic` audit, built in: a path fixed in code is resolved, so it
   can never hide behind `:generic`."
  [top node sym]
  (let [bs (some #(seq (own-bindings % sym)) (reverse (ancestors-of top node)))
        ->path (fn [v] (if (string? v) v (dotted v)))]
    (when (and (seq bs) (not-any? #(= :param (:kind %)) bs))
      (let [vals (for [{:keys [kind init]} bs]
                   (case kind
                     :let (when (literal-value? init) [(->path init)])
                     :each (when (and (coll? init) (not (map? init)) (seq init) (every? literal-value? init))
                             (map ->path init))))]
        (when (every? some? vals)
          (vec (distinct (apply concat vals))))))))

(defn- census-source
  "Every read site in one source text, with its file, enclosing def, the
   file's namespace facts and the symbols bound around it."
  [file text]
  (let [tops (code-children (p/parse-string-all text))
        info (ns-info tops)]
    (vec (for [top tops
               :let [bound (delay (bound-symbols top))
                     dn (or (def-name top) "<top level>")]
               s (site-nodes info top)]
           (assoc s :file file :def dn :info info :bound bound :top top)))))

(def ^:private census-excluded-files
  "The implementation of the read API itself: its internal calls are the
   chokepoints being censused, not reads of a path. (config.db calls its own
   functions unqualified, so the census cannot see them; its tables are
   checked by `config-db-path-tables`.)"
  {"src/digdir/config/accessor.clj" "the accessor's own internals"})

(defn- source-files []
  (for [root source-roots
        f (file-seq (io/file root))
        :when (re-find #"\.clj[c]?$" (.getName ^java.io.File f))
        :let [path (str f)]
        :when (not (contains? census-excluded-files path))]
    path))

(def ^:private real-census
  (delay (vec (mapcat #(census-source % (slurp %)) (sort (source-files))))))

(def ^:private real-ns-infos
  (delay (into (sorted-map)
               (for [f (sort (source-files))
                     :let [info (ns-info (code-children (p/parse-file-all (io/file f))))]
                     :when info]
                 [f info]))))

;; =============================================================================
;; Resolution: from a site to the (path, root) pairs it reads
;; =============================================================================

(defn- resolve-symbol
  "The value of `sym` at the site. A local yields the paths it is bound to when
   every binding is literal (`local-literal-paths`); any other local yields
   nil, never the var it shadows. Otherwise, the var's value in the site's
   namespace."
  [{:keys [info bound top]} sym node]
  (if (contains? @bound sym)
    (when (and top node) (local-literal-paths top node sym))
    (try (require (:ns info))
         (let [v (ns-resolve (:ns info) sym)] (when (var? v) @v))
         (catch Throwable _ nil))))

(defn- paths-in
  "The config paths a resolved VALUE names, or nil."
  [v]
  (cond (config-path? v) [v]
        (and (vector? v) (next v) (every? keyword? v)) [(dotted v)]
        (and (coll? v) (not (map? v)) (seq v) (every? config-path? v)) (vec (sort v))
        :else nil))

(defn- arg-paths [site nodes]
  (let [vs (map sexpr-of nodes)]
    (cond
      (and (next vs) (every? keyword? vs)) [(dotted vs)]
      (= 1 (count vs)) (let [v (first vs)] (paths-in (if (symbol? v) (resolve-symbol site v (first nodes)) v)))
      :else nil)))

(defn- resolve-site
  "`{:reads [{:path :root}]}` when the code alone says what the site reads;
   `{:table true}` when it reads a config.db table (all checked in full);
   otherwise `{:unresolved why}`."
  [{:keys [lib shape args] f :fn :as site}]
  (let [spec (if (= lib 'digdir.config.accessor) (accessor-read-fns f) (config-db-read-fns f))
        reads (fn [ps root] {:reads (mapv (fn [p] {:path p :root root}) ps)})]
    (cond
      (= :value shape) {:unresolved "used as a value, not called"}
      (:table spec) {:table true}
      (:opts-paths spec)
      (let [opts (some-> (nth args (:opts-paths spec) nil) sexpr-of)
            ps (when (and (map? opts) (contains? opts :paths))
                 (arg-paths site [(n/coerce (:paths opts))]))]
        (if ps
          (reads ps (:root spec))
          {:unresolved "a bulk read whose :paths the code does not fix"}))
      :else
      (let [path-nodes (if-let [i (:path-from spec)]
                         (drop i args)
                         (some-> (nth args (:path-at spec) nil) vector))
            ps (arg-paths site path-nodes)
            root (cond (:root spec) (:root spec)
                       (:any-root spec) :any
                       :else (let [r (some-> (nth args (:root-at spec) nil) sexpr-of)]
                               (when (#{:platform :runtime :dataset} r) r)))]
        (cond (nil? ps) {:unresolved "the path is computed at run time"}
              (nil? root) {:unresolved "the root is computed at run time"}
              :else (reads ps root))))))

;; =============================================================================
;; Classified sites: what the code alone cannot resolve
;; =============================================================================

(def ^:private parsed-sources
  "Every censused source file, parsed once: {:file :tops :info}."
  (delay (vec (for [f (sort (source-files))
                    :let [tops (code-children (p/parse-file-all (io/file f)))]]
                {:file f :tops tops :info (ns-info tops)}))))

(defn- calls-in
  "Every call under `node` whose head `head?` accepts, as {:args :row :def}."
  [node head? dn]
  (cond
    (skipped-tags (n/tag node)) []
    (#{:list :fn} (n/tag node))
    (let [[h & args] (code-children node)
          hs (some-> h sexpr-of)]
      (if (= 'comment hs)
        []
        (concat (when (head? hs) [{:args (vec args) :row (:row (meta node)) :def dn}])
                (mapcat #(calls-in % head? dn) (code-children node)))))
    (n/inner? node) (mapcat #(calls-in % head? dn) (code-children node))
    :else []))

(defn- var-calls
  "Every call of the var `vsym` (fully qualified) in `sources` (default: every
   censused file): by its bare name in its own namespace, by any alias
   elsewhere. Each carries the caller's file, namespace facts and top form."
  ([vsym] (var-calls @parsed-sources vsym))
  ([sources vsym]
   (let [lib (symbol (namespace vsym))
         nm (name vsym)]
     (vec (for [{:keys [file tops info]} sources
                :let [head? (fn [h] (and (symbol? h)
                                         (= nm (name h))
                                         (if-let [q (namespace h)]
                                           (= lib (get (:aliases info) (symbol q) (symbol q)))
                                           (= lib (:ns info)))))]
                top tops
                c (calls-in top head? (or (def-name top) "<top level>"))]
            (assoc c :file file :ns (:ns info) :info info :top top))))))

(defn- literal-keys? [v] (or (keyword? v) (and (vector? v) (seq v) (every? keyword? v))))

(defn- helper-reads
  "SECOND-ORDER resolution. A helper that takes the path (or its last key) as
   an argument reads whatever its callers pass, so its paths are the literal
   arguments at `arg` of every call of it anywhere in source. A `wrapper`
   forwards its own parameter to the helper: its forwarding call is followed
   to the wrapper's callers instead. Any other caller passing a non-literal is
   reported, never skipped."
  [{:keys [helper wrappers arg ->keys root] :or {wrappers #{} ->keys identity}}]
  (let [forwarding? (fn [{:keys [ns def]}] (contains? wrappers (symbol (str ns) def)))
        calls (remove forwarding? (mapcat var-calls (cons helper wrappers)))
        arg-of (fn [{:keys [args]}] (if (= :all arg)
                                      (let [vs (mapv sexpr-of args)] (when (every? keyword? vs) vs))
                                      (some-> (nth args arg nil) sexpr-of)))]
    {:reads (vec (for [c calls :let [v (arg-of c)]
                       :when (or (literal-keys? v) (config-path? v))]
                   {:path (if (string? v) v (dotted (->keys v))) :root root}))
     :unresolvable (vec (for [c calls :let [v (arg-of c)]
                              :when (not (or (literal-keys? v) (config-path? v)))]
                          (str (:file c) ":" (:row c) " calls " helper " with " (pr-str v))))}))

(defn- local-helper-reads
  "SECOND-ORDER resolution for a helper bound locally (`let`) in `file`: every
   call of `fn-sym` in that file."
  [file fn-sym arg ->keys root]
  (let [{:keys [tops]} (first (filter #(= file (:file %)) @parsed-sources))
        calls (mapcat #(calls-in % #{fn-sym} nil) tops)
        arg-of (fn [{:keys [args]}] (if (= :all arg)
                                      (let [vs (mapv sexpr-of args)] (when (every? keyword? vs) vs))
                                      (some-> (nth args arg nil) sexpr-of)))]
    {:reads (vec (for [c calls :let [v (arg-of c)] :when (literal-keys? v)]
                   {:path (dotted (->keys v)) :root root}))
     :unresolvable (vec (for [c calls :let [v (arg-of c)] :when (not (literal-keys? v))]
                          (str file ":" (:row c) " calls local " fn-sym " with " (pr-str v))))}))

(defn- code-path-arg
  "The config paths an argument fixes IN CODE at a call: a literal path, a
   keyword vector naming one, or a symbol whose value (a var, or a local bound
   to literals) holds one. nil when the argument is the caller's own data."
  [{:keys [info top]} node]
  (let [v (sexpr-of node)
        site {:info info :top top :bound (delay (bound-symbols top))}]
    (paths-in (if (symbol? v) (resolve-symbol site v node) v))))

(defn- code-path-callers
  "Calls, up to `depth` callers up, that hand `vsym` a path fixed in code."
  [sources vsym depth]
  (when (pos? depth)
    (let [calls (var-calls sources vsym)]
      (concat
       (for [c calls
             a (:args c)
             :let [ps (code-path-arg c a)]
             :when ps]
         (str (:file c) ":" (:row c) " calls " vsym " with " (str/join ", " ps)))
       (mapcat #(code-path-callers sources (symbol (str (:ns %)) (:def %)) (dec depth))
               (distinct (for [c calls :when (not= "<top level>" (:def c))] (select-keys c [:ns :def]))))))))

(defn- form-present?
  "Whether `file` contains the code form `form` (parsed, so a docstring or
   comment naming it does not count)."
  [file form]
  (let [{:keys [tops]} (first (filter #(= file (:file %)) @parsed-sources))]
    (boolean (some #(= form (sexpr-of %))
                   (mapcat #(tree-seq n/inner? code-children %) tops)))))

(defn- var-value [vsym] (var-get (requiring-resolve vsym)))

(defn- pinned-reads
  "Reads declared from the vars a site maps over, trusted only while every
   pin (a label and a check against the parsed source) still holds;
   otherwise reported."
  [file pins reads-fn]
  (let [failed (for [[label ok?] pins :when (not (ok?))] label)]
    (if (seq failed)
      {:reads [] :unresolvable (mapv #(str file ": " % " no longer holds; re-derive this classification") failed)}
      {:reads (reads-fn) :unresolvable []})))

(def ^:private admin-op "a config admin operation over the path it is handed; the path is the caller's data")

(def ^:private classified-sites
  "Every read site the code alone cannot resolve, keyed
   [file enclosing-def lib/fn]: its count, and EITHER the paths it reads
   (`:reads`, resolved second-order from the helper's callers or from the vars
   it maps over; `:uses` pins that the def still references those vars) OR
   `:generic`, why its path is data rather than code.

   Every `:reads` entry pins its YIELD with `:expect-reads`, the way `:count`
   pins sites: a classification that quietly stops yielding (a helper renamed,
   a key renamed) would otherwise pass an unseeded read green.

   WHAT `:generic` COSTS: a `:generic` site is a site the guard does not read.
   Two legs are checked, not trusted: a path bound to literals inside the def
   is RESOLVED by the census (so the site goes stale), and a def called with a
   path fixed in code, up to two callers up, is red. What remains trusted is
   the reason string, for paths that genuinely arrive as data. Prefer making
   the site resolvable, or `:reads`."
  {;; --- second-order: helpers whose callers pass the path -----------------------
   ["src/digdir/llm/marker.clj" "configured" "accessor/get"]
   {:count 1 :expect-reads 2 :reads #(helper-reads {:helper 'digdir.llm.marker/configured :arg 1
                                     :->keys (fn [k] [:services :marker k]) :root :platform})}
   ["src/digdir/llm/provider.clj" "required-credential" "accessor/get"]
   {:count 1 :expect-reads 4 :reads #(helper-reads {:helper 'digdir.llm.provider/required-credential :arg 1 :root :platform})}
   ["src/digdir/setup/common.clj" "get-global-config" "accessor/get-platform-value"]
   {:count 1 :expect-reads 5 :reads #(helper-reads {:helper 'digdir.setup.common/get-global-config
                                     :wrappers '#{digdir.setup.workflow/get-global-config digdir.setup/get-global-config}
                                     :arg 0 :root :platform})}
   ["src/digdir/setup/common.clj" "get-global-config" "config-db/get-definition"]
   {:count 1 :expect-reads 5 :reads #(helper-reads {:helper 'digdir.setup.common/get-global-config
                                     :wrappers '#{digdir.setup.workflow/get-global-config digdir.setup/get-global-config}
                                     :arg 0 :root :platform})}
   ["src/digdir/setup/config.clj" "set-global-config!" "config-db/get-definition"]
   {:count 1 :expect-reads 5 :reads #(helper-reads {:helper 'digdir.setup.config/set-global-config!
                                     :wrappers '#{digdir.setup.workflow/set-global-config! digdir.setup/set-global-config!}
                                     :arg 0 :root :platform})}
   ["src-dev/digdir/sweep/runner.clj" "resolve-model-manifest!" "accessor/get"]
   {:count 1 :expect-reads 3 :uses '[cfgv] :reads #(local-helper-reads "src-dev/digdir/sweep/runner.clj" 'cfgv :all identity :platform)}

   ;; --- var-driven: the site maps over a table held in a var ------------------
   ["src/digdir/rag/typesense.clj" "platform-value" "accessor/get-platform-value"]
   {:count 1 :expect-reads 3
    :reads #(pinned-reads "src/digdir/rag/typesense.clj"
                          [["its keys are (conj required-platform-keys :api-tls)"
                            (fn [] (form-present? "src/digdir/rag/typesense.clj" '(conj required-platform-keys :api-tls)))]
                           ["platform-value has exactly one caller, the map over those keys"
                            (fn [] (= 1 (count (var-calls 'digdir.rag.typesense/platform-value))))]]
                          (fn [] (for [k (conj (var-value 'digdir.rag.typesense/required-platform-keys) :api-tls)]
                                   {:path (dotted [:services :typesense k]) :root :platform})))}
   ["src/digdir/boot/provider_switch.clj" "read-credentials" "accessor/get"]
   ;; every path string in each branch's entry, not the entry's named keys: a
   ;; test that re-types the product's destructuring goes blind when a key is
   ;; renamed
   {:count 1 :expect-reads 5 :uses '[branch-credentials]
    :reads #(hash-map :unresolvable []
                      :reads (vec (distinct (for [entry (vals (var-value 'digdir.boot.provider-switch/branch-credentials))
                                                  path (tree-seq coll? seq entry)
                                                  :when (config-path? path)]
                                              {:path path :root :platform}))))}
   ["src/digdir/boot/provider_switch.clj" "read-tenant" "accessor/get-platform-value"]
   {:count 1 :expect-reads 3 :uses '[azure-credential-paths]
    :reads #(hash-map :unresolvable []
                      :reads (mapv (fn [path] {:path path :root :platform})
                                   (var-value 'digdir.boot.provider-switch/azure-credential-paths)))}
   ["src/digdir/config/verify.clj" "unsupplied-first-query-config" "accessor/get-platform-value"]
   {:count 1 :expect-reads 11 :uses '[env-bridge/first-query-bindings]
    :reads #(hash-map :unresolvable []
                      :reads (vec (for [{:keys [path destination]} (env-bridge/first-query-bindings :any)
                                        :when (and path (not= :environment destination))]
                                    {:path path :root :platform})))}

   ;; --- generic: the path is data, not code -------------------------------------
   ["src/digdir/config/verify.clj" "undecryptable-service-config" "accessor/get-platform-value"]
   {:count 1 :generic "the paths that HOLD encrypted values in the store: a stored value implies its definition"}
   ["src/digdir/api/routes/datasets.clj" "infer-config-root" "config-db/get-definition"]
   {:count 1 :generic "the /config API: the root of a path named in the request"}
   ["src/digdir/api/routes/datasets.clj" "resolve-runtime-config-handler" "accessor/load-runtime-config-v2-with-trace"]
   {:count 1 :generic "the /config API: the paths named in the request, or the whole root"}
   ["src/digdir/api/routes/datasets.clj" "resolve-dataset-config-handler" "accessor/load-dataset-config-v2-with-trace"]
   {:count 1 :generic "the /config API: the paths named in the request, or the whole root"}
   ["src/digdir/config/ui/common.cljc" "get-runtime-trace-data" "accessor/load-runtime-config-v2-with-trace"]
   {:count 1 :generic "the config UI's trace view, over the paths the UI asks for"}
   ["src/digdir/config/ui/common.cljc" "get-inheritance-editor-data" "config-db/resolve-node-values-batch"]
   {:count 1 :generic "the config UI's inheritance editor, over the stored definitions"}
   ["src/digdir/config/ui/common.cljc" "mutate-config-tree!" "config-db/get-definition"]
   {:count 3 :generic "the config UI's edit of the path the operator chose"}
   ["src/digdir/config/ui.cljc" "get-node-value-reset-preview-data" "config-db/get-definition"]
   {:count 1 :generic "the config UI's reset preview of the path the operator chose"}
   ["src/digdir/tools/config.clj" "get-value" "accessor/get-platform-value-with-trace"]
   {:count 1 :generic "the config CLI: the path on its command line"}
   ["src/digdir/tools/config.clj" "get-value" "accessor/get-runtime-value-with-trace"]
   {:count 1 :generic "the config CLI: the path on its command line"}
   ["src/digdir/tools/config.clj" "get-value" "accessor/get-dataset-value-with-trace"]
   {:count 1 :generic "the config CLI: the path on its command line"}
   ["src/digdir/config/ops/bootstrap.clj" "strip-inherit-owned-paths" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/global.clj" "assert-inherit-owned!" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/global.clj" "previous-raw-global-value" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/global.clj" "resolve-pin-source" "config-db/resolve-global-value-with-trace"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/global.clj" "unpin-tenant-value!" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "assert-fork-owned!" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "assert-inherit-owned!" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "demote-from-global!" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "inspect-definition-values" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "pin-all-globals-for-tenant!" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/ownership.clj" "platform-defaults-value-for" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/sync.clj" "import-node-values!" "config-db/get-definition"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/sync.clj" "import-node-values!" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/sync.clj" "preview-node-value-import" "config-db/get-node-value"] {:count 1 :generic admin-op}
   ["src/digdir/config/ops/sync.clj" "audit-tx-data" "config-db/get-definition"]
   {:count 1 :generic "a restore's audit rows: each row's config path is stored data from the backup"}
   ["src/digdir/config/ops/topology.clj" "resolve-root-values" "config-db/resolve-node-values-batch"] {:count 1 :generic admin-op}
   ["src/digdir/setup/workflow.clj" "strip-inherit-owned" "config-db/get-definition"]
   {:count 1 :generic "the keys of the platform-defaults values being forked to a new tenant"}
   ["src/digdir/pipeline/core.clj" "ensure-pipeline-property-definitions!" "config-db/get-definition"]
   {:count 1 :generic "a registration guard: looks the definition up only to upsert it when absent"}})

;; =============================================================================
;; The check, shared by the real census and the planted needles
;; =============================================================================

(defn- short-lib [lib] (if (= lib 'digdir.config.accessor) "accessor" "config-db"))

(defn- site-key [s] [(:file s) (:def s) (str (short-lib (:lib s)) "/" (:fn s))])

(defn- site-label [s] (str (:file s) ":" (:row s) " (" (:def s) ") " (short-lib (:lib s)) "/" (:fn s)))

(defn- references? [top sym]
  (boolean (some #(= sym (sexpr-of %)) (tree-seq n/inner? code-children top))))

(defn- check
  "Every way `sites` break the invariant against `catalogue`, by kind.
   `sources` are the parsed files the `:generic` callers leg searches."
  [sites catalogue classified sources]
  (let [sites (mapv #(assoc % :resolution (resolve-site %)) sites)
        unresolved (filter (comp :unresolved :resolution) sites)
        groups (group-by site-key unresolved)
        second-order (into {} (for [[k entry] classified
                                    :when (and (:reads entry) (contains? groups k))]
                                [k ((:reads entry))]))
        all-reads (concat
                   (for [s sites r (:reads (:resolution s))] (assoc r :from (site-label s)))
                   (for [[k res] second-order r (:reads res)]
                     (assoc r :from (str (first k) " (" (second k) ") second-order"))))]
    {:unclassified (vec (for [[k ss] (sort-by key groups)
                              :when (not (contains? classified k))
                              s ss]
                          (str (site-label s) ": " (:unresolved (:resolution s)))))
     :stale (vec (for [k (sort (keys classified)) :when (not (contains? groups k))] (pr-str k)))
     :count-changed (vec (for [[k {:keys [count]}] (sort-by key classified)
                               :let [found (clojure.core/count (get groups k))]
                               :when (and (pos? found) (not= count found))]
                           (str (pr-str k) " declares " count " site(s), the census finds " found)))
     :premise-gone (vec (for [[k {:keys [uses]}] (sort-by key classified)
                              :let [top (:top (first (get groups k)))]
                              :when top
                              sym uses
                              :when (not (references? top sym))]
                          (str (pr-str k) " no longer references " sym)))
     :unresolvable (vec (mapcat :unresolvable (vals second-order)))
     :unpinned (vec (for [[k entry] (sort-by key classified)
                          :when (and (:reads entry) (not (integer? (:expect-reads entry))))]
                      (str (pr-str k) " yields reads but pins no :expect-reads")))
     :yield-changed (vec (for [[k {:keys [expect-reads]}] (sort-by key classified)
                               :let [res (get second-order k)]
                               :when (and res (integer? expect-reads))
                               :let [n (count (distinct (map (juxt :path :root) (:reads res))))]
                               :when (not= expect-reads n)]
                           (str (pr-str k) " expects " expect-reads " read(s) and yields " n
                                ": the classification stopped seeing what it classifies, or the code changed")))
     :generic-code-paths (vec (for [[k {:keys [generic]}] (sort-by key classified)
                                    :when generic
                                    :let [site (first (get groups k))]
                                    :when site
                                    hit (code-path-callers sources (symbol (str (:ns (:info site))) (:def site)) 2)]
                                (str (pr-str k) " is :generic, but " hit)))
     :unseeded (vec (distinct (for [{:keys [path root from]} all-reads
                                    :when (not (contains? catalogue path))]
                                (str path " (read under " root ") <- " from))))
     :wrong-root (vec (distinct (for [{:keys [path root from]} all-reads
                                      :let [seeded (get catalogue path)]
                                      :when (and seeded (not= :any root) (not= root seeded))]
                                  (str path " is read under " root " but seeded under " seeded " <- " from))))
     :reads (count all-reads)
     :sites sites}))

(def ^:private real-check
  (delay (check @real-census @fresh-catalogue classified-sites @parsed-sources)))

;; =============================================================================
;; The guard
;; =============================================================================

(deftest every-read-path-is-seeded-under-the-root-it-is-read-from
  (let [{:keys [unseeded wrong-root]} @real-check]
    (testing "every path the code reads is seeded on a fresh install"
      (is (empty? unseeded)
          (str "read by the code but NOT seeded on a fresh install. On most read shapes "
               "this is silent: the reader returns its code default and every attempt to "
               "set the value is refused. Seed it (setup/config.clj), or stop reading it:\n  "
               (str/join "\n  " unseeded))))
    (testing "every path is seeded under the root it is read from"
      (is (empty? wrong-root)
          (str "seeded, but under a different root than the reader asks for; the "
               "accessor throws on this at run time:\n  " (str/join "\n  " wrong-root))))))

(deftest every-read-site-is-resolved-or-classified
  (let [{:keys [unclassified stale count-changed premise-gone unresolvable
                unpinned yield-changed generic-code-paths]} @real-check]
    (testing "every site the code cannot resolve is classified"
      (is (empty? unclassified)
          (str "read sites whose path the census cannot resolve. Prefer making each "
               "resolvable (a literal path, a var holding it, or a helper whose callers pass "
               "literals), else give it `:reads` in `classified-sites`. `:generic` SILENCES "
               "this guard for the site: use it only when the path is genuinely the caller's "
               "data:\n  " (str/join "\n  " unclassified))))
    (testing "no classification outlives its site"
      (is (empty? stale) (str "classified, but no such unresolved site remains:\n  " (str/join "\n  " stale))))
    (testing "every classification's site count is current"
      (is (empty? count-changed) (str/join "\n  " count-changed)))
    (testing "every var-driven classification still references its vars"
      (is (empty? premise-gone) (str/join "\n  " premise-gone)))
    (testing "every helper caller passes a literal the census can read"
      (is (empty? unresolvable) (str/join "\n  " unresolvable)))
    (testing "every :reads classification pins its yield, and still yields it"
      (is (empty? unpinned) (str/join "\n  " unpinned))
      (is (empty? yield-changed) (str/join "\n  " yield-changed)))
    (testing "no :generic site's def is called with a path fixed in code"
      (is (empty? generic-code-paths)
          (str "a :generic site whose def is handed a code-fixed path is a read the guard "
               "cannot see; classify it :reads (second-order):\n  " (str/join "\n  " generic-code-paths))))))

(deftest the-read-api-is-fully-classified
  (testing "every accessor public is a read function or a declared non-read"
    (is (= (set (map str (keys (ns-publics 'digdir.config.accessor))))
           (into (set (keys accessor-read-fns)) (keys accessor-publics-that-do-not-read)))
        "a new accessor function must be added to accessor-read-fns (with its root) or declared a non-read"))
  (testing "every config.db public that takes a path is a read or a declared non-read"
    (let [path-param? #(re-find #"(?i)^path|paths$|path-str|^prop-path" (str %))
          params (fn [arglist] (mapcat (fn [a] (if (map? a) (concat (:keys a) (vals (dissoc a :keys :as :or))) [a])) arglist))
          path-fns (set (for [[s v] (ns-publics 'digdir.config.db)
                              :when (fn? @v)
                              :when (some #(some path-param? (params %)) (:arglists (meta v)))]
                          (str s)))]
      (is (= path-fns (into (set (keys config-db-read-fns)) (keys config-db-path-fns-that-do-not-read)))
          "a config.db function taking a path must be a censused read or a declared non-read")))
  (testing "no source file :refers a watched function (a bare symbol is invisible to the census)"
    (let [referring (into (sorted-map) (keep (fn [[f {:keys [refers]}]] (when (seq refers) [f refers]))) @real-ns-infos)]
      (is (empty? referring)
          (str "require the namespace with :as and call it qualified; the census cannot see a bare "
               "referred symbol: " (pr-str referring))))))

(deftest every-config-db-path-table-is-seeded-under-its-root
  (let [path-table-paths (fn [v] (cond (and (map? v) (seq v) (every? config-path? (vals v))) (vals v)
                                       (and (map? v) (seq v) (every? config-path? (keys v))) (keys v)
                                       (and (coll? v) (not (map? v)) (seq v) (every? config-path? v)) (seq v)))
        tables (into {} (for [[s v] (ns-interns 'digdir.config.db)
                              :let [x (try @v (catch Throwable _ nil))]
                              :when (and (not (fn? x)) (path-table-paths x))]
                          [s (path-table-paths x)]))
        catalogue @fresh-catalogue]
    (testing "every path table in config.db is classified (found by the SHAPE of its value)"
      (is (= (set (keys tables)) (set (keys config-db-path-tables)))
          "a new path table in config.db must be given the root it is read under, or a not-read reason"))
    (testing "every path of every read table is seeded under its root"
      (let [bad (for [[s {:keys [root]}] config-db-path-tables
                      :when root
                      p (get tables s)
                      :when (not= root (get catalogue p))]
                  (str p " in " s " is read under " root ", seeded under " (pr-str (get catalogue p))))]
        (is (empty? bad)
            (str "a path table names a path a fresh install does not seed under the table's root; "
                 "the table readers drop it SILENTLY at run time:\n  " (str/join "\n  " bad)))))))

(deftest the-catalogue-is-boots-and-not-the-snapshots
  (let [catalogue @fresh-catalogue
        snapshot (slurp "../config/system-import.normalized.20260821.json")]
    (testing "it holds a known path under each root"
      (is (= :platform (get catalogue "services.llm.provider")))
      (is (= :runtime (get catalogue "skills.retrieval.top-k")))
      (is (= :dataset (get catalogue "pipeline.ui.name"))))
    (testing "NEGATIVE CONTROL: a path the shipped snapshot holds and boot retracts is absent"
      (is (str/includes? snapshot "\"skills.rerank.max-chunk-length\"")
          "the premise moved: the snapshot no longer holds this path; pick another it holds and boot retracts")
      (is (not (contains? catalogue "skills.rerank.max-chunk-length"))
          "the catalogue holds a path boot's rerank-mode-split migration retracts: it was not built by boot alone"))))

;; =============================================================================
;; Controls
;; =============================================================================

(def ^:private known-reads
  "Positive controls on the REAL code, one per shape real code uses, and one
   per `:reads` classification: a path the census must resolve there. A census
   blind to one of them goes red by NAME, which an aggregate count could not
   show. Real reads use four first-order shapes (keyword arguments, `apply` of a
   var's value, a bulk read's `:paths` var, a local bound to literal paths);
   the census's other shapes (a keyword vector, a dotted string, a plain call
   with a symbol, the config.db door with a literal) occur in no real read, so
   only their needles cover them."
  [["literal keywords"               "src/digdir/skills/context.clj"            "system.io-validation.enabled"]
   ["apply of a var's value"         "src/digdir/llm/provider.clj"              "services.llm.provider"]
   ["a bulk read's :paths var"       "src/digdir/execution/scope.clj"           "pipeline.storage.chunks-collection"]
   ["a local bound to literal paths" "src/digdir/pipeline/collections.clj"      "pipeline.storage.docs-collection"]
   ["src-dev, literal keywords"      "src-dev/digdir/skills/enrichment/analyze_corpus.clj" "services.azure-openai.deployment-name"]
   ["second-order: helper callers"   "src/digdir/llm/marker.clj"                "services.marker.timeout-ms"]
   ["second-order: through wrappers" "src/digdir/setup/common.clj"              "services.scaleway-tem.region"]
   ["second-order: a direct caller"  "src/digdir/setup/common.clj"              "services.typesense.collection-prefix"]
   ["var-driven: a pinned key set"   "src/digdir/rag/typesense.clj"             "services.typesense.api-tls"]
   ["var-driven: a bindings table"   "src/digdir/config/verify.clj"             "services.llm.api-endpoint"]
   ["second-order: required-credential" "src/digdir/llm/provider.clj"          "services.azure-openai.api-key"]
   ["second-order: set-global-config!"  "src/digdir/setup/config.clj"          "services.scaleway-tem.from-email"]
   ["second-order: a local helper"      "src-dev/digdir/sweep/runner.clj"      "services.judge.model"]
   ["var-driven: every path in an entry" "src/digdir/boot/provider_switch.clj" "services.llm.api-key"]
   ["var-driven: a whole path var"      "src/digdir/boot/provider_switch.clj"  "services.azure-openai.api-endpoint"]])

(defn- resolved-reads
  "Every (path, source) the check resolved: first-order from sites, and
   second-order from classifications."
  [{:keys [sites]} classified]
  (let [groups (group-by site-key (filter (comp :unresolved :resolution) sites))]
    (concat (for [s sites r (:reads (:resolution s))] [(:path r) (:file s)])
            (for [[k entry] classified
                  :when (and (:reads entry) (contains? groups k))
                  r (:reads ((:reads entry)))]
              [(:path r) (first k)]))))

(deftest the-census-resolves-a-known-read-of-every-shape
  (let [reads (resolved-reads @real-check classified-sites)]
    (doseq [[shape file path] known-reads]
      (testing (str "POSITIVE CONTROL, " shape)
        (is (some (fn [[p f]] (and (= f file) (= p path))) reads)
            (str "the census no longer resolves " path " in " file))))
    (testing "POSITIVE CONTROL: a table reader is recognised as one"
      (is (some #(and (= "src/digdir/skills/templates/core.clj" (:file %)) (:table (:resolution %)))
                (:sites @real-check))))))

(def needle-keys
  "A var the resolved-symbol needle reads. Public because it is reached only by
   name, through `ns-resolve` from `needle-source`."
  [:services :needle :keys])

(def needle-paths
  "A var the bulk needle reads. Public for the same reason."
  #{"skills.needle.bulk"})

(def ^:private needle-source
  "One read of an UNSEEDED path per shape, plus the shapes that must be
   classified. Parsed and checked exactly as the real source is."
  "(ns digdir.config.read-paths-seeded-test
     (:require [digdir.config.accessor :as cfg]
               [digdir.config.db :as config-db]))
   (defn literal [t] (cfg/get {:tenant t} :services :needle :literal))
   (defn vector-path [t] (cfg/get-platform-value [:services :needle :vector] {:tenant t}))
   (defn dotted-string [t] (cfg/get-runtime-value \"skills.needle.string\" {:tenant t}))
   (defn resolved-symbol [t] (cfg/get {:tenant t} needle-keys))
   (defn applied [t] (apply cfg/get {:tenant t} [:services :needle :applied]))
   (defn qualified [t] (digdir.config.accessor/get {:tenant t} :services :needle :qualified))
   (defn anonymous [ts] (map #(cfg/get {:tenant %} :services :needle :anonymous) ts))
   (defn other-door [db t n] (config-db/get-node-value db :platform t n \"services.needle.other-door\"))
   (defn bulk [t] (cfg/load-runtime-config-v2 {:tenant t :paths needle-paths}))
   (defn reader-conditional [t] #?(:clj (cfg/get {:tenant t} :services :needle :cljc) :cljs nil))
   (defn wrong-root [t] (cfg/get-runtime-value \"services.llm.provider\" {:tenant t}))
   (defn as-value [t ps] (map (partial cfg/get {:tenant t}) ps))
   (defn computed [t k] (cfg/get {:tenant t} :services :needle k))
   (defn shadowed [needle-keys t] (cfg/get {:tenant t} needle-keys))
   (defn let-bound [t] (let [p \"services.needle.let-bound\"] (cfg/get-platform-value p {:tenant t})))
   (defn loop-bound [db] (doseq [p [\"services.needle.loop-a\" \"services.needle.loop-b\"]] (config-db/get-definition db p)))
   (defn generic-helper [t p] (cfg/get-platform-value p {:tenant t}))
   (defn calls-generic [t] (generic-helper t \"services.needle.via-generic\"))
   (defn wraps-generic [t p] (generic-helper t p))
   (defn calls-wrapper [t] (wraps-generic t [:services :needle :via-wrapper]))
   (comment (cfg/get {} :services :needle :in-comment))")

(def ^:private needle-sources
  (delay (let [tops (code-children (p/parse-string-all needle-source))]
           [{:file "needle.clj" :tops tops :info (ns-info tops)}])))

(def ^:private needle-classified
  "The needles' own classification: `generic-helper` is filed :generic, so the
   callers leg must catch the code-fixed paths handed to it."
  {["needle.clj" "generic-helper" "accessor/get-platform-value"] {:count 1 :generic "needle: the caller's data"}})

(deftest planted-needles-go-red
  (let [found (check (census-source "needle.clj" needle-source) @fresh-catalogue needle-classified @needle-sources)
        mentions? (fn [kind s] (some #(str/includes? % s) (get found kind)))]
    (doseq [[shape path] [["literal keywords" "services.needle.literal"]
                          ["a keyword vector" "services.needle.vector"]
                          ["a dotted string" "skills.needle.string"]
                          ["a symbol resolved to its value" "services.needle.keys"]
                          ["apply" "services.needle.applied"]
                          ["a fully qualified call" "services.needle.qualified"]
                          ["a call inside #(...)" "services.needle.anonymous"]
                          ["the other door: config.db directly" "services.needle.other-door"]
                          ["a bulk read's :paths var" "skills.needle.bulk"]
                          ["a reader conditional" "services.needle.cljc"]
                          ["a local bound to a literal (let)" "services.needle.let-bound"]
                          ["a local bound to literals (doseq), first" "services.needle.loop-a"]
                          ["a local bound to literals (doseq), second" "services.needle.loop-b"]]]
      (testing (str "NEEDLE, " shape ": an unseeded path is red")
        (is (mentions? :unseeded path) (str path " was not reported: " (pr-str (dissoc found :sites))))))
    (testing "NEEDLE, a path seeded under another root is red"
      (is (mentions? :wrong-root "services.llm.provider is read under :runtime")))
    (doseq [[shape def-name] [["a use as a value" "(as-value)"]
                              ["a computed path" "(computed)"]
                              ["a local that shadows a path var" "(shadowed)"]]]
      (testing (str "NEEDLE, " shape ": the site must be classified")
        (is (mentions? :unclassified def-name))))
    (testing "NEEDLE, a local that shadows a var is never resolved AS the var"
      (is (not (mentions? :unseeded "(shadowed)"))))
    (testing "NEEDLE, a read inside (comment ...) is not code"
      (is (not-any? #(str/includes? (str %) "in-comment") (vals (dissoc found :sites)))))
    (testing "NEEDLE, a :generic def handed a code-fixed path by its caller is red"
      (is (mentions? :generic-code-paths "services.needle.via-generic")))
    (testing "NEEDLE, ... and by its caller's caller"
      (is (mentions? :generic-code-paths "services.needle.via-wrapper")))
    (testing "NEEDLE, a classification with no site is red"
      (let [stale (check [] @fresh-catalogue {["needle.clj" "gone" "accessor/get"] {:count 1 :generic "x"}} [])]
        (is (= 1 (count (:stale stale))))))
    (let [computed ["needle.clj" "computed" "accessor/get"]
          sites (census-source "needle.clj" needle-source)
          yields-nothing (fn [] {:reads [] :unresolvable []})]
      (testing "NEEDLE, a :reads classification that stopped yielding is red"
        (is (seq (:yield-changed (check sites @fresh-catalogue
                                        {computed {:count 1 :expect-reads 1 :reads yields-nothing}} [])))))
      (testing "NEEDLE, a :reads classification that pins no yield is red"
        (is (seq (:unpinned (check sites @fresh-catalogue {computed {:count 1 :reads yields-nothing}} []))))))))
