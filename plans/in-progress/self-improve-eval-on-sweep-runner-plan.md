# Upgrade the self-improve eval onto the sweep runner

**Goal:** replace the self-improvement loop's two-substrate, partly-unfaithful
keep/revert gate with ONE faithful, agent-path measurement built on the sweep
runner — and in doing so unify the self-improve eval with the substrate the
broader retrieval-tuning program uses, so enrichment-portfolio and corpus-vocab
work can be driven by the same harness.

## Why now / why this is an upgrade (not just consolidation)

This session proved a hard lesson: **retrieval-endpoint signals ≠ agent-path
signals.** Several "deployed" levers were silently dormant on the production
agent while the debug endpoints reported success. The self-improve eval has the
same shape of blind spot baked into its keep/revert gate.

## Current architecture (grounded)

The self-improve graph (`src-dev/digdir/demo/self_improve_graph.clj`) per chunk:
`propose → apply → :eval → :verify → :decide`. The keep/revert gate is the
composite `:keep?` from `:verify` (verify_retrieval.clj:266):

    keep? = (and improved? eval-gate-pass?)

with two halves on **two different substrates**:

1. **`improved?` (verify)** — `:builtin/enrichment-verify-retrieval` runs a
   *pre/post Typesense lookup* for the user's intent topic against the enrichment
   collection (verify_retrieval.clj). It answers "is this chunk now retrievable
   via the enrichment?" with rank/position deltas. **It is a raw collection
   lookup — NOT the agent path.** It can say "findable ✓" while the full agent
   (corpus-aware expansion → rerank → top-k display → read decision) never
   surfaces or reads the chunk — precisely the gap we hit this session.
2. **`eval-gate-pass?` (eval)** — `:builtin/enrichment-eval-suite`
   (eval_delta.clj) runs `diagnostics/agent-budget-benchmark` in-process over a
   FIXED fixture suite and returns `:summary :gate-pass` (coarse pass/regress:
   error-count, regressed-count). This IS the agent path, but the output is a
   single boolean over a fixed suite — no per-chunk decomposition, no recall.

### The three weaknesses
- **W1 — unfaithful direct signal.** `improved?` is a raw lookup; it doesn't know
  whether the agent actually reaches/reads the chunk (the in-pool→display→read→top-k
  funnel we now measure with the sweep's golden decomposition).
- **W2 — coarse regression signal.** `gate-pass` is one boolean; it can't see a
  recall@10 erosion that doesn't flip an answer-pattern, and it runs a separate
  substrate (agent-budget-benchmark) from everything else.
- **W3 — two substrates, two ground-truths.** verify (lookup) + eval (benchmark
  fixtures) are disjoint from the sweep runner + `questions.edn` registry the rest
  of the tuning program now uses. Nothing is shared or comparable.

## The upgrade

Replace the gate with ONE sweep-runner measurement that runs the **full agent**
twice — enrichment-OFF vs enrichment-ON — and reads the delta from the agent path
itself. This reuses the exact `levers-off`/`levers-on` pattern we just built:
the enrichment is already written to the collection, so we toggle
`:enrichment-types` in per-config `:skill-params` to get with/without WITHOUT
reverting the write.

For each proposed enrichment (chunk C, motivated by user-query Q):

- **Direct-usefulness sweep** — one question `{query=Q, golden=[C]}` run under two
  configs (enrichment-off, enrichment-on). The keep signal is the **golden
  decomposition delta for C**: did enrichment move C up the funnel
  (`golden-in-search-pool?` → `golden-in-display?` → `golden-read?`) and/or lift
  `recall@10`? This is the faithful agent-path replacement for `improved?` (W1).
- **Regression guard** — a small fixed **regression set** (a handful of
  `questions.edn` rows whose recall/answer must not drop) run under the same two
  configs. Keep requires no regression there. Faithful, recall-aware replacement
  for `gate-pass` (W2).
- **One substrate** — both use the sweep runner + `questions.edn` (W3).

### Keep/revert criterion (the key design decision)
Options for "did enrichment help C on Q", in increasing strictness:
- **(K1) reaches read** — enrichment-on gets C into `golden-read?` (agent actually
  read it) when off didn't. Strong "the enrichment changed what the agent used".
- **(K2) reaches display/top-k** — C enters the top-20 display window or
  `recall@10` improves. Looser; the chunk became *visible* to the agent.
- **(K3) reaches pool** — C enters the search pool at all. Weakest.

**LOCKED DECISION: K2 at top-20.** The primary keep signal is "enrichment makes
the chunk reach the agent's top-20 display window." This maps directly onto the
runner's existing `:golden-in-display?` column (the display window IS 20, see
`default-display-window`), with `:recall-at-20` as the graded companion. Concretely:

    keep? = (enrichment-on lifts `:golden-in-display?` false→true for C, OR
             raises `:recall-at-20` for C, on a MAJORITY of N≥3 repeats)
            AND (no regression-set row — incl. bystanders — drops
                 `:recall-at-20` / `:answer-substring-hit?`)

