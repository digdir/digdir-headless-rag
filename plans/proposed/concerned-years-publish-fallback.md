# `concerned_years`: the publish-year fallback — and two disjoint 216s

Findings for #238. Read-only throughout; nothing was written to any collection.

> **The property the fix must remove is fabrication without provenance, not the
> wrong values.** Correcting 216 rows leaves the mechanism that produced them in
> place, and it will produce them again.

---

## Provenance of every number below

**Instance, stated both ways.** Measured **prod**: the container named
`typesense` on the deploy box, box-side port **8108**, collection
`KUDOS_preprod_v4_documents_ab897fbdedfa`, `num_documents` **11,306** — asserted
before any analysis ran. **Not touched:** `typesense-test` (8208) and
`typesense-dev` (8308), which live on the **same box**. 8208 was read exactly
once, deliberately, to confirm the discriminator: it returns **10,064**.

**Route.** `curl` on the box over SSH, admin key resolved *inside* the SSH
command. No local port was bound, so none of the three local hazards applied —
all three `bb` port-forward tasks bind the same local `8108`, that port is held
by the Colima-published `digdir-rag-typesense-dev` container, and the collection
name is shared across instances. **The count assertion is the only real
discriminator**, and this document's numbers are auditable because the instance
is named.

> ⚠️ **`11306` is a moving target.** Ingestion changes it. When the assertion
> fails, the action is **"establish which instance I am on"** — *never* "refresh
> the constant". A constant updated to match whatever you just measured cannot
> detect that you measured the wrong thing.

---

## The predicate, stated next to the number

The headline was previously quoted without its definition, which is what made
216 look like a constant rather than a measurement.

```
year regex:  (?<!\d)(19[5-9]\d|20[0-2]\d)(?!\d)
include a document iff its title contains EXACTLY ONE distinct such year
                    AND that year differs from the publish year
```

Reproduced independently from a separate export of the same prod collection —
**all five buckets exact**:

| bucket | this measurement | lane A |
|---|---|---|
| single-year titles differing from publish | **2638** | 2638 |
| `concerned_years` has title year only | **2406** | 2406 |
| `concerned_years` has publish year only | **216** | 216 |
| both | **16** | 16 |
| neither | **0** | 0 |

Two lanes, two exports, one predicate, identical results.

### But the number is definition-sensitive, and that interval is the honest one

Four reasonable answers to "does this title state a year" give four different
populations:

| year extraction | single | title | **publish** |
|---|---|---|---|
| `(?<!\d)(19[5-9]\d\|20[0-2]\d)(?!\d)` — the predicate above | 2638 | 2406 | **216** |
| word-bounded `19xx\|20xx` | 2637 | 2409 | **216** |
| word-bounded `19xx\|20xx\|21xx` | 2639 | 2409 | **218** |
| bare 4-digit `19xx\|20xx\|21xx` | 2643 | 2406 | **225** |
| any bare 4-digit | 2667 | 2404 | **262** |

**216–262.** A fifth of variation from a choice nobody had written down. The
*phenomenon* is robust — 8–10% of candidates in every variant — and the
cross-validation holds: `13090` and `12861` appear in this set and in #228's,
found by unrelated methods.

So **216 is a lower bound twice over**: the detector cannot see titles with
ranges, no year, or several years (only 2,638 of 11,306 are even candidates),
*and* its own threshold moves the count by 21%.

---

## ⚠️ Two disjoint 216s — do not conflate them

| | count | |
|---|---|---|
| documents carrying a scalar `concerned_year` | **216** | corpus-wide |
| documents with the publish-year contamination | **216** | under the predicate above (**218** under a wider one) |

**These sets share no members.** The contaminated set is **100% missing** the
scalar `concerned_year` — that absence is precisely what triggers the fallback.
The coincidence is arithmetic, not causal, and a summary that says "216" without
saying *which* 216 will be read the wrong way.

---

## The mechanism, confirmed in the code and on the data

`server/src/digdir/docs/loader.clj:185-189`, in `fill-in-doc-fields`:

```clojure
;; Fallback: use publish_date year if no concerned year info
:else (if-let [year (extract-year-from-date (:publish_date doc))] [year] [])
```

