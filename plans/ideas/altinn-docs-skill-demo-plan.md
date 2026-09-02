# Altinn 3 Doc-Author Skill Demo Plan

## Goal

Build a small, opinionated demonstration that shows the **composable power and flexibility of the skill system** by configuring user-defined agents that assist Altinn 3 documentation maintainers working against `https://docs.altinn.studio` — content reachable through the `digdir/public-docs` dataset in the `digdir` tenant (Altinn docs are one of the materialization sources under that dataset, alongside `digdir-docs`).

The demo's audience is internal: people deciding whether the skill abstraction is paying for itself versus a single hard-coded RAG pipeline. The bar is therefore deliberately high — the comparison floor is **`agent-rag`** (the most capable generic QA path the system has today), not `simple-qa`. To beat that, scenarios must do at least one of:

- impose a deterministic DAG topology that's more reliable than letting the ReAct loop wander (e.g. enforced fan-out across N entities, or a reshape-then-verify pattern);
- introduce a new skill or new ReAct tool that the existing kit can't synthesize from primitives;
- combine specialized `:instructions`, `:guardrails`, and dataset scope to produce outputs the generic agent can't reliably produce on its own.

## Why Altinn-3 docs as the demo target

- The dataset is already materialized in the running system. The logical dataset is `digdir/public-docs`; the Altinn-side materialization (`dataset/digdir/public-docs/altinn-docs/materialization`, source `https://docs.altinn.studio`, ~30k document limit) is one of two sources feeding it (the other being `digdir-docs`). Demo agents target the `digdir/public-docs` dataset; if a scenario benefits from constraining to Altinn-only content, it does so via filters rather than a separate dataset.
- Altinn-3 documentation has the kinds of editorial pain points that benefit from compositional retrieval: cross-references across many pages, parallel Norwegian/English versions, ongoing migration from Altinn-2-era patterns, and frequent feature releases that ripple across multiple doc pages.
- The user persona ("Altinn system developer who maintains the docs") is realistic and distinct from "Altinn end-user asking a how-to question" — that distinction is what makes the agents *agents* and not just chatbots.

## What the skill system gives us to compose

Inventory of moving parts on this branch (`release-v0.1-details`, post-ff), so scenarios stay grounded:

- **Built-in skills** (`server/src/digdir/skills/builtin/*`): `query-planner`, `retrieval`, `multi-retrieval`, `rerank`, `entity-extraction`, `fact-checking`, `summarization`, `synthesis`, `graph-builder`, `agent` (ReAct loop).
- **Built-in skill graphs** (`server/src/digdir/skills/templates/builtin.clj`, registered via `templates/register-skill-graph!`):
  - `:builtin/simple-qa` — plan → retrieve → rerank → synthesis
  - `:builtin/research-assistant` — same shape, expanded retrieval
  - `:builtin/retrieve-only` — plan → retrieve → rerank, no synthesis
  - `:builtin/fact-checker` — retrieve → rerank → synthesis with verification framing
  - `:builtin/agent-rag` — single graph step that wraps the `:builtin/agent` ReAct loop
- **Agents are first-class records, not just skill-graph configs.** `digdir.agents.core/normalize-agent` and `validate-agent` define a concrete data shape, persisted to the config DB via `digdir.agents.db/upsert-agent!`. Each agent has:
  - `:id`, `:name`, `:description` — identity and UI metadata.
  - `:instructions` — the persona/system-prompt the agent carries into whatever graph it runs. **This is the primary lever for differentiating agents that share a skill graph.**
  - `:default-skill-graph` and `:allowed-skill-graphs` — the graph(s) this agent may run; the default is the one used when nothing else is selected.
  - `:allowed-dataset-scopes` — vec of `{:tenant :dataset-config-key}` maps. **This is the dataset-locking mechanism**, validated against blank tenant/key. For this demo, every agent's scope list is `[{:tenant "digdir" :dataset-config-key "digdir/public-docs"}]`.
  - `:guardrails` — free-form map; existing built-ins use `:citations-required`, `:allow-final-answer`, `:answer-style`, `:tool-use`. Custom agents can introduce new guardrail keys, but enforcing a new one requires code reading it.
  - `:enabled?` — boolean.
