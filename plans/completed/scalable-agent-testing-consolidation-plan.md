# Scalable agent-path testing — consolidation & extension plan

**Goal:** a single, faithful, scriptable substrate for running the *full agent
loop* across many (config × question × repeat) combos, so agent-path behaviors
(including the insufficiency/refinement loop — "A") can be verified and
regression-guarded at scale — without manual playground clicking or an API key.

## TL;DR — the substrate already exists and is verified working

`server/src-dev/digdir/sweep/runner.clj` (the **sweep runner**) drives the full
agent loop in-process via `invoke-with-clarification-loop → invoke-rag →
run-skill-graph`. On 2026-05-30 I ran `smoke.edn` (2 configs × 2 questions = 4
runs) concurrently with `bb dev`: **all 4 `complete`, no `cfg/get` tenant error**,
faithful Norwegian answers with citations, real Filter-4 decomposition + cost →
`server/results/sweep-<ts>/runs.csv`. It resolves dataset config from the DB
upfront (`resolve-dataset-config!`), which is exactly why it avoids the tenant
bug that blocks `agent-budget-benchmark`.

**So "A at scale" is an EXTENSION of an existing tool, not new infrastructure.**

## The landscape, split by layer

### Retrieval-only fast loop (keep as-is — different layer, ~seconds/run)
- **`bb v3-score`** (`src-dev/digdir/sweep/v3_cites.clj`) — debug `typesense-retrieve`
  endpoint, chunk/doc recall@K vs golden cite files. The tight inner loop for
  retrieval-knob changes.
- **`bb rerank-benchmark` / `rerank-language-benchmark`** (`tools/diagnostics.clj`) —
  retrieval+rerank only, MRR/rank percentiles, deterministic.
- **`/api/debug/{query-planner,typesense-search,typesense-retrieve}`** — ad-hoc probes.

These bypass the agent loop *by design* — they're fast and deterministic. **They
also can't see any agent-path bug** (every silent no-op we fixed this session was
invisible to them). Keep them for the retrieval layer; do not rely on them for
agent behavior.

### Full agent-loop harnesses (consolidate here)
- **Sweep runner** (`src-dev/digdir/sweep/runner.clj`) — ✅ faithful, in-process,
  cartesian `configs × questions × repeats`, emits `runs.csv` with Filter-4
  decomposition (`:golden-in-search-pool?/-display?/-read?`), recall@5/10/20,
  citation-recall, full cost. **VERIFIED WORKING.** Only gap: REPL-only (no bb
  task), and it doesn't yet capture insufficiency/refinement signals.
- **`bb agent-budget-benchmark`** (`tools/diagnostics.clj`) — same layer, but
  **broken in bare JVM** (`cfg/get requires an explicit :tenant`) and narrower
  (current-vs-relaxed budget only). Redundant with the sweep runner.
- **Self-improve eval gate** (`src-dev/digdir/demo/self_improve_graph.clj`) — runs
  the agent loop to score enrichments; currently wraps `agent-budget-benchmark`
  with a hardcoded-tenant workaround.
- **`/v1/chat/completions`** (`api/.../openai_compat.clj`) — same `invoke-rag`
  path over HTTP; faithful but needs a minted tenant API key. Useful as the
  *external* probe; not needed for in-process sweeps.

### Divergent ground-truth formats (converge)
1. v3-score: markdown `## Cited chunks`.
2. sweep matrix: EDN `{:configs :execution-scope :question-filter ...}` + shared
   `test/fixtures/sweep/questions.edn` (id, query, golden-chunk-ids, tags, source).
3. agent-budget: EDN `{:cases [{:query :expected-answer-pattern :golden-chunk-ids
   :current-budget :relaxed-budget}]}`.
4. rerank: EDN captured candidate pools.

`questions.edn` (a flat id→{query, goldens, pattern, tags} registry) is the
natural convergence target; the others are config/budget axes layered on top.

## Consolidation (what to standardize)

1. **Make the sweep runner the canonical agent-path harness.** Add a thin
   `bb sweep` task (currently REPL-only) wrapping `run-from-files`.
