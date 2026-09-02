(ns digdir.pipeline.skills.api
  "Public API for skill invocation.

   Provides a unified interface for:
   - Executing individual skills
   - Running skill graphs/templates
   - Managing skill registration"
  (:require [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]
            [digdir.pipeline.skills.graph.runner :as runner]
            [digdir.pipeline.skills.graph.schema :as schema]
            [digdir.pipeline.templates.core :as templates]
            [digdir.pipeline.templates.builtin :as builtin-templates]
            ;; Builtin skills
            [digdir.pipeline.skills.builtin.retrieval :as retrieval]
            [digdir.pipeline.skills.builtin.rerank :as rerank]
            [digdir.pipeline.skills.builtin.synthesis :as synthesis]
            [digdir.pipeline.skills.builtin.query-planner :as query-planner]
            [digdir.pipeline.skills.builtin.entity-extraction :as entity-extraction]
            [digdir.pipeline.skills.builtin.fact-checking :as fact-checking]
            [digdir.pipeline.skills.builtin.summarization :as summarization]
            [digdir.pipeline.skills.builtin.multi-retrieval :as multi-retrieval]
            [digdir.pipeline.skills.builtin.graph-builder :as graph-builder]))

;; =============================================================================
;; Initialization
;; =============================================================================

(defonce ^:private !initialized (atom false))

(defn register-builtin-skills!
  "Register all built-in skills."
  []
  (retrieval/register!)
  (rerank/register!)
  (synthesis/register!)
  (query-planner/register!)
  (entity-extraction/register!)
  (fact-checking/register!)
  (summarization/register!)
  (multi-retrieval/register!)
  (graph-builder/register!))

(defn register-builtin-templates!
  "Register all built-in templates."
  []
  (builtin-templates/register-all!))

(defn initialize!
  "Initialize the skills system with built-in skills and templates.
   Safe to call multiple times."
  []
  (when-not @!initialized
    (register-builtin-skills!)
    (register-builtin-templates!)
    (reset! !initialized true)))

(defn reset-skills!
  "Reset the skills system. Clears all registrations."
  []
  (skills/clear-registry!)
  (templates/clear-registry!)
  (reset! !initialized false))

;; =============================================================================
;; Skill Execution
;; =============================================================================

(defn execute
  "Execute a skill by ID.

   Args:
     skill-id - Keyword identifying the skill (e.g., :builtin/retrieval)
     inputs - Map of input values
     opts - Execution options:
       :tenant - Tenant identifier
       :environment - Environment
       :entity - Entity identifier (optional)
       :pipeline-config - Full pipeline configuration
       :parameters - Parameter overrides

   Returns: Skill execution result map"
  [skill-id inputs opts]
  (initialize!)
  (let [ctx (ctx/build-execution-context skill-id inputs opts)]
    (skills/execute-skill skill-id ctx)))

(defn execute-with-context
  "Execute a skill with a pre-built context.

   Args:
     skill-id - Keyword identifying the skill
     ctx - Pre-built execution context

   Returns: Skill execution result map"
  [skill-id ctx]
  (initialize!)
  (skills/execute-skill skill-id ctx))

;; =============================================================================
;; Graph Execution
;; =============================================================================

(defn run-graph
  "Execute a skill graph.

   Args:
     graph - Graph definition map
     inputs - Map of input values (keys without $ prefix)
     opts - Execution options:
       :tenant - Tenant identifier
       :environment - Environment
       :pipeline-config - Pipeline configuration

   Returns: Graph execution result"
  [graph inputs opts]
  (initialize!)
  (runner/run-graph graph inputs opts))

(defn run-template
  "Execute a registered template.

   Args:
     template-id - Template ID keyword
     inputs - Map of input values
     opts - Execution options:
       :tenant - Tenant identifier
       :environment - Environment
       :overrides - Parameter overrides

   Returns: Graph execution result"
  [template-id inputs opts]
  (initialize!)
  (let [{:keys [graph execution-opts]}
        (templates/instantiate-graph
          template-id
          (:tenant opts)
          (:environment opts)
          (:overrides opts {}))]
    (runner/run-graph graph inputs execution-opts)))

