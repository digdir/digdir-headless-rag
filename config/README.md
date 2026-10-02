# `config/` — the committed config snapshot, kept as a TEST FIXTURE

`system-import.normalized.20260821.json` is a **test fixture, not a setup
input**. The documented cold start (`docs/onboarding.md`, "First queryable
dataset") does not import it, and nothing automated imports it into an install.
Tests read it:

- `deployment-specific-test`, `llm-namespace-test`, `read-paths-seeded-test`,
  `removed-definitions-test` and `snapshot-tenants-test` name it by path.
  `removed-definitions-test` imports it into a test store.
- `digdir.llm.provider-fixtures` finds it by regex over `config/`, and throws
  unless exactly one file matches `system-import.normalized.<digits>.json`.
  `provider-selector-pins-test` reads it that way.

So keep it, and keep it the only file of that shape: deleting it, or adding a
second one, breaks those tests.

It carries 119 config definitions, one config node (`__global__/runtime/default`)
with its 10 values, and two agent rows (`builtin/agent-rag-agent` and
`builtin/fact-checker-agent`), which overwrite those two seeded built-ins'
guardrails when imported, until `init-config-db!` and boot run again and restore
the seeded ones. It carries **no tenant, no dataset, no pipeline, no
user and no API key**, so importing it makes nothing queryable by itself. On a
store that has booted, boot already holds its node and values, and its
definitions add nothing boot does not register, apart from 17 that boot
deliberately retracts and the import brings back. That is why the cold
start no longer imports it. The sections below
dated 2026-08-21 and 2026-08-25 describe the file as it was then; the counts in
this paragraph are the current ones, measured from the file. It deliberately
carries **no secret values** — see
[Why it carries no secrets](#why-it-carries-no-secrets-279) for where each of
the five it used to carry comes from instead.

## Why it carries no secrets (#279)

Until 2026-08-25 this file shipped ciphertext for five config paths, two
tenants each — ten encrypted values in total: `services.auth.jwt-secret`,
`services.azure-openai.api-key`, `services.colbert.api-key`,
`services.scaleway-tem.api-key` and `services.typesense.api-key-admin`.

They were sealed with a master key that is not in this repository and was not
recoverable: the fleet's, production's, test's and both `.env.example` values
were each tested against all ten ciphertexts, and none of them opens any.

**Finding the key would not have helped, and that is why this is a removal
rather than a recovery.** An encrypted secret inside a distributed artifact can
only be opened by someone who already holds the key, and a new contributor by
definition does not. Shipping the key alongside the file would make the
encryption ceremonial — committing the key that decrypts the committed secrets
is the same act as committing the secrets. **The design cannot work for its
stated purpose under any key**, so no key was worth hunting for.

What shipped was also worse than shipping nothing. AES-GCM reports a wrong key
as `Tag mismatch`, several layers below whatever wanted the value, so the
observable symptom was a vendor HTTP 400 complaining about a model named
`null` — a configuration problem arriving as someone else's protocol error.

**The structure stays.** All nine `encrypted? true` config definitions remain,
as do all 16 nodes; `data.node-values` goes from 125 to 115. The operator
console still lists these paths and `bb config-set` still writes them. What
changed is that the file no longer claims to supply their values.

### Where each of the five comes from instead

| path | source | how |
|---|---|---|
| `services.auth.jwt-secret` | env `JWT_SECRET` | Read at bootstrap by `digdir.config.core`, and declared in `digdir.secrets` as a tier-0 secret. **This one was already inert in the file:** it is listed in `env-migrated-paths` (`server/src/digdir/config/db.clj`), meaning config-DB writes to it are ignored and reads never consult it. |
| `services.azure-openai.api-key` | env `AZURE_OPENAI_*` | `digdir.e2e.seed/seed-azure-config-from-env!` writes it to that tenant's `platform/<tenant>/default` node at boot, re-encrypted with *your* master key. |
| `services.typesense.api-key-admin` | `bb config-set` | Set explicitly by the onboarding recipe. This is why retrieval works on a fresh install while the LLM half does not — the recipe was already overwriting one of the five with a working dev value, which until now looked like a coincidence rather than the mechanism. |
| `services.colbert.api-key` | `bb config-set` | No seeder. Only needed when `services.colbert.api-url` points at a reranker that authenticates. |
| `services.scaleway-tem.api-key` | `bb setup` | `setup-email-config` prompts for it. Without it, dev login falls back to printing the confirmation code into the `bb dev` log. |

Of the five, only `services.typesense.api-key-admin` is one of the paths the
runtime treats as required, so it is the only one whose absence an import
reports, as plainly absent, for each tenant the imported file carries. The check
is `digdir.config.verify`. The file now carries no tenant, so importing it
reports no such absence (measured); on the documented path the value comes
from `bb demo-tenant`'s
environment bridge or `bb config-set`.


## Why it was regenerated (2026-08-21)

The previous snapshot, `system-import.normalized.20260404.json`, could not be
imported at all. Two independent reasons, both from drift between the file and
the code that reads it (#81, #85):

- its `users` collection used plain keys (`id`, `email`) while `import-users!`
  reads `:user/id` / `:user/email`, so the import died on
  `Cannot store nil as a value at [:db/add … :user/id nil]`;
- all five of its agents named skill graphs that no longer exist —
  `builtin/agent-rag`, `research-assistant`, `retrieve-only`, `simple-qa` were
  retired, and only `builtin/fact-checker` survived — so agent validation threw
  `Unknown default skill graph`.

Regenerating from a live database fixes both without anyone having to decide
what the retired graph ids should map to: the export path canonicalises users
into the namespaced shape, and the agents come out as whatever the current code
actually defines.

## What changed in the content

Nothing was lost. Verified key-by-key against the old file:

| collection | old | new | note |
|---|---|---|---|
| config definitions | 84 | 121 | none lost; +37 that current code creates at DB init |
| config nodes | 17 | 18 | none lost; +1 (`__global__/runtime/default`) |
| node values | 115 | 125 | **none lost, none changed**; +10 rerank defaults on the new node |

> The `125` above is the count as regenerated. #279 later removed the ten
> encrypted values, so the file now holds **115** node values — see
> [Why it carries no secrets](#why-it-carries-no-secrets-279).
| datasets | 2 | 2 | `kudos`, `public-docs` — unchanged |
| dataset-pipelines | 3 | 3 | unchanged |
| users | 1 | 1 | same user, re-keyed to the namespaced shape |
| agents | 5 | 3 | **see below — this is a real change, not cleanup** |

### The agent set changed

| agent | old | new | why |
|---|---|---|---|
| `builtin/agent-rag-agent` | ✅ | ✅ | kept; now names `builtin/agent-rag-graph-bundled` / `-faithful` instead of the retired `builtin/agent-rag` |
| `builtin/fact-checker-agent` | ✅ | ✅ | unchanged — its graph is the one that survived |
| `builtin/research-assistant-agent` | ✅ | ❌ | **dropped** — `builtin/research-assistant` no longer exists |
| `builtin/retrieve-only-agent` | ✅ | ❌ | **dropped** — `builtin/retrieve-only` no longer exists |
| `builtin/simple-qa-agent` | ✅ | ❌ | **dropped** — `builtin/simple-qa` no longer exists |
| `digdir/altinn-docs-tuned` | ❌ | ✅ | **added** — a current builtin, absent from the old file |

Those three agents could not be carried across under any mapping: the graphs
they exist to run are gone. If any of them should come back pointing at a
surviving graph, that is a product decision, and it is separate from this file.

### That product decision was taken (2026-08-24, #240)

All three came back, none of them as a restoration:

| agent | names | what changed |
|---|---|---|
| `builtin/research-assistant-agent` | `builtin/agent-rag-graph-faithful` | The retired `builtin/research-assistant` graph had byte-identical steps to `builtin/simple-qa`, so "research" was never a graph property. It is now the agent-rag loop with a wider retrieval pool, defaulting to the faithful variant because per-step decomposition is what makes a research answer auditable. |
| `builtin/retrieve-only-agent` | `builtin/retrieve-only` | The graph is back too, but rewired: the retired one predated the user-intent first pass and corpus-aware expansion, so restoring it verbatim would have measured a retrieval path production no longer runs. |
| `builtin/ai-overview-agent` | `builtin/ai-overview` | **New**, not a restoration. Replaces `simple-qa` with a Google-AI-Overviews-shaped answer: short, fully cited, and declining rather than answering on thin retrieval. See `digdir.skills.builtin.overview`. |

**This file has NOT been regenerated for that change, and does not need to be
for the system to work.** Today it carries two agent rows,
`builtin/agent-rag-agent` and `builtin/fact-checker-agent`. The built-in agents
themselves come from `digdir.agents.core/production-agent-definitions`, seeded
by `digdir.agents.db/seed-builtin-agents!`, and boot alone seeds none (measured).
They are seeded by `init-config-db!`, which the import runs before
importing anything and which `digdir.setup.first-admin` runs too (both measured;
`bb setup`'s admin step reaches it as well, by reading), and by the E2E boot seed
(measured). The file's two rows then overwrite those two agents' guardrails.
The staleness is a count, not a breakage — nothing in here names a graph that
does not exist, which was #81's actual defect.

Regenerating it is worth doing on its own, and the recipe below still applies —
note especially step 2: an export from a dev JVM carries `builtin/docs-agent`,
and this file must not.

`builtin/docs-agent` is intentionally **absent**. It lives in `src-dev` and
names the `docs/self-improve-*` graphs, which a production build does not have.
Including it would make this snapshot fail to import anywhere without `src-dev`
on the classpath. A dev machine gets it from seeding at boot regardless.

## How to regenerate it

Export from a JVM where the skill graphs are registered, or the exported agents
will be a *filtered derivative* rather than the definitions — that is exactly
what produced the mystery in #81, where a persisted agent's default had been
silently rewritten to the first surviving graph in its allowed list.

1. Start from a database holding the content you want in the snapshot — for
   this regeneration, a fresh DB with the previous snapshot's config imported
   (its `agents` section dropped) and the current builtin agents seeded.
2. Export on the **production classpath** (`clojure -M`, no `src-dev`), so
   dev-only agents stay out:
   ```clojure
   (migration/export-to-file (config-db/get-conn) (db/get-conn) out-path
                             {:master-key (config-core/get-master-key)
                              :include-audit? false})
   ```
   `bb migration-export <out.json>` does the same thing on the dev classpath —
   convenient, but it will include `builtin/docs-agent`.
3. Verify before committing: import it into a fresh DB on **both** classpaths,
   and diff the collections against the file you are replacing. Both were done
   for this one.
