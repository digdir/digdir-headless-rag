# RRF rank fusion + per-(doc, strategy) cap

## Why

Slice-3 V2 measurement (see
`plans/in-progress/target-optimal-baseline-v3/11-llm-classify-validation.md`)
found that filtering monotonically *regressed* top-30 hit rate
against the v3 baseline — even with a perfect LLM classifier
making the correct multi-value decisions:

| Config | Hits | Rate |
|---|---:|---:|
| V0 (no filter) | 6/23 | 26.1% |
| V1 (static lang) | 5/23 | 21.7% |
| V2 (LLM diataxis) | 4/23 | 17.4% |
| V2 (LLM both fields) | 3/23 | 13.0% |

Diagnosis (see end-of-session investigation in slice 3):
- The filter is correct — `diataxis:!=[complement]` does include
  the 32% null-diataxis docs; the cited chunks pass the filter.
- The regression is **ranking, not filtering**. When the candidate
  pool narrows, the strategy-mix composition of top-K changes:
  `:doc-title`'s K=3 chunk fanout per matched doc saturates the
  merged top-30 with one strategy's contributions, crowding out
  the multi-strategy-confirmed chunks that previously surfaced.

In IR vocabulary this is **system contribution imbalance** under
score-weighted-sum fusion (Aslam & Montague, *Models for
metasearch*, SIGIR 2001). The literature has well-validated fixes.
This plan implements two of them.

## Two changes

### Change A — Reciprocal Rank Fusion (RRF) merge mode

Add a `:merge-mode :rrf` option to `merge-chunk-search-results`.
Default stays `:weighted-sum` (current behavior); RRF is opt-in
via config / skill-param.

RRF formula (Cormack, Clarke, Buettcher, *Reciprocal Rank Fusion
outperforms Condorcet and individual rank learning methods*,
SIGIR 2009):

```
RRF_score(d) = Σ_strategies  w_s · 1 / (k + rank_s(d))
```

