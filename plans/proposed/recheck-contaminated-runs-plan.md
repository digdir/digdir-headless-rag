# Re-run plan: revalidate the judge-truncation-contaminated comparisons

**Status:** proposed (2026-06-10). **Branch/worktree:** `model-headtohead` (has the fix).
**Why:** the separate judge pass (`bb sweep-judge`) graded the runs.csv `response` clipped
to 800 chars, faking "partial" on long/enumerative answers. Fix landed here (commit
`2e466e0`: full response persisted + `:response-chars` + judge guard on truncated input;
earlier `04fbf98`). Contaminated CSVs can't be re-judged (full text was lost) → must RE-RUN.
Full audit: `model-headtohead-run-log.md` "CONTAMINATION AUDIT".

## Ground truth this branch already has
- **Fix active** (runner.clj persists full `:response`, judge warns on "…" + returns `:truncated`).
- **gx10/GB10 ollama** models registered: `h2h-control` (Qwen3.6-35B-A3B Q6 = the incumbent),
  `h2h-qwen27b` (Q4), `h2h-gemma31b` (Q4). Endpoint `http://gx10-f695:11434/v1`.
- **Judge config:** `services.azure-openai.deployment-name="gpt-5.5"` (the fix for the 404),
  set `services.judge.model="gpt-5.5"` too. Judge runs azure-ON; agent runs azure-OFF.
- Matrices present: `local-snippet-full42`, `local-snippet-readhi-full42`, `local-matchq-screen`,
  `local-hq-ab-qwen-n5`, and the head-to-head ones. **MISSING:** the completeness matrix
  (`local-completeness-screen`) + its prompt variant — port from the `retrieval-coverage` worktree.

## The agent/judge mechanics (same for every re-run)
Per arm: `bb config-set services.azure-openai.use-azure-openai-api false` + `…model-name
"<ollama-tag>"`, then `env <SAMP> OPENAI_API_ENDPOINT=http://gx10-f695:11434/v1
OPENAI_API_KEY=ollama bb sweep <matrix> --repeats N`. Then judge: azure-ON +
deployment-name/judge.model gpt-5.5 + `bb sweep-judge results/<dir>`; restore azure-off after.
- QWEN_SAMP (control + dense, incumbent thinking config): `OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95
  OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768 OPENAI_PRESERVE_THINKING=true`
- GEMMA_SAMP: `…TOP_K=64 …MAX_TOKENS=32768` (no preserve_thinking/presence_penalty).
- **Concurrency:** MoE control conc-3 OK; DENSE models (qwen27b, gemma) conc-1 + 1800s timeout
  (ollama auto-parallel=1 → conc>1 cascades to queue-timeouts). Use the `*-dense`/`*-c1` matrices.
- Reuse `scripts/model-h2h-overnight.sh` (per-arm config swap + judge + `ollama stop` between
  models + restore). caffeinate -dis. Surgical kills only (kill by PID/specific matrix name —
  a broad `pkill -f digdir.sweep` once killed a sibling reranker sweep).

## ★ Progressive checks — run for EVERY re-run, abort/flag early if they fail
The point: a correct re-run must look DIFFERENT from the contaminated one (de-truncation lifts
scores). A flat result = the fix isn't taking effect or we're on the wrong config — catch it fast.

1. **Fix-active check (first 2–3 rows):** `:response-chars` shows full lengths (many >800);
   responses do NOT end in "…"; the judge log reports `:truncated 0`. If responses are still
   ~800 chars → STOP: wrong branch/runner, fix not active.
2. **Signal check (≈⅓ through, after judge of first rows):** absolute effQ is ABOVE the
   contaminated baseline and partial-rate is BELOW it. Calibration from the verified probe:
   on the 10 all-partial Q, full-text judging lifted mean score **+0.167** and flipped 3/7
   partial→correct; control's 32/42 answers were clipped. ⇒ **expect control-equivalent effQ
   to rise from ~0.67 toward ~0.74–0.80, and golden-read partial-rate to fall from ~54%**.
   If effQ ≈ 0.66–0.67 (identical to contaminated) → FLAT, investigate before burning more hours.
