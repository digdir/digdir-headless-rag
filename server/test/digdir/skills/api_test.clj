(ns digdir.skills.api-test
  "Integration tests for the skills public API.

   These tests verify:
   - Skill and skill graph listing
   - Tool definition generation
   - End-to-end workflow execution with mocked skills"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.skills.api :as api]
            [digdir.skills.context :as ctx]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.init :as init]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-initialized-skills
  "Test fixture that initializes the skills system"
  [f]
  (api/reset-skills!)
  (api/initialize!)
  (f)
  (api/reset-skills!))

(use-fixtures :each with-initialized-skills)

;; =============================================================================
;; Skill Listing Tests
;; =============================================================================

(deftest test-list-skills
  (testing "Lists all registered builtin skills"
    (let [skill-list (api/list-skills)]
      (is (seq skill-list))
      ;; Check that we have the expected builtin skills
      (let [skill-ids (set (map :skill-id skill-list))]
        (is (contains? skill-ids :builtin/retrieval))
        (is (contains? skill-ids :builtin/rerank))
        (is (contains? skill-ids :builtin/synthesis))
        (is (contains? skill-ids :builtin/query-planner))
        (is (contains? skill-ids :builtin/entity-extraction))
        (is (contains? skill-ids :builtin/fact-checking))
        (is (contains? skill-ids :builtin/summarization))
        (is (contains? skill-ids :builtin/multi-retrieval))
        (is (contains? skill-ids :builtin/graph-builder))
        (is (contains? skill-ids :builtin/agent))))))

(deftest test-get-skill-info
  (testing "Gets skill metadata by ID"
    (let [skill-info (api/get-skill-info :builtin/retrieval)]
      (is (some? skill-info))
      (is (= :builtin/retrieval (:skill-id skill-info)))
      (is (= :retrieval (:category skill-info)))
      (is (vector? (:inputs skill-info)))
      (is (vector? (:outputs skill-info)))))

  (testing "Returns nil for unknown skill"
    (is (nil? (api/get-skill-info :nonexistent/skill)))))

;; =============================================================================
;; Skill Graph Listing Tests
;; =============================================================================

(deftest test-list-skill-graphs
  (testing "Lists all registered builtin skill graphs"
    (let [skill-graph-list (api/list-skill-graphs)]
      (is (seq skill-graph-list))
      (let [skill-graph-ids (set (map :id skill-graph-list))]
        (is (contains? skill-graph-ids :builtin/fact-checker))
        (is (contains? skill-graph-ids :builtin/agent-rag-graph-bundled))
        (is (contains? skill-graph-ids :builtin/agent-rag-graph-faithful))))))

