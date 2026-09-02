# Golden-chunk tuning — results roll-up

One row per (query × config). Recall is at the listed top-k, against the
`## Cited chunks` golden set in each `0N-*.md`. Chunk = strict chunk recall;
Doc = doc-level recall.

> Process note: from iteration 1 (corrected) onward, each query is **grounded
> in the live corpus first** (read current content via `ts-search`/`ts-get`),
> then phrased in user register. See the plan's step 1–2.

## Q01 — conditional field visibility (developer, vocabulary mismatch)

Query: "How can I make one form field show up only when another field has a
certain value in my Altinn app?"
Grounded in: `a979511a6b1b` (Dynamic expressions, reference) + `cab432c85c40`
(Build Expressions in Studio, how-to), the corpus' most-recently-revised
expressions docs.
Goldens (5): `46573caa8ca8` `55885dbe10b6` (core) · `121c6cd3f2b4`
`b639390fced0` `ddd75a7ab3b7` (supporting). Span 2 docs.

### Phase A — parameter sweep (no corpus mutation)

| config_label     | flags                          | recall chunk        | recall doc | notes |
| ---------------- | ------------------------------ | ------------------- | ---------- | ----- |
| baseline @30     | (defaults)                     | 0/5 (0%)            | 0/2 (0%)   | golden docs absent from top-30 |
| baseline @10     | `--top-k 10`                   | 0/5 (0%)            | 0/2 (0%)   | |
| expand-8 @30     | `--expand-queries 8`           | 0/5 (0%)            | 0/2 (0%)   | LLM expansion doesn't bridge register |
| depth-100 (diag) | `ts-retrieve --limit 100`      | 0/5 (0%)            | 0/2 (0%)   | absent even at depth 100 → recall, not ranking |

ColBERT rerank skipped (deliberately): golden absent from the candidate pool at
any depth, so reranking the pool cannot help.

**Ceiling (diagnostic, not a config):** a manual `ts-retrieve` whose query uses
corpus vocabulary ("dynamic expressions hide component based on another field
value", "hidden property equals component", "configure expression in Altinn
Studio") lands 4/5 goldens at ranks 2,3,4,6 — including both core goldens. So
the goldens are fully retrievable *when the query speaks corpus vocabulary*.

**Phase A verdict:** no exposed param recovers this query. Genuine recall /
vocabulary-register gap. → enrichment (Phase B) is the candidate lever.

### Phase C — corpus-aware expansion prototype (query-side lever)

Prototype of the recommended fix: a pseudo-relevance-feedback (PRF) front-end
that harvests *real corpus vocabulary* from the live index, then expands using
it. Deterministic stages (harvest, retrieve, score) were scripted via
`ts-search`/`ts-retrieve`; the planner's LLM-grounding step (select/compose
corpus terms from harvested candidates) was simulated transparently — inputs
and outputs recorded below.

Pipeline:
1. **Probe** the `phrases` collection with the corpus-blind planner's own
   concise expansions (e.g. "hide and show form fields based on value").
2. **Hop-1 harvest** → corpus phrases + source-doc titles, e.g.
   "Show/hide fields based on form values" [Dynamic],
   "form field shown or hidden" [Expressions ← golden doc]. Domain terms
   surfaced: **Expressions**, **Dynamic**, **show/hide fields**.
3. **Hop-2 harvest** (procedural): re-probe `phrases` *scoped to the now-known
   topical docs* for procedural vocabulary, e.g.
   "component will be hidden if you enter John in the firstName component"
   (from core chunk `46573caa8ca8`),
   "choose a component field to add the expression to" (from core chunk
   `55885dbe10b6`).
4. **Retrieve** with the grounded query set (user-intent + harvested phrases).

| config                              | chunk@10 | doc@10 | core goldens | golden ranks |
| ----------------------------------- | -------- | ------ | ------------ | ------------ |
| baseline (corpus-blind planner)     | 0/5      | 0/2    | 0/2          | — |
| corpus-aware, 1-hop PRF             | 3/5      | 2/2    | 0/2          | 1, 5, 8 |
| corpus-aware, **2-hop PRF**         | **4/5**  | **2/2**| **2/2**      | 3, 4, 6, 8 |

Both golden *docs* recover from the 1-hop set; the 2-hop set additionally pulls
both core *procedural* chunks (`55885dbe10b6` GUI steps @3, `46573caa8ca8` JSON
example @4). The only 2-hop miss is supporting chunk `ddd75a7ab3b7` (it was @1
in the 1-hop set — the procedural-focused hop-2 traded it out).

**Verdict:** the query-side lever works. Corpus-aware PRF expansion takes Q01
from 0/5 to 4/5 (both core chunks top-4), purely by sourcing expansion
vocabulary from the live index instead of the LLM's general knowledge.

#### Phase C-auto — blind, automated result (productionized)

The PRF expansion is now productionized in the query-planner skill
(`:expansion-mode :corpus-aware-1hop|:corpus-aware-2hop`), exposed via
`/api/debug/query-planner` (+ `dataset-config-key`) and `bb v3-score
--expansion-compare`. Hop-2 scopes to hop-1's retrieved docs in code; the
grounding is the planner's own (2nd) LLM call. NO golden knowledge involved.

