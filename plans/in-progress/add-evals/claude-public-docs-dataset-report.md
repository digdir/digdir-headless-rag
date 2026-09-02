# public-docs Dataset Analysis Report

Status: Produced 2026-04-18 as Deliverable 1 of `public-docs-evals-suite-plan.md`. Runtime sections closed on 2026-04-20 against the imported snapshot `config/system-export-2026-04-20.json`.

> **V1 Scope Pivot (2026-04-20).** Runtime inspection revealed that `public-docs` as currently materialized is a **single-source, Norwegian-only** corpus sourced from `docs.altinn.studio`. No Digdir Docs content and no English content are present. This invalidates the "two pipelines feed the dataset" premise the original plan built on. V1 of the eval suite targets the actual corpus (Altinn NO only); source-slice and bilingual-parity gates are deferred to v2, pending the `digdir-docs` pipeline materialization and English sitemap coverage. See §2 and §8 for the factual update and Appendix B for the pivot record.

Scope: read-only static inspection of the repo, plus a live runtime sweep on 2026-04-20 (Appendix A for `skills.*` config, Appendix B for corpus inventory).

Source citations use `path:line` format.

## 1. Dataset Wiring

### 1.1 Reference resolution

- Entry point: `digdir.api.context/resolve-dataset-context-by-ref!` at `server/src/digdir/api/context.clj:425`.
- Delegates to `digdir.execution.scope` at `server/src/digdir/execution/scope.clj:59`.
- Normalizes `{:tenant "digdir" :dataset-config-key "public-docs"}` (`scope.clj:8`) and loads the dataset node via `config-db/get-dataset-by-ref` (runtime config DB call).

### 1.2 Pipelines feeding `public-docs`

Confirmed from the integration test fixture at `server/test/digdir/pipeline/integration_test.clj:67`, `:82`, `:126`, `:161`, `:195`:

| Pipeline | Source | Sitemap |
|---|---|---|
| `altinn-docs` | Altinn developer docs | `https://docs.altinn.studio/sitemap.xml` |
| `digdir-docs` | Digdir docs | `https://docs.digdir.no/sitemap.xml` |

Both pipelines materialize into a **shared** Typesense collection set keyed to the dataset, not per-pipeline.

### 1.3 Typesense collections

- Collection-name generation: `digdir.pipeline.collections/get-or-generate-collection-names` at `server/src/digdir/pipeline/collections.clj:42`.
- The collection prefix is taken from `:collection-prefix` if set, otherwise from the pipeline name (`collections.clj:63`). For the deployment target topology (`bb bootstrap-deployment-target-topology`) the effective prefix is `public_docs`, producing:
  - `public_docs_documents_{hash}`
  - `public_docs_chunks_{hash}`
  - `public_docs_phrases_{hash}`
- **[RUNTIME]** Exact live collection names (with the current hash suffix) can only be read from the config DB.

### 1.4 Topology bootstrap

- `bb bootstrap-deployment-target-topology` (`bb.edn:1097`) creates the digdir/public-docs and public-sector-knowledge/kudos datasets and registers `altinn-docs` + `digdir-docs` as materialization pipelines into `public-docs`.
- The completed plan `plans/completed/deployment-polish-and-materialization-migration-plan.md` is the historical record.

## 2. Corpus Inventory (resolved 2026-04-20)

Live counts pulled via `typesense.client/retrieve-collection` against `services.typesense.api-host` = `typesense-test.digdir.cloud:443`.

### 2.1 Actual shape

| Metric | Altinn Docs | Digdir Docs | Total |
|---|---|---|---|
| Document count | 62 | 0 | 62 |
| Chunk count | 177 | 0 | 177 |
| Phrase count | 1387 | 0 | 1387 |
| Distinct URL path roots (after `/nb/`) | 12 | — | 12 |
| Source URLs | all `docs.altinn.studio` (inferred from paths) | — | — |

Chunk length and p95 are not captured here; the corpus is small enough that percentile analysis is not informative for v1.

URL path roots under `/nb/` (document counts):

| Root | Docs |
|---|---|
| `authorization` | 19 |
| `altinn-studio` | 15 |
| `broker` | 8 |
| `dialogporten` | 4 |
| `correspondence` | 4 |
| `app-template` | 3 |
| `api` | 3 |
| `community` | 2 |
| `technology` / `events` / `notifications` / `security` | 1 each |

### 2.2 Language mix (actual)

