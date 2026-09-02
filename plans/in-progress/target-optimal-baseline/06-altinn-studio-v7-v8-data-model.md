# Q6 — Compare how Altinn Studio v7 and v8 handle data model definition — what's the migration story?

**Category**: Cross-document synthesis
**Expected locality**: chunks from ≥2 distinct doc_nums
**Actual finding**: the corpus does NOT support a true side-by-side
v7-vs-v8 comparison. v7-specific data model docs don't exist as a
separate subtree. This is a **partial-refusal** answer.
**Retrieval calls used**: 8

## Strategy

Cross-doc synthesis expected. Plan: parallel-probe for `data model`
+ `data modeling` + `v7 v8 migration` to find the comparison
material. If v7 vs v8 isn't directly documented, fall back to
reading the v8 data-modeling docs and the migration doc, and
synthesize what the corpus *does* support.

## Trail

### Tool 1 — Three parallel title probes

```
bb ts-search digdir public-docs docs "data model" --query-by title --limit 10
bb ts-search digdir public-docs docs "data modeling" --query-by title --limit 5
bb ts-search digdir public-docs docs "v7 v8 migration" --query-by title --limit 5
```

Results (the surprise):
- "data model" titles: 8 hits, **all under `/altinn-studio/v8/`**.
  No v7 documentation surfaced.
- "data modeling" titles: 4 hits, **also all v8**:
  EN + NB variants of `designer/build-app/data-modeling/` (1 chunk
  each, brief) and `reference/data/data-modeling/` (EN 13 chunks,
  NB 15 chunks — the rich reference).
- "v7 v8 migration" titles: 4 hits — two "V7" changelog docs and
  **two "Migrating from v7"** docs under
  `/community/changelog/app-nuget/v8/migrating-from-v7/` (EN 3
  chunks, NB 1 chunk).

The full-text data-modeling docs live only in v8. The v7 footprint
is the changelog and the migration doc.

### Tool 2 — Confirm v7 absence + read migration doc

```
bb ts-search digdir public-docs chunks "v7 data model datamodell" \
  --query-by content_markdown --limit 10
bb ts-get digdir public-docs chunks --range c7c8705b5968:0-2 ...
```

- Only **2 chunks** in the entire corpus match "v7 data model
  datamodell" — and (as Tool 3 will confirm) neither is actually
  about v7 data model, they're v8 docs with version callouts.
- The EN migration doc has 3 chunks: an intro pointing to GitHub
  for the latest preview; a description of `altinn-studio` CLI
  for automated upgrade; and a manual upgrade procedure
  (`Altinn.App.Api`/`Altinn.App.Core` NuGet bump from `7.15.3` →
  `8.0.0`). **Data model is not mentioned at all.**

### Tool 3 — Identity-check the 2 "v7 data model" hits + read v8 concept

```
bb ts-search digdir public-docs chunks "altinn-studio v7" --query-by content_markdown --limit 5
bb ts-get digdir public-docs chunks --ids 10ec36244c23,0583c88a4334 ...
bb ts-get digdir public-docs chunks --range 54157c544a28:0-0 ...
```

- "altinn-studio v7" full-text: **1 hit total**. Sparse.
- The 2 hits from Tool 2 are:
  - `10ec36244c23` (chunk 3 of doc `5b8f13d7bb37`) — a *v8*
    validation doc with embedded sections marked "**v7**" and
    "**v4, v5, v6**" showing how server-side validation is
    implemented per version. The doc isn't *about* v7; it
    documents v8 patterns and notes how the same thing worked
    in older versions.
  - `0583c88a4334` (chunk 0 of doc `6c3b5faf0e10`) — a v8
    "Dataprosessering" doc with the same multi-version
    callout structure (v7 / v4-v5-v6 sections showing the
    DI-based vs override-based handler patterns).
- The v8 concept doc `54157c544a28`
  (`/nb/altinn-studio/v8/concepts/data-model/`) is a single-chunk
  conceptual explanation: a data model is a structured description
  of what data to collect and how it interrelates, defining field
  types and validation rules.

## Cited chunks

- `f02dc7256933` (the v8 `concepts/data-model/` overview)
- `e465e972dccc`, `b49b69799613`, `e749f9d3a1ab` (the 3 chunks of
  the EN `migrating-from-v7` doc)
- `10ec36244c23` (the v8 validation doc with the v7/v4-6 callouts —
  cited as evidence of how the corpus *does* note version-specific
  differences)
- `0583c88a4334` (the v8 Dataprosessering doc with the same
  callout pattern)

## Answer

**Honest framing**: the corpus does not support a real
side-by-side comparison of how Altinn Studio v7 vs v8 handles
**data model definition**, because there is no separate v7
data-model documentation subtree. All `data-modeling` reference
docs in `digdir/public-docs` live under
`/altinn-studio/v8/reference/data/data-modeling/` (15 chunks NB /
13 chunks EN). I searched titles for `data model` and `data
modeling` (returned 12 docs total, all v8); full-text searched for
"v7 data model datamodell" (returned 2 chunks, both in v8 docs).

What the corpus *does* support:

### What the v8 data model is (per `f02dc7256933`)

"En datamodell er en strukturert beskrivelse av hvilke data som
skal samles inn og hvordan disse dataene henger sammen." It
defines field types, validation rules (required, ranges), and is
deliberately decoupled from form visual layout — the same model
may back multiple forms.

(The deeper *mechanics* — XSD vs JSON Schema vs C# classes — are
in the 13/15-chunk reference doc which I did not read in full; the
overview doc cited here is the explanation-level summary.)

### The "migration story" as the corpus documents it (per `e465e972dccc`, `b49b69799613`, `e749f9d3a1ab`)

The v7→v8 migration in this corpus is documented as an
**app-NuGet-package upgrade**, not a data-model migration:

- Use the `altinn-studio` CLI (≥1.4.0) and run `altinn-studio
  upgrade backend` from the app root. The CLI handles most
  breaking changes between v7 and v8 of the app NuGets
  automatically.
- Or manually: bump `Altinn.App.Api` and `Altinn.App.Core`
  references in `App.csproj` from `7.15.3` to `8.0.0`, then fix
  breaking changes (the v8 changelog is the canonical list).

**The migration doc does not mention data model migration.** It
treats the v7→v8 transition as a code-and-package upgrade, with
the implication that the data model itself is unchanged or
agnostic to the app version.

### Where v7-vs-v8 *patterns* are visible in the corpus

Two v8 reference docs use a version-callout pattern that
documents how a feature is implemented across versions — these
are the closest thing to side-by-side comparisons:

- **Validation** (`10ec36244c23`, in
  `.../reference/data/validation/` or similar) — has explicit
  "**v7**" and "**v4, v5, v6**" sections showing how server-side
  validation is wired. In v7+ you implement the
  `IInstanceValidator` interface and register it via DI; in
  v4-v6 you put the logic in pre-defined `ValidateData` /
  `ValidateTask` methods in `ValidationHandler.cs`.
- **Dataprosessering** (`0583c88a4334`) — same callout pattern
  for `IDataProcessor` (v7+, DI-registered) vs
  `DataProcessingHandler.cs` overrides (v4-v6).

These are *not* about data model definition specifically, but they
illustrate the *kind* of v7→v8 shift the corpus surfaces: a
consistent move away from override-the-base-class patterns toward
DI-registered interfaces.

**By extension and reasonable inference** (not directly stated in
the corpus): if the v8 data model handling follows the same DI
pattern shift, the comparison would be "v7 introduces
dependency-injected handlers; v4-v6 used base-class overrides." The
v8 NuGet upgrade itself is the upgrade path, with the CLI
handling the breaking-change rewrites.

### What I cannot answer

- Whether the v8 data model *format* (XSD/JSON Schema layout,
  attribute conventions, allowed types) changed materially from
  v7. The corpus's v8 data-modeling reference doc presumably
  describes the v8 format, but there's no v7 baseline to compare
  it against.
- Whether existing v7 data models need any explicit conversion or
  remapping when an app is upgraded via the CLI. The migration
  doc is silent on this.

A real user wanting the full comparison would need to consult the
external Altinn changelog / GitHub release notes
(`https://github.com/Altinn/app-lib-dotnet/releases`, explicitly
linked from the migration doc) to fill the gap.

## Self-assessment

**What was easy**: title-and-content parallel probes confirmed the
v7 documentation gap within 3 tool calls. The right docs surfaced
immediately; the issue is corpus content, not retrieval quality.

**What was hard**: not retrieval — *acceptance*. The natural
instinct was to keep digging for a v7 data-model doc that "must be
somewhere". Forcing the honest answer ("v7 data-model docs don't
exist here, the migration doc doesn't cover this") instead of
manufacturing a comparison from inference required restraint. The
3-chunk migration doc is the most plausible source for a confident
fake answer; reading it carefully and noting that data model is
literally not mentioned was the right move.

**What surprised me**:

1. **The corpus is essentially v8-only for `/altinn-studio/`.**
   Despite the question being well-posed (v7 and v8 are both real
   versions and there *is* a migration story in the real world),
   the corpus has aged out of containing v7-as-current docs. The
   v7 footprint is: changelog, migration doc, version-callouts
   within v8 docs.
2. **Two v8 reference docs use a version-callout pattern** —
   embedding "**v7**" / "**v4, v5, v6**" sections within a v8 doc
   to document how something was done historically. This is a
   really useful corpus convention but it means version-specific
   answers aren't in version-specific *docs*; they're embedded in
   the canonical v8 doc for that feature. A retrieval system
   filtering by URL `/v7/` would miss them entirely.
3. **The 1-chunk concept doc is short and clean.** "Datamodell"
   defined in ~5 paragraphs with the right level of abstraction
   (decoupled from form visuals, type+rule definitions, can be
   shared across forms). Easy to cite.

**What would surprise an automated retrieval system**:

1. **The temptation to confabulate a v7 answer.** A retrieval
   system that scores chunks by topical overlap on "v7 data model"
   could surface `10ec36244c23` (a v8 validation doc with a v7
   section) and confidently quote its v7 callout as if the doc
   were about v7 data model. The chunk text contains "v7",
   "datamodell" (in Norwegian — "valideringer mot datamodell"),
   and code samples that *look* authoritative. Without the
   metadata-awareness that this is a v8 *validation* doc, not a v7
   *data-model* doc, an answer can be fluent and wrong.
2. **Recognizing the migration doc's scope.** A retrieval system
   ranking by "migration v7 v8" would correctly find
   `c7c8705b5968`. Whether the answer acknowledges that the doc
   doesn't cover data-model migration *specifically* depends on
   careful reading. The path of least resistance is to summarize
   the NuGet upgrade procedure as if it answered the question.
3. **The honesty discipline of partial refusal.** This question
   exposes the difference between "found docs that match the
   query terms" and "found docs that answer the question". A good
   automated system would distinguish the two and refuse the
   comparison rather than fabricate it.

**Predicted gap on Q6**: large in the wrong direction. The right
docs surface easily, but the *correct conclusion* — "the corpus
doesn't support a real v7-vs-v8 data-model comparison; here's
what it does support, and where to go for the rest" — is a
judgment call that retrieval+synthesis systems usually fail
toward over-confident answers. The trap chunks (validation /
dataprosessering with embedded v7 callouts) are particularly
seductive because they contain *both* "v7" and "datamodell" in
authoritative-looking code samples.
