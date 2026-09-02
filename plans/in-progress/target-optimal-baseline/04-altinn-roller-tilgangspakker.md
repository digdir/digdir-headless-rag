# Q4 — Når går Altinn over fra Altinn-roller til Tilgangspakker, og hva er de mest betydningsfulle endringene for tjenesteeiere?

**Category**: Temporal (NB)
**Expected locality**: chunks of a migration/transition doc
**Actual locality**: 2 chunks of one doc (timeline + Q&A) carry the
whole answer
**Retrieval calls used**: 5

## Strategy

Temporal question + "what changes" angle. "Tilgangspakker" is a
specific noun likely indexed in titles. After Q3's title-search
duds, planned to run title-search AND chunk-search in parallel to
avoid losing time on the dead end.

## Trail

### Tool 1 — Title + chunk searches in parallel

```
bb ts-search digdir public-docs docs "Tilgangspakker" --query-by title --limit 10
bb ts-search digdir public-docs chunks "Tilgangspakker" --query-by content_markdown --limit 3
```

- **Title hits**: 0 (consistent with Q3 — Norwegian terms don't
  surface in `title` even when they should)
- Chunk hits: 3/135 — substantial coverage in content. Top 3 cluster
  around 2 docs: `43df290718a2` (chunk 9) and `a285a4a303da` (chunks
  1, 2).

### Tool 2 — `ts-get docs` for the 2 candidates

- `43df290718a2` "Verger" at
  `/en/authorization/what-do-you-get/accessgroups/accessgroups-citizens/verger/`
  — 10 chunks, but a *specific concept* (guardians) that mentions
  Tilgangspakker in passing.
- **`a285a4a303da` "New accessgroups"** at
  `/en/authorization/migration/new-accessgroups/` — **3 chunks,
  in the `/migration/` subtree**. This is the doc.

### Tool 3 — `ts-get chunks range=a285a4a303da:0-2` → full doc + NB sibling check

```
bb ts-get digdir public-docs chunks --range a285a4a303da:0-2 ...
bb ts-search digdir public-docs docs "new accessgroups" --query-by title --limit 4
```

Surprise: despite the English `title` "New accessgroups" and the
`/en/` URL, the **content is entirely in Norwegian**. The
frontmatter `title` field is "Innføring av nye tilgangspakker som
erstatning for dagens Altinn 2 roller" — that's the *real* title,
hidden in the chunk content because the indexed `title` is derived
from the URL path, not the frontmatter.

NB sibling exists (`60ea897f58fd` `/nb/authorization/migration/new-accessgroups/`)
but has only 2 chunks vs the EN doc's 3, so the EN doc is the
fuller source (and is in Norwegian anyway).

The 3 chunks:
- chunk 0 (`7b5e7a7429fb`) — frontmatter + one-paragraph intro
- chunk 1 (`489b3d4dc4d9`) — **complete timeline** (this is the
  "når" answer)
- chunk 2 (`8e71d9016de9`) — **11-question Q&A for service owners**
  (this is the "hva er de mest betydningsfulle endringene" answer)

