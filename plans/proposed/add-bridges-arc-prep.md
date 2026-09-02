# Prep for the ADD-bridges arc (grow enrichment), + post-prune consolidation

**Status:** proposed
**Date:** 2026-06-04

The prune half is net-flat (no aggregate recall gain — `combined-phrase-prune-scope.md`).
Evidence points the other way: the ADD half (grow enrichment bridges) has a *positive*
prior (the enrichment arc recovered lay/cross-lingual gaps — `project_enrichment_lever`)
and was never net-benefit-tested. This is the readiness + cleanup map for that arc.

## Readiness — the ADD net-benefit A/B is runnable (no infra blocker)

Confirmed: retrieval honors an explicit `:enrichment-search-targets` override
(`retrieval.clj:~726`, `(or (not-empty enrichment-search-targets) (derive...))`), and a
matrix's per-config `:skill-params` are the highest-precedence layer. So the A/B needs only
a matrix config, NO runner change:

```clojure
{:id "enriched" :skill-params
 {:builtin/retrieval {:enrichment-types [:verified-phrases]
                      :enrichment-search-targets {:verified-phrases "<grown-clone>"}}}}
```

off arm = `{}` (enrichment off, today's default) vs on arm = grown enrichment clone.
Windowing-on both arms, full-42, N≥5, JUDGED. The off/on delta isolates the ADD effect.

## Reusable assets the prune arc already built (reuse, don't rebuild)

- **The benefit-judging IP** (`verify_prune_benefit.clj`): competition-aware LLM judge
  ("for query=p, is chunk c a genuinely strong answer vs crowding?") + the cheap
  **content-rank** proxy (is c a top content-match for its own phrase?) that tracks it
  ~73%/66% and composes (cheap pre-filter → LLM confirm, batch-of-one, no benchmark). This
  is direction-agnostic: ADD wants HIGH-bridge-quality phrases, PRUNE removes LOW. The
  exact same content-rank + competitor machinery answers both.
- **Clone + sweep-override A/B pattern**: `runner.clj` `:phrases-collection-override` + the
  per-config override; the clone-build (`phrase_prune.clj` `:floor 0.0` = full clone);
  competing-chunk extraction from an off-arm `runs.csv`.
- **The corpus runner** (`prune_corpus.clj`): per-chunk skill over a chunk set, progress +
  idempotent/resumable. Generalizes to an add pass.
- **Harness pattern** (`prune_harness.clj`, `benefit_baseline.clj`): run a gate over a chunk
  set, measure agreement / per-stage stats.

## Consolidation / streamline / cleanup (prioritized)

1. **Extract a shared `phrase-bridge-quality` module (HIGH — the key dedup).** Today two
   benefit gates disagree on method: ADD's `verify_retrieval.clj` uses the OLD inward
   position-delta (baseline content-search vs enriched lookup); PRUNE's
   `verify_prune_benefit.clj` uses the better competition-aware content-rank + LLM. Unify
   into one direction-agnostic signal ("is phrase p a good bridge for chunk c?") that ADD
   (keep if high) and PRUNE (remove if low) both call. This dedups the gates AND upgrades
   the ADD gate to the validated approach. Best done at arc-start (its exact shape follows
   the ADD design).
2. **Generalize `prune_corpus.clj` → a per-chunk-skill corpus runner (MEDIUM).** Parameterize
   the skill it invokes so the same progress/resume harness drives the ADD pass.
3. **Decouple `prune_chunk` from `propose_prune`'s 47k-phrase IDF read (MEDIUM, efficiency).**
   prune_chunk uses content-rank for candidate gen; it calls propose_prune only to ENUMERATE
   a chunk's phrases yet pays its full corpus-df computation. An enumerate-only read drops
   that cost. (Only matters if the prune skills are revived.)
4. **Dedup the token-IDF scorer (LOW).** `propose_prune.clj` + `phrase_prune.clj` both carry
   tokenize/df/specificity. IDF is now a secondary signal (content-rank won), so this may be
   removed rather than deduped.
5. **Remove the dead semantic proxy from `verify_prune_benefit` (LOW).** It lost to
   content-rank (60% vs 73%) and is unused in the pipeline; computing it costs a vector
   search per call. Drop when the module is extracted (#1).
6. **Shelve/label the precursor (LOW).** `phrase_prune.clj`'s offline IDF cap is net-negative
   and superseded; keep only its full-clone capability (reused for A/B clones). The research
   record lives in the committed plan + memory.

## Recommendation

The infra is ready (no blocker). The single highest-leverage prep is **#1 — extract the
shared content-rank + LLM bridge-quality gate** so the ADD arc reuses the validated benefit
machinery instead of `verify_retrieval`'s older signal. I'd do #1 + #2 as the first step of
the ADD arc (when its shape is fixed), and fold #3–#6 in opportunistically. Everything else
(ephemeral `/tmp` files, dropped clone) is already cleaned up.

## ORACLE ADD RESULT (2026-06-04): mildly net-positive, but doesn't touch the hard subset

First ADD experiment: grew 258 discriminative verified-phrases over the **43 benchmark
GOLDEN chunks** (`add_corpus.clj` → `website_enrichment_verified_phrases_grown_v1`), A/B off
vs enrichment-ON (`add-oracle-ab.edn`, full-42 N=5 JUDGED, windowing-on). This is the
*oracle upper bound* — enriching the answer chunks directly.

| subset | off recall@20 → enriched | off judge ok → enriched |
|---|---|---|
| long (21) | 0.719 → 0.724 (+0.005) | 76 → 77 |
| mid (10) | 0.840 → 0.840 | 35 → 40 (+5) |
| clean (11) | 0.836 → 0.873 (+0.037) | 49 → 49 |
| **overall** | 0.779 → **0.790 (+0.011)** | 160 → **166 (+6)**, inc 5→4 |

**Mildly net-POSITIVE** (+0.011 recall, +6 judge, −1 incorrect) — the first net-positive
direction in the whole sub-arc, opposite sign from the prune (−0.010 / −5). BUT the gain
concentrates on **clean/short goldens** (recall +0.037) and **answer quality** (judge +6);
the **long subset is flat** (+0.005) → enrichment does NOT solve the core Mode-B burial of
the hard, long goldens. And this is the oracle ceiling; realistic gated corpus-wide growth
would be smaller. (A halfway interim read showed −0.043 — partial-read noise; it converged
to +0.011 at full N. Don't act on partials.) Grown clone dropped.

**Read:** enrichment helps the already-findable surface/answer a bit better; it doesn't
attack the residual that matters (long-golden Mode-B). Decide whether the modest, short-
golden-biased gain is worth productionizing, or whether to (a) try the OTHER enrichment
type (hypothetical-questions — its prior is specifically lay-query bridging), or (b)
investigate WHY the long goldens don't benefit (the dilution/merge interaction).

## ORACLE ADD-HQ RESULT (2026-06-04): hypothetical-questions is STRONGLY positive, cracks the long subset

Grew 215 hypothetical-questions over the 43 goldens (`add_corpus.clj` `:enrichment-type
:hypothetical-questions` → grown HQ clone), A/B off vs enrichment-ON (`add-hq-oracle-ab.edn`,
full-42 N=5 JUDGED).

| subset | off recall@20 → enriched-hq | judge ok |
|---|---|---|
| long (21) | 0.671 → **0.743 (+0.072)** | 69 → 68 |
| mid (10) | 0.830 → 0.940 (+0.11) | 38 → 38 |
| clean (11) | 0.782 → 0.964 (+0.18) | 46 → 51 (+5) |
| **overall** | 0.738 → **0.848 (+0.11)** | 153 → 157, inc 7→4 |

**Strongly net-POSITIVE (+0.11 recall), and lifts the LONG subset (+0.072)** — the hard
Mode-B cases verified-phrases left flat (+0.005). First substantial recall mover besides
Lever A. Mechanism: benchmark queries ARE questions, so HQ bridges question→answer-chunk
directly (question-to-question match) where keyword phrases only matched fragments — the
lay-query bridging the enrichment arc predicted. Scoreboard: prune −0.010 / vp +0.011 / **HQ
+0.11**. CAVEAT: oracle upper bound (enrich the answer chunks directly); the off arm was a
low 0.738 draw (vs ~0.78–0.80) but the paired delta is robust and enriched 0.848 is the
highest observed. Grown clone dropped.

**Next:** realistic corpus-wide HQ growth (enrich the competing chunks, not just goldens) —
how much of +0.11 survives when distractors get enriched too? That is the productionization
test for hypothetical-questions enrichment.

## REALISTIC ADD-HQ RESULT (2026-06-04): SHIPPABLE — +8.6pp answer correctness survives, recall partly washes out

Grew 2150 HQ over the 430 benchmark-COMPETING chunks (goldens AND distractors, no oracle
targeting), A/B off vs enrichment-ON (`add-hq-corpus-ab.edn`, full-42 N=5 JUDGED).

| subset | off recall@20 → enriched-hq | off judge ok → enriched-hq |
|---|---|---|
| long (21) | 0.714 → 0.714 (flat) | 67 → 71 (+4) |
| mid (10) | 0.840 → 0.920 (+0.08) | 28 → 41 (+13) |
| clean (11) | 0.782 → 0.818 (+0.04) | 46 → 47 |
| **overall** | 0.762 → **0.790 (+0.028)** | 141 → **159 (+18 ≈ +8.6pp)**, inc 5→3 |

**Net-positive and shippable.** The split is the finding:
- **Recall partly washed out** — oracle +0.11 → realistic +0.028; the LONG subset went FLAT
  (oracle +0.072 did NOT survive). Enriching distractors gives them question-bridges that
  crowd back (buried 6→13). So the precise golden-recall gain is an oracle artifact.
- **Answer quality jumped and SURVIVED** — judge 141→159 (+18, ≈+8.6pp), inc 5→3, mid +13.
  This is the user-facing metric (did the agent answer right), and it's LARGER realistically
  than in the oracle (+18 vs +4). HQ bridges help find/synthesize the right content broadly.

HQ enrichment does NOT solve long-golden Mode-B burial, but it makes the agent answer ~8.6pp
more questions correctly, robustly under realistic growth. Grown clone dropped.

## Shipping it (what production needs)
- Grow HQ corpus-wide via the gated self-improve loop (`self_improve_phrases_graph`'s
  question variant / `self-improve-graph`) — `add_corpus.clj` is the ungated bulk version.
- Enable `:enrichment-types [:hypothetical-questions]` in the production dataset config
  (`:retrieval-enrichment-types`); the sibling enrichment collection auto-derives — no
  `:enrichment-search-targets` override needed in prod (that was the A/B clone mechanism).
- Confirm at corpus scale (all chunks, not just the 430 competing) + a judged confirm A/B.
- No planner confound (enrichment is a separate sibling strategy; doesn't touch primary).

## SHIP CHECKLIST — HQ enrichment to `release-v0.1-details` (post-compaction self-sufficient)

Validated payoff: realistic HQ enrichment = **+8.6pp answer correctness** (judge 141→159),
survives corpus-wide growth, generated bridges confirmed high-quality (`inspect_enrichment`).

**Branch mechanics:** all this work is committed on `establish-baseline` (this worktree).
`release-v0.1-details` EXISTS and is checked out in ANOTHER worktree (`git branch` shows `+`),
so you can't `git checkout` it here — cherry-pick the relevant commits onto it, or do the ship
steps in that worktree. The shippable *code/config* is just the enrichment-enable + (optionally)
the committed enrichment tooling (`add_corpus`, `inspect_enrichment`, generalized for both types).

**Step 1 — enable the strategy (config, not code):** set dataset-config
`skills.retrieval.enrichment-types` = `"hypothetical-questions"` for tenant `digdir`, dataset
`default` (config-db key `:retrieval-enrichment-types` → that path, `config/db.clj:2273`). Use
`bb config-set` (touches the marker file; `bb dev` polls 5s, debug `/api/debug/config/refresh`
pushes immediately — no JVM restart). Retrieval then self-derives the sibling enrichment
collection name from the chunks collection (no `:enrichment-search-targets` override needed in
prod — that was only the A/B clone mechanism).

**Step 2 — grow HQ corpus-wide (gated):** the realistic test grew UNGATED over 430 competing
chunks via `add_corpus.clj` (a test tool). For production, grow over ALL chunks via the GATED
self-improve loop (`demo/self_improve_phrases_graph` question-variant / `self-improve-graph`,
which has the verify-retrieval keep/revert gate) so only path-creating bridges land. Spot-check
quality with `inspect_enrichment` (dumps to EDN, no collection touched). The grown rows go to the
REAL sibling HQ collection (`website_enrichment_hypothetical_questions_<hash>`), not a clone.

**Step 3 — confirm at corpus scale:** one judged A/B, off vs enrichment-ON, full-42 N=5, windowing
default both arms. Expect the +8.6pp answer-correctness to hold (it survived the 430-chunk
realistic test; corpus-wide adds more bridges + more distractor-enrichment — confirm the net).
NO planner confound (enrichment is a sibling strategy; doesn't touch the primary collection the
query-planner harvests).

Caveat to carry: HQ does NOT fix long-golden Mode-B recall (that washed out under realistic
growth); the win is answer quality. Lever A (windowing) remains the recall/scoring lever, already
default.

## Relates to
- [[combined-phrase-prune-scope]] — the prune arc + its net-flat verdict.
- `project_phrase_promiscuity_noise` — the prune-loop build + verdict.
- `project_enrichment_lever` — the ADD half's positive prior (the reason for this arc).
