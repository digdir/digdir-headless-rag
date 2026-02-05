# Pipeline Skills Architecture Plan

## Overview

This document outlines the architecture for evolving the pipeline system to support agent skills, enabling autonomous pipeline management where agents can create and use pipelines via skills.

## Core Concepts

### Two-Tier Architecture

| Tier | Purpose | Execution Time | Example |
|------|---------|----------------|---------|
| **Data Pipeline** | Materialize searchable collections | Build/config time | Load documents -> chunk -> embed -> store |
| **Skill Pipeline** | Execute agent operations | Runtime/query time | Query -> retrieve -> extract -> synthesize |

### Key Design Decisions

1. **Explicit skill invocation** - Agents explicitly decide which skills to call via tool-use
2. **Tiered state model** - Invocation context (phase 1), conversation context (phase 2), skill memory (phase 3)
3. **Template-based composition** - Pre-configured skill graphs, with dynamic graph creation as a meta-skill
4. **External APIs** - Deferred to later phase

---

## Implementation Status

### Completed Components

| Phase | Component | Status | File |
|-------|-----------|--------|------|
| 1 | Skill Protocol & Registry | **DONE** | `server/src/digdir/rag/skills/core.clj` |
| 1 | Config Namespace | **DONE** | `server/src/digdir/config/db.clj` (extended) |
| 1 | Execution Context | **DONE** | `server/src/digdir/pipeline/skills/context.clj` |
| 2 | Retrieval Skill | **DONE** | `server/src/digdir/pipeline/skills/builtin/retrieval.clj` |
| 2 | Rerank Skill | **DONE** | `server/src/digdir/pipeline/skills/builtin/rerank.clj` |
| 2 | Synthesis Skill | **DONE** | `server/src/digdir/pipeline/skills/builtin/synthesis.clj` |
| 2 | Query Planner Skill | **DONE** | `server/src/digdir/pipeline/skills/builtin/query_planner.clj` |
| 3 | Graph Schema | **DONE** | `server/src/digdir/pipeline/skills/graph/schema.clj` |
| 3 | Graph Runner | **DONE** | `server/src/digdir/pipeline/skills/graph/runner.clj` |
| 3 | Templates Core | **DONE** | `server/src/digdir/pipeline/templates/core.clj` |
| 3 | Builtin Templates | **DONE** | `server/src/digdir/pipeline/templates/builtin.clj` |
| 4 | Entity Extraction | **DONE** | `server/src/digdir/pipeline/skills/builtin/entity_extraction.clj` |
| 4 | Fact Checking | **DONE** | `server/src/digdir/pipeline/skills/builtin/fact_checking.clj` |
| 4 | Summarization | **DONE** | `server/src/digdir/pipeline/skills/builtin/summarization.clj` |
| 4 | Multi-Retrieval | **DONE** | `server/src/digdir/pipeline/skills/builtin/multi_retrieval.clj` |
| 4 | Graph Builder | **DONE** | `server/src/digdir/pipeline/skills/builtin/graph_builder.clj` |
| 5 | API Layer | **DONE** | `server/src/digdir/pipeline/skills/api.clj` |
| 5 | Migration Utilities | **DONE** | `server/src/digdir/pipeline/templates/migration.clj` |
| 5 | Init Namespace | **DONE** | `server/src/digdir/pipeline/skills/init.clj` |

### Test Coverage

| Test File | Status | Results |
|-----------|--------|---------|
| `server/test/digdir/rag/skills/core_test.clj` | **PASSING** | 11 tests, 55 assertions, 0 failures |

### Remaining Work

| Priority | Component | Status | Notes |
|----------|-----------|--------|-------|
| High | API Endpoint Integration | **DONE** | Skills endpoints added to `server/src/digdir/api/routes.clj` |
| Medium | UI - Skills Config | **DONE** | `server/src/digdir/pipeline/ui/skills.cljc` |
| Medium | UI - Templates | **DONE** | Included in skills.cljc as Templates tab |
| Medium | Integration Tests | **DONE** | Tests in `test/digdir/pipeline/skills/` |
| Low | Graph Optimizer | **DONE** | `server/src/digdir/pipeline/skills/graph/optimizer.clj` |

---

## Bug Fixes Made During Implementation

Several pre-existing test infrastructure issues were fixed:

1. **`server/test/digdir/api/integration_test.clj`**
   - Issue: Called `(System/exit 0)` when env vars missing, terminating JVM
   - Fix: Replaced with flag-based skip mechanism

