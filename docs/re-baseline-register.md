# Re-baseline register

**Standing document (#166).** When a defect is fixed mid-stream, results split into a before-set
and an after-set. Quietly comparing across that boundary is how a fake ceiling gets built — this
repo has done it once already, when a judge scoring answers truncated to 800 characters
manufactured a "model ceiling" that survived an entire arc.

## The rule

> **A number is only comparable to another number taken against the same code, *the same data*,
> and *the same model as the server actually ran*.**

The second clause was added 2026-08-24 after entry 3 below, which is not "measured against
superseded code" but "measured against an **unknown corpus**". Same-code is not sufficient: a
harness that resolves its collections at run time and discards them produces a number nobody can
place afterwards.

The third clause was added the same day after entry 4, and it is the one the rest of this document
cannot enforce. **State the commit a measurement belongs to** — that is one line, and it is the
difference between a result and a claim. But a commit only pins what *we* changed, and entry 4 is a
change nobody here makes.

## How to use this

Three steps, in order. **A register that says "re-measure everything" says nothing** — the value is
in the exclusions, and every exclusion carries its reason.

1. Name the decisions that actually depend on a pre-fix measurement. Most will not.
2. For each, ask whether the defect *could have moved that metric*.
3. Re-run only what survives step 2.

---

## The entries

### Entry 1 — the planner prompt dropped its same-language instruction

`when-not` returns only its last form, so the rule never reached the built prompt. `clj-kondo` had
been quoting the missing string verbatim as an `Unused value` warning the whole time.

| | |
|---|---|
| introduced | **`e7a51d6`, 2026-05-27** — the commit that *added* the language rule shipped the bug that dropped it |
| fixed | **`3f0abb0`, 2026-08-21 16:37** (merged `b5ad03b`), issue #75 → PR #163 |
| fires only when | `translate?` is false, i.e. `corpus-language` is nil/blank — **the default path** |

**Crucially, the defect was not reachable from most sweeps.** The planner has three eras:

| era | window | planner behaviour | entry-1 exposure |
|---|---|---|---|
| **A** | before **`bcf6de5`, 2026-06-15** | in azure-off sweeps the planner picked its model from `services.azure-openai.deployment-name`, which **404s** with azure off, so it **fell back to the raw query and never built a prompt at all** | **immune — no prompt, no defect** |
| **B** | 2026-06-15 → 2026-08-21 | planner fires, prompt missing the same-language rule | **affected** |
| **C** | after `3f0abb0` | clean | n/a |

Era A is why most of the retrieval arc is excluded below. Note the asymmetry: **production is
azure-on**, so prod ran the defective prompt from 2026-05-27 to 2026-08-21 — that is a product
fact, not a measurement one.

**Which metrics it can move:** anything language-sensitive. The corpus is Norwegian, and the
dropped rule required generated search phrases to be in the user's language.

### Entry 2 — the graph variants never streamed content deltas

`agent-rag-graph-bundled` and `-faithful` called the 5-arity `call-llm`; the imperative loop called
the 6-arity.

| | |
|---|---|
| fixed | **`b7c00e8`, 2026-08-21 15:31** (merged `35c4cad`), issue #148 → PR #151, tracked in #153 |
| shape of the fix | **purely additive** — passes `{:progress-fn progress-fn}`; the `llm-response` binding is otherwise unchanged |

**The fix does change the transport.** In `call-llm`, a supplied `progress-fn` routes to
`stream-call!`; without it the call is a blocking request. Both arities return the same response
*shape*, so:

- **answer-content metrics — NOT affected.** Same model, same params, same returned shape.
- **latency and time-to-first-token — affected.** Wall-clock characteristics of a streaming vs
  blocking request differ, and **TTFT is not merely wrong on the old path, it is unobtainable** —
  nothing emitted deltas to measure.

### Entry 3 — the 114 s latency figure's corpus is unrecoverable

Added 2026-08-24. Reproduced from the June sweep with a real CSV reader: **16 runs, median 114 s,
min 54 s.** But the harness's `resolve-dataset-config!` resolved the collections at run time and
**discarded them**, and the config DB that could have answered it afterwards **has since been
deleted**.

So this is not a superseded-code problem. **It is a number whose data is unknown**, which is why the
rule above needed its second clause. Provenance recovery from the corpus side, and provenance
columns in the harness, are being handled separately.

### Entry 4 — a vendor can invalidate a baseline with no event on our side

Added 2026-08-24 from #97. **Prospective**: it invalidates nothing measured so far, and it will
apply to every hosted model we ever measure.

All three Kimi models in Azure Foundry are **Preview**, and Microsoft states:

> *"We'll upgrade all deployments of preview models to either future preview versions or to the
> latest stable, generally available version."*

**This is categorically different from entries 1–3, and the difference is what the register was
built on.** Each of those left something to point at:

| | what changed | what was left behind |
|---|---|---|
| entry 1 | our code | a commit (`e7a51d6` → `3f0abb0`) |
| entry 2 | our code | a commit (`b7c00e8`) |
| entry 3 | our data | an *absence* — discoverable once someone looked |
| **entry 4** | **the vendor's model** | **nothing** |

No commit. No config change. No log line. **The deployment name stays identical**, which is the
whole problem: the field we would naturally record is the one guaranteed not to move.

So "state the commit your measurement belongs to" **cannot express this**, and neither can
"state the corpus". Both pin things on our side of the boundary.

#### What to record instead, and why it must come from the response

**Record the model identifier the *server* reports, per run, not the deployment name from our
config.** Azure returns a `model` field in the chat-completion response body; that is the server
telling you what it actually ran. Our config tells you only what we asked for.

This is the independence rule pointed at a new target: *the known answer must come from a source
your instrument cannot influence.* Our config is our instrument. The response is not.

Concretely, alongside the corpus and endpoint provenance columns:

- `model-reported` — from the response body, every run
- assert it is **unchanged** before comparing two sets of numbers; a difference is not a discrepancy
  to reconcile, it is **two different models**, and the comparison is void

#### The compounding risk, worth stating once

Entry 4 combines badly with the other #97 constraints. An **auto-upgraded** preview model whose
**documented languages are `en` and `zh`** could regress on Norwegian — our primary language —
silently, with **no version to pin, no event to detect it, and no standing to complain**, since
Microsoft never claimed the language. Any one of those is manageable. Together they mean a quality
baseline on this path has a shelf life nobody controls.

---

## The register

Every decision is either **NOT AFFECTED** with a reason, or named for re-measurement.

| # | Decision / claim | Informed by | Belongs to | E1 | E2 | E3 | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | **#25 — model quality gap** (incumbent ~0.735 vs K2.6 0.837 / K2.7-Code 0.850) | `kimi-A-headline-full42`, `kimi-B-broad118`, 2026-06-15 batch | era **B** | ⚠️ | — | — | **RE-MEASURE the absolute numbers; the gap needs a judgement call — see below** |
| 2 | **#25 — latency SLA** (median 114 s, mean 154 s) | `kimi-latency-bench`, conc-1, June | era **B**, pre-`b7c00e8` | ⚠️ | ⚠️ | ⚠️ | **RE-MEASURE — affected by all three** |
| 3 | **Tuned local levers** (snippet-AB +0.083, enrichment-HQ +0.068, read-hi, completeness-directive null) | local-arc sweeps, all azure-off, pre-2026-06-15 | era **A** | **NOT AFFECTED** — the planner 404'd to raw query, so no prompt was ever built | — | — | **NOT AFFECTED by E1.** E2 shared-constant (see note) |
| 4 | **"Levers shrink on a frontier synthesizer"** (snippet-AB +0.083 → +0.037 etc.) | kimi C/D/E/F stages, 2026-06-15 | era **B** | ⚠️ | — | — | **NOT AFFECTED for the direction; absolute deltas belong to era B.** Both sides of each ablation ran the same planner on the same model, so the defect is a true shared constant *within* a stage |
| 5 | **Retrieval-coverage: no headroom** (42/42 reachable, zero never-in-pool) | pooled 503 full-42 runs, 2026-06-09 | era **A** | **NOT AFFECTED** — pre-`bcf6de5`, planner never fired | — | — | **NOT AFFECTED** |
| 6 | **Golden-in-pool 98–99%** with `corpus-aware-2hop` working | 2026-06-15 batch | era **B** | ⚠️ | — | — | **RE-MEASURE if used as an absolute** — this is the most directly language-sensitive number in the set, since it measures what the planner's phrases retrieved |
| 7 | **#153 — graph-cutover equivalence** | *no measurement exists* | n/a | — | — | — | **CLOSED — see below** |
| 8 | **bundled vs faithful** comparisons (`baseline-v0`, `round-5-stacked-x-graph`, `smoke`) | sweep matrices | pre-`b7c00e8` | varies | shared | — | **NOT AFFECTED** — both variants were silent, so E2 is a shared constant; the comparison is graph-vs-graph |

### Why entry 1's effect on decision 1 is not a clean "shared constant"

The tempting argument — *both arms ran the same broken planner, so the gap holds* — is the argument
the June run log correctly made about the **404** bug, where the planner fell back to raw query
**identically for both arms**. It does not transfer here.

**The planner runs on the arm's own model** (`planner-completion`: "migrated off litellm-azure so
the planner runs on the same model as the rest of the pipeline"). So under a prompt that omits the
same-language rule, each arm compensates *with its own capability*. A frontier model may infer the
requirement from context; a ~30B may not.

**That means the defect can inflate or compress the measured gap, and the direction of the error is
not knowable without measuring.** It is still very likely that Kimi > incumbent — the gap is large
and holds on the broad set — but **the magnitude is the thing #25 is deciding on**, and the
magnitude is exactly what a model-dependent upstream defect perturbs.

### #153 — resolved, and the answer is stronger than "no"

**Step 1 of that issue closes it: no graph-versus-imperative comparison was ever run.** Every
`:skill-graph-id` in every sweep matrix is `agent-rag-graph-bundled` or `agent-rag-graph-faithful`
— both graph variants. No matrix, test or document names an imperative arm.

**But the issue's second point survives, and this finding sharpens it rather than softening it.**
The concern was that a user-visible divergence existed for three months that no test compared. The
actual situation is one step worse:

> **There is no equivalence measurement at all.** `:graph-cutover` is a tag on source skills, not a
> comparison. The claim "they behave equivalently" rests on code review, not on a measurement — so
> the streaming divergence was not *missed by* the equivalence exercise, it went unnoticed because
> **no exercise existed to miss it**.

Nothing is contaminated, because nothing was measured. That is the finding worth carrying into the
cutover decision.

---

## What re-measurement would cost, before anyone runs it

**Cheap, and worth doing first — the latency re-run.** `kimi-latency-bench` is 8 questions × N=2 ×
**conc-1** = 16 runs. At the observed ~114 s median that is roughly **half an hour of wall-clock per
arm**, plus provisioning. It also fixes all three entries at once: post-`b7c00e8` transport,
post-`3f0abb0` planner, and a recorded corpus.

**And it should measure the right thing.** The interactive target is p50 ≤ 30 s, but the 114 s
figure is *end-to-end completion* taken on a path that **could not emit a first token**. For an
interactive SLA, time-to-first-useful-output is the metric the target is about, and it has never
been measured on any path. Re-running without adding TTFT would answer the old question again.

**Expensive, and gated on something unresolved — the quality re-run.** `kimi-A` is 1 config × 42 Q ×
N=3 = 126 runs at conc-48, plus `kimi-B` at 118 Q, plus a gpt-5.5 judging pass. That is a weekend
batch, and it is **blocked on the same thing #25 item 2 is blocked on**: the measurements ran against
a flaky HPC vLLM allocation whose tunnels flapped, and nobody has costed a stable deployment. **If
that endpoint is gone, decision 1 cannot be re-measured at all** — which is itself worth knowing
before the model choice, because it means the choice would be made on era-B numbers permanently.

**Recommended order:** latency first (cheap, unblocks the SLA question, fixes all three entries),
then decide whether the quality re-run is worth re-provisioning the endpoint for.
