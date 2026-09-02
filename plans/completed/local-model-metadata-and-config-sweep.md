# Local-model run metadata + the performance↔quality config sweep

**Status:** ✅ CONCLUDED (2026-06-07) — all phases done; enrichment A/B run.
**Date:** 2026-06-05 → 2026-06-07
**Owner:** RAG / sweep tooling

> **Read the clean summary first:** `plans/completed/local-model-sweep-conclusions.md`
> (what we achieved). This file is the full process log/record.
> **Follow-up:** `plans/proposed/close-pool-to-read-gap-plan.md` (the ~25–30pp pool→read gap).
>
> **Bottom line:** local agent + local-generated HQ enrichment work end-to-end —
> enriched effQ 0.529 vs off 0.461 (**+6.7pp**, vs cloud's +8.6pp). Locked agent config:
> qwen-adapted prompt (persisted) + default budget + preserve_thinking + finalize
> empty-recovery/grounding + concurrency 1. Empty-rate 47%→0%.

## Why

We are migrating the agent / judge / enrichment-generation LLMs from a single fixed
cloud deployment (Azure `gpt-5.4-mini` / `gpt-5.5`) to **local models** served by
LM Studio (currently `qwen/qwen3.6-35b-a3b` on `bdbrodies-macbook-pro` over
Tailscale). Two things broke today that this plan fixes:

1. **A run's results were uninterpretable from the record alone.** We only learned
   from LM Studio's *logs* that `reasoning_effort: "low"` was silently falling back
   to `"on"`, and that the loaded model was the **MLX `6bit`** build (not the GGUF
   `Q6_K_XL`). Neither is captured in `runs.csv`. We stopped the in-flight A/B
   partly because it carried none of this.
2. **We don't yet know the right local config.** Local inference behaves nothing
   like cloud: it's **prefill-bound** on large agent contexts; the MLX engine has
   no/limited continuous batching (so `:concurrency` bought ~0 — ~117s/run); and
   reasoning is a hard quality↔speed tradeoff on this build (`on` = reliable but
   slow; `off`/`none` = fast but Qwen skips search & hallucinates). We should
   **sweep local model parameters** to find the performance↔quality sweet spot
   *before* committing to the full-42 N=3 A/B.

Cloud collapsed "model" to one fixed deployment, so a single `judge-model` column
sufficed. Local explodes it into `model × build/quant × engine × host × sampling ×
reasoning × concurrency`, several of which vary **silently**. The metadata work is
a prerequisite for the sweep: a screen is only interpretable if every cell records
exactly what ran.

---

## ✅ CONCLUDED — fixed config + focused test plan (2026-06-05)

Model-shopping (old Part B) is **resolved**. **Qwen 3.6 35B-A3B UD-MTP** (Q6_K_XL
GGUF, llama.cpp, served by LM Studio on the remote Mac `bdbrodies-macbook-pro` over
Tailscale; driven via SSH `lms`) is the chosen model for **all local LLM tasks**.
The **judge stays on gpt-5.5 cloud** (local judge only ~60% agreement — see
Findings). This collapses the model/engine/quant axes; what remains is per-task
config + a few real variables. This section supersedes the exploratory Part-B
candidate sweep (B1/B6).

### The four LLM tasks and their FIXED configs
| task | model | mode | sampling | notes |
|---|---|---|---|---|
| **Agent** (RAG loop) | Qwen3.6 MTP | thinking + **`preserve_thinking`** | thinking: temp1.0 / top_p0.95 / top_k20 / min_p0 / pp1.5 | batching ×2 + MTP ×1.3; multi-turn |
| **Query-planner** | Qwen3.6 MTP | non-thinking (prefill) | instruct: temp0.7 / top_p0.8 / top_k20 / min_p0 / pp1.5 | **migrate off litellm-azure → `digdir.llm.client`** |
| **Generation** (HQ) | Qwen3.6 MTP | non-thinking (prefill) | instruct | bulk; n-items under test |
| **Judge** | **gpt-5.5 cloud** | n/a | omit temperature (gpt-5.x) | trustworthy headline |

Fixed infra: engine **llama.cpp**, GPU offload **100%** (`--gpu max`), load
`-c 36000 --parallel 2`, runner `:concurrency 2`, `max_tokens 32768`.
`reasoning_effort` **dropped** (no-op on this GGUF). Non-thinking = `<think></think>`
prefill (the `enable_thinking` flag is a no-op here).

### Variables UNDER TEST
1. **`preserve_thinking`** (agent) — **CONCLUDED: ON** (2026-06-05 smoke, N=1/5Q).
   ON vs OFF: completion-tokens 4029 vs 4583 (−12%), llm-calls Σ24/max7 vs Σ29/max10,
   **cap-exhaustions 0 vs 2**, **empty answers 0 vs 2**, verdicts 5-correct vs
   2-correct+1-incorrect+2-empty. Mechanistic wins (tokens, no cap-out, no empties)
   are robust; the verdict magnitude is soft (noisy local judge). **It fixes
   cap-exhaustion without raising the iteration cap** — so it's the preferred lever,
   and max-iterations can likely stay low. Re-confirm at sweep scale w/ gpt-5.5 judge.
2. **Agent prompts** — default vs **Qwen-adapted** (THIS agent only): tool-use
   instructions, synthesis, language directive. Likely the biggest quality lever. [sweep]
3. **Agent context budget** — retrieval top-k / max-chunks-read / max-chunk-length:
   quality vs prefill cost (the dominant time cost). [sweep]
4. **n-items** (HQ generation density) — **5 vs 8**. [sweep]
5. **Agent budget** — `max-iterations` / `max-clarification-rounds`: start at the
   recommended values below, then **correct from sweep-1's cap-exhaustion data**.
6. **The A/B lever** — off vs enriched-HQ (the actual enrichment measurement),
   full-42, N — the headline experiment.

Recommended starting budget (then correct): **max-iterations 12**,
**max-clarification-rounds 1**, **max_tokens 32768** (avoid reasoning+answer truncation).

### Implementation tasks
- [x] **Query-planner migration — DONE & validated (2026-06-05).** Replaced all 3
  `litellm/completion :azure-openai` sites (`query_planner.clj` 304/464/630) with a
  `planner-completion` helper over `digdir.llm.client`: builds the OpenAI body
  (forced `tool_choice "required"`), routes azure-vs-local via `use-azure-openai-api`,
  normalizes `:tool_calls`→`:tool-calls` so existing parsers are untouched; retries
  (`with-llm-retries`) preserved. Functionally verified on local Qwen MTP: tool-call
  fired, structured `{:user-intent :phrases}` parsed, **6.5s** with non-thinking
  prefill — i.e. forced tool-calling + prefill compose. Planner re-enabled
  (`skills.query-planner.enabled true`). Caveat: Azure path still delegates to wkok
  (untested for `tool_choice "required"`; local is the target). Add as a 4th
  metadata-manifest entry (Qwen MTP, non-thinking).
- [x] **Qwen-adapted agent prompts (this agent only) — DONE (2026-06-05).** Variant
  mechanism scoped to the local agent: `server/test/fixtures/sweep/agent_prompts.edn`
  holds named system-prompt variants; a matrix config selects one via
  `:agent-prompt-variant <key>`, which `runner.clj`'s `apply-prompt-variants` resolves
  into `:skill-params {:builtin/agent {:system-prompt …}}` BEFORE the prod-base overlay
  (rides normal skill-param precedence; invoke.clj already lifts that to the graph's
  `:system-prompt` input). `:default`/absent → built-in prompt; unknown variant THROWS
  (no silent default that confounds the screen). Shipped variant `:qwen-adapted`: hard
  search-first tool-use discipline, Norwegian language directive (fixes the EN/NO drift —
  verified Norwegian-in/Norwegian-out on the smoke run), grounding/synthesis guidance.
- [x] **Prefill non-thinking toggle** — `OPENAI_DISABLE_THINKING` in
  `digdir.llm.client` + `propose_questions.clj`. Done, validated (gen+judge ≈ quality, ~6×).
- [x] **Env injection** — sampling params, `OPENAI_REASONING_EFFORT`,
  `OPENAI_PRESERVE_THINKING` in `digdir.llm.client`. Done.
- [x] **Part A metadata — DONE & validated (2026-06-05).** (a) Sweep-level
  `<sweep-dir>/models.edn` manifest, written once at sweep start by
  `resolve-model-manifest!`: per-task (agent/judge/generation) {provider, engine, model,
  endpoint, mode} + the live `OPENAI_*` sampling snapshot + the LM Studio `/api/v0/models`
  record (quant/arch/loaded_context_length/state/capabilities) for local models. The judge
  is recorded as its INTENDED cloud route (azure gpt-5.5, separate pass). Round-trips
  through `clojure.edn` (uses array-map, not sorted-map — the latter emits an unreadable
  `#sorted/map` tag). (b) Per-row CSV columns `reasoning-tokens` (summed off
  `usage.completion_tokens_details.reasoning_tokens`), `finish-reason` (terminal agent-llm
  turn), `length-finish?` (any turn truncated on `length`), `empty?`. Smoke-validated:
  reasoning-tokens 159 (thinking fired), finish-reason `tool_calls`, empty? false, agent
  record carried quant `Q6_K_XL` + loaded_context_length 60000.
- [x] **Concurrency plumbing fix (2026-06-05).** `run-from-files` previously DROPPED the
  matrix's `:concurrency` (existing `local-*-mtp/par` matrices ran serial silently); now
  threaded through, and `run-matrix` coerces an explicit nil → 1.
- [x] **Separate cloud-judge pass (2026-06-05).** `judge-sweep-dir!` + `bb sweep-judge
  <sweep-dir> [tenant]`: reads a completed `runs.csv`, joins references, judges each
  non-empty answer with gpt-5.5, writes `runs-judged.csv`. Idempotent (skips rows with a
  clean verdict). Resolves the judge=cloud-vs-agent=local config tension via two processes
  (agent sweep azure-off, then flip azure-on + run this) instead of in-run routing.

### Sweep-1 RESULTS (2026-06-05, `results/sweep-2026-06-05T20-00-09-448075Z`)

2×2 (prompt × budget) × 8 Q × N=1, concurrency 2, gpt-5.5 judge (separate pass:
17 judged / 15 skipped-empty). **No confident config winner at N=1** — the
empty-answer noise dominates. The real value was the diagnosis the new metadata made
visible:

| config | empty | recall@10 | judged verdicts | eff-quality* |
|---|---|---|---|---|
| adapted + default-budget | **5/8** | 0.31 | partial×2 correct×1 | 0.24 |
| adapted + reduced-budget | 3/8 | **0.56** | partial×5 | 0.36 |
| **default + default-budget** | **2/8** | 0.44 | partial×3 correct×2 incorrect×1 | **0.45** |
| default + reduced-budget | 5/8 | 0.25 | partial×1 correct×2 | 0.31 |

*eff-quality = judge-score with empty/timeout = 0 (fair end-to-end).

**The dominant failure mode is EMPTY ANSWERS (25–62%), not bad answers.** When the
agent answers, quality is fine (mostly partial→correct, graded-score⌀ 0.57–0.83).
`length-finish?` = 0 EVERYWHERE → empties are **not** token-cap truncation, so raising
`max_tokens` won't help. Empties decompose (13 total) into:
- **4 timeouts** (1/config, hit the 600s cap). Elapsed dist maxed at 599/600s ×4.
- **9 complete-but-empty** — agent ended a turn on `stop`/`tool_calls` with NO
  synthesized answer (iteration-cap/finalization gap). NB commit a738945 added a
  finalize-on-cap guard, yet these persist — it doesn't cover the `tool_calls`/`stop`
  empty-exit path. **This is the real blocker before any A/B; ~28% of runs.**

**Concurrency 2 is counterproductive for big agent prefills.** The concurrency-1 smoke
(dialog-01) ran 127s; under concurrency 2 the same model averaged 230–350s with 4 runs
pinned at the 600s cap — two simultaneous ~30k-token thinking prefills thrash one GPU
(the ~2× GGUF batching gain from Findings Q1b was measured on SMALL prefills, doesn't
survive agent-sized ones). The adapted prompt's hard search-first discipline made it
WORSE (prompt⌀ 34k, reasoning⌀ 510, empty 5/8) — more iterations → more timeouts.

**⇒ Corrections (in progress):**
- [x] **(1) Finalization empty-recovery — DONE (2026-06-05).** `graphs.clj`
  `execute-agent-finalize` non-exhausted path now detects a blank `:finalized?`
  response (with no clarification request) and recovers it through the SAME guarded
  best-answer-now fallback the exhaustion path uses (`fallback-on-exhaustion`):
  prefers the workspace's last generation, else an LLM nudge, else a non-blank
  sentinel — tagged `:recovered-empty-finalize?`. a738945 had only guarded the
  exhaustion path; this closes the `stop`/`tool_calls` empty-exit (~28% of runs).
  Covered by `test/digdir/skills/builtin/agent/finalize_recovery_test.clj` (5 tests,
  incl. clarification-preserved + LLM-fallback + sentinel).
- [x] **(2) Re-screen at concurrency 1 — DONE & both fixes VALIDATED (2026-06-06,
  `results/sweep-2026-06-05T21-35-48-003250Z`).** Same cells, `:concurrency 1`. vs the
  contaminated run: **empty 47%→3%, timeouts 4→1, complete-but-empty 11→0, median
  wall-clock 243s→119s.** gpt-5.5 judge (31/32). Per-config effQ (judge-score, empty=0):
  default+reduced 0.64, adapted+default 0.51, default+default 0.41, adapted+reduced 0.46.
- [ ] **(2b) NEW follow-up bug — finalize recovery emits SCAFFOLDING on retrieval misses.**
  The judge exposed a paradox: qwen-adapted won RETRIEVAL (recall@10 0.56 vs 0.34,
  golden-read 0.62 vs 0.38) but NOT judged quality (effQ 0.48 vs 0.53; 6 incorrect vs 4).
  All 6 adapted-incorrect runs were golden-read=false and their recovered "answers" were
  `"[SEARCH] Queries: […]"` / `"[SYSTEM] … all iterations used…"` / `"I must first find…"`
  — planning/search text, not answers. Root cause: `fallback-on-exhaustion`'s LLM nudge
  re-asks with bare `messages-init` (NO gathered context — see the admitted-TODO comment
  at graphs.clj ~L188), so on a miss the model just plans in prose. The empty-recovery
  thus traded "blank" for "non-blank garbage" on the hardest questions (worse in
  context-starved reduced-budget cells). **Fix: ground the fallback in the workspace's
  retrieved chunks (or run synthesis on them), or reject scaffolding-shaped fallback output
  in favor of an honest "couldn't find it." Do this BEFORE the N=3 confirm** — otherwise
  retrieval gains won't convert to answer-quality gains.
- [x] **(2b) Grounding fix DONE & validated (2026-06-06,
  `results/sweep-2026-06-05T23-10-08-436624Z`).** `fallback-on-exhaustion` now nudges
  with the loop's ACCUMULATED messages (`:messages-out` = assistant turns + tool
  results) instead of bare `messages-init`. Re-screen vs the pre-grounding run:
  **scaffolding-answers 3→0, empty 1→0**, wall-clock steady (~123s median); the 3
  scaffolding-incorrects became honest `partial`s. Prompt axis flipped to adapted's
  favour on quality too (effQ 0.49 vs 0.42) on top of its retrieval lead.
