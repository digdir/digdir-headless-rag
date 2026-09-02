# Global Config Root — Inherit-with-Overrides Plan

## Context

The current config model has three roots (`:platform`, `:runtime`, `:dataset`) and every node must carry a `:config.node/tenant` string. There is no tenantless layer. The setup-time sentinel tenant `__platform-defaults__` (`server/src/digdir/config/core.clj:76-78`) acts only as a **seed template**: values there are copied into each new tenant's own platform tree at registration time (`server/src/digdir/setup/workflow.clj:bootstrap-tenant-platform-tree!`). After the copy, the tenant's tree is an independent fork. System-developer improvements never reach existing tenants without per-tenant hand-patching.

This plan introduces a `:global` layer with **inherit-with-overrides** semantics: system developers maintain a live baseline, tenant trees contain only deliberate deviations, and system-level changes propagate to all tenants that haven't explicitly pinned. Tenants can pin a value — copy the current global into their own tree — to insulate themselves from future upstream changes for that one path.

The two patterns coexist: individual config definitions opt into either **fork** (current behavior, tenant owns the value after registration) or **inherit** (global is live baseline, tenant values are overrides). Per-definition ownership via a new `:config-def/ownership` attribute.

Related prior work:
- `plans/completed/cfg-get-explicit-tenant-plan.md` — made `cfg/get` tenant-explicit.
- Recent env-var migration (this branch) — moved 8 pre-auth/server-scoped settings (`services.auth.*`, `services.rate-limiting.trust-x-forwarded-for`) out of the DB to env vars. Those are process-scoped and stay in env vars; `:global` is for definitions that *could* be tenant-overridden but usually aren't.

## Core design

### Two ownership patterns, declared per definition

Add `:config-def/ownership` to the definition schema (`server/src/digdir/config/schema.clj`):

- `:fork` (default; current behavior) — tenant registration copies values from a seed source into the tenant tree. Tenant owns the value thereafter. System-developer edits to the seed do not propagate.
- `:inherit` — no seed copy at tenant registration. Reads resolve via tenant-chain → **global-chain fallback**. System-developer edits to `:global` propagate to all unpinned tenants on next read.

Ownership is a property of each *definition*, not of a whole tree. A tenant's platform tree can carry fork-owned values alongside inherit-owned overrides. The resolver routes each path by its definition's ownership.

### The `:global` layer

Two options for the schema (see Open Decisions below); the semantics are the same either way:

- System-developer-maintained baseline, one tree per existing root (`:platform-global`, `:runtime-global`, `:dataset-global`) — or a single `:global` scope that unifies them.
- Write operations on `:global` are gated behind a distinct permission (`:config/global-edit`) separate from per-tenant config edits.
- Version-tagged: every batch of global edits gets a version stamp (`:config.global/version`) so operators can see "tenants are reading global v42; a proposed v43 ships these deltas."

### Pinning

A "pin" is not a distinct entity. It's a tenant-level value whose content equals the current global. Structurally identical to an override; semantically an assertion of "I want this exact value, not whatever global becomes."

Optionally, a `:config.value/pin-of-version` attribute on the value records which global version the tenant pinned against. Useful for the UI to show "pinned against v40; global is now v42; remediation: review the diff." Without it, pins and overrides are indistinguishable.

### Resolution

Extend `server/src/digdir/config/db.clj:resolve-node-value-with-trace` (line 1418):

1. Walk the tenant's node chain as today.
2. On miss, look up the definition's `:config-def/ownership`.
3. If `:inherit`, continue walking into the global-chain for that root.
4. If `:fork`, stop (current behavior) — the value is absent.

Disabled nodes: a disabled tenant node blocks the value at that level but does **not** prevent fallback to global. (Disablement is "I don't want this node's contribution," not "I want no value.") Open decision below.

## Phases

### Phase 1 — Additive introduction (opt-in, no migration)

**Goal:** land `:global` as an additive layer that new definitions can opt into. Zero impact on existing definitions and tenants.

#### 1.1 Schema

- Add `:config-def/ownership` (default `:fork`) to `config-def-schema` in `server/src/digdir/config/schema.clj:12`.
- Add `:config.global/version` entity for global-version tracking.
- Decide between:
  - **Option A (recommended)**: reuse existing roots, introduce a sentinel tenant string `"__global__"` (distinct from `__platform-defaults__`). Nodes for this tenant are the global baseline for their root. Schema unchanged except for the ownership attribute.
  - **Option B**: make `:config.node/tenant` optional when a new `:config.node/global?` boolean is true. Cleaner semantically, larger schema change.