- **Five built-in agents** mirror the five built-in graphs (`simple-qa-agent`, `research-assistant-agent`, `retrieve-only-agent`, `fact-checker-agent`, `agent-rag-agent`), each with a one-line `:instructions` and an empty `:allowed-dataset-scopes`. The demo replaces "empty scope list" and "generic instructions" with Altinn-specific values.
- **Three flavors of customization**, in increasing scope:
  1. **Agent over a built-in graph (persona-only).** Pick one of the five built-in graphs, write tailored `:instructions`, lock `:allowed-dataset-scopes`. No new code. Cheapest demo. Useful when the bottleneck is persona, not topology.
  2. **Agent over a custom registered skill graph.** Register a new DAG via `templates/register-skill-graph!` that composes built-in skills in a non-default topology, then create an agent that references its ID. Required when the scenario needs a shape no built-in graph provides (e.g. `entity-extraction → multi-retrieval → summarization → synthesis`).
  3. **Agent with new skills and/or new ReAct tools.** Add a new entry under `digdir.skills.builtin/*` (a fresh skill exposed to graphs) or extend `:builtin/agent`'s tool definitions (a new tool exposed inside the ReAct loop). Required when the scenario needs a primitive operation the current skill set doesn't cover — e.g. parsing release-notes structure, locating NB/EN page pairs, or proposing a doc outline. This flavor is the test of whether the skill system is genuinely extensible by users, not just composable from a fixed kit.

The original framing of this plan treated flavor (3) as out-of-scope. It is now in-scope where a scenario needs it; the demo's value is partly to surface which scenarios force that escalation.
- **Graph runner** is currently sequential (`server/src/digdir/skills/graph/runner.clj`); the optimizer already computes parallel levels but execution doesn't yet exploit them. The demo should not block on parallel execution — pick scenarios where sequential order is acceptable and note where parallelism would help.
- **Persistence and seeding.** Agents go through `upsert-agent!` and survive restarts. There's a `seed-builtin-agents!`-style flow at init time (referenced from `upsert-agent!`'s comments). Demo agents can be seeded the same way — either committed as data in `digdir.skills.init` (or a new `demo` namespace) and registered on boot, or upserted ad-hoc via REPL/`bb migration-import`. The cutover runbook (`docs/runbooks/agents-skills-datasets-cutover-runbook.md`) is the canonical reference for the export/import flow.

## Personas

Two personas, both targeted by the same dataset but with different agent shapes:

- **Doc Maintainer** — owns a section of `docs.altinn.studio`, writes new pages as features ship, fixes bugs from user feedback, keeps cross-references intact.
- **Doc Reviewer / Editor** — does not own pages but reviews PRs to the docs repo, verifies facts, catches drift between Norwegian and English versions, signs off on consistency.

These personas inform tone (suggestive vs. assertive), output format (rewrite proposal vs. structured report), and tool selection.

## Scenarios — first cut

Each scenario is described as: *who it serves*, *the problem*, *the agent definition* (instructions + graph), *the underlying skill graph* (built-in or new), *expected output*, *why this exercises composability*. Scenarios are ordered roughly by how much they stress the skill system, ascending. The goal of this first cut is breadth — a later step in the plan is to **select 3–4 to actually build**, not all of them.

All demo agents lock `:allowed-dataset-scopes` to `[{:tenant "digdir" :dataset-config-key "digdir/public-docs"}]` and (unless noted) have `:guardrails {:citations-required true}`. That part is implicit in the descriptions below.

### S1 — Baseline: Doc Q&A over `agent-rag`

