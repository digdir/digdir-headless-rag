(ns digdir.build.bb-edn-structure-test
  "Guards against a `bb.edn` task that has been swallowed into a sibling (#247).

   THE DEFECT. The same mechanism as #229, in the highest-traffic file in the
   repository: a delimiter opened and not closed makes the following forms
   nest inside the preceding one. The author of the #229 guard hit it in
   `bb.edn` hours after writing that guard — in the one file it does not scan.

   WHY THIS FILE AND NOT THE TEST TREE. The blast radius is worse. A swallowed
   form in `routes_test.clj` cost 73 tests nobody ran; the rest of the suite
   was unaffected. `bb.edn` is read as a single form, so a defect takes EVERY
   task with it.

   WHAT IS ALREADY COVERED, SO THIS DOES NOT DUPLICATE IT. `bb tasks` fails
   loudly when the file does not parse, which is the common case. It is a
   smoke alarm rather than a guard — it fires only when someone runs a task,
   and it names the syntax rather than the missing form — but it does fire.
   THE CASE NOTHING DETECTS IS BALANCED-BUT-WRONG: a compensating delimiter
   restores balance, the file parses, `bb tasks` is happy, and a task has
   silently become an entry inside its neighbour. That is the only case this
   guard is for, and both validation samples below PARSE for that reason.

   THE #229 PREDICATE DOES NOT TRANSFER, AND NEITHER DOES ITS SHAPE. Two
   approaches were measured against this file and rejected:

     LAYOUT. #229 works because a nested deftest still sits at column 0 —
     layout and structure disagree, and that disagreement IS the defect.
     `bb.edn` has no such convention. Task keys sit at indent 9 (80 of them),
     indent 2 (`dev`), and task NAMES also appear at indent 34 inside
     `:depends` vectors. A shape-based text scan for `name {` finds 73 of the
     82 real tasks and two things that are not tasks.

     READER-VERSUS-PARSER. Comparing a depth-walk to the parsed map cannot
     work here: both derive from the same delimiters, so on a swallowed task
     they agree with each other and report nothing. Two views of one artifact
     are not two sources.

   WHAT THIS ASSERTS INSTEAD, and it needs no second instrument: A TASK'S
   VALUE MUST NOT CONTAIN ANOTHER TASK. That is the true analogue of `no
   deftest inside a deftest`, and it is purely structural. A swallowed task
   appears as a task-shaped pair — a name followed by a map of bb task
   options — somewhere inside its neighbour's value, whether that value is a
   map (77 tasks) or a form such as `(do …)` (5 tasks).

   Note the detector is the SHAPE, not a list of names: `{:doc … :task …}` is
   recognisable as a task without knowing what tasks exist, so this keeps
   working as tasks are added. A simpler rule — 'no symbol key inside a task
   value' — was tried and is WRONG: `:init` legitimately contains
   symbol-keyed maps (`term-grace-ms`, `kill-grace-ms`, `poll-ms`).

   WHAT THIS GUARD DOES NOT COVER, stated because sibling guards exist and
   the next person will otherwise assume one check is the whole answer:

     - a task swallowed into a value that is NEITHER a map nor a form
       containing the pair adjacently — the pair has to survive as `name`
       followed by `{…}` for the shape to be recognisable.
     - a task whose value is legitimately absent or malformed. This asks
       'is a task hiding inside another', not 'is this task correct'.
     - anything outside `:tasks`, and any other file. `bb.edn` is guarded
       because it fails WHOLESALE; see the issue for why that is a property
       of the file rather than a reason for a repo-wide policy.
     - the unbalanced case, which `bb tasks` already catches and which these
       samples deliberately do not exercise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private bb-edn-path
  "Tests run with `server/` as the working directory; bb.edn is at the root."
  "../bb.edn")

(def ^:private task-option-keys
  "Keys babashka recognises inside a task map.

   `clojure.edn/read-string` is NOT used to read bb.edn: the file contains a
   `#(…)` literal, seven regex literals and `@`, all of which the EDN reader
   rejects while babashka's own reader accepts them. bb.edn is EDN by
   convention, not by grammar."
  #{:task :doc :depends :requires :extra-deps :extra-paths :extra-env
    :override-builtin :init :enter :leave :when :parallel :verbose})

(defn read-bb-edn
  "The parsed `bb.edn`, or `{:read-error msg}`.

   Uses the Clojure reader rather than the EDN reader for the reason given on
   `task-option-keys`, with `*read-eval*` off and an unknown tagged literal
   passed through so it cannot masquerade as a broken file."
  [source]
  (try
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_tag value] value)]
      (read-string {:read-cond :allow} source))
    (catch Exception e {:read-error (.getMessage e)})))

(defn- task-shaped?
  "True for a map that looks like a bb task definition.

   Recognised by shape rather than by name, so adding a task does not require
   touching this guard. Requires every key to be a known task option AND at
   least one to be present, so an arbitrary keyword-keyed map inside a task
   body is not mistaken for a task."
  [v]
  (and (map? v)
       (seq v)
       (every? keyword? (keys v))
       (some task-option-keys (keys v))
       (every? task-option-keys (keys v))))

