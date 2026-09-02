# Considered divergences

Where this API differs from a neighbouring standard, this page says whether we
**chose** the difference or **inherited** it, and why. That distinction is the
whole point of the page: an undocumented difference reads as a gap, and a reader
cannot tell a boundary from an oversight without being told.

Each entry states the decision, the reason, and where the decision lives. If a
divergence is not listed here, treat it as inherited rather than chosen — and
worth raising.

| Divergence | Chosen or inherited | Where the decision lives |
| --- | --- | --- |
| `dataset` / `pipeline` split | **Chosen** | ADR 2026-03-25 (explicit non-goal) |
| `agent` as the top-level noun | **Chosen** | Matches A2A v1.0 |
| No MCP `resources` or `prompts` in v0.1 | **Chosen** (boundary) | This page; partly closed by #123 |
| API-key auth rather than OAuth | **Not a divergence** | MCP makes `Authorization` OPTIONAL |
| Target the latest MCP revision only | **Chosen** (reversal) | PI decision 2026-08-21; implemented by #146 |
| Public identifier: no `/`, `mode` not `skill-graph` | **Chosen** | [ADR: public identifiers](../../../decisions/public-identifiers.md) |

---

## `dataset` and `pipeline` are two things, deliberately

AWS Bedrock collapses both into a single *knowledge base*. We keep them apart:

- a **dataset** is the retrievable corpus — the thing you query and scope an API
  key to. It is what Bedrock calls a knowledge base.
- a **pipeline** is the materialization definition *beneath* a dataset: how that
  corpus gets built, chunked and indexed.

One dataset can be rebuilt by successive pipelines without its identity — or the
API keys scoped to it — changing. Collapsing the two would make "re-index with
different chunking" indistinguishable from "a different corpus", which is
exactly the distinction operators need when they tune retrieval.

Decided as an explicit non-goal in the ADR of 2026-03-25; the split is already
reflected in the public docs.

## `agent` is the right noun, and we are keeping it

`agent` matches [A2A][a2a] v1.0 usage, now under the Linux Foundation. It is not
a synonym for "workflow", and we are not renaming it to one.

Some of what we expose behind an agent is closer to a fixed workflow than to an
autonomous agent. That distinction is real and worth signalling — but it belongs
in the tool's `title` and annotations, which describe an individual tool to a
reader, not in the noun that names the whole concept. Renaming the concept to
describe the least agentic thing it can hold would trade a correct general term
for a narrower one.

## No `resources` or `prompts` in v0.1 — a boundary, not a gap

MCP has three primitives. We implement **tools**. v0.1 ships without
`resources` and `prompts`, and that is a decision rather than an omission.

The reason is that tools are the primitive our surface actually needs: every
capability we expose is a call that runs retrieval and returns an answer.
`resources` and `prompts` are worth adding when there is something to put behind
them — a document catalogue a client should browse rather than query, or a
prompt library worth publishing — and inventing either to complete a checklist
would leave us maintaining a primitive nobody calls.

What we *have* done is take the half that costs nothing: cited documents come
back as `resource_link` content blocks, so a client can follow a citation today
without us declaring a `resources` capability. The affordance exists before the
capability does; the capability follows the need.

Honest note on how this got decided: the absence was originally **accidental** —
the word *resource* does not appear in the MCP plan in the primitive sense, and
*prompts* are never mentioned at all. It is recorded here as chosen because it
has now been examined and affirmed, not because it was designed that way from
the start.

## The public identifier says `mode`, the code says `skill-graph`

On the wire an agent exposes **modes**: `builtin.agent-rag-agent__agent-rag-graph-bundled`.
In the code the same axis is a **skill graph**. That is deliberate, not drift.

The word *skill* has three live public meanings — Agent Skills at agentskills.io,
A2A's `AgentCard.skills[]`, and ours — and we adopted it citing a source that
already meant one of the others. Renaming the public identifier costs a string
change today and a migration for every client after release; renaming the
internals costs a broad refactor across persistence, routes, UI and config to fix
a problem whose symptom is a reader's mis-prediction. So the public surface moved
and the internals did not.

The identifier also carries no `/`: agent ids are namespaced, and a slash inside
something that becomes a URL path segment works only while every client escapes
it.

Full reasoning, including the citation that caused the collision:
[ADR: public identifiers](../../../decisions/public-identifiers.md).

## API-key auth is not a divergence

MCP's own specification makes the `Authorization` header **OPTIONAL**. An API
key in `X-API-Key` is a conforming choice, not a shortfall against the spec, and
it is already a documented non-goal to replace it for v0.1.

Worth advertising rather than apologising for: it is the auth model our
operators already run, and the one every client in our supported set can use
without an OAuth flow.

## Target the latest MCP revision only — a reversal, recorded

**PI decision, 2026-08-21.** We target the latest MCP revision only. This
supersedes the version policy landed in #141 and #124, and it reverses the
recommendation in the standards analysis's own §7.2, which advised against
adopting `2026-07-28` before release.

Recorded here as **considered** rather than left to look like drift, because a
reversal that is not written down is indistinguishable from having forgotten the
earlier decision.

Why it is defensible despite the analysis:

- **It deletes a bug class rather than patching it.** The dual-era *fallback*
  trigger is the thing our legacy-era server gets wrong — we return HTTP 200
  where the rule needs a 4xx, so two real clients fall back only by leniency. A
  modern-only server has no fallback path to get wrong, so the class disappears
  instead of acquiring a fix we then have to keep correct.
- **We are further along than the analysis assumed.** `2026-07-28` retires the
  `initialize` handshake, protocol sessions and the GET SSE stream, and the
  conversation-handle pattern we chose independently is the one that revision now
  recommends. We are behind on revision, not on design.

What it changed, stated plainly: **the risk inverted.** Before #146, dual-era and
legacy-only clients worked and modern-only clients failed. Now modern and
dual-era clients work and **legacy-only clients fail** — the deliberate trade,
and the measured client table in
[endpoints/mcp.md](endpoints/mcp.md#client-compatibility--measured-on-a-production-artifact)
is where it was checked rather than predicted. That mirror question was
answerable only with the instrument that answered it in the other direction — a
logging reverse proxy in front of a booted production artifact, driving real
named clients and reading actual bytes. It was not answerable from the
compatibility matrix, which is the lesson #124 already paid for.

**#146 has landed, and this entry now records the current state of the server as
well as the decision.** `digdir.mcp.transport` declares `2026-07-28` as the only
supported version, `server/discover` answers with it, and a legacy `initialize`
is refused with `-32022` naming what we speak. The twelve-check conformance
script and a real Claude Code run both exercise that.

This paragraph previously said the opposite — that until #146 landed we were
still shipping the legacy revision. That was true when written and became false
without anything noticing, which is the failure mode this whole page exists to
prevent: the page's value is that a reader can trust it about *current* state,
and a sentence describing pending work is the one kind of claim that expires on
its own.

[a2a]: https://a2a-protocol.org/
