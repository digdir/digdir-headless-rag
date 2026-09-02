# Slice-1 validation — `:doc-title` strategy alone

First implementation slice of
`plans/proposed/retrieval-configurable-fields-rules-plan.md`:
add the `:doc-title` strategy with config-driven `title-fields`
and `doc-title-chunk-fanout`. Auto-filter rule engine + new rule
types deferred to a follow-up slice.

## What shipped

- New `digdir.rag.retrieval/search-docs-by-title` — two-pass:
  (1) multi-search docs collection by `title-fields`, (2)
  multi-search chunks for K chunks per matched doc.
- Wired into `digdir.skills.builtin.retrieval` as a 4th base
  strategy, opt-in via skill parameters
  `:title-fields` + `:doc-title-chunk-fanout`.
- `:doc-title 0.8` added to merge default weights (between
  `:content 1.0` and `:phrase 0.7`).
- Config definitions for `skills.retrieval.title-fields` and
  `skills.retrieval.doc-title-chunk-fanout`.
- Production path (`api.util/build-rag-skill-params`) reads
  the two new config keys.
- Debug endpoint accepts them as query params (also documented
  via `bb ts-retrieve --title-fields … --doc-title-chunk-fanout …`).
- Unit tests for the strategy and the merge weight.

Out of scope this slice:
- `multi_retrieval` skill (same precedent as enrichment-targets:
  the multi-query skill doesn't get the new strategy in slice 1).
- Auto-filter rules for language / diataxis (next slice).
- Org/year detection migration into the rule engine.

## Method

Same 7 questions as the v3 baseline. Ran `bb ts-retrieve` for
each with explicit `--title-fields '["linktitle"
"frontmatter_title"]' --doc-title-chunk-fanout 3`. Compared the
top-30 against v3 cited chunks.

## Results

| Q | v3 cites | Before<br>(v3 gap) | After<br>(slice 1) | Δ | Notes |
|---:|---:|:---:|:---:|:---:|---|
| 1 | 3 | 2 (#9, #10) | 2 (#9, #11) | 0 | Doc-title strategy did not surface `9a5fc194a710` (About-dialogporten). The long natural-language query out-tokens it on `linktitle,frontmatter_title` BM25. |
| 2 | 2 | 0 | 0 | 0 | Same — `5f614bcedc12` (Creating dialogs) lost to other docs on raw BM25. |
| 3 | 2 | 0 | 0 | 0 | Same — neither `fefc9064b271` nor `e465a3646506` surfaced. |
| 4 | 3 | 1 (#11) | **3 (#3, #21, #30)** | **+2** | `bdd5427ec8d5` at #30 + `688781d672e2` at #21, both via `:doc-title`. Title-fields BM25 on NB query "Tilgangspakker" matched the NB linktitle "Nye tilgangspakker" directly. |
| 5 | 5 | 0 | 0 | 0 | Same — none of the 3 NB roles docs surfaced via title-field BM25 on the verbose NB question. |
| 6 | 3 | 1 | 0 | **−1** | Regress. The one v3 cite that previously made top-30 (chunk of `837753b0cd68`) was demoted by the new `:doc-title` hits competing for the same merge-rank window. |
| 7 | 5 | 1 (#3) | 1 (#3) | 0 | `:doc-title` strategy returned 0 hits for this query — no doc's linktitle/frontmatter_title matches "webhook signatures HMAC-SHA512". |
| **Total** | **23** | **5 (22%)** | **6 (26%)** | **+1 (+4 pts)** | |

## Honest accounting

**The strategy works mechanically** — `:doc-title N` in the
attribution proves the call is firing, the fanout returns K
chunks per matched doc, and the merge correctly tags chunks
with `:doc-title`.

**But the standalone gain is small.** 22% → 26% top-30 hit rate.
Of the 23 cited chunks across 7 questions, the slice-1 change
surfaces 1 additional chunk net (Q4 gains 2; Q6 loses 1).

**The reason is the predicted one.** From the proposed plan
(`retrieval-configurable-fields-rules-plan.md`):

> `:doc-title` alone surfaces title-matching docs from the wrong
> language. Helps but noisy. … `auto-filter` alone narrows the
> search set, but no strategy queries the right field. Together
> they reproduce the optimal v3 probe.

Q4 was the one question where the user's query itself contained
the curated NB linktitle term ("Tilgangspakker"). For the other
4 NB/EN questions where the natural-language query has lots of
words *other than* what the canonical linktitle uses, BM25 over
`linktitle,frontmatter_title` ranks unrelated docs above the
right one. Language narrowing (and to a lesser extent diataxis)
is what makes doc-title precise.

**Q6's regress is informative.** The previous 1/3 hit came via
metadata strategy ranking a chunk into the top-30. Adding
`:doc-title` to the merge pulled in 8 doc-title-strategy hits
that bumped that one cite past rank 30. This is the
strategy-weight tuning issue: with `:doc-title 0.8` (slightly
below content), the new strategy is contributing too many
candidates and crowding out a marginal hit from another
strategy. The fix is *not* to lower the weight (Q4's win shows
the weight is reasonable on cases where doc-title is the right
signal); it's to ship the auto-filter complement so doc-title
only fires on the right shape of query.

## What this proves and what it doesn't

**Proves**:
- The strategy is correctly wired into the skill, merge layer,
  and debug endpoint.
- Skill parameters and runtime config flow correctly when the
  flags are passed (via CLI or query params).
- The two-pass multi-search shape works against real Typesense
  data with the digdir/public-docs schema (`linktitle` and
  `frontmatter_title` indexed, `total_chunks` available).
- The merge weight `:doc-title 0.8` produces sensible relative
  rankings — when doc-title and content agree (Q4 #1: types
  `:content :doc-title`), the chunk wins; when only doc-title
  fires (Q4 #21–30), chunks land later but in-frame.

**Doesn't prove**:
- That the strategy on its own materially improves hit rate
  on natural-language queries.
- That `:doc-title 0.8` is the right weight under the
  auto-filter complement.
- That the strategy doesn't introduce regressions in the
  agent-loop (the read-tool path); the slice 1 measurement is
  retrieval-only via the debug endpoint.

## Next slice

Per the unified plan: auto-filter rules engine with
`:marker-word-classify` (language) and `:shape-pattern`
(diataxis) rule types. When that lands and we re-run the same
7 questions, the prediction is **22% → 60–70%**.

The slice-1 change is a prerequisite — without
`search-docs-by-title` in place, even a perfect auto-filter
can't surface the right title-matching docs because no strategy
queries linktitle/frontmatter_title. Slice 1 is necessary but
not sufficient.

## Followups identified during validation

1. **Reitit malli coercion drops un-declared query params silently.**
   Cost me one server restart cycle. Worth a docstring note on
   the schema location or a CI check that bb-task params line
   up with route schemas. (Maybe a follow-up plan: "validate
   bb CLI flag set against route schemas at boot.")
2. **Debug endpoint does not auto-pick the seeded runtime
   config** for these two new keys. To exercise the seeded
   config without explicit flags, the debug endpoint would need
   to call `cfg/get-runtime-skill-config-v2` — but that
   requires an `:agent-id`, which the debug endpoint doesn't
   have. Either: (a) add an agent-less variant of the accessor,
   or (b) route the debug endpoint through a synthetic
   diagnostics-agent. Slice 1 sidesteps via explicit CLI flags.
3. **Production path (`build-rag-skill-params`) reads the new
   keys correctly** — verified by code-path inspection; not
   exercised end-to-end in slice 1 since the validation goes
   via the debug endpoint. The agent-loop path will pick up
   the seeded `skills.retrieval.title-fields` automatically.
