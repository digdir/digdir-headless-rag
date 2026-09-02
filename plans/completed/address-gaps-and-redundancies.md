The system is currently in a high-stakes transition phase, moving from a pipeline-centric model to an agent-centric, multi-dataset architecture. While the engineering quality is
  exceptional, this "halfway state" reveals several critical gaps and opportunities.

  1. Architectural Gaps: The "Identity Duality"
  The system is currently straddling two worlds: the legacy "Pipeline-centric" model and the new "Agent-centric" target.
   * Identity Ambiguity: While "Agents" are being introduced as the primary user-facing persona, legacy pipeline identity is still pervasive in the API routes and conversation persistence. This
     creates cognitive load and ambiguity—for example, it's often unclear if a request is targeting a data materialization process (Pipeline) or a conversational capability
     (Agent).
   * Cross-Database Complexity: Agent definitions live in the Config DB (Datahike), while conversations live in the Main DB. This split requires complex joins or multi-step lookups
     during runtime, which could become a performance bottleneck as the number of agents and conversations grows.

  2. RAG Performance & "Solver" Opportunities
  The current RAG implementation is moving toward a highly sophisticated "Solver-style" loop, but there are clear gaps between the plan and the production code.
   * Inefficient Token Usage: The current production RAG loop (rag/core.cljc) still largely follows a "one-shot" retrieve-then-answer pattern. It fetches full content for the top-K
     chunks regardless of their actual relevance, leading to high token costs and latency.
   * Implemented but Underutilized: I found that retrieve-chunk-metadata-by-id and retrieve-chunks-by-range (Phase 1 of the Solver plan) are already implemented but not yet fully
     orchestrated into the primary agentic-loop.
   * Missed Context Expansion: The system lacks dynamic "context expansion." If a search hit is found in a specific chunk, the system doesn't yet automatically fetch the
     surrounding "neighbor" chunks unless they also happened to hit the similarity threshold. The "Solver" plan addresses this, but it's not yet live.

  3. Dataset Scaling & Materialization Risks
   * Shared Dataset Versioning: The move to allow multiple materialization pipelines to feed a single shared dataset is a major improvement. However, the Collection Versioning
     logic still relies on a configuration hash. A minor change in one pipeline's materialization config (like a limit tweak) could trigger a redundant re-index of the entire
     shared dataset.
   * Strictness vs. Observability: The "strict" materialization mode is being enforced for deployment targets, but many fallbacks still exist in the code for legacy paths. This
     "silent backfilling" makes it harder to identify configuration gaps before they cause issues in production.

  4. Technical Debt & Maintainability
   * The 3,000-Line Monolith: server/src/digdir/skills/builtin/agent.clj has grown into a ~3,000-line monolith. It handles LLM calls, tool execution, workspace management, and
     state persistence. This is a significant maintenance risk and makes it difficult to unit-test individual components of the agent's "brain."
   * Sequential Bottlenecks: The agentic-loop appears to be largely sequential. In a complex "Solver" scenario with 2-3 loops, the end-to-end latency could easily exceed 15-20
     seconds, which is a poor user experience for a chat interface.

  5. Missed Opportunities
   * Cold/Hot Storage: There is no evidence of a tiered data lifecycle. Retired tenants (like altinn or ka) are being handled via offline retirement, but a "Read-only/Archival"
     mode in the live system would preserve historical utility while reducing the operational overhead of active materialization.
   * Execution-Centric Diagnostics: The current diagnostics are "node-centric" (inspecting the tree). There is a missed opportunity to provide "execution-centric" diagnostics that
     show a trace of exactly which config values were resolved for a specific query turn.

  Recommended Priorities
   1. Accelerate the "Clean-Cut": Prioritize Phase 4-5 of the agents-skills-datasets-clean-cut-plan.md to remove legacy pipeline identity from the public API entirely.
   2. Activate the Solver Logic: Orchestrate the already-implemented search (metadata) and read (range) tools into the main agentic-loop to reduce token costs.
   3. Decompose the Agent Monolith: Split agent.clj into functional namespaces (e.g., agent.loop, agent.tools, agent.state).
   4. Formalize Dataset Contracts: Move all remaining materialization fallbacks into the Dataset-root config tree and remove code-level fallbacks.
