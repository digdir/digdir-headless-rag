# Read Tool Sufficiency Signals Strategy and Implementation Plan

Status: In progress. Created on 2026-04-15. Updated on 2026-04-16.

## Handoff Summary

Use this document as the source of truth for the next implementation steps.

### Current state

- Workspace state, shadow sufficiency aggregation, and diagnostics/UI surfacing have been added.
- `read_chunks` now performs LLM-backed local read evaluation via `read_signals.clj` and records structured read signals in workspace state.
- Transitional heuristic semantic matching has been narrowed to degraded-mode fallback inside `read_signals.clj`; it is no longer the default semantic engine.
- Trace files and diagnostics now expose deterministic read-signal and shadow-decision sections directly, instead of relying on tool-result text.
- The loop now defaults to a read-signal-first control path for straightforward lookup cases: decisive shadow read-signal finalize outcomes skip redundant post-`generate_response` fallback evaluation.
- Post-`generate_response` fallback checks are now tracked separately as response validation, not mixed into read-time sufficiency decisions.
- The workspace now tracks previously non-supporting chunk IDs for the active query, and `read_chunks` suppresses rereads of those chunks by default.
- The remaining work is mainly validation and policy tuning with real traces, especially for broad synthesis and ambiguity-heavy queries.

### Source-of-truth direction

- `read_chunks` should perform semantic local evidence evaluation via a structured LLM call.
- The harness should aggregate structured read-time outputs deterministically.
- The global sufficiency evaluator should remain only as a fallback for ambiguity, contradiction, and broad synthesis cases.

### Next implementation task

Validate and tune the new controller policy against real traces. In practice, the next work is:

- exercise broad synthesis, ambiguity, and contradiction-heavy queries to confirm response validation still catches the intended exception cases
- confirm degraded local-evaluator runs escalate into the fallback path rather than silently finalizing
- tighten any remaining routing heuristics if traces show false-positive finalize decisions or unnecessary fallback calls
- decide whether the old global sufficiency path still needs an explicit rollout flag, or whether the current default is stable enough to keep

## Goal

Reduce agent-loop cost and improve retrieval reliability by moving most sufficiency work from a separate post-read LLM gate into the `read_chunks` tool path itself.

The target design is not “let `read_chunks` decide the answer is done.” The target design is “let `read_chunks` return structured evidence-gap updates that the controller can aggregate cheaply.”

This should let the harness avoid an extra LLM sufficiency call after most reads while preserving explicit, inspectable stop/read-more behavior.

## Problem Statement

The current implementation centers sufficiency in [`server/src/digdir/skills/builtin/agent/sufficiency.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/sufficiency.clj), with control flow in [`server/src/digdir/skills/builtin/agent/loop.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/loop.clj). The loop currently treats sufficiency as a gate that runs after `read_chunks`, `rerank_results`, or `generate_response`.

That architecture has three current weaknesses:

- It pays for a second LLM reasoning pass after the agent already spent tokens selecting and reading evidence.
- It mixes broad answer-level judgment with local evidence inspection, so simple read decisions still incur full evaluator overhead.
- It keeps the most useful intermediate signal implicit. The system knows content was read, but it does not yet persist a compact answer-gap delta produced at read time.

The current `read_chunks` tool implementation in [`server/src/digdir/skills/builtin/agent/tools.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/tools.clj) returns content-oriented text for the LLM and records operational history in workspace state, but it does not return or store structured support/gap information. Workspace state in [`server/src/digdir/skills/builtin/agent/workspace.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/workspace.clj) tracks `:read-history`, `:sufficiency-decisions`, and `:last-insufficiency`, but it does not maintain a persistent claim-coverage or evidence-gap model.

## Desired Outcome

After this work:

- `read_chunks` can optionally perform a cheap, local evidence evaluation tied to the active query.
- Each read stores structured outputs such as supported claims, unresolved gaps, contradictions, and a local next-step hint.
- The loop can decide whether to continue reading, re-search, ask for clarification, or finalize using deterministic aggregation in the common case.
- The standalone LLM sufficiency evaluator becomes a fallback for ambiguous or conflicting cases, not the default on every iteration.
- Traces and diagnostics expose why the system continued reading or decided to stop.

