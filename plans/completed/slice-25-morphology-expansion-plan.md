# Slice 25 — Morphology-aware retrieval for verb-form mismatch

Second slice of the corpus-vocabulary arc. Motivated by Q2's
failure mode uncovered in the post-slice-22 investigation: the
right doc is findable by exact title but the planner's
expansion phrases use a *different morphological form*.

## The finding

Q2's v3-cited doc `5f614bcedc12` has `frontmatter_title="Creating
dialogs"` (gerund).

| Doc-title query | Top-1 result |
|---|---|
| `creating dialogs` | doc `5f614bcedc12` ✓ |
| `create dialog` | doc `fb8e1a835614` ("Create" — wrong product) ✗ |
| `create a new dialog` | (Altinn Studio UI form components) ✗ |

The planner emits `"create a new dialog"` /
`"create a new dialog as a service owner"` / etc. — all using the
*infinitive* "create". The corpus uses *gerund* "Creating".

This blocks the doc from entering the candidate pool at all. The
retrieval pool returned 159 candidates for Q2 in one observed run
and **none of them was `5f614bcedc12`** — the right doc never had
a chance at ColBERT rerank.

## Initial discovery task — is Typesense already doing stemming?

**Do this BEFORE any planner changes.** Typesense supports
per-field stemming via the `stemmer` schema property. If it's
correctly configured for our title fields, the verb-form gap
should not exist — and we need to understand why it does before
designing an intervention.

### Tasks

1. **Read Typesense's stemmer documentation.** Use WebFetch on
   `https://typesense.org/docs/latest/api/collections.html`
   (look for `stemmer`, `locale`, and any "language" or
   "morphology"-related field properties). Capture:
   - What property enables stemming on a string field?
   - Is it per-field or collection-wide?
   - What languages/locales are supported?
   - Does it require a separate `stemming_dictionary` artifact?
2. **Inspect the live schemas** for both collections used in
   retrieval:
   - `website_documents_ab897fbdedfa` — particularly the
     `linktitle`, `frontmatter_title`, and `title` fields.
   - `website_chunks_ab897fbdedfa` — the `content` field.
   - Look at each field's properties: `stem`, `stemmer`,
     `locale`, `language`, anything morphology-related.
   - Compare against what the Typesense docs say should be present
     for English (the language of Q2's failing query).
3. **Empirical probe**. Run a controlled search against the docs
   collection with `q=create` and `q=creating` separately,
   `query_by=frontmatter_title`. If the same document hits both
   queries with similar BM25 scores, stemming is active. If
   scores diverge wildly (or one query returns the doc and the
   other doesn't), stemming is NOT active for that field.
4. **Norwegian probe**. Q3's failing query is in Norwegian. Run
   the same diverge test with NB verb forms (e.g.,
   `lokal/utvikling` vs `lokal/utvikle`) to learn whether
   stemming is configured for NB if at all.
5. **Write findings.** Document what the schemas show in the
   slice plan's "Findings" section. Decide branch (a) or (b)
   below based on what was found.

### Branching on discovery

**Branch (a) — Stemming not enabled or misconfigured.** Likely
the case: enable English (and Norwegian) stemming on the title
fields. This is a schema migration — Typesense doesn't allow
adding stemming to an existing field in place, so it requires:
- Update the schema definition in our ingest pipeline.
- Re-ingest the docs collection.
- Re-ingest the chunks collection if content stemming is also a
  goal.
- Re-measure v3 baseline.

Effort: ~2 hours engineering, plus re-ingest wall-clock. Single
config change.

**Branch (b) — Stemming enabled and working for English.**
Means our `create` vs `creating` divergence has a different root
cause. Investigate:
- Is BM25 scoring on this field penalizing the morphological
  match enough that "Create"-titled docs from other products
  outrank "Creating dialogs"? (Probably yes — they're shorter
  titles, IDF favors shorter matches.)
- Does the doc-title strategy normalize query terms before
  searching? If it tokenizes "create a new dialog" without
  stop-word removal, "a new" tokens are noise.

In this case, the fix is **planner-side**: emit multiple
morphological variants explicitly. Update the query-planner
prompt to include a "Generate both gerund and infinitive forms
when the user uses a verb" rule. Easier than schema migration,
but only helps where the LLM emits the right variant — which it
doesn't for Norwegian or for less-obvious morphologies.

## What slice 25 ships

Depending on discovery:

- **Branch (a)**: Typesense schema update enabling stemming on
  `linktitle`, `frontmatter_title`, `title` (and likely
  `content`). Re-ingest. Re-measure.
- **Branch (b)**: Planner prompt update adding verb-form
  variants. Update `digdir.skills.builtin.query-planner/
  build-default-prompt`. Add a unit test that confirms the
  planner emits both forms when relevant. Re-measure.

Either branch is independently shippable. We do the discovery
work first specifically because the cost/value tradeoff is
inverted between the two: (a) is cheap-config but requires
re-ingest; (b) is cheap-code but only addresses cases the LLM
recognises.

## Measurement plan

Same as slice 22's matrix, with slice 22 (cap-N3) as the
comparison baseline:

| Config | Top-10 chunks | Top-30 chunks | Top-30 docs |
|---|---:|---:|---:|
| Slice 22 (current) | 42.0% | 50.7% | 66.7% |
| Slice 24 (H2 indexed) | TBD | TBD | TBD |
| Slice 25 (this) | **target ≥ 45% / 55% / 70%** | | |

3 runs minimum to confirm variance.

## Expected impact (Q2-specific)

If Typesense stemming is correctly enabled, doc-title strategy
should match `create*` → `Creating dialogs`. That puts the
right doc in the candidate pool. ColBERT then has a chance to
surface its chunks.

Realistic: Q2 reaches 1/1 doc at top-30 (+1 doc), 1-2 chunks at
top-30 (+1-2 chunks). Net top-30 chunks: 50.7% → 55-60%, top-30
docs: 66.7% → 75%.

If Q2 isn't the only morphology victim, Norwegian queries (Q3,
Q5, Q6) may also tighten. Q5 is already perfect at top-30 chunks
but currently at 47.8% in the worst slice-22 run; stemming
should stabilize it.

## What slice 25 does NOT ship

- **Agentic vocab-discovery loop**. The user has tabled this
  for a later arc. If slices 24 and 25 close most of the gap,
  the agentic loop may not be needed at all. If they don't,
  the agentic loop is justified by what's left.
- **Q3-specific URL-path disambiguation**. Q3's failure isn't
  morphological — it's that "Autentisering" is ambiguous across
  5+ docs. That needs URL-path-aware retrieval or product-name
  filtering, neither of which is in this slice.

## Followups identified up front

- **If Branch (a) lands**: revisit which other fields would
  benefit from stemming. The chunks `content` field is the
  obvious next candidate but is the largest re-ingest cost.
- **If Branch (b) lands**: measure how many of the planner's
  expansion phrases now hit doc-title BM25 vs before. If <50%,
  the planner-side approach isn't sufficient and we need to
  reconsider the schema migration anyway.

## Independence

This slice is independent of slice 23 (production union) and
slice 24 (H2 metadata). All three can be measured and shipped
in any order; each addresses a distinct failure mode. Combined
gains should be additive.
