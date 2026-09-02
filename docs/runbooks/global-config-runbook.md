# Global Config Root — Operator Runbook

This runbook covers the operational surface of the inherit-with-overrides
config model: the `__global__` baseline, ownership modes (`:fork` vs
`:inherit`), and the day-to-day procedures (promote, demote, pin, unpin,
pin-all). See `plans/completed/global-config-root-plan.md` for the design
context and motivation.

## Quick reference

- **`__global__`** — sentinel tenant that owns the live system-wide baseline
  for every `:inherit`-owned definition. Edits here propagate to every
  tenant that hasn't pinned.
- **`:ownership` on a definition** — declared per `config-def`:
  - `:fork` (default) — tenant registration copies the seed value;
    tenant owns the value thereafter; system-developer edits to the seed
    do **not** propagate.
  - `:inherit` — no per-tenant copy on registration; reads fall through
    to `__global__`; system-developer edits **do** propagate.
- **Pin** — a tenant-level value whose content equals the current global,
  stamped with `:config.value/pin-of-version`. Insulates that one path
  from future global edits.
- **Promote** — flip a definition from `:fork` to `:inherit`, populate
  `__global__` with a chosen value, optionally pin every tenant whose
  current value differs.
- **Demote** — flip a definition back to `:fork`, push the current global
  into every inheriting tenant, drop the global value.

## Where things live in the admin UI

| What you want to do | Where |
|---|---|
| Browse all inherit-owned globals + their current values | Config tab → **Global Defaults** |
| Edit a global value (with changelog) | Global Defaults → **Edit** on a row |
| Demote an inherit-owned definition back to `:fork` | Global Defaults → **Demote** on a row (confirms before acting) |
| Promote a fork-owned definition to `:inherit` | Global Defaults → **Suggested for promotion** section → **Promote…** (opens wizard) |
| Browse a tenant's effective values, side-by-side with global | Config tab → **Inheritance** → drill into a (tenant, root) cell |
| Pin a single tenant value to the current global | Inheritance editor → cell modal → **Pin to current global** |
| Revert a pinned value back to inherited | Inheritance editor → cell modal → **Revert to inherited** |
| Pin **every** inherit-owned global into a tenant's tree | Inheritance editor → tenant column header → **📌 Pin all globals** |
| See who edited what, and when | Config tab → **Audit** |
| Check current global version + recent changelog | Global Defaults → top banner |

## Mental model

### Resolution walks the tenant chain, then falls back to global

For an `:inherit`-owned definition, a read for tenant `ka`:

1. Walk `ka`'s node chain at the relevant root (platform/runtime/dataset).
2. If no value found, walk the `__global__` chain at the same root.
3. Decrypt if the definition is `:encrypted?`.
4. Return decoded value, or the caller's `:default` if neither chain has one.

Trace surfaces include the path each lookup took (visible in the inheritance
editor and playground diagnostics).

### `:fork` keeps the existing semantics

A `:fork`-owned definition behaves exactly like the pre-2026 model: tenant
registration seeds from `__platform-defaults__` (or `__global__` for paths
promoted later), and the value lives on the tenant tree thereafter.
Operator edits to the seed don't propagate.

Most service-credentials-like paths stay `:fork` so a tenant's BYOK setup
isn't accidentally clobbered by a system-wide edit. Tuning knobs (e.g.,
`skills.retrieval.top-k`, `skills.synthesis.model`, `services.colbert.api-url`)
live as `:inherit` so that operators can move all tenants forward at once.

### Pin-of-version is the audit trail for "I opted out"

When a tenant pins a value, the entity carries `:config.value/pin-of-version
N`. The Global Defaults version banner shows the current global version;
when a tenant's pin is older than the current global, the inheritance
editor flags it so operators can review the delta and decide whether to
unpin (re-join the inheritance flow) or update the pin (keep frozen but
acknowledge the divergence).

## Procedures

### Edit a global value

1. Open the **Global Defaults** tab.
2. Find the row for the path you want to change. If the row says
   `— (no global value yet)`, the column shows **Set**; otherwise **Edit**.
3. Click. Enter the new value and a non-empty **changelog** describing
   the why.
4. **Save global**. The global version bumps; every tenant that hasn't
   pinned that path will read the new value on next resolve.

### Promote a fork-owned definition to `:inherit`

1. **Global Defaults → Suggested for promotion** lists fork-owned paths
   where ≥80% of tenants share the same value (low-risk candidates).
   Operators can also promote arbitrary paths by editing the wizard
   directly — the suggestion list is guidance, not a gate.
2. Click **Promote…** on a candidate. The wizard shows:
   - The candidate value (the modal majority).
   - A breakdown of how many tenants currently match vs. diverge.
   - A choice for **non-matching tenants**:
     - `:pin-all` — copy each diverging tenant's current value into their
       own tree as a pin (preserves their effective value, insulates
       them from future global edits).
     - `:pin-non-matching` — pin only the diverging tenants; matching
       tenants flow through the new global.
