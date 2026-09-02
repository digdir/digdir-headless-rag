# Read-snippet self-selection: give the agent a query-relevant preview so it reads the right chunk on its own

**Status:** proposed (2026-06-07)
**Worktree:** `establish-baseline`
**Predecessor:** `plans/proposed/close-pool-to-read-gap-plan.md` (read its RESULTS LOG first)
**Relates to memories:** `project_rerank_truncation_bottleneck` (the `best-content-window`
machinery this reuses), `project_local_model_enrichment`, `project_local_agent_sweep_findings`

---

## The problem (root cause of the pool→read leak)

The agent decides what to `read_chunks` from a **content-blind** display.
`format-search-metadata-results` (`tools.clj:700`) shows metadata only — `title`,
section `headers`, `content_length`, `rerank`/`score`, `rank`, and (with enrichment) up
to 2 `matched_q`. **No snippet of the actual text.** So the model guesses which chunk
holds the answer from a title, a header path, and a similarity number. When two chunks
share a title/section (common in this corpus) it cannot tell the answer-bearing one
apart → it reads by rank heuristic, walks past displayed goldens, and over-searches to
compensate. The Lever-2/3 spot-check caught this directly: golden at **rank 1**,
displayed, **unread**, 4 search passes.

This is the dominant remaining bottleneck (the ~25pp pool→read leak from the predecessor
plan), and it is a *signal* problem, not a *bulk* problem. The `disp-20` result proved
the distinction: widening the context window gave the **best retrieval of any arm**
(pool 1.00, read 0.71, recall 0.65) yet the **worst effQ (0.490)** — flooding a 35B
local model with more full content degrades synthesis. The fix is **more signal at the
read *decision*, not more content in the *context*.**

## Why this instead of a forced read budget

`auto-read-top-k` and raised read budget force reads mechanically; they don't help the
model choose, and over-reading dilutes (disp-20, read-xhi both regressed/were marginal).
The mechanism here mirrors how a competent reader actually works: **grep returns the
matching lines, not just filenames + a score; you read in full only the few whose
snippet shows they have what you want.** Today the agent's search is a grep that strips
the matching lines and returns filenames + a number. Give it the excerpt and it
self-selects — no forced budget, fewer-but-righter reads.

## Mechanism A (core) — query-relevant snippet in the search display

Stop discarding the window we already compute. `best-content-windows content query
budget` (`rerank.clj:115`) finds the ~budget-char span(s) of each chunk with the most
distinct query-term coverage — the answer-likely passage. It's computed at
`rerank.clj:194` solely to build the ColBERT request body and then **thrown away**.

1. **Attach it.** In `rerank-chunks`, compute the window per candidate (parallel to
   `rerank-candidates`) and `assoc` it onto the returned reranked chunk as `:snippet`
   (alongside the existing `:rerank-score`/`:rerank-rank` assoc, `rerank.clj:210-212`),
   capped to a bounded preview length (~200–240 chars). Compute the snippet whenever
   content+query are present (independent of the `:rerankWindowing` scoring flag —
   reuse the same fn). For short chunks (≤ budget) the window IS the full (short) chunk,
   which is fine.
2. **Render it.** In `format-search-metadata-results` (`tools.clj:744`) add a
   `snippet="…"` field per line (single line, newlines collapsed, length-capped). Gate
   to the top-N displayed rows (see token guardrail) so the prefill stays bounded.

That's the load-bearing change: every displayed result now shows *why it matched*.

## Mechanism B (fused) — make the snippet a genuine preview, not a teaser

A puts an excerpt on screen; B makes the model *act on it the way it should* — preview
from the snippet, `read_chunks` only to confirm or when the snippet is truncated. B is
the behavioral layer on top of A:

1. **Size the snippet to be decision-sufficient** (the ~200–240 char window, optionally
   the top-2 windows via `best-content-windows` for the top-K rows so multi-passage
   answers preview fully) — enough to judge fit, not so much it replaces the read or
   bloats context.
2. **Frame it in the prompt + sufficiency gate:** one line in the persisted qwen-adapted
   prompt — *"Each result now shows a `snippet=` preview of the best-matching passage.
   Use it to pick which chunks to read in full; read_chunks only to confirm a promising
   snippet or when it looks truncated. Metadata alone (title/score) is not enough to
   know which chunk has the answer."* This cuts both under-reading (walking past the
   golden) and over-reading (the disp-20/read-xhi failure).

