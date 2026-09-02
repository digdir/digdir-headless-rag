# Read-Signal Controller Validation and Tuning Plan

Status: In progress. Created on 2026-04-18.

This plan captures the remaining work from `read-tool-sufficiency-signals-plan.md`, which is now retired. All build phases (state/contracts, local evaluator, aggregator, loop integration, observability) have landed. What remains is validation against real traces and final policy cleanup.

## Context

The read-signal-first control path is the default for straightforward lookups:

- `read_chunks` runs an LLM-backed local evaluator in `server/src/digdir/skills/builtin/agent/read_signals.clj`
- Workspace state carries `:evidence-plan`, `:claim-coverage`, `:read-evaluations`, `:open-evidence-gaps`, `:evidence-contradictions`, `:last-read-signal`
- The loop in `server/src/digdir/skills/builtin/agent/loop.clj` consults aggregated shadow signals first and skips the fallback evaluator when read-signal outcomes are decisive
- Post-`generate_response` fallback checks are recorded separately as response validation
- Trace files and diagnostics render dedicated `READ SIGNALS` and `SHADOW SUFFICIENCY DECISIONS` sections
- Heuristic semantic matching in `read_signals.clj` is narrowed to degraded-mode fallback only

The remaining risks are all about calibration: false-positive finalizations, silently-finalized degraded reads, and unnecessary fallback calls.

## Remaining Work

### 1. Trace-driven validation of exception cases

**Goal:** confirm the response-validation path still catches the cases that used to be caught by the pre-cutover sufficiency gate.

**Method:** run representative queries and inspect traces for decision provenance and final-answer quality.

Categories to exercise:

- **Broad synthesis.** Queries that span multiple documents or require explanatory composition (e.g. "summarize how Altinn handles authentication end-to-end"). Confirm the loop does not finalize on a single aligned read.
- **Ambiguity.** Queries where scope is genuinely unclear and `:ask-clarification` should win (e.g. "what's the limit?" without an entity). Confirm deterministic routing escalates to the fallback evaluator rather than finalizing on partial support.
- **Contradiction.** Queries where multiple sources disagree on a critical claim. Confirm `:conflicting` status surfaces and the fallback evaluator is consulted.

**Deliverable:** a short trace audit note (inline in this plan's progress log) for each category: query used, whether read-signal path or fallback ran, whether the final answer was correct, and any policy adjustments made.

### 2. Degraded-mode escalation audit

**Goal:** confirm degraded local-evaluator runs never silently finalize.

`read_signals.clj` marks its output degraded when the LLM call fails, JSON is invalid, or enums don't validate. The aggregator in `workspace.clj` is supposed to treat degraded signals conservatively (escalate to fallback evaluator, not finalize).

**Checks:**

- Force a degraded signal in a test or live trace (e.g. by temporarily returning invalid JSON from the read evaluator) and confirm the loop routes to the fallback evaluator rather than finalizing.
- Confirm degraded-mode runs appear in the `SHADOW SUFFICIENCY DECISIONS` trace section with a visible degraded marker, so operators can spot them.
- If the current aggregator does not explicitly branch on `:degraded?`, add a unit test in `server/test/digdir/skills/builtin/agent/workspace_test.clj` and tighten the aggregator until it does.

### 3. Routing-heuristic tightening

**Goal:** remove false-positive finalize decisions and unnecessary fallback calls surfaced by traces.

Likely edit surfaces:

- aggregator rules in `workspace.clj` (deterministic `:sufficient | :insufficient | :ambiguous | :conflicting` classification)
- controller policy in `loop.clj` (when to skip the fallback evaluator)
- read-evaluator prompt in `read_signals.clj` (if false positives come from over-confident local support judgments)

**Approach:** drive every change from a captured trace. Do not pre-tune heuristics without a concrete failing example — the current defaults are stable for the high-value path and speculative tweaks risk regressions.

### 4. Narrow the fallback evaluator prompt

**Goal:** finish Phase 5 from the retired plan. The fallback in `server/src/digdir/skills/builtin/agent/sufficiency.clj` still carries prompt guidance from when it owned ordinary read-more vs finalize decisions.

**Changes:**

- Remove prompt sections that instruct the evaluator to judge ordinary lookup sufficiency.
- Keep only: ambiguity resolution, contradiction arbitration, broad synthesis judgment, and low-confidence claim-plan backfill.
- Confirm `sufficiency_test.clj` still passes; add focused tests for the narrowed responsibilities if coverage is thin.

**Trigger:** do this after step 1 surfaces at least one real trace where the fallback is invoked, so the prompt can be rewritten against a concrete case rather than in the abstract.

### 5. Rollout-flag decision

**Goal:** resolve the open question of whether `:sufficiency-mode` should remain a skill parameter or be removed.

Options:

- **Keep the flag.** Useful if production needs a fast rollback path. Cost: extra branching in `loop.clj` and more test surface.
- **Remove the flag.** Simplifies the controller once trace validation (steps 1–3) shows no regressions. Recommended if two weeks of real traces show no false-positive finalizations.

**Decision criteria:** after step 1's trace audit, count decisions made by read-signal path vs fallback. If read-signal decisions are correct in every audited case and no trace shows a need to force `:llm-gate` mode, remove the flag.

## Out of Scope

- New read-evaluator capabilities (e.g. multi-query coverage tracking, rerank-derived coverage hints) — those belong in a separate plan.
- Changes to `generate_response` response-validation policy beyond keeping it conceptually separate from read-time sufficiency.
- Citation-validation work — already landed, tracked separately.

## Success Criteria

- Trace audit in step 1 shows correct behavior on at least one query per category (broad synthesis, ambiguity, contradiction).
- Degraded-mode reads provably route to the fallback evaluator (demonstrated by a test or captured trace).
- No open trace with a false-positive finalization or an unnecessary fallback call.
- Fallback evaluator prompt reflects its narrowed role.
- `:sufficiency-mode` is either removed or has an explicit justification for staying.

## Progress Log

- 2026-04-18: Created this plan as a successor to `read-tool-sufficiency-signals-plan.md`. All build work from that plan has landed; only validation and policy cleanup remain.
