# Corpus-aware PRF query expansion — implementation plan

## Goal

Productionize a blind, automated corpus-aware expansion mode in the
query-planner skill, and expose it so `bb v3-score` measures all three modes
(`blind`, `corpus-aware-1hop`, `corpus-aware-2hop`) on every case. Replaces the
manual, golden-informed Phase-C prototype with a leakage-free procedure.

See `plans/in-progress/golden-chunk-tuning/results.md` Phase C for the prototype
result on Q01 (0/5 → 4/5; both core chunks top-4) and the loop plan step 4 for
the standing-step + blindness discipline this implements.

## The seam (from codebase investigation)

| Caller | dataset/collection context? |
| --- | --- |
| skill-graph runtime (`graph/runner.clj` → `execute-step`) | YES — `:phrases-collection` wired as input; Typesense at `ctx[:services :typesense]`; `:dataset-config-key` in skill-params |
| `/api/debug/query-planner` (used by **v3-score**) | NO — only `:tenant` |
| `rag/query_relaxation.clj` wrapper | NO — only `:tenant` |

**Blocker:** v3-score expands via the debug endpoint, which lacks collection
context. So the debug endpoint must be extended to accept dataset context and
resolve the phrases/docs collections before PRF can be measured by v3-score.

Reusable building blocks:
- `digdir.pipeline.collections/pipeline-collection-names` → `{:phrases-collection
  :docs-collection ...}` from pipeline config.
- `digdir.rag.retrieval/lookup-search-phrases-similar` — searches the phrases
  collection, but returns chunk refs (`:chunk_id :rank`), NOT phrase text. The
  PRF harvest needs the `search_phrase` text + source doc title, so add a small
  sibling helper that projects those fields (one `ts-client/multi-search` with
  `include_fields=search_phrase` + the joined doc `title`/`linktitle`).
- `litellm/completion` (planner pattern at query_planner.clj:261) for the
  grounding LLM call; config via `cfg/get {:tenant tenant} :services :azure-openai …`.

## Design

### Blindness (enforced in code, not by convention)

