# #239 — `files[].url` does not resolve to the file: findings

**Status:** investigation complete for the questions asked; Part 2 (the hop) not yet built.
**All measurements: PROD only** — `typesense` container, box-side port 8108, via read-only
on-box `curl` over ssh with the key resolved inside the ssh command. **`typesense-test` (8208)
and `typesense-dev` (8308) were not touched.** Gate asserted before every run:
`num_documents == 11306`.

## 1. What `files[].url` is supposed to be: THE FILE

Two independent sources, neither of them our assumption:

- **Ingest downloads it and parses it as a PDF.** `loader.clj:487-491` maps
  `(=> :url mk-require-url-file)` over `(:files doc)`; `mk-require-url-file` (`:192`) derives an
  extension from the URL, streams `(jio/input-stream url)` to a temp file; `chunk-doc` (`:277`)
  hands that file to `pdf->md` → Marker. **There is no cache path that skips the download.**
- **The index's own metadata says so.** All 11,553 file entries declare
  `mimetype: "application/pdf"`, every URL ends `.pdf`, and each carries `size`, `pages`,
  `sha256`. Ingest computes none of these (no such computation in `loader.clj`/`kudos.clj`) —
  they arrive verbatim from the Kudos API. **Kudos itself asserts a PDF at that URL.**

**The asserted file exists.** doc `151`: landing page's `citation_pdf_url` returned
**1,367,181 bytes** (= indexed `size`), magic `%PDF`, **sha256 identical to the indexed value**.
So the metadata is trustworthy and the hop is verifiable per-document, not a spot check.

## 2. Rate: universal, not a subset

Population (via `json.loads`, not regex): **11,306 docs, 11,302 with a non-empty `files` array,
11,553 url entries**; hosts `kudos-preprod.dfo.no` 10,300 / `kudos.dfo.no` 1,253.

| sample | 200 (HTML) | 404 | conn fail | **PDF** |
|---|---|---|---|---|
| systematic, 50/host = 100 | 84 | 15 | 1 | **0** |
| random seed 20260824, n=200 | 140 | 60 | 0 | **0** |

**Zero PDFs in 300 URLs.** Method validated first against the two documents the issue already
characterised (`1796`, `6591`) — both reproduced `404 text/html`.

## 3. Cause: Kudos migrated numeric ids → UUIDs

- Live API rejects numeric ids: `{"error":{"code":"invalid_parameter","message":"Invalid UUID format."}}`
- Current API records have **no numeric id at all** — only `uuid`; files gained `uuid`,
  `is_primary`, `source_file_url`.
- Current file URLs: `https://kudos.dfo.no/dokument/<doc-uuid>/filer/<file-uuid>.pdf`
- Our index holds only the retired numeric scheme, so **we cannot map doc → uuid via the API**.
  The landing-page `citation_pdf_url` hop is the only available bridge.
- Corpus size differs too: API reports **44,447** documents; we hold 11,306.

## 4. Dating the breakage: bounded from our own corpus

- All three prod collections created **2025-12-04** (`chunks` 713,923 / `documents` 11,306 /
  `phrases` 6,564,478). **Only one ingest generation exists.**
- Typesense collections are created solely by the ingest path (`create-docs-coll` etc. in
  `loader.clj`); `import_export/dump.clj` covers the config DB, not Typesense.
- Documents whose URL **404s today still have chunks** (docs 95/1796/6591/90861 → 1/2/2/3).

**⇒ URLs served real PDFs on 2025-12-04; the breakage is later.** ⚠️ This conclusion depends on
a zero-chunk re-ingest being *visible* — see §6, open question.

Narrowing the upper bound is currently blocked: the API exposes no `created_at`/`updated_at`, and
the Internet Archive holds **zero snapshots** of our URL pattern (verified with a working control),
then went "Temporarily Offline" mid-query. Retryable when it returns; otherwise needs Kudos.

