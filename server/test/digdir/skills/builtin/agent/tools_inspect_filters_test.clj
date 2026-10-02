(ns digdir.skills.builtin.agent.tools-inspect-filters-test
  "inspect_filters, driven through the tool dispatcher with only Typesense
   stubbed. It covers fetch-facets, the handler's own select-keys and the
   formatter together, so a wrong key at any of them empties the option names."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [typesense.client :as ts-client]))

(def ^:private type-facet
  {:facet_counts [{:field_name "type"
                   :counts [{:value "Årsrapport" :count 2874}
                            {:value "Tildelingsbrev" :count 1200}]}]})

(deftest inspect-filters-shows-option-values
  (testing "the agent is shown each option's value with its count"
    (with-redefs [ts-utils/make-ts-settings (constantly {:uri "http://ts"})
                  ts-client/retrieve-collection
                  (fn [_settings _collection]
                    {:fields [{:name "type" :type "string" :facet true}]})
                  ts-client/multi-search (fn [_settings _args _opts]
                                           {:results [type-facet type-facet]})]
      (let [out (tools/execute-tool-call*
                 "inspect_filters"
                 {:fields ["type"]}
                 (workspace/create-workspace)
                 {:docs-collection "docs"
                  :opts {:tenant "t" :dataset-config-key "d"}})]
        (is (re-find #"\"Årsrapport\"\(2874\)" out) out)
        (is (re-find #"\"Tildelingsbrev\"\(1200\)" out) out)))))
