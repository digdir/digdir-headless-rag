# Model head-to-head — RUN LOG (execution record)

**Worktree:** `model-headtohead` (branch off `release-v0.1-details` @ edb0ca3 — carries
snippet-AB + the fixed shape-agnostic no-tool-call guard).
**DB:** `server/local-db/dh_bb_dev_model_headtohead_v1` (APFS clone of
establish-baseline v1 — carries the deployed config). **Port:** 8481.
**Plan:** `../retrieval-coverage/plans/proposed/model-headtohead-plan.md`.
**Started:** 2026-06-09.

## The question
Does a higher-capacity (more *active*-param) synthesis model lift the under-extraction
ceiling? The quality arc concluded the residual loss (58% of golden-read runs PARTIAL)
is the incumbent MoE's ~3B-active synthesis-extraction limit. Swap ONLY the model, hold
everything else constant.

## Confirmed compute (verified 2026-06-09, corrects the plan's guesses)
| host | actual | role |
|---|---|---|
| `bdbrodies-macbook-pro` | Apple Silicon, LM Studio :1234 (incumbent normally lives here) | not used this run |
| this Mac `bdb-dd-m4` | M4 Max, **64 GB** (plan guessed 128) | runs the HARNESS only |
| `gx10-f695` | **NVIDIA GB10 / DGX-Spark, 119 GB unified (92 free), CUDA, ollama 0.30.6, docker, 337 GB disk** | serves ALL THREE model arms |

**Decision:** run all three arms on gx10/ollama (ONE runtime) → removes the
Mac-vs-GB10 runtime confound the plan's split hosting would have introduced. Incumbent
Mac not needed. gx10 ollama rebound to tailnet (`OLLAMA_HOST=100.106.192.44:11434`,
systemd drop-in; CLI on gx10 must use that addr, not 127.0.0.1). Endpoint from Mac:
`http://gx10-f695:11434/v1`.

## Arms (lineup per user, 2026-06-09 — dropped gpt-oss-120b/235B; both challengers dense ~30B)
| arm | model | ollama (num_ctx 60000 baked) | quant | sampling |
|---|---|---|---|---|
| **control** | Qwen3.6-35B-A3B (MoE, ~3B active) — incumbent | `h2h-control` ← `unsloth/Qwen3.6-35B-A3B-GGUF:UD-Q6_K_XL` | Q6_K_XL | qwen* |
| **dense** | Qwen3.6-27B (dense, all active) — primary diagnostic | `h2h-qwen27b` ← `unsloth/Qwen3.6-27B-GGUF:UD-Q6_K_XL` | Q6_K_XL | qwen* |
| **gemma** | gemma-4-31B-it-qat (dense, QAT) — cross-family | `h2h-gemma31b` ← `unsloth/gemma-4-31B-it-qat-GGUF:UD-Q4_K_XL` | QAT-Q4 | gemma** |

\* qwen sampling: temp 1.0, top_p 0.95, top_k 20, min_p 0, presence_penalty 1.5,
max_tokens 32768, PRESERVE_THINKING=true (runbook).
\** gemma sampling: temp 1.0, top_p 0.95, top_k 64, min_p 0, max_tokens 32768 (no
qwen-specific preserve_thinking/presence_penalty).

Note: both challengers are multimodal GGUFs (separate mmproj); pulled text-only.
The `:qwen-adapted-snippet` prompt is held CONSTANT across all arms (controlled swap),
even for gemma — a prompt a model can't tool-call under is caught by the smoke gate.

## Mechanism (how the model is swapped)
- Agent model name = config `services.azure-openai.model-name` (platform/digdir/default),
  azure-off (`use-azure-openai-api=false`) → agent POSTs to `OPENAI_API_ENDPOINT` (ollama)
  via `digdir.llm.client` (passes tools + OPENAI_* sampling verbatim — ollama-compatible).
- Per arm: `bb config-set services.azure-openai.model-name "<tag>"` + per-arm OPENAI_* env,
  then `bb sweep`. Unload (`ollama stop <tag>`) before the next arm — never two resident.
- Judge: SEPARATE pass, azure-on + `services.judge.model "gpt-5.5"`, `bb sweep-judge <dir>`.
  Then restore azure-off + model-name.

## Artifacts
- Matrix (full-42, 1 deployed config, N=3): `test/fixtures/sweep/matrices/model-h2h-full42.edn`
- Smoke (3 Q, N=1): `test/fixtures/sweep/matrices/model-h2h-smoke.edn`
- gx10 model registration: `scripts/gx10-create-models.sh`
- Overnight orchestration: `scripts/model-h2h-overnight.sh` (caffeinate -dis)

## Metrics (target = the ceiling, not just effQ)
1. **golden-read PARTIAL → correct conversion** + **under-enumeration rate** (THE target)
2. effQ headline; correct/partial/incorrect mix
3. GUARDRAIL: hallucination / unsupported rate (lever-1 doubled it — watch closely)
4. tool-health: empty / timeout / never-searched per model

## CRITICAL FINDINGS during setup (2026-06-09/10) — forced a design change

1. **GB10 is bandwidth-bound (~273 GB/s) → dense ~30B decode ≈ 8 tok/s.** ollama runner
   log: `tg = 7.84 t/s` for the Q6 27B. The MoE control (~3B active) is ~10x faster.
