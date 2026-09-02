(ns digdir.skills.context-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.context :as ctx]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]))

(deftest build-execution-context-resolves-dataset-ref-into-skill-context
  (testing "dataset refs resolve tenant/dataset-config-key scope and collection inputs"
    (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                  config-core/get-master-key (fn [] "test-master-key")
                  config-db/get-dataset-by-ref (fn [_ dataset-ref _master-key]
                                                 {:id "assistant"
                                                  :tenant (:tenant dataset-ref)
                                                  :dataset-config-key (:dataset-config-key dataset-ref)
                                                                                                    :docs-collection "docs_col"
                                                  :chunks-collection "chunks_col"
                                                  :phrases-collection "phrases_col"})
                  ctx/resolve-all-services (fn [opts] {:typesense opts})]
      (let [dataset-ref {:tenant "altinn-docs"
                         :dataset-config-key "dev"}
            execution-ctx (ctx/build-execution-context
                           :builtin/retrieval
                           {:queries ["hello"]}
                           {:dataset-ref dataset-ref
                            :agent-id "builtin/agent-rag-agent"
                            :validate-io? false
                            :skill-params {:foo :bar}})]
        (is (= dataset-ref (:dataset-ref execution-ctx)))
        (is (= "builtin/agent-rag-agent" (:agent-id execution-ctx)))
        (is (= {:queries ["hello"]
                :docs-collection "docs_col"
                :chunks-collection "chunks_col"
                :phrases-collection "phrases_col"
                :dataset-ref dataset-ref}
               (:inputs execution-ctx)))
        (is (= {:tenant "altinn-docs"
                :dataset-config-key "dev"
                :foo :bar
                :dataset-ref dataset-ref
                :agent-id "builtin/agent-rag-agent"}
               (:skill-params execution-ctx)))
        (is (= {:typesense {:tenant "altinn-docs"
                            :runtime-config-key nil}}
               (:services execution-ctx)))))))

(deftest apply-dataset-context-does-not-re-resolve-when-collections-are-explicit
  (testing "explicit collections avoid a redundant dataset config lookup"
    (let [resolved? (atom false)
          dataset-ref {:tenant "altinn-docs"
                       :dataset-config-key "dev"}]
      (with-redefs [ctx/resolve-dataset-context (fn [_]
                                                  (reset! resolved? true)
                                                  {:dataset-ref dataset-ref
                                                   :tenant "altinn-docs"
                                                   :dataset-config-key "dev"
                                                   :dataset-inputs {:docs-collection "resolved-docs"
                                                                    :chunks-collection "resolved-chunks"
                                                                    :phrases-collection "resolved-phrases"}})]
        (let [{:keys [inputs opts]} (ctx/apply-dataset-context
                                     {:docs-collection "docs"
                                     :chunks-collection "chunks"
                                     :phrases-collection "phrases"}
                                     {:tenant "altinn-docs"
                                      :dataset-config-key "dev"
                                      :validate-io? false
                                      :dataset-ref dataset-ref})]
          (is (false? @resolved?))
          (is (= "docs" (:docs-collection inputs)))
          (is (= dataset-ref (:dataset-ref opts))))))))
