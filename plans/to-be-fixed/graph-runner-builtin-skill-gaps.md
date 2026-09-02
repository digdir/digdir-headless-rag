# Graph-runner / built-in-skill integration gaps

## Why this exists

Building S7 (`plans/ideas/altinn-docs-skill-demo-plan.md`) surfaced a pattern: **the built-in skills were designed for one execution path — `:builtin/agent`'s internal ReAct loop, which invokes them directly — and not for the graph-runner path where they're composed as graph steps**. Production runs `:builtin/agent-rag` exclusively, so the bugs below haven't been observed in real use, but they will block any future custom skill graph that wires a built-in skill as a step.

Five distinct gaps are listed below. Each is individually a small fix; collectively they imply the graph-runner's contract with built-in skills hasn't been exercised end-to-end. Fixing them unblocks scenarios S2, S3, S4, S5, S6, S8 from the Altinn-docs demo plan (every one of those is a custom skill graph).

This file is intentionally not in `proposed/` because the gaps are concrete, ground-truth findings rather than a design proposal. Move to `in-progress/` once someone owns the work.

## Inventory

### Gap 1 — `:config-def/ownership` dropped on YAML import

**Status:** ✅ already fixed on the `demo-composable-skills` branch as part of S7. Listed here for record-keeping and so the same fix can be cherry-picked back to other long-lived branches.

**Symptom:** After `bb dump-import`, the pipeline UI showed "Missing config" for every inherit-owned key, even though `__global__`-tenant values existed in the dump.

**Root cause:** `digdir.config.ops.sync/definition-keyword-fields` lists the `:db.type/keyword` fields on `config-def` that the import must coerce from string back to keyword. `:config-def/ownership` was missing from the set, so YAML's `ownership: inherit` deserialized as the string `"inherit"`. Datahike's `:read` schema-flexibility accepted it; the resolver's `(= :inherit ownership)` check failed.

**Location:** `server/src/digdir/config/ops/sync.clj` lines ~300–307.

**Fix:** Add `:config-def/ownership` to the set. **Done** in this branch. Verify the same set is current on `main`/release branches; backport if missing.

**Verification:** After import, `(d/q '[:find ?own :where [_ :config-def/ownership ?own]] db)` must return keywords (`:inherit`/`:fork`), never strings.

---

### Gap 2 — ~~built-in templates reference a non-existent output key~~ (retracted)

**Status:** ❎ not a real bug — re-checked 2026-05-11 after Gap 1 + Gap 3 were fixed.

`server/src/digdir/skills/templates/builtin.clj` already uses `[:plan :queries]` everywhere (lines 27, 67, 107). The fact-checker template uses `:queries :$claim` directly (no plan step). The earlier diagnosis was a misread on my part — my own `:demo/outline-graph` draft had `[:plan :search-phrases]`, which surfaced as a real error in that graph only.

The two `agent/tools.clj` references at lines ~363 and ~935 are defensive `(or (get-in result [:outputs :queries]) (get-in result [:outputs :search-phrases]))` compatibility shims, reading `:queries` first. The `:search-phrases` fallback is dead code from a prior rename. Worth deleting eventually for cleanliness, but it's not a bug.

---

### Gap 3 — `:builtin/rerank` drops `:tenant` from skill-params

**Status:** ✅ fixed on this branch.

**Symptom:** Running `:builtin/rerank` as a graph step throws at `digdir.config.accessor/normalize-platform-opts` with *"cfg/get requires an explicit :tenant"*. Error fires inside `digdir.rag.rerank/rerank-chunks` trying to look up ColBERT credentials.

**Root cause:** `digdir.skills.builtin.rerank/execute-rerank` builds a `rerank-params` map for `rerank-chunks` but doesn't include `:tenant` from `skill-params`. The downstream `cfg/get` call therefore receives `{:tenant nil}` and the accessor refuses to normalize it.

**Location:** `server/src/digdir/skills/builtin/rerank.clj` lines ~122–141.

**Fix:** **Done** — destructured `skill-params` from the execute fn's ctx and added `:tenant (:tenant skill-params)` to `rerank-params`. Same one-line treatment likely applies to any other built-in skill whose execute fn delegates to a `digdir.rag.*` helper.

**Verification:** Run any graph that includes `:builtin/rerank` against a tenant with ColBERT configured. Should rerank without throwing.

---

### Gap 4 — `:required-services` runner pre-flight is dead-letter validation

**Status:** ✅ fixed on this branch (2026-05-11) via a use-site-resolved-services allowlist in `digdir.rag.skills.core/check-required-services`. `:demo/propose-outline` now declares `:required-services #{:azure-openai}` honestly.

**Symptom:** Any skill that declares `:required-services #{:azure-openai}` and is invoked through the graph runner fails the pre-flight check with `"Missing required services: (:azure-openai)"`. The error data shows `:provided (:typesense)`.

**Root cause:** The graph runner only injects `:typesense` into the `:services` ctx (see the diagnostics dump from S7). Every LLM-using built-in skill (synthesis, query-planner, fact-checking, entity-extraction) declares `:required-services #{:azure-openai}` *and* resolves Azure credentials via `cfg/get` rather than reading from the services map. So the declaration is a lie — the skill doesn't need the services map at all — and the runner's check rejects skills that would actually work.

The reason this hasn't broken production: `:builtin/agent-rag`'s ReAct loop invokes these skills via `digdir.skills.builtin.agent.tools/execute-sub-skill`, which bypasses the `check-required-services` pre-flight. Only the graph-runner path enforces it.

**Location:**
- `server/src/digdir/rag/skills/core.clj` `check-required-services` (~line 595) — the check itself.
- `server/src/digdir/skills/builtin/*.clj` — every skill declaring `:required-services #{:azure-openai}`.
- Wherever the graph runner builds the services map (the same place that currently injects `:typesense`).

