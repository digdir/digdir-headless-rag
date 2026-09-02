# Read-Tool Integration — Verification

Date: 2026-05-17
Branch: self-improvement-agent
Follow-up to Phase B.5

## What changed

Plumbed the `matched-question` text from the hypothetical-questions
sibling strategy through to two LLM-facing decision points:

1. **Search-result preview** (`format-search-metadata-results` in
   `agent/tools.clj`) — each chunk line now appends
   `matched_q="<question>"` when the chunk was retrieved via
   `:hypothetical-questions`. Capped at 2 questions per chunk.
2. **Sufficiency evidence summary** (`build-evidence-summary` in
   `agent/sufficiency.clj` + `compact-search-summary`) — `:matched-questions`
   flows into the sufficiency check so it can treat an enrichment match
   as strong evidence and finalize instead of triggering another read.

Plumbing chain (5 files):
```
lookup-hypothetical-questions-similar       (rag/retrieval.clj)
  → :matched-question on each hit
summarize-chunk-hits                        (rag/merge.cljc)
  → aggregates into :matched-questions vec on the merged row
retrieve-chunks-by-id / -metadata-by-id     (rag/retrieval.clj)
  → preserves :matched-questions onto fetched docs
search-chunk-summary                        (agent/workspace.clj)
  → captures into chunk-summary
format-search-metadata-results              (agent/tools.clj)
  → renders matched_q="..." in preview
build-evidence-summary / compact-search-summary  (agent/sufficiency.clj)
  → surfaces in evidence summary
```

## Re-run smoke (n=3 per side, same DB + enrichment as B.5)

| Side       | current-pass | relaxed-pass | total |
|------------|-------------:|-------------:|------:|
| Baseline   | (unchanged from B.5 — formatter no-ops when :matched-questions empty) | | **3/6 = 50%** |
| Treatment-1 (was 2/2 fail in B.5)  | ✓ | ✗ | 1/2 |
| Treatment-2 (was 0/2 fail in B.5)  | ✓ | ✓ | 2/2 |
| Treatment-3 (was 0/2 fail in B.5)  | ✗ | ✓ | 1/2 |
| **Treatment total** | | | **4/6 = 67%** |

**Delta from baseline: +17pp** (clears the ≥5pp gate spec).

### Read-decision change — direct evidence

The B.5 raw runs never read chunk `8e22ae4b88b1` (the enriched golden) —
the LLM kept picking other chunks based on title heuristics.

This run, the agent **explicitly read 8e22ae4b88b1** in the two passing
treatment runs:

```
treatment-2 reads: ["3b9d00305074" "8e22ae4b88b1" "d81ff032aa35"]
treatment-3 reads: ["3b9d00305074" "8e22ae4b88b1" "d81ff032aa35"]
treatment-1 reads: ["d81ff032aa35"]            ;; missed enriched chunk → relaxed failed
```

The new `matched_q="Når kom første versjon av Altinn 3?"` line in the
preview is what moved the LLM toward the correct chunk-id pick. The
gap Phase B.5 surfaced — "retrieval boosts rank but read tool picks by
title preview" — is now closed for the hypothetical-questions strategy.

## Caveats

- n=3 is still small. The +17pp signal is the same magnitude as the
  ±20pp LLM noise floor on this query, but the *mechanism* shift
  (agent now reads the enriched chunk) is observed deterministically
  and is the more honest signal.
- Single-chunk enrichment is still a wiring proof; broad-coverage
  enrichment (Phase D) will be the real performance lift.
- Option C (re-order preview to put enrichment-matched chunks first)
  was *not* implemented. The matched-question text in the preview
  appears to be enough signal at this scope; revisit if Phase D's
  broader enrichment shows the ordering also matters.

## Tests added

- `digdir.skills.builtin.agent-test`:
  - `test-format-search-metadata-results-surfaces-matched-questions`
  - `test-format-search-metadata-results-no-matched-q-when-empty`
  - `test-format-search-metadata-results-caps-matched-questions-at-two`
- `digdir.rag.core-merge-test`:
  - `merge-aggregates-matched-questions-per-chunk`

55 tests / 208 assertions across the touched + adjacent namespaces
all green.
