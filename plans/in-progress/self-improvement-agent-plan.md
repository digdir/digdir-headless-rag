# Self-improvement agent — plan

## Goal

An offline agent that systematically improves retrieval performance on a
corpus by analyzing existing chunk content + metadata and producing
**verified-useful** enrichments stored in a **parallel Typesense
collection**. Quality gates: every enrichment proposal is kept only if
it improves the eval pass rate on the public-docs suite (and doesn't
regress neighboring queries).

Constraint: never modify the running collections; the new pipeline is
strictly additive.

Wrapping agent skill: `:digdir.demo/self-improve-agent`. Develop on the
`digdir/public-docs` corpus.

## Four enrichment types

1. **Hypothetical questions** per chunk (3–5). Mirror of HyDE done at
   index time. Indexed as a searchable text target, joinable on
   `chunk-id`.
2. **Verified search phrases** — an improved version of the existing
   phrase-gen pipeline (`digdir.pipeline.materialization` →
   `:search-phrases-prompt`, currently dropped into the
   `phrases-collection` at materialization time without retrieval-quality
   verification). Generate candidates per chunk, keep only the ones
   that move the eval gate.
3. **Canonical fact assertions** in propositional form, e.g.
   `{:subject "Altinn 3" :predicate :launch-date :object "juni 2020"
     :source-chunk-id "8e22ae4b88b1"}`. Stored as structured records
   queryable for exact-fact lookups.
4. **Knowledge graph** — entity nodes + typed edges per doc, then
   merged corpus-wide. Stored separately; queryable for
   reasoning-heavy questions.

## Storage layout

A parallel Typesense collection per enrichment type, keyed by
`chunk-id`:

```
{:base-collection         "website_chunks_<corpus-id>"}
{:enrichment-collections
 {:hypothetical-questions "enrichment_hypothetical_questions_<corpus-id>"
  :verified-phrases       "enrichment_verified_phrases_<corpus-id>"
  :fact-assertions        "enrichment_fact_assertions_<corpus-id>"
  :knowledge-graph        "enrichment_knowledge_graph_<corpus-id>"}}
```

Existing search pipeline opt-in: add an
`:enrichment-search-targets` parameter on `:builtin/retrieval` that, when
present, also queries the listed enrichment collections and joins
results on `chunk-id` before rerank. Default empty → no behavioral
change.

## Phases

**Status snapshot (2026-05-20 evening):**

| Phase | Status |
|---|---|
| A — Eval-delta infrastructure | ✅ Done |
| B — Hypothetical-questions end-to-end | ✅ Done |
| C — ReAct wrapping agent | ✅ Done |
| C.5 — Skill-graph variant + side-by-side validation | ✅ Done |
| D1 — Verified phrases | ✅ Done (2026-05-19) |
| D2 — Fact assertions | ✅ Done (2026-05-20) |
| D2.8–D2.21 — Verify-retrieval gate, content-rich report, candidate-pool fixes | ✅ Done (2026-05-20) |
| **D3 — Knowledge graph** | ⏳ Pending |
| E — Iterative auto-tuning (scheduled runs) | ⏳ Pending |

### Phase A — Eval-delta infrastructure (the thermometer)

Without a fast, structured before/after comparison, the agent can't tell
if its proposals help. First deliverable: a skill that runs a fixture
suite, captures the result, swaps in proposed enrichments, runs again,
and emits a delta report.

- `:builtin/enrichment-eval-delta` — takes `{:suite, :enrichments-on?,
  :variant}`, returns `{:cases ..., :delta-vs-baseline ...}`.
- Reuses `bb agent-budget-benchmark` machinery via skill invocation
  (not via shell).
- Initial fixtures: `public_docs_agent_smoke.edn` (5 cases),
  `altinn3_lansert_stability.edn` (1 case, n=10 per run).

### Phase B — One enrichment, end-to-end (hypothetical questions)

Picked first because it's the smallest scope and least controversial.

- `:builtin/enrichment-propose-questions` — read a chunk + doc context,
  emit 3–5 questions the chunk answers. Pure-shape skill, deterministic
  for a given LLM seed.
- `:builtin/enrichment-apply-questions` — write proposed entries to the
  `enrichment_hypothetical_questions_<corpus-id>` collection. Idempotent
  on `chunk-id`.
- Wire `:enrichment-search-targets [:hypothetical-questions]` into the
  retrieval skill (opt-in parameter).
- Run eval-delta on the public-docs suite + lansert stability. **Gate**:
  must move pass rate by ≥ 5pp (n=10 measurement) without regressions
  on neighboring queries.

If Phase B's gate passes, the framework generalizes. If it fails, we
revise the proposal prompt / enrichment schema before adding more
types.

### Phase C — Wrapping agent

`:digdir.demo/self-improve-agent`. ReAct loop with tools:

- `analyze_corpus` (returns corpus stats: doc count, chunk count, etc.)
- `propose_questions_for_chunk`
- `propose_verified_phrases_for_chunk`
- `propose_facts_for_chunk`
- `propose_kg_node_for_doc`
- `apply_enrichments`
- `run_eval_delta`

Driven by a system prompt instructing the agent to maximize
eval-pass-rate-delta per LLM-spend-dollar. The eval-delta tool's
output is the agent's reward signal.

### Phase D — Remaining enrichments

