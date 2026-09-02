# Q2 — How do I create a new dialog as a service owner in Dialogporten?

**Category**: Procedural (EN)
**Expected locality**: 2–6 chunks of one how-to-guide doc
**Retrieval calls used**: 5 (1 search + 2 ranges + 1 in-doc search + 1 multi-id get)

## Strategy (before any tool call)

Procedural question. From Q1 I already noted
`/en/dialogporten/user-guides/service-owners/api-client/` exists,
and the corpus-shape probe surfaced a 20-chunk doc titled "Creating
dialogs" (`5f614bcedc12`) under `/user-guides/service-owners/`.
Plan: confirm with one title search; fetch the first 5 chunks of
that doc for orientation; if the procedure / required-fields aren't
clear, do an in-doc search for "POST" or "required".

## Trail

### Tool 1 — `ts-search docs title:"creating dialogs"` → 2 hits

```
bb ts-search digdir public-docs docs "creating dialogs" --query-by title --limit 5
```

EN doc: `5f614bcedc12`, 20 chunks. NB sibling
`c7b0859d47e2` exists with same shape. EN selected (question is EN).

### Tool 2 — `ts-get chunks range=5f614bcedc12:0-4` → first 5 chunks

```
bb ts-get digdir public-docs chunks --range 5f614bcedc12:0-4 \
  --include-fields chunk_id,chunk_index,content_markdown
```

| # | chunk_id | covers |
|---|---|---|
| 0 | `4ddea10ee77a` | Intro + frontmatter (`diataxis: diataxis_how-to-guides`); Altinn-Studio auto-creates dialogs as the easier path |
| 1 | `6dab51ee6a36` | **The actual 2-step procedure** + `serviceResource` URN format constraint |
| 2 | `ad51acfc1f3b` | Date fields: `createdAt`, `updatedAt`, `expiresAt`, `visibleFrom`, `dueAt` |
| 3 | `c6c20770ce43` | `title` content field; alt-title for high-security-level resources |
| 4 | `d8e1ec536d5a` | `summary` content field |

### Tool 3 — `ts-get chunks range=5f614bcedc12:5-9`

```
bb ts-get digdir public-docs chunks --range 5f614bcedc12:5-9 \
  --include-fields chunk_id,chunk_index,content_markdown
```

| # | chunk_id | covers |
|---|---|---|
| 5 | `3606a5393de1` | `additionalInfo` field (non-personal markdown) |
| 6 | `8b559ebfe64f` | `senderName`, `extendedStatus` |
| 7 | `2e82017c06df` | Front-channel embeds |
| 8 | `aca0976ff9f3` | Search tags |
| 9 | `af47330bd88c` | The `status` enum (NotApplicable, Draft, InProgress, Awaiting, RequiresAttention, Completed) |

### Tool 4 — in-doc search for "POST"

```
bb ts-search digdir public-docs chunks "POST" --query-by content_markdown \
  --filter-by 'doc_num:=5f614bcedc12' --limit 5
```

3 chunks: indices 1 (already read), 14, 19. Checked whether 14/19
contained an example payload or required-fields summary.

### Tool 5 — `ts-get chunks --ids ac03aa0e770f,7e454c5cfe5e`

```
bb ts-get digdir public-docs chunks --ids ac03aa0e770f,7e454c5cfe5e \
  --include-fields chunk_id,chunk_index,content_markdown
```

Both off-topic for the basic-creation procedure:
- chunk 14 (`ac03aa0e770f`) — `isDeleteAction` flag and API actions
- chunk 19 (`7e454c5cfe5e`) — `?isSilentUpdate=true` for historical migrations

