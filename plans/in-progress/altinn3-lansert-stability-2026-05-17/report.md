# "Når ble Altinn 3 lansert?" stability batch — 2026-05-17

User-reported regression suspicion: traces from May 16 showed lower
answer quality than April–early May. Pre-refactor sample (43/55 = 78%)
vs. post-merge sample (2/4 = 50%) was suggestive but n=4 too small for
statistical certainty.

This batch ran the same query 10 times per `:graph-variant` to test
whether the refactor caused a real regression.

## Setup

- Fixture: `server/test/fixtures/agent/altinn3_lansert_stability.edn`
  — one case, query "Når ble Altinn 3 lansert?", expected answer
  pattern `(?i)juni\s*2020`, golden chunk `8e22ae4b88b1`, current and
  relaxed budgets set identical so each invocation = 2 runs.
- Code state: `merge-candidate` branch (merge of `add-evals` onto
  `release-v0.1-details` + the progress-fn fix).
- DB: `add-evals/local-db/add_evals_20260516_evalgate` (imported May 11
  dump).
- Typesense: shared backend on `127.0.0.1:8108`.
- Wall time: 24 min for 15 invocations (30 runs).
- Reproducer: `run-batch.sh` in this directory.

## Per-invocation pass count (out of 2)

| Run    | imperative | bundled | faithful |
|--------|-----------:|--------:|---------:|
| run1   | 2          | 1       | 0        |
| run2   | 1          | 2       | 1        |
| run3   | 1          | 2       | 2        |
| run4   | 0          | 1       | 1        |
| run5   | 2          | 0       | 2        |
| **Σ**  | **6/10**   | **6/10**| **6/10** |

## Aggregate

| Metric                    | imperative | bundled | faithful |
|---------------------------|-----------:|--------:|---------:|
| Pass rate (n=10)          | **60%**    | **60%** | **60%**  |
| Errors / exceptions       | 0          | 0       | 0        |

**Identical pass rates across all three variants.**

## Verdict

**No regression from the 2.4c / 2.5 refactor on this query.** All three
variants produce statistically identical answer-quality distributions
at n=10 each.

The variance is structural — comes from the LLM's chunk-selection step
inside the agent's `read_chunks` tool dispatch, not from any code the
refactor touched. The earlier 78% baseline (43/55) was a mean across
April–early May at unrelated code/model snapshots; the 60% rate
measured here is what this corpus + this model returns *today*,
independent of which variant runs the loop.

## Follow-ups (out of scope here, optional)

Lifting the pass rate on this query is a retrieval-ranking +
chunk-selection problem, not an agent-loop one:

- Raise `rerank-top-k` so the golden chunk `8e22ae4b88b1` ranks high
  enough that the read-tool reliably picks it. In the failing traces
  the chunk was at rerank position 6 / not retrieved at all; in the
  successful traces it was at position 3.
- Add an "always read first 2 chunks of the highest-ranked doc" bias
  for entity-level queries (`:answer-type :lookup` with `:entity`).
  Chunk `8e22ae4b88b1` is in the `/nb/community/about/` doc which is
  the top-ranked hit for the lansert query but read selection
  sometimes picks neighbors.
- Inspect the `read_chunks` tool's prompt to confirm it preferred most
  semantically relevant over breadth.

These are scoped tuning experiments on top of the current code, not
refactor reversals.