2. **Absorb `agent-budget-benchmark` into the sweep matrix** as a `:budget-profiles`
   axis (current/relaxed become two configs or a per-run dimension). Then either
   fix its tenant threading to match the runner or retire it. Point the
   **self-improve eval** at the same runner so there is ONE agent-eval substrate.
3. **Converge suites onto `questions.edn`.** Migrate v3-score's markdown goldens
   and the agent-budget cases into the shared registry; keep matrices/budgets as
   thin overlays referencing question ids.
4. **Keep the retrieval-only loop separate.** v3-score + rerank-benchmark stay as
   the fast inner loop; they answer a different question (did the retrieval
   baseline move) and shouldn't be merged into the agent harness.

## Extension (what A — and the bug class — needs)

The runner records *where the golden landed* but not *what the agent did to get
there*. Add to `score-run` (runner.clj:210, all derivable from the existing
`result`/workspace-final):
- `:search-passes` — number of search passes (a re-search after insufficiency = >1).
- `:insufficiency-fired?` — did the sufficiency gate mark insufficient (from
  `:sufficiency-decisions` / `:shadow-sufficiency-decisions`)?
- `:refinement-corpus-grounded?` — **the A signal**: when insufficiency fired, did
  the refinement suggestions include the planner's corpus-grounded phrases (vs.
  collapsing to generic)? Derive from the workspace `:last-planner-phrases` +
  the emitted `[SYSTEM: … Suggested next call: search …]` guidance.
- `:enrichment-hits` — from search attribution (`:enrichment-hits-by-type`).
- `:clarification-rounds` — already emitted.

Then:
5. **Build a corpus-grounded HARD-question suite** (the Tier 1–5 set: SMS-segment
   numeric-in-table, consentRights multi-hop, vergemål temporal-caveat, status
   cross-system disambiguation, lay-vocab bridging) with goldens, added to
   `questions.edn`. These actually trigger the insufficiency loop.
