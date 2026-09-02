(ns digdir.rag.filters
  "Pure filter serialization helpers for Typesense queries."
  (:require [clojure.string :as str]))

(defn format-filter-value
  "Format a filter value for Typesense. Integers don't need backticks, strings do."
  [value value-type]
  (if (= value-type :integer)
    (str value)
    (str "`" value "`")))

(defn- format-contains-filter-value
  "Format a value for non-exact (contains/token) string filtering."
  [value]
  (let [s (str value)]
    (if (re-matches #"\d+" s)
      s
      (str "`" s "`"))))

(defn- field-spec->typesense-clause
  "Convert a single filter field spec to a Typesense clause."
  [{:keys [type field selected-options value value-type]}]
  (let [field-type (keyword (name (or type :multiselect)))
        options (->> (or selected-options
                         (when (some? value) #{value}))
                     (remove nil?)
                     seq)]
    (cond
      (or (nil? field) (not (seq options)))
      nil

      (= field-type :contains)
      (let [contains-clauses (map #(str field ":" (format-contains-filter-value %)) options)]
        (if (= 1 (count contains-clauses))
          (first contains-clauses)
          (str "(" (str/join " || " contains-clauses) ")")))

      (= field-type :not-in-set)
      ;; Emits `field:!=[v1,v2,...]` — useful for the permissive variant
      ;; of a one-of classifier, where you want to MATCH the named
      ;; value(s) but also include docs that have no value at all
      ;; (e.g. partial-coverage frontmatter fields). The caller computes
      ;; the COMPLEMENT of the desired value(s) against a closed
      ;; universe and passes that as :selected-options.
      (str field ":!=["
           (str/join ","
                     (map #(format-filter-value % value-type) options))
           "]")

      :else
      (str field ":=["
           (str/join ","
                     (map #(format-filter-value % value-type) options))
           "]"))))

(defn filter-map->typesense-filter
  "Converts a filter map to a Typesense filter_by string for use when
   the outer query targets a *different* collection from the filter
   fields (typical: chunks-collection query, docs-collection filter).
   Wraps the filter in `$<docs-collection>(...)` reference-filter syntax."
  [{:keys [fields]} docs-collection-name]
  (let [selected-fields (->> fields
                             (map field-spec->typesense-clause)
                             (remove nil?))]
    (when (seq selected-fields)
      (str "$" docs-collection-name "("
           (str/join " && " selected-fields)
           ")"))))

(defn filter-map->typesense-direct-filter
  "Same as `filter-map->typesense-filter` but without the
   `$<collection>(...)` reference wrapper. Use when the outer query
   targets the same collection as the filter fields (e.g. a docs-by-title
   search using `language`/`diataxis` filters against the docs
   collection itself)."
  [{:keys [fields]}]
  (let [selected-fields (->> fields
                             (map field-spec->typesense-clause)
                             (remove nil?))]
    (when (seq selected-fields)
      (str/join " && " selected-fields))))

(defn filter-map->typesense-facet-multi-search
  "Converts a filter map to a Typesense multi-search with disjunctive faceting"
  [{:keys [fields max-options]} docs-collection-name]
  (let [all-field-names (map :field fields)
        fields-with-selections (filter (fn [{:keys [selected-options value]}]
                                         (or (not-empty selected-options)
                                             (some? value)))
                                       fields)
        create-filter-str (fn [excluded-field]
                            (->> fields-with-selections
                                 (remove #(= (:field %) excluded-field))
                                 (map field-spec->typesense-clause)
                                 (remove nil?)
                                 (str/join " && ")))
        all-facets (into {}
                         (remove (fn [[_ v]] (#{"" nil} v)))
                         (merge
                          {:q "*"
                           :query_by "doc_num"
                           :facet_by (str/join "," all-field-names)
                           :page 1
                           :max_facet_values (or max-options 300)
                           :per_page 6
                           :collection docs-collection-name}))
        main-search (into {}
                          (remove (fn [[_ v]] (#{"" nil} v)))
                          (merge
                           {:q "*"
                            :query_by "doc_num"
                            :facet_by (str/join "," all-field-names)
                            :page 1
                            :max_facet_values (or max-options 300)
                            :per_page 6
                            :collection docs-collection-name}
                           (when (seq fields-with-selections)
                             {:filter_by (create-filter-str nil)})))
        facet-searches (map (fn [{:keys [field]}]
                              (into {}
                                    (remove (fn [[_ v]] (#{"" nil} v)))
                                    (merge
                                     {:q "*"
                                      :query_by "doc_num"
                                      :facet_by field
                                      :page 1
                                      :max_facet_values (or max-options 300)
                                      :collection docs-collection-name}
                                     (when-let [filter-str (create-filter-str field)]
                                       {:filter_by filter-str}))))
                            fields-with-selections)]
    {:searches (vec (concat [all-facets main-search] facet-searches))}))
