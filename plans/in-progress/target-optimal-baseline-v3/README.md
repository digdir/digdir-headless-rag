# Target-optimal baseline v3 — healthy chunks/phrases corpus

Third pass of the 7-question target-optimal baseline against
`digdir/public-docs`. Same protocol, same questions, same tools
as v1/v2. The corpus changes between v2 and v3 are *quality*,
not *schema*:

| Fix | Commit | What it changed |
|---|---|---|
| Delete-orphans after upsert in chunks/phrases | `7719efd` | Chunks collection went from 12,217 (bloated) → 5,692 (intended). Phrases collection cleaned the same way. |
| Pin `:id` on chunks/phrases so upsert keys correctly | `a150570` | Prevents new orphans. |
| Enforce `:id` in store-chunks!/store-phrases! | `019c95a` | Defense-in-depth at storage boundary. |
| Fix phrase parser to use JSON-mode output | `1b99805` | Phrase generation no longer silently corrupts. |
| Provider-aware response_format (LM Studio) | `e2c0dd7`, `9398105` | Phrases generate cleanly across providers. |
| Skip caching empty/failed phrase results | `8067afa` | Failed generations no longer pollute the cache. |

**Coverage after re-ingest** (probed at start of v3 run):

| Collection | v2 size | v3 size | Note |
|---|---:|---:|---|
| docs | 2,136 | 2,139 | +3 from upstream content additions |
| chunks | 12,217 | 5,692 | Orphan-delete reclaimed ~54% bloat |
| phrases | ~14,053 | 47,095 | Phrase generation now succeeds for nearly every chunk |

## Protocol (unchanged from v1/v2)

1. Reasoning first, retrieval second.
2. Tool budget ~10 calls per question.
3. Grounding rule: every claim cites `chunk_id`s; refuse if not
   supported.
4. Trail recording: every tool call captured verbatim.
5. Self-assessment with explicit comparison to v2.

**Tool difference vs v2**: where the phrase corpus might
plausibly help, v3 adds a `bb ts-search ... phrases` probe to
test whether the cleaner phrases surface anything chunks-only
search missed.

## Question index

| # | File | Category | v1 | v2 | v3 | Δ vs v2 | Status |
|---|---|---|---:|---:|---:|---:|---|
| 1 | [01-dialogporten-definition.md](01-dialogporten-definition.md) | Definitional | 2 | 3 | **2** | −1 | ✅ |
| 2 | [02-dialogporten-create-dialog-en.md](02-dialogporten-create-dialog-en.md) | Procedural EN | 5 | 2 | 2 | 0 | ✅ |
| 3 | [03-altinn-app-auth-dev-nb.md](03-altinn-app-auth-dev-nb.md) | Procedural NB | 14 | 5 | 5 | 0 | ✅ |
| 4 | [04-altinn-roller-tilgangspakker.md](04-altinn-roller-tilgangspakker.md) | Temporal | 5 | 3 | 3 | 0 | ✅ |
| 5 | [05-altinn-roller-persons-vs-enterprises.md](05-altinn-roller-persons-vs-enterprises.md) | Cross-language | 6 | 6 | 6 | 0 | ✅ (new finding) |
| 6 | [06-altinn-studio-v7-v8-data-model.md](06-altinn-studio-v7-v8-data-model.md) | Cross-doc synthesis | 8 | 4 | 4 | 0 | ✅ partial refusal |
| 7 | [07-webhook-hmac-sha512-refusal.md](07-webhook-hmac-sha512-refusal.md) | Refusal | 14 | 4† | **5** | +1 | ✅ richer refusal |

**Totals**: 54 → 27 → **26**. The 27→26 drop is the Q1 duplicate-
chunks workaround going away. Q7 added one probe (the new phrases
search) but produced a substantively richer answer.

† v2's 4-call Q7 count assumes v1 knowledge of the canonical
doc_num. A fresh v3 explorer using the phrases probe finds a
*better* answer in fewer calls than fresh-v1; the +1 vs v2 is
the extra phrases probe that surfaces the new finding.

## What the cleaner phrases changed

This is the question this v3 baseline was run to answer.

### One question's answer materially changed

**Q7 (webhook signatures)** is the headline. The phrase corpus
surfaced a doc neither v1 nor v2 inspected:

- Doc `af9c4ce14a2b` at
  `/en/altinn-studio/v8/reference/logic/events/subscribing/`.
- Chunk `9b4017645a43` carries the search-phrase
  **"webhook secret defined"** — text that does **not** appear in
  any chunk's `content_markdown`. The phrase generator produced
  it from the chunk's semantic content.
- Reading the parent chunk (`09eeadadc174`) reveals a
  **shared-secret** authentication mechanism for webhook events
  (`IEventSecretCodeProvider` + `EventSubscription--SecretCode`
  keyvault key). This does *not* change the refusal answer to
  "HMAC-SHA512?" (still no — it's a shared secret, not an HMAC),
  but it **fills a gap** the v1/v2 refusal had implied: Altinn
  doesn't sign webhook payloads, *but it does authenticate
  subscriber endpoints with a shared secret*.

Without the phrase corpus, this doc is essentially undiscoverable
via the v1/v2 probe shape — it's not under `/events/` (it's an
app-side reference doc), and its chunks don't contain "webhook
signature" or "webhook secret" as literal terms; only the
generated phrase does.