A is verified first/alone; B's prompt+sizing layer is added in the same arc and the
screen tells us whether B adds anything over A.

## Method / verification

Reuse the predecessor's harness exactly. **Key difference from the auto-read screen:
read-rate is now the DIRECT target, so the 8-Q screen is NOT blind to it** (the auto-read
null was a subset-blindness artifact; a snippet that helps self-selection should move
read-rate on any subset).

- **Baseline (control):** the locked config + enriched-HQ (the A/B `enriched-hq` arm) —
  same `control` config used in `local-read-display-screen.edn`. Snippets OFF.
- **Screen (cheap):** `local-snippet-screen.edn` — control vs `snippet-A` (mechanism A)
  vs `snippet-AB` (A + the prompt/sizing of B), 8-Q Sweep-1 subset × N=3, conc 1,
  `judge? false`. Primary metric: Filter-4 **read-rate** + **recall@10**; secondary:
  effQ, **prefill tokens / wall-clock** (snippets add display tokens — watch closely),
  empty/timeout. Kill if read-rate doesn't move or token/wall-clock blows up.
- **Judge:** gpt-5.5 separate pass (`bb sweep-judge`), flip azure-on + `judge.model
  gpt-5.5`, then RESTORE both to local.
- **Confirm:** best arm → full-42 × N=3 vs control, gpt-5.5 judge — the effQ headline.
  Report paired per-Q Δ + win/loss and **multi-golden fractional recall** (don't let the
  `any-golden` booleans mask coverage caps like events-01's never-retrieved 2nd golden).
- **Combine (later, if A wins):** A × `read-hi` budget (the Lever-2 winner, 10/24000) —
  better selection + enough budget to keep what it picks.

## Success criteria

- **Read-rate ↑ materially** (target ≥ 0.70, toward the ~0.80 pool ceiling) **without a
  forced read budget** — purely from better self-selection.
- **effQ ≥ control, ideally up**, with **no empty/timeout regression** and **prefill /
  wall-clock within a tolerable budget** (local is prefill-bound; snippets are not free).
- Honest reporting: paired per-Q Δ + win/loss; fractional multi-golden recall.

## Guardrails / watch-outs

- **Token bloat is the main risk.** Snippet × display-limit (20) × ~220 chars ≈ +4k chars
  per search block, × multiple passes. Cap snippet length, cap to top-N displayed rows,
  collapse newlines, and TRACK prefill tokens per run — if wall-clock/empties regress,
  shrink the snippet or the row cap before abandoning.
- **Don't let the snippet replace the read / leak the budget.** Keep it a bounded preview
  (~200–240 chars); the full `read_chunks` still happens for confirmation and still counts
  toward the read budget and Filter-4 `golden-read?`.
- **Pool ceiling ~0.80** — snippets convert pool→read, they don't add coverage; the
  residual ~20% (golden never retrieved) is a separate retrieval-coverage problem.
- **Collections:** no generation needed; never delete a Typesense collection.
- **Config hygiene:** agent runs azure-off; judge azure-on in a separate pass; ALWAYS
  restore (`use-azure false`, `services.judge.model "qwen3.6-35b-a3b-mtp"`) afterward.

## RESULTS LOG

### Screen (2026-06-07): snippet-AB passes — best single-lever effQ of the arc, but mixed per-Q

Matrix `local-snippet-screen.edn` (control / snippet-A / snippet-AB), 8-Q Sweep-1
subset × N=3 = 72 runs, conc 1. Agent run
`results/sweep-2026-06-07T18-48-54-672025Z` (1 timeout/empty: snippet-A events-01,
the multi-golden blind-spot Q — not snippet-specific), gpt-5.5 judge (71/72).

| config | pool | disp | read | r@10 | n-ret | el-s | pTok | llm | **effQ** | c/p/i | paired Δ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| control | 0.88 | 0.71 | 0.583 | 0.521 | 3.13 | 134 | 12.8k | 3.6 | **0.542** | 7/11/6 | — |
| snippet-A | 0.75 | 0.63 | 0.583 | 0.542 | 3.08 | 170 | 19.9k | 4.0 | 0.555 | 10/8/5 | +0.014 (4W/4L) |
| **snippet-AB** | 0.83 | 0.75 | **0.708** | **0.667** | **1.83** | **102** | 12.3k | **2.8** | **0.594** | 10/9/4 | **+0.052 (3W/4L/1T)** |

**snippet-A (render only, prompt unchanged) = cost with no benefit → DEAD.** Read-rate
flat (= control), effQ +0.014 (noise), but prefill **+55% (19.9k vs 12.8k)** and
+27% wall-clock. The model pays the snippet token tax and ignores it. **The prompt
(B) is load-bearing — rendering alone does nothing.**

**snippet-AB (render + preview-aware prompt) = the strongest lever of the arc.**
- **effQ 0.542→0.594 (+0.052)** — the best single-lever effQ delta tested (vs
  read-hi +0.013, auto-read null); more correct (10 vs 7), fewer incorrect (4 vs 6).
- **recall@10 +0.146, read-rate +0.125** — the largest retrieval lift of any lever.
  Nearly closes the display→read gap (control 0.125 → 0.042): of displayed goldens
  the model now reads almost all.
- **And it's CHEAPER/faster:** reads FEWER chunks (n-ret 3.1→1.8) in FEWER LLM calls
  (3.6→2.8) and LESS wall-clock (134s→102s) at control-level tokens. The snippet's
  display-token cost is paid back by the model self-selecting (fewer reads, less
  over-search) — exactly the grep-style "preview → read fewer/righter" behavior.
- **Caveat — mixed per-Q (3W/4L/1T).** The +0.052 mean is carried by two large wins
  (corr-02 0.10→0.85, sys-03 0.33→0.72) against four real regressions (studio-01
  0.30→0.07, broker-03 0.58→0.35, events-01, authz-01). At n=24 the mean is up but
  not broadly distributed → the full-42 confirm (T9) is exactly what resolves whether
  the gain is robust or a couple of lucky cells.

**Verdict:** snippet-AB passes the screen (read-rate ≥0.70 ✓, effQ mean ↑ ✓,
mechanism validated, efficient). Drop snippet-A. → Full-42 confirm:
control vs snippet-AB (the effQ headline), optionally + a snippet-AB × read-hi
(Lever-2 winner) interaction arm.

### Full-42 CONFIRM (2026-06-08): snippet-AB WINS — significant, robust, cheaper

Matrix `local-snippet-full42.edn` (control vs snippet-AB, snippet-A dropped),
42 Q × N=3 = 252 runs, conc 1. Agent run
`results/sweep-2026-06-07T22-25-57-517280Z` (251 complete, 1 timeout = 0.4%),
gpt-5.5 judge (251/252). NB: the local host restarted mid-judge; the agent
`runs.csv` survived on disk, only the (idempotent, cloud) judge pass was re-run.

| config | n | **effQ** | sd | corr/part/inc | read | r@10 | n-ret |
|---|---|---|---|---|---|---|---|
| control | 126 | 0.527 | 0.34 | 38/52/35 | 0.548 | 0.524 | 2.93 |
| **snippet-AB** | 126 | **0.610** | 0.29 | 40/67/**18** | **0.706** | **0.683** | 2.48 |

- **effQ +0.083** — LARGER than the 8-Q screen (+0.052) and larger than the
  enrichment win (+0.067). The screen under-stated it.
- **Paired per-Q (n=42): meanΔ +0.084, se 0.034, t≈2.45, 26W / 14L / 2T.** The
  screen's ambiguous 3W/4L **resolved decisively**. This is the **first lever of the
  entire pool→read arc to reach significance** (t≈2.45 ≈ p≈0.02; enrichment was
  t≈1.55, read-hi/auto-read null).
- **Halves failures:** incorrect **35→18 (−17)**, partials 52→67 — same
  failures→partials mechanism as enrichment, stronger.
- **read-rate +0.158, recall@10 +0.159**, reading FEWER chunks (2.93→2.48). Signal-
  not-bulk confirmed at scale; the provisional pre-judge agent metrics (read 0.565→
  0.716, fewer llm-calls 3.5→3.2, fewer tokens 12.9k→11.3k) held.
- Wins broad (events-03 +0.67, dialog-07 +0.46, ~12 more at +0.2–0.4); regressions
  fewer but real (authz-03 **0.45→0.00**, dialog-06 0.75→0.43, broker-01 0.67→0.35
  — cases where the snippet steered the read wrong). Wins dominate ~2:1.

**CONCLUSION: mechanism A+B (read-snippet self-selection) is CONFIRMED.** It is the
strongest, only-significant lever of the pool→read arc — and uniquely improves
quality AND cost together (no forced read budget; fewer/righter reads).

### PROMOTED to the local-qwen default (2026-06-08)

Persisted into `builtin/agent-rag-agent`'s stored `:skill-params` in this worktree's
config-db (`dh_bb_dev_establish_baseline_v1`), via a surgical datahike tx on
`:agent/skill-params` only (NB: `upsert-agent!` re-validates the whole record and
trips on a pre-existing stale `:allowed-skill-graphs` entry `"builtin/agent-rag"` —
unrelated to this change; the direct tx sidesteps it):
- `[:builtin/agent :system-prompt]` → the `:qwen-adapted-snippet` variant (1588 chars).
- `[:builtin/agent :search-snippets]` → `true`.

The CODE default (`agent-show-snippets?` in tools.clj) stays OFF, so only this
local-qwen agent is affected; cloud/other agents are untouched. Verified through
`api-util/build-rag-skill-params` (the playground/sweep assembler): a bare invoke
now resolves the snippet prompt + `search-snippets true` with no matrix override.

**Revert:** set `[:builtin/agent :system-prompt]` back to the `:qwen-adapted` base
(1242 chars) and drop `[:builtin/agent :search-snippets]` on `builtin/agent-rag-agent`.

### Follow-ups
1. **No-tool-call scaffolding guard — SHIPPED + VALIDATED + DEPLOYED (2026-06-08).**
   The authz-03 regression was diagnosed as a pre-existing local-Qwen "writes the
   search as prose instead of calling the tool" flake (full-42: 17/126 control +
   9/126 snippet-AB never-searched; authz-03 drew 3/3 on snippet-AB by N=3 variance),
   NOT a snippet bug. Guard (`loop.clj`/`iteration_bundled.clj`, commits 096b6a8 /
   94c06ae / 19b8f89) re-prompts for a real tool call instead of finalizing on
   scaffolding; gated behind `:no-tool-call-retry` (code default off).
   - **Validated, not A/B'd — deliberately.** The addressable failure (passes=0 AND
     scaffold-shaped) is **~3/126 ≈ 2.4%** per arm, so a full-42 N=3 guard-off-vs-on
     A/B has only ~3 recoverable runs → ~+0.006 aggregate effQ, at the noise floor.
     8h to measure a 3-run effect is the wrong instrument. Instead validated by
     **deterministic integration test** (`iteration_bundled_guard_test`: the guard
     fires + re-routes + respects flag/retry-cap) + **live smoke**
     (`results/sweep-2026-06-08T11-43-53-364077Z`, 3 offender Q × {off,on} × N=4:
     guard-on 0 scaffold-finalized vs off 1, zero healthy-run regression). Worst
     case is no-worse-than-status-quo (the scaffolding it replaces already judged 0;
     retries give a real recovery chance; healthy/searched runs provably untouched).
   - **DEPLOYED:** persisted `[:builtin/agent :no-tool-call-retry] true` on
     `builtin/agent-rag-agent` (alongside the snippet promotion). Code default stays
     off → only the local-qwen agent is affected. Revert: drop that key.
2. **snippet-AB × read-hi interaction — SCREEN PASSED STRONGLY (2026-06-08), full-42 confirm pending.**
   `local-snippet-readhi-screen.edn` (snippet-AB vs snippet-AB+read-hi, both with
   guard-on + enriched-HQ; only delta = read budget 10/24000), 8-Q × N=3 = 48 runs,
   `results/sweep-2026-06-08T13-05-24-730185Z`, gpt-5.5 judge (48/48, 0 empty).

   | config | effQ | corr/part/inc | read | r@10 |
   |---|---|---|---|---|
   | snippet-AB | 0.535 | 7/12/5 | 0.625 | 0.583 |
   | snippet-AB+read-hi | **0.642** | 12/9/3 | 0.833 | 0.771 |

   effQ **+0.107**; paired per-Q (n=8) meanΔ +0.106, se 0.050, **t≈2.13, 6W/1L/1T**
   (only loss corr-02 −0.13); read-rate +0.21, recall +0.19, n-cited 1.0→1.47
   (keeps more of the right chunks), cheaper/faster.
   **Key synergy:** read-hi ALONE was flat (+0.013, Lever-2 screen) but CONVERTS on
   top of snippet-AB. Mechanism: extra read budget only helps once SELECTION is good
   — without snippets it reads more noise (the disp-20 dilution failure); with
   snippets it keeps more *right* chunks. **Read budget pays off only after the
   read decision is well-informed.** Caveat: n=8 screen (t≈2.13 suggestive; the
   snippet screen under-stated at 8-Q, +0.052→+0.083 full-42) → needs full-42 to
   nail the effQ magnitude before promoting read-hi into the default.

   **FULL-42 CONFIRM (2026-06-09): REVERSED — read-hi is NEGATIVE. NOT promoted.**
   `local-snippet-readhi-full42.edn`, 252 runs
   (`results/sweep-2026-06-08T15-08-15-242242Z`, 252/252 complete, 0 empty),
   gpt-5.5 judge (252/252).

   | config | effQ | corr/part/inc | read | r@10 |
   |---|---|---|---|---|
   | snippet-AB | **0.625** | **46**/65/15 | 0.627 | 0.603 |
   | snippet-AB+read-hi | 0.588 | **33**/75/18 | 0.643 | 0.615 |

   effQ **−0.037**; paired per-Q (n=42) meanΔ −0.037, t≈−1.16, **14W / 23L / 5T**.
   read-hi read slightly more (read-rate/recall nudged up) but **converted 13
   correct answers into partials (correct 46→33)** — the **disp-20 dilution failure
   again**: more reading degrades the 35B local model's synthesis even when
   selection is good. snippet-AB's win came from reading FEWER/righter chunks;
   read-hi undoes that. The 8-Q screen (+0.107, 6W/1L) was a **FALSE POSITIVE** —
   its control scored low (0.535 vs the true full-42 0.625). **Lesson: an 8-Q
   screen can flip sign at scale — confirm before promoting.** read-hi LEFT OUT;
   the deployed default stays snippet-AB (+guard) WITHOUT read budget. Note: this
   confirm's `elapsed-ms` is corrupted by a mid-run idle-sleep (machine slept ~2h;
   fixed with caffeinate) — timing not reported; effQ/verdicts unaffected.
