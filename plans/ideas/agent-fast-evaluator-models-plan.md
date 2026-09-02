# Agent Fast Evaluator Models Plan

## Goal

Cut wall-clock latency on agent iterations by routing the agent's *evaluator* LLM calls (read-signals evaluation and the LLM sufficiency gate) to a cheaper, faster model — independent of the main reasoning model the agent uses for tool-calls. These evaluators return small structured JSON and don't need the main model's reasoning depth.

## Context

The agent currently runs three kinds of LLM call per iteration:

1. **Agent-llm** (tool-calling LLM driving the loop) — `gpt-4o-2024-11-20` by default.
2. **Read-signals evaluator** — `digdir.skills.builtin.agent.read-signals/evaluate-read`, invoked after every `read_chunks`. Classifies which claims are supported by newly-read chunks and returns structured JSON.
3. **Sufficiency gate** — `digdir.skills.builtin.agent.sufficiency/evaluate-sufficiency`, invoked post-tools. Decides whether the evidence is sufficient to finalize.

Both (2) and (3) default to the same model as (1). Trace evidence from the 2026-04-17 debugging session:

- Read-signals LLM call sits on the `read_chunks` critical path. Typical duration **1500–2500ms** (e.g. `read_chunks duration-ms=3000` in `agent-trace-2026-04-17T16-26-55-279948Z.txt` includes ~1800ms of read-signals LLM).
- Sufficiency gate LLM call is **1500–3100ms** on its own when it fires (e.g. `stage=sufficiency-gate iteration=1 duration-ms=3106` in `agent-trace-2026-04-17T18-34-12-988140Z.txt`). Already mitigated for the "discarded result" case but still fires when the decision is kept.

These are small structured-output tasks. `gpt-4o-mini` or `gpt-4.1-mini` typically returns the same schema in **400–800ms** with negligible quality regression on JSON-classification workloads.

## Problem Statement

The evaluator calls are oversized. The agent's reasoning model is chosen for the loop's reasoning quality, but both evaluators re-use it reflexively. Swapping them to a smaller model is the single cheapest latency win remaining after the refund/rerank landed.

## Proposal

Introduce two new optional configuration knobs:

- `:read-signals-model` (already wired through `opts`; defaults to the agent model today). Default changes to a mini model.
- `:sufficiency-model` (new, analogous). Default to a mini model.

Both knobs remain overridable per-invocation, so callers that care about evaluator quality can pin them to the main model.

## Target Models

- **Primary**: `gpt-4o-mini-2024-07-18` (OpenAI or Azure deployment). Stable schema adherence, fast, ~50% of 4o latency.
- **Alternative**: `gpt-4.1-mini` when available on tenant. Better JSON stability in practice.

The structured-eval helper in `digdir.llm.structured-eval` already handles parse errors with a fallback signal, so occasional mini-model JSON hiccups degrade gracefully rather than break the loop.

## Classification Criterion for Safety

Before defaulting to the mini model, the following properties must hold on an eval set:

- Read-signals: on a suite of 20+ captured `read_chunks` calls with known ground-truth `:supported-claims`, the mini model must match the 4o output on `:status`, `:scope-assessment`, and supported-chunk attribution in ≥95% of cases.
- Sufficiency gate: on a suite of 20+ captured post-tool workspaces with known finalize/continue ground-truth, the mini model must agree with 4o on `:status` in ≥95% of cases.

If either fails, keep the mini model optional but don't default it.

## Implementation Sketch

### `server/src/digdir/skills/builtin/agent/read_signals.clj`

`default-llm-fn` already accepts `model` arg. No change needed to the fn; just ensure callers pass the mini model.

### `server/src/digdir/skills/builtin/agent/sufficiency.clj`

Same pattern — `evaluate-sufficiency` takes `{:llm-fn :model :temperature}`. Callers must pass the mini model.

### `server/src/digdir/skills/builtin/agent/core.clj`

Resolve the two new knobs with a sensible default:

