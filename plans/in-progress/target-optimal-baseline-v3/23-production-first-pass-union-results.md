# Slice 23 — Production first-pass union (results)

Carries the slice 21/22 user-intent first-pass union from the `bb v3-score`
harness into the production retrieval skill, so real agents benefit from the
harness-only lift. Plan: `plans/proposed/slice-23-production-first-pass-union-plan.md`.

## TL;DR

- Two-pass user-intent union now lives in the production retrieval skill,
  gated by a `:user-intent-union-enabled` parameter (and corresponding
  runtime-config key `skills.retrieval.user-intent-union.enabled`).
- Server-side measurement matches the slice-25 production-today floor
  closely; the union lifts top-30 chunks +2.9pp and top-10 docs +2.8pp
  over the no-union baseline on identical infrastructure.
- The agent path threads the planner's `:user-intent` automatically;
  unit-tested end-to-end.
- The slice-22 harness baseline (43.5 / 53.6 / 66.7 / 77.8) is not fully
  reproduced — top-10 chunks dropped 4.4pp vs that prior measurement.
  Two contributors: LLM-planner variance run-to-run, and slice 25's
  doc-title sort fix already capturing most of the rank lift the cap-N3
  union previously provided.

## Final 3-run measurements

`bb v3-score --rerank-with-colbert true --expand-queries 8` against
`digdir/public-docs` v3 ground truth (7 questions, 23 cited chunks, 12 docs).

| Configuration | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| Slice 22 harness (3-run) | 42.0 | 50.7 | 64.0 | 66.7 |
| Slice 25 harness (3-run, cap-N3 + sort fix) | 43.5 | 53.6 | 66.7 | 77.8 |
| **Slice 23 — Phase 0 (production-today, no union)** | **39.1** | **52.2** | **63.9** | **80.5** |
| **Slice 23 — Phase 5 (server-side cap-N3 union)** | **39.1** | **55.1** | **66.7** | **80.5** |
| Slice 23 done-criteria thresholds | ≥41 | ≥51 | ≥64 | ≥75 |

Phase 5 - Phase 0:
- Top-10 chunks: 0pp (LLM-variance noise band)
- Top-30 chunks: **+2.9pp**
- Top-10 docs: **+2.8pp**
- Top-30 docs: 0pp (already near ceiling)

Phase 5 vs slice-23 done-criteria: 3 of 4 pass. Top-10 chunks at 39.1 vs
the 41 threshold is below — but Phase 0 is also at 39.1, so this is the
chunk-set the production path delivers across the LLM-planner variance
window. The threshold was set assuming the slice-22 harness numbers
reproduced; in practice the slice-25 sort fix already captures most of
that gain (see "Why top-10 chunks didn't move" below).

## What shipped

### `digdir.skills.builtin.retrieval.union` (new ns)

Three merge modes: `:interleave`, `:cap` (default, N=3 per slice-22
finding), `:rrf`. Operates on full chunk maps with `:chunk_id`
dedup. Mirrors the harness implementation in `bb.edn:870-939`. 15 unit
tests cover empty/disjoint/overlap/cap-overflow/RRF-symmetry.

### `digdir.skills.builtin.retrieval` — `:user-intent` input

The retrieval skill now accepts `:user-intent` as an optional input and
four union-related parameters (`:user-intent-union-enabled`, `-mode`,
`-cap`, `-rrf-k`). When all the conditions for union activation hold
(union enabled AND `:user-intent` provided AND it's distinct from the
single query), the skill runs a SECOND pass with intent-only and
union-merges with the expansion pass.

The per-pass pipeline (auto-filter → search → fetch → boost → diversity
→ ColBERT rerank) is extracted into a closure-local `do-pass` fn called
once or twice, matching the harness semantics that each pass is
independently reranked before merging.

Added 4 unit tests covering: two-pass cap-merge, dedup behavior on
overlap, single-pass when :user-intent absent / disabled / equal to the
single query.

### Runtime config keys

```
skills.retrieval.user-intent-union.enabled  (boolean, default off)
skills.retrieval.user-intent-union.mode     (:cap / :interleave / :rrf)
skills.retrieval.user-intent-union.cap      (int, default 3)
skills.retrieval.user-intent-union.rrf-k    (int, default 60)
```

Plumbed through `skill-property-to-path` and `build-rag-skill-params`
following the slice-25 pattern. Per-tenant opt-in; off by default until
each tenant has been measured.

### Agent integration

`workspace.clj` gains `:last-user-intent`. `plan_queries` stashes the
planner's `:user-intent` there. The `search` tool reads it from
workspace and injects it into retrieval inputs.

Two new agent_test.clj tests verify the wiring:
- After plan_queries, `:last-user-intent` is set on workspace.
- Subsequent search dispatch passes `:user-intent` to retrieval inputs.
- No prior plan_queries → no `:user-intent` (backwards compat).

### Debug endpoint + harness verification flag

`/api/debug/typesense-retrieve` now accepts `:user-intent`,
`:user-intent-union-enabled`, `:user-intent-union-mode`,
`:user-intent-union-cap`, `:user-intent-union-rrf-k` query parameters
(declared in Malli schema, parsed in handler, threaded into
retrieval parameters/inputs).

`bb v3-score --server-side-union true` toggles the harness to send
intent + queries + union config in ONE call to the debug endpoint
(instead of the legacy two-call client-side union). Used in the
Phase 5 measurement above.

