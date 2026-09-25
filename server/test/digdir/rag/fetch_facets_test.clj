(ns digdir.rag.fetch-facets-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.retrieval :as retrieval]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.builtin.agent.tools :as tools]
            [typesense.client :as ts-client]))

(def ^:private type-facet
  {:facet_counts [{:field_name "type"
                   :counts [{:value "Årsrapport" :count 2874}
                            {:value "Tildelingsbrev" :count 1200}]}]})

(deftest fetch-facets-returns-options
  (testing "a local binding no longer shadows multi-search, so facets come back"
    (with-redefs [ts-utils/make-ts-settings (constantly {:uri "http://ts"})
                  ts-client/multi-search (fn [_settings _args _opts]
                                           {:results [type-facet type-facet]})]
      (let [result (retrieval/fetch-facets
                    {:docs-collection "docs"}
                    {:fields [{:type :multiselect
                               :field "type"
                               :selected-options #{}}]}
                    {:tenant "t" :dataset-config-key "d"})]
        (is (= [{:count 2874 :value "Årsrapport" :selected? false}
                {:count 1200 :value "Tildelingsbrev" :selected? false}]
               (->> result :ui/fields first :options (sort-by :count >) vec)))))))

(deftest inspection-result-names-the-options
  (is (re-find #"\"Årsrapport\"\(2874\)"
               (tools/format-filter-inspection-result
                {:available-fields [{:field "type"
                                     :value-type :string
                                     :typesense-type "string"}]
                 :facet-options {"type" [{:value "Årsrapport" :count 2874}]}}))))
