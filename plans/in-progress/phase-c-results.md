# Phase C — `:digdir.demo/self-improve-agent`

Date: 2026-05-17

## What landed

An offline meta-agent that wraps the Phase A eval-suite + Phase B
propose/apply skills behind a ReAct loop, with the public-docs corpus
as its only allowed dataset scope. Lives in
`server/src-dev/digdir/demo/self_improve_agent.clj` (src-dev so the
enrichment skills it depends on are reachable; production builds
without src-dev fall through the `try/require` in `digdir.config.db`
gracefully).

### Four ReAct tools

| Tool | Wraps | Use |
|------|-------|-----|
| `analyze_corpus` | direct Typesense calls | corpus stats (chunk count, doc count, enrichment-row count, sample) — cheap |
| `propose_questions_for_chunk` | `:builtin/enrichment-propose-questions` | generate K (default 4) hypothetical questions for one chunk |
| `apply_enrichments` | `:builtin/enrichment-apply-questions` | batch-write proposals; normalizes string-keyed LLM args to kebab-keyword skill inputs |
| `run_eval_delta` | `:builtin/enrichment-eval-suite` | run agent-budget-benchmark, with or without enrichment retrieval — EXPENSIVE |

All four execute-fns wrap their bodies in `safe-execute` so any
throwable becomes a JSON `:error` envelope on the LLM-facing side.
Tools return compact JSON the agent can re-emit and reason over.

### Agent definition

```clojure
{:id "digdir.demo/self-improve-agent"
 :default-skill-graph "builtin/agent-rag"     ; inherits search/read tools
 :allowed-skill-graphs ["builtin/agent-rag"]
 :allowed-dataset-scopes [{:tenant "digdir"
                           :dataset-config-key "public-docs"}]
 :enabled? true}
```

System prompt explicitly tells the LLM:
- Use `analyze_corpus` to orient
- Use `search_documents`/`read_chunks` to find weakly-retrievable chunks
- Use `propose_questions_for_chunk` per candidate (it can review questions before applying)
- Use `apply_enrichments` to batch-commit
- Use `run_eval_delta` SPARINGLY (once per session) to measure delta

## Verification

- **Unit tests** (`server/test/digdir/demo/self_improve_agent_test.clj`):
  10 tests / 44 assertions covering all four tools.
  - `analyze_corpus` shapes stats + sample correctly
  - Errors come back as JSON `:error`, not exceptions
  - `propose_questions_for_chunk` fetches chunk content from Typesense
    before calling the skill; rejects missing chunks
  - `apply_enrichments` normalizes string-keyed LLM args to
    kebab-keyword skill inputs (including nested provenance keys);
    passes `:dry-run?` through
  - `run_eval_delta` builds `--retrieval-params` only when enrichment
    is enabled (baseline runs don't carry the override)
  - Agent definition has the required `:id`, `:default-skill-graph`,
    `:allowed-dataset-scopes`, `:enabled?` fields
- **Live DB seeding** verified end-to-end:
  ```
  All agent ids:
   - builtin/agent-rag-agent
   - builtin/fact-checker-agent
   - builtin/research-assistant-agent
   - builtin/retrieve-only-agent
   - builtin/simple-qa-agent
   - demo/altinn-authoring
   - digdir.demo/self-improve-agent — Public-Docs Self-Improvement Agent enabled?: true
  ```
- **Tools registered**: `#{propose_questions_for_chunk analyze_corpus
  run_eval_delta apply_enrichments}` (alongside the standard
  search/read tools registered by `builtin/agent-rag`).
- **Wide test sweep**: 173 tests / 665 assertions across the
  Phase A/B/C namespaces — all green.

## Not yet done

- **End-to-end smoke through the playground/UI.** The agent is in
  the DB; manual test from the user side would confirm the
  `:allowed-dataset-scopes` resolution path works in the runtime
  agent loop. We did unit-level verification of the dispatch
  pieces; live invocation is an obvious next check before declaring
  victory.
- **The other three enrichment types.** Phase D adds
  `propose_verified_phrases_for_chunk`, `propose_facts_for_chunk`,
  `propose_kg_node_for_doc` as their underlying skills land.
- **Auto-driven Phase E loop.** Once Phase D's broader scope makes
  the eval-delta signal trustworthy, we can let the agent run
  unattended.

## Cost discipline

The system prompt explicitly cautions against repeated
`run_eval_delta` calls. A single eval pass on
`altinn3_lansert_stability.edn` takes ~2 minutes wall time and a
few cents of LLM spend; the agent's own ReAct loop adds another
~$0.10-0.50 per session depending on iterations. Worth running with
budget caps in real use.

## Files changed

- `server/src-dev/digdir/demo/self_improve_agent.clj` (new, ~360 lines)
- `server/test/digdir/demo/self_improve_agent_test.clj` (new, ~250 lines)
- `server/src/digdir/config/db.clj` (+9 lines: try/require hook)
