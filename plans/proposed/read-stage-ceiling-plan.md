# Read-stage ceiling — raising read-coverage in the agentic RAG loop

**Status:** proposed
**Motivating finding (faithful baseline #3):** retrieval surfaces the golden into
the agent's view almost always — **pool 95%, display 92%** — but the agent only
**reads 80%**. The 12-pt display→read gap is the agent's *read-decision*, not
retrieval. It's sharpest on multi-golden questions: `ue-broker-05` has **both**
golden twins pooled+displayed every run, yet the agent reads **at most one**, so
recall@20 caps at 0.5 (often 0.0). The bottleneck has moved downstream — from
"can we find it" to "does the agent pull it into context."

This is the inverse of the enrichment arc (corpus-side, retrieval). The read
stage is **agent-side**, and the knobs to move it already exist — this is a
tuning + measurement problem on the now-faithful judged harness, not a build.

---

## How the read stage works (from the code map)

Agentic loop: `search_documents` (returns **metadata only** — id/title/score/rank,
no content, top-`display-limit` rows) → the LLM picks chunks → `read_chunks`
pulls **full content** into the workspace. Scoring stages:
- **pool** = every chunk retrieval returned.
- **display** = top-N *visible* in the search-tool result (`default-search-result-display-limit` = **20**).
- **read** = chunks the agent actually pulled via `read_chunks` (subject to a budget).

Why goldens are displayed but not read:
1. **No auto-read by default** — the agent must *explicitly* pick reads from a
   metadata-only list; for multi-source / compound questions it under-reads.
2. **Read budget is modest** — `max-read-operations` **6**, `max-read-content-length`
   **12000** chars (`workspace.clj:67-69`); `trim-chunks-to-budget` can drop a
   golden when the budget fills with earlier reads.
3. **Display window 20** — goldens ranked 21+ aren't even visible (not
   `broker-05`'s issue — its goldens *are* displayed — but a factor elsewhere).

So `broker-05` is purely a read-**decision** failure (displayed, budget ample,
just not chosen). The 12-pt aggregate gap is a mix of decision + budget-trim.

## The levers (all already implemented; default off/conservative)

Per-agent skill-params under `:builtin/agent` (or top-level params), faithful via
the consolidated runner — a matrix `:skill-params` delta now layers on the real
agent config:

| Lever | Path | Default | Effect | Code |
|---|---|---|---|---|
| **Auto-read top-K** (primary) | `:builtin/agent :auto-read-top-k` | 0 (off) | After search, auto-`read_chunks` the top-K by rank into the workspace regardless of the LLM's pick — directly fixes "displayed not read" | `tools.clj:628-698,1057-1068` |
| Read-ops budget | `:max-read-operations` | 6 | How many `read_chunks` calls allowed | `workspace.clj:68` |
| Read content budget | `:max-read-content-length` | 12000 | Total chars readable; gates `trim-chunks-to-budget` | `workspace.clj:69` |
| Display window | `:builtin/agent :search-display-limit` | 20 | Ranked chunks shown in the search result | `tools.clj:593-613` |
| Strategy quota | `:builtin/agent :search-strategy-quota` | 0 | Interleave top-Q per strategy into the visible head (surfaces single-strategy goldens) | `tools.clj:532-549` |
| Title-overlap weight | `:builtin/agent :title-overlap-weight` | 0.0 | Re-score visible chunks by title/url query-token overlap | `tools.clj:615-626` |
| Richer display (code) | — | metadata-only | Add a content snippet to the search result so reads are less necessary | `tools.clj:724-767` |

`auto-read-top-k` is the highest-leverage, lowest-risk knob and the most direct
fix for the read-decision failure; budget knobs are the secondary lever.

## Goals / non-goals

**Goal:** raise read-coverage — close the display→read gap and lift multi-golden
recall — **without regressing answer quality or blowing token cost**. The faithful
judged harness measures all three at once: `golden-read?` / `recall@20` (coverage),
the judge verdict + score (quality), and `prompt/completion/total-tokens` +
`llm-calls` (cost, already in the CSV).

**Non-goals:** retrieval/rerank changes (separate arc); corpus enrichment (separate
arg). This is strictly the read stage.

## Success criteria (define up front)

A config wins if, vs the faithful baseline, on N≥3 repeats:
- **read-rate** rises (target: 80% → ≥90% on the read-limited cohort) and
  **multi-golden recall** lifts (`broker-05` 0.25 → ≥0.5 reliably, ideally 1.0);
- **judge** correct-count is **up or flat** (no quality regression from reading
  more/irrelevant chunks diluting context — the judge is the guardrail);
- **cost** stays within an acceptable band — `total-tokens` per run rises < ~30%
  (auto-read + larger budgets read more chunks → more tokens; this is the main
  tradeoff and must be measured, not assumed).

## Phasing

- **P0 — Define the test cohort.** From the faithful baseline, pull the
  **read-limited** rows: `golden-in-display? = true AND golden-read? = false`
  (the agent saw it but didn't read it), plus the multi-golden questions
  (`broker-05`). This is the subset where the read stage is the binding
  constraint — the focused arena for the sweep. (A dashboard/CSV filter; no code.)
- **P1 — Single-knob sweep: `auto-read-top-k`.** Matrix configs `{off}` vs
  `{:builtin/agent {:auto-read-top-k 2}}` / `3` / `5`, judged, N≥3, on the P0
  cohort. Report per-config: read-rate, recall@20, judge verdict/score, and
  Δtotal-tokens. Expectation: K=3 closes most of the gap; watch the cost curve
  and whether judge-correct holds (reading off-topic chunks could hurt).
- **P2 — Budget sweep (if P1 under-delivers).** `:max-read-operations`
  {6→8→10} × `:max-read-content-length` {12000→18000}, with the P1 winner.
  Only if auto-read alone leaves multi-golden recall < target (e.g. budget-trim
  is dropping the second golden).
- **P3 — Confirm on the full faithful baseline.** Take the winning config, run
  the whole 42-question judged baseline (the matrix delta makes it faithful
  agent + read knobs). Confirm net lift in read-rate/recall AND no judge
  regression AND acceptable cost across the *whole* set — not just the cohort
  it was tuned on (guard against cohort over-fit).
- **P4 — (optional) richer display.** If the knobs plateau, the code-change
  lever: add a short content snippet to the search-result rendering so the agent
  can answer without a separate read (collapses display+read). Bigger change,
  higher per-search token cost — only if P1-P3 hit a wall.

## P1 RESULT (2026-06-01) — auto-read-top-k is mis-targeted; clean negative

Ran `auto-read-top-k {0,2,3,5}` × the 10-question cohort × N=3, judged (matrix
`read-coverage-p1.edn`, sweep `2026-06-01T14-13-52`). **Verdict: near-null effect,
do not ship as-is.**
- recall@20: 0.33 / 0.40 / 0.42 / 0.33 — non-monotonic, back to baseline at K=5.
  No dose-response.
- Conditional read-rate given the golden was *displayed* moved only 42% → ~50%
  and saturated; the read-set size barely changed (~5.3 → ~5.6 chunks). The knob
  is not visibly force-injecting top-K chunks.
- The two stark cases were **never** rescued by any K: `ue-studio-02` golden read
  **0/12** (on screen in 7 runs!), `ue-broker-05` twins **0-or-1 of 2 in 12 runs**.
- Cost was flat (median tokens ~5k across configs; the one high mean was a single
  89k-token outlier). The "fewer searches" hypothesis was unsupported.

**Root cause:** `auto-read-top-k` reads by **display rank**, but this cohort's
goldens are displayed at LOW rank (or the display is unstable run-to-run), so
K=2/3/5 never reaches them. Reading by rank-K can't fix a low-rank problem.

**The real signal (lever direction is right, mechanism is wrong):**
`P(judge correct | golden-read) = 71%` vs `49%` when not read. Converting
displays→reads genuinely helps; auto-read just fails to deliver the reads.

**Measurement gap found:** the CSV records pool/display/read as *booleans* but not
the golden's **rank within the display set**. We can't directly diagnose
rank-gated effects without it. → Instrument a `golden-display-rank` column before
the next experiment.

**Redirect (supersedes P2 as written):**
- **P2' — instrument display-rank** (cheap runner change) so the next sweep can
  separate "golden ranked low in display" from "golden read-skipped."
- **P3' — pick the corrected lever:** either (a) a **rank-lift** pass
  (`:search-strategy-quota`, `:title-overlap-weight`, or a rerank change) so the
  golden lands in the top-K read window, then auto-read pays off; or (b)
  **read the whole displayed set** (budget-bounded) rather than top-K-by-rank —
  the read-set is only ~5 chunks and tokens are flat, so there's clear headroom,
  and `P(correct|read)=71%` says converting displays→reads is where the value is.
- **Separately:** 22% of cohort runs never put the golden in the pool at all —
  a retrieval-recall failure no read knob can touch.

## P2 RESULT (2026-06-01) — rank-lift backfired; the cohort was noise-selected. ARC CONCLUDED.

Ran `off` vs `strategy-quota-3` vs `title-overlap-10` vs `lift+read` × the same
10-question cohort × N=3, judged (matrix `read-coverage-p2-ranklift.edn`, sweep
`2026-06-01T15-41-43`), now with the `golden-display-rank` column.

**The levers DEMOTE the goldens (opposite of intended):** mean golden-display-rank
`off 4.0 → strategy-quota 5.3 → title-overlap 10.3`; read-rate `53% → 30% → 27%`;
recall `0.52 → 0.30 → 0.27`; judge-correct `19 → 16 → 13`; tokens up 30-55%.

**Mechanism (confirmed via the new rank column + golden titles):** `title-overlap-weight`
boosts chunks by query↔title/url token overlap, but **these goldens are
content-matched, not title-matched** (query∩title = 0-1 tokens; e.g. NB query vs
EN title "Data modeling" = 0 shared). So boosting title-overlap promotes
*distractors* that happen to hit title tokens and **buries the goldens**
(authz-06/07 pushed rank 1→14). The one golden with real title overlap
(studio-02, title "Pdf") barely moved. The lever optimizes the wrong signal for
this corpus. `lift+read` only claws read-rate back to baseline via auto-read — a
net wash at +50% tokens and fewer correct.

**The deeper finding — the cohort was a single-run artifact:** the SAME `off`
config on the SAME 10 questions swung **read 33%↔53%, recall 0.33↔0.52** between
P1 and P2. The displayed-but-not-read signature that justified the cohort
collapsed (14/24 → 5/21 runs). **5/10 questions are a full-range coin-flip at
N=3.** The cohort was selected off ONE baseline run → ~80% noise.

**VERDICT: the read stage is not a tractable lever here; variance on a
noise-selected cohort dominated any signal. Arc concluded — negative.**
Where reads genuinely fail (studio-02 golden displayed at rank 2-5 but never read
in 12/12 runs; broker-05 twins never both read) it's a read-selection /
content-mismatch problem that ranking can't fix.

**Redirect (the actual next arc):**
1. **Cohort hygiene is now mandatory** — select test cohorts by *multi-run
   agreement* (displayed-not-read across ALL N repeats), never a single run.
   (On this data, only studio-02 survives.) See [[feedback-cohort-multi-run-selection]].
2. **Move upstream to the pool/retrieval miss** — 22% of cohort runs never pool
   the golden at all; that gap is *deterministic and reproducible* (unlike the
   read gap), so it's the tractable lever. This rejoins the retrieval/enrichment
   arcs, not a read-stage one.

## Risks
- **Cost blowup** — auto-read + larger budgets read more chunks → more prompt
  tokens. Bounded by measuring `total-tokens` per run as a first-class metric;
  reject configs that lift coverage but balloon cost.
- **Context dilution** — auto-reading top-K may pull irrelevant chunks that
  crowd the context and *lower* answer quality. The judge verdict/score is the
  guardrail; a coverage gain with a judge regression is a loss.
- **Cohort over-fit** — tuning on the read-limited subset then regressing
  elsewhere; P3 (full-baseline confirm) catches this.
- **Per-question variance** — single-config runs are high-variance (17/42 swung
  ≥0.5 between identical runs); all comparisons use N≥3 replicates.

## Why now
The harness is finally the right instrument: **faithful** (knob delta = real
agent delta, post-consolidation), **judged** (quality guardrail beyond recall),
**cost-instrumented** (token columns), and **error-resilient** (the judge-timeout
fix + error/no-answer/judge-err indicators keep a long read-sweep honest). The
read stage is the next-highest-leverage lever now that retrieval reliably gets
goldens to display. Relates to [[project-llm-as-judge]] and
[[project-sweep-runner-agent-substrate]].
