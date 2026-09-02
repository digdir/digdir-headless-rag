(ns digdir.data.db-platform-test
  "Which vars exist in the :cljs branch of data/db.cljc (#169).

   The vars below call `d`, required under :clj only, so they must not survive
   into the ClojureScript branch. Asserting that from a source grep would prove
   nothing - the point is what the READER produces under :cljs, which is the
   same expansion the ClojureScript compiler sees.

   Uses clojure.tools.reader, not clojure.core/read: the Clojure reader always
   implies the :clj feature, so it CANNOT read a file as ClojureScript and a
   check built on it reports every :clj-only var as present. That was the first
   version of this test, and it failed loudly rather than passing vacuously.

   Scope, stated so nobody reads more into a green run than it earns: this
   checks which top-level defns survive the reader under :cljs. It does not run
   shadow-cljs, so it proves the form is absent from the :cljs branch - not
   that the emitted build is byte-free of it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]))

(defn- read-all
  "Every top-level form of `path`, read with exactly `features`."
  [path features]
  (let [r (rt/string-push-back-reader (slurp (io/file path)))
        eof (Object.)]
    (binding [tr/*read-eval* false]
      (doall
        (take-while #(not= eof %)
                    (repeatedly #(tr/read {:read-cond :allow
                                           :features features
                                           :eof eof}
                                          r)))))))

(defn- defn-names
  [forms]
  (into #{}
        (comp (filter seq?)
              (filter #(contains? #{'defn 'defn-} (first %)))
              (map second)
              (filter symbol?))
        forms))

(def ^:private db-file "src/digdir/data/db.cljc")

(def ^:private jvm-only
  "Vars that must not exist in the :cljs branch. Each calls `d`, and
   create-folder also uses System interop."
  '#{create-folder get-chunk rename-convo-topic rename-folder
     delete-convo delete-folder clear-all-conversations})

(deftest jvm-only-vars-are-absent-from-the-cljs-branch
  (let [cljs-defns (defn-names (read-all db-file #{:cljs}))
        clj-defns (defn-names (read-all db-file #{:clj}))]
    (testing "the reader actually produced something, so a pass is not vacuous"
      (is (seq cljs-defns) "no defns at all in the :cljs branch - test is broken")
      (is (seq clj-defns)))

    (testing "each jvm-only var is present on :clj and absent on :cljs"
      (doseq [v jvm-only]
        (is (contains? clj-defns v)
            (str v " should still exist on the JVM"))
        (is (not (contains? cljs-defns v))
            (str v " calls :clj-only code and must not reach the cljs branch"))))))
