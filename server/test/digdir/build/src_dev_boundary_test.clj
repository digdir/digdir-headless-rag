(ns digdir.build.src-dev-boundary-test
  "Guards the src/ -> src-dev/ boundary.

   `src-dev` is NOT on the production classpath: `deps.edn` sets
   `:paths [\"src\" \"resources\"]`, `:prod` adds only `src-prod`, and
   `server.Dockerfile` copies src / src-build / src-prod / resources.
   So a `:require` in `src/` pointing at a namespace that lives only in
   `src-dev/` makes the production artifact fail to load — while every dev
   command keeps working, because `:dev` puts `src-dev` on the classpath.

   That is exactly how issue #30 happened: `api/routes/endpoints/debug.clj`
   required `digdir.skills.enrichment.collections` (src-dev), which broke the
   `prod` entrypoint for three months without anyone noticing, since the
   research work all ran from dev classpaths.

   This test is the cheap standing check. It reads ns forms rather than
   grepping, because several src/ docstrings legitimately *mention* src-dev
   namespaces by name (e.g. `skills/enrichment/naming.clj` explains its
   relationship to `...enrichment.collections`) and a text search flags those."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- clj-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))))

(defn- path->ns
  "server/src-dev/a/b_c.clj -> a.b-c"
  [root ^java.io.File f]
  (-> (.getPath f)
      (str/replace (re-pattern (str "^" root "/")) "")
      (str/replace #"\.cljc?$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- read-ns-form
  "Read the first form of a Clojure file with reader conditionals allowed,
   so .cljc files parse. Returns nil if the file has no readable ns form."
  [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (binding [*read-eval* false]
        (let [form (read {:read-cond :allow :eof nil} r)]
          (when (and (seq? form) (= 'ns (first form))) form))))
    (catch Exception _ nil)))

(defn- required-namespaces
  "Namespace symbols named in the :require clauses of an ns form."
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

(deftest src-must-not-require-src-dev-namespaces
  (testing "no namespace under src/ requires a namespace that exists only in src-dev/"
    (let [dev-only (set (map #(path->ns "src-dev" %) (clj-files "src-dev")))
          offenders (for [f (clj-files "src")
                          :let [form (read-ns-form f)]
                          :when form
                          :let [bad (filter dev-only (required-namespaces form))]
                          :when (seq bad)]
                      {:file (.getPath ^java.io.File f) :requires (vec bad)})]
      (is (empty? offenders)
          (str "src/ must not depend on src-dev/ — src-dev is absent from the "
               "production classpath, so these requires break the prod artifact "
               "at load time while dev keeps working:\n"
               (str/join "\n" (map pr-str offenders)))))))

(deftest boundary-test-is-actually-looking-at-something
  (testing "guards against the check silently passing because it found no files"
    (is (pos? (count (clj-files "src"))) "found no files under src/")
    (is (pos? (count (clj-files "src-dev"))) "found no files under src-dev/")))
