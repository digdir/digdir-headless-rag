# Reassessing prior retrieval arcs with the rerank-truncation fix held constant

**Status:** proposed
**Date:** 2026-06-02

## Premise

The rerank-truncation discovery (`project_rerank_truncation_bottleneck`) means every
prior lever was measured under a reranker that buried long answer-goldens regardless
of the lever — a recall cap on the long-golden subset (21 of 42 questions, golden
>2000 chars). Their negative/flat verdicts may be artifacts of that cap. Now that
Lever A (`:rerank-windowing`) fixes the scoring truncation and is net-positive, we
re-run the prior levers **with windowing held ON** to see their TRUE effect.

Scope (user decision): **long-golden subset only** (the 21 >2000-char questions) —
that's where the contamination was. The ≤1000 clean set is the unchanged control.

## Design

Per lever, isolate its marginal effect ON TOP of the fixed reranker:
- baseline arm = `windowing@2000` (Lever A on, no lever)
- treatment arm = `windowing@2000 + <lever>`
- long-golden 21, **N≥5** (the whole rerank whipsaw was N=3 variance — never go below 5)
- recall@20 + golden-pool/display first (judge off, fast); judged confirm on winners.

## Levers to reassess (priority order)

1. **salient-noun-coverage** (closed NEGATIVE) — `:builtin/query-planner
   {:salient-noun-coverage true}`. Was specifically about pooling the golden, the exact
   thing truncation sabotaged downstream. Best "was-it-masked?" test. **FIRST.**
2. **corpus-vocab slices 23/25** (user-intent-union) — `:user-intent-union-enabled`.
3. **enrichment** (typical-question bridging) — `:enrichment-types`.
4. **read-stage levers** (auto-read / rank-lift) — concluded negative under variance.

## Success / kill criteria

A lever is REVIVED if (treatment − baseline) is a real recall+judge gain on the long
subset at N≥5 (not a coin-flip), WITHOUT regressing the clean control. Otherwise its
negative verdict STANDS (now confirmed un-masked) and we stop — no further chasing.

## RESULTS

**#1 salient-noun (long-21, N=5, windowing held ON both arms) — NEGATIVE STANDS, not
masked.** `reassess-salient-windowed` / snapshot `sweep-2026-06-02T21-32-39`.
window 0.724 r@20 → window+salient 0.681 (−0.04); helped 4, hurt 4 (same coin-flip).
Pool ticked up (92→94%) but didn't convert — the query-displacement mechanism is
unchanged. **Truncation was a rerank-SCORING bug; salient-noun is a QUERY lever — a
different stage, so the fix doesn't revive it.** Kill criterion met; stop chasing it.

**Meta-conclusion (premise weakened):** the remaining queued levers (corpus-vocab/
user-intent-union, enrichment) are ALSO query-stage levers, so they're expected to
behave like salient — un-masked by the scoring fix. The masking hypothesis applies
to RANKING/scoring levers, and Lever A already IS the scoring fix. Side win:
windowing alone lifts long-21 to 0.724 (from 0.659 off) — Lever A re-confirmed.

## Notes
- All arms hold `:rerank-windowing true :rerank-max-chunk-length 2000`.
- Relates to [[rerank-truncation-plan]] (Lever A, the held-constant fix) and
  [[query-planner-salient-noun-plan]] (the closed-negative lever tested first).
