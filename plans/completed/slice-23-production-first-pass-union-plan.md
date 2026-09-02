# Slice 23 — Carry first-pass union into production retrieval

Move the slice 21/22 user-intent first-pass union (cap N=3) from the
`bb v3-score` measurement harness into the production retrieval
skill, so that real agents benefit from the harness-only lift.

## Why this matters

Slices 18–20 (intent-aware planner) and slice 25 (doc-title pass-2
ranking glue + per-field fan-out) are already in production because
they live inside skills the agent calls in its normal loop. The
intent-aware planner returns `{:queries [...] :user-intent "..."}`
and slice 25's `search-docs-by-title` improvements live in the
retrieval skill itself.

Slices 21–22 are **not** in production. The two-pass + union logic
lives in `bb.edn`'s `v3-score` task (around bb.edn:870-939 for
union strategies; bb.edn:958-964 for the two-call pattern). The
production retrieval skill makes one call per agent iteration and
is unaware of `:user-intent` as a signal distinct from `:queries`.

Concrete impact: real agents lose the cap-N3 first-pass union lift
that the harness measurements take credit for.

## Acceptance criteria

A fresh `bb v3-score` run that calls retrieval **through the
production path** (not through `/api/debug/typesense-retrieve` with
the harness-side union) should reproduce the slice-25 harness
numbers within variance.

The relevant baselines, all measured against the v3 cited-chunks
ground truth with ColBERT rerank on, `--expand-queries 8`, 3-run mean:

| Configuration | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| **Pre-slice-25 production (no union, no sort fix)** | 34.8% | 43.5% | 64.0% | 64.0% |
| Slice 22 harness w/ cap-N3 (no sort fix) | 42.0% | 50.7% | 64.0% | 66.7% |
| **Slice 25 production today (sort fix shipped, no union)** | — | — | — | — *(needs measure)* |
| **Slice 25 harness w/ cap-N3 (sort fix + harness union)** | **43.5%** | **53.6%** | **66.7%** | **77.8%** |
| **Slice 23 production target (cap-N3 in skill)** | ≥41% | ≥51% | ≥64% | ≥75% |

The "Slice 25 production today" row needs to be measured before
implementation starts — `bb v3-score` without `--user-intent-first-pass`
will produce it. That number is the floor slice 23 must beat.

In addition: existing callers that don't pass `:user-intent` must
keep working with no behavior change (backwards compat).

## Reference implementation

The harness implementation in `bb.edn` is the spec for the
server-side behavior. Specifically:

- **Union strategy** — `bb.edn:870-939`, function `union-merge`
  with three modes: `:cap` (default, the winner), `:interleave`,
  `:rrf`. Cap N=3 takes the first 3 chunks from the intent-only
  pass, then concatenates the expansion pass with chunk-id dedup.
- **Two-call orchestration** — `bb.edn:958-964`, conditional on
  `--user-intent-first-pass true` AND `user-intent != queries`.
  When the intent string matches the queries string (e.g.
  `--expand-queries` not set), skip the second call.

Carry these semantics into `digdir.skills.builtin.retrieval`.

## What's in scope

1. **Retrieval skill schema** gains an optional `:user-intent`
   input.
   - File: `server/src/digdir/skills/builtin/retrieval.clj`
   - Schema location to extend: lines 25–40 (the OpenAPI-style
     skill descriptor) and line 49 (`:inputs` vector in the
     metadata block at line ~44).
   - Docstring at line 522 (`execute-retrieval`) lists current
     inputs; add `:user-intent` documentation.

2. **Retrieval skill execution path** detects `:user-intent` and
   runs the two-pass union when present.
   - The existing single-call path stays — when `:user-intent` is
     absent OR matches the (single) query, run as today.
   - When `:user-intent` differs from `:queries`, run TWO passes
     (intent-only + full-queries), then `cap-merge` with N=3.
   - The `union-merge`, `cap-merge`, `rank-interleave`, `rrf-merge`
     and `unpack-chunks` functions in `bb.edn:870-939` are the
     reference. Port to Clojure namespace under the retrieval
     skill (`digdir.skills.builtin.retrieval.union` is a natural
     home).

