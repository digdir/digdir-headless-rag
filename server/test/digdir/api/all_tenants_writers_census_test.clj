(ns digdir.api.all-tenants-writers-census-test
  "WHO touches the all-tenant marker, by
   ATTRIBUTE, over every source root (src, src-dev, src-prod).

   - Every top-level form whose CODE names `:access-policy/all-tenants?` is
     classified: the schema, the reads, the ONE setter (`set-all-tenants!`), and
     the COPIES (the importer and the canonical export, pinned behaviourally by
     `digdir.import-export.all-tenants-carry-test`). An unclassified form is red.
   - The callers of `set-all-tenants!` are exactly the console route, the key
     UI's admin-checked door, and the e2e seed. A fourth is red.

   Code is read as CODE (reader conditionals allowed, any alias accepted): a
   name in a docstring or a comment is not a reference. Both have planted
   controls. A file the census cannot read is red, not skipped."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]))

(def ^:private attribute :access-policy/all-tenants?)

(def ^:private roots ["src" "src-dev" "src-prod"])

(def ^:private expected-attribute-sites
  "Each form that names the attribute, and what it does with it."
  {'digdir.data.db/access-policy-schema :schema
   'digdir.config.api-keys/access-policy-base-pull-pattern :read
   'digdir.config.api-keys/all-tenants-marked? :read
   'digdir.config.api-keys/set-all-tenants! :set
   'digdir.import-export.entities.api-keys/imported-all-tenants-marker :read-the-record
   'digdir.import-export.entities.api-keys/import-api-keys-tx :copy
   'digdir.import-export.entities.api-keys/all-tenant-keys-written :read-back-for-the-report
   'digdir.import-export.canonical.system/normalize-api-key-export :copy})

(def ^:private expected-setter-callers
  #{'digdir.api.routes.handlers/set-api-key-all-tenants-handler
    'digdir.config.ui.api-keys/set-all-tenants-as-admin!
    'digdir.e2e.seed/seed!})

(defn- code-forms
  "Every top-level form of the Clojure source `text`, read as CODE."
  [text]
  (binding [*read-eval* false
            *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                (currentNS [_] 'user)
                                (resolveClass [_ s] s)
                                (resolveAlias [_ s] s)
                                (resolveVar [_ s] s))]
    (with-open [r (java.io.PushbackReader. (java.io.StringReader. text))]
      (doall (take-while #(not= ::eof %) (repeatedly #(read {:read-cond :allow :eof ::eof} r)))))))

(def ^:private def-heads #{"def" "defn" "defn-" "defmacro" "defonce"})

(defn- site-name
  "`ns/name` for a top-level def (plain or Electric's `e/defn`), else
   `ns/<head>` for any other top-level form."
  [ns-sym form]
  (let [head (when (seq? form) (first form))]
    (symbol (str ns-sym)
            (str (if (and (symbol? head) (def-heads (name head)) (symbol? (second form)))
                   (second form)
                   (str "<" head ">"))))))

(defn- sites
  "The top-level forms of `text` whose code contains an element satisfying
   `pred`, as `ns/name` symbols."
  [text pred]
  (let [forms (code-forms text)
        ns-sym (or (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms) 'unknown-ns)]
    (set (for [form forms
               ;; `#?(:clj (do (defn a ...) (defn b ...)))` defines each var by its own name
               f (if (and (seq? form) (= 'do (first form))) (rest form) [form])
               :when (not (and (seq? f) (= 'ns (first f))))
               :when (some pred (tree-seq coll? seq f))]
           (site-name ns-sym f)))))

(defn- attribute-sites [text] (sites text #(= attribute %)))

(defn- setter-callers
  "Forms that reference `set-all-tenants!` by any alias (a call, `apply`, a
   var-quote, a higher-order pass), other than its own definition."
  [text]
  (disj (sites text #(and (symbol? %) (= "set-all-tenants!" (name %))))
        'digdir.config.api-keys/set-all-tenants!))

(defn- source-files []
  (for [root roots
        f (file-seq (io/file root))
        :when (re-find #"\.clj[cs]?$" (str f))]
    f))

(defn- census [site-fn]
  (let [results (for [f (source-files)]
                  (try {:sites (site-fn (slurp f))}
                       (catch Exception e {:unreadable (str f ": " (.getMessage e))})))]
    {:files (count results)
     :roots-read (set (for [f (source-files)] (first (clojure.string/split (str f) #"/"))))
     :unreadable (vec (keep :unreadable results))
     :sites (apply set/union (keep :sites results))}))

(deftest the-census-can-see-what-it-must-refuse
  (testing "CONTROL: a planted writer of the attribute is found, under its own name"
    (is (= #{'x/sneaky} (attribute-sites "(ns x) (defn sneaky [c p] (d/transact c [[:db/add p :access-policy/all-tenants? true]]))")))
    (is (= #{'x/also} (attribute-sites "(ns x) #?(:clj (defn also [p] {:db/id p :access-policy/all-tenants? true}))")))
    (is (= #{'x/inner} (attribute-sites "(ns x) #?(:clj (do (defn other [] nil) (defn inner [p] {:db/id p :access-policy/all-tenants? true})))"))
        "a var defined inside a `do` wrapper is named as itself"))
  (testing "CONTROL: a docstring or a comment is not a reference"
    (is (= #{} (attribute-sites "(ns x) (defn doc \"writes :access-policy/all-tenants?\" [] nil) ; :access-policy/all-tenants?"))))
  (testing "CONTROL: a caller of the setter is found by any alias and any form of reference"
    (is (= #{'x/a} (setter-callers "(ns x) (defn a [c] (api-keys/set-all-tenants! c \"k\" true {}))")))
    (is (= #{'x/b} (setter-callers "(ns x) (defn b [c] (#'k/set-all-tenants! c \"k\" true {}))")))
    (is (= #{'x/c} (setter-callers "(ns x) (e/defn c [c] (apply set-all-tenants! [c \"k\" true {}]))")))
    (is (= #{} (setter-callers "(ns x) (defn d \"calls set-all-tenants!\" [] nil) ; api-keys/set-all-tenants!")))))

(deftest every-form-that-names-the-marker-is-classified
  (let [{:keys [files roots-read unreadable sites]} (census attribute-sites)]
    (is (= (set roots) roots-read) "PREMISE: every source root is read")
    (is (< 250 files) "PREMISE: the census reads the source roots (272 files at 8fe2f61f)")
    (is (= [] unreadable) "a file the census cannot read could hide a writer")
    (is (= (set (keys expected-attribute-sites)) sites)
        (str "a form names :access-policy/all-tenants? without a classification (a second SETTER is red; "
             "a new copy or read must be declared here, with its parity pinned)"))))

(deftest the-one-setter-has-exactly-three-callers
  (let [{:keys [unreadable sites]} (census setter-callers)]
    (is (= [] unreadable))
    (is (= expected-setter-callers sites)
        "set-all-tenants! is called by the console route, the UI's admin-checked door and the e2e seed, and nothing else")))