Last modified 2025-09-29. The doc explicitly notes that the
authoritative current timeline lives at
[Altinn/altinn-access-groups#6 on GitHub](https://github.com/Altinn/altinn-access-groups/issues/6).

## Cited chunks

- `7b5e7a7429fb` (chunk 0) — frontmatter title + framing
- `489b3d4dc4d9` (chunk 1) — full Q1-2023-to-Q2-2026 timeline
- `8e71d9016de9` (chunk 2) — service-owner Q&A

## Answer

**Tidsplan** (per `489b3d4dc4d9`, sist oppdatert 2025-09-29 — den
autoritative versjonen vedlikeholdes på
[GitHub: altinn-access-groups#6](https://github.com/Altinn/altinn-access-groups/issues/6)):

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

Selve overgangen er altså trinnvis, ikke ett tidspunkt: nye
tilgangspakker har kunnet settes på tjenester siden Q4 2024, men de
*gamle* Altinn 2-rollene fortsetter å fungere parallelt fram til
Altinn 2 slukkes i juni 2026. Først da bytter policyene på
tjenestene over til å bare henvise til nye tilgangspakker, og først
da mister ansatte tilgang gjennom de gamle Altinn 2-rollene
(`489b3d4dc4d9`).

**De mest betydningsfulle endringene for tjenesteeiere** (sammensatt
fra `7b5e7a7429fb` og `8e71d9016de9`):

1. **Konseptbytte: roller → ansvarsområder.** Der dagens Altinn 2
   gir tilgang via *roller*, gir Altinn 3 tilgang via
   *tilgangspakker* — bunter av tjenester som hører til et
   ansvarsområde (`7b5e7a7429fb`).

2. **Policyene må oppdateres, men ikke umiddelbart.** Apper på
   Altinn 3 må få sine policyer utvidet til å inkludere nye
   tilgangspakker *i tillegg til* de gamle Altinn-rollene som
   allerede står der. De gamle rollene kan stå inntil Altinn 2
   fases ut, så lenge tilgangspakke(r) er lagt til (Q&A pkt. 8).

3. **Flere tilgangspakker per tjeneste er mulig.** Tjenester som
   spenner flere fagområder kan knyttes til flere tilgangspakker
   samtidig (Q&A pkt. 10).

4. **Enkeltrettigheter forsvinner *ikke*.** Direkte delegering på
   tjenestenivå fortsetter å fungere uavhengig av policy-endringer.
   Alle enkeltrettigheter virksomheten har gitt ansatte i Altinn 2
   flyttes med lenketjenesten til Altinn 3 (Q&A pkt. 9).

5. **ER-roller knyttes til tilgangspakker i stedet.** Roller i
   Enhetsregisteret beholdes, men kobles mot nye tilgangspakker
   etter at høringsrunden er ferdig (Q&A pkt. 3).

6. **Brukerflaten for fullmaktstyring blir ny i Q1 2025.** Først
   etter den lanseringen kan virksomhetenes administratorer
   faktisk *tildele* nye tilgangspakker til ansatte. Inntil da er
   tilgangspakkene "på plass" på tjenestene men ikke i daglig
   bruk (`489b3d4dc4d9`).

7. **Egen fase for innbyggere kommer senere.** Tilgangspakker for
   privatpersoner (som erstatter Altinn 2-roller for innbyggere)
   er fase 2, basert på den granuleringen som er gjort for vergemål
   (Q&A pkt. 4).

8. **Veiledning kommer for å velge riktig tilgangspakke.** Altinn
   lover å publisere veiledning slik at tjenesteeiere kan
   identifisere passende tilgangspakke(r) for sine tjenester når
   tjenester migreres eller lages nye (Q&A pkt. 6).

**Praktisk implikasjon for tjenesteeiere akkurat nå (Q2 2026 er
inneværende kvartal)**: dette er kvartalet der Altinn 2 faktisk
slås av. Tjenester som ennå ikke har fått tilgangspakker tildelt i
policy vil miste de gamle rollebaserte tilgangene når Altinn 2 går
ned i juni. Det er en hard deadline ifølge tidsplanen i dokumentet,
men sjekk GitHub-issuen for siste status.

## Self-assessment

**What was easy**: parallel title-and-chunk search (the lesson from
Q3) immediately paid off. Title hits = 0, chunk hits = 3/135 — the
chunk signal directly pointed at the right migration doc on the
second tool call. Total tool budget = 5.

**What surprised me**: **the indexed `title` field is derived from
the URL path, not from the frontmatter.** The doc's frontmatter
title is the descriptive Norwegian "Innføring av nye tilgangspakker
som erstatning for dagens Altinn 2 roller" — the most natural
title-search match for this question. But the indexed `title` is
"New accessgroups" (path-segment-cased), so a user querying
"tilgangspakker" or "Altinn-roller" gets zero title hits. The
frontmatter title only appears in chunk *content*.

This explains a lot of Q3's pain too. **It's a corpus-shape finding
worth surfacing**: the indexer should consider preferring the
frontmatter `title` over the URL-derived one, or indexing both as
`title` + `linktitle` (the frontmatter actually has both fields —
chunk 0 of this doc shows `linktitle: Nye tilgangspakker` which is
*exactly* what users would type).

**What would surprise an automated retrieval system**:

1. **The URL says `/en/`, the content is `/nb/`.** Any retrieval
   strategy that filters or sorts by URL language prefix would
   miss this doc for an NB query and might miss it for an EN
   query too (since the content vocabulary is Norwegian). The
   doc is essentially mis-shelved language-wise.
2. **`Q2 2026` requires interpreting "now".** The temporal answer
   ("når") hinges on knowing which quarter we're in. The doc's
   `lastmod` is 2025-09-29 and the today-date (2026-05-26)
   indicates Q2 2026 is *this quarter*. An automated retrieval
   system has no inherent reason to surface the urgency of the
   answer unless the agent layer interprets dates relative to
   "today". Q4's answer should arguably *foreground* the Q2-2026
   timing because it's actionable right now.
3. **The 11-item Q&A pattern.** Chunk 2 is one long FAQ. A
   chunk-by-chunk retrieval system that picks the highest-scoring
   chunk for "betydningsfulle endringer for tjenesteeiere" might
   pick chunk 2 (the FAQ has high token overlap) and miss the
   timeline in chunk 1. Or vice versa. A complete answer needs
   both.
4. **The doc explicitly defers to a GitHub issue for current
   truth.** "Tidsplan holdes oppdatert" on the linked issue. An
   automated retrieval grounded *only* in the corpus would
   confidently quote dates that may have slipped. Honest answer
   should always flag the GitHub-issue caveat.

**Predicted gap on Q4**: small for finding *some* answer; large for
finding a *correctly framed* answer. The right doc is easy to
locate; the right *framing* (timeline + Q&A, with "right now is
Q2 2026" salience and GitHub-issue caveat) requires composing
multiple chunks and interpreting them against the current date.
