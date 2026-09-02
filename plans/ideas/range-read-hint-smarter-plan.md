# Smarter Range-Read Hint Plan

## Goal

Make the agent's runtime range-read hint substantially more accurate at selecting the right document to expand, and avoid burning the read-budget when the hint would point at a doc that's likely wrong or expensive to range-read.

Builds on the dynamic hint machinery added alongside the budget-refund feature. The refund covers us when the hint is wrong; this plan aims to make the hint wrong less often.

## Context

After `read_chunks` completes with a gap-remaining read-signal, `digdir.skills.builtin.agent.workspace/range-read-suggestion` inspects the just-read chunks and, if it finds a multi-chunk doc with unread adjacent chunks that hasn't been range-read yet, appends a copy-pasteable `[SYSTEM: … read_chunks doc_num=X chunk_range={from: 0, to: N-1}]` nudge to the tool-result message.

Trace evidence from multiple 2026-04-17 runs shows the hint:

- **Fires correctly on gap-remaining reads** — happy paths don't trigger it; stressed paths do.
- **Is obeyed by the LLM most of the time** — agent follows the exact `doc_num + chunk_range` tool call on the next iteration in the observed cases.
- **Picks the wrong doc ~50% of the time** — see `agent-trace-2026-04-17T19-42-11-504390Z.txt` (iter 1 hint pointed at `db112a3d8c92` = "Linked Services" when the answer lived in `6bfb44e9124f` = "About"). Post-refund, this still costs 1–2 iterations to recover.

Root cause: `range-read-suggestion` uses `some ... candidates` where candidates is the output of `(group-by :doc-num just-read)`. Effective behavior: pick whichever doc's chunks appear first in the agent's chunk-ids list. Order-dependent, not quality-dependent.

## Problem Statement

The hint mechanism works, but the ranking heuristic does not. We have richer signals in workspace state that go unused:

- `:supported-claims :chunk-ids` on the current read-signal — chunks that actually contributed evidence
- `:retrieval-prior` on each chunk — retrieval-time relevance score
- `:rerank-score` on each chunk (now available post-ColBERT-at-search-time landing) — semantic relevance
- Per-doc read-content-cost estimate — total `:content_length` of unread chunks in the doc
- Remaining read-content-budget

A smarter ranking can reliably pick the "live" doc; a budget gate can suppress hints that would be too expensive even if correct.

## Proposal

Replace `range-read-suggestion`'s "first-in-group-by" selection with a scored ranking, and add a budget-aware gate before emitting the hint.

### Ranking signal (per candidate doc)

Rank candidates by tuple `[supported? semantic-score retrieval-score -cost]` descending:

1. **`supported?`** (boolean) — does any chunk from this doc appear in the current read-signal's `:supported-claims :chunk-ids`? Docs with at least one supporting chunk are strictly preferred.
2. **`semantic-score`** — max `:rerank-score` across the doc's just-read chunks (ColBERT signal). Falls back to 0 if absent.
3. **`retrieval-score`** — max `:retrieval-prior` across the doc's just-read chunks.
4. **`-cost`** — negation of estimated unread-chunk content length (prefer cheaper docs on tiebreak).

Pick the top-ranked candidate.

### Budget gate (after ranking, before emitting)

Estimate the suggested range-read's cost:

```
estimated-cost = Σ content_length of unread chunk_indices in target doc
```

`content_length` is captured in the search-attribution's chunk summaries and/or the workspace `:chunks` map. If unknown, use a conservative per-chunk estimate (e.g. 1500 chars).

Suppress the hint when:

```
(estimated-cost) > (0.3 * remaining-read-content-budget)
```

Rationale: a hint should be cheap insurance, not a high-stakes bet. Skipping the hint costs nothing (refund covers dead chunks; the agent still has its own judgment). Firing a too-expensive hint risks locking the agent into a bad direction — the exact shape that caused `agent-trace-2026-04-17T19-42-11-504390Z.txt` to lose ~7000 chars on a wrong-doc range read.

### Strengthen the hint channel (optional, stage 2)

When the hint does fire, raise its directive weight by either:

- **Option A**: Rephrase from `"before issuing any new search, call read_chunks with these exact args: {...}"` to `"[SYSTEM DIRECTIVE: Your next tool call MUST be: read_chunks {...}. Do not search, plan, or inspect filters before this read.]"`.
- **Option B**: Inject as a `{:role "system"}` message appended after the tool-result messages, rather than an appendix to the tool-result body. System messages read more authoritatively to the LLM.

Observed evidence: the iter-2 agent followed the hint. The iter-6 agent (same trace) ignored an identical hint — likely because after several diverted iterations, earlier reasoning outweighed a tool-result-body nudge. A dedicated system message could break ties in favor of the hint.

Stage 2 is optional; ship the ranking + budget gate first and re-measure.

## Classification Criterion Summary

A range-read hint fires iff:

- Last read was chunk-ids mode (existing condition)
- Some candidate doc has unread adjacent chunks AND hasn't been range-read yet (existing)
- At least one of the doc's just-read chunks appears in `:supported-claims` OR the doc's best rerank-score exceeds a threshold (e.g. 10.0) **(new)**
- Estimated range-read cost ≤ 30% of remaining read-content-budget **(new)**

The second bullet prevents firing on docs the evaluator hasn't attributed any claim to. The third bullet prevents expensive dead-end range reads.