## 5. No automatic ingest exists — the corpus is only ingested by hand

| surface | result |
|---|---|
| root crontab | only `ci-disk-guard.sh`, unrelated |
| `/etc/cron.*` | zero matches for digdir/ingest/materialize/kudos |
| systemd timers | OS-level only |
| containers | only `my-guide/lovdata-archiver` — a different project |
| GitHub Actions | `ci.yml` is the only workflow; `pull_request` + `push`, **no `schedule:`** |
| Kamal `deploy.yml` | no cron, no job section, `boot:` commented out |
| `jarohen/chime` | in `deps.edn`, **used nowhere in code** |
| pipeline execution | `execute-pipeline-async!` called only from the admin UI |

Caveats: this rules out infrastructure schedulers, **not** a human clicking execute (#251's step 1
is a re-ingest), and **not** an operator running the bb task on a personal cadence.

## 6. OPEN: is the corpus evidence, or a fossil?

The §4 dating assumes a re-ingest that yields zero chunks would *visibly* zero the state.
If `delete-orphans!` does not fire for an empty chunk list, or `total_chunks` is not overwritten,
a re-ingest could have happened and left stale chunks in place — making 11,302 non-zero counts a
fossil rather than a live fact. **Being established from the producer.** Note `total_chunks` is
in `prepare-doc`'s select-keys but is **absent from the documents collection schema**.

## 7. Residue (the ~15-30% that 404): it clusters, hard

From the n=200 random sample: **60 404s (30.0%)**.

- **By type — this is the cluster:** 404s are **55/60 `Tildelingsbrev`**; the 200-group has only
  23/140 Tildelingsbrev. Sampled Tildelingsbrev are roughly **70% 404**.
- **By host:** 404s 55 preprod / 5 prod; 200s 122 preprod / 18 prod — roughly proportional, so
  host is *not* the discriminator.
- **By year:** spread across 2020-2025, no concentration.
- **By org:** Justis- og beredskapsdepartementet 16/60, then Kommunal- og distrikts 8, Samferdsels 8.
- **By doc_num:** 404 median 90,767 (range 501-426,636) vs 200 median 71,018 (151-447,423).
- For 404s the **document itself** is gone at the numeric scheme (`/documents/<n>` → 404), and the
  landing page carries **no `citation_pdf_url`** — so a hop-based fix cannot reach them.

Docs `1796` and `6591` share a title — duplicates in our index, both 404.

---

# CORRECTIONS AND ADDITIONS (supersede §4 and §6)

## §4's dating bound is WITHDRAWN — the corpus is a fossil for chunk counts

The argument was: 404-today documents still have chunks, so the URLs worked at ingest.
**It does not hold**, because a zero-chunk re-ingest leaves no trace:

- `delete-orphans!` (`loader.clj:419`) is guarded on `(seq current-ids)`. Zero chunks means
  `current-chunk-ids` is empty, so **the delete never fires**. Prior chunks survive, orphaned.
  The same guard protects the phrases collection.
- `(ts/upsert-documents! chunks-coll [])` is a **no-op**.

So 11,302 non-zero chunk counts are consistent with *both* "nothing re-ingested since the
breakage" and "everything re-ingested, produced nothing, and silently kept the old chunks."

**Reframed hazard:** a re-ingest against today's HTML would not visibly zero anything. It would
leave a corpus that *looks* intact while every touched document's chunks are orphaned from
metadata that no longer describes them. The failure is silent at the write layer too, not just
at the fetch.

#223's four zero-chunk documents are NOT evidence either way: they are A3 landscape tabular PDFs
yielding one whole-document chunk (Bearing, falsifying both the scanned-PDF and the fed-HTML
hypotheses). This sentence used to continue "...that then fails the length bounds". That clause
is **withdrawn** — see the correction below.

## #223's length-bound clause is WITHDRAWN — one chunk, not zero