3. **Runtime config knobs** — make union configurable per tenant.
   New keys under `skills.retrieval.*`:
   - `skills.retrieval.user-intent-union.enabled` (boolean,
     default `true` for `digdir/public-docs`, `false` elsewhere
     until measured)
   - `skills.retrieval.user-intent-union.mode` (one of
     `"cap" | "interleave" | "rrf"`, default `"cap"`)
   - `skills.retrieval.user-intent-union.cap` (integer, default 3)
   - `skills.retrieval.user-intent-union.rrf-k` (integer, default
     60, only used when mode = "rrf")
   - Wire through the same way other retrieval params (e.g.
     `skills.rerank.enabled`) are wired — check existing config
     wiring for examples.

4. **Agent wires `:user-intent` through to retrieval**.
   - File: `server/src/digdir/skills/builtin/agent/tools.clj`
     around lines 645–730 (the `search` tool execution path).
   - Today the audit found that when the agent calls
     `plan_queries`, the planner returns `:user-intent`, but the
     agent only carries the `:queries` list forward into search.
   - The change: when the agent has a recent planner result with
     `:user-intent`, pass it into retrieval. Likely needs:
     - The agent loop's scratchpad/context to retain the last
       planner output (or re-extract user-intent from the latest
       user message + planner result).
     - The `search` tool's argument schema to accept
       `:user-intent` (LLM-facing), or to be auto-populated from
       agent state without LLM involvement.
     - Decision needed (see Open Questions): LLM-visible vs
       agent-state-driven.

5. **Debug endpoint** — extend
   `/api/debug/typesense-retrieve` to accept a `:user-intent`
   query parameter, so the harness can switch from client-side
   union to verifying the server-side union path.
   - File: `server/src/digdir/api/routes/endpoints/debug.clj`
   - Add to the Malli schema for query params (the audit notes
     that malli-reitit silently drops un-declared params, so this
     entry is required).
   - **Reference pattern**: slice 25 added three new params
     (`chunk-content-fields`, `chunk-metadata-fields`,
     `retrieval-mode`) to the same endpoint. The parse →
     parameter-build → cond-> assoc flow is the template to copy.

6. **Harness verification mode** — add a `bb v3-score
   --server-side-union true` flag that instead of doing the
   client-side union, passes `:user-intent` to the debug endpoint
   and lets the server do the work. Measurements with this flag
   should match slice-22's client-side numbers.

7. **Agent loop smoke test** — outstanding from task #62 (slice
   18). Carrying it now because slice 23 is the first slice that
   makes the production agent path materially different from the
   pre-planner baseline. Pick one v3 question (e.g. Q1 or Q5),
   run it through the production agent loop end-to-end, assert
   that retrieval received `:user-intent`, that the cap-N=3
   union ran, and that one of the cited chunks landed in the
   final context.

## What's out of scope

- **Corpus-vocabulary-aware phrase generation** — the bigger
  remaining lever for Q2/Q3/Q7. Tabled in
  `plans/proposed/corpus-vocabulary-aware-planning.md` per
  the user's instruction to handle that arc separately via
  purpose-built interactive skill graphs.
- **New ColBERT rerank tuning** — slice 23 must not change rerank
  behavior. Any rerank gains belong in a separate slice.
- **Per-language stemmed fields** — disproven in slice 25. The
  per-language `_en`/`_nb` field architecture has multi-field
  BM25 sparse-population issues that net-negative recall, AND
  the candidate-pool widening dilutes ColBERT rerank. Slice 23
  inherits the cleaned-up legacy-field schema; do not re-introduce
  language-variant fields.
- **Hierarchical retrieval mode.** Slice 25 added
  `:retrieval-mode :hierarchical` as an opt-in skill parameter
  but found it net-negative on v3 (drops `:phrase`/`:metadata`/
  `:content` signals the v3 chunks depend on). Slice 23 should
  apply the user-intent union to the default `:multi-strategy`
  mode; behavior in `:hierarchical` mode is out of scope (the
  hook is there for future experimentation, not production).