2. **ollama auto-parallel = 1 (NOT batching).** Concurrency >1 on the dense just QUEUES
   requests; queued runs burn their run-timeout while waiting → self-timeout. So the
   **dense arm must run concurrency 1** (separate matrix `model-h2h-full42-dense.edn`,
   conc 1 + 1800s timeout). Fast arms keep conc 3 (retrieval-overlap, ~1.45x, no risk).
   (Couldn't set OLLAMA_NUM_PARALLEL — needs sudo; user asleep.)
3. **Qwen3.6 thinking is UNCONTROLLABLE via ollama.** Tested all three switches on the
   dense: `chat_template_kwargs.enable_thinking:false` → EMPTY response; `/no_think` →
   947 tok (ignored); native `think:false` → 935 tok (ignored). It always emits ~900-2000
   thinking tok/call → ~250s/call at 8 t/s → 6-call questions exceed even 1200s.
   (Added an inert `OPENAI_ENABLE_THINKING` env hook in client.clj; doesn't work for this
   GGUF, left for documentation. Control is fast WITH thinking — MoE.)
4. **Gemma 4 is non-thinking by default** (303 tok simple Q) and tool-calls cleanly →
   fast and tractable as-is.

### Design consequence — each model runs in its NATURAL mode
- **control (Qwen 35B-A3B, thinking) vs dense (Qwen 27B, thinking)** = the **clean PRIMARY
  comparison** (same family, same mode, MoE-3B-active vs dense-all-active — exactly the
  hypothesis). Both Q6_K_XL (matched quant).
- **gemma (non-thinking, Q4 QAT)** = SECONDARY cross-family arm (mode + family + quant
  caveats; non-thinking can't be matched because Qwen thinking can't be turned off).
- This is NOT the deployed thinking config end-to-end for gemma, and the dense is too slow
  to be deployable — itself a finding. The control's numbers won't equal the historical
  thinking-baseline only if mode changed; control keeps thinking so it stays comparable.

### Run parameters (LAUNCHED 2026-06-10 01:21 under caffeinate)
- **N=1 all arms, full-42**, order **control → gemma → dense** (fast arms first = early
  judged results; slow dense last gets the rest of the night). Per-arm gpt-5.5 judge
  (azure-on) right after each sweep, so finished arms are fully scored by morning.
- Expected wall: control ~1.5h, gemma ~1.8h, dense ~7-8h (Q6, conc 1). Dense finishes
  ~midday; heaviest 6-call dense questions may still timeout at 1800s (→ empty cells).
- Orchestration: `scripts/model-h2h-overnight.sh` (bg task). Runlog:
  `server/results/h2h-overnight-*.log`; arm→dir map in `server/results/h2h-manifest.tsv`.

## Progress
- [x] Harness deps verified; gx10 reachable; all 3 models registered (num_ctx 60000).
- [x] All 3 gate-passed: clean tool_calls + Norwegian (control full smoke; dense+gemma probes).
- [x] Preflight (edited client.clj boots, control studio-03 Norwegian, exit 0).
- [x] LAUNCHED overnight (control→gemma→dense, N=1, per-arm judge).
- [ ] Monitor; analyze effQ + golden-read PARTIAL→correct + under-enumeration + hallucination.

## RESULTS (live)

### CONTROL — Qwen3.6-35B-A3B (MoE, thinking) — DONE + judged (02:54)
`sweep-2026-06-09T23-21-40-331331Z` · 42/42, 0 empty.
- **effQ 0.669** · 17 correct / 22 partial / 3 incorrect
- golden-read **28/42**; among golden-read: **13 correct / 15 partial / 0 incorrect (54% partial)**
- ✅ Reproduces the historical under-enumeration ceiling (~54-58% partial among golden-read,
  effQ ~0.66). This is the baseline the challengers must beat (convert partial→correct).
- ✅ gpt-5.5 judge path validated (42/42 judged, 0 skipped, no azure error).

### ⚠️ conc-3 cascade bug (caught + fixed 03:36)
GEMMA is a DENSE 31B → its 8k-token agent-prompt PREFILL is slow. At conc-3, ollama
serializes (auto-parallel=1), so queued runs burned the 1200s timeout WAITING for a slot
(3/5 empty, n_ret=0, llm=blank). Only the MoE control (cheap prefill) survived conc-3.
**Fix: gemma → conc-1 matrix** (`model-h2h-full42-c1.edn`); dense already conc-1.
Confirmed at conc-1: gemma broker-01 = 351s, golden-read, NOT empty (decode 17.6 t/s, Q4).
Control arm (already done+judged at conc-3) was unaffected. (Op note: a broad `pkill` to
stop the bad gemma sweep also killed a sibling reranker-bakeoff sweep — surgical kills only.)

### GEMMA — re-running conc-1 (started 03:36). Steady ~410s/run, 0 empty. ~5h.

### Dense quant switch Q6 → Q4 (04:54)
At conc-1 the Q6 dense (8 t/s + uncontrollable thinking ~950-2000 tok/call) projects
~1500s for 6-call questions → **~17h for the dense arm** (and timeouts). Switching the
dense to **UD-Q4_K_XL** (~2.3x faster decode like gemma's Q4) → ~7h, far fewer timeouts,
full-42 completes. Quant caveat: control is Q6 MoE vs dense Q4 — but the ~1-2% quant gap is
negligible vs the 9x active-param difference under test, and it makes BOTH dense arms Q4-
consistent. Re-register `h2h-qwen27b` FROM the Q4 base before the dense arm starts.

### ⚠️ JUDGE config bug (caught + fixed 08:30) — deployment-name
Gemma's first judge returned all 42 `error: Interceptor Exception`. Root cause: the
establish-baseline DB clone had `services.azure-openai.deployment-name="qwen3.6-35b-a3b-mtp"`
(the LOCAL model name, leftover from the local-model runbook's restore). The judge's azure
call (`digdir.sweep.judge`, `:impl :azure`) builds the URL from deployment-name → 404
DeploymentNotFound. Fix: `bb config-set services.azure-openai.deployment-name "gpt-5.5"`
(verified azure HTTP 200; the `gpt-5.5` deployment exists). Re-judged gemma → real verdicts.
Control's earlier judge (02:54) was fine, so deployment-name only broke later — but the fix
is now in place for the dense judge too. (judge.model was already "gpt-5.5".)

### GEMMA — gemma-4-31B-qat (dense, non-thinking) — DONE + judged
`sweep-2026-06-10T01-37-36-005746Z` · 42/42, 0 empty, 0 timeout.
- **effQ 0.664** · 16 correct / 21 partial / 5 incorrect
- golden-read **30/42**; among read: **12 correct / 17 partial / 1 incorrect (57% partial)**
- **PAIRED vs control (n=42): effQ −0.005, t≈−0.10 — DEAD EVEN.** W/L/T 20/13/9.
- On the 15 questions where control under-enumerated (partial+read): gemma → 4 correct,
  10 partial, 1 incorrect. Weak conversion, offset by regressions elsewhere (gemma has 5
  incorrect vs control's 3).
- ⇒ **A different dense ~30B (gemma, cross-family, non-thinking) does NOT lift the
  under-enumeration ceiling.** Caveats: secondary arm, mode-confounded (non-thinking), N=1.

### DENSE — Qwen3.6-27B (dense, SAME family, thinking) — DONE + judged (15:19)
`sweep-2026-06-10T06-41-03-398444Z` · 42/42, 0 empty, 0 timeout (Q4, conc-1).
- **effQ 0.660** · 16 correct / 21 partial / 5 incorrect
- golden-read **35/42** (highest of all arms); among read: **12 correct / 19 partial / 4 incorrect (54% partial)**
- PAIRED vs control (n=42): effQ **−0.010**, t≈−0.23, W/L/T 17/16/9.
- On the 15 questions where control under-enumerated (partial+read): dense → **1 correct, 11
  partial, 3 incorrect** — converted only 1, BROKE 3. Worse than gemma's conversion (4).

---

## ⚠️⚠️ RESULTS INVALID — judge-truncation artifact (CONFIRMED 2026-06-10)
**The verdict below is NOT trustworthy — the head-to-head must be re-run.** The SEPARATE
judge pass (`bb sweep-judge` / `judge-sweep-dir!`) grades the `response` column read from
runs.csv, which `runner.clj` `truncate`d to **800 chars** (line ~773) before writing. The
gpt-5.5 judge never saw the END of long/enumerative answers → it marked them
"partial: missing X" when X was generated but clipped past char 800.

### Verified (trunc-check: 10 all-partial Q re-run on control with cap→20000, re-judged full)
- **Blast radius:** of the control arm's 22 partials, **22/22 (100%) were on clipped answers**
  (32/42 answers hit the 800 cap). Dense 19/21 partials clipped (90%); gemma 16/21 (76%).
- **Smoking gun:** broker-01 full answer = **2019 chars and DOES contain «Slettet»** — the exact
  item the truncated judge called "missing." The model enumerated everything; the judge saw
  only the first 800 chars. (This is the canonical case the PRIOR arc cited as proof of the
  "model ceiling" — it was complete all along. ⇒ prior "ceiling" conclusion contaminated too.)
- **Magnitude:** on the 7 questions golden-read in the re-run, mean judge-score
  **0.550 → 0.717 (Δ +0.167)** with full responses; **3/7 flipped partial→correct**
  (studio-03 0.55→0.85, dialog-06 0.65→0.82, studio-05 →0.95).
- **Not 100% artifact:** broker-01 stayed partial/0.70 even with full text — but now for a
  DIFFERENT reason (judge wants the reference's explicit state ORDERING; dings the model's
  arguably-correct "no fixed sequence" caveat) = judge-strictness / reference-format, NOT
  under-enumeration. authz-06 stayed partial on a genuinely short (279-char) answer. So a
  residual partial rate is real, but the bulk of the measured "partial" was truncation.

### Consequence
Every effQ / partial-rate / "dead-even / no-lift" number below is an artifact of all three arms
being clipped at 800 before judging (control 100% of partials, dense 90%, gemma 76%). **The
capacity question is UNANSWERED.** Fix: `runner.clj` `:response` truncate cap 800→20000 (done,
uncommitted). Re-run the full head-to-head with the fix before drawing any model conclusion.

## ★ THREE-WAY VERDICT (full-42, N=1, gpt-5.5 judge) — 2026-06-10  [PROVISIONAL — see taint warning above]

| arm | model | mode | effQ | C/P/I | golden-read | % partial (of read) | incorrect-rate |
|---|---|---|---|---|---|---|---|
| **control** | Qwen3.6-35B-A3B | MoE 3B-active, thinking | **0.669** | 17/22/3 | 28/42 | 54% | 7% |
| **gemma** | gemma-4-31B-qat | dense, non-thinking | **0.664** | 16/21/5 | 30/42 | 57% | 12% |
| **dense** | Qwen3.6-27B | dense all-active, thinking | **0.660** | 16/21/5 | 35/42 | 54% | 12% |

Paired vs control: gemma −0.005 (t≈−0.10), dense −0.010 (t≈−0.23). Both **firmly null**.

### Conclusion: NO. Higher active-param capacity does NOT lift the under-enumeration ceiling.
- All three models land within **0.009 effQ** (0.660–0.669) — statistically indistinguishable.
- The hypothesis "under-extraction = the MoE's ~3B active params" is **REFUTED**: a same-family
  DENSE model with ~9× the active params (Qwen 27B) performed **identically** (slightly worse).
- The under-enumeration rate among golden-read runs is **~54-57% across ALL THREE** — the
  ceiling is a property of the ~30B model CLASS, not the MoE architecture.
- **Strongest evidence it's not retrieval/read:** the dense Qwen READ the golden MORE (35/42 vs
  control 28/42) yet STILL under-enumerated at the same 54% — reading the full golden does not
  produce exhaustive enumeration.
- **Guardrail regressed:** both challengers raised incorrect/hallucination 7% → 12%.
- On the 15 control-partial questions, neither challenger meaningfully converted them
  (dense 1/15, gemma 4/15) and both introduced new errors.

### Implications / recommendation
- The "swap to a better ~30B local model" lever is **dead** — stop pursuing it.
- To move this ceiling would need either (a) a MUCH larger model (gpt-oss-120b / 235B-class —
  the plan's scale-up arm, deferred), or (b) accepting part of the gap is the judge's
  exhaustiveness strictness vs single-chunk goldens (re-examine a sample of "partials" by hand).
- Incumbent Qwen3.6-35B-A3B remains the best local choice (best effQ + lowest hallucination +
  fast MoE). No model change warranted.

### Caveats
- N=1 (paired across 42 Q gives a directional mean; t-stats firmly null so larger N won't rescue).
- gemma is cross-family + non-thinking (Qwen3.6 thinking uncontrollable via ollama); dense Qwen
  is the clean same-family/same-mode test and is also null. dense ran Q4 (vs control Q6) for
  tractability — a ~1-2% quant gap, negligible vs the 9× active-param difference tested.
- Each model in its natural ollama mode; all on gx10/GB10 (one runtime, no host confound).

---

## ★ CONTAMINATION AUDIT — other recent comparisons hit by the same bug (2026-06-10)

**Bug window:** the 800-char `truncate` cap has existed since the runner was created
(546d5ea); the SEPARATE judge pass (`judge-sweep-dir!`) was added in 5cf2af3 (the
local-model arc). So **every local-agent quality A/B since the local-model arc that used
`:judge? false` + `bb sweep-judge` is contaminated** (the judge saw answers clipped at 800).
Inline `:judge? true` sweeps judged the full in-memory response and are FINE. Pure
retrieval/recall sweeps (chunk-recall, golden-read decomposition, v3-score) have no judge
text and are unaffected.

**The existing contaminated CSVs cannot be re-judged** — the full responses were truncated at
write time and are lost. They must be RE-RUN with the fix (cap→20000).

| comparison | matrix | mode | status | risk |
|---|---|---|---|---|
| **snippet-AB +0.083 (DEPLOYED to release-v0.1-details)** | local-snippet-full42 | judge? false | **CONTAMINATED — 75% of answers clipped@800** | ⚠️⚠️ HIGH — shipped on truncated judging; re-run to revalidate |
| **answer-completeness Lever 1 "NULL"** | local-completeness-screen | judge? false | **CONTAMINATED** | ⚠️ HIGH — directive *lengthens* answers → more truncation on treatment → the NULL may be a MASKED gain (wrong negative) |
| **"model ceiling" (Lever 2 diagnostic)** | local-synth-trace | judge? false | CONTAMINATED | ⚠️ HIGH — already flagged; the broker-01 «Slettet» evidence was a truncation artifact |
| local enrichment HQ A/B +6.7pp | local-hq-ab-qwen-n5 | judge? false | CONTAMINATED (abs.); Δ may survive | MED — retrieval lever, treatment unlikely to change length much |
| read-hi interaction "reversal at scale" | local-snippet-readhi-full42 | judge? false | CONTAMINATED | MED — exploratory; the reversal verdict itself may be an artifact |
| matchq screen (+0.030) | local-matchq-screen | judge? false | CONTAMINATED | LOW — exploratory, not promoted |
| HQ corpus A/B, oracle, phrase-prune, prune-loop, salient-noun, read-coverage, local-hq-ab | (those matrices) | **judge? true (inline)** | **FINE** | — judged full responses |

**Δ-survival caveat:** the bug truncates BOTH arms, so an A/B Δ is roughly preserved *unless the
treatment changes answer length past 800*. That's why the completeness directive (explicitly
lengthens) is the highest-risk reinterpretation, and snippet-AB (changes read/answer behavior)
needs revalidation despite being "just a delta."

**Recommended order:** (1) re-run snippet-AB control-vs-AB (deployed, full-42) with the fix —
does +0.083 hold? (2) re-run the completeness directive — was the NULL a masked gain? (3) re-run
this head-to-head. All cheap given the 1-line fix; each ~hours on gx10.

---

## ★ RE-RUN RESULTS (truncation fix active) — 2026-06-11

Driven by `recheck-contaminated-runs-plan.md`. Fix `2e466e0` active: full `:response`
persisted, judge guard. Model = `h2h-control` (deployed MoE), gx10/ollama, gpt-5.5 judge.
Matrix bumped to conc-3 + **1200s** run-timeout (the inherited 600s caused a broker-01
timeout cascade at conc-3 — 2/7 died; 1200s → 0 timeouts; does NOT change agent behavior).

### ✅ P0 — snippet-AB revalidation (DEPLOYED lever) — Δ HOLDS
`sweep-2026-06-10T16-22-04-112324Z` · 252/252, **0 timeout, 0 empty, judge 252/252,
0 skipped, 0 TRUNCATED** (guard confirms no clipped judge inputs). conc-3, N=3.

| arm | effQ (mean judge score) | C/P/I | golden-read | % partial of read |
|---|---|---|---|---|
| control    | **0.644** | 60 / 42 / 24 | 76/126 | 34% |
| snippet-AB | **0.727** | 77 / 36 / 13 | 89/126 | 25% |

- **PAIRED (by question, n=42): Δ = +0.083, t≈2.19, W/L/T 28/10/4.**
- Fix-active confirmed pre-run: broker-01 snippet-AB answer = 1517 chars (not clipped@800),
  CONTAINS «Slettet» (the exact item the truncated judge had called "missing").
- **VERDICT: the deployed +0.083 SURVIVES full-text judging — identical Δ to contaminated.**
  The bug clipped BOTH arms so the Δ was preserved; de-truncation lifted snippet-AB's *absolute*
  effQ to 0.727 (it reads more → longer answers → was clipped more). snippet-AB also reads the
  golden MORE (89 vs 76), cuts partials, and HALVES incorrects (24→13) — not trading accuracy for
  completeness. **The deployment stands; no reconsideration needed.** (Contaminated was Δ+0.083,
  t≈2.45, 26W/14L — the corrected run is, if anything, slightly cleaner: 28W/10L.)

### ✅ P1 — completeness directive re-run (was "NULL + hallucination doubled") — NULL is GENUINE
`sweep-2026-06-11T00-27-48` · 48/48, 0 timeout, judge 0-truncated. 8-Q N=3, conc-3.

| arm | effQ | C/P/I | incorrect-rate | golden-read % partial |
|---|---|---|---|---|
| snippet-AB          | 0.713 | 12 / 9 / 3 | 12% | 31% |
| snippet-AB+complete | 0.713 | 13 / 8 / 3 | 12% | 21% |

- **PAIRED (n=8): Δ = +0.000, t≈0.01, W/L/T 3/4/1.** Dead null on full text.
- **VERDICT: the NULL was NOT a masked gain — it's genuine.** The completeness directive does
  nothing (no partial→correct conversion) → under-enumeration is NOT a prompt-instruction gap
  (confirms the model-ceiling read). BONUS: the contaminated run's "hallucination DOUBLED
  0.042→0.083" guardrail alarm was ITSELF a truncation artifact — clean run is 12% incorrect on
  BOTH arms, no increase. No full-42 escalation.

### ⏳ NEXT: P1 head-to-head re-run (3 arms) + P2. The THREE-WAY verdict (control/gemma/dense
"dead-even") remains INVALID until re-run with the fix.

---

## ★ P1 head-to-head re-run + P2 (2026-06-11) — PARALLELIZED across two GB10 boxes
P1 head-to-head on `gx10-f695` (control done, gemma done, dense in progress). P2 runs IN
PARALLEL on a SECOND box `gx10-eccf` from an isolated worktree `p2-parallel` (cloned config
DB `dh_bb_dev_p2_parallel_v1`=h2h-control, conc-3/1200s `-c3` matrices, direct tailscale
endpoint). Isolation rationale: model is config-driven (`services.azure-openai.model-name`),
so a shared DB would collide; Typesense is shared but READ-ONLY in the sweep path (no conflict).

### head-to-head clean arms so far (full-42, N=1, gpt-5.5 judge):
- **control** (Qwen3.6-35B-A3B MoE) `sweep-…02-02-22`: effQ **0.735** (contaminated 0.669),
  C/P/I 30/6/6, partial-of-read **14%** (contaminated 54%!) — the "54% under-enumeration
  ceiling" was LARGELY A TRUNCATION ARTIFACT.
- **gemma** (gemma-4-31B dense) `sweep-…03-31-38`: effQ 0.742, paired vs control **Δ +0.006,
  t≈0.12** — still dead-even (contaminated −0.005). Secondary "no lift" HOLDS on clean data.
- **dense** (Qwen3.6-27B same-family) — in progress (the decisive capacity comparison).

### ✅ P2 matchq (`local-matchq-screen-c3`, eccf) — "weak +0.030" flips NEGATIVE on clean text
`sweep-2026-06-11T07-06-05` · 48/48, 0 timeout, 0 truncated.
| arm | effQ | C/P/I | incorrect |
|---|---|---|---|
| snippet-AB        | 0.684 | 12/9/3 | 12% |
| snippet-AB+matchq | 0.604 | 11/7/6 | 25% |
- **PAIRED (n=8): Δ −0.080, t≈−1.15, W/L/T 2/6/0.** matchq DOUBLES hallucination (12→25%).
  Contaminated was +0.030 — an artifact. **"Not promoted" decision stands, now better justified.**
- P2 still running: enrichment-HQ A/B + read-hi reversal (full-42 each) pending.

### ✅✅ THREE-WAY capacity verdict (CLEAN, full-42 N=1 gpt-5.5) — conclusion HOLDS, ceiling was inflated
dense `sweep-2026-06-11T06-15-21` · 41 scored + 1 timeout (heaviest 6-call Q at 1800s).

| arm | effQ | C/P/I | partial-of-read |
|---|---|---|---|
| control (Qwen3.6-35B-A3B MoE) | 0.735 | 30/6/6 | 14% |
| gemma (gemma-4-31B dense)     | 0.742 | 26/13/3 | 27% |
| dense (Qwen3.6-27B same-family)| 0.730 | 25/12/4 | 25% |

- **PAIRED vs control: dense Δ −0.008 (t≈−0.14, W/L/T 19/16/6); gemma Δ +0.006 (t≈0.12).** Both null.
- **VERDICT: higher active-param capacity does NOT lift over the MoE — CONFIRMED on clean text.**
  Same-family dense Qwen-27B (~9× active params) is dead-even with the incumbent; all three within
  0.012 effQ. The contaminated "dead-even, no lift" Δ conclusion SURVIVES de-truncation.
- **BUT the "ceiling" was inflated:** contaminated said ~54% under-enumeration across all three (a
  severe model-class ceiling). Clean it's **14–27% partial-of-read, effQ ~0.73–0.74** — the models
  are much better than the truncated data implied; the residual gap is modest, NOT a hard ceiling.
  ⇒ The prior "QUALITY ARC CONCLUDED — at the model's ceiling" framing was over-pessimistic
  (driven by the 800-char bug). The incumbent Qwen3.6-35B-A3B stays (best effQ + lowest
  hallucination + fast); no model change warranted, but there is MORE residual quality headroom
  than the contaminated arc implied.

### ✅ P2 enrichment-HQ A/B (`local-hq-ab-qwen-n5-c3`, eccf) — CONFIRMED, Δ survives
`sweep-2026-06-11T08-49-23` · 252/252, 0 timeout, 0 truncated. off vs enriched-hq, N=3.
| arm | effQ | C/P/I | golden-in-pool |
|---|---|---|---|
| enriched-HQ | 0.730 | 81/31/14 | **95%** |
| off         | 0.664 | 65/41/19 | 84% |
- **PAIRED (n=42): Δ +0.068, t≈2.14, W/L/T 26/14/2** — matches contaminated +6.7pp (retrieval
  lever → Δ survives truncation). Mechanism: enrichment lifts golden-in-pool **+11pp (84→95%)**.
  The corpus-side lever is REAL; this is the technique Plan 2 (public-docs scale) generalizes.
### ✅ P2 read-hi reversal (`local-snippet-readhi-full42-c3`, gx10-f695) — "reversal" CONFIRMED
`sweep-2026-06-11T12-05-17` · 252/252, 0 truncated. snippet-AB vs snippet-AB+read-hi, N=3.
| arm | effQ | C/P/I |
|---|---|---|
| snippet-AB          | 0.758 | 79/37/10 |
| snippet-AB+read-hi  | 0.709 | 72/38/16 |
- **PAIRED (n=42): Δ −0.048, t≈−1.81, W/L/T 12/28/2** — slightly STRONGER negative than the
  contaminated −0.037. Adding read budget on top of snippet-AB hurts (incorrect 10→16). The
  "reversal at scale / not promoted" decision was correct; confirmed on clean text.

## ✅✅✅ RE-CHECK PLAN COMPLETE (2026-06-11) — all contaminated comparisons re-validated
| comparison | contaminated | CLEAN (full-text judge) | verdict |
|---|---|---|---|
| P0 snippet-AB (deployed) | +0.083 | **+0.083** t≈2.19 | HOLDS — deployment justified |
| P1 completeness | null (+hallu doubled) | **+0.000**, no hallu rise | null GENUINE |
| matchq | +0.030 | **−0.080** (hallu 12→25%) | artifact→harmful; stays unpromoted |
| enrichment-HQ | +6.7pp | **+0.068** t≈2.14 (+11pp pool) | HOLDS — real corpus lever |
| head-to-head 3-way | dead-even @0.66 | **dead-even @0.73**, dense Δ−0.008 | no model lift; ceiling was inflated |
| read-hi reversal | −0.037 | **−0.048** t≈−1.81 | reversal CONFIRMED |
**Throughline:** the bug clipped BOTH arms so the A/B *deltas* almost all survived, but it badly
inflated absolute difficulty (partial-of-read 54%→14%; effQ ~0.66→0.73-0.76). The proven real
levers to scale = snippet-AB (+0.083) and enrichment-HQ (+0.068). No prompt-directive lever helped.
No local model swap helped. Next phase = scale to public-docs (see
`establish-baseline/plans/proposed/public-docs-{agent-config-activation,corpus-scale-and-goldens}-plan.md`).

### ★★★ KIMI K2.6 (frontier tier) — BREAKS THE CEILING (2026-06-13)
`sweep-2026-06-12T20-24-51` · full-42 **N=3** (126 runs), 0 timeout/empty, judge 126/126.
Kimi via localhost:8010 (HPC), deployed snippet-AB config held constant, gpt-5.5 judge.

| arm | model | effQ | C/P/I | hallucination |
|---|---|---|---|---|
| control | Qwen3.6-35B-A3B MoE | 0.735 (N1) | 17/22/3 | 7% |
| gemma   | gemma-4-31B dense   | 0.742 (N1) | 16/21/5 | 12% |
| dense   | Qwen3.6-27B dense   | 0.730 (N1) | 16/21/4 | 12% |
| **Kimi** | **K2.6 ~1T/32B-active** | **0.826 (N3)** | **89/34/3** | **2.4%** |

- **PAIRED Kimi − control (n=42): Δ +0.090, t≈2.03, W/L/T 22/17/3.** Well outside the ~30B
  cluster spread (0.730–0.742). 6 control-partial → Kimi-correct conversions. golden-in-pool 95%,
  golden-read 86%, partial-of-read 22% (NOT lower than the cluster — the win is coverage +
  near-zero hallucination + 71% correct, not less under-enumeration per se).
- **VERDICT: a FRONTIER-tier model DOES lift the ceiling the ~30B class could not** — confirms the
  head-to-head's deferred "would need a MUCH larger model" hypothesis. Hallucination 2.4% vs 7–12%.
- **Op notes (Kimi serving):** reasoning model, ~10 tok/s on the HPC GPU; `reasoning_effort` and
  thinking-off toggles are IGNORED by the serving (always reasons ~13k chars). Tractable only via
  CONCURRENCY (endpoint batches → ran conc-32) + raised timeouts (client socket-timeout now
  env-configurable via OPENAI_SOCKET_TIMEOUT_MS; 50-min run cap). NOT latency-deployable as-is
  (~10 tok/s), but viable for batch/quality-critical synthesis. Endpoint is a flaky HPC allocation.

---

## ★★★★ KIMI WEEKEND BATCH — fixed-planner, repair-loop, FINAL CLEAN RESULTS (2026-06-15)

Six-stage sweep over the free-weekend Kimi K2.6 vLLM endpoint (`localhost:8010`, conc-48, N=3,
gpt-5.5 judge). Driver `scripts/kimi-weekend-queue.sh`. Goal: get the *clean deployed-config*
frontier numbers (the 06-13 ceiling-break ran with a silently-degraded query-planner) and ablate
each tuned lever on a frontier synthesizer.

### Two bugs fixed first
1. **Query-planner eval bug (`query_planner.clj`)** — picked the planner model from
   `services.azure-openai.deployment-name` unconditionally; with azure OFF (all local/Kimi sweeps)
   that stale name 404s → planner fell back to RAW query, so `corpus-aware-2hop` expansion **never
   actually ran** in *any* azure-off sweep (the whole local arc + the 06-13 ceiling-break). Fix:
   mirror `agent/loop.clj` — use `model-name` when azure is off. It's an eval-harness bug, not prod
   (prod is azure-on). The +0.090 *delta* held (both arms equally degraded) but absolute numbers
   were measured without expansion; this batch re-establishes them with the planner actually firing.
2. **Mid-stage endpoint flakiness** corrupts runs invisibly: a half-alive endpoint returns
   `status=complete` runs that aborted after ~1 LLM call (~60-char non-answer, 0 cited). The
   smoke gate only guards the START of a stage. First A run came back 52% healthy / effQ 0.440
   (pure endpoint damage, not model). FIX = a **repair loop** (`scripts/repair-util.clj` +
   `repair_stage()`): after round-0, re-run ONLY the endpoint-damaged (config×question) combos
   (status≠complete / empty / <2 LLM calls / <200 chars), health-gated, until each has N healthy
   runs; merge the healthy set; THEN judge. Resumable; tolerates a flaky endpoint by retrying.
   Repaired A from 0.440 → 0.837 (1 round). Repair rounds this batch: A=1, B=3, C=1, E=1, F=0, D=3;
   every stage reached 0-short. All stages 96–99% agent-health, 0 truncated judge inputs.

### Results (fixed planner, repaired clean, gpt-5.5 judge)
| stage | config | effQ | C/P/I | halluc | dir |
|---|---|---|---|---|---|
| **A** headline | deployed snippet-AB, full-42 | **0.837** | 93/32/1 | 0.8% | `repaired-A-headline-full42-20260614T194221` |
| **B** broad | deployed, 118 public-docs | **0.832** | 263/80/11 | 3.1% | `repaired-B-broad118-20260614T221616` |
| **C** snippet ablation | control vs snippet-AB, 42 | 0.817 / 0.854 | — | — | `repaired-C-snippet-ablation-20260615T003743` |
| **E** completeness | snippet-AB vs +complete, 42 | 0.848 / 0.834 | — | — | `repaired-E-completeness-20260615T025925` |
| **F** read-hi | snippet-AB vs +read-hi, 42 | 0.850 / 0.854 | — | — | `repaired-F-readhi-20260615T045811` |
| **D** enrichment | off vs enriched-hq, 118 | 0.818 / 0.835 | — | — | `repaired-D-enrichment-118-20260615T085546` |

Paired deltas (by question): **C snippet-AB +0.037** (20W/13L/9T) · **E completeness −0.015**
(17W/21L/4T) · **F read-hi +0.004** (17W/16L/9T) · **D enriched−off +0.017** (51W/48L/19T).
(D needed a re-judge: 55 azure judge-side `error` verdicts on `status=complete` rows; generation
was clean, re-judge filled them → no error/timeout.)

### Verdict
- **The ceiling-break is REAL and GENERALIZES.** Clean fixed-planner Kimi = **0.837** on the 42-Q
  tuning set and **0.832** on the 118-Q broad set (essentially identical → not tuning-set overfit),
  vs control ~0.735 (42) and local-deployed ~0.681 (118) → a **+0.10 / +0.15** lift, hallucination
  ~1–3% (vs 7%+). The working `corpus-aware-2hop` lifted golden-in-pool to 98–99%.
- **Only the model tier moves the headline.** Every tuned lever is small-to-null on a frontier
  synthesizer: snippet-AB still helps but ~half (+0.037 vs the local +0.083); the completeness
  directive is a non-lever (−0.015, mild over-reach, confirms the local null); read-hi is neutral
  (+0.004) — the local model's −0.048 *penalty* DISAPPEARS (a frontier model isn't hurt by extra
  reading); enrichment does NOT transfer to the broad set (+0.017, ≈ the local +0.024 null), i.e.
  the enrichment win is curated-42-specific and model-independent. The levers were largely
  compensating for ~30B limitations.

Incumbent recommendation unchanged for latency-bound prod (Kimi ~10 tok/s, not deployable as-is),
but for batch/quality-critical synthesis Kimi is a clear, generalizing quality step-up.
Artifacts: `server/results/repaired-*` (intermediate sweep-*/ dirs + logs left untracked).
Harness: `scripts/{kimi-weekend-queue,repair-util,smoke-health,kimi-conc-watch}.sh|clj`,
matrices `test/fixtures/sweep/matrices/kimi-*.edn`. Planner fix in `query_planner.clj`.

---

## ★ K2.7-Code follow-up — DEPLOYABLE frontier quality (2026-06-15)

New release `moonshotai/Kimi-K2.7-Code` on **:8020** (K2.6 stays :8010). Question: can a fast,
**NON-reasoning** coder model match K2.6's *reasoning*-model ceiling-break on Norwegian doc-QA?

**Gate (3-Q agent smoke):** 3/3 healthy, 0×404, fluent Norwegian, tool-calls cleanly through the
loop (**llm-calls 2 vs K2.6's 3** — fewer, more direct turns). `reasoning_effort` is **NOT honored**
(293 vs 276 completion tokens low-vs-high) → no controllable-reasoning knob, so the planned S4
(reasoning-effort sweep) was dropped. Harness made endpoint/model-parameterized
(`KIMI_ENDPOINT`/`KIMI_MODEL_NAME`/`KIMI_PROBE_MODEL`); S1 driver `scripts/kimi-s1-k27.sh` runs A+B
through the SAME repair loop under `k27-*` labels.

**S1 — K2.7-Code vs the committed K2.6 baselines (paired by question, identical config):**
| set | K2.6 | K2.7-Code | Δ (K2.7−K2.6) | W/L/T |
|---|---|---|---|---|
| A 42-headline | 0.837 | **0.850** | **+0.012** | 24/15/3 |
| B 118-broad   | 0.832 | **0.827** | **−0.005** | 59/53/6 |

Both a **WASH** (within noise). K2.7 ~99% agent-health, comparable hallucination. (k27-B honestly
logged **1/118 short** — a `wg-api` question the repair loop couldn't heal in 10 rounds, kept at
N=1; immaterial to the mean.)

**VERDICT: K2.7-Code matches K2.6's ~0.83 ceiling-break quality but is DEPLOYABLE.** K2.6 broke the
ceiling yet wasn't latency-deployable (~10 tok/s + ~13k reasoning chars/call). K2.7-Code delivers the
same quality **non-reasoning, ~2× faster (~18 tok/s), fewer agent turns (2 vs 3), 0 repair rounds on
A** — i.e. the ceiling-break quality in a shippable model. Datasets `server/results/repaired-k27-{A,B}-*`.
Deferred: per-category code-vs-prose cut (does the coder win on API/spec, lose on prose, netting the
wash?) and S3 (lever ablations on K2.7).

---

## ★ CORRECTION + conc-1 latency + LANDING note (2026-06-16)

**CORRECTION to the K2.7 section above — K2.7-Code is LIGHT-reasoning, NOT "non-reasoning."** The
"non-reasoning" call was an artifact of the *serving not reporting* reasoning tokens. After a
server-side fix (reasoning tokens now returned + counted), direct probes show
`usage.completion_tokens_details.reasoning_tokens` ≈ **120–180/call**, and the agent path logs
**~67/query median** (16/16 runs). K2.7 reasons FAR less than K2.6 (~13k chars/call) — its speed
comes from *lighter* reasoning, not none. So "K2.7 is faster because non-reasoning" → "**faster
because lighter-reasoning**." (Harness already extracts it via `runner.clj:401`.)

**Reasoning-token serving improvement (prompted by this work):** the endpoint now returns reasoning
token counts in the chat/completions usage. We POST **`/v1/chat/completions`** (never `/v1/responses`),
so the fix must land at `usage.completion_tokens_details.reasoning_tokens` (or flattened top-level) —
verified populated end-to-end. New runs carry real `:reasoning-tokens`; prior datasets stay 0.

**conc-1 LATENCY (true single-request; batching confound removed) — PARTIAL:**
- **K2.7-Code** (`sweep-2026-06-15T19-10-11`, 8Q×N2, conc-1, 16/16 healthy): end-to-end per-query
  latency **median 114 s / mean 154 s**; raw **19.7 tok/s**; ~2 llm-calls, ~331 completion + ~67
  reasoning tok/query. (~½ the conc-48 figure ~210 s — batching had inflated apparent latency ~2×.)
- **K2.6 half PENDING** (`:8010` down all weekend; armed to auto-run on return). End-to-end includes
  the model-independent retrieval/rerank/read overhead, so K2.6 is needed to isolate the model share.

**LANDING NOTE (2026-06-16): experimented with BOTH K2.6 and K2.7-Code; NO operational/model change
concluded yet.** Both deliver ~**0.83 effQ — the best self-hosted model scores recorded to date**
(vs the ~30B cluster ~0.73), and are quality-equivalent to each other (A +0.012, B −0.005, within
noise). K2.7-Code is an attractive production candidate (equivalent quality, lighter reasoning,
faster), but a switch is **deferred** pending: the K2.6 latency half, a prod-grade serving (the HPC
tunnels flapped repeatedly), and a latency-SLA decision. The branch lands as a research record +
the two src fixes (`query_planner.clj` deployment-name, `client.clj` socket-timeout), not a model
change.
