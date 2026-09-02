(ns digdir.rag.auto-filter
  "Automatic filter detection for search queries.
   Detects organization names and years, then returns filter maps that
   constrain retrieval to the likely relevant subset."
  (:require [clojure.string :as str]
            [typesense.client :as ts-client]
            [digdir.rag.auto-filter-rules :as auto-filter-rules]
            [digdir.rag.typesense :as ts-utils]))

;; =============================================================================
;; Facet Value Cache
;; =============================================================================

(def ^:private cache-ttl-ms
  "Cache TTL in milliseconds (5 minutes)"
  (* 5 60 1000))

(def ^:private !facet-cache
  "Atom storing cached facet values per collection.
   Shape: {collection-name {:values {\"orgs_long\" [{:name \"Digdir\" :count 145} ...]
                                     \"orgs_short\" [{:name \"DFD\" :count 12} ...]}
                            :fetched-at <epoch-ms>}}"
  (atom {}))

(defn clear-facet-cache!
  "Clear the facet cache. Useful for testing."
  []
  (reset! !facet-cache {}))

(defn- cache-valid?
  "Returns true if the cached entry for a collection is still within TTL."
  [collection]
  (when-let [entry (get @!facet-cache collection)]
    (< (- (System/currentTimeMillis) (:fetched-at entry)) cache-ttl-ms)))

;; =============================================================================
;; Facet Value Fetching
;; =============================================================================

(def ^:private org-facet-fields
  "Fields to fetch facet values for."
  ["orgs_long" "orgs_short"])

(defn- fetch-org-facet-values
  "Fetch org facet values from Typesense for the given docs collection.
   Returns {\"orgs_long\" [{:name \"Digdir\" :count 145} ...], \"orgs_short\" [...]}"
  [docs-collection opts]
  (let [ts-settings (ts-utils/make-ts-settings opts)
        multi-search {:searches (mapv (fn [field]
                                        {:collection docs-collection
                                         :q "*"
                                         :query_by "doc_num"
                                         :facet_by field
                                         :page 1
                                         :per_page 0
                                         :max_facet_values 500})
                                      org-facet-fields)}
        response (ts-client/multi-search ts-settings multi-search {:query_by "doc_num"})
        results (:results response)]
    (into {}
          (map (fn [field result]
                 [field (->> (get-in result [:facet_counts])
                             (filter #(= (:field_name %) field))
                             first
                             :counts
                             (mapv (fn [{:keys [value count]}]
                                     {:name value :count count})))])
               org-facet-fields results))))

(defn- get-cached-facet-values
  "Get facet values from cache, fetching from Typesense if expired or missing."
  [docs-collection opts]
  (if (cache-valid? docs-collection)
    (get-in @!facet-cache [docs-collection :values])
    (let [values (fetch-org-facet-values docs-collection opts)]
      (swap! !facet-cache assoc docs-collection
             {:values values :fetched-at (System/currentTimeMillis)})
      values)))

;; =============================================================================
;; Matching Algorithm
;; =============================================================================

(def ^:private min-facet-value-length
  "Minimum length for a facet value to be considered for matching.
   Prevents false positives from short abbreviations like \"IT\", \"AS\"."
  3)

(defn- build-word-boundary-pattern
  "Build a case-insensitive word-boundary regex for the given value."
  [value]
  (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote value) "\\b")))

(defn- find-matching-values
  "Find facet values that appear in the query text.
   Returns a set of matched value names."
  [facet-values query-text]
  (->> facet-values
       (filter #(>= (count (:name %)) min-facet-value-length))
       (filter (fn [{:keys [name]}]
                 (re-find (build-word-boundary-pattern name) query-text)))
       (map :name)
       set))

(defn detect-org-filters
  "Detect organization names in search queries and return a filter map.

   Concatenates all queries, matches against cached facet values for orgs_long
   and orgs_short. Prefers orgs_long matches over orgs_short.

   Returns a filter map like:
     {:fields [{:field \"orgs_long\" :selected-options #{\"Digdir\"} :value-type :string}]}
   or nil if no matches found."
  [queries docs-collection opts]
  (when (seq queries)
    (let [query-text (str/join " " queries)
          facet-values (get-cached-facet-values docs-collection opts)
          orgs-long-matches (find-matching-values (get facet-values "orgs_long") query-text)
          orgs-short-matches (find-matching-values (get facet-values "orgs_short") query-text)]
      (cond
        (seq orgs-long-matches)
        {:fields [{:type :multiselect
                   :field "orgs_long"
                   :selected-options orgs-long-matches
                   :value-type :string}]}

        (seq orgs-short-matches)
        {:fields [{:type :multiselect
                   :field "orgs_short"
                   :selected-options orgs-short-matches
                   :value-type :string}]}

        :else nil))))

(def ^:private year-pattern
  #"\b(19\d{2}|20\d{2}|21\d{2})\b")

(defn- detect-year-title-filter
  "Detect year mentions in queries and build a title contains filter.
   We intentionally use title contains (not concerned_years) due to data quality."
  [queries]
  (when (seq queries)
    (let [query-text (str/join " " queries)
          years (->> (re-seq year-pattern query-text)
                     (map second)
                     set)]
      (when (seq years)
        {:fields [{:type :contains
                   :field "title"
                   :selected-options years
                   :value-type :string}]}))))

(defn detect-query-filters
  "Detect all supported auto-filters from query text.

   Hardcoded detections (slated for migration into the rules engine
   in a follow-up slice — see
   `plans/proposed/retrieval-configurable-fields-rules-plan.md`):
   - org names -> orgs_long/orgs_short multiselect
   - years -> title contains

   Config-driven detections (rules engine, supplied via opts):
   - `(:auto-filter-rules opts)` — vector of rule specs handled by
     `digdir.rag.auto-filter-rules/detect-from-rules`. Each rule
     self-describes its `:rule/type` and parameters. Empty/absent
     preserves prior 3-detector behavior exactly.

   Returns nil when no filters are detected."
  [queries docs-collection opts]
  (let [org-filter (detect-org-filters queries docs-collection opts)
        year-filter (detect-year-title-filter queries)
        rules-filter (auto-filter-rules/detect-from-rules
                      (:auto-filter-rules opts) queries opts)
        merged-fields (vec (concat (:fields org-filter)
                                   (:fields year-filter)
                                   (:fields rules-filter)))]
    (when (seq merged-fields)
      {:fields merged-fields})))