When the upstream KUDOS record carries no `concerned_year_from` / `_to` /
`concerned_year`, **we substitute the publish year**. Deliberate and commented —
this was never upstream data quality, it is our own fallback.

**Confirmed on the data:** of the publish-year set, **218/218 — 100% — have no
upstream scalar `concerned_year`**, exactly as the fallback predicts.

**The honest limit on that test.** The title-year set is *also* 97% missing that
scalar, which looks contradictory until you notice that `concerned_year_from`
and `concerned_year_to` are **dropped by `prepare-doc`'s `select-keys` and never
reach Typesense**. They are the first two branches of the loader's `cond`, so
they almost certainly supply the correct values. The collection therefore
**cannot distinguish "fallback fired" from "range fields supplied it"** in
general. It happens that the negative case *is* the population in question, so
the confirmation stands — but not one step further.

---

## Why a one-off correction is not a repair

`loader.clj:427` — *"Always upserts"* — and line 446 upserts `(prepare-doc doc)`,
which recomputes `concerned_years` through that same fallback on every ingest.

**Every hand-repaired document reverts the next time it is ingested.** A one-off
pass is not incomplete; it is temporary.

---

## The options, scored against ground truth

Lane A evaluated these against **14 ground-truth values** derived from the
documents themselves in #228 — 8 from titles, 6 from fetched PDFs.

| option | verdict |
|---|---|
| **1. Stop fabricating** — emit `[]` when there is no concerned-year information | **Recommended.** Makes the gap visible instead of inventing a plausible value, and makes the population countable, which today it is not |
| **2. Keep the fallback, record provenance** in a sibling field | Viable, strictly more work than 1, and it preserves a value that is wrong ~8–10% of the time |
| **3. Derive from the title** when it states exactly one year | **5/5 correct where it fires, but fires on only 5 of 14 — 36% recall.** High precision, unusable as the primary rule |

### The fact that decides it

**4 of the 14 ground-truth values are not single years at all** — `2023–2026`,
`2017+2021`, `2020–2024`, `2023–2024`. That is **29%**.

**No single-scalar fallback can ever be right for those**, however clever the
derivation. Option 3 would emit a confidently wrong single year for each, and
option 2 would faithfully record the provenance of a value that could not have
been correct.

> A value that cannot be right 29% of the time should not be invented at all.

---

## Recommendation

1. **Restore `concerned_year_from` and `concerned_year_to` to the projection**
   (`prepare-doc`'s `select-keys` in `loader.clj`). Those two fields are exactly
   what the 29% multi-year population needs, and without them the collection
   cannot tell a fabricated year from a real one. **This is a prerequisite for
   measuring the population, let alone repairing it** — not a nice-to-have.
2. **Adopt option 1:** when there is no concerned-year information, emit nothing.
3. **Backfill** the affected documents once 1 and 2 are in place, so the
   correction is not immediately overwritten.
4. **Option 3 only as a bounded repair**, where a title states exactly one year,
   with its precision stated as **5/5 on 36% recall** — never as the general rule.

---

## Retrieval consequence — narrower than first stated, and for an instructive reason

#238 originally said a year-filtered query silently returns the wrong document.
**That is not true of our default path**, and the reason is the good part:
`server/src/digdir/rag/auto_filter.clj:140` —

> *"We intentionally use title contains (not `concerned_years`) due to data
> quality."*

Every consumer was checked: the loader writes the field, `docs_schema.edn`
declares it, `auto_filter` documents avoiding it, and tests reference it. **There
is no query-time consumer of `concerned_years` in our code.**

But it is `"facet" true` in `resources/docs_schema.edn`, and the retrieval API
passes `filter_by` through — so any caller building a year facet gets the bad
data. **Declaring a field as a facet is an invitation to use it.**

So the exposure is on the **facet surface**, not the default query path — and
that workaround is itself evidence the defect was known at the retrieval layer
and routed around rather than fixed at the source.

---

## Related

**#228** — the 15 disjoint-year pairs, and the ground truth used above.
**#101** — duplicate registrations. **#72** — chunk-id collisions, measured at 0.000%.
