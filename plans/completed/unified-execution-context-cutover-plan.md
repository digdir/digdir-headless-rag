# Plan: Unified Execution Context Cutover

Tracks the implementation work for three linked improvements:

1. unify request and skill execution-context resolution
2. finish removal of legacy `pipeline-id` persistence compatibility once verified
3. migrate integration tests and diagnostics helpers to canonical `dataset-ref` and `agent-id` inputs

## Status

- [x] Phase 1: Introduce the unified resolver
- [x] Phase 2: Refactor request handlers onto the unified resolver
- [x] Phase 3: Align skill context builders with the same contract
- [x] Phase 4: Update unit and request tests around the unified resolver
- [x] Phase 5: Migrate diagnostics helpers and live integration tests
- [x] Phase 6: Verify legacy migration compatibility
- [x] Phase 7: Final legacy schema deletion

## Context

The codebase already has the core target model in place:

- agents are durable policy objects
- datasets are selected by canonical `{:tenant ... :dataset-config-key ...}` refs
- request handlers increasingly resolve runtime and dataset config through V2 node accessors

What remains is convergence work. The current execution path still assembles context in several layers:

- [server/src/digdir/api/routes/common.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes/common.clj)
- [server/src/digdir/api/routes/handlers.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes/handlers.clj)
- [server/src/digdir/skills/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj)
- [server/src/digdir/agents/policy.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/agents/policy.clj)

This creates duplication around:

- agent selection and policy loading
- dataset selection and authorization
- dataset config loading
- runtime config loading
- merged execution opts assembly
- trace capture and propagation

At the same time, legacy compatibility remains in:

- migration import/export paths
- API key legacy pipeline grant compatibility
- diagnostics tools and live integration tests that still take `tenant/config-key/pipeline-id`

## Goals

### Goal 1

Introduce one high-level execution-context resolver that returns the full resolved bundle needed by request handlers and skill execution entrypoints.

### Goal 2

Move integration and diagnostics callers to canonical `dataset-ref` and `agent-id` contracts, so pipeline-era runtime arguments are no longer the default test surface.

### Goal 3

Delete the last legacy `:conversation/pipeline-id` and `:api-key/pipelines` schema usage only after compatibility tooling has been verified against the updated tests.

## Non-Goals

- renaming all remaining `pipeline` namespaces
- removing internal materialization pipeline entities from config storage
- redesigning dataset materialization topology itself
- changing public dataset administration endpoints that still manage materialization pipelines

## Target Resolver Contract

Add one resolver, likely in a new namespace:

- [server/src/digdir/api/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/context.clj)

Alternative: extend [server/src/digdir/agents/policy.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/agents/policy.clj) if the team wants policy and context resolution colocated. Current structure suggests a dedicated namespace is cleaner because the work spans request parsing, config loading, and skill execution setup, not only policy.

### Proposed return shape

```clojure
{:agent-id "builtin/agent-rag-agent"
 :agent {...}
 :execution-policy {...}
 :dataset-ref {:tenant "ka" :dataset-config-key "prod"}
 :dataset-id "dataset/ka-assistant"
 :dataset-config {...}
 :runtime-config {...}
 :config {...}                  ;; merged dataset + runtime skill config
 :dataset-node {...}            ;; optional when available
 :runtime-node {...}            ;; optional when available
 :traces {:dataset {...}
          :runtime {...}}
 :allowed-dataset-refs [...]
 :skill-graph-id "builtin/agent-rag"}
```

### Required behavior

- enforce API key agent grants
- enforce API key dataset grants
- enforce agent dataset policy restrictions
- require canonical dataset selection for multi-dataset callers
- require explicit runtime config selection for request-time runtime execution
- preserve and return V2 trace data instead of dropping it
- expose a merged config map for handler convenience
- preserve canonical `dataset-ref` and `agent-id` into execution opts

## Implementation Phases

## Phase 1: Introduce the Unified Resolver (Completed)

### Files

- [server/src/digdir/api/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/context.clj) new
- [server/src/digdir/api/routes/common.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes/common.clj)
- [server/src/digdir/agents/policy.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/agents/policy.clj)
- [server/src/digdir/skills/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj)

### Tasks

- Extract reusable request-time resolution logic from `routes/common.clj` into one resolver entrypoint.
- Preserve current helpers where useful, but make them thin wrappers over the new resolver instead of separate resolution paths.
- Extend the resolver to return:
  - selected agent
  - execution policy
  - selected dataset ref
  - dataset config
  - runtime skill config
  - merged config
  - traces from dataset/runtime V2 accessors
