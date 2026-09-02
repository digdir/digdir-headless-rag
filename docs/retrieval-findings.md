# What we learned building a RAG system over Norwegian public documentation

**Status:** research record, closed 2026-06-16. Written 2026-08-21 for a team that will
rebuild this on a different technology stack.

This document exists because the work is spread across 618 commits on a branch that is
being retained read-only. Retaining a branch makes findings *recoverable*; it does not
make them *known*. Nobody reads 618 commits.

**Who this is for.** Someone building retrieval-augmented question answering over a
corpus of public-sector documentation, who will not have the original codebase open and
should not need it. Everything below is about the problem, the corpus, and the way we
measured — not about the implementation.

**How to read it.** The methodology and the negative results are the valuable parts.
The tuned parameter values are deliberately *not* carried forward, for reasons given in
§5. Every claim names the commit or the run directory it comes from, so a sceptical
reader can go and check. Where a claim we previously believed turned out to be wrong,
it is marked **SUPERSEDED** rather than deleted — knowing that something stopped being
true is itself a finding, and silently dropping it makes it look like it was never tried.

---

## 1. The shape of the system, in one paragraph

A question arrives in Norwegian or English. A planner expands it into several search
queries; a lexical/semantic search returns a candidate pool from a chunked documentation
corpus; a reranker orders them; an agent loop decides which chunks to read in full,
then synthesises a cited answer. Quality was measured end-to-end by an LLM judge scoring
each answer against a hand-written reference as *correct / partial / incorrect*; the
headline metric ("effQ" below) is the mean judge score over a fixed question set. Nothing
in this document depends on those components being built the way we built them.

---

## 2. Methodology — the part most worth carrying over

These cost the most to learn and are entirely stack-independent.

### 2.1 Verify what the evaluator actually consumed

This is the single most important finding in the document.

Our sweep runner wrote each answer to a results file, truncating it to **800 characters**.
A separate scoring pass then read that file and sent it to the judge. The judge therefore
never saw the end of any long answer, and marked it *"partial: missing X"* when X had in
fact been generated and then clipped.

The consequences were large and went undetected for weeks:

- Of one model's 22 "partial" verdicts, **22 of 22 were on clipped answers**; 32 of 42
  answers hit the cap (`04fbf98`, `0a85dad`).
- The canonical piece of evidence for a supposed *model capability ceiling* — an answer
  that "omitted" a required status value — was **2,019 characters long and did contain
  the value**. The model had enumerated everything. (`04fbf98`)
- Re-judging on full text moved the mean score on the affected sample **0.550 → 0.717**,
  flipping 3 of 7 verdicts from partial to correct.
- It inflated apparent difficulty across the whole arc: measured under-enumeration among
  answers that had read the right source fell from **54% to 14%** once fixed (`ea39736`).

The bug had existed since the runner was written (`546d5ea`, 2026-05-25) and became
harmful when a *separate* judging pass was introduced (`5cf2af3`, 2026-06-06). Sweeps
that judged inline, in memory, were never affected — the defect lived in the hand-off
between producing an answer and scoring it.

**The lesson, generalised:** an evaluation pipeline has a seam wherever the artefact
being judged is serialised, stored, re-read, or transformed. Assert on what the evaluator
*received*, not on what the system produced. We added a guard that fails the run if any
judge input arrives truncated (`2e466e0`), and every subsequent sweep reports
"0 truncated" as a first-class health number.

**This lesson kept paying.** Three further evaluator defects were found by asking the
same question:

- The judge selected its model from a configuration key that was only correct when a
  cloud provider was enabled. With it disabled — which was true for *every* local run —
  the name resolved to nothing and the call 404'd. Symptom: whole batches of `error`
  verdicts (run log, 2026-06-10).
- The scoring pass did not persist which question set a run used, so re-scoring silently
  fell back to the default set and skipped every row whose id it did not recognise
  (`6288092`).
- A query-planner in the same position picked its model from the cloud-only key, so with
  the cloud disabled it silently fell back to the raw, unexpanded query. **Query expansion
  never ran in any local experiment** for the whole arc, including a headline result. The
  A/B *deltas* survived, because both arms were equally degraded, but every absolute
  number was measured with a component switched off that we believed was on
  (run log, 2026-06-15).

Note the shared shape of all four: a configuration value that is correct in one
environment and silently degrades in another, with **no error surfaced**. If you take one
structural precaution from this document, make the evaluation harness fail loudly when a
component it depends on is absent, rather than falling back.

