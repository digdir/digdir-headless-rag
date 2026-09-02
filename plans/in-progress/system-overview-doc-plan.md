# Plan — Single "System Overview" document

## Goal

Produce **one** document — `docs/system-overview.md` — that explains every main module of the Digdir RAG-as-a-Service application **as it is implemented today**, including the design and architecture objectives visible in the code. The doc is the primary artifact for the "explain and ship" phase: something a new engineer, reviewer, or partner can read end-to-end and walk away with an accurate mental model.

## Non-goals (hard constraints)

- **Do not read anything under `plans/`** — proposals, in-progress plans, and completed plans are all out of scope. They describe intent at a point in time, not what the code does today. Treat them as noise.
- **Do not describe target or future architecture.** If a doc under `docs/architecture/` is labeled "target", "gap analysis", "proposal", "recommendation", or "branch-analysis", only use it to cross-check *names* of things; describe only behavior visible in source.
- **Do not invent a redesign.** If the implementation is messy or inconsistent, describe it honestly; flag it in a "Known rough edges" subsection, but do not propose fixes.
- **No new code, no refactors, no renames** as part of this work.
- **Do not create supplementary docs.** One document. If a topic needs more depth, link to the existing source file; do not spin out a companion doc.

## Ground-truth sources (priority order)

1. **Source code** under `server/src/digdir/**` — authoritative.
2. **Top-level `README.md`** — for user-facing framing.
3. **`CLAUDE.md`** — for Electric/Hyperfiddle patterns the UI relies on.
4. **`server/docs/`** — `pipeline-architecture.md`, `PIPELINES.md`, `PIPELINES-QUICKSTART.md`, `DATASETS.md`, `api/README.md`, `api/authentication.md`, `api/getting-started.md`, `api/openapi.yaml`, `api/endpoints/*`.
5. **`decisions/*.md`** — three ADRs (`agents-skills-and-datasets.md`, `platform-runtime-dataset-config-roots.md`, `skill-based-agentic-rag.md`). These record *why* choices were made; extract reasoning, but verify each claim against current code before repeating it.
6. **`docs/architecture/*.md`** — **use with caution.** Many files are target/gap analyses that predate current code. Treat as hints only; every factual claim must be re-verified in source.
7. **`bb.edn`, `deploy.yml`, `mise.toml`, `server/deps.edn`** — for build/deploy/dependency surface area.

When a source conflicts with the code, the code wins and the source is ignored.

## Output — target shape

- **Path:** `docs/system-overview.md`
- **Length target:** 1,500–2,500 lines. Long enough to be thorough, short enough to re-read. If a module section grows past ~200 lines, that is a signal to link to the source file and summarize, not to expand further.
- **Audience:** engineer joining the project or reviewing for adoption. Assumes Clojure literacy; does not assume prior context on this codebase.
- **Tone:** descriptive, present-tense, grounded. Every behavioral claim should be traceable to a file path + line range. Use `file:line` references liberally (e.g. `server/src/digdir/rag/retrieval.clj:142`).

## Document outline

Use this as the top-level TOC. Section numbering is intentional — it becomes the navigation spine.

1. **Introduction** (½ page)
   - One-paragraph elevator pitch of what the system is.
   - Who uses it (admin UI users vs. API consumers) and what they get.
   - How to read this document.

2. **System at a glance** (1–2 pages)
   - The three externally visible surfaces: admin UI, headless HTTP API, ingestion pipelines.
   - ASCII/mermaid block diagram showing request flow: client → API → RAG → skills/agents → LLM + Typesense → response.
   - Primary persistence stores: Postgres (config, auth, conversations, pipeline state), Typesense (documents/chunks/phrases), filesystem (caches, local-db).
   - Tech stack one-liner: Clojure, Electric/Hyperfiddle, Jetty, Reitit, Aero, Typesense, Postgres, LLM SDKs.

3. **Repository layout and build** (1 page)
   - Directory map (top-level only) with one-line purpose each.
   - Build toolchain: `bb.edn`, `mise.toml`, `shadow-cljs.edn`, `deps.edn` aliases (dev/prod/build/test).
   - Dev entrypoint vs. prod entrypoint (`src-dev/dev.cljc` vs. `src-prod/prod.cljc`), ports.
   - Deployment surface: `deploy.yml` (Kamal), `server.Dockerfile`, `bb deploy` destinations.

