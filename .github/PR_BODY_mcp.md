# Self-Improve Agent (Phases A–D2) + MCP Server

**Base:** `release-v0.1-details` · **Head:** `harmonize-runtime` · **Merges as fast-forward** · 73 commits, 141 files, +16,900 / −3,252.

Two distinct workstreams ride along on the same branch. They share no implementation surface — the self-improve work landed before the MCP plan started, and `bb test` is green for both — so the PR is reviewable as two sequential reads.

| Workstream | Commits | Plan |
|---|---|---|
| **A. Self-improve agent (Phases A–D2)** | 48 (oldest first: `598fb47` → `381e7d2`) | [`plans/in-progress/self-improvement-agent-plan.md`](./plans/in-progress/self-improvement-agent-plan.md) |
| **B. MCP server** | 25 (`4abf590` → `92e7479`) | [`plans/completed/mcp-server-plan.md`](./plans/completed/mcp-server-plan.md) |

---

## Workstream A: Self-improve agent

Adds an enrichment-mode agent that proposes phrases, facts, and questions for indexed chunks, then verifies retrieval and produces structured reports. Multiple iterations of refinement (D1 → D2.21) landed as discrete commits so the history is bisectable.

Highlights:

- **Phases A / B**: in-process eval-suite skill ("the thermometer") + parallel enrichment collection schema; new `:builtin/enrichment-propose-questions` and `:builtin/enrichment-apply-questions` skills.
- **Phase C**: `:digdir.demo/self-improve-agent` wraps the Phase A/B skills behind a ReAct loop with strict tool discipline; sufficiency machinery is suppressed for enrichment-mode agents.
- **Phase D1**: verified-phrases enrichment end-to-end as a skill graph.
- **Phase D2 (D2.8 → D2.21)**: fact-assertions enrichment, retrieval improvements, content-rich `:report` outputs, propose-phrases DISCRIMINATIVE phrasing, etc.
- **Observability + bugfixes**: per-tool detail panels in the Playground, graph-variant live observability, query-planner tool-calls key fix, sufficiency-finalize shortcut suppression for enrichment agents, several smaller fixes from playground smoke tests.

For a per-phase commit map see the linked plan. The Playground sidebar pagination (`71e5300`) and the `markdown` HTML-tag pass-through (`10bda7c`) are smaller standalone fixes that also ride along.

**Reviewer note:** the self-improve work is mature — multiple smoke tests landed, eval-suite gates passed, all tests green. If you've been following along you can skim this half; if not, the plan file is the entry point.

---

## Workstream B: MCP Server

Replaces `/api/rag`, `/api/retrieve`, and `/api/skill-graphs/*/execute` with a Model Context Protocol server at `POST /api/mcp`. The Playground's invocation path becomes the canonical runtime via a shared `digdir.skills.invoke/invoke-rag` function; both the Playground UI and the new MCP server call through it.

### Phases + follow-ups