(deftest test-get-skill-graph-info
  (testing "Gets skill graph info by ID"
    (let [skill-graph-info (api/get-skill-graph-info :builtin/fact-checker)]
      (is (some? skill-graph-info))
      (is (= :builtin/fact-checker (:id skill-graph-info)))
      (is (string? (:name skill-graph-info)))
      (is (map? (:graph skill-graph-info)))))

  (testing "Returns nil for unknown skill graph"
    (is (nil? (api/get-skill-graph-info :nonexistent/skill-graph)))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest test-get-all-tool-definitions
  (testing "Returns tool definitions for all skills"
    (let [tools (api/get-all-tool-definitions)]
      (is (vector? tools))
      (is (= 10 (count tools)))  ; 10 builtin skills
      ;; Check structure of tool definitions (OpenAI function calling format)
      (doseq [tool tools]
        (is (= "function" (:type tool)))
        (is (map? (:function tool)))
        (is (string? (get-in tool [:function :name])))
        (is (string? (get-in tool [:function :description])))
        (is (map? (get-in tool [:function :parameters])))))))

(deftest test-get-skill-tool-definition
  (testing "Gets tool definition for specific skill"
    (let [tool (api/get-skill-tool-definition :builtin/retrieval)]
      (is (some? tool))
      (is (= "function" (:type tool)))
      (is (= "retrieval" (get-in tool [:function :name])))
      (is (string? (get-in tool [:function :description])))
      (is (= "object" (get-in tool [:function :parameters :type])))))

  (testing "Returns nil for unknown skill"
    (is (nil? (api/get-skill-tool-definition :nonexistent/skill)))))

;; =============================================================================
;; Skill Metadata Validation Tests
;; =============================================================================

(deftest test-builtin-skill-metadata
  (testing "All builtin skills have valid metadata"
    (doseq [skill (api/list-skills)]
      (is (skills/valid-skill-metadata? skill)
          (str "Invalid metadata for skill: " (:skill-id skill))))))

(deftest test-skill-categories
  (testing "Each skill has a valid category"
    (doseq [skill (api/list-skills)]
      (is (contains? skills/skill-categories (:category skill))
          (str "Invalid category for skill: " (:skill-id skill))))))

(deftest test-skill-inputs-outputs
  (testing "Each skill has at least one input and output"
    (doseq [skill (api/list-skills)]
      (is (pos? (count (:inputs skill)))
          (str "No inputs for skill: " (:skill-id skill)))
      (is (pos? (count (:outputs skill)))
          (str "No outputs for skill: " (:skill-id skill))))))

;; =============================================================================
;; Skill Graph Validation Tests
;; =============================================================================

(deftest test-skill-graphs-valid
  (testing "All skill graphs are structurally valid"
    (doseq [skill-graph (api/list-skill-graphs)]
      (let [graph (:graph skill-graph)]
        (is (vector? (:inputs graph))
            (str "Invalid inputs for skill graph: " (:id skill-graph)))
        (is (vector? (:outputs graph))
            (str "Invalid outputs for skill graph: " (:id skill-graph)))
        (is (vector? (:steps graph))
            (str "Invalid steps for skill graph: " (:id skill-graph)))))))

(def ^:private step-skills
  "The production walker (#91). This test used to carry its own copy; sharing
   the definition means the check here and the boot-time check in
   digdir.skills.init cannot drift apart."
  init/step-skill-ids
)

(deftest test-skill-graph-skills-exist
  (testing "All skills referenced in skill graphs exist"
    (doseq [skill-graph (api/list-skill-graphs)]
      (let [graph (:graph skill-graph)
            ids (mapcat step-skills (:steps graph))]
        (doseq [skill-id ids]
          (is (skills/get-skill skill-id)
              (str "Skill graph " (:id skill-graph)
                   " references unknown skill: " skill-id)))))))

;; =============================================================================
;; Initialization Tests
;; =============================================================================

(deftest test-initialize-idempotent
  (testing "Initialize can be called multiple times safely"
    (let [count-before (count (api/list-skills))]
      (api/initialize!)
      (api/initialize!)
      (api/initialize!)
      (is (= count-before (count (api/list-skills)))))))

(deftest test-initialize-recovers-empty-registries
  (testing "Initialize repopulates registries if they were cleared after init"
    ;; Simulate an external clear while the initialize! guard flag remains true.
    (skills/clear-registry!)
    (templates/clear-registry!)

    (is (zero? (count (skills/list-skills))))
    (is (zero? (count (templates/list-skill-graphs))))

    (api/initialize!)

    (is (pos? (count (skills/list-skills))))
    (is (pos? (count (templates/list-skill-graphs))))))

(deftest test-reset-clears-all
  (testing "Reset clears all skills and skill graphs"
    (let [skills-before (count (api/list-skills))
          skill-graphs-before (count (api/list-skill-graphs))]
      (is (pos? skills-before))
      (is (pos? skill-graphs-before))

      (api/reset-skills!)

      (is (zero? (count (skills/list-skills))))
      (is (zero? (count (templates/list-skill-graphs)))))))

(deftest test-run-skill-graph-propagates-progress-fn-to-skill-params
  (testing "run-skill-graph threads progress-fn into skill-params for agent/internal callbacks"
    (let [progress-fn (fn [_] nil)
          captured-opts (atom nil)
          instantiate-args (atom nil)]
      (with-redefs [templates/instantiate-graph
                    (fn [graph-id tenant runtime-config-key dataset-ref overrides]
                      (reset! instantiate-args {:graph-id graph-id
                                                :tenant tenant
                                                :runtime-config-key runtime-config-key
                                                :dataset-ref dataset-ref
                                                :overrides overrides})
                      {:graph {:inputs [] :outputs [] :steps []}
                       :execution-opts {:skill-params {}}})
                    api/initialize! (fn [] nil)
                    runner/run-graph
                    (fn [_ _ opts]
                      (reset! captured-opts opts)
                      {:outputs {} :step-results {} :execution-metadata {}})]
        (api/run-skill-graph :builtin/agent-rag {} {:progress-fn progress-fn
                                                    :tenant "ka"
                                                    :runtime-config-key "default"})
        (is (= {:graph-id :builtin/agent-rag
                :tenant "ka"
                :runtime-config-key "default"
                :dataset-ref nil
                :overrides {}}
               @instantiate-args))
        (is (fn? (:progress-fn @captured-opts)))
        (is (fn? (get-in @captured-opts [:skill-params :progress-fn])))))))

(deftest test-run-skill-graph-propagates-agent-and-dataset-scope
  (testing "run-skill-graph preserves explicit agent-id and dataset-ref in execution opts"
    (let [captured-opts (atom nil)
          dataset-ref {:tenant "altinn-docs"
                       :dataset-config-key "dev"}]
      (with-redefs [ctx/apply-dataset-context (fn [inputs opts]
                                                {:inputs inputs
                                                 :opts opts
                                                 :dataset-ref (:dataset-ref opts)
                                                 :agent-id (:agent-id opts)})
                    templates/instantiate-graph
                    (fn [_ _ _ _ _]
                      {:graph {:inputs [] :outputs [] :steps []}
                       :execution-opts {:skill-params {}}})
                    api/initialize! (fn [] nil)
                    runner/run-graph
                    (fn [_ _ opts]
                      (reset! captured-opts opts)
                      {:outputs {} :step-results {} :execution-metadata {}})]
        (api/run-skill-graph :builtin/agent-rag {} {:dataset-ref dataset-ref
                                                    :agent-id "builtin/agent-rag-agent"})
        (is (= dataset-ref (:dataset-ref @captured-opts)))
        (is (= dataset-ref (get-in @captured-opts [:skill-params :dataset-ref])))
        (is (= "builtin/agent-rag-agent" (:agent-id @captured-opts)))
        (is (= "builtin/agent-rag-agent"
               (get-in @captured-opts [:skill-params :agent-id])))))))

(deftest test-execute-builds-skill-context-from-dataset-ref
  (testing "execute uses dataset-ref-aware context building for direct skill execution"
    (let [captured (atom nil)
          dataset-ref {:tenant "altinn-docs"
                       :dataset-config-key "dev"}]
      (with-redefs [ctx/apply-dataset-context (fn [inputs opts]
                                                {:inputs (assoc inputs :dataset-ref (:dataset-ref opts))
                                                 :opts opts})
                    ctx/build-execution-context (fn [skill-id inputs opts]
                                                  (reset! captured {:skill-id skill-id
                                                                    :inputs inputs
                                                                    :opts opts})
                                                  {:inputs inputs
                                                   :parameters {}
                                                   :services {}
                                                   :skill-params opts})
                    api/initialize! (fn [] nil)
                    skills/execute-skill (fn [_ ctx] {:outputs {:ctx ctx} :metadata {}})]
        (api/execute :builtin/retrieval {:queries ["hello"]} {:dataset-ref dataset-ref
                                                              :agent-id "builtin/agent-rag-agent"})
        (is (= :builtin/retrieval (:skill-id @captured)))
        (is (= dataset-ref (get-in @captured [:inputs :dataset-ref])))
        (is (= dataset-ref (get-in @captured [:opts :dataset-ref])))
        (is (= "builtin/agent-rag-agent" (get-in @captured [:opts :agent-id])))))))
