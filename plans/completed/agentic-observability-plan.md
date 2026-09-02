# Strategic Plan: Agentic Observability & Traceability

## 1. Objective
Enable deep visibility into the iterative "Solver" RAG loop within the Playground. Users and developers must be able to understand *why* an agent made certain decisions, *what* it searched for, and *how* it evaluated the evidence it found.

## 2. Strategy: "Turn-Based Traceability"
Shift from a "Flat Diagnostics" model to a "Turn-Based Trace" model.
- **Data Model:** Capture reasoning, tool arguments, and result summaries for every iteration of the agentic loop.
- **UI Presentation:** Replace the static "Agent Status" text with an interactive "Iteration Trace" that allows drilling into specific turns.
- **Granularity:** Distinguish between "Skimming" (metadata-only) and "Reading" (full-content) operations.

## 3. Implementation Plan

### Phase 1: Data Enrichment (Backend)
- **File:** `server/src/digdir/skills/builtin/agent/workspace.clj`
- **Action:** Ensure `record-turn!` captures the full state including sufficiency decisions and budget snapshots.
- **Action:** Add `record-sufficiency-decision!` to track the agent's internal "Enough?" gate.

### Phase 2: Trace Normalization (Frontend Helpers)
- **File:** `server/src/digdir/playground/diagnostics.cljc`
- **Action:** Add `compact-iteration-trace` to prepare raw agent state for the UI.
- **Action:** Normalize tool results (e.g., "Search returned 5 hits" or "Read 3,200 tokens") into human-readable summaries.

### Phase 3: Interactive Trace UI (Frontend)
- **File:** `server/src/digdir/playground/ui.cljc`
- **Action:** Enhance `AgentTracePanel` to support:
    - Collapsible iterations.
    - Syntax-highlighted (or at least formatted) tool arguments.
    - Visualization of "Budget Consumption" per turn.
    - Inline display of sufficiency rationale.

### Phase 4: Integration
- **Action:** Ensure the `execute-agent` loop in `server/src/digdir/skills/builtin/agent/core.clj` correctly threads the trace back to the Playground execution state.
- **Action:** Update the Playground "Execution" tab to prioritize the Trace view for agentic graphs.

## 4. Success Criteria
- [ ] Users can see the exact search queries used in Iteration 1 vs Iteration 2.
- [ ] The "Reasoning" field explains the transition from "Searching" to "Reading".
- [ ] "Skimmed" chunks are visually distinguished from "Read" chunks.
- [ ] Sufficiency decisions (e.g., "Conflict detected - reading more") are clearly visible.
