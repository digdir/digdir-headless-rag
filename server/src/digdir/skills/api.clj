(ns digdir.skills.api
  "Public API for skill invocation.

   Provides a unified interface for:
   - Executing individual skills
   - Running skill graphs
   - Managing skill registration"
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.context :as ctx]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.templates.core :as templates]
            [digdir.skills.templates.builtin :as builtin-templates]
            ;; Builtin skills
            [digdir.skills.builtin.retrieval :as retrieval]
            [digdir.skills.builtin.rerank :as rerank]
            [digdir.skills.builtin.synthesis :as synthesis]
            [digdir.skills.builtin.query-planner :as query-planner]
            [digdir.skills.builtin.entity-extraction :as entity-extraction]
            [digdir.skills.builtin.fact-checking :as fact-checking]
            [digdir.skills.builtin.summarization :as summarization]
            [digdir.skills.builtin.multi-retrieval :as multi-retrieval]
            [digdir.skills.builtin.graph-builder :as graph-builder]
            [digdir.skills.builtin.agent :as agent]
            ;; Phase 2.5: agent graph-cutover skills + outer/inner graphs.
            ;; These must register BEFORE :graph-variant :bundled / :faithful
            ;; can be invoked — otherwise :builtin/agent-rag-graph-bundled /
            ;; -faithful aren't in the templates registry and dispatch fails.
            [digdir.skills.builtin.agent.graphs :as agent-graphs]
            [digdir.skills.builtin.agent.iteration-bundled :as agent-iter-bundled]
            [digdir.skills.builtin.agent.iteration-faithful :as agent-iter-faithful]))

;; =============================================================================
;; Initialization
;; =============================================================================

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
  (graph-builder/register!)
  (agent/register!)
  ;; Phase 2.5 — graph-cutover skills + outer/inner graphs. Order
  ;; matters: agent-graphs registers the two outer graphs (which
  ;; reference inner sub-graphs by id); the inner sub-graph
  ;; registrations land via agent-iter-bundled/-faithful below.
  (agent-graphs/register!)
  (agent-iter-bundled/register!)
  (agent-iter-faithful/register!))

(defn register-builtin-skill-graphs!
  "Register all built-in skill graphs."
  []
  (builtin-templates/register-all!))

