# S7 — Altinn Doc Authoring Assistant

**Demo scenario from** [`plans/ideas/altinn-docs-skill-demo-plan.md`](../../plans/ideas/altinn-docs-skill-demo-plan.md)

A side-by-side comparison of two user-defined agents drafting an Altinn 3 doc page outline from the same prompt — one built on `:builtin/agent-rag` with an optional new ReAct tool, the other built on a custom skill graph that makes structured-outline synthesis mandatory. The skill system is *composable enough* that we could build both without modifying any built-in code path (the underlying gaps we ran into during the build are tracked separately in [`plans/to-be-fixed/graph-runner-builtin-skill-gaps.md`](../../plans/to-be-fixed/graph-runner-builtin-skill-gaps.md)).

## What was built

All in [`server/src/digdir/demo/altinn_authoring.clj`](../../server/src/digdir/demo/altinn_authoring.clj):

- **A shared LLM helper** `generate-outline` that calls Azure OpenAI with structured tool-forcing on an `emitOutline` schema. Used by both the tool and the skill, so the two paths produce comparable outputs.
- **`propose_outline` ReAct tool** — registered with the agent loop's new tool registry (small additive refactor in [`agent/tools.clj`](../../server/src/digdir/skills/builtin/agent/tools.clj)).
- **`:demo/propose-outline` skill** — same generator wrapped as a regular graph-step skill.
- **`:demo/outline-graph` skill graph** — `plan → retrieve → rerank → propose-outline-skill`. The synthesis stage IS the structured outline tool, so every run emits a typed outline + consistency notes.
- **Two agents seeded into the config DB**:
  - `demo/altinn-authoring` — `:default-skill-graph "builtin/agent-rag"`, with `propose_outline` available as an optional ReAct tool. Customization flavors 1 (persona) + 3 (new tool).
  - `demo/altinn-outline-graph` — `:default-skill-graph "demo/outline-graph"`. Customization flavors 1 + 2 (custom skill graph) + 3 (the new skill).

Both agents lock `:allowed-dataset-scopes [{:tenant "digdir" :dataset-config-key "public-docs"}]`. Wiring lives in [`skills/init.clj`](../../server/src/digdir/skills/init.clj) (boot-time registration) and [`config/db.clj`](../../server/src/digdir/config/db.clj) `init-config-db!` (dump-import seeding).

## The test prompt

```
I'm writing a new doc page about how Altinn 3 apps integrate with
Maskinporten. Outline it for me, citing prior art and flagging any
contradictions.
```

## Side-by-side outputs

Both runs against the same prompt, same dataset (`digdir/public-docs`, ~62 documents in `website_documents_ab897fbdedfa`).

### `demo/altinn-authoring` (agent-rag + optional tool)

Agent loop: `plan_queries → search → read_chunks ×4 → generate_response`. The LLM **did not** call `propose_outline` — its standard synthesis step covered the task.

Excerpt:

```
## Proposed page outline

### 1. Title and scope
- Title: "Altinn 3 Apps and Maskinporten"
- Scope statement: Explain that the page covers how Altinn 3 apps use
  Maskinporten for authentication and token exchange…
- Clarify that the provided sources do not describe a dedicated "Altinn 3
  apps ↔ Maskinporten" integration guide in detail; they mainly describe:
  - Altinn Autorisering and Dialogporten integration [1]
  - Altinn Apps as the application runtime/platform [5]
  - System registration and token/scopes requirements [6][7]

### 2. What Maskinporten is used for
- State that organizations authenticated in Maskinporten can exchange their
  JWT for a valid Altinn-plattform JWT … [3]
- Mention that end users authenticated through ID-porten can do a similar
  exchange, but that is a separate path from Maskinporten [3].

### 3. Authentication flow overview
  …
```

Output shape: 9 numbered sections, freeform prose, inline `[N]` citation markers, no structured prior-art list, no dedicated consistency section.

### `demo/altinn-outline-graph` (custom skill graph)

Pipeline: `plan → retrieve → rerank → :demo/propose-outline`. The synthesis stage is the structured outline tool; no LLM agency over whether to call it.

Excerpt:

