(ns digdir.build.nested-deftest-test
  "Guards against a `deftest` that the test runner cannot see (#229).

   THE DEFECT. A single missing closing paren makes every following form
   nest inside the preceding one. `routes_test.clj` lost one at line 1382
   and the remaining 82 deftests became the *body* of the deftest above
   them. A deftest body is not evaluated at load time, so those tests were
   never interned and never enumerated: 144 deftests in the file, 71 run.
   The suite stayed green the entire time, because a test that does not
   exist cannot fail.

   WHY IT SURVIVED SO LONG. Nesting here is PAREN BALANCE, NOT LAYOUT. All
   82 sat at column 0, textually identical to a top-level deftest. Every
   grep-shaped and indentation-shaped check reported the file clean —
   including the first version of this guard's own detector, which returned
   `nested: 0` for the known-broken file. That is why this reads FORMS via
   the reader instead of lines via a regex: the defect is invisible to any
   instrument that looks at text.

   NOT EVERY NESTED DEFTEST IS DEAD, AND THAT IS WHY THIS ASSERTS ON
   STRUCTURE RATHER THAN COUNTS. When an enclosing deftest runs, its body
   executes and the nested `deftest` forms intern their vars. clojure.test's
   iteration is not a frozen snapshot, so a var interned early enough gets
   picked up and run. 10 of the 85 nested deftests were running that way
   (9 in routes_test, 1 in agent_integration_test — which had a real nesting
   defect and ZERO dead tests). Those are worse than dead: they pass today
   and vanish the moment test order or the enclosing test changes. So a
   count-based check is not equivalent to this one — on agent_integration_test
   the count delta from repairing the paren was zero.

   WHAT THIS GUARD DOES NOT COVER, stated because a sibling guard exists and
   the next person will otherwise assume one of them is the whole answer.
   `digdir.build` also gets a text-based scanner (paren balance over raw
   characters, no reader). The blind spots are complementary and neither is
   strictly better:

     this one   — needs the reader configured, and SILENTLY SKIPS ANY FILE
                  IT CANNOT READ. That blind spot is FILE-SHAPED: it hides
                  everything in the file at once. It really happened —
                  `discarded_fields_log_test.clj` uses an auto-resolved
                  ::alias keyword, went unread by every scan for a day, and
                  hid 4 deftests. Hence `every-test-file-is-readable`, which
                  is not a nicety: an unreadable file is exactly where a
                  defect hides, and it is reported as clean by any instrument
                  that skips it.
     text-based — immune to reader configuration, but anchors on line start,
                  so it MISSES A DEFTEST NOT AT COLUMN 0, which this catches.

   Neither covers a deftest produced by a macro, since no deftest form
   appears in the source at all. No such macro exists in this repo today."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private test-root
  "Tests run with `server/` as the working directory."
  "test")

