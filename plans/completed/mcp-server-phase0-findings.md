# MCP Server Plan — Phase 0 Discovery Findings

Companion to `mcp-server-plan.md`. Records the outputs of the four discovery streams in Phase 0. Decisions and follow-ups feed back into the main plan.

## 1. `assoc-execution-scope` idempotency — CLOSED

**Question.** When `invoke-rag` calls `assoc-execution-scope` externally, will the internal call inside the skills layer double-lift opts?

**Answer.** No. Calling it twice is safe.

**Evidence.** `server/src/digdir/execution/scope.clj:43-57`:

```clojure
(defn assoc-execution-scope
  [opts {:keys [tenant dataset-config-key dataset-ref agent-id entity]}]
  (let [skill-params (cond-> (or (:skill-params opts) {})
                       tenant (assoc :tenant tenant)
                       dataset-config-key (assoc :dataset-config-key dataset-config-key)
                       ...)]
    (cond-> (assoc opts
                   :tenant tenant
                   :dataset-config-key dataset-config-key
                   :skill-params skill-params)
      dataset-ref (assoc :dataset-ref dataset-ref)
      agent-id (assoc :agent-id agent-id))))
```

Each branch is a straight `assoc` with the same value the caller passed. There is no toggling, no defaulting that would diverge between calls. The internal callsite at `server/src/digdir/skills/context.clj:93` derives the same scope values from `opts` via `apply-dataset-context`, then re-asserts them; the second call is a no-op when the first put the same values in.

**Phase 1 implication.** Drop the "audit `run-skill-graph` for idempotency" Risk. `invoke-rag` can call `assoc-execution-scope` unconditionally without coordinating with the skills layer.

## 2. Skill-graph inventory and pruning — CLOSED (decisions locked)

**Question.** Can MCP tool `inputSchema`s be generated reflectively from skill-graph registrations, or do we need a manual mapping table?

**Answer.** *Was* a manual mapping table; the scope cut below collapses everything onto one input shape and adds explicit per-skill-graph schemas. No mapping table needed.

### Decisions (Phase 0 actions)

The product decision is to prune aggressively. Phase 0 executes the following — there are no live API clients to coordinate with (operator confirmed; see §4), so we delete rather than deprecate.

**Preserve only three `:builtin/*` skill-graphs** (as MCP tools):

- `:builtin/fact-checker`
- `:builtin/agent-rag-graph-bundled`
- `:builtin/agent-rag-graph-faithful`

**Drop these `:builtin/*` registrations** (not MCP tools, not used elsewhere):

- `:builtin/simple-qa`
- `:builtin/research-assistant`
- `:builtin/retrieve-only` (retrieval-only is deprecated wholesale, no MCP equivalent)
- `:builtin/agent-rag` (the original ReAct-shaped agent; superseded by the graph-structured variants)

**Keep but hide from MCP tool listing** — these are orchestration sub-graphs the preserved tools depend on internally:

- `:builtin/agent-iteration-bundled` (inner sub-graph of `agent-rag-graph-bundled`)
- `:builtin/agent-iteration-faithful` (inner sub-graph of `agent-rag-graph-faithful`)

**Rename and rehome the demo graphs as `:docs/*`** under a new `:builtin/docs-agent`:

| Old id | New id | Source |
|---|---|---|
| `:demo/outline-graph` | `:docs/outline-graph` | `server/src/digdir/demo/altinn_authoring.clj` |
| `:demo/translation-drift` | `:docs/translation-drift` | `server/src/digdir/demo/altinn_translation_drift.clj` |
| `:demo/release-cross-check` | `:docs/release-cross-check` | `server/src/digdir/demo/altinn_release_notes.clj` |
| `:demo/release-cross-check-v2` | `:docs/release-cross-check-v2` | `server/src/digdir/demo/altinn_release_notes.clj` |
| `:digdir.demo/enrich-one-chunk` | `:docs/enrich-one-chunk` | `server/src-dev/digdir/demo/self_improve_graph.clj` |
| `:digdir.demo/self-improve-graph` | `:docs/self-improve-graph` | same |
| `:digdir.demo/enrich-one-chunk-facts` | `:docs/enrich-one-chunk-facts` | `server/src-dev/digdir/demo/self_improve_facts_graph.clj` |
| `:digdir.demo/self-improve-facts-graph` | `:docs/self-improve-facts-graph` | same |
| `:digdir.demo/enrich-one-chunk-phrases` | `:docs/enrich-one-chunk-phrases` | `server/src-dev/digdir/demo/self_improve_phrases_graph.clj` |
| `:digdir.demo/self-improve-phrases-graph` | `:docs/self-improve-phrases-graph` | same |

