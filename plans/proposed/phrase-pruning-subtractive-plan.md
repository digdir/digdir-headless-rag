# Subtractive lever, done right: prune corpus-generic ingest phrases at the source

**Status:** proposed
**Date:** 2026-06-03

## Why this, and why it's the *right-shaped* subtractive lever

Thread 2 set out to build a "subtractive" lever — instead of boosting the golden,
penalize whatever buries it. The first instinct (penalize distractor chunks at
**rerank scoring**) is the wrong stage: Lever A (windowing) already fixed the
rerank-scoring burial. In the windowed world (P2 window arm, N=3):

- full burial is rare (4/120), and **3 of 4 are Mode-B** — the golden never enters
  the top-40 candidate pool (`R—`), so a rerank-stage penalty cannot reach it;
- displayed-golden median rank is already 1, ≤5 at 91%.

So the residual burial lives **upstream of rerank — at the lexical candidate gate.**
A subtractive lever has to act *there* to matter. The phrase strategy is exactly a
candidate-gate input, and the data says it's a noise source.

## The validated mechanism (live phrase-collection audit, `website_phrases_ab897fbdedfa`, 46,969 phrases)

Ingest generates phrases **per chunk** (`digdir.docs.pipeline.search_phrases`),
prompting for "keyword search phrases with high BM25 precision" — with **no
corpus-wide awareness.** A phrase that's locally precise can be corpus-generic.

Concrete audit of the recurring super-distractors (chunks that sit above goldens
across *unrelated* questions in the pool-head) vs a buried golden:

| doc | title | # phrases | character |
|---|---|---|---|
| 88a046dc8cb8 | **Events** (overview) | **60** | broad: "event-driven architecture", "Pub/Sub model", "event streaming", "Publish/Subscribe concepts", "Simple HTTP interface", "secure and scalable", "Altinn Events" |
| b66bdced0835 | Publish events | 32 | broad event-domain |
| 221250e04c48 | Expression validation | 23 | broad |
| b882f9ab4b85 | Static | 17 | broad |
| f9c39f17a534 | **broker-03 golden** "Very large files" | **10** | specific + good: "files larger than 2GB", "virus scan disabled", "theoretical maximum size 1.6TB" |