## Direction Update on 2026-04-16

The target design is now stricter:

- deterministic orchestration and aggregation stay in the harness
- semantic support/gap evaluation moves inside `read_chunks`
- the LLM, not heuristics, should decide what newly read evidence changes
- the global sufficiency evaluator becomes a fallback for ambiguity, contradiction, and broad synthesis cases

This is the intended end state. Transitional heuristics may remain temporarily for degraded-mode fallback, but they should not become the long-term semantic engine.

## Design Principles

### 1. Keep global control in the loop

Do not let `read_chunks` return a final authoritative verdict like “the answer is sufficient.”

The loop in [`server/src/digdir/skills/builtin/agent/loop.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/loop.clj) should remain responsible for final action selection because it owns:

- iteration limits
- budget state
- clarification routing
- search vs read tradeoffs
- finalize vs retry behavior

### 2. Move local evidence evaluation into `read_chunks`

`read_chunks` should answer a narrower question:

- what did this read add?
- which answer requirements did it satisfy?
- what important gaps remain?
- did it reveal contradiction or ambiguity?
- what is the most likely next action from this local view?

### 3. Prefer structured state over prose

Do not rely on parsing natural-language read summaries later. The tool should emit explicit machine-readable fields that can be aggregated without another LLM call.

### 4. Bias toward false negatives

When local read evaluation is uncertain, it should leave gaps unresolved rather than prematurely imply completeness.

### 5. Preserve fallback evaluator capability

The current evaluator in [`server/src/digdir/skills/builtin/agent/sufficiency.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/sufficiency.clj) should not be removed immediately. It should be narrowed to:

- ambiguity resolution
- contradiction handling
- broad synthesis questions
- cases where required-claim extraction is unclear

### 6. Use heuristics only for orchestration, not semantics

Heuristics remain appropriate for:

- budgets
- chunk/window selection
- read/unread tracking
- repeated-gap detection
- deterministic escalation thresholds

Heuristics should not remain responsible for:

- deciding whether a chunk semantically supports a claim
- deciding whether evidence is on the right scope
- deciding whether evidence leaves a gap open
- deciding whether two passages conflict semantically
- deciding whether a broad request requires clarification

Those are LLM judgments that should be emitted as structured outputs.

## Architectural Direction

### A. Introduce a query-scoped evidence plan

At the beginning of an agent run, derive a compact query-scoped structure that represents what the system still needs in order to answer safely.

Suggested shape:

```clojure
{:required-claims
 [{:claim-id "c1"
   :text "Default timeout value"
   :kind :fact
   :critical? true}
  {:claim-id "c2"
   :text "Where the timeout is configured"
   :kind :location
   :critical? false}]
 :clarification-needed? false
 :query-scope
 {:answer-type :lookup
  :entity nil
  :year-or-date nil
  :metric nil}}
```

This does not need to be perfect. It only needs to give the loop and read tool a stable target for coverage accounting.

The preferred implementation is:

- deterministic derivation for obviously narrow/factoid queries
- one small-model claim-plan call for broader or more complex queries
- optional plan revision only when repeated reads or ambiguity show that the original plan was incomplete

### B. Add local read evaluation output

Extend `read_chunks` so it can evaluate the chunks it just fetched against the active evidence plan.

Suggested shape:

```clojure
{:supported-claims
 [{:claim-id "c1"
   :support-level :explicit
   :chunk-ids ["chunk-123"]}]
 :partial-claims
 [{:claim-id "c2"
   :support-level :partial
   :chunk-ids ["chunk-123"]}]
 :remaining-gaps
 [{:claim-id "c3"
   :reason :not-found}
  {:claim-id "c4"
   :reason :scope-unclear}]
 :contradictions
 []
 :local-sufficiency
 {:status :gap-remaining
  :next-action-hint :read-more}}
```