#### 1.2 Resolution

- `config-db/resolve-node-value-with-trace` — add global-fallback branch when ownership is `:inherit` (`server/src/digdir/config/db.clj:1418`).
- `config-db/resolve-node-values-batch` — same extension (line 1557).
- `config-db/tenant-root-node!` — when resolving a tenant that has no tree and the definition is `:inherit`-owned, fall through to global instead of throwing.
- Add `:global` segment to resolution traces so the UI can show which layer won.

#### 1.3 Write API

- `config-db/set-global-value!` — write a value at the `:global` tenant for a given root + path. Gated on `:config/global-edit` permission.
- `config-db/pin-tenant-value!` — convenience: copy the current effective global value into the tenant's tree with `:pin-of-version` stamped. Emits to the audit log.
- `config-db/unpin-tenant-value!` — retract the tenant-level value, reverting the tenant to live-global.

#### 1.4 Accessor

- `config/accessor.clj` — no API change; resolution changes are internal to the db layer.
- Extend `get-platform-value-with-trace` / `get-runtime-value-with-trace` trace output to surface `:inherited-from :global` on wins that came from global.
- `cfg/get` semantics unchanged: callers still pass `{:tenant "ka"}` (or whatever); resolution chooses fork vs inherit transparently based on the definition.

#### 1.5 Tests

- Add test fixtures seeding a `__global__` tenant with values.
- Unit tests: inherit definition resolves from global on tenant miss; fork definition does not; pinned value masks global; disabled tenant node still falls through to global (or not — depends on decision).
- Tests covering the interaction with node-parent chains: does tenant-parent walk before or after global fallback? Resolve first (decision: walk full tenant chain before any global fallback).

#### 1.6 Initial inherit-owned definitions

Seed a small set of new definitions that use `:ownership :inherit` from day one, chosen because they're genuinely system-owned:

- `services.scaleway-tem.region` / `services.scaleway-tem.from-email` — currently tenant-scoped but almost never overridden.
- Recommended skill defaults (`skills.synthesis.model`, `skills.retrieval.top-k`, etc.) — system ships best-known values; tenants override selectively.

These prove the model end-to-end without touching any existing fork-owned definitions.

### Phase 2 — UI support

**Goal:** operator console surfaces ownership, inheritance, and pinning clearly; supports editing globals and managing tenant overrides.

#### 2.1 Inheritance view extension

The existing `ConfigInheritanceEditor` in `server/src/digdir/config/ui/inheritance.cljc` already visualizes per-node resolution. Extend:

- **Ownership badge** per row: "fork" vs "inherit" (visual distinction small but consistent).
- **Global column** added to the right of the tenant columns for inherit-owned definitions. Shows the current global value.
- **Winner indicator** — existing "winning node" logic extended to recognize `:global` as a possible winner.
- **Provenance pill** on each displayed value: `"inherited from global v42"`, `"pinned against v40"`, `"overridden"`, `"tenant-owned (fork)"`.

Files:
- `server/src/digdir/config/ui/inheritance.cljc` — extend `InheritanceValueCell`, `comparison-columns`, `merge-definitions`.
- `server/src/digdir/config/ui.cljc` — add global-tab or integrate into existing root tabs.

#### 2.2 Global editor

New page/section: "Global defaults (system)."

- Permission-gated (`:config/global-edit`).
- Category-grouped view of inherit-owned definitions with their current global value.
- Edit flow mirrors the existing tenant edit modal; writes go via `set-global-value!`.
- Each edit prompts for a version bump and a changelog entry ("Why this change? Who is affected?").
- Diff preview: "This will change the effective value for N tenants (M have pinned; K will see the change)."

Files:
- New `server/src/digdir/config/ui/global.cljc` — `GlobalDefaultsEditor` component.
- Extend `server/src/digdir/config/ui.cljc` routing to include the global tab.

#### 2.3 Tenant-side controls

On each inherit-owned row in a tenant's view:

- **"Pin to current global"** button — writes current global into tenant tree, stamps `:pin-of-version`.
- **"Override"** button — opens edit modal, writes tenant-level value.
- **"Revert to inherited"** button (on pinned or overridden rows) — retracts the tenant value.
- **"Pin everything shown"** bulk action for risk-averse tenants who want fork-like behavior on a per-category basis.

#### 2.4 Version dashboard

Small widget showing: current global version, last-changed timestamp, list of tenants pinned to older versions with counts by version. Links to a "pending upgrades" page per tenant.

#### 2.5 Audit and change log

Existing audit machinery (`server/src/digdir/config/ui/audit.cljc`) extends to record:

