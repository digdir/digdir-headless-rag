# Lever B: uniform chunk sizing via a chunk-level sub-split transform

**Status:** proposed (committed-to after Lever A P2)
**Date:** 2026-06-02

## Why (the P2 finding)

Lever A (rerank passage windowing) is a net-flat REDISTRIBUTION: +0.10 recall on the
21 long-golden questions, −0.15 on the 11 clean (≤1000-char) questions, because
windowing lifts the rerank-score of EVERY long chunk — including long DISTRACTORS —
so short goldens competing against long distractors get buried (`events-03`:
display-rank 2→17). The cost is **inherent to windowing variable-length chunks**.
Removing the length disparity at the source eliminates it: if every chunk is
~uniform and reranker-sized, no chunk needs windowing and no long-chunk has a
rerank advantage. That is Lever B.

Root cause in the corpus: `rag/chunking.clj` header-splits markdown but applies NO
size cap — `:chunks/maximum-length` (256000) is a DROP filter in
`docs/pipeline/protocol.clj`, not a splitter. A header-section with no sub-headers
becomes one chunk of any size → the 22651 / 10384 / 6997-char goldens.

## Approach: chunk-level transform (NOT a re-ingest)

The docs collection stores only metadata (no `content_markdown`); the full text
lives in the CHUNKS. But we do NOT need to re-fetch or re-header-split: the existing
chunks are already header-split, small-merged, and carry their header hierarchy in
`:metadata`. The only defect is size. So:

1. **Read** all chunks from the existing chunks collection.
2. **Split** each chunk whose `content_length` > `max` into ≤`max` sub-chunks
   (paragraph-aware, with overlap), each INHERITING the parent's `:metadata`
   (header hierarchy) and `:doc_num`/`:chunk_index` (re-indexed). New
   `chunk_id = sha256(new content_markdown)`.
3. **Pass through** chunks already ≤`max` UNCHANGED → identical `chunk_id`.
4. **Write** to a NEW chunks collection; register a new `dataset-config-key`
   (e.g. "rechunked") pointing docs+phrases at existing, chunks at the new one.
5. The sweep runs off=existing-config vs on="rechunked"-config.

**Consequences:**
- Only OVERSIZED goldens change id → only the long-golden subset needs re-grounding;
  the 11 clean (≤max) goldens keep their exact ids (no work, and they're the control).
- No source re-fetch, no header re-parse, no document reconstruction.
- Header lines are already in `:metadata` (chunking sets `include-headers-in-content?
  false`); rerank/display prepends `Title + metadata-headers + content`, so
  sub-chunks keep header context. ✓
- Enrichment/phrases collections are NOT rebuilt — fine for the baseline test
  (default agent has enrichment OFF). Note it; rebuild only if enrichment is enabled.
- `total_chunks` on docs goes stale (display/citation-only) — backfill optional.

## The sub-split algorithm (`rag/chunking.clj`, new `split-oversized-chunks`)

Pure function over the post-header-split / post-small-merge chunk list:
- Target `max` ≈ 1500 chars (covers ColBERT's ~512-token / ~2000-char window with
  headroom; tunable). Overlap ≈ 150 chars so a boundary-spanning answer isn't lost.
- For each chunk with `(count content) > max`: segment by paragraph (`\n\n`), then
  greedily pack segments into ≤`max` sub-chunks; if a single segment > `max`, fall
  back to sentence split, then hard char-window. Prepend ~overlap chars of the prior
  sub-chunk to each subsequent one.
- Each sub-chunk inherits parent `:metadata`; optionally tag `{"Part" "k/n"}`.
- Wire as a final pass in `split-into-chunks-by-headers` (after
  `concatenate-too-small-chunks`) gated on a new `:chunks/maximum-length` semantics
  (split, not drop) — keep the >256000 hard-drop as a separate safety net.

## Re-grounding (only the long goldens)

Automated, not manual: for each old oversized golden id, fetch its old content; the
new goldens are the sub-chunk(s) of the same `doc_num` whose content is a window of
the old content AND that contain the question's `expected-answer-pattern` (or the
answer span). Update `questions.edn` `:golden-chunk-ids` for those questions only.
Spot-check the auto-mapping before trusting it.

## Test / measurement plan

1. Implement + unit-test `split-oversized-chunks` (pure; this commit).
2. Migration script: read chunks → transform → write new collection + register
   "rechunked" dataset-config-key.
3. Re-ground the long-golden subset (auto-map + spot-check).
4. **Full-42 judged A/B:** off (existing corpus, windowing OFF) vs rechunked
   (new corpus, windowing OFF). Success =
   - clean set (11 ≤1000) UNCHANGED (same chunks → control), and
   - long-golden set (21) recall/judge UP (now reranker-sized, no truncation),
   - net overall positive, WITHOUT the windowing distractor-lift cost.
5. If positive: also test rechunked + windowing (should ≈ rechunked, since few
   chunks exceed `max`), and re-judge the long subset.

## Open questions
- `max` value: 1500 vs 2000 (ColBERT ~512-token cap) — sweep it.
- Overlap size and whether to tag part-index in metadata.
- Does smaller chunking hurt CITATION precision or any short-golden question by
  fragmenting a previously-coherent answer? (Watch the clean set + citation-recall.)

Relates to [[rerank-truncation-plan]] (Lever A, the near-term patch this supersedes
as the root fix) and `project_rerank_truncation_bottleneck` memory.

## RESULTS — Lever B is OUT (re-chunking regressed the long subset)

Built and ran end-to-end (max 1500, overlap 150): migration (5600→9145 chunks, max
size 1652), LLM re-grounding of the long goldens, `:chunks-collection-override`
runner support, full-42 judged A/B vs the P2 off baseline
(`sweep-2026-06-02T19-15-47`).

| subset | recall@20 off→rechunk | judge correct off→ | disp | read |
|---|---|---|---|---|
| **LONG (21)** | 0.659 → **0.409** | **42 → 27** (incorrect 3→**10**) | 89→**63%** | 68→**48%** |
| CLEAN (11) | 0.848 → 0.818 | 30 → 29 | flat | flat |

Re-chunking **regressed the long subset on recall AND answer quality** (the judge is
independent of the golden re-grounding, so not an artifact), clean set flat.

**Mechanism — the key lesson:** the truncation was *rerank-SCORING-only*. Once the
agent retrieved a coarse chunk it READ THE FULL chunk for synthesis. Re-chunking
broke two things the coarse chunks did right: (1) **synthesis context** — the agent
now reads a 1500-char fragment instead of the full answer region → worse answers;
(2) **retrieval reliability** — the answer fragment competes with its near-identical
siblings → surfaced less (disp 89→63%). **Coarse chunks are GOOD for reading; they
were only BAD for rerank scoring.** So the fix must be scoring-only (Lever A), which
leaves full chunks for reading. Lever B fixed the wrong layer.

Rechunked collection `website_chunks_rechunk1500v1` dropped after the test. This
plan is shelved; Lever A is the shipped fix.
