(ns digdir.skills.templates.builtin
  "Built-in skill graph definitions.

   Provides the preserved default skill graphs:
   - fact-checker: claim verification against retrieved evidence
   - retrieve-only: retrieval + rerank with no generation (see below)
   - agent-rag-graph-bundled / -faithful: graph-structured agentic RAG
     (registered in digdir.skills.builtin.agent.graphs)
   - ai-overview: short, fully-cited synthesised answer
     (registered in digdir.skills.builtin.overview)

   The legacy :builtin/simple-qa, :builtin/research-assistant,
   :builtin/retrieve-only, and :builtin/agent-rag (ReAct) skill graphs
   were retired in Phase 0 alongside the custom /api/rag and /api/retrieve
   HTTP endpoints they served.

   :builtin/retrieve-only is BACK (issue #240) but it is not the retired
   graph restored verbatim. The retired one was written before the retrieval
   stack grew a user-intent first pass and corpus-aware query expansion: its
   :plan step passed neither :phrases-collection to the planner nor
   :user-intent to retrieval, so copying it across would have quietly
   measured a retrieval path nobody runs any more. The graph below wires the
   current contract. See digdir.agents.core for what the agent is FOR."
  (:require [digdir.skills.templates.core :as templates]))

;; =============================================================================
;; Fact Checker Template
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

(def fact-checker-skill-graph
  (templates/make-skill-graph
    :builtin/fact-checker
    "Fact Checker"
    "Verify claims against document evidence"
    fact-checker-graph
    {:version "1.0.0"
     :tags #{:rag :fact-checking :verification}
     :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Retrieve-Only Template
;; =============================================================================

(def retrieve-only-graph
  "Query expansion + retrieval + rerank, stopping before generation.

   Every step here is a skill that is registered today
   (:builtin/query-planner, :builtin/retrieval, :builtin/rerank), and the
   wiring matches how :builtin/agent-rag-* drives them: the planner is given
   the phrases collection so corpus-aware expansion can run, and its
   :user-intent is handed to retrieval so the user-intent first-pass union is
   available. Omitting either is what would make this graph a measurement of
   a retrieval path that no production caller uses."
  {:id :retrieve-only
   :name "Retrieve Only"
   :description "Query expansion, retrieval and reranking with no LLM generation"
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection
            :conversation-history]
   :outputs [:chunks :context-docs :queries :user-intent :search-attribution]
   :steps [{:id :plan
            :skill :builtin/query-planner
            :inputs {:query :$user-query
                     :conversation-history :$conversation-history
                     :phrases-collection :$phrases-collection}}
           {:id :retrieve
            :skill :builtin/retrieval
            :inputs {:queries [:plan :queries]
                     :user-intent [:plan :user-intent]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}]})

(def retrieve-only-skill-graph
  (templates/make-skill-graph
    :builtin/retrieve-only
    "Retrieve Only"
    "Retrieve and rerank evidence without generating an answer"
    retrieve-only-graph
    {:version "2.0.0"
     :tags #{:rag :retrieval :diagnostics}
     :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-all!
  "Register all built-in skill graphs owned by this namespace.

   The graph-structured agentic RAG variants (:builtin/agent-rag-graph-bundled
   and :builtin/agent-rag-graph-faithful) are registered separately by
   digdir.skills.builtin.agent.graphs, and :builtin/ai-overview by
   digdir.skills.builtin.overview."
  []
  (templates/register-skill-graph! fact-checker-skill-graph)
  (templates/register-skill-graph! retrieve-only-skill-graph))

(def builtin-skill-graphs
  "Map of built-in skill graphs by ID owned by this namespace."
  {:builtin/fact-checker fact-checker-skill-graph
   :builtin/retrieve-only retrieve-only-skill-graph})
