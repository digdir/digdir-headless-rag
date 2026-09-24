(ns digdir.api.skill-params-filter-test
  "A caller-chosen filter must reach the retrieval skill.

   `build-skill-params-from-params` is a whitelist: only the per-call keys it
   names are mapped onto skill params. `:retrieve-filter-by` was missing, so an
   MCP call with `overrides.retrieve-filter-by` had its filter dropped with no
   error — measured with an impossible filter (`type = ZZZ_no_such_type`), which
   still returned chunks. The retrieval skill already accepted `:filter-by`;
   auto-filter feeds the same path."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.api.util :as api-util]))

(def ^:private dfo-filter
  {:fields [{:field "orgs_long"
             :selected-options ["Direktoratet for forvaltning og økonomistyring"]}]})

(deftest retrieve-filter-by-reaches-retrieval
  (testing "the per-call filter lands on the retrieval skill as :filter-by"
    (is (= dfo-filter
           (get-in (api-util/build-skill-params-from-params {:retrieve-filter-by dfo-filter})
                   [:builtin/retrieval :filter-by])))))

(deftest no-filter-adds-no-key
  (testing "without a filter no empty :filter-by is added, so lower layers are not cleared"
    (is (not (contains? (:builtin/retrieval
                          (api-util/build-skill-params-from-params {:retrieve-top-k 50}))
                        :filter-by)))))

(deftest per-call-filter-wins-over-lower-layers
  (testing "the per-call filter overrides a filter configured on the dataset or agent"
    (let [dataset-filter {:fields [{:field "type" :selected-options ["Evaluering"]}]}
          merged (api-util/build-rag-skill-params
                   {}
                   {:retrieve-filter-by dfo-filter}
                   {:builtin/retrieval {:filter-by dataset-filter}})]
      (is (= dfo-filter (get-in merged [:builtin/retrieval :filter-by]))))))