3. The "future mechanisms" below.

### Mechanism C (matched_q, prompt-only) screen (2026-06-09): WEAK positive — full-42 not justified

`local-matchq-screen.edn` (snippet-AB vs snippet-AB+matchq; only delta = the
`:qwen-adapted-snippet-matchq` prompt variant adding step-2 guidance to weight the
already-displayed `matched_q=` enrichment signal — NO rendering change), 8-Q × N=3
= 48 runs, `results/sweep-2026-06-09T06-48-08-840773Z`, gpt-5.5 judge (48/48, 0 empty).

| config | effQ | corr/part/inc | read | r@10 |
|---|---|---|---|---|
| snippet-AB | 0.600 | 7/13/4 | 0.708 | 0.646 |
| snippet-AB+matchq | 0.630 | 9/12/3 | 0.875 | 0.813 |

effQ +0.030; paired per-Q (n=8) meanΔ +0.030, se 0.040, **t≈0.76, 4W/2L/2T**. The
mechanism engages (read-rate +0.167, recall +0.167, more correct) but the effQ gain
is small and NOT significant. **Decision: do NOT run the full-42.** Two reasons:
(1) +0.030 at t≈0.76 is in the noise band, below "clearly positive"; (2) the read-hi
precedent — a MUCH stronger screen (+0.107, 6W/1L, t≈2.13) reversed to −0.037 at
full-42 — and matchq's gain comes from READING MORE (read-rate +0.167), the same
dilution-risk direction. 8h for a +0.030 noisy screen is low-EV. **Prompt-only C is
marginal; the fuller C (threading rank_fusion_score into the display as a strength
number) is NOT worth the retrieval-merge work.** The `:qwen-adapted-snippet-matchq`
variant is left in the registry (opt-in) but NOT promoted. Read-stage arc concluded:
snippet-AB is the win; everything else (auto-read, read-budget, display-widen,
read-hi interaction, matchq) is null/negative/marginal. Next real headroom is the
RETRIEVAL-COVERAGE arc (the ~20% golden-never-in-pool), a separate effort.