```clojure
(let [evaluator-default-model (cfg/get {:tenant (:tenant params)} :services :azure-openai :mini-deployment-name)
      read-signals-model (or (:read-signals-model parameters) evaluator-default-model)
      sufficiency-model (or (:sufficiency-model parameters) evaluator-default-model)]
  ;; pass both into the loop opts
  ...)
```

Add the new config key to `digdir.config.accessor` schema docs and to any config files that pin deployment names for Azure.

### `server/src/digdir/skills/builtin/agent/loop.clj`

Pass `:model sufficiency-model` into the `sufficiency/evaluate-sufficiency` call-sites (around `loop.clj:674-682` and `loop.clj:727-731`).

### Trace visibility

The stage timings already record `:llm-model`. Confirm the trace shows `llm-model=gpt-4o-mini-*` on `stage=sufficiency-gate` and on read-signals whenever they're emitted.

## Expected Impact

On a typical 3-iteration happy-path run:
- Read-signals: 1 invocation, saves ~1000–1500ms on the critical path.
- Sufficiency gate: 0–1 invocations (post–unkeepable-skip landing), saves 0–1500ms when it fires.

Net happy-path wall-clock: **~1.5–3.0s faster median**. Previous median ~9.5s → expected ~7–8s post-change.

On pathological 10-iteration runs: up to **~10–15s** wall-clock saved across 4–6 read-signals invocations.

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Mini model mis-classifies support attribution | Medium | Eval gate before rollout; fall back to `:degraded-fallback` on parse error; existing per-chunk budget-refund guards on confidence ≥ 0.5 already filter out low-confidence evaluations. |
| Sufficiency gate mini-model produces unstable finalize/continue decisions | Medium | Eval gate before rollout; the LLM sufficiency gate is gated behind the `sufficiency-keepable?` pre-filter, so bad calls mostly get discarded anyway. Budget-limit `max-sufficiency-rejections` still caps any instability. |
| Azure deployment naming differs per tenant | Low | Config-driven deployment name; default to env var with clear fallback behavior. Document in `server/docs/api/`. |
| Token billing surprise if evaluators silently fall back to main model | Low | Record `:llm-model` in stage-timings (already done). PRs touching evaluator paths must grep-check the model field. |

## Validation Plan

1. **Eval set**: capture 20+ (read-signal, ground-truth) pairs and 20+ (workspace, ground-truth) pairs from real traces. Run the evaluator against 4o and the mini model. Compare structured outputs. Ship only if agreement ≥95%.
2. **Trace comparison**: run the same 5 happy-path queries + 5 pathological-shape queries before/after. Confirm:
   - `stage=agent-llm llm-model=gpt-4o-*` unchanged
   - `stage=sufficiency-gate llm-model=gpt-4o-mini-*` (new)
   - Read-signals calls show mini model in any explicit timing
   - Median wall-clock drops by the expected ~1.5–3s
3. **Regression watch**: monitor `:citation-validation :all-valid` rates for 7 days post-rollout. A drop indicates evaluator quality regression.

## Out of Scope / Related Work

- **Embedding-model swap for retrieval** — separate plan, out of scope.
- **Direct-synthesis fast path** (skip the agent loop entirely for high-confidence lookup queries) — tracked elsewhere, complementary.
- **Query-planner model swap** — the `:builtin/query-planner` sub-skill also hits an LLM. Could follow the same pattern if planner latency matters (typical 1500–3500ms observed). Recommend second-pass plan.

## Key Files

- `server/src/digdir/skills/builtin/agent/read_signals.clj` — evaluator entry point and default-llm-fn
- `server/src/digdir/skills/builtin/agent/sufficiency.clj` — sufficiency evaluator
- `server/src/digdir/skills/builtin/agent/core.clj` — where the default model resolves
- `server/src/digdir/skills/builtin/agent/loop.clj` — sufficiency-gate call-sites (lines ~674, ~727)
- `server/src/digdir/config/accessor.clj` — new config key
- `server/test/digdir/skills/builtin/agent/read_signals_test.clj` — eval-set tests

## Suggested PR Shape

1. Add config knobs + default wiring (no behavior change yet — keep default at 4o).
2. Build the eval set and comparison harness.
3. Flip default to mini model once eval passes.