- `:config.audit/kind :global-edit` with version stamp and changelog.
- `:config.audit/kind :pin` / `:unpin` with tenant ID.

No schema changes needed in the audit log beyond the new kind keywords.

### Phase 3 — Selective migration of existing definitions via the UI

**Goal:** operators promote individual existing fork-owned definitions to inherit-owned without a wholesale data migration. Safe, reversible, and visible.

#### 3.1 Promotion workflow (per definition)

In the global editor, each fork-owned definition has a **"Promote to global"** action. Selecting it runs this flow (UI-driven, reversible at any step):

1. **Inspection step** — the UI reads every existing tenant's value for this path and presents a clustered table:
   - Cluster by identical value; show count of tenants at each value.
   - Identify a **candidate global value**: typically the modal (most-common) value, or one operator-chosen.
   - Flag outliers (tenants whose value differs) so the operator knows who would be affected if we didn't preserve their override.

2. **Preservation step** — operator selects:
   - Candidate global value.
   - For each non-matching tenant: **pin the existing value** (preserve their override as-is) or **revert to the new global** (accept the change).
   - Default is **pin all non-matching tenants**, to guarantee no behavior change for any tenant on cutover.

3. **Preview step** — dry-run diff: "After promotion: N tenants inherit the new global; M tenants will be pinned to their current value; K tenants already had no value and will now resolve from global."

4. **Apply step** — atomic operation:
   - Flip `:config-def/ownership` to `:inherit` for this path.
   - Write the chosen global value to `__global__` tenant.
   - For tenants marked "pin," their existing value remains but is tagged `:config.value/pin-of-version`.
   - For tenants marked "revert," retract their existing value.
   - Bump global version.

5. **Rollback** — demoting back to `:fork` is symmetric: set ownership back, push the global value down into every tenant that currently inherits (so they keep the same effective value), retract the global.

Files:
- New `server/src/digdir/config/ui/ownership_migration.cljc` — `PromoteToGlobalWizard` component.
- Server-side: `server/src/digdir/config/ops/ownership.clj` — `promote-to-global!`, `demote-from-global!`, `inspect-definition-values` helpers.

#### 3.2 Candidate priority list

To guide operators, the UI surfaces a suggested priority list of definitions likely to benefit from promotion. Heuristic: definitions where ≥80% of tenants share the same value AND the value is one the system developers set at registration time. Lets operators clear the low-hanging fruit first.

#### 3.3 Bulk promotion is intentionally not offered

One definition at a time. Bulk promotion would be tempting but risks promoting a definition that *looks* uniform across tenants but is actually load-bearing for one tenant. The per-definition wizard forces operator review.

### Phase 4 — Tenant cutover posture and long tail

**Goal:** decide the default posture for the large set of existing fork-owned definitions we don't actively promote.

#### 4.1 No automatic cutover of existing definitions

Existing fork-owned definitions stay fork-owned unless an operator runs the promotion wizard on them. This preserves the current contract for every tenant.

#### 4.2 New-tenant bootstrap

`setup/workflow.clj:bootstrap-tenant-platform-tree!` changes:

- For fork-owned definitions: unchanged (seed values copied into tenant tree).
- For inherit-owned definitions: **skip the copy**. Tenant gets an empty node for these paths, inheriting from global on read.
- Net: new tenants have sparser trees by default, but behavior is identical to today for every existing definition.

#### 4.3 Pin-all affordance for new tenants

On tenant registration, offer an optional "pin everything currently inherited" checkbox for operators who want the fork-style contract on day one. Generates a snapshot of every inherit-owned global value into the tenant tree.

### Phase 5 — Documentation and operational runbooks

- `docs/architecture/global-config-model.md` — the two patterns, the resolution algorithm, the ownership attribute, the pin/override semantics.
- `docs/runbooks/promoting-definition-to-global.md` — step-by-step for operators.
- `docs/runbooks/global-change-process.md` — how to propose, version-bump, announce, and deploy a global edit. Required human approvals, notification expectations.

## Schema summary

Definitions (`server/src/digdir/config/schema.clj`):
```
:config-def/ownership  ; :fork (default) | :inherit
```

Nodes (Option A — sentinel tenant):
```
:config.node/tenant = "__global__"  ; reserved tenant string
```

Values:
```
:config.value/pin-of-version  ; optional long, for tenant-level values that pin against a global version
```

Global versioning (new entity):
```
:config.global/version  ; long
:config.global/created-at
:config.global/created-by
:config.global/changelog  ; string, operator-authored
```

