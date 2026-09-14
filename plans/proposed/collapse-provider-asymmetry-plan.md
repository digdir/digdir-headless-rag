# Collapse the Azure / OpenAI provider asymmetry

## Goal

The system's dual-path LLM plumbing exists because it once had to call Azure
**and** an OpenAI-compatible endpoint *at the same time*. That requirement is
gone. This plan reevaluates every asymmetry that requirement produced and
removes the ones that no longer earn their place — storage, dispatch, naming
and call-site shape, not storage alone.

The test applied to each asymmetry below is: **does this still encode a real
difference between the two providers, or only the memory of needing both at
once?**

## Evidence base

Measured on `8a14ed1` (branch `improve-onboarding`), 2026-09-14. Counts are
from grep over `server/src` and `server/src-dev`; file:line references are exact.

### There are FOUR provider selectors, not one

| # | Selector | Type | Where |
|---|---|---|---|
| 1 | `services.azure-openai.use-azure-openai-api` | boolean | 32 conditional occurrences across 25 files |
| 2 | `services.search-phrases.provider` | keyword | `docs/pipeline/search_phrases.clj:23-56, 90` |
| 3 | `services.self-improvement.provider` | keyword | `skills/enrichment/propose_questions.clj:135-148, 217-218` |
| 4 | hardcoded `case` + fixed default | none | `docs/loader.clj:641-659`, called with `:azure-openai` at `:670` |

Selectors 2, 3 and 4 **do not consult selector 1**. Keyword selectors accept
`:azure-openai | :openrouter | :lmstudio` and default to `:azure-openai`.

**Consequence, and it is the headline finding:** a deployment that sets
`use-azure-openai-api false` still drives search-phrases, the document loader
and self-improvement enrichment down the **Azure** branch. "OpenAI mode" is
not one switch; it is one switch plus two config keys plus a code change.

### There are THREE credential stores for "an OpenAI-compatible endpoint"

| Store | Paths | Scope | Settable by |
|---|---|---|---|
| environment | `OPENAI_API_ENDPOINT`, `OPENAI_API_KEY` | process-global | `.env` only — `:path nil` in the bridge |
| config DB | `services.lmstudio.{api-endpoint,api-key,model}` | per tenant | `bb config-set` |
| config DB | `services.openrouter.api-key` (URL hardcoded) | per tenant | `bb config-set` |

`services.lmstudio.*` definitions are created at runtime by
`setup/config.clj:56 ensure-lmstudio-config-definitions!`, not shipped in the
committed snapshot — which is why they look absent to a `config-defs/` grep.

A per-tenant, config-backed OpenAI-compatible endpoint **already exists**. The
env-only `OPENAI_API_*` pair is a second, weaker implementation of the same
idea, and it is the one `llm/client.clj:90 openai-compat-completion` falls
back to.

### The branch is copied into every call site

`llm/openai.cljc:51-63` is the canonical shape:

```clojure
(if (use-azure-openai tenant)
  (api/create-chat-completion {:model (cfg/get ... :deployment-name) ...}
                              {:api-key ... :api-endpoint ... :impl :azure})
  (api/create-chat-completion {:model (cfg/get ... :model-name) ...}))
```

Note the else-branch passes **no opts map at all**, so endpoint and key fall
through to `System/getenv` / `secrets/get!` inside the client. That single
detail is the source of the per-tenant asymmetry.

The same shape is re-implemented at, among others:
`rag/synthesis.clj:21,65`; `sweep/judge.clj:87`;
`skills/builtin/{fact_checking:124, summarization:135, entity_extraction:114,
graph_builder:212, synthesis:232, query_planner:314,617,660}`;
`skills/builtin/agent/{loop.clj:83,128,139, read_signals.clj:151}`;
`demo/altinn_{release_notes:182, authoring:95, translation_drift:337}`;
`playground/core.cljc:853`; `src-dev/sweep/{runner.clj:1377, rechunk_reground.clj:22}`.

30 sites mention `:impl :azure` in total.

### Parameter resolution already has four layers — and records none of them

`skills/graph/runner.clj:139-151 resolve-step-parameters`:

```clojure
(merge
  (:parameters step)     ; skill-GRAPH step defaults      <- lowest
  common-params          ; graph-wide :model/:temperature/:max-tokens/:prompt
  per-skill-params       ; (get skill-params skill-id)
  execution-overrides)   ; per-run                        <- highest
```

All eight LLM-calling skills honour `(or model ...)`, so the chain reaches
every one of them: `synthesis`, `query_planner`, `fact_checking`,
`summarization`, `entity_extraction`, `graph_builder`, `agent/loop`,
`agent/read_signals`.

**Two facts follow, and they pull in opposite directions.**

The reassuring one: the in-skill `(if (llm/use-azure-openai tenant)
deployment-name model-name)` fallback sits BELOW all four layers. It is the
only thing Phase 1 replaces, so collapsing the provider asymmetry cannot
reduce skill- or graph-level parameterisation.

