# Retrospective: Playground UI Observability

## Goal

Expose the agent as an evidence-driven loop in Playground so a single run answers:

- What did the agent think the query meant?
- What did it search for first?
- What did it actually read?
- Why did it search again or stop?
- Was there ambiguity or conflict?
- Did budget constraints influence behavior?
- Which chunk actually supported the final answer?

## Summary of Completed Work

Backend diagnostics now expose `query-intent`, `budget-state`, `search-history`, `read-history`, `sufficiency-decisions`, `last-insufficiency`, built synthesis prompts, and stage-timing metadata in agent traces. The UI has been updated to surface these in `Focused` and `Detailed` view modes within `ui.cljc`.

### Objective 1: Run Summary
- **Status:** Partially Completed.
- **Progress:** Summary data is normalized in `digdir.playground.diagnostics`. Compact stat lines are shown, but a dedicated "Execution summary bar" above the diagnostics tabs is still missing.

### Objective 2: Decision Timeline
- **Status:** Completed.
- **Progress:** Decision timeline now renders in both `Focused` and `Detailed` views, exposing fallback searches, query counts, and structured insufficiency details.

### Objective 3: Search/Read History
- **Status:** Completed.
- **Progress:** Search and read histories are present in diagnostics. UI distinguishes between metadata-only search and full reads.

### Objective 4: Retrieved Evidence Panel
- **Status:** Completed.
- **Progress:** Added `Retrieved evidence` expandable table to `Detailed` view. Supports both agentic and non-agentic paths.

### Objective 5: Retrieval Explanation
- **Status:** Completed.
- **Progress:** The `Why` column in the `Retrieved evidence` table explains ranking signals (numeric evidence, content overlap, etc.).

### Objective 6: Re-search Guidance
- **Status:** Completed.
- **Progress:** Suggested follow-up queries are extracted from re-search decisions and shown inline.

### Objective 7: Conflict and Ambiguity Panel
- **Status:** Minimally Completed (Deferred).
- **Progress:** Settlement was made for minimal summary-chip rendering. Richer conflict-specific panels are still deferred until there is a safer extraction point for new Electric UI blocks.

### Objective 8: Budget Mode Visibility
- **Status:** Completed.
- **Progress:** Budget modes (`normal`, `low`, `exhausted`) are surfaced and annotate timeline entries.

## Known Gaps and Unresolved Issues

### 1. Zero-count Search/Phrases Tabs in Agentic Graphs
While `merged-results` are populated, the sub-type counts (Phrase/Metadata/Content) still show as 0 for agentic runs because the search attribution logic currently bypasses these counts when using the agentic skill graph.

### 2. Missing Execution Summary Bar
The planned horizontal summary bar (Model, Iterations, Duration, Budget) above the diagnostics tabs was not implemented. The metadata exists but is not rendered in the UI.

### 3. Missing Execution Tab
`skill-execution-metadata` (including step-by-step timings) is collected but is currently shown as an expandable section ("Execution timing") rather than a dedicated tab in a unified diagnostics panel.

### 4. Duration Tracking Gaps
Resolved in the follow-up observability pass. Per-step duration tracking now flows through graph execution metadata and is surfaced in the trace UI.

### 5. "Inline Everything" Architectural Ceiling
All observability features have been implemented inline within `ui.cljc`. The file has grown to ~4000 lines, making it increasingly difficult to maintain. Objective 7 (Richer Conflict Panel) was explicitly deferred due to the risks of further inflating this file before component extraction.

### 6. Verification and Testing
Diagnostics normalization functions in `diagnostics.cljc` lack unit tests. Verification has been primarily visual in the UI, without rigorous performance or payload size analysis for large multi-iteration runs.

## Next Steps

A follow-up plan (`plans/completed/playground-ui-observability-v2.md`) addressed these gaps, focusing on:
1. Component extraction from `ui.cljc` to shared blocks.
2. Fixing agentic search count attribution.
3. Implementing the missing execution summary bar and dedicated tabs.
4. Adding unit tests for `diagnostics.cljc`.