## Implementation Sketch

### `server/src/digdir/skills/builtin/agent/workspace.clj`

Add helpers:

```clojure
(defn- candidate-doc-scores
  "Score each candidate doc for range-read-hint ranking. Returns a map of
   doc-num -> {:supported? :semantic-score :retrieval-score :cost-estimate}."
  [candidate-docs just-read supported-chunk-ids chunks-map]
  ...)

(defn- rank-candidates
  "Sort candidate-docs by (supported?, semantic, retrieval, -cost) desc."
  [candidate-scores]
  ...)

(defn- estimated-unread-content-cost
  "Sum content_length of unread chunk_indices in a doc. Falls back to a
   per-chunk default when length is unknown."
  [workspace doc-num unread-indices]
  ...)
```

Update `range-read-suggestion` to:

1. Build candidate list (as today).
2. Score each candidate.
3. Rank and pick top.
4. If best candidate's estimated cost > 0.3 × remaining-budget, return `nil`.
5. Otherwise return the suggestion map (as today).

### `server/src/digdir/skills/builtin/agent/loop.clj`

No behavior change — `range-read-hint-candidate` consumers already handle `nil`.

Optional stage 2: emit the hint text as a `{:role "system"}` message into the outgoing message list between tool results and the next `call-llm`, instead of embedding in the tool-result body. Requires a small refactor around the `tool-messages` build-up (loop.clj:~790).

### Trace visibility

The existing `stage=range-read-hint` detail already shows `doc_num=… unread=… read=…`. Extend the detail to include the ranking rationale:

```
detail=doc_num=X unread=[0 3] read=[2] rank=supported+rerank=19.8 cost=3200/11000
```

When suppressed due to cost, emit a different stage with a clear reason:

```
stage=range-read-hint-suppressed detail=doc_num=X estimated-cost=7800 remaining-budget=10000 reason=cost-exceeds-threshold
```

## Expected Impact

Trace re-run predictions (against `agent-trace-2026-04-17T19-42-11-504390Z.txt`):

- Iter 1 current hint: `db112a3d8c92` (wrong doc) → new hint picks `6bfb44e9124f` (About, supported by d81ff032aa35).
- Iter 2 range-read hits the right doc → answer chunk surfaces → finalize in 3 iterations.

Happy paths: unchanged (hint still doesn't fire on support-found reads).

Pathological-shape paths: expected cut from ~20s recovery (current post-refund) to ~9–10s (similar to happy path). Post-refund still works as safety net when the ranking is wrong; this plan just makes "wrong" rarer.

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Ranking depends on `:supported-claims` which is populated by an earlier LLM eval — if that call was noisy, ranking is noisy | Low | Fallback tie-breakers (rerank-score, retrieval-prior, cost) still produce a reasonable order without support info. The budget gate prevents worst-case blow-ups. |
| 30% budget threshold too conservative; legitimate range reads get suppressed | Medium | Emit `range-read-hint-suppressed` stage in traces. Tune threshold based on trace data (likely 25–40% range). |
| 30% threshold too loose; still wastes budget on wrong doc | Low | Refund already mitigates. This plan reduces but doesn't need to eliminate wrong-doc hints. |
| Stage-2 system-message injection breaks tool-call sequencing | Medium | Only consider after stage 1 is validated. OpenAI tolerates arbitrary system messages; the risk is only in how the API handles ordering. Test incrementally. |

## Validation Plan

1. **Re-run pathological queries** (3 variants of "Når ble Altinn 3 lansert?" and 2 novel queries where search top ranks include multiple docs). Capture:
   - How often the hint fires
   - How often it's suppressed by the cost gate
   - Doc-number of the fired hint vs. ground-truth answer doc
   - Final iteration count and status
2. **Happy-path regression**: confirm hint still doesn't fire on support-found reads. Median wall-clock within ±200ms of current.
3. **Unit test** `range-read-suggestion` with fixture workspaces:
   - Supported claim from one doc → that doc is picked even when another doc has higher retrieval-prior
   - Two equally-scored docs → cheaper one wins
   - Target doc's estimated cost > 30% remaining budget → returns `nil`

## Out of Scope / Related Work

- **Forcing the agent's next tool call** (intercepting the LLM response and rewriting the tool call to the hinted range-read) — too invasive, breaks agent autonomy. Defer unless ranking+gating prove insufficient.
- **Multi-doc hints** — could point the agent at 2–3 candidate docs. Complex to write and parse. Skip for now.
- **Prompt-caching impact of stronger hint channel** — system messages appended at the end don't invalidate the prefix cache. Safe.
- **Auto-terminate low-yield searches** — separate plan (search returns <3 new chunks → inject "switch strategy" hint). Complementary; lives in its own file.

## Key Files

- `server/src/digdir/skills/builtin/agent/workspace.clj` — `range-read-suggestion`, new helpers
- `server/src/digdir/skills/builtin/agent/loop.clj` — injection site; optional stage-2 system-message path
- `server/test/digdir/skills/builtin/agent/workspace_test.clj` — extend existing test file
- `server/logs/agent-trace-2026-04-17T19-42-11-504390Z.txt` — reference pathological trace for validation

## Suggested PR Shape

1. First commit: ranking + cost-gate + trace detail updates + unit tests. Ship standalone.
2. Second commit (optional, after trace data): stage-2 system-message injection.
