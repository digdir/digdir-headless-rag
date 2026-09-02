# Q3 v3 — Hvordan setter jeg opp autentisering for en Altinn-app i utviklingsmiljøet?

**Category**: Procedural (NB)
**v1 calls**: **14** (over budget)
**v2 calls**: 5
**v3 calls**: 5

## Strategy (unchanged from v2)

Use `linktitle` + `frontmatter_title` indexed fields with
`diataxis` + `language` facets. Question spans reference
(auth config) + how-to-guide (local dev), so the trail needs at
least two distinct searches.

## Trail

### Tool 1 — `diataxis:=how-to-guides && language:=nb` only

```
bb ts-search digdir public-docs docs "autentisering" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=how-to-guides && language:=nb' \
  --limit 8
```

**1 hit / 1 found** — `c90b2e25cfc0` "Authenticating" at
`/nb/dialogporten/user-guides/authenticating/`. **Wrong scope**
(Dialogporten user-auth, not Altinn-app auth). Same as v2.

### Tool 2 — Broaden to include `reference`

```
bb ts-search digdir public-docs docs "autentisering" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=[how-to-guides,reference] && language:=nb' \
  --limit 8
```

**5 hits**, including `fefc9064b271` at
`/nb/altinn-studio/v8/reference/configuration/authentication/`.
This is the app-side auth-config reference doc.

### Tool 3 — Find the local-dev guide by `linktitle`

```
bb ts-search digdir public-docs docs "Lokal utvikling" \
  --query-by linktitle \
  --filter-by 'language:=nb && diataxis:=how-to-guides' \
  --limit 5
```

**1 hit / 1 found**: `e465a3646506` at
`/nb/altinn-studio/v8/guides/development/local-dev/`.

### Tools 4–5 — Range-fetch both docs' chunks

```
bb ts-get digdir public-docs chunks --range fefc9064b271:0-0 ...
bb ts-get digdir public-docs chunks --range e465a3646506:0-1 ...
```

Both return exactly the intended chunk count (no duplicates).
Cited chunks: `89698907c227` (auth config) and `a3a3d87967da`
(local-dev → LocalTest + testbrukere).

## Cited chunks

- `89698907c227` — `/nb/altinn-studio/v8/reference/configuration/authentication/`
  chunk 0 (app-side auth declaration)
- `a3a3d87967da` — `/nb/altinn-studio/v8/guides/development/local-dev/`
  chunk 1 (LocalTest + testbrukere)

## Answer

(Substantively identical to v1/v2.)

Å sette opp autentisering for en Altinn-app i utviklingsmiljøet er
todelt:

**1. Appens egne autentiseringskrav** (`89698907c227`)

- Påkrevd autentiseringsnivå settes som obligation i XACML-policy.
- Standard ID-provider er ID-porten / Altinn-pålogging.
- Alternativ OIDC-provider (FEIDE eller UIDP) settes i
  `appSettings`:
  ```json
  "AppOidcProvider": "uidp"
  ```

**2. Selve utviklingsmiljøet** (`a3a3d87967da`)

- Bruk **LocalTest** — lokal mockup av Altinn Plattform — fra
  [Altinn/app-localtest](https://github.com/Altinn/app-localtest/blob/master/README.md).
- Start appen med `dotnet run` (eller `dotnet watch` for hot
  reload).
- Logg inn på `http://local.altinn.cloud` med en *testbruker*.

## Self-assessment vs v2

**What stayed the same**: 5 calls, identical trail, identical
cited chunks. The NB linktitle indexing solved this question
in v2; v3 has nothing left to improve here at the docs/chunks
level.

**What got slightly cleaner**: range fetches return one chunk
per index (no orphan duplicates).

**The remaining cost**: Tool 1 returning the wrong-scope doc.
The trail still needs to recognize "this auth question spans
reference *and* how-to-guides" — a single-shot retrieval can't
make that call. With a stricter diataxis filter the reference
doc is missed; with a broader filter Tool 1 already finds both
(but at the cost of more results to scan).

## Predicted gap on Q3 v3

Same as v2. The win came from `linktitle` indexing; phrase-corpus
health doesn't shift this question's call count because the
bottleneck is *strategy* (knowing to span reference+how-to),
not chunk surfacing. The right next-iteration lever is
multi-shot query-rewriting that learns to broaden the diataxis
filter when the first probe returns a wrong-scope hit.
