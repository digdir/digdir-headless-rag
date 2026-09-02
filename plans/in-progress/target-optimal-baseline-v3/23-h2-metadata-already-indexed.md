# Slice 24 — H2 metadata is already indexed (no-op)

Discovery refutes the slice 24 plan's mechanical premise. Recorded
as a no-op so the next time we touch the corpus-vocab arc we
don't repeat the investigation.

## What the plan assumed

`plans/proposed/slice-24-h2-metadata-as-title-field-plan.md` framed
Q7's failure as an *unindexed signal*: the `metadata` field
(containing chunk H2/H3 headers) wasn't `query_by`-able. Two
implementation paths were proposed:

- **Path A** — re-ingest with a clean `headers` field.
- **Path B** — make the existing `metadata` field query-by-able
  via a retrieval-skill config change.

## What discovery actually found

| Step | Source | Finding |
|---|---|---|
| Schema check | `server/src/digdir/docs/website.clj:273` | `metadata` declared `:index true :type "string"` — **already indexed**. |
| Retrieval check | `server/src/digdir/rag/retrieval.clj:333` | `:metadata` strategy issues `:query_by "metadata"` — **already query-by-able and already used**. |
| BM25 probe | `bb ts-search ... q=Maskinporten --query-by metadata` | Returns 34 chunks. Q7's `09eeadadc174` at #11, `9b4017645a43` at #10. **Index works.** |
| Live planner output | `/api/debug/query-planner` for Q7 | Emits "HMAC-SHA512 webhook signature", "Altinn webhook signatures", etc. **Zero phrases contain Maskinporten / Configuring / subscription / event vocabulary.** |
| Live retrieval with planner phrases | `/api/debug/typesense-retrieve` | Top-30: neither Q7 cited chunk present. **0/2.** |
| Live retrieval with corpus-vocab phrase | Same endpoint, query=`"Maskinporten subscription event"` | Top-3: `9b4017645a43` at #0, `09eeadadc174` at #2. **2/2 at top-3.** |

## What this means

The retrieval infrastructure for Q7 is fully working. The
**only** blocker is that the LLM planner doesn't know the corpus
vocabulary maps "webhook signature" → "Maskinporten subscription".
This is exactly the corpus-vocab gap the user tabled into a
future agentic-loop arc.

Slice 24 as written has no schema/config work to do, and is
therefore skipped. The corpus-vocab gap is captured in
`plans/proposed/corpus-vocabulary-aware-planning.md`.

## What stays open

If the agentic-loop arc remains tabled but the gap turns out to
need closing, two lighter mechanical interventions are worth
revisiting:

1. **Per-strategy weight tuning**. The `:metadata` strategy
   currently shares the merge with `:phrase`, `:content`,
   `:doc-title`, `:enrichment-*`. When the planner *does* emit a
   phrase that happens to align with an H2, is the merge giving
   that strategy enough weight to surface its hit? Quick probe:
   bump `:metadata` to 1.5× and re-measure.
2. **`metadata` JSON token noise**. The field stores
   `{"Header 2" "..."}` as a literal JSON string. BM25 tokenizes
   that with `"Header"` and `"2"` as terms; these are
   high-frequency noise. Path A's clean `headers` field would
   remove that noise even though the index already exists. Only
   worth doing if step 1 shows a real gain.

Both followups are deferred — neither was the slice's premise,
and the agentic-loop arc is the more direct fix.

## Net effect on the slice order

Original: slice 24 → slice 25 → slice 23.
Adjusted: **slice 25 → slice 23**.

No measurement change. No code change. Moving directly to
slice 25's Typesense-stemmer discovery.