3. **Verdict check (on completion):** compare to BOTH the contaminated numbers and the hypothesis
   (per-run "expected" below). Report absolute effQ shift AND the A/B Δ; explicitly call out if a
   run is flat vs expectation.

## Runs, in priority order (each: matrix · config · expected vs contaminated · check)

### P0 — snippet-AB (DEPLOYED +0.083) · `local-snippet-full42.edn` (control + snippet-AB, full-42)
- Model `h2h-control`, QWEN_SAMP, conc-3, N=3 (orig was N=5; N=3 revalidates the Δ). Judge full.
- **Contaminated:** effQ ~0.66 both arms, Δ +0.083 for snippet-AB.
- **Expected if real:** both arms' absolute effQ UP (de-truncation); snippet-AB still ≥ control by
  a meaningful margin. **Failure signal:** Δ collapses to ~0 → the deployment was justified by an
  artifact (snippet-AB's win may have been a length/truncation differential). This is the most
  important single result — it's shipped.
- ~6h.

### P1 — completeness directive ("NULL", possible masked gain) · port `local-completeness-screen.edn`
- FIRST port the matrix + the completeness prompt variant from `../retrieval-coverage/server/
  test/fixtures/sweep/`. Model `h2h-control`, QWEN_SAMP. Re-run the 8-Q screen, judge full.
- **Contaminated:** effQ 0.660→0.657 (NULL), hallucination doubled.
- **Expected:** the directive LENGTHENS answers → it was the arm MOST clipped → the NULL is the
  highest-risk reinterpretation. With full text it may now show a real gain. **If it does → escalate
  to full-42 confirm.** If still null on full text → the NULL stands (genuine).
- ~1h screen (+ full-42 ~4h only if the screen turns positive).

### P1 — head-to-head capacity question · `model-h2h-full42.edn` (+ `-dense` for the dense arm)
- 3 arms, N=1 full-42: control `h2h-control` (conc-3), gemma `h2h-gemma31b` (conc-1 matrix),
  dense `h2h-qwen27b` (conc-1 `-dense` matrix, 1800s). Judge full. Per-arm via the orchestration
  script (RUN_CONTROL=1 RUN_GEMMA=1 default-dense), or arm-by-arm.
- **Contaminated:** control 0.669 / gemma 0.664 / dense 0.660 — dead-even (all clipped at 800).
- **Expected:** partial-rate drops across ALL arms; the capacity question gets a CLEAN answer —
  either still flat (genuine no-lift) or a real spread emerges. **Either is a valid result now.**
- ~10h (dense is the long pole).

### P2 — lower stakes (exploratory; re-run only if time/interest)
- enrichment HQ A/B `local-hq-ab-qwen-n5` (+6.7pp; Δ likely survives, abs. wrong).
- read-hi "reversal at scale" `local-snippet-readhi-full42` (the reversal verdict may be an artifact).
- matchq screen `local-matchq-screen` (+0.030, not promoted).

## Sequencing
P0 → P1(completeness screen) → P1(head-to-head) → P2. P0 + the completeness screen give the
highest-value answers fastest (deployed lever + a likely wrong-negative) before the slow dense
re-run. Run sequentially (one model resident on gx10 at a time; unload between).

## Done when
Each P0/P1 run has: fix-active ✓, a non-flat signal vs contaminated, and a verdict written to
`model-headtohead-run-log.md` (absolute effQ shift + A/B Δ + whether the original conclusion holds).
Update memory ([[project_read_snippet_self_selection]], [[project_model_headtohead_arc]]) with the
corrected numbers. If snippet-AB's Δ collapses, flag the deployment for reconsideration.
