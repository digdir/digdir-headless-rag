# Q3 v2 — Hvordan setter jeg opp autentisering for en Altinn-app i utviklingsmiljøet?

**Category**: Procedural (NB)
**v1 calls**: **14** (worst question; over budget)
**v2 calls**: **5** (64% reduction; back under budget)

## Strategy (v2)

v1 was the canary for the NB-title-search problem: `--query-by
title "autentisering"` returned 0 hits because the URL-derived
`title` field had no Norwegian terms. Three title-search duds
and several chunk-content fallbacks cost 14 calls.

v2 strategy: use the new `linktitle` and `frontmatter_title`
indexed fields (where the actual Norwegian terms live) plus the
`diataxis` and `language` facets to narrow precisely.

## Trail

### Tool 1 — `diataxis:=how-to-guides && language:=nb` only

```
bb ts-search digdir public-docs docs "autentisering" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=how-to-guides && language:=nb' \
  --limit 8
```

**1 hit / 1 found** — but the wrong doc: `c90b2e25cfc0`
"Authenticating" at `/nb/dialogporten/user-guides/authenticating/`.
That's Dialogporten's user authentication, not Altinn-app auth.

### Tool 2 — Broadened to include `reference` docs

```
bb ts-search digdir public-docs docs "autentisering" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=[how-to-guides,reference] && language:=nb' \
  --limit 8
```

**5 hits**, including **`fefc9064b271`** —
`/nb/altinn-studio/v8/reference/configuration/authentication/`.
This is the app-side auth-config reference doc. Same doc v1
found after 9 tool calls.

### Tool 3 — Find the local-dev guide by its linktitle

```
bb ts-search digdir public-docs docs "Lokal utvikling" \
  --query-by linktitle \
  --filter-by 'language:=nb && diataxis:=how-to-guides' \
  --limit 5
```

**1 hit / 1 found**: `e465a3646506` at
`/nb/altinn-studio/v8/guides/development/local-dev/`. Same doc v1
took its 5th tool call to find (via the chunk-content search for
"lokal utvikling autentisering" → 5 chunks → look up parent docs).
v2 finds it directly with `linktitle` because the frontmatter
`linktitle: Lokal utvikling` is now indexed.

### Tools 4–5 — Range-fetch both docs' chunks

```
bb ts-get digdir public-docs chunks --range fefc9064b271:0-0 ...
bb ts-get digdir public-docs chunks --range e465a3646506:0-1 ...
```

Same content as v1 (these particular docs weren't re-revised
between ingests, so chunk_ids are identical: `89698907c227` and
`a3a3d87967da`).

## Cited chunks

- `89698907c227` — `/nb/altinn-studio/v8/reference/configuration/authentication/`
  chunk 0 (app-side auth declaration)
- `a3a3d87967da` — `/nb/altinn-studio/v8/guides/development/local-dev/`
  chunk 1 (LocalTest + testbrukere)

## Answer

(Substantively identical to v1; corpus content unchanged for
these docs.)

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

## Self-assessment vs v1

**What got dramatically better**:
- **The NB title-search problem is fully resolved.** v1 had three
  title-only searches that returned 0 hits each ("autentisering",
  "innlogging", "testbrukere") because those terms don't appear in
  the URL-derived `title` field. v2's first probe finds something
  on the first try (even if it's the wrong doc), and the second
  probe finds the reference doc directly. The third probe finds
  the local-dev doc by its `linktitle: Lokal utvikling` — exactly
  the user-natural form.
- **The `diataxis` facet is the right lever.** "Auth config" is
  a *reference* doc; "local dev" is a *how-to-guide*. Filtering
  by diataxis biases the search appropriately for each half of
  the answer.

**What didn't improve**:
- **Recognizing the answer needs two docs.** Even with the new
  fields, the answer-shape (reference + how-to) is a judgment
  call that retrieval can't make for you. v2 needed two
  independent searches because the diataxis split is real.
- **First probe needed refinement** (Tool 1 returned the wrong
  Dialogporten doc; Tool 2 broadened the filter). This is the
  cost of being precise: tightening the diataxis filter
  to a single value cut out the reference doc I needed.

**What got worse**: nothing material.

## Predicted gap on Q3 v2

The retrieval gap *narrowed considerably*. The remaining work is
strategic: knowing to broaden the diataxis filter, and knowing to
search for the local-dev guide by its NB linktitle. Both are
judgment calls a single-pass retrieval system can't make in one
query — they require either (a) multi-shot retrieval with
query-rewriting between shots, or (b) a query-classifier that
recognizes "this question spans both reference and how-to."