Key point: **the golden's own phrases are excellent.** It is not buried by bad
phrases of its own — it is buried because broad *overview/landing* docs carry a
large volume of generic phrases, and the phrase strategy **rewards promiscuity**
(`h` = hit-count: how many of a run's expanded queries returned a chunk). A doc
with 60 broad phrases matches many query variants → high hit-count → high
retrieval-prior → it crowds the top-40 candidate pool and pushes the specific
answer out. That is the Mode-B miss.

This is corpus-relative noise a per-chunk ingest prompt cannot detect, but a
corpus-aware pass *can*: a phrase that returns hits for too many unrelated queries
/ matches too many docs is low-discriminative and a pruning candidate.

## The practical approach (user's proposal): self-improve agent edits the PRIMARY phrases collection, with removal

Today the self-improvement agent (`digdir.demo.self-improve-phrases-graph`) only
**adds** phrases, and only to a separate `enrichment_verified_phrases` collection
(default-off in retrieval). There is no path to **remove** ingest-generated noise
from the primary `phrases` collection. Redirect it so it can prune.

### Code path (mapped)

- **Write/apply:** `digdir.skills.enrichment.apply-phrases/execute-apply-phrases`
  (`src-dev/.../enrichment/apply_phrases.clj`) — currently upserts to the
  enrichment collection. Add an optional target so it can write/delete on the
  primary phrases collection using the ingest path's deterministic id scheme
  (`sha256-short(chunk_id "|" phrase)`, in `docs/pipeline/storage.clj`
  `store-phrases!` / `delete-orphan-phrases!`).
- **Remove/revert:** `enrichment/revert_chunk.clj/execute-revert-chunk` already
  does generic Typesense `delete-documents!` by filter — works on any collection.
  A *prune* op is the same delete keyed by `chunk_id` + the specific phrase ids.
- **Graph:** `self_improve_phrases_graph.clj` — thread a `:phrases-collection`
  target through `:analyze` → `:per-chunk` → `:apply`/`:revert`; add a `:prune`
  proposal step (propose phrases to REMOVE, not just add).
- **Gate:** the graph already has an **eval-delta `:keep?` gate** (`:eval`/`:verify`
  re-run retrieval and keep only ranking-improving changes). Reuse it verbatim for
  prune decisions — a phrase is removed only if its removal does not hurt (ideally
  helps) ranking on the eval set. **This gate is the whole safety story.**

### Mutating the primary collection safely

Editing the production `phrases` collection in place resets every prior baseline
and risks regressions. Operate on a **cloned phrases collection** (mirror of
`store-phrases!`/migration toolchain from Lever B, `rechunk_migrate.clj` pattern)
and point retrieval at it via a dataset-config-key / `:phrases-collection`
override (the sweep runner already supports a `:chunks-collection-override`; add
the phrases analogue). Off = original phrases, on = pruned clone — a faithful A/B.

## Risk — pruning is double-edged

Generic phrases are not pure noise: the **enrichment arc** showed typical-question
bridging *recovers* lay-query and cross-lingual gaps (e.g. broker-03's golden has
English phrases vs a Norwegian query — its real miss is partly cross-lingual, and
pruning would NOT fix that case; it helps the cases where a promiscuous doc
out-ranks a well-phrased golden). So blanket genericness pruning could drop the
one bridge a lay query needed. **Mitigation: prune only eval-gated** (the `:keep?`
delta), and prefer pruning phrases on *high-promiscuity overview docs* rather than
globally. The lever is "remove the phrase only if retrieval improves," not "remove
all generic phrases."

## Measurement plan (A/B at N≥5 — the session's recurring lesson)

1. **Quantify genericness corpus-wide first** (before any change): for each phrase,
   how many distinct docs/queries it matches; rank docs by phrase-volume ×
   breadth. Confirm the super-distractors are systematically high and goldens low
   beyond the 5-doc anecdote. (`search_phrase` is `facet:false` — use filtered
   counts or an export pass, not Typesense facets.)
2. **Prototype prune** on the worst N overview docs → cloned phrases collection.
3. **A/B**, off vs pruned-clone, **windowing held ON both arms** (Lever A is the
   default world this stacks on), full-42, **N≥5**, judged. Success = Mode-B
   burial down (goldens enter the top-40 more), recall@20 + judge up, **no clean-set
   regression**, and specifically watch lay/bridged questions (Q06/Q08/Q23 in the
   enrichment portfolio) for a pruning-induced *drop*.
4. If positive: widen the self-improve prune pass corpus-wide, keep the eval gate.

## EXECUTION (2026-06-03): validate-by-clone first, then productionize

Validate the hypothesis with the **standard sweep process** BEFORE wiring the
self-improve agent. The agent-wiring (apply/revert redirect + prune step) is the
*productionized* delivery; it only earns its cost if a cheap offline-prune clone
shows the lever moves the baseline. So:

**Clean A/B (key advantage over Lever B):** pruning only *removes rows from the
phrases collection*. Chunk ids, golden-chunk-ids, docs, and chunks are untouched —
so **NO re-grounding**, and the ≤1000 clean set is a true control. Off and pruned
differ in exactly one variable.

### Step 1 — genericness metric (corpus-relative token-IDF)

A phrase is "noise" when it is non-discriminative *within this corpus* (everything
is about Altinn events/auth, so "event-driven architecture" matches everywhere).
Operationalize with a token-IDF over the phrase→doc graph:

- tokenize each `search_phrase` (lowercase, split on non-alphanumeric);
- `df(token)` = # distinct `doc_num` whose phrases contain the token;
  `idf(token) = log(N_docs / df(token))`;
- **phrase specificity = max token idf** (its rarest/most-distinctive token). A
  phrase with at least one rare token is kept; a phrase whose tokens are ALL
  corpus-common (the generic tail) scores low.

Calibrate the cutoff against ground truth we already have: the broker-03 golden
phrases ("theoretical maximum size 1.6TB", "files up to 2GB") must land HIGH; the
"Events" overview phrases ("event-driven architecture", "secure and scalable",
"Altinn Events") must land LOW. Pick the threshold in the gap; start **conservative**
(prune only the clear low-specificity tail) — the sweep's judge + clean-set
guardrail catches over-pruning, and a follow-up can tune aggressiveness.

### Step 2 — build the pruned clone (dev script, `rechunk_migrate.clj` pattern)

`server/src-dev/digdir/sweep/phrase_prune.clj`: export all phrases (paginated),
compute specificity, drop phrases below the cutoff, clone the phrases schema
verbatim, write `website_phrases_pruned_v1` with the surviving rows (deterministic
ids preserved → idempotent). Read-only on the config DB; only creates a Typesense
collection. Report: # pruned / # kept, the cutoff, and per-doc deltas for the
super-distractors vs goldens.

### Step 3 — runner support

Add `:phrases-collection-override` to `runner.clj/resolve-dataset-config!`
(exact analogue of the existing `:chunks-collection-override`), threaded into the
`:collections` map so a sweep can retrieve from the pruned clone.

### Step 4 — A/B sweep

`phrase-prune-ab.edn`: full-42, **N=5**, JUDGED, `:order :interleaved`,
**windowing held ON both arms** (the promoted default world this stacks on):
- `off` = `{}` (windowing-on default, original phrases);
- `pruned` = `{}` + `:phrases-collection-override "website_phrases_pruned_v1"`.

Success = Mode-B burial down (goldens enter the top-40 more) + recall@20/judge up,
**no clean-set regression**, and no drop on lay/bridged questions. Drop the clone
after the test (like the Lever B rechunk collection). If positive → productionize
via the self-improve agent (eval-gated prune on the primary collection).

## Open questions

- Promiscuity vs doc-size confound: is the signal raw phrase count, or per-phrase
  corpus-breadth? Favor breadth (an IDF-like statistic over the phrase→doc graph).
- Do we prune phrases, or *down-weight* the phrase strategy's promiscuity reward
  (`h`/hit-count) for high-fanout docs? Pruning is durable + inspectable; a
  retrieval-time promiscuity dampener is reversible + needs no collection clone.
  Worth A/B-ing the dampener as the cheaper first cut.
- Cross-lingual misses (broker-03) are a *separate* lever (phrase/query language) —
  do not conflate; pruning won't address them.

## Scope reconciliation (2026-06-03): the in-progress sweep is a precursor, not the target

The user confirmed the **eventual productionized prune should reason over the combined
(primary + enrichment) phrase set**. A code audit established that the in-progress sweep
(`phrase-prune-ab.edn`) validates a *narrower precursor* and does **not** exercise the
self-improvement loop or the combined context.

### What the in-progress sweep is vs. is not

| | In-progress sweep | Combined-context target |
|---|---|---|
| Mechanism | Deterministic token-IDF script (`sweep/phrase_prune.clj`) — no LLM, no agent | LLM self-improve agent, eval-gated |
| Phrase set acted on | **Primary** ingest collection only (`phrase_prune.clj:90`) | **Primary + enrichment** (union) |
| Enrichment in retrieval | **Off** on both arms (`:skill-params {}`, no `:enrichment-search-targets`/`:enrichment-types`; `retrieval.clj:235-268`) | **On** |
| Answers | "Do generic *primary* phrases bury goldens?" (necessary precursor) | "Does pruning the *combined* set, with bridges live, net-improve ranking?" |

Note also that today's self-improve propose step reads **neither** collection's existing
phrases — only chunk content + doc metadata (`fetch_chunk_context.clj` →
`propose_phrases.clj`). So "reason over existing phrases" is itself net-new.

### Combined-context target = net-new on three axes (none built today)

- **Read context:** propose/prune must read a chunk's existing phrases from BOTH
  collections (today: neither).
- **Write reach:** prune must DELETE from the PRIMARY collection (today `apply_phrases.clj`
  only upserts to enrichment); path mapped via `revert_chunk.clj` deterministic-id delete.
- **Eval world:** the eval-delta `:keep?` gate's retrieval must run with **enrichment ON**,
  or it optimizes a world production won't be in.

### The combined target sharpens the double-edged risk

Enrichment deliberately adds *broad* bridges (lay-query / cross-lingual / typical-question
— Q06/Q08/Q23). To an IDF/genericness metric, a deliberate bridge and a corpus-generic
ingest-noise phrase are **indistinguishable** (both low-specificity). In the combined world
prune and bridges compete in the same band; only the eval-delta gate disambiguates them —
which is why the gate must run enrichment-on (above). See `project_enrichment_lever`.

### Add/prune interaction

With both ops over one chunk's combined set, a pruned primary phrase may be compensated by
an enrichment bridge (or vice versa). The gate must judge **net combined state** per
change, not each op in isolation.

### How to read the finishing sweep

- **`pruned` wins** → confirms generic *primary* phrases drive Mode-B burial in the
  windowed world → greenlights building the combined prune. The combined version still
  needs its **own** enrichment-on validation (enrichment changes the candidate pool the
  prune is judged against).
- **flat / loses** → does NOT kill the combined hypothesis; may be `cap=20`/`floor=3.0`
  mis-tuning. Disambiguate via the script's per-doc kept-count diagnostic
  (`GOLDEN broker-03 kept x/10 | Events kept x/60 | …`): if the 60-phrase Events overview
  wasn't trimmed relative to broker-03, the null is a tuning artifact, not a mechanism
  verdict.

## Relates to

- [[rerank-truncation-plan]] — Lever A (windowing), the scoring-stage fix this
  stacks on; the reason the residual is now Mode-B (candidate gate).
- [[prior-arc-reassessment-plan]] — the "fix one stage, residual moves to the next"
  pattern; this lever finally targets the moved-to stage.
- `project_enrichment_lever` — bridging phrases HELP lay queries; the de-risking
  constraint on pruning.
- `project_rerank_truncation_bottleneck` — the stage model.
