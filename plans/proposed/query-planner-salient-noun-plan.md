# Query-planner: guarantee a salient-noun query in the golden's language

**Status:** proposed
**Motivating finding (query-variance diagnostic, 2026-06-01):** the upstream
"pool-miss" is **query-formulation variance**, not a vocab gap or a ranking-floor.
The golden's pool-rank swings 1–5 ↔ 60+/never *within the same question across
identical reruns*, and the swing tracks **which queries the LLM planner happened
to emit**. Instrumented via `golden-pool-rank` + `issued-queries`.

## The evidence (N=5 over the 10 retrieval-variance questions)

Two cleanly separable burial mechanisms:

**(A) Generic-collapse / vocabulary-miss — DOMINANT (6 of 8 high-variance Qs).**
The golden pools top-10 *iff* at least one issued query carries the question's
precise **feature noun in the golden's language**; it's buried (30+/never) when
the planner's draw collapses into generic topic queries or the wrong language.
- `studio-02`: binary — runs with a `service task` query pool top-3 *every time*;
  generic-only runs (`altinn studio process`, `app process`) never pool.
- `corr-01`: only the run whose planner emitted the **English** `recipient filter
  / partyuuid` query pooled top-3; NB-only runs landed 10–58/never.
- `studio-01`: pools only with the NB UI label `skjule send inn-knappen`;
  English keyword-soup runs never pool.
- `authz-01`: the one run missing `access management api` fell to rank 20 despite
  spraying 12 queries over 4 passes — volume didn't compensate.
- `sys-02`: the long **verbatim** user question *dilutes* (pins rank 17–34); the
  run that led with the crisp noun `altinn pdp` got the best rank (12).

**(B) Twin-eclipse — minority (events-02, broker-05).** Rank wobble from a
near-duplicate chunk trading places with the golden, **independent of query
content** (events-02 issued the right scope query and still ranked 65). Forcing
nouns doesn't help these; needs twin-dedup.

**Not a lever: more search passes / query count.** passes=1 vs 2 pool
identically (rank≤20: 18/25 vs 16/24; passes=2 if anything slightly worse).
Volume ≠ the right noun.

## The lever: query-planner coverage

Change the planner (`:builtin/query-planner`, prompt + phrase-selection in
`agent/tools.clj plan_queries` / `agent/workspace.clj planned-query-batch`) to:

1. **Make a salient-noun query a non-droppable slot.** The failure signature is
   the planner *replacing* the specific framing with generic topic queries. Keep
   a verbatim/near-verbatim query carrying the question's domain noun phrase
   (`service task`, `instance metadata endpoint`, `recipient filter`,
   `skjule send inn-knappen`) as a guaranteed slot, not one of N stochastic
   expansions a bad draw can crowd out.
2. **Emit a both-languages pair for the salient noun.** The golden's
   discriminating vocabulary lives sometimes only in EN (corr-01: "recipient")
   and sometimes only in NB (studio-01: "uttrykk"). Issuing the salient noun in
   *both* languages removes the language-lottery (currently costs a top-3
   pooling ~half the time).
3. **Cap/deprioritize the long verbatim user question as a standalone query**
   (sys-02: the verbose query dilutes). Keep for breadth; don't let it dominate.

## Test plan

- **P0:** implement the planner change behind a skill-param flag so it's a sweep
  delta (faithful: a matrix `:skill-params` overlay on the real agent).
- **P1:** re-run the query-variance cohort (the 10 questions) N=5, off vs on,
  measuring **`golden-pool-rank`** (does the variance collapse — fewer never/60+
  draws?) and **recall@20**. Success = the dominant (A) questions
  (studio-02, studio-01, corr-01, authz-01, api-04) pool reliably top-10 across
  all 5 (variance gone), without regressing the CLEAN-20.
- **P2:** confirm on the full 42-question faithful judged baseline (no regression,
  net recall/judge lift), with the error/no-answer/judge-err guardrails.

## RESULTS

**P1 (10-question variance cohort, off vs on, N=5) — clear win.** never-pooled
11/50 → 3/50, recall@20 0.47 → 0.55. Mechanism confirmed: `sys-02` rank ~20 →
top-10, the twin `events-02` finally pooled its NB golden (both-languages working),
`broker-03`/`authz-01` variance fixed. One already-good question (`api-04`) mildly
regressed.

**P2 (full 42, off vs on, N=3, JUDGED) — the lever is TARGETED-positive but
GLOBALLY-negative. Do NOT ship as a global default.**
| bucket | off r@20 | on r@20 | Δ |
|---|---|---|---|
| variance cohort (10) | 0.333 | 0.617 | **+0.28** ✅ |
| rest (32, incl CLEAN-20) | 0.839 | 0.661 | **−0.18** ❌ |
| overall (42) | 0.718 | 0.651 | −0.067 |
Judge: cohort correct 14→21; rest 70→59, incorrect 3→7. Tokens +10% (concentrated
in 2 refinement-spiral questions: studio-06 +518%, authz-07 +327%). Not a
time-confound — opposite effects on two subsets of the same arm can't be drift.

**Mechanism (diagnosed): DISPLACEMENT, not bad extraction.** The salient nouns +
the take-6 cap EVICT the grounded/verbatim query that pooled the golden at rank 1
on the clean questions (e.g. `dialog-07`'s rank-1 query is absent from the
salient-on batch), and the forced generic nouns (`"dialoger api"`, `"authentication
level"`) dilute the merge → pool-rank buried on 13/17 regressions. ~8/17
retrieval-loss (golden buried out of display), ~9/17 downstream-from-retrieval.