- Decide whether dataset config loading should continue to come from `config-db/get-dataset-by-ref` or move to `cfg/get-dataset-pipeline-config-v2-with-trace` plus canonical materialization lookup. Prefer the trace-capable path if it does not regress current materialization resolution behavior.
- Add one public helper for downstream execution opts assembly, so handlers and `skills/context.clj` stop rebuilding the same shape by hand.

### Acceptance

- one namespace owns execution-context resolution
- request handlers can consume one resolved bundle
- V2 runtime and dataset traces are preserved in the returned structure

## Phase 2: Refactor Request Handlers onto the Unified Resolver (Completed)

### Files

- [server/src/digdir/api/routes/handlers.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes/handlers.clj)
- [server/src/digdir/api/routes/common.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)

### Tasks

- Update `api-rag-handler` to call the unified resolver once, then read:
  - `:agent-id`
  - `:dataset-ref`
  - `:config`
  - `:allowed-dataset-refs`
  - `:skill-graph-id`
- Remove duplicated sequencing where handlers separately call:
  - `resolve-request-agent-policy!`
  - `resolve-request-dataset-context!`
  - `resolve-request-config-node!`
- Keep retrieval-only handlers dataset-scoped, but move them onto the same context bundle shape with `:agent-id` omitted.
- Ensure the resolver supports both:
  - agent-scoped conversational requests
  - explicit dataset-only retrieval requests

### Acceptance

- handler code becomes simpler and no longer manually merges partial context pieces
- request-time context behavior is unchanged from the user perspective
- trace data is available for future diagnostics use

## Phase 3: Align Skill Context Builders with the Same Contract (Completed)

### Files

- [server/src/digdir/skills/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj)
- [server/src/digdir/skills/api.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/api.clj)
- [server/src/digdir/rag/skills/core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/rag/skills/core.clj)

### Tasks

- Keep `apply-dataset-context` focused on skill-input enrichment, not high-level request authorization.
- Reuse the new resolver helpers where skill execution entrypoints need canonical dataset and agent propagation.
- Make execution context creation preserve:
  - `:dataset-ref`
  - `:agent-id`
  - merged skill config inputs
  - optional trace metadata if useful for future diagnostics
- Review whether `build-execution-context` should accept a pre-resolved context bundle to avoid repeat dataset lookups.

### Acceptance

- skill execution APIs and request handlers use the same canonical context shape
- no new code path reintroduces pipeline-era request identity assumptions

## Phase 4: Update Unit and Request Tests Around the Unified Resolver (Completed)

### Files

- [server/test/digdir/api/routes_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/api/routes_test.clj)
- [server/test/digdir/skills/context_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/context_test.clj)
- [server/test/digdir/skills/api_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/api_test.clj)
- [server/test/digdir/agents/policy_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/agents/policy_test.clj)

### Tasks

- Add focused tests for the new resolver:
  - explicit dataset selection
  - multi-dataset API key rejection without explicit dataset ref
  - agent dataset policy narrowing
  - missing runtime-config-key rejection
  - merged config and trace propagation
- Refactor route tests to assert against the unified resolver contract instead of current intermediate helper sequencing.
- Update skill context tests to use canonical `dataset-ref` and `agent-id` only.

### Acceptance

- tests validate behavior, not legacy helper internals
- new resolver is directly covered

## Phase 5: Migrate Diagnostics Helpers and Live Integration Tests (Completed)

### Files

- [server/src-dev/digdir/tools/diagnostics.clj](/Users/bdbrodie/dev/digdir/rag/server/src-dev/digdir/tools/diagnostics.clj)
- [bb.edn](/Users/bdbrodie/dev/digdir/rag/bb.edn)
- [server/test/digdir/rag/agent_budget_benchmark_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/agent_budget_benchmark_integration_test.clj)
- [server/test/digdir/rag/rerank_language_parity_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/rerank_language_parity_integration_test.clj)
- [server/test/digdir/rag/rerank_isolation_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/rerank_isolation_integration_test.clj)
- [server/test/digdir/rag/rerank_evaluation_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/rerank_evaluation_test.clj)
- [server/test/digdir/rag/agent_query_batch_parity_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/agent_query_batch_parity_integration_test.clj)
- [server/test/digdir/rag/auto_filter_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/rag/auto_filter_integration_test.clj)

### Tasks

- Replace test-level `target-pipeline-id` inputs with canonical `dataset-ref` fixtures.
- Prefer `agent-id` where the test is validating agent behavior rather than raw retrieval.
- Introduce shared test helpers for:
  - `dataset-ref`
  - dataset config lookup
  - collection name resolution from canonical dataset context
- Update diagnostics entrypoints to accept canonical inputs first.
- If CLI compatibility must be preserved temporarily, support old args only as translation shims, with the canonical path driving execution internally.
- Update `bb` task descriptions so examples prefer `dataset-ref` and `agent-id`.

