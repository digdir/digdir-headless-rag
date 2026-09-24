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
