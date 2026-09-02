(ns digdir.tools.namespace-decomposition
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def def-like-heads
  #{'def 'defn 'defn- 'defmacro 'defmulti 'defmethod 'defprotocol 'defrecord 'deftype})

(defn- safe-sexpr
  [zloc]
  (try
    (z/sexpr zloc)
    (catch Exception _
      ::unreadable)))

(defn- top-level-locs
  [root]
  (loop [loc root
         acc []]
    (if loc
      (recur (z/right loc) (conj acc loc))
      acc)))

(defn- form-kind
  [form]
  (when (seq? form)
    (let [head (first form)]
      (cond
        (= head 'ns) :ns
        (contains? def-like-heads head) (keyword (name head))
        :else :other))))

(defn- form-name
  [kind form]
  (case kind
    :ns (second form)
    :defmethod (nth form 2 nil)
    :other nil
    (second form)))

(defn- form-position
  [zloc]
  (let [m (meta (z/node zloc))]
    (select-keys m [:row :col :end-row :end-col])))

(defn audit-file
  [path]
  (let [root (z/of-file path)
        forms (->> (top-level-locs root)
                   (map (fn [loc] [loc (safe-sexpr loc)]))
                   (remove (fn [[_ form]] (or (nil? form)
                                              (= ::unreadable form)
                                              (not (seq? form)))))
                   (map (fn [[loc form]]
                          (let [kind (form-kind form)]
                            (merge
                             {:kind kind
                              :name (form-name kind form)
                              :form form}
                             (form-position loc)))))
                   vec)
        defs (filter #(contains? def-like-heads (symbol (name (:kind %)))) forms)]
    {:path path
     :namespace (some-> forms first :name)
     :form-count (count forms)
     :def-count (count defs)
     :forms forms}))

(defn file-line-count
  [path]
  (with-open [r (io/reader path)]
    (count (line-seq r))))

(defn inventory-entry
  [path]
  (let [{:keys [form-count def-count]} (audit-file path)]
    {:path path
     :line-count (file-line-count path)
     :form-count form-count
     :def-count def-count}))

(defn inventory-files
  [paths]
  (->> paths
       (map inventory-entry)
       (sort-by (juxt :line-count :form-count)
                (fn [a b] (compare b a)))
       vec))

(defn- namespace-file?
  [f]
  (and (.isFile f)
       (re-find #"\.clj[cs]?$" (.getName f))))

(defn- namespace-root-dir
  [path]
  (let [file (io/file path)
        base-name (some-> (.getName file)
                          (str/replace #"\.clj[cs]?$" ""))
        parent (.getParentFile file)
        child (when parent
                (io/file parent base-name))]
    (when (and child (.exists child))
      child)))

(defn- sibling-namespace-files
  [path]
  (if-let [root-dir (namespace-root-dir path)]
    (->> (file-seq root-dir)
         (filter namespace-file?)
         (map #(.getPath ^java.io.File %))
         vec)
    []))

(defn- extracted-names-from-files
  [paths]
  (->> paths
       (map audit-file)
       (mapcat (fn [audit]
                 (->> (:forms audit)
                      (filter #(contains? def-like-heads (symbol (name (:kind %)))))
                      (map :name))))
       (remove nil?)
       (map (fn [x]
              (cond
                (string? x) x
                (keyword? x) (name x)
                (symbol? x) (name x)
                :else nil)))
       (remove nil?)
       set))

(defn- form-name-string
  [form-name]
  (cond
    (string? form-name) form-name
    (keyword? form-name) (name form-name)
    (symbol? form-name) (name form-name)
    :else nil))

(defn- leading-segment
  [form-name]
  (some-> (form-name-string form-name)
          (str/split #"-" 2)
          first))

(defn- delegation-wrapper?
  [form]
  (when (and (seq? form)
             (contains? #{'defn 'defn-} (first form)))
    (let [form-name (second form)
          body (->> (drop 2 form)
                    (drop-while #(or (string? %) (map? %))))]
      (when (and (= 2 (count body))
                 (vector? (first body))
                 (seq? (second body)))
        (let [call-form (second body)
              call-head (first call-form)]
          (and (symbol? call-head)
               (= (clojure.core/name form-name)
                  (clojure.core/name call-head))))))))

(defn- candidate-first-split-names
  [audit known-names]
  (let [forms (->> (:forms audit)
                   (drop-while #(not= :ns (:kind %)))
                   rest)]
    (->> forms
         (take-while #(not= "bootstrap-root-defaults" (form-name-string (:name %))))
         (filter #(contains? def-like-heads (symbol (name (:kind %)))))
         (remove #(delegation-wrapper? (:form %)))
         (remove #(contains? known-names (form-name-string (:name %))))
         (mapv :name)
         (remove #{'iso-timestamp})
         vec)))

(defn recommend-first-split
  [path]
  (let [audit (audit-file path)
        extracted-names (extracted-names-from-files (sibling-namespace-files path))
        names (candidate-first-split-names audit extracted-names)]
    {:path path
     :namespace (:namespace audit)
     :extracted-names (sort extracted-names)
     :recommended-target (when (seq names)
                           (cond
                             (and (str/ends-with? path "config/ops.clj")
                                  (empty? extracted-names))
                             "digdir.config.ops.util"

                             (str/ends-with? path "config/ops.clj")
                             (str "digdir.config.ops." (or (leading-segment (first names))
                                                           "extracted"))

                             :else
                             (str (name (:namespace audit)) ".extracted")))
     :recommended-names names
     :reason (cond
               (and (str/ends-with? path "config/ops.clj")
                    (empty? extracted-names))
               "These are the pre-bootstrap helper/constant defs at the top of the namespace, and they are the safest first extraction because they are pure and highly localized."

               (seq names)
               "These are the earliest contiguous def-like forms that have not already been extracted into sibling namespaces."

               :else
               "No obvious contiguous helper slice was detected from the namespace audit.")}))

(defn- target-namespace-path
  [target-namespace]
  (str "src/" (str/replace (name target-namespace) "." "/") ".clj"))

(defn- ns-form->target-namespace
  [ns-form target-namespace]
  (if (and (seq? ns-form)
           (= 'ns (first ns-form)))
    (concat (list 'ns target-namespace) (drop 2 ns-form))
    (throw (ex-info "Source file does not start with an ns form"
                    {:ns-form ns-form
                     :target-namespace target-namespace}))))

(defn- line-start-offsets
  [text]
  (loop [idx 0
         starts [0]]
    (if (< idx (count text))
      (if (= \newline (nth text idx))
        (recur (inc idx) (conj starts (inc idx)))
        (recur (inc idx) starts))
      starts)))

(defn- position->offset
  [line-starts {:keys [row col]}]
  (+ (nth line-starts (dec row))
     (dec col)))

(defn- remove-spans
  [text spans]
  (let [spans (sort-by first > spans)]
    (reduce (fn [acc [start end]]
              (str (subs acc 0 start)
                   (subs acc end)))
            text
            spans)))

(defn extract-top-level-forms!
  [source-path target-namespace names]
  (let [target-names (set (map name names))
        audit (audit-file source-path)
        source-text (slurp source-path)
        line-starts (line-start-offsets source-text)
        selected-forms (->> (:forms audit)
                            (filter #(contains? target-names (some-> % :name name)))
                            vec)
        spans (mapv (fn [{:keys [row col end-row end-col]}]
                      [(position->offset line-starts {:row row :col col})
                       (position->offset line-starts {:row end-row :col (inc end-col)})])
                    selected-forms)
        source-ns-form (:form (first (:forms audit)))
        remaining-text (remove-spans source-text spans)
        target-path (target-namespace-path target-namespace)
        target-ns-form (ns-form->target-namespace source-ns-form target-namespace)]
    (spit source-path remaining-text)
    (spit target-path
          (str (pr-str target-ns-form)
               "\n\n"
               (str/join "\n\n" (map #(pr-str (:form %)) selected-forms))
               "\n"))
    {:source-path source-path
     :target-path target-path
     :target-namespace target-namespace
     :moved-names (mapv (comp name :name) selected-forms)
     :moved-count (count selected-forms)}))

(defn print-audit!
  [path]
  (let [{:keys [namespace form-count def-count forms]} (audit-file path)]
    (println (str "File: " path))
    (println (str "Namespace: " (or namespace "(unknown)")))
    (println (str "Top-level forms: " form-count))
    (println (str "Def-like forms: " def-count))
    (doseq [{:keys [kind name row col]} forms]
      (println (str "  - " (clojure.core/name kind)
                    (when name (str " " name))
                    (when row (str " @" row ":" col)))))
    forms))

(defn print-inventory!
  [paths]
  (doseq [{:keys [path line-count form-count def-count]} (inventory-files paths)]
    (println (str line-count " lines  "
                  form-count " forms  "
                  def-count " defs  "
                  path))))

(defn print-first-split!
  [path]
  (let [{:keys [namespace recommended-target recommended-names reason]} (recommend-first-split path)]
    (println (str "Namespace: " namespace))
    (println (str "Target: " (or recommended-target "(none)")))
    (println (str "Names: " (if (seq recommended-names)
                              (clojure.string/join ", " (map name recommended-names))
                              "(none)")))
    (println (str "Why: " reason))))

(defn print-extract!
  [source-path target-namespace names]
  (let [result (extract-top-level-forms! source-path target-namespace names)]
    (println (str "Source: " (:source-path result)))
    (println (str "Target: " (:target-path result)))
    (println (str "Namespace: " (:target-namespace result)))
    (println (str "Moved: " (clojure.string/join ", " (:moved-names result))))
    (println (str "Count: " (:moved-count result)))
    result))

(defn -main
  [& args]
  (let [[cmd & more] args]
    (case cmd
      "audit"
      (let [path (first more)]
        (when (str/blank? path)
          (throw (ex-info "Usage: namespace-decomposition audit <file.clj|file.cljc>" {})))
        (print-audit! path))

      "inventory"
      (do
        (when (empty? more)
          (throw (ex-info "Usage: namespace-decomposition inventory <file.clj|file.cljc>..." {})))
        (print-inventory! more))

      "first-split"
      (let [path (first more)]
        (when (str/blank? path)
          (throw (ex-info "Usage: namespace-decomposition first-split <file.clj|file.cljc>" {})))
        (print-first-split! path))

      "extract"
      (let [[source-path target-namespace & names] more]
        (when (or (str/blank? source-path)
                  (str/blank? target-namespace)
                  (empty? names))
          (throw (ex-info "Usage: namespace-decomposition extract <source.clj> <target.ns> <form-name>..." {})))
        (print-extract! source-path (symbol target-namespace) names))

      (do
        (when (str/blank? cmd)
          (throw (ex-info "Usage: namespace-decomposition <audit|inventory|first-split> ..." {})))
        (print-audit! cmd)))))
