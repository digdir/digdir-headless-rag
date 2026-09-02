# Q6 v3 — Compare how Altinn Studio v7 and v8 handle data model definition — what's the migration story?

**Category**: Cross-document synthesis / partial refusal
**v1 calls**: 8
**v2 calls**: 4
**v3 calls**: 4

## Strategy (unchanged from v2)

Filter by `language:=en` + linktitle/frontmatter_title to enumerate
v8 data-model docs; chunk-content probe to confirm v7 absence;
migration-doc check; inspect the new Altinn-2-datamodel doc.

## Trail

### Tool 1 — Filtered linktitle search "data model" EN

```
bb ts-search digdir public-docs docs "data model" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'language:=en' --limit 10
```

**5 hits**, all under `/altinn-studio/v8/`. Same set as v2:
`82a143cfd981`, `088a6ff49181`, `837753b0cd68` (Altinn 2
datamodel, new since v1), `d0ee8bb8a189`, `98c4e6acf97c`. No v7-
specific data-model doc.

### Tool 2 — Confirm v7 absence via chunk content

```
bb ts-search digdir public-docs chunks "v7 data model datamodell" \
  --query-by content_markdown --limit 5
```

**Result: 2 hits / 2 found** — `10ec36244c23` (validation, chunk 3)
and `0583c88a4334` (dataprocessing, chunk 0). v2 saw 4 rows
because of duplicate revisions; v3's orphan-delete leaves exactly
the two genuine chunks. Both are v8 docs with embedded `**v7**`
callouts, not v7-about docs — same finding as v2.

### Tool 3 — Migration-from-v7 doc

```
bb ts-search digdir public-docs docs "migrating v7" \
  --query-by "linktitle,frontmatter_title,title" \
  --filter-by 'language:=en' --limit 3
```

**1 hit**: `c7c8705b5968` "Migrating from v7" (3 chunks).
Unchanged from v2.

### Tool 4 — Inspect Altinn-2-datamodel doc

```
bb ts-get digdir public-docs chunks --range 837753b0cd68:0-0 ...
```

chunk_id `d871d2d193b8` (different from v2's pre-orphan-delete
ID, since the doc was re-revised). Content unchanged in
substance: frontmatter title "Utvikle datamodell for Altinn 2 i
Altinn Studio" — confirms it's about Altinn-2-platform datamodel
development from Studio v8, *not* a v7→v8 story.

## Cited chunks

- `c7c8705b5968` chunks 0–2 (Migrating from v7) — NuGet-upgrade
  migration; no data-model content
- `82a143cfd981` (v8 concept doc) — v8 data-model definition
- `d871d2d193b8` (Altinn-2-datamodel, new revision) — about Altinn
  2 platform, not v7

## Answer

(Same partial-refusal answer as v1/v2.)

The corpus does **not** support a real side-by-side comparison
of how Altinn Studio v7 vs v8 handles data model definition:

- All `data-modeling` docs filtered by `language:=en` live under
  `/altinn-studio/v8/`. **No v7 documentation subtree exists.**
- `837753b0cd68` (Altinn-2-datamodel) is about the *Altinn 2
  platform* datamodel development workflow, not v7→v8 of Altinn 3
  apps. A retrieval system tempted by "altinn-2 + datamodel"
  could confuse Altinn 2 (platform) with Altinn 3 v7 (app NuGet
  version).
- The migration doc `c7c8705b5968` covers the v7→v8 transition
  as a **NuGet upgrade** (`Altinn.App.Api`/`Altinn.App.Core` 7.x →
  8.0.0 via the Altinn Studio CLI's `upgrade backend` command).
  **Data model is not discussed.**

What the corpus *does* support:

- The **v8 data model concept** (`82a143cfd981`): structured
  schema definition decoupled from form layout.
- **Version-callout patterns** within v8 docs (chunks
  `10ec36244c23`, `0583c88a4334`) showing validation and
  dataprocessing shifted from override-base-class (v4-v6) to
  DI-registered interfaces (v7+). By extension the v7→v8
  data-model story likely follows a similar DI pattern, but the
  corpus doesn't say.

For the full story the doc explicitly defers to external GitHub
release notes for app-lib-dotnet.

## Self-assessment vs v2

**What stayed the same**: 4 calls, identical conclusion, same
partial-refusal shape.

**What got cleaner**: Tool 2's chunk-content probe now returns
2 chunks instead of 4. The "duplicate revisions visually noisy"
artifact is gone; the v7-absence finding is even easier to make
than in v2.

**What did not improve**: the corpus content gap. Phrase-corpus
health doesn't address Q6 — the question fails on absence-of-
content, not on retrieval signal.

## Predicted gap on Q6 v3

Same as v2 (large). Refusal discipline is still the dominant
requirement. The cleaner chunks collection makes the v7-absence
*easier to confirm* but doesn't change whether an automated
system will or won't trip on the NuGet-upgrade doc as a
substitute for the real answer.