| Phase | What landed | Commit |
|---|---|---|
| **0**  | Hard-delete legacy `/api/rag`, `/api/retrieve`, `/api/skill-graphs/*/execute`. Prune the skill-graph registry to the kept set. Rename `:demo/*` → `:docs/*`. Add `:builtin/docs-agent`. Declare `:input-schema` on the preserved graphs (Malli). | ends `094f4d0` |
| **1**  | Extract `digdir.skills.invoke/invoke-rag` from the Playground as the canonical RAG runtime contract. Playground delegates; behavior unchanged. | `d7241a0` |
| **3**  | MCP server: `digdir.mcp.{tools,transport}`. `POST /api/mcp` under `wrap-api-key-auth`. Per-API-key rate limiter. Per-agent `inputSchema` synthesized from each kept graph's Malli `:input-schema`. | `d066e4f` + fix `3efc147` |
| **4**  | Progress streaming via Streamable HTTP / SSE. `digdir.mcp.streaming` bridges the agent loop's `progress-fn` to `notifications/progress` frames. Heartbeats; `text/event-stream` excluded from Jetty's GzipHandler. | `84b6d67` |
| **4b** | Per-paragraph token streaming through `call-llm`. Paragraph-or-250ms chunker emits coherent `:response/chunk` events. New `digdir.llm.openai/streaming-chat-completion`. | `354dca6` |
| **Follow-ups 4 + 5** | Client-disconnect cancellation (SseBody cancel-atom watch → `Future.cancel(true)` → agent loop's `Thread/isInterrupted` check). Playground diagnostics snapshot test. | `d19765f` |
| **Follow-up 1** | Real-client conformance check (`server/scripts/mcp_conformance_check.clj`) — boots an ephemeral Jetty with the full middleware stack and walks through seven JSON-RPC checks. Surfaced + fixed a scope-resolution bug for empty `:allowed-dataset-scopes`. | `c43f359` |
| **Follow-up 2** | Public docs: [`server/docs/api/endpoints/mcp.md`](./server/docs/api/endpoints/mcp.md), README update, OpenAPI `/api/mcp` entry. | `0af63bc` |
| **Follow-up 6** | OpenWebUI + Playwright E2E harness: `server/e2e/` with docker-compose stack (dev server + MCPO + Open WebUI), seed script, Playwright scaffold, five `bb e2e:*` tasks. | `6e5b0e7` |
| **Follow-up 3 + N3** | File the plan to `plans/completed/`. Drop the stale `RagRequest`/`RetrieveRequest`/`RagResponse`/`RetrieveResponse`/`ChunkBasic`/`ChunkDetailed` schemas from `openapi.yaml` (paths were deleted in Phase 0; schemas lingered). | `2f1e2da`, `92e7479` |

### Public-facing changes

**New:** `POST /api/mcp` — full reference at [`server/docs/api/endpoints/mcp.md`](./server/docs/api/endpoints/mcp.md). Speaks JSON-RPC 2.0 over a single endpoint; opens an SSE stream when a `tools/call` request carries `_meta.progressToken`. Per-API-key rate limited (120 req / 60 s).

**Tool naming:** `<agent-id>__<skill-graph-name>` — e.g. `builtin/fact-checker-agent__fact-checker`. Cross-product of `(agent × allowed-skill-graph)` from the agent DB, scoped to the API key's `:agent-refs` and `:skill-graphs`.

**Removed:** `/api/rag`, `/api/retrieve`, `/api/skill-graphs/:id/execute`, `/api/skill-graphs/execute`. Phase 0 confirmed there are no live API clients to migrate.

**Kept unchanged:** `/api/conversations/*`, `/api/datasets`, `/api/skills`, `/api/skill-graphs` (listing).

### Reviewer smoke test (~10s)

```
cd server
clojure -J-Dlogback.configurationFile=scripts/logback-quiet.xml \
        -M -e '(do (load-file "scripts/mcp_conformance_check.clj")
                   (mcp-conformance-check/-main))'
```

Boots a Jetty with the production middleware stack on an ephemeral port and exercises `initialize`, `tools/list`, `tools/call`, `ping`, `notifications/initialized`, the unknown-method path, and a streaming `tools/call`. Output is one ✓ line per check. Add `"-v"` to the args for full per-step payloads.

---

## Test surface (both workstreams)

`bb test`: **1311 tests / 4694 assertions / 0 failures.** 35 of those tests are new in this PR and cover the MCP work: `invoke-rag` (output extraction, error envelope, progress-fn pass-through), the rate limiter, the MCP tools and transport namespaces, the SSE writer + event-to-progress mapping, the paragraph chunker, the streaming `call-llm` branch, client-disconnect cancellation, the Playground diagnostics snapshot, and the scope-resolution fallback paths.

## What's NOT in this PR

- **E2E harness execution.** `bb e2e:*` is gated behind explicit task invocation and Docker availability — not part of `bb test`. The compose stack and Playwright scaffold ship, but no human has run `bb e2e:up && bb e2e:test` start-to-finish yet. See *Next Steps* in the MCP plan for what to validate first.
- **Per-token deltas in MCP.** Phase 4b ships *paragraph*-bounded chunks. Per-token deltas remain a deliberate non-goal (high overhead, marginal UX for the report-emitting graphs we care about).
- **MCP-native OAuth.** API-key bearer auth stays behind the MCP transport.
- **stdio transport for MCP.** Streamable HTTP only.

## Risks worth flagging during review

- **Existing brittle tests in `agent_integration_test.clj`.** Eight tests there depend on full-suite test-order init; they're unchanged. The new `loop_behavior_test.clj` (isolation-safe) covers the same surface for the MCP cancellation work. Don't refactor the brittle ones in this PR — see the note in `loop_behavior_test.clj`'s docstring.
- **Empty `:allowed-dataset-scopes` semantics.** The conformance check surfaced this and the fix in `c43f359` is opinionated: empty scopes now mean "agent imposes no restriction" with fallback to API-key scopes / explicit tool args / `TENANT`+`DATASET_CONFIG_KEY` env defaults. Flag if a different policy is wanted.
- **Playground continues to call `invoke-rag` directly in-process.** It doesn't become an MCP client. The two surfaces share the runtime contract via the function, not the wire protocol.
- **Self-improve agent uses src-dev paths.** The `:digdir.demo/*` graphs that back it are loaded from `server/src-dev/` and aren't part of the production uberjar — by design. The `:docs/*` rename in MCP Phase 0 (commit `e2f4c18`) updated all references.
