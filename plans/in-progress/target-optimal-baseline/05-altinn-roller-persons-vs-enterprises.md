# Q5 — Hva er forskjellen mellom Altinn-roller for personer og virksomheter?

**Category**: Cross-language (NB query, mixed-language corpus)
**Expected locality**: 1–2 chunks across NB/EN
**Actual locality**: 5 chunks across 3 NB docs (parent overview +
two siblings)
**Retrieval calls used**: 6

## Strategy

Cross-language probe. From corpus-shape characterization I noted
that `Altinn roles persons` and `Altinn roles enterprices` exist as
EN-titled docs. Question is NB. Plan: parallel-probe NB title +
EN title + NB chunk content to see which path actually finds the
docs.

## Trail

### Tool 1 — Three parallel probes

```
bb ts-search digdir public-docs docs "Altinn-roller" --query-by title --limit 5
bb ts-search digdir public-docs docs "altinn roles" --query-by title --limit 8
bb ts-search digdir public-docs chunks "forskjell personer virksomheter altinn-roller" \
  --query-by content_markdown --limit 5
```

Results:

- **NB title "Altinn-roller"**: 0 hits. The hyphenated noun isn't
  tokenized in any title. (Third NB-title 0-hit in this session.)
- **EN title "altinn roles"**: **8 hits**, with paired EN/NB
  versions for "persons", "enterprices" [sic], and "administration".
  Both NB siblings exist with the same English-cased title because
  the indexed `title` is derived from the URL path segment, not the
  frontmatter.
- **Chunk content for forskjell-personer-virksomheter-altinn-roller**:
  only 2 hits — a single chunk indexed twice (under two different
  doc_nums), at chunk_index 5 in both cases. Probably not the
  authoritative source for this question.

### Tool 2–4 — Three parallel `ts-get chunks` for the three NB docs

```
bb ts-get digdir public-docs chunks --range 110535984ef1:0-0 ...   # parent overview
bb ts-get digdir public-docs chunks --range b7841e9db901:0-1 ...   # persons
bb ts-get digdir public-docs chunks --range e5a83fdead4d:0-1 ...   # enterprises
```

Parent doc has 1 chunk that frames the split (overview);
each leaf doc has 2 chunks (frontmatter+intro, then role list).

**Discovery**: the role-list chunks contain role *descriptions*
("Beskrivelse: ... Kan delegeres: ja") and the regulatory
disclaimer for enterprise roles, **but the role NAMES are missing**.
The chunker split the content in a way that separated each role's
heading from its description. To know that a given description
belongs to (say) "Patentstyret" or "Energirelaterte tjenester",
you'd need to read the original HTML — the indexed chunk text
alone can't tell you.

## Cited chunks

- `75c3ec105b6e` (parent overview, `/nb/altinn-studio/v8/.../roles_altinn/`)
  — the conceptual split between person- and enterprise-roles
- `36b260f5d51f` + `df90a3ca5a45` (persons doc, both chunks)
- `66fb43dab201` + `a136030a86f8` (enterprises doc, both chunks)

## Answer

**Den prinsipielle forskjellen** (per `75c3ec105b6e`): Altinn-rollene
deles i to hovedkategorier ut fra *hvem som delegerer fullmakt*:

- **Personroller** brukes når en *person* gir en bruker tilgang til
  å handle på sine vegne. Eksempler i listen: rolle for å handle
  på vegne av en annen privatperson, skatteopplysninger om egen
  eller eget enkeltpersonsforetaks skatteforhold, lese meldinger
  i meldingsboksen, signere utvalgte skjema (`36b260f5d51f`,
  `df90a3ca5a45`).

- **Virksomhetsroller** brukes når en *virksomhet* (organisasjon /
  foretak) gir en bruker tilgang til å handle på vegne av
  organisasjonen. Eksempler: rolle for å handle på vegne av
  enheter/foretak, regnskapsrelaterte tjenester, signere
  økonomiske og forretningsmessige skjema, revisor-attestasjon på
  MVA-kompensasjon (RF-0009), tilgang til taushetsbelagt post fra
  stat og kommune (`66fb43dab201`, `a136030a86f8`).

I tillegg finnes en tredje, mindre relatert kategori:
**administrasjonsroller** for å administrere tilganger (lenket fra
overviewet men ikke lest her, på `.../altinn_roles_administration/`).

**Hva er felles** (sammenliknet på tvers av de to listene):
Mange *temaområder* finnes i begge — kommunale tjenester,
energi/miljø/klima, lønn og personal, patent/varemerke/design,
regnskap, samferdsel, primærnæring og næringsmidler, DiBK-godkjente
skjema, meldingsboks. Forskjellen er da *på vegne av hvem* rollen
gir tilgang, ikke *hvilket tema* den dekker.

**Hva er unikt for personroller** (i NB-personroller-dokumentet,
basert på beskrivelsene):

