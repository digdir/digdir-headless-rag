# LLM-driven query expansion validation

Closes the loop opened by the hand-crafted upper-bound measurement
in `16-query-expansion-handcrafted-upper-bound.md`. The question:
can an LLM produce expansion phrases of comparable quality to the
hand-crafted ones, automatically, per query?

## What shipped

### 1. `/api/debug/query-planner` endpoint

New GET endpoint that runs the existing `query-planner` skill
against a single user query and returns N expanded phrases.

```
GET /api/debug/query-planner?tenant=digdir&query=<...>&max-phrases=5
→ {:queries [...] :phrase-count N :fallback? bool :model-used "..."}
```

Schema entry in `routes/endpoints.clj`, handler in
`routes/endpoints/debug.clj`. Parallel to the slice-1
`typesense-retrieve` plumbing pattern.

### 2. Bug fix in `query-planner` skill

The skill at `server/src/digdir/skills/builtin/query_planner.clj`
was using snake_case `:tool_choice` and `:tool_calls` while
`query-relaxation` (known-working) uses kebab `:tool-choice` and
`:tool-calls`. litellm-clj's translation appears to expect kebab.
Under the snake-case form, every LLM response silently failed the
tool-call parse and `execute-query-planner` fell back to returning
the original query as the only "expanded" phrase.

This means **query-planner has likely been silently broken in
production** (agent loop) — it has been returning the original
question as a single-phrase "expansion" rather than generating
diverse alternatives.

Fix: change `:tool_choice` → `:tool-choice` and `:tool_calls`
→ `:tool-calls`. Verified by direct curl probe of the new
debug endpoint: now returns 5 distinct phrases.

### 3. `bb v3-score --expand-queries N`

Calls `/api/debug/query-planner` per question, gets N phrases,
passes them comma-separated as the `:queries` param to
typesense-retrieve. ColBERT remains opt-in via
`--rerank-with-colbert true`.

## Results

| Config | Top-10 | Top-30 |
|---|---:|---:|
| V0 + ColBERT (no expansion) | 6/23 (26.1%) | 6/23 (26.1%) |
| **LLM expansion N=5 + ColBERT** (good run) | **13/23 (56.5%)** | **16/23 (69.6%)** |
| LLM expansion N=5 + ColBERT (run 2) | 7/23 (30.4%) | 12/23 (52.2%) |
| Hand-crafted upper bound + ColBERT | 16/23 (69.6%) | 17/23 (73.9%) |

LLM expansion delivers significantly more recall than V0+ColBERT
alone. Two runs showed variance — both were +30pp at top-10 vs the
no-expansion baseline, but the spread between runs was wide
(30.4% to 56.5%).

### Per-question detail (LLM N=5, second run)

| Q | V0+ColBERT | LLM N=5 (top-10) | LLM N=5 (top-30) | Hand-crafted (top-10) |
|---|:-:|:-:|:-:|:-:|
| Q1 | 2/3 | **0/3** | 0/3 | 2/3 |
| Q2 | 0/2 | 1/2 | 1/2 | 2/2 |
| Q3 | 0/2 | 0/2 | 0/2 | 1/2 |
| Q4 | 3/3 | 2/3 | 3/3 | 3/3 |
| Q5 | 0/5 | 3/5 | 5/5 | 5/5 |
| Q6 | 0/3 | 1/3 | 1/3 | 1/3 |
| Q7 | 1/5 | 0/5 | 2/5 | 2/5 |

Highlights:
- **Q5 lands consistently strong** — both LLM runs got 5/5 at
  top-30, matching hand-crafted. The LLM correctly produced
  expansions like "Altinn-roller personer virksomheter" that
  reach the role-description chunks.
- **Q1 regression in this run**: from 2/3 to 0/3. The LLM
  generated expansions like "what is Dialogporten and what
  problem does it solve" — generic reformulations that
  *didn't* match the linktitle of About-Dialogporten as well as
  the literal "Dialogporten" search (V0) did. This is a real
  downside.
- **Q7 partial**: surfaced `9b4017645a43` (the webhook-secret
  chunk) at #30 in run 2, but missed it entirely at top-10. The
  hand-crafted "EventSecretCodeProvider" phrase reliably puts
  it at #1; LLM-generated phrases ("webhook signature HMAC",
  "event subscription secret") don't trigger the same.
