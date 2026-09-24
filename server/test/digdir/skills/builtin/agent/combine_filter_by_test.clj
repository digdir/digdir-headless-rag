(ns digdir.skills.builtin.agent.combine-filter-by-test
  "The reader's filter is binding; the model's is a guess.

   The agent's search tool used to `merge` the model's `filter_by` over the
   caller's, so a model that passed no filter (nil) erased the reader's, and a
   reader who ticked one organisation got another's documents back. Measured:
   orgs_long = DFØ AND type = Årsrapport returned «Årsrapport Statens vegvesen
   2024»."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.tools :as tools]))

(def ^:private reader {:fields [{:field "orgs_long" :selected-options #{"DFØ"}}]})
(def ^:private model {:fields [{:field "type" :selected-options #{"Årsrapport"}}]})

(deftest both-are-anded
  (testing "the reader's and the model's fields are kept together, ANDed"
    (is (= {:fields (into (:fields reader) (:fields model))}
           (tools/combine-filter-by reader model)))))

(deftest a-missing-model-filter-keeps-the-readers
  (testing "a model that passes no filter no longer erases the reader's"
    (is (= reader (tools/combine-filter-by reader nil)))))

(deftest a-model-filter-alone-still-works
  (testing "without a reader's filter the model's is used as before"
    (is (= model (tools/combine-filter-by nil model)))))

(deftest neither-gives-nil
  (testing "no filter from anyone is nil, not an empty filter"
    (is (nil? (tools/combine-filter-by nil nil)))
    (is (nil? (tools/combine-filter-by {:fields []} nil)))))
