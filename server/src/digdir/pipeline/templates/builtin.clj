(ns digdir.pipeline.templates.builtin
  "Built-in template definitions.

   Provides default templates:
   - simple-qa: Basic RAG pipeline
   - research-assistant: Multi-query with planning
   - fact-checker: Verification pipeline"
  (:require [digdir.pipeline.templates.core :as templates]))

;; =============================================================================
;; Simple QA Template
;; =============================================================================

(def simple-qa-graph
  "Basic RAG graph: plan -> retrieve -> rerank -> generate"
  {:id :simple-qa
   :name "Simple Q&A"
   :description "Basic question answering with query expansion and retrieval"
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection]
   :outputs [:response :chunks :search-phrases]
   :steps [{:id :plan
            :skill :builtin/query-planner
            :inputs {:query :$user-query
                     :conversation-history []}}
           {:id :retrieve
            :skill :builtin/retrieval
            :inputs {:queries [:plan :search-phrases]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}
           {:id :generate
            :skill :builtin/synthesis
            :inputs {:query :$user-query
                     :context-docs [:rerank :context-docs]}}]})

(def simple-qa-template
  (templates/make-template
    :builtin/simple-qa
    "Simple Q&A"
    "Basic RAG pipeline: retrieve relevant documents and generate an answer"
    simple-qa-graph
    {:version "1.0.0"
     :tags #{:rag :qa :production}}))

;; =============================================================================
;; Research Assistant Template
;; =============================================================================

(def research-assistant-graph
  "Multi-query RAG: plan -> retrieve -> rerank -> generate"
  {:id :research-assistant
   :name "Research Assistant"
   :description "Advanced RAG with query expansion"
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection]
   :outputs [:response :chunks :search-phrases]
   :steps [{:id :plan
            :skill :builtin/query-planner
            :inputs {:query :$user-query
                     :conversation-history []}}
           {:id :retrieve
            :skill :builtin/retrieval
            :inputs {:queries [:plan :search-phrases]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}
           {:id :generate
            :skill :builtin/synthesis
            :inputs {:query :$user-query
                     :context-docs [:rerank :context-docs]}}]})

(def research-assistant-template
  (templates/make-template
    :builtin/research-assistant
    "Research Assistant"
    "Advanced RAG with query expansion for comprehensive research"
    research-assistant-graph
    {:version "1.0.0"
     :tags #{:rag :research :advanced}}))

;; =============================================================================
;; Fact Checker Template (Placeholder)
;; =============================================================================

(def fact-checker-graph
  "Fact verification: retrieve -> verify"
  {:id :fact-checker
   :name "Fact Checker"
   :description "Verify claims against retrieved evidence"
   :inputs [:claim :docs-collection :chunks-collection :phrases-collection]
   :outputs [:verification :evidence]
   :steps [{:id :retrieve
            :skill :builtin/retrieval
            :inputs {:queries :$claim  ; retrieval skill normalizes string to vector
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$claim
                     :docs-collection :$docs-collection}}
           {:id :verify
            :skill :builtin/synthesis
            :inputs {:query :$claim
                     :context-docs [:rerank :context-docs]}
            :parameters {:generation-prompt "Based on the evidence, verify if the following claim is true, false, or uncertain. Cite specific evidence.\n\nEvidence:\n{context}\n\nClaim: {question}\n\nVerification:"}}]})

(def fact-checker-template
  (templates/make-template
    :builtin/fact-checker
    "Fact Checker"
    "Verify claims against document evidence"
    fact-checker-graph
    {:version "1.0.0"
     :tags #{:rag :fact-checking :verification}}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-all!
  "Register all built-in templates."
  []
  (templates/register-template! simple-qa-template)
  (templates/register-template! research-assistant-template)
  (templates/register-template! fact-checker-template))

(def builtin-templates
  "Map of all built-in templates by ID."
  {:builtin/simple-qa simple-qa-template
   :builtin/research-assistant research-assistant-template
   :builtin/fact-checker fact-checker-template})
