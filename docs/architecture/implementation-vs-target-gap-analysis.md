# Implementation vs Target Gap Analysis

This document compares the current implementation with the target model defined in [consolidated-target-architecture.md](consolidated-target-architecture.md).

It is intentionally implementation-facing. The goal is to answer:

- what is already aligned
- what is only partially aligned
- what still materially conflicts with the target model
- what should be sequenced next

## Executive Summary

The codebase is meaningfully closer to the target architecture than the older planning documents suggest, but it is not yet conceptually clean.

The strongest aligned areas are:

- root-aware config storage and node-based resolution
- explicit config ceiling authorization
- durable agent entities
- agent-scoped conversational APIs
- dataset-first Playground UI state and conversation scope persistence
- turn-based agent trace capture and Playground observability

The largest remaining mismatches are:

- request-facing config selectors are converged on the main runtime routes and graph/template execution paths, but generic internal selector vocabulary still remains active
- request-facing config selectors are converged on the main runtime routes, graph/template execution paths, and playground runtime bridge, but low-level config accessors still carry generic selector vocabulary
- legacy pipeline identity still exists in internal materialization lookup, operator/materialization endpoints, and migration paths
- runtime execution still exposes multiple overlapping execution models and translation layers

In short:

- config architecture is ahead of the runtime model
- observability is ahead of identity cleanup
- runtime support-layer cleanup is now the main conceptual blocker

## 1. Canonical Domain Model

### Status

Partially aligned.

### What is already aligned

- Agents are first-class durable entities in the config DB.
- Agent policies expose allowed skill graphs and allowed dataset refs.
- Public conversational APIs are agent-scoped.
- Skill graphs are the main execution surface for `/api/rag` and `/api/retrieve`.

Relevant implementation:

- `server/src/digdir/agents/core.clj`
- `server/src/digdir/agents/db.clj`
- `server/src/digdir/agents/policy.clj`
- `server/src/digdir/skills/api.clj`
- `server/src/digdir/api/routes.clj`

### Main gaps

- The runtime still carries both the newer skill-graph execution model and older RAG/pipeline-oriented helper concepts.
- Tool definition generation is still manual and registry-adjacent rather than fully metadata-driven.

Concrete evidence:

- `server/src/digdir/agents/core.clj`, `server/src/digdir/agents/db.clj`, and `server/src/digdir/agents/policy.clj` now operate on canonical `{tenant, dataset-config-key}` refs.
- `server/src/digdir/skills/api.clj` still maintains manual tool-definition lists and `case` dispatch.
- `server/src/digdir/rag/core.cljc` remains a substantial parallel runtime surface rather than a thin adapter.

### Gap assessment

The domain model now uses canonical dataset refs through agent storage and policy. The remaining gap in this area is execution-surface convergence rather than dataset identity shape.

## 2. Configuration Model

### Status

Mostly aligned.

### What is already aligned

- Config definitions are root-aware.
- Config nodes are tenant-local and single-parent.
- Resolution uses explicit nodes and ancestor walking.
- Resolution traces exist.
- API key config ceilings exist and are enforced against explicit nodes.
- Public resolve endpoints for runtime and dataset config exist.

Relevant implementation:

- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/accessor.clj`
- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/api/routes.clj`

### Main gaps

- Legacy aliases like `config-key` still remain active at public boundaries for compatibility.
- Internal APIs still use `tenant-config-key` in the lowest-level config accessor layer, but runtime-facing bridges now prefer `runtime-config-key`.
- Runtime resolution still models compatibility partly in terms of `dataset-id` and dataset/pipeline bindings.
- Dataset-root semantics still mix public dataset selection with pipeline/materialization concepts.

### Updated assessment

- The route layer now accepts `runtime-config-key` and `dataset-config-key` on the explicit config-resolve endpoints.
- Shared API-key normalization now accepts root-specific selector names and preserves them in public responses alongside legacy aliases.
- The remaining config-model gap is no longer the public request contract itself; it is the continued spread of legacy selector naming inside runtime and persistence code.