### Acceptance

- integration tests no longer model runtime selection as `tenant/config-key/pipeline-id`
- diagnostics internals resolve canonical dataset context first

## Phase 6: Verify Legacy Migration Compatibility (Completed)

### Files

- [server/src/digdir/migration/system.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/migration/system.clj)
- [server/test/digdir/migration/system_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/migration/system_test.clj)

### Tasks

- Keep migration import/export support for legacy fields long enough to verify roundtrips.
- Tighten tests to distinguish:
  - legacy import compatibility
  - current runtime schema
- Confirm that exported transformed data no longer treats `:conversation/pipeline-id` as a target-state field.
- Confirm whether `:conversation/pipeline` must remain in export/import payloads as compatibility-only data or can now be retired from transformed output as well.

### Acceptance

- migration tooling remains able to ingest old snapshots
- runtime code no longer depends on conversation pipeline fields

### Completion Notes

- `clojure -M:test -n digdir.migration.system-test` passed after the cutover work, confirming legacy snapshot ingest and transform compatibility remained intact.
- Legacy `:conversation/pipeline-id` handling remains isolated to migration compatibility code and tests; it is no longer part of the live runtime schema.

## Phase 7: Final Legacy Schema Deletion (Completed)

### Files

- [server/src/digdir/data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)
- [server/src/digdir/migration/system.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/migration/system.clj)
- any remaining tests or fixtures discovered during implementation

### Tasks

- Remove `:api-key/pipelines` from the live main DB schema once diagnostics/tests no longer require it.
- Remove any remaining runtime reads of legacy pipeline grant fields.
- If still present anywhere in live schema or migration scaffolding, remove `:conversation/pipeline-id` after migration verification completes.
- Re-scan the codebase for:
  - `:conversation/pipeline-id`
  - `:api-key/pipelines`
  - runtime request usage of `pipeline-id`

### Acceptance

- no live runtime schema depends on legacy pipeline-grant or conversation-pipeline-id attributes
- legacy support is isolated to explicit migration transform code only, or removed if no longer needed

### Completion Notes

- Removed `:api-key/pipelines`, `:api-key.dataset-ref/pipeline`, and `:api-key.dataset-ref/tenant-config-key` from the live schema in `server/src/digdir/data/db.cljc`.
- Updated live schema tests to assert only canonical API-key dataset-ref fields.
- `:conversation/pipeline-id` required no live-schema deletion because it had already been removed from the runtime schema before this phase; remaining references are migration-only compatibility paths.

## Verification Matrix

Run at minimum:

- targeted unit tests for the new context resolver
- [server/test/digdir/api/routes_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/api/routes_test.clj)
- [server/test/digdir/skills/context_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/context_test.clj)
- [server/test/digdir/skills/api_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/api_test.clj)
- [server/test/digdir/migration/system_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/migration/system_test.clj)
- relevant live integration tests after diagnostics migration
- one final repository-wide search for legacy request/runtime identifiers

Suggested final grep set:

```bash
rg -n ":conversation/pipeline-id|:api-key/pipelines|pipeline-id|tenant-config-key" server/src server/test
```

The final grep should still allow materialization-domain `pipeline-id` usage in dataset admin and config internals, but should not show pipeline-era request identity or conversation scope in the agentic runtime path.

## Risks and Watchpoints

- `config-db/get-dataset-by-ref` currently encapsulates materialization disambiguation. Replacing it carelessly could regress dataset resolution behavior.
- Retrieval-only flows intentionally bypass agent policy. The unified resolver must keep that distinction explicit.
- Integration tests currently depend on diagnostics helpers that are themselves pipeline-centric. Trying to delete legacy schema before moving those helpers will create churn.
- Migration tests still validate old payload shapes. Runtime cleanup and migration compatibility cleanup should be staged separately.

## Recommended Execution Order

1. Implement the unified resolver and route adoption.
2. Align skill context builders and direct execution entrypoints.
3. Refactor unit tests around the new contract.
4. Migrate diagnostics helpers and integration tests to canonical inputs.
5. Re-verify migration compatibility.
6. Delete final legacy schema compatibility fields.

## Done Definition

The work is complete when:

- request handlers resolve agent + dataset + config + traces through one high-level function
- skill execution entrypoints preserve canonical `dataset-ref` and `agent-id` without duplicate resolution logic
- integration tests and diagnostics prefer `dataset-ref` and `agent-id`
- runtime schema no longer depends on legacy `pipeline-id` conversation or API key grant fields
- migration compatibility, if still needed, is explicitly isolated and tested