## Resolution algorithm (pseudocode)

```
(resolve db root tenant node-id path)
  let def = get-definition(path)
  let tenant-value = walk-tenant-chain(db, root, tenant, node-id, path)
  cond
    some? tenant-value       -> tenant-value  ; override or pin
    def.ownership = :fork    -> nil
    def.ownership = :inherit -> walk-global-chain(db, root, path)
```

## UI work summary

| Surface | Component | File |
| --- | --- | --- |
| Per-row ownership badge | extend `InheritanceValueCell` | `config/ui/inheritance.cljc` |
| Global value column | extend `comparison-columns` | `config/ui/inheritance.cljc` |
| Global defaults editor | new `GlobalDefaultsEditor` | `config/ui/global.cljc` (new) |
| Tenant pin/override controls | extend existing row actions | `config/ui/inheritance.cljc` |
| Version dashboard | new `GlobalVersionStatus` | `config/ui/global.cljc` (new) |
| Promotion wizard | new `PromoteToGlobalWizard` | `config/ui/ownership_migration.cljc` (new) |
| Audit entries for new kinds | extend existing `AuditLog` | `config/ui/audit.cljc` |

Server-side additions:
- `config/ops/ownership.clj` (new) — `promote-to-global!`, `demote-from-global!`, `inspect-definition-values`.
- `config/ops/global.clj` (new) — `set-global-value!`, `pin-tenant-value!`, `unpin-tenant-value!`, `current-global-version`.

## Open decisions

1. **Schema variant — Option A (sentinel tenant) or Option B (tenantless nodes)?** Recommended: A, smaller blast radius, reuses existing machinery. B is cleaner but requires touching every query that assumes `:config.node/tenant` is present.

2. **Disabled tenant node: does resolution fall through to global, or stop cold?** Recommended: fall through. Disablement means "this node should not contribute," not "block all inheritance." Document this explicitly.

3. **Pin granularity: per-value only, or also per-tenant bulk?** Recommended: offer both. Per-value is the primary mechanism; bulk pin is a convenience for tenants who want fork-style posture.

4. **Global version model: monotonic counter, semver, or commit-style hash?** Recommended: monotonic counter with changelog entries. Semver is overkill; hashes are opaque.

5. **Can tenant nodes' `:config.node/parent` reference a global node, or is the tenant→global hop only at the leaf?** Recommended: leaf only, for simplicity. Tenant chain is purely within one tenant; global fallback fires after tenant chain is exhausted.

6. **Promotion wizard — fully automated on apply, or require manual review per tenant for non-matching values?** Recommended: automated with a configurable threshold. If ≥N tenants would be affected differently, require manual per-tenant decisions.

7. **Should env-var-migrated settings ever move into `:global`?** Recommended: no. Process-scoped settings (session cookies, proxy trust) stay in env vars because they have no tenant-overridable interpretation. `:global` is for definitions where tenant override is *conceivable* even if rarely used.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A global edit silently changes a tenant's behavior in production | Version stamp + mandatory changelog + pre-flight diff preview + announce-before-deploy operational rule. Pin-all affordance for risk-averse tenants. |
| Promotion wizard mis-clusters tenant values and promotes a definition that's actually varied | Default is "pin all non-matching"; no tenant loses their current value on promotion. Demotion is symmetric and reversible. |
| Operator forgets to bump global version when editing | UI requires version bump on every global write; no silent edits. |
| Resolution performance degrades due to extra global-chain walks | Batch resolver (`resolve-node-values-batch`) already paginates; global-chain walk is a single extra per-path lookup when tenant chain misses. Benchmark on a tenant with 500+ definitions. |
| Existing tests assume every read throws on missing tenant tree | Grep pass for `tenant-root-node-error` in tests; migrate to allow the inherit pathway. |
| Data migration bugs during promotion | Every promotion is reversible via demotion. Dry-run preview is mandatory before apply. Per-tenant audit records every change. |

## Sequencing recommendation

Land Phase 1 behind a feature flag (`CONFIG_GLOBAL_ROOT_ENABLED`) and exercise it internally with a handful of new inherit-owned definitions before opening it up. Phase 2 UI work can run in parallel with Phase 1 server work since the DB contract is the dependency boundary. Phase 3 (promotion wizard) should not ship until Phase 2 UI is stable — operators need to see what they're doing. Phase 4 bootstrap changes only after a promoted definition has survived a production deploy cycle.

Total estimated effort: 4–6 weeks of focused work plus operational rollout time. Phase 1 is the majority; UI phases are additive but substantial because the inheritance view is already the most complex part of the operator console.
