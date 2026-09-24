(ns digdir.rag.filters-value-type-test
  "value-type is compared by name, so a filter from MCP works on numeric fields.

   Over MCP the transport keywordizes keys, not values, so value-type arrives as
   \"integer\". Compared with = against :integer it quoted the number, and
   Typesense refused the filter on a numeric field — measured on
   concerned_years, which then returned 0 chunks with no error."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.filters :as filters]))

(deftest integer-by-keyword-or-string
  (testing "an integer is not quoted, whether value-type is a keyword or a string"
    (is (= "2024" (filters/format-filter-value 2024 :integer)))
    (is (= "2024" (filters/format-filter-value "2024" "integer")))))

(deftest strings-stay-quoted
  (testing "a string value is quoted, and so is anything without a value-type"
    (is (= "`Årsrapport`" (filters/format-filter-value "Årsrapport" :string)))
    (is (= "`Årsrapport`" (filters/format-filter-value "Årsrapport" "string")))
    (is (= "`Årsrapport`" (filters/format-filter-value "Årsrapport" nil)))))

(deftest a-year-filter-from-mcp-builds-a-numeric-clause
  (testing "the whole path: a string value-type yields a clause Typesense accepts"
    (is (= "$docs(concerned_years:=[2024])"
           (filters/filter-map->typesense-filter
             {:fields [{:field "concerned_years"
                        :selected-options ["2024"]
                        :value-type "integer"}]}
             "docs")))))

(deftest a-value-cannot-become-filter-syntax
  (testing "an integer that is not a number is dropped, so it cannot close the list and add a clause"
    (is (nil? (filters/filter-map->typesense-filter
                {:fields [{:field "concerned_years"
                           :selected-options ["2024] || type:=[`Tildelingsbrev`"]
                           :value-type "integer"}]}
                "docs"))))
  (testing "a string with a backtick is dropped, so it cannot end its own quoting"
    (is (nil? (filters/filter-map->typesense-filter
                {:fields [{:field "type" :selected-options ["Årsrapport`] || orgs_long:=[`x"]}]}
                "docs"))))
  (testing "the safe values beside an unsafe one are kept"
    (is (= "$docs(concerned_years:=[2023,2024])"
           (filters/filter-map->typesense-filter
             {:fields [{:field "concerned_years"
                        :selected-options ["2023" "2024] || x:=[1" "2024"]
                        :value-type "integer"}]}
             "docs")))))

(deftest ordinary-values-are-safe
  (testing "the values a real filter carries are all accepted"
    (is (filters/safe-filter-value? "2024" "integer"))
    (is (filters/safe-filter-value? "-1" :integer))
    (is (filters/safe-filter-value? "Direktoratet for forvaltning og økonomistyring" :string))
    (is (filters/safe-filter-value? "Årsrapport" nil))))
