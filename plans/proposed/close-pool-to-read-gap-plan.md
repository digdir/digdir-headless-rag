# Closing the pool→read gap (the ~25–30pp the agent leaves unread)

**Status:** proposed (2026-06-07)
**Worktree:** `establish-baseline`
**Predecessor:** `plans/completed/local-model-sweep-conclusions.md` (read this first)
**Relates to:** `plans/proposed/read-stage-ceiling-plan.md`, `plans/completed/agent-read-budget-refund-plan.md`, `plans/completed/read-tool-sufficiency-signals-plan.md`

---

## The problem (from the enrichment A/B, `results/sweep-2026-06-06T13-05-58-917967Z`)

Filter-4 decomposition on the best current config (qwen-adapted prompt + enriched-HQ):

| stage | rate | leak to next |
|---|---|---|
| golden **in candidate pool** | ~0.80 | |
| golden **in display window** | ~0.71 | −0.09 (pool→display) |
| golden **read** by the agent | ~0.55 | **−0.16 (display→read)** |
| → final recall@10 | ~0.53 | |

The golden is *retrievable* ~80% of the time, but the agent only *reads* it ~55%. That
**~25pp pool→read leak** (≈9pp at display truncation + ≈16pp at the agent's read decision)
is the dominant remaining bottleneck — bigger than anything enrichment moved (+6pp). And
`recall@k` can't see past it: `n-retrieved` median is **3**, so recall@10 == recall@20.

**Ceiling note:** the pool itself caps at ~80% on this question set, so fully closing
pool→read tops out around recall@10 ≈ 0.80. The other ~20% (golden never in pool) is a
separate *retrieval-coverage* problem (different plan — query expansion / chunking /
phrase-pruning), explicitly out of scope here.

## Hypothesis

The agent is too *selective* at the read stage — it keeps ~3 chunks and skips displayed
goldens. Forcing/widening reads (and widening the display window feeding them) should
convert in-pool goldens into read goldens, and read goldens into answer quality (the A/B
showed read→quality conversion is real: +0.06 read drove +6.7pp effQ).

## Levers (ranked; each is an A/B vs the current best config)

### Lever 1 — `auto-read-top-k` (PRIMARY; attacks display→read) ✅ already wired
`tools.clj` already auto-reads the top-K reranked chunks by merged rank, budget-respecting,
additive, capped at 10. Read directly from `:skill-params {:builtin/agent {:auto-read-top-k K}}`
(default 0 = off). **No plumbing needed** — sweep `K ∈ {0, 3, 5}`. This forces a displayed
golden into the workspace regardless of the LLM's selective read → read-rate should climb
toward the display-rate (~0.70). Watch: each auto-read adds prefill (wall-clock ↑) and
consumes read budget; pair with Lever 2 so auto-reads don't starve the agent's own reads.

### Lever 2 — read budget (`:max-read-operations`, `:max-read-content-length`)
`resolve-budget-limits` (workspace.clj) reads these. ⚠️ **Plumbing check (step 0):** they
resolve from the agent's `parameters`, NOT from `:skill-params` — confirm whether a matrix
config can set them; if not, add a small lift (mirror the `:agent-prompt-variant` pattern:
read `[:skill-params :builtin/agent :max-read-operations]` in `core.clj`/`invoke.clj`).
Then sweep a higher read allowance so the agent (and auto-read) can keep more than ~3 chunks.

### Lever 3 — display window up (`:builtin/rerank :context-top-k`, `:max-context-length`)
Sweep-1 only tested context-top-k DOWN (reduced budget, which hurt). UP is untested.
Raising context-top-k (10→15/20) attacks the pool→display leak (−0.09). Cost: more prefill;
the rerank-truncation memory says don't shrink per-chunk length, only widen count/total.

### Lever 4 — read-signal permissiveness (`read_signals.clj` `evaluate-read`)
The read evaluator gates which displayed chunks are worth reading. A more permissive
threshold (or skipping the gate when `auto-read-top-k` is on) could lift display→read.
Lower-confidence lever; try only if 1–3 underperform.

### Lever 5 — prompt nudge (read-before-answer)
Add to the (already-persisted) qwen-adapted prompt: "Before generating, read the top
displayed results even if you think you have enough." Cheapest, but the A/B showed the
agent under-reads despite the current prompt — likely weaker than the mechanical levers.

## Method

- **Baseline (control):** the locked config + enriched-HQ — i.e. the A/B's `enriched-hq`
  arm, `auto-read-top-k 0`. Reuse `local-hq-ab-qwen-n5.edn`'s enriched arm.
- **Screen (cheap, fast):** Levers 1–3 one-at-a-time, **8-Q diverse subset (the Sweep-1
  set) × N=3, concurrency 1**, gpt-5.5 judge. Primary metric: Filter-4 **read-rate** and
  **recall@10**; secondary: effQ, wall-clock (auto-read inflates prefill), empty/timeout.
  Kill any lever that doesn't move read-rate.
- **Confirm:** best 1–2 configs → **full-42 × N=3** vs the baseline, gpt-5.5 judge — the
  effQ headline. Use `bb sweep` (agent-run env) then `bb sweep-judge` (cloud, separate pass,
  restore config after).
- **Interaction:** test `auto-read-top-k 5` × `context-top-k 15` together once the
  one-at-a-time screen identifies the live levers (auto-read needs a wide enough display
  to have goldens to grab, and enough read budget to keep them).

## Success criteria
- **Read-rate** ↑ materially toward the ~0.80 pool ceiling (target ≥ 0.70, from ~0.55).
- **effQ** ↑ beyond the enriched baseline (0.53), ideally toward ~0.60+, **without** an
  empty/timeout regression and within a tolerable wall-clock budget (auto-read adds prefill).
- Honest reporting: paired per-question Δ + win/loss (the A/B's t≈1.55 shows N=3/42 is
  noisy; prefer larger N for the final claim).

## Guardrails / watch-outs
- **Pool ceiling ~0.80** — don't chase past it here; the residual 20% is retrieval coverage.
- **Wall-clock** — auto-read + wider context = more prefill; local is prefill-bound. Track
  `elapsed-ms` and keep concurrency 1.
- **Collections** — no new generation needed (reuse `website_enrichment_hypothetical_questions_qwen_n5`); never delete collections.
- **Config hygiene** — agent runs azure-off; judge azure-on in a separate pass; ALWAYS
  restore (`use-azure false`, `services.judge.model "qwen3.6-35b-a3b-mtp"`) afterward.

---

## RESULTS LOG

### Lever 1 — `auto-read-top-k` screen (2026-06-07): NULL on this subset

Matrix `local-autoread-screen.edn` (`auto-read-top-k ∈ {0,3,5}`, control == the
A/B `enriched-hq` arm), 8-Q Sweep-1 subset × N=3 = 72 runs, conc 1.
Agent run `results/sweep-2026-06-07T09-51-36-592459Z` (0 empty, 0 timeout),
gpt-5.5 judge (72/72, separate pass).

| config | pool | disp | read | r@10 | n-ret | ans-hit | **effQ** | corr/part/inc | el-s | pTok |
|---|---|---|---|---|---|---|---|---|---|---|
| autoread-0 (control) | 0.79 | 0.67 | 0.625 | 0.563 | 2.42 | 0.79 | **0.523** | 8/11/5 | 143 | 14.3k |
| autoread-3 | 0.88 | 0.71 | 0.667 | 0.625 | 2.88 | 0.83 | **0.525** | 8/8/8 | 125 | 8.5k |
| autoread-5 | 0.83 | 0.71 | 0.667 | 0.604 | 3.17 | 0.88 | **0.533** | 7/12/4 | 145 | 10.5k |

Paired per-Q effQ: **k3 Δ+0.002 (3W/5L), k5 Δ+0.010 (4W/4L)** — a wash. The
mechanism fires (n-retrieved 2.42→3.17, ans-hit 0.79→0.88, both monotonic in K),
but it does not convert to judged quality: auto-read helps the two low-baseline
retrieval Qs (authz-01 0.28→0.52, broker-03 0.37→0.52) and equally hurts two
others (corr-02 0.60→0.32, sys-03 0.65→0.50). No health regression; K=3 was even
faster/cheaper.

**Why the screen can't validate Lever 1: the subset has no read leak to close.**
On this 8-Q set the control display→read gap is only ~0.04 (read 0.625 vs display
0.667), versus the **0.16** gap on full-42 that motivated this plan. Auto-read
force-reads the top-K *displayed* chunks, but the read-rate was already tracking
the display-rate here — there was nothing left on the table. **Lever 1 is not dead
— this cheap screen is structurally blind to it.** Exercising it needs a full-42
run where the 0.16 display→read leak actually exists. Deferred: carry
`auto-read-top-k 5` into the full-42 confirm / interaction test, not a standalone
8-Q screen.

**Methodological note for the rest of this plan:** the Sweep-1 8-Q subset was
chosen for the *prompt/budget* screen (Sweep-1), where the levers act upstream of
read. It is a poor screen for *read-stage* levers because its control read-rate is
already near its display-rate. Lever 2 (read budget) and Lever 3 (display window)
act earlier in the pipe (display→read source, pool→display) so the subset may show
more — but watch for the same blindness and prefer the full-42 confirm for the
verdict.

### Lever 2 plumbing (the step-0 lift) — shipped

Confirmed `resolve-budget-limits` resolved only from the graph's `parameters`
(`core.clj:813`), which the sweep runner / invoke-rag cannot reach (they pass
`:skill-params`). Added `workspace/resolve-budget-limits-from-skill-params`
(skill-params `[:builtin/agent {:max-read-operations … :max-read-content-length …
:max-search-passes …}]` win per-key over `parameters`, absent keys → defaults),
wired in `core.clj`. 3 isolation-safe tests in `workspace_test.clj` (13/13 pass).
`:max-read-operations` / `:max-read-content-length` are now sweepable from a
matrix config.

### Lever 2 + Lever 3 combined screen (2026-06-07)

Matrix `local-read-display-screen.edn` (shared control + read-hi/read-xhi +
disp-15/disp-20), 8-Q Sweep-1 subset × N=3 = 120 runs, conc 1.
Agent run `results/sweep-2026-06-07T13-00-20-360238Z` (0 empty, 0 timeout),
gpt-5.5 judge (120/120).

| config | pool | disp | read | r@10 | n-ret | ans-hit | **effQ** | corr/part/inc | paired Δ |
|---|---|---|---|---|---|---|---|---|---|
| control | 0.92 | 0.79 | 0.583 | 0.542 | 3.38 | 1.00 | **0.602** | 9/11/4 | — |
| **read-hi** (ops 10 / 24k) | 0.92 | 0.79 | **0.708** | 0.646 | 2.75 | 0.92 | **0.615** | 9/12/3 | **+0.013 (4W/4L)** |
| read-xhi (ops 12 / 36k) | 0.79 | 0.75 | 0.625 | 0.583 | 2.71 | 0.83 | 0.510 | 4/14/6 | −0.092 (3W/5L) |
| disp-15 (ctxk 15 / 24k) | 0.88 | 0.63 | 0.458 | 0.396 | 3.58 | 0.79 | 0.521 | 6/11/6 | −0.081 (5W/3L) |
| disp-20 (ctxk 20 / 32k) | 1.00 | 0.92 | 0.708 | 0.646 | 2.92 | 0.75 | 0.490 | 6/11/7 | −0.112 (2W/6L) |

**Lever 2 (read budget): the read-stage lever that actually moves — at a sweet
spot.** `read-hi` (max-read-operations 10 / max-read-content-length 24000) lifts
read-rate **0.583→0.708 (+0.125, meets the plan's ≥0.70 target)** and recall@10
**+0.10** with the SAME display-rate — i.e. it converts *displayed* goldens into
*read* ones, exactly the display→read leak this plan targets. It's also faster
(138s) and cheaper (10.3k vs 18.3k prompt tokens). BUT effQ is only +0.013
(paired 4W/4L = a tie at n=24) — the mechanism fires; the judged-quality payoff
is marginal/within noise on this subset. `read-xhi` (12 / 36000) **regresses**
(effQ −0.092, correct verdicts 9→4): past the sweet spot, over-reading dilutes the
35B local model. ⇒ moderate budget only; **10 / 24000 is the operating point.**

**Lever 3 (widen display): negative both directions — DROP IT.** `disp-15`
regresses (−0.081). `disp-20` is the decisive cautionary result: **best retrieval
of any arm (pool 1.00, read 0.708, recall 0.646) but the WORST effQ (0.490,
−0.112)** and lowest ans-hit (0.75). Retrieval up, answer quality down — flooding
the local model with 20 chunks / 32k chars degrades synthesis (context dilution).
This kills the planned `auto-read-5 × context-top-k 15` interaction: widening the
window is counterproductive for this model. (Caveat: disp-15's display-rate 0.63 <
control 0.79 despite MORE slots is backwards → n=24 retrieval variance; but
disp-20's retrieval-up/quality-down is the robust, repeatable signal.)

### Screen verdict across Levers 1–3 (8-Q, N=3, gpt-5.5 judge)

- **Lever 1 (auto-read-top-k): NULL** — subset structurally blind (no read gap here).
- **Lever 2 (read budget): the live lever**, but only at the moderate setting
  (`read-hi` 10/24000): read-rate +0.125 (target met), effQ flat-to-slightly-up
  (+0.013, within noise). More budget hurts.
- **Lever 3 (display window): NEGATIVE** — widening degrades local-model synthesis.

**Recommended confirm:** full-42 × N=3, `read-hi` vs control, PLUS an
`auto-read-5 + read-hi-budget` interaction arm (Lever 1 needs the read budget to
keep what it grabs; the full-42 set actually has the 0.16 display→read gap the 8-Q
screen lacked, so Lever 1 gets a fair test there). Do NOT carry any disp arm
forward. effQ is the headline; report paired per-Q + multi-golden fractional
recall (don't let the `any-golden` booleans mask coverage caps like events-01,
whose 2nd golden `4aa2b0740446` is never retrieved → hard recall@10 cap 0.5).

---

## STARTER PROMPT (paste into a fresh session)

> Continue the local-model RAG work in the `establish-baseline` worktree. Read these first:
> `plans/proposed/close-pool-to-read-gap-plan.md` (this plan) and
> `plans/completed/local-model-sweep-conclusions.md` (what's already done). Also recall the
> memories `project_local_agent_sweep_findings`, `project_local_model_enrichment`,
> `project_rerank_truncation_bottleneck`.
>
> Context in one line: the local agent + local-generated HQ enrichment work (enriched effQ
> 0.529 vs off 0.461, +6.7pp); the dominant remaining bottleneck is that the golden chunk is
> in the candidate pool ~80% but the agent only *reads* it ~55% — a ~25pp pool→read leak.
>
> Goal this session: close that gap. Start with **Lever 1, `auto-read-top-k`** — it's already
> wired (`:skill-params {:builtin/agent {:auto-read-top-k K}}`, default 0, in `agent/tools.clj`),
> so no code change needed. Build a screen matrix off `local-hq-ab-qwen-n5.edn`'s enriched arm:
> configs `auto-read-top-k ∈ {0, 3, 5}`, the 8-question Sweep-1 subset, N=3, concurrency 1,
> `judge? false`. Then do step-0 of Lever 2: check whether `:max-read-operations` is settable
> from a matrix config (it currently resolves from agent `parameters`, not `:skill-params` —
> may need the same lift as `:agent-prompt-variant`).
>
> Runbook (all in the conclusions doc §6): the model `qwen3.6-35b-a3b-mtp` should be loaded on
> `bdbrodies-macbook-pro:1234`; run `bb sweep <matrix>` from repo root via `mise exec` with the
> agent-run `OPENAI_*` env (preserve_thinking + recommended sampling); judge with `bb sweep-judge
> <dir>` after flipping `use-azure-openai-api true` + `services.judge.model "gpt-5.5"`, then
> RESTORE both to local. Report Filter-4 read-rate + recall@10 + effQ per config, paired per-Q.
> Do NOT delete any Typesense collections.