- `docs.altinn.studio` (via /nb/): **100% Norwegian Bokmål**. No `/en/` or `/nn/` URLs in the materialized set.
- `docs.digdir.no`: **not materialized**. Expected Norwegian/English/Nynorsk mix absent from the current snapshot.

### 2.3 Freshness spread (actual)

- Oldest `lastmod`: 2023-05-15 (`/nb/app-template/architecture/app-frontend/configuration/index.md`).
- Newest `lastmod`: 2026-02-19 (`/nb/authorization/guides/end-user/system-user/accept-request/index.md`).
- Distribution skews recent: most docs carry 2025-09 through 2026-02 timestamps, reflecting an Altinn docs freshness pass.

### 2.4 Schema fields (chunks collection)

`website_chunks_ab897fbdedfa` defines: `chunk_id`, `chunk_index`, `content_length`, `content_markdown`, `doc_num`, `metadata`, `url`. No `language`, no `source` / `pipeline_id`, no `title` — the URL path is the only discriminator for slicing.

Implication: `:source-slice` and `:language` cannot be filtered server-side; the eval runner would need to derive them from the URL pattern (`/nb/` prefix + host guessed from path root). For v1 (single-source), this is moot.

## 3. Content Character

**[RUNTIME]** Sample ~20 chunks across both sources and record:

- Structural traits — markdown headings, tables, code blocks, admonitions, tabbed sections.
- Entity density — organization names, Norwegian law references, acronyms (KRR, Altinn, ID-porten, Maskinporten, eFormidling).
- Question-ability — reference-style vs narrative vs FAQ.
- Bilingual pairing — whether the same page exists in NO and EN, and whether chunk IDs track across language.

Until sampled, assume a mix of reference documentation (navigational lookup: "hvordan legge til en avsender"), product explainer content (factual: "hva er Altinn 2"), and regulatory/standards pages (legal references).

## 4. Retrieval Config

The `public-docs` dataset is served by the `retrieval` skill at `server/src/digdir/skills/builtin/retrieval.clj`. Defaults below are static defaults from source; dataset-level overrides live in the config DB and must be confirmed at runtime.

### 4.1 Multi-strategy search

`retrieval.clj:74`-`:127`. Three parallel searches merged by `chunk_id`:

1. Phrase search (similarity on phrases collection).
2. Metadata search (structured fields — orgs, doc properties).
3. Content search (full-text on chunks collection).

### 4.2 Boost weights (defaults, `retrieval.clj:193`)

```
title-overlap-per-token   0.03   (cap 0.30)
content-overlap-per-token 0.04   (cap 0.40)
year-match                0.15
org-filter-match          0.40
content-search-type       0.35
phrase-search-type        0.08
metadata-search-type      0.05
numeric-evidence          0.45
original-rank-weight      0.10
```

### 4.3 Diversity (defaults, `retrieval.clj:208`)

- `default-max-per-document: 10`.
- Auto-relaxation to 50 when ≥30 total results from ≥3 docs with top doc ≥45% of pool.

### 4.4 Auto-filter

- Enabled by default (`retrieval.clj:466`). Detects org and year constraints from query text.
- Fallback: retries without auto-filter if zero results.

### 4.5 ColBERT rerank

- Opt-in via `rerank-with-colbert: true` (off by default at the skill layer; dataset-level override expected).
- Fetches top-K candidates (default 40) with full content, sends to ColBERT service (`skills/builtin/rerank.clj`), sorts by score desc.
- On ColBERT failure, returns candidates unchanged with `:rerank-error`.

### 4.6 Dataset-level config

**[RUNTIME]** Run `bb config-get skills.rerank.top-k digdir runtime public-docs` (and analogous paths) to capture the effective config. Paths to inspect:

- `skills.retrieval.top-k`, `skills.retrieval.max-per-document`, `skills.retrieval.auto-filter`, `skills.retrieval.query-aware-boost`
- `skills.rerank.top-k`, `skills.rerank.context.top-k`, `skills.rerank.context.min-chunks`, `skills.rerank.context.relative-score-threshold`
- `skills.agent.*` budget settings if present.

## 5. Usage Signals

**[RUNTIME]** Sources to mine if available:

- Playground conversations scoped to `digdir/public-docs` (`db/get-playground-conversations`-style surface).
- Live production query logs if any retention exists.
- Observability UI (`digdir.playground.ui.observability.*`) — recently active in the working tree.

Until mined, assume cold-start: queries must be authored rather than replayed.

## 6. Known Failure Modes

