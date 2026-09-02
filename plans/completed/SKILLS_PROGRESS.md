# Skills Implementation Progress

**Last Updated**: 2026-02-04
**Current Phase**: Phase 1 & 2 - Foundation

---

## ✅ Completed

### 1. Core Abstractions (`skills/core.clj`)

**Status**: ✅ Complete and tested

**What was built**:
- Malli schemas for skill metadata, execution context, and results
- Validation functions with human-readable error messages
- Result helpers (success/error constructors, checking, accessors)
- Context validation (required inputs and services)
- Utility functions for working with skills
- Exception wrapping
- Comprehensive inline documentation

**Key Features**:
- **Skill Metadata Schema**: Defines what a skill is
  - Namespaced keyword ID (e.g., `:query-expansion/llm-v1`)
  - Name, description, category
  - Input/output specifications
  - Parameter definitions with types
  - Required services declaration
  - Optional versioning and tags

- **Execution Context Schema**: What gets passed to a skill
  - Skill ID
  - Input data map
  - Resolved parameters from pipeline config
  - Service clients (Typesense, OpenAI, etc.)
  - Pipeline configuration
  - Optional execution ID for tracking

- **Execution Result Schema**: What a skill returns
  - Either success (outputs + metadata) or error
  - Standardized error format with type, message, and data
  - Duration tracking and arbitrary metadata support

**Example Usage**:
```clojure
;; Define skill metadata
(def query-expansion-metadata
  {:skill-id :query-expansion/llm-v1
   :name "LLM Query Expansion v1"
   :description "Expands user query using Azure OpenAI"
   :category :query-transformation
   :inputs [:user-query :conversation-history]
   :outputs [:search-phrases :confidence]
   :parameters {:prompt :string
                :model :string
                :max-phrases :number}
   :required-services #{:azure-openai}})

;; Validate metadata
(validate-skill-metadata! query-expansion-metadata)
;; => returns metadata if valid, throws ex-info with human-readable errors if invalid

;; Create execution context
(def ctx
  (make-execution-context
    :query-expansion/llm-v1
    {:user-query "hva er skattefradrag?"
     :conversation-history []}
    {:prompt "Expand query..." :model "gpt-4o" :max-phrases 5}
    {:azure-openai {:client "..."}}
    {:tenant "ka" :environment "prod"}
    {:execution-id "exec-123"}))

;; Check context validity
(check-required-inputs query-expansion-metadata (:inputs ctx))
;; => nil (valid) or error map

;; Create results
(def success
  (success-result
    {:search-phrases ["skattefradrag" "tax deduction"]
     :confidence 0.95}
    {:duration-ms 250 :model-used "gpt-4o"}))

(result-success? success) ;; => true
(get-result-outputs success) ;; => {:search-phrases [...] :confidence 0.95}

(def error
  (error-result :api-timeout "Request timed out" {:timeout-ms 5000}))

(result-error? error) ;; => true
(get-result-error error) ;; => {:error-type :api-timeout, ...}
```

**Files Created**:
- `server/src/digdir/rag/skills/core.clj` (589 lines)
- `server/test/digdir/rag/skills/core_test.clj` (186 lines)

**Test Status**: ✅ All manual tests passing

---

## 📋 Next Steps (In Order)

### 2. Skill Registry (`skills/registry.clj`)

**What to build**:
- Global atom storing all registered skills
- `register-skill!` function with validation
- `get-skill` for lookup by ID
- `list-skills` with optional category filtering
- Auto-registration pattern for skills

**Estimated effort**: 1-2 hours

### 3. Parameter Resolution (`skills/parameters.clj`)

**What to build**:
- Mapping from skill ID + parameter name to config path
- `resolve-skill-parameters` using existing 8-level config hierarchy
- Default parameter values
- Parameter type coercion if needed

**Key insight**: Reuse existing `config-db/resolve-value` and `config-db/decode-value`

**Estimated effort**: 2-3 hours

### 4. Service Context (`skills/services.clj`)

**What to build**:
- Build service client map from pipeline config
- Typesense client builder
- Azure OpenAI client builder
- HTTP client builder
- ColBERT API client builder

**Key insight**: Extract existing client construction logic

**Estimated effort**: 1-2 hours

### 5. Skill Executor (`skills/executor.clj`)

**What to build**:
- `execute-skill` - single skill execution
- `execute-skill-sequence` - sequential execution with threading
- Error handling and logging
- Execution tracking