- Hop-2 scope = the **top source doc_nums of the hop-1 phrase harvest** (the
  docs whose phrases matched the user's intent). This is derived purely from
  the live index response to the query — never from goldens. (Simpler than a
  separate retrieval pass and equally blind.)
- Grounding is an LLM call conditioned on harvested candidates; no golden
  knowledge is available to it.

### query-planner skill changes (`builtin/query_planner.clj`)

Add parameter `:expansion-mode` ∈ `{:blind :corpus-aware-1hop :corpus-aware-2hop}`
(default `:blind` = today's behaviour, zero regression). Add optional inputs
`:phrases-collection`, `:docs-collection` (graph already provides them).

Pipeline when corpus-aware:
1. Run the existing blind expansion → `user-intent` + `blind-phrases`.
2. **Hop-1 harvest:** phrase-search the phrases collection with `blind-phrases`
   (+ `user-intent`); collect top `search_phrase` strings and their source
   doc_nums/titles (deduped, score-ranked).
3. **Grounding LLM call:** prompt = {user query, blind expansions, harvested
   corpus phrases + titles}; instruct it to emit N expansion phrases using the
   corpus's actual vocabulary. → `grounded-phrases`.
4. If `:corpus-aware-2hop`: take top-K source doc_nums from step 2, phrase-search
   the phrases collection **scoped to `doc_num:=[those]`** for procedural
   vocabulary, fold those candidates into a second grounding call (or extend the
   step-3 prompt). → adds procedural phrases.
5. Return `{:queries [user-intent & grounded-phrases] :user-intent}` plus
   `:metadata {:expansion-mode :harvested-phrases :hop1-docs …}` for transcript.

**Graceful fallback:** if `:phrases-collection` / Typesense client are absent
(debug/standalone without dataset context), log and fall back to `:blind`. Never
throw — non-graph callers keep working.

### debug endpoint changes (`endpoints/debug.clj` + `endpoints.clj`)

Extend `debug-query-planner-handler` + its query-param schema to accept:
- `dataset-config-key` (optional) — resolve `{:phrases-collection :docs-collection}`
  via `pipeline-collection-names` and pass as inputs.
- `expansion-mode` (optional, default `blind`).
Pass both into `execute-query-planner`. When `dataset-config-key` is omitted,
behaviour is unchanged (blind only).

### v3-score changes (`bb.edn`)

- Add `--expansion-mode blind|corpus-aware-1hop|corpus-aware-2hop` forwarded to
  the planner endpoint (with `dataset-config-key`).
- Add `--expansion-compare` that runs all three modes for each question and
  prints a per-question table (chunk@10/@30, doc@10/@30) with a per-mode TOTAL —
  the standing A/B the loop wants. Default остаётся single-mode for back-compat.

## Tests (`server/test/...`)

- query-planner: `:blind` unchanged (golden output stable); `:corpus-aware-1hop`
  issues a phrase search + 2nd LLM call and returns grounded phrases (mock
  Typesense + litellm); fallback to blind when phrases-collection absent.
- hop-2 scopes its phrase search to hop-1 doc_nums (assert the `filter_by`).
- debug endpoint: `dataset-config-key` resolves collections; omitted → blind;
  `expansion-mode` plumbed through.

## Rollout / measurement

1. Land skill + endpoint + v3-score behind the default-blind flag (no behaviour
   change until asked for).
2. Re-run Q01 with `--expansion-compare`; confirm it reproduces the Phase-C
   ceiling *blindly* (expect 1-hop ≈ 2/2 docs; 2-hop core chunks — automated
   numbers may be below the hand-tuned ceiling, that's the honest result).
3. Then resume the loop: every new case records all three modes.

## Out of scope (for now)

- Enabling corpus-aware mode in the production retrieval graph by default (it
  stays measurement-only behind the flag until the loop shows it generalizes).
- Caching/perf tuning of the extra phrase-search + LLM call.

## Run-to-run variance — sources (reference)

Phase D (results.md) showed aggregate doc recall swinging 57%↔86% between
identical runs. Mental model of every nondeterministic point in the
`corpus-aware-2hop` path, ranked by likely impact. Scoring itself is pure
set-intersection (zero variance); everything upstream of it:

`v3-score → planner endpoint (TWO LLM calls) → PRF harvest (Typesense) →
typesense-retrieve → score`.

1. **Grounding LLM sampling — primary.** Both planner calls run at
   `temperature 0.1`, no seed. Whether a run emits the bridging domain term
   ("dynamic expressions", "system user") is a temp-weighted draw. Even
   `temperature 0` is NOT fully deterministic on Azure/gpt — batched-inference
   float non-associativity and MoE expert-routing both vary with server-side
   batch composition. temp→0 reduces, doesn't eliminate.
2. **Two-stage amplification — why variance is LARGE.** Call 1 (intent + blind
   phrases) feeds the PRF probes → harvest → candidate list → call 2
   (grounding). A small call-1 wording change shifts the harvest, which shifts
   call-2 input. Two stacked stochastic calls, the first feeding the second
   through retrieval → variance compounds rather than averages. Structural
   reason Q01/Q03 flip while single-call-aligned Q05 stays stable.
3. **Auto-filter amplification — deterministic, but a CONFOUND/footgun.** ON in
   v3-score (`:auto-filter "true"`). `digdir.rag.auto-filter` is deterministic
   regex (word-boundary match on org-facet values + year detection, facet
   cache) — NOT an LLM call. But it runs on the VARYING grounded queries, so a
   run whose queries contain an org/year token can gain or drop a `filter_by`
   that includes/excludes the golden doc, flipping recall hard. Also a
   measurement confound — the ts-retrieve isolation convention flips it OFF for
   exactly this reason; v3-score leaves it ON.
4. **Silent fallback to blind — explains full-0 runs.** The planner retries on
   transient errors then falls back to the raw query. A transient 429/timeout
   on EITHER call → that run silently scores ~blind. Distinguishable via the
   `:fallback?` / `:corpus-aware-fallback?` flags the endpoint already returns.
5. **RRF tie-breaking — small, fixable.** `rrf-rank` uses `group-by`
   (hash-map iteration order) + `sort-by` on RRF score; equal-RRF phrases break
   ties by unstable hash-map order, so candidate ordering and `top-doc-nums`
   can shift even with an identical harvest. Removable with a secondary sort key.
6. **ANN/HNSW approximate search — minor.** Hybrid `phrase_vec` harvest and
   retrieval vector search are approximate; usually deterministic for a fixed
   index+query, but `rank_fusion_score` ties add jitter. Negligible vs 1–2.
7. **Environmental — mostly slow.** Index mutation between runs (Typesense
   shared across worktrees? enrichment writes?), cold-vs-warm facet cache,
   model-version drift behind the `gpt-5.4-mini` deployment. Don't usually move
   within-minutes runs except concurrent writes.

**Discrimination experiment (cheapest split):** call the planner endpoint ~5×
for 2–3 queries; diff `:queries`, `:hop1-docs`, and the fallback flags.
- `:queries` stable but recall varies → downstream (auto-filter / ANN) = #3/#6.
- `:queries` vary → planner-side = #1/#2/#5.
- fallback flags true on a run → #4 live; 0-runs are partly silent failures.
Then a focused #3 test: run the same grounded queries through retrieval with
auto-filter ON vs OFF and compare golden recall.

## Next change (teed up): single-call collapse

**Motivation.** Corpus-aware mode currently makes TWO Azure LLM calls per query
(call-1 = intent + blind-expansion probes; call-2 = grounding). Under sustained
load that doubles latency, cost, and failure surface — and Azure flakiness
(timeouts + 5xx "contact Microsoft" backend errors, NOT clean 429s) then forces
silent fallbacks to blind that depress corpus-aware recall. Halving the LLM
calls is the throughput/reliability win that is *within our control* (vs an
Azure quota knob, which we have no 429 evidence to justify).

**The constraint.** The two calls can't be naively merged: the PRF harvest runs
*between* them (call-1 output seeds the harvest probes; call-2 grounds on the
harvested candidates). To reach one LLM call, one touchpoint must go non-LLM.

**Design 1 (lead).** Drop the blind-expansion LLM call; make the harvest probe
deterministic.
- Hop-1 harvest probes = the raw query (optionally + a cheap deterministic
  keyword/noun-chunk split — no LLM).
- Hop-2 harvest scoped to hop-1's top RRF docs (unchanged) — still free
  (Typesense), so 2-hop is preserved.
- ONE LLM call = grounding, with its tool returning BOTH `user_intent` and
  `search_phrases` (fold intent-extraction into grounding). Prompt: "given the
  raw user question + these harvested corpus phrases, extract the topical
  user-intent AND produce grounded search phrases preferring corpus vocabulary."
- Result queries = `user_intent` + grounded phrases (cap as today).
- Blind mode unchanged (its own single call).
- Net: corpus-aware 2 calls → 1.

**Risk.** Raw-query hop-1 harvest is noisier than the LLM-blind-phrase probes
(observed: the verbose query harvests off-topic phrases). Mitigations: the
grounding LLM filters the noisier candidate set; hop-2 re-harvest recovers via
the RRF-top docs; optional deterministic keyword split improves probes. Whether
this holds is an empirical question → must A/B.

**Design 2 (fallback).** Keep call-1 (intent + blind probes); drop the grounding
LLM; use the top-N RRF-ranked harvested phrases DIRECTLY as queries. Simpler,
but an earlier select-verbatim variant underperformed generate-grounding (though
that was pre-RRF-ranking).

**Validation A/B (on the 59-query set):** two-call (current) vs Design 1 vs
Design 2, comparing chunk + doc recall AND LLM-calls/query, latency, and
fallback rate. Keep the best recall-per-LLM-call. Add `--expansion-variant` (or
a config flag) so v3-score can compare them like the existing modes.

**Tests:** mock harvest + a single `litellm/completion`; assert corpus-aware
makes exactly ONE completion call; assert queries = user_intent + grounded;
assert blind mode unchanged (still one call, no harvest).

**Sequencing:** quantify the current run's fallback rate first (fallbacks ÷
total). If fallbacks are corrupting (not just noise), this change is also the
remedy. Implement + A/B after the generality verdict lands.