The unwelcome one: `resolve-step-parameters` merges and returns. Nothing
records WHICH layer supplied the winning value — no trace event, no output
field. During Phase 1 that is disabling: the bottom layer is being rewritten
and there is no way to show that the same model was selected for the same
reason before and after. A passing test suite does not answer that question.

The right pattern already exists one level down. The enrichment subsystem
stamps `:model`, `:prompt-hash` and `:generated-at-ms` onto every row it
writes, explicitly "for audit and future-selective-regeneration", with a
sentinel when the model is unknown (`skills/enrichment/apply_questions.clj:60-95`).

### Persisted skill-level model config covers one skill in eight

Only `synthesis` has operator-facing keys: `skills.synthesis.{model,
temperature,max-tokens}`. `query-planner` has `{enabled, prompt, max-phrases,
expansion-mode}` and no model or temperature. `fact-checking`,
`summarization`, `entity-extraction`, `graph-builder`, `agent/loop` and
`agent/read-signals` have no `skills.*` namespace at all.

Those seven are configurable per RUN by a caller, but an operator cannot
persistently pin one. Today that is invisible because everything lands on the
same tenant default; it becomes visible the moment provider choice is real per
usage, which is what this plan enables.

## Asymmetry inventory, and the verdict on each

| # | Asymmetry | Why it existed | Still earns it? |
|---|---|---|---|
| A1 | Credentials in env (Path B) vs config DB (Path A) | Path B was a local-dev convenience bolted on beside a cloud deployment | **No** — `services.lmstudio.*` already proves config-backed works |
| A2 | Path B is process-global, Path A per tenant | Same | **No** |
| A3 | `deployment-name` vs `model-name` as separate keys | Azure needs a deployment id in the URL; OpenAI needs a model in the body | **Partly** — one key, one meaning; see O1 |
| A4 | wkok client vs clj-http POST | Azure URL shape, `api-version`, `api-key` header vs `Authorization: Bearer` | **Yes** — irreducible, but belongs at ONE chokepoint |
| A5 | The conditional duplicated at ~16 call sites | No design reason; accretion | **No** |
| A6 | Keyword provider dispatch (selectors 2-4) beside the boolean | Precisely the "both at once" requirement | **No** |
| A7 | Non-Azure model name stored at `services.azure-openai.model-name` | Expedience | **No** |
| A8 | `:lm-studio` (loader) vs `:lmstudio` (search-phrases) for one thing | Two authors | **No** |
| A9 | Two OpenAI-compatible stores (`OPENAI_API_*` vs `services.lmstudio.*`) | Different subsystems, different eras | **No** |
| A10 | Persisted skill-level model config for `synthesis` only | Synthesis was the first skill to need a non-default model | **No** — extend to the other seven, or drop the one |

A4 is the only one that survives. Everything else is the residue of the
retired requirement.

## Proposed changes

### Phase 0 — record what was resolved, and from where

Make `resolve-step-parameters` return the resolved values **with their source
layer**, and emit that on the step's trace:

```clojure
{:model "gpt-5.5" :model/source :graph-step
 :temperature 0.1  :temperature/source :skill-default}
```

with `:provider-fallback` as the source name for the in-skill
`(if use-azure ...)` branch that Phase 1 replaces.

No behaviour change, no config change, small diff. It exists so that every
later phase can be verified by comparing resolved-parameter records before and
after, rather than by trusting that a green suite means the same model was
chosen for the same reason.

**This phase is a precondition for Phase 1, not an optional extra.** Without
it, the Phase 1 verification in this plan cannot actually be performed.

### Phase 1 — one resolver, one shape

Introduce a single `digdir.llm.provider/resolve` returning a complete,
already-decided call spec:

```clojure
{:model "..." :api-key "..." :api-endpoint "..." :impl :azure|:openai}
```

Every call site becomes `(client/create-chat-completion params (provider/resolve tenant))`
with **no conditional**. A4 lives inside `llm/client.clj` where it already is;
nothing above the client learns which provider is in play.

This is the phase that pays for the rest: it removes 32 conditionals and makes
every later change one-site.

### Phase 2 — one credential namespace

Add `services.llm.{api-endpoint,api-key,model,provider}`, fed by the env bridge
exactly as Azure already is. `provider` replaces the boolean and both keyword
selectors with one value: `:azure | :openai-compatible`.

Keep `OPENAI_API_ENDPOINT` / `OPENAI_API_KEY` as **seeding inputs** to those
paths, not as a read path.

**Explicitly out of scope:** the `OPENAI_{REASONING_EFFORT,TEMPERATURE,TOP_P,
MIN_P,MAX_TOKENS,DISABLE_THINKING,...}` family. Those are deliberately global,
run-level tuning knobs for sweeps and benchmarks and should stay env-only.
Migrating them would break the experiment workflow for no benefit.