**Estimated effort**: 3-4 hours

### 6. Extract Query Expansion Skill (`skills/query_expansion.clj`)

**What to build**:
- Extract logic from `digdir.rag.core` query-relaxation stage
- Define metadata
- Implement execute function
- Register skill
- Unit tests

**Reference**: `digdir.rag.core` lines ~1150-1200

**Estimated effort**: 2-3 hours

### 7. Extract Search Skills (`skills/search.clj`)

**What to build**:
- Phrase search skill
- Metadata search skill
- Content search skill
- Multi-strategy search skill (composition)
- Rank fusion logic

**Reference**: `digdir.rag.core` lines ~1200-1300

**Estimated effort**: 4-5 hours

### 8. Extract Reranking Skills (`skills/rerank.clj`)

**What to build**:
- ColBERT reranking skill
- (Optional) LLM judge reranking skill

**Reference**: `digdir.rag.core` lines ~1300-1350

**Estimated effort**: 2-3 hours

### 9. Extract Generation Skills (`skills/generation.clj`)

**What to build**:
- Answer generation with citations skill
- Context building logic
- Citation extraction

**Reference**: `digdir.rag.core` lines ~1350-1400

**Estimated effort**: 2-3 hours

### 10. Create Compositions (`skills/compositions.clj`)

**What to build**:
- Standard RAG workflow composition
- Composition executor
- Composition validation

**Estimated effort**: 1-2 hours

### 11. Update Playground Chat

**What to modify**:
- `server/src/digdir/playground/core.cljc`
- Replace `rag/rag-pipeline` with `compositions/execute-standard-rag`
- Verify functionality
- Test in UI

**Estimated effort**: 2-3 hours

---

## 📊 Overall Progress

- [x] **Step 1**: Core abstractions (✅ Complete)
- [ ] **Step 2**: Skill registry
- [ ] **Step 3**: Parameter resolution
- [ ] **Step 4**: Service context
- [ ] **Step 5**: Skill executor
- [ ] **Step 6**: Query expansion skill
- [ ] **Step 7**: Search skills
- [ ] **Step 8**: Reranking skills
- [ ] **Step 9**: Generation skills
- [ ] **Step 10**: Compositions
- [ ] **Step 11**: Playground Chat integration

**Completion**: 9% (1/11 steps)

**Estimated total time remaining**: 20-30 hours

---

## 🎯 Success Criteria (Reminder)

Phase 1 & 2 is complete when:

1. ✅ All skills are extracted and registered
2. ✅ Skill executor can run standard RAG composition
3. ✅ Playground Chat uses skill-based execution
4. ✅ All unit tests pass
5. ✅ Integration tests show no regression
6. ✅ Performance is within 10% of current implementation
7. ✅ Code is documented and reviewed
8. ✅ Clear path to Phase 3 (MCP server) is evident

---

## 📝 Notes

### Why Malli?

Chose Malli over clojure.spec.alpha because:
- Already in project dependencies
- Better error messages (humanized output)
- More concise syntax
- Better performance
- More flexible (easier to compose schemas)
- Good tooling support

### Design Decisions Made

1. **Synchronous execution**: Skills return values directly (not Missionary tasks)
   - Rationale: Simpler to start, can add async later
   - Future: Phase 3+ can add async execution

2. **Error handling**: Errors returned in result map (not thrown)
   - Rationale: Makes skill execution more functional and testable
   - Skill can indicate failure without breaking the chain

3. **Parameter types**: Using keywords (`:string`, `:number`, etc.)
   - Rationale: Simple, extensible, matches existing config system
   - Future: Can add custom validators if needed

4. **Service context**: Built once per execution, passed to all skills
   - Rationale: Avoids recreating clients, enables connection pooling
   - Services are shared across skill invocations in a sequence

### Questions to Resolve

- [ ] Should we support skill-level caching? (e.g., memoize query expansion results)
- [ ] How to handle partial failures in skill sequences? (Continue or abort?)
- [ ] Should we add skill execution timeouts?
- [ ] Do we need skill middleware/interceptors?
- [ ] Should services be lazy-loaded or all created upfront?

---

## 🔗 Related Documentation

- [Architecture Decision](./ARCHITECTURE_DECISION.md) - Full vision and rationale
- [Implementation Plan](./IMPLEMENTATION_PLAN.md) - Detailed step-by-step guide
- [Skills README](./SKILLS_README.md) - Navigation and quick reference
