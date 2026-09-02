# Skill-Based RAG Architecture

> **HISTORICAL** — superseded by [`decisions/skill-based-agentic-rag.md`](../../../decisions/skill-based-agentic-rag.md) (the accepted ADR) and `docs/system-overview.md` §5.3–5.4, which describe the current skills/agents implementation. Kept as a design record; some of the roadmap below (e.g. the Phase 3 MCP server) has since shipped in a different shape than described here — see the current docs above for what's actually live.

This directory contains documentation for the skill-based agentic RAG architecture.

---

## Quick Start

### For Implementers

1. **Read the architecture decision**: [`decisions/skill-based-agentic-rag.md`](../../../decisions/skill-based-agentic-rag.md)
   - Understand the vision and why we're doing this
   - See what's in scope for Phase 1 & 2

2. **Follow the implementation plan**: no standalone `IMPLEMENTATION_PLAN.md` was ever committed; treat the ADR above plus the current source (`server/src/digdir/skills/`) as the implementation reference.
   - Step-by-step guide for building skills
   - File structure and code examples
   - Testing strategy

3. **Review the current implementation**:
   - Skill core and registry: `server/src/digdir/rag/skills/core.clj`
   - Skill graphs and runner: `server/src/digdir/skills/graph/`
   - Built-in skills: `server/src/digdir/skills/builtin/`
   - Skill graph registry/storage: `server/src/digdir/skills/templates/`

### For Reviewers

- **Architecture Decision**: Is this the right approach?
- **Implementation Plan**: Are the steps clear and achievable?
- **File Structure**: Does the organization make sense?

---

## Documentation Index

| Document | Purpose | Audience |
|----------|---------|----------|
| **ARCHITECTURE_DECISION.md** | Why we're doing this, vision, phases | Everyone |
| **IMPLEMENTATION_PLAN.md** | How to build Phase 1 & 2 | Implementers |
| **SKILLS_README.md** (this file) | Navigation and quick reference | Everyone |

---

## Key Concepts

### What is a Skill Definition?

A **skill definition** is data describing a skill’s inputs, outputs, parameters, tool definition, version, and tags.
A **skill implementation** is the code that executes a skill definition.

```clojure
;; Skill definition (data)
{:skill-id :query-expansion/llm-v1
 :name "LLM Query Expansion v1"
 :inputs [:user-query :conversation-history]
 :outputs [:search-phrases :confidence]
 :parameters {:prompt :string, :model :string}}
```

### What is a Skill Graph?

A **skill graph** is a directed acyclic graph of skills. It is the governed, callable unit of runtime execution.

```clojure
;; Standard RAG skill graph (conceptual)
[:query-expansion/llm-v1        ; User query → search phrases
 :search/multi-strategy         ; Search phrases → ranked chunks
 :rerank/colbert                ; Ranked chunks → top chunks
 :generate/answer-with-citations] ; Top chunks + query → answer
```

### How do Pipelines Fit In?

**Pipelines materialize datasets for retrieval.** Skill graphs define how skills are composed at runtime.
API keys provide access to pipelines and skill graphs, with optional filters for tenant, environment, pipeline, and skill graph.

All configuration uses the existing 8-level inheritance hierarchy.

---

## Current RAG Pipeline → Skill Mapping

| Current Stage | Skill(s) | File |
|---------------|----------|------|
| `query-relaxation` | `:query-expansion/llm-v1` | `query_expansion.clj` |
| `lookup-search-phrases` | `:search/phrase`<br>`:search/metadata`<br>`:search/content`<br>`:search/multi-strategy` | `search.clj` |
| `retrieve-chunks` | (part of search skills) | `search.clj` |
| `rerank-chunks` | `:rerank/colbert` | `rerank.clj` |
| `generate-response` | `:generate/answer-with-citations` | `generation.clj` |

Other stages (init, fetch-messages, transact-assistant-msg, etc.) remain as orchestration logic.

---

## Current Status

- Skill definitions/implementations and registry are in place.
- Skill graphs and graph runner are implemented.
- Playground Chat supports skill-based execution.
- Original RAG pipeline in `digdir.rag.core` is available as a fallback.

