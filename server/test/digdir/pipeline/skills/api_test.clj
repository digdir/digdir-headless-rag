(ns digdir.pipeline.skills.api-test
  "Integration tests for the skills public API.

   These tests verify:
   - Skill and template listing
   - Tool definition generation
   - End-to-end workflow execution with mocked skills"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.pipeline.skills.api :as api]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.templates.core :as templates]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-initialized-skills [f]
  "Test fixture that initializes the skills system"
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
        (is (contains? skill-ids :builtin/graph-builder))))))

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
;; Template Listing Tests
;; =============================================================================

(deftest test-list-templates
  (testing "Lists all registered builtin templates"
    (let [template-list (api/list-templates)]
      (is (seq template-list))
      ;; Check that we have the expected builtin templates
      (let [template-ids (set (map :id template-list))]
        (is (contains? template-ids :builtin/simple-qa))
        (is (contains? template-ids :builtin/research-assistant))
        (is (contains? template-ids :builtin/fact-checker))))))

(deftest test-get-template-info
  (testing "Gets template info by ID"
    (let [template-info (api/get-template-info :builtin/simple-qa)]
      (is (some? template-info))
      (is (= :builtin/simple-qa (:id template-info)))
      (is (string? (:name template-info)))
      (is (map? (:graph template-info)))))

  (testing "Returns nil for unknown template"
    (is (nil? (api/get-template-info :nonexistent/template)))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest test-get-all-tool-definitions
  (testing "Returns tool definitions for all skills"
    (let [tools (api/get-all-tool-definitions)]
      (is (vector? tools))
      (is (= 9 (count tools)))  ; 9 builtin skills
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
;; Template Graph Validation Tests
;; =============================================================================

(deftest test-template-graphs-valid
  (testing "All template graphs are structurally valid"
    (doseq [template (api/list-templates)]
      (let [graph (:graph template)]
        (is (vector? (:inputs graph))
            (str "Invalid inputs for template: " (:id template)))
        (is (vector? (:outputs graph))
            (str "Invalid outputs for template: " (:id template)))
        (is (vector? (:steps graph))
            (str "Invalid steps for template: " (:id template)))))))

(deftest test-template-graph-skills-exist
  (testing "All skills referenced in templates exist"
    (doseq [template (api/list-templates)]
      (let [graph (:graph template)
            step-skills (map :skill (:steps graph))]
        (doseq [skill-id step-skills]
          (is (skills/get-skill skill-id)
              (str "Template " (:id template)
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

(deftest test-reset-clears-all
  (testing "Reset clears all skills and templates"
    (let [skills-before (count (api/list-skills))
          templates-before (count (api/list-templates))]
      (is (pos? skills-before))
      (is (pos? templates-before))

      (api/reset-skills!)

      (is (zero? (count (skills/list-skills))))
      (is (zero? (count (templates/list-templates)))))))
