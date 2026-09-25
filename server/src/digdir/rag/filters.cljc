(ns digdir.rag.filters
  "Pure filter serialization helpers for Typesense queries."
  (:require [clojure.string :as str]))

(defn format-filter-value
  "Format a filter value for Typesense. Integers don't need backticks, strings do."
  [value value-type]
  (if (= "integer" (some-> value-type name))
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

(def ^:private field-name-pattern #"[A-Za-z_][A-Za-z0-9_.]*")
(def ^:private field-types #{"multiselect" "contains" "not-in-set"})
(def ^:private max-options 100)
(def ^:private max-value-length 256)
(def ^:private max-fields 20)
(def ^:private field-spec-keys #{:field :type :value-type :selected-options :value})

(defn- named [x]
  (when (or (string? x) (keyword? x)) (name x)))

(defn- field-spec-errors
  [spec]
  (if-not (map? spec)
    ["Each filter field must be an object."]
    (let [{:keys [field type value-type selected-options value]} spec
          options (cond
                    (or (sequential? selected-options) (set? selected-options)) selected-options
                    (some? selected-options) nil
                    (some? value) [value]
                    :else [])
          unknown (remove field-spec-keys (keys spec))]
      (cond-> []
        (seq unknown)
        (conj (let [listed (pr-str (vec (take 5 unknown)))]
                (str "Unknown filter field keys: " (subs listed 0 (min 200 (count listed))) ".")))

        (not (and (string? field) (re-matches field-name-pattern field)))
        (conj (let [shown (pr-str field)]
                (str "Filter field must be a plain field name; got " (subs shown 0 (min 80 (count shown))) ".")))

        (and (some? type) (not (field-types (named type))))
        (conj (str "Filter type must be one of " (sort field-types) "; got " (pr-str type) "."))

        (and (some? value-type) (not (#{"integer" "string"} (named value-type))))
        (conj (str "Filter value-type must be integer or string; got " (pr-str value-type) "."))

        (nil? options)
        (conj "Filter selected-options must be a list.")

        (and (some? options) (empty? options))
        (conj "A filter field needs at least one option.")

        (< max-options (count options))
        (conj (str "A filter field takes at most " max-options " options."))

        (some #(not (or (string? %) (integer? %))) options)
        (conj "Filter options must be strings or integers.")

        (some #(and (string? %) (re-find #"[`\\\x00-\x1f\x7f]" %)) options)
        (conj "Filter options cannot contain a backtick, a backslash or a control character.")

        (some #(and (string? %) (< max-value-length (count %))) options)
        (conj (str "Filter options are at most " max-value-length " characters."))

        (and (= "integer" (named value-type))
             (some #(not (re-matches #"-?\d+" (str %))) options))
        (conj "An integer filter takes only integer options.")))))

(defn filter-map-errors
  "Why a caller-supplied filter map cannot be serialised safely. Empty when it can."
  [filter-map]
  (cond
    (nil? filter-map) []
    (not (map? filter-map)) ["Filter must be an object with a fields list."]
    (not (sequential? (:fields filter-map))) ["Filter fields must be a list."]
    (empty? (:fields filter-map)) ["Filter fields must not be empty."]
    (< max-fields (count (:fields filter-map))) [(str "A filter takes at most " max-fields " fields.")]
    :else (vec (mapcat field-spec-errors (:fields filter-map)))))

(defn normalize-filter-map
  "Keywordize each field's :type and :value-type, as retrieval's own filters carry them."
  [filter-map]
  (when filter-map
    (update filter-map :fields
            (partial mapv #(cond-> %
                             (:type %) (update :type (comp keyword named))
                             (:value-type %) (update :value-type (comp keyword named)))))))

(defn merge-filter-maps
  "Fields of `primary`, plus those of `secondary` on fields `primary` does not name."
  [primary secondary]
  (let [named-fields (set (map :field (:fields primary)))
        fields (vec (concat (:fields primary)
                            (remove #(contains? named-fields (:field %)) (:fields secondary))))]
    (when (seq fields)
      {:fields fields})))

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