This should be recorded in workspace state immediately after a successful read.

The key refinement is that this local evaluation should be produced by a small-model LLM call inside `read_chunks`, not primarily by heuristic matching code.

### C. Let workspace aggregate coverage incrementally

Workspace state should maintain accumulated evidence coverage across all reads, not just the latest read.

Suggested additional state in [`server/src/digdir/skills/builtin/agent/workspace.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/workspace.clj):

```clojure
:evidence-plan nil
:claim-coverage {}
:read-evaluations []
:open-evidence-gaps []
:evidence-contradictions []
:last-read-signal nil
```

This allows the loop to ask deterministic questions such as:

- are all critical claims covered?
- did any contradiction appear?
- are the same gaps repeating across reads?
- is the next best move another read, a new search, or clarification?

### D. Replace most post-read sufficiency calls with aggregation

In the common case, the loop should not call the LLM sufficiency evaluator after every `read_chunks` result.

Instead it should:

- update workspace coverage
- run a deterministic aggregation step
- only escalate to the LLM sufficiency evaluator if the aggregation result is ambiguous

### E. Keep `generate_response` validation separate

A response-quality check may still be useful after `generate_response`, especially when synthesis introduces unsupported claims. That is a different problem from read-time evidence sufficiency and should remain conceptually separate.

## Target Execution Model

The planned steady-state control loop is:

1. infer query intent
2. build or update evidence plan
3. search for candidates
4. read promising chunks
5. evaluate the read locally against the evidence plan
6. update workspace claim coverage
7. aggregate deterministically
8. only if ambiguous, run fallback LLM sufficiency evaluation
9. generate response once critical coverage exists

This differs from the current model by making step 5 part of the read path rather than a separate global gate in the default case.

## Implementation Strategy

### Phase 1. Add state and contracts without changing control policy

Status: implemented.

Goal: land the data model first while preserving current runtime behavior.

### Changes

- Extend workspace state in [`server/src/digdir/skills/builtin/agent/workspace.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/workspace.clj) with fields for:
  - `:evidence-plan`
  - `:claim-coverage`
  - `:read-evaluations`
  - `:open-evidence-gaps`
  - `:evidence-contradictions`
  - `:last-read-signal`
- Add helper functions to:
  - initialize an evidence plan
  - record a read evaluation
  - merge claim support into accumulated coverage
  - derive a compact aggregate status from workspace state
- Extend the shared output schema in [`server/src/digdir/rag/skills/core.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/rag/skills/core.clj) if needed so traces and consumers can safely carry the new fields.

### Notes

This phase should not change the loop’s stop/read-more behavior yet. It should only make the data visible and testable.

### Phase 2. Add a local read evaluator

Status: implemented.

Goal: teach `read_chunks` to emit structured support/gap updates.

### Changes

- Extend the `read_chunks` execution path in [`server/src/digdir/skills/builtin/agent/tools.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/tools.clj).
- Add a helper, likely in a new namespace adjacent to sufficiency, for example:
  - `server/src/digdir/skills/builtin/agent/read_signals.clj`
- The helper should accept:
  - active query
  - query intent
  - current evidence plan
  - chunks returned by this read
  - current open gaps
- The helper should return a structured read signal with:
  - supported claims
  - partially supported claims
  - unsupported/open gaps
  - contradictions
  - scope assessment
  - local next-action hint
- `tools.clj` should record this signal in workspace state immediately after `record-read!` style accounting.

### Direction refinement

The local read evaluator should be an LLM-backed structured evaluator by default.

The important optimization is:

- one LLM call inside `read_chunks` that both reads and evaluates

instead of:

- one LLM call that implicitly reads
- a second LLM call that judges sufficiency globally

### Implementation note

Do not make the read evaluator depend on the whole workspace if it can be avoided. It should consume a compact input snapshot so it remains testable and cheap.

The read evaluator must not answer the user’s question. It should only judge what the newly read chunks contribute.