### Phase 3 — collapse the dispatchers

Delete `openai-implementation` (`loader.clj:641`, `search_phrases.clj:23`) and
`provider-impl` (`propose_questions.clj:135`); route all three through Phase 1's
resolver. `:openrouter` becomes a `services.llm` value rather than a code branch.

This is where the headline finding is actually fixed: after Phase 3 the switch
means what its name says everywhere.

### Phase 4 — naming

Retire `services.azure-openai.model-name` in favour of `services.llm.model`;
normalise `:lm-studio` → `:lmstudio`. Keep `services.azure-openai.*` for the
genuinely Azure-only values (`deployment-name`, `api-version`).

### Phase 5 — setup and docs

`scripts/setup-env.sh` prompts for the Path B values when the provider answer
is not Azure. It currently prompts for three Azure variables and **zero**
OpenAI ones (`grep`: no occurrence of `OPENAI_API_ENDPOINT` or `OPENAI_API_KEY`
in the script), so answering "false" yields a `.env` with nothing the chosen
path needs. Rewrite `.env.example`'s Path A / Path B blocks around the single
`provider` key.

## Implementation map

| Phase | Files |
|---|---|
| 0 | `skills/graph/runner.clj:139-151, 201`; the step-trace emitter |
| 1 | new `server/src/digdir/llm/provider.clj`; `llm/openai.cljc:44-63`; the ~16 call sites listed above |
| 2 | `config/env_bridge.clj:145-225`; `setup/config.clj:52-118`; `config/deployment_specific.clj:55-100` |
| 3 | `docs/loader.clj:641-670`; `docs/pipeline/search_phrases.clj:23-90`; `skills/enrichment/propose_questions.clj:135-218` |
| 4 | `config-defs` + a snapshot import; `playground/ui/common.cljc:141,263` |
| 5 | `scripts/setup-env.sh:41,89,214-240`; `.env.example:111-180`; `README.md`; `docs/onboarding.md` |

## Verification plan

1. **Resolved-parameter records, before and after every phase** (Phase 0).
   For a fixed corpus of graph runs, the `{value, source}` pair for `:model`,
   `:temperature` and `:max-tokens` at each step must be unchanged across
   Phases 1-4 — except where a phase intends to change the source, which it
   then states. This is the only check that can distinguish "same behaviour"
   from "same test outcome".
2. **A guard on boot-tier composition.** Nothing currently pins *which*
   variables are `:tier :boot`; `required_env_test` only asserts the check
   reads `:tier` off the table. Add a test asserting the boot set is exactly
   the six database/bootstrap variables, so an LLM key can never silently
   become boot-required. (Verified today: `boot-requirements` with zero Azure
   variables returns `{:checked 6 :missing [] :unsatisfied-groups []}`.)
3. **One test per selector, pre-change**, pinning today's behaviour including
   the defects — then flipped as each phase lands. Specifically: a test that
   `use-azure-openai-api false` still sends search-phrases to Azure (red-by-
   design, documents the bug), inverted in Phase 3.
4. **Both branches at the chokepoint.** `model_params_test/capture-direct-body`
   and `capture-azure-params` already do this; extend them to assert the
   resolver's output rather than hand-built opts.
5. **A sabotage pass per phase**: revert the change, require the new tests red.
6. **End-to-end**: one real LM Studio run (the only Path B target ever
   exercised per `.env.example`) and one Azure run, before and after.

## Risks and open questions

- **Config-def surgery is the real cost.** New definitions need an import, and
  the committed snapshot is a single-line JSON file that collides unmergeably
  between branches (see `279-secret-removal-expected-outcome.md`). Phases 2
  and 4 should land alone, not stacked.
- **`services.azure-openai.model-name` has live values.** Phase 4 is a
  migration, not a rename; needs a read-both/write-new interval.
- **Is env-only deliberate for local dev?** Reading config with an env fallback
  preserves the laptop workflow while making the deployed case per-tenant. That
  is the recommended resolution, but it is a judgement call.
- **`:openrouter` hardcodes its URL** at two sites. Folding it into
  `services.llm` is right but widens the blast radius; it could be deferred.
- **Unknown:** whether any deployment currently relies on the keyword selectors
  differing from the boolean — i.e. Azure for queries, LM Studio for
  enrichment. That combination is exactly what the retired requirement
  supported, and Phase 3 removes it. **This needs confirming before Phase 3.**

## Suggested execution order

1. **Phase 0 first** — provenance recording. Everything below is verified against it.
2. Boot-tier guard and pinning tests — no behaviour change.
3. Phase 1 resolver + call-site collapse. Largest diff, lowest risk, no config change.
4. Phase 5 `setup-env.sh` fix — independent, small, fixes a live onboarding gap.
5. Confirm the open question above.
6. Phase 2, then 3, then 4 — each on its own branch because of the snapshot.
