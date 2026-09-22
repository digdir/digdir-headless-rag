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

(def ^:private declared-rag-agent
  {:id "builtin/agent-rag-agent"
   :name "Agentic RAG Agent"
   :description "General-purpose agentic retrieval assistant."
   :instructions "Use the available retrieval and reasoning tools."
   :default-skill-graph "builtin/agent-rag-graph-bundled"
   :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                          "builtin/agent-rag-graph-faithful"]
   :guardrails {:citations-required true}})

(def ^:private both-graphs
  #{"builtin/agent-rag-graph-bundled" "builtin/agent-rag-graph-faithful"})

(deftest test-agent-drift
  (testing "An agent with no code definition is custom, not drifted"
    (let [drift (agents/agent-drift
                 {:stored (assoc declared-rag-agent :id "e2e/hand-rolled")
                  :declared nil
                  :available-skill-graphs both-graphs})]
      (is (= :custom (:status drift)))
      (is (= "e2e/hand-rolled" (:id drift)))))

  (testing "A declared agent with no row would be created by seeding"
    (let [drift (agents/agent-drift
                 {:stored nil
                  :declared declared-rag-agent
                  :available-skill-graphs both-graphs})]
      (is (= :unseeded (:status drift)))))

  (testing "A row matching the declaration needs no reseed"
    (let [drift (agents/agent-drift
                 {:stored declared-rag-agent
                  :declared declared-rag-agent
                  :available-skill-graphs both-graphs})]
      (is (= :matches-code (:status drift)))
      (is (empty? (:differing-fields drift)))))

  (testing "A graph missing from the row but registered here is stale, and recoverable by reseeding"
    (let [drift (agents/agent-drift
                 {:stored (assoc declared-rag-agent
                                 :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"])
                  :declared declared-rag-agent
                  :available-skill-graphs both-graphs})]
      (is (= :stale (:status drift)))
      (is (= ["builtin/agent-rag-graph-faithful"]
             (get-in drift [:allowed-skill-graphs :recoverable])))
      (is (empty? (get-in drift [:allowed-skill-graphs :unavailable])))))

  (testing "A graph missing from the row and unregistered is narrowed, and reseeding cannot recover it"
    (let [drift (agents/agent-drift
                 {:stored (assoc declared-rag-agent
                                 :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"])
                  :declared declared-rag-agent
                  :available-skill-graphs #{"builtin/agent-rag-graph-bundled"}})]
      (is (= :narrowed (:status drift)))
      (is (= ["builtin/agent-rag-graph-faithful"]
             (get-in drift [:allowed-skill-graphs :unavailable])))
      (is (empty? (get-in drift [:allowed-skill-graphs :recoverable])))))

  (testing "An edited field is diverged, so a reseed would revert it"
    (let [drift (agents/agent-drift
                 {:stored (assoc declared-rag-agent :instructions "Locally tuned.")
                  :declared declared-rag-agent
                  :available-skill-graphs both-graphs})]
      (is (= :diverged (:status drift)))
      (is (contains? (:differing-fields drift) :instructions))))

  (testing "A recoverable graph outranks an edited field, because it is the actionable one"
    (let [drift (agents/agent-drift
                 {:stored (assoc declared-rag-agent
                                 :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
                                 :instructions "Locally tuned.")
                  :declared declared-rag-agent
                  :available-skill-graphs both-graphs})]
      (is (= :stale (:status drift)))
      (is (contains? (:differing-fields drift) :instructions)))))