- **Q3 still 0/2**: the LLM's reformulations didn't surface
  either the auth-config doc or the local-dev doc.

## What this proves

**LLM query expansion is a major lever** but has three real
costs against the hand-crafted upper bound:

1. **Variance.** Run-to-run differences are substantial (top-30:
   52.2% vs 69.6% across two N=5 runs at temperature 0.1).
   Aggregate across the v3 set, both runs beat no-expansion by
   +20 to +30pp, but individual questions can flip pass/fail.
2. **Regression risk on already-passing questions.** Q1 went
   from 2/3 to 0/3 in one LLM run because the LLM's
   reformulations diluted the literal-term match the boost step
   was using to surface About-Dialogporten. **Expansion can
   *remove* recall on questions where the literal query was the
   best probe.**
3. **Corpus-vocabulary blindness.** The LLM doesn't know the
   corpus's specific linktitle vocabulary (`Personroller`,
   `Virksomhetsroller`, `EventSecretCodeProvider`). It generates
   reasonable reformulations of the user's question, but they
   don't always match the compound-word, domain-specific terms
   the corpus indexes.

The hand-crafted upper bound was authored knowing these terms.
An LLM that's given the corpus's title vocabulary (e.g., as a
list of high-precision keywords pulled from `linktitle` /
`frontmatter_title` indexes) might close the rest of the gap.

## The cumulative v3 picture

| Stage | Top-10 | Top-30 |
|---|---:|---:|
| Initial v3 baseline (no doc-title, no ColBERT) | (~13%) | 22% |
| Slice 1 (`:doc-title` strategy) | (~13%) | 26.1% |
| Slice 4 (ColBERT post-merge rerank) | 26.1% | 26.1% |
| **Slice + LLM expansion (N=5 + ColBERT)** | **30–56%** (variance) | **52–70%** (variance) |
| Hand-crafted ceiling | 69.6% | 73.9% |

LLM expansion is **the biggest single lever after ColBERT** and
the only one that has materially moved recall at top-30
(slice 1's doc-title was the only other one, and that was +4pp).

Combined effect from V0 to best LLM-expansion run:
- **Top-10**: V0 13% → 56.5% = **4.4× improvement**
- **Top-30**: V0 22% → 69.6% = **3.2× improvement**

## Decision points

1. **Enable query-planner by default for the production agent
   path on digdir/public-docs?** The runtime config key is
   `skills.query-planner.enabled`. Default it on for the
   dataset. The skill has been silently broken (snake/kebab bug)
   so production agents may have been running expansion-free
   despite the config; the bugfix lands with this change.

2. **Mitigate the regression risk?** Three options:
   - **Always include the original query** in the expansion
     list (alongside LLM-generated phrases). Guarantees the
     literal-query candidate set is never lost. Cheapest.
   - **Higher temperature + 3 runs, union the phrases**. More
     LLM cost but reduces single-run variance.
   - **Seed the LLM prompt with corpus vocabulary**. E.g., for
     queries about specific domains, include a list of relevant
     `linktitle` values as "high-precision terms the corpus uses
     for this domain." Would need corpus-side seeding.

3. **Validate end-to-end through the agent loop?** Slice work
   has measured the retrieval skill via the debug endpoint.
   The agent loop's read-tool and synthesis stages may behave
   differently with N expanded queries — worth a separate
   measurement against the production-shaped flow.

My recommendation: **(1) + (2a)**. Enable query-planner default,
and modify the expansion to always include the original query.
Both are small, low-risk additions; together they should give
us the LLM-expansion uplift without the Q1-style regression.

## Footnote on the bug fix

The query-planner snake/kebab bug means the agent loop's
behavior in production may **change materially** when this fix
ships. Two scenarios worth considering before deploying:

- **If the agent's downstream stages (reranking, synthesis,
  read-tool) were tuned against the broken behavior** (one
  query, no expansion), the fix could regress those stages
  even as it improves retrieval recall.
- **If they were tuned assuming expansion was working**, the fix
  is a strict improvement.

Worth a separate agent-loop smoke test against a representative
question set before rolling out widely.