---

## Development Workflow

### 1. Create a New Skill

```clojure
;; 1. Define metadata
(def my-skill-metadata
  {:id :my-category/my-skill
   :name "My Skill"
   :description "What it does"
   :category :my-category
   :inputs [:input1 :input2]
   :outputs [:output1]
   :parameters {:param1 :string}
   :required-services #{:service1}})

;; 2. Implement execute function
(defn execute-my-skill
  [{:keys [inputs parameters services skill-params]}]
  (let [{:keys [input1 input2]} inputs
        {:keys [param1]} parameters
        service (:service1 services)]

    ;; Do work
    (let [result (do-something service input1 input2 param1)]

      {:outputs {:output1 result}
       :metadata {:duration-ms ...}})))

;; 3. Register skill
(registry/register-skill! my-skill-metadata execute-my-skill)

;; 4. Map parameters to config paths
;; In parameters.clj:
(def skill-parameter-paths
  {:my-category/my-skill
   {:param1 "skills.my-category.my-skill.param1"}})
```

### 2. Test the Skill

```clojure
;; Unit test (mock services)
(deftest test-my-skill
  (let [result (execute-my-skill
                 {:inputs {:input1 "test" :input2 "data"}
                  :parameters {:param1 "config"}
                  :services {:service1 (mock-service)}
                  :skill-params {}})]
    (is (= "expected" (get-in result [:outputs :output1])))))

;; Integration test (real services, dev environment)
(deftest test-my-skill-integration
  (let [result (executor/execute-skill
                 :my-category/my-skill
                 {:input1 "test" :input2 "data"}
                 {:tenant "test"
                  :environment "dev"
                  :pipeline-name "test-pipeline"
                  :master-key test-key})]
    (is (some? (get-in result [:outputs :output1])))))
```

### 3. Use in a Skill Graph

```clojure
;; Skill graph (conceptual)
{:id :skill-graph/my-graph
 :skills [:query-expansion/llm-v1
          :my-category/my-skill  ; <-- Your skill
          :generate/answer-with-citations]}
```

---

## Configuration Notes

- Pipelines are for ingestion/materialization only.
- Skill graph configuration controls runtime behavior and parameters.
- API keys scope access to pipelines and skill graphs, with optional tenant/environment filters.
- Runtime API and UI use `skill graph` terminology directly (no template alias surface).

---

## Testing Commands

```bash
# Run all skill tests
bb test :dirs server/test/digdir/rag/skills

# Run specific skill test
bb test :includes digdir.rag.skills.query-expansion-test

# Run integration tests
bb test :includes digdir.rag.skills.integration-test

# Lint
bb lint
```

---

## Future Phases (Post Phase 1 & 2)

### Phase 3: MCP Server
- Build MCP server that exposes skills as tools
- TypeScript or Clojure implementation
- Dynamic tool generation from skill graph config

### Phase 4: Agent Orchestration
- Claude-as-orchestrator execution mode
- Extended thinking integration
- Dynamic skill selection based on query complexity
- Quality gates and constraints

### Phase 5: Full Migration
- Migrate legacy runtime flows to skill graphs
- UI for skill graph visualization
- A/B testing framework
- Performance optimization

---

## Getting Help

- **Architecture questions**: See `ARCHITECTURE_DECISION.md`
- **Implementation questions**: See `IMPLEMENTATION_PLAN.md`
- **Code questions**: Check the implementation files in `server/src/digdir/rag/skills/`
- **Testing questions**: Check test files in `server/test/digdir/rag/skills/`

---

## Contributing

When adding new skills:
1. Follow the skill protocol in `server/src/digdir/rag/skills/core.clj`
2. Implement in `server/src/digdir/skills/builtin/`
3. Register in `server/src/digdir/skills/api.clj` and `server/src/digdir/skills/init.clj`
4. Write unit tests
5. Write integration tests
6. Document in this README

---

## Status

**Current Phase**: Skill graph foundation implemented
**Next Step**: MCP server and agent orchestration (Phase 3+)

See `IMPLEMENTATION_PLAN.md` for detailed next steps.
