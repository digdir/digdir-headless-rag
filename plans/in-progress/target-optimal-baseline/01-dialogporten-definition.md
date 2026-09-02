# Q1 — What is Dialogporten and what problem does it solve?

**Category**: Definitional
**Expected locality**: single chunk (one overview/explanation doc)
**Retrieval calls used**: 2 (well under the 10-call budget)

## Strategy (written before any tool call)

Definitional question. Plan: search docs by `title` for "Dialogporten"
to find an overview/landing page, pick the most "what is X" looking
candidate (URL/title cues), fetch its chunks. If "what is" and
"problem solved" live in different docs, fetch a second.

## Trail

### Tool 1 — `ts-search docs title:"Dialogporten"` → 9 hits

```
bb ts-search digdir public-docs docs "Dialogporten" --query-by title --limit 15
```

| doc_num | title | url | chunks |
|---|---|---|---|
| `856c493c6637` | Dialogporten | `/en/api/dialogporten/index.md` | 1 |
| `9f8e5e3a4868` | Dialogporten | `/en/dialogporten/index.md` | 1 |
| `2a7a456f5569` | Dialogporten | `/nb/authorization/guides/system-vendor/system-user/dialogporten/index.md` | 9 |
| `3023558b0ee9` | Dialogporten | `/nb/api/dialogporten/index.md` | 1 |
| `25c6442790e3` | Dialogporten | `/nb/dialogporten/index.md` | 1 |
| `405e5c9efcc6` | Dialogporten arbeidsflate | `/en/correspondence/.../dialogporten-arbeidsflate/` | 2 |
| **`9a5fc194a710`** | **About dialogporten** | **`/en/dialogporten/about-dialogporten/index.md`** | **5** |
| `eaa7903e19e2` | Dialogporten arbeidsflate | `/nb/correspondence/.../dialogporten-arbeidsflate/` | 2 |
| `b5bf7b3760d7` | About dialogporten | `/nb/dialogporten/about-dialogporten/index.md` | 5 |

Picked `9a5fc194a710` — title is literally "About dialogporten",
under `/dialogporten/` (the canonical Dialogporten root, not a guide
or API-spec subtree), 5 chunks (substantive but not a full guide).
The NB variant exists too; chose EN because the question is in English.

### Tool 2 — `ts-get chunks range=9a5fc194a710:0-4` → 5 chunks

```
bb ts-get digdir public-docs chunks --range 9a5fc194a710:0-4 \
  --include-fields chunk_id,chunk_index,content_markdown
```

Chunk 0 had Hugo frontmatter that included
`diataxis: diataxis_explanation` — the corpus uses the Diátaxis
docs framework to tag explanation vs how-to vs reference, which is
itself a useful retrieval-bias signal.

## Cited chunks

- `965b3916f9e2` (chunk 0 of `about-dialogporten/index.md`) —
  definition + primary goal
- `a233d1c22ebe` (chunk 1) — what a "dialog" is, structurally
- `75962c2f79c6` (chunk 2) — how it operates (read-only,
  content-referenced model)

## Answer

**Dialogporten** is a solution that serves as a common API and
metadata state store for digital dialogs. It does not host the
substantive content of a service (forms, body texts, attachments,
form data); those remain on the originating service platform and
are merely *referenced* from a dialog by URL. (`965b3916f9e2`,
`75962c2f79c6`)

A "dialog" in Dialogporten represents one particular instance of a
digital service and consists of a sender (usually a government
actor), a recipient (a citizen or organization), a reference to a
service definition in the Altinn Resource Registry, and presentation
content like title, summary, and activity history for the
end-user. (`a233d1c22ebe`)

**The problem it solves**: it standardizes how public service
platforms — Altinn being the primary one — communicate ongoing
digital dialogs to users, so that interoperability across different
systems is possible and a user can view and manage all their
in-progress interactions in one place (the "arbeidsflate",
previously called the inbox). (`965b3916f9e2`,
`75962c2f79c6`)

Architecturally Dialogporten is **read-only for end users**: users
can see dialog state and metadata, but writes are performed by the
service provider (e.g. Altinn) on behalf of the service owner, in
sync with the user's interaction with the actual service. This
preserves a single source of truth and keeps data consistent and
secure across systems. (`75962c2f79c6`)

## Self-assessment

**What was easy**: a perfectly-titled "About dialogporten" doc with
`diataxis: diataxis_explanation` frontmatter existed exactly where
intuition said it should (under `/dialogporten/about-dialogporten/`).
Chunk 0 of that doc contains both the definitional sentence
("Dialogporten is a solution which serves as...") AND the
problem-statement sentence ("The primary goal of Dialogporten is to
facilitate..."). One chunk could have answered the question
adequately; reading chunks 1–2 added structural understanding cheap.

**What was hard**: nothing. 2 tool calls.

**What would surprise an automated retrieval system**:

1. **Picking the right "Dialogporten" doc among 9.** A BM25 search on
   "Dialogporten" matches all 9 doc-title hits roughly equally.
   The 5-chunk `about-dialogporten/` page is the *right* source for a
   definitional question, but the top-level `/dialogporten/index.md`
   (1 chunk) and the `/api/dialogporten/index.md` (1 chunk) would
   probably tie on title-match score. The cue I used — "About"
   in the title, `/dialogporten/` as the path root — is
   url/title-pattern reasoning, not a retrieval feature.
2. **The `diataxis` frontmatter.** It's right there in the chunk
   content but the chunks-collection schema has no `diataxis`
   facet/index. If it were promoted to a sortable field, automated
   retrieval could bias toward `explanation` docs for "what is X"
   questions and toward `how-to` docs for "how do I X" questions,
   which would help Q1 and Q2/Q3 differently.
3. **Avoiding the NB doppelganger** (`b5bf7b3760d7`,
   `/nb/dialogporten/about-dialogporten/`) when the question is in
   English. The corpus is bilingual; English and Norwegian
   counterparts have the same `title`. Without a `language` field
   to filter on, an automated retrieval system might surface both
   and either pick one arbitrarily or interleave. I used URL prefix
   as the language cue.

**Predicted gap**: this question is the easiest to answer optimally
*and* the easiest for automated retrieval to bungle the source
selection. The gap on Q1 is probably small but instructive: the
right *answer chunk* is high in any BM25 ranking, but the right
*source doc* requires URL/title pattern reasoning.
