# Golden-chunk tuning loop

## Goal

Establish a repeatable, well-labelled process for answering one question:

> Is there a single set of tuned retrieval parameters for the
> `digdir/altinn-docs-tuned` agent that retrieves the right ("golden")
> chunks across a spread of realistic user queries — or does good
> retrieval require per-query/per-topic specialization (and/or targeted
> enrichments)?

We run the loop below N times across different corpus topics, record
structured results, then analyze post-hoc for a config that generalizes
versus evidence that specialization is required.

This plan establishes the *baseline* (hence the worktree name): what the
exploration primitives + parameter tuning alone can achieve, before any
enrichment changes, and what enrichments add on top.

## Decisions locked (from discussion)

- **Golden set = fresh, judgment-confirmed.** For each query I generate
  the goldens myself by reading chunk content and judging relevance —
  *not* by trusting search rank, and *not* anchored to the existing
  v3-baseline citations. Search is for *discovery* of candidates only;
  relevance is judged from `content_markdown`.
- **Eval design = per-query, then post-hoc.** Tune each query
  independently, record its winning config, then look across queries for
  a config that clears the bar everywhere. No held-out test split — so
  the conclusion is framed honestly as "a config that fits *this* query
  set," and any query that only succeeds under a unique config (or only
  with enrichments) is logged as direct evidence for specialization.
- **CLI is `v3-score`** (not `v3-sweep` — that name does not exist). We
  drive sweeps by re-invoking `v3-score` with different flag combinations.
- **Query topic is grounded in the live corpus, not recollection.** Each
  iteration starts by reading current corpus content (the corpus is
  continuously revised), then derives the query from the real need that
  content serves. Register independence is preserved by not echoing the
  corpus's surface vocabulary when phrasing the query.

## Components (verified present in this worktree)

- **`bb ts-search` / `bb ts-get`** — filesystem-style corpus
  introspection. Roles: `docs`, `chunks`, `phrases`,
  `enrichment/{hypothetical-questions,verified-phrases,fact-assertions}`.
  Cheap-by-default projections; `content_markdown` opt-in via
  `--include-fields content_markdown`. `ts-get --range <doc_num>:<s>-<e>`
  fetches a chunk neighbourhood. (`bb.edn`)
- **`bb ts-retrieve`** — multi-strategy retrieval wrapper. Gated by
  `RAG_TS_RETRIEVE_ENABLED` (set per-worktree in `mise.local.toml`).
  `v3-score` calls it under the hood, so it must be enabled here.
- **`bb v3-score`** — runs `ts-retrieve` per question file in `--v3-dir`
  and reports chunk-level + doc-level recall@10 and @30. Knobs exposed as
  flags (see *Config sweep dimensions*). Default `--v3-dir` is the v3
  baseline; **we point it at our own golden dir.**
- **Enrichment skills** (`server/src-dev/digdir/skills/enrichment/`):
  `propose_phrases` / `apply_phrases`, `propose_facts` / `apply_facts`,
  `propose_questions` / `apply_questions`, `mark_keep`, `revert_chunk`,
  `verify_retrieval`, `eval_delta`. These mutate the Typesense index and
  back step 4.
- **Agent**: `digdir/altinn-docs-tuned` (resolved via agents config/seed).

## The loop (per query)

1. **Ground in the corpus first.** Before forming any query, browse the
   *current* corpus to choose a real topic and read real content — never
   rely on my own recollection of what Altinn/digdir documents (the corpus
   is under continuous revision, so recollection goes stale). Sample with
   `ts-search docs "*"` + facets (product / diataxis / language) to see
   what exists now, pick a topic area that is genuinely present, and
   `ts-get --include-fields content_markdown` to read a representative
   document. Understand the *user need* that content serves. Read for
   meaning and concepts; consciously do **not** memorise the corpus's
   surface vocabulary (that discipline belongs to the next step).

