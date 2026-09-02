# Skills, Datasets, and Agents

> **HISTORICAL** — superseded by [`decisions/agents-skills-and-datasets.md`](../../../decisions/agents-skills-and-datasets.md) (the accepted ADR) and `docs/system-overview.md` §5.3–5.4. Kept as a design record; see the "Historical Note" at the end of this file for what it itself replaced.

This document describes the current architecture after the agents/skills/datasets refactor.

For the formal decision, see [decisions/agents-skills-and-datasets.md](../../../decisions/agents-skills-and-datasets.md).

## Current Model

The system now separates three concerns:

- **Datasets**: materialized document corpora, still implemented with the existing `pipeline` terminology
- **Skills**: generic runtime capabilities such as retrieval, reranking, synthesis, and tool orchestration
- **Agents**: policy-governed actors that own instructions, guardrails, accessible skill graphs, and allowed dataset refs

## Dataset Identity

Concrete dataset selection is explicit and structured:

```clojure
{:tenant "altinn-docs"
 :environment "dev"
 :pipeline "assistant"}
```

This is the canonical application-facing representation for:

- API key grants
- low-level retrieval/debug requests
- skill execution context
- agent dataset policies

Composite strings like `altinn-docs:dev:assistant` may still appear as internal compatibility or migration details, but they are no longer the public/runtime model.

## Skills

Skills stay generic. They should operate over explicit inputs, including dataset refs when data access is required.

Examples:

- `retrieve`
- `rerank`
- `synthesize`
- `fact-check`
- `agent`

Dataset-specific tool names such as `query_altinn_docs` are implemented as agent-visible bindings over generic skills, not as separate retrieval engines.

## Agents

Agents are now first-class definitions stored in the config DB. An agent definition includes:

- stable `agent-id`
- name and description
- instructions
- default skill graph
- allowed skill graphs
- allowed dataset refs
- guardrails
- enabled/disabled state

Conversations are scoped to agents, not to a single dataset.

## Conversations and Execution

Conversation identity now answers:

- which agent owns the thread
- which datasets were available to the agent
- which datasets were used during execution

The request/response model is now:

- conversational flows: **agent-scoped**
- low-level data access flows: **dataset-ref-scoped**

## Operator Tooling

Repo tooling should follow the same contract:

- `bb` diagnostics and debug tasks take explicit `<tenant> <env> <pipeline>` arguments
- migration operations are available through `bb migration-export`, `bb migration-transform`, and `bb migration-import`
  - `migration-export` and `migration-import` are steady-state system export/import surfaces
  - `migration-transform` is an explicit cutover surface for historical export normalization and should be deleted when that migration window closes
- admin/API key UI surfaces expose dataset refs and agent grants directly

## Historical Note

This file replaces the earlier implementation plan that mixed historical status, outdated namespace paths, and composite pipeline-ID assumptions. Historical implementation details should be taken from git history or the completed/in-progress planning artifacts instead of this document.
