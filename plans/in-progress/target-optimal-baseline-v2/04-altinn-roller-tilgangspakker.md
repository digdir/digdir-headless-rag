# Q4 v2 — Når går Altinn over fra Altinn-roller til Tilgangspakker, og hva er de mest betydningsfulle endringene for tjenesteeiere?

**Category**: Temporal (NB)
**v1 calls**: 5
**v2 calls**: **3** (40% reduction)

## Strategy (v2)

`linktitle` now contains the user-natural Norwegian term
"Nye tilgangspakker". Filter by `language:=nb`, query by
`linktitle`. Should surface the migration doc on first probe.

## Trail

### Tool 1 — NB linktitle search for "Tilgangspakker"

```
bb ts-search digdir public-docs docs "Tilgangspakker" \
  --query-by linktitle \
  --filter-by 'language:=nb' \
  --limit 6
```

**4 hits**, migration doc `60ea897f58fd` (`/nb/authorization/migration/new-accessgroups/`)
ranked #1.

Contrast v1: title-search for "Tilgangspakker" returned 0 hits;
chunk-content search returned 3 hits / 135 total matches; needed
2 more probes to identify the migration doc.

### Tool 2 — Range fetch the NB migration doc (2 chunks)

```
bb ts-get digdir public-docs chunks --range 60ea897f58fd:0-1 \
  --include-fields chunk_id,chunk_index,content_markdown
```

Got the frontmatter-titled "Innføring av nye tilgangspakker som
erstatning for dagens Altinn 2 roller" intro (chunk 0) and the
full timeline (chunk 1).

**But the NB doc is missing the 11-question Q&A** that lives in
the EN sibling's chunk 2.

### Tool 3 — Fetch EN doc's chunk 2 for the Q&A

```
bb ts-get digdir public-docs chunks --range a285a4a303da:2-2 ...
```

Same Q&A content as v1 (chunk_id `8e71d9016de9` unchanged).

## Cited chunks

- `bdd5427ec8d5` (NB chunk 0, new revision lastmod 2025-11-07) — intro
- `688781d672e2` (NB chunk 1, new revision) — full Q1-2023-to-Q2-2026 timeline
- `8e71d9016de9` (EN chunk 2, unchanged) — 11-question service-owner Q&A

## Answer

(Substantively identical to v1's answer; corpus content is the same.)

**Tidsplan** (per `688781d672e2`, sist oppdatert 2025-11-07):

| Periode | Aktivitet |
|---|---|
| Q1 2023 | Forslag til nye tilgangspakker |
| Q2/Q3 2023 | Høring versjon 1 |
| Q3 2023 | Tilgangspakker versjon 2 etablert |
| Q3/Q4 2023 | Høring versjon 2 |
| (etterhvert) | Endelig versjon etablert |
| **Q4 2024** | Tjenesteeiere kan begynne å sette tilgangspakker på apper og ressurser; full GUI i Ressursregisteret/Altinn Studio |
| **Q1 2025** | Ny brukerflate for fullmaktstyring for virksomheter lanseres |
| **Q2 2026** | **Altinn 2-roller avvikles — Altinn 2 slås av i juni 2026** |

**Praktisk implikasjon**: Q2 2026 er *inneværende kvartal*.
Tjenester som ennå ikke har fått tilgangspakker tildelt i policy
vil miste de gamle rollebaserte tilgangene når Altinn 2 går ned
i juni.

**Mest betydningsfulle endringer for tjenesteeiere** (`8e71d9016de9`,
`bdd5427ec8d5`):

1. **Konseptbytte**: roller → ansvarsområdebaserte tilgangspakker.
2. **Policyene må oppdateres** — apper på Altinn 3 må få sine
   policyer utvidet til å inkludere nye tilgangspakker i tillegg
   til de gamle Altinn-rollene.
3. **Gamle roller kan stå** inntil Altinn 2 fases ut, så lenge
   tjenesten har fått ny(e) tilgangspakker i tillegg.
4. **Flere tilgangspakker per tjeneste er mulig**.
5. **Enkeltrettigheter forsvinner ikke** — direkte delegering på
   tjenestenivå fortsetter å fungere.
6. **ER-roller knyttes til tilgangspakker** i stedet.
7. **Ny brukerflate i Q1 2025** for å faktisk tildele
   tilgangspakker til ansatte.
8. **Innbyggere kommer i fase 2** — egne tilgangspakker som
   erstatter Altinn 2-roller for privatpersoner.

## Self-assessment vs v1

**What got better**:
- **NB linktitle search worked.** The whole point of indexing
  `linktitle` was to make "Tilgangspakker" a discoverable query
  term. v2 finds the migration doc on the first probe.

**What didn't improve / new findings**:
- **The NB doc is *shorter* than EN** — 2 chunks vs 3 — and
  missing the Q&A. Even with perfect retrieval, a fully NB-
  audience answer requires reading the EN sibling for the Q&A.
  This is a content-completeness issue, not a retrieval one.
- **Same duplicate-chunks contamination as Q1/Q2** in the range
  output (chunks 0 and 1 each appear twice — old + new revisions).

## Predicted gap on Q4 v2

Smaller than v1. The right doc is now trivially findable. The
remaining gap is the cross-language synthesis ("answer in NB but
fetch the Q&A from the EN sibling") which is a judgment move —
not something a single-pass retrieval system would do without an
explicit fallback strategy.