(defn swallowed-tasks
  "Names of task-shaped pairs found anywhere inside `v`.

   A task name is a symbol (`dev`) or, for `:init`, a keyword. In a map the
   pair is an entry; in a form such as `(do …)` it is two adjacent elements."
  [v]
  (let [found (volatile! [])
        name? #(or (symbol? %) (keyword? %))]
    (letfn [(walk [x]
              (cond
                (map? x)
                (do (doseq [[k value] x]
                      (when (and (name? k) (task-shaped? value))
                        (vswap! found conj k)))
                    (run! walk (vals x)))

                (coll? x)
                (do (doseq [[a b] (partition 2 1 x)]
                      (when (and (name? a) (task-shaped? b))
                        (vswap! found conj a)))
                    (run! walk x))))]
      (walk v))
    @found))

(defn- offenders
  "`[[enclosing-task [swallowed …]] …]` for one parsed bb.edn."
  [parsed]
  (vec (for [[task value] (:tasks parsed)
             :let [swallowed (swallowed-tasks value)]
             :when (seq swallowed)]
         [task swallowed])))

;; ---------------------------------------------------------------------------

(deftest bb-edn-parses
  (testing "the file this guard reasons about can be read at all"
    ;; The precondition. A guard that cannot read bb.edn reports it clean —
    ;; and its blind spot is file-shaped, hiding every task at once.
    (let [parsed (read-bb-edn (slurp bb-edn-path))]
      (is (not (:read-error parsed))
          (str "bb.edn did not parse, so this guard can make no claim about "
               "it: " (:read-error parsed)))
      (is (map? (:tasks parsed))
          "bb.edn parsed but has no :tasks map"))))

(deftest no-task-is-swallowed-into-another-task
  (testing "every task is a direct entry of :tasks, not an entry of a sibling"
    (let [parsed (read-bb-edn (slurp bb-edn-path))
          bad    (offenders parsed)]
      ;; Counts are REPORTED, never asserted. Asserting a total would make
      ;; this a tripwire on adding a task, and it would fail on the next PR
      ;; that adds one.
      (println (format "[bb.edn guard] %d tasks, %d swallowed"
                       (count (:tasks parsed))
                       (reduce + (map (comp count second) bad))))
      (is (empty? bad)
          (str "these bb.edn tasks contain another task inside their own "
               "value, which means a delimiter was opened and not closed and "
               "a compensating one restored balance further down. The file "
               "still parses and `bb tasks` still works, so nothing else "
               "reports this — but the swallowed task no longer exists.\n\n"
               (str/join "\n"
                         (for [[task swallowed] bad]
                           (format "  %s has swallowed: %s"
                                   task (str/join ", " swallowed)))))))))

(deftest every-depends-reference-resolves
  (testing ":depends names a task that exists"
    ;; An independent signal, using the file's own cross-references rather
    ;; than a second reading of the same delimiters. A swallowed task leaves
    ;; its dependents dangling, so this fires even where the shape detector
    ;; cannot see the pair.
    (let [tasks (:tasks (read-bb-edn (slurp bb-edn-path)))
          dangling (vec (for [[task value] tasks
                              :when (map? value)
                              dep (:depends value)
                              :when (not (contains? tasks dep))]
                          [task dep]))]
      (is (empty? dangling)
          (str "these :depends entries name tasks that do not exist. If the "
               "name looks correct, the target may have been swallowed into "
               "a sibling rather than deleted:\n"
               (str/join "\n" (for [[task dep] dangling]
                                (format "  %s depends on missing %s" task dep))))))))

(deftest the-guard-can-actually-detect-a-swallowed-task
  (testing "the detector fires on known-broken samples"
    ;; WITHOUT THIS THE GUARD IS INDISTINGUISHABLE FROM ONE THAT ALWAYS
    ;; PASSES. The samples are inline rather than a fixture of a real broken
    ;; file: a fixture would be a snapshot of a defect we no longer have,
    ;; while these fail forever.
    (let [clean "{:tasks
                  {alpha {:doc \"a\" :task (println \"a\")}
                   beta {:doc \"b\" :task (println \"b\")}}}"
          ;; alpha's map is never closed; the extra brace at the very end
          ;; restores balance, so beta becomes an entry of alpha.
          broken-map "{:tasks
                       {alpha {:doc \"a\" :task (println \"a\")
                        beta {:doc \"b\" :task (println \"b\")}}}}"
          ;; the (do …) is never closed; gamma is swallowed into it.
          broken-seq "{:tasks
                       {:init (do (def x 1)
                        gamma {:doc \"g\" :task (println \"g\")})
                        delta {:doc \"d\" :task (println \"d\")}}}"]

      (testing "the broken samples PARSE, which is what makes them the right samples"
        ;; If a sample failed to parse it would be exercising the unbalanced
        ;; case that `bb tasks` already catches, and this guard would be
        ;; validated against a defect it is not for.
        (doseq [[label sample] [["broken-map" broken-map] ["broken-seq" broken-seq]]]
          (is (not (:read-error (read-bb-edn sample)))
              (str label " must parse: this guard exists for the "
                   "balanced-but-wrong case, so a sample that fails to parse "
                   "would validate the wrong thing"))))

      (testing "a clean sample is clean"
        (let [parsed (read-bb-edn clean)]
          (is (= 2 (count (:tasks parsed))) "both tasks are visible")
          (is (empty? (offenders parsed)))))

      (testing "a task swallowed into a sibling's MAP is caught"
        (let [parsed (read-bb-edn broken-map)]
          ;; the symptom the guard exists to make visible
          (is (= '[alpha] (vec (keys (:tasks parsed))))
              "beta has silently stopped being a task")
          (is (= '[[alpha [beta]]] (offenders parsed)))))

      (testing "a task swallowed into a sibling's FORM is caught"
        (let [parsed (read-bb-edn broken-seq)]
          (is (not (contains? (:tasks parsed) 'gamma))
              "gamma has silently stopped being a task")
          (is (= '[[:init [gamma]]] (offenders parsed)))))

      (testing "a dangling :depends is caught"
        (let [tasks (:tasks (read-bb-edn
                              "{:tasks {a {:doc \"a\" :depends [nope]}}}"))]
          (is (seq (for [[_ v] tasks, d (:depends v)
                         :when (not (contains? tasks d))]
                     d))))))))
