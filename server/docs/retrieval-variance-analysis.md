# Retrieval Variance Analysis And Guardrails

## Summary

The retrieval variance issue was traced to merge dynamics, not missing content:

1. The golden Digdir chunk (`6a80d6499075`) was consistently strong in `content` retrieval.
2. `metadata` retrieval often did not return the golden chunk for årsverk queries.
3. Phrase search produced large duplicate volumes across query variants, which inflated merged ranking signals.
4. Merge ordering over-valued duplicate-heavy signals relative to direct content evidence.

## Implemented Changes

### 1) Content-first merge ranking

`rag/merge-chunk-search-results` now uses strategy weights that prioritize content:

- `:content` = `1.0`
- `:phrase` = `0.35`
- `:metadata` = `0.2`

### 2) Per-strategy dedupe before merge

Retrieval now deduplicates each strategy list by `:chunk_id` before merge, keeping the strongest hit for each chunk within that strategy.

This is applied in:

- `server/src/digdir/skills/builtin/retrieval.clj`
- `server/src-dev/digdir/tools/diagnostics.clj`

### 3) Per-strategy contribution cap per chunk

Merge now caps strategy contribution per chunk (`default = 1` per strategy), preventing repeated phrase hits from dominating merged rank.

## Regression Safety Nets

### Unit tests

- `server/test/digdir/rag/core_merge_test.clj`
  - verifies content-primary behavior
  - verifies capped duplicate contribution
  - verifies configurable caps

### Fixture-based performance tracking

- Fixture:
  - `server/test/fixtures/retrieval/merge_strategy_regression.edn`
- Test harness:
  - `server/test/digdir/rag/retrieval_merge_fixture_test.clj`

Each fixture case defines:

- user query
- golden chunk id
- synthetic phrase/metadata/content hits
- max acceptable merged rank for the golden chunk

This provides deterministic checks across multiple query/golden pairs without live retrieval variance.

## Running The New Retrieval Regression Tests

From `server/`:

```bash
clojure -M:test -e "(require 'digdir.rag.core-merge-test 'digdir.rag.retrieval-merge-fixture-test) (clojure.test/run-tests 'digdir.rag.core-merge-test 'digdir.rag.retrieval-merge-fixture-test)"
```