2. **Derive a realistic user query for that grounded need.** Phrase it the
   way a real user would ask — and deliberately **avoid echoing the
   corpus's surface terms**, so the vocabulary-mismatch realism is
   preserved. (Grounding fixes *what* is asked about to real content;
   register discipline keeps *how* it is asked user-like. These are
   orthogonal.) Vary register across iterations: some lay phrasing, some
   vocabulary-mismatch, some closer to corpus terms. Record: grounded
   source doc(s), topic, owning org/product, expected diataxis type,
   query register.

3. **Find & confirm goldens.** Use `ts-search` to *discover* candidate
   chunks across angles (content, title, phrases, enrichment roles, facet
   browse). Then `ts-get --include-fields content_markdown` to **read**
   each candidate and decide relevance by judgment. Also walk the owning
   document(s) with `ts-get --range` to catch relevant chunks that *no*
   search angle surfaced — those misses are the highest-value cases.
   Record the confirmed golden `chunk_id`s and a one-line justification
   each. (The grounding doc from step 1 is usually the primary golden
   source, but goldens are still confirmed by relevance judgment, not by
   assuming the grounding doc is the whole answer.)

4. **Sweep parameters (no corpus mutation).** Write the query as a golden
   file (format below), then run `v3-score --v3-dir <golden-dir>` across
   the config grid. Record recall@10/@30 (chunk + doc) per config and the
   winning config for *this* query. **Expansion-mode is now a standing
   dimension of this sweep**, measured on every case:
   - `blind` — the current corpus-blind planner (baseline).
   - `corpus-aware-1hop` — PRF: probe the `phrases` collection with the
     planner's expansions, harvest real corpus phrases + the titles of the
     docs they come from, ground the final expansion on them.
   - `corpus-aware-2hop` — additionally re-probe `phrases` *scoped to the
     docs surfaced by hop-1's retrieval* for procedural vocabulary, then
     retrieve.
   Each case records baseline AND corpus-aware recall, so the loop doubles
   as a per-case A/B of the query-side lever (see Phase C in results.md).

   **Blindness discipline (non-negotiable).** Corpus-aware expansion must
   run with NO knowledge of the goldens, or the all-cases measurement is
   circular:
   - Hop-2 scopes to the docs returned by **hop-1 retrieval**, never to the
     golden doc_nums.
   - The grounding step (selecting/composing corpus terms from harvested
     candidates) is done by the **automated planner LLM**, not by hand with
     the answer in view. The Phase-C prototype's manual term-selection and
     golden-scoped hop-2 were a ceiling estimate, NOT the procedure — the
     procedure is the blind, automated form only.

5. **If params can't get there → enrichment sub-loop.** Only after step 4
   is demonstrably insufficient:
   - Use the enrichment `propose_*` skills to draft candidate search
     phrases / facts / typical questions for the golden chunk(s); or
     identify enrichments to *remove* if a competing chunk is winning.
   - `apply_*` to the index, then **re-run the full param sweep** for this
     query, and **also re-run a regression check** over previously-passing
     queries (enrichments are global state — see *Discipline*).
   - **`revert_chunk`** to restore baseline before the next query, unless
     we decide to keep the enrichment (logged explicitly).
   - Record the enrichment delta, its effect on this query, and any
     regression observed.

6. **Log a structured row** (schema below) capturing everything tried.

## Golden file format (reuse `v3-score`)

`v3-score` parses each `0N-*.md` in `--v3-dir`:

- **H1 line**: `# Q<n> v<n> — <question text>` (em-dash `—`, U+2014).
- **`## Cited chunks`** section: the golden `chunk_id`s as backticked
  12-hex tokens, e.g. `` `9a5fc194a710` ``. These are the goldens scored
  against.
- Everything else (strategy, trail, notes) is freeform and ignored by the
  scorer — we use it to record the discovery trail and justifications.

**Constraint to fix first**: the scorer's filename regex is
`^(0[1-7])-.*\.md$` — it only scores files `01`–`07`. For a loop that
exceeds 7 queries, widen it to `^(\d{2})-.*\.md$` (and the matching
`qnum` capture) in the `v3-score` task in `bb.edn`. Small, isolated edit;
do it before iteration 8 (or keep ≤7 goldens per batch dir).

