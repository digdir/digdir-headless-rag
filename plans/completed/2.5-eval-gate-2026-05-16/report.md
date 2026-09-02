# Phase 2.5 eval gate — 2026-05-16

Three-way comparison of the agent ReAct loop across:

- **`:imperative`** — the legacy `loop/agentic-loop` (2.4c.3 threaded-workspace
  rewrite). Default. The post-2.4c baseline.
- **`:bundled`** — the inner sub-graph that lands the agent body as 4
  coarse-grained skills (`:llm-and-tools`, `:evaluate-evidence`,
  `:sufficiency-gates`, `:record-and-route`). 2.5.C.
- **`:faithful`** — the inner sub-graph that lands the body as 10
  fine-grained skills plus a `:select` route step with 6 branches. 2.5.D.

Same fixtures as the 2.4c eval gate (`public_docs_agent_smoke.edn`, 5 cases,
current + relaxed budgets), same imported May 11 DB, same Typesense corpus.
`--graph-variant <name>` selects the variant.

Raw payloads alongside this report:
- [`agent-budget-imperative.edn`](./agent-budget-imperative.edn)
- [`agent-budget-bundled.edn`](./agent-budget-bundled.edn)
- [`agent-budget-faithful.edn`](./agent-budget-faithful.edn)

## Headline numbers

| Metric                       | imperative | bundled | faithful |
|------------------------------|-----------:|--------:|---------:|
| Cases                        | 5          | 5       | 5        |
| Errors / exceptions          | 0          | 0       | 0        |
| current-pass                 | 1          | 1       | 3        |
| relaxed-pass                 | 1          | 2       | 1        |
| answer-pass (across budgets) | 7 / 10     | 9 / 10  | 10 / 10  |
| golden-present (across)      | 2 / 10     | 3 / 10  | 5 / 10   |
| stable-fail                  | 4          | 2       | 2        |
| improved                     | 0          | 2       | 0        |
| regressed (cur→rel)          | 0          | 1       | 2        |

Both graph variants run end-to-end with **zero infrastructural errors**.
Neither variant introduced behavioral breakage versus the imperative
baseline; both show *at least as good* answer-pass and golden-present
rates on this single-run snapshot.

## Per-case answer-pass × golden-present

Format: `current-budget | relaxed-budget`, each as `answer-pass / golden-present`.

