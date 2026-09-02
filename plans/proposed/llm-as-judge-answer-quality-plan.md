# LLM-as-judge for sweep answer quality

**Status:** proposed
**Motivating finding:** the 41-question un-enriched baseline scored **100%
`answer-substring-hit`** while golden-chunk recall@20 was only 0.79. Substring
matching against a regex (`:expected-answer-pattern`) is too loose to trust as a
quality signal — it confirms a key phrase *appears*, not that the answer is
correct, complete, or faithful. We want a proper LLM-as-judge, run by a model
*stronger* than the agent under test, before we accept any baseline as "good."

---

## Goals / non-goals

**Goals**
- A judge that scores each sweep run's answer for **correctness + completeness +
  faithfulness**, returning a structured verdict (not a substring boolean).
- The judge **model is configurable**, defaulting to `gpt-5.5-chat`, and is
  **independent of the agent model** (it can be a stronger model than the agent
  it is grading).
- **Shadow the old signal**: keep `answer-substring-hit?` alongside the judge
  verdict so we can measure where the two disagree (the same old-gate-as-shadow
  pattern we've used before). The disagreement set is the payload.
- **Score the existing 82-row baseline offline** (runs.csv already stores
  `:response`) — no re-sweep needed to answer "is the 100% real?"

**Non-goals (this plan)**
- Replacing retrieval metrics (recall@k, golden-in-display, the Filter-4
  decomposition) — those stay; the judge replaces only the *answer-quality*
  proxy.
- Using the judge inside the production agent loop or the self-improve gate.
  (Future: the same judge ns could feed the eval-sweep gate, but out of scope
  here.)

---

## Key design decisions

### 1. Judge model is a new platform config, default `gpt-5.5-chat`
New config definition `services.judge.model` (`:value-type :string`, root
`:platform`, ownership `:inherit`), declared in
`server/src/digdir/setup/config.clj` next to the existing
`services.azure-openai.*` definitions, default seeded to `gpt-5.5-chat`. Read
via `(cfg/get {:tenant tenant} :services :judge :model)`.

Add a companion `services.judge.enabled` (`:value-type :boolean`, default
`false`) so retrieval-only sweeps don't pay for judging unless asked. The sweep
also takes a per-run `:judge?` override param (param > config).

> The model id must be routable by the configured LLM proxy/endpoint. Because we
> call with an explicit `:model` string (litellm-style model-name routing, not
> an Azure deployment-name), `gpt-5.5-chat` just needs to exist in the proxy.
> If the proxy only does Azure deployment-name routing, the operator points
> `services.judge.model` at a deployment that serves the strongest model.

### 2. Judge calls the model-name path with an explicit model
`digdir.llm.openai/create-chat-completion [tenant messages]` is hardwired to the
agent deployment, so it can't run a *different* judge model. Instead the judge
ns calls the underlying `api/create-chat-completion` directly:

```clojure
(api/create-chat-completion
  {:model       judge-model            ; e.g. "gpt-5.5-chat"
   :messages    [{:role "system" :content rubric-system}
                 {:role "user"   :content judge-user}]
   :temperature 0
   :stream      false}
  {:api-key      (cfg/get {:tenant tenant} :services :azure-openai :api-key)
   :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)})
```

Reuses the existing endpoint/key config (one place to point at the proxy).
`temperature 0` for determinism.

### 3. Structured verdict
The judge returns JSON, parsed into:
- `:verdict` — one of `correct` / `partial` / `incorrect` (or `refusal` when a
  refusal was expected per `:grounding-mode :refusal-expected`).
- `:score` — 0.0–1.0.
- `:rationale` — one sentence (kept short; goes in CSV).
Robust parse: request JSON, extract the first `{...}` block, fall back to
`:verdict :unparseable` (never throws, never silently passes).

### 4. Ground truth the judge grades against — **DECIDED: authored reference answers**
The questions today carry only `:expected-answer-pattern` (a regex). **Chosen
approach: author a `:reference-answer` per question and grade
response-vs-reference** — the highest-fidelity option. The regex pattern stays,
but only as the *shadow* signal; it no longer defines correctness.

Each judged question gets a new **`:reference-answer`** string field: a concise,
correct, complete answer to `:query`. The judge is given `(query,
reference-answer, the agent's answer)` and asked whether the agent's answer is
correct + complete + faithful **relative to the reference**, allowing for
correct answers phrased differently or grounded in different (non-golden) chunks
— so the corpus-redundancy effect the baseline revealed does *not* unfairly
penalize a right answer.

**Authoring (bootstrap + human review), the P0 prerequisite:**
- Draft each reference with the judge model from `(query + golden-chunk
  content + the stored agent `:response` from runs.csv)` — we already have all
  three. The draft is a *candidate*, not ground truth.
- **Human-review every draft** before it counts as reference. Pay special
  attention to the 6 baseline rows with recall@20=0 yet 100% substring-hit:
  their golden chunk was *not* retrieved, so the reference must be authored from
  the question's true correct answer, not from the (missing) golden chunk.
- Store references alongside the questions (in `questions.edn`, or a sibling
  `references.edn` keyed by `:id` if we want to keep the question file lean).

Rejected alternatives (recorded for context): (A) deriving a rubric from the
regex — too weak; (C) grading directly against golden-chunk text — penalizes
correct answers from non-golden chunks, the exact redundancy we found.

### 5. Cost control
- Off by default; opt-in per sweep (`:judge?` param / `services.judge.enabled`).
- **Cache by `[judge-model question-id (hash response)]`** in a file under the
  sweep dir (`judge-cache.edn`) so identical responses aren't re-judged across
  repeats/re-runs. Offline re-scoring of an existing sweep reuses the cache.
- Single call per run at temp 0 to start. (Optional future: 3-vote majority for
  the disagreement set only.)

---

## Pieces to build

1. **Config** (`server/src/digdir/setup/config.clj`)
   `ensure-config-definition!` for `services.judge.model` (default
   `gpt-5.5-chat`) and `services.judge.enabled` (default `false`); seed defaults
   in the global-config setup path.

2. **Judge ns** (`server/src-dev/digdir/sweep/judge.clj`, new)
   - `build-messages [question response opts]` → system rubric + user payload
     (question, required facts/reference, the answer; refusal-aware).
   - `judge-answer [tenant question response opts]` → `{:verdict :score
     :rationale :judge-model}`; calls `api/create-chat-completion`, parses JSON,
     never throws.
   - File cache keyed by `[model qid response-hash]`.

3. **Question schema + references** (`server/src-dev/digdir/sweep/questions.clj`)
   Add a `:reference-answer` (string) field, validated when present. A judged
   question with no reference is **skipped + flagged** (`:verdict :no-reference`)
   rather than silently passing — judging never grades against nothing. Plus the
   authored references themselves (bootstrap-draft + human-review, per
   decision 4) for the question set under test.

4. **Runner integration** (`server/src-dev/digdir/sweep/runner.clj`)
   - Thread `tenant` (from `execution-scope`) and a `:judge?` flag into
     scoring. `score-run` currently takes `[question result]` and has no tenant;
     do the judge call in `run-single` after `score-run` (it has the scope), or
     extend `score-run`'s arglist.
   - New CSV columns (keep `answer-substring-hit?` for shadow):
     `:answer-judge-verdict`, `:answer-judge-score`, `:answer-judge-rationale`,
     `:answer-judge-model`. Append to `csv-columns`.
   - Skip judging (leave blank) when judging is off — backward compatible.

5. **Offline re-scorer** (`server/src-dev/digdir/sweep/judge.clj` entry +
   `bb` task, e.g. `bb sweep-judge <sweep-dir>`)
   Reads an existing `runs.csv`, judges each row's stored `:response`, writes
   `runs-judged.csv` (or augments in place) + a summary. **This scores the
   current 82-row baseline with no agent re-run.**

6. **Dashboard** (`server/src/digdir/sweep/dashboard.cljc`)
   - New summary card: **Judge** (mean score / % correct).
   - New table columns: judge verdict (color: correct=green, partial=amber,
     incorrect=red) next to the existing `ANS` (substring) column.
   - A small **agreement** stat: % of rows where substring-hit and judge-correct
     agree, surfacing the disagreement count the whole exercise is about.
   - Reads judge columns when present; degrades gracefully when absent.

---

## Phasing

- **P0 — Reference answers (prerequisite).** Schema field + bootstrap-draft the
  `:reference-answer` for the 41 baseline questions from `(query +
  golden-chunk content + stored agent response)`, then **human-review each**
  (especially the 6 recall@20=0 rows). Deliverable: a reviewed reference set.
- **P1 — Judge + offline re-score (the payoff).** Config + judge ns + offline
  re-scorer; run over the committed 82-row baseline using the P0 references.
  Deliverable: *"of the 100% substring-hits, X% judged correct / Y% partial /
  Z% incorrect,"* plus the disagreement list — validates or refutes the baseline
  with no re-sweep.
- **P2 — Live integration. ✅ DONE.** Judging wired into `run-single` behind the
  `:judge?` flag (run-matrix / run-from-files / matrix-file `:judge?`). New CSV
  columns: `answer-judge-verdict/score/rationale/model`. Response-hash cache
  (`!judge-cache`, keyed by `[model qid (hash response)]`) so identical answers
  judge once. `merge-references` joins references.edn onto questions by id.
  **Extended with task-difficulty:** the judge also emits an intrinsic
  difficulty (1-5, assessed from question+reference ONLY — told to ignore the
  candidate) + rationale → columns `task-difficulty` / `task-difficulty-rationale`.
  A complementary magnitude scale vs the existing categorical `:difficulty` tags
  (`:simple :compound :temporal :compare :hard :negative`); use it to slice the
  leaderboard and differentiate the question library. Surfaced in the dashboard
  as a DifficultyBadge next to the verdict.
- **P3 — Dashboard surfacing.** Judge card + column + substring↔judge agreement
  stat. (Reference-review card already shows verdict + difficulty; the Results
  table still needs a judge/difficulty column + agreement stat.)

## Validation / done criteria
- P0 produces a judged report over the existing baseline; we manually spot-check
  5–10 verdicts (especially the 6 recall@20=0 / 100%-substring rows) to confirm
  the judge is sane before trusting aggregates.
- Substring↔judge agreement is reported; disagreements are inspectable
  (rationale + response in the row).
- Judge is deterministic enough that re-running P0 over the same cache is a
  no-op; clearing the cache and re-judging yields stable verdicts (temp 0).
- Nothing regresses when judging is off (default): existing sweeps run
  unchanged, blank judge columns.

## Risks
- **Judge hallucination / leniency** — a strong model can still be wrong or too
  generous, especially under option (A) with no reference. Mitigate: temp 0,
  explicit rubric, human spot-check of P0, prefer authored references (B) over
  time, optionally 3-vote the disagreement set.
- **Model availability** — `gpt-5.5-chat` must be routable by the configured
  proxy/endpoint; config makes this swappable.
- **Cost** — bounded by opt-in + response-hash cache; offline P0 over 82 rows is
  ≤82 calls once.
