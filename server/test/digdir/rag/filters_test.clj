(ns digdir.rag.filters-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.rag.filters :as filters]))

(deftest filter-map-errors
  (testing "A well-formed caller filter has no errors"
    (is (empty? (filters/filter-map-errors
                 {:fields [{:field "orgs_short" :selected-options ["Digdir" "Skatteetaten"]}
                           {:field "concerned_years" :value-type "integer" :selected-options [2022 2023]}
                           {:field "title" :type "contains" :value "årsrapport"}]}))))

  (testing "No filter at all is fine"
    (is (empty? (filters/filter-map-errors nil))))

  (testing "Each way out of the quoting is refused"
    (doseq [bad [{:fields "type"}
                 {:fields [{:field "type" :selected-options ["a`b"]}]}
                 {:fields [{:field "type) || (x" :selected-options ["a"]}]}
                 {:fields [{:field "year" :value-type "integer" :selected-options ["1 || x:=1"]}]}
                 {:fields [{:field "type" :type "range" :selected-options ["a"]}]}
                 {:fields [{:field "type" :selected-options [{:nested "map"}]}]}
                 {:fields [{:field "type" :selected-options ["a\\"]}]}
                 {:fields [{:field "type" :selected-options ["a\nb"]}]}]]
      (is (seq (filters/filter-map-errors bad)) (pr-str bad))))

  (testing "Unbounded input is refused"
    (is (seq (filters/filter-map-errors
              {:fields [{:field "type" :selected-options (repeat 101 "a")}]})))
    (is (seq (filters/filter-map-errors
              {:fields [{:field "type" :selected-options [(apply str (repeat 257 "a"))]}]})))))

(deftest merge-filter-maps
  (testing "The primary map wins on a field both name"
    (is (= {:fields [{:field "type" :selected-options ["Evaluering"]}
                     {:field "orgs_short" :selected-options ["Digdir"]}]}
           (filters/merge-filter-maps
            {:fields [{:field "type" :selected-options ["Evaluering"]}]}
            {:fields [{:field "type" :selected-options ["Årsrapport"]}
                      {:field "orgs_short" :selected-options ["Digdir"]}]}))))

  (testing "Nothing on either side is no filter"
    (is (nil? (filters/merge-filter-maps nil nil)))))

(deftest integer-filters-accept-digit-strings
  (is (empty? (filters/filter-map-errors
               {:fields [{:field "year" :value-type :integer :selected-options #{"2022"}}]}))))

(deftest a-filter-that-would-filter-nothing-is-refused
  (doseq [bad [{:fields []}
               {:fields [{:field "type" :selected-options []}]}
               {:fields [{:field "type"}]}
               {:fields [{:field "type" :selected_options ["Evaluering"]}]}]]
    (is (seq (filters/filter-map-errors bad)) (pr-str bad))))

(deftest a-filter-takes-at-most-twenty-fields
  (is (seq (filters/filter-map-errors
            {:fields (vec (repeat 21 {:field "type" :selected-options ["a"]}))}))))

(deftest normalize-filter-map
  (is (= {:fields [{:field "type" :type :contains :value-type :string :selected-options ["a"]}
                   {:field "orgs_long" :selected-options ["b"]}]}
         (filters/normalize-filter-map
          {:fields [{:field "type" :type "contains" :value-type "string" :selected-options ["a"]}
                    {:field "orgs_long" :selected-options ["b"]}]}))))