2. **`server/test/digdir/pipeline/core_test.clj`**
   - Issue: Used `d/connect` without `d/create-database`; used `binding` on non-dynamic var
   - Fix: Added `d/create-database`, changed to `with-redefs`

3. **`server/test/digdir/pipeline/integration_test.clj`**
   - Issue: Same database creation and binding issues
   - Fix: Same fixes as core_test.clj

4. **`server/src/digdir/pipeline/executor.clj`** and loader files
   - Issue: `t/error!` called with wrong arity
   - Fix: Restructured to use single map argument

5. **`server/src/digdir/pipeline/ui/pipelines.cljc`**
   - Issue: Unbalanced parentheses at line 249
   - Fix: Added missing closing paren

6. **`server/src/digdir/api/routes.clj`**
   - Issue: Reference to undefined `entity-id` variable
   - Fix: Changed to `pipeline-id`

---

## Manual Test Procedures

### 1. Verify Test Suite Runs

```bash
cd /Users/bdbrodie/dev/digdir/rag
bb test
```

**Expected:** 417+ tests run, skills core tests included:
```
Testing digdir.rag.skills.core-test
...
Ran 417 tests containing 1156 assertions.
```

### 2. Verify Skills Core Tests Pass

```bash
cd /Users/bdbrodie/dev/digdir/rag/server
clj -M:test -n digdir.rag.skills.core-test
```

**Expected:**
```
Ran 11 tests containing 55 assertions.
0 failures, 0 errors.
```

### 3. REPL Verification - Skill Registry

```bash
cd /Users/bdbrodie/dev/digdir/rag/server
clj -M:dev
```

```clojure
;; Load the skills system
(require '[digdir.rag.skills.core :as skills])

;; Registry should be empty initially
(skills/list-skills)
;; => ()

;; Test skill registration
(skills/register-skill!
  {:metadata {:skill-id :test/example
              :name "Test Skill"
              :description "A test skill"
              :category :validation
              :inputs [:query]
              :outputs [:result]
              :parameters {:threshold :number}
              :required-services #{}}
   :execute (fn [ctx] (skills/success-result {:result "ok"}))})

;; Verify registration
(skills/list-skill-ids)
;; => (:test/example)

;; Execute skill
(skills/execute-skill :test/example
  {:inputs {:query "test"}
   :parameters {:threshold 0.5}
   :services {}
   :pipeline-config {}})
;; => {:outputs {:result "ok"}, :metadata {:duration-ms ...}}

;; Cleanup
(skills/clear-registry!)
```

### 4. REPL Verification - Skill Metadata Validation

```clojure
(require '[digdir.rag.skills.core :as skills])

;; Valid metadata
(skills/valid-skill-metadata?
  {:skill-id :query-expansion/llm-v1
   :name "LLM Query Expansion"
   :description "Expands queries"
   :category :query-transformation
   :inputs [:user-query]
   :outputs [:search-phrases]
   :parameters {:model :string}
   :required-services #{:azure-openai}})
;; => true

;; Invalid metadata (missing namespace in skill-id)
(skills/valid-skill-metadata?
  {:skill-id :invalid
   :name "Test"
   :category :retrieval})
;; => false
```

### 5. REPL Verification - Graph Schema

```clojure
(require '[digdir.pipeline.skills.graph.schema :as schema])

;; Validate a simple graph
(schema/valid-graph?
  {:graph-id :test-graph
   :name "Test Graph"
   :inputs [:query]
   :outputs [:answer]
   :steps [{:step-id :retrieve
            :skill-id :retrieval/basic
            :inputs {:query :$query}}
           {:step-id :generate
            :skill-id :synthesis/basic
            :inputs {:chunks :retrieve
                     :query :$query}}]
   :entry-point :retrieve
   :output-step :generate})
;; => true

;; Check for cycles (semantic validation)
(schema/detect-cycles
  [{:step-id :a :skill-id :s1 :inputs {:x :b}}
   {:step-id :b :skill-id :s2 :inputs {:y :a}}])
;; => [:a :b :a]  ; Cycle detected
```

### 6. REPL Verification - Templates

```clojure
(require '[digdir.pipeline.templates.builtin :as builtin])

;; Get builtin templates
(keys builtin/builtin-templates)
;; => (:simple-qa :research-assistant :fact-checker)

;; Examine simple-qa template
(:simple-qa builtin/builtin-templates)
;; => {:template-id :simple-qa, :name "Simple Q&A", ...}
```

### 7. REPL Verification - Execution Context