- [x] **(3) N=3 confirm — DONE, VERDICT CONFIRMED (2026-06-06,
  `results/sweep-2026-06-06T07-20-43-196336Z`).** Full 2×2 × 8Q × N=3 = 96 runs,
  concurrency 1, gpt-5.5 judge (32 unique answers). Health held at scale: 1 empty /
  1 timeout / 1 scaffolding in 96. effQ = mean±sd over per-question means:

  | config | effQ | recall@10 | gread | ans-hit | corr/part/inc |
  |---|---|---|---|---|---|
  | **adapted + default-budget** | **0.61 ± 0.21** | **0.69** | **0.75** | **0.92** | 10/10/4 |
  | adapted + reduced-budget | 0.50 ± 0.27 | 0.50 | 0.54 | 0.79 | 9/7/8 |
  | default + reduced-budget | 0.45 ± 0.23 | 0.38 | 0.42 | 0.79 | 6/9/7 |
  | default + default-budget | 0.36 ± 0.25 | 0.35 | 0.42 | 0.75 | 3/11/10 |

  **WINNER: qwen-adapted prompt + default budget.** Prompt axis (n=48 each): adapted
  effQ 0.55 vs default 0.40 (+0.15), recall@10 0.59 vs 0.36, correct verdicts 19 vs 9
  (doubled), incorrect 12 vs 17 — a robust, large win, not the N=1 noise. Budget axis
  is a quality WASH (default 0.48 vs reduced 0.47) but reduced STARVES the adapted
  prompt (0.61→0.50) by cutting context-top-k → keep default budget. `length-finish?`≈0
  and empties≈0 throughout, so **no max-iterations increase needed** (the finalize
  fixes handle exhaustion). ⇒ **Locked agent config for the local A/B: qwen-adapted
  prompt, default context budget, preserve_thinking on, finalize empty-recovery +
  context-grounded fallback, concurrency 1, max-iterations 12.**