- **Server-side metric collection** — doc-level recall is a
  measurement-time concept (harness) and does not need to be
  computed server-side.

## Phased implementation

### Phase 0 — establish the production-today floor

0. Before any code change, run `bb v3-score --rerank-with-colbert
   true --expand-queries 8` (i.e. WITHOUT `--user-intent-first-pass
   true`) three times. This measures the single-pass production
   path against the v3 baseline on the current code. Record the
   3-run mean as the "Slice 25 production today" row in the
   acceptance table — the floor slice 23 must clear.

### Phase 1 — server-side union plumbing

1. Read `bb.edn:870-939` carefully. Port the four merge functions
   (`rank-interleave`, `cap-merge`, `rrf-merge`, `union-merge`)
   plus `unpack-chunks` into a new ns
   `digdir.skills.builtin.retrieval.union`. Keep the same
   keyword-driven `union-mode` switch.
2. Add unit tests for each merge function. Cover:
   - Empty A, empty B
   - Heavy overlap (most chunks in both)
   - No overlap (disjoint)
   - Cap N exceeds A's length
   - RRF order independence (a then b == b then a within
     floating-point tolerance)

### Phase 2 — retrieval skill integration

3. Extend `execute-retrieval` in
   `server/src/digdir/skills/builtin/retrieval.clj` to accept
   optional `:user-intent`. When present and distinct from
   `:queries`:
   - Execute the existing single-pass retrieval with `:queries`
     (full list, expansions included).
   - Execute a SECOND pass with `:queries = [user-intent]` only.
   - Apply `union-merge` according to the runtime-config knobs.
   - Continue with the existing rerank step on the merged list,
     then truncate to `:retrieve-top-k`.
4. Update the schema (lines 25–40 and 49), docstring (line 522),
   and metadata. Backwards-compat: callers without `:user-intent`
   hit the existing single-pass path.
5. Add a unit test that mocks the two retrieval calls and asserts
   the union output matches expectation.

### Phase 3 — runtime config wiring

6. Add the four new config keys under `skills.retrieval.*`.
7. Wire through the same plumbing other retrieval params use.
   **Reference pattern**: slice 25 added
   `skills.retrieval.chunk-content-fields` and
   `skills.retrieval.chunk-metadata-fields` in
   `server/src/digdir/setup/config.clj` — copy that structure.
   Default-on flags such as `skills.rerank.enabled` also work
   as templates.
8. Set the `digdir/public-docs` runtime config to enable the
   union by default (mode = "cap", cap = 3). Other tenants
   default to disabled until measured.

### Phase 4 — agent integration

9. Decide on LLM-visible vs agent-state-driven (Open Question A).
10. Plumb `:user-intent` from the most recent `plan_queries`
    output into the `search` tool's retrieval call.
11. Update agent integration tests that assert the search tool's
    invocation arguments.

### Phase 5 — debug endpoint + harness verification

12. Extend `/api/debug/typesense-retrieve` to accept
    `:user-intent` (add to Malli schema in
    `server/src/digdir/api/routes/endpoints/debug.clj`).
13. Add `bb v3-score --server-side-union true`. When set, instead
    of making two harness-side HTTP calls and merging client-side,
    pass `:user-intent` once in the params and let the server do
    the merge.
14. Re-measure 3 runs. Numbers must match slice-22 within
    variance.

### Phase 6 — smoke test + documentation