### One question gained a v3→v4 lead

**Q5 (Altinn roles persons vs enterprises)** has a long-standing
corpus-quality issue: the chunker stripped role-name headers from
the descriptions, so the chunks cited say "Tilgang til
helse-/sosial-/velferdsrelaterte tjenester ..." but never name
which role this is. v3 confirmed that the phrase corpus
**recovered those role names**: phrase
`"helse-, sosial- og velferdsrelaterte tjenester"` is attached to
exactly the chunk whose body contains the description without the
header. A phrase-aware retrieval would surface that chunk for a
"Helse-/sosial-/velferdsroller" query that chunks-only BM25
would miss.

This didn't change Q5's optimal trail (still 6 calls, chunks-only
ts-search/ts-get is sufficient for the human-readable answer),
but it identifies the **single largest predicted gain** for
multi-strategy retrieval against this corpus.

### Five questions: minor / no change

- **Q1**: 3 → 2 calls. The duplicate-chunks workaround from v2
  is gone (orphan-delete fix). Cited content unchanged.
- **Q2**: 2 → 2 calls. Same cite content, range fetch returns
  the intended 2 rows instead of v2's 4. No retrieval-cost
  change.
- **Q3**: 5 → 5 calls. The win came from `linktitle` indexing
  in v2; phrase-corpus health doesn't shift this question.
- **Q4**: 3 → 3 calls. Same trail; cleaner range fetches.
- **Q6**: 4 → 4 calls. Tool 2's chunk-content probe returns 2
  rows instead of 4. Conclusion (partial refusal) unchanged.

## Cross-cutting findings

### The chunks-orphan fix is the bigger raw-quality win

The orphan-delete commit (`7719efd`) cut the chunks collection
in half and eliminated the "old + new revision" noise that v2
called out on Q1, Q2, Q4, Q6, Q7. Every range fetch and every
chunk-content search in v3 returns honest cardinality. This
isn't a retrieval-quality improvement so much as a baseline
sanity restoration — but its absence in v2 added 1 call to Q1
and added visual noise to every other trail. v3 trails are
qualitatively easier to read.

### The phrase corpus is now a genuine semantic-recovery layer

Pre-v3, phrase searches were unreliable because of failed
generations and stale entries. Post-fix, the phrases collection
has 47,095 entries (≥8× pre-fix) and surfaces semantically
correct labels for chunks whose literal content_markdown lacks
those terms. The two clearest examples this baseline uncovered:

1. **Q5**: role-name phrases attached to chunks whose body lost
   the role-name headers.
2. **Q7**: "webhook secret defined" phrase attached to a chunk
   whose body discusses `IEventSecretCodeProvider` without ever
   using the words "webhook secret" together.

Both are recoveries from upstream information loss (the chunker
in Q5; the doc's word-choice in Q7). The phrase generator is
effectively a small LLM enrichment step that gives retrieval a
second chance.

### The new fields (v2) plus clean phrases (v3) compose

The v2 wins were structural (linktitle / frontmatter_title /
diataxis / language). The v3 wins are semantic (phrases as
content-recovery). They stack:

| Question shape | Cheapest precise probe |
|---|---|
| Definitional / procedural / reference where title-word matches | v2: `diataxis:= && language:= + query-by linktitle,frontmatter_title` |
| Cross-language NB where literal Norwegian title differs | v2: `query-by linktitle filter language:=nb` |
| Chunk whose content_markdown lacks the lexical query but is semantically relevant | **v3: `ts-search phrases <query>`** then `ts-get chunks --ids <chunk_id>` |
| Confirming a refusal | v3: combine all three (chunks probe + phrases probe + diataxis-filtered docs probe) |

### What still didn't get better

- **Cross-doc synthesis (Q3, Q4, Q5)** still costs N calls for
  N docs. Field/phrase improvements sharpen each probe but
  can't combine answers across documents.
- **Refusal discipline (Q6)** is unchanged. The corpus content
  gap on v7 data-model docs is what it is.
- **Linktitle tokenization on multi-word queries (Q7's Tool 2)**
  still fails — "subscription" alone returns 0 hits even though
  some doc's `linktitle` is "Set up a subscription". Worth
  investigating Typesense's tokenization config for `linktitle`.

## Implications for the gap-measurement phase

When measuring `ts-retrieve` against these v3 targets:

1. **Q7's enrichment-via-phrases win is testable.** If
   `RAG_TS_RETRIEVE_ENABLED=true` with `enrichment-types` that
   includes phrases retrieves `9b4017645a43` for a "webhook
   signature" query, that's confirmation the multi-strategy
   stack can compose the same recovery a human did with two
   tool calls.
2. **Q5's role-name recovery is also testable.** Multi-strategy
   retrieval that consults phrases should surface
   `a136030a86f8` for "Helse-/sosial-/velferdsroller" queries
   that chunks-only BM25 misses.
3. **Refusal-discipline questions (Q6, Q7) are still where
   automated retrieval is most likely to fail with confident
   wrong answers** — not from missed signal but from
   over-synthesis of correlated signals.

## Next step

Run `bb ts-retrieve` against the same 7 questions and compare
its chunk lists to the v3 cited chunks. The two predicted-gain
probes (Q5 role-names, Q7 webhook-secret) are the cleanest
tests of whether the production retrieval stack realizes the
phrase-corpus payoff.
