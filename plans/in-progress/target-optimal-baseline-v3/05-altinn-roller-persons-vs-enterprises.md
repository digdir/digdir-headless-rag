# Q5 v3 — Hva er forskjellen mellom Altinn-roller for personer og virksomheter?

**Category**: Cross-language (NB query, mixed-language corpus)
**v1 calls**: 6
**v2 calls**: 6
**v3 calls**: 6

## Strategy (unchanged from v2)

Three NB linktitle probes (parent + persons + enterprises), then
range-fetch each doc's chunks.

## Trail

### Tool 1 — Three parallel linktitle probes

```
bb ts-search digdir public-docs docs "Altinn-roller"      --query-by linktitle --filter-by 'language:=nb'
bb ts-search digdir public-docs docs "personroller virksomhetsroller" --query-by linktitle --filter-by 'language:=nb'
bb ts-search digdir public-docs docs "Virksomhetsroller"  --query-by linktitle --filter-by 'language:=nb'
```

Each returns exactly 1 hit:

| Probe | Doc | URL |
|---|---|---|
| "Altinn-roller" | `110535984ef1` | `/nb/.../roles_altinn/` (1 chunk) |
| "personroller virksomhetsroller" | `b7841e9db901` | `/nb/.../altinn_roles_persons/` (2 chunks) |
| "Virksomhetsroller" | `e5a83fdead4d` | `/nb/.../altinn_roles_enterprices/` (2 chunks) |

### Tools 2–4 — Range-fetch each doc

```
bb ts-get digdir public-docs chunks --range 110535984ef1:0-0 ...
bb ts-get digdir public-docs chunks --range b7841e9db901:0-1 ...
bb ts-get digdir public-docs chunks --range e5a83fdead4d:0-1 ...
```

All three return exactly the intended chunk count. No duplicates.

## Cited chunks

- `75c3ec105b6e` (parent, "Roller fra Altinn") — high-level
  framing: two categories by who-delegates
- `36b260f5d51f`, `df90a3ca5a45` (persons) — descriptions
  ("Kan delegeres: ja" stripped of role-name headers — same
  chunker artifact as v1/v2)
- `66fb43dab201`, `a136030a86f8` (enterprises) — descriptions
  with the boilerplate "Ved regelverksendringer ..."

## Answer

(Substantively identical to v1/v2.)

Altinn-rollene deles i to hovedkategorier ut fra *hvem som
delegerer fullmakt*:

- **Personroller** (`36b260f5d51f`, `df90a3ca5a45`): en *person*
  gir en bruker tilgang til å handle på sine vegne. Eksempler:
  handle på vegne av en annen privatperson, skatteopplysninger om
  eget enkeltpersonsforetak, lese meldinger i meldingsboksen.
- **Virksomhetsroller** (`66fb43dab201`, `a136030a86f8`): en
  *virksomhet* gir en bruker tilgang til å handle på vegne av
  organisasjonen. Eksempler: handle på vegne av enheter/foretak,
  regnskap, signere økonomiske skjema, taushetsbelagt post,
  revisor-attestasjon på MVA-kompensasjon (RF-0009).

Mange *temaområder* (kommunale, energi/miljø, lønn, patent,
regnskap, samferdsel) finnes i begge — forskjellen ligger i *på
vegne av hvem* rollen gir tilgang.

**Unike virksomhetsroller**: helse-/sosial-/velferdstjenester,
Økokrim, taushetsbelagt post fra stat/kommune, revisor
MVA-attestasjon, "bred utvalg low-restriction"-rolle.

**Unike personroller**: vegne av annen privatperson
(vergemålsmønster), skatteopplysninger om eget
enkeltpersonsforetak.

**Viktig forbehold** (uendret fra v1/v2): chunkene inneholder
*beskrivelsene* av rollene, men *ikke* rollenavnene. Chunkeren
har løsrevet rolle-headere fra body. Dette er en
corpus-quality-issue, ikke en retrieval-issue, og forblir uløst
i v3.

**Kontekst** (per Q4): hele dette rolle-systemet er under
utfasing — Altinn 2-roller avvikles Q2 2026 (inneværende kvartal)
og erstattes av tilgangspakker.

## Self-assessment vs v2

**What stayed the same**: 6 calls, identical trail, identical
cited chunks.

**What did not improve**: the chunker's lost role-names problem
(persistent corpus-quality issue across all three baselines).
Phrase-corpus health doesn't address this — it's a chunking
artifact, not a retrieval signal.

## Predicted gap on Q5 v3

Unchanged from v2. The remaining gap is corpus-quality (lost
role-name headers in chunks); retrieval improvements can't fix
content the chunker discarded.

**One v3-specific finding** (probed during this baseline): with
phrases now clean and abundant (47k entries vs ~14k pre-fix),
the `phrases` collection *partially compensates* for the
chunker's lost role-name headers.

Example probe:

```
bb ts-search digdir public-docs phrases "Helse-/sosial-/velferdstjenester" --limit 3
→ hit: chunk_id=a136030a86f8 (enterprises chunk 1), search_phrase=
        "helse-, sosial- og velferdsrelaterte tjenester"
```

That same chunk `a136030a86f8` contains the description body
("Tilgang til helse-, sosial- og velferdsrelaterte tjenester...")
but the role-name heading was stripped by the chunker — and yet
the phrases collection has the role-name-equivalent phrase
attached to it. Phrase generation evidently has access to
source-doc context the chunker dropped.

**Implication**: a phrase-aware retrieval path (e.g.
`ts-retrieve`'s multi-strategy mode) would likely surface
`a136030a86f8` for a "Helse-/sosial-/velferdsroller" query even
though a pure chunk-content BM25 search wouldn't. This is the
single most visible payoff of the phrase-corpus fix: it gives
retrieval a way to recover from chunker information loss. This
is the right v4 follow-up — measure that payoff with
`ts-retrieve` against the same 7 questions.
