# Duplicate registrations: what happens when two records share a digest

Policy for #101. **Recommendation: never collapse registrations automatically.
Fix the user-visible harm at read time, flag at ingest, and expose the query.**

> The digest field is a **lookup primitive, not a dedup policy**. This document
> is the policy that reads its answer.

---

## The measurement that decides it

#101 left one question open, and called it inspectable: *for the 222 shared
files, do the duplicate registrations differ in metadata a user relies on?*
**They do, in 44% of cases.**

**Provenance.** Production, read-only: container `typesense` on the deploy box,
box-side 8108, `KUDOS_preprod_v4_documents_ab897fbdedfa`, `num_documents`
**11,306** asserted before reading. Not touched: `typesense-test` (8208),
`typesense-dev` (8308).

**The extraction was validated against published numbers before being extended** —
222 digests / 452 rows / fan-out 6 from #101, and the 216 / 6 split from #228,
all reproduced exactly. Only then were new figures computed from it.

### How the 216 duplicate-registration groups differ

Annex groups excluded, per #228.

| field | identical | differs |
|---|---|---|
| `title` | 160 (74%) | **55 disjoint (25%)**, 1 overlapping |
| `concerned_years` | 197 (91%) | 15 disjoint (6%), 4 subset |
| `orgs_long` | 176 (81%) | 25 subset (11%), 10 overlapping, 5 disjoint |
| `type` | 205 (94%) | 11 disjoint (5%) |

> **122 of 216 groups (56%) are identical on all four — collapsing loses
> nothing.**
> **94 of 216 (44%) differ on at least one — collapsing loses something.**

**The sharpest number is the 55 disjoint titles.** A quarter of these files are
registered under *entirely different names*. Collapsing means choosing one title
and discarding another, with nothing in the data to choose on.

---

## The policy

### 1 · Never collapse two registrations automatically

44% differ in a field a user reads. There is no rule over the *pair* that
recovers the right answer for those — the same conclusion #228 reached for years,
now measured across all four fields.

This also settles the orgs question the right way round: for the **25 subset
cases**, one record is merely less complete, and there may be **no fact of the
matter** about which is correct. A policy that forces a winner is wrong for them,
and a policy that merges silently invents a record that was never registered.

### 2 · Fix the actual harm at read time, not by deletion

#101 identifies the surviving user-visible harm precisely: *"seeing the same file
twice is a wrong answer; seeing two versions is arguably right."*

That harm lives in the **result set**, not in the index. So suppress repeat
**files** within a single response — keep the highest-ranked registration of a
digest and drop the rest from that response.

This is strictly better than deletion on every axis that matters here:

- **Nothing is destroyed.** All 452 rows stay; only one response changes.
- **No winner is forced.** Keeping the highest-ranked result is a *ranking*
  decision, not a claim that the other registration is wrong.
- **It needs no ingest cycle** — which matters, because there isn't a safe one
  (below).
- **It covers all 222 digests**, not just the 56% that are safe to collapse.

### 3 · Flag at ingest, never refuse

When ingest sees a digest that is already registered, record that fact; do not
reject the registration. **Refusal is wrong for the 44%:** the second
registration frequently carries a title, org list or year the first does not
have, and refusing it discards information that no later pass can recover.

### 4 · Expose the query

Once `file_sha256` is declared, *"who else has this digest"* is one `filter_by`.
That is what makes the 222 inspectable by a person or an agent, which is the
right place for a judgement that cannot be made by rule.

---

## The four candidates, priced against the measurement

| candidate | verdict |
|---|---|
| **refuse the second registration at ingest** | ❌ wrong for the 44% whose second registration carries information the first lacks — and irreversible, since the discarded record is never written |
| **accept and merge the org lists** | ❌ addresses only `orgs_long`, leaves title/year/type differences untouched, and **invents a record that was never registered**. Wrong for the 5 disjoint-org cases in particular |
| **accept and flag** | ✅ **recommended.** Loses nothing, forces no winner, and puts the 222 in front of something that can judge |
| **do nothing, expose the query** | ⚠️ acceptable but incomplete — it leaves the duplicate-results harm in place, which read-time suppression fixes for free |

---

## What is inert until other things land

**The lookup needs the schema PATCH.** Steps 3 and 4 assume `file_sha256` is
declared. Until then the field is written and stored but not filterable, so both
are inert — nothing breaks, they simply do not fire. Step 2 does not depend on
the PATCH at all if the response already carries file digests.

**Step 3 cannot be described as taking effect on the next ingest cycle**, because
there isn't a safe one. Lane C measured that of 100 `files[].url` entries in prod
across both Kudos hosts, **none served a PDF** — 84 returned an HTTP 200 HTML
landing page, 15 returned 404, and a PDF parser fed HTML yields zero chunks and
no error. Ingest-time flagging is a legitimate design; it is not a schedule.

**Scale check on the harm, so the effort is proportionate:** 230 redundant rows,
**2.0% of the corpus**. This is worth a read-time filter and a flag. It is not
worth a migration, and it was never worth the title+year dedup that would have
deleted **1,362 distinct documents**.

---

## Related

**#101** — the population and the safe-key finding. **#228** — the annex category
and the disjoint-year groups. **#255** — the flat digest field that makes the
lookup reachable. **#251** — the backfill, gated on the same ingest cycle.