6. **Use `:repeats` for distributions** — agent runs are high-variance (we saw
   #8/–/#19/#9/#10 on one query); 3–5 repeats give a band, not a point.

## Outcome
A single `bb sweep` over a hard-question matrix with N repeats that reports, per
question: golden decomposition, whether insufficiency fired, whether the
refinement stayed corpus-grounded, enrichment hits, recall, and cost — i.e. **A
verified at scale**, plus a durable regression harness for the entire agent-path
bug class found this session (optional-inputs, enrichment activation, corpus-aware
wiring, query dilution, refinement grounding).

## Sequencing
- **P0** (unblocks A): extend `score-run` with the 5 signal columns + add the
  hard-question suite + add the `bb sweep` task. Then run it with repeats.
- **P1**: converge suites onto `questions.edn`; absorb budget-profiles; retire/fix
  `agent-budget-benchmark`; repoint the self-improve eval.
- **P2**: optional `/v1` key-minting helper for external/faithful spot checks.

## Progress log

### P0 — DONE (2026-05-30, commit 483fda2 + follow-ups)
- ✅ Verified the sweep runner is the faithful agent-path substrate (smoke 4/4,
  no tenant bug). See `[[project_sweep_runner_agent_substrate]]`.
- ✅ `score-run` extended with 4 agent-path behavior signals (`:search-passes`,
  `:insufficiency-fired?`, `:refinement-corpus-grounded?` = the A signal,
  `:enrichment-hits`); added to `csv-columns`; `run-from-files` honors `:repeats`.
  Unit-verified + durable tests in `runner_test.clj`.
- ✅ `bb sweep <matrix> [--repeats N]` task added.
- ✅ Hard-question suite (5 Qs, Tier 1–5) in `questions.edn` + matrix
  `hard-agent-path.edn` with TWO configs (`levers-off` vs `levers-on`) that pin
  expansion-mode + enrichment-types via per-config `:skill-params` — the matrix
  IS the config axis, so the sweep MEASURES the lever impact head-to-head.
- ✅ **A VERIFIED AT SCALE.** levers-off run had `:enrichment-hits 0` and the
  insufficiency loop firing with generic refinement; the levers-on run on the
  consent-rights multi-hop question gave `:enrichment-hits 57`, corpus-grounded
  planner phrases, 3 search passes, and **`:refinement-corpus-grounded? true`**.
  The dilution + refinement (A) fixes hold end-to-end on the real agent path.

**Faithfulness note (resolved by design):** `invoke-rag` consumes a pre-built
`:skill-params` and does NOT resolve config defaults itself, so a bare matrix
config runs blind (no enrichment, default expansion). For sweeps this is correct
— pin the production levers per-config via `:skill-params` and compare. (A future
nicety: a runner helper that builds skill-params via
`api-util/build-rag-skill-params` from the resolved dataset/runtime config, for a
"production-default" config that needs no manual pinning.)

### P1 — IN PROGRESS
- ✅ **Fixed the `agent-budget-benchmark` `cfg/get` tenant bug** (commit 2a4d2a6).
  `run-agent-budget-profile` omitted the `:ambient-ctx-opts` graph input the
  agent-rag-graph reads tenant from; added it (mirroring invoke-rag). Verified:
  the stability suite runs through the agent loop with no tenant error. The
  bare-JVM benchmark is now faithful; the self-improve eval's hardcoded-tenant
  workaround is redundant (left in place — removing it touches the self-improve
  flow, do as a deliberate change).
- ✅ **Ground-truth convergence — `questions.edn` IS the canonical registry; lift
  precedent established.** Its schema already carries every source
  (`:baseline | :altinn3-stability | :exploratory-rerank | :new | …`), and agent
  fixtures are lifted into it on demand (e.g. `altinn3-lansert-when`, tagged
  `// LIFTED — altinn3_lansert_stability.edn`). The new hard-question suite was
  added the same way. **Decision: lift remaining legacy agent fixtures on demand
  rather than bulk-migrate; do NOT rewrite v3-score's markdown parser** — it is a
  working, frequently-used retrieval-only tool and converging its 7 cite files
  into the registry is net-negative (real regression risk on a tool in active
  use, for the cosmetic win of one file format). The registry is canonical; the
  convergence mechanism exists and is used.
- ✅ **Budget profiles subsumed by the config axis** — no new runner feature.
  Per-config `:skill-params` carries `:builtin/agent` budget knobs
  (`:max-search-passes`, `:max-read-operations`, `:max-read-content-length`), so
  "current vs relaxed" is two configs in a matrix (like levers-off/levers-on).
  agent-budget-benchmark's one unique capability is expressible in the sweep today.
- ✅ **Self-improve eval — substrate parity achieved at the invocation layer;
  scoring-engine unification deliberately NOT done.** Re-reading the `:eval` step
  (self_improve_graph.clj:120): its hardcoded `tenant-config-key`/`runtime-config-key`/
  `dataset-config-key` are *legitimate config pins* for the agent's
  allowed-dataset-scopes contract, NOT a tenant-bug workaround — `:tenant` is
  properly threaded (`:$tenant`), and the eval already runs faithfully in-server
  (it kept 7–8 enrichments this session). The benchmark tenant fix gave the
  *standalone* path parity too. So both eval substrates now drive the agent
  faithfully through the same invoke-rag/run-skill-graph path with correct tenant
  threading — the consolidation that matters. **Forcing the self-improve eval onto
  the sweep runner's recall/answer-hit scoring would change its keep/revert gate
  semantics on a flow we just stabilized — a product decision, not a mechanical
  refactor, so it is intentionally left to a deliberate future change rather than
  done blind.**

### P2 — optional, not needed
- `/v1` key-minting helper: unnecessary — the in-process sweep runner is the
  faithful programmatic agent-path interface (no API key required).

## Status: COMPLETE (P0 + P1; P2 declined as unnecessary)
All P0 deliverables shipped and A is verified at scale. P1's mechanical items are
done (benchmark tenant fix, budget-profiles-as-config, registry convergence
precedent); the two items that would have been net-negative or semantics-changing
are documented decisions rather than blind refactors. The session's agent-path
bug class now has a durable, faithful, scriptable regression harness:
`bb sweep <matrix> [--repeats N]`.
