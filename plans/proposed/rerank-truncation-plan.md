# Rerank truncation: the cross-arc discovery, and the two levers to fix it

**Status:** proposed
**Date:** 2026-06-02

## The discovery (pool-head → prior-decomp → rerank-score instrumentation)

The agent's `search_documents` runs ColBERT rerank (`tools.clj` hard-codes
`:rerank-with-colbert true`) over the top **40** lexical candidates. The reranked
order — not the lexical prior — is what the agent sees. Instrumenting the actual
sort key (`:rerank-score`) on the 10-question variance cohort (N=3) showed:

- **20/21 buried-golden runs are a RERANKER miss** (golden is in the top-40
  window but scored below generic chunks), not a retrieval miss.
- **20/20 of those goldens are longer than `rerank-max-chunk-length` (1000).**
- golden >1000 chars → buried **91%**; ≤1000 → buried **33%**.
- Long answer-goldens score **12–19 even when fully seen**; short generic chunks
  score **20+**. ColBERT systematically under-scores long answer chunks.

### Root cause (now precise)

The text sent to ColBERT is built **client-side** in `rag/rerank.clj:92-99`:
`truncate-head-tail(Title + metadata + content, rerankMaxChunkLength=1000)`.
`truncate-head-tail` keeps the **head + tail and drops the MIDDLE**. The
answer in a long golden lives in the dropped middle. For `studio-02` (22651
chars) ColBERT sees ~497 head + ~497 tail = ~4% of the chunk.

This is why **every additive/boost lever in the research log failed**: the golden
was retrieved and in-window; the loss happens downstream at rerank time where no
query/boost tuning can reach. It also explains the coin-flip variance (whether the
head/tail happen to carry query tokens) and the difficulty structure (the hard
cohort == the long-golden questions).

### Why a flat chunk-length raise is NOT the fix (falsified, N=3 A/B)

`rerank-max-chunk-length` 1000→4000: r@20 0.333→0.400 (inside the cohort's
coin-flip band), golden rerank-score +0.2 net. Per-question: 2 wins, ~3 losses.
Mechanism: (a) ColBERT's own model caps the sequence (~512 tok ≈ 2k chars), so a
bigger budget can't make it read more; (b) `truncate-head-tail` still drops the
middle; (c) sending more text DILUTES the late-interaction signal (`sys-02`
19.2→16.9) and pushes the longest goldens out of the candidate window
(`corr-01` fell from 1/3 to 0/3 in-window). The fix is putting the RIGHT passage
in the budget, not a bigger budget.

## Stratification of the 42-question benchmark (golden length)

Exact golden `content_length` (via `golden_lengths.clj`):

| band | count | % | examples |
|---|---|---|---|
| **> 2000 chars** (strongly truncated) | **21** | 50% | studio-02 22651, corr-04 10384, authz-01 6997, events-01 6345, corr-01 6240, studio-04 5827, sys-02 4600 |
| 1000–2000 (mildly truncated) | 10 | 24% | events-02 1776, authz-06 1853, api-04 1444 |
| ≤ 1000 (clean) | 11 | 26% | broker-03 693, dialog-03 462, sys-01 683 |

**31/42 (74%) have a golden longer than the 1000-char cap.** The ≤1000 set is the
clean control where the reranker works and prior levers were measured faithfully.

## Lever A — Passage/window reranking (NEAR-TERM; recommended first)

**Feasibility: high. Entirely client-side; no ColBERT service change.**

Replace the `truncate-head-tail` call in `rag/rerank.clj:92-99` with a query-aware
window: for each candidate, select the ~`window-size` span of `content` with the
highest query-term overlap (reuse `digdir.skills.builtin.retrieval/tokenize`),
keep the short `Title:`/metadata prefix (query-relevant, cheap). Send that focused
passage to ColBERT instead of head+tail of the whole chunk.

- **Fixes both** failure modes: truncation (answer no longer dropped) AND dilution
  (one focused passage, not the whole long chunk).
- **No corpus change, no re-grounding** — testable against the existing 42-question
  benchmark immediately.
- Cost: a string scan over ≤40 candidates per search — negligible.

**Design notes / risks:**
- Window granularity: paragraph/sentence-boundary windows read better than a raw
  char slide; fall back to char window. Window size ≈ ColBERT's effective max
  (~1500–2000 chars) so the model actually ingests the whole passage.
- Multi-passage answers: start with the single best window; if recall on
  multi-part goldens lags, send top-2 windows concatenated.
- Keep `rerank-max-chunk-length` as the window-size knob (semantics shift from
  "truncate to" → "window of"; document it).
- Guardrail: must not regress the ≤1000 clean set (short chunks = whole chunk is
  the window, so behavior is unchanged there by construction — verify).

