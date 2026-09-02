(ns digdir.config.structure-test
  "The config roots had TWO private, independent, hand-written definitions that
   were identical by coincidence. These tests exist so that agreement is checked
   rather than hoped for.

   ⚠️ THESE ASSERT THE INVARIANT, NOT THE VALUE. `#{:platform :runtime :dataset}`
   is the set today and is ALLOWED TO CHANGE — a fourth root is a product
   decision, not a regression. A test pinning the literal set would go red for
   the correct change and teach whoever hit it to edit the test, which is how a
   guard becomes a formality. What must never change is that the two consumers
   agree and that the source of truth is loadable."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.api-keys]
            [digdir.config.db]
            [digdir.config.ops.bootstrap :as bootstrap]
            [digdir.config.structure :as structure]))

(defn- private-value [ns-sym var-sym]
  (let [v (ns-resolve ns-sym var-sym)]
    (when v @v)))

(deftest both-consumers-derive-the-same-roots
  (testing "db.clj and api_keys.clj resolve to one set, because they read one file"
    (let [db-roots  (private-value 'digdir.config.db 'config-roots)
          key-roots (private-value 'digdir.config.api-keys 'valid-config-roots)]
      (is (some? db-roots)
          "digdir.config.db/config-roots did not resolve — if it was renamed this
           guard is no longer checking anything and must be rewritten, not deleted")
      (is (some? key-roots)
          "digdir.config.api-keys/valid-config-roots did not resolve — same:
           rewrite this guard against the new name rather than removing it")
      (is (= db-roots key-roots structure/config-roots)
          (str "the two config-root definitions have diverged, or one has stopped "
               "deriving from the structure file. db.clj sees " (pr-str db-roots)
               ", api_keys.clj sees " (pr-str key-roots)
               ", the file says " (pr-str structure/config-roots)
               ". They must all be the same object read from one place — if you are "
               "adding a root, add it to server/resources/config/config-structure.edn.")))))

(deftest the-loader-refuses-bad-structure-files
  ;; ⚠️ THIS REPLACED A VACUOUS TEST. The first version asserted that the real
  ;; structure file exists and parses — which can NEVER go red, because anything
  ;; that would fail it makes `digdir.config.structure` fail to load, and this
  ;; namespace requires it. The test could not run in the only case it was
  ;; written for.
  ;;
  ;; So the loader is exercised directly, against fixtures. This CAN go red, and
  ;; the case it guards is the one the loader's docstring forbids: someone adding
  ;; a local fallback set — `(or (roots from file) #{...})` — which would recreate
  ;; the duplication this whole change removes, invisibly, because it would only
  ;; surface when the file failed to load.
  (testing "a missing resource throws rather than defaulting"
    (is (thrown? clojure.lang.ExceptionInfo
                 (structure/load-structure! "structure-fixtures/does-not-exist.edn"))))
  (testing "unparseable EDN throws"
    (is (thrown? Exception
                 (structure/load-structure! "structure-fixtures/unparseable.edn.fixture"))))
  (testing "an empty root list throws — it would make every config root invalid"
    (is (thrown? clojure.lang.ExceptionInfo
                 (structure/load-structure! "structure-fixtures/empty-roots.edn.fixture"))))
  (testing "non-keyword roots throw"
    (is (thrown? clojure.lang.ExceptionInfo
                 (structure/load-structure! "structure-fixtures/not-keywords.edn.fixture"))))
  (testing "POSITIVE CONTROL — the loader does succeed on the real file, so the
            four assertions above are about the fixtures and not about a loader
            that throws unconditionally"
    (is (vector? (structure/load-structure!)))))

(deftest the-roots-are-a-non-empty-set-of-keywords
  ;; Deliberately NOT `(= #{:platform :runtime :dataset} ...)`. See the ns docstring.
  (testing "Shape, not membership"
    (is (set? structure/config-roots) "membership form is a set")
    (is (vector? structure/config-roots-ordered) "ordered form is a vector, so order is defined")
    (is (= (set structure/config-roots-ordered) structure/config-roots)
        "the set and the ordered sequence must describe the same roots")
    (is (seq structure/config-roots))
    (is (every? keyword? structure/config-roots))))

(deftest explicit-bootstrap-policy-covers-the-canonical-roots
  (testing "root-specific bootstrap values stay explicit, but cannot silently omit a new root"
    (is (= structure/config-roots
           (set (keys bootstrap/bootstrap-root-defaults))))))

;; ---------------------------------------------------------------------------
;; The durability guard
;; ---------------------------------------------------------------------------

(def ^:private intentional-root-enumerations
  "Files that deliberately attach different behavior to individual roots.

   This is not the old #450 exception list: the UI copies, retirement summary,
   and request-key dispatch are gone. These remaining sites cannot acquire a
   fourth behavior by iteration alone. Bootstrap is additionally guarded by an
   exact coverage assertion, so adding a root fails until its policy is chosen.

   The other entries either attach roots to distinct path prefixes or perform
   root-specific topology work. They are classified here so the proximity scan
   can stay aggressive without presenting intentional dispatch as duplication.
     config/ops/bootstrap.clj     explicit, exhaustively checked root policy
     api/routes/datasets.clj      per-prefix :root tags
     config/db.clj                :platform-specific definition lookup
     config/ops/topology.clj      per-root resolution calls"
  #{"config/ops/bootstrap.clj" "api/routes/datasets.clj" "config/db.clj"
    "config/ops/topology.clj"})

(defn- source-files []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))))

