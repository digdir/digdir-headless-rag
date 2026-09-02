# Consolidated Target Architecture

This document is the living target-state specification for the repository.

It consolidates the intent of the accepted ADRs, the completed architecture notes, and the active implementation plans into one canonical reference for:

- product boundaries
- runtime execution
- configuration resolution
- dataset materialization
- observability and traceability
- UI and API expectations

If a lower-level document conflicts with this one, this document wins.

## 1. Architecture Principles

The target system is organized around a small set of explicit rules:

- explicit inputs over implicit defaults
- one canonical concept per concern
- generic runtime skills over dataset-specific runtimes
- dataset materialization separated from runtime behavior
- adapters allowed at the boundary, but not as permanent parallel models
- all meaningful runtime behavior must be explainable after the fact

The system may keep legacy names in transitional code paths, but the target model itself must remain clean and unambiguous.

## 2. Canonical Domain Model

The target system has three primary runtime concepts and one supporting substrate concept.

### Agents

Agents are the policy-governed actors that participate in conversations with the end user. Agent configuration is resolved at runtime through the hierarchical configuration resolution engine.

An agent definition includes:

- stable `agent-id`, globally unique, user-defined
- name and description
- instructions and behavioral policy
- allowed skill graphs
- allowed dataset refs
- guardrails and escalation policy
- enabled or disabled state

Agents decide what to do. Skills do the work.

### Skills

Skills are generic executable capabilities defined in code, with dynamic inputs coming from a mix of agent-supplied values and resolved configuration values.

Examples of typical skills include:

- query planning
- retrieval
- reranking
- synthesis
- fact checking
- summarization

Skills must remain reusable across different agentic contexts. They should not hard-code dataset identity as their primary abstraction.

### Skill Graphs

Skill graphs are directed acyclic graphs of skills. They are especially relevant for defining multi-step processes and orchestration boundaries.

A skill graph defines:

- the order of execution
- branching or fan-out where needed
- how data flows between skills
- which skill outputs become downstream inputs

The graph is the unit of orchestration, not the individual skill.

### Datasets and Pipelines

Datasets are the read-optimized retrieval substrate.

Pipelines are the materialization definitions that produce and refresh datasets.

In target-state terms:

- `pipeline` means ingestion and materialization
- `dataset-ref` means the concrete, queryable dataset identity

The two are related, but they are not the same thing.

## 3. Configuration Model

The configuration system uses three fixed roots:

- `:platform`
- `:runtime`
- `:dataset`

These roots are fixed by the product. Tenants define the tree shape inside each root.

### Tree Semantics

Within each root:

- each tenant owns its own inheritance tree
- each node has zero or one parent
- inheritance is acyclic
- the nearest matching ancestor wins
- a fixed system-managed chain may exist above the tenant tree where needed

This gives us deterministic configuration and global default values, without forcing every tenant into the same hierarchy shape.

### Request-Facing Node Identity

The request-facing selector for a config node is an explicit root-scoped tenant-local key, not an internal database ID.

Target terminology:

- internal durable identity: `config.node/id`
- request-facing selector: `platform-config-key`, `runtime-config-key`, or `dataset-config-key` depending on root

Because the configuration system has three independent config roots with independent tenant-specific inheritance trees, the API should require explicit root-scoped selectors for any endpoint that resolves configuration. For example, a retrieval endpoint may require explicit selectors for `platform-config-key`, `runtime-config-key`, and `dataset-config-key`.

The canonical request contract should be explicit about the selected node. It should not infer a node from bindings, defaults, or unrelated entity metadata.

### Configuration Resolution Rules

Resolution is explicit and traceable:

1. authenticate the request
2. resolve the requested node
3. authorize the request against the caller's ceilings as specified in the API key
4. validate root-specific compatibility metadata
5. walk the ancestry chain from the requested node upward
6. return the first matching value for each path
7. include a resolution trace

The model is fail-closed:

- missing nodes fail
- disabled nodes fail
- incompatible requests fail
- cross-root lookups fail
- ambiguous selection does not silently choose a node

### Config Definitions and Values

Each config definition belongs to exactly one root.

Each config value belongs to:

- one root
- one tenant
- one node
- one definition

This prevents runtime config from becoming a generic unstructured key-value blob.

### API Key Authorization

API keys do not select config nodes.

They grant ceilings.

Authorization is based on whether the requested node is equal to or below at least one allowed ceiling node for the relevant root.

That keeps access control separate from selection and keeps the selection model understandable.

## 4. Runtime and Conversation Model

Conversations are scoped to agents.

Each conversation still belongs to a tenant, but it may reference multiple datasets over its lifetime.

The conversation record should answer:

- which tenant owns the conversation
- which agent owns the thread
- which datasets were available
- which datasets were actually used
- which skill graph or graph fragment was executed
- what evidence was gathered
- why the system decided it had enough evidence, or why it continued searching

### Execution Contract

Runtime execution should use explicit structured inputs:

- `agent-id`
- `dataset-ref`
- request payload
- explicit config selections where needed

The system should not rely on implicit dataset selection from one-off defaults when the request can be made explicit.

### Skill Runtime Contract

Skills receive:

- resolved inputs
- resolved config values
- injected services
- any required runtime context

Skills should avoid direct, ad hoc config lookup whenever the caller can resolve parameters centrally first.

