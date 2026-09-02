# Q3 — Hvordan setter jeg opp autentisering for en Altinn-app i utviklingsmiljøet?

**Category**: Procedural (NB)
**Expected locality**: 2–6 chunks of one doc
**Actual locality**: 2 chunks across 2 distinct docs (the app-side
auth config reference and the local-dev how-to)
**Retrieval calls used**: 14 (4 over budget — this is itself the most
interesting finding for Q3)

## Strategy (before any tool call)

Procedural NB. Terms are Norwegian; expected to find docs under
`/nb/altinn-studio/` or `/nb/api/`. Plan: search docs by title for
"autentisering"; pick a result in the altinn-studio dev subtree;
drill in.

## Trail

### Tool 1 — `ts-search docs title:"autentisering"` → **0 hits**

```
bb ts-search digdir public-docs docs "autentisering" --query-by title --limit 15
```

Surprise: no doc titles contain "autentisering" at all. First sign
this question's vocabulary doesn't align cleanly with the corpus's
naming.

### Tool 2 — Three parallel probes

```
bb ts-search digdir public-docs docs "innlogging" --query-by title --limit 5
bb ts-search digdir public-docs docs "authentication" --query-by title --limit 5
bb ts-search digdir public-docs chunks "autentisering utviklingsmiljø" --query-by content_markdown --limit 5
```

- "innlogging" in titles: **0 hits**
- "authentication" in titles: 5 EN docs surfaced (incl.
  `/en/altinn-studio/v8/reference/configuration/authentication/`,
  1 chunk — a strong English candidate)
- Chunks for "autentisering utviklingsmiljø": 5 hits / 80 total
  matches, all in NB

### Tool 3 — `ts-get docs` for the 5 NB chunk hits → titles & URLs

Top NB candidates surfaced but none were obvious wins for
"Altinn-app in dev environment":
- `/nb/authorization/getting-started/enterpriseuser/` (general auth)
- `/nb/authorization/reference/architecture/accessmanagment/` (platform arch)
- `/nb/api/guides/appownerintegration/receivingdata/` (api integration)
- `/nb/authorization/getting-started/maskinportenclient/` (M2M auth)

These are about *platform* or *integration* auth, not app-developer
dev-environment auth.

### Tool 4 — `ts-get docs` for the truncated top result (1 doc fetched)

Doc was `/en/authorization/migration/api-migration/` — wrong
direction (EN, migration topic, not dev setup).

### Tool 5 — Two retried probes

```
bb ts-search digdir public-docs docs "authentication" --query-by "title,url" --filter-by 'url:/nb/altinn-studio/' --limit 5
bb ts-search digdir public-docs chunks "lokal utvikling autentisering" --query-by content_markdown --limit 5
```

- `filter-by 'url:/nb/altinn-studio/'`: **0 hits** (Typesense's
  `filter_by` doesn't do substring/prefix on string fields; it's
  equality only). Documented this in self-assessment.
- "lokal utvikling autentisering" chunks: 5/7 hits

### Tool 6 — `ts-get docs` for those 5 chunk hits → titles & URLs

Top result: `/nb/altinn-studio/v8/guides/development/local-dev/`
(doc `e465a3646506`, 2 chunks) — finally the dev-environment doc.
Also `/nb/altinn-studio/v8/about/`, `/nb/events/...`,
`/nb/altinn-studio/v8/guides/administration/monitor-and-instrument/`.

### Tool 7 — `ts-get chunks range=e465a3646506:0-1` → full local-dev doc

Title: **Lokal utvikling** (`diataxis: diataxis_how-to-guides`).

The doc is about cloning the repo, running LocalTest, syncing
changes via git — only briefly mentions authentication (Gitea
access token for `git clone`; "logg inn med en testbruker" linked
to `/nb/altinn-studio/v8/reference/testing/local/testusers/`).

Useful but not authoritative for the auth-config part of the
answer.

### Tool 8 — Two more probes

```
bb ts-search digdir public-docs docs "testbrukere" --query-by title --limit 5
bb ts-search digdir public-docs chunks "autentisering app konfigurasjon" --query-by content_markdown --limit 6
```

- "testbrukere" in titles: **0 hits** (third title-search dud)
- "autentisering app konfigurasjon" chunks: 3 hits

### Tool 9 — `ts-get docs` for the 2 most promising hits

- `fefc9064b271`: title "Authentication", URL
  `/nb/altinn-studio/v8/reference/configuration/authentication/`
  (1 chunk) — **the NB sibling of the EN auth-config reference**
  surfaced way back in Tool 2.
- `401bc7f6daf0`: Maskinporten getting-started — adjacent, not
  needed.

### Tool 10 — `ts-get chunks --ids 89698907c227`

The single chunk of the NB Konfigurasjon-av-autentisering reference.
Tells me how the app declares its auth requirements (auth level via
XACML policy, optional `AppOidcProvider` setting for FEIDE/UIDP).

## Cited chunks

- `89698907c227` (the 1 chunk of `/nb/altinn-studio/v8/reference/configuration/authentication/`)
  — app-side auth configuration
- `a3a3d87967da` (chunk 1 of `/nb/altinn-studio/v8/guides/development/local-dev/`)
  — local dev environment (LocalTest, testbrukere)

