# Agent Diagnostics Data Flow Improvements

Status: Completed on 2026-04-11.

Review note: The agentic search-attribution fixes, execution summary UI,
execution metadata tab, graph-level timing, trace query extraction, and agent
reasoning prompt update are all present in the current codebase. The current
trace pipeline also carries stage timings from tool calls and graph execution
into the observability UI. Follow-on UI refinement continued under
`plans/completed/playground-ui-observability-v2.md`.

## Context

We have a Clojure skill graph system that executes RAG pipelines. The newest addition is `builtin/agent` — a ReAct-style agentic skill that runs an LLM tool-use loop (search → rerank → generate) with dynamic iteration. The agent works well but the diagnostics data flow from graph execution to the playground UI has several gaps that make it hard to observe and debug what happened.

The data flow path is:

```
Skill Graph Execution (runner.clj)
  → API layer (api.clj)
    → execute-skills-pipeline (playground/core.cljc)
      → DB persistence as EDN string (data/db.cljc)
        → MessageDiagnosticsPanel (playground/ui.cljc)
```

## Improvements to Implement

There are 6 improvements, ordered by priority. Implement all of them.

---

### Improvement 1: Fix Phrases/Search tabs showing zeros for agentic graphs

**Problem:** The Phrases tab always shows `(0)` and the Search tab shows `(0)` when using the agentic skill graph. This is because the agent collects search phrases and chunks internally in its workspace, but the playground's `execute-skills-pipeline` function expects these in a format matching the classic pipeline's `:search-attribution` output.

**Root cause in `server/src/digdir/playground/core.cljc` (around line 577-598):**

```clojure
;; search-attribution is always {} for agentic graphs because the agent
;; doesn't produce :search-attribution in its outputs
search-attribution (or (:search-attribution outputs) {})

;; These all resolve to 0 because search-attribution is empty:
:phrase-search-count (get search-attribution :phrase 0)
:metadata-search-count (get search-attribution :metadata 0)
;; etc.
```

Meanwhile, the agent's outputs DO contain `:search-phrases` (a vector of query strings) and `:chunks` (all retrieved chunks), but they aren't mapped to the diagnostics schema.

**Fix:**
- In `execute-skills-pipeline`, when `:agent-trace` is present in outputs (indicating agentic execution), populate the diagnostics differently:
  - `:query-relaxation` should use the agent's `:search-phrases` (already done, but may be empty if agent skipped `plan_queries` — fall back to extracting queries from the agent trace's `search_documents` tool calls)
  - `:merged-count` should reflect the total chunks the agent collected
  - `:merged-results` should show the chunks (already done)
  - The phrase/metadata/content sub-counts can remain 0 since the agent doesn't distinguish search types — but `:merged-count` should be non-zero

- Extract search phrases from the agent trace if `:search-phrases` is empty:
  ```clojure
  (defn- extract-search-phrases-from-trace [trace]
    (->> trace
         (mapcat :tool-calls)
         (filter #(= "search_documents" (:tool %)))
         (mapcat #(get-in % [:args :queries]))
         distinct
         vec))
  ```

**Files:** `server/src/digdir/playground/core.cljc` — `execute-skills-pipeline` function

---

### Improvement 2: Show execution summary in UI

**Problem:** There's no overview showing total execution time, which model was used, how many steps ran, or whether the agent exhausted its iterations. The `skill-execution-metadata` is stored in diagnostics but never rendered.

**Fix:**
- Add an execution summary bar at the top of `MessageDiagnosticsPanel` (before the tabs), showing key metrics:
  - Model used (from `skill-execution-metadata`)
  - Total iterations (for agentic graphs, from `:agent-trace-count`)
  - Total chunks retrieved
  - Whether execution was exhausted (`:exhausted` flag in metadata)
  - Total search phrases used

- The data is already in `parsed-diagnostics` under `:skill-execution-metadata`:
  ```clojure
  {:iterations 3
   :chunks-retrieved 40
   :search-phrases-used ["phrase1" "phrase2"]
   :exhausted false
   :trace-file "logs/agent-trace-..."}
  ```

- Render as a compact row of stat badges above the tab buttons, e.g.: `Iterations: 3 | Chunks: 40 | Model: gpt-4o`

**Files:** `server/src/digdir/playground/ui.cljc` — `MessageDiagnosticsPanel` component (around line 1217)

---

### Improvement 3: Add reasoning instruction to agent system prompt

**Problem:** The agent trace shows tool calls but no LLM reasoning. GPT-4o typically sets `content: null` when making tool calls — the reasoning goes only into tool selection, not the `content` field. Our trace captures `(:content message)` as the reasoning, which is null.

**Fix:**
- In `server/src/digdir/skills/builtin/agent.clj`, modify `default-system-prompt` to instruct the model to always include its thinking in the content field:

  Add to the beginning of the system prompt (after the first paragraph):
  ```
  IMPORTANT: Always include your reasoning in your message text before making tool calls. Explain what you're about to do and why. This is critical for debugging and observability.
  ```

- This is a tiny change — just add the instruction paragraph to the existing `default-system-prompt` string.

**Files:** `server/src/digdir/skills/builtin/agent.clj` — `default-system-prompt` (around line 295)

---

### Improvement 4: Show skill-execution-metadata in a new Execution tab

**Problem:** `skill-execution-metadata` contains per-skill timing and metrics but is never displayed. For non-agentic graphs, this is the only source of execution detail.

**Fix:**
- Add an "Execution" tab to `MessageDiagnosticsPanel`, conditionally shown when `skill-execution-metadata` is present in diagnostics
- Display the metadata as a simple key-value list or a compact table
- For agentic graphs, this shows: iterations, chunks-retrieved, search-phrases-used, exhausted, trace-file
- For non-agentic graphs, this shows whatever metadata the individual skills produced (e.g., per-step timing if we add it — see improvement 5)

