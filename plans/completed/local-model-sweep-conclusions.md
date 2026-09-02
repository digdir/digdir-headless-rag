# Local-model sweep — CONCLUSIONS (what we achieved)

**Status:** ✅ CONCLUDED (2026-06-07)
**Worktree:** `establish-baseline` (own config-db `dh_bb_dev_establish_baseline_v1`)
**Full record:** `plans/completed/local-model-metadata-and-config-sweep.md` (process log)
**Follow-up:** `plans/proposed/close-pool-to-read-gap-plan.md` (the remaining gap)

---

## One-paragraph summary

We made the **local** RAG stack work end-to-end and proved it reproduces the cloud
enrichment win at $0. Starting from a broken local agent (47% empty answers), we shipped
run-metadata instrumentation, a Qwen-adapted agent prompt, two agent-loop finalize fixes,
and a separate cloud-judge pass; screened the agent config (N=3) to a clear winner; then
generated hypothetical-questions (HQ) enrichment with the local Qwen and ran the full-42
off-vs-enriched A/B. **Result: local-generated HQ lifts answer quality +6.7pp (effQ
0.461→0.529), reproducing most of the cloud HQ's historical +8.6pp.** The remaining
bottleneck is diagnosed: the golden chunk is in the candidate pool ~80% of the time but
the agent only *reads* it ~49–55% — a ~25–30pp pool→read leak that no cutoff change
touches.

---

## 1. Code shipped (committed: `5cf2af3`, `ca21130`, `5003870`, `1c2ee01`, `6ed4472`)

| Area | What | Where |
|---|---|---|
| **Run metadata (Part A)** | `<sweep-dir>/models.edn` manifest (per-task model/quant/engine/endpoint/mode + OPENAI_* sampling + LM Studio `/api/v0/models` record); per-row CSV cols `reasoning-tokens`, `finish-reason`, `length-finish?`, `empty?` | `sweep/runner.clj` |
| **Prompt variants** | `:agent-prompt-variant` matrix key → named system-prompt from a registry; resolved before the prod-base overlay; unknown variant throws | `runner.clj`, `test/fixtures/sweep/agent_prompts.edn` |
| **Separate cloud-judge** | `judge-sweep-dir!` + `bb sweep-judge <dir>` — judge a completed runs.csv with gpt-5.5 in a second pass (resolves the cloud-judge vs local-agent `use-azure-openai-api` tension via two processes); idempotent | `runner.clj`, `bb.edn` |
| **Concurrency fix** | `run-from-files` had silently dropped the matrix's `:concurrency`; threaded + nil-coerced | `runner.clj` |
| **Finalize empty-recovery** | non-exhausted finalize path recovers a blank `:finalized?` answer via the guarded best-answer-now fallback (closes the `stop`/`tool_calls` empty-exit a738945 missed) | `agent/graphs.clj` |
| **Finalize grounding** | `fallback-on-exhaustion` now nudges with the loop's ACCUMULATED messages (assistant turns + tool results), not bare `messages-init` — kills scaffolding-garbage answers on retrieval misses | `agent/graphs.clj` |
| **Tests** | 6 isolation-safe tests for the finalize recovery/grounding | `test/.../agent/finalize_recovery_test.clj` |
| **Dashboard** | manifest panel + reasoning/empty/length-cut stats + finish-reason histogram + new run columns | `sweep/dashboard.cljc` |
| **Persisted config** | qwen-adapted prompt written to the agent's stored skill-params (`builtin/agent-rag-agent` → `:builtin/agent :system-prompt`) so it's the default for both A/B arms | config-db (agents-db) |

Two real bugs caught while verifying: `:concurrency` silently dropped; `models.edn`
`sorted-map` serialized as an unreadable `#sorted/map` tag (→ array-map).

## 2. The locked local-agent config (Sweep-1 verdict, N=3)

`results/sweep-2026-06-06T07-20-43-196336Z` — 2×2 {prompt × budget} × 8Q × N=3, gpt-5.5 judge:

| config | effQ | recall@10 | correct/partial/incorrect |
|---|---|---|---|
| **qwen-adapted + default-budget** | **0.61 ± 0.21** | 0.69 | 10/10/4 |
| adapted + reduced | 0.50 | 0.50 | 9/7/8 |
| default + reduced | 0.45 | 0.38 | 6/9/7 |
| default + default | 0.36 | 0.35 | 3/11/10 |

**Prompt is the big lever** (n=48/arm): adapted effQ 0.55 vs default 0.40, recall@10 0.59
vs 0.36, **correct verdicts doubled (19 vs 9)**. **Budget is a quality wash** but reduced
budget STARVES the adapted prompt → keep default.

⇒ **Locked config:** qwen-adapted prompt (persisted) · default context budget ·
`preserve_thinking` on · finalize empty-recovery + context-grounded fallback ·
concurrency 1 · max-iterations 12. Judge = gpt-5.5 cloud, separate pass.

### The fixes that got us here (empty-answer collapse)
| run | empty | timeouts | scaffolding | median wall-clock |
|---|---|---|---|---|
| baseline (concurrency 2) | 47% | 4/32 | 3 | 243s |
| + concurrency 1 + finalize empty-recovery | 3% | 1/32 | 3 | 119s |
| + context-grounded fallback | 0% | 1/32 | 0 | 123s |

- **Concurrency 2 thrashes** big agent prefills (two ~30k-token thinking prefills saturate one GPU) → use concurrency 1.
- **Empties were never token-truncation** (`length-finish?`≈0); they were timeouts + agent finalizing on `stop`/`tool_calls` with no synthesized answer.
- **The grounding fix** stopped the recovery from echoing planning/search scaffolding on retrieval misses.