Where:
- `rank_s(d)` is the 1-based rank of chunk d within strategy s's
  output (∞ if d not in s's list — contributes 0).
- `k` is a smoothing constant. Cormack et al. used k=60;
  Elasticsearch defaults to 60; some implementations use 10–40
  for shorter lists.
- `w_s` is the per-strategy weight (preserves the existing
  `:strategy-weights` tuning). Standard RRF is unweighted (all
  weights = 1); we keep the weights so caller-supplied biases
  (e.g. `:content 1.0 :doc-title 0.8`) still apply, just as a
  multiplier on the RRF term instead of on a normalized score.

**Why this addresses the regression**: RRF uses rank position
(bounded by pool size in a comparable way across strategies),
not raw score (which re-normalizes within each strategy's pool).
A strategy returning many items from a small pool can't dominate
by virtue of its score distribution; each item gets a bounded
contribution `≤ w_s / (k+1)`.

**Implementation surface** in `digdir.rag.merge`:

```clojure
(defn- summarize-chunk-hits-rrf
  [hits strategy-weights rrf-k]
  (let [hits-by-type (group-by (comp normalize-search-type :search-type) hits)
        ;; For each strategy that hit this chunk, take the best (lowest) :index
        ;; and convert to a 1-based rank for the RRF denominator.
        per-type-rrf (into {}
                           (map (fn [[search-type ts-hits]]
                                  (let [best-index (apply min (map #(long (or (:index %) 0)) ts-hits))
                                        rank-1based (inc best-index)
                                        w (double (get strategy-weights search-type 0.0))]
                                    [search-type (* w (/ 1.0 (+ rrf-k rank-1based)))])))
                           hits-by-type)
        rrf-score (reduce + 0.0 (vals per-type-rrf))
        search-types (set (keys hits-by-type))
        ;; ... (keep matched-questions, hit-count, etc. unchanged)
        ]
    {:rank rrf-score
     :search-types search-types
     ...}))

(defn merge-chunk-search-results [& args]
  (let [[opts results] (parse-merge-args args)
        merge-mode (or (:merge-mode opts) :weighted-sum)
        ...]
    (case merge-mode
      :rrf (merge-rrf opts results)
      :weighted-sum (merge-weighted-sum opts results))))
```

Default `:rrf-k 60` matching Cormack et al. Configurable via
`:merge-opts {:rrf-k <int>}`.

### Change B — per-(doc, strategy) contribution cap

The existing `:strategy-contribution-caps` caps per-chunk (one
strategy can only contribute once per chunk, default cap 1).
That solves duplicate-counting but doesn't address fanout.

Add `:per-doc-strategy-cap` — limit how many chunks a single
strategy can contribute *for one parent doc* before merge.
Default `nil` (no cap, preserve current behavior).

When set to 1 and `:doc-title` returns chunks 0/1/2 of one
matched doc, only the best-ranked chunk survives into merge —
freeing top-K slots for cross-strategy-confirmed chunks.

**Implementation surface**: doc_num needs to be on each hit map.
Currently strategies emit `{:chunk_id :rank :index :search-type}`
without doc_num. Easy fix:

1. Add `:doc_num` to `include_fields` in each strategy's
   Typesense query.
2. Add `:doc_num` to the emitted hit map.
3. In the engine's pre-merge step (already calls `dedupe-hits`),
   add a `cap-per-doc-strategy` pass that keeps the top-N hits
   per (strategy, doc_num) pair.

`doc-title` strategy already knows doc_num (the matched doc).
Content/phrase/metadata strategies just need `:include_fields
"chunk_id,doc_num,..."` extended.

## Sweep variants this enables

Extend the V0–V4 matrix from
`plans/proposed/retrieval-dynamic-filter-generation-experiment.md`:

| Level | Mechanism | Notes |
|---|---|---|
| V5 | V0 + RRF | Same broad pool as V0, but RRF fusion. Tests whether the merge math alone moves the needle without changing strategies. |
| V6 | V0 + RRF + per-doc-strategy cap 1 | Add the fanout cap. Should not regress V5; may help when `:doc-title` would otherwise emit K=3 per doc. |
| V7 | V2 + RRF + per-doc-strategy cap 1 | The decisive test. If V7 ≥ V0, **filtering was a phantom problem** caused by the merge math; the rule-engine investment retroactively becomes valuable. If V7 < V0, filtering is genuinely subtractive on this corpus. |

Optional:
- **V8**: V2 + RRF only (no per-doc cap) — isolates whether RRF alone fixes the regression or whether the cap is also needed.
- **V9**: V0 + RRF + per-doc cap 1 + `:doc-title-chunk-fanout 1` — strategy emits one chunk per doc instead of three. Cleaner separation but may miss chunks 1/2 that are answer-bearing.

## Implementation order

1. **RRF in `merge.cljc`** — new defmethod / case split. Default
   `:weighted-sum` for backwards compat. Unit tests for the math
   (rank-1 chunk in two strategies > rank-1 chunk in one
   strategy > rank-10 chunk in one strategy).
2. **doc_num threading** — extend strategy hit shape; update
   `:include_fields` per strategy; add tests confirming doc_num
   round-trips.
3. **per-(doc, strategy) cap** — small dedup pass in engine
   before merge. Unit test: cap=1 keeps best chunk per (strategy,
   doc); cap=nil preserves current behavior.
4. **Config + skill plumbing** — add `skills.retrieval.merge-mode`
   (`:weighted-sum` | `:rrf`), `skills.retrieval.rrf-k` (number),
   `skills.retrieval.per-doc-strategy-cap` (number | nil). Wire
   through `build-rag-skill-params`, retrieval skill parameters,
   debug endpoint, bb v3-score, bb ts-retrieve.
5. **Sweep variants** — write the matrix, run, score.
6. **Report** — `plans/in-progress/target-optimal-baseline-v3/12-rrf-validation.md`.

## Validation gate

V5 (RRF on V0) must reproduce **≥ V0's 26.1% within ±2pp**. If
V5 << V0, the RRF math is broken or the k constant is
miscalibrated, and we stop and investigate before running V6/V7.

## Risks

1. **k constant sensitivity.** Cormack et al's k=60 is for TREC-scale
   ranked lists (1000s). Our per-strategy lists are 20 items; the
   merged top-K is 30. k=60 may smooth too aggressively.
   Mitigation: run V5 with k ∈ {10, 30, 60} as a sub-sweep before
   committing.

2. **Lost score nuance.** Weighted-sum captures *how strong* a
   match is (a 0.9 BM25 vs 0.3 BM25 in the same strategy). RRF
   only sees rank position — a #1 hit with score 0.05 fuses the
   same as a #1 with score 0.95. For sparse retrieval where most
   strategies return weak matches in a narrowed pool, RRF may
   over-credit weak hits. Sweep will reveal this.

3. **Per-doc cap interaction with multi-doc answers.** If the
   right answer spans 5+ chunks of one doc, cap=1 hurts. Q4's
   answer needs ≥2 chunks of the migration doc; cap=2 may be
   safer than cap=1. Sweep cap ∈ {1, 2, 3}.

4. **Backwards compatibility.** Existing tenants tuned
   `:strategy-weights` against the weighted-sum math. Switching
   default to RRF would silently change their behavior. **Default
   stays `:weighted-sum`; RRF is opt-in.** Tenant migration is a
   separate question.

5. **Pre-merge cap drops legitimate hits.** Currently each
   strategy returns up to per-query-limit (20) hits. The 21st
   hit is already dropped. Per-(doc, strategy) cap at 1 would drop
   chunks 1/2 of doc-title fanouts EVEN IF they were the answer-
   bearing chunks. Mitigation: the cap operates on rank within
   strategy, so chunks 0 of doc-title (the frontmatter+intro) get
   priority — which is where the linktitle/frontmatter_title match
   lives. Chunks 1/2 lose their merge contribution but are still
   findable via content/phrase strategies if they're
   answer-bearing. Net effect on hit-rate is the empirical
   question the sweep answers.

## Where each change actually lives in code

| File | Change |
|---|---|
| `server/src/digdir/rag/merge.cljc` | Add `merge-rrf` defn; case-split in `merge-chunk-search-results` on `:merge-mode`; add `:rrf-k` default 60. |
| `server/src/digdir/rag/retrieval.clj` | Add `:doc_num` to `:include_fields` in `search-chunks-by-metadata`, `search-chunks-by-content`, `lookup-search-phrases-similar`, and enrichment lookups. Emit `:doc_num` in each hit map. |
| `server/src/digdir/rag/retrieval.clj` | `search-docs-by-title` already knows doc_num — add to emitted hits. |
| `server/src/digdir/skills/builtin/retrieval.clj` | Add `cap-per-doc-strategy` helper. Apply between `dedupe-hits` and merge. Read `:per-doc-strategy-cap` from opts. Surface `:merge-mode` and `:rrf-k` to merge-opts. |
| `server/src/digdir/api/util.clj` | Read 3 new config keys in `build-rag-skill-params`. |
| `server/src/digdir/config/db.clj` | 3 new skill-property aliases. |
| `server/src/digdir/setup/config.clj` | 3 new config definitions. |
| `server/src/digdir/api/routes/endpoints.clj` | 3 new malli entries in `debug-typesense-retrieve-query-parameters`. |
| `server/src/digdir/api/routes/endpoints/debug.clj` | Parse 3 new query params into the parameters map. |
| `bb.edn` | 3 new CLI flags on `ts-retrieve-params` (used by both `bb ts-retrieve` and `bb v3-score`). |

Plus unit tests in `core_merge_test.clj` (RRF math, cap behavior).

## What this proves and what it doesn't

**Proves (if V5 ≈ V0 and V7 ≥ V0)**: filter narrowing's
regression was a fusion-math artifact, not a property of
filtering itself. Filtering is rescued by RRF + per-doc cap;
the rule engine + LLM classifier work as designed. The slice-3
investment retroactively pays off.

**Proves (if V5 << V0)**: RRF is wrong for this corpus shape. We
keep weighted-sum and look for the saturation fix elsewhere
(strategy-weight tuning, dynamic K, etc).

**Proves (if V7 < V0 despite V5 ≈ V0)**: filter narrowing is
genuinely subtractive on this baseline regardless of fusion math.
The slice-2/3 "additive moves help, subtractive moves don't"
finding stands and the rule-engine work doesn't pay off retroactively.

**Doesn't prove**:
- Generalization beyond the v3 7-question set.
- That RRF is the *best* fusion (RRF is the strongest off-the-
  shelf default; learned fusion could outperform).
- That per-doc cap = 1 is the right cap.

The sweep is the gate; production rollout (default `:merge-mode
:rrf`) is a separate decision after seeing the data.

## Out of scope

- MMR / xQuAD diversification. The per-doc cap is a simpler
  fanout-control mechanism that addresses the specific saturation
  we're seeing. MMR is a follow-up if same-document diversity
  isn't sufficient.
- Hierarchical retrieval (doc-then-chunk). A larger architectural
  change worth its own experiment.
- Learned-fusion (Weighted-Reciprocal-Rank-Fusion with trained
  weights). Would need a labeled training set.
- Changing the default merge mode (silent backwards-incompatible
  change). Default stays `:weighted-sum`; per-tenant config opts
  in to RRF after sweep confirms.

## Open questions

1. **`k` constant.** Sweep k ∈ {10, 30, 60} sub-axis with V5. Pick
   the value that maximizes V5 hit rate; carry forward to V6/V7.
2. **Cap value.** Sweep cap ∈ {1, 2, 3, nil} sub-axis with V6.
   Same approach.
3. **Should `:strategy-weights` change under RRF?** The weights
   are tuned against weighted-sum's score distribution. Under RRF
   the natural defaults might be different (e.g. equal weights as
   in standard RRF). Worth a sub-experiment: V5 with `weights =
   {1.0 1.0 1.0 1.0}` vs current weights.
4. **Should doc-title's chunk-fanout K interact?** Logically: with
   per-(doc, strategy) cap, K=3 fanout becomes K=cap. Setting K=1
   in the strategy directly is a cheaper way to express the same
   intent. Are they equivalent? Worth a comparison run.