**Test plan:**
1. Implement behind a skill-param flag (e.g. `:rerank-windowing`) so it's a
   faithful sweep delta. — DONE (commit 88b50b4).
2. P1: cohort N=5, off vs on — golden rerank-score (does the long-golden score
   rise?), pool-rank, recall@20. Success = long goldens (broker-05, sys-02,
   studio-01, corr-01) score up and surface, with the ≤1000 set unchanged.
3. P2: full-42 judged, off vs on — net recall + judge lift, no clean-set
   regression. Re-judge the long-golden subset (answers were graded on
   rerank-starved context).

### Lever A RESULTS — P1 (cohort 10, N=5, off vs windowing@2000): WIN, mechanism confirmed

`rerank-windowing-p1.edn` / snapshot `sweep-2026-06-02T16-09-00`. We already proved
a flat length raise (1000→4000, no windowing) is a wash, so the win here is
attributable to windowing, not the budget.

- **Mechanism confirmed:** golden rerank-score rises on long goldens — sys-02 +4.3,
  studio-01 +3.9, authz-01 +3.2, broker-05 +2.8; short control broker-03 unchanged.
- **Retrieval stage:** golden-displayed 74→80%, golden-read 44→60%, median
  display-rank 3→2, recall@20 0.42→0.57. **recall UNDERSTATES it** — it is keyed on
  what the agent READ; windowing surfaced studio-02 to display-rank 2 but the read
  stage doesn't pick it up → the bottleneck moved DOWNSTREAM to the read stage.
- **Residuals (both predicted):** `corr-01` (6240) regressed from a suboptimal
  single window → fix = **top-2 windows concatenated**; `studio-02` (22651) surfaced
  to display-rank 2 but unread → read-stage + Lever B (mega-chunk).

First lever all session to move recall via a PROVEN mechanism, not a correlate.

### Lever A RESULTS — P2 (full-42, N=3, JUDGED, top-2 windowing): NET-FLAT REDISTRIBUTION

`rerank-windowing-p2.edn` / snapshot `sweep-2026-06-02T16-57-13`.

| subset | recall@20 off→win | judge correct off→win |
|---|---|---|
| long-golden (21, >2000) | 0.659 → **0.762 (+0.10)** ✅ | 42 → **46** ✅ |
| clean (11, ≤1000) | 0.848 → **0.697 (−0.15)** ⚠️ | 30 → **27** ⚠️ |
| overall (42) | 0.746 → 0.762 (+0.02) | 89 → **92 (+3)** |

Windowing wins on its target subset (proven) but **regresses the clean set** — and
the mechanism is the giveaway: windowing raises the rerank-score of EVERY long
chunk, **including long DISTRACTORS**, so on a query whose answer is a SHORT golden
competing against long distractors, the golden gets buried (`events-03`:
display-rank 2→17, read 3/3→0/3). The rest of the clean regression is N=3 read
variance (broker-03/04: golden at rank 1, just 1 fewer read). Net overall ~flat on
recall, marginally positive on judge.

**This is the decisive finding: the redistribution cost is INHERENT to windowing
variable-length chunks** — you cannot make the reranker see long answer-chunks
better without also helping long distractors. The clean fix is to remove the length
disparity itself → **Lever B (uniform chunking)**. P2 converts Lever B from "maybe"
to "commit". Lever A is NOT shippable as a global default (real clean regression),
but it stays a faithful flag and PROVED the truncation mechanism.

### CORRECTION — the P2 "clean regression" was N=3 VARIANCE (clean-set N=5 check)

`windowing-clean-n5.edn` / snapshot `sweep-2026-06-02T20-37-08`. Re-ran the 11 clean
(≤1000) questions off vs windowing@2000 at **N=5**:

| clean-11 | off | windowing | 
|---|---|---|
| recall@20 | 0.800 | **0.855 (+0.055)** |
| display | 98% | 96% |

**There is NO distractor-lift.** At N=5 windowing slightly IMPROVES the clean set;
`events-03` (the P2 "smoking gun", display-rank 2→17 at N=3) went UP (0.20→0.40);
the only regressor (`broker-03` 1.00→0.80) has display-rank unchanged at 1 — the
known Mode-B lexical coin-flip, not displacement. **The P2 −0.15 was N=3 read
variance.** Putting the reliable numbers together: long +0.10 (P1/P2), clean
~flat-to-+0.05 (N=5) ⇒ **Lever A is NET-POSITIVE, not net-flat.** Length-
normalization is therefore NOT warranted (no lift to offset). The whole whipsaw was
the per-question N=3 variance the session keeps re-discovering — A/B at N≥5.