## 3. The headline — enrichment A/B (full-42, N=3)

`results/sweep-2026-06-06T13-05-58-917967Z` — off vs enriched-HQ, both arms on the
persisted adapted prompt (isolates only enrichment), concurrency 1, gpt-5.5 judge (251/252).
HQ generated by **local Qwen3.6 MTP non-thinking** over the 526 competing chunks →
`website_enrichment_hypothetical_questions_qwen_n5` (2,587 questions, n-items 5, $0).

| arm | effQ (mean±sd) | recall@10 | golden-read | ans-hit | correct/partial/**incorrect** |
|---|---|---|---|---|---|
| off | 0.461 ± 0.21 | 0.47 | 0.49 | 0.85 | 30/51/**45** |
| **enriched-HQ** | **0.529 ± 0.25** | 0.53 | 0.55 | 0.90 | 31/63/**31** |

- **+6.7pp effQ** (vs cloud HQ's historical +8.6pp) — local generation reproduces most of the win, at $0.
- **Mechanism: converts failures → partials** — incorrect 45→31 (−14), partials 51→63, fully-correct flat (30→31).
- **Paired per-question (n=42): Δ +0.067, se 0.04, t≈1.55, wins 24 / loses 18.** Real and cloud-matching, but **moderate, not a slam-dunk** — meaningful per-question variance (~18 regressions).

## 4. The key diagnostic insight (recall@20 question → Filter-4)

`recall@10 == recall@20` (both arms) because **`n-retrieved` median = 3** (p90 ~6, max ~13)
— the agent's final cited set is tiny and never reaches rank 10, so loosening the cutoff
measures nothing. The useful lens is the Filter-4 stage decomposition:

| stage | off | enriched | Δ |
|---|---|---|---|
| golden **in pool** | 0.81 | 0.79 | −0.02 |
| golden **in display** | 0.70 | 0.71 | +0.01 |
| golden **read** | 0.49 | 0.55 | +0.06 |

- **Enrichment is a ranking/read lever, not a coverage lever** — the golden is already in
  the pool ~80% in both arms; enrichment's win is entirely at READ.
- **The dominant remaining bottleneck is pool→read (~25–30pp):** golden retrieved but not
  read. This is the agent's read decision (it keeps only ~3 chunks), not a cutoff artifact.
  → addressed by `plans/proposed/close-pool-to-read-gap-plan.md`.

## 5. Artifacts (preserved — no collections deleted)

- **Enrichment collection:** `website_enrichment_hypothetical_questions_qwen_n5` (2,587 HQ rows, 526 chunks). Generation is idempotent per-chunk; the codebase has **no collection-delete calls anywhere**.
- **Result dirs (each with runs.csv, runs-judged.csv, models.edn, matrix.edn):**
  - `sweep-2026-06-05T20-00-09-448075Z` — Sweep-1 N=1 (concurrency 2, contaminated baseline)
  - `sweep-2026-06-05T21-35-48-003250Z` — Sweep-1 N=1 (concurrency 1 + finalize fix)
  - `sweep-2026-06-05T23-10-08-436624Z` — Sweep-1 N=1 (+ grounding fix)
  - `sweep-2026-06-06T07-20-43-196336Z` — Sweep-1 **N=3 confirm** (config verdict)
  - `sweep-2026-06-06T13-05-58-917967Z` — **enrichment A/B** (the headline)
- **Matrices:** `local-sweep1-agentcfg.edn`, `local-sweep1-c1.edn`, `local-sweep1-n3.edn`, `local-hq-ab-qwen-n5.edn`, `local-sweep1-smoke.edn`.
- **Generation target:** `server/results/local-model-overnight/competing_chunks.edn` (526 ids).

## 6. Operational notes (for re-runs)

- Generation: `digdir.sweep.add-corpus/run {:chunk-ids-file … :enrichment-collection … :enrichment-type :hypothetical-questions :n-items 5 :create? true :tenant "digdir"}` with env `OPENAI_DISABLE_THINKING=true`. Model resolves via `services.self-improvement.model` (= qwen MTP), which beats `services.lmstudio.model` (gemma).
- Agent sweep env: `OPENAI_API_ENDPOINT=http://bdbrodies-macbook-pro:1234/v1 OPENAI_API_KEY=lmstudio OPENAI_PRESERVE_THINKING=true OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768`; run `bb sweep <matrix>` from repo root via `mise exec`.
- Judge tension: agent needs `services.azure-openai.use-azure-openai-api false`; the gpt-5.5 judge needs it `true`. Run the agent sweep azure-off (`judge? false`), then flip azure-on + `services.judge.model "gpt-5.5"` and `bb sweep-judge <dir>`, then **restore both to local** (`use-azure false`, `judge.model "qwen3.6-35b-a3b-mtp"`).
- Remote model load: `qwen3.6-35b-a3b-mtp` on `bdbrodies-macbook-pro:1234` (Q6_K_XL GGUF, llama.cpp), `loaded_context_length 60000`.

## 7. Open follow-ups
1. **Close the pool→read gap** — the big one; see `plans/proposed/close-pool-to-read-gap-plan.md`.
2. **n=8 HQ density arm** — does denser HQ widen the +6.7pp? (Sweep-2 phase-4's other half, not run.)
3. **The ~18 per-question A/B regressions** — enrichment noise crowding some goldens (cf. `project_phrase_promiscuity_noise` memory / phrase-pruning plans).
4. **Larger N** — tighten the A/B t-stat from ~1.55 (promising) toward significance.
