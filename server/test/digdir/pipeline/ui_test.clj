(ns digdir.pipeline.ui-test
  (:require [clojure.test :refer [deftest is]]
            [digdir.pipeline.ui.pipelines :as pipelines]))

(deftest test-branch-key-label
  (is (= "unkeyed (_)" (pipelines/branch-key-label nil)))
  (is (= "unkeyed (_)" (pipelines/branch-key-label "")))
  (is (= "prod" (pipelines/branch-key-label "prod"))))

(deftest test-diagnostics-url
  (is (= "/config/diagnostics"
         (pipelines/diagnostics-url {:root :dataset
                                     :tenant "digdir"
                                     :tenant-config-key "prod"
                                     :pipeline-id "assistant"
                                     :dataset-id "public-docs"})))
  (is (= "/config/diagnostics"
         (pipelines/diagnostics-url {:root :dataset
                                     :tenant "digdir"}))))

(deftest test-dataset-diagnostics-url
  (is (= "/config/diagnostics"
         (#'pipelines/dataset-diagnostics-url {:id "public-docs"
                                               :pipelines [{:tenant "digdir"}
                                                           {:tenant "digdir"}]})))
  (is (= "/config/diagnostics"
         (#'pipelines/dataset-diagnostics-url {:id "public-docs"
                                               :pipelines [{:tenant "digdir"}
                                                           {:tenant "nav"}]}))))

(deftest test-dataset-base-node-id
  (is (= "dataset/digdir/public-docs/default"
         (pipelines/dataset-base-node-id "digdir" "public-docs"))))

(deftest test-dataset-runtime-selections
  (is (= [{:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/default"}]
         (#'pipelines/dataset-runtime-selections {:id "public-docs"
                                                  :pipelines [{:tenant "digdir"}
                                                              {:tenant "digdir"}]})))
  (is (= [{:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/default"}
          {:tenant "nav"
           :root :dataset
           :node-id "dataset/nav/public-docs/default"}]
         (#'pipelines/dataset-runtime-selections {:id "public-docs"
                                                  :pipelines [{:tenant "digdir"}
                                                              {:tenant "nav"}]}))))

(deftest test-runtime-pipeline-selection-label
  (is (= "Assistant [digdir/prod]"
         (#'pipelines/runtime-pipeline-selection-label {:name "Assistant"
                                                        :tenant "digdir"
                                                        :tenant-config-key "prod"}))))

(deftest test-pipeline-console-url-state
  (is (= {:view :list}
         (#'pipelines/pipeline-console-url-state {})))
  (is (= {:view :dataset-detail
          :selected-dataset-id "public-docs"}
         (#'pipelines/pipeline-console-url-state {"dataset" "public-docs"})))
  (is (= {:view :dataset-detail
          :selected-dataset-id "public-docs"
          :runtime-selected-pipeline-ids ["digdir::_::digdir-docs"
                                          "digdir::_::altinn-docs"]}
         (#'pipelines/pipeline-console-url-state {"dataset" "public-docs"
                                                  "pipelines" "digdir::_::digdir-docs,digdir::_::altinn-docs"}))))

(deftest test-pipeline-console-query-params
  (is (= {}
         (#'pipelines/pipeline-console-query-params {:view :list
                                                     :selected-dataset-id "public-docs"
                                                     :runtime-selected-pipeline-ids ["digdir::_::altinn-docs"]})))
  (is (= {"dataset" "public-docs"
          "pipelines" "digdir::_::altinn-docs,digdir::_::digdir-docs"}
         (#'pipelines/pipeline-console-query-params {:view :dataset-detail
                                                     :selected-dataset-id "public-docs"
                                                     :runtime-selected-pipeline-ids ["digdir::_::digdir-docs"
                                                                                     "digdir::_::altinn-docs"]})))
  (is (= {"dataset" "public-docs"}
         (#'pipelines/pipeline-console-query-params {:view :edit-pipeline
                                                     :selected-dataset-id "public-docs"
                                                     :runtime-selected-pipeline-ids []}))))

(deftest test-dataset-runtime-comparison-selections
  (is (= [{:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/default"}
          {:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/prod/assistant/materialization"
           :column-label "Assistant [digdir/prod]"
           :selection-id "digdir::prod::assistant"}]
         (#'pipelines/dataset-runtime-comparison-selections
         {:id "public-docs"
           :pipelines [{:id "assistant"
                        :dataset-id "public-docs"
                        :name "Assistant"
                        :tenant "digdir"
                        :tenant-config-key "prod"
                        :context-count 1}
                       {:id "ambiguous"
                        :dataset-id "public-docs"
                        :name "Ambiguous"
                        :tenant "digdir"
                        :tenant-config-key "blue"
                        :context-count 2}]}
          #{"digdir::prod::assistant"})))
  (is (= [{:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/default"}
          {:tenant "digdir"
           :root :dataset
           :node-id "dataset/digdir/public-docs/assistant/materialization"
           :column-label "Assistant [digdir/unkeyed (_)]"
           :selection-id "digdir::_::assistant"}]
         (#'pipelines/dataset-runtime-comparison-selections
          {:id "public-docs"
           :pipelines [{:id "assistant"
                        :dataset-id "public-docs"
                        :name "Assistant"
                        :tenant "digdir"
                        :tenant-config-key nil
                        :context-count 1
                        :contexts [{:tenant "digdir"
                                    :tenant-config-key nil
                                    :node-id "dataset/digdir/public-docs/assistant/materialization"}]}]}
          #{"digdir::_::assistant"}))))

(deftest test-pipeline-editor-selection
  (is (= {:tenant "digdir"
          :root :dataset
          :node-id "dataset/digdir/public-docs/prod/assistant/materialization"
          :selection-id "digdir::prod::assistant"}
         (#'pipelines/pipeline-editor-selection {:dataset-id "public-docs"
                                                 :tenant "digdir"
                                                 :tenant-config-key "prod"
                                                 :pipeline-id "assistant"})))
  (is (= {:tenant "digdir"
          :root :dataset
          :node-id "dataset/digdir/public-docs/assistant/materialization"
          :selection-id "digdir::_::assistant"}
         (#'pipelines/pipeline-editor-selection {:dataset-id "public-docs"
                                                 :tenant "digdir"
                                                 :tenant-config-key nil
                                                 :pipeline-id "assistant"
                                                 :contexts [{:tenant "digdir"
                                                             :tenant-config-key nil
                                                             :node-id "dataset/digdir/public-docs/assistant/materialization"}]})))
  (is (nil? (#'pipelines/pipeline-editor-selection {:dataset-id "public-docs"
                                                    :tenant ""
                                                    :tenant-config-key "prod"
                                                    :pipeline-id "assistant"}))))