(The src-dev graphs surface a couple of additional `self-improve-*` variants that were missed by the initial inventory — picked up via `server/test/digdir/demo/self_improve_*_test.clj`.)

**Create `:builtin/docs-agent`** — new agent registration whose `:agent/allowed-skill-graphs` is the `:docs/*` set above. Description anchored to the docs-curation use case (rename and exact wording TBD during the registration edit).

### Single input shape

After pruning, the kept user-facing tools share **one** input shape: `agent-with-overrides`:

```
{:user-query string                  ;; required
 :conversation-history vec?          ;; optional, default []
 :model string?                      ;; optional, agent runtime config wins if absent
 :temperature number?                ;; optional, agent runtime config wins if absent
 :claim string?}                     ;; optional, fact-checker only — defaults to :user-query
```

Collections are *never* user inputs — they come from the agent's bound dataset-config. The `:claim` input is treated as an optional override that only `:builtin/fact-checker` reads.

### Explicit schemas on skill-graph registrations

Add `:input-schema` (Malli — the codebase already uses Malli for API route schemas) to the three preserved `:builtin/*` graphs and the renamed `:docs/*` graphs. The MCP server reads these directly; no mapping table.

`digdir.skills.templates.core/make-skill-graph` grows an optional `:input-schema` field on the registration map. Reflection-based generation drops out as a follow-up question — we now declare schemas explicitly where it matters.

**Plan implication.** Phase 3's per-agent input schema risk closes entirely. The `digdir.mcp.schemas` mapping table is replaced by `(get-skill-graph id) -> :input-schema`.

## 3. Conversation lifecycle scan — CLOSED with action items

**Schema** (`server/src/digdir/data/db.cljc:44-421`):

- `:conversation/id` (string, unique identity)
- `:conversation/topic` (string)
- `:conversation/created` (long, ms timestamp)
- `:conversation/agent-id` (string)
- `:conversation/user-id` (string)
- `:conversation/folder`, `:conversation/tags`, `:conversation/view-mode`
- `:conversation/type` — distinguishes API vs. playground conversations
- `:conversation/tenant`, `:conversation/dataset-config-key`, `:conversation/skill-graph-id` — scoping
- `:conversation/messages` — ref to message entities

**Cleanup story.** None. Persisted conversations have no TTL. The only "cleanup" in the codebase is:

- `playground/core.cljc` `cleanup-old-executions!` — in-memory `!playground-executions` atom, not persisted conversations. Wrong scope.
- `api/rate_limit.clj` `cleanup-old-entries!` — auth endpoint brute-force rate-limit state. Wrong scope.

**Rate-limiting on `/api/rag`, `/api/retrieve`, `/api/skill-graphs/*`.** None.
`api/rate_limit.clj` only wraps the `/auth` endpoint (`wrap-rate-limit` is conditional on `(or (= uri "/auth") (str/starts-with? uri "/auth/"))`, lines 65-66). API endpoints have no application-level rate limit today.

**Phase 3 implications.**

1. **Conversation cleanup for the MCP server.** Decide between (a) bounded retention (e.g. delete conversations untouched for N days), (b) per-API-key quota, or (c) no cleanup, mirroring today's API. Lean: **(c) for first ship.** Persisted conversations are durable today and customers have no expectation of cleanup. Defer to a follow-up if storage becomes a concern.