### 2.2 A safeguard must be demonstrated to fail before it is trusted

We shipped a guard that was supposed to catch the agent answering without searching. The
A/B was deliberately skipped, with a documented and superficially reasonable rationale:
the addressable failure was ~2.4% of runs, so a full comparison would have measured a
~0.006 effect at the noise floor — eight hours of compute for a three-run signal
(`1bcd4e3`). It was validated instead by a wiring test plus a live smoke test.

It was **inert from the day it landed**. The trigger was a regular expression over the
answer text, and it matched none of the shapes that actually occurred — a parenthesis-less
`SEARCH queries=` form, a Norwegian "let me search" phrasing, and answers that
hallucinated without any search-like text at all (`edb0ca3`).

The replacement was shape-agnostic: re-prompt on *any* terminal answer where no search
had been performed, since that is a policy violation regardless of how the text reads
(`edb0ca3`). It was re-tested against the captured offending population and reported as
eliminating never-searched terminals outright — but those measurements were made in the
worktree described in §3.5 and are **not recoverable from the archive**; only the fix
itself is. The inertness, and the reason for it, are recorded in the commit.

**The lesson:** the reasoning for skipping the A/B was sound *about effect size* and
irrelevant *about correctness*. A test that shows a guard fires on the inputs you imagined
is not evidence it fires on the inputs you have. Before trusting a safeguard, show it
firing on captured real failures — and if it is genuinely not worth an A/B, that is an
argument for a cheap targeted check, not for none.

### 2.3 A small screen can flip sign at scale

We screened a promising interaction on 8 questions: **+0.107**, 6 wins to 1 loss
(`f1cb410`). Confirmed on the full 42-question set it was **−0.037** (`516f96e`), and on
a later clean re-run **−0.048**, converting 13 correct answers into partials.

The instructive part is *why*. The treatment arm was stable; the **8-question screen's
control arm scored anomalously low** (0.535 against its true 0.625). The screen measured
a bad baseline, not a good treatment.

**The lesson:** small screens are for triage, not for promotion. When a screen looks
strong, check whether the *control* is the outlier before believing the delta. Confirm at
full scale before shipping anything.

### 2.4 Select failure cohorts by multi-run agreement

We built a 10-question "failure cohort" from a single run and spent an arc tuning against
it. The same configuration, re-run, swung read-rate 33%↔53% and recall 0.33↔0.52 between
two passes: the cohort was **~80% noise — 5 of 10 were coin-flips at N=3** (`3412ca1`).
The levers built for it made things worse.

**The lesson:** a single run selects for variance, not for difficulty. Require a failure
to reproduce across independent runs before you treat it as a target.

### 2.5 Reading code establishes what is plausible; only running it establishes what is true

Repeatedly, a well-argued premise did not survive contact with data. In our own recent
triage the rate was roughly one in three: a corpus-integrity issue whose failure mode was
real in the code but **not firing in production** for a reason nobody had checked; a
lint-debt issue whose headline number was stale by 40%; a "model ceiling" that was a
truncation artefact.

None of these were careless. They were all *reasonable inferences from reading the code*.
The cost of checking was in every case a small fraction of the cost of acting on the
wrong premise.

---

## 3. Negative results

The least recoverable category. Nothing in a codebase records what was tried and
abandoned, so without this section a future team pays full price to rediscover it.

### 3.1 Telling the model to be more complete does nothing

Diagnosis said answers were under-enumerating: they read the right source and still
omitted required items. The obvious lever is to instruct the synthesiser to enumerate
exhaustively.

**Result: null.** On clean full-text judging, Δ **+0.000**, t≈0.01, 3 wins / 4 losses
(run log, 2026-06-11). Re-tested later on a frontier model: **−0.015**, 17 wins / 20
losses, verified from the raw run data in `repaired-E-completeness-20260615T025925`.

> **SUPERSEDED — a correction to our own earlier record.** We reported for some time that
> this directive was "null *and doubled hallucination* (0.042 → 0.083)". **The doubling
> was itself an artefact of the truncation bug.** On clean data the incorrect-rate is 12%
> on *both* arms — no increase. The null is real; the guardrail alarm was not. Anyone
> repeating this should expect a non-lever, not a harmful one.

**Why it matters:** under-enumeration is not an instruction-following gap, so prompt
directives cannot close it. Do not spend a cycle on prompt wording here.

### 3.2 More reading is not better reading