Concrete evidence:

- `server/src/digdir/api/routes.clj` now requires `:runtime-config-key` in `resolve-runtime-config-handler` and `:dataset-config-key` in `resolve-dataset-config-handler`.
- `server/src/digdir/config/accessor.clj` still uses `:tenant-config-key` throughout the accessor contract, while runtime-facing adapters now translate to `:runtime-config-key`.
- `server/src/digdir/config/db.clj` still declares runtime bindings with `:dataset` and dataset bindings with `:pipeline`.

### Gap assessment

The mechanics are largely right. Public contract cleanup is now underway; the remaining work is mostly internal selector vocabulary cleanup and removal of legacy compatibility aliases, not a redesign of the resolver itself.

## 3. Dataset Identity and Dataset Refs

### Status

Partially aligned.

### Target

The target architecture defines the canonical dataset ref as:

```clojure
{:tenant "digdir"
 :dataset-config-key "default"}
```

Pipeline identity is part of materialization, not the public runtime dataset identity.

### Current implementation

The live public/runtime contract now treats dataset refs as:

```clojure
{:tenant ...
 :dataset-config-key ...}
```

That shape is now used in:

- API request parsing for runtime routes
- runtime-facing API responses
- API key grants
- agent policy
- Playground conversation persistence

Pipeline/materialization identity still exists, but it is now mostly behind dataset lookup and public dataset/pipeline materialization endpoints rather than in the main runtime contract.

Relevant implementation:

