# Skill-Based RAG Architecture

This directory contains documentation for the transition to skill-based agentic RAG.

---

## Quick Start

### For Implementers

1. **Read the architecture decision**: [`ARCHITECTURE_DECISION.md`](./ARCHITECTURE_DECISION.md)
   - Understand the vision and why we're doing this
   - See what's in scope for Phase 1 & 2

2. **Follow the implementation plan**: [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)
   - Step-by-step guide for building skills
   - File structure and code examples
   - Testing strategy

3. **Start implementing**:
   ```bash
   # Create skill namespace structure
   mkdir -p server/src/digdir/rag/skills
   mkdir -p server/test/digdir/rag/skills

   # Start with core abstractions
   # 1. Create skills/core.clj (protocol)
   # 2. Create skills/registry.clj (registration)
   # 3. Extract first skill: query_expansion.clj
   # 4. Test it works
   # 5. Continue with other skills
   ```

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

### What is a Skill?

A **skill** is a discrete, composable capability that takes inputs and produces outputs:

```clojure
;; Skill definition
{:id :query-expansion/llm-v1
 :name "LLM Query Expansion v1"
 :inputs [:user-query :conversation-history]
 :outputs [:search-phrases :confidence]
 :parameters {:prompt :string, :model :string}}

;; Skill execution
(execute-skill :query-expansion/llm-v1
  {:user-query "hva er skattefradrag?"
   :conversation-history [...]}
  {:tenant "ka"
   :environment "prod"
   :pipeline-name "government-services"})
;; => {:outputs {:search-phrases ["skattefradrag" "tax deduction" ...]
;;               :confidence 0.95}
;;     :metadata {:duration-ms 250, :model-used "gpt-4o"}}
```

### What is a Composition?

A **composition** is a sequence of skills:

```clojure
;; Standard RAG composition
[:query-expansion/llm-v1        ; User query → search phrases
 :search/multi-strategy         ; Search phrases → ranked chunks
 :rerank/colbert                ; Ranked chunks → top chunks
 :generate/answer-with-citations] ; Top chunks + query → answer
```

### How do Pipelines Fit In?

**Pipelines configure skills**:
- Which skills are available
- Parameters for each skill (prompts, models, thresholds)
- Default composition (recommended workflow)
- Agent instructions (future: for orchestration)

All configuration uses the existing 8-level inheritance hierarchy.

---

## Current RAG Pipeline → Skills Mapping

| Current Stage | Skill(s) | File |
|---------------|----------|------|
| `query-relaxation` | `:query-expansion/llm-v1` | `query_expansion.clj` |
| `lookup-search-phrases` | `:search/phrase`<br>`:search/metadata`<br>`:search/content`<br>`:search/multi-strategy` | `search.clj` |
| `retrieve-chunks` | (part of search skills) | `search.clj` |
| `rerank-chunks` | `:rerank/colbert` | `rerank.clj` |
| `generate-response` | `:generate/answer-with-citations` | `generation.clj` |

Other stages (init, fetch-messages, transact-assistant-msg, etc.) remain as orchestration logic.

---

## Phase 1 & 2 Scope

**✅ In Scope**:
- Extract pipeline stages as skills
- Build skill registry and executor
- Create standard RAG composition
- Update Playground Chat to use skills
- Unit and integration tests

**❌ Out of Scope** (deferred to Phase 3+):
- MCP server
- Agent orchestration (Claude-as-orchestrator)
- Dynamic skill selection
- UI for skill composition

**Why**: Prove the abstraction works before adding complexity.

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
  [{:keys [inputs parameters services pipeline-config]}]
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
   {:param1 "pipeline.my-category.my-skill.param1"}})
```

### 2. Test the Skill

```clojure
;; Unit test (mock services)
(deftest test-my-skill
  (let [result (execute-my-skill
                 {:inputs {:input1 "test" :input2 "data"}
                  :parameters {:param1 "config"}
                  :services {:service1 (mock-service)}
                  :pipeline-config {}})]
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

### 3. Use in a Composition

```clojure
;; Add to a composition
(def my-workflow
  {:id :composition/my-workflow
   :skills [:query-expansion/llm-v1
            :my-category/my-skill  ; <-- Your skill
            :generate/answer-with-citations]})

;; Execute
(executor/execute-skill-sequence
  (:skills my-workflow)
  {:user-query "test query"}
  {:tenant "ka" :environment "prod" :pipeline-name "main"})
```

---

## Configuration Examples

### Define Skill Parameters in Pipeline

```clojure
;; Create pipeline with skill configuration
(pipeline/create-pipeline! conn
  {:tenant "ka"
   :environment "prod"
   :pipeline-name "my-pipeline"
   :properties {;; Query expansion config
                :skill-param-query-expansion-v1-prompt "Expand to Norwegian and English..."
                :skill-param-query-expansion-v1-model "gpt-4o"
                :skill-param-query-expansion-v1-max-phrases 5

                ;; Search config
                :skill-param-search-phrase-top-k 20
                :skill-param-search-phrase-hybrid-weights {:text 0.3 :vector 0.7}

                ;; Rerank config
                :skill-param-rerank-colbert-top-k 10
                :skill-param-rerank-colbert-max-chunk-length 512

                ;; Generation config
                :skill-param-generate-prompt "You are a Norwegian government expert..."
                :skill-param-generate-model "gpt-4o"
                :skill-param-generate-max-context-length 8000}
   :master-key master-key})
```

### Enable/Disable Skills (Future)

```clojure
;; Specify which skills are available
(pipeline/create-pipeline! conn
  {:tenant "ka"
   :environment "prod"
   :pipeline-name "restricted-pipeline"
   :properties {:enabled-skills #{:query-expansion/llm-v1
                                  :search/phrase
                                  :generate/answer-with-citations}
                ;; ColBERT reranking NOT available in this pipeline
                }
   :master-key master-key})
```

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
- Dynamic tool generation from pipeline config

### Phase 4: Agent Orchestration
- Claude-as-orchestrator execution mode
- Extended thinking integration
- Dynamic skill selection based on query complexity
- Quality gates and constraints

### Phase 5: Full Migration
- Migrate all existing pipelines
- UI for skill composition visualization
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
1. Follow the skill protocol in `skills/core.clj`
2. Register in `skills/registry.clj`
3. Map parameters in `skills/parameters.clj`
4. Write unit tests
5. Write integration tests
6. Document in this README

---

## Status

**Current Phase**: Not Started
**Next Step**: Create `server/src/digdir/rag/skills/core.clj` with skill protocol

See `IMPLEMENTATION_PLAN.md` for detailed next steps.
