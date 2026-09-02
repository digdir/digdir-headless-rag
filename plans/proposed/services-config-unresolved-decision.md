# After import, the runtime resolves no `services` config — the decision

> ## ✅ DECIDED (PI): `"default"`, not `"shared"`.
>
> *"This was indeed a latent mismatch that ends in a choice. My choice is
> default, not shared."*
>
> **The fix is at the write end** — option A below, plus the data half (E),
> which are complements rather than alternatives. `ops/topology.clj` now seeds
> the tenant root with `:single-node? true`, matching what the other three
> platform bootstraps have always done, and the shipped snapshot has been
> corrected to place its 44 platform values on `default`.
>
> ### If you already imported
>
> **A code change does not move values that are already in your database.** If
> you imported before this landed, your platform values are on
> `platform/<tenant>/shared` and the runtime cannot see them.
>
> **Re-import the corrected snapshot** — `bb migration-import
> config/system-import.normalized.20260821.json --on-conflict overwrite`. The
> values will be written to `default`, where the runtime reads. The old
> `shared` node remains, empty of anything the runtime wants; it is inert and
> can be left.
>
> **You do not have to guess whether you are affected.** The import now tells
> you: it logs `::runtime-cannot-resolve-service-config` at `:error` if the
> runtime cannot see its own config, and names the node the values are on.
> Silence from that check is the confirmation.
>
> ### What this does NOT fix
>
> **The five encrypted values still cannot be read** — `auth.jwt-secret`,
> `azure-openai.api-key`, `colbert.api-key`, `scaleway-tem.api-key`,
> `typesense.api-key-admin`. They are now *reachable*, and still sealed with a
> master key a fresh install does not have. That is **#279**, and the import
> reports it separately as `::service-config-present-but-undecryptable` so the
> two are never confused for one another.



For #275. **The mechanism is not what the issue records, and the correction
changed what the options were.** The analysis below is what the decision was
taken on; the decision itself is recorded above.

---

## Correction: the fallback exists, and it runs the other way

#275 states there is *"no parent link and the node schema has no parent
attribute at all, so nothing falls back — the fallback does not exist to be
misconfigured."* **All three parts are wrong, and the real mechanism is
sharper.**

| claim | measured |
|---|---|
| no parent attribute | `:config.node/parent` is in the schema — `config/schema.clj:113` |
| no parent link is set | `bootstrap-config-tree!` sets `:parent-id base-node-id` — `ops/bootstrap.clj:111` |
| nothing falls back | resolution walks it — `config/db.clj:788`, via `prefetch-ancestor-chain` |

`prefetch-ancestor-chain` is documented as walking *"requested node → tenant
ancestors → tenant tree root"* — **upward only, child → parent.**

And the entry point is not an accessor default either. `tenant-root-node!` is
literally:

```clojure
(or (get-config-node-by-tenant-config-key db tenant root "default") (throw …))
```

**`"default"` is the definition of the tenant root**, not a configurable default
someone could change.

So:

> `bootstrap-config-tree!` writes the values to the **leaf** and makes the leaf a
> **child** of the base. The runtime enters at the **base**. Inheritance flows
> child → parent. **Entering at the parent can never reach the child's values.**

Nothing is misconfigured. The tree is built downward and read upward.

---

## The four questions

### 1 · What is `"shared"` for? — nothing reads it

`"shared"` appears **once** in the entire repository as a config key:
`ops/topology.clj:269`, which *creates* it. There is no reader in `src`,
`src-dev`, `test` or `admin`.

**It is write-only in practice** — which, per the issue's own framing, is the
simpler problem.

A third name is in play and needed correcting: the platform leaf default in
`bootstrap-root-config` is **`"prod"`**, which topology overrode to `"shared"`.
**I first concluded `"prod"` was dead too, and that was wrong** — I checked
production callers and generalised to "used by nobody", which is a different
claim. `test-bootstrap-config-tree-supports-platform-root-through-generic-api`
asserts the node id `platform/ka/prod` and reads a base value up through that
leaf, so it is live in the generic API. It is kept and documented rather than
deleted; the test suite is a caller.