(def ^:private proximity-window
  "How close together all three roots must appear to count as an enumeration.

   Chosen because every shape that enumerates them puts them adjacent — a
   vector, a set, a `case`, a `cond`, a map keyed by root, a prose list. A file
   that merely handles `:platform` in one function and `:dataset` two hundred
   lines later is not enumerating anything, and flagging it would produce an
   allowlist whose entries are mostly innocent — which is worse than a narrow
   predicate, because a list nobody believes is a list nobody reads."
  400)

(defn- root-token-pattern
  [root]
  (re-pattern
   (str "(?<![A-Za-z0-9_./-])"
        (java.util.regex.Pattern/quote (str root))
        "(?![A-Za-z0-9_./-])")))

(def ^:private root-token-patterns
  (into {}
        (map (fn [root] [root (root-token-pattern root)]))
        structure/config-roots-ordered))

(defn- token-indexes
  [text root]
  (let [matcher (re-matcher (get root-token-patterns root) text)]
    (loop [indexes []]
      (if (.find matcher)
        (recur (conj indexes (.start matcher)))
        indexes))))

(defn- enumerates-roots?
  "Does this file name all three config roots CLOSE TOGETHER?

   ⚠️ SEPARATOR-INDEPENDENT, AND THE PREVIOUS VERSION IS WHY. It matched
   `:platform[^a-z]+:runtime[^a-z]+:dataset` — any lowercase letter between two
   roots defeated it. Measured against realistic shapes:

     [:platform :runtime :dataset]                  caught
     :platform / :runtime / :dataset                caught
     :platform, :runtime, or :dataset               MISSED
     (case root :platform a :runtime b :dataset c)  MISSED
     {:platform x :runtime y :dataset z}            MISSED
     (cond (= r :platform) … (= r :dataset) …)      MISSED

   The missed shapes are the ones a fourth root actually gets dropped by. A bare
   literal collection is the one form everybody now knows not to write; `case`,
   `cond` and a map keyed by root are not. And the prose copies that survived the
   first version did so because they used `, or ` rather than ` / ` — a
   PUNCTUATION ACCIDENT, not an exemption anyone chose.

   This asks a different question: do all three appear within
   `proximity-window` characters of each other, whatever sits between them."
  [^java.io.File f]
  (let [text (slurp f)
        anchor-root (first structure/config-roots-ordered)
        remaining-roots (rest structure/config-roots-ordered)]
    (boolean
     (some (fn [anchor-index]
             (let [lo (max 0 (- anchor-index proximity-window))
                   hi (min (count text) (+ anchor-index proximity-window))
                   window (subs text lo hi)]
               (every? #(re-find (get root-token-patterns %) window)
                       remaining-roots)))
           (token-indexes text anchor-root)))))

(deftest no-source-file-outside-the-loader-enumerates-the-roots
  ;; THE INVARIANT, not a count. A test pinning "exactly N copies remain" would
  ;; go green after someone adds one and deletes another, and would have to be
  ;; edited by anyone doing the right thing.
  ;;
  ;; This is what makes the change durable rather than a one-time sweep: the
  ;; roots were duplicated nine times precisely because nothing objected when a
  ;; tenth was written.
  (let [offenders (->> (source-files)
                       (remove #(str/includes? (.getPath ^java.io.File %) "config/structure.clj"))
                       (remove #(some (fn [known] (str/includes? (.getPath ^java.io.File %) known))
                                      intentional-root-enumerations))
                       (filter enumerates-roots?)
                       (mapv #(.getPath ^java.io.File %))
                       sort)]
    (is (seq (source-files))
        "no source files found — this check would be vacuous, and would pass")
    (is (empty? offenders)
        (str "these files enumerate the config roots instead of deriving them from "
             "digdir.config.structure:\n  " (str/join "\n  " offenders)
             "\n\nUse `structure/config-roots` for membership, or "
             "`structure/config-roots-ordered` where order is observable. If this "
             "is ClojureScript, it cannot use the loader — say so on the tracking "
             "issue rather than adding to the known list."))))
