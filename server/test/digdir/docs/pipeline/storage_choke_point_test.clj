(ns digdir.docs.pipeline.storage-choke-point-test
  "Every Typesense DOCUMENT write in shipped code goes through
   `digdir.docs.pipeline.storage`.

   Storage is where a refused row fails the write (and, with it, the run). A
   write anywhere else could drop Typesense's per-row answer, or delete a row's
   old revision after its replacement was refused. So this test finds every
   reference to a `typesense.client` document-write function in `server/src`
   and `server/src-prod`, and requires each to be in storage.

   How a reference is found: per namespace, the alias (or `:refer`) under which
   it requires `typesense.client` is read from the source, and then every
   symbol naming one of the eight functions through it is a reference: a call,
   a var-quote, or an argument to `apply`/`partial`. Matching the text `ts/`
   would be wrong, because other namespaces are required as `ts` too.

   Deliberately NOT covered:
   - collection-schema writes (`create-collection!`, `update-collection!`,
     `delete-collection!`): no per-row answer;
   - `server/src-dev`, the hand-run research and operator tools that do not
     ship (`apply_phrases`, `apply_facts`, `apply_prune`, `sweep/phrase_prune`,
     `sweep/rechunk_migrate`);
   - storage's own operator backfills, which live in storage already."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private document-writers
  '#{create-document! upsert-document! update-document! delete-document!
     create-documents! upsert-documents! update-documents! delete-documents!})

(def ^:private storage-file "src/digdir/docs/pipeline/storage.clj")

(defn- code-only
  "`src` with comments, strings and regex literals blanked (lengths kept), so a
   function named in a docstring or a comment is not read as a reference."
  [src]
  (let [sb (StringBuilder.)
        n (count src)]
    (loop [i 0 state :code]
      (if (>= i n)
        (str sb)
        (let [c (.charAt ^String src i)]
          (case state
            :code (cond
                    (= c \;) (do (.append sb \space) (recur (inc i) :comment))
                    (= c \\) (do (.append sb c)
                                 (when (< (inc i) n) (.append sb (.charAt ^String src (inc i))))
                                 (recur (+ i 2) :code))
                    (= c \") (do (.append sb \space) (recur (inc i) :string))
                    :else (do (.append sb c) (recur (inc i) :code)))
            :comment (if (= c \newline)
                       (do (.append sb c) (recur (inc i) :code))
                       (do (.append sb \space) (recur (inc i) :comment)))
            :string (cond
                      (= c \\) (do (.append sb "  ") (recur (+ i 2) :string))
                      (= c \") (do (.append sb \space) (recur (inc i) :code))
                      :else (do (.append sb (if (= c \newline) c \space)) (recur (inc i) :string)))))))))

(def ^:private require-spec
  "A `[typesense.client ...]` libspec, including a nested `:refer [...]`."
  #"\[\s*typesense\.client\b([^\[\]]*(?:\[[^\]]*\][^\[\]]*)*)\]")

(defn- requires-of
  "The ways `code` requires `typesense.client`: {:aliases #{..} :refers #{..}}.
   The fully qualified name always counts, required or not."
  [code]
  (let [specs (map second (re-seq require-spec code))]
    {:aliases (set (keep #(second (re-find #":as\s+([^\s\]]+)" %)) specs))
     :refers (set (mapcat (fn [spec]
                            (cond
                              (re-find #":refer\s+:all" spec) (map str document-writers)
                              :else (some->> (re-find #":refer\s+\[([^\]]*)" spec) second
                                             (re-seq #"[^\s\]]+"))))
                          specs))}))

(def ^:private sym-char "[\\w*+!?<>=.'-]")

(defn references
  "Each `[fn-name line]` naming a `typesense.client` document writer in `src`."
  [src]
  (let [code (code-only src)
        {:keys [aliases refers]} (requires-of code)
        ;; the libspec itself names the referred fns: it is not a reference
        code (str/replace code require-spec #(str/replace (first %) #"[^\n]" " "))
        qualifiers (conj aliases "typesense.client")
        lines (str/split-lines code)]
    (vec (for [[i line] (map-indexed vector lines)
               w (sort (map str document-writers))
               :let [qualified (for [q qualifiers]
                                 (re-pattern (str "(?<![\\w*+!?<>=.-])" (java.util.regex.Pattern/quote (str q "/" w))
                                                  "(?!" sym-char ")")))
                     bare (when (contains? refers w)
                            [(re-pattern (str "(?<![\\w*+!?<>=./-])" (java.util.regex.Pattern/quote w)
                                              "(?!" sym-char ")"))])]
               rx (concat qualified bare)
               _ (re-seq rx line)]
           [w (inc i)]))))

(defn- source-files []
  (for [root ["src" "src-prod"]
        :let [dir (io/file root)]
        :when (.isDirectory dir)
        f (file-seq dir)
        :when (and (.isFile f) (re-find #"\.clj[cs]?$" (.getName f)))]
    f))

(deftest the-reference-finder-resolves-the-alias-per-namespace
  ;; Planted controls: the finder must see each shape it claims to see, and must
  ;; not flag another namespace that happens to be required under the same alias.
  (testing "a call through the typesense.client alias is a reference"
    (is (= [["upsert-documents!" 2]]
           (references "(ns x (:require [typesense.client :as ts]))\n(ts/upsert-documents! s c rows)"))))
  (testing "the same text under an alias for another namespace is not"
    (is (= [] (references "(ns x (:require [digdir.something :as ts]))\n(ts/upsert-documents! s c rows)"))))
  (testing "a var-quote, apply, partial and the fully qualified name all count"
    (is (= #{"delete-documents!" "upsert-document!" "update-documents!" "create-documents!"}
           (set (map first (references (str "(ns x (:require [typesense.client :as tc]))\n"
                                            "(def f #'tc/delete-documents!)\n"
                                            "(apply tc/upsert-document! args)\n"
                                            "(partial tc/update-documents! s)\n"
                                            "(typesense.client/create-documents! s c rows)")))))))
  (testing "a referred name counts bare"
    (is (= [["upsert-documents!" 2]]
           (references "(ns x (:require [typesense.client :refer [upsert-documents!]]))\n(upsert-documents! s c rows)"))))
  (testing "a read (search) and a schema write (create-collection!) are not document writes"
    (is (= [] (references "(ns x (:require [typesense.client :as ts]))\n(ts/search s c q) (ts/create-collection! s schema)"))))
  (testing "a comment or a docstring naming the function is not a reference"
    (is (= [] (references "(ns x (:require [typesense.client :as ts]))\n;; (ts/upsert-documents! s c rows)\n(defn f \"calls ts/delete-documents!\" [])")))))

(deftest every-typesense-document-write-goes-through-storage
  (let [hits (for [f (source-files)
                   :let [path (str/replace (.getPath ^java.io.File f) #"^\./" "")]
                   [w line] (references (slurp f))]
               {:file path :fn w :line line})
        outside (remove #(= storage-file (:file %)) hits)]
    (is (seq (filter #(= storage-file (:file %)) hits))
        "PREMISE: storage itself writes through typesense.client, so the finder can see a write")
    (is (empty? outside)
        (str "a Typesense document write outside " storage-file
             " (route it through storage, where a refused row fails the write): "
             (pr-str (vec outside))))))