The mechanism is right in its first half and wrong in its second, and the difference between one
chunk and zero chunks is the whole of #223.

Measured by *executing* `chunk-document` against the live configuration — `minimum-length` 333,
`maximum-length` 256000, identical in the code defaults and in the committed snapshot, for both
`digdir/public-docs` and `public-sector-knowledge/kudos`:

| headerless document (the A3 shape) | chunks kept |
|---|---|
| 86,000 chars | **1** |
| 128,000 chars | **1** |
| 255,999 and 256,000 chars | **1** |
| 256,001 chars | 0 |
| under 333 chars | 0 |

The bounds **drop** a chunk and never split it, so "lives or dies entire" is right about the
mechanism. But the documents in the reported 86k–128k range are two to three times *under* the
maximum and survive. The bound is exclusive: 256,000 is kept, 256,001 is dropped.

**What the evidence does support**, stated positively because it is what makes the next reader
ask the right question: *these documents produce exactly one chunk when the file is present and
its text is extractable.* The A3-landscape-against-header-based-chunking mechanism is real and
confirmed. What is not established is that anything then discards that chunk.

### Why this belongs in THIS document

Reaching zero chunks does not require a length bound at all. On the kudos loader path there are
five distinct routes to zero, each confirmed by execution:

1. **`(:files doc)` is empty** — `chunk-doc` mapcats over it, so no file means no chunks, with no
   PDF conversion and no bound involved.
2. `pdf->md` yields nothing.
3. markdown under `minimum-length`.
4. markdown over `maximum-length`.
5. every chunk filtered by `filter-headers` (`Table of Contents` / `This Page` / `Navigation`).

Route 1 is this issue's own subject. Bearing established that the documents are born-digital by
opening the PDFs **directly**, which settles that completely and says nothing about whether the
*pipeline* could fetch them at ingest — two questions, one piece of evidence. Combined with the
fossil finding above (a zero-chunk re-ingest never deletes prior chunks, so a document at zero
today has produced zero on *every* ingest it has ever had), a persistent fetch failure fits the
evidence better than a bound nobody has changed — which would have to have always excluded these
documents, a strictly stronger claim than anyone made.

**Open, in this order:** (a) does the loader resolve a fetchable file for those four at all;
(b) only if it does, the `content_markdown` length after `pdf->md`. If (a) is the cause, (b)
measures a stage that never ran, and neither a bound change nor a splitter would recover them.

## The chronology correction — right number, wrong reason

I first explained the absence of `total_chunks` as "not in the schema, so Typesense drops it".
**That reason is wrong.** Typesense stores undeclared fields and simply does not index them
(lane B, controlled test: wrote undeclared, declared it after, immediately filterable with no
re-upsert, then dropped the field again and the value survived).

The real cause is chronological:

| event | when |
|---|---|
| `:total_chunks` + `:content_length` entered `prepare-doc` | `f1b4a80`, **2026-02-13** |
| prod KUDOS collections created | **2025-12-04** |

**The corpus predates the writer.** Schema reads answer "what is indexed", not "what is stored" —
the wrong instrument, for the same reason a grep answers appearance rather than reach.

## Census (read-only, prod, gate asserted)

**0 of 11,306 documents carry `total_chunks`; 0 carry `content_length`.**

## The recovered dating instrument — conditional on one control

Any re-ingest after 2026-02-13 calls today's `prepare-doc`, which emits `total_chunks` on every
document including the zero-chunk case (the document upsert always fires; only `delete-orphans!`
is guarded). So a universal absence implies **no re-ingest since 2026-02-13** — a bound that
survives the fossil objection because it depends on a field the writer adds rather than on chunk
state.

**It rests on one premise: that the field would have been stored if written.** The control is the
`website_*` collections: if they were created after 2026-02-13 and carry `total_chunks`, the bound
holds; if they predate it too, the control is void and the bound is unsupported. Print
`created_at` alongside any such census — the date is the load-bearing half. (Bearing running it.)