(defn- normalise-auto-resolved-aliases
  "Rewrite `::alias/kw` to `:dummy.alias/kw` before reading.

   An auto-resolved keyword cannot be read without the alias being
   established, which requires loading the namespace — far too much for a
   structural scan. Binding `*alias-map*` is NOT the fix: under bb's
   tools.reader shim it is silently ignored and fails with
   'PersistentArrayMap cannot be cast to java.util.concurrent.Future', an
   error that names nothing relevant to the cause.

   This substitution cannot change paren balance or the position of any
   form, so it cannot affect the nesting question. Its only blind spot is a
   literal `::alias/` inside a string."
  [s]
  (str/replace s #"::([a-zA-Z][a-zA-Z0-9.*+!_'?<>=-]*)/" ":dummy.$1/"))

(defn read-all-forms
  "Every top-level form in `source`, or `{:read-error msg}`.

   `*default-data-reader-fn*` is bound so an unknown tagged literal does not
   masquerade as a broken file — that error names the tag, not the problem."
  [source]
  (try
    (let [r (java.io.PushbackReader.
              (java.io.StringReader. (normalise-auto-resolved-aliases source)))]
      (binding [*read-eval* false
                *default-data-reader-fn* (fn [_tag value] value)]
        (doall (take-while some?
                           (repeatedly #(read {:read-cond :allow :eof nil} r))))))
    (catch Exception e {:read-error (.getMessage e)})))

(defn- deftest-form?
  "True for `(deftest …)` however the namespace is aliased — `deftest`,
   `t/deftest`, `clojure.test/deftest` all count."
  [x]
  (and (seq? x) (symbol? (first x)) (= "deftest" (name (first x)))))

(defn count-deftests-anywhere
  "Every deftest form in the tree, nested or not."
  [forms]
  (let [n (volatile! 0)]
    (letfn [(walk [x]
              (when (deftest-form? x) (vswap! n inc))
              (when (coll? x) (run! walk x)))]
      (run! walk forms))
    @n))

(defn count-top-level-deftests
  "Only the deftests the runner can intern at load time."
  [forms]
  (count (filter deftest-form? forms)))

(defn- test-files []
  (->> (file-seq (io/file test-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
       (sort-by #(.getPath ^java.io.File %))))

(defn- scan
  "{:path … :top n :all n} per file, or {:path … :read-error msg}."
  []
  (for [^java.io.File f (test-files)
        :let [forms (read-all-forms (slurp f))]]
    (if (map? forms)
      {:path (.getPath f) :read-error (:read-error forms)}
      {:path (.getPath f)
       :top (count-top-level-deftests forms)
       :all (count-deftests-anywhere forms)})))

(deftest no-test-is-nested-inside-another-test
  (testing "every deftest is top-level, so the runner can see it"
    (let [rows (remove :read-error (scan))
          offenders (->> rows
                         (filter #(not= (:top %) (:all %)))
                         (map #(assoc % :nested (- (:all %) (:top %)))))]
      ;; Counts are REPORTED, never asserted. Asserting a total would make
      ;; this a tripwire on adding tests — in the very file that just lost
      ;; 73 of them — and it would fail on the PR that introduced it.
      (println (format "[nested-deftest guard] %d files, %d deftests, %d nested"
                       (count rows)
                       (reduce + (map :all rows))
                       (reduce + (map :nested offenders))))
      (is (empty? offenders)
          (str "these files contain deftests nested inside another form, so "
               "the runner cannot enumerate them. They are not necessarily "
               "dead — a nested deftest whose enclosing test runs early "
               "enough is interned and run, which makes it order-dependent "
               "instead. Both are defects.\n\n"
               "The usual cause is ONE MISSING CLOSING PAREN, which makes "
               "every following form nest inside the one above it.\n\n"
               (str/join "\n"
                         (for [o offenders]
                           (format "  %s — %d top-level, %d total, %d NESTED"
                                   (:path o) (:top o) (:all o) (:nested o))))))))) 

(deftest every-test-file-is-readable
  (testing "no test file is silently skipped by this scan"
    ;; The load-bearing half. A file this guard cannot read is reported clean
    ;; by the guard, and its blind spot is file-shaped: it hides every test in
    ;; the file at once. That is not hypothetical — an auto-resolved ::alias
    ;; keyword hid 4 deftests from every scan run for a day.
    (let [unreadable (filter :read-error (scan))]
      (is (empty? unreadable)
          (str "this guard could not read these files, so it cannot make any "
               "claim about them:\n"
               (str/join "\n" (for [u unreadable]
                                (str "  " (:path u) " — " (:read-error u))))
               "\n\nFix the reader, not the file. Known causes: an "
               "auto-resolved ::alias/keyword (normalise the text) and an "
               "unknown tagged literal (bind *default-data-reader-fn*). Both "
               "report errors that name something irrelevant to the cause.")))))

(deftest the-guard-can-actually-detect-nesting
  (testing "the detector fires on a known-nested sample"
    ;; WITHOUT THIS, THE GUARD IS INDISTINGUISHABLE FROM ONE THAT ALWAYS
    ;; PASSES. Its first version returned nested:0 for the real broken file.
    ;; The sample is inline rather than a fixture of the historical file,
    ;; because routes_test.clj is now repaired and a guard that has only ever
    ;; seen a clean tree proves nothing.
    (let [broken (str "(deftest outer\n"
                      "  (is true)\n"          ; <- one closing paren short
                      "(deftest looks-top-level\n"
                      "  (is true))\n"
                      "(deftest also-swallowed\n"
                      "  (is true)))\n")
          forms (read-all-forms broken)]
      (is (not (map? forms)) "the sample should read")
      (is (= 1 (count-top-level-deftests forms))
          "only the outer deftest is top-level in the sample")
      (is (= 3 (count-deftests-anywhere forms))
          "all three deftests exist in the tree")
      (is (= 2 (- (count-deftests-anywhere forms) (count-top-level-deftests forms)))
          "the detector must report 2 nested for this sample")))

  (testing "a clean sample reports nothing, so the detector is not always-on"
    (let [clean "(deftest a (is true))\n(deftest b (is true))\n"
          forms (read-all-forms clean)]
      (is (= 2 (count-top-level-deftests forms)))
      (is (= 2 (count-deftests-anywhere forms)))))

  (testing "nested deftests at column 0 are still detected"
    ;; The specific reason grep- and indentation-based checks failed: the 82
    ;; nested deftests in routes_test.clj were NOT indented.
    (let [forms (read-all-forms "(deftest outer (is true)\n(deftest at-column-zero (is true)))\n")]
      (is (= 1 (count-top-level-deftests forms)))
      (is (= 2 (count-deftests-anywhere forms)))))

  (testing "unreadable source is REPORTED, not silently skipped"
    ;; every-test-file-is-readable asserts this returns nothing. Prove the
    ;; detector can produce something first, or that assertion is one that
    ;; cannot fail.
    (let [forms (read-all-forms "(deftest truncated (is true)")]
      (is (map? forms) "a truncated form must surface as a read error")
      (is (:read-error forms) "the error message must be carried, not swallowed")))

  (testing "an aliased deftest form is recognised"
    (let [forms (read-all-forms "(t/deftest aliased (is true))\n")]
      (is (= 1 (count-top-level-deftests forms)))))

  (testing "the scan is looking at a real, populated tree"
    ;; Guards against every assertion above passing vacuously because the
    ;; file walk silently found nothing.
    (let [rows (remove :read-error (scan))]
      (is (< 100 (count rows))
          (str "implausibly few test files scanned: " (count rows)))
      (is (< 1000 (reduce + (map :all rows)))
          (str "implausibly few deftests found: " (reduce + (map :all rows)))))))