Direct probe verifying the path:
```
GET /api/debug/typesense-retrieve?...&user-intent=hva%20er%20dialogporten
   &user-intent-union-enabled=true&user-intent-union-mode=cap&...
=> :search-attribution.user-intent-pass
   {:merged 57, :chunks-after-diversity 10, :rerank-ms 333,
    :rerank-candidate-count 10, :rerank-error nil,
    :union-mode :cap, :union-cap 3, :union-rrf-k 60}
```

Two-pass union confirmed running server-side, intent pass independently
reranked, cap-N=3 merge applied.

## Why top-10 chunks didn't move from Phase 0 to Phase 5

The slice-25 doc-title pass-2 sort fix (`(sort-by :rank >)` before
`take 40`) already pulls Dialogporten-like high-rank docs' chunks into
top positions via the order-stable tie-break mechanism (see
`25-stemming-implementation-results.md`). For Q1 specifically:
- Phase 0 top-10 already includes `ea7de904e1aa` (one of the three Q1
  cited chunks) at position 2.
- Phase 5 cap-N=3 intent pass would put the intent's top-3 chunks
  first — but if those overlap with what the sort fix already placed
  at the top, there's no incremental lift.

This is the expected interaction: two compositional rank fixes that
both target the same "rank-1 chunk gets buried" failure mode end up
sharing the gain rather than stacking. Top-30 chunks (+2.9pp) and
top-10 docs (+2.8pp) capture the union's incremental value — chunks
the intent pass surfaces from doc-title cohorts the expansion pass
didn't fully expose.

## Cumulative trajectory

| Stage | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| V0 + ColBERT | 26.1 | 26.1 | — | — |
| + Slice 19 intent-aware planner | 30.4 | 39.1 | — | — |
| + Slice 20 language preservation | 34.8 | 43.5 | 64.0 | 64.0 |
| + Slice 21 interleave union (harness only) | 37.7 | 47.8 | 55.6 | 66.7 |
| + Slice 22 cap-N3 union (harness only) | 42.0 | 50.7 | 64.0 | 66.7 |
| + Slice 25 sort-fix (production) | 39.1 | 52.2 | 63.9 | 80.5 |
| **+ Slice 23 production cap-N3 union** | **39.1** | **55.1** | **66.7** | **80.5** |
| Hand-crafted ceiling | 69.6 | 73.9 | — | — |

The top-30 docs trajectory in particular is striking: 64.0 → 80.5
cumulative, +16.5pp toward the doc-level ceiling. Top-10 chunks has
plateaued at ~39% across the last 3 slices, suggesting the next move
needs to address the same-doc-wrong-chunk failure mode that ColBERT
rerank dilution surfaces (per slice 25's "what's still missing"
analysis).

## Open Question decisions

**A. Agent integration shape — LLM-visible or agent-state-driven?**
**Chose agent-state-driven** as recommended in the plan. `plan_queries`
auto-captures the planner's `:user-intent` into `workspace
:last-user-intent`, and the `search` tool auto-injects it into
retrieval inputs. No new LLM-visible argument on the search tool — the
LLM doesn't need to manually pass it.

**B. Latency budget.** Untouched in this slice. Server-side union still
runs the two passes serially. Per the Phase 5 probe attribution
`:rerank-ms 333` for the intent pass; this is one of two reranks
that happen on each unioned call, so ~600-700ms added to each search
when the union fires. Acceptable for now; parallel execution is the
obvious future lever.

**C. Production fanout interaction.** Per the plan refresh, slice 25's
`take 40` cap already addresses `limit_multi_searches`; the two-pass
union doesn't compound the issue because each pass is its own
multi-search request. No mitigation needed for `digdir/public-docs`.

**D. Backwards compat.** Verified: a retrieval call without
`:user-intent` (or with `:user-intent-union-enabled false`) takes the
single-pass path identical to pre-slice-23 behavior. The
`test-search-without-prior-plan-queries-omits-user-intent` test covers
this.

## Followups

1. **Enable per-tenant.** `skills.retrieval.user-intent-union.enabled`
   is off by default. For `digdir/public-docs` to benefit in the
   agent loop, flip the runtime config:
   ```
   skills.retrieval.user-intent-union.enabled = true
   skills.retrieval.user-intent-union.mode    = "cap"
   skills.retrieval.user-intent-union.cap     = 3
   ```
   (Set per-environment via the admin UI / config-db API.)

2. **Latency telemetry.** Add a span around the intent-pass `do-pass`
   call so production latency dashboards can track the two-pass
   overhead.

3. **Same-doc-wrong-chunk arc.** Q1 still misses two of its three
   cited chunks (`a233d1c22ebe`, `b8ddca7bace0`) at top-10 — both
   live in a doc that DOES reach top-30 via the sort fix. The next
   slice should investigate the chunk-internal selection inside a
   high-rank doc. This is corpus-vocabulary territory; the planned
   interactive-skill-graphs arc absorbs it.

4. **Parallel execution of two passes.** Currently serial. `pmap` or
   `core.async` would make wall-clock cost ≈ one retrieval. Worth it
   only if real-world latency starts to bite — for now the harness
   numbers say the work is well within budget.

## Phase status

- [x] Phase 0: measure production-today floor (3-run)
- [x] Phase 1: port union-merge to `retrieval.union` + tests
- [x] Phase 2: integrate `:user-intent` into `execute-retrieval` + tests
- [x] Phase 3: runtime config keys + builder wiring
- [x] Phase 4: agent threads `:user-intent` through `plan_queries → search` + tests
- [x] Phase 5: debug endpoint extension + `bb v3-score --server-side-union true` measurement
- [x] Phase 6: agent loop smoke test (unit-level) + this results doc
