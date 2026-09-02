# Implementation scope: combined, eval-gated phrase prune (self-improvement loop, prune half)

**Status:** proposed (scope)
**Date:** 2026-06-03
**Parent:** [[phrase-pruning-subtractive-plan]] (the offline-IDF precursor + scope reconciliation)

## Target restated

A productionized self-improvement **PRUNE** pass that removes generic/non-discriminative
phrases from the **combined (primary generated + enrichment) phrase surface**, gated by an
**enrichment-ON retrieval delta** so a phrase is removed ONLY if removal doesn't hurt. The
eval gate — not the IDF metric — is the discriminator, because IDF cannot tell a *deliberate
broad bridge* (the enrichment arc's lay/cross-lingual recoveries) from *generic noise*.

This is distinct from what we already ran: the offline-IDF clone (`phrase_prune.clj`, cap=20)
was (a) **confounded** (the override starved the corpus-aware query-planner, which harvests
the same primary collection — `agent/core.clj:834,868`) and (b) **primary-only, enrichment-off**.
It validated a precursor, not this target.

## Precondition finding (checked 2026-06-03) — reshapes the scope

Enrichment collections exist but are **near-empty**: `verified_phrases` 50, `hypothetical_questions`
116, `fact_assertions` 24 (~190 rows vs **46,969** primary). Retrieval has them OFF by default.
Consequences:

- **"Combined" ≈ primary today** (enrichment is 0.4%). An "enrichment-on" prune validation is
  only *meaningful* once enrichment is populated at scale.
- The PRUNE lever (remove primary noise) and the existing **ADD** lever (grow enrichment
  bridges, `self-improve-phrases-graph`) are **two halves of one self-improvement loop**. The
  ADD half exists but has only ever run on ~10-chunk samples.
- **Decision surfaced:** do we (1) scope/build the prune half against the primary surface now
  (enrichment negligible → validation is effectively primary-only, i.e. ~the precursor), or
  (2) first grow enrichment to scale via the ADD half, THEN prune over a genuinely combined
  surface? The user's "combined" framing implies (2) is the real target.

## The net-new axes (3 from the reconciliation + 1)

1. **READ both collections** — today nothing reads the existing phrase set for pruning.
2. **DELETE from primary** — today the agent only upserts to enrichment.
3. **eval gate runs enrichment-ON.**
4. **The gate must INVERT and go MULTI-QUERY.** `verify_retrieval.clj` is ADD-shaped:
   `:keep? = improved? AND eval-pass`, where `improved?` = "the new enrichment created a
   retrieval path from one intent." Pruning needs the opposite, across multiple queries:
   *does removal regress the queries this phrase legitimately serves, and does it relieve the
   queries where its doc over-surfaces?*

## Component design (grounded in the existing skills)

### A. Candidate generation — `:builtin/enrichment-propose-prune` (mirror of `propose_phrases`)
- **Unit: DOC** (promiscuity is doc-level; `analyze` currently samples chunks — extend to rank
  docs by phrase-volume × low-specificity, reusing the `phrase_prune.clj` token-IDF scorer).
- READ the doc's current **combined** phrase set: primary (`role=phrases`, filter `doc_num`)
  + enrichment verified-phrases (filter `doc_num`/`chunk_id`).
- IDF specificity + per-doc volume **seed** prune candidates; they do NOT decide. Optionally an
  LLM pass flags "non-discriminative for this corpus" on borderline candidates.
- Output: `[{:chunk-id :phrase :collection :primary|:enrichment :specificity}]`.

### B. The prune-verify gate — `:builtin/enrichment-verify-prune` (the core net-new logic)
For each candidate phrase `p` (chunk `c`, doc `d`):
- Build a query set — **(i) owner queries** `c`/`d` legitimately answers (must-not-regress),
  **(ii) off-target queries** where `d` over-surfaces as a distractor (should-improve-or-neutral).
- Run retrieval **enrichment-ON, production planner**, WITH `p` vs WITHOUT `p` (on a clone with
  `p` removed); measure owner-rank delta + off-target distractor-rank delta.
- `keep-prune? = (owner rank not regressed beyond ε) AND (off-target surfacing reduced or neutral)`.
- Query source: start **benchmark-derived** (label which doc each of the 42 answers; off-target =
  docs the question surfaces but shouldn't) for validation; generalize to generated queries later.
- Keep `eval_delta`/`eval_sweep` as the **global regression guard** (run enrichment-ON), unchanged.

### C. Apply / deletion — `:builtin/enrichment-apply-prune`
- **Primary:** delete by deterministic id `sha256-short(chunk_id "|" phrase)` (the
  `storage/store-phrases!` id scheme) — `delete-orphan-phrases!` already deletes by `doc_num` +
  keep-set; a targeted prune deletes specific ids.
- **Enrichment:** `revert_chunk.clj` already does generic `delete-documents!` by filter —
  delete by `chunk_id` + phrase.
- **Reversibility:** operate on CLONES (primary clone + enrichment clone) for validation;
  productionize to real collections only after a positive A/B, with rollback = drop clone.

### D. Orchestration — `:docs/self-improve-prune-phrases-graph` (mirror of the ADD graph)
`intent → plan-queries → analyze(rank over-promiscuous docs) → foreach doc [read-combined →
propose-prune → apply-prune(clone) → verify-prune(multi-query, enrichment-on) → decide(prune/keep)]
→ compose`. The `:decide` select switches on `verify-prune`'s `:keep-prune?`.

## The query-planner confound — a real design decision
Pruning primary phrases changes the corpus-aware planner's harvested vocabulary. Either:
1. **Accept it** — production's planner uses the pruned collection, so the measured combined
   effect is faithful; the gate + final A/B run the production planner. (Recommended: simpler,
   faithful to "what shipping this actually does.")
2. **Split** — planner harvests the UNpruned collection; only the retrieval matcher sees the
   pruned view. More faithful to "phrases as *retrieval* noise," but a structural change
   (collections map must carry two phrase views). Reach for this only if planner-starvation is
   shown to dominate.

## Validation
- **Enrichment-ON A/B:** off (full combined) vs pruned (eval-gated-pruned combined), full-42,
  **N=5**, judged, enrichment **ON both arms** (set `:enrichment-types`; requires populated
  enrichment collections — see precondition).
- If enrichment is NOT grown first, this degrades to a primary-only A/B (≈ the precursor) — so
  either grow enrichment in P0 or accept primary-only validation and label it as such.
- Handle the planner confound per the design decision (default: production planner, accept).

## Phasing
- **P0 — decisions + precondition:** (a) planner-confound handling; (b) grow enrichment first
  vs prune primary now; (c) prune-half-alone vs full ADD+PRUNE loop. Confirm benchmark
  owner/off-target query labels exist or build them.
- **P1 — gate + candidates:** `enrichment-propose-prune` + the inverted multi-query
  `enrichment-verify-prune` (the hard part). Unit-test the gate on the known
  Events-vs-broker-03 case.
- **P2 — graph + apply:** the prune graph + clone-based `enrichment-apply-prune` (both collections).
- **P3 — validate:** enrichment-on (or primary-only, labeled) N=5 judged A/B.
- **P4 — productionize:** real collections + rollback, only on a positive P3.

## Honest headroom caveat (carry it forward)
Mode-B headroom is ~5 cases in the windowed world; a surgical prune ceilings there. The
eval-gated combined design is the *correct* shape (only the gate distinguishes bridge from
noise), but expected magnitude is small. The larger prize may be the **ADD** half (growing
enrichment bridges — the enrichment arc has a *positive* prior). Decide whether the prune half
alone justifies the build (P1's gate is non-trivial), or whether to build the full loop.

## REFINEMENT (2026-06-03): unified ADD+PRUNE agent + the D2.21 corroboration

Direction (user): build ONE adjusted self-improvement agent that **prunes and expands in a
single pass per doc**, each side independently eval-gated; **initial test on the existing ~190
enrichment rows' chunks** as a gate-*discrimination* confirmation (not a recall A/B) before scaling.

**Major corroboration — `propose_phrases.clj` D2.21 (lines 67–79):** the enrichment ADD pass
already discovered our thesis from the other side. Its first prompt produced BROAD phrases that
"matched the chunk but also matched many other chunks on the same topic"; the verify gate saw
`enriched-rank < baseline-rank` and **reverted every chunk**; the prompt was rewritten to demand
DISCRIMINATIVE phrases. Implications:
- the mechanism (broad phrases dilute/bury) is **independently validated** from the add side;
- **the gate already discriminates broad-vs-specific correctly** — the hardest scope risk is retired;
- the **primary** 46,969 phrases came from the OLD broad-style ingest prompt ("high BM25
  precision") — exactly the style enrichment learned to STOP producing. The primary collection
  is full of the phrases the gate already knows to reject. Pruning them is the natural complement.

**This simplifies the two net-new skills:**
- **`verify-prune` = an inversion of `verify_retrieval.clj`, reusing its exact plumbing**
  (`rag/lookup-*` + `find-hit` + `position-delta`). Add asks "does the chunk's rank improve
  WITH the new phrase?"; prune asks "does the chunk's rank stay as good WITHOUT this phrase?"
  Conservative keep-prune rule: **remove `p` iff chunk `c`'s rank is not worse without `p`**
  (across the run's topic queries) → `p` was redundant/non-load-bearing for `c`. Off-target
  *relief* is a bonus tie-breaker, NOT required for the decision. The global `eval-suite`
  regression guard (run enrichment-ON) backstops broad regressions. (Limitation, same as the
  add gate: single-topic verify can't see a phrase that bridges a query outside the run's topic
  — the global guard is the net.)
- **`propose-prune` = apply the D2.21 discriminative criterion to EXISTING phrases** (read the
  chunk's combined primary+enrichment set; flag the broad ones as prune candidates), IDF-seeded
  via the `phrase_prune.clj` scorer. Not a fresh generation — a judgment over what's already there.

**Unified per-doc pass (one adjusted graph):**
`… analyze(rank over-promiscuous docs) → foreach doc: read-combined → {ADD: propose→apply(enrich
clone)→verify-add→keep/revert} + {PRUNE: propose-prune→apply-prune(primary/enrich clone)→
verify-prune→prune/keep} → compose`. Each side gated independently; both on clones; enrichment-ON.

**Initial ~190-row test (gate-discrimination, the confirmation):** run the unified agent over the
already-enriched chunks + the known cases. PASS iff: prune-gate **rejects** removing broker-03's
specific phrases (removal regresses owner) and **accepts** removing the "Events" doc's broad
phrases (removal is rank-neutral for owner); add-gate keeps a path-creating bridge, rejects a
redundant one. Confirms the machinery + the discrimination before any benchmark A/B.

## BUILD NOTE (2026-06-03): verify-prune is SAFETY-only — broadness can't be a per-op retrieval probe

Built `:builtin/enrichment-verify-prune` (`enrichment/verify_prune.clj`) + added `:matched-phrase`
to `lookup-search-phrases-similar`. While validating on known cases, found that a per-op retrieval
probe **cannot carry the broadness axis**: the hybrid phrase lookup's vector half returns ~15–20
hits for ANY phrase, so an off-target COUNT does not separate broad from specific (measured: broad
16–19 vs specific 11–15, fully overlapping). The token-IDF over the phrase→doc graph DOES separate
them. So the gate is explicitly **three-stage**, each doing only what it can do well:

1. **propose-prune (token-IDF)** → broadness (which phrases are candidates).
2. **verify-prune (retrieval)** → removal SAFETY: `safe-to-prune? = (not stranded?)`, where
   stranded = chunk reaches retrieval for query=p ONLY via p (no sibling phrase, no content path).
   Cleanly separates on the known cases: specifics show `via-other-phrase=true`, prunable broad
   show `via-content=true`, and the legit-broad "Altinn Events" shows STRANDED → kept.
3. **eval-suite (batch, enrichment-ON benchmark)** → net benefit (the faithful arbiter).

Consequence: the "gate discriminates specific-vs-broad" property is a **two-skill** property
(propose-prune ∩ verify-prune), confirmed only once propose-prune exists; verify-prune in isolation
is a safety veto, not a discriminator.

## BENEFIT GATE (2026-06-03): local "worth removing?" — content-rank + LLM compose

Built `:builtin/enrichment-verify-prune-benefit` — the prune-side counterpart of
verify-retrieval, answering BENEFIT (not just safety). The asymmetry: an ADD's benefit
is INWARD (the enriched chunk is its own target), a PRUNE's is OUTWARD (it un-buries
OTHER chunks), so judging it needs ground truth for the affected queries. We derive it
LOCALLY (no benchmark) so self-improvement runs at "batch of one".

**Calibration matters — v1 asked the wrong question.** A "distinctive identifier vs
generic" LLM judge voted REMOVE on **74%** of phrases — it nuked useful SHARED bridges
(`401 Unauthorized`, `application/cloudevents+json`, `Altinn 3 juni 2020`). Lesson:
*distinctive ≠ worth-removing*; a phrase can be non-distinctive yet a valuable bridge.

**v2 (competition-aware) fixed it.** Show the LLM the actual competitors (top
content-matches for query=p) and ask: is the chunk a genuinely strong answer (KEEP, real
bridge) or merely carrying the phrase while no more relevant than the alternatives
(REMOVE, crowding)? Remove rate dropped 74%→**36%**, with sound rationales (keeps
`Altinn 3 juni 2020`, removes `401` where better 401 docs exist).

**Two LOCAL signals that COMPOSE (the win).** Baseline over 129 phrases vs the v2 judge:

| cheap signal | what it measures | agreement w/ LLM | precision | recall |
|---|---|---|---|---|
| token-IDF | lexical rarity | 60% | — | — |
| semantic (near-dup phrase) | embedding duplication | 60% | 45% | 51% |
| **content-rank** | competitive standing | **73%** | **66%** | 53% |

content-rank — *is the chunk among the top content-matches for its own phrase, or do
better answers exist?* — mirrors the judge's actual question and tracks it best; the
broadness/duplication signals don't. It is NOT a clean substitute (73%/66%/53%), but it
**composes**: run content-rank (cheap, one content search) as the first-pass filter
(~29% flagged) → LLM confirms ONLY those (fixes the false positives) → ~3× cheaper than
all-LLM, no benchmark, full quality. That is the working batch-of-one benefit gate.

Skills: `verify_prune_benefit.clj`. Harnesses: `sweep/prune_harness.clj` (~190-row safety
discrimination), `sweep/benefit_baseline.clj` (LLM-vs-cheap agreement).

### Content-rank threshold sweep + the candidate-generation bottleneck (2026-06-03)

content-rank is a PRE-FILTER (the LLM supplies precision), so recall is what matters. Sweep
over 129 phrases vs the v2 judge (remove? = c-rank > t OR absent):

| t | flag-rate (LLM-call cost) | recall | precision |
|---|---|---|---|
| 0 | 37% | **58%** | 54% |
| 1 | 29% | 51% | 61% |
| 2 | 26% | 49% | 67% |
| 3 | 22% | 40% | 62% |

Recall **ceilings at ~58%** (t=0) — even maximally aggressive, content-rank misses ~42% of
the LLM's removes. Use t=0 for max recall when LLM cost allows, t=1 as the balanced default.

**Structural finding (bigger than the threshold):** the `prune-chunk` pipeline pre-filtered
candidates with **propose-prune's IDF (only ~9% flagged)** BEFORE the benefit gate ran, so
the pipeline's real recall ceiling was IDF's ~9%, far below content-rank's 58%. IDF was the
right cheap *broadness* signal but the wrong candidate *net* (too narrow).

**DONE — prune-chunk now generates candidates by content-rank, not IDF.** propose-prune is
used only to ENUMERATE a chunk's combined phrases; content-rank (verify-prune-benefit with
the new `:use-llm? false` cheap mode) flags candidates over ALL of them; the LLM confirms
only the flagged. Lifts the pipeline recall ceiling ~9% → ~58% at the cost of a content
search per phrase (the LLM, the real cost, still runs only on the ~37% content-rank
crowders). Validated: on chunk 5f3606528ac4 the content-rank net catches `Altinn 3
Arbeidsflate` (a real crowder IDF missed) → LLM confirms → PRUNE, while keeping `Dialogporten`/
`Altinn Melding` (LLM: the chunk genuinely answers those). IDF specificity is retained as a
secondary per-phrase signal.

## NET-BENEFIT VERDICT (2026-06-04): the prune half is net-FLAT — no aggregate recall gain

Ran the full prune loop end-to-end as a benchmark A/B. Built a primary clone
(`website_phrases_loop_v1`), ran the eval-gated prune (content-rank gen + LLM confirm,
`sweep/prune_corpus.clj`) over the **433 benchmark-competing chunks** → **532 phrases
pruned** (~1.1% of the corpus). A/B off vs pruned-clone, windowing-on both arms.

**Interim (N=3, recall-only, first 100 chunks): +0.064 — but it was NOISE.** The interim
off arm was an anomalously low draw (0.714); the real off is 0.798. Resuming on that
signal was a mistake (N=3 recall-only is unreliable — the session's recurring lesson).

**Definitive (full-42, N=5, JUDGED):**

| subset | off recall@20 → pruned | off judge ok → pruned |
|---|---|---|
| long (21) | 0.757 → 0.733 | 79 → 79 |
| mid (10) | 0.840 → 0.850 | 40 → 38 |
| clean (11) | 0.836 → 0.836 | 49 → 46 |
| **overall** | 0.798 → **0.788 (−0.010)** | 168 → **163** |

Net-flat-to-slightly-negative (−0.010 recall, −5 judge, both within noise); Mode-B burial
even ticked up (4→8). **The prune half, on its own, does NOT move benchmark recall.**

**Why (the modest-headroom caveat, realized):** the lever is correctly engineered — local
gates discriminate beautifully, surgical (532 phrases vs the blind cap's ~19k), and does
NO harm where the blind cap did real damage (blind −0.084 vs eval-gated −0.010). But with
the Mode-B residual at ~4–8 cases there was never enough aggregate headroom for even a
perfectly-targeted prune to register. The value is *preventing the harm of naive pruning*,
not adding recall. Clone dropped after the test. Net-benefit question definitively closed.

## Relates to
- [[phrase-pruning-subtractive-plan]] — precursor + scope reconciliation.
- [[rerank-truncation-plan]] — Lever A (windowing), the default this stacks on; why the residual
  is Mode-B.
- `project_phrase_promiscuity_noise`, `project_enrichment_lever` (the bridge prior),
  `feedback_cohort_multi_run_selection` (N≥5).