`bb v3-score --v3-dir plans/in-progress/golden-chunk-tuning --expansion-compare true`:

| mode (blind, automated)  | chunk@30 | doc@30 | hit |
| ------------------------ | -------- | ------ | --- |
| blind                    | 0/5      | 0/2    | — |
| corpus-aware-1hop        | 1/5      | 1/2    | `121c6cd3f2b4` (#3) |
| corpus-aware-2hop        | 1/5      | 1/2    | `121c6cd3f2b4` (#1) |

Real lift (0 → 1/5 chunk, 0 → 50% doc); 2-hop improves *ranking* (#3→#1) but
recovers no extra goldens. **Well below the hand-tuned 4/5 ceiling — the honest
number.** Two diagnosed reasons (planner output inspected):

1. **Grounding stays conceptual.** Automated grounded queries:
   `["show/hide fields based on form values" "form field shown or hidden"
   "dynamic rules show hide fields" "dynamics to show or hide fields"
   "conditional rendering rules"]`. It surfaced the conceptual golden chunk
   ("form field shown or hidden") but never reached the precise domain term
   **"dynamic expressions"** nor the procedural vocabulary the ceiling used.
2. **Blind hop-2 mis-scoped.** `hop1-docs = [7f9395781d54 17fa0f18533d 35abbbf11529
   c128b3970ecb e11b30869cc1]` — **none are the golden docs**. The ceiling
   cheated by scoping hop-2 to the golden doc_nums; the blind harvest's top-5
   docs aren't the golden docs, so the (correctly blind) hop-2 targeted the
   wrong docs and recovered no procedural goldens.

**Takeaway:** the lever is real but the naive automated harvest/grounding
captures only a fraction of the ceiling. Tuning levers to close the gap (future
work, not yet done): rank/expand the hop-1→hop-2 doc scope wider; feed harvested
doc *titles* (not just phrases) into grounding; prompt grounding to prefer the
single most domain-specific term. None of these are validated yet.

#### Phase C-auto-v2 — tuning attempt to close the gap (partial, with caveats)

Applied three levers, learned which help:

- **RRF aggregation across probes (kept).** `harvest-search-phrases` now returns
  `:probe-rank`; the planner aggregates candidates by Reciprocal Rank Fusion
  (rank-based, comparable across probes) instead of raw `rank_fusion_score`
  (per-query, NOT comparable — a first "score-sort" attempt *regressed* to 0/5
  by mixing scales). RRF surfaces phrases that rank high across multiple probes,
  and **does** float a golden doc (`a979511a6b1b`) into hop1-docs, so hop-2 then
  harvests its content and the grounded queries finally include the domain term
  ("dynamic expressions Altinn 3 app", "expressions evaluated in Altinn").
- **Wider hop-2 doc scope (kept):** top-5 → top-10.
- **Select-verbatim grounding (reverted):** constraining the LLM to *select*
  from the noisy candidate pool under-performed *generate*-grounding (the latter
  produces golden-matching phrasings). Kept generate-grounding, biased toward
  harvested vocabulary.

Result — `--expansion-mode corpus-aware-2hop`, **three identical runs**:

| run | chunk@30 | doc@30 |
| --- | -------- | ------ |
| 1   | 0/5      | 0/2    |
| 2   | 2/5      | 2/2    |
| 3   | 1/5      | 1/2    |

**The dominant finding is VARIANCE, not the mean.** The result swings 0→2/5
chunk / 0→100% doc across identical runs, gated on whether the grounding LLM
(temp 0.1) emits the bridging term "dynamic expressions" that round. So:
- Best case now reaches 2/5 chunk + 100% doc **without leakage** (RRF surfaced
  the golden doc legitimately) — genuine progress over the 1/5 first cut.
- But a single run is unreliable; the 4/5 hand-tuned ceiling is NOT reproduced
  blind, and was inflated by golden-doc-scoped hop-2 + hand-picked terms.

**Honest conclusion for Q01:** corpus-aware PRF gives a real but **high-variance**
lift on this hard vocabulary-mismatch query; doc recall can reach 100% on good
runs, chunk recall ~0–2/5. Stabilising it (lower grounding temperature toward 0;
union of 2 grounding samples; title-grounding) is plausible future work but
unproven, and the gain may be Q01-specific — generality must be checked on more
queries before investing further. 1-hop alone is too weak here (needs the hop-2
domain-term harvest).

#### Caveats / cost (for a production design)

- **LLM-grounding simulated.** A real planner replaces the human selection step
  with an LLM call conditioned on the harvested candidates. Quality depends on
  that prompt; the numbers here are a near-ceiling for the lever, not a
  guaranteed automated result.
- **Latency/cost.** 2-hop adds a first retrieval pass to identify topical docs
  (+ potentially a 2nd LLM call). ~2× retrieval, ~1–2× LLM vs the current
  planner. The 1-hop variant (single extra phrase-search, no 2nd hop) already
  recovers both docs at 3/5 and is much cheaper — a candidate default.
- **Generality untested.** Confirmed on one query. Phase Next: replicate on the
  authorization case and on iterations 2–3 before treating PRF as the
  generalizing fix.

### Phase B — enrichment sub-loop

(deferred — the query-side lever (Phase C) is the better generalization bet;
enrichment kept as the corpus-side comparison if PRF proves insufficient on
later queries. No Typesense mutation performed.)

## Prior observation — authorization "give employee access" (pre-correction Q01)

Produced before the grounding step was added (query invented first, then found
to be corpus-valid in hindsight). Kept because the failure mode **replicated**
the Q01 pattern exactly:
- Query: "How do I give an employee in my company access to a service in Altinn
  on behalf of the organisation?" (lay register).
- Goldens: 5 chunks in `a364eb308056` (Access management API guide).
- baseline / expand-6 / expand-10 / depth-100 → all **0/5**.
- Ceiling (corpus-vocab "delegate access package / individual rights to a
  person, connections API") → core goldens at #1 and #3.

## Cross-cutting signal (after 2 grounded-equivalent queries)

Both independent queries fail identically: **lay/user-register query → 0 recall;
`--expand-queries N` does not bridge; the same goldens are top-ranked once the
query uses corpus vocabulary.**

## Query-side gap — ROOT CAUSE (probed at the checkpoint)

The query-planner (`digdir.skills.builtin.query-planner`,
`/api/debug/query-planner`) is **corpus-blind**. Its prompt asks the LLM to
expand "from different angles" toward answer-passage phrasing, but the LLM uses
general knowledge, never the corpus's actual terminology.

Direct evidence — planner output for Q01:
```
user-intent: <verbatim user query, EN — corpus-language translation is OFF>
expansions:  conditional display of form fields
             show field only when another field has a certain value
             conditional visibility in Altinn app
             hide and show form fields based on value
             field visibility rules in Altinn
```
All conceptual paraphrases in the *user's* register. None contains the corpus's
domain term **"dynamic expressions"** (Altinn's word for conditional form
logic).

Isolating the missing ingredient:
- Planner's own 5 expansions, run verbatim through `ts-retrieve` → **0/5**.
- Same 5 expansions **+ one corpus term** ("dynamic expressions hidden property
  component") → golden doc `a979511a6b1b` recovers at ranks 2–3.

So the entire 0/5 → recovery delta is one missing bridge word. The planner
produces semantically-correct paraphrases that never reach the corpus's
idiosyncratic vocabulary.

### Generalizing fix candidates (query-side, preferred over per-chunk enrichment)

1. **Corpus-aware expansion (pseudo-relevance feedback).** Before final
   retrieval, harvest real vocabulary near the topic from the live index (a
   cheap first-pass retrieval, or the `phrases`/titles/headers of top candidate
   docs) and feed those terms to the planner so expansions use *actual corpus
   terms*. Robust to continuous corpus revision (terms come from the index, not
   a static list). **Recommended.**
2. **Phrase-collection grounding.** Condition expansion on the existing
   `phrases` enrichment collection (retrieve candidate phrases, expand toward
   them).
3. **Glossary injection** — brittle; goes stale as the corpus changes. Not
   preferred.

This is a corpus-wide lever (one planner change helps every register-mismatched
query), in contrast to Phase B enrichment which fixes one chunk at a time.
Both should be measured; the query-side fix is the better generalization bet.

## Phase D — generality validation (6 grounded queries, anti-overfit)

To avoid overfitting the PRF mechanism to Q01, validated on 5 fresh queries
grounded in distinct products / registers (Q02 Dialogporten, lay-definitional;
Q03 system-user, dev-mismatch; Q04 events, lay-mismatch; Q05 options,
**corpus-aligned control**; Q06 correspondence, lay-mismatch). `bb v3-score
--v3-dir plans/in-progress/golden-chunk-tuning --expansion-compare`.

| mode               | chunk@30        | doc@30          |
| ------------------ | --------------- | --------------- |
| blind (raw query)  | 0/19 (0%)       | 0/7 (0%)        |
| corpus-aware-1hop  | 6/19 (31.6%)    | 4/7 (57.1%)     |
| corpus-aware-2hop (run A) | 9/19 (47.4%) | 6/7 (85.7%)  |
| corpus-aware-2hop (run B) | 6/19 (31.6%) | 4/7 (57.1%)  |

Per-query 2-hop (run A / run B doc-recall):
- Q01 expressions: 2/5,1/0 chunk — variance (flips 2/2↔1/2 doc)
- Q02 dialogporten: 2/3 chunk, 1/1 doc — **stable hit**
- Q03 system-user: 1/3 ↔ 0/3 — variance (1/1 ↔ 0/1 doc)
- Q04 events: 1/2 chunk, 1/1 doc — **stable hit**
- Q05 options (aligned control): 3/3 chunk, 1/1 doc — **stable, full recovery**
- Q06 correspondence: 0/3, 0/1 — **consistent FAILURE** (lay "letter" never
  bridges to "correspondence/messages")

### Assessment

1. **The lever generalizes.** Across 6 diverse grounded queries (5 products,
   mixed registers) corpus-aware expansion lifts doc recall from **0% (blind) to
   57–86%** and chunk recall from **0% to 32–47%**. The effect is NOT
   Q01-specific — it helps 4–5 of 6 queries every run. This is the anti-overfit
   check passing.
2. **2-hop ≥ 1-hop** (run A: 9/6 vs 6/4). The hop-2 domain-term harvest matters.
3. **Variance is the main weakness.** Aggregate doc recall swings 57%↔86% between
   identical runs; Q01 and Q03 flip hit↔miss on grounding-LLM sampling. Q02/Q04/
   Q05 are stable. Stabilising (grounding temperature → 0; union of 2 grounding
   samples; more grounded phrases) is the clear next lever.
4. **One consistent failure: Q06 (correspondence).** The lay framing "official
   letter to a company" never bridges to the corpus's "correspondence / send
   messages". The harvest doesn't surface the correspondence docs — a case PRF
   alone doesn't fix; a candidate for the enrichment lever (Phase B) or a
   title-grounded harvest.
5. **Even the aligned control (Q05) needed expansion.** Blind raw-query retrieval
   scored 0/3 even with corpus-term phrasing; corpus-aware fully recovered it
   (3/3). Caveat: "blind" here = raw query with NO expansion; the comparison is
   corpus-aware-expansion vs no-expansion (blind-WITH-expansion was also ≈0 on
   Q01, slices earlier).

### Verdict for the generalize-vs-specialize question

At this early stage the evidence favours a **generalizing** query-side fix
(corpus-aware-2hop expansion) over per-query specialization: one mechanism,
applied blind, recovers 0→~50–86% across unrelated topics. It is not yet
production-ready (variance + the Q06-style bridging gap), but it is the right
arc to invest in. Next: (a) stabilise the grounding call, (b) characterise Q06's
failure mode, (c) expand the query set further before any production default.

## Phase E — variance discrimination arc

Two experiments to localise the Phase-D run-to-run variance. (Full ranked
source list lives in plans/proposed/corpus-aware-prf-expansion-plan.md.)

### Exp 1 — planner determinism (5 runs each, Q01/Q03/Q05, 2hop)

- **No fallbacks** in any of the 15 runs (`:fallback? false`,
  `:corpus-aware-fallback? false`). → **Source #4 (silent blind fallback) RULED
  OUT**; the Phase-D 0-runs were genuine retrieval misses, not LLM failures.
- **`:queries` vary every run for ALL three queries**, including the
  recall-"stable" Q05. → variance is **planner-side** (#1 sampling + #2
  two-stage amplification), not downstream.
- **Mechanism visible in `:hop1-docs`:** Q01 runs 2 & 5 contain the golden doc
  `a979511a6b1b`; their queries then include "dynamic expressions Altinn 3 app"
  / "form field shown or hidden" → hits. Runs 1/3/4 lack it → queries stay
  conceptual ("conditional rendering rules", "if someField is equal to
  someValue") → miss. So recall hinges on **whether RRF floats the golden doc
  into hop1-docs**, which depends on call-1's (varying) blind probes.
- **Why Q05 is recall-stable despite varying queries:** its topic (options/code
  lists) is densely covered by many phrases, so any reasonable phrasing hits.
  Q01/Q03 goldens hinge on ONE bridge term that appears only some runs.

### Exp 2 — auto-filter footgun (#3): on vs off, fixed good query set

- The `public-docs` website corpus has **no `orgs_long`/`orgs_short` facet**
  (those are kudos-pipeline fields) and the queries contain no 4-digit years.
- `ts-retrieve` with the same good query set, `--auto-filter true` vs `false`:
  **identical goldens at identical ranks** (ddd75a7ab3b7 #3, 121c6cd3f2b4 #9).
- → **#3 is INERT on public-docs.** But it is a **latent footgun**: on an
  org-tagged corpus (kudos has `orgs_long`), auto-filter runs on the *varying*
  grounded queries and could add an org filter that excludes goldens — and
  v3-score leaves `:auto-filter "true"`. Dormant here, dangerous elsewhere.

### Conclusion — confirmed sources & fix priority

1. **#1/#2 planner LLM sampling + two-stage amplification — PRIMARY, confirmed.**
   Fix: drop grounding (+ intent) `temperature` to 0; consider union of 2
   grounding samples and/or more grounded phrases to make bridge-term inclusion
   reliable. (Won't be fully deterministic — Azure batch/MoE — but should shrink
   the swing.)
2. **#5 RRF tie-break — plausible secondary, cheap.** `group-by` hash-map order
   makes equal-RRF ties unstable. Add a deterministic secondary sort key. (Hard
   to isolate from #1/#2 until temp is fixed; do it anyway — free.)
3. **#3 auto-filter — inert here, latent footgun.** Flip `:auto-filter` OFF in
   the measurement harness for isolation (matches the ts-retrieve convention),
   and treat ON-by-default as a hazard for org-tagged corpora.
4. **#4 silent fallback — ruled out**, but surface `:fallback?` /
   `:corpus-aware-fallback?` in v3-score output so future 0-runs are diagnosable.
5. **#6 ANN/#7 environmental — not isolated; likely minor** given #1/#2 dominate.

## Phase F — variance fixes applied + re-measured

Applied (commit pending): grounding call `temperature → 0`; intent/expansion
call `temperature → 0` in corpus-aware mode (blind keeps 0.1); deterministic RRF
tie-break (secondary sort by phrase); `bb v3-score --auto-filter` flag and
re-ran with `--auto-filter false` to isolate the #3 confound.

corpus-aware-2hop, `--auto-filter false`, three runs:

| run | chunk@30      | doc@30        | Q01    | Q03 |
| --- | ------------- | ------------- | ------ | --- |
| 1   | 9/19 (47.4%)  | 6/7 (85.7%)   | hit    | hit (1/3, 1/1) |
| 2   | 9/19 (47.4%)  | 6/7 (85.7%)   | 2/5,2/2| 1/3, 1/1 |
| 3   | 7/19 (36.8%)  | 4/7 (57.1%)   | 0/5,0/2| 1/3, 1/1 |

1-hop (run 1): 7/19 (36.8%), 4/7 (57.1%).

**Outcome:** variance reduced, not eliminated.
- **Q03 stabilised** — flipped 1↔0 before, now 1/1 doc every run. temp→0 fixed
  queries whose variance rode a *secondary* signal.
- **Q01 still flips** (runs 1/2 hit, run 3 collapses to 0). Confirms the
  analysis's caveat: `temperature 0` is NOT deterministic on Azure (batch FP /
  MoE routing), and the **two-stage amplification** converts that residual
  jitter in call-1's probes into "does RRF surface the lone golden doc
  `a979511a6b1b` into hop1-docs". Q01's recall hinges on one borderline bridge.
- **auto-filter off: no change on public-docs** (inert, per Exp 2) — kept as
  correct isolation hygiene for org-tagged corpora.
- Deterministic RRF tie-break removes one nondeterminism source but can't help
  when the *upstream probes themselves* vary.

**Remaining lever (not done):** make hop1-docs reliably include a borderline
golden doc despite probe jitter — e.g. deeper per-probe harvest (`limit` 8 → N),
RRF weighting toward topically-concentrated docs, or seeding the harvest with a
doc-title search. This targets the two-stage amplification directly rather than
the (irreducible) LLM determinism.

## Phase G — 10× generality (59 queries) + fallback quantification

Widened to 59 grounded queries (Q01–Q60 minus the Q07/Q49 dup) across 7 product
areas, built in parallel by area-scoped subagents. 159 distinct golden chunks,
60 golden docs. Ran `bb v3-score --expansion-compare --auto-filter false` with
the doc-level truncation fix (batched chunk→doc lookup) in place.

| mode               | chunk@30        | doc@30          |
| ------------------ | --------------- | --------------- |
| blind (raw query)  | 16/159 (10.1%)  | 17/60 (28.3%)   |
| corpus-aware-1hop  | 54/159 (34.0%)  | 35/60 (58.3%)   |
| corpus-aware-2hop  | 57/159 (35.8%)  | 35/60 (58.3%)   |

### Verdict: corpus-aware expansion GENERALIZES (robustly, at scale)

- 1-hop lifts chunk recall **3.4×** (10.1→34.0%) and doc recall **2.06×**
  (28.3→58.3%) over a realistic blind baseline, across 59 diverse queries
  spanning every product area. No longer a single-bridge-term artifact — it
  holds broadly. This is the strongest evidence yet for a *generalizing*
  query-side fix over per-query specialization.
- **2-hop ≈ 1-hop at scale** (chunk 34.0→35.8%, doc identical 58.3%). The
  expensive 2nd harvest hop, which clearly helped on the 6-query set, adds
  almost nothing in aggregate. → **1-hop is the cost-effective default;** reserve
  2-hop (if at all) for known hard single-bridge cases.

### Fallback quantification (Azure flakiness impact)

Server log over the run(s): **16 intent-call total failures** (→ whole query
degrades to raw/blind) + **5 grounding-call failures** (→ corpus-aware falls
back to blind) + 67 transient retries that recovered. Counts are an upper bound
(log spans the earlier killed run); the re-run's share is ~13 + ~4.

Against 118 corpus-aware query-modes, **~14% silently degraded to blind.** So:
- The measured 1hop/2hop numbers are **UNDERSTATED** — true lift is higher (the
  generality verdict is therefore conservative/robust).
- The flakiness is **material, not tolerable noise** — enough to depress results
  by a meaningful margin. This justifies the **single-call collapse** (halves the
  per-query LLM calls → halves the failure surface; see the PRF plan's teed-up
  design). It is NOT clearly a 429/throughput cap (errors were timeouts + Azure
  5xx "contact Microsoft" backend errors), so the fix is on our side, not a quota.

### Next

1. Implement + A/B the single-call collapse (Design 1) — expect higher *and*
   more stable corpus-aware numbers (fewer fallbacks) plus ~½ latency/cost.
2. A cleaner re-run when Azure is calmer would raise the measured lift toward its
   true value.

## Phase H — single-call collapse A/B (Design 1)

`:expansion-variant :one-call` drops the blind-expansion LLM call: deterministic
raw-query probes (`keyword-probes`) seed the harvest, one grounding call extracts
intent + grounds the expansion. 1 LLM call/query vs two-call's 2.

| variant   | mode | chunk@30      | doc@30        | LLM calls/q |
| --------- | ---- | ------------- | ------------- | ----------- |
| (blind)   | —    | 16/159 (10.1%)| 17/60 (28.3%) | 0 |
| two-call  | 1hop | 54/159 (34.0%)| 35/60 (58.3%) | 2 |
| two-call  | 2hop | 57/159 (35.8%)| 35/60 (58.3%) | 2 |
| one-call  | 1hop | 42/159 (26.4%)| 33/60 (55.0%) | 1 |
| one-call  | 2hop | 50/159 (31.4%)| 34/60 (56.7%) | 1 |

**Confound:** the one-call run hit calm Azure (0 retries, 0 fallbacks — clean
prf5 log); the Phase-G two-call run was during turbulence (~14% fallbacks
depressing it). So two-call's true recall is even higher → one-call's recall
deficit is real and somewhat understated.

### Verdict: genuine quality/cost tradeoff, not a dominant winner

- **Two-call has higher absolute recall** (+~5–8pp chunk, +~2–3pp doc). The
  LLM-blind-expansion probes harvest better than deterministic raw-query probes
  — probe quality is where one-call loses ground.
- **One-call is far cheaper & more robust by construction:** 1 LLM call (½
  latency/cost) and one failure point instead of two (so under Azure turbulence
  its fallback rate should be ~½ two-call's — couldn't measure directly here as
  Azure was calm).
- **Recall-per-LLM-call favors one-call** (26.4%/call vs 17.0%/call at 1hop);
  **absolute recall favors two-call.**
- **For one-call, 2-hop is worth it** (+5pp chunk over 1hop), unlike two-call
  where 2hop≈1hop. one-call-2hop reaches ~88% of two-call-2hop chunk recall and
  ~97% of its doc recall **at half the LLM cost**.

### Decision options

1. **Keep two-call default** (max recall), one-call as a cost/latency option.
2. **Adopt one-call-2hop** for production (½ cost, ½ failure surface, ~90% of the
   recall) — strong if cost/latency/reliability dominate.
3. **Close one-call's gap by improving its deterministic probes** (more
   keyword/n-gram probe variants, or a cheap title-search seed) — recover recall
   without re-adding an LLM call. The gap is probe quality, so this is the
   targeted lever.

### Decision (chosen): keep two-call as default

Two-call remains the default `:expansion-variant` (max recall). `:one-call` stays
available as an opt-in cost/latency/robustness option (`--expansion-variant
one-call`), not the default. Option 3 (improving one-call's deterministic probes
to close the recall gap) is noted as future work, not pursued now.

## Phase I — enrichment lever (corpus-side), Strategy 1: typical-question bridging

The query-side PRF couldn't close Q06 (correspondence "official letter to a
company" ↔ corpus "send messages"). Strategy 1 attacks it corpus-side: attach
content-derived, user-vocabulary typical questions to the chunks
(hypothetical-questions enrichment collection), so a lay query matches
question↔question.

Mechanism (validated end-to-end):
- Applied 9 hand-authored bridging questions across Q06's 3 golden chunks via
  `execute-apply-questions` (collection auto-created;
  `website_enrichment_hypothetical_questions_*`; tagged prompt-hash
  `q06-bridge-v1` for clean revert). Ran via `clojure -M:dev` (no CLI/endpoint
  exists for apply — the machinery gap).
- Measured with `bb ts-retrieve <Q06 raw query> --enrichment-types
  hypothetical-questions` (v3-score does NOT forward enrichment-types, so scored
  via ts-retrieve directly).

Result (Q06, top-30 chunk recall):
| condition                                   | goldens hit |
| ------------------------------------------- | ----------- |
| before (collection empty)                   | 0/3 |
| control: same query, no --enrichment-types  | 0/3 |
| **after: + --enrichment-types hypothetical-questions** | **2/3** (e00057498d4d #29, ebd89d3a9496 #30) |

**Verdict: the bridging strategy works** — recovers a gap the query-side lever
could not (0→2/3, incl. the core golden). **But ranking is weak** (#29–30): the
enrichment hit is one sibling strategy and, with no content/phrase match on the
lay query, lands at the tail. Retrievable ≠ top-ranked — at top-10 it'd still
miss. Refinement levers (untested): raise enrichment strategy weight; add
verified-phrases alongside questions; user-intent union.

Index state: Q06 enrichment rows left in place (dormant — production/v3-score
don't pass enrichment-types), tagged `q06-bridge-v1`, revertible via
`execute-revert-chunk`. Note: deploying this lever to production would also
require enabling enrichment-search-targets in the production retrieval path
(a separate productionization step, like expansion-mode was).

### Strategy 1 generality (3 queries, 3 products)

Tested the typical-question bridging strategy on two further lay/mismatch
bridging gaps (raw retrieval = 0 for both), different products:

| query | area | before (raw) | after (+enrichment-types) | ranks |
| ----- | ---- | ------------ | ------------------------- | ----- |
| Q06   | correspondence        | 0/3 | 2/3 (incl. core) | 29, 30 |
| Q08   | authorization-consent | 0/3 | 2/3 (incl. core) | 26, 27 |
| Q23   | dialogporten          | 0/3 | 2/3 (incl. core) | 20, 30 |

**Generality verdict: the bridging strategy works broadly** — 0/3→2/3 on every
bridging-gap query tested, always recovering the *core* golden, across 3
products. Two systematic patterns (not query-specific):
- **Weak ranks (20–30):** enrichment is a lone matching strategy on these lay
  queries, so its hits land at the tail of top-30 (would miss at top-10).
  Retrievable, not yet top-ranked. → gating refinement (task: enrichment
  strategy weight; combine with corpus-aware expansion; verified-phrases too).
- **One golden/query consistently missed** (the weakest-bridged chunk). 2/3
  incl. core is the reliable floor.

Enrichment rows tagged `q06-bridge-v1` (Q06) and `gen-bridge-v1` (Q08/Q23);
dormant outside `--enrichment-types` runs; revertible per chunk.

**Portfolio status:** Strategy 1 (typical-question bridging) = validated,
generalizes, needs ranking work. No bridging FAILURE found yet (it works on all
3) — alternative strategies (verified-phrases, fact-assertions) still to be
exercised on query types where questions underperform (e.g. factual-lookup).

### Phase J — production combo: corpus-aware expansion + enrichment

Tooling fixed: `v3-score --enrichment-types` (forwards to retrieve), `bb
enrich-apply`/`enrich-revert` (+ `digdir.skills.enrichment.manual` CLI; the
clojure -M:dev path, since enrichment skills are dev-only).

Scored the 3 enriched queries (Q06/Q08/Q23) comparing enrichment-only
(blind+enrich) vs combo (corpus-aware expansion + enrich):

| query | enrichment-only rank | combo rank (best run) |
| ----- | -------------------- | --------------------- |
| Q06   | #29–30               | **#8** (2hop) / #12,#17 (1hop) |
| Q08   | #26–27               | #23–24 |
| Q23   | #15–20               | **#1** (1hop) |

**The reinforcement hypothesis holds.** Enrichment-only lands goldens at the
tail (15–30; top-10 = 0/9). The combo lifts them — Q23 to #1, Q06 to #8 —
because corpus-aware expansion adds a content/phrase hit on the SAME chunk the
enrichment question also matches, so two weak signals sum to a strong merged
rank. Caveats: (1) variance-affected (Q23 #1 at 1hop but missed at 2hop that
run); (2) uneven (Q08 lifts less); (3) top-30 recall unchanged (~4/9) — the
combo RE-RANKS the already-recoverable goldens upward, it doesn't recall more.
Not yet RELIABLY top-10 (needs the known variance fix + possibly enrichment
strategy weighting).

Production deployment of enrichment (wiring enrichment-search-targets into
build-skill-params-from-config, like expansion-mode) is deferred until the combo
ranks reliably in top-10 — premature to default-on an uneven signal.

## Phase K — portfolio: fact-assertions vs questions on a factual lookup

Hypothesis: typical-question bridging underperforms on precise factual/entity
lookup, where fact-assertion triples should win. Tested on the access-management
scopes chunk `33c85fc05357` (a dense scope table incl. `...toothers.write` =
"Create, update and delete access given to others").

Query (factual): "Which permission or scope does a system need to delete access
that was given to others?" Answer chunk = `33c85fc05357`.

| condition (raw query + …)        | answer-chunk rank |
| -------------------------------- | ----------------- |
| no enrichment                    | absent (>30)      |
| Strategy 1: hypothetical-questions | **#9** (top-10) |
| Strategy 3: fact-assertions      | #12               |
| both (questions + facts)         | **#1**            |

Applied via `bb enrich-apply` (specs tagged `scope-q-v1` / `scope-fact-v1`).

**Findings (honest — hypothesis NOT confirmed):**
- **Questions did not underperform on factual lookup** — recovered the chunk to
  #9 (top-10), slightly *better* than facts (#12). Strategy 1 is more robust
  than predicted; factual lookup didn't break it.
- **Fact-assertions work** (#12) and are a viable bridge, but weren't *needed*
  here.
- **Strategies STACK.** Questions + facts → #1. Multiple enrichment signals on
  the same chunk reinforce — the same effect as corpus-aware-expansion +
  enrichment (Phase J). The portfolio strategies are **complementary and
  additive, not partitioned by query type.**
- **No clean "questions-fail / facts-win" case found yet.** A genuine one likely
  needs cross-chunk disambiguation (a specific entity/value spread across many
  sibling chunks), which this single-table chunk didn't exercise.

**Portfolio so far:** Strategy 1 (questions) = primary, broad bridge (lay gaps +
factual lookup). Strategy 3 (facts) = complementary, stacks for higher rank.
Strategy 2 (verified-phrases) = untested. Reinforcement/stacking is the
through-line: combine signals (expansion + question + fact) on a chunk to lift
rank toward the top. Enrichment rows tagged + dormant + revertible.

## Phase L — variance reduction + top-20 cutoff

Levers: deeper PRF harvest (per-probe limit 8→12; hop-2 doc scope 10→15, so a
borderline golden doc reliably enters hop-2) + adopt **top-20** as the operative
cutoff (context-window headroom). Re-measured combo (2hop + hypothetical-questions)
on Q06/Q08/Q23, 3 runs:

| run | chunk@20    | doc@20      |
| --- | ----------- | ----------- |
| 1   | 5/9 (55.6%) | 2/3 (66.7%) |
| 2   | 5/9 (55.6%) | 3/3 (100%)  |
| 3   | 5/9 (55.6%) | 3/3 (100%)  |

**Aggregate chunk recall is now stable (5/9 every run)** — vs the 3–4/9 swing at
top-30 before. Doc recall reliably ~100% (2/3 runs 3/3). Ranks strong: goldens
reach #1–#5 (e.g. Q06 #1/#4/#5). Residual: per-query *which-golden* still jitters
(Q23 dropped to 0 in run 1), but the top-20 cutoff + deeper harvest absorb it at
the aggregate/doc level. **Reliable enough to deploy.** (Seed-based LLM
determinism left as a further lever if needed; not required to clear top-20.)

### Deploy scoping (enrichment → production)

- **No classpath blocker:** `rag/retrieval.clj` (core prod) already requires
  `digdir.skills.enrichment.collections`, so the enrichment collection-name
  resolver is on the production classpath despite living in src-dev.
- **Resolution point = agent-setup, NOT build-skill-params-from-config.** The
  config builder gets a *flattened* config map (`:retrieval-top-k` …) with no
  pipeline-config, so it can't resolve enrichment collection names. The retrieval
  skill consumes a pre-resolved `:enrichment-search-targets` map. Resolution must
  happen at agent-setup (`graphs.clj`), which has the pipeline-config and already
  computes docs/chunks/phrases collection names.
- **Deploy plan:** config key `skills.retrieval.enrichment-types` (default
  "hypothetical-questions", `:ownership :inherit`); agent-setup resolves it to
  `:enrichment-search-targets` via `enrichment-collection-names` and threads it
  to the retrieval skill; set operative top-k to 20. More involved than the
  expansion-mode config key (it's graph-level wiring). Tracked as a task.

## Phase M — trace analysis: the "confabulation" was a real bug

User reported the agent-rag-agent (builtin/agent-rag-agent, same agent-rag-graph
+ retrieval/query-planner skills as altinn-docs-tuned) refusing to retrieve:
"the search engine requires an explicit user-intent field not available in this
tool signature." Trace analysis (server/logs/graph-trace-agent-rag-graph-bundled-*)
showed it was a TRUE report, not a confabulation:

```
{:source :search :tool "search" :issue-type :missing-inputs
 :message "Missing required inputs: (:user-intent)"
 :details {:required [:queries :user-intent :docs-collection :chunks-collection :phrases-collection]
           :missing (:user-intent)}}
```

- **Root cause:** `rag/skills/core.clj/check-required-inputs` treats EVERY entry
  in a skill's `:inputs` as required. The retrieval skill listed `:user-intent`
  in `:inputs` (added by slice-23 commit fbf49e4), but it's documented optional.
  The agent's `search` tool only provides `:user-intent` after a prior
  `plan_queries`; a direct `search` → "Missing required inputs" → 0 retrieval →
  the agent accurately reported it couldn't search.
- **Pre-existing, NOT from the enrichment/expansion deploy.** Both recent chats
  hit it (14:54: 302×, 20:45: 8×); a May-28 trace didn't. So the improvements
  appeared absent because retrieval never ran — blocked at the input gate.
- **Fix (commit 10760e5):** remove `:user-intent` from retrieval `:inputs`. Still
  received when provided (resolved-inputs is a full merge, not a select), so the
  slice-23 union is unaffected.
- **E2E verified** through the agent's exact sub-skill path (tools/execute-sub-skill
  → build-execution-context → check-required-inputs → retrieval): a `search`
  with NO `:user-intent` now returns 30 chunks (was 0/blocked) and the enriched
  correspondence golden surfaces.

**Verification-layer lesson:** my v3-score / ts-retrieve harness exercised the
debug *retrieve endpoint* (queries passed directly), NOT the agent's tool-call
input-validation, so it was blind to this agent-path bug. The trace files
exercised the real agent path. Future: validate at least one query through the
actual agent (not just the debug harness) before declaring a deploy good.

## gc-rerun (2026-05-31) — 25 lifted queries, N=3, enrichment off vs on (sweep runner)

End-to-end via the batch eval-sweep methodology (marginal A/B, corpus-aware on both,
enrichment the only delta), 150 agent runs. Aggregate (75 runs/config):

| metric | off | on |
|---|---|---|
| golden-in-pool | 0.84 | 0.89 |
| golden-in-display (top-20) | 0.75 | 0.85 |
| golden-read | 0.61 | 0.69 |
| recall@20 | 0.36 | 0.45 |

Per-question display (majority of 3): **9 lifted, 1 dropped, 15 unchanged**.
- 15 unchanged = saturation (corpus-aware already covers them).
- 9 lifted = real gap-fills.
- 1 dropped = dilution (gc-09), the intermittent displacement from the (B) probe.
Net: enrichment helps (display +0.10, recall@20 +0.09); saturation dominates;
dilution real but rare. Validates the net-effect batch gate.