3. Enter a changelog describing the rationale (e.g., "consolidating to
   v3 default — X tenants will pin their override").
4. **Promote**. The definition flips to `:inherit`; the global value is
   set; non-matching tenants are pinned per the chosen strategy.

### Demote an inherit-owned definition back to `:fork`

1. Confirm you actually want this. Demotion is the inverse of promote
   and is operationally heavier than editing a global value.
2. **Global Defaults → Demote** on the row. The browser confirm dialog
   describes what will happen.
3. The op:
   - Pushes the current global value into every tenant that was
     **inheriting** (no direct value) — so their effective value is
     preserved.
   - Retracts `:config.value/pin-of-version` from tenants that were
     pinned (the stamp is meaningless once the value is fork-owned).
   - Deletes the global value.
   - Flips ownership back to `:fork`.
4. Going forward, system-developer edits at this path will not
   propagate. Per-tenant edits are needed.

### Pin a tenant to the current global (single path)

Use this when one tenant wants to opt out of a specific global change.

1. **Inheritance** tab → drill into the tenant's column at the
   appropriate root.
2. Click the cell for the path. The modal opens.
3. **Pin to current global**. The tenant gets a direct value matching
   the current global, with `:config.value/pin-of-version` stamped.

### Pin **all** globals into a tenant's tree

Use this for a brand-new tenant that should start with a stable snapshot
("fork-like posture from day one").

1. **Inheritance** tab → look at the tenant's column at the relevant
   root (platform / runtime / dataset).
2. Click **📌 Pin all globals** in that column header. (Hidden for the
   `__global__` tenant.)
3. Every inherit-owned definition that has a current global value is
   pinned into the tenant's `default` node, stamped with the current
   version. Future global edits won't propagate to this tenant for
   those paths until someone unpins.

### Unpin a tenant value (re-join inheritance)

1. Inheritance editor → cell modal for the pinned path.
2. **Revert to inherited**. The tenant's direct value is removed; on
   next resolve, the global value applies.

## Reading state

### Current global version

The **Global Defaults** tab shows a banner with the current
`:config.global/version` and the most recent changelog entry. Each global
edit increments the version; the banner is the "what's deployed" pointer.

### Audit log

**Audit** tab. Filter by tenant, tenant-config-key, user, or path. The
dropdown options are populated from the audit data itself (no
hardcoded universes), so they reflect what's actually been changed.

Audit entries cover:

- Tenant value edits (`:set-node-value`, `:delete-node-value`).
- Global edits (`:set-global-value`).
- Promotion (`:promote-to-global`) and demotion (`:demote-from-global`).
- Pin (`:pin-tenant-value`) and unpin (`:unpin-tenant-value`).
- Bulk pin (`:pin-all-globals-for-tenant`).

### Resolution traces

Wherever a value is shown in the admin UI, the trace surface explains
*why* a particular value was selected. Look for:

- The cell-level provenance pill (FORK / INHERIT / PINNED / GLOBAL).
- The traversal path in the inheritance editor (which nodes were checked,
  in order, and which produced the value).
- Playground diagnostics (`:traces` field in the runtime/dataset
  resolution payload — visible in the playground's Inspect view).

## Troubleshooting

### "Operator-set value isn't taking effect"

Most common causes:

1. The path is `:inherit`, the operator edited the global, but a tenant
   has an explicit value (pin or fork). Check the Inheritance editor's
   cell — if it shows OVERRIDE, the tenant is shadowing the global.
   Resolution: ask the tenant whether they want to unpin, or accept the
   override.
2. The path is `:fork` (not `:inherit`). Operator edits to the seed
   never propagate. Confirm via the Global Defaults tab — if the path
   isn't listed there, it's `:fork`. Promote it if you want operator
   edits to propagate.
3. The runtime config knob isn't wired into the per-skill builder
   (`api/util.clj:build-rag-skill-params` /
   `build-retrieval-skill-params`). The `skill-property-to-path-parity`
   test in `routes_test.clj` will catch this if the wiring is missing,
   but if a path was added without updating that table, it can slip
   through. Manually verify the path appears in the routing table.

### "Demote button is missing on a row"

The Demote button only renders when a row has a current global value
(otherwise there's nothing to demote from). If the row says `— (no
global value yet)`, set a value first, then demote.

### "Pin-all-globals button is missing for a tenant"

By design, the button is hidden for the `__global__` tenant column —
pinning the global tree to itself is meaningless. Also hidden for
`:column-kind :global` virtual columns. If you don't see it on a real
tenant column, it likely means the inheritance editor view is in the
multi-tenant matrix mode that doesn't show per-cell actions; drill into
the focused single-cell view first.

### "Login fails with cfg/get tenant=nil error"

Pre-2026, `cfg/get` silently normalized a nil tenant to
`__platform-defaults__`. That fallback is retired. Every call site must
pass an explicit tenant. For platform-scoped reads (e.g., the operator
login flow before a tenant is selected), pass
`digdir.config.core/global-tenant` (`"__global__"`) explicitly.

## CLI helpers

```bash
# Read a config value (resolves through tenant + global fallback for inherit-owned paths)
bb config-get <path> <tenant> <root> <tenant-config-key>
# Example: bb config-get skills.retrieval.top-k digdir runtime default

# Set a config value at a specific node — NOTE the value is SECOND, not last
bb config-set <path> <edn-value> <tenant> <config-root> <config-key>
# Example: bb config-set skills.retrieval.top-k 100 digdir runtime default
# Note: for :inherit-owned paths, use the admin UI (Global Defaults editor)
# to ensure the changelog is recorded and the global version bumps.
```

## Related

- `plans/completed/global-config-root-plan.md` — design rationale.
- `plans/completed/cozy-herding-spring.md` — the rerank-mode-split work
  that surfaced as a divergence between RAG and retrieval-only flows.
- `plans/completed/cfg-get-explicit-tenant-plan.md` — the precursor to
  retiring the nil-tenant normalization.
