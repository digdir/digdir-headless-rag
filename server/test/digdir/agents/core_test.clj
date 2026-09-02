(ns digdir.agents.core-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.agents.core :as agents]
            [digdir.skills.api :as skills-api]))

(defn with-initialized-skills
  [f]
  (skills-api/reset-skills!)
  (skills-api/initialize!)
  (f)
  (skills-api/reset-skills!))

(use-fixtures :each with-initialized-skills)

(deftest test-normalize-agent
  (testing "Normalizes keyword skill graph IDs and dataset scopes"
    (let [agent (agents/normalize-agent
                 {:id "agent/example"
                  :name "Example Agent"
                  :description "Example"
                  :instructions "Do the thing."
                  :default-skill-graph :builtin/agent-rag
                  :allowed-dataset-scopes [{"tenant" "altinn-docs"
                                            "dataset-config-key" "dev"}]
                  :guardrails {:citations-required true}})]
      (is (= "builtin/agent-rag" (:default-skill-graph agent)))
      (is (= ["builtin/agent-rag"] (:allowed-skill-graphs agent)))
      (is (= [{:tenant "altinn-docs"
               :dataset-config-key "dev"}]
             (:allowed-dataset-scopes agent)))
      (is (true? (:enabled? agent))))))

(deftest test-validate-agent
  (testing "Valid agent definitions pass validation"
    (let [{:keys [valid? errors agent]}
          (agents/validate-agent
           {:id "agent/example"
            :name "Example Agent"
            :description "Example"
            :instructions "Do the thing."
            :default-skill-graph "builtin/agent-rag"
            :allowed-skill-graphs ["builtin/agent-rag"]
            :allowed-dataset-scopes [{:tenant "altinn-docs"
                                      :dataset-config-key "dev"}]
            :guardrails {:citations-required true}
            :enabled? true}
           {:available-skill-graphs #{"builtin/agent-rag"}})]
      (is valid?)
      (is (empty? errors))
      (is (= "agent/example" (:id agent)))))

  (testing "Unknown skill graphs fail validation"
    (let [{:keys [valid? errors]}
          (agents/validate-agent
           {:id "agent/invalid"
            :name "Invalid Agent"
            :description "Example"
            :instructions "Do the thing."
            :default-skill-graph "builtin/missing"
            :allowed-skill-graphs ["builtin/missing"]
            :guardrails {}
            :enabled? true}
           {:available-skill-graphs #{"builtin/agent-rag"}})]
      (is (false? valid?))
      (is (some #(re-find #"Unknown default skill graph" %) errors)))))

(deftest test-validate-agent-can-use-injected-available-graphs-without-registry-init
  (testing "Injected available graphs avoid touching the global skills registry"
    (with-redefs [agents/available-skill-graph-ids (fn []
                                                     (throw (ex-info "should not be called" {})))]
      (let [{:keys [valid?]}
            (agents/validate-agent
             {:id "agent/example"
              :name "Example Agent"
              :description "Example"
              :instructions "Do the thing."
              :default-skill-graph "builtin/agent-rag"
              :allowed-skill-graphs ["builtin/agent-rag"]
              :guardrails {}
              :enabled? true}
             {:available-skill-graphs #{"builtin/agent-rag"}})]
        (is valid?)))))