4. **Cross-cutting concerns**
   - 4.1 **Configuration system** — `digdir.config.*`
   - 4.2 **Authentication & authorization** — `digdir.auth.*`, API keys, JWT cookies, permissions
   - 4.3 **Data layer** — `digdir.data.*` (Postgres access, background worker)
   - 4.4 **Execution scope / context** — `digdir.execution.scope`, `digdir.api.context`
   - 4.5 **LLM integrations** — `digdir.llm.*`
   - 4.6 **Utilities** — `digdir.util.*` (brief — just catalog what's there)

5. **Core domain modules**
   - 5.1 **Document ingestion pipelines** — `digdir.pipeline.*`, `digdir.docs.*` (loaders: episerver, website, folder)
   - 5.2 **RAG core** — `digdir.rag.*` (retrieval, chunking, filters, auto-filter, rerank, query relaxation, synthesis, Typesense integration)
   - 5.3 **Skills system** — `digdir.skills.*` (builtin skills, graph, templates, context, events)
   - 5.4 **Agents** — `digdir.agents.*` (core, db, policy) and the agent loop under `digdir.skills.builtin.agent.*`
   - 5.5 **Import/Export** — `digdir.import_export.*`
   - 5.6 **Setup workflow** — `digdir.setup.*`

6. **External surfaces**
   - 6.1 **Headless HTTP API** — `digdir.api.*` (routes, rate limiting, middleware), authentication model, key endpoints (`/api/rag`, `/api/retrieve`, conversations)
   - 6.2 **Admin UI** — `digdir.ui.*` root, Electric/Hyperfiddle pattern, major UI areas (config, skills, pipelines, playground, docs browser, auth)
   - 6.3 **Playground & observability UI** — `digdir.playground.*` (chat session, timeline, diagnostics, live status, citations, observability panes)

7. **Operations**
   - First-run setup (`bb setup`), required env vars and what each one unlocks.
   - Config modes (DB-backed vs. legacy EDN).
   - Deployment (`bb deploy prod|test`), what Kamal deploys, external accessories.
   - Logging, telemetry/timings, caches (`server/cache/`, `server/state/`).

8. **Testing**
   - RCF pattern, `bb test`, `bb test-config`, `bb lint`.
   - What each test tree covers (`server/test/**`).

9. **Glossary**
   - entity, tenant, environment, dataset, skill, agent, graph, pipeline, loader, chunk, phrase, read-signal, token (Electric), materialization, accessor, override, API key, permission.
   - 1–3 sentences each, grounded in the code where each term is defined.

10. **Appendix: file-path index**
    - Flat list: every `.clj/.cljc` namespace under `server/src/digdir/`, one line per file with its purpose. Acts as "where does X live?" reference.

## Per-module section template

Every subsection in sections 4–6 follows the same shape. Consistency matters more than cleverness.

```
### X.Y Module name (`digdir.some.ns`)

**Purpose.** One paragraph. What problem does this module solve? Who calls it?

**Entry points.** The 1–5 public functions or namespaces that other modules actually use. `file:line` refs.

**Key files.** Bulleted list, `file — one-line summary`, ordered by importance (not alphabetical).

**How it works.** 3–10 sentences describing the flow. Focus on the shape of the data and the sequence of calls. Concrete examples beat abstractions.

**Design objectives (as implemented).** What this module is *trying* to achieve, inferred from the code's structure: e.g., "keep retrieval deterministic given a fixed config", "allow sub-skills to be composed without each owning LLM setup". Each objective must be grounded in a visible design choice, not in a plan doc. Cite the ADR in `decisions/` only if the code still reflects the decision.

**Integrates with.** Bulleted list of other modules it depends on or that depend on it. Keep short; avoid exhaustive graphs.

**Known rough edges (if any).** Inconsistencies, dead code suspected but not removed, `TODO`/`FIXME` clusters, obvious duplication. One or two lines, not an indictment.
```

Omit a sub-bullet when there's nothing to say rather than filling it with filler.

## Execution phases

The phases are ordered to build the skeleton first, then the flesh, then polish. Each phase ends with a commit so progress is checkpointed.

### Phase 0 — Inventory and scaffolding (1 pass)

Before writing prose, produce a working map. Do not skip this — it is the guardrail against drift.

- [ ] Walk `server/src/digdir/` one level deep, list every top-level namespace group and one-line intent per group.
- [ ] For each of the 17 top-level groups, list every file in the group with a one-line "what it does" note (grep for `(ns ...)` docstrings, top-of-file comments, and the first `defn` names).
- [ ] Record the output as **Section 10 (Appendix: file-path index)** — writing it first forces a full sweep and gives every later section a stable reference surface.
- [ ] Create `docs/system-overview.md` with just the TOC from this plan and empty section headers.
- [ ] **Commit:** `docs: scaffold system-overview.md with file-path index`.

### Phase 1 — System-at-a-glance and repo layout (sections 1–3)

- [ ] Write intro (§1) after drafting §§2–3 so the elevator pitch reflects what the doc actually covers.
- [ ] Draft §2 with the request-flow diagram. Sketch in ASCII first; only upgrade to mermaid if the ASCII becomes unreadable.
- [ ] Draft §3 from `bb.edn`, `deps.edn`, `mise.toml`, `deploy.yml`, `shadow-cljs.edn`, `server.Dockerfile`. Do not guess versions — read them.
- [ ] **Commit:** `docs: system-overview §§1–3 (overview, layout, build)`.

### Phase 2 — Cross-cutting concerns (§4)

Six subsections; each follows the per-module template. Order chosen so upstream dependencies are explained before downstream consumers.

- [ ] 4.1 Configuration — `digdir.config.accessor`, `core`, `schema`, `validator`, `permissions`, `api_keys`, `audit`, `crypto`, `db`, `ops/**`, `ui/**`. Include the two config modes (DB-backed vs. EDN). Flag that `config/README.md` and `docs/config-resolution.md` referenced in the top README do not exist — do not fabricate their contents.
- [ ] 4.2 Auth — `digdir.auth.core`, `cookies`, `migration`, `views`, `ui`. Describe the JWT cookie flow for admin UI, API-key flow for `/api/*`, and the permissions model.
- [ ] 4.3 Data layer — `digdir.data.db`, `background_worker`. Cover connection handling, worker loop, and what other modules enqueue onto it.
- [ ] 4.4 Execution scope — `digdir.execution.scope`, `digdir.api.context`. Explain the request-scoped value propagation.
- [ ] 4.5 LLM integrations — `digdir.llm.anthropic`, `openai`, `kudos`, `kudos_preprod`, `marker`, `structured_eval`, `prompt_fragments`. Describe the provider abstraction as it actually exists (it may be looser than a formal protocol).
- [ ] 4.6 Utilities — `digdir.util.*`. Brief catalog, no deep dive.
- [ ] **Commit:** `docs: system-overview §4 cross-cutting concerns`.

### Phase 3 — Core domain modules (§5)

These are the heart of the system and will be the largest sections. Consider spawning **Explore subagents in parallel** to draft each subsection — prompt them with this plan's per-module template plus an explicit "do not read anything under `plans/`" guardrail. Integrate returned drafts yourself; do not have subagents write directly into the final document.

- [ ] 5.1 Document ingestion pipelines — `digdir.pipeline.core`, `executor`, `model`, `collections`, `materialization`, `loaders/**`, `ui/**`, plus loader implementations in `digdir.docs.episerver`, `website`, `folder`, `loader`, `pipeline/**`. Describe the Source → Extract → Normalize → Chunk → Phrases → Store flow concretely, naming the functions that perform each stage.
- [ ] 5.2 RAG core — `digdir.rag.core`, `retrieval`, `chunking`, `filters`, `auto_filter`, `rerank`, `query_relaxation`, `synthesis`, `merge`, `formatting`, `typesense`, `typesense_admin`, `skills/**`, `ui/**`. Make the retrieval pipeline the spine of this section. Note where ColBERT rerank sits and how `auto_filter` feeds it.
- [ ] 5.3 Skills system — `digdir.skills.api`, `context`, `events`, `init`, `ui`, `builtin/**` (agent, entity_extraction, fact_checking, graph_builder, multi_retrieval, query_planner, rerank, retrieval, summarization, synthesis), `graph/**` (optimizer, runner, schema), `templates/**`. Describe skills as the composable units; describe graphs as how they get wired together.
- [ ] 5.4 Agents — `digdir.agents.core`, `db`, `policy`, plus the agent loop in `digdir.skills.builtin.agent.{core,loop,read_signals,sufficiency,tools,workspace}`. Clarify the relationship: agents are orchestration around skills; the loop lives under the skills tree for historical reasons (verify this before asserting it).
- [ ] 5.5 Import/Export — `digdir.import_export.{export,import,files,model,registry,report,system,canonical/**,entities/**}`. Describe the round-trip (export → canonical JSON → import) and what it covers.
- [ ] 5.6 Setup workflow — `digdir.setup.{common,config,workflow}` and `digdir.setup` (root). Describe the `bb setup` flow end-to-end.
- [ ] **Commit:** `docs: system-overview §5 core domain modules`.

### Phase 4 — External surfaces (§6)

- [ ] 6.1 Headless API — `digdir.api.{routes,http,context,rate_limit,util,routes/**,routes/endpoints/**}`. Anchor against `server/docs/api/openapi.yaml` for completeness, but describe what the routes actually do by reading the handlers.
- [ ] 6.2 Admin UI — `digdir.ui.{main,components,routing}` plus per-domain UI namespaces (`digdir.config.ui.*`, `digdir.skills.ui`, `digdir.pipeline.ui.*`, `digdir.docs.ui`, `digdir.auth.ui`, `digdir.rag.ui.*`). Reference `CLAUDE.md` for the Electric patterns the UI relies on; do not re-explain them in depth.
- [ ] 6.3 Playground & observability — `digdir.playground.{core,chat_session,citations,diagnostics,live_status_scheduler,status,timeline,ui,ui/**,ui/observability/**}`. Describe what a user sees and what data backs each view.
- [ ] **Commit:** `docs: system-overview §6 external surfaces`.

### Phase 5 — Operations, testing, glossary (§§7–9)

- [ ] §7 Operations — drawn from `README.md`, `bb.edn`, `deploy.yml`, `.kamal/**`, `scripts/**`, `mise.toml`. Env-var table should list the var, who reads it, and what happens if it's missing.
- [ ] §8 Testing — `server/TESTING.md`, `server/test/**`, `bb.edn` test tasks.
- [ ] §9 Glossary — pull terms from across the doc; make sure every one has at least one `file:line` anchor.
- [ ] **Commit:** `docs: system-overview §§7–9 ops, testing, glossary`.

### Phase 6 — Integration and polish

- [ ] Read the document end-to-end in one sitting. Fix: repeated explanations across sections, contradictions, broken `file:line` refs, sections that overshoot the 200-line guideline.
- [ ] Verify a sample of ~20 `file:line` references still resolve in the current tree.
- [ ] Add a short "How this doc is maintained" note at the top: "This doc describes the implementation at commit `<sha>`. When behavior changes, update the relevant section in the same PR."
- [ ] Update top-level `README.md` to link to `docs/system-overview.md` as the canonical architectural reference (one line, near the top).
- [ ] **Commit:** `docs: system-overview polish pass and README link`.

## Working rules while drafting

- **Read before writing.** For every subsection, read the primary file(s) first, then open the `.md` to draft. Do not draft from memory of earlier sessions.
- **Cite code, not plans.** Every non-trivial claim carries a `file:line` anchor. If you can't find one, the claim doesn't belong.
- **When a decision doc says X and the code shows Y, describe Y.** Optionally add "(The decision doc at `decisions/foo.md` reflects this; note: Z has since changed.)"
- **Describe the messy parts honestly.** "The X flow has two parallel code paths: `foo/a.clj:12` (used by the admin UI) and `foo/b.clj:44` (used by the API). They share the helper at `foo/common.clj:88`." — that's useful. Glossing over it is not.
- **Prefer short paragraphs over bullet lists** for explaining flows. Bullets are for enumerations (files, endpoints, env vars).
- **One diagram per major section at most.** Diagrams rot fast; use them only where prose genuinely fails.

## Parallelization notes

Sections 5.1–5.6 are the most expensive and are mostly independent once the file-path index (Phase 0) is in place. When delegating to Explore subagents:

- Pass the per-module template verbatim.
- Pass the non-goals list verbatim (especially "do not read `plans/`").
- Ask for a draft at target length, with `file:line` citations, returned as markdown ready to paste.
- Have the subagent list any claims it made that it could not cite; review each before integrating.

## Completion criteria

- `docs/system-overview.md` exists, covers all sections in the outline, and reads cleanly front-to-back.
- Every module group under `server/src/digdir/` appears in the document at least in the file-path index (§10), and non-trivial ones have a dedicated subsection in §§4–6.
- A spot-check of ten `file:line` references picked at random all resolve.
- Top-level `README.md` links to it.
- No content drawn from `plans/**`.

## When this plan is done

Move this file to `plans/completed/` in the same commit that adds the README link.
