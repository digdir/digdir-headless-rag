# Release v0.1 — Rough-edges inventory

> **Status:** Proposed / reference artifact (2026-07-08). Not itself a work plan.
> **Companion plan:** [`release-v0.1-packaging-plan.md`](release-v0.1-packaging-plan.md).
> **Baseline:** branch `release-v0.1-packaging` off `release-v0.1-details`, HEAD `efec3c6`.
> **Purpose:** a single, deduplicated, severity-ranked catalog of every rough edge found
> while packaging v0.1 — so a **separate polishing mission** can prioritize functional
> cleanup, and so this packaging mission knows exactly which doc/onboarding fixes it owns.

## How to read this

Every item is tagged with **owner** and **severity**.

- **Owner** = which mission fixes it:
  - `PACKAGING` — fixed by the packaging plan (docs, onboarding, scaffolding). Owned *now*.
  - `POLISH` — a functional/code rough edge deferred to the **separate polishing mission**.
    These are the ones the user asked us to *flag, not fix*.
- **Severity:**
  - `BLOCKER` — stops a new contributor cold on day one.
  - `BROKEN` — a feature, task, or documented path that does not work as written.
  - `DRIFT` — docs/comments contradict shipped code (misleads, doesn't break).
  - `CLEANUP` — tech debt / duplication / half-finished migration; no user-visible break.
  - `COSMETIC` — nits, stale text, placeholder copy.

Evidence anchors are `path:line` against the worktree. Items marked ★ are the
highest-leverage; fix or triage those first.

---

## 0. The two systemic findings (read these first)

Everything below is downstream of two facts that dominate the v0.1 packaging effort:

- ★ **S1 — A major API migration (`/api/rag` + `/api/retrieve` → `/api/mcp`) landed in
  code but not in docs.** The legacy handlers were removed in "Phase 0 of the MCP server
  migration" (`server/src/digdir/api/routes/handlers.clj:4-6`); the route table no longer
  serves them. But `README.md`, `docs/system-overview.md` (§2.2 calls `/api/rag` "the
  central path"), and ~half of `server/docs/api/*` still present the *removed* endpoints
  as the primary interface. `server/docs/api/MIGRATION-PROMPT.md` even instructs AI
  assistants to migrate client code *toward* the dead endpoints. `openapi.yaml` is the one
  API doc that is already correct. **Owner: PACKAGING.** This single correction cascades
  through the whole documentation review (see D-series items).

- ★ **S2 — The documented first-run path is broken end-to-end.** The README's flagship dev
  command doesn't exist, there is no env template on the documented path, retrieval
  requires SSH to a shared remote box with no local fallback, the setup wizard rejects the
  DB backend the team actually uses, and admin-UI login needs an email service with no dev
  bypass. A new hire cannot get from `git clone` to a working query by following the docs.
  **Owner: PACKAGING.** See the B-series items.

---

## 1. Onboarding blockers & broken paths — `PACKAGING`

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★B1 | BLOCKER | README's headline dev command `bb admin-dev` **does not exist**; real task is `bb dev` / `bb server-dev`. `mise run dev` also documented but there is no `[tasks.dev]` in mise. First command a newcomer types fails. | `README.md:98,92`; `bb.edn:557` (`dev`), `766` (`server-dev`); `mise.toml:8-9` (only `[tasks.bb]`) |
| ★B2 | BLOCKER | No usable env template on the documented path. No root `.env.example`, no `gen-jwt-secret.sh` (both absent). The only working template is `server/e2e/.env.example` — excellent but never referenced by README. | `server/e2e/.env.example`; `.gitignore` (`mise.local.toml`, `**/scripts/local/`) |
| ★B3 | BLOCKER | Retrieval requires SSH to a shared remote box. No local Typesense (no compose service ships it); a real query needs `bb port-forward` → `root@5.75.220.232`, a credential a day-one hire lacks, with no self-service fallback. | `bb.edn:561-562,674-695`; `server/e2e/docker-compose.e2e.yml:15-78` (no Typesense/Postgres) |
| ★B4 | BLOCKER | Empty-DB cold start with **no documented seed sequence**. Fresh instance has no tenant/dataset/API-key/data. The pieces exist (`bb dump-import`, committed config JSON, `bootstrap-*` tasks, E2E auto-seed) but are never assembled into one "get your first queryable dataset" recipe. | `server/src/digdir/setup/common.clj:129-134`; `bb.edn:1850-1879` (`dump-import`), `2117-2181` (bootstrap); `config/system-import.normalized.20260404.json` |
| B5 | BROKEN | Admin-UI first login blocked without email service. Login uses a 6-digit code via Scaleway TEM which throws if unconfigured; there is **no dev fallback that logs the code**. The E2E_API_KEY auto-seed is the undocumented escape hatch. | `server/src/digdir/auth/core.clj:130-208`; `server/src-dev/dev.cljc:60-62` (auto-seed) |
| B6 | BROKEN | Setup wizard vs. code disagree on DB backend. `bb setup` hard-requires all three `ADH_POSTGRES_*` and `exit(1)`s otherwise, but `load-bootstrap-config` treats `DATAHIKE_FILE_PATH` as the *preferred dev/test* backend. The Postgres-free path the team actually uses is exactly what the wizard rejects. | `server/src/digdir/setup/common.clj:47-75`; `server/src/digdir/config/core.clj:30-63,77-80` |
| B7 | BROKEN | `bb dev` silently does less than advertised: `-dev {:depends [#_port-forward server-dev]}` — port-forward is commented out, so `bb dev` starts only the server despite the "with port forwarding" description. | `bb.edn:698,557-558` |
| B8 | DRIFT | Phantom Clojure alias: `server-dev` invokes `-X:personal/dev:dev` but `:personal/dev` is undefined in `server/deps.edn` (only `:dev :prod :build :test`). Clojure warns "undeclared alias" on every boot and continues. | `bb.edn:766-767`; `server/deps.edn:65-94` |
| B9 | COSMETIC | ~77 bb tasks, zero help text in `bb tasks` output (tasks use `:description`, listing shows `:doc`). Only ~8 are day-one relevant; nothing signposts them. Learning any task means reading a 170 KB `bb.edn`. | `bb.edn` (170 KB); e.g. `:557` uses `:description` |
| B10 | BLOCKER | No CI. `.github/` has only `PR_BODY_mcp.md`; no `workflows/`. A newcomer's PR is validated by nothing; no enforced parity with local `bb lint`/`bb test`. | `.github/` (only `PR_BODY_mcp.md`); `server/TESTING.md:190` ("CI/CD is not currently configured") |

**Port note (not a defect, but document it):** the raw HTTP default is 8080
(`server/src/digdir/api/http.clj:313`), `dev.cljc` bumps `bb dev` to **8081**
(`server/src-dev/dev.cljc:74`, overridable via `HTTP_PORT`), and the e2e stack + prod use
8080. README's "dev = 8081" is therefore **correct for `bb dev`**, but the per-entrypoint
port story is inconsistent enough to state explicitly in onboarding docs.

---

## 2. Documentation drift — `PACKAGING`

### 2a. README (the front door)

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★D1 | DRIFT | Advertises the **removed** `/api/rag` + `/api/retrieve` as the headless API, with a curl example. Must be rewritten around `/api/mcp`. (See S1.) | `README.md:8,137-157` |
| D2 | BROKEN | References `config/README.md` (×3) — file does not exist. | `README.md:62,126,163`; `config/` has only JSON snapshots |
| D3 | BROKEN | References `docs/config-resolution.md` — does not exist. | `README.md:164` |
| D4 | BROKEN | Documents a "Legacy/EDN config mode" via `ENTITY_CONFIG_FILE` (+ example `config/ka_dev.edn`) that is **not wired in current code** — 0 code references; config is DB-only. | `README.md:60,166-168`; grep `ENTITY_CONFIG_FILE` = 0 hits; `server/src/digdir/config/core.clj:1-16` |
| D5 | BROKEN | Documents env var `AGENT_GRAPH_DEFAULT_LLM_CONFIG` — unused, 0 code references. | `README.md:78-80` |
| D6 | DRIFT | `TYPESENSE_API_KEY`/`_ADMIN` presented as config env vars, but not consumed to configure Typesense at runtime (read from DB config). Only appear in the setup wizard's presence check. | `README.md:71-72`; `server/src/digdir/rag/typesense.clj:17`; `server/src/digdir/setup/common.clj:51` |
| D7 | DRIFT | Omits `DATAHIKE_FILE_PATH`, the easiest dev bootstrap (the "preferred for dev/test" backend). | `README.md:66-68`; `server/src/digdir/config/core.clj:34-35,42-54` |

### 2b. `docs/system-overview.md` (the spine — re-ground to HEAD)

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★D8 | DRIFT | §2.2 still calls `POST /api/rag` "the central path through the system" and lists `/api/rag`/`/api/retrieve` as endpoints — stale post-MCP. (See S1.) | `docs/system-overview.md:112-119` |
| D9 | DRIFT | Baseline is `0cf3032` (2026-04-22); HEAD is `efec3c6` — **387 commits later**. Several "in-flight" notes are now resolved (see D10, D11); the doc needs a re-baseline pass. | `docs/system-overview.md:5` |
| D10 | DRIFT | §3.5 note: `server.Dockerfile` "still references an `admin/` layout … will not build". **Now resolved** — Dockerfile copies the flat `server/` tree. Note is stale. | `docs/system-overview.md:206`; `server.Dockerfile` (flat layout) |
| D11 | DRIFT | §3.5 / §7.4: bb deploy tasks use `deploy-admin.yml`; accessories "being removed in this branch." **deploy-admin.yml transition is resolved** (all tasks use `deploy.yml`); accessories removal is *committed* (not in-flight). | `docs/system-overview.md:218,220,1005`; `bb.edn` deploy tasks; no `deploy-admin.yml` |
| D12 | DRIFT | §4.4 flags `api.context/normalize-dataset-ref` vs `execution.scope/normalize-dataset-ref` as a "near-duplicate." **False flag** — the api.context one is an intentional request-layer wrapper (rejects legacy keys, then delegates). | `docs/system-overview.md:392`; `server/src/digdir/api/context.clj:56` |
| D13 | DRIFT | §10 file index claims 159 files; omits `server/src/digdir/demo/*.clj` (3 demo namespaces) and `skills/graph/trace.clj`, which exist. | `docs/system-overview.md:1099`; `server/src/digdir/demo/` |

### 2c. `server/docs/api/*` and `server/docs/*` (the API/pipeline docs)

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★D14 | BROKEN | `MIGRATION-PROMPT.md` is **actively harmful** — an AI prompt instructing assistants to keep/migrate to the removed `/api/rag`+`/api/retrieve`. Archive or rewrite first. | `server/docs/api/MIGRATION-PROMPT.md:48-49,135-157` |
| ★D15 | BROKEN | `getting-started.md` — the entire first-request tutorial is built on the two removed endpoints; never mentions `/api/mcp`. Also links to missing `endpoints/rag.md`, `endpoints/retrieve.md`. | `server/docs/api/getting-started.md:27-213,218-219` |
| D16 | BROKEN | `client-smoke-flow.md` steps 4–6 hit removed endpoints (steps 1–3 still valid). | `server/docs/api/examples/client-smoke-flow.md:62-119` |
| D17 | BROKEN | `curl-examples.md` — RAG/Retrieve blocks dead; no `/api/mcp` example. | `server/docs/api/examples/curl-examples.md:29-56` |
| D18 | DRIFT | `authentication.md` "Endpoints Requiring API Keys" lists removed routes; omits `/api/mcp` (rest of doc accurate). | `server/docs/api/authentication.md:9,28-29` |
| D19 | BROKEN | `PIPELINES-QUICKSTART.md` final step queries removed `/api/rag` with a non-contract body; also wrong key field and broken `CLAUDE.md` link. | `server/docs/PIPELINES-QUICKSTART.md:85-94,82,178` |
| ★D20 | BROKEN | `server/README.md` is the **unmodified Electric v3 starter boilerplate** — no RAG content, wrong root-fn path `src/admin_app/main.cljc`. Misleading as the server entry-point README. | `server/README.md:1-50,31` |
| ★D21 | BROKEN | `server/TESTING.md` documents `bb test:unit/integration/watch/repl` — **none exist** (only `test`, `test-config`, `test-diagnostics`); references dead `agent.graph.*` namespaces; claims "migrated to RCF" though 125 `*_test.clj` files use clojure.test; states no known-failing tests. | `server/TESTING.md:14-26,35-155`; `bb.edn:1888,1934` |
| D22 | DRIFT | `pipeline-architecture.md` omits the `:kudos` source entirely though it is a first-class source type. (Other file refs accurate.) | `server/docs/pipeline-architecture.md`; `server/src/digdir/pipeline/executor.clj:419-443` |
| D23 | BROKEN | `endpoints/conversations.md` links to missing `./rag.md` and has a stale "auto-created when using `/api/rag`" note. | `server/docs/api/endpoints/conversations.md:339,348` |

**Verified CURRENT (no action):** `openapi.yaml`, `api/README.md`, `endpoints/{mcp,datasets,api-keys,pipelines}.md`, `DATASETS.md`, `PIPELINES.md`, `e2e/README.md`.

### 2d. `docs/` tree — links & freshness

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★D24 | BROKEN | `docs/demo/s5-altinn-translation-drift.md` links to a **machine-local `.claude` memory path** outside the repo — must never ship. | `docs/demo/s5-altinn-translation-drift.md:118` |
| D25 | BROKEN | 5 architecture docs use absolute machine-specific `/Users/bdbrodie/...` links that are all broken (wrong base path; some also point at files since moved to `archives/`). | `docs/architecture/{implementation-vs-target-gap-analysis:3, explicit-node-resolution-gap-analysis:11, archives/config-tree-schema-proposal:5, archives/explicit-node-resolution-schema-proposal:13, archives/skills-pipeline-plan:5}` |
| D26 | BROKEN | `docs/architecture/archives/skill-based-agentic-rag.md` links to `./ARCHITECTURE_DECISION.md` and `./IMPLEMENTATION_PLAN.md` — neither exists. | `docs/architecture/archives/skill-based-agentic-rag.md:11,15` |
| D27 | DRIFT | Archive-vs-current not signposted: `branch-analysis-refactor-config-hierarchies.md` and `explicit-node-resolution-gap-analysis.md` are historical/superseded but sit alongside authoritative docs unlabeled. | `docs/architecture/*` |
| D28 | DRIFT | Two runbooks not cross-referenced from system-overview and possibly ahead of shipped state: `dump-export-import-runbook.md` (YAML dump surface) and `global-config-runbook.md` (`__global__` sentinel + fork/inherit not in the overview). Verify against `bb.edn`/code. | `docs/runbooks/dump-export-import-runbook.md`, `docs/runbooks/global-config-runbook.md` |

---

## 3. Functional rough edges — `POLISH` (separate mission)

These are the "flag, don't fix here" items. Grouped by area. Severity is for the polishing
mission's prioritization, not for packaging.

### 3a. Broken / half-removed features

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| ★P1 | BROKEN | `accessories.yml` referenced by 5 bb tasks but **absent** from the repo → `bb logs-typesense`/`logs-postgres`/`exec-*` all fail. | `bb.edn:753-763`; `README.md:239` |
| P2 | CLEANUP | "Bindings" feature half-removed: UI copy says deprecated and several code paths `throw "Binding metadata is deprecated"`, yet remain reachable. | `server/src/digdir/config/ui/common.cljc:103,538,541,678,790`; `config/ui.cljc:87,384`; `config/ops/{bootstrap:66,sync:716}.clj` |
| P3 | CLEANUP | Config trace resolvers exist as `*-with-trace` and non-trace near-duplicates that could be collapsed. | `docs/system-overview.md:282` |

### 3b. Half-finished migrations / deprecated shims

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| P4 | CLEANUP | Alias shim `digdir.config.ops.archive` → `config.ops.sync` (5 `^:deprecated` fns). | `server/src/digdir/config/ops/archive.clj:4-20` |
| P5 | CLEANUP | Alias shim `digdir.config.ops.common` → `config.ops.util` (5 `^:deprecated` defs). | `server/src/digdir/config/ops/common.clj:4-12` |
| P6 | CLEANUP | One-shot `digdir.auth.migration` (domain-whitelist → permissions) still wired into fresh setup. | `server/src/digdir/auth/migration.clj`; `setup/workflow.clj:91` |
| P7 | CLEANUP | Large legacy-migration surface in `config/db.clj` (~40 `legacy` refs): one-shot-migrations registry, `purge-legacy-config-values!`, legacy tuple-scoped parsing "for historical export migration." Retirement in-flight. | `server/src/digdir/config/db.clj:321,347,563,578,672,679,1767` |
| P8 | CLEANUP | Legacy API-key migration path: `migrate-legacy-keys-to-policies!` + auto-create-policy-for-legacy-key; readers must tolerate both `:api-key/tenants`/`:scopes` and `:api-key/policy`. | `server/src/digdir/config/api_keys.clj:1030,1047`; `docs/system-overview.md:327` |
| P9 | CLEANUP | Legacy pipeline-id parsing + binding-metadata dropping in importer. | `server/src/digdir/import_export/canonical/system.clj:8,44,92,140,695` |
| P10 | CLEANUP | One-shot cutover tool retained: `tools/config_values_pipeline_cutover.clj`. | `server/src/digdir/tools/config_values_pipeline_cutover.clj:16,138` |
| P11 | CLEANUP | 4 no-op deprecated observability shims "retained for compatibility." | `server/src/digdir/playground/ui/observability.cljc:577,582`; `observability/live.cljc:715,721` |
| P12 | CLEANUP | `playground .../live.cljc` and `live_next.cljc` coexist with duplicate logic; callers not fully migrated. | `docs/system-overview.md:970` |
| P13 | CLEANUP | Ingestion mid-transition: `digdir.pipeline.*` adapters delegate to legacy `digdir.docs.*`; consolidation incomplete; `docs/loader.clj` (Kudos) is heavy mixed-concerns legacy. | `docs/system-overview.md:507,518,519` |
| P14 | CLEANUP | `setup-pipeline!` deprecated no-op still present. | `docs/system-overview.md:807`; `server/src/digdir/setup/workflow.clj:101` |

### 3c. Duplication

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| P15 | CLEANUP | `kudos.clj` vs `kudos_preprod.clj` duplicate ~95% of pagination/backoff; differ only in base-url, `/search` suffix, backoff, event names. Prime dedup (parameterize base-url). | `server/src/digdir/llm/kudos.clj`, `kudos_preprod.clj` |
| P16 | CLEANUP | `import_export/canonical/system.clj` (~860 lines) mixes legacy schema migration with new-schema normalization; hard to test in isolation. | `docs/system-overview.md:760` |
| P17 | CLEANUP | `util/ui.cljc` is a grab-bag mixing async helpers, UI lenses, and benchmarking. | `docs/system-overview.md:455` |

### 3d. Hardcoded / non-configurable values

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| P18 | CLEANUP | Marker backoff `retry-delays-ms` (up to 24h) and `marker-timeout-ms` (6h) hardcoded as top-level `def`s; changing needs a code edit. | `server/src/digdir/llm/marker.clj:11,15` |
| P19 | CLEANUP | Deploy host IP `5.75.220.232` hardcoded in `deploy.yml` (server + builder-remote) and in `bb port-forward`; no env indirection. | `deploy.yml:12,38`; `bb.edn:676-695` |

### 3e. Robustness / correctness sharp edges (from system-overview)

| ID | Sev | Item | Evidence |
| --- | --- | --- | --- |
| P20 | BROKEN? | OpenAI has no 429 retry (Anthropic does) — rate-limit behavior provider-dependent. | `docs/system-overview.md:436` |
| P21 | BROKEN? | No handling for the GPT-5 `:max_tokens`/`:max_completion_tokens` quirk; call sites passing `:max_tokens` would error against GPT-5-family deployments. | `docs/system-overview.md:439` |
| P22 | CLEANUP | Rerank error path: ColBERT failure yields unranked chunks, no retry, no degradation signal beyond `:rerank-error`. | `docs/system-overview.md:584` |
| P23 | CLEANUP | Auto-filter fallback fires only on empty results; low-recall-but-non-empty with tight filters is not retried. | `docs/system-overview.md:586` |
| P24 | CLEANUP | Confirmation codes in-memory only; server restart invalidates all outstanding codes. | `docs/system-overview.md:326` |
| P25 | CLEANUP | Chunk IDs hashed from content only; identical chunks across documents collide, blurring lineage. | `docs/system-overview.md:520` |
| P26 | CLEANUP | Search-phrase cache file-based/local; multi-instance deployments have no cross-instance invalidation. | `docs/system-overview.md:521` |
| P27 | CLEANUP | HTTP response-envelope inconsistency (some handlers wrap, some return flat); `X-User-Id` not gated globally (fails late). | `docs/system-overview.md:873,876` |
| P28 | CLEANUP | `execute-skill-handler` requires explicit `{:tenant,:dataset-config-key}` with no API-key-scope fallback like the old `/api/rag`; no `:allowed-skills` gate. | `docs/system-overview.md:874-875` |
| P29 | CLEANUP | `graph/optimizer.clj` computes execution levels but the runner is sequential; optimizer unused. | `docs/system-overview.md:642` |
| P30 | CLEANUP | No boot-time cross-check that built-in agent definitions' skill-graph ids exist in `templates.builtin`. | `docs/system-overview.md:708` |
| P31 | COSMETIC | 3 placeholder deployment-target agent defs ("placeholder before specialized skills exist"). | `server/src/digdir/config/ops/topology.clj:41,60,79` |
| P32 | COSMETIC | "temporary" WebSocket 100M message-size caps; repeated Electric TODO ("teach electric to transfer time/error values") ×3; stale removed-handler comment. | `server/src/digdir/api/http.clj:307-308,223`; `ui/components.cljc:110,123`; `api/routes/handlers.clj` |

---

## 4. Marker-sweep summary (raw)

360 total marker hits across `server/src/`: `legacy` 166, `TODO` 78, `deprecated` 30,
`one-shot` 15, `temporary` 6, `in-flight` 3, `do not use` 2 (benign prompt text), `XXX` 1
(benign). **`FIXME`/`HACK`: 0.** The concentration is in `config/db.clj` and the
import/export + setup migration surface (P4–P10) — consistent with a config-model
migration that shipped its new path but hasn't retired the old one.

---

## 5. Suggested triage for the polishing mission

Not prescriptive — a starting order for whoever owns POLISH:

1. **P1 (accessories.yml)** — the only truly *broken* operator task; either restore the
   file or delete the 5 tasks. One-line-ish, high signal.
2. **P20/P21** — provider-robustness (OpenAI 429, GPT-5 param quirk) if those providers
   are in scope for v0.1 traffic.
3. **P15 (kudos dedup)** and **P4/P5/P11 (dead shims)** — cheap, satisfying, shrink the
   surface a new contributor has to grok.
4. **P6–P10, P13 (migration retirement)** — larger; do once you're confident all
   environments are cut over (coordinate with ops).
5. Everything else as capacity allows.