| Case                              | imperative          | bundled              | faithful            |
|-----------------------------------|---------------------|----------------------|---------------------|
| altinn-broker-getting-started     | ✓/✗ \| ✓/✗          | ✓/✗ \| ✓/**✓**       | ✓/✗ \| ✓/✗          |
| altinn-broker-technical-overview  | ✗/✗ \| ✗/✗          | **✓**/✗ \| **✓**/**✓** | **✓**/**✓** \| **✓**/✗ |
| altinn-dialogporten-about         | ✓/✓ \| ✓/✓          | ✓/✓ \| ✓/✗           | ✓/✓ \| ✓/✓          |
| altinn-studio-create-user         | ✗/✗ \| ✓/✗          | ✗/✗ \| ✓/✗           | **✓**/**✓** \| ✓/✗  |
| altinn-studio-datamodeling        | ✓/✗ \| ✓/✗          | ✓/✗ \| ✓/✗           | ✓/✗ \| ✓/✗          |

Bolded cells: **graph variant outperformed imperative on this trial**.

The `altinn-broker-technical-overview` case, which failed answer-pass on
both budgets in both today's imperative run *and* the 2.4c eval-gate run
(`plans/completed/2.4c-eval-gate-2026-05-16/report.md`), passed in both
graph variants. Bundled hit the golden chunk under relaxed budget;
faithful hit it under current.

## Notable runner / schema gaps surfaced

Three real bugs and one design gap fell out while wiring the variants up,
each fixed as a precondition for the eval gate:

1. **Skill registrations split across `init.clj` and `api.clj`.** The eval
   path runs through `api/run-skill-graph` which has its own
   `register-builtin-skills!` hardcoded list. Adding the new
   `agent-graphs`, `iteration-bundled`, and `iteration-faithful` skills
   to `init.clj`'s registry was not enough — they had to land in
   `api.clj`'s list too, or the bundled/faithful outer graphs reported
   `:sub-graph/graph-not-found` at execution time.

2. **`test-skill-graph-skills-exist` assumed every step has `:skill`.**
   The test mapped `:skill` over every step in every registered graph,
   so the new outer graphs (which use `:loop` + `:sub-graph`) and the
   faithful inner sub-graph (which uses `:select`) tripped it. Fixed by
   teaching the test to walk `:loop`/`:foreach`/`:select`/`:sub-graph`
   step shapes and only check inner skills it can reach.

3. **Schema validator rejected `:$xxx` refs from a loop step's inner
   sub-graph when `xxx` matched a loop-step input key.** The
   `iteration-scope-ref?` predicate only filtered `:$iter` / `:$i`,
   not refs that resolve against the loop step's *own* `:inputs` map.
   Fixed by extending the predicate to treat each
   `:$<loop-input-key>` as scope-local (analogous to how the runtime
   `iter-inputs` merges loop inputs with iter-scope).

4. **The runner's `execute-loop-step` ignored the loop step's own
   `:inputs` map.** Only outer `inputs` + `iter-scope` reached the
   inner step's reference resolution, so the bundled/faithful designs'
   `:react :inputs {:ambient-ctx [:setup :ambient-ctx] ...}` was a
   no-op at runtime. The inner sub-graph saw `:$ambient-ctx` resolve to
   nil, and `cfg/get` rejected the resulting nil tenant. **And** the
   schema's `extract-input-refs` for loop steps only extracted refs
   from `:do/:inputs`, so the topological sort didn't see the
   `[:setup :ambient-ctx]` dependency and ran `:react` *before*
   `:setup`, leaving `step-outputs` empty when the loop tried to
   resolve. Fixed by (a) resolving the loop step's `:inputs` in
   `execute-loop-step` and merging into `iter-inputs`, and
   (b) including outer-`:inputs` refs in `extract-input-refs` for
   loop steps so the topological sort sees the right dependencies.

## Operational notes

- All three variants run from the same imported May 11 DB
  (`local-db/add_evals_20260516_evalgate`), same Typesense backend, same
  fixtures. Differences are real behavioral differences plus LLM
  nondeterminism between runs.
- Single-run results have high variance for evals at this size (5
  cases × 2 budgets). The 2.4c eval gate's imperative run scored
  8/10 answer-pass; today's imperative run scored 7/10. The graph
  variants' apparent improvement should be **reproduced with at
  least 3 runs per variant** before being treated as a real win.
- All three variants share the same outer agent-rag pipeline
  (citation backfill, trace file, post-loop output assembly) via the
  `run-graph-variant` reflector + `reset! !workspace
  workspace-final` in `execute-agent`. No variant-specific output
  surface.

## Multi-run stability check (n=3 per variant)

After the single-run snapshot above, a 9-run batch (`stability/`)
re-evaluated each variant three more times on the same fixtures and
DB. Raw EDN payloads per run: `stability/{imperative,bundled,faithful}-run{1,2,3}.edn`.
Total wall time 57 min, all 9 runs completed with zero infra errors.

Per-run current/relaxed-pass:

| Run        | imperative | bundled | faithful |
|------------|-----------:|--------:|---------:|
| run1       | 3 / 1      | 1 / 2   | 3 / 1    |
| run2       | 2 / 1      | 2 / 4   | 2 / 1    |
| run3       | 3 / 2      | 3 / 2   | 2 / 2    |

Aggregate (mean (min–max)):

| Metric          | imperative   | bundled         | faithful     |
|-----------------|--------------|-----------------|--------------|
| current-pass    | 2.67 (2–3)   | 2.00 (1–3)      | 2.33 (2–3)   |
| relaxed-pass    | 1.33 (1–2)   | **2.67 (2–4)**  | 1.33 (1–2)   |
| regressed       | 1.33 (1–2)   | **0.67 (0–2)**  | 1.33 (1–2)   |
| errors          | 0            | 0               | 0            |
| total pass      | 4.00         | **4.67**        | 3.67         |

Findings:

1. **Zero infrastructural errors across 9 runs and 3 variants.**
   The graph composition is stable enough to ship in either form.
2. **Bundled is the strongest performer on this fixture.** Mean
   relaxed-pass is 2× the other two variants (2.67 vs 1.33), and mean
   regressed count is half (0.67 vs 1.33). Total pass (current +
   relaxed) is higher than imperative by ~17% and higher than faithful
   by ~27%.
3. **Faithful tracks imperative closely.** Within-run differences are
   inside the variance band; the 10-step decomposition didn't change
   answer quality on this fixture.
4. **Variance is real but moderate.** Each variant's current-pass
   spans 1–2 points across the 3 runs; relaxed-pass spans 1–2 except
   bundled which spans 2–4. The bundled-run2 score of 4 relaxed-pass
   is the single best run in the batch.
5. **Single-snapshot numbers in the section above are within
   stability variance.** The earlier "faithful 5/5 answer-pass"
   observation is no longer the typical case.

## Recommendation for 2.5.F

Based on the multi-run data:

- **Make `:bundled` the new default for `:graph-variant`.** It posted
  the highest mean total pass, the fewest regressions, and the
  smallest skill surface (4 inner + 3 outer + 1 picker = 8 skills,
  vs faithful's 16 + 3 + 1 = 20). Eight skills' worth of code is the
  smallest move-the-needle change we can make and still claim the
  graph cutover.
- **Keep `:faithful` registered as an opt-in variant.** Its strength
  is observability (each phase A/B-replaceable independently), not
  answer quality on this fixture. If a future eval suite calls for
  per-phase swap experiments (e.g. cheaper sufficiency-gate LLM,
  different range-read-hint heuristics), faithful is the right
  surface.
- **Keep `:imperative` registered for one more cycle.** Single
  fixture × n=3 isn't enough confidence to drop the proven baseline.
  Next eval suite (when the corpus drift from the 2.4c gate is
  resolved) would be the natural moment to retire it.
- **Worth a follow-up:** the bundled-run2 outlier (4 relaxed-pass).
  Diff its trace against the lower-scoring bundled runs to see
  whether the LLM hit a particularly favorable phrasing or whether
  there's a reproducible policy improvement to absorb back into the
  default budgets.
