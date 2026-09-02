(ns digdir.build.harness-boundary-test
  "Guards the production build against the research harness.

   Sibling to `digdir.build.src-dev-boundary-test`. That one asks whether a
   namespace *exists* on the production classpath; this one asks what the
   production build can *reach*. Slice 1 of #82 (#89) promoted the
   hypothetical-questions enrichment path from src-dev into src, and the
   constraint that made the promotion safe — that it drags none of
   `digdir.sweep.*` or `digdir.tools.diagnostics` across with it — is
   invisible in the code. It would erode silently.

   It is worth stating precisely how narrow the escape is. Of the twelve
   enrichment namespaces the outer self-improve graph names, all harness
   contamination flowed through exactly two:

     enrichment.eval-delta -> tools.diagnostics       (1 call site)
     enrichment.eval-sweep -> sweep.runner, questions (2 call sites)

   Slice 2b (#94) closed the second. `enrichment.eval-sweep` is now a src/
   namespace that runs its comparison through `enrichment.eval-runner` and
   takes regression questions as data, so `promoted-eval-path-reaches-no-harness`
   below pins it the same way slice 1's path is pinned. `eval-delta` is still
   src-dev and still the sole requirer of `digdir.tools.diagnostics`; it is
   NOT dead code, despite being dropped from `demo.self-improve-graph` in
   slice 2a — `demo.self-improve-prune-phrases-graph` has an `:eval` step
   naming its `:builtin/enrichment-eval-suite` and gates readiness on it.

   Adding a single require to a promoted enrichment namespace re-opens that
   path, and nothing else would notice.

   `digdir.playground.diagnostics` is deliberately NOT treated as harness: it
   is a UI panel that ships in the product. The harness is `digdir.sweep.*`
   and `digdir.tools.diagnostics` specifically.

   TWO SHAPES OF CROSSING, because the first version of this test only saw
   one. It parsed `ns` forms, so it was blind to `requiring-resolve` — and
   `digdir/sweep/dashboard.cljc` reached `sweep.runner/run-matrix` and
   `sweep.questions/load-questions!` exactly that way, from a namespace that
   ships in production. Those calls threw FileNotFoundException when a user
   clicked, and the guard reported clean. Slice 2a (#94) fixed the edges and
   taught the guard the second shape.

   So: `reachable-*` covers static requires, `dynamic-targets` covers
   `requiring-resolve` and runtime `require`. A crossing that uses neither —
   resolving a namespace from a string built at runtime, say — would still
   be invisible, and that is the next shape to worry about."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private harness-ns?
  "True for namespaces that belong to the research harness rather than the
   product."
  (fn [n] (boolean (re-find #"^digdir\.sweep\.|^digdir\.tools\.diagnostics$" (str n)))))

(def ^:private known-production-harness-namespaces
  "Harness namespaces the production build already reaches, as of #89.

   These are NOT an exemption for new ones — this set is a ratchet and must
   only ever shrink. Both arrive through the admin UI, which mounts a sweep
   dashboard:

     prod -> digdir.ui.main -> digdir.sweep.dashboard -> digdir.sweep.judge

   They predate this test. Whether an evaluation dashboard belongs in the
   product surface is a real question, but it is a product question and not
   one slice 1 should settle by deleting code. Filed as a finding on #89
   rather than silently accepted."
  '#{digdir.sweep.dashboard
     digdir.sweep.judge})

(defn- clj-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))))

(defn- path->ns
  [root ^java.io.File f]
  (-> (.getPath f)
      (str/replace (re-pattern (str "^" root "/")) "")
      (str/replace #"\.cljc?$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- read-ns-form
  [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (binding [*read-eval* false]
        (let [form (read {:read-cond :allow :eof nil} r)]
          (when (and (seq? form) (= 'ns (first form))) form))))
    (catch Exception _ nil)))

(defn- required-namespaces
  [ns-form]
  (->> ns-form
       (filter seq?)
       (filter #(= :require (first %)))
       (mapcat rest)
       (keep (fn [entry]
               (cond
                 (symbol? entry) entry
                 (vector? entry) (first entry)
                 :else nil)))
       set))

(defn- require-graph
  "ns symbol -> set of required ns symbols, across the given source roots."
  [roots]
  (into {}
        (for [root roots
              f (clj-files root)
              :let [form (read-ns-form f)]
              :when form]
          [(path->ns root f) (required-namespaces form)])))

(defn- reachable-from
  "Transitive closure of `starts` over `graph`. Namespaces absent from the
   graph (external libs, or src-dev when it is excluded) terminate a branch."
  [graph starts]
  (loop [seen #{} queue (vec starts)]
    (if-let [n (first queue)]
      (if (contains? seen n)
        (recur seen (subvec queue 1))
        (recur (conj seen n) (into (subvec queue 1) (get graph n))))
      seen)))

;; The production classpath: deps.edn base :paths plus the :prod alias.
;; src-dev is deliberately excluded — that is the whole point.
(def ^:private production-roots ["src" "src-prod"])

(def ^:private known-dynamic-src-dev-targets
  "src-dev namespaces that production code may resolve at runtime.

   Shrink-only, like the static allowlist, but a different lifecycle: these
   are *intentional optional* loads, each wrapped so a missing namespace
   degrades rather than throws —

     digdir.agents.dev              widens the agent list in a dev build (#71)
     digdir.demo.self-improve-agent registers dev-only agent tools

   Both catch FileNotFoundException and continue. That is what separates
   them from the four edges slice 2a removed, which were unguarded and threw
   in a user's face. Adding to this list means arguing that a new optional
   load degrades safely; it is not a parking space for broken edges."
  '#{digdir.agents.dev
     digdir.demo.self-improve-agent})

(defn- read-all-forms
  "Every form in a file, or nil if it will not read."
  [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (binding [*read-eval* false]
        (doall
          (take-while some?
                      (repeatedly #(read {:read-cond :allow :eof nil} r))))))
    (catch Exception _ nil)))

(defn- quoted-sym
  "The symbol inside (quote x), else nil."
  [form]
  (when (and (seq? form) (= 'quote (first form)) (symbol? (second form)))
    (second form)))

(defn- dynamic-targets
  "Namespaces named by `(requiring-resolve 'ns/var)` or `(require 'ns)`
   anywhere in a file's forms.

   Walks forms rather than grepping: a docstring in this very namespace
   mentions `sweep.runner/run-matrix` by name, and a text search would flag
   its own explanation."
  [forms]
  (let [out (volatile! #{})]
    (letfn [(walk [x]
              (when (seq? x)
                (when-let [head (first x)]
                  (when (#{'requiring-resolve 'require} head)
                    (doseq [arg (rest x)]
                      (when-let [sym (quoted-sym arg)]
                        (vswap! out conj
                                (if-let [ns-part (namespace sym)]
                                  (symbol ns-part)
                                  sym)))))))
              (when (coll? x) (run! walk x)))]
      (run! walk forms))
    @out))

(defn- dynamic-edges-from
  "{source-ns -> #{target-ns}} for every dynamic reference under `roots`."
  [roots]
  (into {}
        (for [root roots
              f (clj-files root)
              :let [forms (read-all-forms f)
                    targets (when forms (dynamic-targets forms))]
              :when (seq targets)]
          [(path->ns root f) targets])))

(deftest production-code-dynamically-resolves-no-harness
  (testing "no requiring-resolve/require in src reaches the research harness"
    (let [offenders (for [[src-ns targets] (dynamic-edges-from production-roots)
                          :let [bad (filter harness-ns? targets)]
                          :when (seq bad)]
                      {:from src-ns :to (vec (sort bad))})]
      (is (empty? offenders)
          (str "production code resolves harness namespaces at RUNTIME. This is "
               "the shape the first version of this test could not see: it "
               "throws FileNotFoundException when a user triggers it, not at "
               "boot, so nothing notices.\n"
               (str/join "\n" (map pr-str offenders)))))))

(deftest production-code-dynamically-resolves-no-unlisted-src-dev
  (testing "runtime resolution of src-dev namespaces is limited to guarded, listed ones"
    (let [dev-only (set (map #(path->ns "src-dev" %) (clj-files "src-dev")))
          offenders (for [[src-ns targets] (dynamic-edges-from production-roots)
                          :let [bad (->> targets
                                         (filter dev-only)
                                         (remove known-dynamic-src-dev-targets))]
                          :when (seq bad)]
                      {:from src-ns :to (vec (sort bad))})]
      (is (empty? offenders)
          (str "production code resolves src-dev namespaces at runtime that are "
               "not on the guarded list " (pr-str known-dynamic-src-dev-targets)
               ". These throw FileNotFoundException in a production build when "
               "the feature is used:\n"
               (str/join "\n" (map pr-str offenders)))))))

(deftest promoted-enrichment-path-reaches-no-harness
  (testing "the promoted enrichment graph pulls in no sweep/diagnostics namespace"
    (let [graph (require-graph production-roots)
          reached (reachable-from graph '[digdir.skills.enrichment.questions-graph])
          offenders (sort (filter harness-ns? reached))]
      (is (empty? offenders)
          (str "the promoted hypothetical-questions path must not reach the "
               "research harness — slice 1 of #82 exists to keep it out of the "
               "production artifact. Reached:\n"
               (str/join "\n" (map #(str "  " %) offenders))
               "\n\nIf an enrichment namespace now needs something from sweep/ "
               "or tools/diagnostics, extract the piece it needs rather than "
               "requiring the tree.")))))

(deftest promoted-eval-path-reaches-no-harness
  (testing "the promoted keep/revert gate pulls in no sweep/diagnostics namespace"
    ;; Slice 2b of #82. This is the namespace that USED to be the harness's
    ;; way in: it called sweep.runner/run-matrix and sweep.questions. If a
    ;; later change reaches for either again — the obvious move, since both
    ;; do more than the replacements — the production build inherits ~3,470
    ;; lines of research harness and a fixture under test/, and the failure
    ;; shows up as FileNotFoundException in a user's face rather than here.
    (let [graph (require-graph production-roots)
          reached (reachable-from graph '[digdir.skills.enrichment.eval-sweep])
          offenders (sort (filter harness-ns? reached))]
      (is (contains? graph 'digdir.skills.enrichment.eval-sweep)
          "digdir.skills.enrichment.eval-sweep is not in src/ — was slice 2b reverted?")
      (is (empty? offenders)
          (str "the enrichment keep/revert gate must not reach the research "
               "harness. Reached:\n"
               (str/join "\n" (map #(str "  " %) offenders))
               "\n\nRegression questions are DATA on this path: pass them as "
               ":regression-questions, or install a source into "
               "eval-sweep/regression-question-source from src-dev.")))))

(deftest production-build-reaches-no-new-harness-namespaces
  (testing "nothing new in the production build reaches the research harness"
    (let [graph (require-graph production-roots)
          reached (reachable-from graph '[prod])
          offenders (->> reached
                         (filter harness-ns?)
                         (remove known-production-harness-namespaces)
                         sort)]
      (is (empty? offenders)
          (str "these harness namespaces became reachable from the production "
               "entrypoint:\n"
               (str/join "\n" (map #(str "  " %) offenders))
               "\n\nThe known set is " (pr-str known-production-harness-namespaces)
               " and it is a ratchet: it may shrink, never grow. Either extract "
               "the piece the product needs, or make the case for widening it.")))))

(deftest harness-boundary-test-is-actually-looking-at-something
  (testing "guards against the check passing because it measured nothing"
    (let [graph (require-graph production-roots)
          from-prod (reachable-from graph '[prod])
          from-graph (reachable-from graph '[digdir.skills.enrichment.questions-graph])]
      (is (pos? (count (clj-files "src"))) "found no files under src/")
      (is (contains? graph 'prod)
          "the prod entrypoint is missing from the require graph")
      (is (contains? graph 'digdir.skills.enrichment.questions-graph)
          "the promoted enrichment graph is missing from src/ — did slice 1 get reverted?")
      ;; Both closures must be substantial; a helper that silently returns
      ;; nothing would make every assertion above vacuously true.
      (is (< 50 (count from-prod))
          (str "prod closure implausibly small: " (count from-prod)))
      (is (< 5 (count from-graph))
          (str "enrichment closure implausibly small: " (count from-graph)))
      ;; And the harness predicate must actually match the thing it names.
      (is (harness-ns? 'digdir.sweep.runner))
      (is (harness-ns? 'digdir.tools.diagnostics))
      (is (not (harness-ns? 'digdir.playground.diagnostics))
          "the UI diagnostics panel is product code, not harness")
      ;; The dynamic scanner is the half that was missing until slice 2a, so
      ;; prove it sees a real edge rather than trusting an empty result. If
      ;; dynamic-edges-from silently returned {}, both dynamic tests above
      ;; would pass vacuously — which is precisely the failure this whole
      ;; namespace exists to prevent.
      (let [edges (dynamic-edges-from production-roots)]
        (is (< 3 (count edges))
            (str "dynamic scanner found implausibly few source namespaces: "
                 (count edges)))
        (is (contains? (get edges 'digdir.agents.core) 'digdir.agents.dev)
            "the scanner cannot see a known requiring/resolve edge
             (digdir.agents.core -> digdir.agents.dev); it is not working")
        (is (contains? (get edges 'digdir.sweep.dashboard)
                       'digdir.skills.enrichment.eval-runner)
            "the scanner cannot see the dashboard's requiring-resolve of the
             promoted eval runner; it is not working")))))
