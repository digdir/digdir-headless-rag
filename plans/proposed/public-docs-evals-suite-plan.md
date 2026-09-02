# public-docs Evals Suite — Analysis & Requirements Plan

Status: Proposed on 2026-04-18.

## Goal

Lay the groundwork for a dataset-specific evals suite targeting the `public-docs` dataset in the `digdir` tenant. This plan covers two preparatory deliverables; suite implementation is deliberately out of scope here and will be planned separately once both deliverables are signed off.

The two deliverables are:

1. An analysis report of the `public-docs` dataset, capturing corpus shape, retrieval config, usage signals, and known failure modes.
2. A requirements and decision list for the evals suite, built on top of the report so scope choices are grounded in actual dataset characteristics rather than guesses.

## Background — current state of evals in the repo

Relevant surface that already exists (from exploration):

- `digdir.llm.structured-eval` — shared JSON parsing + enum validation for LLM-as-judge outputs (used by `read_signals` and sufficiency classifiers).
- `digdir.tools.diagnostics` (in `src-dev`) — agent budget profiler with golden-chunk tracking. Metrics today: `golden-present`, max-iterations, max-tool-calls, max-chunks. Query-driven via `golden-chunk-ids` + answer regex.
- `digdir.rag.rerank-evaluation-test` — ColBERT score distribution integration test. Scoped to `kudos`, gated by `RUN_RERANK_EVALUATION_INTEGRATION=true`. No recall@k / MRR / nDCG yet.
- No dataset-specific evals exist today. No curated Q/A set for `public-docs` has been located.

Relevant retrieval surface for `public-docs`:

- Resolution: `digdir.api.context/resolve-dataset-context-by-ref!` for `{:tenant "digdir" :dataset-config-key "public-docs"}`.
- Typesense collections: `public_docs_docs`, `public_docs_chunks`, `public_docs_phrases`.
- Pipelines feeding it: `altinn-docs`, `digdir-docs`.
- Retrieval skill: `digdir.skills.builtin.retrieval` — 3-strategy search (phrase / metadata / content) + optional ColBERT rerank, auto-filter, query relaxation.

## Deliverable 1 — `public-docs` dataset analysis report

Target file: `server/docs/evals/public-docs-dataset-report.md`.

Read-only exploration; no code changes.

### Inputs to gather

1. **Dataset shape**
   - Resolve context via `digdir.api.context/resolve-dataset-context-by-ref!`.
   - Record dataset-id, collection prefix (expected `public_docs_`), pipelines feeding it.

2. **Corpus inventory** (from Typesense `public_docs_{docs,chunks,phrases}`)
   - Document count, chunk count.
   - Avg / median / p95 chunk length (tokens and characters).
   - Doc-title distribution, URL / domain breakdown.
   - Language mix (if detectable).
   - SHA / freshness spread — how old is the oldest doc, newest, distribution.

3. **Content character** — sample ~20 chunks across domains
   - Structural traits (markdown headings, tables, code blocks, lists).
   - Entity density (norms, org names, legal references, acronyms).
   - Question-ability: reference vs. narrative vs. FAQ-like.

4. **Retrieval config** — effective `skills.*` config for this dataset
   - Pulled via `digdir.tools.config/get-value`.
   - Retrieval top-k, rerank settings, auto-filter rules, query relaxation, embedder / model choices.

5. **Usage signals** (best-effort)
   - Any query logs or playground conversations scoped to this dataset.
   - Success / failure patterns, if visible.
   - If none surface: flag the gap and skip rather than fabricate.

6. **Known failure modes**
   - Scan recent commits, `plans/`, and open issues touching `public-docs` for recurring complaints (missed retrievals, hallucinations, latency, rerank drift).

### Report structure

One section per input above, plus a closing **Implications for evals** section that bridges into Deliverable 2 (what the corpus shape suggests about which metrics matter, test-set size, judge model choice, etc.).

## Deliverable 2 — evals suite requirements & decisions

Target file: `server/docs/evals/public-docs-evals-requirements.md`.

Produced after Deliverable 1 so decisions are grounded in observed dataset characteristics.

### Requirements section — what the suite must do

- **Scope**: retrieval-only vs. end-to-end agent vs. both.
- **Metric set**: recall@k, MRR, nDCG, golden-chunk-present, answer correctness (LLM judge), cost, latency.
- **Test-set shape & size**: curated Q/A, synthetic from corpus, replayed from logs, or a mix.
- **Mode**: regression (CI-gated), scheduled, on-demand REPL only.
- **Framework posture**: per-dataset fixtures on a shared runner, vs. bespoke one-off for public-docs.
- **Reproducibility**: deterministic seeds, pinned models, snapshot outputs.

### Decisions list — each entry frames options, recommendation, and remaining open question

- Judge model (Haiku vs. Sonnet vs. Opus) — cost vs. accuracy.
- Golden set construction — hand-curated at size N, LLM-generated from chunks, log-mined, or hybrid.
- Storage location — EDN fixtures in repo, Datahike, or external.
- Runner surface — `bb eval:public-docs`, test namespace, admin UI page.
- Reuse strategy for existing pieces — `digdir.llm.structured-eval`, `digdir.tools.diagnostics` golden-chunk harness, rerank-evaluation-test patterns.
- Baseline snapshot strategy — how regressions are detected over time.

## Open questions to resolve before starting

These sharpen scope and should be answered before work begins on Deliverable 1:

1. **Environment access** — can the report use live Typesense + config DB, or should corpus-stat sections be placeholders for the user to fill in?
2. **Query logs** — do playground or production conversations for `public-docs` exist and are mineable, or is this a cold start?
3. **Primary failure to catch** — retrieval misses, hallucinated answers, latency regressions, or rerank drift? (Shapes which metrics go first.)
4. **Generic vs. dataset-specific** — is `public-docs` the first instance of a reusable framework, or a one-off tailored suite?
5. **Output format** — markdown docs under `server/docs/evals/` as proposed, or something else (Notion, issues, EDN)?

## Out of scope for this plan

- Implementing the evals suite itself.
- Building a golden Q/A set.
- Any changes to retrieval, rerank, or agent code.
- Changes to CI configuration.

A follow-up implementation plan will be proposed once Deliverable 2 is signed off.

## Sequencing

1. Resolve the five open questions above.
2. Produce Deliverable 1 (dataset report).
3. Produce Deliverable 2 (requirements & decisions), referencing Deliverable 1.
4. Review with user; on sign-off, spin up a separate implementation plan.
