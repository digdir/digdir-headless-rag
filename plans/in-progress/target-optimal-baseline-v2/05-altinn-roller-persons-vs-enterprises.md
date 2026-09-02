# Q5 v2 — Hva er forskjellen mellom Altinn-roller for personer og virksomheter?

**Category**: Cross-language (NB query, mixed-language corpus)
**v1 calls**: 6
**v2 calls**: 6 (same count, but **qualitatively much cleaner**)

## Strategy (v2)

v1 used EN-title fallback ("altinn roles") because NB title-search
returned 0 hits. With `linktitle` now indexed, NB queries should
hit directly: `Altinn-roller` for the parent, `Personroller` for
persons, `Virksomhetsroller` for enterprises.

## Trail

### Tool 1 — Three parallel linktitle probes

```
bb ts-search digdir public-docs docs "Altinn-roller"      --query-by linktitle --filter-by 'language:=nb'
bb ts-search digdir public-docs docs "personroller virksomhetsroller" --query-by linktitle --filter-by 'language:=nb'
bb ts-search digdir public-docs docs "Virksomhetsroller"  --query-by linktitle --filter-by 'language:=nb'
```

Each probe returns **exactly 1 hit** — and exactly the right
doc:

| Probe | Result |
|---|---|
| "Altinn-roller" | `110535984ef1` parent overview (`/nb/.../roles_altinn/`) |
| "personroller virksomhetsroller" | `b7841e9db901` persons (`/nb/.../altinn_roles_persons/`) |
| "Virksomhetsroller" | `e5a83fdead4d` enterprises (`/nb/.../altinn_roles_enterprices/`) |

Compare v1: NB title search returned 0; had to fall back to EN
title "altinn roles" returning 8 candidates needing visual
filtering.

### Tools 2–4 — Range-fetch all three docs

```
bb ts-get digdir public-docs chunks --range 110535984ef1:0-0 ...   # parent
bb ts-get digdir public-docs chunks --range b7841e9db901:0-1 ...   # persons
bb ts-get digdir public-docs chunks --range e5a83fdead4d:0-1 ...   # enterprises
```

Same chunk_ids as v1 — these docs weren't re-revised
(`75c3ec105b6e` for parent, `36b260f5d51f`/`df90a3ca5a45` for
persons, `66fb43dab201`/`a136030a86f8` for enterprises).

## Cited chunks

- `75c3ec105b6e` (parent overview, frontmatter title "Roller fra Altinn")
- `36b260f5d51f`, `df90a3ca5a45` (persons doc)
- `66fb43dab201`, `a136030a86f8` (enterprises doc)

## Answer

(Substantively identical to v1; corpus content unchanged.)

Altinn-rollene deles i to hovedkategorier ut fra *hvem som
delegerer fullmakt*:

- **Personroller**: en *person* gir en bruker tilgang til å
  handle på sine vegne. Eksempler: handle på vegne av en annen
  privatperson, skatteopplysninger om eget enkeltpersonsforetak,
  lese meldinger i meldingsboksen.
- **Virksomhetsroller**: en *virksomhet* (foretak) gir en bruker
  tilgang til å handle på vegne av organisasjonen. Eksempler:
  handle på vegne av enheter/foretak, regnskap, signere
  økonomiske skjema, taushetsbelagt post, revisor-attestasjon på
  MVA-kompensasjon (RF-0009).

Mange *temaområder* (kommunale, energi/miljø, lønn, patent,
regnskap, samferdsel) finnes i begge — forskjellen ligger i *på
vegne av hvem* rollen gir tilgang.

**Unike virksomhetsroller**: helse-/sosial-/velferdstjenester,
Økokrim, taushetsbelagt post fra stat/kommune, revisor
MVA-attestasjon, "bred utvalg low-restriction"-rolle.

**Unike personroller**: vegne av annen privatperson
(vergemålsmønster), skatteopplysninger om eget
enkeltpersonsforetak.

**Viktig forbehold**: chunkene jeg leste inneholder
*beskrivelsene* av rollene, men *ikke* rollenavnene (chunker har
løsrevet rolle-headere fra body — corpus-quality issue, ikke
retrieval-issue).

**Kontekst** (per Q4): hele dette rolle-systemet er under
utfasing — Altinn 2-roller avvikles Q2 2026 (inneværende kvartal)
og erstattes av tilgangspakker.

## Self-assessment vs v1

**What got dramatically better**:
- **Every NB linktitle probe was a direct hit.** Three probes,
  three perfect single-hit results. v1's title-search returned 0
  hits for "Altinn-roller" and we had to retreat to EN-title
  search returning 8 candidates.
- **Language filtering eliminated the EN-doppelganger issue.**
  v1 saw `Altinn roles persons` (EN) and the NB version both
  hit equally on EN title search; v2's `language:=nb` filter
  cleanly excludes the EN siblings.

**What didn't improve (because not a retrieval issue)**:
- **The role names are still missing from chunk content.** The
  chunker decoupled list-item headers from descriptions in v1
  ingest; v2's docs-only re-upsert didn't change chunks. Same
  caveat applies to the answer.
- **The cross-doc synthesis** (parent + persons + enterprises)
  still requires 3 fetches because that's the document
  structure, not a retrieval cost.

## Predicted gap on Q5 v2

The retrieval gap on Q5 has essentially closed. All three NB
docs are now directly findable via `linktitle`. Remaining gap:
the chunker's lost role-names is corpus-quality, and the parent-
plus-leaves synthesis is the doc structure. Both are fixable but
out of scope for retrieval improvements.
