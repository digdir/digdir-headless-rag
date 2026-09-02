# Q4 v3 — Når går Altinn over fra Altinn-roller til Tilgangspakker, og hva er de mest betydningsfulle endringene for tjenesteeiere?

**Category**: Temporal (NB)
**v1 calls**: 5
**v2 calls**: 3
**v3 calls**: 3

## Strategy (unchanged from v2)

`linktitle` query for "Tilgangspakker" with `language:=nb`. Then
range-fetch the migration doc, and (because the NB sibling is
shorter) fetch chunk 2 of the EN counterpart for the Q&A.

## Trail

### Tool 1 — NB linktitle search

```
bb ts-search digdir public-docs docs "Tilgangspakker" \
  --query-by linktitle \
  --filter-by 'language:=nb' \
  --limit 6
```

**4 hits**, migration doc `60ea897f58fd` ranked #1. Identical to v2.

### Tool 2 — Range-fetch NB migration doc (2 chunks)

```
bb ts-get digdir public-docs chunks --range 60ea897f58fd:0-1 \
  --include-fields chunk_id,chunk_index,content_markdown
```

**Result: 2 chunks** — `bdd5427ec8d5` (chunk 0 intro) +
`688781d672e2` (chunk 1 timeline). v2 returned 4 rows (duplicate
revisions); v3 is clean.

### Tool 3 — Fetch EN sibling's chunk 2 for the Q&A

```
bb ts-get digdir public-docs chunks --range a285a4a303da:2-2 \
  --include-fields chunk_id,chunk_index,content_markdown
```

**Result: 1 chunk** — `8e71d9016de9` (the 11-question Q&A).

## Cited chunks

- `bdd5427ec8d5` (NB chunk 0) — intro / Altinn-2 retire framing
- `688781d672e2` (NB chunk 1) — full Q1-2023-to-Q2-2026 timeline
- `8e71d9016de9` (EN chunk 2) — 11-question service-owner Q&A

## Answer

(Substantively identical to v1/v2; corpus content for these docs
is unchanged.)

**Tidsplan** (per `688781d672e2`, sist oppdatert 2025-11-07):

| Periode | Aktivitet |
|---|---|
| Q1 2023 | Forslag til nye tilgangspakker |
| Q2/Q3 2023 | Høring versjon 1 |
| Q3 2023 | Tilgangspakker versjon 2 etablert |
| Q3/Q4 2023 | Høring versjon 2 |
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
8. **Innbyggere kommer i fase 2**.

## Self-assessment vs v2

**What stayed the same**: 3 calls, same path, same cited chunks.

**What got cleaner**: range fetches no longer return duplicate
revisions. The NB doc is still missing the Q&A — that's a
content-completeness issue (the NB sibling has 2 chunks where
the EN has 3), unrelated to corpus health.

## Predicted gap on Q4 v3

Unchanged from v2. The remaining gap is the cross-language
synthesis (NB question → fetch EN for the Q&A), which a single-
pass retrieval system won't do without explicit fallback logic.
Phrase-corpus health is orthogonal to that judgment.