## FIX DIRECTION (supersedes "ship as default")

Make the lever **selective + additive**, never global-replace:
- **(a) Additive, non-droppable grounded slot.** Reserve the verbatim user-intent
  (and top PRF-grounded query) as a slot the take-N cap can't evict; add salient
  nouns AFTER it; raise the cap above 6 (cohort wins came from ADDING salient, and
  cohort tokens went DOWN — headroom exists); cap/de-dup the salient phrases
  themselves (broker-02 emitted 16 near-duplicates).
- **(b) Refinement-triggered gating (strongest).** Fire salient-noun coverage only
  when the default FIRST pass fails to pool the golden — i.e. on the agent's
  re-search pass. Clean questions pool at rank 1–2 on pass 1, so they never invoke
  it → the 0.84 baseline is protected; the variance cohort (which misses pass 1)
  still gets it. Also kills the two token spirals.
- (c) Better salient extraction is tertiary — won't fix displacement alone.

Next: re-implement as (a)+(b), then re-run the P2 verification (now auto-interleaved
A/B) to confirm the cohort win survives with the CLEAN-20 baseline restored.

## (a) RESULTS + (b) INFEASIBILITY — ARC CLOSED NEGATIVE (2026-06-02)

**(a) additive/reserved-grounded-slot, full 42, off vs on, N=3, JUDGED, interleaved
(252 runs):** still net-negative, and a coin-flip even on the target cohort.

| bucket | off r@20 | on r@20 | off correct | on correct | on incorrect |
|---|---|---|---|---|---|
| cohort (10) | 0.433 | 0.517 (+0.08) | 19 | **15** | 0→**2** |
| rest (32, incl clean-20) | 0.812 | 0.719 (−0.09) | 69 | **61** | 5→5 |
| overall (42) | 0.722 | 0.671 (−0.05) | 88 | **76** | 5→**7** |

(a) muted P2's swings on BOTH sides (cohort +0.28→+0.08, rest −0.18→−0.09) but
stayed net-negative; correct 88→76, incorrect +2. **The cohort recall gain did
NOT convert to answers — cohort judge correct went the WRONG way (19→15).**

**Per-question cohort (decisive):** salient is a coin-flip, and its HARM clusters
on questions the DEFAULT already handles — it ADDS generic-query noise that
displaces good default chunks:
- clear wins (2): `sys-02` (CPP→CCP, recall 0→.33), `authz-01` (CCP→CCC, .67→1.0)
- **salient HURTS retrieval where default was already good**: `corr-01` .67→**0**
  (CCC→CCP), `broker-03` 1.0→**.67** (CCC→CCP), `broker-05` .33→.17 (CCP→**IIP**)
- recall-up-but-answer-down (no conversion): `studio-01`, `events-02`
- no change: `studio-02` (still buried), `api-04`, `api-06`

**(b) refinement-gating is NOT IMPLEMENTABLE as specified.** Its whole premise —
"clean questions pool on pass-1 and never invoke salient; only the cohort, which
misses pass-1, triggers it" — is FALSE in this agent:
- `insufficiency-fired?` true **6/252** — the sufficiency gate is effectively dead;
  not a usable trigger.
- **Re-search is uncorrelated with retrieval success.** OFF pool-rate by passes:
  pass-1 **65/68 (96%)**, pass-2 52/56 (93%). The agent re-searches on its own LLM
  judgment ~44% of the time *regardless of whether pass-1 already pooled the golden*
  (14/25 already-pooled cohort runs re-searched). So gating salient on "pass≥2" /
  "refinement" would leak it into ~46% of runs **including the clean ones** →
  re-creating exactly the (a) clean-set tax we're trying to avoid.
- There is **no production-available signal** (no golden at runtime) that
  discriminates "pass-1 missed the golden" from "pass-1 got it." Building a real
  pass-1-retrieval-sufficiency proxy (e.g. "< K results above score S") is a
  prerequisite for (b) — a non-trivial, speculative investment for a lever whose
  best case is a cohort coin-flip.

**Verdict: salient-noun arc CLOSED negative.** The P1 recall-only "win" did not
survive judged evaluation; (a) is net-negative; (b)'s trigger does not exist in
this agent. The additive paradigm keeps failing the same way — **it ADDS generic
queries that displace good chunks** (corr-01/broker-03 above are the cleanest
proof). This is the empirical hand-off to the **subtractive arc** (down-weight
noise/generic/duplicate chunks) — see the subtractive-bias direction; it targets a
stable property (genericness) instead of requiring per-query coverage, and removes
noise instead of adding it.

## Secondary / deferred

- **Twin-dedup** (events-02, broker-05): a separate, smaller lever — only 2
  questions, and broker-05 already pools (worst rank 22). Index-time
  near-duplicate collapse or NB/EN language-match scoring. Do after the planner.
- **Per-query-rank instrumentation** (golden's rank under *each* issued query,
  not the best-across-union): only needed to resolve the twin-eclipse mechanism
  (events-02 issued the right query yet ranked 65 — can't tell if that query
  alone would've surfaced it). Build only if chasing the twin residue; the
  planner lever is unambiguous from the data already in hand.

## Why this is the right next arc
The read-stage arc concluded negative (variance-dominated). This investigation,
enabled by the new instrumentation (`golden-pool-rank` / `golden-display-rank` /
`issued-queries`), found the *actual* tractable lever: 6 of 8 buried goldens are a
deterministic planner-coverage problem with a concrete, falsifiable fix. Relates
to [[project-corpus_aware_prf_expansion]] and [[feedback-cohort-multi-run-selection]].
