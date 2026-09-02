# Can a sha256 lookup become possible? — costed decision

Closes the open item on #228: *"a decision exists on whether a sha256 lookup
becomes possible, or this recurs by design."*

**Recommendation: yes, and it is cheaper than anyone assumed — a flat
`file_sha256` field, written by ingest now and declared by one schema PATCH
later. Measured at 0.91s over 11,306 documents, with no reindex, no re-upsert,
and full reversibility.**

> We have been getting the storage for free and mistaking it for an index. The
> digest is *already in the collection*. The fix is to declare the index, not to
> build a parallel store.

---

## What was measured, and where

**Production, read-only.** Container `typesense` on the deploy box, box-side
8108, collection `KUDOS_preprod_v4_documents_ab897fbdedfa`, `num_documents`
**11,306** — asserted before every read. **Not touched:** `typesense-test`
(8208), `typesense-dev` (8308). Prod runs Typesense **30.1**.

**Schema experiments ran on the LOCAL dev container**, never on prod — a scratch
collection created, measured, and deleted. That instance runs **30.2**, one patch
release ahead of prod; schema-alteration semantics are not expected to differ
across a patch release, but the version gap is stated rather than assumed away.

| fact | value |
|---|---|
| `enable_nested_fields` on the live collection | **false** |
| indexed fields | `concerned_years doc_num orgs_long orgs_short title type url` |
| `files` shape | array of objects, each carrying `sha256` |

---

## Two experiments that decide the costing

### Test 1 — `enable_nested_fields` cannot be changed on an existing collection

```
PATCH /collections/<c>  {"enable_nested_fields": true}
→ "Only `fields`, `metadata` and `synonym_sets` can be updated at the moment."
```

So `filter_by=files.sha256:=…` cannot be made to work by altering the collection.
It requires **dropping and recreating** it.

### Test 2 — declaring a flat field retroactively indexes values already stored

A field was written into documents as an *undeclared* value (stored, unindexed —
exactly how `files`, `concerned_year` and `publish_date` already ride along).
Declaring it afterwards made those pre-existing values **immediately
filterable**, with no re-upsert.

| measurement | result |
|---|---|
| import 11,306 docs carrying a stored-only `file_sha256` | 0.3 s |
| **PATCH declaring the field over those 11,306 stored values** | **0.91 s** |
| lookup on a value stored *before* the field existed | found, correctly |
| drop the field again | succeeds; **stored values survive the drop** |

**The operation is additive, ~1 second, and reversible in both directions.**

---

## The four options, costed

### 1a — index `files.sha256` directly · ❌ not viable

Needs nested fields, which Test 1 shows cannot be enabled on an existing
collection. **Requires dropping and recreating the documents collection**, i.e. a
full re-ingest of 11,306 documents and a search gap on that collection.

**What it cannot do:** nothing that 1b cannot, at vastly higher cost. Its only
advantage is querying the digest at its original nested path, which is cosmetic.

### 1b — flat `file_sha256` field · ✅ recommended

Ingest derives a flat, de-duplicated vector of digests from `files` and writes it
alongside. One PATCH declares it whenever we choose.

**Cost:** the code change (in this PR, no production effect) plus a **0.91 s**
schema PATCH. No reindex. No re-upsert. No bulk write. Reversible.

**Sequencing — the same shape as #238's fix:** the code lands now, the data
catches up on the **natural ingest cycle**, and the PATCH is cheap whenever it is
sanctioned. At no point is a bulk production write required.

> ⚠️ **That prerequisite is not currently satisfied, and the reason is not this
> issue.** The natural ingest cycle is **unsafe today**: a sample of 100
> `files[].url` entries in prod across both Kudos hosts returned **no PDFs at
> all** — 84 served an HTTP 200 HTML landing page and 15 returned 404. Ingest
> hands those bytes to a PDF parser, which produces zero chunks and no error, so
> a run today would silently re-chunk documents from landing pages.
>
> So: **`file_sha256` populates on the natural ingest cycle only once that cycle
> is safe again.** Nothing here depends on fixing it and nothing here blocks on
> it — the code half has no production effect, and the PATCH is independent of
> ingest — but a reader should not assume the cycle is running. The backfill in
> #251 is gated on the same thing.

**What it cannot do:** it cannot answer for documents that have not been
re-ingested since the change. Coverage grows with ordinary ingest rather than
arriving at once — which is a schedule, not a defect, and it is visible because
the field is simply absent until then. Given the paragraph above, that schedule
currently has no start date.

### 2 — a side index maintained by ingest · ❌ underrated only until it is priced

Ingest reaches Typesense and a `duratom`; it does not touch Datahike. So a side
index means a **second copy of a digest the documents collection already
stores**, kept consistent by hand.

**Cost:** a new store, a new consistency burden, and a new drift mode — the side
index and the collection can disagree, and nothing would detect it.

**What it cannot do:** it cannot participate in a Typesense query. "Has this
digest been registered, and under what org?" becomes a join across two stores
rather than one `filter_by`. Its only genuine advantage — avoiding a production
schema write — is worth 0.91 s.

### 3 — catch it upstream at KUDOS · ⚠️ right in principle, unavailable in practice

Registering the same file twice is arguably KUDOS's defect rather than ours.

**Cost:** coordination with a system we do not control, on an unknown timeline.

**What it cannot do:** anything about the **216 already-registered** documents,
and nothing at all until it ships. Worth raising upstream *as well as*, never
*instead of*.

### 4 — accept recurrence · ❌ no longer defensible

This was a reasonable position while the fix was believed to cost a reindex. At
**0.91 s and reversible**, accepting a correction that undoes itself is not a
trade any more.

---

## How this honours the three settled constraints

**A shared digest is not always a duplicate.** `file_sha256` is a **per-document
list**, so a document with an annexed file carries both digests. The 6
shared-attachment groups are therefore a *representable state*, not an error. The
field is a **lookup primitive, not a dedup policy** — it answers "who else has
this digest", and legitimacy is decided by whatever policy reads that answer.

**Orgs is mostly a merge, not a contradiction.** Nothing here forces a winner. A
lookup tells you two records share a file; for the 27 subset cases where there
may be no fact of the matter, it stays silent on which is right.

**`title+year` is unsafe as a key** — it would delete 1,362 distinct documents.
This uses **sha256**, the safe key, which is #101's headline and the reason the
lookup matters at all.

---

## What needs sanction, and what does not

**Does not:** the code in this PR. It changes what future ingests *write*; it has
no effect on any existing collection.

**Does:** the schema PATCH declaring `file_sha256`. It is a production schema
change and the PI's standing instruction is measurement yes, production changes
no. **Sanction request, with the cost attached:**

- **0.91 s**, measured at 11,306-document scale on a matching Typesense major.
- **Additive** — no existing field, document, or collection is modified.
- **Reversible** — the field can be dropped, and dropping it leaves the stored
  values intact.
- **Does not touch** the chunks or phrases collections.
- Can be deferred indefinitely: until it runs, the field is stored and inert.

---

## Caveat carried forward

#228's example lines printed only the first two rows per group, so where a group
has 3+ rows the printed pair may not be the pair that drove the classification.
**Counts come from full sets; examples are illustrative.** No count in this
document was derived from an example line.

---

## Related

**#228** — the disjoint-year groups and the annex category. **#101** — duplicate
registrations, where sha256 is the safe key. **#238** — the same
"corrections revert unless the producer changes" property, one layer down.
