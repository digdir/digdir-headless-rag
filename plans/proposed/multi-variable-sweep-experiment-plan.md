# Multi-Variable Sweep — "data picks the agent"

## Goal

Demonstrate the data-driven thesis of the platform by running systematic
config sweeps on a curated question set, producing a leaderboard, and
showing that the configuration that wins on the benchmark also wins
user-visibly in Open WebUI.

The thesis: *we should not be hand-picking agent + skill-graph + prompt
choices through intuition. We have the eval-suite (thermometer), the
runtime-config knobs, multiple enrichment variants, and an E2E harness
that can drive Open WebUI end-to-end. Composing those into a "sweep,
score, deploy" workflow is what closes the loop.*

This plan is deliberately infrastructure-light. Nearly every piece
already exists; the work is composition, curation, and analysis.

## Why this experiment, why now

D2 just landed — the matrix is phrases ✓, facts ✓, questions ✓. The
plan asks "should we do D3 next?" but flags D3 as the riskiest, with
murky verify-retrieval signal. Before adding a fourth enrichment type
we want to know which of the existing three are pulling their weight,
and which combinations win. A sweep tells us that directly.

The E2E harness landing this session is the missing piece: the same
seeded stack that runs the benchmark can also surface the winning
config through Open WebUI for a demo. The story becomes "the
data picked this; here's the user experience it produces."

## Variables to sweep

A naive cartesian product across every knob is intractable. The
sweep proceeds in three rounds, each round narrowing the search
space using the previous round's signal.

### Round 1 — One factor at a time (OFAT) baselines

Each row varies one dimension while holding everything else at a
fixed control. Goal: rank dimensions by impact magnitude before
investing in 2D+ sweeps.