## Answer

Å sette opp autentisering for en Altinn-app i utviklingsmiljøet er
todelt: hva appen din selv erklærer som autentiseringskrav, og
hvordan det lokale utviklingsmiljøet faktisk autentiserer brukere.

**1. Appens egne autentiseringskrav** (`89698907c227`)

- *Påkrevd autentiseringsnivå* settes som en obligation i appens
  XACML-policy (`policy.xml`). Se
  `/nb/altinn-studio/v8/reference/configuration/authorization/`.
- *Standard ID-provider* er ID-porten / Altinn-pålogging. Appen
  trenger ingen ekstra konfigurasjon for å bruke denne.
- *Hvis du vil bruke en alternativ OIDC-provider* (kun FEIDE eller
  UIDP er godkjent i dag), settes dette i `appSettings`:
  ```json
  "AppOidcProvider": "uidp"
  ```

**2. Selve utviklingsmiljøet** (`a3a3d87967da`)

I lokal utvikling kjører du ikke mot reell ID-porten. I stedet:

- **LocalTest** er en lokal mockup av Altinn Plattform. Last ned
  og start den fra
  [Altinn/app-localtest på GitHub](https://github.com/Altinn/app-localtest/blob/master/README.md).
- Start appen din med `dotnet run` (eller `dotnet watch` for hot
  reload) i `<app-name>/App`-mappa.
- Logg inn på `http://local.altinn.cloud` med en *testbruker*. Listen
  over testbrukere er på
  `/nb/altinn-studio/v8/reference/testing/local/testusers/`
  (lenket fra Lokal-utvikling-guiden, ikke lest i denne sesjonen).

**Forutsetning**: appen må ha en tilknyttet datamodell før den kan
kjøres i LocalTest (se
`/nb/altinn-studio/v8/reference/data/data-modeling/`).

Det er altså ingen separat "sett opp autentisering for dev" — dev-
miljøet *mocker* autentiseringen for deg gjennom LocalTest, og
appens auth-erklæring (nivå, OIDC-provider) er den samme uansett om
appen kjører lokalt eller i produksjon.

## Self-assessment

**What was hard — and the biggest finding of the day**: this
question burned 14 calls (4 over budget) because **the user's
natural Norwegian vocabulary ("autentisering", "innlogging",
"testbrukere") doesn't appear in any doc title**. The title corpus
favors *English* terms ("Authentication") and *compound noun forms*
("Konfigurasjon av autentisering" — wouldn't surface for a literal
"autentisering" query because Typesense's BM25 ranks single-token
title matches lower than multi-token chunk matches). I had to fall
back to chunk-content search three separate times to find docs.

**What was easy**: once I'd identified the right two docs, both
were short (1 chunk and 2 chunks) and clearly authoritative.

**What surprised me**: the existence of `diataxis_reference` vs
`diataxis_how-to-guides` frontmatter tags. Chunk
`89698907c227` is tagged `diataxis_reference` — and that's the
right shape for the *app-side* part of the answer. Chunk
`a3a3d87967da` is `diataxis_how-to-guides` — right for the
*dev environment* part. The answer naturally splits along the
diátaxis-types of the source docs.

**What would surprise an automated retrieval system**:

1. **The vocabulary mismatch.** Multi-strategy retrieval (with the
   phrase strategy + auto-filter) might help: 50 of the website
   pipeline's chunks are enriched with `verified-phrases` that are
   topical extractions like "Altinn 3 juni 2020" — exactly the
   kind of compact Norwegian phrasing that fills the title-vocabulary
   gap. But only 10 chunks have those enrichments right now (per
   `corpus-shape-results.md`), and none of the chunks relevant to
   this question are in that 10. So the production multi-strategy
   path probably has the same vocabulary problem on this question
   as the bare BM25 path.
2. **Recognising the answer is in two docs, not one.** A retrieval
   system that picks one top-ranked doc and synthesizes from it
   would either get the *reference* answer (app config) or the
   *dev environment* answer (LocalTest), but not both. The split
   isn't artificial — it's how the corpus organizes the topic
   (reference vs how-to per diátaxis).
3. **The `filter_by 'url:/nb/altinn-studio/'` dead end**. Typesense's
   `filter_by` is equality-only on string fields; substring/prefix
   needs to be a search-side concern (`q="..."` + `query_by url`).
   But URL search tokenizes by slash, so `q="/nb/"` matches by
   token, not prefix, and ranks poorly. A genuinely effective
   "narrow to NB" filter would need a `language` field on docs
   that doesn't exist today, OR a regex-style filter (which
   Typesense doesn't have). Worth noting as corpus-shape feedback.
4. **Three title-searches returned 0 hits.** That's a strong signal
   that title-based retrieval is fragile for Norwegian queries
   against this corpus. An automated system biased to title hits
   would consistently miss on NB queries; one biased to chunk
   content (BM25 on full text) does better but needs to scope
   carefully to avoid drowning in tangential matches.

**Predicted gap on Q3**: large. The full answer requires (a)
recognizing that the title vocabulary doesn't match, (b) falling
back to chunk-content search, (c) following links to find the
sibling doc, and (d) composing across two diátaxis-types. Each is
a retrieval-strategy decision a one-shot automated retrieval pass
typically doesn't make.
