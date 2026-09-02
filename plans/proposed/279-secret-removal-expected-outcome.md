# #279 — expected outcome, written before the change

## What is actually in the file

Five secret PATHS, ten ciphertext VALUES — each secret is stored once per
tenant (`digdir`, `public-sector-knowledge`):

| path | per-tenant | in `runtime-required-service-paths`? |
|---|---|---|
| `services.auth.jwt-secret` | 2 | no |
| `services.azure-openai.api-key` | 2 | no |
| `services.colbert.api-key` | 2 | no |
| `services.scaleway-tem.api-key` | 2 | no |
| `services.typesense.api-key-admin` | 2 | **YES** |

`data.node-values` goes 125 -> 115. Definitions (all 121, including the nine
`encrypted? true` ones) and all 18 nodes are untouched: that is the "structure
and non-secret configuration" the scope says to keep.

## Delete the rows — do NOT blank them

Blanking (`raw: ""`) is strictly worse and it is worth saying why, because it
is the obvious-looking option. `config/db.clj:579` sends any `encrypted? true`
value to `crypto/decrypt`, which decodes Base64 and then computes
`(- (alength combined) salt-length iv-length)`. For `""` that length is
NEGATIVE, so it throws — and `undecryptable-service-config` reports anything
that throws. A blanked secret would therefore still be reported UNDECRYPTABLE,
with a reason that is now a lie. An absent row is reported by the mechanism
that is actually true of it.

## Expected check output

`verify.clj` lives on Lane B's branch, not on `release-v0.1-details`, so the
"0 unresolvable / 2 undecryptable per tenant" baseline is NOT reproducible from
my base. Two states have to be predicted separately.

### State A — my branch alone

My base has all 44 platform values on `platform/<tenant>/shared`, and
`ancestor-ids` walks child -> parent from the entry node
`platform/<tenant>/default`, whose `parent-id` is null. So `shared` is
UNREACHABLE on my base and nothing encrypted is scanned at all.

- before: 5 UNRESOLVABLE, 0 UNDECRYPTABLE per tenant
- after:  5 UNRESOLVABLE, 0 UNDECRYPTABLE per tenant

**My change is nearly invisible on my own base branch.** The single observable
difference is that `services.typesense.api-key-admin` goes from
`:defined-on ["platform/<tenant>/shared"]` to `:defined-on []`.

### State B — the shipping state (Lane B's placement fix + this removal)

Values on `default` = reachable.

- bare import, no env: **1 UNRESOLVABLE** per tenant
  (`services.typesense.api-key-admin`, `:defined-on []`), **0 UNDECRYPTABLE**
- after the documented recipe, whose step 3 seeds `api-key-admin` from env:
  **0 UNRESOLVABLE, 0 UNDECRYPTABLE**

The 0-undecryptable prediction is robust and does not depend on resolving the
open question below: whichever subset was throwing, all five ciphertexts are
gone, so `encrypted-paths-on` returns an empty set. Any encrypted value seeded
later from env is sealed with the LOCAL master key and opens normally.

## Open question I could not resolve before running

Lane B reports **2** undecryptable per tenant, but **5** encrypted values are
reachable on their branch. Step 3 seeding `api-key-admin` accounts for one.
The other two exclusions are unexplained. I am not manufacturing a number for
them; if the run disagrees with anything here, that is the finding.

## The design question, answered deliberately

**No, the five do not all resolve from the environment via `digdir.secrets`,
and it would be wrong to say they do.** `digdir.secrets/declared` contains
exactly one of them: `services.auth.jwt-secret` (`:jwt-secret` -> `JWT_SECRET`,
tier `:bootstrap`). The other four are Tier 1 service credentials, which that
namespace **deliberately refuses to front** — "Fronting them too would give one
value two doors, which is the failure this issue exists to prevent." They live
in the config DB and arrive there from the environment by SEEDING
(`digdir.e2e.seed/seed-azure-config-from-env!`, the `api-key-admin` line in
onboarding §3) or by `bb config-set`.

So after removal the five split three ways, and the README must say which is
which rather than implying one mechanism:

- `auth.jwt-secret` — env, `JWT_SECRET`, via `digdir.secrets`
- `azure-openai.api-key`, `typesense.api-key-admin` — env-seeded into config
- `colbert.api-key`, `scaleway-tem.api-key` — `bb config-set`, no seeder

**Does the check need to distinguish "absent because it comes from the env"
from "absent because it is misplaced"? The DATA already does; the LOG MESSAGE
does not.** `unresolved-service-config` returns `:defined-on []` for genuinely
absent and `:defined-on [node-id]` for misplaced. But
`report-unresolved-service-config!` prints, unconditionally:

