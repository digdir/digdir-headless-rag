# public-docs Query Inventory — Phase 1 Candidate Pool (v1, post-pivot)

Status: Originally produced 2026-04-20 as Phase 1 of `public-docs-automated-evals-plan.md`. **Rewritten 2026-04-20 (same day)** after runtime inspection proved the materialized corpus is single-source Altinn-NO only. See `claude-public-docs-dataset-report.md` §2 and Appendix B for the factual basis of the pivot.

This document is the hand-curated candidate pool for the v1 `public-docs` eval suite. Every case is authored against a concrete document present in the materialized corpus — not from broad sitemap knowledge.

## 1. Scope & Methodology

**V1 scope** (revised post-pivot):

- Corpus: 62 documents / 177 chunks / 1387 phrases in `website_*_ab897fbdedfa`, all `docs.altinn.studio` Norwegian Bokmål.
- Target count: 10-15 candidates (requirements §1.3 v1).
- Single `:source-slice :altinn-docs`, single `:language :no`.
- No bilingual pairs, no query-family gating, no Nynorsk.

**Authoring method** (changed from previous version):

1. Pulled the full document index via `typesense.client/search` against `website_documents_ab897fbdedfa`.
2. Selected documents with ≥3 chunks or with a topic anchor that is likely to attract real user queries (definitions, getting-started pages, top-level concept pages).
3. Authored one Norwegian query per selected document, phrased as a typical reader would ask it.
4. Balanced across the corpus's actual topic spread: authorization (19 docs), altinn-studio (15), broker (8), correspondence (4), dialogporten (4), api/systemuser (3), app-template (3).

**What was deprecated from the previous inventory:**

- All 14 Digdir Docs queries — source not materialized.
- All 15 English queries — no English content exists.
- The Nynorsk smoke case — corpus is Bokmål only.
- Bilingual pair labels — no pairs possible.

Nothing from the previous inventory survived unchanged; even the single `altinn-app-data-model-add-no` case is reformulated against the concrete `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` doc (15 chunks — the richest target in the corpus).

## 2. Labelling Conventions

Same as the frozen contract (`claude-public-docs-eval-contract.md` Decision 2). Reproduced here for review convenience:

| Field | V1 value space | Notes |
|---|---|---|
| `:id` | `altinn-<topic>-<slug>` kebab-case | unique within the inventory |
| `:query` | Norwegian Bokmål text | user-style phrasing |
| `:language` | `:no` (fixed for v1) | v2 re-adds `:en`, `:nn` |
| `:source-slice` | `:altinn-docs` (fixed for v1) | v2 re-adds `:digdir-docs` |
| `:query-family` | `:exact-lookup` \| `:paraphrase` \| `:navigational` \| `:factual` | |
| `:golden-chunk-ids` | vector of chunk-id strings | **TBD** — fill via `bb retrieve-debug` |
| `:notes` | freeform | target doc URL + any edge-case flag |

## 3. V1 Candidate Pool — Altinn NO

Each row is tied to a concrete document. The "Target doc" column is the authoritative answer source; the golden chunk ID(s) come from that document after a `bb retrieve-debug` inspection. All cases: `:source-slice :altinn-docs`, `:language :no`.