**Fix taken (Option D — exemption allowlist):** Added a `use-site-resolved-services` set (`#{:azure-openai :colbert}`) in `digdir.rag.skills.core`. `check-required-services` now skips presence checks for services in that set, since they're resolved at use site via `cfg/get` rather than being pre-populated in the services map. Skills can still declare them in `:required-services` as honest documentation. The set is the explicit complement of what `digdir.skills.context/resolve-all-services` actually pre-resolves (`:typesense`).

Considered alternatives:
- **A.** Drop `:required-services` from skills entirely. Loses the metadata signal.
- **B.** Have the runner pre-populate `:azure-openai`/`:colbert` into the services map. Conflicts with the existing per-tenant-override design that intentionally defers resolution to use site.
- **C.** Refactor skills to read credentials from the services map. Biggest change; right direction long-term but a redesign, not a bug fix.

Option D was chosen because it's a 3-line change that preserves both the existing design intent (use-site resolution for tenant overrides) and the existing metadata declarations on built-in skills. The set should empty out if/when option C is undertaken later.

**Verification:** `:demo/propose-outline` now declares `:required-services #{:azure-openai}` and runs successfully through the graph runner as part of `:demo/outline-graph`.

---

### Gap 5 — agent `:dataset-config-key` form is the bare dataset id, not the path fragment

**Status:** ✅ infrastructure landed on this branch (2026-05-11); enforcement is opt-in per call site.

- `validate-agent` accepts an optional `:dataset-scope-checker` fn and rejects scopes that don't resolve.
- `digdir.agents.db/make-dataset-scope-checker` constructs a checker bound to a config-DB conn.
- `upsert-agent!` accepts the checker in its 3-arg `opts` and forwards it to `validate-agent`. The 1-arg form does **not** auto-construct one — strict validation would break test fixtures that intentionally use unresolved scopes (they're testing upsert mechanics, not scope resolution).
- The S7 demo's `seed-agents!` (`digdir.demo.altinn-authoring`) opts in by passing the checker, so wrong-form demo scopes are caught at boot before any user sees the dropdown drop.

**Symptom:** A demo agent seeded with `:allowed-dataset-scopes [{:tenant "digdir" :dataset-config-key "digdir/public-docs"}]` validates fine (`validate-agent` doesn't reach into the dataset registry) but is silently stripped from the playground's dataset dropdown. `config-db/get-dataset-by-ref` throws `:dataset-ref-not-resolved` on the wrong form; `dataset-scope-usable?` catches the exception and returns `false`.

**Root cause:** The string `"digdir/public-docs"` is a config-tree node-id fragment (e.g. `dataset/digdir/public-docs/default`). The actual `:dataset-config-key` for the agent scope is the bare dataset id (`"public-docs"`). The two forms look similar enough that anyone using the system-export JSON as documentation will guess wrong.

**Location:**
- `server/src/digdir/agents/core.clj` `validate-agent` — should call `config-db/get-dataset-by-ref` (or equivalent) on each allowed scope and raise a clear error rather than letting the playground silently drop it.
- `server/src/digdir/playground/ui/common.cljc` `dataset-scope-usable?` — the silent catch is appropriate at UI-render time but the validation should happen earlier.
- Documentation in `decisions/agents-skills-and-datasets.md` or similar should explicitly state the `:dataset-config-key` form.

**Fix:** `validate-agent` now accepts `:dataset-scope-checker` (optional). When supplied, scopes that don't resolve are collected and produce a clear error: *"Allowed dataset scopes do not resolve to a known dataset: [...]. Use the bare :dataset/id (e.g. \"public-docs\"), not a node-id path fragment (e.g. \"digdir/public-docs\")."*

`digdir.agents.db/make-dataset-scope-checker conn` returns a checker that consults `config-db/get-dataset-by-ref` (wrapped in a broad catch). Call sites that want strict validation pass it explicitly.

**Follow-up worth doing:** wire the strict checker into the admin/UI agent-upsert API path so production agent edits get the validation. The dump-import path and tests should continue to omit the checker (intentionally lax) so they don't depend on a fully-populated dataset registry.

**Verification:** Upsert an agent with `:dataset-config-key "digdir/public-docs"` via `digdir.demo.altinn-authoring/seed-agents!` — the call refuses with the error above pointing at the bad key.

## Status summary

| Gap | Status | Branch |
|---|---|---|
| 1. Ownership keyword coercion | ✅ fixed | `demo-composable-skills` |
| 2. Template `[:plan :search-phrases]` | ❎ retracted (not a real bug) | — |
| 3. Rerank skill drops `:tenant` | ✅ fixed | `demo-composable-skills` |
| 4. `:required-services` dead-letter check | ✅ fixed (allowlist) | `demo-composable-skills` |
| 5. Agent scope validation too lax | ✅ fixed (opt-in checker) | `demo-composable-skills` |

All four real gaps are now closed on this branch. Gap 1 should be confirmed on parallel long-lived branches (the fix is a 1-line set extension); the others are net-new on this branch.

## Out of scope here

- Migrating skills to actually consume the `:services` map for `:azure-openai`/`:colbert` (would empty out `use-site-resolved-services`). That's a redesign rather than a bug fix.
- Deleting the two `:search-phrases` compatibility-shim fallback reads in `agent/tools.clj` (lines ~363, ~935). Harmless dead code; can be cleaned up alongside any future rename of the query-planner output.
- Rewriting the dump format to round-trip keyword-typed fields without a hard-coded coercion list (the root cause behind Gap 1). The current `definition-keyword-fields` set is sufficient for now.