Scan of `plans/` and recent commits did not surface `public-docs`-specific complaints (missed retrievals, rerank drift, hallucinations, latency). The eval machinery that exists today is exercised against the `kudos` dataset (see §7). Likely latent risk areas, inferred from retrieval algorithm shape:

- **Source asymmetry**: a blended dataset score can mask regression in one source (Altinn vs Digdir).
- **Bilingual parity**: NO and EN queries against the same content may rank differently because of embedder and boost asymmetry.
- **Navigational vs factual**: factual lookups (exact numeric answers) work well with the current ColBERT rerank; navigational "take me to page X" queries are less well-tested in existing suites.
- **Query relaxation edge cases**: auto-filter falling back to unfiltered retrieval can change the candidate pool shape in ways that shift rerank ordering.

## 7. Existing Eval Machinery (Reuse Surface)

### 7.1 Suite EDN shape

Canonical shape, observed in `server/test/fixtures/rerank/benchmark_suite.edn` and `server/test/fixtures/agent/baseline_token_budget_medium_difficulty.edn`:

```clojure
{:cases
 [{:id                        "unique-id"
   :query                     "Hvor mange årsverk hadde Digdir i 2022?"
   :golden-chunk-ids          ["6a80d6499075"]
   :language                  :no              ;; optional
   :pair-id                   "arsverk-2022"   ;; optional, bilingual pairs
   :expected-answer-pattern   "(?is)\\b326\\b..." ;; optional, agent
   :budget-dimension          :read-chars      ;; optional, agent
   :current-budget            {...}            ;; optional, agent
   :relaxed-budget            {...}}]}          ;; optional, agent
```

### 7.2 Runners

Resolved from `bb.edn`:

| `bb` task | Entry | Purpose |
|---|---|---|
| `rerank-benchmark` | `bb.edn:502` | Live retrieval+rerank benchmark from a suite. Supports `--max-acceptable-rank`, `--require-context`, `--fail-on-gate`, `--no-auto-filter`. |
| `rerank-benchmark-report` | `bb.edn:628` | Wraps `rerank-benchmark` with a compact per-case table. |
| `rerank-language-benchmark` | `bb.edn:560` | Rerank-only, runs against a **captured candidate fixture**. Isolation-style. |
| `rerank-language-report` | `bb.edn:578` | Compact NO vs EN comparison on rerank-language output. |
| `agent-budget-benchmark` | `bb.edn:676` | Live end-to-end agent runs with current vs relaxed budgets; uses `:expected-answer-pattern`. |
| `agent-budget-report` | `bb.edn:687` | Per-case PASS/FAIL table + search/read path details. |
| `capture-isolation-candidates` | `bb.edn:520` | Persist filtered/unfiltered top40/top100 candidate pools for one query+golden. |
| `capture-rerank-language-candidates` | `bb.edn:542` | Persist bilingual candidate pools from a suite. |

### 7.3 Reporting payload

Last-line EDN emitted by the runners includes (from `bb.edn:597`, `:647`, `:707`):

```
:summary
  {:gate-pass :cases :cases-failed :mrr :p50-rank
   :golden-present-in-retrank :golden-present-in-context
   :regressed :current-pass :relaxed-pass :improved}
:summary-by-language     {:no {...} :en {...}}
:summary-by-source-slice {:altinn-docs {...} :digdir-docs {...}}
:results [{:id :language :source-slice
           :golden [{:rerank-position :context-position ...}]
           :pass}]
```

Native slice reporting is available for both language and source-slice. The `digdir.tools.diagnostics/slice-summary` helper backs both; cases without a slice value are bucketed under `nil`.

### 7.4 Deterministic tests

- `digdir.rag.rerank-evaluation-test` — ColBERT distribution, gated by `RUN_RERANK_EVALUATION_INTEGRATION`. Dataset currently targets `kudos` only.
- `digdir.rag.rerank-isolation-integration-test` — fixed-candidate rerank from fixture (via `bb rerank-isolation-eval`).
- `digdir.rag.auto-filter-integration-test` — auto-filter regression.
- `digdir.rag.budget-gate-integration-test` — budget gate.
- `digdir.tools.diagnostics` (under `server/src-dev/`) — retrieve-debug, rerank-debug, golden-chunk harness; `answer-matches?` is the regex check on final response.

### 7.5 LLM judge scaffolding

