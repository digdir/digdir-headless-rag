# public-docs Rerank Stability Report (Phase D)

Generated 2026-04-21 from 3 sequential `bb rerank-benchmark` runs against `server/test/fixtures/rerank/public_docs_rerank_exploratory.edn`.

## Summary

| class | count |
|---|---|
| stable | 7 |
| marginal-variance | 2 |
| marginal-one-miss | 7 |
| unstable-variance | 0 |
| unstable-many-miss | 0 |
| always-missed | 1 |

## Per-case detail

| id | r1 | r2 | r3 | max-min | missed | class |
|---|---|---|---|---|---|---|
| altinn-authorization-accessgroups-knytning | 1 | miss | 1 | 0 | 1 | marginal-one-miss |
| altinn-authorization-regler | miss | miss | miss | - | 3 | always-missed |
| altinn-authorization-tilgangslister | miss | 1 | 1 | 0 | 1 | marginal-one-miss |
| altinn-broker-getting-started | 2 | 2 | 2 | 0 | 0 | stable |
| altinn-broker-rest-usage | miss | 3 | 3 | 0 | 1 | marginal-one-miss |
| altinn-broker-technical-overview | 6 | 6 | 4 | 2 | 0 | stable |
| altinn-correspondence-post-published | 53 | 53 | 53 | 0 | 0 | stable |
| altinn-dialogporten-about | 1 | 1 | 1 | 0 | 0 | stable |
| altinn-events-architecture | miss | 14 | 14 | 0 | 1 | marginal-one-miss |
| altinn-studio-create-user | 4 | 4 | 4 | 0 | 0 | stable |
| altinn-studio-datamodeling | 1 | 1 | 1 | 0 | 0 | stable |
| altinn-studio-grouping-repeating | 3 | 6 | 6 | 3 | 0 | marginal-variance |
| altinn-studio-grouping-repeating-edit | miss | 2 | 1 | 1 | 1 | marginal-one-miss |
| altinn-systemuser-accept-request | 17 | miss | 10 | 7 | 1 | marginal-one-miss |
| altinn-systemuser-api-model | 71 | 71 | 71 | 0 | 0 | stable |
| altinn-systemuser-api-opprett | 12 | 15 | 15 | 3 | 0 | marginal-variance |
| altinn-systemuser-delegate-clients | 1 | 2 | miss | 1 | 1 | marginal-one-miss |

## Aggregate summary per run

- Run 1: mrr=0.3191 p50=3 p95=71 golden-present-in-retrank=12/17 gate-pass=false
- Run 2: mrr=0.3287 p50=3 p95=71 golden-present-in-retrank=14/17 gate-pass=false
- Run 3: mrr=0.3983 p50=4 p95=71 golden-present-in-retrank=15/17 gate-pass=false

## Classification rubric

- **stable**: all 3 runs hit, max-min rank variance ≤2.
- **marginal-variance**: all 3 runs hit, max-min variance 3-5.
- **marginal-one-miss**: exactly one run missed, other two hit.
- **unstable-variance**: all 3 runs hit but max-min variance >5.
- **unstable-many-miss**: 2 of 3 runs missed.
- **always-missed**: all 3 runs missed — consistent retrieval miss, not variance.
