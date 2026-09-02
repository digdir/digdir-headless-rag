# Budget Regression Test Extension Plan

## Objective
Extend the current test suite so we can do two things at once:

1. Preserve the current efficiency standard as a regression gate.
2. Prove that small, controlled budget increases on specific hard cases allow the agent to succeed where it currently fails.

## Background
The "Solver" loop is now live. It uses `search` and `read_chunks` in a multi-turn cycle.
We have found that some queries (e.g. "Hvor mange ansatte i Digdir?") are hard because the answer is split across multiple documents or requires reading deeper into a specific year.

Currently, the default budget (3 searches, 6 reads, 32k chars) is tight.
If we increase it too much, we risk cost blowouts.
If we keep it too low, we risk "I don't know" answers for hard cases.

## Phase 1: Alignment (Complete)
- [x] fix deterministic assertion drift so the suite matches the current exhausted-budget contract

## Phase 2: Failure Reproduction (The "Tight Budget" Gate) (Complete)
- [x] Create a new integration test file: `server/test/digdir/rag/budget_gate_integration_test.clj`
- [x] Add a test case `test-numeric-fact-fails-on-low-budget`
  - Use a real dataset-ref (e.g. `public-sector-knowledge/dev/kudos`)
  - Set a very low budget (1 search, 1 read)
  - Assert that the agent returns `:answer-with-uncertainty` or `:insufficient-context`
  - Assert that the `sufficiency-decisions` in the trace correctly identify what is missing.

## Phase 3: Success Verification (The "Relaxed Budget" Proof) (Complete)
- [x] Add a test case `test-numeric-fact-succeeds-on-relaxed-budget`
  - Use the same query/dataset as Phase 2.
  - Set a slightly relaxed budget (2 searches, 3 reads).
  - Assert that the agent finds the answer.
  - Verify that the `trace` shows the second search/read being triggered by the sufficiency gate.

## Phase 4: CI Integration (Complete)
- [x] Ensure these tests are categorized so they run in CI but don't blow the budget if they fail.
  - Added `^:agent-budget` metadata to the tests.
  - Added `bb budget-gate-test` task.
  - The tests are named `*-integration-test` which follows the project's naming convention for automated integration tests.
  - The tests use extremely tight budgets (1-3 steps) which are much lower than the production default, ensuring they are cost-efficient.
