(ns digdir.agents.policy-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.agents.policy :as policy]))

(def example-agent
  {:id "agent/example"
   :name "Example Agent"
   :description "Example"
   :instructions "Do the thing."
   :default-skill-graph "builtin/agent-rag"
   :allowed-skill-graphs ["builtin/agent-rag" "builtin/retrieve-only"]
   :allowed-dataset-scopes [{:tenant "altinn-docs"
                             :dataset-config-key "dev"}]
   :guardrails {:citations-required true}
   :enabled? true})

(deftest test-allows-skill-graph
  (testing "Policy checks allowed skill graphs"
    (is (policy/allows-skill-graph? example-agent "builtin/agent-rag"))
    (is (policy/allows-skill-graph? example-agent :builtin/retrieve-only))
    (is (not (policy/allows-skill-graph? example-agent "builtin/simple-qa")))))

(deftest test-allows-dataset-scope
  (testing "Policy checks allowed dataset scopes"
    (is (policy/allows-dataset-scope? example-agent
                                     {:tenant "altinn-docs"
                                      :dataset-config-key "dev"}))
    (is (not (policy/allows-dataset-scope? example-agent
                                           {:tenant "altinn-docs"
                                            :dataset-config-key "prod"})))))

(deftest test-resolve-execution-policy
  (testing "Policy exposes a compact execution view"
    (let [execution-policy (policy/resolve-execution-policy example-agent)]
      (is (= "agent/example" (:agent-id execution-policy)))
      (is (= "builtin/agent-rag" (:default-skill-graph execution-policy)))
      (is (= #{"builtin/agent-rag" "builtin/retrieve-only"}
             (:allowed-skill-graphs execution-policy)))
      (is (= [{:tenant "altinn-docs"
               :dataset-config-key "dev"}]
             (:allowed-dataset-scopes execution-policy)))
      (is (true? (:enabled? execution-policy))))))
