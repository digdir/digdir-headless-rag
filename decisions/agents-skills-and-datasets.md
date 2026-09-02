# Architecture Decision: Agents, Skills, and Datasets

**Date**: 2026-03-25
**Status**: Accepted
**Decision**: Treat pipelines as dataset materialization definitions, keep skills generic, introduce first-class agent definitions, and scope conversations to agents rather than pipelines

---

## Context

The current system has evolved along two partially overlapping axes:

- **Pipelines** define ingestion/materialization behavior and retrieval configuration for a document corpus.
- **Skills** and **skill graphs** define runtime capabilities and execution workflows.

Recent work has surfaced two modeling issues:

1. A `pipeline` is effectively the closest thing the system has to a **RAG dataset**. It defines how external documents are processed into a persistent, read-only retrieval substrate.
2. Conversations are still too tightly coupled to a specific pipeline, even though the system is moving toward multi-skill and multi-dataset agentic behavior.

At the same time, the system now has enough skills and skill graphs to make a sharper distinction between:

- the **knowledge substrate** being queried
- the **capabilities** used to operate over that substrate
- the **policy-governed actor** that decides what to do in a conversation

Without this distinction, the model blurs:

- dataset identity
- retrieval capability
- workflow composition
- conversational behavior and guardrails

This makes the API model less explicit and makes it harder to support:

- multiple datasets per tenant
- agent-specific tool access
- conversations spanning multiple datasets
- clean permission boundaries
- future multi-agent or agent-as-product designs

---

## Decision

We adopt a three-layer model:

### 1. Datasets: pipelines

Pipelines are the system's **dataset materialization definitions**.

A pipeline defines:

- source location and source type
- ingestion/materialization configuration
- chunking and indexing behavior
- persistent storage collections
- retrieval-oriented dataset defaults

Conceptually, a pipeline is a **dataset definition**, even if the implementation and namespace naming continue to use the term `pipeline`.

### 2. Capabilities: skills and skill graphs

Skills are **generic executable capabilities**.

Examples:

- retrieve
- rerank
- synthesize
- fact-check
- summarize
- plan

Skill graphs are **composed workflows** over skills.

Skills and skill graphs should remain generic and reusable. They should not become the primary place where dataset identity is hard-coded.

### 3. Product/runtime actor: agents

Agents are **policy-governed actors** with agency.

An agent definition should include:

- purpose and behavioral specification
- instructions/system prompt
- accessible skills and/or skill graphs
- allowed dataset refs
- guardrails and constraints
- answer style / response policy
- fallback or escalation policy
- optional memory policy

Agents decide what to do. Skills do not.

---

## Naming Model

- **Pipeline** = dataset materialization definition
- **Dataset ref** = explicit concrete tuple identifying a materialized dataset:

```clojure
{:tenant "altinn-docs"
 :dataset-config-key "assistant"}
```

- **Skill** = generic capability
- **Skill graph** = reusable workflow over skills
- **Agent** = policy-governed runtime actor with agency

---

## Conversation Scope

Conversations should be scoped to an **agent**, not to a single pipeline.

Persisted conversation identity should answer:

- which agent is responsible for the conversation
- which datasets were available or allowed
- which datasets were actually used per turn

It should not assume that one conversation maps to one dataset.

This supports:

- multi-dataset retrieval
- dataset selection by the agent
- richer guardrails
- future specialization of agents without changing conversation identity semantics

---

## Dataset Access Model

Concrete dataset access should be modeled explicitly as **structured refs**, not composite IDs and not parallel arrays.

Example:

```clojure
[{:tenant "altinn-docs" :dataset-config-key "assistant"}
 {:tenant "ka" :dataset-config-key "kudos"}]
```

This is the canonical representation for access grants such as API key permissions.

We reject:

### Rejected: parallel collections

```clojure
{:tenants ["altinn-docs" "ka"]
 :environments ["dev"]
 :pipelines ["assistant" "kudos"]}
```

Reason:
- does not preserve tuple membership
- introduces ambiguity
- encourages UI and validation bugs

### Rejected: composite pipeline IDs as public/runtime identity

```clojure
"altinn-docs:dev:assistant"
```

Reason:
- hides independent dimensions inside a string
- encourages parsing/formatting logic at every call site
- is less explicit than structured refs

Composite IDs may remain as internal implementation details only where strictly justified, but not as the primary application-facing model.

---

## Relationship Between Skills and Datasets

Skills should remain generic.

For example, the primitive capability should be closer to:

```clojure
(retrieve {:dataset-ref {:tenant ...
                         :dataset-config-key ...}
           :query ...})
```

not:

```clojure
(query_altinn_docs ...)
(query_kudos ...)
```

as the primary internal abstraction.

However, dataset-bound tools are still useful at the **agent tool surface**.

So the preferred pattern is:

- internal capability: generic retrieval skill
- external tool surface: dataset-bound aliases or bindings where useful

Examples of agent-visible tools:

- `query_altinn_docs`
- `query_kudos`

These should be implemented as bindings over generic skills plus explicit dataset refs, not as duplicated dataset-specific retrieval engines.

---

## Why This Model

This model cleanly separates:

- **what exists**: datasets
- **what can be done**: skills
- **how work is composed**: skill graphs
- **who decides**: agents

It gives us:

- clearer API contracts
- cleaner permission modeling
- support for multiple datasets per tenant
- support for conversations that can touch more than one dataset
- better long-term alignment with agentic orchestration

---

## Consequences

### Positive

- Makes dataset identity explicit and structurally correct
- Prevents ambiguity in access grants
- Lets conversations outgrow one-pipeline assumptions
- Makes agents first-class product/runtime units
- Supports dataset-bound tools without corrupting the internal skill model

### Negative

- Requires a broad refactor across persistence, routes, UI, and tests
- Requires a new agent model in storage and runtime
- Requires migration of existing composite `pipeline-id` usages and public dataset-ref shapes
- Introduces an additional top-level concept: `agent`

### Neutral / acceptable

- Existing `pipeline` naming may remain in code even though conceptually it means dataset materialization
- Some internal composite uniqueness keys may remain if they are purely storage details

---

## Implementation Direction

The expected refactor direction is:

1. Introduce explicit dataset refs as the canonical access model
2. Remove composite `pipeline-id` as a legacy public/runtime identity
3. Introduce first-class agent definitions
4. Scope conversations to agents
5. Record dataset usage per turn or execution, rather than embedding a single pipeline identity at conversation scope
6. Keep skills generic
7. Optionally expose dataset-bound tool aliases to agents

---

## Non-Goals

This decision does not require:

- renaming all `pipeline` namespaces immediately
- eliminating every internal composite key if it remains purely internal
- finalizing the exact Datahike schema shape for agent definitions in this ADR

Those belong in the implementation plan.

---

## Superseded Assumptions

This decision supersedes the assumption that pipeline should be the primary unit of conversation scope.

It also sharpens the earlier skill-based architecture by making explicit that:

- pipelines are datasets
- agents are not the same thing as skill graphs
- skills should not become dataset-specific primitives

---

## Follow-Up

Create a detailed clean-cut refactor plan covering:

- explicit dataset refs in persistence and APIs
- first-class agent definitions
- conversation scope migration from pipeline to agent
- export -> transform -> fresh DB -> import migration strategy
