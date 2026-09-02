# Speculative Synthesis Plan

## Goal

Overlap the agent's final `generate_response` call with the sufficiency-gate evaluation when heuristics suggest the gate will almost certainly pass. In the common "sufficient after one read" shape this hides the ~1–1.5s sufficiency-gate latency entirely behind synthesis that would have to run anyway.

## Context

Today, on the happy path, the agent loop executes sequentially after `read_chunks`:

```
read_chunks (inline read-signals LLM)
  → sufficiency-gate LLM        (~1500ms, blocks)
    → generate_response LLM     (~1200ms)
```

The existing sufficiency-gate pre-filter (landed alongside the refund) already skips the evaluator when its result would be discarded anyway. But when a `read_chunks` leaves no unread evidence AND returns a strong read-signal, the gate still runs and almost always returns `:sufficient`. Synthesis waits unnecessarily.

Trace evidence — any happy-path trace from `agent-trace-2026-04-17T20-46-06-004160Z.txt` onward shows this pattern: gate runs, decides sufficient, synthesis follows. Typical shape: 1500ms gate + 1200ms synthesis = 2700ms serial when 1500ms parallel would do.

## Problem Statement

Two LLM calls that aren't data-dependent on each other run sequentially. The sufficiency gate's job is to *decide* whether to synthesize — but in the common case we could start synthesizing immediately and cancel (or discard) if the gate rejects.

This is a speculation pattern: optimistic execution + compensating action on the uncommon branch.

## Proposal

When the post-read read-signal looks decisive (`status=:support-found`, `scope=:aligned`, confidence ≥ 0.8, no open evidence gaps), kick off `generate_response` synthesis **and** the sufficiency-gate evaluation as concurrent futures. Await both.

- If gate returns `:sufficient` (expected, majority case): use the synthesis result. Net wall-clock = `max(gate-ms, synth-ms)` instead of `gate-ms + synth-ms`.
- If gate returns non-sufficient (minority case): discard the synthesis. Apply the gate hint to the next iteration as today. Cost: one wasted synthesis LLM call.

The speculation is triggered only when heuristics already indicate a ≥90% probability of sufficient. We are not speculating blindly — we're hiding latency on the happy path.

## Speculation Trigger

Fire speculative synthesis when ALL of:

- Current iteration's tool calls include `read_chunks` AND did NOT include `generate_response` (agent hasn't already synthesized).
- `workspace.last-read-signal`:
  - `:status = :support-found`
  - `:scope-assessment = :aligned`
  - `:confidence >= 0.8`
  - `:degraded? = false`
  - `:evaluation-mode ∈ #{:llm :degraded-fallback}`
- `workspace.open-evidence-gaps` is empty.
- `workspace.evidence-contradictions` is empty.
- Shadow aggregate sufficiency (`aggregate-read-signals`) returns `:status :sufficient`.

This is strictly stricter than `should-run-sufficiency-gate?`. The speculation cost is paid only when the gate's answer is nearly certain.

## Expected Hit Rate

From observed traces (happy-path Altinn query, 20+ runs):

- Read-signal returns `:support-found :aligned` on first successful read ≥85% of runs.
- Sufficiency gate confirms sufficient when those conditions hold ≥95% of runs.
- Combined: speculation is triggered ~80% of happy-path runs; the speculated synthesis is kept ~95% of those (~76% of all happy-path runs benefit).

Wasted synthesis calls: ~4% of happy-path runs. Acceptable ratio given the gain on the common path.

## Implementation Sketch

### `server/src/digdir/skills/builtin/agent/loop.clj`

Define a predicate after `shadow-read-decision` is computed:

```clojure
speculative-synthesis-ready?
(and read-chunks-called?
     (not generated-response-call?)
     (let [rs (:last-read-signal workspace-after-tools)]
       (and (= :support-found (:status rs))
            (= :aligned (:scope-assessment rs))
            (>= (double (or (:confidence rs) 0.0)) 0.8)
            (not (:degraded? rs))
            (contains? #{:llm :degraded-fallback}
                       (:evaluation-mode rs))))
     (empty? (:open-evidence-gaps workspace-after-tools))
     (empty? (:evidence-contradictions workspace-after-tools))
     (= :sufficient (:status shadow-gate-raw-decision)))
```

When true, kick off synthesis as a `future` before running the keepable sufficiency gate:

```clojure
speculated-synthesis (when speculative-synthesis-ready?
                       (future
                         (try
                           (execute-synthesis query workspace-after-tools opts)
                           (catch Exception e
                             {:synthesis-error (.getMessage e)}))))
```

(`execute-synthesis` encapsulates what the `generate_response` tool handler does today.)