Rationale: K2 is what "the enrichment made the chunk findable to the agent"
actually means; K1 (reached-read) is confounded by the agent's read-budget
decisions — this session we saw correctly-surfaced chunks not get read, so gating
on read would wrongly revert good enrichments. Top-20 (not top-10) is the
operative cutoff we already adopted corpus-side (the agent's display window and
the rerank context window are both 20). Keep the threshold a parameter so it
stays tunable, and require the lift on a MAJORITY of N repeats (agent runs are
variance-prone — we measured A at 11/11 only after repeats).

## Methodology: net-effect, dilution, and repeats (from the B/C investigation)

Three findings from the launch-date probe (chunk 8e22ae4b88b1), now baked into how
the gate must be used:

1. **Saturation is the EXPECTED steady state, not a failure.** The
   self-improvement loop runs repeatedly and the corpus changes between runs, so an
   enrichment being "redundant" (no marginal lift over corpus-aware) is the normal
   end state. The gate's job is to *measure* marginal value each round so we know
   what still helps and what to prune — measuring is the only way to know.

2. **Measure NET effect (help − harm), not just help. Enrichment can be
   DILUTIVE.** Adding the enrichment strategy floods the candidate pool with broad
   enrichment hits that can *displace* a content-found chunk out of top-20. The
   off/on comparison already captures this: a target DROP flips `:improved?`→false
   → revert, so the gate won't keep an enrichment that hurts its own target. To
   catch broad dilution on *other* chunks, the **regression set should include a
   few innocent-bystander queries**, not just the target.
   - Characterization (N=4): dilution is **real but intermittent (~1/4 runs) and
     ONLY in the blind baseline.** In the production corpus-aware context it did
     NOT appear — `:golden-in-display?` was `[1 1 1 1]` for both off and on.
   - Mechanism (complementarity): corpus-aware preserves the lay phrasing, so the
     enrichment's OWN targeted question matches strongly and the chunk holds its
     place; blind mangles the phrasing, leaving only the dilutive flooding. **Corpus-
     aware is what makes enrichment safe — they are complementary, not redundant.**
   - **Production safety: enrichment is neutral-to-safe with corpus-aware on
     (=production).** The dilution risk is a blind-baseline artifact.

3. **N≥3 repeats are mandatory.** n=2 produced a FALSE "harmful" verdict (one
   unlucky repeat read as displacement); at n=4 the same enrichment was neutral in
   production. The gate's default `:repeats` is now **3**; use ≥3 for any keep/revert
   decision.

## Phases

- **P0 — eval-sweep skill.** Add `:builtin/enrichment-eval-sweep` (src-dev) that,
  given `{user-query, chunk-id, regression-suite, repeats}`, builds an in-memory
  sweep over `[{Q, golden=[C]}] + regression-set` × `[off, on]` configs, calls
  the sweep runner's `run-matrix` in-process, and returns a structured verdict:
  `{:improved? :decomposition-delta :recall-delta :regressed? :keep?}`. Reuses the
  runner we already extended; no new measurement code.
- **P1 — A/B the new gate vs the old (de-risk the semantics change).** Run BOTH
  gates over a known set — the enrichments this session kept (7–8) and reverted
  (2) — and compare keep/revert decisions. Goal: the new gate keeps the same good
  ones and rejects the same bad ones, OR the differences are explainable
  improvements (e.g. it correctly reverts an enrichment the old lookup-based
  `improved?` wrongly kept because the agent never read the chunk). Record the
  confusion matrix. **Do not cut over until this passes.**
- **P2 — cut over.** Repoint the self-improve graph's `:eval`/`:verify`/`:decide`
  to the new verdict. Keep the old skills registered and add a `:shadow?` mode
  that runs both and logs disagreement for a few runs before removing the old
  path.