- Tilgang på vegne av en annen privatperson (vergemålsmønster)
- Skatteopplysninger om eget/eget enkeltpersonsforetaks
  skatteforhold
- En enklere/mer generisk signeringstilgang

**Hva er unikt for virksomhetsroller**:

- Helse-, sosial- og velferdsrelaterte tjenester
- Tilgang til tjenester fra Økokrim
- Taushetsbelagt post fra stat og kommune
- Revisor-attestasjon av MVA-kompensasjonsskjema (RF-0009)
- En generell "bred utvalg low-restriction"-rolle for skjema med
  mindre strenge autorisasjonskrav
- Deprecated/historiske roller som ikke lenger skal brukes
  (Havforskningsinstituttet algetestdata, Statens vegvesen
  transportløyvegarantist)

**Viktig forbehold** (gjelder hele svaret): chunkene jeg leste
*inneholder beskrivelsene av hver rolle, men ikke rollenavnene*.
Det betyr at jeg kan si hva slags rolle som finnes (hva den gir
tilgang til), men kan ikke navngi rollene med korrekt Altinn-navn
fra dokumentet alene. Den fullstendige navnet+beskrivelse-tabellen
finnes på den originale dokumentsiden
(`/nb/altinn-studio/v8/reference/configuration/authorization/guidelines_authorization/roles_and_rights/roles_altinn/altinn_roles_persons/`
for personer, tilsvarende for virksomheter).

**Kontekstuell merknad knyttet til Q4**: hele dette rolle-systemet
er under utfasing. Per
`/nb/authorization/migration/new-accessgroups/` planlegger Altinn å
avvikle disse rollene når Altinn 2 slukkes (Q2 2026, dvs.
inneværende kvartal), og erstatte dem med **tilgangspakker**. For
nye tjenester bør tjenesteeiere derfor *også* knytte til
tilgangspakker, ikke kun rollene beskrevet i disse dokumentene.

## Self-assessment

**What was easy**: paired EN/NB versions exist for every doc in
this subtree, and the URL-derived `title` field made the EN-titled
title-search ("altinn roles") cleanly surface all 6 + admin
variants. Reading 3 NB docs in three parallel `ts-get` calls gave
a complete picture in 6 total tool calls.

**What was hard**:

1. **The NB query for the title failed.** "Altinn-roller" returned
   0 title hits — the hyphenated compound noun isn't split into
   "altinn" + "roller" by Typesense's title-tokenization (or it is,
   but the title is the URL-derived "Roles altinn" / "Altinn roles
   persons" which has neither term). The EN-title fallback rescued
   me. A real NB-only user with no EN fluency would struggle.
2. **The chunker stripped the role NAMES.** Both leaf docs split
   their content such that role descriptions live in the chunk
   without the corresponding role names. The descriptions remain
   useful but the answer is *named-role-incomplete*. This is a
   genuine corpus-quality issue, not a retrieval issue.

**What surprised me**: chunk `df90a3ca5a45` (the persons role
descriptions) has a different ending punctuation pattern than
`a136030a86f8` (the enterprise role descriptions) — the latter
appends a standard disclaimer "Ved regelverksendringer eller
innføring av nye digitale tjenester kan det bli endringer i
tilganger som rollen gir." to *each* role. The persons doc has no
such disclaimer. This stylistic asymmetry is real signal about how
each set is governed — enterprise roles are more frequently
subject to regulatory drift, hence the per-role disclaimer.

**What would surprise an automated retrieval system**:

1. **Title-tokenization on hyphenated Norwegian.** Multi-strategy
   retrieval's *phrase* strategy works on the `phrases` collection
   which has 7,456 verified phrases — *if* "Altinn-roller" or
   "tilgangspakker" appears as a phrase, it'd surface. Most
   automated systems doing only chunks-content BM25 would mimic
   this failure mode.
2. **Recognizing the role-names-are-missing problem.** A retrieval
   system that confidently surfaces chunks of "Beskrivelse: ...
   Kan delegeres: ja" without flagging that role names are absent
   would produce answers that *seem* fluent but are unverifiable —
   user can't look up "the role that grants access to municipal
   services" because the role doesn't have a name in the answer.
   An honest answer flags this.
3. **The cross-doc reference back to Q4 (tilgangspakker).** This
   question's *true* answer in mid-2026 includes "and this whole
   model is being phased out, see tilgangspakker". A retrieval
   system focused on the literal question won't connect to the
   migration story unless it's running multi-doc synthesis. The
   parent overview (`75c3ec105b6e`) doesn't even mention
   tilgangspakker — I had to bring in Q4's findings to
   contextualize.

**Predicted gap on Q5**: moderate. Finding the right docs is easy
*if* the system can bridge the NB→EN title gap. Composing a useful
answer requires (a) handling the missing-role-names problem
gracefully and (b) connecting back to the tilgangspakker migration.
Both are honesty/judgment moves, not retrieval moves.
