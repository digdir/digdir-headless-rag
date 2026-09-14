(ns digdir.setup.config-cli-test
  "Guards for the on-jar config CLI (#505).

   These are source-level guards on purpose. The thing that breaks this CLI is
   not a logic error a unit test would catch — it is a namespace that resolves
   fine on a developer machine and is absent from the uberjar, which fails ONLY
   inside the runtime image. A test that exercised the functions here would pass
   in exactly the situation that breaks production."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private cli-source-path "src/digdir/setup/config_cli.clj")

(defn- code-only
  "Source with every string literal and line comment emptied, so a guard cannot
   be satisfied by prose.

   ⚠️ THIS IS NOT TIDINESS. The first version of these guards matched the raw
   source with `str/includes?`, and the namespace docstring names every function
   they assert on. Deleting the actual marker call left the docstring behind and
   the guard stayed green — found by sabotage, which is the only thing that
   could have found it. A check whose subject also appears in the documentation
   of that check is measuring the documentation. Comments are stripped for the
   same reason strings are: a comment naming the call would keep the guard green
   just as well as a docstring did.

   ⚠️ AND IT IS A CHARACTER SCAN RATHER THAN A REGEX, WHICH IS THE SECOND
   DEFECT IN THIS HELPER, NOT A STYLE CHOICE. The fix for the docstring hole was
   `str/replace` with `#\"\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"\"`. Java compiles a
   quantified group into `Loop.match`, which RECURSES ONCE PER REPETITION — so a
   2271-character docstring costs ~2271 stack frames and the pattern throws
   `StackOverflowError` on any thread with a small enough stack. It passed
   locally on an 8 MiB main thread and failed in CI. Reproduced deliberately:
   the regex succeeds at a 2 MiB thread stack and overflows at 1 MiB, on this
   very file. `loop`/`recur` is a jump, so this version uses constant stack and
   the failure mode is removed rather than tuned."
  [^String src]
  (let [n (.length src)
        sb (StringBuilder. n)]
    (loop [i 0, state :code]
      (if (>= i n)
        (.toString sb)
        (let [c (.charAt src i)]
          (case state
            :code (cond
                    ;; Clojure character literal (\a, \newline, and notably \")
                    ;; — consume both chars so a literal quote cannot be read as
                    ;; the start of a string.
                    (= c \\) (do (.append sb \space) (recur (+ i 2) :code))
                    (= c \") (do (.append sb \") (recur (inc i) :string))
                    (= c \;) (recur (inc i) :comment)
                    :else (do (.append sb c) (recur (inc i) :code)))
            :string (cond
                      (= c \\) (recur (+ i 2) :string)
                      (= c \") (do (.append sb \") (recur (inc i) :code))
                      :else (recur (inc i) :string))
            :comment (if (= c \newline)
                       (do (.append sb \newline) (recur (inc i) :code))
                       (recur (inc i) :comment))))))))

(defn- ns-sym->relative-path
  "digdir.config.cache-invalidation -> digdir/config/cache_invalidation.clj"
  [ns-sym]
  (-> (name ns-sym)
      (str/replace "-" "_")
      (str/replace "." "/")
      (str ".clj")))

(defn- required-digdir-namespaces
  "The `digdir.*` namespaces the CLI requires, read from its own ns form."
  []
  (let [src (slurp (io/file cli-source-path))]
    (->> (re-seq #"\[(digdir\.[a-z0-9.-]+)\s+:as" src)
         (map second)
         (map symbol)
         set)))

;; ---------------------------------------------------------------------------
;; The property that makes the CLI work at all
;; ---------------------------------------------------------------------------

(deftest every-namespace-the-cli-requires-is-on-a-shipped-source-path
  (testing "src-build/build.clj copies only these roots into the uberjar"
    (let [build-clj (slurp (io/file "src-build/build.clj"))
          copy-line (re-find #"\{:target-dir class-dir :src-dirs \[([^\]]+)\]\}" build-clj)]
      ;; Control: if the shape of build.clj changes, this test must fail loudly
      ;; rather than silently stop checking anything.
      (is (some? copy-line)
          "could not find the uberjar copy-dir call in src-build/build.clj — this guard is no longer reading what it thinks it reads")
      (let [shipped-roots (->> (str/split (second copy-line) #"\s+")
                               (map #(str/replace % "\"" ""))
                               (remove str/blank?)
                               set)]
        (is (contains? shipped-roots "src")
            "the uberjar no longer copies src")
        (is (not (contains? shipped-roots "src-dev"))
            "src-dev now ships — if that is deliberate this guard is obsolete, but the CLI's reasoning depends on it not shipping")

        (testing "so every digdir namespace the CLI requires resolves under one of them"
          (let [required (required-digdir-namespaces)]
            ;; Control: the regex must actually find requires. An empty set
            ;; would make every assertion below vacuously true.
            (is (seq required)
                "parsed zero requires out of the CLI ns form — the guard is not reading the file it names")
            (is (contains? required 'digdir.tools.config)
                "the CLI no longer requires digdir.tools.config — if the read path was reimplemented instead, that is the drift #505 exists to avoid")
            (doseq [ns-sym (sort required)]
              (let [rel (ns-sym->relative-path ns-sym)
                    found-in (filter #(.exists (io/file % rel)) shipped-roots)]
                (is (seq found-in)
                    (str ns-sym " is required by the config CLI but is not under any shipped source root "
                         (pr-str shipped-roots)
                         " — it would resolve on a developer machine and be absent from /app/app.jar"))))))))))

(deftest tools-config-is-not-left-behind-in-src-dev
  ;; Control first: prove this check can see the filesystem it is asserting about.
  (is (.exists (io/file "src/digdir/setup/config_cli.clj"))
      "control failed — the test is not running from the server/ directory, so every path assertion below is meaningless")
  (is (.exists (io/file "src/digdir/tools/config.clj"))
      "digdir.tools.config must be under src so the uberjar carries it")
  (is (not (.exists (io/file "src-dev/digdir/tools/config.clj")))
      "a second copy of digdir.tools.config is back in src-dev — two files, one namespace, and which one wins depends on the classpath"))

;; ---------------------------------------------------------------------------
;; The two things the brief says decide whether this is any good
;; ---------------------------------------------------------------------------

(deftest cli-calls-the-same-functions-the-bb-tasks-call
  (let [code (code-only (slurp (io/file cli-source-path)))]
    ;; Control: prove the stripper left code behind. If it ate everything, each
    ;; assertion below would fail rather than pass — but a later refactor could
    ;; invert that, so state the precondition.
    (is (str/includes? code "defn -main")
        "control failed — string-stripping removed the code this guard reads")
    (testing "resolve and write go through config-db, not a local reimplementation"
      (is (str/includes? code "(config-db/get-config-node-by-tenant-config-key")
          "the CLI must CALL the same resolve function bb config-set uses")
      (is (str/includes? code "(config-db/set-node-value!")
          "the CLI must CALL the same write function bb config-set uses"))
    (testing "the read goes through the shared helper"
      (is (str/includes? code "(tools-config/get-value")
          "the CLI must CALL the same read function bb config-get uses"))))

(deftest cli-touches-the-change-marker
  ;; Miss this and the value lands in the DB while a running server keeps
  ;; serving its cached snapshot: the write appears to do nothing, silently,
  ;; and gets blamed on the write rather than the missing signal.
  (let [code (code-only (slurp (io/file cli-source-path)))]
    (is (str/includes? code "(ci/touch-marker!)")
        "the CLI must CALL the same change-marker bb config-set touches — a mention in a docstring is not a call")))

(deftest cli-reads-values-with-the-same-edn-reader-as-bb-config-set
  ;; bb.edn's read-edn carries {'sorted/map identity}. A plain
  ;; clojure.edn/read-string here would reject a value the bb task accepts, so
  ;; the two entry points would disagree about what is a legal value.
  (let [code (code-only (slurp (io/file cli-source-path)))
        bb-edn (slurp (io/file ".." "bb.edn"))]
    (is (str/includes? bb-edn "'sorted/map identity")
        "control failed — bb.edn no longer carries the reader this guard compares against, so the comparison below is against nothing")
    (is (str/includes? code "{:readers {'sorted/map identity}}")
        "the CLI must read EDN with the same reader map bb config-set uses")))

(deftest code-only-does-not-recurse-per-character
  ;; ⚠️ THIS IS A REGRESSION TEST FOR THE HELPER, NOT FOR THE CLI.
  ;;
  ;; `code-only` was a `str/replace` with a quantified group. Java compiles that
  ;; into `Loop.match`, which recurses once per repetition, so a 2271-character
  ;; docstring cost ~2271 frames. It passed on an 8 MiB main thread and threw
  ;; StackOverflowError in CI — measured, not inferred: the regex succeeded at a
  ;; 2 MiB thread stack and overflowed at 1 MiB on this very file.
  ;;
  ;; "It passes locally" cannot detect that, because the variable is stack depth
  ;; rather than correctness. So pin the stack instead of hoping: 128 KiB is
  ;; eight times below where the old implementation died.
  (let [src (slurp (io/file cli-source-path))
        result (atom nil)
        t (Thread. nil
                   ^Runnable (fn []
                               (reset! result
                                       (try {:ok (count (code-only src))}
                                            (catch Throwable e
                                              {:err (.getSimpleName (class e))}))))
                   "code-only-small-stack"
                   (* 128 1024))]
    (.start t)
    (.join t)
    ;; Control: a short input would pass on any stack and prove nothing.
    (is (> (count src) 5000)
        "control failed — the source under test is too small to exercise the failure mode this pins")
    (is (nil? (:err @result))
        (str "code-only threw " (:err @result) " on a 128 KiB stack — it is consuming stack per character again"))
    ;; `or 0` so that when the stack blows, this reports as one clean failure
    ;; above rather than adding a NullPointerException on top of it.
    (is (pos? (long (or (:ok @result) 0)))
        "code-only produced nothing")))

(deftest cli-exposes-exactly-the-two-documented-operations
  (let [src (slurp (io/file cli-source-path))
        ops (->> (re-seq #"\"(get|set)\" \(run-" src)
                 (map second)
                 set)]
    (is (= #{"get" "set"} ops)
        (str "the CLI dispatch table is " (pr-str ops)
             " but #505 specifies exactly get and set"))
    (is (empty? (set/difference ops #{"get" "set"}))
        "an undocumented operation was added to the dispatch table")))