### ENRICHMENT A/B RESULT — full-42 (2026-06-07, `results/sweep-2026-06-06T13-05-58-917967Z`)

Headline experiment: **off vs enriched-HQ**, HQ generated by LOCAL Qwen3.6 MTP
(non-thinking) over the 526 competing chunks → `website_enrichment_hypothetical_questions_qwen_n5`
(2587 questions, n-items 5). Full-42 × N=3 = 252 runs, concurrency 1, both arms on the
persisted qwen-adapted agent prompt (isolates the enrichment lever). gpt-5.5 judge
(251/252; health at scale: enriched 1 empty/1 timeout, off 0/0).

| arm | effQ (mean±sd) | recall@10 | gread | ans-hit | corr/part/inc |
|---|---|---|---|---|---|
| off | 0.461 ± 0.21 | 0.47 | 0.49 | 0.85 | 30/51/45 |
| **enriched-HQ** | **0.529 ± 0.25** | 0.53 | 0.55 | 0.90 | 31/63/**31** |

**Local HQ enrichment lifts answer quality +6.7pp effQ (0.461→0.529)** — reproduces
most of the cloud HQ's historical +8.6pp, at $0. Mechanism: it **converts failures into
partials** — incorrect verdicts 45→31 (−14), partials 51→63; fully-correct ~flat
(30→31) — driven by better retrieval (recall@10 +0.06, golden-read +0.06, ans-hit +0.05).
Paired per-question (n=42): mean Δ +0.067 (se 0.04, **t≈1.55**), enriched **wins 24 /
loses 18**. So the lever is **real and directionally matches cloud, but moderate, not a
slam-dunk** at N=3 — meaningful per-question variance (18 regressions). Biggest gains:
ue-sys-01, ue-corr-04, ue-dialog-07, ue-studio-07; biggest regressions: ue-api-06,
ue-dialog-02/05, ue-corr-03. Collections PRESERVED (no deletes; versioned `_qwen_n5`).

⇒ **The end-to-end local pipeline works: local agent (adapted prompt + fixes) + local
$0 HQ generation reproduces the cloud enrichment win.** Open follow-ups: n=8 density
arm; investigate the ~18 per-question regressions (enrichment noise crowding some
goldens — cf. [[project_phrase_promiscuity_noise]]); larger N to tighten the t-stat.

Metadata + judge-pass tooling worked end-to-end.

### Test phases
1. **Conclude preserve_thinking** (smoke — in progress).
2. **Implement**: planner migration → Qwen-adapted prompts → Part-A metadata.
3. **Sweep-1 (confirm agent config) — RUNNING (2026-06-05).** Matrix
   `server/test/fixtures/sweep/matrices/local-sweep1-agentcfg.edn`: 2×2 factorial
   {prompt: default/qwen-adapted} × {context-budget: default / reduced
   (`:builtin/rerank {:context-top-k 6 :max-context-length 5000}` — cuts prefill via
   fewer/shorter total context, NOT per-chunk length, to avoid re-introducing the
   rerank-truncation bottleneck)} × 8 diverse Q × N=1, concurrency 2, `judge? false`.
   Run with the agent-run `OPENAI_*` env (preserve_thinking + recommended thinking
   sampling). **Then judge separately**: set `services.judge.model "gpt-5.5"` +
   `services.azure-openai.use-azure-openai-api true`, then `bb sweep-judge
   results/sweep-<ts>`. Pick the agent config; correct `max-iterations` from
   `length-finish?` / cap-exhaustion data. (1-run path validated via
   `local-sweep1-smoke.edn`: 127s/run, recall@10 1.0, Norwegian-out, metadata captured.)
4. **Sweep-2 (n-items)** — grow HQ (non-thinking gen) at **n=5** and **n=8**; A/B each vs off.
5. **Confirm A/B** — full-42, N=3, chosen config, off vs enriched-HQ, **gpt-5.5 judge** — headline.

### Operational runbook (how to actually run this)
**Remote (where inference lives):** `bdbrodies-macbook-pro` (Tailscale; IP 100.67.63.119).
- SSH (key-only): `ssh -o IdentitiesOnly=yes -i ~/.ssh/id_ed25519 bdbrodie@bdbrodies-macbook-pro`
- `lms` path on remote: `~/.cache/lm-studio/bin/lms` (NOT `~/.lmstudio/bin`).
- HTTP API (OpenAI-compat): `http://bdbrodies-macbook-pro:1234/v1` (key `lmstudio`).
- **Load the model** (suppress the spinner with `>/dev/null`):
  `ssh … '~/.cache/lm-studio/bin/lms load "qwen3.6-35b-a3b-mtp" -c 36000 --parallel 2 --gpu max -y >/dev/null 2>&1; ~/.cache/lm-studio/bin/lms ps'`
- One 35B fits memory — `lms unload --all` before loading a different build.

**Env recipe** (the client reads these — `digdir.llm.client`):
- Always: `OPENAI_API_ENDPOINT=http://bdbrodies-macbook-pro:1234/v1  OPENAI_API_KEY=lmstudio`
- **Agent run** (thinking): `OPENAI_PRESERVE_THINKING=true OPENAI_TEMPERATURE=1.0 OPENAI_TOP_P=0.95 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5 OPENAI_MAX_TOKENS=32768`
- **Generation/standalone non-reasoning** (judge runs cloud; generation via `inspect_enrichment`/`add_corpus`): `OPENAI_DISABLE_THINKING=true OPENAI_TEMPERATURE=0.7 OPENAI_TOP_P=0.8 OPENAI_TOP_K=20 OPENAI_MIN_P=0.0 OPENAI_PRESENCE_PENALTY=1.5`
- Planner prefills itself (local) → no env needed for it.
- `bb sweep` MUST run from repo root; generation/config-set via `mise exec -- bb …`.

**Config-db state to (re)assert** (tenant digdir, via `bb config-set <path> <val> digdir <root> default`):
- `services.azure-openai.use-azure-openai-api false` (platform) — routes LLM local
- `services.azure-openai.deployment-name`/`.model-name` `"qwen3.6-35b-a3b-mtp"` (platform) — agent/planner model
- `services.judge.model` → for the **cloud judge**, set back to `"gpt-5.5"` + `use-azure-openai-api true` *for the judge process only* (judge is the one cloud task — run judging separately or special-case it)
- `services.self-improvement.provider :lmstudio` + `.model "qwen3.6-35b-a3b-mtp"` (platform), `services.lmstudio.api-endpoint "http://bdbrodies-macbook-pro:1234/v1"` — generation model
- `skills.query-planner.enabled true` (runtime)
- ⚠️ **Judge=cloud vs agent=local is a config tension** (both read `use-azure-openai-api`): the judge needs azure-on, the agent needs azure-off. Resolve in the metadata/run design — e.g. judge in a separate pass with its own config, or a judge-specific routing flag. (Open implementation detail.)

**Artifacts:** competing-chunk list `server/results/local-model-overnight/competing_chunks.edn` (526 ids); matrices in `server/test/fixtures/sweep/matrices/local-*.edn`; code: `digdir.llm.client`, `propose_questions.clj`, `query_planner.clj`.

---

## Part A — Expand per-run model metadata

### A1. Fields to capture (tiered)

**Tier 1 — must-have (each caused a real problem today):**
- `reasoning-effort-requested` — what we asked for (`low`)
- `reasoning-effort-effective` — what actually ran (`on`); the silent fallback
- `reasoning-tokens` (per run, summed) — proves whether/how much reasoning fired
  (would have flagged `low→on` instantly)
- `model-quant` (`6bit` MLX vs `Q6_K_XL` GGUF) — same id, different model, not comparable
- `engine` (MLX / llama.cpp / Azure) — governs batching + prefill cost + throughput
- `endpoint-host` (`localhost` vs `100.67.63.119`) — which machine ran it
- `provider` (`azure-openai` / `lmstudio` / `openrouter`)
- `max-tokens` + `finish-reason` (per call) — `length` finish = truncation = the
  empty-answer bug

**Tier 2 — reproducibility:**
- **Three separate model records, not one** — `agent-model`, `judge-model`,
  `enrichment-generation-model` can now differ; each carries {id, quant, engine,
  endpoint, reasoning, temperature}. (Today: agent+judge = Qwen MLX `on`;
  enrichment = Qwen `off`. None recorded.)
- Sampling: `temperature`, `top_p`/`top_k`/`min_p`, `seed`, penalties
- `context-length-loaded` (e.g. 64000) — interacts with truncation + llama.cpp ÷slots
- `concurrency`
- `empty-content-count` / per-call `finish_reason` histogram — first-class
  degenerate-answer signal (we currently eyeball it)

**Tier 3 — provenance:**
- enrichment-collection → its generation model + `prompt-hash` (rows already store
  `:provenance {:model :provider :prompt-hash}`; surface a run-level link)
- LM Studio server version + sweep-tool git SHA
- per-call latency breakdown (prefill vs decode if exposed)

### A2. Storage design (don't bloat every CSV row)

Most Tier-1/2 model fields are **constant within a (sweep, config)**. So:
- **Sweep-level manifest** (`<sweep-dir>/models.edn`, or fold into `matrix.edn`),
  captured once at sweep start, keyed by `config-id`: `{model-id, quant, arch,
  engine, context-length, endpoint, provider, reasoning-effective, temperature,
  max-tokens}` for each of agent / judge / generation.
- **Per-row dynamic fields** on `runs.csv`: `reasoning-tokens`, `finish-reason`,
  `empty?`, per-call latency, plus the existing prompt/completion/total/cached
  tokens + `llm-calls`.

### A3. Capture sources & implementation sketch
- `resolve-model-manifest!` at sweep start: query LM Studio **`/api/v0/models`**
  (richer than `/v1/models` — returns `quantization`, `arch`, `loaded_context_length`,
  `state`) for each configured model; merge with config (`provider`, `endpoint`,
  `reasoning requested`) and env (`OPENAI_API_ENDPOINT`, `OPENAI_REASONING_EFFORT`).
  Azure configs record `engine: azure` + deployment name. Write `models.edn`.
- Per call, read `reasoning_tokens` from `usage.completion_tokens_details` and
  `finish_reason` from the choice; aggregate onto the run row (the agent loop
  already records `:usage` + `:finish-reason` in workspace stage timing — surface
  them to the CSV writer).
- New CSV columns: `agent-model`, `agent-quant`, `agent-engine`, `provider`,
  `reasoning-requested`, `reasoning-effective`, `reasoning-tokens`,
  `finish-reason`, `empty?`, `concurrency`, `endpoint-host`. Judge/generation model
  identity lives in `models.edn` (keyed by config) to keep the row width sane.

**Single highest-leverage subset to ship first:** `models.edn` manifest from
`/api/v0/models` + per-row `reasoning-tokens` + `finish-reason`. Those alone would
have surfaced both of today's silent issues.

---

## Part B — Strategy: discover the real-world-performance ↔ quality sweet spot

### B0. The shape of the problem
Cost is ~0 locally, so this is a pure **throughput ↔ quality** Pareto search, not a
cost tradeoff. The dominant facts (measured today):
- Agent calls are **prefill-bound** (thousands of tokens of system + tools +
  retrieved chunks + history); a single GPU saturates on one large prefill.
- **MLX has no effective continuous batching** → `:concurrency` is wasted; **GGUF /
  llama.cpp does** (`--cont-batching`), but divides context across slots (64K÷8 = 8K/slot).
- Reasoning is a hard lever: `on` = reliable tool-use, slow; `off` = fast,
  unreliable (skips search, hallucinates). The current **MLX build honors only
  `on`/`off`** (`low` silently falls back to `on`).
- **One 35B-class model fits in memory at a time** on the remote — JIT-loading the
  GGUF Qwen while the MLX Qwen was loaded failed with *"insufficient system
  resources."* Consequence: the sweep must **sequence model loads** (batch all runs
  for one model, then swap), and a fast/quality model that fits *two* at once (so
  agent + judge can co-reside) is itself a selection criterion.

### B1. Axes to sweep (agent model = the bottleneck; **judge held fixed** — see B4)
1. **Engine / build**: `{MLX-6bit, GGUF-Q6_K_XL}` (same Qwen) — the primary
   *throughput* lever (batching). The GGUF variant is already on the remote
   (`unsloth/qwen3.6-35b-a3b`, not-loaded).
2. **reasoning_effort**: `{off, on}` — the primary *quality↔speed* lever.
3. **concurrency**: `{1, 4, 8}` — only pays off with a batching engine; interacts
   with (1). Confirms/quantifies the MLX-vs-llama.cpp batching difference.
4. **Agent context budget**: `{default, reduced}` (rerank context top-k / max chunk
   length / max chunks read) — a *local-specific* speed lever, since prefill cost
   scales with context. High value: may recover most of the throughput at little
   quality cost.
5. **(Optional) model size**: `{35b-a3b, a smaller model e.g. qwen3.5-9b}` — if
   quality holds, a large speed win.
6. **(Secondary) sampling** (`temperature`, `top_p`) — hold fixed initially; not
   expected to drive the sweet spot.

### B2. Method — screen → refine → confirm (avoid full cartesian)
- **Stage 1 — coarse screen** on the 3 dominant axes (`engine × reasoning ×
  concurrency`), on a **small fixed question set** (the diverse 8–12-question
  smoke set), **N=1**, enrichment **off** (we're characterizing the agent, not the
  lever yet). Cheap. Identifies the throughput/quality *regimes* and kills dominated
  configs.
- **Stage 2 — refine** the context-budget (and optionally model-size) axis on the
  1–2 surviving configs from Stage 1.
- **Stage 3 — confirm**: take the chosen config and run the **full-42 N=3 A/B**
  (off vs enriched-HQ) — the trustworthy lever measurement, now on a
  characterized, metadata-stamped local config.

Use a **greedy / one-axis-at-a-time** descent from a sensible baseline rather than
the full grid; the dominant interactions are (engine × concurrency) and (reasoning ×
quality), so screen those jointly and treat the rest as one-at-a-time.

### B3. Metrics (per config)
- **Performance**: runs/min at the tested concurrency, median + p90 per-run
  wall-clock, prefill tok/s, `reasoning-tokens`/run.
- **Quality**: judge-quality (correct=1.0, partial=0.5), golden-read rate,
  **empty-response rate**, recall@20.
- **Sweet-spot criterion**: the **knee of the (throughput, quality) Pareto curve** —
  the highest-throughput config whose quality is within a tolerance (≈2–3 pp
  judge-quality) of the best-quality config, subject to a **quality floor**
  (must not regress materially vs the cloud baseline, and empty-rate below, say, 10%).

### B4. Critical controls
- **Hold the judge fixed during the screen.** The judge is itself a local-model
  variable; sweeping the agent while the judge varies confounds quality. Use a
  single trusted judge for all screen cells — recommend **cloud `gpt-5.5`** (the
  existing reference standard) so quality scores are comparable, even though final
  production may use a local judge. (Sweeping the *judge* against gpt-5.5 agreement
  is a separate sub-study; the none-reasoning local judge was only 66% earlier.)
- **Hold the question set, retrieval config, and enrichment fixed** across the
  screen so only the agent-model axis moves.
- **Metadata (Part A) is a hard prerequisite** — without `reasoning-effective` +
  `quant` + `engine` per cell, the screen repeats today's "was it low or on?" /
  "MLX or GGUF?" ambiguity.

### B6. Candidate models to evaluate (incl. not-yet-downloaded)

The current model (`qwen/qwen3.6-35b-a3b`) is a reasonable baseline but exposes two
weaknesses we should shop against: (i) the MLX build only honors `on`/`off`
reasoning (no graded control), and (ii) `off` makes it unreliable at tool-use. The
sweep should include models that *might not have these problems* — i.e. **download
new candidates**, don't just sweep what's already local.

**Selection criteria for the AGENT model:** graded `reasoning_effort` support
(so a true speed/quality midpoint exists), reliable OpenAI-style tool-calling,
good Norwegian, and a build that fits memory (ideally small enough to co-reside
with the judge).

**Top candidates to download & test (agent):**
- **`gpt-oss-20b`** — *highest priority.* OpenAI open-weight MoE (~3.6B active) with
  **first-class `reasoning_effort` low/medium/high** as a documented feature, and
  built for agentic tool-use. Directly targets weaknesses (i)+(ii); fast MoE helps
  throughput. (The remote currently has only `gpt-oss-safeguard-20b`, a moderation
  variant — not the base model.)
- **`gpt-oss-120b`** — quality ceiling, still MoE-efficient; memory permitting.
- **A dense Qwen3 (e.g. Qwen3-32B)** — non-MoE may tool-call reliably at lower
  reasoning, isolating whether the `off`-unreliability is MoE-specific.
- **GLM-4.6** (strong agentic) and/or **Magistral** (Mistral's reasoning model) as
  alternates.

**Generation-side candidate (no tool-use needed → optimize Norwegian quality):**
- **`nb-llama-3.1-8b`** (Nasjonalbiblioteket Norwegian-tuned) — already on the
  remote; worth A/B-ing as the *enrichment-generation* model vs Qwen, since HQ
  generation only needs language quality, not tool-calling.

**Build/engine note:** each candidate has GGUF (llama.cpp → real continuous
batching) and MLX builds — the engine axis (B1.1) applies to all; prefer GGUF for
throughput unless quality differs.

### B7. Control plane — load-time vs per-request params (`lms`)

`lms load <model> -c <context-length> --parallel <count> --gpu <ratio> -y` sets
context + parallelism + GPU offload **at load time** (confirmed: `lms ps` shows
`CONTEXT`/`PARALLEL` columns; e.g. local has Qwen at context 20000, parallel 8).
Implications for the sweep:
- **Two param classes.** `context-length` and `--parallel` are **load-time**
  (changing them = a model **reload**, ~30–60s). `reasoning_effort`, `temperature`,
  and sampling are **per-request** (free to vary). → Sweep structure: **outer loop
  = load-time (engine/build × context × parallel), reloading between; inner loop =
  per-request (reasoning × sampling)**, many runs per load. This also composes with
  the one-model-fits-memory constraint (B0).
- **`:concurrency` (runner) must be ≤ the loaded `--parallel`.** The matrix's
  `:concurrency` only delivers throughput up to the slots allocated at load.
- **Control scope.** `lms` drives the *local* LM Studio. Driving the **remote**
  needs **LM Link** pairing (`lms link`) or hands-on `lms`/UI on the remote. Two
  execution options for the sweep:
  1. **Run on the local instance** (full programmatic `lms load` control — I can
     reload per config), at the cost of tying up the local GPU; or
  2. **Set up LM Link to the remote** so loads can be scripted there too (keeps the
     local machine free). Preferred if pairing is feasible.
  A scripted `lms load … -y` between configs is what makes the load-time axis
  sweepable without manual UI steps.

### B5. Infra need that unblocks the sweep
Model/endpoint/reasoning are currently **global** (`services.*` config-db +
`OPENAI_*` env), not per-matrix-config — so a clean grid isn't expressible today.
Add **per-config model overrides** to the matrix (each config may specify
`{provider, model, endpoint, reasoning-effort, context-budget}`), resolved by the
runner and recorded in `models.edn`. This is the same plumbing the manifest reads,
so A2/A3 and B5 share code. (Alternative interim: one sweep per config, flipping
config/env between — workable but error-prone and exactly what bit us today.)

---

## Sequencing
1. **Part A** — metadata manifest + per-row reasoning/finish_reason (prerequisite).
2. **B5** — per-config model overrides in the matrix/runner.
3. **Stage 1 screen** — engine × reasoning × concurrency, small Q-set, fixed gpt-5.5 judge.
4. **Stage 2 refine** — context budget (± model size) on survivors.
5. **Stage 3 confirm** — full-42 N=3 A/B (off vs enriched) on the chosen config.

## Findings — open questions answered with data (2026-06-05, GGUF Q6_K_XL via SSH+`lms`)

Loaded `qwen3.6-35b-a3b@q6_k_xl` (llama.cpp) on the remote with `lms load … -c
32000 --parallel 4 --gpu max`. Direct `/v1` probes:

**Q1a — reasoning_effort on the llama.cpp/GGUF build: DIFFERENT vocabulary, and no
off switch.** Supported values are **`none, minimal, low, medium, high`** (`off`
and `on` → HTTP 400 — the *opposite* of the MLX build, which took `on`/`off`).
BUT reasoning is **effectively always on** and barely graded on simple prompts:
reasoning_tokens were `none=244, minimal=235, low=225, medium=237, high=244` — no
meaningful gradient, and **`none` does NOT disable it** (=244 ≈ `high`).
`chat_template_kwargs {enable_thinking:false}` produced 294 reasoning tokens and
broke the output (11 chars). ⇒ **The GGUF Qwen build has no fast reasoning-off
mode** (unlike MLX, where `none`→0 reasoning).

**Q1b — does llama.cpp batch? YES (~2×), then plateaus.** Large 2542-token prompt,
`reasoning=minimal`: 1 req 6.5s; 2 concurrent wall 7.2s (**1.80×**); 4 concurrent
wall 13.3s (1.93×). So llama.cpp continuous batching is real (MLX gave ~1.0×), but
**caps at ~2× for large prefill-heavy requests** — the GPU saturates around 2
concurrent big prefills, so `--parallel > 2` doesn't add throughput for our
agent-sized contexts. **Net: GGUF + concurrency 2–4 ≈ doubles A/B throughput vs
the MLX serial run.**

**Q2 — the reasoning-on/off hybrid is not achievable on either Qwen build.** MLX
`off` is fast but unreliable (skips search / hallucinates); GGUF can't disable
reasoning at all. So a hybrid (reason for tool-decisions, no-reason for synthesis)
would need a model with a *true* reasoning-OFF that still tool-calls reliably —
i.e. it's a **model-selection** question, not an `agent/loop.clj` change. This is
the strongest argument yet for testing **`gpt-oss-20b`** (first-class graded
reasoning incl. a real low/off).

### Model-card findings (unsloth/Qwen3.6-35B-A3B-GGUF) — change how we test

Reading the card (https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF):
- **`reasoning_effort` is an LM Studio abstraction, not native.** Qwen3.6's real
  control is a **boolean** `enable_thinking` (default ON; emits `<think>…</think>`).
  The card states it "does not officially support the soft switch… `/think` and
  `/nothink`." ⇒ the `none/minimal/low/medium/high/xhigh` levels are LM Studio
  mapping onto a binary switch — hence the near-identical reasoning-token counts.
- **Official disable = `chat_template_kwargs:{enable_thinking:false}`** — which I
  tested and it **failed** through LM Studio (294 reasoning tok, broken 11-char
  output). So LM Studio's GGUF stack isn't honoring it; getting a true off-mode is
  an LM-Studio-version/template problem to chase, not a model limit.
- **⚠️ Our sampling is wrong.** The agent runs at `temperature 0.3` (the
  `agent/loop.clj` default) with no top_k/penalties. Qwen3.6 **thinking-mode**
  recommends `temp 1.0, top_p 0.95, top_k 20, min_p 0.0, presence_penalty 1.5`
  (non-thinking: `temp 0.7, top_p 0.8, top_k 20, presence_penalty 1.5`). This is
  likely hurting quality and may drive some empty/degenerate answers. **Add a
  SAMPLING axis to the sweep — and fix the default first.**
- **Context ≥128K recommended** "to preserve thinking capabilities"; we loaded at
  32K. Probably fine for ~5–15K agent prompts but worth a data point.
- **Throughput candidate: `unsloth/Qwen3.6-35B-A3B-MTP-GGUF`** (Multi-Token
  Prediction = speculative-decode-style speedup) — add to B6.
- **Tool-call parser = `qwen3_coder`** — relevant to whether LM Studio parses tool
  calls correctly on this build.

⇒ Sweep-design updates: (a) **sampling becomes a first-class axis** (recommended vs
default), and the recommended set should be the baseline; (b) "disable reasoning"
is gated on making `enable_thinking:false` work in LM Studio (or it's simply
unavailable, reinforcing the gpt-oss path); (c) add the **MTP** build to the
candidate list as the throughput play.

**MTP measured — ~1.31× decode, and it's real (2026-06-05).** Loaded
`qwen3.6-35b-a3b-mtp` vs base `qwen3.6-35b-a3b@q6_k_xl`, matched (`-c 36000
--parallel 2`), identical reasoning-heavy prompt (2000 tokens ≈ all reasoning):
**MTP 57.3 tok/s vs base 43.8 tok/s decode (~1.31×).** ⇒ LM Studio *does* use the
MTP heads for self-speculative decoding on this build. Because the GGUF path has
always-on reasoning (decode-heavy), this speedup applies to most of each call, and
it **stacks with batching** (~2× prefill). **The throughput config for Qwen 3.6
GGUF is the MTP build at `--parallel 2`.** Cost: +0.8 GB (the MTP head) and the
one-model-fits-memory constraint is unchanged (34.4 GB). Note: `lms load … >
/dev/null` suppresses the progress-spinner spam.

### GPU offload + the non-reasoning build-split (2026-06-05)

- **GPU offload = 100%.** `lms load --gpu max` ⇒ all layers on Metal (`lms load
  --estimate-only` reports `GPU Offload: 100%`, est. 33.07 GiB). `lms ps`/the API
  log don't expose it; `--estimate-only` does. Keep `--gpu max` on every load.
- **No off-switch for thinking on the GGUF/MTP build (definitive).** Controlled,
  3-run, identical sampling, "Hva er hovedstaden i Norge?" (needs zero reasoning):
  `enable_thinking:true` median **178** vs `enable_thinking:false` median **204**
  reasoning tokens — `false` reasoned *more*, i.e. the flag is a **no-op** (both
  the nested `chat_template_kwargs` and Alibaba-style top-level forms). With the
  recommended sampling on a real prompt the non-think arm looked lower (503 vs 832)
  but that's variance — the controlled test settles it. `reasoning_effort` is also
  ignored (LM Studio warning: "No valid custom reasoning fields found… cannot be
  converted to any custom KVs"). The model card prescribes
  `chat_template_kwargs:{enable_thinking:false}` and warns "support … varies by
  inference framework" with **no** llama.cpp/LM Studio note — this is the gap.
  ⇒ **The MTP/GGUF build can only run thinking mode; non-reasoning is unavailable,
  so the MLX swap (or a separate non-reasoning model) is unavoidable.** Thinking
  mode itself works well with the recommended sampling (good content, ~832-tok
  think chain).
- **The MLX build CAN do non-reasoning.** `reasoning_effort none` → **0 reasoning
  tokens**, clean output. Generation-style prompt: **2.2s (non-reasoning) vs 11.3s
  (reasoning)** — ~5× faster wall-clock (skips the `<think>` chain; tok/s is the
  same ~50, the win is generating ~112 vs 600+ tokens).
- **⇒ Build-level task split for Qwen 3.6:** non-reasoning tasks (enrichment
  generation, possibly judging) → **MLX + `reasoning_effort none`**; reasoning
  tasks (agent) → **GGUF/MTP** (batching + MTP). They can't co-reside (one 35B fits
  memory), but our phases are sequential, so swap between phases. **The metadata
  manifest (Part A) must record build + reasoning-mode per task** — agent and
  generation will legitimately differ.
- Open: a dedicated fast **non-reasoning model** (e.g. `nb-llama` for Norwegian
  generation) may beat MLX-Qwen-none for the non-reasoning tasks — B6 candidate.

**✅ SUPERSEDED — prefill trick gives non-thinking on the MTP/GGUF build (no swap).**
The documented `enable_thinking:false` is a no-op in LM Studio, BUT appending a
**closed think block as a trailing assistant turn** forces non-thinking at the
prompt level (bypasses the template): `messages=[…user…, {role:"assistant",
content:"<think></think>"}]` → **reasoning_tokens=0**, clean content. Measured on a
generation task: **thinking 10.5s (434 tok, 348 reasoning) vs prefill non-think
1.8s (73 tok, 0 reasoning) — ~6× faster**, good output. ⇒ **The MTP model serves
BOTH modes with one load, no MLX swap**: reasoning tasks send a normal request;
non-reasoning tasks (enrichment generation, judge) append the empty-`<think>`
prefill. Integration: add a prefill toggle (env flag, e.g. `OPENAI_DISABLE_THINKING`)
to `digdir.llm.client` (judge/agent path) AND `propose_questions.clj`'s own
`local-chat-completion` (generation uses the self-improvement direct-POST path, not
the client). Caveat: only valid for **single-turn** calls (don't append to the
multi-turn agent conversation — and the agent wants thinking anyway). Worth
broader validation that the prefill never degrades output.

**✅ Quality validated — non-thinking ≈ thinking for generation AND judge (2026-06-05).**
Implemented the prefill toggle (`OPENAI_DISABLE_THINKING` env) in `digdir.llm.client`
(judge/agent path) and `propose_questions.clj` (generation direct-POST path), then
compared on the loaded MTP model:
- **Generation** (6 chunks, HQ via `inspect_enrichment`): non-thinking produced 5
  specific/grounded questions/chunk, on par with thinking, **~6× faster**. Only
  diff: on the one *English* chunk thinking matched the source language (EN) while
  non-thinking went Norwegian — a wash for HQ.
- **Judge** (29 triples vs gpt-5.5 verdicts): thinking 17/29 = **59%** (median 1503
  reasoning tok) vs non-thinking 18/29 = **62%** (0 reasoning) — equal within noise,
  **reasoning buys nothing**, ~5–6× faster.
⇒ **Use non-thinking (prefill) for generation + judge; pay for thinking only on the
agent.** Separate note: the local Qwen judge's *absolute* agreement with gpt-5.5 is
only ~60% (gemma was 66%) — so for a *trustworthy* A/B headline, prefer gpt-5.5 as
judge; the local judge is a rough/relative proxy regardless of thinking mode.

### Remaining open questions
- End-to-end on GGUF: does always-on reasoning give reliable tool-use + good
  answers, and at what real per-run wall-clock with concurrency 2–4? (agent smoke
  in flight.)
- Is `-c 32000` total or per-slot on LM Studio's llama.cpp? (a single 2.5K-token
  request worked; the per-request ceiling vs `--parallel` is untested.)
- `gpt-oss-20b`: does it deliver a genuine reasoning gradient (fast `low`/`minimal`)
  + reliable tool-use? — the candidate most likely to give a real speed/quality midpoint.
- Quality floor: "within X pp of cloud `gpt-5.4-mini` baseline" vs an absolute
  judge-quality threshold.

## Relates to
- `plans/proposed/local-model-overnight-findings.md` — the local-model viability findings.
- Memory `project_local_model_enrichment` — own clj-http client, `reasoning_effort`
  passthrough, the on/off/`low→on` behavior, MLX-vs-GGUF engine note.
- `server/src-dev/digdir/sweep/runner.clj` — `:concurrency` already added; the
  manifest + per-config model overrides extend it.