**FINAL: Lever A (`:rerank-windowing`) is net-positive and shipped as a faithful
flag (off by default).** Lever B (chunking) is OUT — see chunking-strategy-plan
(re-chunking regressed the long subset by fragmenting synthesis context; the
truncation was rerank-SCORING-only, and coarse chunks are GOOD for reading).

### PROMOTION — full-42 N=5 JUDGED confirmation → Lever A is now the DEFAULT

`windowing-promote-n5.edn` / snapshot `sweep-2026-06-02T22-42-20`. The one test
never run before: full-42, N=5, JUDGED, off vs windowing@2000. This is the gate
the P2 N=3 clean dip (30→27 judge) demanded — re-check at N=5.

| subset | recall@20 off→win | judge correct off→win | judge incorrect off→win |
|---|---|---|---|
| long (21) | 0.610 → **0.710** (+0.10) | 65 → **70** | 9 → **7** |
| mid (10) | 0.770 → **0.840** (+0.07) | 33 → 34 | 2 → **0** |
| clean (11) | 0.709 → **0.782** (+0.07) | 42 → **45** | 3 → **0** |
| **overall (42)** | 0.674 → **0.760** (+0.086) | 140 → **149** | 14 → **7** |

**Every subset up on recall AND judge; incorrect answers drop in every band.** The
clean set (the P2 −0.15 scare) is clearly positive at N=5 — confirming, a third
time, that the whole Lever-A whipsaw was N=3 variance. Promotion criterion (net
positive, no subset regresses at N=5) MET.

**Promoted in code** (`skills/builtin/retrieval.clj`): `:rerank-windowing`
defaults TRUE and `:rerank-max-chunk-length` defaults 2000 (the window budget IS
the chunk-length, so both move together to match the validated arm). Production
omits both keys in `:builtin/retrieval` skill-params, so the `:or` defaults fire
on the agent path (verified: dataset default + agent search-tool inject neither).
**To reproduce the historical pre-Lever-A baseline, pass BOTH `:rerank-windowing
false :rerank-max-chunk-length 1000`** — future "off" arms must set both, since a
bare `{}` is now the windowed default.

## Lever B — Chunking strategy (STRUCTURAL; stage AFTER Lever A)

**Current** (`rag/chunking.clj`, params in `pipeline/materialization.clj:9-38`):
header-based markdown split, min **333**, max **256000** (effectively unbounded),
no overlap. A large header-section with no sub-headers becomes one giant chunk →
the 22k/10k/6k goldens. `chunk_id = sha256(content_markdown)`.

**Change:** introduce a real max-chunk-length (~1500–2000 chars) with sub-splitting
of over-long header-sections (by paragraph/sentence, with small overlap ~100–150
chars so a boundary-spanning answer isn't lost); inherit parent header metadata
onto sub-chunks.

**Why it's invasive (and therefore second):**
- Changing chunk params changes the **config hash → new collections** (parallel,
  old preserved). Retrieval must point at the new collection.
- `chunk_id` changes for every re-split chunk → **all 106 golden-chunk-ids in
  `questions.edn` must be re-grounded** to the new boundaries, and every prior
  baseline resets (not comparable across corpora).
- Re-chunking = full re-ingest (re-load sources → re-chunk → re-index →
  enrichment collections rebuilt).

**Re-chunk workflow:** update pipeline config (`:chunks/maximum-length`, new
sub-split strategy) → full re-ingest (new collections auto-created) → re-ground
the 42 goldens → re-establish the faithful judged baseline on the new corpus.

**Open question Lever B answers that Lever A doesn't:** giant chunks also bloat
synthesis context and citation precision, not just rerank. If Lever A recovers
most of the recall, Lever B becomes a quality/cost optimization rather than a
recall necessity — decide its priority AFTER seeing Lever A's P2.

## Sequencing + the prior-arc reassessment

1. **Lever A first** — cheap, reversible, no re-grounding, testable now. Land it,
   measure on the existing benchmark.
2. **Reassess prior arcs (long-golden subset only)** with Lever A held constant:
   re-run salient-noun, corpus-vocab slices 23/25, enrichment, user-intent-union,
   read-stage on the **21 >2000-char questions**, comparing each lever's effect
   with the rerank fix in place vs the old contaminated measurement. The ≤1000
   set is the unchanged control.
3. **Decide on Lever B** based on Lever A's residual: if long goldens still lag
   after windowing (e.g. genuinely multi-passage answers, or 22k mega-chunks),
   commit to the re-chunk + re-ground migration; else defer it to a quality/cost
   pass.
4. **Re-judge** affected questions — answer-quality verdicts were downstream of
   truncated context and some may flip.

Relates to [[query-planner-salient-noun-plan]] (closed negative — now understood
as masked by this), and the corpus-vocab / enrichment arcs (re-run candidates).
