# Retrieval And Evidence-Aware Loop Plan

## Objective
Improve retrieval precision and second-pass recovery without reintroducing opaque heuristics or brittle prompt-only fixes. The system should retrieve answer-bearing evidence earlier, explain why a pass failed in structured form, and generate evidence-aware follow-up searches when the first pass is insufficient or conflicting.

## Problem Statement
Recent debugging clarified three distinct facts:

1. Retrieval is good enough to find relevant evidence eventually.
2. The pipeline was previously losing decisive evidence due to chunk truncation in the rerank-to-synthesis path.
3. Even after fixing truncation, retrieval ordering is still noisy for broad fact queries like `Hvor mange årsverk hadde Digdir i 2022?`

The remaining issue is not "can the system ever find the answer?" It is:
- can the system retrieve answer-bearing chunks early enough,
- can it distinguish insufficient evidence from conflicting evidence,
- and can it formulate better second-pass searches based on what was actually missing?

## Design Principles

### 1. Separate Recall From Precision
- Query generation and retrieval strategy selection improve recall.
- Chunk fusion and prioritization improve precision.
- Do not overload one stage to solve both problems.

### 2. Keep Retrieval Strategies Specialized
- `phrase` search should remain sparse lexical anchoring.
- `metadata` search should remain title/header/filter-oriented.
- `content` search should remain body-text evidence retrieval.
- Query-aware ranking should happen after retrieval, during fusion/prioritization.

### 3. Make Failure Reasons Structured
- Re-search guidance should not infer everything from free-form model text.
- Store explicit failure reasons in workspace state so follow-up search decisions are inspectable and testable.

### 4. Prefer General Retrieval Concepts Over Case-Specific Rules
- Factoid and numeric queries should bias toward answer-bearing chunks.
- Canonical document families should be preferred when conflicts appear.
- The system should not accumulate a growing list of query-specific hacks.

## Current Known Findings

### Confirmed Resolved
- Chunk truncation in rerank context assembly was removing decisive evidence.
- Removing chunk manipulation allowed the same Digdir query to succeed end to end.
- The latest successful trace used full chunk content and answered correctly from `6a80d6499075`.

### Confirmed Still Weak
- Generic fact queries still surface noisy HR/headcount/turnover chunks too high.
- `prioritize-chunks` is still mostly driven by merged hit count, original rank, title overlap, year, and org match.
- That favors thematic relevance over answer-bearing evidence in body text.

### Implication
Next work should improve:
- query analysis,
- retrieval fusion,
- re-search guidance,
- and loop control.

## Incremental Objectives

## Progress Log

### 2026-03-14
- Started Objective 1 in `server/src/digdir/skills/builtin/agent.clj`.
- Added deterministic structured insufficiency state to the sufficiency gate and workspace:
  - `:failure-type`
  - `:target-entity`
  - `:target-year`
  - `:target-metric`
  - `:doc-family-hint`
  - `:evidence-gap-summary`
  - `:recommended-action`
- Decision: keep this first version heuristic and inspectable rather than model-derived, so Objective 2 query intent can reuse the same shape later.
- Added trace logging for the structured insufficiency payload under `== SUFFICIENCY DECISIONS ==`.
- Added agent tests covering:
  - structured insufficiency for `:read-more`
  - structured insufficiency for `:re-search`
  - trace rendering of the new payload
- Verified with: `clj -M:test -n digdir.skills.builtin.agent-test`
- Started and completed Objective 2 in `server/src/digdir/skills/builtin/agent.clj`.
- Added a lightweight query intent layer with:
  - `:answer-type`
  - `:entity`
  - `:year-or-date`
  - `:metric`
  - `:doc-family-preference`
  - `:scope-signals`
- Decision: infer intent once from the active query plus conversation history, store it in workspace, and expose it in trace/output before using it to steer retrieval. This keeps Objective 2 passive and testable while Objective 3 reuses the exact same shape.
- Refined entity extraction to ignore capitalized question words and fall back to conversation history when the current turn is elliptical.
- Added agent tests covering:
  - numeric fact intent
  - definition intent
  - comparison intent using conversation history
  - trace rendering of the query intent payload