| ID | Query | Family | Target doc |
|---|---|---|---|
| altinn-authorization-tilgangslister | Hva er tilgangslister i Altinn Authorization og hva brukes de til? | `:exact-lookup` | `/nb/authorization/about/index.md` or `/nb/authorization/what-do-you-get/index.md` |
| altinn-authorization-regler | Hva er tilgangsregler i Altinn Authorization? | `:exact-lookup` | `/nb/authorization/what-do-you-get/rules/index.md` (4 chunks) |
| altinn-authorization-rules-getting-started | Hvordan kommer jeg i gang med å lage tilgangsregler? | `:navigational` | `/nb/authorization/getting-started/rules/index.md` |
| altinn-systemuser-api-opprett | Hvordan oppretter jeg en systembruker via Altinn system user API? | `:navigational` | `/nb/api/authentication/systemuserapi/systemregister/create/index.md` (4 chunks) |
| altinn-systemuser-api-model | Hvordan ser datamodellen for en system user ut i Altinn? | `:exact-lookup` | `/nb/api/authentication/systemuserapi/systemregister/model/index.md` (4 chunks) |
| altinn-systemuser-accept-request | Hvordan aksepterer en sluttbruker en forespørsel om system user i Altinn? | `:navigational` | `/nb/authorization/guides/end-user/system-user/accept-request/index.md` (4 chunks) |
| altinn-systemuser-delegate-clients | Hvordan delegere klienter til en system user i Altinn? | `:navigational` | `/nb/authorization/guides/end-user/system-user/delegate-clients/index.md` (3 chunks) |
| altinn-studio-datamodeling | Hvordan definerer jeg en datamodell i Altinn Studio v8? | `:navigational` | `/nb/altinn-studio/v8/reference/data/data-modeling/index.md` (15 chunks — largest doc) |
| altinn-studio-create-user | Hvordan oppretter jeg en bruker i Altinn Studio v8? | `:navigational` | `/nb/altinn-studio/v8/getting-started/create-user/index.md` (5 chunks) |
| altinn-studio-grouping-repeating | Hva er forskjellen mellom repeterende og ikke-repeterende grupper i Altinn Studio? | `:paraphrase` | `/nb/altinn-studio/v8/reference/ux/fields/grouping/repeating/index.md` + `/non-repeating/index.md` |
| altinn-studio-grouping-repeating-edit | Hvordan redigerer jeg en repeterende gruppe i Altinn Studio v8? | `:navigational` | `/nb/altinn-studio/v8/reference/ux/fields/grouping/repeating/edit/index.md` (6 chunks) |
| altinn-broker-getting-started | Hvordan kommer jeg i gang med Altinn Broker under overgangen til Altinn 3? | `:navigational` | `/nb/broker/broker-transition/getting-started/index.md` (6 chunks) |
| altinn-broker-rest-usage | Hvordan bruker jeg Altinn Broker med REST-API? | `:navigational` | `/nb/broker/broker-transition/usage/rest/index.md` (10 chunks) |
| altinn-broker-technical-overview | Hva er den tekniske arkitekturen bak Altinn Broker i overgangsfasen? | `:exact-lookup` | `/nb/broker/broker-transition/technical-overview/index.md` (5 chunks) |
| altinn-correspondence-post-published | Hva skjer med en correspondence etter at den er publisert i Altinn? | `:factual` | `/nb/correspondence/explanation/status-lifecycle/post-published/index.md` (5 chunks) |
| altinn-events-architecture | Hvordan er Altinn Events arkitektonisk bygget opp? | `:exact-lookup` | `/nb/events/reference/architecture/events/index.md` (13 chunks) |
| altinn-dialogporten-about | Hva er Dialogporten? | `:exact-lookup` | `/nb/dialogporten/index.md` or `/nb/dialogporten/reference/authorization/altinn-authorization/index.md` (3 chunks) |
| altinn-authorization-accessgroups-knytning | Hvordan knytter man en organisasjon til en tilgangsgruppe i registeret? | `:navigational` | `/nb/authorization/what-do-you-get/accessgroups/register_er/knytning_org/index.md` (5 chunks) |

**Total: 18 candidates.** Target v1 post-promotion: 10-15 stable cases.

### 3.1 Query-family coverage

| Family | Count |
|---|---|
| `:exact-lookup` | 7 |
| `:navigational` | 9 |
| `:paraphrase` | 1 |
| `:factual` | 1 |

Paraphrase is thin — the corpus does not have many paraphrase-paired documents. If a second paraphrase case is needed to hit the contract's ≥1 `:paraphrase` minimum (which it does, but tightly), we can derive one from the Altinn Broker REST/SOAP pair (two near-synonymous interfaces).

### 3.2 Target-doc coverage check

Topics represented:

| Topic root | Cases | Docs available |
|---|---|---|
| `authorization/` | 4 | 19 — under-represented; can add more post-retrieve-debug |
| `altinn-studio/` | 4 | 15 — proportionally covered |
| `api/authentication/systemuserapi/` + `authorization/guides/*system-user*` | 4 | 6 — well-represented |
| `broker/` | 3 | 8 — proportionally covered |
| `correspondence/` | 1 | 4 — thin |
| `dialogporten/` | 1 | 4 — thin |
| `events/` | 1 | 1 — one-shot, 13 chunks |

Authorization and altinn-studio together carry ~55% of the corpus; the inventory puts 8 cases against them (~44%). Acceptable.

## 4. Promotion Rubric (unchanged from v0)

A candidate moves from this inventory into `server/test/fixtures/rerank/public_docs_rerank_suite.edn` (enforced) iff:

