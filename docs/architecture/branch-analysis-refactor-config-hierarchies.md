# Branch Analysis: `refactor-config-hierarchies`

> **HISTORICAL** — a point-in-time review of the (since-merged) `refactor-config-hierarchies` branch, superseded by `docs/system-overview.md`, which describes the current architecture directly. Kept as a design record; the recommendations below may or may not have been acted on since.

**Date:** 2026-04-04
**Scope:** 207 commits, ~121K lines added, 310 files changed
**Baseline:** merge-base with `digdir/main`

## What's Working Well

### 1. Agent model is canonical

Agents own conversations. `resolve-request-agent-policy!` gates all runtime paths. Skills are genuinely generic -- no dataset identity leakage into skill execution. This matches the target architecture.

### 2. Config roots are structurally sound

The three-root model (`:platform`, `:runtime`, `:dataset`) is enforced at the schema level. Root-specific selectors (`platform-config-key`, `runtime-config-key`, `dataset-config-key`) exist and propagate through the accessor layer. Resolution tracing is good -- `get-*-value-with-trace` returns trace data with stop-reason.

### 3. Pipeline/runtime separation is clean

Pipeline namespaces handle ingestion and materialization. Skill execution doesn't import pipeline code. Dataset-refs use the canonical `{:tenant :dataset-config-key}` shape.

### 4. Observability foundation is solid

Turn-based iteration history, search/read distinction, drill-down UI with collapsible iteration cards -- the bones are right.

---

## Findings

### A. The "default" fallback contradicts fail-closed

The target architecture says: *"ambiguous selection does not silently choose a node"* and *"implicit fallback behavior should be treated as transitional, not canonical."*

But `routes.clj:182` still does:

```clojure
[tenant (or dataset-config-key tenant-config-key "default")]
```

And `routes.clj:393-395` auto-selects when exactly one dataset is granted:

```clojure
default-ref (when (and (not require-explicit?)
                       (= 1 (count effective-granted-refs)))
              (first effective-granted-refs))
```

The `require-explicit?` flag defaults to `false`. This means the *default* behavior of the system is implicit selection -- the opposite of the stated goal. Every new endpoint that calls `select-request-dataset-ref!` without `{:require-explicit? true}` inherits the implicit path.

**Recommendation:** Flip the default. Make `require-explicit?` default to `true`. Opt individual endpoints *out* where you have a documented reason (e.g., backwards-compatible public API surface).

### B. Legacy parameter aliases are accumulating, not converging

`normalize-dataset-ref` and `request-dataset-ref` accept six key variants each (`:dataset-config-key`, `:dataset_config_key`, `:tenant-config-key`, `:tenant_config_key`, `:config-key`, `:config_key`). `request-explicit-dataset-ref` also accepts `:pipeline`, `:pipeline-id`, `:pipeline_id`.

These aliases aren't deprecated or logged -- they're silently accepted. There's no path toward removing them because nothing signals to callers that they should stop using them. The target architecture says *"avoid adapters, expose our core naming conventions in APIs"*.

**Recommendation:** Add deprecation logging when legacy aliases are used. Set a horizon date in a plan for removing them. Document the canonical parameter names in the OpenAPI spec and mark alternatives as deprecated.

### C. File size concentration is a risk

Five files exceed 1900 lines:

| File | Lines |
|------|-------|
| `config/ui.cljc` | 3651 |
| `api/routes.clj` | 3206 |
| `config/ops.clj` | 2143 |
| `playground/ui.cljc` | 1934 |
| `migration/system.clj` | 1928 |

`routes.clj` at 3206 lines is doing routing, parameter normalization, authorization, dataset resolution, agent resolution, config ceiling checks, and endpoint handlers all in one namespace. This makes it hard to reason about the authorization boundary independently from the business logic.

**Recommendation:** Extract the parameter normalization and authorization logic into a dedicated `digdir.api.authorization` or `digdir.api.params` namespace. The route handlers themselves can remain in `routes.clj` but call into these. Same approach for `config/ops.clj` -- consider splitting admin-facing config operations from runtime resolution.

### D. Observability traces lack iteration indexing

Search history, read history, and sufficiency decisions are flat lists -- they don't carry an `:iteration` field linking back to the turn that produced them. The workspace does record per-turn tool-calls, but budget snapshots aren't captured at turn boundaries either. This means reconstructing "what happened on iteration 3" requires indirect correlation rather than direct lookup.

**Recommendation:** Add `:iteration` to `record-search!`, `record-read!`, and `record-sufficiency-decision!` in `workspace.clj`. Add a `budget-snapshot` field to the turn record in `record-turn!`. This is low-effort and high-value for debugging.

### E. `pipeline-id` references are still widespread

351 occurrences of `pipeline-id` across 16 source files. The target architecture says pipeline identity is *not part of the public dataset-ref shape* -- pipelines are the materialization layer behind datasets. But `pipeline-id` remains a first-class concept in config, ops, the accessor layer, audit, and the UI.

This isn't necessarily wrong -- pipelines need identifiers internally. But the distinction between "pipeline-id as internal materialization identity" and "pipeline-id as a runtime selection parameter" isn't crisp in the code. Some of these 351 references are genuinely internal; others leak into request-facing surfaces.

**Recommendation:** Audit the 50 occurrences in `routes.clj` specifically. Any `pipeline-id` that appears in request parameter parsing or response bodies should be evaluated for whether it belongs there per the target architecture.

### F. 8 partially-completed plans create ambiguity

There are 8 plans in `plans/partially-completed/` and 1 in `plans/in-progress/`. Some of these (like `remove-classic-playground-path-checklist.md`) appear to describe work that's already done on this branch. Others (like `solver-style-rag-redesign-plan.md`) describe aspirational work. The gap between "plan says partially done" and "code says done" creates confusion about what actually remains.

**Recommendation:** Sweep the partially-completed plans. For each one, either promote to `completed/` with a note about what was achieved, or extract the remaining actionable items into a fresh, focused plan.

---

## Recommended Next Direction (Priority Order)

1. **Make explicit selection the default** -- Flip `require-explicit?` to `true`, fix the tests that break, and document which endpoints intentionally allow implicit selection. This is the single highest-leverage change for aligning the runtime behavior with the architecture.

2. **Extract `routes.clj` authorization logic** -- The 3200-line monolith is the riskiest file for introducing subtle authorization bugs. Decompose it before adding more features on top.

3. **Add iteration indexing to traces** -- Quick win that unlocks better debugging and moves the observability story from "good" to "complete."

4. **Deprecation logging for legacy aliases** -- Make the convergence toward canonical naming visible and measurable.

5. **Plan cleanup** -- Sweep partially-completed plans so the planning system reflects reality.

---

## Summary

The branch has done the heavy structural work. What remains is tightening: removing the gap between what the architecture *says* and what the runtime *defaults to*.