### Phase 3. Build the first deterministic aggregator

Status: partially implemented.

Goal: make the loop capable of cheap sufficiency decisions without an LLM call in straightforward cases.

### Changes

- Add aggregation helpers in [`server/src/digdir/skills/builtin/agent/workspace.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/workspace.clj) or a small dedicated namespace.
- The aggregator should answer:
  - are all critical claims covered?
  - are any critical claims still open?
  - are there contradictions?
  - are repeated reads failing to reduce gaps?
  - does the current state imply `:read-more`, `:re-search`, `:ask-clarification`, or `:finalize`?
- Add a compact derived status, for example:

```clojure
{:status :sufficient | :insufficient | :ambiguous | :conflicting
 :reason-code :all-critical-claims-covered | :critical-gap-remaining | :conflicting-support | :scope-unclear
 :suggested-strategy :finalize | :read-more | :re-search | :ask-clarification
 :missing-claims ["c3" "c4"]}
```

### Decision policy

Use deterministic rules first:

- any unresolved critical claim => not sufficient
- any contradiction on a critical claim => conflicting
- all critical claims covered and no contradiction => sufficient
- broad unresolved scope ambiguity => ask clarification
- repeated reads with same unresolved gaps => re-search

Current implementation note:

- the workspace aggregator now consumes structured read signals and can classify degraded or ambiguous local signals conservatively
- repeated-gap handling now escalates repeated zero-support reads with unchanged critical gaps from `:read-more` to `:re-search`
- clarification-oriented routing still needs more real-trace validation before this phase is complete

### Phase 4. Integrate aggregator into the loop

Status: implemented.

Goal: make post-read LLM sufficiency optional instead of default.

### Changes

- Update gate flow in [`server/src/digdir/skills/builtin/agent/loop.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/loop.clj).
- After `read_chunks`, the loop should:
  - consult the deterministic aggregate read signal first
  - skip the LLM sufficiency call when the aggregate result is decisive
  - call the fallback evaluator only when aggregate status is `:ambiguous` or `:conflicting` and deterministic routing cannot safely choose an action
- Keep the old evaluator path available behind a feature flag during rollout.

### Suggested flagging

Add a feature flag or skill param such as:

```clojure
{:sufficiency-mode :llm-gate | :read-signals | :hybrid}
```

Recommended rollout order:

- `:llm-gate` as current baseline
- `:hybrid` for rollout and trace comparison
- `:read-signals` only after confidence is established

Current implementation note:

- the loop records and consults shadow read-signal outcomes first
- straightforward `generate_response` finalization now skips redundant fallback evaluation when read signals already established confident sufficiency
- post-`generate_response` fallback checks are recorded as response validation instead of as another sufficiency-gate decision

### Phase 5. Narrow the existing LLM sufficiency evaluator

Status: partially implemented.

Goal: reduce cost and scope of [`server/src/digdir/skills/builtin/agent/sufficiency.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/sufficiency.clj).

### Changes

- Preserve existing evidence-summary helpers where still useful.
- Narrow the evaluator prompt so it is only responsible for:
  - ambiguity resolution
  - contradiction arbitration
  - broad synthesis cases
  - low-confidence claim-plan derivation when deterministic planning is inadequate
- Remove prompt guidance that tries to fully own ordinary read-more vs finalize decisions once the deterministic aggregator covers them.

### Result

The LLM sufficiency evaluator becomes an exception path, not the normal per-read path.

Current implementation note:

- ordinary narrow lookup reads now avoid the fallback evaluator by default
- broad synthesis and ambiguous cases still use the existing evaluator as the fallback engine
- more trace validation is still needed before this phase can be considered complete

### Phase 5a. Retire transitional heuristic semantic matching

Status: mostly implemented.

Goal: ensure `read_signals.clj` does not harden into a second heuristic sufficiency engine.

### Changes

- Remove heuristic support/gap logic whose job is semantic interpretation.
- Keep only:
  - prompt assembly
  - compact request shaping
  - JSON parsing/validation
  - degraded-mode fallback handling
  - trace-friendly summaries
- If the local evaluator fails, emit a conservative fallback signal and mark it as degraded.

### Result

The steady-state read path becomes:

- deterministic setup
- one local LLM evidence evaluation
- deterministic aggregation

Current implementation note:

- `read_signals.clj` now defaults to the structured local LLM evaluator
- heuristic semantic matching remains only as degraded fallback when the local evaluator fails

### Phase 6. Improve tool-facing output and observability

Status: implemented, with one adjustment from the original plan.

Goal: make the new mechanism usable by the agent and debuggable by humans.

### Changes

- Keep `format-read-result` in [`server/src/digdir/skills/builtin/agent/tools.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/tools.clj) compact. Do not append raw read-time JSON to the tool text.
- Surface structured read signals deterministically in trace files and diagnostics instead.
- Extend diagnostics and playground views to show:
  - evidence plan
  - per-read support/gap updates
  - aggregate coverage status
  - whether a decision came from deterministic aggregation or fallback LLM evaluation