After `sufficiency-decision` is resolved:

```clojure
;; Speculation resolution
speculated-result (when speculated-synthesis
                    (deref speculated-synthesis))
speculation-kept? (and speculated-result
                       (nil? (:synthesis-error speculated-result))
                       (or (nil? sufficiency-decision)
                           (= :sufficient (:status sufficiency-decision))))
```

If `speculation-kept?`, inject the synthesis output as the next iteration's forced `generate_response` tool call (or short-circuit the loop to `:finalize`). Otherwise discard.

Trace instrumentation:

- `stage=speculative-synthesis status=ok|discarded duration-ms=N detail=kept=true|false reason=…`
- `stage=speculative-synthesis-wasted detail=gate-rejected|synthesis-errored` when discarded.

### Synthesis encapsulation

Today synthesis lives inside the `generate_response` tool handler in `tools.clj`. Extract a pure-ish `execute-synthesis` helper that takes query + workspace + opts and returns `{:response :citations :citation-index :citation-validation}`. Both the tool handler and the speculation path use it.

### Cancellation vs. discard

Clojure `future`s can't be cancelled mid-call from inside `openai-clojure`'s HTTP call. Accept that a discarded speculation completes in the background — it just goes unused. Log the wasted call for visibility but don't try to interrupt.

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Wasted synthesis cost on rejected speculation | Low | Gated at ≥90% expected sufficient rate. Trace logs `speculative-synthesis-wasted` for monitoring. |
| Race between speculative synthesis and workspace state mutation | Medium | `execute-synthesis` reads workspace snapshot at speculation-start, not live. No writes during synthesis. Tool-handler path unchanged. |
| Speculated synthesis result diverges from what the post-gate synthesis would have produced | Low | Same workspace snapshot, same model, same temperature. Divergence is only possible if workspace state changes between spec-start and spec-use, which is prevented by snapshotting. |
| Billing surprise from extra LLM calls | Low | Wasted-call rate target ≤5% of happy-path runs. Monitor via new stage-timing. If rate creeps up, tighten speculation trigger or disable. |
| Complicates already-intricate loop control flow | Medium | Keep speculation strictly post-read, pre-synthesis. No nested speculation. Extensive unit tests on the kept/discarded branches. |

## Validation Plan

1. **Shadow-mode first**: for 1–2 weeks, run the speculation logic but always discard the speculative result (still run the serial synthesis after the gate). Record `stage=speculative-synthesis-shadow` comparing speculative vs. actual synthesis outputs:
   - Output equality (ignoring surface formatting) — aim ≥99%.
   - Citation set equality — aim ≥99%.
2. **Flip to active once shadow data confirms safety**. Record:
   - % of happy-path runs that trigger speculation
   - % of triggered speculations that are kept
   - Median wall-clock delta on kept vs. non-speculated happy paths
3. **Regression watch** for 14 days: citation-validation all-valid rate, pathological-rate (errors per 100 queries), wasted-speculation-rate.

## Expected Impact

On happy-path runs that trigger speculation (~80%):
- Before: `gate 1500ms + synth 1200ms = 2700ms serial`
- After: `max(1500, 1200) = 1500ms parallel`
- Wall-clock saved per run: ~1200ms.

Combined median impact on happy paths: **~900ms faster** (accounting for the ~20% that don't trigger speculation). Post-landing happy-path median target: ~7–8s.

On pathological paths: no effect (speculation doesn't fire when read-signal is gap-remaining).

## Out of Scope / Related Work

- **Parallel read-signals + search** — a different speculation pattern (kick off the next search while signals evaluate). Separate plan; higher complexity.
- **Streaming synthesis** — start streaming tokens before gate resolves, truncate if rejected. Even more complex; UI-specific; defer.
- **Combined with `agent-fast-evaluator-models-plan.md`** — if the gate drops to ~600ms via mini model, the synthesis is already the longer leg and speculation saves less. Worth re-measuring impact post mini-model rollout.

## Key Files

- `server/src/digdir/skills/builtin/agent/loop.clj` — main speculation wiring, around `shadow-read-decision` and the sufficiency-decision block (loop.clj:~648–700).
- `server/src/digdir/skills/builtin/agent/tools.clj` — extract `execute-synthesis` helper from the `generate_response` tool handler.
- `server/test/digdir/skills/builtin/agent/loop_test.clj` — new or extended tests for speculation trigger + kept/discarded branches.

## Suggested PR Shape

1. Extract `execute-synthesis` (refactor, no behavior change).
2. Add speculation infrastructure in shadow mode (logs only, no result use).
3. Flip to active mode once shadow data proves safety.