```clojure
(require '[digdir.pipeline.skills.context :as ctx])

;; Build a minimal context (will fail service resolution without config)
(ctx/build-execution-context
  :test/skill
  {:query "test"}
  {:model "gpt-4o"}
  {:tenant "ka" :environment "prod"})
;; => {:skill-id :test/skill, :inputs {...}, :parameters {...}, ...}
```

### 8. Lint Check

```bash
cd /Users/bdbrodie/dev/digdir/rag
bb lint
```

**Expected:** No errors in new skills files.

---

## Schema Definitions

### 1. Skill Definition Schema

```clojure
;; Stored in config system under pipeline.skills.{skill-id}/*
{:skill/id :query-planner
 :skill/name "Query Planner"
 :skill/description "Decomposes complex queries into focused search queries"
 :skill/version "1.0.0"

 ;; Skill type determines execution
 :skill/type :llm  ; :llm | :function | :composite | :graph-builder

 ;; Tool definition (for agent to invoke)
 :skill/tool-definition
 {:name "plan_queries"
  :description "Break down a complex question into multiple search queries"
  :input_schema
  {:type "object"
   :properties
   {:question {:type "string" :description "The user's question"}
    :max_queries {:type "integer" :description "Maximum queries to generate" :default 5}
    :include_relaxations {:type "boolean" :description "Include query relaxations" :default true}}
   :required ["question"]}
  :output_schema
  {:type "object"
   :properties
   {:queries {:type "array"
              :items {:type "object"
                      :properties {:query {:type "string"}
                                   :intent {:type "string"}
                                   :priority {:type "integer"}}}}}}}

 ;; LLM-specific config (resolved via inheritance)
 :llm/model-path "pipeline.skills.query-planner/model"
 :llm/prompt-path "pipeline.skills.query-planner/prompt"

 ;; Context requirements
 :skill/requires-conversation false
 :skill/requires-memory false}
```

### 2. Skill Graph Schema (Templates)

```clojure
{:template/id :research-assistant
 :template/name "Research Assistant"
 :template/description "Multi-query research with entity extraction"

 ;; Data pipeline config (subset of pipeline.* paths to set)
 :template/data-pipeline
 {:source/type :website
  :chunks/strategy :semantic
  :chunks/minimum-length 500
  :retrieval.rerank/enabled true
  :retrieval.rerank/top-k 20}

 ;; Skill graph definition
 :template/skill-graph
 {:graph/id :research-flow
  :graph/entry :query-planning
  :graph/steps
  [{:step/id :query-planning
    :step/skill :query-planner
    :step/inputs {:question :$user-query}
    :step/outputs [:planned-queries]}

   {:step/id :retrieval
    :step/skill :multi-retrieval
    :step/inputs {:queries :planned-queries}
    :step/outputs [:chunks :sources]}

   {:step/id :extraction
    :step/skill :entity-extraction
    :step/inputs {:chunks :chunks
                  :schema :$extraction-schema}
    :step/outputs [:entities]}

   {:step/id :synthesis
    :step/skill :synthesis
    :step/inputs {:question :$user-query
                  :chunks :chunks
                  :entities :entities}
    :step/outputs [:response :citations]}]

  :graph/outputs [:response :citations :entities :sources]}}
```

### 3. Built-in Skills Library

| Skill | Type | Description | Status |
|-------|------|-------------|--------|
| `:query-planner` | LLM | Decomposes complex queries into search queries | **DONE** |
| `:retrieval` | Function | Executes search against TypeSense collections | **DONE** |
| `:multi-retrieval` | Function | Executes multiple queries and merges results | **DONE** |
| `:rerank` | Function | ColBERT reranking of retrieved chunks | **DONE** |
| `:entity-extraction` | LLM | Extracts structured entities from text | **DONE** |
| `:fact-checking` | LLM | Verifies claims against retrieved evidence | **DONE** |
| `:synthesis` | LLM | Generates response with citations | **DONE** |
| `:summarization` | LLM | Condenses long content | **DONE** |
| `:graph-builder` | LLM | Dynamically composes skill graphs | **DONE** |

### 4. Context Model (Tiered)

```clojure
{:invocation {:inputs {...}        ; Explicit inputs
              :config {...}}       ; Resolved config values

 :conversation {:id "conv-123"     ; Optional, if provided
                :history [...]}    ; Previous turns (Phase 2)

 :memory {:tenant "ka"             ; Optional, skill opts-in (Phase 3)
          :skill-id :entity-extraction
          :store <memory-store>}}
```

---

## File Structure