Likely follow-on files:

- [`server/src/digdir/playground/diagnostics.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/diagnostics.cljc)
- [`server/test/digdir/playground/diagnostics_test.clj`](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/playground/diagnostics_test.clj)
- [`server/src-dev/digdir/tools/diagnostics.clj`](/Users/bdbrodie/dev/digdir/rag/server/src-dev/digdir/tools/diagnostics.clj)

## Evidence Plan Design

The hardest design choice is how much abstraction to put into `:required-claims`.

Recommended progression:

### Version 1: lightweight answer-shape templates

Use query intent and answer type to derive coarse requirements.

Examples:

- numeric fact:
  - target metric
  - target entity
  - target year/date if present
  - one explicit supporting value
- comparison:
  - support for both sides
  - comparison relation or explanation
- how-to:
  - named procedure or workflow
  - ordered steps or prerequisites
- broad platform question:
  - detect ambiguity and allow clarification requirement

This keeps the first version tractable and aligns well with the existing intent logic already present in workspace and sufficiency code.

### Version 2: LLM-derived minimal claim plan

For complex queries, generate a small claim plan once early in the loop, then reuse it across reads.

This is still cheaper than calling a full sufficiency judge after every iteration.

### Preferred operating model

Use the LLM to derive the evidence plan once per query for non-trivial questions, then reuse that plan across reads. Do not rebuild the plan every iteration unless ambiguity or repeated failed reads show that the plan is wrong.

## Local Read Evaluator Design

The read evaluator should be token efficient and narrow.

Recommended contract:

```clojure
{:status :support-found | :gap-remaining | :conflicting | :unclear
 :supported-claims [...]
 :partial-claims [...]
 :remaining-gaps [...]
 :contradictions [...]
 :next-action-hint :read-more | :re-search | :ask-clarification | :none}
```

Implementation approach:

### Recommended path

Use a small-model local evaluator by default inside `read_chunks`.

The evaluator should receive only:

- the active user query
- the compact evidence plan
- the currently unresolved claims
- the newly read chunks

The key optimization is not “no LLM in the read path.” The key optimization is:

- one LLM call inside `read_chunks` that both reads and evaluates

instead of:

- one LLM call to synthesize understanding of the read
- another LLM call to judge sufficiency globally

### Heuristic fallback only

If the local evaluator fails, the tool may emit a conservative fallback signal, but that should be treated as degraded mode rather than the normal evaluation path.

## `read_chunks` LLM Prompt

The local evaluator prompt should be strict, low-temperature, and JSON-only.

Suggested system prompt:

```text
You are the read-time evidence evaluator for a RAG coding/research agent.

Your job is to inspect only the newly read chunks and decide what they add to the current evidence state.

Do not answer the user’s question.
Do not use prior knowledge.
Do not guess.
Prefer false negatives over false positives.

You must evaluate:
- which required claims are explicitly supported by the new chunks
- which claims are partially supported
- which claims remain unsupported
- whether the new chunks introduce contradictions
- whether the evidence is on the correct scope for the user’s query
- the best local next action hint

Return JSON only, matching the provided schema exactly.
Allowed next_action_hint values: read-more, re-search, ask-clarification, finalize.
Allowed scope_assessment values: aligned, partially-aligned, wrong-scope, ambiguous.
Allowed support values: explicit, partial.
```

Suggested user prompt template:

```text
User query:
{{query}}

Query intent:
{{query_intent_json}}

Evidence plan:
{{evidence_plan_json}}

Currently unresolved claims:
{{open_claims_json}}

Newly read chunks:
{{read_chunks_json}}

Evaluate only what these newly read chunks contribute.
Return JSON only.
```

Suggested chunk representation:

```json
[
  {
    "chunk_id": "chunk-17",
    "doc_num": "42",
    "chunk_index": 3,
    "title": "Retry policy",
    "metadata": {"header": "Timeouts"},
    "content": "..."
  }
]
```

## JSON Schema for Read-Time Evaluation

Recommended read-time evaluation payload:

```json
{
  "status": "support-found | gap-remaining | conflicting | unclear",
  "scope_assessment": "aligned | partially-aligned | wrong-scope | ambiguous",
  "supported_claims": [
    {
      "claim_id": "c1",
      "support": "explicit | partial",
      "chunk_ids": ["chunk-17"],
      "notes": "optional short explanation"
    }
  ],
  "remaining_gaps": [
    {
      "claim_id": "c2",
      "reason": "not-addressed | ambiguous | wrong-scope | contradiction",
      "critical": true
    }
  ],
  "contradictions": [
    {
      "claim_id": "c3",
      "chunk_ids": ["chunk-17", "chunk-22"],
      "summary": "optional short contradiction summary"
    }
  ],
  "next_action_hint": "read-more | re-search | ask-clarification | finalize",
  "confidence": 0.0
}
```

Notes:

- `supported_claims` should reference only claims from the active evidence plan.
- `remaining_gaps` should be conservative.
- `confidence` is advisory only. Deterministic controller policy still owns the final action.
- `notes` and `summary` must remain short enough for traces and diagnostics.

## Exact Controller Policy for Skipping the Global Evaluator

The controller should skip the global evaluator when aggregated read-signal state is decisive.

### Skip the global evaluator and finalize when:

- all critical claims are supported
- there are no contradictions
- scope assessment is `aligned`
- no clarification requirement is open

Action:

- `:finalize`

### Skip the global evaluator and keep reading when:

- one or more critical claims remain open
- there are unread promising hits or local range expansions left
- scope assessment is `aligned` or `partially-aligned`
- no contradictions are present

Action:

- `:read-more`

### Skip the global evaluator and re-search when:

- one or more critical claims remain open
- there are no promising unread local reads left
- recent reads are not reducing the set of open critical gaps
- scope is not clearly wrong, but current evidence is not progressing

Action:

- `:re-search`

### Do not skip the global evaluator when:

- any contradiction is present on a critical claim
- scope assessment is `ambiguous`
- scope assessment is `wrong-scope` but the correct target is unclear
- the evidence plan appears under-specified
- the query is broad explanatory synthesis and local read signals disagree or stay low confidence
- the local evaluator returns invalid JSON, degraded output, or repeated `unclear`

Action:

- call the global evaluator

### Ask clarification directly without the global evaluator only when:

- deterministic ambiguity signals are very strong
- the unresolved choice is explicit and user-facing
- the local read evaluator also returns `ask-clarification`

Otherwise:

- use the global evaluator to arbitrate clarification vs re-search

## Deterministic Aggregation Rules

The aggregator should stay simple enough to inspect from traces.

Recommended rules:

- `:sufficient`
  - all critical claims covered
  - no critical contradictions
  - no open clarification requirement
- `:insufficient`
  - one or more critical claims open
  - no contradiction, no major ambiguity
- `:ambiguous`
  - unresolved scope ambiguity
  - claim plan too weak to decide
  - support exists but only partially addresses query intent
- `:conflicting`
  - mutually inconsistent support on a critical claim

Recommended strategy mapping:

- `:insufficient` + unread promising hits remain => `:read-more`
- `:insufficient` + repeated gap with no unread promising hits => `:re-search`
- `:ambiguous` + broad scope ambiguity => `:ask-clarification`
- `:sufficient` => `:finalize`
- `:conflicting` => fallback evaluator or finalize-with-uncertainty depending on policy

These rules should aggregate structured LLM outputs from `read_chunks`, not semantic heuristic guesses.

## Migration and Rollout Plan

### Step 1. Instrument only

Ship new state and trace fields without changing behavior.

### Step 2. Shadow evaluation

Run local read evaluation and deterministic aggregation in parallel with the current sufficiency gate, but do not let it control the loop yet.

Capture comparison metrics:

- agreement rate with current sufficiency decisions
- cases where local aggregation avoided a redundant LLM call
- cases where local aggregation would have stopped too early
- cases where local aggregation missed clarification opportunities

Current status:

- this step is effectively complete enough to observe in traces
- real traces now show explicit `READ SIGNALS` and `SHADOW SUFFICIENCY DECISIONS` sections
- recent runs show materially shorter solve paths when the local evaluator finds decisive support

### Step 3. Hybrid control

Use deterministic aggregation for decisive cases and fallback evaluator for ambiguous ones.

Current status:

- partially implemented
- the main remaining work is to remove redundant late gating in straightforward successful runs

### Step 4. Default to read-signal-first

Once traces show stability, make read-signal-first the default mode.

## Testing Plan

### Unit tests

Add focused tests for:

- evidence-plan derivation from query intent
- read-evaluation merge behavior
- deterministic aggregate decision rules
- contradiction accumulation
- repeated-gap detection

Likely targets:

- [`server/test/digdir/skills/builtin/agent/sufficiency_test.clj`](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/builtin/agent/sufficiency_test.clj)
- [`server/test/digdir/skills/builtin/agent_test.clj`](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/builtin/agent_test.clj)
- a new namespace-specific test file for `read_signals.clj` if introduced

Current status:

- implemented coverage now includes `server/test/digdir/skills/builtin/agent/read_signals_test.clj`
- agent tests cover `read_chunks` wiring, trace formatting, and diagnostics rendering for read signals

### Integration tests

Extend integration coverage so complete agent runs verify:

- read signals are recorded after `read_chunks`
- deterministic aggregation chooses `:read-more` vs `:re-search` correctly
- finalization can happen without an intervening full LLM sufficiency call in straightforward cases
- clarification still wins when scope ambiguity remains

Likely target:

- [`server/test/digdir/skills/builtin/agent_integration_test.clj`](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/skills/builtin/agent_integration_test.clj)

### Diagnostics tests

Extend diagnostics formatting tests to confirm new trace fields render correctly and compactly.

## Success Criteria

- The median number of LLM sufficiency calls per agent run drops materially.
- Straightforward read iterations no longer require a separate post-read sufficiency call.
- Clarification quality does not regress for broad ambiguous questions.
- Finalization false positives do not increase materially.
- Traces clearly show which claims were satisfied, which gaps remained, and why the next action was chosen.

## Risks and Mitigations

### Risk: tool/controller boundary becomes muddy

Mitigation:

- keep final stop decisions in the loop
- restrict `read_chunks` to local evidence updates

### Risk: claim-plan quality is too weak

Mitigation:

- start with answer-shape templates
- use fallback LLM plan derivation for complex questions

### Risk: local evaluator becomes another heuristic pile

Mitigation:

- prefer a compact structured contract
- keep rules generic by answer type, not domain specific
- use a small LLM where deterministic extraction becomes brittle

### Risk: contradictory or broad synthesis cases still need holistic reasoning

Mitigation:

- preserve the fallback evaluator for ambiguous/conflicting cases

### Risk: observability gets worse if signals are over-compressed

Mitigation:

- persist per-read evaluation entries in workspace
- expose aggregate and source-local signals separately in diagnostics

## Recommended Initial Scope

Implement the first slice for the highest-value path only:

- `read_chunks`
- workspace evidence plan and coverage state
- LLM-backed local read evaluation for numeric fact, comparison, and narrow lookup questions
- deterministic aggregation for numeric fact, comparison, and narrow lookup questions
- fallback to existing LLM sufficiency for everything else

Do not try to solve broad procedural or synthesis questions in the first pass.

## Open Questions

- Should the evidence plan be built once per user query or updated after every search pass?
- Should the local read evaluator live in `tools.clj`, `sufficiency.clj`, or a new focused namespace?
- Should `generate_response` also return supported-claim traces so unsupported synthesis can be detected explicitly?
- Should rerank contribute coverage hints, or should coverage remain read-derived only?

## Recommended Decision

Proceed with a hybrid design where `read_chunks` returns structured sufficiency signals and workspace aggregates them, while the current LLM sufficiency evaluator remains as a fallback and rollout baseline.

This is the best tradeoff because it:

- removes redundant LLM passes in the common path
- keeps final decision authority explicit and inspectable
- scales better than domain heuristics
- avoids the brittleness of letting a single read tool make hidden global stop decisions
- uses the LLM for semantic evidence judgment instead of rebuilding a second heuristic sufficiency engine

## Progress Log

- 2026-04-15: Created this strategy and implementation plan after reviewing the current agent tool bridge, workspace state, loop gating, and sufficiency evaluator. The current architecture already has clear integration points in `tools.clj`, `workspace.clj`, `loop.clj`, and `sufficiency.clj`, which makes an incremental rollout feasible.
- 2026-04-16: Updated the plan to make the target direction explicit: semantic support/gap evaluation should happen inside `read_chunks` via a structured LLM call, while the controller remains deterministic and the global evaluator becomes a fallback for ambiguity, contradiction, and broad synthesis cases. Added a concrete `read_chunks` prompt, a read-time JSON schema, and exact controller rules for when to skip the global evaluator.
- 2026-04-16: Implemented the local LLM-backed `read_chunks` evaluator in `read_signals.clj`, including structured prompt assembly, JSON normalization/validation, and degraded fallback handling. Wired the read signal into workspace state and shadow aggregation.
- 2026-04-16: Removed raw read-time JSON from `read_chunks` tool text and moved deterministic observability into trace files and diagnostics. Trace output now includes dedicated `READ SIGNALS` and `SHADOW SUFFICIENCY DECISIONS` sections, which made recent traces much easier to validate.
- 2026-04-16: Real traces now show the intended high-value path working: one search, one read, local LLM support detection, shadow finalize, and a materially shorter solve loop for straightforward lookup questions. Remaining work is mainly controller cleanup: make read-signal-first the default control policy and narrow the fallback sufficiency gate to true exception cases.
- 2026-04-16: Finished the controller-side cutover in `loop.clj` for the main path. Decisive shadow read-signal finalize outcomes now skip redundant post-`generate_response` fallback evaluation, while broad or uncertain post-generate checks are tracked separately as `response validation` in workspace state, diagnostics, and trace output.
- 2026-04-16: Tightened the deterministic aggregator so repeated zero-support reads with the same critical gaps now escalate to `:re-search` instead of repeatedly consuming unread hits. This directly addresses the loop pattern seen in the Altinn 3 launch-date trace.
- 2026-04-16: Added query-scoped suppression of previously non-supporting chunk IDs. Zero-support, non-degraded reads now mark their returned chunks as non-supporting, and later `read_chunks` calls skip those IDs instead of fetching them again. This is intended to stop re-search loops from rereading the same dead chunks.
- 2026-04-16: Fixed citation-validation reporting so validation is recomputed against the final renumbered response and final citation index. This removes stale `valid-indices` values like the earlier `[3]`-vs-`[1]` mismatch seen in traces.