## Future mechanisms to test later (from the brainstorm — NOT in this arc)

- **B-extended:** a dedicated `preview_chunks` tool / longer-preview tier for the top-K,
  returning snippets without consuming full-read budget.
- **C — louder `matched_q`:** show enrichment match *strength* (sim of matched Q to user
  query) and/or sort/flag the display by it. (Enrichment already lifted read-rate +0.06
  because `matched_q` is today's only content-derived signal — a paraphrase of relevance,
  weaker than A's source-text snippet.)
- **D — sufficiency-gate hint that names the unread high-signal chunk** ("rank 2 '…' is
  displayed, high-rerank, unread — read it before finalizing"). Self-correction via the
  gate that already fires (`sufficiency.clj`).
- **E — read-evaluator names specific chunk_ids** to read (`read_signals.clj` already
  emits `next_action_hint :read-more`; make it concrete).
- **F — cheaper snippet-first reads:** progressive read (compressed/snippet first, full
  on demand) to lower the implicit read cost that keeps the model at ~3 chunks.
- **G — prompt nudge alone** (weak per the Lever screen; only as a complement).
- **A × read-hi budget** combination once A is confirmed.

---

## Task list

- [ ] **T1 — Plumb the snippet (A, attach).** `rerank.clj`: compute `best-content-windows`
      per candidate and `assoc :snippet` (bounded ~220 chars, newlines collapsed) on each
      reranked chunk. Verify the agent search path returns chunks carrying `:snippet`.
- [ ] **T2 — Render the snippet (A, display).** `tools.clj`
      `format-search-metadata-results`: add `snippet="…"` per line, capped + gated to the
      top-N displayed rows. Unit-test the formatter (isolation-safe): snippet present,
      length-capped, newline-free, absent when nil.
- [ ] **T3 — Snippet preview framing (B).** Add the one-line snippet guidance to the
      persisted qwen-adapted prompt (and/or a sufficiency-gate note). Keep A-only and
      A+B as separate screen arms so B's marginal value is measurable.
- [ ] **T4 — Lint + tests green.** `bb lint` clean on changed files; new formatter test +
      existing `tools_*`/`workspace` tests pass.
- [ ] **T5 — Screen matrix.** Write `local-snippet-screen.edn` (control / snippet-A /
      snippet-AB, 8-Q × N=3, conc 1, judge? false, enriched-HQ base + persisted prompt).
- [ ] **T6 — Run the screen** (agent-run OPENAI_* env, `bb sweep`); verify
      `effective-skill-params` + that snippets actually appear in a sample run's display.
- [ ] **T7 — Judge the screen** (flip azure-on + gpt-5.5, `bb sweep-judge`, RESTORE).
      Aggregate Filter-4 read-rate + recall@10 + effQ + **prefill tokens/wall-clock** +
      empty/timeout per arm; paired per-Q; fractional multi-golden recall.
- [ ] **T8 — Verdict + commit.** Append a RESULTS LOG entry; commit code + tests + matrix
      + result dir + analysis. Decide go/no-go for the full-42 confirm.
- [ ] **T9 — Full-42 confirm (gated on T8).** Best arm vs control, full-42 × N=3, gpt-5.5
      judge; the effQ headline + paired per-Q. (Optionally add the A × read-hi arm.)

## STARTER PROMPT (paste into a fresh session)

> Continue the local-model RAG read-gap work in the `establish-baseline` worktree. Read
> `plans/proposed/read-snippet-self-selection-plan.md` (this plan) and the RESULTS LOG in
> `plans/proposed/close-pool-to-read-gap-plan.md`. Recall memories
> `project_rerank_truncation_bottleneck`, `project_local_model_enrichment`,
> `project_local_agent_sweep_findings`.
>
> One line: the agent reads from a content-blind display (metadata only), so it walks past
> displayed goldens (~25pp pool→read leak). Mechanism A surfaces the query-relevant
> `best-content-window` we already compute in `rerank.clj` (and discard at line 194) as a
> `snippet=` field in `format-search-metadata-results`; B frames it as a preview so the
> model reads full only to confirm. Start at task T1. Agent runs azure-off; gpt-5.5 judge
> in a separate `bb sweep-judge` pass, then RESTORE config to local. Don't delete any
> Typesense collections. Read-rate is the direct target — report it + recall@10 + effQ +
> prefill-token cost, paired per-Q, with fractional multi-golden recall.