- Verified with: `clj -M:test -n digdir.skills.builtin.agent-test`
- Started and completed Objective 3 in `server/src/digdir/skills/builtin/agent.clj`.
- Added a shared deterministic query-batch helper used by both `plan_queries` and re-search suggestions.
- Current query generation now aims to emit:
  - a broad variant
  - a metric-specific variant
  - a canonical-document variant
  - a disambiguating variant
- Example Digdir output now includes:
  - `årsverk digdir 2022`
  - `utførte årsverk digdir 2022`
  - `digdir årsrapport 2022 årsverk`
  - `digdir antall ansatte årsverk 2022`
- Decision: keep Objective 3 agent-local for now by post-processing query-planner output, rather than modifying the standalone query-planner skill first. This makes the behavior easy to test and lets us move later logic into the planner if it proves stable.
- Added agent tests covering:
  - direct query-batch generation
  - `plan_queries` workspace updates with intent-aware variants
  - continued non-repetition of latest search batches in re-search guidance
- Verified with: `clj -M:test -n digdir.skills.builtin.agent-test`
- Started and completed Objective 4 in `server/src/digdir/skills/builtin/retrieval.clj`.
- Extended late fusion in `prioritize-chunks` with:
  - `:search-type` boost favoring chunks surfaced by content search
  - `:content-overlap` boost for body-text/title/metadata token overlap
  - `:numeric-evidence` boost for numeric-fact queries when a chunk contains the query year, a non-year number, and matching metric terms
- Decision: keep the three retrieval strategies unchanged and improve precision only in late fusion. This preserves strategy specialization while letting body-text evidence outrank generic thematic matches.
- Important refinement from tests: numeric evidence must require a non-year number. Treating the query year alone as numeric evidence overboosted generic 2022 titles.
- Added retrieval tests covering:
  - direct answer-bearing content outranking title-only matches for numeric fact queries
  - metadata-only retrieval still benefiting from content-hit fusion signals
  - preservation of existing prioritization/document-diversity behavior
- Verified with:
  - `clj -M:test -n digdir.skills.builtin.retrieval-test`
  - `clj -M:test -n digdir.skills.builtin.agent-test -n digdir.skills.builtin.retrieval-test`
- Started and completed Objective 5 in `server/src/digdir/skills/builtin/agent.clj`.
- Re-search guidance now uses:
  - `:last-insufficiency`
  - `:last-query-intent`
  - latest search queries
  - latest search chunk summaries (title/header-level document-family hints)
- Added explicit insufficiency-driven search variants for:
  - `:missing-numeric-fact`
  - `:wrong-doc-family`
  - `:conflict`
  - `:missing-date`
- Decision: keep evidence-aware re-search deterministic and agent-local for now, rather than asking the model to invent the next query batch from scratch. This improves traceability and makes failures directly testable.
- Added agent tests covering:
  - metric-specific + canonical-source suggestions for missing numeric facts
  - document-family pivot suggestions for wrong-doc-family cases
  - disambiguating summary-oriented suggestions for conflict cases
- Verified with:
  - `clj -M:test -n digdir.skills.builtin.agent-test`
  - `clj -M:test -n digdir.skills.builtin.agent-test -n digdir.skills.builtin.retrieval-test`
- Started and completed Objective 6 in `server/src/digdir/skills/builtin/agent.clj`.
- Conflict is now a first-class sufficiency status:
  - `:status :conflict`
  - `:action :read-more` when unread evidence remains
  - `:action :re-search` when the current hit set is exhausted
- Added dedicated conflict guidance that tells the agent to avoid presenting one value as definitive, prefer canonical summary sources, and use scope-disambiguating searches.
- Decision: keep conflict inside the same high-level control flow as insufficiency for now, but distinguish it explicitly in state, trace output, and guidance. This gives us the right semantics before loop budgeting is introduced.
- Added agent tests covering:
  - conflict-specific generate-response guidance
  - trace output that distinguishes `status=conflict` from `status=insufficient`
- Verified with:
  - `clj -M:test -n digdir.skills.builtin.agent-test`
  - `clj -M:test -n digdir.skills.builtin.agent-test -n digdir.skills.builtin.retrieval-test`
- Started and completed Objective 7 in `server/src/digdir/skills/builtin/agent.clj`.
- Added explicit loop budgets with defaults for:
  - search passes
  - read operations
  - cumulative read content length
- Enforced budgets directly in tool execution:
  - `search` now refuses calls once the search budget is exhausted
  - `read_chunks` now refuses calls once read-operation or read-content budget is exhausted