Giving the agent a larger read budget, on top of a change that improved *which* chunks it
selected, was negative: **−0.048** at full scale (t≈−1.81), turning correct answers into
partials. The winning change had worked by reading *fewer and better* chunks; adding
budget undid it.

> **SUPERSEDED on a frontier model.** Re-measured on a frontier synthesiser the penalty
> **disappears**: **+0.004**, 17 wins / 16 losses, t≈0.33 (verified from
> `repaired-F-readhi-20260615T045811`). The harm was a property of the weaker model being
> diluted by extra context, not a property of the retrieval design.

**Why it matters:** "give the model more context" is model-dependent, and can be actively
harmful below some capability threshold. Do not assume it is free.

### 3.3 A corpus-side enrichment win did not generalise

We generated hypothetical questions for each chunk and indexed them as an additional
retrieval target — a corpus-side lever, aimed at bridging the gap between how users ask
and how documentation is written (§4.3). On the curated 42-question tuning set it was a
solid win: **+0.068**, t≈2.14, and the mechanism was visible and correct — golden-in-pool
rose **84% → 95%** (run log, 2026-06-11).

On a broad 118-question set it was **null**:

| set | model tier | Δ | statistic |
|---|---|---|---|
| curated 42 | ~30B local | **+0.068** | t≈2.14, 26W/14L |
| broad 118 | ~30B local | **+0.024** | t≈0.67, 42W/48L (`6288092`) |
| broad 118 | frontier | **+0.017** | 50W/45L/23T (verified from `repaired-D-enrichment-118-…`) |

Because it is null on the broad set at *both* model tiers, the failure to transfer is a
property of the **question set**, not of the model. The curated set had been used to
develop the lever; the broad set had not.

**Why it matters:** this is tuning-set overfitting in its most seductive form — a real
mechanism, a plausible story, a significant result, and no generalisation. Hold out a
question set that was never used to develop the thing you are measuring.

### 3.4 More model capacity did not fix under-extraction

Hypothesis: answers under-enumerated because the incumbent was a mixture-of-experts model
with few *active* parameters. Test: same harness, same prompts, swap only the model, one
runtime to avoid a host confound.

Three ~30B-class models landed within **0.012** of each other (0.730 / 0.742 / 0.735).
A same-family **dense** model with roughly 9× the active parameters was **−0.008** against
the incumbent (t≈−0.14) — dead even. It read the correct source *more often* and still
under-enumerated at the same rate, which is the strongest evidence that the limitation was
not retrieval and not reading (run log, 2026-06-11; original invalid run `a9cd091`,
superseded by the clean re-run).

> **SUPERSEDED in its framing.** The first version of this result concluded a hard
> "model ceiling" at ~54% under-enumeration. That figure was inflated by the truncation
> bug; on clean data it is **14–27%**. The *comparison* survived de-truncation unchanged —
> capacity does not help — but the pessimism did not. The residual gap was modest, not a
> wall.

**What did move it:** a frontier-tier model, at **0.837** on the 42-set and **0.832** on
the broad 118-set (verified directly from the raw run data: 126 and 354 scored runs
respectively), against ~0.735 for the ~30B class, with hallucination falling to ~1–3% from
7–12%. Note that the near-identical scores on the tuning set and the never-tuned broad set
are the evidence that this is a real capability gain rather than overfitting.

**Why it matters:** when a quality gap is caused by model capability, harness-level and
prompt-level levers do not close it, and no amount of same-tier model shopping helps.
Establish which side of that line you are on early — it determines whether effort should
go into the pipeline or into the model budget.

### 3.5 Retrieval coverage was not the bottleneck — *evidence no longer available*

An arc was launched on the premise that ~20% of questions never had the right chunk in the
candidate pool. Diagnosis found the premise wrong: on pooled multi-run data every question
was reachable, with zero never-in-pool, and the original figure was a single-run artefact
compounded by a scaffolding bug that miscounted "never searched" as "not covered". The arc
was redirected on that finding.

> ⚠️ **We could not verify this from the archive.** The work was done in a worktree whose
> branch was never pushed; it is absent from the retained branch, from every other ref, and
> from the plan files. The finding is recorded here on the strength of the contemporaneous
> summary, and this document is now its only durable trace. Treat the specific numbers as
> unverified; the *direction* is corroborated by the observation in §3.3 that a working
> pipeline reached 95–99% golden-in-pool.
>
> This is also a concrete illustration of why this document exists: a branch-retention
> policy protects only what was committed and pushed. One of the four negative results
> considered most valuable had already been lost by the time we came to write it down.

---

## 4. The corpus and the data