### 2 · What else assumes `"default"`? — everything, unanimously

**109 `:services` read sites across 26 namespaces. Zero pass a
`tenant-config-key` or `:node-id`.** Every one resolves the tenant root.

There is no half-and-half to break. The one site that *is* explicit —
`setup/common.clj:269` — passes `"default"`, the root, not the leaf.

**Why only platform is broken:** every root uses base `"default"` + a leaf, and
values go to the leaf. Runtime and dataset work because their callers thread
`runtime-config-key` / `dataset-config-key` explicitly through the API context.
**Platform is the only root with no key-threading convention.**

### 3 · Was the snapshot exported from a working system? — export is faithful

`export-full` pulls nodes and node-values **verbatim**; there is no relocation on
export or import. **Export and import agree about their own format** — so that
is *not* the finding.

The relocation happens earlier: `topology.clj:257` reads source values with key
`"default"` and writes them to the `"shared"` leaf. The snapshot faithfully
records a database already in this state, and the snapshot carries **both** nodes
with **all 22 platform values on `shared` and zero on `default`** — for both
tenants.

### 4 · What would a check look like that fails today? — built, and it fires

`digdir.config.verify` resolves the required service paths **the way the runtime
does** and reports what it cannot see *and where the values actually are*.

Run against the shipped snapshot imported into a scratch database:

```
tenant digdir                      unresolved: 5
  services.typesense.api-host        entry=platform/digdir/default  defined-on=["platform/digdir/shared"]
  services.typesense.api-tls         …
  services.typesense.api-key-admin   …
  services.azure-openai.model-name   …
  services.azure-openai.use-azure-openai-api …
tenant public-sector-knowledge     unresolved: 5   (same shape)
```

**`public-sector-knowledge` is affected too**, which #275 does not mention.

It is wired into `import-system`, logs at `:error`, and returns
`:unresolved-service-config` in the result — **alongside the import's own
"completed successfully"**. It logs rather than throws: throwing would pick an
answer to the open decision by making the import fail. Silence is wrong under
every option; failing is not.

---

## The options, and their blast radius

| | change | blast radius | what it cannot do |
|---|---|---|---|
| **A** | **Bootstrap writes values to the base** instead of the leaf | one call site (`topology.clj:269`) — but leaves the leaf node existing and empty, and does not explain what a leaf is *for* | nothing for databases already in the bad state; needs a data fix too |
| **B** | **Platform callers pass a key** | **109 sites, 26 namespaces** | not seriously proposable at that spread |
| **C** | **The platform accessor enters at a leaf** by default | one place (`normalize-platform-opts` / `resolve-platform-node!`) | changes what "tenant root" means for platform only, diverging the three roots; and the leaf name is ambiguous — `"shared"` in topology, `"prod"` in `bootstrap-root-config` |
| **D** | **Invert the link** so the root is a child of the leaf | one bootstrap line | semantically backwards: the root would inherit from a leaf, and every future leaf would have to be an ancestor |
| **E** | **Migrate the values** onto the base and leave the code alone | data-only | recurs on the next bootstrap, which is what put them there |

**Two observations that bear on the choice, offered rather than decided:**

- **A and E are complements, not alternatives.** A stops it recurring; E fixes
  the databases already in this state. Either alone leaves half the problem —
  the same rule-then-data shape as #238.
- **C is the only option that makes the leaf meaningful.** If leaves are supposed
  to be where tenant-specific values live, the runtime not reading them is the
  defect. If leaves are an artefact nobody wanted, A is simpler and C is a
  distraction. **That is the product question**, and it is genuinely open: the
  leaf mechanism is used deliberately by all three roots, but only two have
  callers that reach it.

---

## Scope note

The check in this PR is correct under **every** option, because *"the runtime
resolves no services config"* is wrong in all of them. Nothing here changes
resolution, bootstrap, or any stored value.

## Related

**#275** — the issue. **#238** — the same rule-then-data sequencing.