## Dating: current answer

- **Lower bound (Kudos side):** unknown. The archive holds zero snapshots of our URL pattern
  (verified with a working control); the API exposes no `created_at`/`updated_at`.
- **Upper bound (our side):** no ingest since **2026-02-13**, conditional on the control above.
- **Not asked of Kudos** — outward-facing, routes through the PI.

## Incidental: the missing sha256 lookup has a measured cost

`filter_by=files.sha256:=...` fails with "Could not find a filter field named `files.sha256` in
the schema". Finding the document for a known digest therefore requires a **full export plus a
client-side match**. Worked example: sha256 `cc77fe53...` maps to `doc_num 4885`, 641,165 bytes.
(For #228's record.)

---

# ADDENDUM 2026-08-25/26 — rate correction and failure granularity

## The unreachable rate is ~26%, not ~15% — the earlier figure was biased

| sample | method | 404 rate |
|---|---|---|
| n=100 | **systematic**, every 206th / 25th through export order | 15% |
| n=200 | random, seed 20260824 | 30% |
| n=300 | random, seed 2025 | 23.7% (95% CI 18.9-28.5%) |

The two random samples agree; the systematic one falls outside both intervals. **Export order
correlates with reachability**, so a fixed stride through it is not a random sample.
**The 15% figure is withdrawn.**

**Best estimate, pooling the random samples (n=500): 26.2% unreachable, 95% CI 22.3-30.1%** —
roughly **3,000 of 11,553 file entries**. Everything else about the residue stands: those URLs
404, their landing pages lack the `citation_pdf_url` string entirely (validated, see §
"Validation of the extractor"), and no hop can reach them.

Worth keeping: systematic sampling *looks* more rigorous than random and silently inherits
whatever ordering the source imposes. Nothing in the result announced the bias — two samples had
to disagree before it was visible.

## Failure granularity: isolated per document, but the run still aborts

Established from the producer.

- **Per-document isolation exists.** `mk-prepare-documents-f` wraps each document in `try/catch`,
  collecting failures in `!failed-documents`. A single unreachable file does **not** abort directly.
- **`:fault-tolerance/max-document-failures` defaults to 10** (`materialization.clj:22,38`). On the
  10th failure `terminal?` fires, `!terminal-failure?` is set, and `(throw e)` aborts everything:
  *"FATAL: 10 documents failed to import. Shutting down"*.
- **Each failure first passes through `backoff` of 1 minute + up to 59 random** (`loader.clj:659`)
  — a ladder built for transient Marker/network faults, now applied to a **deterministic 404**
  where a retry cannot succeed.

**At the measured 26% rate the 10th failing document arrives after roughly 38 documents — about
0.3% of the 11,302.** So a re-ingest today runs for up to an hour, completes a third of one
percent of the corpus, and aborts.

## What the fix is, and is not

**Not a quieter throw.** The digest check is what makes a wrong fetch impossible to miss and
should stay exactly as it is. The problem is that **an unreachable file is a known state of the
world, not a fault**:

1. Classify "404 + landing page with no `citation_pdf_url`" as a **recognised skip**, recorded
   against the document, rather than an exception. `fetch-file!` already puts `:tag-declared?
   false` in `ex-data`, so a caller can distinguish it from a parse failure today — nothing
   consumes that yet.
2. Keep the failure budget of 10 for **genuine transient faults**, which is what it is sized for.
3. Do not apply the 1-60 minute retry ladder to a deterministic 404.

Without this, #251's backfill cannot run — it aborts in the first minutes having touched almost
nothing. With it, a re-ingest completes and leaves ~3,000 documents **explicitly recorded as
unreachable**, which is a better artifact than either silent HTML chunks or a FATAL after 38
documents.

**This wants its own issue**: "what a skipped document should look like in the index" is a
decision, not an implementation detail. Not in #270 or #304.
