# Q6 v2 — Compare how Altinn Studio v7 and v8 handle data model definition — what's the migration story?

**Category**: Cross-document synthesis
**v1 calls**: 8
**v2 calls**: 4 (50% reduction, but same conclusion)

## Strategy (v2)

v1 established: corpus has no v7-specific data-model docs. All
data-modeling content is under `/altinn-studio/v8/`, plus a
migration doc that's about NuGet upgrades not data model. v2's
test: do the new fields change that?

## Trail

### Tool 1 — Filtered linktitle/frontmatter search for "data model" EN

```
bb ts-search digdir public-docs docs "data model" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'language:=en' --limit 10
```

**5 hits**, all under `/altinn-studio/v8/`:

- `82a143cfd981` — concept doc `/v8/concepts/data-model/`
- `088a6ff49181` — `/v8/guides/development/options/sources/from-data-model/`
- **`837753b0cd68`** — **NEW since v1**: `/v8/guides/altinn-2/altinn-2-datamodel/`
  — but this is "Utvikle datamodell for Altinn 2 i Altinn Studio",
  i.e. how to *develop* an Altinn-2 datamodel from Altinn Studio
  v8. Not about v7→v8.
- `d0ee8bb8a189` — `/v8/reference/data/data-modeling/` (13 chunks)
- `98c4e6acf97c` — `/v8/designer/build-app/data-modeling/`

### Tool 2 — Confirm v7 absence via chunk content

```
bb ts-search digdir public-docs chunks "v7 data model datamodell" \
  --query-by content_markdown --limit 5
```

**4 chunks** (2 unique chunk_ids, each duplicated due to the
upsert side effect): `10ec36244c23` (chunk 3 of the validation
doc) and `0583c88a4334` (chunk 0 of Dataprosessering). Same as
v1 — these are v8 docs with embedded `**v7**` callouts, not v7-
about docs.

### Tool 3 — Migration-from-v7 doc

```
bb ts-search digdir public-docs docs "migrating v7" \
  --query-by "linktitle,frontmatter_title,title" \
  --filter-by 'language:=en' --limit 3
```

**1 hit**: `c7c8705b5968` "Migrating from v7" — same NuGet-
upgrade doc as v1, still 3 chunks, still doesn't cover data
model.

### Tool 4 — Inspect the new Altinn-2-datamodel doc

```
bb ts-get digdir public-docs chunks --range 837753b0cd68:0-0 ...
```

Confirmed: this doc is about **Altinn 2 datamodel development
from Altinn Studio v8** (frontmatter: "Utvikle datamodell for
Altinn 2 i Altinn Studio"). Different topic entirely — Altinn 2
vs Altinn 3 platforms, not v7 vs v8 of the app NuGets.

## Cited chunks

- `c7c8705b5968` chunks 0–2 (Migrating from v7) — still the
  canonical migration doc; covers NuGet upgrade only
- `82a143cfd981` (v8 concept doc) — datamodel definition
- `837753b0cd68` (v2 corpus addition; not relevant to v7→v8)

## Answer

(Same partial-refusal as v1.)

The corpus does **not** support a real side-by-side comparison
of how Altinn Studio v7 vs v8 handles data model definition.
Confirmed in v2 with the new fields:

- All `data-modeling` docs filtered by `language:=en` live under
  `/altinn-studio/v8/`. No v7 documentation subtree exists.
- A new doc surfaced in v2's corpus (`837753b0cd68` Altinn-2-
  datamodel) but it's about the *Altinn 2 platform* datamodel
  development workflow, not v7→v8 of Altinn 3 apps.
- The migration doc `c7c8705b5968` documents the v7→v8 transition
  as a **NuGet upgrade** (`Altinn.App.Api`/`Altinn.App.Core` 7.x →
  8.0.0 via the Altinn Studio CLI's `upgrade backend` command).
  **Data model is not mentioned at all.**

What the corpus *does* support:

- The **v8 data model concept** (`82a143cfd981`): "Datamodell er
  en strukturert beskrivelse av hvilke data som skal samles inn
  og hvordan disse dataene henger sammen." Type/validation
  definitions, decoupled from form layout.
- The **migration tooling**: use the CLI for automated upgrade.
- **Version-callout patterns** within v8 docs (chunks
  `10ec36244c23`, `0583c88a4334`) showing how validation and
  dataprosessering shifted from override-the-base-class (v4-v6)
  to DI-registered interfaces (v7+). Not directly about data
  model.

By extension, the v7→v8 shift for data-model handling likely
follows the same DI pattern. But the corpus doesn't say.

For the full story, the doc explicitly defers to external
GitHub release notes for app-lib-dotnet.

## Self-assessment vs v1

**What got better**:
- **Faster confirmation of the v7-absence finding.** v2 reached
  the same conclusion in 4 calls instead of 8 because the
  language-filtered linktitle search is more decisive about what
  IS and ISN'T in the corpus.

**What changed in corpus**:
- New doc `837753b0cd68` (`/v8/guides/altinn-2/altinn-2-datamodel/`)
  appeared since v1. Could be mistaken for a v7-related doc by a
  retrieval system that looks at "altinn-2" + "datamodel" — but
  it's about the *Altinn 2 platform* not the *v7 NuGets*.

**What didn't change**: the corpus content gap. No v7-side
data-model docs exist; the migration doc still doesn't cover
data model. v2's nicer retrieval can't fabricate content that
isn't there.

## Predicted gap on Q6 v2

Same as v1 (large). The trap is the same: confidently
summarizing the NuGet-upgrade migration as if it answered the
data-model question, or extrapolating from the v8 reference
doc as if it implied v7 baseline. The new Altinn-2 doc is an
*additional* trap — a retrieval system looking at
"altinn-2 + datamodel" could confuse Altinn 2 vs v7 as
"old/new". Refusal discipline is still the dominant requirement.