```
server/src/digdir/
├── config/
│   └── db.clj                           # Extended with skill config paths
├── pipeline/
│   ├── core.clj                         # Existing - data pipeline CRUD
│   ├── executor.clj                     # Existing - data pipeline execution
│   ├── collections.clj                  # Existing - collection management
│   ├── loaders/                         # Existing - data source adapters
│   │
│   ├── skills/
│   │   ├── api.clj                      # Unified API for skill/graph execution
│   │   ├── context.clj                  # Execution context building
│   │   ├── init.clj                     # System initialization
│   │   │
│   │   ├── builtin/
│   │   │   ├── entity_extraction.clj    # NER skill
│   │   │   ├── fact_checking.clj        # Claim verification skill
│   │   │   ├── graph_builder.clj        # Dynamic graph creation meta-skill
│   │   │   ├── multi_retrieval.clj      # Multi-query search skill
│   │   │   ├── query_planner.clj        # Query expansion skill
│   │   │   ├── rerank.clj               # ColBERT reranking skill
│   │   │   ├── retrieval.clj            # TypeSense search skill
│   │   │   ├── summarization.clj        # Content summarization skill
│   │   │   └── synthesis.clj            # LLM generation skill
│   │   │
│   │   └── graph/
│   │       ├── runner.clj               # Graph execution engine
│   │       └── schema.clj               # Graph validation schemas
│   │
│   ├── templates/
│   │   ├── builtin.clj                  # Default templates
│   │   ├── core.clj                     # Template CRUD operations
│   │   └── migration.clj                # Entity config migration
│   │
│   └── ui/
│       ├── pipelines.cljc               # Existing
│       ├── executions.cljc              # Existing
│       ├── skills.cljc                  # TODO: Skill configuration UI
│       └── templates.cljc               # TODO: Template management UI
│
└── rag/
    └── skills/
        └── core.clj                     # Skill protocol, registry, validation

server/test/digdir/
└── rag/
    └── skills/
        └── core_test.clj                # Skill core unit tests (11 tests, all passing)
```

---

## Default Templates

| Template | Data Pipeline | Skill Graph | Status |
|----------|--------------|-------------|--------|
| `simple-qa` | Basic chunking | retrieve -> synthesize | **DONE** |
| `research-assistant` | Semantic chunking | query-plan -> multi-retrieve -> extract -> synthesize | **DONE** |
| `fact-checker` | Dense phrases | retrieve -> fact-check -> summarize | **DONE** |

---

## Architecture Notes

### Skill Execution Flow

```
Agent Request
    |
    v
API Endpoint (/api/skills/execute)
    |
    v
digdir.pipeline.skills.api/execute-skill
    |
    v
digdir.rag.skills.core/execute-skill
    |
    v
Skill Execute Function
    |
    v
Service Calls (TypeSense, Azure OpenAI, ColBERT)
    |
    v
Result with Outputs + Metadata
```

### Graph Execution Flow

```
Template Selection
    |
    v
digdir.pipeline.templates.core/instantiate
    |
    v
digdir.pipeline.skills.graph.runner/run-graph
    |
    v
Topological Sort of Steps
    |
    v
For Each Step:
    - Resolve Inputs (from graph inputs or previous step outputs)
    - Execute Skill
    - Store Outputs
    |
    v
Final Output from output-step
```

### Config Resolution (Inheritance)

Skills inherit configuration through the standard 8-level precedence:
1. Entity + Tenant + Environment (most specific)
2. Entity + Tenant
3. Entity + Environment
4. Tenant + Environment
5. Entity only
6. Tenant only
7. Environment only
8. Global default (least specific)

Config paths follow pattern: `pipeline.skills.{skill-id}/{property}`

---

## Migration Strategy

1. **Keep existing pipelines unchanged** - They become "data pipelines"
2. **Add skill config namespace** - `pipeline.skills.*` (DONE)
3. **Implement skill executor** - New namespace, no changes to existing code (DONE)
4. **Create default skill graph** - Matches current RAG behavior (DONE)
5. **Add template system** - Optional layer on top (DONE)
6. **Migrate entity configs** - Entity retrieval/generation settings -> skill graph configs (DONE)
7. **Wire API endpoints** - TODO
8. **Build UI components** - TODO

---

## Next Steps (Priority Order)

1. **Wire API Endpoints**
   - Add skill execution endpoint to routes.clj
   - Return available skills as tools for agent invocation

2. **Integration Tests with Mocked Services**
   - Create tests that mock TypeSense, Azure OpenAI, ColBERT
   - Verify each builtin skill produces expected outputs

3. **UI Components**
   - Create skills.cljc for skill configuration UI
   - Create templates.cljc for template management UI

4. **End-to-End Testing**
   - Test full RAG pipeline using skills system
   - Verify backwards compatibility with existing entity configs