- **Persona:** Doc Maintainer, asking themselves a quick orientation question.
- **Problem:** "What does the current Altinn 3 doc say about X?" — sanity check before editing.
- **Agent:** new agent `demo/altinn-doc-qa`, `:default-skill-graph "builtin/agent-rag"`, generic doc-author `:instructions`. `:guardrails {:tool-use :allowed :citations-required true}`.
- **Skill graph:** built-in `:builtin/agent-rag` — the ReAct-style loop wrapping the `:builtin/agent` skill. No new graph.
- **Output:** Cited answer, possibly produced via multi-step search.
- **Why include it:** establishes the floor at the **most capable generic QA path the system has today**, not the simplest. `agent-rag` already plans, searches multiple times, reads chunks, and synthesizes — beating it requires either a more constrained DAG (so we don't pay for the agent loop's degrees of freedom on tasks it doesn't need) or new skills/tools the loop can't currently invoke. Every later scenario is implicitly compared to this one.

### S2 — Release-Notes Cross-Check

- **Persona:** Doc Maintainer immediately after an Altinn 3 release.
- **Problem:** Given a list of release-note items, identify which existing doc pages must be updated, and how each is affected.
- **Agent:** new agent `demo/altinn-release-cross-check`, with `:instructions` framing the input as a release-notes list and the output as a per-page TODO. `:guardrails {:answer-style :research :citations-required true}`.
- **Skill graph:** new registered graph `demo/release-cross-check` — `entity-extraction` (pull feature names, API symbols, version numbers from the release-notes input) → `multi-retrieval` (queries per extracted entity) → `rerank` (per-entity) → `summarization` (per affected page: "what changed about this page's topic") → `synthesis` (consolidated TODO list keyed by page).
- **Output:** Markdown checklist: page → predicted edits → relevant release-note item.
- **Why it shows composability:** non-trivial fan-out via `multi-retrieval` over extracted entities, and a per-page reduction; a one-shot RAG call would either over-retrieve once or require bespoke code per release. Demonstrates a custom DAG shape no built-in graph provides.

### S3 — Cross-Reference Auditor

- **Persona:** Doc Reviewer.
- **Problem:** Given a doc page slated for deletion or rename, find every other page that links to it or depends on its concepts; flag which references will rot.
- **Agent:** new agent `demo/altinn-xref-audit`, `:instructions` describing the deletion/rename context.
- **Skill graph:** new registered graph `demo/xref-audit` — `entity-extraction` (anchor IDs, page slugs, terminology unique to the target page) → `multi-retrieval` (broad-recall queries on those identifiers) → `rerank` (page-level grouping) → `synthesis` (audit report grouped by linking page).
- **Output:** Per-page list of incoming references, classified by type (direct link, conceptual reference, code-sample reuse).
- **Why it shows composability:** the entity layer changes the *retrieval queries*, not just the synthesis prompt. Painful to express in a one-shot RAG call.

### S4 — Altinn-2 → Altinn-3 Migration Reviewer

- **Persona:** Doc Maintainer cleaning up legacy pages.
- **Problem:** A given doc page still uses Altinn-2-era concepts, terminology, or examples. Detect them and propose Altinn-3 equivalents grounded in the current docs.
- **Agent:** new agent `demo/altinn-2-to-3-migration`, with `:instructions` carrying a small Altinn-2-vocabulary primer (terms to look for) and the rewriting framing.
- **Skill graph:** new registered graph `demo/migration-rewrite` — `entity-extraction` (Altinn-2 concept terms in the input page) → `retrieval` per concept (find the Altinn-3 equivalent page) → `fact-checking` (does the candidate Altinn-3 concept actually replace the Altinn-2 one?) → `synthesis` (suggested rewrite with citations).
- **Output:** Annotated diff suggestion.
- **Why it shows composability:** uses `fact-checking` for the role it was actually designed for — verifying a synthesizer's claim before it ships — rather than as a vestigial pipeline stage. Also shows that a curated `:instructions` is part of agent definition, not just decoration.

### S5 — Translation Drift Detector (NB ↔ EN)

- **Persona:** Doc Reviewer responsible for parity between Norwegian and English versions.
- **Problem:** Norwegian and English versions of the "same" page have drifted; identify which pairs disagree on substantive claims (not just phrasing).
- **Agent:** new agent `demo/altinn-translation-drift`, `:instructions` instructing the agent to treat NB/EN as paired inputs and surface substantive divergence only.
- **Skill graph:** new registered graph `demo/translation-drift` — `retrieval` (locate paired NB/EN pages by URL pattern) → `summarization` (claim list per language, in a normalized JSON shape) → `fact-checking` (cross-language: do claims in EN appear in NB and vice versa?) → `synthesis` (drift report).
- **Output:** Per-pair list of substantive divergences, with the specific NB and EN sentences that differ.
- **Why it shows composability:** the `summarization` step reshapes both inputs into a comparable form *before* checking — i.e. one step exists purely to enable the next.
- **Risk to call out in the plan:** depends on retrieval being able to identify NB/EN pairs reliably; if not, this scenario needs an upstream "page-pairing" step or falls back to a manual input.

### S6 — Duplicate-Explanation Finder

- **Persona:** Doc Reviewer doing housekeeping.
- **Problem:** Multiple pages explain the same concept slightly differently, causing inconsistency. Find near-duplicates so they can be canonicalized.
- **Agent:** new agent `demo/altinn-duplicate-finder`, `:instructions` framing the task as finding redundant teachings, not answering questions. `:guardrails {:answer-style :research}` (suppresses the tutorial register).
- **Skill graph:** new registered graph `demo/duplicate-finder` — `query-planner` (expand a concept into varied phrasings) → `multi-retrieval` (each phrasing as its own query) → `rerank` (cluster overlapping results) → `synthesis` (duplicate report with redundant passages quoted).
- **Output:** Clustered list of pages teaching the same thing, with the overlapping passages.
- **Why it shows composability:** this is "retrieval as a search-quality tool", not "retrieval as part of an answer". Same skills, different intent — useful demonstration of skill reuse.

### S7 — Authoring Assistant (ReAct + new tool)

- **Persona:** Doc Maintainer drafting a new page from scratch.
- **Problem:** "I'm writing a page on `<feature>`. Outline it, fill the prior-art section by citing existing doc pages, and flag any contradictions with what's already published."
- **Agent:** new agent `demo/altinn-authoring`, `:default-skill-graph "builtin/agent-rag"`, `:instructions` establishing the authoring persona (tone, output structure, what to cite vs. what to skip). `:guardrails {:tool-use :allowed :citations-required true}`.
- **Skill graph:** built-in `:builtin/agent-rag`, but with a **new tool added to the `:builtin/agent` tool registry** — e.g. `propose_outline` (returns a candidate outline given a topic and retrieved context) or `find_related_pages` (returns canonical paired pages for a slug). This is the demo's primary instance of customization flavor (3).
- **Output:** Draft outline with embedded citations and a "consistency" sidebar.
- **Why it shows composability:** the baseline (S1) is *also* an agent over `agent-rag`, just with a generic doc-Q&A persona — so persona-only customization alone (flavor 1) wouldn't differentiate this scenario much. Adding a new tool proves that the ReAct loop's toolkit is extensible, not fixed. If we discover the new tool is unnecessary because the existing `search_documents`/`read_chunks` tools cover it once the persona is right, that's itself a useful finding to record.

### S8 — Feedback Triager

- **Persona:** Doc Maintainer reading user-reported doc bugs.
- **Problem:** Given an unstructured user complaint ("the section about X is wrong / outdated / confusing"), identify the page they likely mean, classify the bug type (typo, factual error, missing content, structural), and suggest a fix.
- **Agent:** new agent `demo/altinn-feedback-triage`, `:instructions` defining the bug-type taxonomy and the expected triage-card output shape.
- **Skill graph:** new registered graph `demo/feedback-triage` — `entity-extraction` (concepts mentioned in the report) → `retrieval` (candidate pages) → `rerank` → `synthesis` (triage record with classification and proposed action).
- **Output:** Triage card per complaint.
- **Why it shows composability:** simplest custom graph in the set — useful as a "second floor" above S1, demonstrating that even a small composition (entity-extraction in front of retrieval) is meaningfully better than `simple-qa` for this task.

### Coverage matrix

| Scenario | Custom agent | Custom skill graph | New skill / tool | Built-in graph used | Multi-retrieval | Fact-checking | Entity-extraction | Summarization in pipeline |
|----------|:---:|:---:|:---:|:---|:---:|:---:|:---:|:---:|
| S1 baseline           | ✓ |   |   | `agent-rag` | (loop) | (loop) | (loop) | (loop) |
| S2 release notes      | ✓ | ✓ |   | —           | ✓ |   | ✓ | ✓ |
| S3 xref audit         | ✓ | ✓ |   | —           | ✓ |   | ✓ |   |
| S4 A2→A3 migration    | ✓ | ✓ |   | —           |   | ✓ | ✓ |   |
| S5 translation drift  | ✓ | ✓ |   | —           |   | ✓ |   | ✓ |
| S6 duplicate finder   | ✓ | ✓ |   | —           | ✓ |   |   |   |
| S7 authoring ReAct    | ✓ |   | ✓ | `agent-rag` | (loop) | (loop) | (loop) | (loop) |
| S8 feedback triage    | ✓ | ✓ |   | —           |   |   | ✓ |   |

`(loop)` means the skill is reachable from inside the ReAct agent's tool set — its use is dynamic per query, not encoded in the graph. Every scenario produces at least one persisted custom agent (flavor 1). Six register a custom skill graph (flavor 2). S7 is the planned exemplar of flavor 3 (new tool/skill); other scenarios may also escalate to flavor 3 if the build phase reveals the existing kit doesn't suffice.

## What this plan is *not*

- Not a *broad* plan to extend the skill system. New skills and ReAct tools are in scope **only when a chosen scenario forces it** — see customization flavor (3). The aim is to add the minimum needed for the picked scenarios; if a scenario could be built well with the existing kit, no new primitives.
- Not an evaluation/benchmark plan. There's separate ongoing work on automated evals (`plans/proposed/public-docs-automated-evals-plan.md`, `plans/proposed/codex-public-docs-evals-suite-plan.md`); this demo can borrow eval datasets from there if they exist, but designing one is not part of this plan.
- Not a UI plan. If the existing admin UI can already drive registered skill graphs and configured agents, the demo runs through it; if it can't, the demo runs through the API or REPL and a UI integration is a follow-up.

## Next steps after the scenario list

In order:

1. ✅ **Validate the scenarios with the user.** Working set chosen: S1 (now `agent-rag`-based baseline), S2, S3, S5, S7. **S7 was selected as the first to build.**
2. ✅ **For S7, the artifacts are in `server/src/digdir/demo/altinn_authoring.clj`:**
   - Custom skill graph `:demo/outline-graph` (plan → retrieve → rerank → propose-outline-skill) registered via `templates/register-skill-graph!`.
   - Custom skill `:demo/propose-outline` registered via `skills/register-skill!`.
   - New ReAct tool `propose_outline` registered via `digdir.skills.builtin.agent.tools/register-tool!` (using the small registry refactor in `agent/tools.clj`).
   - Two agent definitions persisted via `digdir.agents.db/upsert-agent!`:
     - `demo/altinn-authoring` — agent over `builtin/agent-rag` with `propose_outline` as an optional ReAct tool (flavor 1 + 3).
     - `demo/altinn-outline-graph` — agent over the custom `:demo/outline-graph` (flavor 1 + 2 + 3).
   - Init wiring: `digdir.skills.init/initialize!` calls `digdir.demo.altinn-authoring/register!` on every dev-server boot; `digdir.config.db/init-config-db!` calls `seed-agents!` on every dump-import / DB reset.
3. ⏳ **Fixture set** — only one prompt has been exercised end-to-end so far:
   > *"I'm writing a new doc page about how Altinn 3 apps integrate with Maskinporten. Outline it for me, citing prior art and flagging any contradictions."*

   For a fuller S7 evaluation we'd want ~4 more prompts: a digdir-side topic, an Altinn topic well-covered in the corpus, a topic *not* in the corpus (to test the "no prior art" branch), and a topic with a known overlap to test consistency-notes detection.
4. ✅ **End-to-end runs captured** for S7 against `digdir/public-docs`:
   - `demo/altinn-authoring`: produced a freeform 9-section outline via the standard ReAct loop (`plan_queries → search → read_chunks ×4 → generate_response`); LLM never invoked the optional `propose_outline` tool despite the persona instructing it to.
   - `demo/altinn-outline-graph`: produced a deterministic 6-section outline with per-section `*Prior art:*` URLs and a substantive Consistency notes block (language-mixing observation, Maskinporten-vs-ID-porten distinction, doc-overlap warning, honest "no dedicated Maskinporten page exists" admission).
5. ✅ **S7 narrative artifact** written to [`docs/demo/s7-altinn-authoring-assistant.md`](../../docs/demo/s7-altinn-authoring-assistant.md). Covers the setup, the test prompt, both outputs side-by-side, the comparison observations, the costs found (cross-linked to the to-be-fixed plan), and what the three customization flavors actually prove.
6. ✅ **S2 — Release-Notes Cross-Check (S2-A MVP)** built in [`server/src/digdir/demo/altinn_release_notes.clj`](../../server/src/digdir/demo/altinn_release_notes.clj). Narrative artifact: [`docs/demo/s2-altinn-release-cross-check.md`](../../docs/demo/s2-altinn-release-cross-check.md).

   > **Dev-loop finding:** Demo agents previously only got persisted via `init-config-db!`, which fires on `bb dump-import` / DB reset — not on regular `bb dev` restarts. So a new demo namespace would register its skill graph at boot but the agent record never landed in the DB, and the playground dropdown stayed empty. Fixed by adding an idempotent dev-only seed step to `server/src-dev/dev.cljc`: after `skills-init/initialize!`, every dev boot now calls each demo namespace's `seed-agents!` against the live config-DB conn. Production paths still seed exclusively via `init-config-db!`.


   - New bridge skill `:demo/entities->queries` (pure data transform: `:entities` → `:queries`, no LLM, no services). Demonstrates that user-defined adapter skills are first-class.
   - New structured-synthesis skill `:demo/release-todo-synthesis` (Azure OpenAI with tool-forcing on an `emitReleaseTodos` JSON schema — per-page TODOs + uncovered-items + cross-cutting notes).
   - New skill graph `:demo/release-cross-check`: `entity-extraction → entities->queries → multi-retrieval → rerank → release-todo-synthesis`.
   - New agent `demo/altinn-release-cross-check`: `:default-skill-graph "demo/release-cross-check"`, locked to `digdir/public-docs`, `:guardrails {:answer-style :research :citations-required true}`.
   - Init wiring: `digdir.skills.init/initialize!` calls `digdir.demo.altinn-release-notes/register!`; `digdir.config.db/init-config-db!` calls `seed-agents!` (opt-in dataset-scope-checker, mirroring S7).
   - Status: lint clean, full test suite green (1033 tests / 3759 assertions / 0 failures / 0 errors). Not yet exercised end-to-end in the playground.

   Canonical S2 test prompt (synthetic release notes — Altinn-3-adjacent so the corpus has *some* prior art, item 5 intentionally outside the corpus to exercise the `uncovered_items` branch):

   ```
   Altinn 3 — release v3.42 notes:
   1. Maskinporten JWT exchange now requires a new `altinn:apps:read` scope claim for app-side token exchange.
   2. Dialogporten dialog API: deprecate the old /v0 endpoints; consumers should migrate to /v1 (no breaking response-shape changes).
   3. Authentication service: tokens issued via ID-porten now carry an explicit `acr` claim distinguishing high vs. substantial assurance.
   4. Altinn Studio CLI: rename `altinn-app deploy` to `altinn-app publish`; the old name still works but emits a deprecation warning.
   5. Outbound webhook signatures now use HMAC-SHA384 (previously HMAC-SHA256).

   For each item, identify the existing doc pages on docs.altinn.studio that need to be updated. Flag any item that has no existing coverage.
   ```

7. ✅ **Graph-runner observability** added in [`server/src/digdir/skills/graph/trace.clj`](../../server/src/digdir/skills/graph/trace.clj). Every graph run (custom skill graphs + `:builtin/agent-rag`) now writes a `graph-trace-<graph-id>-<ts>.txt` under `server/logs/` capturing: graph id, tenant/dataset, per-step skill+status+duration+input-refs+**full** output content, and any step error. Built because custom skill graphs (everything except `:builtin/agent-rag`) previously had no on-disk record of execution. Failure path writes a partial trace before throwing so step errors are visible without re-running. Trace policy: never truncate model output during dev — full LLM responses, tool-call args, and structured outputs are written verbatim. Traces are typically tens to low-hundreds of KB; that's the point.

8. ✅ **S2-B — `:foreach` step in the graph runner.** Runner extension landed in [`server/src/digdir/skills/graph/`](../../server/src/digdir/skills/graph/): new `ForeachStep` schema, `execute-foreach-step` with `:fail`/`:skip`/`:default` semantics, foreach-aware trace rendering, 7 new unit tests. Discriminated step shape — existing single-skill steps unchanged. Demo rebuild: `:demo/release-cross-check-v2` graph adds per-page summarization between rerank and synthesis; `:demo/release-todo-synthesis` extended to optionally consume the `:summaries` vector. Side-by-side comparison vs v1 in [the S2 narrative § S2-B](../../docs/demo/s2-altinn-release-cross-check.md#s2-b-foreach-based-variant). Both agents (`demo/altinn-release-cross-check` and `demo/altinn-release-cross-check-v2`) seeded; v1 is unchanged.

9. ⏳ **ReAct loop as a graph composition (Phase 2 of the runner-unification track).** With graph-runner traces in place, the next observability step is making `:builtin/agent`'s internal ReAct loop a real graph composition rather than a bespoke imperative loop. Design note: [`plans/proposed/react-loop-as-graph-plan.md`](../proposed/react-loop-as-graph-plan.md). Five-phase staged migration: trace unification (cheap) → lift tool dispatch → add `:sub-graph` / `:loop` / `:select` / `:dispatch-by-name` primitives → workspace as pure-data → cutover. Stop points after each phase; phases 2.0 and 2.1 are recommended even if the full migration doesn't happen.

8. ⏳ **Other scenarios** — S3 not yet built. With graph-runner gaps closed (1, 3, 4, 5) and the S2-A composition pattern established, S3 (xref audit) should now be a linear add.

10. ✅ **S5 — Translation Drift Detector (S5-A MVP)** built in [`server/src/digdir/demo/altinn_translation_drift.clj`](../../server/src/digdir/demo/altinn_translation_drift.clj). Narrative: [`docs/demo/s5-altinn-translation-drift.md`](../../docs/demo/s5-altinn-translation-drift.md). Three new skills (page-pairer, full-content-expander, drift-synthesizer) + reused entities→queries bridge from S2. Two pre-requisite fixes shipped alongside: (a) `digdir.docs.website/parse-sitemap` now recurses into `:sitemapindex` roots, and (b) the altinn-docs materialization config now points at `localhost:1313/sitemap-markdown.xml` (parent sitemapindex over both `/nb/` and `/en/` leaves). Corpus re-crawl produced ~2100 documents (1075 NB + 1030 EN).

## Findings so far (2026-05-11)

### Comparison between flavor-1+3 and flavor-1+2+3 agents on the same prompt

Both agents produce well-formed outlines. The key behavioral differences:

- **Determinism.** The custom-graph agent emits the same overall shape every run (title + 4–7 sections + consistency notes), because the synthesis stage IS the structured outline tool. The agent-rag agent's output shape varies with the LLM's mood — sometimes numbered sections, sometimes prose, sometimes a different ordering.
- **Prior-art surfacing.** Custom-graph emits explicit `*Prior art:*` URL arrays per section, derived from the tool's schema. Agent-rag emits inline `[N]` citations that resolve via the synthesis stage's citation index.
- **Consistency notes.** Custom-graph produces a dedicated trailing block with substantive observations (language mixing, scope distinctions, overlap risk). Agent-rag mentions overlaps inline but without a structured "consistency" handle.
- **LLM agency.** Agent-rag could call `propose_outline` but chose not to — its standard synthesis covered the task. The custom-graph forces structured output as the synthesis stage; the LLM has no choice, but it also doesn't need agency for this task.

The takeaway: customization flavor 3 (new ReAct tool) is *registry-extensible* but the LLM treats new tools as optional. Customization flavor 2 (custom skill graph) is the right escape hatch when the demo needs deterministic structure.

### Structural validation beats prompt engineering (S2-A)

S2-A's structured-synthesis stage emits a typed `{:todos [...] :uncovered_items [...]}` map. A simple invariant — each release-note item appears in exactly one of the two arrays — turned out to be unenforceable by prompting alone. Three rounds of reinforcement (explicit rule, schema description, removing `"low"` from the confidence enum) each shifted *how* the model bent the rule rather than fixing it: first low-confidence TODOs, then medium-confidence TODOs with a hallucinated URL, finally medium-confidence TODOs against a wrong-page URL with the rationale openly admitting the mismatch.

A 10-line post-processor enforced the constraint structurally and didn't bend at all. The trace records what the model tried to double-emit so the LLM-vs-validator delta stays visible. Full write-up in [`docs/demo/s2-altinn-release-cross-check.md`](../../docs/demo/s2-altinn-release-cross-check.md).

**Generalizable principle:** tool-forcing JSON gives you shape; post-processing gives you constraints. When downstream consumers depend on a structural invariant, validate it in code. The prompt's job is to get the shape right; the validator's job is to keep promises the model can't.

### Gaps surfaced in built-in code

Building S7 surfaced a pattern: **built-in skills are designed for one execution path (the agent ReAct loop, which invokes them directly), not for the graph-runner path. Several latent bugs only show up when a custom skill graph invokes them as steps.**

1. **`:config-def/ownership` not keywordized on dump-import** (`digdir.config.ops.sync/definition-keyword-fields`). Added on this branch. Without the fix, `:ownership :inherit` becomes the string `"inherit"` post-import and inheritance silently fails. Affected the pipeline UI's "Missing config" warning before the fix.
2. ~~**Skill graph templates wire `[:plan :search-phrases]` but query-planner outputs `:queries`.**~~ Retracted on closer reading. The built-in templates already use `[:plan :queries]`; my own `:demo/outline-graph` draft was the only thing wired wrong. See the retracted Gap 2 entry in [the gaps plan](../to-be-fixed/graph-runner-builtin-skill-gaps.md).
3. **`:builtin/rerank` doesn't propagate `:tenant` from `skill-params` into its inner params.** Causes `cfg/get` to throw on a nil tenant when rerank runs as a graph step. Fixed in built-in code on this branch.
4. **`:required-services` runner check is dead-letter validation.** The runner only injects `:typesense` into the `:services` map. Every LLM-using skill (synthesis, query-planner, fact-checking, entity-extraction) declares `:required-services #{:azure-openai}` but resolves credentials via `cfg/get` rather than the services map. The check would reject all of them if they were invoked through the graph runner. Worked around in `:demo/propose-outline` by not declaring `:required-services`; the underlying gap remains.
5. **Agent's `:allowed-dataset-scopes` `:dataset-config-key` is the bare dataset id** (`"public-docs"`), not the path fragment (`"digdir/public-docs"`). Wrong form silently strips the dataset from the playground dropdown. Caught while seeding the demo agent.

These are individually one-line fixes; collectively they're a real finding: the graph runner's contract with built-in skills hasn't been exercised end-to-end. Worth a follow-up PR after S7 ships.

### Corpus limitation

~~Typesense holds 62 documents / 177 chunks in `website_documents_ab897fbdedfa` — almost certainly the digdir.no side rather than docs.altinn.studio. The `altinn-docs` pipeline hasn't been run in this worktree.~~ Resolved during S5: after teaching the website loader to recurse `:sitemapindex` and re-pointing the materialization config at `localhost:1313/sitemap-markdown.xml`, the corpus now holds ~2100 documents covering both `/nb/` (1075) and `/en/` (1030) trees of docs.altinn.studio. See [S5 narrative § Iteration 1](../../docs/demo/s5-altinn-translation-drift.md) for the loader/config sequence.

### Chunk-level retrieval vs. page-level reasoning (S5)

S5's drift detector first emitted false-positive "substantive drift" findings because the synthesizer compared retrieval-matched chunks from one page-section against retrieval-matched chunks from a different page-section of the supposedly-paired translation. The model self-diagnosed the issue in its `:notes` field — useful evidence that structured outputs surface upstream bugs faster than free-form responses do.

**Fix in shape:** when a synthesis step's intended unit of comparison is a *page* but retrieval returns *chunks*, add a bridging step that explicitly fetches the full page on both sides before comparison. S5's `:demo/translation-pair-content-expander` does this with `digdir.rag.retrieval/retrieve-chunks-by-range`. Cheap: 2 Typesense calls per pair, plus the LLM call.

**Generalizable principle:** *upstream pipeline shape determines downstream skill correctness.* If the LLM disagrees with a reasonable observer about whether two things are aligned, ask whether you're feeding it the unit it was actually supposed to compare. Retrieval is granular; alignment is page-level. Bridge that explicitly rather than hoping the synthesizer infers the intent.

### Foreach changed synthesis behaviour, not just timing (S2-B)

The expectation going in: adding per-page summarization via `:foreach` would let the runner exercise its new fan-out primitive, at the cost of N extra LLM calls per run. The observation in practice: the v2 graph's synthesis stage emitted **more, better-routed TODOs** than v1 on the same input, and the post-processor that S2-A needed in v1 didn't fire at all in v2.

V2 vs v1 on identical input:
- V1: 3 TODOs (all medium confidence), 2 enforced post-processor moves
- V2: 4 TODOs (1 high + 3 medium), 0 post-processor moves, release-item 1 correctly routed to two different doc pages instead of collapsed to one

The mechanism: the per-page summaries give the synthesizer page-level identity ("this page is the Studio Maskinporten guide" vs "this page is the auth concept page") that the raw chunk text alone obscures. The synthesizer used that identity to disambiguate where a release-note item should land, and emitted a higher-confidence claim where evidence was concrete.

**Generalizable principle:** *fan-out can improve synthesis quality, not just decompose work.* The case for `:foreach` isn't only "real iterative DAG topology" — it's that intermediate per-item structured outputs (page summaries here, but the pattern generalizes) give the downstream synthesizer better orientation than raw upstream artifacts alone. Pay the per-iteration LLM cost when it buys the synthesizer a sharper view.

Cost note: 5 sequential summarization iterations added ~8 s wall-clock to a previously-16s pipeline. Parallel iteration would cut that nearly to the slowest single call. A future `:parallelism N` flag is the obvious follow-up.

### Crawler-config UX friction surfaced by S5

Three pieces of friction that were invisible from a backend-only viewpoint:

1. **Discovering the right `tenant-config-key` for the altinn-docs materialization node** required a direct Datahike query. `bb pipeline-config <tenant> <key> <pipeline-id>` and `bb config-set <path> <value> <tenant> <root> <key>` use different key conventions for the same node (`altinn-docs` vs. `altinn-docs-materialization`). The error message — "Dataset ref does not resolve to a canonical dataset runtime node" — didn't point at the conflict.
2. **Loader code changes don't hot-reload across a re-trigger.** After patching `parse-sitemap` to recurse `:sitemapindex`, the first re-crawl still produced 0 URLs because the running JVM had the old definition. Symptom (zero URLs) was indistinguishable from a genuinely empty sitemap. Worth a future warning: emit a `:website/sitemap-root-ignored` event when a `:sitemapindex` root is encountered but recursion isn't supported.
3. **No UI affordance for "this is a default value, override it here."** Both `:website-sitemap-url` and `:website-base-url` resolved via topology defaults (no stored values on any node) prior to S5's `config-set`. Nothing in the admin UI hints at "this is a default; if you want a different value, write it to <node>." Hard to know what to override and where.

## Open questions

- ~~Do user-defined agents need a persistence story for this demo?~~ Resolved by post-ff inspection: agents persist via `digdir.agents.db/upsert-agent!` into the config DB; the cutover runbook documents the export/import path. Demo agents can be seeded the same way as built-ins.
- ~~Is the admin UI on this branch capable of selecting a registered (non-built-in) skill graph and a custom agent for a chat/playground session?~~ Yes — confirmed by S7. Both `demo/altinn-authoring` and `demo/altinn-outline-graph` appear in the playground agent picker, and selecting the custom-graph agent runs `:demo/outline-graph` end-to-end.
- ~~For S5, is there an existing convention in the `digdir/public-docs` materialization for pairing NB/EN versions of the same page?~~ Resolved: docs.altinn.studio uses mirror URL paths (`/nb/<path>` ↔ `/en/<path>`), so canonical-path pairing works directly off URL prefix. The S5 narrative records that retrieval is still language-biased — a single semantic query surfaces ~10× more unpaired paths than paired ones, suggesting either embedding-model asymmetry or a relevance-scoring bias toward one language per chunk. Future fan-out version could explicitly run NB-filtered and EN-filtered retrievals and merge.
- Are any of the proposed `:guardrails` keys actually enforced today (`:answer-style :research`, `:tool-use :allowed`, etc.), or are they advisory metadata? Not yet investigated; S7 worked regardless of guardrail enforcement, so the question is deferred until a scenario depends on a specific guardrail.