Stopped here — the doc delegates the exact field-by-field DTO shape
to a separate reference page (`/en/dialogporten/reference/entities/dialog#create-post`)
linked from chunk 1. That delegation is appropriate for a how-to
answer; chasing it would be a different question ("what is the
exact create-dialog payload shape?").

## Cited chunks

- `4ddea10ee77a` (chunk 0) — intro + Studio-auto-creation alternative
- `6dab51ee6a36` (chunk 1) — the 2-step procedure
- `ad51acfc1f3b` (chunk 2) — date fields
- `c6c20770ce43` (chunk 3) — title content field
- `d8e1ec536d5a` (chunk 4) — summary content field
- `af47330bd88c` (chunk 9) — status enum

## Answer

The corpus documents creating a dialog as a service-owner API call.
Before reaching for that API, note that **if you're using Altinn
Studio, dialogs are created automatically** for your app instances
unless you explicitly opt out (`4ddea10ee77a`). The procedure below
is for service owners using the direct API.

**Procedure** (per `6dab51ee6a36`):

1. **Authenticate as a service owner.** The auth steps are documented
   separately at `/en/dialogporten/user-guides/authenticating/`.
2. **POST a create-dialog DTO.** The exact request shape is at
   `/en/dialogporten/reference/entities/dialog#create-post`; the
   key constraints are:
   - `serviceResource` must reference a resource in the Altinn
     Resource Registry whose `hasCompententAuthority` matches your
     authenticated organization number. Format:
     `urn:altinn:resource:<identifier>`.
   - Resources of type `CorrespondenceService` are reserved for
     Altinn Correspondence and cannot be referenced from a
     dialog directly.

**Recommended fields to populate** (defaults aside):

- **Dates** (`ad51acfc1f3b`) — `createdAt` / `updatedAt` default to
  now if omitted; `expiresAt` and `visibleFrom` control end-user
  visibility windows; `dueAt` is a non-blocking due-date hint.
- **`title`** (`c6c20770ce43`) — the dialog headline. For
  high-security-level resources, supply an alternative non-sensitive
  title used when the viewer's authentication level is below the
  resource's policy.
- **`summary`** (`d8e1ec536d5a`) — short overview shown in
  search/list APIs; same alternative-summary rule for
  high-security-level resources.
- **`status`** (`af47330bd88c`) — one of `NotApplicable` (default),
  `Draft`, `InProgress`, `Awaiting`, `RequiresAttention`,
  `Completed`. End-user systems use this to organize the dialog
  list, so it's worth setting accurately.

**For the exact DTO field list**, the corpus links to the entity
reference at `/en/dialogporten/reference/entities/dialog#create-post`
and the OpenAPI spec at `/en/dialogporten/reference/openapi/`.

## Self-assessment

**What was easy**: a perfectly-titled "Creating dialogs" how-to guide
with `diataxis: diataxis_how-to-guides` frontmatter existed exactly
where the URL structure predicted (`/dialogporten/user-guides/service-owners/`).
Chunk 1 alone gave the procedure in two numbered steps.

**What was harder**: the doc is 20 chunks but the *procedure* is
chunk 1; chunks 2–19 are field-by-field elaboration. Recognizing
this structure (intro → procedure → field appendix) and reading
selectively kept the call count to 5. A naïve reader fetching all
20 chunks would still get the answer but would burn the budget
and risk noise.

**What would surprise an automated retrieval system**:

1. **Mixing on-doc and off-doc "POST" matches.** My in-doc search
   for "POST" (Tool 4) was scoped via `filter_by 'doc_num:=...'`.
   Without that scope, "POST" matches across the corpus would
   return many irrelevant chunks (every API guide mentions POST).
   Automated retrieval typically doesn't apply per-doc scoping
   like this mid-question.
2. **Recognising the procedure chunk vs the elaboration chunks.**
   Chunk 1 is the answer. Chunks 2–9 elaborate. An attribution
   model that picks the *highest scoring* chunk on "create dialog"
   might pick chunk 0 (which has "Creating dialogs" in the
   frontmatter title and "how you can use the service owner API
   to create dialogs" in body) instead of chunk 1 (which has the
   numbered steps but uses less of the literal query vocabulary).
   The frontmatter-rich chunk *looks* like a better match by
   BM25 but is actually pre-amble.
3. **Recognising delegation.** Chunk 1 ends with a link to the
   `[create dialog DTO]` reference. The answer's full DTO shape
   intentionally lives on a different page. An automated system
   that doesn't follow internal references would have to either
   (a) cite chunk 1 and accept that the user follows the link
   themselves, or (b) do a second retrieval pass cued by the
   reference target — neither of which is a standard agent
   behavior here.
4. **The Studio-auto-creation aside.** Chunk 0's info-box
   ("ℹ️ When using Altinn Studio, dialogs will be automatically
   created for you") is the *answer to a related question*
   ("do I even need to do this?"). A retrieval system focused on
   the literal "how do I create" wording would probably drop this,
   missing the most pragmatic up-front fact for a real user.

**Predicted gap on Q2**: moderate. The right doc is easy to find;
the right *single* chunk to cite (chunk 1) requires understanding
that a numbered list ≠ a stylistically elaborate "how to" sentence.
Automated retrieval that ranks chunks by lexical overlap with
"create dialog as service owner" will probably surface chunks 0
or 9 above chunk 1.