```
# Altinn 3 app integration with Maskinporten

### Overview
Explain what Maskinporten is in the Altinn 3 context, which actors use it,
and the high-level purpose of integrating an app with it…
*Prior art:* /nb/altinn-studio/about/, /nb/altinn-studio/apps/, /nb/platform/authentication/

### Authentication flow
Describe the end-to-end token flow for organizations and system-to-system
access: obtaining a Maskinporten JWT, exchanging it for an Altinn platform
JWT…
*Prior art:* /nb/platform/authentication/, /nb/platform/authentication/api/v1, /nb/altinn-studio/apps/

  …

## Consistency notes

- Prior art mixes Norwegian and English titles/content for Authentication,
  so the new page should choose one language consistently and align
  terminology (e.g., 'Maskinporten JWT' vs 'Altinn platform JWT').
- The existing Authentication docs state that both organizations
  authenticated in Maskinporten and end users authenticated through
  ID-porten can exchange JWTs for an Altinn platform JWT. The new page
  should clearly distinguish machine-to-machine integration from end-user
  flows to avoid implying Maskinporten is required for all app access.
- There is potential overlap with the general Altinn Apps overview and
  platform authentication reference; keep this page focused on
  Maskinporten-specific integration details and link out rather than
  re-explaining platform-wide auth concepts.
- The retrieved prior art does not show a dedicated Maskinporten page, so
  avoid inventing endpoint names or setup steps unless they are verified
  elsewhere in the docs or product specs.
```

Output shape: title + 6 sections + dedicated Consistency notes block. Each section has an explicit `*Prior art:*` URL list. Consistency notes surface real overlaps and the corpus gap.

## Observations

- **Determinism.** The custom-graph agent emits the same overall shape every run because the synthesis step is the structured outline tool. The agent-rag agent's output shape varies — sometimes numbered sections, sometimes prose, sometimes a different ordering.
- **Prior-art surfacing.** Custom-graph emits explicit `*Prior art:*` URL arrays per section, derived from the tool's `prior_art_pages` schema. Agent-rag emits inline `[N]` citations that resolve via the synthesis stage's citation index — usable but harder to consume programmatically.
- **Consistency notes.** Custom-graph produces a dedicated trailing block with substantive observations (language mixing, scope distinctions, overlap risk, honest gap acknowledgement). Agent-rag mentions overlaps inline but has no structured "consistency" handle.
- **LLM agency.** Agent-rag could call `propose_outline` but chose not to — the standard synthesis covered the task. The custom-graph forces structured output, but the LLM doesn't need agency for this task.

**The takeaway:** customization flavor 3 (new ReAct tool) is *registry-extensible*, but the LLM treats new tools as optional. Customization flavor 2 (custom skill graph) is the right escape hatch when the demo needs deterministic structure.

## Costs found along the way

Building S7 surfaced a pattern: **built-in skills work in the agent-rag ReAct loop but don't all play well when invoked through the graph runner**. Four real bugs (plus one retracted misdiagnosis) are documented in [`plans/to-be-fixed/graph-runner-builtin-skill-gaps.md`](../../plans/to-be-fixed/graph-runner-builtin-skill-gaps.md). All four are now closed on this branch: config-def keyword coercion (Gap 1), rerank tenant propagation (Gap 3), services pre-flight allowlist for use-site-resolved services (Gap 4), and opt-in agent-scope validation against the live dataset registry (Gap 5). Individually each is a one-liner; collectively they're a real finding — the graph-runner's contract with built-in skills hadn't been exercised end-to-end in production before S7. The test suite (1033 tests / 3759 assertions) still passes.

## Corpus limitation

Typesense holds 62 documents / 177 chunks in this worktree's DB — almost certainly the digdir.no side rather than `docs.altinn.studio`. The `altinn-docs` materialization pipeline has not been run here. For the Maskinporten test prompt this was acceptable (most retrieved prior art comes from `/nb/platform/authentication/` and `/nb/altinn-studio/apps/`), but a true Altinn-authoring demo would benefit from materializing the full Altinn docs first.

## What this proves about the skill system

Three flavors of customization, all live in the same demo:

| Flavor | Mechanism | Where it shows up in S7 |
|---|---|---|
| 1. Persona | Custom `:instructions` on the agent record | Both agents share authoring-persona instructions |
| 2. Custom skill graph | Register a new graph via `templates/register-skill-graph!` and reference its id from the agent | `:demo/outline-graph` for `demo/altinn-outline-graph` |
| 3. New skill / new tool | Register a new skill via `skills/register-skill!` *and/or* a new ReAct tool via `agent-tools/register-tool!` | `:demo/propose-outline` + `propose_outline` |

All three are exposable to end users without modifying built-in code (the `agent-tools/register-tool!` registry was a small additive refactor; the underlying registries already existed). The demo confirms the abstraction is genuinely composable — and shows where the seams are when you exercise the less-trodden paths.