1. A `bb retrieve-debug digdir default public-docs "<query>" --top-k 20 --dataset-id public-docs` run identifies one or more chunk IDs whose content actually answers the query. The chunk ID(s) go in `:golden-chunk-ids`.
2. Three consecutive `bb rerank-benchmark` runs against the case return the same primary `:rerank-position`, or variance ≤2 rank slots.
3. No run produces an error on the case.
4. If any run's `:rerank-position` exceeds 10, the case either gets a per-case `:max-acceptable-rank` override (documented in `:notes`) or moves to Suite 4 (exploratory).

Until promoted, candidates live in `server/test/fixtures/rerank/public_docs_rerank_exploratory.edn`.

## 5. Fill-In Workflow for Goldens

```bash
# For each candidate case:
bb retrieve-debug digdir default public-docs "<query>" --top-k 20 --dataset-id public-docs

# Inspect the top-ranked chunks. Typically the right golden is the top-ranked chunk
# from the Target doc column in §3. If not, log why in the case's :notes.
# If no chunk from any document answers the query, drop the case and record a note.
```

Keep a running worksheet (one row per candidate) during the fill-in: `[id, top-3 chunk_ids, chosen golden(s), notes]`. That worksheet is the audit trail for the first enforced commit.

## 6. Open Items

1. **Broker REST vs SOAP paraphrase pair.** Add `altinn-broker-soap-usage` as a second paraphrase partner if the corpus can support a distinct-but-equivalent query (SOAP doc has 4 chunks vs REST's 10).
2. **Authorization under-representation.** If retrieve-debug shows stable retrieval for the 4 existing authorization cases, add 2-3 more (PAP, PIP, contexthandler) before final promotion.
3. **0-chunk docs** (`authorization/getting-started/rules`, `notifications/reference/openapi`, `altinn-studio/index.md`) are listed in the documents collection but have no chunks. Any query that targets them will fail retrieval by construction — avoid authoring against them. The `altinn-authorization-rules-getting-started` case above may fall into this trap; verify during retrieve-debug and swap the target doc if so.
4. **Heavy-chunk docs** (`data-modeling` 15, `events` 13, `broker rest` 10): consider two cases per such doc post-promotion to catch rank degradation when multiple chunks from the same doc compete.
5. **Nothing from the deprecated v0 inventory is salvageable** except the labeling conventions. Prior Digdir/EN cases are not parked for future v2 use — they were authored blindly and assume target content that may or may not exist post-materialization. Re-author against v2 reality when that time comes.

## 7. Appendix — EDN Skeleton

This is the shape the suite file will take once goldens are filled. Only the first three cases shown.

```clojure
{:cases
 [{:id                "altinn-authorization-tilgangslister"
   :query             "Hva er tilgangslister i Altinn Authorization og hva brukes de til?"
   :language          :no
   :source-slice      :altinn-docs
   :query-family      :exact-lookup
   :golden-chunk-ids  []
   :notes             "target /nb/authorization/about/index.md — verify via retrieve-debug"}

  {:id                "altinn-authorization-regler"
   :query             "Hva er tilgangsregler i Altinn Authorization?"
   :language          :no
   :source-slice      :altinn-docs
   :query-family      :exact-lookup
   :golden-chunk-ids  []
   :notes             "target /nb/authorization/what-do-you-get/rules/index.md (4 chunks)"}

  {:id                "altinn-studio-datamodeling"
   :query             "Hvordan definerer jeg en datamodell i Altinn Studio v8?"
   :language          :no
   :source-slice      :altinn-docs
   :query-family      :navigational
   :golden-chunk-ids  []
   :notes             "target /nb/altinn-studio/v8/reference/data/data-modeling/index.md (15 chunks, largest doc in corpus)"}]}
```

## 8. Cross-References

- Decisions frozen in (and pivoted 2026-04-20): `claude-public-docs-eval-contract.md`.
- Requirements + v1 sizing: `claude-public-docs-evals-requirements.md` §1.3.
- Dataset shape + pivot rationale: `claude-public-docs-dataset-report.md` §2 + Appendix B.
- Suite specs (EDN shapes) + v1 coverage rule: `claude-public-docs-suite-specs.md` §2.2.
- Operations + golden-update workflow: `claude-public-docs-eval-ops.md`.

## 9. Change Log

- 2026-04-20 (initial): 26 candidates across 2 sources + 3 languages, authored blind from sitemaps.
- 2026-04-20 (rewrite): 18 candidates, Altinn-NO only, grounded in the materialized 62-doc corpus. Deprecation of prior Digdir/EN/Nynorsk cases recorded.