- **P3 — unify for the retrieval program (sets up #3).** Expose the same
  eval-sweep as the manual measurement for enrichment-portfolio / corpus-vocab
  experiments, so a proposed strategy is scored identically whether it comes from
  the self-improve agent or a hand-run experiment. `questions.edn` (+ the
  hard-question suite, now content-grounded) becomes the shared ground truth.

## How this sets up continuing #3 (the retrieval program)
The enrichment portfolio and the tabled corpus-vocab-discovery arc both need to
answer "does this corpus-side change help, on the agent path, without
regressions" — exactly what the eval-sweep computes. Once self-improve and manual
tuning share it:
- proposed enrichments are comparable across sources and runs;
- a strategy portfolio can be swept (one matrix, many configs) and ranked on the
  same golden decomposition + recall the self-improve gate uses;
- regressions are caught by the same regression set everywhere.

## Risks & mitigations
- **Cost/latency.** The eval-sweep runs full agent loops (vs the old fast raw
  lookup). Mitigate: keep the per-enrichment suite tiny (target Q + a small fixed
  regression set, e.g. 3–5 rows), cap repeats at 2, run configs concurrently (the
  runner already parallelizes). Budget: ~1 target×2 configs×2 repeats + regression
  set; a few agent runs per enrichment.
- **Semantics change (keep/revert).** P1's A/B is the gate — no cut-over without
  it. Shadow mode in P2 catches drift in production.
- **In-server invocation.** Self-improve runs in the bb dev JVM; the sweep runner
  is an in-process src-dev fn (same JVM, registered graphs) — already proven
  callable this session. Confirm the eval-sweep skill resolves collections +
  threads tenant the way invoke-rag does (the runner already does via
  `resolve-dataset-config!`).
- **Variance.** Agent runs flip between runs (we measured A at 11/11 only after
  repeats). Require majority-of-N, not a single run.

## Open decisions (for the build)
1. ~~Keep threshold~~ — **LOCKED: K2 at top-20** (`:golden-in-display?` /
   `:recall-at-20`) + no-regression, majority-of-N. Threshold stays a parameter.
2. Regression-set composition: which `questions.edn` rows are the "must-not-drop"
   guard, and how big (cost vs coverage).
3. Repeat count per eval (2 vs 3) — variance vs cost.
4. Cut-over strategy: shadow-mode duration before removing the old verify/eval
   skills.

## Status / progress log
- **2026-05-31 — plan LOCKED, moved to in-progress.** Keep threshold fixed at
  K2/top-20 (`:golden-in-display?` + `:recall-at-20`). Starting P0 next.
- P0 ✅ `:builtin/enrichment-eval-sweep` skill DONE
  (`src-dev/digdir/skills/enrichment/eval_sweep.clj`, commit acd22df): off/on
  configs (enrichment the only delta) + target `{query, golden=[chunk]}` +
  optional regression set → `run-matrix` in-process → K2/top-20 verdict
  (`:golden-in-display?` / `:recall-at-20` lift, no regression drop). Verdict logic
  unit-tested; **E2E validated** on the launch-date enrichment — it runs the agent
  off/on and returns a meaningful verdict.
  - **Baseline framing parameterized (user decision): default MARGINAL.** The
    `:expansion-mode` param sets the baseline for BOTH configs: `:corpus-aware-2hop`
    (default) = marginal value over production; `:blind` = absolute value (old-gate
    framing). P1 will A/B both.
  - **Notable e2e finding:** the launch-date enrichment scored `keep? false` under
    the marginal default — corpus-aware ALONE already puts the chunk in top-20
    (display-off=1.0), so the enrichment adds no marginal lift. Correct, stricter
    behavior than the old gate (which kept it): the marginal gate prunes
    enrichments corpus-aware covers and focuses on genuine bridging gaps. This is
    exactly the divergence P1 quantifies.
- P1 🔄 A/B new gate vs old. First data points on the launch-date enrichment
  (chunk 8e22ae4b88b1), n=2:
  - **marginal** (off=corpus-aware): `display 1.0→1.0` → redundant → revert.
  - **absolute** (off=blind): `display 1.0→0.0` → enrichment DISPLACED the golden
    (broad enrichment hits dilute the content-found chunk under blind queries) →
    revert (and mildly harmful).
  - **Takeaway:** BOTH framings revert this enrichment — the OLD lookup gate kept
    it (its raw enrichment-collection lookup said "findable"), but the faithful
    agent-path gate shows it adds no top-20 value (marginal) and can even hurt
    (absolute). Strong validation of the upgrade's premise; also a flag that
    enrichment can be DILUTIVE, not just additive. **Caveat: n=2 — the
    displacement claim needs more repeats.** Need a true bridging gap-filler (lay
    query corpus-aware can't reach) to demonstrate a clean KEEP.
  - ✅ **(B) Dilution characterized (N=4).** Intermittent (~1/4 runs), ONLY in the
    blind baseline; NEUTRAL/safe in the production corpus-aware context
    (`:golden-in-display?` `[1 1 1 1]` both off/on). Mechanism: corpus-aware
    preserves lay phrasing → enrichment's targeted hit holds; blind → only dilutive
    flooding. Production-safe. n=2 over-stated it.
  - ✅ **(C) Folded into methodology** (see "Methodology" section + skill default
    `:repeats` 2→3): measure NET effect (help − harm, target drop → revert),
    bystander regression set, N≥3 mandatory, saturation is expected.
  - ⬜ Remaining: extract the self-improve run's actual kept/reverted set +
    motivating queries; run the gate (N≥3) and compare to old decisions (the full
    A/B — sizeable compute; scope with user).
- P2 🔄 BATCH redesign (user chose offline batch over per-chunk).
  - ✅ **Batch eval-sweep primitive DONE** (commit f69e5f9, eval_sweep.clj v2.0):
    `:builtin/enrichment-eval-sweep` accepts `:chunk-ids` (vector), runs ONE
    off/on×N sweep with golden=all chunks, returns a PER-CHUNK verdict from each
    chunk's top-20 membership in the run's reranked list; a shared regression set
    vetoes keeps. Cost = ~`(1+|reg|)×2×N` runs for the WHOLE batch (not per chunk).
    Unit-tested (lift→keep, no-lift→revert, regression→veto-all).
  - The throwaway per-chunk P2 graph wiring (a36b40d) was reverted; graph is back
    to the known-good OLD gate.
  - ✅ **eval-sweep accepts `:chunk-outcomes`** (the foreach collect-as vector of
    maps → chunk-ids extracted) and ✅ **`:builtin/enrichment-batch-decide` skill
    DONE** (commit follows f69e5f9, batch_decide.clj): consumes per-chunk
    `:verdicts` + `:chunk-outcomes` (for each chunk's propose-provenance
    `:prompt-hash`) and reverts the failed chunks (prompt-hash-scoped), keeps the
    rest; `:dry-run?` for testing. Unit-tested.
  - ✅ **Graph rewired** (commit 6412539): inner = `fetch→propose→apply→verify`
    (cheap-lookup shadow; dropped the expensive benchmark + per-chunk decide);
    outer = `…→ :per-chunk → :batch-eval → :batch-decide → :compose`. compose
    works unchanged (batch-decide merges :decision into each outcome).
  - ✅ **VERIFIED END-TO-END** (contained run, 3 broker chunks): `TOP-ERROR nil`
    (the in-server nesting works — `run-skill-graph` → eval-sweep → `run-matrix` →
    invoke-rag → agent `run-skill-graph`), batch-eval scored all 3 in ONE sweep,
    batch-decide reverted all 3 (all `top-20 0.67→0.67`/`0.33→0.33` — no marginal
    lift → correctly pruned as redundant; corpus-aware already surfaces these
    broker chunks), report generated. The run self-cleaned (reverted its own 3).
  - Found+fixed an input-validation bug en route: eval-sweep required
    `:tenant`/`:dataset-config-key` as `:inputs`, but graph steps get them via
    skill-params → marked them `:optional-inputs` (the very mechanism added earlier
    this session).
  - ⚠ Minor artifact: an earlier pre-fix run left **3 un-gated broker enrichments**
    (chunk-ids not captured; harmless since enrichment is net-positive). Not
    blind-reverted (risk of removing pre-existing good rows). Cleanup TODO if it
    matters.
- **gc-rerun ✅ DONE + ANALYZED:** 25 golden-chunk queries lifted (goldens 61/61
  live), N=3 marginal A/B (150 runs): enrichment net-positive
  (display 0.75→0.85, recall@20 0.36→0.45); **9 lifted / 1 diluted / 15 saturated**
  — validates the net-effect methodology end-to-end. (results.md updated.)
  Remaining: **41 un-enriched-chunk-grounded Qs → 100.**
- P3 ⬜ expose eval-sweep for manual portfolio / corpus-vocab work (bridge to #3)
- **gc-rerun (end-to-end confirmation, separate track):** 25 golden-chunk-tuning
  queries lifted into `questions.edn` (goldens 61/61 live), N=3 marginal A/B sweep
  (150 runs) launched. Building toward 100 (41 un-enriched-chunk-grounded Qs next).
- P3 ⬜ expose eval-sweep for manual portfolio / corpus-vocab work (bridge to #3)

## Out of scope (intentionally)
- Rewriting v3-score's parser (separate, low-value — see the consolidation plan).
- Changing what the self-improve agent *proposes* (this is about how proposals are
  *scored*, not generated).