;; =============================================================================
;; Tool Definitions for Agent Invocation
;; =============================================================================

(defn get-all-tool-definitions
  "Get tool definitions for all registered skills.

   Returns: Vector of tool definition maps for OpenAI function calling"
  []
  (initialize!)
  [retrieval/retrieval-tool-definition
   rerank/rerank-tool-definition
   synthesis/synthesis-tool-definition
   query-planner/query-planner-tool-definition
   entity-extraction/entity-extraction-tool-definition
   fact-checking/fact-checking-tool-definition
   summarization/summarization-tool-definition
   multi-retrieval/multi-retrieval-tool-definition
   graph-builder/graph-builder-tool-definition])

(defn get-skill-tool-definition
  "Get tool definition for a specific skill.

   Args:
     skill-id - Skill ID keyword

   Returns: Tool definition map or nil"
  [skill-id]
  (case skill-id
    :builtin/retrieval retrieval/retrieval-tool-definition
    :builtin/rerank rerank/rerank-tool-definition
    :builtin/synthesis synthesis/synthesis-tool-definition
    :builtin/query-planner query-planner/query-planner-tool-definition
    :builtin/entity-extraction entity-extraction/entity-extraction-tool-definition
    :builtin/fact-checking fact-checking/fact-checking-tool-definition
    :builtin/summarization summarization/summarization-tool-definition
    :builtin/multi-retrieval multi-retrieval/multi-retrieval-tool-definition
    :builtin/graph-builder graph-builder/graph-builder-tool-definition
    nil))

;; =============================================================================
;; Introspection
;; =============================================================================

(defn list-skills
  "List all registered skills.

   Returns: Sequence of skill metadata maps"
  []
  (initialize!)
  (map :metadata (skills/list-skills)))

(defn list-templates
  "List all registered templates.

   Returns: Sequence of template maps"
  []
  (initialize!)
  (templates/list-templates))

(defn get-skill-info
  "Get information about a skill.

   Args:
     skill-id - Skill ID keyword

   Returns: Skill metadata or nil"
  [skill-id]
  (initialize!)
  (when-let [skill (skills/get-skill skill-id)]
    (:metadata skill)))

(defn get-template-info
  "Get information about a template.

   Args:
     template-id - Template ID keyword

   Returns: Template map or nil"
  [template-id]
  (initialize!)
  (templates/get-template template-id))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn simple-qa
  "Execute a simple Q&A workflow using the simple-qa template.

   Args:
     query - User query string
     collections - Map with :docs-collection, :chunks-collection, :phrases-collection
     opts - Execution options (:tenant, :environment)

   Returns: Map with :response, :chunks"
  [query collections opts]
  (let [inputs {:user-query query
                :docs-collection (:docs-collection collections)
                :chunks-collection (:chunks-collection collections)
                :phrases-collection (:phrases-collection collections)}]
    (run-template :builtin/simple-qa inputs opts)))

(defn research
  "Execute a research workflow using the research-assistant template.

   Args:
     query - User query string
     collections - Map with :docs-collection, :chunks-collection, :phrases-collection
     opts - Execution options

   Returns: Map with :response, :chunks, :search-phrases"
  [query collections opts]
  (let [inputs {:user-query query
                :docs-collection (:docs-collection collections)
                :chunks-collection (:chunks-collection collections)
                :phrases-collection (:phrases-collection collections)}]
    (run-template :builtin/research-assistant inputs opts)))

(comment
  ;; Initialize the system
  (initialize!)

  ;; List available skills
  (list-skills)

  ;; List available templates
  (list-templates)

  ;; Execute a skill directly
  (execute :builtin/query-planner
           {:query "What is the tax deduction for home office?"}
           {:tenant "ka" :environment "prod"})

  ;; Run a template
  (simple-qa "How do I file taxes?"
             {:docs-collection "ka_docs"
              :chunks-collection "ka_chunks"
              :phrases-collection "ka_phrases"}
             {:tenant "ka" :environment "prod"})

  ;; Get tool definitions for an agent
  (get-all-tool-definitions))