> **Status as of 2026-05-20 evening:** D1 (verified phrases) and D2
> (fact assertions) are shipped end-to-end with a richer per-chunk
> pipeline than originally specified — see the post-Phase-C.5
> sections below. Only D3 (knowledge graph) remains, plus several
> follow-up polish items listed in "Remaining work" above.
>
> **Direction shift (2026-05-19, post-Phase C.5):** these were built
> as **graph nodes from day one**, not as ReAct tools first. Phase
> C.5 demonstrated that the propose → apply → eval → verify → decide
> graph composition eliminates the LLM-non-determinism failure modes
> Phase C's ReAct version was hitting. See the "Phase D direction
> shift" section near the end of this file for the concrete shape.

Add types 2, 3, 4 incrementally, each gated by both the eval-delta
machinery from Phase A AND the per-run verify-retrieval check (added
in D2.8 — see below).

- ~~**Verified phrases (D1)**~~ — **DONE 2026-05-19** ([details below](#d1-deliverables-locked-in)).
- ~~**Fact assertions (D2)**~~ — **DONE 2026-05-20** ([details below](#d2-deliverables--done-2026-05-20)).
- **Knowledge graph (D3) — pending.** Per-doc node/edge extraction;
  merge cross-doc by canonical entity. The retrieval pipeline can use
  the graph for query expansion (find related entities, then search
  broader). New shape compared to D1/D2: outer graph foreach iterates
  `:doc-num` instead of `:chunk-id`. Propose-apply-eval-verify-decide
  rhythm otherwise unchanged.

### Phase E — Iterative auto-tuning

Once all four enrichments are in place and verified, the agent can
operate autonomously on a schedule:

- Detect failing queries from production traces.
- Propose targeted enrichments for the failure-mode chunks.
- Apply, eval, keep or discard.

## Open design questions to revisit at Phase boundaries

- **Phase A**: should the eval-delta skill use an in-process invocation
  of the agent eval suite, or shell out to `bb`? In-process is cleaner
  but couples the skill's deps to the eval test harness.
- **Phase B**: enrichment collection schema — should fields include a
  generation provenance (model, prompt-hash, timestamp) so we can
  re-generate selectively when prompts change?
- **Phase B**: how does the runtime retrieval skill compose results from
  the base collection + N enrichment collections? Each collection
  returns its own ranking; how do they merge?
- **Phase D**: does the knowledge graph live in Typesense at all, or
  is it better suited to a real graph DB (Datalog/Datahike)?

## Out of scope

- Modifying the running collections.
- Changing the runtime agent ReAct loop. The runtime agent is unchanged
  except for opt-in `:enrichment-search-targets` parameter to the
  retrieval skill.
- Heuristic-based enrichments hand-tuned per query. Everything is
  agent-proposed, eval-gated.
- Multi-tenant generalization. First we prove it on `digdir/public-docs`.

## First concrete commit boundary

Phase A only: the eval-delta skill + a test fixture that confirms it
works. No proposals, no apply, no agent. This is the foundation that
lets Phase B's gate be trustworthy.

## Phase C.5 — Skill-graph variant + side-by-side validation

**Status (2026-05-19): COMPLETE.** Graph variant built, registered,
playground-runnable. Side-by-side comparison run against the live
`digdir/public-docs` corpus and the `altinn3_lansert_stability`
fixture. Structural-determinism thesis empirically validated across
all five claimed metrics; bug-fix sweep on existing skills captured
several silent-failure modes that are now caught and surfaced
explicitly.

**Motivation.** The ReAct version of the self-improve agent surfaced
two persistent LLM-non-determinism failure modes that two iterations
of prompt-tightening did not suppress:

1. **Compositional drift** — the LLM sometimes skips
   `propose_questions_for_chunk` and composes the four questions inline
   as arguments to `apply_enrichments`. The work happens; provenance is
   dishonest (auto-stamped `agent-composed`).
2. **Terminal denial** — even on fully successful runs (gate PASS, all
   counters populated), the LLM's final content message sometimes says
   "I can't actually run `apply_enrichments` or `run_eval_delta` from
   this chat." A face-saving training prior firing against an explicit
   anti-denial instruction.

Both come from the same root: in a ReAct loop the LLM decides the shape
of each turn — which tools to call, in what order, what the terminal
message looks like. The skill-graph thesis says: encode the *shape* of
work as edges, not prompt suggestions, and the LLM stays creative
inside nodes (writing questions, picking chunks) but doesn't drive the
control flow.

See [`plans/proposed/self-improve-graph-plan.md`](../proposed/self-improve-graph-plan.md)
for the surrounding architecture.

### What was built

**Nine** skills, all under `src-dev/`:

| Component | Purpose |
|---|---|
| `:builtin/extract-user-intent` | LLM extracts `(:topic :explicit-chunk-ids :goal)` from the raw user prompt. Translates topic to the corpus language, surfaces user-named chunk-ids verbatim, summarises intent. Fallbacks: regex chunk-id extraction + raw query as topic on LLM error. |
| `:builtin/enrichment-analyze-corpus` | Picks chunk-ids to enrich. Three modes: `:explicit` (use `:explicit-chunk-ids` from intent verbatim), `:llm` (Typesense content-search + LLM rerank), `:heuristic` (alphabetical-first). Mode chosen at runtime, reported in `:corpus-stats :selection-mode`. |
| `:builtin/enrichment-fetch-chunk-context` | Looks up chunk content + doc title/URL by chunk-id. First step of each per-chunk sub-graph iteration. |
| `:builtin/enrichment-mark-keep` | Records a `:decision :keep` verdict; pure tagging. |
| `:builtin/enrichment-revert-chunk` | Deletes enrichment rows for one chunk-id; idempotent on 404. Bracket-list filter for Typesense. Outputs `:decision :revert`. |
| `:builtin/enrichment-compose-report` | Deterministic walk of `{:analysis :outcomes}` → Markdown + structured stats. Replaces the LLM-authored final message. |
| `:builtin/run-sub-graph` | Generic dispatcher that lets a sub-graph live inside a foreach `:do` (workaround for the runner's single-skill foreach schema). Auto-forwards tenant + config-keys from skill-params. |
| `:digdir.demo/enrich-one-chunk` (inner sub-graph) | fetch → propose → apply → eval → select(keep/revert). |
| `:digdir.demo/self-improve-graph` (outer graph) | intent → analyze → foreach(per-chunk sub-graph) → compose-report. |

Plus retrofits to existing skills (each forced by a bug discovered during the validation runs):

- `revert-chunk` outputs `:decision :revert` so the foreach collects uniform outcome shapes.
- `revert-chunk` filter uses bracket-list form (`chunk_id:=[<id>]`) — bare `chunk_id:=<id>` silently matches nothing in Typesense for non-numeric string ids.
- `apply-questions` accepts singular `:proposal` (graph) in addition to `:proposals` (ReAct), with optional `:doc-num` backfill for the graph caller (since propose doesn't emit `:doc-num` but the Typesense schema requires it).
- `apply-questions` now reads Typesense's per-row success vec and throws on any row-level rejection — previously masked behind `applied-count = (count rows)`, which silently lied when `doc_num` rows were rejected.
- `eval-delta` mirrors `:gate-pass` at the outputs root (workaround for runner's 2-element ref limit, which can't reach `[:summary :gate-pass]`).
- Outer graph hardcodes `:tenant-config-key "default"` / `:runtime-config-key "default"` literals on the `:eval` step — the playground only threads `:tenant` via skill-params, and without these the diagnostics layer falls back to `dataset-config-key` ("public-docs") which doesn't exist under the runtime config root.

**~95 tests / ~250 assertions across new code, all green.** Zero lint warnings. Playground agent `digdir.demo/self-improve-graph` is seeded alongside the ReAct `digdir.demo/self-improve-agent` for direct side-by-side comparison.

### How to run the side-by-side experiment

Prerequisites: dev server running (`bb dev`), Typesense reachable, LLM API key configured, `digdir/public-docs` corpus indexed.

1. Open the playground, pick **dataset** = `digdir/public-docs`.
2. Run the **ReAct variant**: pick agent `digdir.demo/self-improve-agent`. Submit a starter query that asks it to enrich a small number of chunks (e.g. *"Improve retrieval for one chunk and report the eval delta."*). Capture the trace file the dev server writes to `logs/agent-trace-<ts>.txt`.
3. Reset the enrichment collection (so both variants start from the same baseline) — or accept that the second run uses `:exclude-already-enriched? true` to skip chunks the first run touched.
4. Run the **graph variant**: pick agent `digdir.demo/self-improve-graph`. Submit a query that triggers the graph (the question text itself is ignored — the graph reads its inputs from agent params / dataset config). Capture the trace.
5. Fill in the table below with measured values.

### Metrics

| Metric | Definition | Why it matters |
|---|---|---|
| `propose-rate` | `(count propose-questions-calls) / (count apply-questions-calls)` | 1.0 ⇒ never skipped; <1.0 ⇒ compositional drift |
| `denial-in-final?` | Does the agent's terminal user-visible response contradict its tool history? | true ⇒ terminal-denial fired |
| `gate-pass` | Boolean: did at least one chunk's eval-delta gate pass? | sanity that the experiment did real work |
| `wall-clock-ms` | Total time from query submission to final response | structural overhead vs ReAct loop |
| `provenance-honesty-%` | `(count rows-with-real-model) / (count rows-applied) × 100` | 100% ⇒ provenance always traceable; <100% ⇒ `agent-composed` fallback fired |

### Results

Measured 2026-05-19 across multiple graph runs against
`test/fixtures/agent/altinn3_lansert_stability.edn`:

| Metric | ReAct (`self-improve-agent`) | Graph (`self-improve-graph`) | Notes |
|---|---|---|---|
| `propose-rate` | observed <1.0 (skipped on some runs — `agent-composed` rows appeared) | **1.0** (structural) | Graph cannot skip propose — it's the upstream edge of apply, enforced by the graph topology. |
| `denial-in-final?` | observed true on multiple runs even when tools succeeded | **false** (structural) | Graph has no LLM-authored final turn; `compose-report` walks workspace state deterministically. |
| `gate-pass` | mixed (LLM-decided chunks can ignore the user's chunk_id reference) | **true** on user-named chunk `8e22ae4b88b1`; **true** on LLM-picked chunks `f80f23a50c0a`, `9b3259e6164d`, `3ffbf915292c` | Multiple successful runs (traces below). The 3-chunk variant where ALL three passed shows the graph honestly enriching genuinely relevant chunks. |
| `wall-clock-ms` | ~5-10 minutes (full eval per chunk in a freeform loop) | **~8-58 seconds** depending on iteration count | Per-iteration: ~2s intent + ~2s analyze + ~7s sub-graph (fetch+propose+apply+eval+decide). Much faster than the ReAct loop, primarily because there's no LLM deliberation between tool calls. |
| `provenance-honesty-%` | <100% (when LLM inline-composed, rows were `model=agent-composed prompt_hash=nil`) | **100%** (structural) | Graph routes through propose-questions → apply-questions, so each row carries the real `:model` + `:prompt-hash` from the propose step. |

**Evidence (selected trace files):**

- `graph-trace-self-improve-graph-2026-05-19T18-18-14-064057Z.txt` — user prompt naming `8e22ae4b88b1` → intent extracted the chunk-id → analyze short-circuited to `:explicit` mode → propose generated 4 Norwegian questions ("Når kom første versjon av Altinn 3...") → apply persisted 4 rows → eval gate-pass=true → decision keep → final report cites kept chunk.
- `graph-trace-self-improve-graph-2026-05-19T18-18-10-786609Z.txt` — 3-chunk LLM-mode run picked `9b3259e6164d`, `3ffbf915292c`, `f80f23a50c0a` from content-search rankings against the cleaned topic — all three gate-pass=true, all kept.
- Earlier 2026-05-19T12:* / 13:* / 14:* / 15:* traces — bug-fix iteration log: silent-success on row failure, missing doc_num, wrong filter syntax, wrong title field, missing tenant-config-key.

The values marked **bold** are structurally guaranteed by the graph
design and don't require measurement — they fall out of edges, not from
running anything. The ReAct comparisons are descriptive of behavior
observed during the earlier ReAct-only iteration (Phase C trace logs);
the gate-pass / wall-clock numbers above are the new graph evidence.

### Architectural insight: intent extraction as a discrete graph node

The most interesting lesson from the validation runs: the graph's
LLM-rank step (analyze-corpus's `:llm` mode) initially failed because
the **playground passes the raw user prompt as the search query**, and
that prompt was:

- Long and noisy ("propose 4 hypothetical questions, apply them via
  `apply_enrichments`, then call `run_eval_delta` with...").
- In English over a Norwegian corpus ("launch date" doesn't tokenize-
  match "lansert" or "kom").
- Named a specific chunk_id verbatim that the keyword search would
  never recover from prose.

We solved this by adding `:builtin/extract-user-intent` as the
**first** node in the graph: a single LLM call that produces
`(:topic :explicit-chunk-ids :goal)`. Downstream `analyze-corpus`
then has a clean signal — and short-circuits entirely when the user
named a chunk-id (the explicit path).

This is a generic pattern (query rewriting / intent decomposition is
standard in production RAG pipelines), and it slots cleanly into the
skill-graph thesis: yet another LLM-driven slot, but the graph decides
when to invoke it and what to do with the output. The ReAct loop
conflated intent extraction with action selection in one freeform
planner — which is exactly the pattern that failed.

### Known v1 limitations to revisit

- **Revert filter is chunk-id only.** No `prompt_hash` narrowing in
  this build. Safe given the `:exclude-already-enriched?` default but
  not robust against concurrent / repeated runs over the same chunks.
  Fix: thread `[:propose :provenance :prompt-hash]` into revert
  (currently blocked because the runner only supports 2-element refs;
  see runner-extension item under Next steps).
- **Reverted-block in the report omits gate verdict.** Cosmetic — the
  structured stats (`:report-structured`) carry the gate-fail evidence
  even though the Markdown block doesn't render the verdict line.
- **`:builtin/run-sub-graph` dispatcher** exists only because the
  runner's `ForeachStep` schema currently allows a single-skill `:do`,
  not a sub-graph. When the runner gains sub-graph-in-foreach support
  (small change tracked in `react-loop-as-graph-plan.md`'s Phase 2.2
  area), this dispatcher becomes deletable.
- **`analyze-corpus` content-search has fallback gaps.** When the
  cleaned topic produces zero Typesense hits *and* the user-query
  contains no explicit chunk-id, the graph emits an empty
  `:chunk-outcomes` and returns a non-error "no chunks tried"
  report. The honest behaviour, but worth surfacing in the UI more
  prominently than a single Markdown line.

## Next steps

Organised by priority. The first cluster is small, concrete follow-ups
that emerged from the validation iteration; the second is the larger
question of how Phase D should be shaped now that the graph variant
has proven itself.

### Near-term cleanup

(Status updated 2026-05-20 evening.)

1. ~~**Silent-failure audit across the skills layer.**~~ **DONE** —
   silent-failure audit completed during Phase C.5 cleanup. The verify
   step + revert-reason rendering specifically were added because
   "exception caught, default returned" is exactly the failure mode we
   want to surface, and several D2 bugs (query-planner, ensure-collection,
   prompt_hash indexing) were caught only because traces now make
   degraded paths visible.
2. ~~**Runner: 2-element ref limit.**~~ **DONE** in
   Cleanup.1 — runner now supports n-element refs and we use them
   liberally (e.g., `[:propose :provenance :prompt-hash]` in revert
   wiring).
3. ~~**Runner: foreach `:do` accepting `:sub-graph`.**~~ **DONE** in
   Cleanup.2 — foreach steps now dispatch directly to sub-graphs;
   `:builtin/run-sub-graph` is gone.
4. ~~**Sample-chunks silent-empty path.**~~ **DONE** in Cleanup.4 —
   sample-chunks distinguishes error from no-matches and warn-logs.

### Remaining work (state of 2026-05-20 evening)

The Phase D infrastructure is fully built. What's left is:

1. **D3 — Knowledge graph enrichment.** Per-doc rather than per-chunk;
   outer graph foreach iterates over `:doc-num` instead of `:chunk-id`.
   Same propose-apply-eval-decide rhythm as D1/D2. Not started; spec
   sketched in the "Phase D direction shift" section below.
2. **Propose-questions / propose-facts prompts: do they need the
   discriminative-not-topical shift D2.21 made to propose-phrases?**
   Likely yes — the broad-topical failure mode is LLM-shaped, not
   enrichment-type-shaped. Pilot first on phrases (post-D2.21), then
   port the prompt structure to questions and facts if results are
   consistent.
3. **Quantify the `:builtin/query-planner` fix via the right harness.**
   `:builtin/retrieve-only` or `:builtin/simple-qa` benchmarks would
   isolate the recall delta. Currently the fix is reverted in HEAD
   (commit `da00727`); re-applying is a one-line change.
4. **Eval-suite cost.** Each chunk in the foreach runs a full eval
   benchmark, which dominates wall-clock at `:max-chunks 10`. Two
   options if this becomes a bottleneck: (a) eval-once-at-end strategy
   (lose per-chunk causal attribution); (b) parallelize the foreach.
   Not urgent; revisit when run times exceed 5min.
5. **Phase E — Iterative auto-tuning.** Run on a cron, mine failing
   queries from production traces, propose targeted enrichments. The
   infrastructure is ready; this is operational scheduling, not new
   code.

### Phase D direction shift (graph-native from the start)

The validation run confirms that the graph composition pattern works
for the propose-apply-eval-decide cycle. Phase D's three remaining
enrichment types should be built as **graph nodes from day one**, not
as ReAct tools first and then ported.

Concretely:

- **Verified phrases (D1) — DONE 2026-05-19**: built with
  `:builtin/enrichment-propose-phrases` +
  `:builtin/enrichment-apply-phrases` + the same per-chunk sub-graph
  shape (propose → apply → eval → decide). Plus retrieval-side
  extension: `lookup-verified-phrases-similar` in `rag/retrieval.clj`
  and a `:verified-phrases` dispatch case in `:builtin/retrieval`'s
  `:enrichment-search-targets` handling so the eval suite can pick up
  phrase enrichments alongside hypothetical-question enrichments.
  Generalised `analyze-corpus` with an `:enrichment-type` parameter
  that decides which collection-name to derive.
- **Fact assertions (D2)**: structured triple extraction. The graph
  composition is identical; only the propose skill's output schema
  changes (a vec of `{:subject :predicate :object :source-chunk-id}`
  triples instead of question/phrase strings).
- **Knowledge graph (D3)**: per-doc node/edge extraction. New shape
  (whole-doc rather than per-chunk) so the outer graph needs a new
  `:foreach` over `:doc-num` instead of `:chunk-id`. But the
  propose-apply-eval-decide rhythm is the same.

Each of D1–D3 should also use the `:builtin/extract-user-intent` step
as its first node so user prompts that name specific docs or topics
get honored deterministically.

**The thesis we're now confident in:** structural-determinism +
intent-extraction is a re-usable pattern for offline self-improvement
agents. Phase D scales it across enrichment types without re-doing the
LLM-control-flow debate.

#### D1 deliverables (locked in)

New skills (mirroring the questions-variant):

| Skill | Purpose |
|---|---|
| `:builtin/enrichment-propose-phrases` | LLM generates N short topical phrases per chunk. Mirrors propose-questions with a phrase-shaped prompt (Norwegian, 3-10 words, search-style fragments not full sentences). |
| `:builtin/enrichment-apply-phrases` | Writes phrase rows to the verified-phrases enrichment collection. Same per-row failure surfacing and `:doc-num` backfill as apply-questions. |

New retrieval path:
- `digdir.rag.retrieval/lookup-verified-phrases-similar` — mirror of `lookup-hypothetical-questions-similar` against the `phrase`/`phrase_vec` fields. Re-exported via `digdir.rag.core`.
- `:builtin/retrieval` dispatches on `:verified-phrases` inside `:enrichment-search-targets`, with a 0.7 strategy-weight default (same as hypothetical-questions).

New schemas:
- `verified-phrases-schema` in `digdir.skills.enrichment.collections` — mirror of hypothetical-questions-schema with `phrase`/`phrase_vec` fields.
- `analyze-corpus` generalised: `:enrichment-type` parameter decides which collection-name segment to derive (defaults to `:hypothetical-questions` for backwards compat).

New graphs + agent:
- `:digdir.demo/enrich-one-chunk-phrases` (inner sub-graph)
- `:digdir.demo/self-improve-phrases-graph` (outer graph)
- Playground agent `digdir.demo/self-improve-phrases-graph` seeded alongside the questions variant.

What's reusable (no changes needed) — confirmation that the structural pattern generalises:
- `:builtin/extract-user-intent` — intent extraction is enrichment-agnostic.
- `:builtin/enrichment-analyze-corpus` — same, after the `:enrichment-type` parameter generalisation.
- `:builtin/enrichment-fetch-chunk-context` — enrichment-agnostic.
- `:builtin/enrichment-mark-keep` — generic decision tagging.
- `:builtin/enrichment-revert-chunk` — generic; just needs the right collection-name.
- `:builtin/enrichment-eval-suite` — same eval, dispatches based on `:enrichment-search-targets` which now includes phrases.
- `:builtin/enrichment-compose-report` — outcome shape is the same regardless of enrichment type.

96 tests / 228 assertions across new D1 code and updated callers, all green.

#### D3 outlook (pending, post-D2)

D2 confirmed (twice) that the per-enrichment-type mirror pattern works:
new schema, new propose, new apply, new retrieval lookup, new graph
files; everything else shared. D3 should follow the same template
with one shape change.

**What's different about D3**: the unit of work is per-doc, not
per-chunk. Knowledge-graph nodes describe entities (people, products,
laws, dates) and edges describe relations between them, and both
naturally span chunks within a doc. So:

- Outer graph's foreach iterates `[:analyze :doc-nums]`, not chunk-ids.
- Sub-graph's fetch step pulls a WHOLE document's chunks, not one
  chunk's context — and synthesizes them for the propose step.
- Propose emits `{:nodes [...] :edges [...]}` rather than questions /
  phrases / triples.
- Apply writes to a new `:knowledge-graph` enrichment collection with
  separate node and edge schemas (or one collection with a `:kind`
  discriminator — TBD).
- Retrieval lookup needs to handle two related-but-distinct surface
  forms (entity match vs relation match) — likely two separate
  `lookup-kg-*` fns.

**What carries over verbatim** (zero changes expected):
- `:builtin/extract-user-intent` — enrichment-agnostic.
- `:builtin/enrichment-analyze-corpus` — already parameterised by
  `:enrichment-type`; just add `:knowledge-graph` to the
  type→segment map.
- `:builtin/enrichment-mark-keep` / `:builtin/enrichment-revert-chunk`
  — generic; pass-through fields (:proposal, :eval, :verify, :context)
  accommodate any shape.
- `:builtin/enrichment-eval-suite` — same eval; suite picks up KG hits
  via `:enrichment-search-targets`.
- `:builtin/enrichment-verify-retrieval` — needs a `:knowledge-graph`
  dispatch case in `lookup-fn-for`; otherwise identical.
- `:builtin/enrichment-compose-report` — outcome shape unchanged. The
  `proposal-items` formatter needs a new case for `{:nodes :edges}`
  proposals (render as `entity1 — relation → entity2`).

**Cost estimate**: similar to D2 (~one focused day) plus an extra
~half-day for the per-doc fetch synthesis step, since combining chunks
into a coherent doc representation is a new pattern.

**Should we do it next?** Worth deciding before starting: D3's KG
shape is the riskiest of the four enrichment types (canonicalization
across docs is hard, and the verify-retrieval signal is harder to
interpret for KG hits than for phrase/question/fact hits). Might want
to invest the next pass into the propose-questions / propose-facts
discriminative-prompt rework (D2.21-style) first to make sure the
D1/D2 enrichments earn their keep before adding D3's complexity.

#### D2 deliverables — DONE 2026-05-20

New skills (mirroring the phrases variant):

| Skill | Purpose |
|---|---|
| `:builtin/enrichment-propose-facts` | LLM extracts K (subject, predicate, object) triples per chunk. Pipe-delimited prompt contract (`subject \| predicate \| object`), with marker-stripping and case-insensitive dedupe on the synthesized triple text. |
| `:builtin/enrichment-apply-facts` | Writes triple rows to the fact-assertions enrichment collection. Same per-row failure surfacing, same doc-num backfill, plus a `valid-triple?` guard that drops incomplete triples instead of upserting malformed rows. |

New retrieval path:
- `digdir.rag.retrieval/lookup-fact-assertions-similar` — mirror of `lookup-verified-phrases-similar` against the `triple_text`/`triple_vec` fields, but with `prioritize_exact_match=true` (D1's phrases lookup biases the opposite way). Re-exported via `digdir.rag.core`.
- `:builtin/retrieval` dispatches on `:fact-assertions` inside `:enrichment-search-targets`, with a 0.7 strategy-weight default (same as the other two enrichment types). Hits are tagged `:matched-triple {:subject :predicate :object}`.

New schema:
- `fact-assertions-schema` in `digdir.skills.enrichment.collections` — subject/predicate/object as separately-indexed strings (for filter/facet), plus a synthesized `triple_text` and auto-embedded `triple_vec` for the retrieval path. `:index true` on `prompt_hash` (regression-guarded).
- `analyze-corpus` extended with the `:fact-assertions` entry in `enrichment-type->segment` so the corpus analyzer can derive the right collection name.

New graphs + agent:
- `:digdir.demo/enrich-one-chunk-facts` (inner sub-graph)
- `:digdir.demo/self-improve-facts-graph` (outer graph)
- Playground agent `digdir.demo/self-improve-facts-graph`, seeded via `dev.cljc` alongside the other two variants.

What's reusable (zero changes needed) — second confirmation that the structural pattern generalises:
- `:builtin/extract-user-intent` — enrichment-agnostic.
- `:builtin/enrichment-analyze-corpus` — enrichment-agnostic after extending the type→segment map.
- `:builtin/enrichment-fetch-chunk-context` — enrichment-agnostic.
- `:builtin/enrichment-mark-keep` / `:builtin/enrichment-revert-chunk` — generic.
- `:builtin/enrichment-eval-suite` — same eval; the suite picks up triples via `:enrichment-search-targets`.
- `:builtin/enrichment-compose-report` — outcome shape unchanged.

Test counts: collections (8 tests / 47 assertions), propose-facts (6 / 26), apply-facts (8 / 31), retrieval dispatch (19 / 56), graph registration (9 / 13), analyze-corpus extended. All green.

#### D2.8 + D2.9 — Verify-retrieval step (2026-05-20)

Up through D2 the inner sub-graph's gate was the static eval-suite
benchmark (`agent-budget-benchmark` against
`altinn3_lansert_stability.edn`). That answers "did this change break
anything?" — a regression guard. It does NOT answer "did this change
actually make the user's intent topic more findable for THIS chunk?"

`:builtin/enrichment-verify-retrieval` closes that gap. After apply,
it issues one Typesense `lookup-*-similar` call against the freshly-
written enrichment collection scoped by the user's intent topic
(threaded from `:builtin/extract-user-intent`). If the just-enriched
chunk shows up in the hit list, the new enrichment created a real
retrieval path for the query that triggered the run.

The skill emits a composite `:keep?` boolean that ANDs:
- `:improved?` — chunk-id present in the lookup hits (the verify signal)
- `:eval-gate-pass?` — the eval-suite's gate-pass (the regression guard)

The `:decide` step's `:select` now switches on `[:verify :keep?]`
instead of `[:eval :summary :gate-pass]`. Same one-condition shape,
stronger signal.

Wiring:
- `:enrichment-type` is a **parameter** on the verify step, not an
  input — literal keywords in `:inputs` would be misread as step
  references by the runner.
- `:user-query` flows through the sub-graph from the outer graph's
  intent step (`[:intent :topic]`).
- `mark-keep` echoes `:verify` so `compose-report` can render the
  verify signal alongside the eval summary.
- If `:user-query` is missing (intent extraction returned nothing)
  or the enrichment-type is unknown, verify degrades gracefully to
  eval-only — same behaviour as the pre-D2.8 graph.

Files:
- New: `server/src-dev/digdir/skills/enrichment/verify_retrieval.clj` + tests.
- Modified: all three inner sub-graphs (questions/phrases/facts) and `mark-keep`.

Test counts added: verify-retrieval (9 tests / 30 assertions) + new
graph assertions per variant. All green.

#### D2.10–D2.13 — Measured improvement + content-rich report (2026-05-20)

The D2.8 verify gate was "did the chunk appear in the new enrichment
lookup at all?" Useful but coarse: any hit counted as improvement,
even if the chunk was already at position 0 in baseline content
search and the enrichment pushed it down.

D2.10 closes that by running TWO Typesense calls inside verify and
computing deltas:

| Output | Meaning |
|---|---|
| `:baseline-found?` / `:baseline-index` / `:baseline-rank` | Result of `search-chunks-by-content` against the chunks collection (no enrichment) |
| `:enriched-found?` / `:enriched-index` / `:enriched-rank` | Result of `lookup-*-similar` against the new enrichment collection |
| `:position-delta` | `baseline-index - enriched-index` — positive = chunk moved up. The primary comparable signal (rank units differ between text-match and hybrid scoring). |
| `:newly-findable?` | True iff baseline missed AND enriched found — the strongest improvement signal. |
| `:improved?` | `newly-findable? OR (positive position-delta)`. False if baseline already had the chunk higher. |
| `:keep?` | `improved? AND eval-gate-pass?`. Tighter gate than D2.8's any-hit. |

D2.11/D2.13 made the post-run report self-explanatory:
- Each kept/reverted block carries the actual generated content
  (Questions / Phrases / Facts) with count headers.
- Verify summary fragment: `regressed N place(s): index X → Y`,
  `newly findable ✓`, `moved up N place(s)`, etc.
- Reverted blocks gained a plain-English `_Why reverted:_` line that
  translates the verify signals into one of four prose explanations
  (eval-fail, baseline+enriched-miss, position-regression, both-miss).
- `:matched-enrichment` line shows the exact surface form verify
  found.
- `revert-chunk` now echoes `:proposal`/`:eval`/`:verify` symmetrically
  with `mark-keep`, so reverted entries explain themselves with the
  same data shape as kept entries.

D2.12 wired `:chunks-collection` into all three sub-graphs' verify
inputs so the baseline call has something to query. Missing it falls
back to the D2.8 "enriched-only" shape (still works, just no delta).

Net effect: a reader of the post-run Markdown can independently audit
each chunk's verdict — user intent, what was proposed, what verify
saw, what eval said, why we kept or reverted.

#### D2.14–D2.17 — Report polish + chunk-context details + max-chunks bump

Four smaller polish items in a single commit:

- **D2.14**: Corpus analysis prose collapsed to one line, leading with
  the extracted intent topic.
  Before: `Corpus has 2100 document(s) and 5919 chunk(s). The enrichment collection contains 50 row(s) already. Selected 1 chunk(s) (up to 3) without existing enrichment rows.`
  After: `Intent: «...» · Selected 1/10 chunks from 5919 (2100 docs, 50 existing enrichment row(s)).`
- **D2.15**: The plain-English `_Why reverted:_` line described above.
- **D2.16**: Chunk source text in a collapsible `<details><summary>Chunk content</summary>...</details>` block per outcome, with `_from [doc-title](doc-url)_` attribution. Threaded via a new `:context :fetch` pass-through on mark-keep + revert-chunk. Lets readers verify "did the propose make sense given the source?" without leaving the report.
- **D2.17**: `:max-chunks` bumped 3 → 10 in all three outer graphs.

#### D2.18–D2.20 — Wider candidate pool

Why narrow runs picked only 1 chunk even with `max-chunks=10`:

1. The LLM-selection prompt in `analyze-corpus` said "pick the N chunks MOST RELEVANT" — the LLM read MOST RELEVANT as a quality threshold and returned 1 chunk for narrow intents.
2. The candidate pool came from a single Typesense content search against the intent topic. Narrow intent → narrow pool → few obvious candidates.

Fixes:

- **D2.18**: Selection prompt reframed from filtering to RANKING.
  "Returning fewer than N chunks means missing improvement
  opportunities; the verify step is the gate, not you. Be inclusive."
- **D2.19**: `analyze-corpus` accepts an optional `:queries` (vec)
  input. When supplied, `sample-chunks` runs once per query and the
  union is deduped by chunk_id before LLM ranking.
- **D2.20**: New `:plan-queries` step (using existing
  `:builtin/query-planner`) inserted between `:intent` and `:analyze`
  in all three outer graphs. Expands intent topic into ~6 relaxed
  queries.

#### D2.21 — Propose-phrases prompt reframed: discriminative, not topical

Empirically: the verify gate kept reverting every chunk in live runs
because the LLM-proposed phrases were too BROAD. They matched the
chunk but also matched many neighbours on the same topic, so
enriched-rank dropped below baseline-rank.

Example from a 2026-05-20 trace (chunk `e843f5ba3968`, intent
"tilpasse tekstene på kvitteringstrinnet"):
- Proposed phrases: `Altinn Studio teksteditor`, `endre tekster i appen`, `tekster i App/config/texts`, `standardtekster og feilmeldinger`, `file_uploader_validation_error vedlegg`
- Baseline content search had the chunk at position 0; enriched lookup at position 6. **Verify correctly reverted.**

The new prompt asks for DISCRIMINATIVE phrases:
- At least one verbatim 3–7 word quote from the chunk.
- Others must combine distinctive entities, code identifiers,
  headings, numbers, or terminology that appears here but not nearby.
- Anti-example baked in: generic `endre tekster i Altinn-app` (too
  broad) vs distinctive `edit-texts-in-designer.png` /
  `file_uploader_validation_error vedlegg`.

Open question: do the questions and facts propose skills need the
same shift? Probably yes — the broad-topical anti-pattern is
LLM-shaped, not enrichment-type-shaped. Try after we have a
discriminative-phrases pilot trace to compare against.

### Cross-cutting finding: `:builtin/query-planner` was silently broken for 3.5 months (2026-05-20)

While wiring `:plan-queries` (D2.20) we discovered that
`server/src/digdir/skills/builtin/query_planner.clj` read `:tool_calls`
(snake_case) from the litellm response, but litellm-clj emits
`:tool-calls` (kebab-case). Every invocation hit a silent fallback
that returned `[original-query]` — a single-element vec — instead of
the intended 4–6 LLM-expanded search phrases. Confirmed by reading
the litellm-clj examples directory.

Affected consumers (all silently degraded to single-query behaviour):
- `:builtin/simple-qa`, `:builtin/research-assistant`,
  `:builtin/retrieve-only` skill graphs (where `:plan` is a hardcoded
  step).
- `digdir.demo.altinn-authoring` demo agent.
- Our D2.20 `:plan-queries` step.

NOT affected: `:builtin/agent-rag` (the ReAct-style agentic-rag). Its
loop calls `wkok.openai-clojure.api` directly and writes multi-query
lists straight into the `search` tool, bypassing `:builtin/query-planner`
entirely. This was confirmed by a benchmark subagent that observed
0/8 `plan_queries` invocations across before/after `agent-budget-benchmark`
runs.

Status of the fix: applied (commit `b556d87`), then reverted in
commit `da00727` for reasons outside this plan. Re-applying remains a
one-line change. The `digdir.rag.query-relaxation` namespace (the
older, non-skill version) reads `:tool-calls` correctly and has been
the path that actually worked since before the skill version was
introduced.

Worth quantifying the impact properly via `:builtin/retrieve-only`
benchmarks (which force `:plan` invocation) — `agent-budget-benchmark`
is the wrong harness for this fix.

### Post-D1 bug-finding (2026-05-19, late)

Two follow-up issues surfaced after D1 landed:

**Hotfix: `:dataset-config-key` leak through diagnostics fallback.**
The playground only threads `:tenant` via skill-params, so the eval
step's `:$dataset-config-key` resolved to nil. Diagnostics'
`normalize-diagnostics-dataset-ref` then falls back through
`:tenant-config-key`, and our literal `"default"` tenant-config-key
leaked into the dataset key — tripping the agent's
`:allowed-dataset-scopes` check with "not allowed to access dataset
default". Fixed by hardcoding `:dataset-config-key "public-docs"` in
the eval step of both inner sub-graphs. Empirically validated by
`gate-pass=true` on multiple traces afterward.
(In `self_improve_graph.clj` and `self_improve_phrases_graph.clj`.)

**Schema bug: `prompt_hash` was un-indexed in both enrichment schemas.**
`hypothetical-questions-schema` and `verified-phrases-schema` declared
`prompt_hash` with `:index false`. Typesense silently returns zero
matches when `filter_by` targets an un-indexed field (no error
surfaced), which is why revert-by-prompt-hash returned 0 deletions on
phrases — the chunk-id-only filter worked but the conjunctive
`chunk_id:=[...] && prompt_hash:=[...]` did not. Fixed in
`collections.clj`: both schemas now use `:index true` on `prompt_hash`.
**Migration required** before the fix takes effect: Typesense's
`alter_collection` cannot flip a field from un-indexed to indexed
in place; existing collections need to be dropped + recreated (or the
prompt_hash field drop+re-added via alter API). Until that's done,
the revert path is still effectively chunk-id-only on the live
collections — which is the v1 graph's documented behaviour anyway.

Both of these were caught only because the graph's structural form
makes silent failures visible (the eval step's "dataset not allowed"
error appeared in the trace immediately; the schema bug showed as
`reverted-count 0` in traces that explicitly hit the revert branch).
Same lesson as the earlier silent-failure audit.

### Validation experiment retrospective (for the writeup)

Useful framings if we want to write this up externally:

- "Five bugs hidden behind silent failures, surfaced only by running
  the graph end-to-end and watching the trace" — concrete cost of
  caught-and-defaulted exception handling.
- "Adding the intent-extraction node turned a noisy English prompt
  over a Norwegian corpus from 0 hits to a successful gate-pass in
  one trace" — the structural value of separating intent from action.
- "Wall-clock dropped from minutes (ReAct freeform loop) to ~10
  seconds (graph) for the same eventual outcome." — the practical
  cost of LLM deliberation between tool calls.
- Same fixture, same eval suite, two agents available in the playground
  picker for any reader to reproduce. The branch and trace files are
  the artifact.
