# ColBERT Rerank Scaling Plan

## Objective
Increase effective rerank candidate depth and answer quality without exceeding ColBERT API limits or regressing latency.

## What We Observed (Live)
- `bb rerank-eval` now runs successfully with Typesense reachable.
- `bb rerank-isolation-eval` currently fails quality gates for top-100 fixture pools:
  - filtered top100: golden `6a80d6499075` moved `38 -> 32` (expected `<= 10`)
  - unfiltered top100: golden `6a80d6499075` moved `72 -> 28` (expected `<= 10`)
- Deterministic sweep from one retrieval snapshot (`/tmp/rerank-debug/retrieve.edn`):
  - `top-k=40`: `792beec82d38` rank `13`
  - `top-k=80`: `792beec82d38` rank `22`
  - `top-k=100`: `792beec82d38` rank `25`
  - `top-k=120`: ColBERT API returns `422`
- Relative-score behavior (same snapshot, top score `19.171875`):
  - `>=90%` top score drops golden chunk
  - `>=85%` keeps golden chunk with significantly smaller set than full tail

Implication: candidate depth alone is not enough; depth must be paired with better candidate composition and score-aware context assembly.

## Track #1: Baseline and Gates
### Deliverables
- Add a small benchmark harness that records per-query metrics from `retrieve-debug` + `rerank-debug`.
- Persist JSON/EDN snapshots for baseline and each experiment.

### Metrics
- Retrieval: candidate count, org entropy, doc diversity, golden present/absent.
- Rerank: `MRR@10`, `Recall@10/30`, golden rank delta, score spread (`top1`, `top10`, `top1-top10`).
- Context: golden in context (`yes/no`, position), context token/char budget.
- Runtime: p50/p95 latency for retrieval, rerank, full request.
- Reliability: non-200 rates (especially 422/5xx from ColBERT).

### Gates
- No regression in `Recall@30` and golden context inclusion on benchmark queries.
- p95 latency increase capped by agreed SLO.
- API error rate non-increasing.

## Track #3: Improve Pre-Rerank Candidate Quality
### Goal
Use the extra depth budget for better candidates, not just more noise.

### Changes
1. Add query-aware prior to merge ordering:
- boost candidates with org/title overlap against query phrases
- keep existing hit-count/rank signals, but add a small additive prior

2. Add diversity cap before `retrieve-chunks-by-id`:
- cap chunks per `doc_num` in pre-rerank pool (e.g. max 2-3)
- optional cap per organization field if available

3. Keep pool size configurable by rerank budget:
- `retrieve-top-k` should be explicitly driven by desired rerank depth profile

### Acceptance
- Better golden pre-rerank position distribution.
- Higher unique-doc coverage in top-N candidates.
- Same or better rerank/context inclusion outcomes.

## Track #4: Adaptive Post-Rerank Context Selection
### Goal
Use ColBERT scores to avoid weak tail chunks in context.

### Strategy
- Replace fixed-only context selection with hybrid policy:
  - hard floor: at least `min_context_k` (e.g. 8)
  - relative threshold: keep chunks with score >= `alpha * top_score` (start `alpha=0.85`)
  - hard cap: never exceed `max_context_k`

### Why 0.85 first
- In live snapshot, `0.9` removed golden chunk.
- `0.85` kept golden chunk while pruning substantial tail.

### Acceptance
- Context precision improves (manual/LLM-judge sample).
- Golden inclusion does not regress.
- Prompt size decreases or stays flat.

## Track #5: Retrieval-Filter and Rerank Coupling
### Goal
Combine precision from filters with recall from broad search.

### Strategy
- Dual-path retrieval when auto-filter has confidence:
  - Path A: filtered retrieval (precision)
  - Path B: unfiltered retrieval (recall safety)
- Union + dedupe + diversity cap, then rerank once.
- If filtered path returns sparse/empty results, automatically increase B budget.

### Acceptance
- Fewer off-domain chunks in rerank input for org-specific queries.
- No increase in misses for ambiguous/no-filter queries.

## Parallel Rerank Design (New)
### Problem
- Current ColBERT call appears capped (`k > 100` returns `422`).
- Single-call strategy limits effective candidate depth.

### Design
1. Shard candidates into rerank batches by `k` and payload budget:
- batch size <= 100 docs
- optional char/token ceiling per batch

2. Execute ColBERT calls in parallel (bounded concurrency, e.g. 2-4).

3. Normalize scores across shards before final merge:
- default: min-max z-normalization per shard
- fallback: rank-based reciprocal fusion when score calibration is unstable

4. Fuse shard outputs into one global ranked list.

### Risks
- Raw scores may not be directly comparable across shards.
- Parallel fan-out can spike p95 and error rate if unbounded.

### Mitigations
- Start with 2 shards and compare:
  - baseline single-call@100
  - parallel 2x100 fused@200
- Add retries/backoff and circuit breaker on ColBERT errors.
- Keep feature-flagged rollout.

## Suggested Execution Order
1. Implement Track #1 benchmark + gates.
2. Implement Track #3 candidate composition improvements.
3. Implement Track #4 adaptive context policy.
4. Implement parallel rerank path behind flag.
5. Implement Track #5 dual-path retrieval-filter coupling.

## Immediate Next Experiments
1. Run A/B on 20-30 queries:
- Baseline: current (`top-k=40`, fixed context top-k)
- Variant A: `top-k=100` only
- Variant B: `top-k=100` + doc diversity cap
- Variant C: Variant B + adaptive context (`alpha=0.85`)

2. Run parallel rerank prototype on same set:
- 2x50 and 2x100 shards, fused ranking
- Compare quality/latency/error against single-call baseline
