(ns digdir.rag.budget-gate-integration-test
  "Integration tests verifying the agent's behavior under tight vs. relaxed budget constraints.
   
   These tests prove that the 'Solver' loop correctly identifies missing information
   and triggers follow-up actions (re-search/read) when the budget allows,
   or fails gracefully with uncertainty when it does not.

   Requires a running config DB, Typesense, and LLM access.
   Uses the kudos dataset on public-sector-knowledge/dev environment."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.core :as config-core]
            [digdir.rag.live-context :as live-ctx]
            [digdir.skills.api :as skills-api]
            [digdir.api.context :as api-ctx]))

;; =============================================================================
;; Configuration & Fixture
;; =============================================================================

(def target-dataset-ref live-ctx/default-dataset-ref)
(def target-agent-id live-ctx/default-agent-id)

(defn- services-reachable? []
  (and (config-core/get-master-key)
       (try
         (some? (config-db/get-conn))
         (catch Exception _ false))))

(defn- dataset-seeded? []
  (try
    (some? (api-ctx/resolve-dataset-context-by-ref! target-dataset-ref))
    (catch Exception _ false)))

(defn- typesense-reachable? []
  (live-ctx/typesense-reachable? {:tenant (:tenant target-dataset-ref)
                                  :dataset-config-key (:dataset-config-key target-dataset-ref)}))

(defn budget-gate-fixture [f]
  (cond
    (not (services-reachable?))
    (println "Skipping budget gate integration tests: CONFIG_MASTER_KEY not set or config DB not reachable")

    (not (dataset-seeded?))
    (println (str "Skipping budget gate integration tests: target dataset not seeded "
                  "(" (:tenant target-dataset-ref) "/" (:dataset-config-key target-dataset-ref) ")"))

    (not (typesense-reachable?))
    (println "Skipping budget gate integration tests: Typesense not reachable")

    :else (f)))

(use-fixtures :once budget-gate-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- run-agent-with-budget
  [query budget]
  (let [ctx (live-ctx/resolve-live-dataset-context! target-dataset-ref)
        inputs {:query query
                :docs-collection (:docs-collection ctx)
                :chunks-collection (:chunks-collection ctx)
                :phrases-collection (:phrases-collection ctx)
                :conversation-history []}
        skill-params {:builtin/agent (merge {:model "gpt-4o"
                                             :temperature 0.0}
                                            budget)}
        scope (api-ctx/assoc-execution-scope
                {:tenant (:tenant target-dataset-ref)
                 :dataset-config-key (:dataset-config-key target-dataset-ref)
                 :runtime-config-key (:dataset-config-key target-dataset-ref)
                 :skill-params skill-params}
                {:tenant (:tenant target-dataset-ref)
                 :dataset-config-key (:dataset-config-key target-dataset-ref)
                 :dataset-ref target-dataset-ref
                 :agent-id target-agent-id})]
    (skills-api/run-skill-graph :builtin/agent-rag inputs scope)))

;; =============================================================================
;; Phase 2: Failure Reproduction (Tight Budget)
;; =============================================================================

(deftest ^:agent-budget test-numeric-fact-fails-on-low-budget
  (testing "Agent fails gracefully with uncertainty when budget is too tight to find the answer"
    ;; Hard query: requires multiple search/read steps to find the exact metric.
    (let [query "Hvor mange årsverk hadde Digdir i 2022?"
          ;; Extremely tight budget: only 1 search, 1 read, low char limit.
          low-budget {:max-search-passes 1
                      :max-read-operations 1
                      :max-read-content-length 2000}
          result (run-agent-with-budget query low-budget)
          _outputs (:outputs result)
          agent-outputs (get-in result [:step-results :agent :outputs])
          decisions (:sufficiency-decisions agent-outputs)]
      
      (is (contains? result :outputs) "Should return outputs")
      (is (true? (:insufficient-context agent-outputs)) 
          "Should flag insufficient context on tight budget")
      
      ;; Verify that it didn't just guess or hallucinate.
      ;; The response should contain uncertainty or a note about missing info.
      (is (or (str/includes? (str/lower-case (:response agent-outputs)) "ikke")
              (str/includes? (str/lower-case (:response agent-outputs)) "unslå")
              (str/includes? (str/lower-case (:response agent-outputs)) "opplys"))
          "Response should acknowledge missing information")
      
      ;; Verify sufficiency decisions correctly identified the bottleneck.
      (is (seq decisions) "Should have at least one sufficiency decision")
      (let [last-decision (last decisions)]
        (is (= :insufficient (:status last-decision)))
        (is (= :finalize (:action last-decision)) 
            "Should recommend finalizing with uncertainty when budget is exhausted")))))

;; =============================================================================
;; Phase 3: Success Verification (Relaxed Budget)
;; =============================================================================

(deftest ^:agent-budget test-numeric-fact-succeeds-on-relaxed-budget
  (testing "Agent succeeds in finding the answer when budget is slightly relaxed"
    (let [query "Hvor mange årsverk hadde Digdir i 2022?"
          ;; Slightly relaxed budget: 2 searches, 3 reads.
          relaxed-budget {:max-search-passes 2
                          :max-read-operations 3
                          :max-read-content-length 8000}
          result (run-agent-with-budget query relaxed-budget)
          agent-outputs (get-in result [:step-results :agent :outputs])
          trace (:trace agent-outputs)
          decisions (:sufficiency-decisions agent-outputs)]
      
      (is (not (true? (:insufficient-context agent-outputs)))
          "Should NOT flag insufficient context on relaxed budget")
      
      ;; Verify the answer is found. 326 or 356 are common values for årsverk in kudos.
      (is (re-find #"\b(326|356)\b" (:response agent-outputs))
          "Should find the correct numeric fact (326 or 356 årsverk)")
      
      ;; Verify the trace shows multiple steps if the first one wasn't enough.
      (is (> (count trace) 1) "Trace should show multiple reasoning/tool turns")
      
      ;; Verify the sufficiency gate triggered a second pass.
      (is (some #(= :re-search (:action %)) decisions)
          "At least one decision should have recommended re-search or read-more before finalizing"))))