- Added budget-aware guidance:
  - low remaining search budget pushes re-search toward canonical doc families and exact metric phrasing
  - low remaining read budget pushes the agent toward precise chunk IDs rather than broad range reads
  - exhausted budgets tell the agent to work with already-read evidence and answer with explicit uncertainty when needed
- Added budget state to:
  - trace headers
  - agent outputs/metadata
  - effective parameter logging
- Decision: keep budget enforcement at the tool boundary, not inside the LLM loop itself. This makes budget behavior deterministic and easy to audit in traces.
- Added agent tests covering:
  - hard stop on extra search calls
  - hard stop on extra read calls
  - low-budget re-search guidance
  - trace logging of budget state
- Verified with:
  - `clj -M:test -n digdir.skills.builtin.agent-test`
  - `clj -M:test -n digdir.skills.builtin.agent-test -n digdir.skills.builtin.retrieval-test`
- Started and completed Objective 8 in `server/test/digdir/skills/builtin/agent_integration_test.clj`.
- Added a new deterministic fixture:
  - `server/test/fixtures/agent/07_evidence_aware_second_pass.edn`
- Added a deep integration test that verifies:
  - first-pass search/read/rerank/generate ends in structured insufficiency
  - generate-response guidance contains a concrete targeted re-search suggestion
  - the next search follows that suggestion
  - the agent reads new evidence
  - the second pass reranks, generates, and succeeds
- Decision: use a deep integration path with real `execute-tool-call`, mocked retrieval/rerank/synthesis sub-skills, and mocked chunk reads. This exercises the actual agent workspace, sufficiency state, and trace output rather than only scripted shallow tool strings.
- Verification confirms:
  - tool sequence across both passes
  - read history and search history growth
  - first sufficiency status is `:insufficient`
  - second sufficiency status is `:enough`
  - final answer contains the recovered numeric fact
- Verified with:
  - `clj -M:test -n digdir.skills.builtin.agent-integration-test`
  - `clj -M:test -n digdir.skills.builtin.agent-test -n digdir.skills.builtin.retrieval-test -n digdir.skills.builtin.agent-integration-test`

### Objective 1: Structured Insufficiency And Conflict State
Add explicit workspace fields describing why the current evidence failed.

State to capture:
- `:failure-type` one of:
  - `:missing-numeric-fact`
  - `:missing-date`
  - `:missing-entity-resolution`
  - `:wrong-scope`
  - `:wrong-doc-family`
  - `:conflict`
- `:target-entity`
- `:target-year`
- `:target-metric`
- `:doc-family-hint`
- `:evidence-gap-summary`

Implementation notes:
- Keep this deterministic where possible.
- Synthesis may still supply a free-form explanation, but the agent should normalize it into structured state.

Verification:
- Unit tests prove structured failure state is recorded after `generate_response`.
- Trace files include the structured insufficiency/conflict payload.
- Existing agent tests remain green.

### Objective 2: Query Intent Extraction For Retrieval
Add a lightweight query-analysis layer that derives retrieval intent from the current user query and conversation state.

Intent fields:
- `:answer-type` such as `:numeric-fact`, `:definition`, `:comparison`, `:explanation`
- `:entity`
- `:year-or-date`
- `:metric`
- `:doc-family-preference`
- `:scope-signals`

Initial target behavior:
- Numeric fact queries should be identified explicitly.
- Year-bearing questions should preserve year as a first-class retrieval signal.
- Known canonical document families should be represented structurally.

Verification:
- Unit tests for representative query classes.
- Digdir årsverk query resolves to numeric-fact + entity + year + metric + årsrapport preference.

### Objective 3: Intent-Aware Query Generation
Use query intent to produce more useful initial and follow-up search batches.

Desired generation behavior:
- broad query variant
- metric-specific query variant
- canonical-document query variant
- disambiguating query variant

Example output shape for the Digdir query:
- `årsverk Digdir 2022`
- `utførte årsverk Digdir 2022`
- `Digdir årsrapport 2022 årsverk`
- `Digdir ansatte årsverk 2022`

Implementation notes:
- Preserve diversity, but avoid synonym spam.
- Keep query batches inspectable and deterministic enough for tests.

Verification:
- Query planner tests show metric-specific and document-aware variants are generated.
- Agent traces show those variants in real tool calls.