`digdir.llm.structured-eval` at `server/src/digdir/llm/structured_eval.clj` — JSON+enum validation with `:degraded?` fallback, already used for `read_signals` and sufficiency classifiers. Not currently used for answer grading, but the primitives are ready if we want LLM-as-judge later.

### 7.6 Existing suites

| File | Cases | Dataset | Notes |
|---|---|---|---|
| `server/test/fixtures/rerank/benchmark_suite.edn` | 10 | kudos | Norwegian arsverk lookups, one EN paraphrase. |
| `server/test/fixtures/rerank/mono_no_suite.edn` | 5 | kudos | Norwegian mono. |
| `server/test/fixtures/rerank/mono_en_suite.edn` | 5 | kudos | English mono. |
| `server/test/fixtures/agent/baseline_token_budget_medium_difficulty.edn` | 4 | kudos | Full agent shape: answer patterns + budgets. |
| `server/test/fixtures/agent/budget_hard_query_suite.edn` | 0 | — | Placeholder. |

**No `public-docs`-scoped suite exists today.**

## 8. Implications for Evals

Updated 2026-04-20 for v1 single-source reality.

1. The existing suite EDN format is a good fit. Keep `:source-slice` and `:language` fields — they pre-stage v2 multi-source work without hurting v1.
2. **Source slicing is a v2 ambition, not a v1 requirement**. With only `docs.altinn.studio` materialized, every case has `:source-slice :altinn-docs` and the slice gate is vacuously true. Gate decisions reduce to aggregate + per-case thresholds.
3. **Bilingual parity is a v2 ambition, not a v1 requirement**. All current content is Norwegian Bokmål; English and Nynorsk parity checks are deferred.
4. **Suite size shrinks**. The "20-40 cases" target assumed a two-source bilingual dataset. With 62 docs (many with 1-2 chunks) and a narrow topic spread, v1 targets **10-15 Altinn-NO cases** authored against the concrete content visible in §2.
5. Existing bb runners cover the three layers the automated plan wants — `rerank-benchmark` (Layer B), `rerank-isolation-eval` + isolation fixtures (Layer A's rank path), `agent-budget-benchmark` (Layer C). Layer A's deterministic retrieval-merge fixture piece is not yet wired for `public-docs`; a new fixture file and a test namespace are implied but small.
6. LLM-as-judge is explicitly out of scope for v1 of the suite per the automated plan. The `structured-eval` namespace is ready if a later version wants it.
7. Cold-start authoring: absent live logs, the first query inventory is hand-authored. The v1 inventory (post-pivot) is in `claude-public-docs-query-inventory.md` and is grounded in the concrete 62-doc content — not in what either source website publishes in aggregate.
8. Remaining runtime blockers before enforcing gates:
   - Run `bb retrieve-debug` per v1 candidate to identify goldens.
   - Run `bb rerank-benchmark` at least 3 times against the v0 suite to measure variance before picking `max-acceptable-rank`.

## 9. Open Runtime Tasks

Tracked so they can be closed before Phase 2 gate-enforcement:

1. ~~`bb config-get` sweep on `skills.*` for `{digdir, runtime, public-docs}`.~~ **Done 2026-04-20** — see Appendix A.
2. Typesense document/chunk counts per pipeline slice.
3. Sample 20 chunks, 10 per source, to sanity-check content character assumptions in §3.
4. Mine playground conversations scoped to `digdir/public-docs` for real query patterns; if none exist, flag cold start explicitly in the requirements doc.
5. Run `bb rerank-benchmark` against a 5-case seed suite to validate the runner against this dataset before scaling to 20-40.

## Appendix A — `skills.*` Config Sweep for `public-docs` (2026-04-20)

Sweep run against the imported snapshot `config/system-export-2026-04-20.json`, loaded into `local-db/add_evals_20260420/`. Resolver call:
`digdir.config.accessor/get-runtime-value-with-trace` with `{:tenant "digdir" :tenant-config-key "default" :dataset-id "public-docs" :agent-id "builtin/agent-rag-agent"}`.

### A.1 Effective overrides

Only two paths carry an explicit override in the config DB. All other `skills.*` paths resolve to `nil` at the DB layer, meaning the skill falls back to its hardcoded default at runtime.

| Path | Override value | Winning node |
|---|---|---|
| `skills.retrieval.strategy-contribution-caps` | `{:phrase 2, :content 1}` | `runtime/digdir/default` |
| `skills.retrieval.strategy-weights` | `{:phrase 1.0, :content 0.5, :metadata 0.5}` | `runtime/digdir/default` |

These are tenant-level overrides under `runtime/digdir/default`; they apply to *every* dataset scoped to the digdir tenant, not just `public-docs`. No `public-docs`-specific runtime override exists.

### A.2 Paths with no DB override (skill default applies)

The following paths return `:value nil, :winning-node nil` — the skill falls through to its built-in default:

- `skills.retrieval.{enabled,top-k,max-per-document,query-aware-boost,phrase-gen-prompt}`
- `skills.rerank.{enabled,top-k,max-chunk-length,max-context-length,max-total-length}`
- `skills.rerank.context.{top-k,min-chunks,relative-score-threshold,max-chunk-length}`
- `skills.query-planner.{enabled,max-phrases}`
- `skills.synthesis.{max-docs,max-tokens,model,temperature}`

The skill defaults listed in §4.2/§4.3/§4.5 of this report are therefore authoritative for the benchmark runs.

### A.3 Paths called out in the original plan but not configurable

- `skills.retrieval.auto-filter` — `:definition-not-found`. Not a config-DB key; controlled by the `--no-auto-filter` runner flag and the skill's internal logic (`retrieval.clj` §4.4).
- `skills.rerank.rerank-with-colbert` — `:definition-not-found`. Not a config-DB key; ColBERT rerank is opt-in at the skill-call layer.

Record this so the evals plan does not chase a configurable-rerank toggle that does not exist in the DB schema.

### A.4 Collection names (from `resolve-dataset-context-by-ref!`)

Already resolved while verifying the config-get flag; recorded here for reproducibility:

- `:docs-collection` `website_documents_ab897fbdedfa`
- `:chunks-collection` `website_chunks_ab897fbdedfa`
- `:phrases-collection` `website_phrases_ab897fbdedfa`
- `:collection-prefix` `website_`
- `:dataset-node-id` `dataset/digdir/public-docs/default`

Dataset-level config is minimal — only the collection names are stored at the dataset node. All tuning happens at `runtime/digdir/default` or at the skill-code level.

### A.5 Implications for the evals plan

- No per-dataset rerank tuning exists today. The baseline will be recorded against the runtime `default` node + the two strategy overrides.
- Any future `skills.*` tuning that targets `public-docs` specifically should go through a new `runtime/digdir/<some-key>` node with `:dataset-id "public-docs"` scoping, not by editing `runtime/digdir/default` (which affects other datasets).
- The contract's "required-before-merge" trigger (requirements §1.4) fires on changes to `runtime/digdir/default` *and* any future `public-docs`-scoped runtime override.

## Appendix B — V1 Pivot Record (2026-04-20)

Live inspection of `typesense-test.digdir.cloud:443` against the collections resolved from the dataset node `dataset/digdir/public-docs/default` returned:

- 62 docs / 177 chunks / 1387 phrases, **all Norwegian, all Altinn Docs** (100% `/nb/` URLs rooted in `authorization`, `altinn-studio`, `broker`, `dialogporten`, `correspondence`, and related Altinn topics).
- Zero `docs.digdir.no` content.
- Zero English content.

The `digdir-docs` pipeline is registered on the dataset (per §1.4 topology bootstrap and `pipeline/integration_test.clj:289`), but no documents from that pipeline are present in the materialized collections at the snapshot time. Whether this reflects a pending materialization run, a crawl cap, or a disabled pipeline is not determined by this report.

### B.1 Decision recorded

User accepted (2026-04-20): proceed with v1 targeting the actual corpus. Deferred to v2 / later scope:

- `:source-slice :digdir-docs` gate.
- `:language :en` gate.
- `:language :nn` representation.
- Bilingual pair reporting.
- Source-balanced case counts (contract Decision 3 mandatory-slice rule).

### B.2 Affected plan documents

The following must be revised in concert with this report:

- `claude-public-docs-eval-contract.md` — Decision 3 (gated scope) relaxed for v1.
- `claude-public-docs-evals-requirements.md` — §1.3 (test-set shape), Decisions 7 & 8 noted as deferred.
- `claude-public-docs-suite-specs.md` — §2.2 slice minimum reduced to single-slice for v1.
- `claude-public-docs-query-inventory.md` — rewritten from the concrete 62-doc content.
- `claude-public-docs-eval-ops.md` — drift handlings §4.2 reduces to aggregate while single-source.

V2 restoration criteria (when any future materialization run adds Digdir or English content): revert the v1 relaxations in one coordinated PR, re-baseline, and re-promote the v2 inventory.