(defn initialize!
  "Initialize the skills system with built-in skills and skill graphs.

   Delegates to `digdir.skills.init/ensure-initialized!` — the one door (#91).
   Two initialisers with two state atoms is what let a caller receive a registry
   that one of them believed was populated and the other had cleared (#85), and
   what let this one register graphs whose skills the other never registered.

   Resolved at runtime rather than required: `digdir.skills.init` requires
   `digdir.agents.core`, which requires this namespace, so a direct `:require`
   would close a cycle."
  []
  ((requiring-resolve 'digdir.skills.init/ensure-initialized!)))

(defn reset-skills!
  "Reset the skills system. Clears all registrations.

   Delegates for the same reason `initialize!` does (#91): this namespace no
   longer keeps initialisation state of its own. Two state atoms tracking one
   pair of registries is what let a reset here leave the other initialiser
   believing the registry was still populated (#85)."
  []
  ((requiring-resolve 'digdir.skills.init/reset-skills!)))

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
       :dataset-config-key - dataset selector
       :runtime-config-key - runtime selector for graph/template execution
       :entity - Entity identifier (optional)
       :skill-params - Skill parameter overrides by skill-id
       :parameters - Parameter overrides
  Returns: Skill execution result map"
  [skill-id inputs opts]
  (initialize!)
  (let [{:keys [inputs opts]} (ctx/apply-dataset-context inputs opts)
        ctx (ctx/build-execution-context skill-id inputs opts)]
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
       :dataset-config-key - dataset selector
       :runtime-config-key - runtime selector for graph/template execution
       :skill-params - Skill parameter overrides by skill-id
  Returns: Graph execution result"
  [graph inputs opts]
  (initialize!)
  (let [{:keys [inputs opts]} (ctx/apply-dataset-context inputs opts)]
    (runner/run-graph graph inputs opts)))

(defn run-skill-graph
  "Execute a registered skill graph.

   Args:
     skill-graph-id - Skill graph ID keyword
     inputs - Map of input values
     opts - Execution options:
       :tenant - Tenant identifier
       :dataset-config-key - dataset selector
       :runtime-config-key - runtime selector for graph/template execution
       :overrides - Skill graph parameter overrides
       :skill-params - Skill parameter overrides (by skill-id)
       :model, :temperature, :max-tokens, :prompt - Execution overrides
       :progress-fn - Optional callback receiving graph/step progress payloads
   Returns: Graph execution result"
  [skill-graph-id inputs opts]
  (initialize!)
  (let [{:keys [inputs opts dataset-ref agent-id]} (ctx/apply-dataset-context inputs opts)
        graph-overrides (or (:overrides opts) {})
        {:keys [graph execution-opts]}
        (templates/instantiate-graph
          skill-graph-id
          (:tenant opts)
          (:runtime-config-key opts)
          dataset-ref
          graph-overrides)        extra-skill-params (:skill-params opts)
        exec-opts (cond-> (-> execution-opts
                              (update :skill-params (fnil merge {}) extra-skill-params)
                              (update :skill-params assoc :skill-graph-id skill-graph-id)
                              (update :skill-params assoc :progress-fn (:progress-fn opts))
                              (merge (select-keys opts [:model :temperature :max-tokens :prompt :progress-fn
                                                        :dataset-ref :agent-id])))
                    dataset-ref (update :skill-params assoc :dataset-ref dataset-ref)
                    agent-id (update :skill-params assoc :agent-id agent-id))]
    (runner/run-graph graph inputs exec-opts)))

;; =============================================================================
;; Tool Definitions for Agent Invocation
;; =============================================================================

(defn get-all-tool-definitions
  "Get tool definitions for all registered skills.

   Returns: Vector of tool definition maps for OpenAI function calling"
  []
  (initialize!)
  (->> (skills/list-skills)
       (keep #(get-in % [:metadata :tool-definition]))
       (distinct)
       (vec)))

(defn get-skill-tool-definition
  "Get tool definition for a specific skill.

   Args:
     skill-id - Skill ID keyword

   Returns: Tool definition map or nil"
  [skill-id]
  (initialize!)
  (get-in (skills/get-skill skill-id) [:metadata :tool-definition]))

;; =============================================================================
;; Introspection
;; =============================================================================

(defn list-skills
  "List all registered skills.

   Returns: Sequence of skill metadata maps"
  []
  (initialize!)
  (map :metadata (skills/list-skills)))

(defn list-skill-graphs
  "List all registered skill graphs.

   Returns: Sequence of skill graph maps"
  []
  (initialize!)
  (templates/list-skill-graphs))

(defn get-skill-info
  "Get information about a skill.

   Args:
     skill-id - Skill ID keyword

   Returns: Skill metadata or nil"
  [skill-id]
  (initialize!)
  (when-let [skill (skills/get-skill skill-id)]
    (:metadata skill)))

(defn get-skill-graph-info
  "Get information about a skill graph.

   Args:
     skill-graph-id - Skill graph ID keyword

   Returns: Skill graph map or nil"
  [skill-graph-id]
  (initialize!)
  (templates/get-skill-graph skill-graph-id))

;; Convenience wrappers for :builtin/simple-qa and :builtin/research-assistant
;; were removed in Phase 0 alongside those skill graphs. Callers should
;; invoke `run-skill-graph` directly with the preserved graph ids
;; (:builtin/fact-checker, :builtin/agent-rag-graph-bundled,
;; :builtin/agent-rag-graph-faithful, or any registered :docs/* graph).

(comment
  ;; Initialize the system
  (initialize!)

  ;; List available skills
  (list-skills)

  ;; List available skill graphs
  (list-skill-graphs)

  ;; Execute a skill directly
  (execute :builtin/query-planner
           {:query "What is the tax deduction for home office?"}
           {:tenant "ka" :dataset-config-key "prod"})

  ;; Run a skill graph
  (run-skill-graph :builtin/fact-checker
                   {:claim "Tax deduction X applies to home offices."}
                   {:tenant "ka" :dataset-config-key "prod"
                    :runtime-config-key "default"})

  ;; Get tool definitions for an agent
  (get-all-tool-definitions))