> "Values exist on other nodes but config inheritance runs CHILD -> PARENT"

which is FALSE when `defined-on` is empty — and after this change that is
exactly the case that fires for `services.typesense.api-key-admin` on a bare
import. That is a finding against Lane B's file, not mine to edit.

## Predicted diff stat

**2 files changed.** `config/system-import.normalized.20260821.json` is stored
as ONE compact line, so a correct change to it is **1 insertion, 1 deletion** —
if the insertion count is in the thousands I have pretty-printed it and the
diff is unreviewable. `config/README.md` is a normal multi-line diff.

---

# Measured (2026-08-25) — run after the above was written

Harness: a throwaway worktree at Lane B's commit `77e9333` (their `verify.clj`
is the instrument), importing each snapshot into its own fresh Datahike file DB
with the fleet's `CONFIG_MASTER_KEY`.

| variant | placement | secrets | UNRESOLVABLE/tenant | UNDECRYPTABLE/tenant |
|---|---|---|---|---|
| B-before (control) | `default` | present | 0 | **5** |
| B-after (shipping state) | `default` | removed | **1** (`typesense.api-key-admin`, `defined-on []`) | **0** |
| B-after + recipe step 3 | `default` | removed, then seeded | **0** | **0** |
| A-after (this branch alone) | `shared` | removed | 5 | 0 |

## Predictions that held

State A and State B both came out exactly as written above, including the
specific detail that `services.typesense.api-key-admin` moves to
`:defined-on []` while the other four required paths keep pointing at
`platform/<tenant>/shared`. Post-recipe reaches 0/0, which is the success
condition: the credential arrives from the environment, or its absence is
named.

## THE FINDING: the "2 undecryptable per tenant" baseline does not reproduce

The control measures **5 per tenant, not 2** — every one of the five reports
`Tag mismatch`, on both tenants. All five ARE reachable on Lane B's placement
and all five fail. I could not explain "2" before running and deliberately did
not invent a number for it; the measurement says the reported figure is wrong
or was taken against a database where part of the recipe had already run
(step 3 overwrites `api-key-admin`, azure seeding overwrites another — that
would give 3, still not 2).

This does not change the removal, and it strengthens it: the artifact was
carrying more dead ciphertext than the board believed.

## SECOND FINDING: the operator-facing message contradicts its own data

Confirmed from the actual log line, not from reading the source:

```
ERROR ::runtime-cannot-resolve-service-config The runtime cannot resolve 1
required service config value(s) for tenant digdir. Values exist on other
nodes but config inheritance runs CHILD -> PARENT, so a node below the entry
point is never reached. See #275.
   data: {... :unresolved ["services.typesense.api-key-admin"],
          :values-found-on []}
```

The sentence says values exist on other nodes; the data says
`:values-found-on []`. Before this change that combination was rare; after it,
it is THE routine case — a fresh install with no Typesense key gets an error
telling it to go looking for a misplaced value that does not exist, which is a
new instance of the "names the wrong cause" shape #275 exists to remove.

The fix belongs in `report-unresolved-service-config!` (branch the message on
`(seq defined-on)`), which is Lane B's file on Lane B's branch. Not edited
here. Reported to the PI.

## Re-applying this after Lane B lands (the collision)

Lane B has ALREADY committed a different version of this same file
(`469f6da`, 120,002 bytes) moving all 44 platform values from `shared` to
`default`. My base has `591d967`, 120,733 bytes. **The file is one line, so
git cannot merge the two edits — whoever merges second gets a whole-line
conflict, not a hunk.**

No hand-merge is needed, because the rule is mechanical and content-addressed
rather than positional:

> Delete every `data.node-values` row whose `config.value/definition-path`
> names a definition with `config-def/encrypted? true`. Change nothing else.

Applied to Lane B's file that rule removes the same ten rows and yields the
B-after variant measured above (125 -> 115 node-values, 0 encrypted). So the
resolution is: take Lane B's version whole, re-run the rule, keep this README.

## Post-rebase correction (2026-08-26)

Rebased onto `8c2ae38` (#310 merged). The rule was re-run over Keel's snapshot
and produced a file **byte-identical** to the B-after variant measured above,
so the measurement stands on the real artifact rather than on a synthetic
merge.

**One count above is stale and is deliberately not edited**, because this
section of the document is the record of what was predicted beforehand: the
"all 18 nodes are untouched" line was true of the pre-rebase base. Keel's
version deletes the two now-empty `platform/<tenant>/shared` nodes, so the
shipped file has **16** nodes, not 18. `config/README.md` was corrected to say
16; the prediction text is left as written.