15. Agent loop end-to-end smoke test (task #62 carried forward).
    Pick Q1 (clearest first-pass-union win in slice 22).
16. Write the slice-23 in-progress doc capturing measurement,
    decisions made on Open Questions, any deviations from this
    plan, and remaining followups.

## Open questions

**A. Agent integration shape — LLM-visible or agent-state-driven?**

Two options:

- **LLM-visible `:user-intent` arg on the search tool.** The
  agent's tool schema adds `:user-intent` as an optional string
  arg, and the LLM is responsible for passing whatever it
  considers the canonical user-intent. Pro: explicit, debuggable.
  Con: depends on LLM behavior; LLM might skip it.
- **Agent-state-driven, automatic.** The agent loop remembers
  the last `plan_queries` output and auto-injects `:user-intent`
  into every subsequent `search` invocation in the same turn.
  Pro: no LLM cooperation needed; always-on benefit. Con: more
  invisible state; needs careful scoping (e.g. clear on new user
  message).

Recommendation: start with agent-state-driven. It's the simplest
way to guarantee the production agent gets the slice-22 win
without depending on LLM tool-call hygiene. Add LLM-visible as a
follow-up if needed for advanced agent patterns.

**B. Latency budget — 2x retrieval per agent search.**

The two-pass union doubles retrieval load. In the harness this
manifested as occasional `limit_multi_searches` errors from
Typesense (slice 21 doc, "Number of multi searches exceeds
limit_multi_searches parameter"). For production:

- Measure typical retrieval call latency today (likely ~200–500ms
  per call against local Typesense).
- Double that gives ~400ms–1s additional per agent search.
- Acceptable for now, but worth telemetry. Consider parallel
  execution of the two calls (`pmap` or `core.async`) so the
  wall-clock cost is one retrieval, not two.

**C. Production fanout interaction.** *(better understood after slice 25)*

Slice 25 hit Typesense's default `limit_multi_searches=50` ceiling
when the doc-title pass-2 issued more than 50 chunk lookups in
one multi-search call. Resolved by capping `matched-docs` to 40
in `search-docs-by-title` pass-2 (already shipped). The cap also
benefits from the sort-by-rank that landed alongside it.

The union does NOT compound this issue — each pass (intent-only
and expansions) is its own request, each independently bounded
by the existing per-strategy multi-search structure. The two
HTTP calls share no Typesense-side state.

What's still worth checking:
- `:phrase`, `:metadata`, `:content` strategies issue `(queries ×
  fields)` multi-search items per call. With slice 25's per-field
  fan-out and the default single-field config, that's just
  `queries` items — typically 6–8, well under 50.
- If a caller opts into multi-field configs
  (`:chunk-content-fields`, `:chunk-metadata-fields` — the
  opt-in params slice 25 added), the count multiplies and may
  approach 50. The intent-only pass uses 1 query, so it's safer.
  The expansion pass uses N queries; pair that with 4-field
  configs and you're at 32, still under 50 but tightening.

Concrete action: before slice 23 enables union for any tenant
beyond `digdir/public-docs`, scan that tenant's config for
multi-field overrides and the configured `--expand-queries N`.
If `N × max(fields)` ≥ 40, raise Typesense's
`limit_multi_searches` or cap N in the planner.

**D. Backwards compat for tenants without query-planner.**

If a tenant doesn't have `skills.query-planner.enabled = true`,
the agent never calls `plan_queries`, so `:user-intent` is never
populated. The retrieval skill should handle this transparently
— the existing single-pass code path stays exactly as today when
`:user-intent` is absent. Verify with a tenant config where the
planner is disabled.

## Risks

1. **Latency regression** (Open Question B). Mitigation: parallel
   execution + telemetry.
2. **Typesense multi-search limit overflows** (Open Question C).
   Mitigation: measure current limit, raise if needed, or
   conditionally reduce fanout when union is enabled.
3. **Hidden coupling in the retrieval pipeline** — the existing
   single-pass already does multi-strategy fusion (phrase,
   metadata, content, doc-title, enrichment). Adding the
   user-intent union on top might compound merge effects in
   unexpected ways. Mitigation: the harness's slice-22
   measurement is the safety net; the server-side numbers must
   match.
4. **LLM behavior shift** — adding `:user-intent` as an arg on
   the `search` tool could confuse current agents that have no
   reason to populate it. Mitigation: start with
   agent-state-driven (Open Question A); revisit if needed.

## Self-contained context for the fresh session

The agent picking up this slice will not have the prior
conversation context. Everything they need is in this plan plus
these references:

- **Slice 22 measurement table** —
  `plans/in-progress/target-optimal-baseline-v3/22-cap-union-mode.md`
- **Slice 21 first-pass union rationale** —
  `plans/in-progress/target-optimal-baseline-v3/21-doc-level-scoring-and-intent-first-pass.md`
- **Slice 25 stemming-arc results** (immediate predecessor) —
  `plans/in-progress/target-optimal-baseline-v3/25-stemming-implementation-results.md`.
  Important context: the harness baseline slice 23 must match is
  43.5/53.6/66.7/77.8, not the slice 22 numbers. Slice 25 already
  shipped per-field fan-out and a doc-title pass-2 sort+take cap
  that contributed +1.5–11.1pp on their own.
- **Harness reference implementation** — `bb.edn`, the `v3-score`
  task (search for `union-merge`).
- **Retrieval skill** —
  `server/src/digdir/skills/builtin/retrieval.clj`. The schema
  block, `:inputs`, and `execute-retrieval` all live in this
  file. Slice 25 added three opt-in parameters
  (`:chunk-content-fields`, `:chunk-metadata-fields`,
  `:retrieval-mode`) — use those edits as the template for
  threading `:user-intent` through.
- **Query-planner skill** —
  `server/src/digdir/skills/builtin/query_planner.clj` returns
  `{:queries [..] :user-intent ".."}`.
- **Agent search tool** —
  `server/src/digdir/skills/builtin/agent/tools.clj` lines
  645–730 (approx). Today: queries flow through; `:user-intent`
  is computed by the planner but discarded.
- **V3 question set** —
  `plans/in-progress/target-optimal-baseline-v3/0[1-7]-*.md`.
- **Debug endpoint** —
  `server/src/digdir/api/routes/endpoints/debug.clj`. Note:
  Malli reitit silently drops query params not declared in the
  schema (a slice-12-era trap to remember). Slice 25 added three
  new params and shows the parse / cond-> pattern.
- **Verify dev server URL** before measuring — see slice-21
  doc for the port-detection issue (Electric fell back from 8081
  to 8181 when shadow-cljs took the default). Use `lsof -p
  <java-pid>` to confirm bound port. This worktree uses
  `HTTP_PORT=8181` to avoid colliding with the sibling worktree.
- **`bb dev` startup** — the worktree needs
  `HTTP_PORT=8181 DATAHIKE_FILE_PATH=./local-db/dh_bb_dev_establish_baseline_v1
  RAG_TS_RETRIEVE_ENABLED=true bb dev` to bring up with both
  the right database and the typesense-retrieve debug endpoint
  enabled (the debug endpoint is off-by-default in production).
- **API key** — set `RAG_API_BASE_URL` and `RAG_DEBUG_API_KEY`
  env vars before any `bb v3-score` invocation. The server
  rejects calls without `X-Debug-Api-Key`.

## Done definition

- Phase 0 floor measured and recorded (single-pass production
  today, with slice-25 sort fix already in place).
- All four new retrieval-skill config keys exist and have
  default values.
- `bb v3-score --server-side-union true` reproduces slice-25
  harness cap-N=3 numbers within ±3pp on chunks (allowing for
  LLM-planner variance) and within ±5pp on top-30 docs.
- Slice 23 production target (cap-N3 in skill): chunks ≥41% top-10,
  ≥51% top-30; docs ≥64% top-10, ≥75% top-30. These thresholds
  give margin below the harness baseline for measurement noise.
- Agent loop smoke test (Q1) passes: retrieval received
  `:user-intent`, cap-N=3 union ran, one or more cited chunks
  landed in the final context.
- Backwards compat verified: a retrieval call without
  `:user-intent` produces identical output to the Phase 0 floor
  (no union).
- Slice-23 in-progress doc written, capturing measurement
  results, Open Question decisions, and remaining followups.
- Latency telemetry added or noted as a fast-follow.