## Config sweep dimensions (`v3-score` flags)

Primary knobs to grid over (start coarse, refine around winners):

- `--expand-queries N` — LLM query expansion breadth.
- `--user-intent-first-pass true` + `--union-mode {cap|interleave|rrf}`
  (+ `--intent-cap N` for cap, `--union-rrf-k K` for rrf).
- `--rerank-with-colbert true` (+ `--rerank-candidate-k`,
  `--per-strategy-rerank`, `--rerank-final-cap`).
- `--title-fields` and `--doc-title-chunk-fanout`.
- `--auto-filter-rules '<edn>'` (e.g. diataxis/language/org biasing).
- `--strategy-weights`, `--strategy-contribution-caps`,
  `--chunk-content-fields`, `--chunk-metadata-fields`, `--retrieval-mode`,
  `--merge-mode`, `--rrf-k`.
- `--top-k` for the scoring threshold (report both 10 and 30).

Keep `RAG_TS_RETRIEVE_ENABLED=true` for the worktree throughout.

## Recording schema

One results file per iteration under the golden dir, plus a single
roll-up table (`results.md` or `results.tsv`) with one row per
**(query × config)**:

| field | meaning |
| --- | --- |
| `qnum` | query id (01, 02, …) |
| `query` | the user query text |
| `topic` / `org` / `diataxis` / `register` | stratification |
| `golden_chunk_ids` | confirmed goldens |
| `config_label` | short name for the flag combo |
| `flags` | exact `v3-score` flags used |
| `recall@10_chunk` / `recall@30_chunk` | strict chunk recall |
| `recall@10_doc` / `recall@30_doc` | doc-level recall |
| `enrichment_delta` | phrases/facts/questions added or removed (or `none`) |
| `reverted` | whether the enrichment was rolled back |
| `winning?` | marks this query's best config |
| `notes` | observations, regressions, surprises |

The roll-up is what the post-hoc analysis consumes — fixed columns so the
analysis is mechanical.

## Post-hoc analysis (after N iterations)

- Collect each query's winning config. Look for a **single config** that
  clears the recall bar on (nearly) every query → evidence it generalizes
  on this set.
- Flag every query that only passes under a query-specific config or only
  after enrichment → evidence for **specialization**. Cluster these by
  topic/org/diataxis/register to see whether specialization is per-query
  or per-*segment* (the more useful, deployable kind).
- Separate the **param contribution** from the **enrichment
  contribution**: report baseline-param recall, best-param recall, and
  best-param+enrichment recall, so the value of each lever is attributable.

## Discipline / risks

- **Goldens independent of the tool being tuned.** Judge relevance from
  content, never from rank. Actively look for search-invisible relevant
  chunks (step 2 doc-walk) so we don't only optimize what's already easy.
- **Enrichments are global mutable state.** Applying an enrichment to win
  query A changes the retrieval surface for *all* queries and any
  concurrent run. So: exhaust params before enrichments; re-baseline
  affected queries after every enrichment change; revert between queries
  unless a keep is explicitly logged; never run two enrichment
  experiments interleaved.
- **Two levers measured separately.** Param tuning (cheap, reversible,
  no mutation) is fully exhausted and recorded before any enrichment is
  applied for a given query.
- **Honest generalization framing.** No held-out set → we claim "fits
  this query set," not "generalizes," and we surface per-query/per-segment
  specialization explicitly.

## Directory layout

```
plans/in-progress/golden-chunk-tuning-loop-plan.md   (this file)
plans/in-progress/golden-chunk-tuning/               (golden dir)
  01-<slug>.md … NN-<slug>.md                         (one per query)
  results.md                                          (roll-up table)
```

`v3-score --v3-dir plans/in-progress/golden-chunk-tuning` scores the set.

## Out of scope

- Production retrieval orchestration changes (lives in the retrieval skill).
- Permanent enrichment commits — any enrichment we keep is logged and
  decided deliberately, not as a side effect of the loop.
- A held-out generalization claim (explicitly deferred; see decisions).