This keeps skills easier to test and makes their behavior easier to reason about.

### Canonical I/O Vocabulary

Runtime translation should converge on a small canonical vocabulary for data passed between skills and adapters.

Examples include:

- query
- chunks
- context-docs
- search-phrases
- response

Any legacy translation should happen at the boundary, not throughout the codebase.

## 5. Dataset and Pipeline Model

Pipelines define how documents are turned into retrieval-ready datasets.

The pipeline architecture should remain focused on:

- source ingestion
- extraction
- normalization
- chunking
- search phrase generation
- storage/materialization
- telemetry for ingestion jobs

The pipeline layer should not absorb runtime policy, conversational logic, or skill orchestration concerns.

### Dataset Ref Shape

The canonical dataset reference is a structured map.

Example:

```clojure
{:tenant "digdir"
 :dataset-config-key "default"}
```

This ref identifies a concrete dataset node within a tenant's dataset tree.
Tenants may define multiple dataset nodes and select among them explicitly.

This ref is used in:

- API key grants
- skill execution context
- conversational dataset access
- low-level retrieval/debug flows

Pipeline identity is not part of the public dataset-ref shape.
Pipelines remain the materialization layer behind datasets, not the runtime identity surface.

## 6. Observability and Traceability

Traceability is a core architectural requirement, not an optional debugging feature.

The target model is turn-based.

Each agent turn should be able to capture:

- the input that triggered it
- the search or reasoning steps performed
- tool arguments
- tool results or summaries
- sufficiency decisions
- budget snapshots or token-cost context where relevant
- the final action taken on that turn

### Trace Granularity

The trace should distinguish between:

- metadata-only skim operations
- full-content read operations
- evidence gathered from different datasets or sources
- decisions to continue, stop, or escalate

This is the basis for explaining the solver loop in a way both users and developers can inspect.

### UI Contract

The UI should render traces as a drill-down structure, not as a flat log line dump.

The user-facing model should emphasize:

- iteration order
- what was searched
- what was read
- why the system moved from searching to reading
- why the system considered the evidence sufficient

## 7. User-Facing Surfaces

### Admin UI

The admin UI should expose the canonical runtime model directly:

- agents
- skill graphs
- datasets
- pipelines
- config trees
- API key grants and ceilings

The UI should use the product model, not legacy internal naming where that would obscure the intent.

### Playground

The Playground is the primary inspection surface for runtime behavior.

Its execution view should prioritize:

- the active agent
- the dataset refs used
- the execution trace
- the per-turn reasoning or sufficiency explanation

### API Surface

Public APIs should require explicit identifiers where ambiguity would otherwise creep in:

- agent execution should be agent-scoped
- retrieval should be dataset-ref-scoped
- config resolution should be node-scoped and traceable

Implicit fallback behavior should be treated as transitional, not canonical.

## 8. Legacy Compatibility and Migration Philosophy

The current codebase contains partial implementations and transitional naming. We are currently in a "Stop the world" state and working towards removing all transitional states, legacy naming, etc.


The migration rules are:

- choose one canonical path per concern
- avoid adapters, expose our core naming conventions in APIs
- use TDD to create new tests with expected behaviour and then update the implementation to achieve green tests
- remove duplicate abstractions and legacy tests once the canonical path is in place

The target state is not "many models with documentation around them."
It is one model, a export/transform/import strategy for preserving current configuration values.

## 9. Non-Goals

The target architecture explicitly does not aim to be:

- an arbitrary configuration graph language
- a hybrid of multiple runtime execution models
- a permanent compatibility layer for all historical names
- a dataset-specific skill runtime
- a conversational system that silently chooses scope on the user's behalf

## 10. Open Questions

These are the remaining decisions that should be confirmed before the spec is treated as frozen.

### 10.1 Request-Facing Config Key Naming

Option A: keep a single generic request-facing term such as `config_key`.

Option B: use context-specific keys throughout the product, such as `platform-config-key`, `runtime-config-key`, and `dataset-config-key`.

Preferred direction: Option B. The generic `config_key` term should be phased out.

### 10.2 Reasoning Persistence and UI Rendering

Option A: persist only summaries and compact traces, and discard detailed reasoning after execution.

Option B: persist all reasoning for later debugging, and render summaries in Focused view with full details available in Detailed view.

Preferred direction: Option B. Detailed reasoning should be retained for debugging and displayed in Detailed view, while Focused view should continue to show summaries.

### 10.3 Dataset-Specific Tool Naming

Option A: move the UI toward generic skill terminology and de-emphasize dataset-specific aliases.

Option B: continue to show dataset-specific names, while keeping the UI itself decoupled from any one tool alias.

Preferred direction: Option B. Dataset-specific names should remain visible, but the UI should not depend on any particular alias.

## 11. Reference Documents

This specification is informed by, and should stay aligned with:

- `decisions/agents-skills-and-datasets.md`
- `decisions/platform-runtime-dataset-config-roots.md`
- `docs/architecture/config-parameter-naming-audit.md`
- `docs/architecture/skills-simplification-recommendations.md`
- `plans/in-progress/agentic-observability-plan.md`
- `plans/completed/priority-implementation-plan.md`
- `docs/architecture/explicit-node-resolution-gap-analysis.md`
