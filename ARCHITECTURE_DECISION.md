# Architecture Decision: Skill-Based Agentic RAG

**Date**: 2026-02-04
**Status**: In Progress
**Decision**: Transition from fixed pipeline to skill-based agentic RAG architecture

---

## Context

The current RAG system uses a fixed 10-stage state machine defined in `digdir.rag.core`. While functional, this approach lacks:
- Flexibility to compose different retrieval strategies
- Ability to A/B test different approaches
- Clear separation of concerns for individual capabilities
- Foundation for future agentic orchestration

We are transitioning to a **skill-based architecture** where:
- Each stage becomes a composable, testable skill
- Pipelines configure which skills are available and how they behave
- Skills can be composed into workflows (initially fixed, eventually agent-orchestrated)
- All configuration remains explicit, durable, auditable, and reproducible

---

## Vision: Full Agentic RAG (Future State)

The ultimate goal is Claude-orchestrated RAG using Anthropic's MCP/Agent SDK:

```
User Query
    ↓
Claude Agent (with extended thinking)
    ↓
Available Skills (from pipeline config):
  - query-expansion/llm-v1
  - query-expansion/llm-v2
  - search/phrase
  - search/metadata
  - search/content
  - search/multi-strategy
  - rerank/colbert
  - rerank/llm-judge
  - generate/answer-with-citations
    ↓
Agent decides which skills to use and in what order
    ↓
Full execution trace logged for auditability
    ↓
Answer + reasoning + skill trace
```

### Benefits of Full Vision:
1. **Dynamic strategy selection**: Simple queries skip reranking, complex queries use multi-hop reasoning
2. **Self-optimization**: Agent learns which skill combinations work best
3. **Auditability**: Complete trace of decisions and skill invocations
4. **Reproducibility**: Re-run with same config = same behavior
5. **Multi-tenancy**: Different skills/behaviors per tenant via config

---

## Implementation Strategy: Incremental Rollout

### **Phase 1 & 2: Foundation (Current Focus)**
**Goal**: Build skill abstraction and prove it works

**Scope**:
1. ✅ Extract current pipeline stages into discrete skills
2. ✅ Build skill registry with metadata
3. ✅ Implement skill parameter resolution (using existing 8-level config hierarchy)
4. ✅ Create skill executor that loads pipeline config and calls skills
5. ✅ Verify by updating Playground Chat to use skill-based execution

**Non-Goals** (Postponed):
- ❌ MCP server implementation
- ❌ Agent orchestration / Claude-as-orchestrator
- ❌ Dynamic skill selection
- ❌ Migration of existing pipelines

**Why This Approach**:
- **Prove the abstraction works** without over-engineering
- **No breaking changes** - Playground Chat works the same, just different internals
- **Learn what skill APIs should look like** before committing to MCP protocol
- **Incremental value** - skills are composable even without agent orchestration

---

### **Phase 3: MCP Server (Postponed)**
Build MCP server that exposes skills as tools for Claude agents.

**Deliverables**:
- TypeScript MCP server (or Clojure equivalent)
- Dynamic tool generation from pipeline config
- Skill execution via HTTP API

---

### **Phase 4: Agent Executor (Postponed)**
Implement Claude-as-orchestrator execution mode.

**Deliverables**:
- Agent execution loop with extended thinking
- Skill invocation tracking and logging
- Composition mode support (strict, flexible, agent-decided)
- Quality gates and constraints enforcement

---

### **Phase 5: Full Migration (Postponed)**
Migrate all existing pipelines to skill-based configuration.

**Deliverables**:
- Migration scripts for existing pipeline configs
- UI for skill composition visualization
- A/B testing framework for skill combinations
- Performance benchmarks

---

## Current Implementation Plan (Phase 1 & 2)

See `IMPLEMENTATION_PLAN.md` for detailed step-by-step guide.

### Success Criteria for Phase 1 & 2:
1. ✅ All current RAG functionality works through skills
2. ✅ Playground Chat uses skill-based execution
3. ✅ Skills are testable in isolation
4. ✅ Skill parameters resolve from pipeline config
5. ✅ No regression in RAG quality or performance
6. ✅ Clear path to Phase 3 (MCP server)

---

## Key Architectural Principles

### 1. **Skills are Pure Functions**
```clojure
(defn execute-skill
  [{:keys [inputs parameters services pipeline-config]}]
  ;; Returns: {:outputs {...} :metadata {...}}
  )
```

### 2. **Pipeline Config Defines Behavior**
- Which skills are available
- Parameters for each skill
- Default composition (workflow)
- Agent instructions and constraints

### 3. **8-Level Inheritance for Skill Parameters**
Skill parameters inherit through the same hierarchy as other config:
```
Global Default → Entity → Tenant → Environment → Tenant+Env → Entity+Tenant → Entity+Env → Entity+Tenant+Env
```

### 4. **Execution is Traceable**
Every skill invocation is logged:
- Skill ID
- Inputs and outputs
- Parameters used
- Duration
- Timestamp

### 5. **Backwards Compatibility Path**
Current fixed pipeline → Skill-based fixed composition → Agent-orchestrated composition

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Skill abstraction is too rigid | Start with flexible interfaces, refine based on Phase 1 & 2 learnings |
| Performance regression | Benchmark current system, compare skill-based execution |
| Complexity explosion | Keep Phase 1 & 2 simple, add complexity only when proven necessary |
| Config management overhead | Leverage existing 8-level hierarchy, add minimal new config |
| Testing burden | Skills are isolated and testable, easier to test than monolithic pipeline |

---

## References

- [Anthropic MCP Skills and Agents](https://cra.mr/mcp-skills-and-agents/)
- Current RAG implementation: `server/src/digdir/rag/core.cljc`
- Pipeline system: `server/src/digdir/pipeline/core.clj`
- Config system: `server/src/digdir/config/db.clj`

---

## Decision Log

- **2026-02-04**: Decided on skill-based architecture with incremental rollout
- **2026-02-04**: Prioritized Phase 1 & 2 implementation with Playground Chat verification
- **2026-02-04**: Postponed MCP server and agent orchestration until foundation is proven