These are properties of the documents and the questions. They migrate with the data, and
they are the parts of this work that stay true after a rewrite.

### 4.1 The golden question sets

Two hand-built evaluation sets, both retained in the repository as data files rather than
code, in a schema that is trivial to port:

- a **42-question** curated tuning set, used to develop levers;
- a **118-question** broad set, generated later against the documentation corpus and used
  as the generalisation check.

Each question carries: a stable id; the question text; **the chunk ids expected to be
retrieved**; a regular expression an acceptable answer must match; a `grounding-mode`
distinguishing *retrieval-and-answer* from *answer-only* and from **`refusal-expected`**
(questions where the correct behaviour is to decline); and tags for slicing results by
difficulty, form and domain.

Three design choices worth copying:

1. **Golden chunk ids separate retrieval quality from answer quality.** Without them you
   cannot tell "did not find it" from "found it and answered badly" — which is the single
   most useful decomposition in the whole exercise, and the thing that let §3.4 be settled.
2. **`refusal-expected` rows.** A suite of answerable questions rewards a model that always
   answers. Including questions whose correct answer is "the documentation does not say"
   keeps that honest.
3. **Matched Norwegian/English pairs over the same source chunk** — the same question asked
   in both languages, expecting the same evidence. This isolates language handling from
   subject difficulty.

### 4.2 The judge, and its known biases

Answers were scored by a frontier LLM against a hand-written reference. It worked well
enough to base decisions on, with two documented biases:

- **Exhaustiveness strictness.** Where a reference enumerates items, the judge penalises
  any omission, so "partial" conflates *missed a required item* with *phrased it less
  completely than the reference*.
- **Reference-format sensitivity.** In at least one hand-audited case it marked an answer
  partial for not reproducing the reference's explicit ordering, while the answer's own
  caveat that no fixed ordering exists was arguably more correct (run log, 2026-06-10).

Both push in the same direction: the judge understates quality on enumerative questions.
Read absolute scores with that in mind; paired A/B deltas are far more trustworthy than
absolute levels, which is the reason nearly every result here is reported as a paired delta.

### 4.3 The lay-query gap

Questions phrased the way a non-expert asks them retrieve poorly against documentation
written in institutional register. The corpus-side response — generating, for each chunk,
the questions it would answer, and indexing those as an additional retrieval target — is
the lever in §3.3. It genuinely raised pool coverage (84% → 95%) by giving lay phrasings
something to match. It just did not generalise beyond the set it was developed on.

The general principle survives even though the specific win did not: **the mismatch is
between the user's vocabulary and the document's, and it can be attacked from the corpus
side as well as the query side.**

### 4.4 A structural hazard: content-addressed chunk identifiers

Chunk identifiers were derived by hashing **chunk content alone**, and used as the storage
primary key with upsert semantics. Two documents containing an identical passage therefore
produce the same id, and the second write silently overwrites the first — the losing
document's chunk is not mislabelled, it is *absent from the index*, while the document
still appears fully indexed.

Measured on the production corpus (11,306 documents / 713,923 chunks):

- **9.09%** of chunk rows are identical-content duplicates (64,887 rows).
- **36%** of documents (4,070) contain at least one chunk byte-identical to another
  document's.
- Collapsing them under content-only ids would erase **~394 documents entirely** and
  degrade ~3,080 more.

Two populations drive it, and only one is what you would guess: **765 documents are full
duplicates** of another document (the same report registered under several identifiers),
and a longer tail share a handful of standard passages. The duplication is spread
**uniformly** through documents rather than clustering at the head and foot — it is not
shared page furniture but standard mid-document sections, such as an identical
accounting-principles paragraph appearing in 114 different annual reports.

**The lesson, and it is a design lesson rather than a bug report:** if a chunk identifier
is content-addressed and also a primary key, identical content across documents is
data loss by construction. Derive the identifier from document identity *plus* content (or
position), or deduplicate deliberately and store the set of owning documents. Either way,
**measure duplication in your corpus before choosing** — in a public-documentation corpus
9% is unremarkable, and near-duplicate whole documents are common.

---

## 5. What we are deliberately not carrying forward

### 5.1 The tuned parameter values

Several levers were measured carefully, held up under re-validation, and are still not
worth porting. Re-measured on a frontier synthesiser they shrink toward nothing:

| lever | ~30B local | frontier | status |
|---|---|---|---|
| answer-snippet self-selection | +0.083 | **+0.037** | real but roughly halved |
| extra read budget | −0.048 | **+0.004** | penalty disappears |
| completeness directive | +0.000 | **−0.015** | non-lever at both tiers |
| corpus enrichment (broad set) | +0.024 | **+0.017** | null at both tiers |

*(Frontier figures verified from the raw run data in `repaired-{C,E,F,D}-…`; local figures
from the clean re-validated runs recorded in the run log, 2026-06-11.)*

Most of these levers were **compensating for the limitations of a ~30B model**. Recording
"this lever is worth +0.083" as a finding would be teaching a future team something that is
no longer true. What generalises is the *shape*: help the model choose what to read rather
than giving it more to read; fix retrieval from the corpus side rather than the prompt side.

The one lever that survives as a *design idea* is answer-snippet self-selection — showing
the agent a short preview of each result's best-matching passage so it can judge relevance
before reading in full. It still helped at frontier tier (+0.037), and it was cheaper than
the alternative, because reading fewer, better chunks costs less than reading more.

### 5.2 Local model serving specifics

Deliberately omitted: throughput figures for particular accelerators, batching behaviour of
particular servers, quantisation choices, and the operational workarounds for a flaky
compute allocation. All of it was true in mid-2026 and none of it will be true, or relevant,
on a different stack. The only durable point is architectural: **a reasoning model that
cannot be told to stop reasoning is a latency problem, and "fast because it does not reason"
is worth verifying** — we asserted that of one model and were wrong, because the server was
simply not reporting reasoning tokens (run log correction, 2026-06-16).

---

## 6. Where the evidence lives

| topic | evidence |
|---|---|
| judge truncation: discovery, audit, fix | `04fbf98`, `0a85dad`, `2e466e0`; original cap `546d5ea`, exposed by `5cf2af3` |
| full re-validation of every contaminated A/B | `ea39736`, `18d5c36`, and the run log |
| guard shipped without an A/B; found inert; fixed | `1bcd4e3`, `19b8f89` → `edb0ca3` |
| 8-question screen and its reversal | `f1cb410` → `516f96e` |
| cohort selected from a single run was noise | `3412ca1` |
| snippet self-selection: screen, confirm | `3e5b8dc`, `b235c3c` |
| enrichment: local win, then non-transfer | `6ed4472`, `6288092` |
| model capacity head-to-head | `a9cd091` (the invalid first verdict), run log 2026-06-11 (clean re-run) |
| frontier-tier results and lever ablations | run directories `repaired-{A,B,C,D,E,F}-…`, `repaired-k27-{A,B}-…` |
| golden question sets | data files retained in the repository (42-question curated; 118-question broad) |
| chunk-id collision measurement | issue #72 |
| narrative record of the whole arc | the model head-to-head run log |

**Caveats on the record itself.** The run log is a contemporaneous execution record, so it
contains conclusions that were later overturned in place — read it forwards, and treat the
2026-06-11 re-check section as authoritative over anything earlier. Win/loss/tie tallies
recomputed here from raw data differ from the run log by one or two questions in a couple of
places, from tie-handling at floating-point precision; the deltas agree.

**The archive was checked, not assumed.** Every commit cited above was verified to resolve
and to be an ancestor of the retained branch, and the frontier-tier run directories were
verified to be tracked in it — the headline figures in §3.4 and the four ablations in §5.1
were recomputed from those files rather than copied from the log. Two things did *not*
survive that check and are called out where they arise: the retrieval-coverage arc (§3.5)
and the guard's re-test measurements (§2.2), both of which lived in a worktree whose branch
was never pushed. A third, the head-to-head plan document, exists only as an unreferenced
object that garbage collection would eventually remove.

That is the practical warning to take from all of this. A retention policy protects what was
committed *and pushed to a retained branch* — nothing else. Work in progress in a local
worktree is not archived, and deleting the worktree destroys it silently, with no error and
no gap in the history to notice later.

---

## 7. If you are starting this again

1. **Build the evaluation harness before the levers, and instrument the harness itself.**
   Assert on what the judge received. Make missing dependencies fail loudly instead of
   falling back. Every hour we spent here was repaid several times; every hour we did not
   spend cost us an arc.
2. **Hold out a question set you never tune on**, and check generalisation before shipping.
3. **Establish early whether your quality gap is model-limited.** If it is, pipeline levers
   will produce a long series of small, real, non-transferring wins.
4. **Measure content duplication in your corpus** before choosing how to identify chunks.
5. **Write down what did not work**, including the things that stopped being true. It is the
   only category nothing else in the system records.