- `server/src/digdir/api/routes.clj`
- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/data/db.cljc`
- `server/src/digdir/agents/core.clj`
- `server/src/digdir/agents/db.clj`
- `server/src/digdir/skills/context.clj`
- `server/src/digdir/skills/builtin/retrieval.clj`
- `server/src/digdir/migration/system.clj`

### Main gaps

- `config-db/get-dataset-by-ref` still resolves through materialization pipelines internally, even though it now returns canonical dataset fields and nests materialization details under `:materialization`.
- Public dataset/pipeline materialization endpoints legitimately expose pipelines, but they still rely on legacy pipeline-id formatting for execution status and operator context.
- Migration and setup code still encode dataset trees in terms of tenant-config-key plus pipeline-id.
- The route boundary now resolves the dataset-selection / dataset-config-selection overlap by reserving top-level `dataset-config-key` for dataset-root config selection, while explicit dataset selection uses nested `dataset-ref` or full legacy tuple aliases.

### Gap assessment

This was the largest mismatch, and it is now substantially narrowed. The public/runtime dataset contract is canonical. The remaining work is to finish pushing that model down into runtime support code so pipeline/materialization details stay fully behind the dataset boundary.

## 4. API Surface

### Status

Partially aligned.

### What is already aligned

- `/api/rag` is agent-scoped.
- `/api/retrieve` is explicitly dataset-scoped.
- API key ceilings are enforced for explicit config-node requests.
- Conversation APIs are keyed by agent ownership, not pipeline ownership.

### Main gaps

- Runtime-facing dataset payloads are now canonical, but some config-related responses and internal helpers still carry legacy selector aliases.
- Public dataset/pipeline materialization endpoints still expose pipeline identity, which is correct for that surface, but the naming and helper structure are not yet clearly partitioned from runtime concepts.
- Operator and public dataset materialization views still rely on legacy external pipeline IDs when surfacing execution status.

Relevant implementation:

- `server/src/digdir/api/routes.clj`

### Gap assessment

The route structure is now largely aligned for runtime use. The remaining route-level work is mostly cleanup of execution-status identity and continued separation between runtime dataset routes and materialization/operator routes.

## 5. Conversations and Persistence

### Status

Partially aligned.

### What is already aligned

- `:conversation/agent-id` exists.
- API conversation flows enforce agent ownership.
- External API conversation creation is agent-scoped.
- Playground UI state and conversation persistence now store only `:conversation/tenant` and `:conversation/dataset-config-key` for dataset scope.

Relevant implementation:

- `server/src/digdir/data/db.cljc`
- `server/src/digdir/api/routes.clj`

### Main gaps

- Conversation records do not yet clearly represent the full target-state set of runtime facts:
  - available dataset refs
  - actually used dataset refs
  - executed skill graph identity
  - evidence-gathering rationale per turn

Concrete evidence:

- `server/src/digdir/data/db.cljc` now persists playground conversation scope as `:conversation/tenant` plus `:conversation/dataset-config-key`.
- The active conversation record no longer persists `pipeline` or `tenant-config-key`, but conversation records still do not capture the fuller target-state runtime facts listed above.

### Gap assessment

The primary public conversation model is agent-scoped, and its persisted dataset scope is now aligned with the target architecture. The remaining gap here is richness of runtime fact capture, not legacy identity shape.

## 6. Runtime Execution Model

### Status

Partially aligned.

### What is already aligned

- Skill graphs are the main execution model for public API flows.
- Skills receive structured execution context.
- Agentic execution has been decomposed into `core`, `loop`, `tools`, and `workspace`.

Relevant implementation:

- `server/src/digdir/skills/api.clj`
- `server/src/digdir/skills/graph/runner.clj`
- `server/src/digdir/skills/builtin/agent/core.clj`
- `server/src/digdir/skills/builtin/agent/loop.clj`
- `server/src/digdir/skills/builtin/agent/tools.clj`
- `server/src/digdir/skills/builtin/agent/workspace.clj`

### Main gaps

- `digdir.rag.core` is still a large parallel execution surface.
- Skill context resolution still relies on dataset-to-pipeline resolution internally instead of stopping at canonical dataset-node resolution.
- The runtime vocabulary is not fully canonicalized; there are still translation points between route params, dataset refs, config resolution, and downstream skill/service inputs.

### Updated assessment

- `server/src/digdir/skills/context.clj` now accepts and propagates `:dataset-config-key` as the primary dataset selector, and service resolution now consumes that selector directly.
- `server/src/digdir/config/db.clj` now exposes dataset-ref-oriented lookup helpers, and `server/src/digdir/skills/context.clj` uses them as the primary runtime entry point for dataset lookup.
- `server/src/digdir/api/routes.clj` now keeps explicit runtime dataset selection under nested `dataset-ref`, which removes the old collision with top-level dataset-root config selection.
- `server/src/digdir/skills/templates/core.clj` and `server/src/digdir/skills/api.clj` now treat `runtime-config-key` as the explicit graph/template execution selector instead of using the generic `tenant-config-key` name.
- This confirms that the remaining work is in the runtime support layers rather than in the public route contract.

Concrete evidence:

- `server/src/digdir/rag/core.cljc` remains large and active.
- `server/src/digdir/skills/context.clj` still depends on dataset lookup that resolves via internal materialization records, even though the runtime-facing selector is now canonical.
- `server/src/digdir/config/db.clj` now keeps pipeline/materialization details nested under `:materialization`, but runtime lookup still depends on picking a materialization record under the hood.
- `server/src/digdir/api/routes.clj` now uses a dataset-first resolver, but some local variable names and downstream config payloads still reflect tenant-config-key-era internals.

### Gap assessment

The main runtime path is already graph-based, but the supporting context model has not been simplified to match the target architecture.

## 7. Observability and Traceability

### Status

Mostly aligned, with one important persistence gap.

### What is already aligned

- Agent workspace captures turn-by-turn iteration history.
- Reasoning text and tool call results are recorded.
- Sufficiency decisions are recorded.
- Search/read histories and backend issues are captured.
- Playground diagnostics and observability UI render turn-based traces.

Relevant implementation:

- `server/src/digdir/skills/builtin/agent/workspace.clj`
- `server/src/digdir/skills/builtin/agent/loop.clj`
- `server/src/digdir/playground/diagnostics.cljc`
- `server/src/digdir/playground/ui/observability.cljc`

### Main gaps

- The target architecture now prefers full reasoning persistence plus summary-focused rendering, but the persisted/playground data model still looks optimized around compact diagnostics rather than an explicitly durable detailed trace contract.
- Trace naming and dataset identity inside observability still inherit the old dataset-ref shape.

### Gap assessment

This area is operationally strong. The remaining work is less about feature breadth and more about making the persistence contract explicit and aligned with the new naming model.

## 8. Dataset and Pipeline Boundary

### Status

Partially aligned.

### What is already aligned

- Pipeline code is clearly separated into ingestion/materialization namespaces.
- Operator-facing dataset/pipeline APIs exist as distinct materialization surfaces.
- Materialization configuration is already treated differently from runtime execution.

Relevant implementation:

- `server/src/digdir/pipeline/*`
- `server/src/digdir/docs/pipeline/*`
- `server/src/digdir/api/routes.clj`

### Main gaps

- The runtime layer still reaches for materialization details too early in support code.
- Dataset materialization records and runtime dataset refs are cleaner than before, but they are not yet fully separated in naming or lookup flow.
- Setup/bootstrap and migration code still encode dataset trees in terms of tenant-config-key plus pipeline-id.

Relevant implementation:

- `server/src/digdir/setup.clj`
- `server/src/digdir/migration/system.clj`

### Gap assessment

The architectural separation exists in the namespace layout, but not yet in the canonical runtime identity model.

## 9. Testing and Migration Surface

### Status

Partially aligned.

### What is already aligned

- There is test coverage around explicit config resolution, API key ceilings, agent selection, and dataset authorization.
- Migration tooling exists for legacy composite pipeline identity parsing and dataset-ref normalization.

### Main gaps

- Tests in the actively used runtime surfaces now cover the new canonical selector vocabulary, but broader repo areas still encode old dataset-ref and config-key terminology.
- Migration code still normalizes dataset refs into the old `{tenant-config-key, pipeline}` shape.
- The repository still needs this gap analysis to be turned into a concrete delivery checklist and tracked workstream.

Relevant implementation:

- `server/test/digdir/api/routes_test.clj`
- `server/src/digdir/migration/system.clj`

## 10. Recommended Sequencing

The next cleanup sequence should be:

1. Remove legacy dataset-ref mirror fields from live runtime paths.
  Conversation persistence, route parsing, public API execution, graph/template execution, agent policy, API-key grants, and Playground UI state are already on the canonical `{tenant, dataset-config-key}` plus `runtime-config-key` shape. The next step is to keep `pipeline` fully internal to materialization lookup and to remove the remaining legacy dataset-ref compatibility handling from support and migration layers.

2. Rename request-facing config selectors by root.
   Continue replacing generic `config-key` and broad `tenant-config-key` internals with `platform-config-key`, `runtime-config-key`, and `dataset-config-key`. Public route boundaries and graph/template execution have started this cutover; the remaining work is internal.

3. Remove remaining legacy runtime identity shells.
   Retire the remaining runtime-support `tenant-config-key` and pipeline compatibility fields outside the now-clean public/runtime contract.

4. Simplify runtime context construction.
   Make dataset-node resolution the primary runtime input, with pipeline/materialization lookup behind that boundary instead of in front of it.

5. Tighten observability persistence.
   Make detailed trace persistence an explicit contract, with Focused and Detailed views as renderings over the same durable trace model.

## 11. Bottom Line

The implementation is no longer in an early-concept state. The main architecture is visible in the code.

What remains is not a new invention phase. It is a convergence phase:

- remove the old dataset identity model
- remove the generic config selector vocabulary
- remove the remaining legacy runtime identity shells
- keep the already-strong config and observability foundations

That should be treated as a targeted cleanup program, not another exploratory redesign.