2. **Rate-limiting.** MCP `tools/call` should be rate-limited per API key. The current `wrap-rate-limit` middleware is auth-specific and not reusable as-is. Add a new `wrap-api-rate-limit` keyed on `:api-key/id`. **Make this a Phase 3 sub-task**, not a future plan — the absence of rate-limiting today is a gap MCP shouldn't widen.

## 4. Traffic audit — CLOSED

**Operator confirmed: no live endpoint clients.** No deprecation risk. The `:api-key/usage-count` field is per-key total (incremented in `validate-api-key` at `config/api_keys.clj:836`, called from `wrap-api-key-auth` at `endpoints.clj:671`), but we no longer need a breakdown — there are no customers to coordinate with.

**Plan implication.** The deprecation-window machinery (`Deprecation` headers, `Sunset` headers, `MIGRATION.md`) is unnecessary. Phase 0 prunes the routes outright instead of soft-deprecating. Phase 2 of the original plan collapses entirely. Phase 5 ("retire later") also disappears — retirement happens in Phase 0.

## Operator action items (resolved)

1. ~~Run a traffic audit script.~~ **Not needed** — no live endpoint clients. Phase 0 hard-deletes the routes.
2. **Public `_meta` payload contract.** Approved: opt-in via a `_meta.include` request field, default-off. Wire payload stays small for typical clients; power users opt in for `agent_trace`, `search_history`, per-step timings, etc. Phase 3 implements.
3. ~~Set sunset dates.~~ **Not needed** — Phase 0 prunes the deprecated paths immediately. No sunset window because there is nothing to give notice for.

## Phase 1 risk-list delta

Closed:
- ✅ "Implicit `assoc-execution-scope` contract" → idempotent, safe.

Shrunk:
- "Per-agent input schema generation" → three-row mapping table; not a Phase 3 blocker.

Added:
- **Rate-limiting gap.** Today no application-level rate limit exists on `/api/*`. MCP must not ship without one. Becomes a Phase 3 sub-task.

Unchanged:
- "Playground diagnostics map is large." Still needs the snapshot test in Phase 1.
- "`streaming-public-api-plan.md` overlap." Still relevant for Phase 4.
- "Auth gap for tool listing." Still relevant for Phase 3.

## Phase 0 code work (sequenced)

With no live clients and no deprecation window, Phase 0 is now an execution phase, not a comms phase. The work, in dependency order:

1. **Flip the Playground default skill-graph** from `"builtin/agent-rag"` to `"builtin/agent-rag-graph-bundled"` (`server/src/digdir/playground/core.cljc:442` and `:739`). Prerequisite for dropping `:builtin/agent-rag`.
2. **Rename `:demo/*` → `:docs/*`** in `server/src/digdir/demo/altinn_*.clj` and any callers.
3. **Rename `:digdir.demo/*` → `:docs/*`** in `server/src-dev/digdir/demo/self_improve_*.clj` and their tests.
4. **Create `:builtin/docs-agent`** registration pointing at the renamed `:docs/*` set.
5. **Drop the four unused `:builtin/*` skill-graph registrations** from `server/src/digdir/skills/templates/builtin.clj`. Inner agent-iteration sub-graphs stay registered — they back the preserved tools.
6. **Add `:input-schema` to `make-skill-graph`** in `digdir.skills.templates.core` and declare schemas on the kept `:builtin/*` and `:docs/*` graphs.
7. **Hard-delete API surfaces**: `api-rag-handler`, `api-retrieve-handler` from `handlers.clj`; `execute-skill-graph-handler`, `execute-graph-handler` from `endpoints.clj`; routes from `routes.clj`/`endpoints.clj`; OpenAPI paths from `openapi.yaml`; endpoint docs (`rag.md`, `retrieve.md`); the related tests in `routes_test.clj`. Remove now-orphan helpers (`build-rag-skill-params`, `build-retrieval-skill-params`) from `api/util.clj` if they have no other callers.
8. **Test/lint pass** (`bb lint`, `bb test`).

Phase 1 (`invoke-rag` extraction) starts after this — and is now scoped purely to the Playground-only surface, since the API endpoints will be gone.
