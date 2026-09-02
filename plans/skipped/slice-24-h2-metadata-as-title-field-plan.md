# Slice 24 — Expose chunk H2-metadata as a query-by-able title field

> **STATUS — investigated, skipped (2026-05-27).** The plan's
> mechanical premise was refuted by discovery: `metadata` is
> already indexed (`website.clj:273`) and already query-by-able
> (`retrieval.clj:333` issues `:query_by "metadata"`). A direct
> BM25 probe (`q=Maskinporten` against `metadata`) returns Q7's
> chunks at positions #10 and #11. The blocker is purely the
> planner's vocabulary — it never emits Maskinporten/subscription
> terms for an HMAC-SHA512 question. See
> `plans/in-progress/target-optimal-baseline-v3/23-h2-metadata-already-indexed.md`
> for the full discovery trail. Slice order adjusted to
> 25 → 23; slice 24 has no implementation work.

First slice of the corpus-vocabulary arc, motivated by the
post-slice-22 investigation that showed Q7's failure isn't about
missing compound terms — it's about an **unindexed signal**.

## The finding

Q7's v3-cited chunks `09eeadadc174` and `9b4017645a43` (doc
`af9c4ce14a2b`, URL `…/events/subscribing/…`) carry chunk-internal
H2 headers in their `:metadata` field:

```
{"Header 2" "Configuring Maskinporten integration"
 "Header 3" "Create subscription"}
```

This is the single most precise vocabulary signal for what these
chunks are about. But:

- **"Maskinporten" does not appear in chunk content** for these
  chunks (verified via `q=Maskinporten&query-by=content` → 0 hits
  on the right chunks).
- **The parent doc title is "Subscribing"** — too generic to
  surface them on a webhook-signature query.
- The `:metadata` field exists on every chunk but is **not currently
  a Typesense `query_by`-able field**. The retrieval skill's
  `title-fields` config only lists `linktitle` + `frontmatter_title`
  on the *docs* collection, not chunk-level headers.

Doc-title search for "Maskinporten" returns 12 hits across the
corpus — clearly indexable vocabulary. The current pipeline simply
can't reach Q7's chunks via any vocabulary path.

## Why this matters

If H2 headers can be made searchable, the same single config change
likely helps multiple questions: any chunk whose narrow topic is
better captured by its section header than by its parent doc title.
Q7 is the most acute example in v3, but Q6 (Altinn Studio v7→v8
data model) and parts of Q3 (Norwegian Altinn Studio
configuration) probably benefit too.

Doc-level baseline expectation:

- Q7 currently top-30 docs: 1/2 (only the `setup-subscription` doc
  is reached, not the `subscribing` doc with the Maskinporten H2).
- Goal: 2/2 with H2 indexed.

## Two implementation paths

### Path A — Promote H2 into its own indexed string field at ingest

Cleaner long-term. At chunk-ingest time, parse `metadata`'s JSON,
extract `Header 2` (and optionally `Header 3`), concatenate into a
new `headers` string field, mark it `index: true` in the schema.

Pros:
- Native BM25 over a clean field; no JSON-string indexing
  weirdness.
- Field is human-inspectable in Typesense responses.

Cons:
- Requires schema migration → re-ingest of the whole
  chunks-collection. Cheap for a single dataset but not zero.
- Touches ingestion code paths.

### Path B — Make existing `metadata` field query-by-able

If `metadata` is already a `string` field (it appears so — the
returned value is a JSON-string), we may be able to mark it
`index: true` without re-ingest, or copy it into a Typesense
auto-embedding field.

Pros:
- No re-ingest if the field is already stored.
- Minimal code change.

Cons:
- BM25 over JSON-encoded strings introduces token noise
  (`"Header"`, `"2"`, etc., as separate tokens).
- May rank poorly compared to a clean header field.

**Recommendation**: start with Path B for a fast probe (does the
signal exist at all?). If positive, do Path A for production.

## Discovery tasks (before implementation)

1. Inspect the Typesense schema for the chunks collection.
   - Is `metadata` already indexed? `(typesense API
     /collections/website_chunks_ab897fbdedfa)`
   - What's its type? `string`, `string[]`, `object`?
2. Sample 100 chunks: how many have non-empty `metadata`?
   How many specifically have a `Header 2`? Distribution informs
   how much retrieval-side gain to expect.
3. Probe BM25 directly: if `metadata` *is* query-by-able, run a
   one-off `ts-search` with `q=Maskinporten&query-by=metadata` and
   see whether `09eeadadc174` ranks high.

If step 3 already works without code changes, we may just need to
add `metadata` to the retrieval skill's `:chunk-title-fields`
config — a one-line change.

## Implementation steps

1. **Discovery** (above). Decide Path A or Path B.
2. **Path B (likely)**: Extend the retrieval skill so that the
   chunk-collection search query includes `metadata` in the
   `query_by` list when configured. Default off; opt in via a
   runtime config knob `skills.retrieval.chunk-header-fields`
   = `["metadata"]`.
3. **Path A (if Path B noisy)**: Add `headers` field to chunks
   schema; populate during ingest from the JSON `metadata` field;
   re-ingest dataset.
4. Update `bb v3-score` to pass through the new field config so
   we can measure with and without.
5. Run measurement matrix:
   - Slice 22 (current best): cap-N3 union, no header indexing.
   - Slice 24-A or 24-B: same + headers indexed.
   - 3 runs each, compare top-10 / top-30 chunks and docs.

## Expected impact

Conservative: Q7 reaches its second cited doc → +1 doc, +2 chunks
at top-30. Top-30 docs: 66.7% → 75%. Top-30 chunks: 50.7% → ~58%.

Optimistic: H2 vocabulary helps Q6's missing sibling chunks (Q6
doc IS found, but specific chunks rank low) and tightens precision
on Q5. Could add 2-3 more chunk hits → top-30 chunks ~63%.

This is a stand-alone slice — completely independent of slice 23
(production union) and slice 25 (morphology).

## Followups identified up front

- **If Path A is taken**: revisit the chunk schema to consider
  whether other ingest-time signals (link anchor text, code
  symbol names, image alt-text) could be similarly promoted.
- **The metadata field also carries `Header 3` and beyond**.
  Worth measuring whether including all-headers helps more than
  just H2.
