(ns digdir.docs.pipeline.write-sites-test
  "every place the product calls a Typesense write that answers PER ROW
   hands that answer to something that reads it.

   ⚠️ THIS IS A REACHABILITY CENSUS. It guarantees a write's answer, and each
   report built from it, REACHES CODE. It does NOT guarantee that a refusal is
   acted on, and it is not a guard against swallowed refusals: what the reading
   code does with the answer is semantics it cannot see. The one swallow in
   production today - the executor binds the loader task's value to `_` - is
   invisible to it (the value crosses a missionary flow; see `delegated-sites`).
   That is step 2 of the silent-success work, not this.

   ⚠️ FUNDAMENTAL LIMIT: A SHAPE-BASED CENSUS CANNOT TELL A DEFECT FROM CORRECT CODE
   OF THE SAME SHAPE. This is not a bug to be fixed by another rule, so the census
   bounds what an exception can clear instead (each bound pinned in
   `the-escape-hatches-refuse-what-they-must`):
   - A RAW per-row result has NO laundering route. Like a discard, it is judged
     before any exception is consulted: it goes to `storage/write-report`, or it
     is red. It cannot be excused and it cannot be delegated, so the phrases-reference tripwire defect
     in any shape the census sees as raw - `(let [resp (upsert!)] {:written
     (count resp)})` included - stays red whatever this file says.
   - The price is one over-fire with a one-line remedy: a raw result BOUND TO A
     LOCAL is judged at the binding, so a local that later reaches `write-report`
     is still flagged. Inline the call into `write-report`.
   - A NON-RAW site (a report, or a function that returns one) can be excused,
     and only by exact shape: a pin whose `:src` the census misjudges with the
     same verdict, context, consumer and raw-ness. Within that shape a defect is
     excusable like correct code, and that is a human claim, for a reviewer. No
     pin today reproduces a misjudgement that can be excused (each is raw or a
     discard), so no product site is excused.
   - A delegation must be a NON-RAW value crossing a flow (handed on, or returned
     from an anonymous fn) and name a deftest that exists. That the deftest pins
     the behaviour on the other side is not checked.

   A Typesense bulk import answers HTTP 200 with one result per row and does
   not throw when a row is refused. Before the phrases-reference tripwire every ingest write site dropped
   that answer. `digdir.docs.pipeline.write-report-test` pins what the writes now
   RETURN; this namespace pins that no NEW site drops it - the sixth site, the
   one nobody has written yet.

   HOW THE SET IS DERIVED, NOT LISTED
   - The per-row writes are read from the LIBRARY'S OWN SOURCE on the classpath:
     every `typesense.client` function that POSTs/PATCHes/PUTs/DELETEs and parses
     the response as JSON lines. A library upgrade that adds one is seen.
   - Two report constructors are the other seeds: `storage/write-report` and
     `storage/document-report`. A report dropped on the floor is the same defect
     one level up.
   - Producers are closed under TAIL POSITION: a function whose value IS a
     producer's value is itself a producer (a wrapper), to a fixpoint. A local
     that a `let` returns counts: `(let [r (p)] r)` is `(p)`, unless the body
     also read it and the form drops it. Tail position sees through `do`, `m/sp`,
     `m/via`, `m/?`, `deref` (with or without a timeout), `future`, `or`, `and`'s
     LAST argument, `doto`'s first, and the last argument of a log call that
     returns it (`t/trace!`, `t/spy!`, `t/error!`, `t/catch->error!`: measured,
     telemere 1.2.0), because each form's value IS one of those.
   - RAW is closed the same way. A wrapper of a raw per-row write is raw, and its
     callers are held to the raw rule. (Before this, the rule bit only at the
     library call, so `(count (bulk! ...))` over a one-line wrapper passed: the
     the phrases-reference tripwire shape one level up. Found in the review of the write-report change.)
   - Every use of a producer in `src` and `src-prod` is then judged by what
     happens to its value: returned (the enclosing function joins the set),
     discarded (a statement, a `_` binding, a binding never read), LOGGED (handed
     only to a log sink, directly, inside a literal, or through a local every use
     of which is a log: that line is for logs, the guard is the value),
     TRUTH-TESTED (a producer called straight into an `if`/`when`/`cond` test or
     a non-last `and` argument: a report or per-row vector is always truthy, so
     the test drops it), consumed (an argument, including a call `doto` makes on
     it; a binding that is read), or handed on (passed as a value, returned from
     an anonymous function).

   WHAT GOES RED
   - Any discard, including a value that is only logged or only truth-tested.
   - A raw per-row result, from the library or from a raw wrapper, consumed by
     anything but `storage/write-report`.
   - A producer handed on as a value, returned from an anonymous function, or
     used at top level.
   - Excepted, and nothing else is, for a NON-RAW site only: a site in
     `delegated-sites` (a value handed on or returned from an anonymous fn that
     crosses a missionary flow; it names a deftest, which must exist), or a site under
     `:excuses` of a `known-over-fires` pin whose `:src` reproduces THAT SITE'S
     misjudgement - same verdict, context, consumer and raw-ness, as the census judges
     the pin NOW. Filed under any other pin it is refused (laundering), and it
     expires the moment the census stops misjudging the pin's shape. Each exception
     must also name a real use that the census flags without it.
   - A :discarded site (statement, `_`, unread, logged, truth-tested) and a RAW
     site cannot be excused or delegated at all: both verdicts are decided before
     any exception is consulted. Raw goes to `storage/write-report`, full stop.
   - There is NO list of sites judged \"safe\". Clearing a correct site means
     pinning the census's mistake, in one edit, where it stays visible.

   HOW IT AVOIDS PASSING TRIVIALLY
   - Parsed, not matched: rewrite-clj, so comments, docstrings, `#_` and
     `(comment ...)` are not code. Aliases, `:refer`s, prefix lists, spliced
     reader conditionals and `:refer :all`/`:use` resolved per namespace.
   - Controls: planted sources, one per red rule, must come back red through the
     same scanner and rules; a planted correct consumption must come back green;
     and real sites must be found where they are known to be.
   - The review batteries (69 cases: 47 pre-registered before it read this census,
     11 post-read, 11 round-2), checked in byte-identical under
     `write_sites_battery/` and hash-verified, must be judged as their authors
     expected, except the listed `battery-deviations`, each pinned elsewhere.

   OVER-FIRING (a guard that flags correct code gets switched off)
   - Legitimate uses that also LOG, store or pass a value on are planted as GREEN
     controls (`legit/*`), one per transparency or logging rule, plus the 42 green
     cases of the review batteries.
   - The known over-fires are pinned RED in `known-over-fires`: a raw result bound
     to a local (judged at the binding; the remedy is one line, inline the call
     into `write-report`, and it cannot be excused. `apply_questions`, which checked
     `:success` itself, now reads its answer through `write-report`), a local named
     like a producer under `:refer :all`, and a
     LOCAL fn named `println` (lexical locals are not tracked; a namespace's own or
     `:refer-clojure` excluded one is).
   - A failing product census is WORDED as a discard even when the census is the
     one that is wrong. Real sites exercise only the shapes today's code uses, so
     they are a SECONDARY over-fire signal, not the alarm; the fixtures are. Its
     failure message tells a raw site to go to `write-report` (it prints no key:
     there is nothing to paste), and prints a non-raw site's key and where it goes
     if the census is the one that is wrong: under that pin's `:excuses`.
   - Measured when this was written, over `src`, `src-prod`, `src-dev` and `test`
     (this file excluded: its quoted seed sets flag themselves): each widening
     flags EXACTLY the sites the version before it flagged (31; none in
     `src`/`src-prod`). Two are `src-dev` discards, two are
     `src-dev` skills that bind the raw result and check it (the first known
     over-fire), and 27 are test files' redefs and fakes, which this census does
     not cover.

   WHAT THIS DOES NOT ESTABLISH (the shapes marked * are pinned GREEN in
   `known-holes`, so this list cannot go stale without a test saying so)
   - * That a consumer USES a report correctly. \"Read\" means the value reaches
     a call or a binding that is read; `(count report)` reads it. Whether code
     looks at `:rejected` is semantics. The behaviour is pinned by
     `write-report-test`. (A RAW result is held to the stricter rule.)
   - * A value passed through a second local (`(let [r (p) s r] s)`), or out
     through destructuring: only a local the `let` itself returns is followed.
   - * A log sink outside the enumerated `logging-sinks`, such as the pipeline's
     own `say`: a report handed only to it reads as consumed.
   - * Statement positions inside `reify`, `proxy`, `deftype`/`defrecord` and
     `extend-*` method bodies, and inside the expansion of a user macro: those
     bodies and arguments are read as consumed. None of them writes to Typesense
     today.
   - * A call through `resolve` of a computed symbol: never seen. Nor a direct
     HTTP request to Typesense's import endpoint. Neither exists in the product
     today (searched when this was written).
   - A value handed through a missionary flow. The store tasks' reports travel as
     flow elements and are folded by `storage/merge-write-reports`; the census
     sees only that the task function is handed to the flow, which is why those
     sites are delegated, each naming the test that pins its fold.
   - `src-dev`. It is not shipped. Measured when this was written: two sweep
     scripts discard the per-row result (`sweep/phrase_prune.clj`,
     `sweep/rechunk_migrate.clj`) and two enrichment skills read it.
   - (pinned in `the-escape-hatches-refuse-what-they-must`) Within a pin's
     misjudgement, a DEFECT is excusable exactly like correct code: the census
     cannot tell them apart, which is what an over-fire is. An excuse is a claim
     a human makes and a reviewer checks; the census only checks that the claim is
     about the right misjudgement.
   - (pinned there too) A delegation to an UNRELATED real deftest is accepted.
     Whether a test covers the behaviour is not mechanical.
   - Transparent forms beyond the list above (`identity`, a user's threading
     macro, ...) are read as consumers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; =============================================================================
;; Source -> forms, with line numbers
;; =============================================================================

(def ^:private skipped-tags #{:whitespace :newline :comma :comment :uneval})

(defn- code-children [node] (remove #(skipped-tags (n/tag %)) (n/children node)))

(defn- sexpr-of [node] (try (n/sexpr node) (catch Exception _ ::unreadable)))

(defn- with-line [x node]
  (if (instance? clojure.lang.IObj x) (with-meta x {:line (:row (meta node))}) x))

(defn- node->form
  "A rewrite-clj node as plain data, JVM branch of reader conditionals taken,
   each list and symbol carrying its `:line`. `::absent` marks what is not code
   here (an unreadable token, a reader conditional with no JVM branch), so it
   can be dropped WITHOUT dropping a literal `nil`: `(defn g [] (w) nil)`
   returns nil, not `(w)`, and reading it the other way hides the discard."
  [node]
  (let [kids (when (n/inner? node) (code-children node))
        forms (fn [] (remove #(= ::absent %) (map node->form kids)))
        one (fn [k] (if k (node->form k) ::absent))]
    (case (n/tag node)
      :list (with-line (apply list (forms)) node)
      ;; #(...) is a function whose body is the call
      :fn (with-line (list 'fn [] (with-line (apply list (forms)) node)) node)
      :vector (with-line (vec (forms)) node)
      :map (let [xs (forms)]
             (with-line (if (even? (count xs)) (apply array-map xs) (vec xs)) node))
      (:set :namespaced-map) (with-line (vec (forms)) node)
      :reader-macro (let [[marker body] kids]
                      (if (and body (#{"?" "?@"} (n/string marker)) (even? (count (code-children body))))
                        (let [branches (partition 2 (code-children body))
                              pick (fn [k] (some (fn [[kn v]] (when (= k (sexpr-of kn)) v)) branches))]
                          (one (or (pick :clj) (pick :default))))
                        ::absent))
      (:quote :syntax-quote) (list 'quote (one (last kids)))
      :var (list 'var (one (last kids)))
      :deref (with-line (list 'deref (one (last kids))) node)
      :meta (one (last kids))
      (:token :multi-line) (let [v (sexpr-of node)] (if (= ::unreadable v) ::absent (with-line v node)))
      ::absent)))

(defn- source-forms [s]
  (vec (remove #(= ::absent %) (map node->form (code-children (p/parse-string-all s))))))

;; =============================================================================
;; The library: which typesense.client functions answer per row
;; =============================================================================

(defn- library-per-row-writes
  "Every `typesense.client` defn that writes and parses its response as JSON
   lines, read from the library's own source."
  []
  (set (for [f (source-forms (slurp (io/resource "typesense/client.clj")))
             :when (and (seq? f) (= 'defn (first f)))
             :let [syms (set (filter symbol? (tree-seq coll? seq f)))]
             :when (and (syms 'util/http-response-jsonline->maps)
                        (some syms '[http/post http/patch http/put http/delete]))]
         (symbol "typesense.client" (name (second f))))))

(def ^:private report-constructors
  '#{digdir.docs.pipeline.storage/write-report digdir.docs.pipeline.storage/document-report})

;; =============================================================================
;; Resolution
;; =============================================================================

(defn- require-specs
  "Every `[lib & opts]` in a `(:require ...)` or `(:use ...)` clause, as
   `[lib opts-map clause]`. A prefix list `(prefix [suffix & opts] ...)` names
   `prefix.suffix`; a spliced reader conditional arrives as a vector of specs."
  [nsf]
  (for [clause (drop 2 nsf)
        :when (and (seq? clause) (#{:require :use} (first clause)))
        entry (rest clause)
        spec (cond
               (and (vector? entry) (vector? (first entry))) entry
               (vector? entry) [entry]
               (symbol? entry) [[entry]]
               (seq? entry) (let [[prefix & subs] entry]
                              (for [s subs]
                                (if (vector? s)
                                  (into [(symbol (str prefix "." (first s)))] (rest s))
                                  [(symbol (str prefix "." s))])))
               :else [])
        :when (and (vector? spec) (symbol? (first spec)))]
    (let [[lib & kv] spec]
      [lib (apply hash-map (take (* 2 (quot (count kv) 2)) kv)) (first clause)])))

(defn- ns-info [forms]
  (let [nsf (first (filter #(and (seq? %) (= 'ns (first %))) forms))
        specs (require-specs nsf)]
    {:ns (second nsf)
     :aliases (into {} (for [[lib m] specs a [(:as m) (:as-alias m)] :when a] [a lib]))
     :refers (into {} (for [[lib m] specs r (when (sequential? (:refer m)) (:refer m))]
                        [r (symbol (str lib) (name r))]))
     ;; `:refer :all`, or `:use` without `:only`: any unqualified name may come from it
     :refer-all (vec (for [[lib m clause] specs
                           :when (or (= :all (:refer m)) (and (= :use clause) (not (:only m))))]
                       lib))
     :defs (set (for [x forms :when (and (seq? x) (#{'defn 'defn- 'def 'defmacro} (first x)))] (second x)))
     :excluded (set (for [clause (drop 2 nsf)
                          :when (and (seq? clause) (= :refer-clojure (first clause)))
                          [k v] (partition 2 (rest clause))
                          :when (= :exclude k)
                          x v]
                      x))}))

(defn- resolve-syms
  "Every var `s` may name here. More than one only through `:refer :all`, where
   the census cannot know which lib defines the name, so it records each."
  [{:keys [ns aliases refers refer-all defs]} s]
  (cond
    (not (symbol? s)) []
    (namespace s) [(symbol (str (get aliases (symbol (namespace s)) (namespace s))) (name s))]
    (refers s) [(refers s)]
    (defs s) [(symbol (str ns) (name s))]
    :else (mapv #(symbol (str %) (name s)) refer-all)))


;; =============================================================================
;; What happens to each value
;; =============================================================================

(declare visit)

(def ^:private logging-sinks
  "Calls whose arguments go to a log line and whose VALUE is not the argument: a value
   handed only to one of these is DISCARDED (\"that line is for logs; the guard is the
   value\"). Measured on telemere 1.2.0: `event!` and `log!` return nil. `signal!` is
   in neither set: with `:run` it returns the run's value (a consumer, the safe way)."
  (into '#{clojure.core/println clojure.core/prn clojure.core/print clojure.core/pr clojure.core/printf
           taoensso.telemere/event! taoensso.telemere/log!}
        (for [lib '[taoensso.timbre clojure.tools.logging]
              f '[log logf error warn info debug trace errorf warnf infof debugf tracef]]
          (symbol (str lib) (str f)))))

(def ^:private value-logging-fns
  "Log calls that RETURN their last argument (measured, telemere 1.2.0): `(t/trace! x)`
   IS `x`. Their last argument takes the call's context; anything before it (an id,
   an opts map) only reaches the log. A LONE map literal is read as opts and the call
   returns nil/true, so then it is a sink (measured)."
  '#{taoensso.telemere/error! taoensso.telemere/trace! taoensso.telemere/spy!
     taoensso.telemere/catch->error!})

(defn- core-name
  "`clojure.core/<h>` for an unqualified head the namespace does not define or exclude."
  [info h]
  (when (and (symbol? h) (nil? (namespace h))
             (not ((:defs info) h)) (not (contains? (:excluded info) h)))
    (symbol "clojure.core" (name h))))

(defn- sink? [info h]
  (boolean (or (logging-sinks (first (resolve-syms info h))) (logging-sinks (core-name info h)))))

(defn- value-logging? [info h] (contains? value-logging-fns (first (resolve-syms info h))))

(defn- opts-only? [args] (and (= 1 (count args)) (map? (first args))))

(defn- local-uses
  "Every place `sym` is used in `forms`: true where it only reaches a log (a sink's
   argument, or a value-returning log call's id/opts), false where it is read."
  [info sym forms]
  (let [out (atom [])]
    (letfn [(walk [f logged?]
              (cond
                (= f sym) (swap! out conj logged?)
                (seq? f) (let [[h & args] f]
                           (cond
                             (= 'quote h) nil
                             (or (sink? info h) (and (value-logging? info h) (opts-only? args)))
                             (doseq [a args] (walk a true))
                             (value-logging? info h) (do (doseq [a (butlast args)] (walk a true))
                                                         (walk (last args) logged?))
                             :else (do (walk h logged?) (doseq [a args] (walk a logged?)))))
                (coll? f) (doseq [x f] (walk x logged?))))]
      (doseq [f forms] (walk f false)))
    @out))


(defn- used-later? [sym forms] (some #(= sym %) (tree-seq coll? seq forms)))

(defn- visit-body [info forms ctx out]
  (let [forms (vec forms)]
    (doseq [[i f] (map-indexed vector forms)]
      (visit info f (if (= i (dec (count forms))) ctx {:kind :statement}) out))))

(defn- tail-symbols
  "The locals `form` can return as its value."
  [form]
  (cond
    (symbol? form) #{form}
    (not (seq? form)) #{}
    :else (let [[h & args] form
                clause? #(and (seq? %) (#{'catch 'finally} (first %)))]
            (case (str h)
              ("do" "let" "loop" "binding" "with-open" "when-let" "when-some" "when" "when-not" "when-first"
               "locking" "m/sp" "m/ap" "m/via" "m/?" "future") (tail-symbols (last args))
              "deref" (set (mapcat tail-symbols args))
              "and" (tail-symbols (last args))
              "doto" (tail-symbols (first args))
              ("if" "if-not" "if-let" "if-some") (into (tail-symbols (second args)) (tail-symbols (nth args 2 nil)))
              "or" (set (mapcat tail-symbols args))
              "cond" (set (mapcat tail-symbols (take-nth 2 (rest args))))
              "try" (tail-symbols (last (remove clause? args)))
              #{}))))

(defn- visit-bindings
  "A binding whose local is RETURNED takes the context of the form that returns it,
   so `(let [r (bulk!)] r)` makes the function a wrapper of `bulk!` exactly as
   `(bulk!)` would - unless the body also READ it and the form drops it, when it was
   used (`(let [r (w)] (check! r) r)` inside a `doseq`). Otherwise a binding's value
   is DISCARDED when its target is `_`, is never read after it, or reaches only log
   calls: `(let [r (w)] (t/event! :x {:data r}) :done)` logs the report and drops it."
  [info bv body returned ctx out]
  (let [pairs (vec (partition 2 bv))]
    (doseq [[i [target v]] (map-indexed vector pairs)]
      (let [after (concat (map second (subvec pairs (inc i))) body)
            uses (when (and (symbol? target) (not= '_ target)) (local-uses info target after))]
        (visit info v (cond
                        (and (symbol? target) (not= '_ target) (returned target))
                        (if (and (#{:statement :logged :truth-tested} (:kind ctx)) (> (count (filter false? uses)) 1))
                          {:kind :bound}
                          ctx)
                        (and (symbol? target) (or (= '_ target) (not (used-later? target after)))) {:kind :statement}
                        (and (seq uses) (every? true? uses)) {:kind :logged :by :local}
                        :else {:kind :bound})
               out)))))

(defn- visit [info f ctx out]
  (cond
    (symbol? f)
    (doseq [r (resolve-syms info f)]
      (swap! out conj {:var r :line (:line (meta f)) :ctx {:kind :handed-on}}))

    (seq? f)
    (let [[h & args] f
          hs (str h)
          rs (resolve-syms info h)
          r (first rs)]
      (doseq [r rs] (swap! out conj {:var r :line (:line (meta f)) :ctx ctx}))
      (cond
        (#{"comment"} hs) nil
        (#{"quote" "var"} hs) (let [x (first args)]
                                (doseq [s (filter symbol? (tree-seq coll? seq x))]
                                  (visit info s ctx out)))
        (= "letfn" hs) (let [[specs & body] args]
                         (doseq [spec specs :when (seq? spec)] (visit info (cons 'fn (rest spec)) ctx out))
                         (visit-body info body ctx out))
        (= "defmethod" hs) (visit info (cons 'fn (drop 2 args)) ctx out)
        (#{"let" "loop" "binding" "with-open" "when-let" "when-some" "if-let" "if-some"} hs)
        (let [[bv & body] args
              returned (if (#{"if-let" "if-some"} hs)
                         (set (mapcat tail-symbols body))
                         (tail-symbols (last body)))]
          (when (vector? bv) (visit-bindings info bv body returned ctx out))
          (if (#{"if-let" "if-some"} hs)
            (doseq [b body] (visit info b ctx out))
            (visit-body info body ctx out)))
        ;; A test only asks whether the value is truthy, and a report or a per-row
        ;; vector always is: a producer called straight into a test is dropped.
        (#{"when" "when-not" "locking"} hs)
        (do (visit info (first args) (if (= "locking" hs) {:kind :consumed :by h} {:kind :truth-tested :by h}) out)
            (visit-body info (rest args) ctx out))
        (#{"if" "if-not"} hs)
        (do (visit info (first args) {:kind :truth-tested :by h} out) (doseq [b (rest args)] (visit info b ctx out)))
        ;; Value-transparent: the form's value IS its last body form's.
        (#{"do" "m/sp" "m/ap" "m/?" "future"} hs) (visit-body info args ctx out)
        ;; `(deref x ms timeout-val)`: the value is x's, or the timeout value.
        (= "deref" hs) (doseq [a args] (visit info a ctx out))
        ;; Its value may be ANY argument's (a report is truthy, so the first wins).
        (= "or" hs) (doseq [a args] (visit info a ctx out))
        ;; Its value is its LAST argument; the others are only truth-tested.
        (= "and" hs) (do (doseq [a (butlast args)] (visit info a {:kind :truth-tested :by h} out))
                         (when (seq args) (visit info (last args) ctx out)))
        ;; Its value is its FIRST argument; the rest are calls made ON it, so the
        ;; value is read unless every one of them only logs it.
        (= "doto" hs) (let [[x & calls] args
                            heads (map #(if (seq? %) (first %) %) calls)
                            reader (first (remove #(sink? info %) heads))]
                        (visit info x (cond
                                        (not (#{:statement :logged :truth-tested} (:kind ctx))) ctx
                                        (empty? calls) ctx
                                        reader {:kind :consumed :by reader}
                                        :else {:kind :logged :by (first heads)})
                               out)
                        (doseq [a calls] (visit info a {:kind :statement} out)))
        (#{"m/via"} hs) (visit-body info (rest args) ctx out)
        (= "try" hs)
        (let [clause? #(and (seq? %) (#{'catch 'finally} (first %)))]
          (visit-body info (remove clause? args) ctx out)
          (doseq [c (filter clause? args)]
            (if (= 'catch (first c))
              (visit-body info (drop 3 c) ctx out)
              (visit-body info (rest c) {:kind :statement} out))))
        (= "cond" hs) (doseq [[t v] (partition 2 args)]
                        (visit info t {:kind :truth-tested :by h} out) (visit info v ctx out))
        (= "case" hs) (let [[x & cl] args]
                        (visit info x {:kind :consumed :by h} out)
                        (doseq [[_ v] (partition 2 cl)] (visit info v ctx out))
                        (when (odd? (count cl)) (visit info (last cl) ctx out)))
        (#{"doseq" "dotimes"} hs) (let [[bv & body] args]
                                    (when (vector? bv) (doseq [x bv] (visit info x {:kind :consumed :by h} out)))
                                    (visit-body info body {:kind :statement} out))
        (#{"fn" "fn*"} hs) (let [xs (remove symbol? args)
                                 arities (if (vector? (first xs)) [xs] (filter seq? xs))]
                             (doseq [[_ & body] arities] (visit-body info body {:kind :anonymous-fn-return} out)))
        (#{"->" "->>" "some->" "some->>" "cond->" "cond->>" "as->"} hs)
        (do (doseq [a (butlast args)] (visit info a {:kind :consumed :by h} out))
            (visit info (last args) ctx out))
        :else (do
                ;; A head that is itself a form - `((resolve 'x) arg)`,
                ;; `((store-doc s kview) doc)` - is code too, and its value is
                ;; consumed by being called. Skipping it hid everything inside.
                (when-not (symbol? h) (visit info h {:kind :consumed :by :call-head} out))
                (cond
                  (or (sink? info h) (and (value-logging? info h) (opts-only? args)))
                  (doseq [a args] (visit info a {:kind :logged :by (or r (core-name info h))} out))
                  (value-logging? info h)
                  (do (doseq [a (butlast args)] (visit info a {:kind :logged :by r} out))
                      (visit info (last args) ctx out))
                  :else
                  (doseq [a args] (visit info a {:kind :consumed :by (or r h)} out))))))

    ;; A literal shares the fate of the place it stands in when that place drops
    ;; or only logs it; anywhere else its elements are read as the literal's.
    (coll? f) (let [c (if (#{:statement :logged :truth-tested} (:kind ctx)) ctx {:kind :consumed :by :literal})]
                (doseq [x f] (visit info x c out)))))

(defn- def-arities [f]
  (let [xs (drop-while #(or (string? %) (map? %)) (drop 2 f))]
    (if (vector? (first xs)) [xs] (filter seq? xs))))

(defn- scan
  "Every resolved symbol use in one source, with what happens to its value and the
   top-level definition it sits in."
  [file src]
  (let [forms (source-forms src)
        info (ns-info forms)
        out (atom [])]
    (doseq [x forms :when (and (seq? x) (not= 'comment (first x)) (not= 'ns (first x)))]
      (let [before (count @out)
            def-name (when (#{'defn 'defn- 'def 'defmacro 'defmethod} (first x)) (second x))]
        (if (#{'defn 'defn-} (first x))
          (doseq [[_ & body] (def-arities x)]
            (visit-body info body {:kind :returned :by (symbol (str (:ns info)) (name (second x)))} out))
          (visit info x {:kind :top-level} out))
        (swap! out (fn [v] (into (subvec v 0 before)
                                 (map #(assoc % :file file :def def-name) (subvec v before)))))))
    @out))

;; =============================================================================
;; The census
;; =============================================================================

(defn- census
  "Producers closed under tail position, and every use of one.

   RAW is closed the same way: a function whose value IS a raw per-row result is
   itself raw, so its callers are held to the raw rule too. Without that, the rule
   bit only at the library call, and `(count (bulk! ...))` over a one-line wrapper
   read as a use: the phrases-reference tripwire shape, one level up (found in review verifying the write-report change)."
  [sources seeds raw-seeds]
  (let [uses (vec (mapcat (fn [[file src]] (scan file src)) sources))
        returning (fn [hits] (set (keep #(when (= :returned (get-in % [:ctx :kind])) (get-in % [:ctx :by])) hits)))]
    (loop [producers seeds raw raw-seeds]
      (let [hits (filter #(producers (:var %)) uses)
            grown (into producers (returning hits))
            grown-raw (into raw (returning (filter #(raw (:var %)) hits)))]
        (if (and (= grown producers) (= grown-raw raw))
          {:producers producers :raw raw :hits (vec (sort-by (juxt :file :line) hits))}
          (recur grown grown-raw))))))

(def ^:private delegated-sites
  "Uses whose value crosses a boundary the census cannot follow - a missionary
   flow - so the census DELEGATES them to the behaviour test that pins what happens
   on the other side. Keyed by [file enclosing-definition producer kind]. Each entry
   must be a NON-RAW value crossing a flow (`:handed-on` or `:anonymous-fn-return`),
   must name a test that exists, and must match a use the census would otherwise
   flag. A raw per-row result cannot be delegated: it goes to `storage/write-report`.

   There is deliberately NO list of sites judged \"safe\". Correct code the census
   flags is a census OVER-FIRE: it goes under `:excuses` of the `known-over-fires`
   pin that reproduces it, where it is recorded as the census being wrong and stays
   excused only while the census still is."
  {["src/digdir/docs/loader.clj" 'store-doc 'digdir.docs.pipeline.storage/document-report :anonymous-fn-return]
   {:pinned-by 'digdir.docs.pipeline.write-report-test/the-kudos-typesense-store-returns-what-it-wrote
    :why "the kudos store functions: `mk-store-document-in-store-t` calls them, and their value is a store-flow element folded by `storage/merge-write-reports` (also: a-materialization-returns-what-the-run-wrote, kudos)"}

   ["src/digdir/docs/folder.clj" 'mk-store-documents-f 'digdir.docs.folder/mk-store-document-t :handed-on]
   {:pinned-by 'digdir.docs.pipeline.write-report-test/a-materialization-returns-what-the-run-wrote
    :why "the store task handed to the store flow; its reports are folded in `mk-materialize-t` (label: folder)"}

   ["src/digdir/docs/website.clj" 'mk-store-documents-f 'digdir.docs.website/mk-store-document-t :handed-on]
   {:pinned-by 'digdir.docs.pipeline.write-report-test/a-materialization-returns-what-the-run-wrote
    :why "the store task handed to the store flow; folded in `mk-materialize-t` (label: website)"}

   ["src/digdir/docs/episerver.clj" 'mk-store-documents-f 'digdir.docs.episerver/mk-store-document-t :handed-on]
   {:pinned-by 'digdir.docs.pipeline.write-report-test/a-materialization-returns-what-the-run-wrote
    :why "the store task handed to the store flow; folded in `mk-materialize-t` (label: episerver)"}

   ["src/digdir/docs/pipeline/protocol.clj" 'mk-materialize-t 'digdir.docs.pipeline.protocol/mk-store-document-t :anonymous-fn-return]
   {:pinned-by 'digdir.docs.pipeline.write-report-test/the-shared-orchestration-folds-what-its-store-step-returns
    :why "the store task handed to `orchestration/mk-materialize-t`, which folds it"}})

(defn- site-key [{:keys [file def var ctx]}] [file def var (:kind ctx)])

(defn- verdicts
  "Each use of a producer, judged. Raw means the census's raw set: the library's
   per-row writes and every function that returns one's value."
  [{:keys [hits raw]} judged]
  (for [h hits
        :let [kind (get-in h [:ctx :kind])
              by (get-in h [:ctx :by])
              raw? (contains? raw (:var h))
              judged? (contains? judged (site-key h))]]
    (assoc h :raw? (boolean raw?) :verdict
           (cond
             (#{:statement :logged :truth-tested} kind) :discarded
             (= :returned kind) :ok
             ;; Like a discard, a RAW per-row result is judged BEFORE any exception is
             ;; consulted: it goes to `write-report`, or it is flagged. Nothing excuses it.
             (and raw? (= :consumed kind) (= 'digdir.docs.pipeline.storage/write-report by)) :ok
             raw? :unjudged
             judged? :ok
             (#{:consumed :bound} kind) :ok
             :else :unjudged))))

(defn- label [{:keys [file line var ctx]}]
  (str file ":" line " " var " " (name (:kind ctx)) (when-let [b (:by ctx)] (str " by " b))))

(defn- product-sources []
  (for [root ["src" "src-prod"]
        f (file-seq (io/file root))
        :when (and (.isFile f) (re-find #"\.clj[c]?$" (.getName f)))]
    [(str f) (slurp f)]))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest the-library-answers-per-row-where-it-says-it-does
  (let [per-row (library-per-row-writes)]
    (is (contains? per-row 'typesense.client/upsert-documents!)
        "non-vacuity: the bulk upsert every ingest site uses must be recognised")
    (is (not (contains? per-row 'typesense.client/upsert-document!))
        "the single-document upsert throws on a refusal; it is not a per-row write")
    (is (not (contains? per-row 'typesense.client/export-documents))
        "export parses JSON lines too, but it is a READ")))

(def ^:private planted
  "One planted source per red rule, plus correct uses that must stay green."
  {"planted/discard.clj"
   "(ns planted.discard (:require [typesense.client :as tsx]))
    (defn f [s] (tsx/upsert-documents! s \"c\" []) :done)"
   "planted/underscore.clj"
   "(ns planted.underscore (:require [typesense.client :refer [update-documents!]]))
    (defn f [s] (let [_ (update-documents! s \"c\" [])] 1))"
   "planted/unread.clj"
   "(ns planted.unread (:require [typesense.client :as ts]))
    (defn f [s] (let [r (ts/create-documents! s \"c\" [])] 1))"
   "planted/wrapper.clj"
   "(ns planted.wrapper (:require [typesense.client :as ts]))
    (defn w [s] (when s (ts/upsert-documents! s \"c\" [])))
    (defn g [s] (w s) nil)"
   "planted/counted.clj"
   "(ns planted.counted (:require [typesense.client :as ts]))
    (defn f [s] (count (ts/upsert-documents! s \"c\" [])))"
   "planted/anonymous.clj"
   "(ns planted.anonymous (:require [typesense.client :as ts]))
    (defn f [s batches] (run! #(ts/upsert-documents! s \"c\" %) batches))"
   "planted/head.clj"
   "(ns planted.head (:require [typesense.client :as ts]))
    (defn f [s] ((identity ts/upsert-documents!) s \"c\" []) :x)"
   "planted/report-dropped.clj"
   "(ns planted.report-dropped (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (storage/store-chunks! cfg \"c\" []) :stored)"
;; found in review verifying the write-report change: shapes the first census missed or never saw.
   ;; The first two are fresh RED controls it used to prove its harness could fire.
   "battery/fresh-red.clj"
   "(ns battery.fresh-red (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (try (storage/upsert-row! cfg \"c\" {}) (finally (println 1))) :x)"
   "battery/fresh-red-raw.clj"
   "(ns battery.fresh-red-raw (:require [typesense.client :as ts]))
    (defn f [s rows] (seq (ts/update-documents! s \"c\" rows)))"
   "battery/raw-wrapper-counted.clj"
   "(ns battery.raw-wrapper-counted (:require [typesense.client :as ts]))
    (defn bulk! [s rows] (ts/upsert-documents! s \"c\" rows))
    (defn g [s rows] (count (bulk! s rows)))"
   "battery/raw-let-wrapper-counted.clj"
   "(ns battery.raw-let-wrapper-counted (:require [typesense.client :as ts]))
    (defn bulk! [s rows] (let [r (ts/upsert-documents! s \"c\" rows)] r))
    (defn g [s rows] (count (bulk! s rows)))"
   "battery/raw-wrapper-logged.clj"
   "(ns battery.raw-wrapper-logged (:require [typesense.client :as ts] [taoensso.telemere :as t]))
    (defn bulk! [s rows] (ts/upsert-documents! s \"c\" rows))
    (defn g [s rows] (t/event! :x {:data {:resp (bulk! s rows)}}) :done)"
   "battery/report-into-event.clj"
   "(ns battery.report-into-event (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (t/event! :x {:data (storage/store-chunks! cfg \"c\" [])}) :stored)"
   "battery/report-and.clj"
   "(ns battery.report-and (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg ok] (and ok (storage/store-chunks! cfg \"c\" [])) :x)"
   "battery/report-mq.clj"
   "(ns battery.report-mq (:require [digdir.docs.pipeline.storage :as storage] [missionary.core :as m]))
    (defn f [cfg] (m/? (m/via m/blk (storage/store-chunks! cfg \"c\" []))) :x)"
   "battery/let-wrapper.clj"
   "(ns battery.let-wrapper (:require [digdir.docs.pipeline.storage :as storage]))
    (defn w2 [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] r))
    (defn g [cfg] (w2 cfg) :x)"
   "battery/prefix.clj"
   "(ns battery.prefix (:require (typesense [client :as ts])))
    (defn f [s] (ts/upsert-documents! s \"c\" []) :x)"
   "battery/refer-all.clj"
   "(ns battery.refer-all (:require [typesense.client :refer :all]))
    (defn f [s] (upsert-documents! s \"c\" []) :x)"
   "battery/doto.clj"
   "(ns battery.doto (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (doto (storage/store-chunks! cfg \"c\" []) prn) :x)"
   "battery/future.clj"
   "(ns battery.future (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (future (storage/store-complete-document! cfg {} identity identity)) :x)"
;; LEGITIMATE uses that also LOG, store, or pass the value on. The over-firing
   ;; axis: each must stay GREEN. A guard that flags correct code gets switched off.
   "legit/log-and-return.clj"
   "(ns legit.log-and-return (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (t/event! :x {:data r}) r))"
   "legit/log-and-read.clj"
   "(ns legit.log-and-read (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (t/event! :x {:data {:n (:written r)}}) (when (seq (:rejected r)) (throw (ex-info \"refused\" r))) :ok))"
   "legit/spy.clj"
   "(ns legit.spy (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (t/spy! (storage/store-chunks! cfg \"c\" [])))"
   "legit/stored-in-atom.clj"
   "(ns legit.stored-in-atom (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg a] (swap! a conj (storage/store-chunks! cfg \"c\" [])) :x)"
   "legit/raw-wrapper-to-write-report.clj"
   "(ns legit.raw-wrapper-to-write-report (:require [typesense.client :as ts] [digdir.docs.pipeline.storage :as storage]))
    (defn bulk! [s rows] (ts/upsert-documents! s \"c\" rows))
    (defn g [s rows] (storage/write-report \"c\" rows (bulk! s rows)))"
   "legit/and-returned.clj"
   "(ns legit.and-returned (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg ok] (and ok (storage/store-chunks! cfg \"c\" [])))"
   "legit/future-deref-read.clj"
   "(ns legit.future-deref-read (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (:written @(future (storage/store-chunks! cfg \"c\" []))))"
;; Round 3 (the second review): one GREEN and one RED control per rule.
   "legit/trace-returned.clj"
   "(ns legit.trace-returned (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (t/trace! (storage/store-chunks! cfg \"c\" [])))"
   "legit/deref-timeout-read.clj"
   "(ns legit.deref-timeout-read (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (:written (deref (future (storage/store-chunks! cfg \"c\" [])) 60000 nil)))"
   "legit/ns-own-print.clj"
   "(ns legit.ns-own-print (:refer-clojure :exclude [print]) (:require [digdir.docs.pipeline.storage :as storage]))
    (def reports (atom []))
    (defn print [r] (swap! reports conj r))
    (defn f [cfg] (print (storage/store-chunks! cfg \"c\" [])) :x)"
   "legit/doto-validate.clj"
   "(ns legit.doto-validate (:require [digdir.docs.pipeline.storage :as storage]))
    (defn check! [r] (when (seq (:rejected r)) (throw (ex-info \"refused\" r))))
    (defn f [cfg] (doto (storage/store-chunks! cfg \"c\" []) check!) :x)"
   "legit/check-then-return-in-doseq.clj"
   "(ns legit.check-then-return-in-doseq (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg bs] (doseq [b bs] (let [r (storage/store-chunks! cfg \"c\" b)] (when (seq (:rejected r)) (throw (ex-info \"refused\" r))) r)) :done)"
   "legit/or-returned.clj"
   "(ns legit.or-returned (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (or (storage/store-chunks! cfg \"c\" []) (storage/write-report \"c\" [] [])))"
   "legit/error-with-exception-returned.clj"
   "(ns legit.error-with-exception-returned (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg e] (let [r (storage/store-chunks! cfg \"c\" [])] (t/error! {:id :x :data r} e) r))"
   "planted/logged-via-local.clj"
   "(ns planted.logged-via-local (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (t/event! :x {:data r}) :done))"
   "planted/printed-via-local.clj"
   "(ns planted.printed-via-local (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (println r) :done))"
   "planted/and-non-last.clj"
   "(ns planted.and-non-last (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (and (storage/store-chunks! cfg \"c\" []) :stored))"
   "planted/truth-tested.clj"
   "(ns planted.truth-tested (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (when (storage/store-chunks! cfg \"c\" []) :stored))"
   "planted/spy-statement.clj"
   "(ns planted.spy-statement (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (t/spy! (storage/store-chunks! cfg \"c\" [])) :x)"
   "planted/error-opts-only.clj"
   "(ns planted.error-opts-only (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg e] (t/error! {:id :x :data (storage/store-chunks! cfg \"c\" [])} e) :x)"
   "planted/error-lone-map-returned.clj"
   "(ns planted.error-lone-map-returned (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (t/error! {:id :x :data (storage/store-chunks! cfg \"c\" [])}))"
   "planted/correct.clj"
   "(ns planted.correct (:require [typesense.client :as ts] [digdir.docs.pipeline.storage :as storage]))
    (defn f [s rows] (storage/write-report \"c\" rows (ts/upsert-documents! s \"c\" rows)))
    (defn g [s rows] (let [r (f s rows)] (:written r)))"})

(deftest planted-uses-are-judged-as-they-must-be
  ;; The control. Run through the SAME census and rules as the product, seeded
  ;; with the product's storage functions so a planted report drop can be seen.
  (let [per-row (library-per-row-writes)
        seeds (into (into per-row report-constructors)
                    '#{digdir.docs.pipeline.storage/store-chunks!
                       digdir.docs.pipeline.storage/upsert-row!
                       digdir.docs.pipeline.storage/store-complete-document!})
        vs (verdicts (census planted seeds per-row) {})
        by-file (group-by :file vs)
        bad (fn [f] (set (keep #(when (not= :ok (:verdict %)) (:verdict %)) (by-file f))))]
    (doseq [[f expected] {"planted/discard.clj" #{:discarded}
                          "planted/underscore.clj" #{:discarded}
                          "planted/unread.clj" #{:discarded}
                          "planted/wrapper.clj" #{:discarded}
                          "planted/counted.clj" #{:unjudged}
                          "planted/anonymous.clj" #{:unjudged}
                          "planted/head.clj" #{:unjudged}
                          "planted/report-dropped.clj" #{:discarded}
                          "battery/fresh-red.clj" #{:discarded}
                          "battery/fresh-red-raw.clj" #{:unjudged}
                          "battery/raw-wrapper-counted.clj" #{:unjudged}
                          "battery/raw-let-wrapper-counted.clj" #{:unjudged}
                          "battery/raw-wrapper-logged.clj" #{:discarded}
                          "battery/report-into-event.clj" #{:discarded}
                          "battery/report-and.clj" #{:discarded}
                          "battery/report-mq.clj" #{:discarded}
                          "battery/let-wrapper.clj" #{:discarded}
                          "battery/prefix.clj" #{:discarded}
                          "battery/refer-all.clj" #{:discarded}
                          "battery/doto.clj" #{:discarded}
                          "battery/future.clj" #{:discarded}
                          "legit/log-and-return.clj" #{}
                          "legit/log-and-read.clj" #{}
                          "legit/spy.clj" #{}
                          "legit/stored-in-atom.clj" #{}
                          "legit/raw-wrapper-to-write-report.clj" #{}
                          "legit/and-returned.clj" #{}
                          "legit/future-deref-read.clj" #{}
                          "legit/trace-returned.clj" #{}
                          "legit/deref-timeout-read.clj" #{}
                          "legit/ns-own-print.clj" #{}
                          "legit/doto-validate.clj" #{}
                          "legit/check-then-return-in-doseq.clj" #{}
                          "legit/or-returned.clj" #{}
                          "legit/error-with-exception-returned.clj" #{}
                          "planted/logged-via-local.clj" #{:discarded}
                          "planted/printed-via-local.clj" #{:discarded}
                          "planted/and-non-last.clj" #{:discarded}
                          "planted/truth-tested.clj" #{:discarded}
                          "planted/spy-statement.clj" #{:discarded}
                          "planted/error-opts-only.clj" #{:discarded}
                          "planted/error-lone-map-returned.clj" #{:discarded}
                          "planted/correct.clj" #{}}]
      (testing f
        (is (seq (by-file f)) "PREMISE: the census saw a use in this file")
        (is (= expected (bad f)) (str/join "\n" (map label (by-file f))))))))

(def ^:private known-holes
  "Shapes the census does NOT catch, each with the limitation it demonstrates.
   PINNED GREEN on purpose, the way the KUDOS phrase-parser unification pins current behaviour: they are the
   docstring's starred list, measured instead of asserted. If a change to the
   census starts catching one, `known-holes-stay-uncaught` goes red, and the fix is
   to move the shape into `planted` and delete its line from the docstring."
  {
   "hole/report-counted.clj"
   ["consumer semantics: `(count report)` reads it"
    "(ns hole.report-counted (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (count (storage/store-chunks! cfg \"c\" [])))" true]
   "hole/second-local.clj"
   ["a value passed through a second local"
    "(ns hole.second-local (:require [digdir.docs.pipeline.storage :as storage]))
    (defn w [cfg] (let [r (storage/store-chunks! cfg \"c\" []) s r] s))
    (defn g [cfg] (w cfg) :x)" true]
   "hole/custom-log-sink.clj"
   ["a log sink outside `logging-sinks`"
    "(ns hole.custom-log-sink (:require [digdir.docs.pipeline.storage :as storage] [digdir.docs.pipeline.core :as core]))
    (defn f [cfg] (core/say (storage/store-chunks! cfg \"c\" [])) :x)" true]
   "hole/reify-body.clj"
   ["a statement inside a `reify` method body"
    "(ns hole.reify-body (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (reify Runnable (run [_] (storage/store-chunks! cfg \"c\" []) nil)))" true]
   "hole/user-macro.clj"
   ["a statement inside a user macro's expansion"
    "(ns hole.user-macro (:require [digdir.docs.pipeline.storage :as storage]))
    (defmacro quietly [& body] (list 'do (cons 'do body) nil))
    (defn f [cfg] (quietly (storage/store-chunks! cfg \"c\" [])))" true]
   "hole/check-then-only-log.clj"
   ["a report CHECKED and then only logged: the swallowed tenant-refusal issue pattern at the consumer. The check reads it, so the census cannot tell this from acting on it"
    "(ns hole.check-then-only-log (:require [digdir.docs.pipeline.storage :as storage] [taoensso.telemere :as t]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (if (seq (:rejected r)) (t/event! :x {:data r}) nil) :done))" true]
   "hole/and-through-local.clj"
   ["a truth test of a report through a local: `(and r :x)` reads `r`. Only a producer call DIRECTLY in `and`/test position is judged a truth test"
    "(ns hole.and-through-local (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (let [r (storage/store-chunks! cfg \"c\" [])] (and r :stored)))" true]
   "hole/future-cancelled.clj"
   ["a future bound and only cancelled: `future-cancel` consumes the future, not the report (review case D04, a judgement)"
    "(ns hole.future-cancelled (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (let [fut (future (storage/store-chunks! cfg \"c\" []))] (future-cancel fut) :x))" true]
   "hole/computed-resolve.clj"
   ["a call through `resolve` of a computed symbol"
    "(ns hole.computed-resolve)
    (defn f [s] ((requiring-resolve (symbol \"typesense.client\" \"upsert-documents!\")) s \"c\" []) :x)" false]
   })

(deftest known-holes-stay-uncaught
  (let [per-row (library-per-row-writes)
        seeds (into (into per-row report-constructors) '#{digdir.docs.pipeline.storage/store-chunks!})
        vs (verdicts (census (map (fn [[f [_ src]]] [f src]) known-holes) seeds per-row) {})
        by-file (group-by :file vs)]
    (doseq [[f [why _ seen?]] known-holes]
      (testing (str f " - " why)
        ;; Not vacuous: a MISJUDGED shape must be seen and passed; a BLIND one
        ;; must be unseen. A pin whose source never parsed would fail here.
        (is (= seen? (boolean (seq (by-file f))))
            (if seen? "PREMISE: the census sees this use (the hole is a misjudgement)"
                "PREMISE: the census does not see this use (the hole is blindness)"))
        (is (empty? (remove #(= :ok (:verdict %)) (by-file f)))
            (str "the census now CATCHES this shape: move it to `planted` and delete it from the docstring's list\n"
                 (str/join "\n" (map label (by-file f)))))))))

(def ^:private known-over-fires
  "Correct code the census FLAGS: each pin is the shape, the reason, and the REAL
   sites it excuses. Pinned RED on purpose, the mirror of `known-holes`.

   THIS IS THE ONE PLACE A FLAGGED PRODUCT SITE IS CLEARED. Put the site's key under
   `:excuses` of the pin whose shape reproduces the misjudgement (or add a pin: the
   shape, the reason, the key). One edit, and it records the census as WRONG, not
   the site as \"safe\". Only a NON-RAW site can be excused: a raw per-row result,
   like a discard, is judged before any exception is consulted, and its only remedy
   is `storage/write-report`. The excuse holds only while it is true: the site must be
   misjudged EXACTLY as this pin's `:src` is misjudged now (so a wrong pin is refused,
   and a census fix of this shape expires the excuse), it must
   still exist, and it must still be flagged without the excuse
   (`every-per-row-write-in-the-product-reaches-code`).
   If a census fix stops flagging the shape, move it to the `legit/*` controls in
   `planted`, drop the pin and its excuses, and delete it from the docstring."
  {"overfire/bound-raw-then-write-report.clj"
   {:why "a raw result bound to a local is judged AT the binding, so a local that later reaches `write-report` is still flagged. The remedy is one line: inline the call into `write-report`. A raw site cannot be excused, so this pin has none, and code that checks `:success` itself is flagged until it reads the answer through `write-report`"
    :src "(ns overfire.bound-raw-then-write-report (:require [typesense.client :as ts] [digdir.docs.pipeline.storage :as storage]))
    (defn f [s rows] (let [resp (ts/upsert-documents! s \"c\" rows)] (storage/write-report \"c\" rows resp)))"
    :excuses #{}}
   "overfire/local-fn-named-println.clj"
   {:why "the census does not track lexical locals, so a LOCAL fn named `println` reads as clojure.core's; a namespace's own `println`, or one excluded via `:refer-clojure`, is handled"
    :src "(ns overfire.local-fn-named-println (:require [digdir.docs.pipeline.storage :as storage]))
    (defn f [cfg] (let [println (fn [r] (when (seq (:rejected r)) (throw (ex-info \"refused\" r))))] (println (storage/store-chunks! cfg \"c\" [])) :x))"
    :excuses #{}}
   "overfire/refer-all-local.clj"
   {:why "under `:refer :all` an unqualified name resolves to every such lib, so a LOCAL named like a producer reads as a call to it"
    :src "(ns overfire.refer-all-local (:require [typesense.client :refer :all]))
    (defn f [upsert-documents!] (upsert-documents! 1) :x)"
    :excuses #{}}})

(defn- over-fire-excuses
  "site-key -> the pin that excuses it."
  []
  (into {} (for [[pin {:keys [excuses]}] known-over-fires, k excuses] [k pin])))

(defn- flag-signature
  "What the census concluded about a flagged use - the misjudgement, not the site:
   its verdict, context kind, consumer, and whether the value is RAW."
  [v]
  [(:verdict v) (get-in v [:ctx :kind]) (get-in v [:ctx :by]) (boolean (:raw? v))])

(defn- pin-signatures
  "pin -> the misjudgements its `:src` makes the census produce NOW. A pin the census
   no longer misjudges has none, so nothing can stay excused under it."
  []
  (let [per-row (library-per-row-writes)
        vs (verdicts (census (map (fn [[f {:keys [src]}]] [f src]) known-over-fires)
                             (into (into per-row report-constructors) '#{digdir.docs.pipeline.storage/store-chunks!})
                             per-row)
                     {})]
    (into {} (for [pin (keys known-over-fires)]
               [pin (set (map flag-signature (filter #(and (= pin (:file %)) (not= :ok (:verdict %))) vs)))]))))

(defn- key-form
  "A site key as source that compiles pasted verbatim into `:excuses`."
  [[file def var kind]]
  (str "[" (pr-str file) " '" def " '" var " " kind "]"))

(defn- excuse-problems
  "Why each excuse (site-key -> pin) is not honest, judged against the census run
   WITHOUT excuses. A RAW site is refused outright. Otherwise an excuse must name a
   real use, flagged, whose misjudgement is one its pin's `:src` reproduces: filed
   under any other pin it is laundering."
  [unexcused excuses pin-sigs]
  (let [by-key (group-by site-key unexcused)]
    (for [[k pin] excuses
          :let [flagged (remove #(= :ok (:verdict %)) (by-key k))
                sigs (set (map flag-signature flagged))]
          problem [(when (some :raw? flagged)
                     (str "raw: a per-row result cannot be excused; pass it to `storage/write-report`"
                          " (inline the call if it is bound to a local): " (key-form k)))
                   (when-not (by-key k) (str "stale excuse: no such use " (key-form k)))
                   (when (and (by-key k) (empty? flagged)) (str "unneeded excuse: the census does not flag " (key-form k)))
                   (when (and (seq flagged) (not (some (get pin-sigs pin #{}) sigs)))
                     (str "wrong pin: " (key-form k) " is misjudged as " (pr-str sigs) ", but " pin
                          " reproduces " (pr-str (get pin-sigs pin #{}))))]
          :when problem]
      problem)))

(defn- delegation-problems
  "Why each delegation is not honest: it must be a NON-RAW value crossing a flow,
   name a deftest that exists, and be a real use the census would otherwise flag."
  [unexcused delegated]
  (let [by-key (group-by site-key unexcused)]
    (for [[k {:keys [pinned-by]}] delegated
          :let [flagged (remove #(= :ok (:verdict %)) (by-key k))]
          problem [(when-not (:test (meta (try (requiring-resolve pinned-by) (catch Exception _ nil))))
                     (str "no deftest " pinned-by " for " (key-form k)))
                   (when (some #(or (:raw? %) (not (#{:handed-on :anonymous-fn-return} (get-in % [:ctx :kind])))) flagged)
                     (str "not a flow handoff: only a NON-RAW value handed on or returned from an anonymous fn"
                          " can be delegated, and " (key-form k) " is " (pr-str (map flag-signature flagged))))
                   (when-not (by-key k) (str "stale delegation: no such use " (key-form k)))
                   (when (and (by-key k) (every? #(= :ok (:verdict %)) (by-key k)))
                     (str "unneeded delegation: the census judges " (key-form k) " without it"))]
          :when problem]
      problem)))

(deftest known-over-fires-stay-flagged
  (let [per-row (library-per-row-writes)
        vs (verdicts (census (map (fn [[f {:keys [src]}]] [f src]) known-over-fires)
                             (into (into per-row report-constructors) '#{digdir.docs.pipeline.storage/store-chunks!})
                             per-row)
                     {})
        by-file (group-by :file vs)]
    (doseq [[f {:keys [why]}] known-over-fires]
      (testing (str f " - " why)
        (is (seq (by-file f)) "PREMISE: the census sees the use (an unseen pin proves nothing either way)")
        (is (seq (remove #(= :ok (:verdict %)) (by-file f)))
            (str "the census no longer FLAGS this correct shape: move it to the legitimate controls\n"
                 (str/join "\n" (map label (by-file f)))))))))

(def ^:private battery-dir "test/digdir/docs/pipeline/write_sites_battery/")

(def ^:private battery-files
  "The review's over-firing batteries for the write-report change, byte-identical. The first was
   PRE-REGISTERED, written before its author read this census, and its hash is the
   one recorded at registration. An edit to any case shows here, not silently."
  {"overfire_battery.edn"          "1ded4aa6192ef1367a5ddbcc12459e89207e3fe4140e14e14a839518ccd3099b"
   "overfire_battery_postread.edn" "ad5e4f5d3390d5ac905d35e21206f0428223cdb187dd833b8a658d3f0f661360"
   "overfire_battery_round2.edn"   "57844950851aca4bb4d54c2a2614e7971dddb01269a6863e78c50e748e27fe8b"})

(def ^:private battery-deviations
  "The cases whose `:expect` this census deliberately does not meet: the verdict it
   gives, and where that limit is pinned. A `:judgement` case gets the verdict chosen."
  {:L31-refer-all-param-shadows     [:red   "known-over-fires: overfire/refer-all-local"]
   :P04-local-fn-named-println      [:red   "known-over-fires: overfire/local-fn-named-println"]
   :P10-let-local-and-non-last      [:green "known-holes: hole/and-through-local"]
   :R11-log-one-arm-ignore-other    [:green "known-holes: hole/check-then-only-log"]
   :D04-future-bound-only-cancelled [:green "judgement; known-holes: hole/future-cancelled"]
   :D15-log-only-in-if-branches     [:green "judgement: the report is READ in the test before either log; the class is hole/check-then-only-log"]})

(defn- sha256-hex [f]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest d (java.nio.file.Files/readAllBytes (.toPath (io/file f))))))))

(defn- battery-source
  "A case as a source, wrapped the way the probe wraps it."
  [{:keys [id src ns-extra ns-opts]}]
  [(str "sx/" (name id) ".clj")
   (str "(ns sx." (str/lower-case (name id)) " " (or ns-opts "")
        " (:require [digdir.docs.pipeline.storage :as storage] [typesense.client :as ts]"
        " [taoensso.telemere :as t] [missionary.core :as m] " (or ns-extra "") "))\n" src)])

(deftest review-batteries-are-judged-as-expected
  (doseq [[file sha] battery-files]
    (testing (str file " is the copy that was registered")
      (is (= sha (sha256-hex (str battery-dir file))))))
  (let [cases (vec (mapcat #(edn/read-string (slurp (str battery-dir %))) (sort (keys battery-files))))
        per-row (library-per-row-writes)
        seeds (into (into per-row report-constructors)
                    '#{digdir.docs.pipeline.storage/store-chunks!
                       digdir.docs.pipeline.storage/upsert-row!
                       digdir.docs.pipeline.storage/store-complete-document!})
        by-file (group-by :file (verdicts (census (map battery-source cases) seeds per-row) {}))]
    (is (= 69 (count cases)) "PREMISE: 47 pre-registered + 11 post-read + 11 round-2 cases were read")
    (doseq [c cases
            :let [[f _] (battery-source c)
                  vs (by-file f)
                  got (if (seq (remove #(= :ok (:verdict %)) vs)) :red :green)
                  [deviation where] (battery-deviations (:id c))
                  want (or deviation (:expect c))]]
      (testing (str (name (:id c)) (when where (str " (deviation: " where ")")))
        (is (seq vs) "PREMISE: the census saw the case")
        (is (not= :judgement want) "a judgement case needs its chosen verdict in battery-deviations")
        (is (= want got) (str/join "\n" (map #(str (name (:verdict %)) " " (label %)) vs)))))
    (testing "every deviation names a case that exists"
      (is (empty? (remove (set (map :id cases)) (keys battery-deviations)))))))

(deftest every-per-row-write-in-the-product-reaches-code
  (let [per-row (library-per-row-writes)
        c (census (product-sources) (into per-row report-constructors) per-row)
        unexcused (verdicts c {})
        pin-sigs (pin-signatures)
        excuses (over-fire-excuses)
        vs (verdicts c (merge delegated-sites excuses))
        flagged (remove #(= :ok (:verdict %)) vs)]
    (testing "non-vacuity: the census finds the writes it must, however they are consumed"
      (is (some #(and (str/ends-with? (:file %) "docs/pipeline/storage.clj")
                      (= 'typesense.client/upsert-documents! (:var %)))
                vs)
          "the storage layer's bulk upsert")
      (is (some #(and (str/ends-with? (:file %) "docs/loader.clj")
                      (= 'digdir.docs.pipeline.storage/upsert-rows! (:var %)))
                vs)
          "the kudos loader's bulk upserts")
      (is (contains? (:producers c) 'digdir.docs.pipeline.storage/store-complete-document!)
          "the document store is a producer: it returns what it wrote"))
    (testing "every per-row write's answer, and every report, reaches code"
      (is (empty? flagged)
          (str (str/join "\n" (for [v flagged
                                    :let [pins (sort (keep (fn [[pin sigs]] (when (sigs (flag-signature v)) pin)) pin-sigs))]]
                                (str (name (:verdict v)) ": " (label v)
                                     (cond
                                       (:raw? v)
                                       (str "\n    RAW: a per-row result cannot be excused. Pass it to `storage/write-report`;"
                                            " if it is bound to a local first, inline the call into `write-report`.")
                                       (= :discarded (:verdict v))
                                       "\n    a discard cannot be excused: fix the site."
                                       :else
                                       (str "\n    key: " (key-form (site-key v))
                                            (if (seq pins)
                                              (str "\n    its misjudgement is the one pinned by: " (str/join ", " pins))
                                              "\n    no pin reproduces this misjudgement"))))))
               "\n\nEither the site drops the value: fix the site. Or, for a site that is neither raw nor"
               " discarded, it is CORRECT code the census misjudges: paste the key into `:excuses` of the"
               " pin named above - one edit - or, if none is named, add a pin whose `:src` reproduces the"
               " misjudgement, with the key. The census checks the SHAPE only: within it a defect is"
               " excusable like correct code (see FUNDAMENTAL LIMIT), so that is your claim, for a reviewer.")))
    (testing "every over-fire excuse is honest: a real, flagged use whose misjudgement its pin reproduces"
      (let [problems (excuse-problems unexcused excuses pin-sigs)]
        (is (empty? problems) (str/join "\n" problems))))
    (testing "every delegation names a deftest that exists, for a use the census would otherwise flag"
      (let [problems (delegation-problems unexcused delegated-sites)]
        (is (empty? problems) (str/join "\n" problems))))))

(deftest the-escape-hatches-refuse-what-they-must
  (let [per-row (library-per-row-writes)
        seeds (into (into per-row report-constructors) '#{digdir.docs.pipeline.storage/store-chunks!})
        pin-sigs (pin-signatures)
        run (fn [src] (verdicts (census [["sx/site.clj" src]] seeds per-row) {}))
        flagged-of (fn [vs] (remove #(= :ok (:verdict %)) vs))
        flagged-key (fn [vs] (some-> (first (flagged-of vs)) site-key))
        exact-pin (fn [vs] {"pin/synthetic" (set (map flag-signature (flagged-of vs)))})
        a-real-deftest 'digdir.docs.pipeline.write-sites-test/the-library-answers-per-row-where-it-says-it-does
        p4 "(ns sx.site (:require [typesense.client :as ts]))
            (defn f [s rows] {:written (count (ts/upsert-documents! s \"c\" rows))})"
        p5 "(ns sx.site (:require [typesense.client :as ts]))
            (defn f [s rows] (let [resp (ts/upsert-documents! s \"c\" rows)] {:written (count resp)}))"
        checks "(ns sx.site (:require [typesense.client :as ts]))
                (defn f [s rows] (let [resp (ts/upsert-documents! s \"c\" rows)]
                                   (when (some (complement :success) resp) (throw (ex-info \"refused\" {})))
                                   :ok))"
        handoff "(ns sx.site (:require [digdir.docs.pipeline.storage :as storage]))
                 (defn f [cfgs] (storage/merge-write-reports (mapv storage/store-chunks! cfgs)))"]
    (testing "a RAW site cannot be excused at all: the phrases-reference tripwire shape direct (P4), through a local (P5), and code that checks `:success` itself (apply_questions before the write-report change)"
      (doseq [[label src] [["P4" p4] ["P5" p5] ["checks :success" checks]]
              :let [c (census [["sx/site.clj" src]] seeds per-row)
                    vs (verdicts c {})
                    k (flagged-key vs)
                    raw-refusal? (fn [problems] (some #(str/starts-with? % "raw: ") problems))]]
        (is (some? k) (str label " PREMISE: flagged"))
        (is (every? :raw? (flagged-of vs)) (str label " PREMISE: raw"))
        (doseq [pin (keys known-over-fires)]
          (is (raw-refusal? (excuse-problems vs {k pin} pin-sigs)) (str label " is refused AS RAW under " pin)))
        (is (raw-refusal? (excuse-problems vs {k "pin/synthetic"} (exact-pin vs)))
            (str label " is refused as raw even under a pin that reproduces its misjudgement exactly"))
        (is (= [:unjudged] (map :verdict (filter #(= k (site-key %)) (verdicts c {k "pin/synthetic"}))))
            (str label " and it stays flagged with the excuse applied: raw is judged before any exception, like a discard"))))
    (testing "a DISCARD cannot be excused at all: :discarded is decided before any excuse is consulted"
      (let [c (census [["sx/site.clj" "(ns sx.site (:require [digdir.docs.pipeline.storage :as storage]))
                                       (defn f [cfg] (storage/store-chunks! cfg \"c\" []) :x)"]] seeds per-row)
            k (flagged-key (verdicts c {}))]
        (is (= [:discarded] (map :verdict (filter #(= k (site-key %)) (verdicts c {k "pin/synthetic"})))))))
    (testing "a NON-RAW site is excusable by EXACT shape only: verdict, context, consumer and raw-ness"
      (let [vs (run handoff)
            k (flagged-key vs)
            [verdict kind by raw?] (flag-signature (first (flagged-of vs)))]
        (is (some? k) "PREMISE: a non-raw handoff is flagged")
        (is (false? raw?) "PREMISE: not raw")
        (is (empty? (excuse-problems vs {k "pin/synthetic"} (exact-pin vs))) "the exact signature is accepted")
        (is (seq (excuse-problems vs {k "pin/synthetic"} {"pin/synthetic" #{[verdict kind by true]}})) "a raw one is not")
        (is (seq (excuse-problems vs {k "pin/synthetic"} {"pin/synthetic" #{[verdict :anonymous-fn-return by raw?]}})) "nor another context")
        (doseq [pin (keys known-over-fires)]
          (is (seq (excuse-problems vs {k pin} pin-sigs))
              (str "nor a pin that does not reproduce it: " pin " (no pin today misjudges a non-raw site that can be excused)")))))
    (testing "the printed key compiles pasted verbatim, and reads back as the key"
      (let [k ["src/digdir/x.clj" 'f 'typesense.client/upsert-documents! :bound]]
        (is (= k (eval (read-string (key-form k)))))))
    (testing "the delegation door takes only a NON-RAW value crossing a flow: P4, P5, a raw result returned from an anonymous fn, and a top-level use are refused even with a real deftest"
      (doseq [[label src flow-kind? raw?] [["P4" p4 false true] ["P5" p5 false true]
                                           ["raw fn-return" "(ns sx.site (:require [typesense.client :as ts]))
                                                             (defn f [s rows] (let [g (fn [] (ts/upsert-documents! s \"c\" rows))] (g)))"
                                            true true]
                                           ["top-level" "(ns sx.site (:require [digdir.docs.pipeline.storage :as storage]))
                                                         (storage/store-chunks! {} \"c\" [])"
                                            false false]]
              :let [c (census [["sx/site.clj" src]] seeds per-row)
                    vs (verdicts c {})
                    k (flagged-key vs)]]
        (is (some? k) (str label " PREMISE: flagged"))
        (is (= [[flow-kind? raw?]] (distinct (map (juxt #(boolean (#{:handed-on :anonymous-fn-return} (get-in % [:ctx :kind]))) :raw?)
                                                  (flagged-of vs))))
            (str label " PREMISE: flow kind " flow-kind? ", raw " raw?))
        (is (some #(str/starts-with? % "not a flow handoff") (delegation-problems vs {k {:pinned-by a-real-deftest}}))
            (str label " delegated to a real deftest is refused as not a flow handoff"))
        (when (some :raw? (flagged-of vs))
          (is (= [:unjudged] (map :verdict (filter #(= k (site-key %)) (verdicts c {k {:pinned-by a-real-deftest}}))))
              (str label " and, raw, it stays flagged with the delegation applied")))))
    (testing "a delegation to a missing test, a missing namespace, or a var that is not a test is refused"
      (let [vs (run handoff)
            k (flagged-key vs)]
        (doseq [bad '[digdir.docs.pipeline.write-report-test/no-such-test
                      no.such.namespace/anything
                      digdir.docs.pipeline.write-sites-test/battery-dir]]
          (is (seq (delegation-problems vs {k {:pinned-by bad}})) (str bad)))
        (testing "PINNED GAP: for a non-raw flow handoff, a delegation to an UNRELATED real deftest is accepted. Whether a test covers the behaviour is not mechanical"
          (is (empty? (delegation-problems vs {k {:pinned-by a-real-deftest}}))))))
    (testing "PINNED GAP, THE FUNDAMENTAL LIMIT: a NON-RAW defect in the exact shape of a non-raw pin is excusable like the correct code that pin records"
      (let [sigs (fn [src] (set (map flag-signature (flagged-of (run src)))))
            defect "(ns sx.site (:require [digdir.docs.pipeline.storage :as storage]))
                    (defn f [cfgs] (mapv storage/store-chunks! cfgs) :x)"
            vs (run defect)
            k (flagged-key vs)]
        (is (seq (sigs handoff)) "PREMISE: the correct handoff is flagged (an over-fire a pin would record)")
        (is (= (sigs handoff) (sigs defect)) "the defect, which drops every report, is misjudged identically")
        (is (empty? (excuse-problems vs {k "pin/synthetic"} {"pin/synthetic" (sigs handoff)}))
            "so an excuse under a pin of the correct shape is accepted: the census cannot tell them apart")))))