- Add to the tab list (same pattern as agent-trace tab conditional):
  ```clojure
  (when (:skill-execution-metadata parsed-diagnostics)
    [:execution "Execution" :execution-count])
  ```

- Create an `ExecutionMetadataPanel` component that renders the metadata map as a clean key-value display

**Files:** `server/src/digdir/playground/ui.cljc` — new component + tab in `MessageDiagnosticsPanel`

---

### Improvement 5: Track per-step duration and total execution time in graph runner

**Problem:** The graph runner (`server/src/digdir/skills/graph/runner.clj`) merges all step outputs but doesn't track timing. Individual skills may include `:duration-ms` in their metadata, but there's no graph-level timing.

**Fix:**
- In `run-graph` (the main execution function in `runner.clj`), wrap the execution in timing:
  - Record `start-time` before the step execution loop
  - Record `end-time` after all steps complete
  - For each step, record the step start/end time
  - Include in the final result:
    ```clojure
    {:outputs {...}
     :step-results {...}
     :execution-metadata {:total-duration-ms (- end start)
                          :steps-executed N
                          :step-timings {:plan 250 :retrieve 150 :synthesize 200}}}
    ```

- In `execute-skills-pipeline` (playground/core.cljc), extract this timing data and include it in diagnostics:
  ```clojure
  :execution-timing (get-in result [:execution-metadata :step-timings])
  :total-duration-ms (get-in result [:execution-metadata :total-duration-ms])
  ```

- The `ExecutionMetadataPanel` from improvement 4 should display step timings if available

**Files:**
- `server/src/digdir/skills/graph/runner.clj` — `run-graph` function
- `server/src/digdir/playground/core.cljc` — `execute-skills-pipeline` (extract timing)
- `server/src/digdir/playground/ui.cljc` — `ExecutionMetadataPanel` (display timing)

---

### Improvement 6: Unify search attribution between classic and skills paths

**Problem:** The classic pipeline (non-skills path in `execute-playground-chat-pipeline`) stores actual search results with titles, scores, and chunk IDs for each search type (phrase/metadata/content). The skills pipeline only stores count-based mocks:

```clojure
;; Classic — actual results
:phrase-search (mapv enrich-result (take 20 phrase-results))

;; Skills — mocks
:phrase-search (vec (repeat N {:search-type :phrase}))
```

This means the Search tab's sub-tabs (Phrase, Metadata, Content) show nothing useful for skills-based execution.

**Fix:**
- For non-agentic skill graphs (simple-qa, research-assistant), the retrieval skill produces chunks with `:search-types` metadata on each chunk. Use this to split chunks into per-type lists:
  ```clojure
  (defn- split-chunks-by-search-type [chunks]
    {:phrase (filterv #(contains? (:search-types %) :phrase) chunks)
     :metadata (filterv #(contains? (:search-types %) :metadata) chunks)
     :content (filterv #(contains? (:search-types %) :content) chunks)})
  ```

- Populate the diagnostics with actual per-type results instead of mocks
- For agentic graphs where chunks don't have `:search-types`, fall back to showing all chunks under `:merged` only (current behavior, but with correct count)

**Files:** `server/src/digdir/playground/core.cljc` — `execute-skills-pipeline` diagnostics building section

---

## Key Files Reference

| File | Purpose | Key sections |
|------|---------|-------------|
| `server/src/digdir/skills/builtin/agent.clj` | Agent skill with ReAct loop | `default-system-prompt` (~line 295), `record-turn!`, `agentic-loop`, `execute-agent`, `write-trace-file!` |
| `server/src/digdir/skills/graph/runner.clj` | DAG executor for skill graphs | `run-graph` (main execution loop) |
| `server/src/digdir/playground/core.cljc` | Bridges skills to playground | `execute-skills-pipeline` (~line 502-632) — extracts outputs and builds diagnostics |
| `server/src/digdir/playground/ui.cljc` | Playground UI components | `MessageDiagnosticsPanel` (~line 1217), `AgentTracePanel` (~line 1139), `DiagnosticsUsedChunks` (~line 1076), `DiagnosticsResultsTable` (~line 1009) |
| `server/src/digdir/rag/skills/core.clj` | Skill protocol, result helpers | `success-result`, `error-result`, `get-result-outputs`, `get-result-metadata` |
| `server/src/digdir/skills/templates/core.clj` | Skill graph registry | `instantiate-graph`, `list-skill-graphs` |

## Electric/Hyperfiddle Patterns

The UI uses Electric (Hyperfiddle) for reactive rendering. Key patterns:
- `e/defn` for reactive components
- `e/for` with `e/diff-by` for list rendering
- `e/Token` pattern for event handlers (see CLAUDE.md for examples)
- `e/server` / `e/client` for server/client boundary crossing
- `e/watch` on atoms for reactive state

When adding UI components, follow existing patterns in `MessageDiagnosticsPanel` and `AgentTracePanel`.

## Verification

After implementing all changes:
1. `bb lint` — should show no new warnings/errors
2. `bb test` — all tests should pass
3. Test in playground with Agentic RAG skill graph:
   - Phrases tab should show search phrases used by the agent
   - Search tab's Merged sub-tab should show chunks with correct count
   - Agent Trace tab should show LLM reasoning text (not just tool calls)
   - Execution summary should appear above tabs with iteration count, model, timing
   - Execution tab should show metadata and step timings
4. Test with Simple Q&A skill graph:
   - Search sub-tabs should show actual results (not count-based mocks)
   - Agent Trace tab should NOT appear
   - Execution tab should show step timings
