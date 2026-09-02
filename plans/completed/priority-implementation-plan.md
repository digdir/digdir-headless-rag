# Integrated Priority Implementation Plan (2026-03-29)

## Workstream 1: Identity Clean-Cut (Priority 1)
**Goal:** Remove legacy `pipeline-id` as a primary runtime identifier and move to `agent-id` + `dataset-ref`.

### Phase A: API & Middleware Refactor [COMPLETED]
1.  **Modify `api/routes.clj`**:
    *   Update `/api/rag` and `/api/chat` to accept an `agent` string (ID) instead of `pipeline`.
    *   Update API key middleware to validate against `:api-key/agent-refs` and `:api-key/dataset-refs`.
    *   **Removed** `pipeline-id` fallback and translation logic. All conversational APIs now require explicit `agent-id` and `dataset-ref`.
2.  **Modify `data/db.cljc`**:
    *   Add `:conversation/agent-id` to the schema.
    *   **Decision:** Migration logic for existing conversations was deemed unnecessary for this greenfield stage and has been removed from the code for clarity.

### Phase B: Playground Cutover [COMPLETED]
1.  **Update `playground/core.cljc`**:
    *   Change the conversation creation signature to require `agent-id`. [DONE]
    *   Derive the "Available Datasets" for the UI by querying the selected Agent's `:allowed-dataset-refs`. [DONE]
2.  **Update `playground/ui.cljc`**:
    *   Replace the "Pipeline Selector" with an "Agent Selector." [DONE]
    *   Update diagnostics to display the `agent-id` and the specific `dataset-ref` (labeled as Dataset) used for each turn. [DONE]

---

## Workstream 2: Activate Solver RAG Loop (Priority 2)
**Goal:** Orchestrate metadata-only search and selective reading to cut token costs by up to 70%.

### Phase A: Tool Integration in `agentic-loop` [COMPLETED]
1.  **Refactor `agent.clj` Tool Definitions**:
    *   Update the `search` tool to use `rag/retrieve-chunk-metadata-by-id`. [DONE]
    *   Update the `read` tool to support `doc_num` + `chunk_range` (context expansion). [DONE]
2.  **Implement the Sufficiency Gate**:
    *   Add a logic step after the first `search` + `read` turn to evaluate if the evidence in the "Workspace" is sufficient. [DONE - via `sufficiency-decision` in `agent.tools`]

### Phase B: Cost-Aware Truncation [COMPLETED]
1.  **Enable `max_content_length`**:
    *   Wire the LLM's ability to "skim" chunks (requesting only the first 500 chars) for broad document analysis. [DONE]
    *   Implement server-side truncation in `rag/core.cljc` for the `read` tool. [DONE]

---

## Workstream 3: Decompose the Agent Monolith (Priority 3) [COMPLETED]
**Goal:** Split the 3,000-line `agent.clj` into functional, testable namespaces.

---

## Workstream 4: Formalize Dataset Contracts (Priority 4) [COMPLETED]
**Goal:** Finalize `Dataset-root` as the absolute source of truth for materialization.

### Implementation Tasks [COMPLETED]
1.  **Strict Executor**:
    *   Modify `pipeline/executor.clj` to throw an exception if a required materialization key is missing from the `Dataset-root` config tree.
    *   **Removed** the `fallback-materialization-defaults` map from code entirely.
2.  **Configuration Audit**:
    *   Run `bb seed-target-materialization-defaults` across all environments to ensure no "Silent Failures" occur after the code fallbacks are removed. [DONE]
3.  **UI Verification**:
    *   Update the Admin UI to flag any Dataset leaves that are missing "Strict Contract" values (e.g., missing search phrase model or storage prefix). [DONE]

---

## Verification & Success Criteria

| Priority | Success Metric | Status |
| :--- | :--- | :--- |
| **Clean-Cut** | Zero legacy `pipeline-id` refs in `api/routes.clj` and `playground/core.cljc`. | **SUCCESS** |
| **Solver Logic** | Average "Read" tokens per query reduced by >50%. | **SUCCESS** |
| **Decomposition** | No single agent-related namespace exceeds 800 lines of code. | **SUCCESS** |
| **Dataset Contracts** | `executor.clj` has zero hard-coded materialization values. | **SUCCESS** |
