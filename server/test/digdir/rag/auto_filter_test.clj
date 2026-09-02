(ns digdir.rag.auto-filter-test
  "Unit tests for auto-filter org detection.
   Uses stubbed facet cache to avoid Typesense dependency."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.rag.auto-filter :as auto-filter]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-facet-values
  {"orgs_long" [{:name "Digitaliseringsdirektoratet" :count 145}
                {:name "Digdir" :count 12}
                {:name "Fiskeridirektoratet" :count 80}
                {:name "Petoro" :count 50}
                {:name "Sikt" :count 30}
                {:name "Sametinget" :count 25}
                {:name "IT" :count 5}]
   "orgs_short" [{:name "DFD" :count 145}
                  {:name "FDir" :count 80}
                  {:name "IT" :count 5}
                  {:name "AS" :count 3}]})

(defn with-stubbed-cache [f]
  ;; Populate the cache atom directly so detect-org-filters doesn't call Typesense
  (auto-filter/clear-facet-cache!)
  (let [cache-atom @#'auto-filter/!facet-cache]
    (reset! cache-atom
            {"test-docs" {:values test-facet-values
                          :fetched-at (System/currentTimeMillis)}})
    (f)
    (auto-filter/clear-facet-cache!)))

(use-fixtures :each with-stubbed-cache)

;; =============================================================================
;; Tests
;; =============================================================================

(deftest basic-org-detection
  (testing "Detects short org name in query"
    (let [result (auto-filter/detect-org-filters
                   ["årsverk Digdir 2022"] "test-docs" {})]
      (is (some? result))
      (is (= "orgs_long" (get-in result [:fields 0 :field])))
      (is (contains? (get-in result [:fields 0 :selected-options]) "Digdir")))))

(deftest full-name-detection
  (testing "Detects full organization name in query"
    (let [result (auto-filter/detect-org-filters
                   ["ressursbruk Digitaliseringsdirektoratet 2022"] "test-docs" {})]
      (is (some? result))
      (is (= "orgs_long" (get-in result [:fields 0 :field])))
      (is (contains? (get-in result [:fields 0 :selected-options])
                     "Digitaliseringsdirektoratet")))))

(deftest multiple-orgs-detection
  (testing "Detects multiple organizations in a single query"
    (let [result (auto-filter/detect-org-filters
                   ["Digdir vs Fiskeridirektoratet"] "test-docs" {})]
      (is (some? result))
      (is (= "orgs_long" (get-in result [:fields 0 :field])))
      (is (= #{"Digdir" "Fiskeridirektoratet"}
             (get-in result [:fields 0 :selected-options]))))))

(deftest no-match-returns-nil
  (testing "Returns nil when no org names found"
    (let [result (auto-filter/detect-org-filters
                   ["årsverk 2022"] "test-docs" {})]
      (is (nil? result)))))

(deftest short-values-excluded
  (testing "Facet values shorter than 3 chars are excluded"
    (let [result (auto-filter/detect-org-filters
                   ["IT-systemer 2022"] "test-docs" {})]
      (is (nil? result)
          "\"IT\" (2 chars) should not match despite being a facet value"))))

(deftest case-insensitive-matching
  (testing "Matches are case-insensitive"
    (let [result (auto-filter/detect-org-filters
                   ["digitaliseringsdirektoratet"] "test-docs" {})]
      (is (some? result))
      (is (contains? (get-in result [:fields 0 :selected-options])
                     "Digitaliseringsdirektoratet")))))

(deftest word-boundary-matching
  (testing "Word boundaries prevent partial matches"
    (let [result (auto-filter/detect-org-filters
                   ["direktoratet 2022"] "test-docs" {})]
      (is (nil? result)
          "\"direktoratet\" should NOT match \"Fiskeridirektoratet\" due to word boundary"))))

(deftest orgs-short-fallback
  (testing "Falls back to orgs_short when orgs_long has no matches"
    (let [result (auto-filter/detect-org-filters
                   ["DFD årsverk 2022"] "test-docs" {})]
      (is (some? result))
      (is (= "orgs_short" (get-in result [:fields 0 :field])))
      (is (contains? (get-in result [:fields 0 :selected-options]) "DFD")))))

(deftest orgs-long-preferred-over-short
  (testing "Prefers orgs_long when both have matches"
    (let [result (auto-filter/detect-org-filters
                   ["Digdir DFD årsverk"] "test-docs" {})]
      (is (some? result))
      (is (= "orgs_long" (get-in result [:fields 0 :field]))
          "orgs_long should be preferred even when orgs_short also matches"))))

(deftest empty-queries-returns-nil
  (testing "Returns nil for empty query vector"
    (is (nil? (auto-filter/detect-org-filters [] "test-docs" {}))))

  (testing "Returns nil for nil queries"
    (is (nil? (auto-filter/detect-org-filters nil "test-docs" {})))))

(deftest multiple-queries-concatenated
  (testing "Matches across multiple query strings"
    (let [result (auto-filter/detect-org-filters
                   ["årsverk 2022" "ressurser Digdir"] "test-docs" {})]
      (is (some? result))
      (is (contains? (get-in result [:fields 0 :selected-options]) "Digdir")))))

(deftest filter-map-structure
  (testing "Returns correctly structured filter map"
    (let [result (auto-filter/detect-org-filters
                   ["Digdir 2022"] "test-docs" {})]
      (is (map? result))
      (is (vector? (:fields result)))
      (is (= 1 (count (:fields result))))
      (let [field (first (:fields result))]
        (is (= :multiselect (:type field)))
        (is (= :string (:value-type field)))
        (is (set? (:selected-options field)))))))

(deftest query-year-detection
  (testing "Detects year and creates title contains filter"
    (let [result (auto-filter/detect-query-filters
                   ["Hvor mange årsverk hadde Digdir i 2022?"] "test-docs" {})]
      (is (some? result))
      (is (= "title" (get-in result [:fields 1 :field])))
      (is (= :contains (get-in result [:fields 1 :type])))
      (is (= #{"2022"} (get-in result [:fields 1 :selected-options]))))))

(deftest query-filter-merges-org-and-year
  (testing "Detects both org and year in a combined filter map"
    (let [result (auto-filter/detect-query-filters
                   ["Digdir årsverk 2023"] "test-docs" {})
          fields (:fields result)
          org-field (first (filter #(= "orgs_long" (:field %)) fields))
          year-field (first (filter #(= "title" (:field %)) fields))]
      (is (some? org-field))
      (is (some? year-field))
      (is (= #{"Digdir"} (:selected-options org-field)))
      (is (= #{"2023"} (:selected-options year-field))))))