### Objective 4: Query-Aware Chunk Fusion
Evolve `prioritize-chunks` into a more principled fusion layer that rewards answer-bearing evidence.

Primary goal:
- boost chunks whose body text contains the likely answer shape for the inferred query type.

For numeric fact queries, fusion should reward:
- metric term overlap in `content_markdown`
- year match in content or title
- close co-occurrence of metric terms and numbers
- canonical document family matches

Important constraint:
- do not apply identical heuristics inside all three retrieval strategies.
- preserve strategy specialization and fuse afterward.

Verification:
- Retrieval unit/integration coverage shows decisive chunks move materially upward for Digdir årsverk queries.
- Add a retrieval regression requiring `6a80d6499075` to rank significantly higher for metric-specific variants.
- No obvious regressions on existing retrieval/rerank isolation tests.

### Objective 5: Evidence-Aware Re-Search Guidance
Generate follow-up search suggestions from structured failure state plus seen/read evidence.

Guidance sources:
- latest search history
- unread hits
- read history
- structured failure reason
- query intent

Guidance should support cases like:
- same entity + more specific metric
- same entity + canonical document family
- same topic + alternate document family
- conflict-resolution queries

Verification:
- Unit tests for `:missing-numeric-fact`, `:wrong-doc-family`, and `:conflict`.
- Agent tests confirm follow-up search suggestions are concrete and not merely "search again."

### Objective 6: Conflict Path
Introduce a distinct conflict path rather than collapsing everything into insufficiency.

Behavior:
- `:insufficient` means not enough decisive evidence yet.
- `:conflict` means multiple plausible values/scopes were found.

Conflict should trigger:
- preference for canonical summary sources,
- explicit scope-disambiguation queries,
- different tool guidance than the generic re-search path.

Verification:
- Unit tests for conflict detection and conflict-specific search guidance.
- Trace output clearly distinguishes `:conflict` from `:insufficient`.

### Objective 7: Loop Budgeting
Make re-search and read guidance aware of remaining budget.

Budget dimensions:
- max search passes
- max read operations
- max cumulative content length

Desired behavior:
- earlier passes can stay broad,
- later passes should narrow to canonical sources and exact metric phrasing,
- the agent should stop expanding when the remaining budget is too low.

Verification:
- Agent tests demonstrate budget-aware guidance messages.
- Trace output includes remaining or consumed budget state.

### Objective 8: Integration Coverage For Second-Pass Success
Add an end-to-end integration case where:
- first pass is insufficient,
- the system records structured failure reasons,
- re-search guidance proposes a better search,
- the second pass reads new evidence,
- and the agent succeeds.

Verification:
- Integration test passes deterministically or under the same live-test gating strategy used elsewhere.
- Trace inspection from the test shows evidence-aware second-pass behavior.

## Implementation Order
Follow this sequence:

1. Objective 1: Structured insufficiency/conflict state
2. Objective 2: Query intent extraction
3. Objective 3: Intent-aware query generation
4. Objective 4: Query-aware chunk fusion
5. Objective 5: Evidence-aware re-search guidance
6. Objective 6: Conflict path
7. Objective 7: Loop budgeting
8. Objective 8: Second-pass success integration coverage

Rationale:
- Objectives 1 and 2 provide the state the later objectives need.
- Objective 4 improves immediate retrieval quality.
- Objectives 5 through 7 make the loop adaptive instead of reactive.
- Objective 8 proves the whole system can recover, not just rank better.

## Verification Strategy

### Unit Tests
- insufficiency/conflict normalization
- query intent extraction
- query generation outputs
- retrieval prioritization signals
- re-search guidance selection
- budget-aware agent behavior

### Integration Tests
- retrieval ranking of decisive chunks
- synthesis grounding with full chunk content
- second-pass search/read success after structured insufficiency

### Trace Validation
Each milestone should produce traces that make the following visible:
- inferred query intent
- structured insufficiency/conflict state
- why a chunk was prioritized
- why a follow-up search was suggested
- what budget remained at each decision point

## Exit Criteria
This effort is successful when:
- decisive evidence ranks earlier for broad fact queries,
- the agent records structured reasons for failure,
- second-pass retrieval guidance is specific and evidence-aware,
- conflict and insufficiency take different paths,
- and the system succeeds on targeted second-pass integration cases without reintroducing opaque chunk manipulation.