| Dimension                | Levels                                                                              | Rationale |
|--------------------------|-------------------------------------------------------------------------------------|---|
| Enrichments active       | none / phrases / facts / questions / phrases+facts / all-three                      | Direct test of "do enrichments earn their keep?" |
| Skill graph              | `agent-rag-graph-bundled` / `agent-rag-graph-faithful` / `fact-checker`             | Topology comparison; tests if `:report`-emitting graphs help on benchmark queries |
| Synthesis model          | `gpt-5.4-mini` / `gpt-4o` (or whatever's available)                                 | Cost/quality tradeoff at the answer stage |
| Rerank top-k             | 10 / 20 / 40 / 80                                                                   | Context-window pressure vs. recall |
| Retrieval strategy weights | balanced / phrase-heavy / metadata-heavy / content-heavy                          | Tests whether one strategy carries most of the signal |
| Context relative-score threshold | 0.70 / 0.85 / 0.95                                                          | How aggressively to trim before synthesis |

Total Round-1 runs: ~25 configs × N queries × M repeats.

### Round 2 — 2D sweeps in the high-impact regions

Take the top-3 dimensions Round 1 identified as impactful and cross
them pairwise. Typical structure:

```
(enrichment-type × rerank-top-k)
(enrichment-type × synthesis-model)
(rerank-top-k × strategy-weights)
```

Total Round-2 runs: 3 pairs × ~16 cells × N queries × M repeats.

### Round 3 — Prompt variants on the winning config

The D2.21 discriminative-prompt rework is unconfirmed on
propose-questions and propose-facts. Round 3 holds the winning
config from Round 2 and varies the propose-* prompts:

- Original (topical) vs D2.21-style (discriminative)
- For each of propose-phrases / propose-facts / propose-questions

Total Round-3 runs: 6 configs × N queries × M repeats.

## Question set

Round 1 needs a curated benchmark. Quality of the entire experiment
depends on this set being representative — bad questions produce
bad rankings, no matter how clean the sweep.

**Target**: 30–50 questions, drawn from the public-docs corpus, with
each question carrying:

- `:query` — the user question
- `:expected-chunks` — the chunk-ids that should appear in retrieved context (1–5 each)
- `:expected-answer-substrings` — durable phrases the answer must contain
- `:tags` — `#{:simple :compound :temporal :compare :followup ...}` for slicing the leaderboard

Sources:
1. Mine `digdir.demo/altinn_*` and existing eval suites — there's
   already a `:builtin/enrichment-eval-suite` driving
   `agent-budget-benchmark` traces. Lift its question list.
2. Sample real user queries from playground conversations (the
   Datahike DB has them). Anonymize, deduplicate.
3. Author 5–10 deliberately hard queries that we expect to *fail*
   in the baseline so improvements are visible — followups,
   compound asks, ones requiring cross-doc synthesis.

Curating the question set is **the highest-leverage piece of work
in this plan** and the easiest to under-invest in. Budget a half-day
to ~one day on this alone; reuse for every subsequent experiment.

## Metrics

Each (config, query) run produces a record with:

| Metric                  | What it measures |
|-------------------------|--------------------------------------------------|
| `recall@k`              | Fraction of `:expected-chunks` that appear in the top-k retrieval result. Computed at k=5, 10, 20. |
| `answer-substring-hit`  | Boolean — does the synthesized answer contain ≥1 `:expected-answer-substrings`. |
| `answer-grounded?`      | LLM-as-judge: does the answer cite chunks that justify the claim? Use a small rubric prompt. |
| `latency-ms`            | Wall-clock for the full agent loop |
| `tokens-total`          | Prompt + completion tokens summed across all LLM calls |
| `iterations`            | Number of agent-loop iterations to terminal |

Leaderboard reports the **mean ± stderr** of each metric over the
question set, sliced by `:tags`. We need to know whether one config
wins on *every* slice or just averages out well — the latter is
much less convincing.

Repeats: `M=3` per (config, query) to control LLM non-determinism.
Use a fixed seed when the model allows; otherwise average.

## Phases

### Phase S0 — Question set curation (~1 day)

- Lift existing eval-suite questions into a structured EDN/JSON
  file under `server/test/fixtures/sweep/questions.edn`.
- Add 5–10 hard queries; tag everything.
- Write a small validator: load the file, assert all expected
  chunk-ids resolve in the dev dataset, fail loudly on dangling
  refs.

### Phase S1 — Sweep runner (~1 day)

A Clojure script (not a `bb` task) that:

1. Takes a config matrix (EDN) describing the variables to sweep.
2. For each cell in the matrix × question × repeat, invokes
   `digdir.skills.invoke/invoke-rag` (the canonical entry point —
   already shared by Playground + MCP) with the cell's parameters
   patched into runtime config.
3. Captures the result map (response, chunks, metadata, timings).
4. Writes one row per run to a CSV / Parquet / Datahike collection.

Key call site: `invoke-rag` accepts `:skill-params` and
`:execution-scope` — both are knobs we can drive from the matrix.
For dimensions that live in the runtime-config DB (like rerank
top-k), we either pre-write them via `bb config-set` or, cleaner,
extend the runner to write per-run overrides.

### Phase S2 — Round-1 OFAT sweep (~half-day execution)

Run the OFAT matrix from above against the question set. Output:

- Per-run record CSV in `server/results/sweep-<timestamp>/runs.csv`
- A summary Markdown in `server/results/sweep-<timestamp>/REPORT.md`
  with per-dimension impact tables.

### Phase S3 — Round-2 2D sweeps (~half-day execution)

Pick the top-3 dimensions from S2. Run the 2D matrices. Same
output shape.

### Phase S4 — Round-3 prompt variants (~half-day execution)

Hold the Round-2 winner fixed; vary propose-* prompts. Confirms or
refutes the D2.21 discriminative-prompt hypothesis for the other
two enrichment types.

### Phase S5 — Demo flow through Open WebUI (~half-day)

Configure the E2E stack to use the winning agent + enrichments.
Walk through 3–5 of the curated questions in Open WebUI, screenshot
each turn. Compare side-by-side to the baseline config on the same
queries. Record a screencap.

This is the artifact for the "data picked the agent" story.

## Infrastructure inventory

What we already have:

| Piece                                                 | Status | Notes |
|-------------------------------------------------------|--------|-------|
| `digdir.skills.invoke/invoke-rag`                     | ✓ | Canonical entry point; both Playground + MCP go through it |
| Eval-suite (`agent-budget-benchmark`)                 | ✓ | Phase A — running today inside the self-improve agent |
| Three enrichment types (phrases / facts / questions)  | ✓ | D1, D2 done |
| Runtime-config-driven knobs                           | ✓ | Most dimensions in the table are already configurable |
| E2E harness (Docker + Playwright)                     | ✓ | Landed earlier this session |
| Boot-time auto-seed for tenants + Azure config        | ✓ | Per the just-committed work |

What we need to build:

- Question set fixture (Phase S0)
- Sweep runner script (Phase S1)
- Leaderboard / summary report generator (small Clojure utility, part of S2)
- Per-run config-override mechanism (likely a thin wrapper that calls `bb config-set` between runs, or an in-process equivalent)

What we explicitly do NOT need:

- New skills or skill graphs
- New agents
- New retrieval paths
- New evaluation infrastructure

The whole experiment is composition of existing pieces.

## Risks

- **Question-set bias.** If the questions don't span the real
  user-traffic distribution, the leaderboard ranks for the wrong
  thing. Mitigation: sample real playground queries; review the set
  with someone close to the product before locking it in.

- **Cost blowout.** Each (config, query) pair triggers a full agent
  loop. ~25 Round-1 configs × 40 questions × 3 repeats = 3,000 runs.
  At ~30s each that's ~25 hours of wall clock and a non-trivial
  token bill. Mitigations: smaller question set in pilot,
  parallelize the runner, cap iterations more aggressively for
  sweep mode than for prod.

- **LLM-as-judge variance.** The `answer-grounded?` metric depends
  on a judge model that's itself noisy. Use a strong model
  (gpt-4o-class) for judging, multiple judges per answer, and
  report agreement rate alongside the score.

- **Confounders.** Two dimensions varying together because they're
  coupled in code (e.g., changing skill graph also changes default
  prompts). Audit each cell's effective config before running;
  log it; flag deltas.

- **Selecting on noise.** With ~30 questions and 3 repeats, the
  per-config sample size is small. Small effect sizes may be
  noise. Report stderr; require ≥2σ separation before declaring
  a winner.

## Success criteria

By the end:

1. A persisted leaderboard (CSV + REPORT.md) showing per-dimension
   impact, with stderr, sliced by question tag.
2. A named "best" configuration and a clear delta-over-baseline
   number (`+18% recall@10, -22% latency, etc.`).
3. A user-visible demo through Open WebUI that subjectively reads as
   better on at least 3 example queries.
4. A finding that informs the D3 decision: do the existing
   enrichments earn their keep at all, and which one is doing most
   of the work?

## What success enables next

If the sweep confirms one or two enrichment types dominate, we have
a strong empirical case for D3 (or its rejection). The methodology
and tooling become reusable: every future agent or skill change
runs through the same sweep harness before shipping.

## Non-goals

- **Replacing the eval-suite.** This sweep *uses* the eval-suite;
  it doesn't reinvent it.
- **Production traffic.** Sweeps run on the dev/E2E stack against
  the curated question set, not against real user queries.
- **Auto-tuning loops.** Phase E in the self-improve plan covers
  that. The sweep is offline analysis; auto-tuning is online
  control. They share the eval-suite but are different artifacts.

## File map (anticipated)

- New: `server/test/fixtures/sweep/questions.edn`
- New: `server/src-dev/digdir/sweep/runner.clj`
- New: `server/src-dev/digdir/sweep/report.clj`
- New: `bb sweep:run` task that wires the runner with a default matrix
- New: `server/results/sweep-<timestamp>/{runs.csv,REPORT.md}` (gitignored; results published as PR comments or a separate artifact branch)
- Edited: `server/e2e/.env.example` if the demo flow needs additional config

## Open design questions

To resolve at the Phase S1 boundary, not now:

1. Where do per-run config overrides live — in-memory inside the
   runner, or via `bb config-set` calls? In-memory is faster but
   means the runner has to know about every config key it might
   set.
2. Storage format for results — CSV, EDN, Parquet, or Datahike?
   CSV is simplest; Datahike lets us query historically.
3. Question-set versioning — when we tweak the set later (and we
   will), how do we keep old leaderboards comparable?
