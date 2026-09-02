# Architecture Decision: Public identifiers

**Date**: 2026-08-21
**Status**: Adopted
**Decision**: The public identifier is `<agent>__<mode>`, carries no `/`, and does not use the word *skill*

---

## Context

One string does three jobs. It is the MCP tool name, the OpenAI-compatible model
id, and — through MCPO — a segment of an OpenAPI path. It was built as
`<agent-id>__<skill-graph-short-name>`, which gave it two problems.

**It carried a `/`.** Agent ids are namespaced (`builtin/agent-rag-agent`), so
the identifier read `builtin/agent-rag-agent__agent-rag-graph-bundled`. A slash
inside a value that becomes a URL path segment works only for as long as every
client escapes it, and the failure when one does not is a 404 that looks like a
missing tool rather than a malformed URL.

**It used the word *skill*.** That word now has at least three live public
meanings, and ours is not any of them:

| Whose | What it means there |
| --- | --- |
| [Agent Skills](https://agentskills.io/) | A standalone spec: reusable prompts with bundled artifacts |
| [A2A](https://a2a-protocol.org/) `AgentCard.skills[]` | A capability an agent advertises in its card (v1.0, Linux Foundation) |
| Ours | A composable runtime unit; a *skill graph* is a wired pipeline of them |

The awkward part is the provenance. [`skill-based-agentic-rag.md`](./skill-based-agentic-rag.md)
(2026-02-04) adopted the word citing exactly one external reference — a post
published 2026-01-20 that **already defined skills as reusable prompts with
bundled artifacts**. We cited a source that meant something else and did not note
the difference. That is how the collision got in: not by choosing against the
field, but by not looking.

## Decision

**1. The identifier carries no `/`.** The wire form writes the agent's namespace
separator as a dot: `builtin.agent-rag-agent__agent-rag-graph-bundled`. Only the
first separator is translated, mirroring the existing first-slash rule for
namespaced ids, so the transform is reversible and a name segment containing a
dot survives it.

**2. The second axis is a *mode* of the agent on the public surface.** An agent
exposes one or more modes; the tool name, the model id and the `_mode` metadata
field all use that word. `mode_not_allowed` and `mode_not_authorized` replace the
`skill_graph_*` error codes.

**3. Internal names do not change.** `skill`, `skill-graph`, `:allowed-skill-graphs`,
the registries, the config keys and the persistence layer keep their names. A
full internal rename is a broad refactor across persistence, routes, UI and
config, and the cost of the internal collision is *mis-prediction by a reader*,
not failure. This decision is about the public surface, where the cost is a
migration for every client.

**4. Tool-name uniqueness is checked, not assumed.** The MCP plan promised this
guard and never built it. `mcp.tools/tool-name-report` returns collisions and
round-trip failures across a set of agents, and a test fails on a deliberate
collision.

## Why now

This is the only item in the standards analysis whose cost is a *public
identifier*. Before release it is a string change with a guard. After release it
is a migration for every MCP client, every OpenAI-compatible client, and every
MCPO-generated path. The window is open exactly once.

## Consequences

- Existing callers using the slash form break. That is the point of doing it
  before release rather than after; there are no external consumers to migrate.
- Two agents whose ids differ only by the separator (`builtin/x` and
  `builtin.x`) now collide on the wire. That is a real narrowing, and it is why
  the uniqueness guard exists rather than being left to chance — the guard names
  both agents rather than reporting that a collision happened.
- The word *skill* survives internally, so a reader of the code and a reader of
  the API see different words for the same axis. That is a deliberate trade: one
  of those audiences can be handed a glossary, and the other cannot.

## Related

- [Considered divergences](../server/docs/api/considered-divergences.md) — this
  decision belongs in the *considered* column of that register, which is the
  whole reason for writing it down.
- [`skill-based-agentic-rag.md`](./skill-based-agentic-rag.md) — the ADR that
  adopted the internal vocabulary, and whose single citation is the collision.
