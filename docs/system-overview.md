# System overview

> This document describes the Digdir RAG-as-a-Service application as it is implemented today. It is intentionally grounded in the source tree: every non-trivial claim is anchored to a file and line range. When behavior changes, update the relevant section in the same PR.
>
> *Baseline commit when first written: `0cf3032` (2026-04-22). Re-baselined during the v0.1 packaging pass at HEAD `efec3c6` (2026-07-08) — a drift pass was applied on the `release-v0.1-packaging` branch to correct the sections noted inline (see §2.2, §3.5, §4.4, §10).*

## Table of contents

1. [Introduction](#1-introduction)
2. [System at a glance](#2-system-at-a-glance)
3. [Repository layout and build](#3-repository-layout-and-build)
4. [Cross-cutting concerns](#4-cross-cutting-concerns)
   - 4.1 [Configuration system](#41-configuration-system)
   - 4.2 [Authentication & authorization](#42-authentication--authorization)
   - 4.3 [Data layer](#43-data-layer)
   - 4.4 [Execution scope](#44-execution-scope)
   - 4.5 [LLM integrations](#45-llm-integrations)
   - 4.6 [Utilities](#46-utilities)
5. [Core domain modules](#5-core-domain-modules)
   - 5.1 [Document ingestion pipelines](#51-document-ingestion-pipelines)
   - 5.2 [RAG core](#52-rag-core)
   - 5.3 [Skills system](#53-skills-system)
   - 5.4 [Agents](#54-agents)
   - 5.5 [Import / Export](#55-import--export)
   - 5.6 [Setup workflow](#56-setup-workflow)
6. [External surfaces](#6-external-surfaces)
   - 6.1 [Headless HTTP API](#61-headless-http-api)
   - 6.2 [Admin UI](#62-admin-ui)
   - 6.3 [Playground & observability](#63-playground--observability)
7. [Operations](#7-operations)
8. [Testing](#8-testing)
9. [Glossary](#9-glossary)
10. [Appendix: file-path index](#10-appendix-file-path-index)

---

## 1. Introduction

Digdir RAG-as-a-Service is a Clojure application that lets teams stand up a configured, tenant-scoped Retrieval-Augmented Generation service over their own document sources. A single deployment serves three surfaces:

- A **headless HTTP API** (`/api/*`) that external applications call for RAG answers, retrieval-only queries, and conversation management.
- An **admin web UI** (Electric/Hyperfiddle) for configuration, permissions, API-key management, dataset browsing, and an interactive Playground with deep observability.
- **Document ingestion pipelines** that pull content from sources such as Kudos, EPiServer, websites, and folder trees, normalize and chunk it, and store it into per-tenant Typesense collections.

The repository started from the Electric v3 starter and has evolved into a prototype platform where tenants, datasets, skills, agents, and LLM configuration are all modelled as rows in a Datahike/Postgres config tree that the runtime resolves on every request. It is explicitly labelled "PROTOTYPE" in `README.md:2` — the architecture reflects ongoing experimentation, with some modules in mid-transition between older and newer shapes (noted inline throughout this document).

**How to read this doc.** Sections §§4–6 follow a consistent per-module template (purpose, entry points, key files, how it works, design objectives, integrations, rough edges). §§1–3 give the shape of the system; §§7–9 cover operations, testing, and vocabulary. §10 is a flat index of every production source file. Every behavioral claim is anchored with a `path:line` reference you can open directly.

## 2. System at a glance

### 2.1 The three surfaces

```
┌─────────────────────────────────────────────────────────────────────┐
│                         External consumers                          │
└─────────────┬───────────────────────────────────────┬───────────────┘
              │ X-API-Key                             │ Browser + JWT cookie
              │                                       │
        ┌─────▼─────────────┐                   ┌─────▼──────────────┐
        │ Headless HTTP API │                   │   Admin web UI     │
        │  /api/mcp (JSON-RPC)│                 │   (Electric v3)    │
        │  /api/conversations│                  │   Playground       │
        │  /api/datasets    │                   │   Config / Skills  │
        │  /v1/chat/completions│                │   Pipelines / Docs │
        └─────────┬─────────┘                   └──────────┬─────────┘
                  │                                         │
                  └────────────────┬────────────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │   Skills graph runner       │
                    │   (query-planner →          │
                    │    multi-retrieval →        │
                    │    rerank → synthesis,      │
                    │    or agentic loop)         │
                    └──┬───────────────────────┬──┘
                       │                       │
              ┌────────▼─────────┐   ┌─────────▼──────────┐
              │  RAG core         │   │  LLM providers    │
              │  retrieval        │   │  Anthropic        │
              │  auto-filter      │   │  OpenAI / Azure   │
              │  ColBERT rerank   │   │  (via litellm)    │
              │  synthesis        │   │  Kudos            │
              └────────┬──────────┘   └────────────────────┘
                       │
              ┌────────▼──────────┐
              │   Typesense       │
              │   collections     │
              │   (documents,     │
              │    chunks,        │
              │    phrases)       │
              └───────────────────┘

                       ┌──────────────────────────┐
                       │ Ingestion pipelines      │
  Kudos / EPiServer /  │ (executor → loader →     │◄── operator console
  website / folder  ──►│  chunker → phrases →     │    (admin UI)
                       │  Typesense storage)      │
                       └──────────────────────────┘

                ┌──────────────────────────────────┐
                │  Postgres (via Datahike)         │
                │  • config tree (tenants,         │
                │    datasets, skills, agents)     │
                │  • users, permissions, API keys  │
                │  • conversations, messages       │
                │  • pipeline executions           │
                └──────────────────────────────────┘
```

### 2.2 Request shape at a glance

A `tools/call` request to `POST /api/mcp` (MCP JSON-RPC — the legacy `/api/rag`/`/api/retrieve` were removed in Phase 0 of the MCP migration; full reference `server/docs/api/endpoints/mcp.md`) is the central path through the system:

1. **Auth middleware** (`server/src/digdir/api/http.clj`, configured in `digdir.api.routes.endpoints`) resolves the `X-API-Key` to a principal carrying `:dataset-scopes`, `:agent-refs`, and `:skill-graphs`.
2. **MCP dispatch** (`digdir.mcp.transport/handle-mcp-request`) parses the JSON-RPC envelope and routes `tools/call` into `digdir.mcp.tools`, which maps the tool name to an `(agent, skill-graph)` pair (`resolve-agent`, `resolve-skill-graph-id`) and resolves the `(tenant, dataset-config-key)` scope from the tool arguments, the API key's scopes, or env defaults — this is a separate resolution path from `digdir.api.context`, which serves the REST-shaped `/console-api/*` and remaining `/api/*` endpoints (§6.1).
3. **Skill graph invocation** (`server/src/digdir/skills/api.clj`) runs the configured graph — the built-in `basic-rag` template is `query-planner → retrieval → rerank → synthesis` (`server/src/digdir/skills/templates/builtin.clj:15`); `multi-query-rag` swaps in `multi-retrieval` (`server/src/digdir/skills/templates/builtin.clj:55`); agent-configured datasets run the `digdir.skills.builtin.agent` ReAct loop instead.
4. **Retrieval** (`digdir.rag.retrieval`) issues multi-strategy Typesense searches; `digdir.rag.auto_filter` injects detected filters; results are merged, then optionally **reranked** (ColBERT via `digdir.rag.rerank`).
5. **Synthesis** (`digdir.rag.synthesis`) calls the configured LLM with the assembled context and returns an answer with citations, streamed as SSE `notifications/progress` frames when the caller sets `_meta.progressToken`.
6. **Conversation persistence** happens directly through `digdir.data.db` (`create-playground-conversation`, `fetch-conversation-tree`) — the same Datahike helpers the rest of the API and the admin Playground use.

### 2.3 Persistence stores

- **Postgres**, fronted by **Datahike** (`io.replikativ/datahike 0.6.1610` + `datahike-jdbc 0.3.50`, `server/deps.edn:36-38`). Schema + connection in `digdir.data.db`. Holds the config tree, users, permissions, API keys, conversations, messages, agents, and pipeline executions.
- **Typesense** (client via `io.github.runeanielsen/typesense-clj 0.1.147`, `server/deps.edn:50`). Holds per-pipeline `documents`, `chunks`, and `phrases` collections. Connection utilities in `digdir.rag.typesense`.
- **Filesystem caches**: `server/cache/` holds chunk/phrase caches and downloaded artifacts; `server/state/` holds durable atoms (via `duratom`). In production these live under `/app/cache` bind-mounted at `/opt/admin/cache` (`deploy.yml:66-67`).

### 2.4 Tech stack one-liner

Clojure 1.12.4 + ClojureScript 1.11.121 on Java 24, Electric v3 (Hyperfiddle) for the UI, Ring/Jetty + Reitit for HTTP, Datahike over Postgres for state, Typesense for the search index, Malli for schema, Telemere for logging, `litellm-clj` as the LLM abstraction plus direct Anthropic/OpenAI clients, shadow-cljs 2.26.2 for the browser build, deployed via Kamal.

## 3. Repository layout and build

### 3.1 Top-level directory map

| Path | Purpose |
| --- | --- |
| `server/` | The Clojure/ClojureScript application (admin UI + headless API + ingestion). All runtime code lives here. |
| `server/src/digdir/` | Production namespaces (indexed in §10). |
| `server/src-dev/` | Dev entrypoint (`dev.cljc`), dev-only diagnostics, `logback.xml`. |
| `server/src-prod/` | Prod entrypoint (`prod.cljc`) and `logback.xml`. |
| `server/src-build/` | `build.clj` — uberjar + client build orchestration. |
| `server/resources/` | `public/` static assets, `i18n/` translations, `docs_schema.edn`. |
| `server/test/` | RCF and clojure.test tests. |
| `server/docs/` | API docs (`api/openapi.yaml`, `api/README.md`, endpoint examples), pipeline docs (`pipeline-architecture.md`, `PIPELINES.md`, `PIPELINES-QUICKSTART.md`), `DATASETS.md`. |
| `server/cache/`, `server/state/`, `server/logs/`, `server/local-db/` | Runtime artifacts; gitignored. |
| `config/` | Tenant/system-export JSON snapshots — no EDN config files (despite the `README.md` reference to `config/README.md`, which does not exist). |
| `config-defs/` | A single normalized import payload (`import-live-2026-02-06-124739-hard-cut-final.json`). |
| `decisions/` | Three ADR-style design notes. |
| `docs/` | Engineering docs, including this file, `architecture/` gap analyses, `runbooks/`, `kudos/`. |
| `plans/` | Active and completed planning docs. **Not a source of truth for implementation.** |
| `bb.edn` | Babashka task runner — 59+ tasks covering dev, deploy, diagnostics, benchmarks, config operations, migrations, and tests. |
| `mise.toml`, `mise.local.toml` | Toolchain versions (`java = "24"`, `clojure = "latest"`, `node = "22"`, `yarn = "latest"`, `babashka = "latest"`) and shared env vars. |
| `deploy.yml`, `deploy.test.yml` | Kamal deployment config (prod + test). |
| `.kamal/` | Kamal secrets stubs. |
| `server.Dockerfile` | Production image recipe. |
| `scripts/local/` | Local one-shot helpers (e.g., `gen-jwt-secret.sh`). |

### 3.2 Build toolchain

The server build is driven by `server/deps.edn`. The relevant aliases:

- `:dev` (`server/deps.edn:65`) — adds `src-dev` and shadow-cljs; this is what `bb dev`/`bb server-dev` invoke via `-X:personal/dev:dev dev/-main` (backend only), and what `bb dev-fullstack` invokes via `-X:personal/dev:dev dev/-main-fullstack` (server + client watch build). See #330.
- `:prod` (`server/deps.edn:77`) — adds `src-prod` and a pinned closure-compiler.
- `:build` (`server/deps.edn:80`) — `tools.build` + shadow-cljs for uberjar and client builds; invoke with `clj -X:build build-client` or `clj -X:build:prod uberjar`.
- `:test` (`server/deps.edn:86`) — `cognitect-labs/test-runner`, RCF enabled via `-Dhyperfiddle.rcf.enable=true`, skips dev hooks via `-Ddigdir.skip-user-dev=true`.
- `:diagnostics` (`server/deps.edn:68`) — `src-dev` on the classpath without dev user hooks; used by bb diagnostic tasks.
- `:1.12-storm` — FlowStorm debugger.

The ClojureScript client is built by shadow-cljs (`server/shadow-cljs.edn`): `:dev` target writes unhashed modules under `resources/public/admin_app/js`; `:prod` uses hashed module names (`:module-hash-names true`). The Electric build hook `hyperfiddle.electric.shadow-cljs.hooks3/reload-clj` triggers Clojure recompilation on `.cljc` changes.

Dependencies worth calling out (all from `server/deps.edn`):

- **Electric v3** (`com.hyperfiddle/electric v3-alpha-00120400`) — the differential-dataflow UI framework used across the admin UI.
- **Ring 1.11.0 pinned** — "1.12+ uses Jetty 12, incompatible with Electric" (explicit comment at `server/deps.edn:12`).
- **Reitit 0.9.2** (`reitit-ring`, `reitit-malli`) — routing.
- **Datahike 0.6.1610** + `datahike-jdbc 0.3.50` + `postgresql 42.7.8` — the DB stack.
- **Typesense-clj 0.1.147** — search client.
- **litellm-clj** (`tech.unravel/litellm-clj`, git SHA) + `openai-clojure` — LLM routing.
- **Telemere 1.2.0** — structured logging.
- **Malli** (git SHA) — schema / data validation.
- **duratom** (git SHA) — filesystem-backed atoms.
- **chime** — scheduling.
- **RCF** (`com.hyperfiddle/rcf`) — inline rich-comment tests.

### 3.3 Dev entrypoint

`server/src-dev/dev.cljc` starts the dev server:

1. Increases Jackson's default stream-read string limit to 50MB (`server/src-dev/dev.cljc:15-21`) — shadow-cljs would otherwise choke on large data payloads during hot reload.
2. Initializes the skills system via `digdir.skills.init/initialize!` (`server/src-dev/dev.cljc:33-36`).
3. Starts the shadow-cljs server (it provides the nREPL). The `:dev` **watcher** runs only under `bb dev-fullstack`; `bb dev` skips it, which is what frees the backend from the Hyperfiddle activation token (#330).
4. Starts Jetty on **port 8081** via `digdir.api.http/start-server!`, booting the Electric app through `digdir.ui.main/electric-boot`.

Invoke with `bb dev` (`bb.edn`), or `bb dev-fullstack` for the coupled server + client watch. It starts the server only — it does not set up port forwarding, and its description no longer claims to. That is deliberate: local Typesense comes from `docker-compose.dev.yml`, so `bb dev` never SSHes into the shared deploy box. Run `bb port-forward` separately when you want to borrow the shared, populated corpus instead of a local empty one (§7.3).

### 3.4 Prod entrypoint

`server/src-prod/prod.cljc` bakes `resources/electric-manifest.edn` in at compile time (`comptime-resource` macro, `server/src-prod/prod.cljc:11`). The manifest — written by `src-build/build.clj` — carries the shared `:hyperfiddle/electric-user-version` string that the client and server must agree on. Prod starts on **port 8080** with `public/admin_app/index.prod.html`.

Invoke with `clj -M:prod -m prod` (after `clj -X:build:prod uberjar`).

### 3.5 Container image and deployment

`server.Dockerfile` builds from `clojure:tools-deps-trixie`, copies `deps.edn`, warms the dependency cache (`RUN clojure -A:build:prod -M -e ::ok`), copies source + config, then runs `clj -X:build:prod uberjar :version "$VERSION" :build/jar-name "app.jar"`. Start command: `java -cp target/app.jar clojure.main -m prod`.

> **Note:** `server.Dockerfile` copies from the flat `server/` layout (`COPY server/deps.edn deps.edn`, etc., build context = repo root) — the earlier `admin/`-layout mismatch is resolved. Real deployments go through Kamal with `builder.remote` set to `ssh://root@<deploy host>` and the build executed on that host, but `docker build -f server.Dockerfile .` from the repo root now also works locally against the current tree.

`deploy.yml` targets a single host via the Kamal proxy at `rag.digdir.cloud` (test: `test.rag.digdir.cloud`, overridden via `deploy.test.yml`). The host itself is not hardcoded: `servers:` and `builder.remote` both render from `DIGDIR_DEPLOY_HOST`, which falls back to `5.75.220.232` when unset, and `bb.edn`'s port-forward tasks read the same variable — so the two cannot drift, and standing up a second environment is an `export` rather than an edit to a tracked file. SSL is provisioned through Let's Encrypt. The container exposes port 8080, the proxy's `app_port`. Persistent cache is bind-mounted from `/opt/admin/cache` into `/app/cache`.

Deploy tasks live in `bb.edn`:

- `bb deploy-server <dest>` → `kamal deploy --config-file=deploy.yml` (`bb.edn:700-707`). Destination is `prod` or a named alternative resolved by `kamal-destination`.
- `bb boot-server <dest>` → restart without rebuilding.
- `bb exec-server <dest> bash` → interactive container shell.
- `bb logs-server <dest>` → `kamal app logs --lines 300 --follow`.
- `bb release-server-lock <dest>` → break a stuck Kamal deploy lock.

> **Note:** These tasks were previously named `bb deploy-admin`/`boot-admin`/`exec-admin`/`logs-admin`/`release-admin-lock` and pointed at a `deploy-admin.yml` that didn't match the repo's `deploy.yml` — both the task names and the config-file mismatch are now resolved; all target `deploy.yml` (and `deploy.test.yml` for the test destination).

There are no accessory tasks. Five of them — `bb logs-typesense`, `bb logs-typesense-test`, `bb logs-postgres`, `bb exec-typesense`, `bb exec-postgres` — tailed logs and shelled into shared Typesense/Postgres containers via `kamal accessory --config-file=accessories.yml`. `accessories.yml` was removed from the repo in `f3d9c37`, so all five failed outright with `Configuration file not found`; they were deleted rather than restored (issue #9), because the file was removed deliberately and re-adding it would re-commit the shared deploy host address. Local Typesense and Postgres now come from `docker-compose.dev.yml`, where `docker compose -f docker-compose.dev.yml logs` and `... exec` cover the same needs. For the deployed accessories, run `kamal accessory` directly against your own Kamal config.

## 4. Cross-cutting concerns

### 4.1 Configuration system

**Purpose.** The configuration system stores and resolves all runtime settings — services, auth, LLM providers, agent behavior, dataset definitions, skill parameters — from a Datahike-backed tree. Per-request context (tenant, agent, dataset ref) drives resolution: callers ask for a path, the system walks a tenant's inheritance tree under one of three fixed roots (platform, runtime, dataset) and returns the first ancestor's value, decrypting on the fly when definitions are marked as encrypted. Writes happen through the admin UI, the `bb config-set` CLI, and import/export tools; reads happen from essentially every request handler and background job.

**Entry points.**
- `(cfg/get {:tenant t} :services :auth :jwt-token-expiry-hours)` — `server/src/digdir/config/accessor.clj:93` — platform-rooted value fetch with decryption; throws if path undefined.
- `(cfg/get-runtime-value path {:tenant t :agent-id a :node-id n})` — `server/src/digdir/config/accessor.clj:238` — runtime-rooted fetch for an explicit agent.
- `(cfg/load-runtime-config-v2 {:tenant t :agent-id a :node-id n})` — `server/src/digdir/config/accessor.clj:324` — bulk fetch of all runtime values for a node.
- `(cfg/get-dataset-value path {:tenant t :dataset-id d :pipeline-id p})` — `server/src/digdir/config/accessor.clj:441` — dataset-rooted fetch.
- `(cfg/get-if-allowed ...)` — `server/src/digdir/config/accessor.clj:593` — permission-checked variant that consults the ABAC layer.

**Key files.**
- `server/src/digdir/config/accessor.clj` — public read API; path normalization, value decoding, decryption, platform/runtime/dataset variants with and without trace.
- `server/src/digdir/config/core.clj` — bootstrap: reads env vars, builds the Datahike config (file or JDBC backend), exposes `(use-db-config?)`.
- `server/src/digdir/config/db.clj` — Datahike operations on the config tree: definition CRUD, node lookup, ancestor walk, batch resolution.
- `server/src/digdir/config/schema.clj` — Datahike schema for config definitions, nodes, values, and bindings.
- `server/src/digdir/config/crypto.clj` — AES-256-GCM with PBKDF2-SHA256 key derivation (100k iterations, 16-byte salt, 12-byte IV).
- `server/src/digdir/config/validator.clj` — Clojure spec validation for bootstrap and config structures.
- `server/src/digdir/config/permissions.clj` — ABAC permission evaluation for config access and user login.
- `server/src/digdir/config/audit.clj` — audit log for config mutations; redacts encrypted values.
- `server/src/digdir/config/api_keys.clj` — API key generation, storage, validation, scope normalization.
- `server/src/digdir/config/ops/*` — bulk operations: `sync` (export/import), `bootstrap` (seed a tenant), `clone`, `materialization` (pipeline-to-dataset reconciliation), `retirement`, `topology`; `archive` and `common` are deprecated alias shims.
- `server/src/digdir/config/ui.cljc` + `config/ui/*.cljc` — Electric UI over the tree (tree view, permissions, audit, API keys, inheritance editor). See §6.2.

**How it works.**

Bootstrap selects a Datahike backend: **file** (set `DATAHIKE_FILE_PATH`, preferred for dev) or **remote JDBC/Postgres** (set `ADH_POSTGRES_URL` + user + password) — `server/src/digdir/config/core.clj:28-67`. `CONFIG_MASTER_KEY` and `JWT_SECRET` are required in both; `(use-db-config?)` returns true iff the master key is set (`server/src/digdir/config/core.clj:76-81`). The `load-bootstrap-config` helper runs once at process start and caches the result in `!bootstrap-config` (`server/src/digdir/config/core.clj:73`). Tenant is **not** resolved from env (`core.clj:83-86` explicitly returns nil); it is supplied per request.

A call like `(cfg/get {:tenant t} :services :auth :jwt-token-expiry-hours)` resolves as follows:

1. Normalize the path into a canonical definition key and look up the definition by path — the definition declares which root (platform/runtime/dataset) owns this value and whether it is `:encrypted?`.
2. Resolve against the platform tree: starting from the tenant's selected platform node, walk parent pointers upward until an ancestor has a value for the path (`server/src/digdir/config/db.clj`). The first hit wins; this gives deterministic single-winner resolution.
3. Decode by the definition's declared type (string, boolean, number, or EDN) and decrypt with the master key if the definition carries `:config-def/encrypted? true`.
4. Return the decoded value, or the declared default if none was found in the traversal.

`get-runtime-value` and `get-dataset-value` follow the same pattern against their own roots, but first resolve which node to start from — typically by agent-id (runtime) or dataset/pipeline-id (dataset) binding. Bulk loaders (`load-runtime-config-v2`, `get-runtime-skill-config-v2`, `get-dataset-pipeline-config-v2`) fetch the entire ancestor chain once and resolve all paths against it, which is O(tree depth) instead of O(tree depth × paths).

The three roots partition definitions — `server/src/digdir/config/schema.clj:68`:

- **Platform** — shared infrastructure: LLM provider endpoints, auth secrets, email, rate limits.
- **Runtime** — agent/skill behavior: prompts, retrieval settings, response policy.
- **Dataset** — source-of-truth per dataset: source type, extraction rules, chunking, target Typesense collection names.

Encryption at rest uses AES-256-GCM. The master key is derived from `CONFIG_MASTER_KEY` via PBKDF2-SHA256 (100k iterations) with a per-value random 16-byte salt and 12-byte IV; storage format is Base64(salt ‖ iv ‖ ciphertext+tag) (`server/src/digdir/config/crypto.clj`).

**Design objectives (as implemented).**
- **Deterministic single-winner resolution within tenants.** Single-parent trees and upward walk guarantee one value per path per node; every result carries a trace identifying the winning ancestor.
- **Per-request tenant context.** The server is stateless with respect to tenant — each request names its tenant and config-key, so one process serves many tenants without cross-talk (`server/src/digdir/config/core.clj:13-14, 83-86`).
- **Encryption of secrets at rest.** Definitions declare `:encrypted?`; encrypted values are stored ciphertext-only, redacted from audit logs.
- **Auditability.** Every mutation records actor, timestamp, before/after (redacting encrypted fields), and tenant/client metadata.
- **Explainability.** `*-with-trace` variants return the resolution path, not just the value — surfaced in the admin UI inheritance editor and in the Playground diagnostics.
- **Granular ABAC.** Permissions match user attributes (role, team) against definition attributes (service, sensitivity, function) with per-path-prefix rules.

**Integrates with.** Almost every module reads through `digdir.config.accessor`: the HTTP stack (`digdir.api.http` for auth config, `digdir.api.context` for per-request resolution), skills (`digdir.skills.api`, every builtin skill), RAG core (synthesis, rerank, retrieval, query-relaxation), pipeline executor, setup wizard. Writers are fewer: admin UI (`digdir.config.ui.*`), ops namespaces, import/export (`digdir.import_export.entities.config`), and `bb config-set`. Shares a Datahike connection with `digdir.data.db` (schema lives in the same DB).

**Known rough edges.**
- `README.md:157-168` references `config/README.md` and `docs/config-resolution.md`. Neither file exists in the tree; both are aspirational holdovers. The README also implies a "legacy EDN config mode" selected by `ENTITY_CONFIG_FILE`, but that path is not wired in current code — `load-bootstrap-config` only knows the two Datahike backends.
- Several runtime and dataset resolvers exist in both `*-with-trace` and non-trace variants with near-identical bodies; trace metadata could likely be made zero-cost and the variants collapsed.

### 4.2 Authentication & authorization

**Purpose.** Two independent authentication paths gate the two external surfaces: email-confirmation + JWT cookie for the admin UI, and `X-API-Key` header for `/api/*`. Both resolve into a permission-based authorization check against an ABAC model stored in the config DB.

**Entry points.**
- `digdir.auth.core/user-by-email` — `server/src/digdir/auth/core.clj:63` — lookup for login.
- `digdir.auth.core/create-token` — `server/src/digdir/auth/core.clj:155` — sign a JWT via `buddy-sign`.
- `digdir.auth.core/send-confirmation-code` — `server/src/digdir/auth/core.clj:184` — email a 6-digit code.
- `digdir.api.http/auth-post-handler` — `server/src/digdir/api/http.clj:40` — initial login endpoint.
- `digdir.api.routes.endpoints/wrap-api-key-auth` — `server/src/digdir/api/routes/endpoints.clj:660` — API key middleware.
- `digdir.config.permissions/can-login?`, `can-access?`, `is-admin?` — policy gates (`server/src/digdir/config/permissions.clj`).

**Key files.**
- `server/src/digdir/auth/core.clj` — JWT signing, confirmation-code issue and verify, user lookup by email and id.
- `server/src/digdir/api/routes/endpoints.clj` (around lines 660–702) — `wrap-api-key-auth` middleware.
- `server/src/digdir/config/api_keys.clj` — API key generation, validation, and access-policy normalization.
- `server/src/digdir/config/permissions.clj` — ABAC permission evaluation.
- `server/src/digdir/auth/cookies.clj` — HTTP-only cookie helpers.
- `server/src/digdir/api/http.clj` — `wrap-admin-auth` middleware, login/confirm/logout handler wiring.
- `server/src/digdir/api/rate_limit.clj` — per-IP attempt tracking for login endpoints.
- `server/src/digdir/auth/views.clj` — server-rendered HTML for the confirm / not-approved / error pages.
- `server/src/digdir/auth/migration.clj` — one-shot migration from an earlier role model to the permission model; mostly informational.

**How it works.**

**Admin UI flow.** A visitor posts their email to `/auth`. The handler (`http.clj:40`) hands off to `perms/can-login?`, which checks the email is known and the user has ≥1 permission. If allowed, `send-confirmation-code` emails a 6-digit code (stored in an in-memory atom with a 10-minute TTL; `auth/core.clj:137`). The user submits the code at `/auth/confirm-email`; on success, `create-token` issues a JWT whose expiry is read from the config DB (`:services :auth :jwt-token-expiry-hours`) and `set-http-only-cookie` puts it in the `auth-token` cookie. Subsequent UI requests carry the cookie; the Electric server reads `:user/id` and `:user/email` out of the ring-request that middleware has enriched, and any request without a valid token or without permissions is redirected to `/auth`.

**API flow.** A client sends `X-API-Key: rag_...`. `wrap-api-key-auth` extracts the header, calls `api-keys/validate-api-key`, and on success attaches the resolved scope info to the request — `:dataset-scopes`, `:agent-refs`, `:allowed-config-keys`, `:client-id` — which downstream handlers (conversations, `/api/mcp` tool dispatch, etc.) use to gate per-dataset and per-agent access. Usage counters and last-used timestamps are updated on each request.

**Permissions (ABAC).** A permission entity carries `:permission/attributes` (an EDN blob of `:service` / `:sensitivity` / `:function`), optional tenant and tenant-config-key restrictions, and `:permission/actions` (`:read`/`:write`). `can-access?` matches a config definition's attributes against the acting user's permissions; `is-admin?` is a shortcut for the pre-seeded `"admin-full"` permission.

**Rate limiting.** `wrap-rate-limit` guards the auth endpoints with per-IP tracking in an in-memory atom (`rate_limit.clj:11`). After 10 failed attempts in a 15-minute window, further requests are rejected (429). Only non-2xx responses count as failures, so a user entering the right code on the 3rd try is not penalized. A cleanup pass drops entries older than 1 hour. A config flag elects to trust `X-Forwarded-For` for load-balancer deployments.

**Design objectives (as implemented).**
- Keep the admin-UI JWT and the API-key channels fully separate: JWTs are short-lived (hours) and cookie-bound; API keys are long-lived, plaintext-display, revocable without deletion.
- Enforce "exists + has ≥1 permission" for admin login — pending users see the "not approved" page (`http.clj:51`). The default-deny posture is explicit rather than implicit.
- Rate limiting is lenient on counting (non-2xx only) so mistyped codes don't lock out legitimate users.
- API key scope is collapsed at validation time into a single resolved structure rather than re-resolved per-handler, giving consistent downstream checks.

**Integrates with.** Config accessor (permission attributes, API key records, JWT expiry config), data DB (user/permission entities), admin UI root (Electric server reads user id/email), API handlers (every `/api/*` request), audit log (login events and API key mutations).

**Known rough edges.**
- Confirmation codes live in an in-memory atom (`auth/core.clj:137`); a server restart invalidates all outstanding codes.
- `digdir.config.api_keys` still writes the legacy `:api-key/tenants` / `:api-key/scopes` fields alongside the newer `:api-key/policy` access-policy entity during normalization. Readers must check both paths.
- `digdir.auth.migration` is a one-shot migration namespace that can be removed once all environments are confirmed migrated.

### 4.3 Data layer

**Purpose.** A single Datahike connection (file or JDBC/Postgres backend) holds everything the system needs to remember: the config tree, users, permissions, API keys, access policies, conversations and messages, pipeline executions, and agent definitions. A small background worker batches writes so latency from JDBC doesn't stall request handlers or the agent loop.

**Entry points.**
- `digdir.data.db/get-conn` — `server/src/digdir/data/db.cljc:659` — returns the shared Datahike connection (deref for the current DB value).
- `digdir.data.background_worker/start!` — `server/src/digdir/data/background_worker.clj:58` — boots the async transaction queue.
- `digdir.data.background_worker/queue-transact!` — `server/src/digdir/data/background_worker.clj:80` — enqueue a `[tx-data]` with optional callbacks.

**Key files.**
- `server/src/digdir/data/db.cljc` — schema (config, users, permissions, API keys + access-policies, conversations, messages, agents, pipelines) and connection init.
- `server/src/digdir/data/background_worker.clj` — `core.async`-backed batch writer.

**How it works.**

`get-conn` returns a single shared connection that `deref` resolves to a Datahike DB value for queries; writes go through `d/transact` directly (synchronous) or `queue-transact!` (async). The background worker reads from a buffered `core.async` channel and batches up to 50 queued transactions per write, or flushes every 10ms when the queue is quiet. Callbacks are fired per-transaction after the batch commits; failures per transaction are surfaced on the error-callback and logged.

The schema is wide and pragmatic: access-policies and API keys carry most of the auth/authorization shape; conversations/messages support branched threads via `:message/parent-message`; agents and permissions are ordinary entities with EDN-encoded attribute blobs.

**Design objectives (as implemented).**
- One DB for everything — config, auth, conversations — avoids cross-store consistency problems at the cost of mixing operational and app data.
- Async writes for the non-critical path (audit log rows, API-key usage timestamps, message persistence) keep request latency independent of JDBC hiccups.
- Synchronous writes still available for mutations that need immediate read-after-write (config edits, user creation).

**Integrates with.** Config (shares schema + connection), auth (user/permission entities), conversations and messages via API handlers, pipeline executor (stores execution rows), Electric UI (reads its own user and conversation entities via pull syntax).

**Known rough edges.**
- Confirmation codes for login are in-memory only (see §4.2); they are not in the Datahike schema.
- API key records carry both legacy fields and new access-policy refs (see §4.2); readers must tolerate both.

### 4.4 Execution scope

**Purpose.** Canonicalize "what this request is operating on" into a single value: a tenant, a dataset reference (tenant + dataset-config-key), plus the selected agent, the resolved agent policy, and the merged dataset + runtime config. The boundary matters: `digdir.execution.scope` is pure (normalization and config-DB reads over a passed-in dataset ref); `digdir.api.context` adds request-level concerns (HTTP params, API-key scope filtering, agent policy, error responses).

**Entry points.**
- `digdir.execution.scope/normalize-dataset-ref` — `server/src/digdir/execution/scope.clj:8` — idempotent canonicalization of `{:tenant … :dataset-config-key …}` across aliases and string/keyword key variants.
- `digdir.execution.scope/distinct-dataset-refs` — `server/src/digdir/execution/scope.clj:30` — dedupe a list of refs.
- `digdir.execution.scope/assoc-execution-scope` — `server/src/digdir/execution/scope.clj:43` — attach a normalized scope onto a map.
- `digdir.execution.scope/resolve-dataset-context-by-ref!` — `server/src/digdir/execution/scope.clj:59` — load the dataset entity and its dataset-rooted config tree with traces.
- `digdir.api.context/resolve-request-dataset-context!` — `server/src/digdir/api/context.clj:430` — request-aware wrapper: picks a dataset ref from request params + API-key grants, then resolves it.
- `digdir.api.context/resolve-request-execution-context!` — `server/src/digdir/api/context.clj:475` — top-level assembly: agent policy + dataset context + runtime config merge.

**Key files.**
- `server/src/digdir/execution/scope.clj` — pure normalization and config-DB reads, no request coupling.
- `server/src/digdir/api/context.clj` — request-coupled resolution: reads HTTP params, enforces API-key dataset scopes, resolves agent policy, merges dataset + runtime config into one view handlers can act on.

**How it works.**

A *dataset ref* is the minimal identifier for work: `{:tenant "ka" :dataset-config-key "prod"}`. `normalize-dataset-ref` accepts string/keyword keys and common aliases (`:config-key`) and returns the canonical map, or nil. `resolve-dataset-context-by-ref!` does the I/O part: look up the dataset entity in the config DB, load the dataset-rooted config tree with a resolution trace, and return a context map `{:dataset … :config … :trace …}`. These two functions are reused outside the HTTP path (e.g., pipeline retries, conversation context loading).

In the HTTP path, `resolve-request-execution-context!` orchestrates: (1) select the agent from request params, validated against the API-key's `:agent-refs`; (2) load agent + its execution policy via `digdir.agents.policy`; (3) call `resolve-request-dataset-context!`, which uses `select-request-dataset-ref!` (`api/context.clj:193`) to pick a dataset ref (again checked against API-key `:dataset-scopes`), then `resolve-dataset-context-by-ref!`; (4) resolve the `:runtime` config node for this (agent, tenant) pair via `cfg/get-runtime-skill-config-v2-with-trace`; (5) merge dataset + runtime config into one resolved view. Handlers receive one fully-resolved context and can dispatch into skills without further I/O beyond the RAG pipeline itself.

**Design objectives (as implemented).**
- **Pure core, impure edge.** Normalization and config-DB reads can be reused in any execution context (pipeline, CLI, background job); only `api.context` knows about HTTP.
- **One resolution, one context.** Handlers don't redo config resolution; the context is assembled once per request and passed through.
- **API-key scope enforcement is centralized.** Dataset-ref selection and agent-ref selection both check the API-key's grants before returning; handlers don't have to re-check.
- **Traces all the way down.** `*-with-trace` variants of config accessors carry the ancestor chain; the execution context preserves these traces for Playground diagnostics.

**Integrates with.** `digdir.api.routes.*` (every route handler that touches a dataset or agent), `digdir.config.accessor` and `digdir.config.db` (dataset + runtime config loading), `digdir.config.api_keys` (allowed-config-key validation), `digdir.agents.db` and `digdir.agents.policy` (agent lookup + execution policy), `digdir.skills.api` (consumes the resolved runtime config).

**Known rough edges.**
- API-key allowed-config-key checks walk node ancestry once per key (`config/api_keys.clj:867-880`); on deep trees with many keys this is O(depth × keys).
- ~~`digdir.api.context/normalize-dataset-ref` and `digdir.execution.scope/normalize-dataset-ref` are near-duplicates~~ — **false flag, corrected 2026-07-08.** `api/context.clj:56` is an intentional request-layer wrapper: it rejects legacy parameter aliases (`reject-legacy-dataset-keys!`, throwing a 400-shaped `ex-info` if present) and then delegates to `execution.scope/normalize-dataset-ref` (`execution/scope.clj:8`) for the actual canonicalization. Not a duplicate implementation.

### 4.5 LLM integrations

**Purpose.** Provide callable access to the LLM providers the rest of the system uses for answer synthesis, query planning, reranking heuristics, agentic tool-use loops, and structured (JSON) evaluations — plus two non-LLM "model-shaped" integrations, Kudos (document source) and Marker (PDF-to-markdown). Azure OpenAI is the primary production provider; Anthropic and direct OpenAI are wired too.

**Entry points.**
- `digdir.llm.openai/create-chat-completion` — `server/src/digdir/llm/openai.cljc:43` — non-streaming completion; switches between Azure and OpenAI based on tenant config.
- `digdir.llm.openai/stream-chat-completion` — `server/src/digdir/llm/openai.cljc:18` — SSE-style streaming path (server-side, Clojure-only).
- `digdir.llm.openai/use-azure-openai` — `server/src/digdir/llm/openai.cljc:40` — per-tenant Azure toggle read from config.
- `digdir.llm.anthropic/create-chat-completion` — `server/src/digdir/llm/anthropic.cljc:12` — Anthropic messages API via clj-http; includes 429 retry.
- `digdir.llm.structured-eval/evaluate` — `server/src/digdir/llm/structured_eval.clj:133` — JSON-output envelope with enum normalization and fallback.
- `digdir.llm.marker/->md` — `server/src/digdir/llm/marker.clj:101` — PDF→markdown conversion with long backoff.
- `digdir.llm.kudos/documents` — `server/src/digdir/llm/kudos.clj` — paginated Kudos list, taking a deployment profile (`kudos/prod` or `kudos/preprod`, selected per kview by `kudos/profile`); `documents-by-ids` alongside it.

**Key files.**
- `server/src/digdir/llm/openai.cljc` — OpenAI + Azure OpenAI via `wkok.openai-clojure.api`; both streaming and non-streaming.
- `server/src/digdir/llm/anthropic.cljc` — clj-http POST to the Anthropic messages API with built-in 429 retry.
- `server/src/digdir/llm/client.clj` — the OpenAI-compatible chokepoint every runtime call funnels through: verbatim request body, GPT-5 param normalization, and 429 retry with backoff on both the direct and `:impl :azure` branches (`OPENAI_MAX_RETRIES`, `OPENAI_RETRY_DELAY_MS`).
- `server/src/digdir/llm/structured_eval.clj` — strips markdown fences, extracts a first JSON object, normalizes enum values (downcases, `_→-`), forwards typed exceptions (`:stage` = `:json-parse` / `:enum-validation` / `:llm-call`) to a fallback function.
- `server/src/digdir/llm/marker.clj` — multipart POST to Marker, with an hour-scale retry ladder and request timeout read from `services.marker.retry-delays-ms` / `services.marker.timeout-ms` (falling back to 1min–24hr and 6 hours when unset).
- `server/src/digdir/llm/kudos.clj` — Missionary-based async pagination with backoff 1s/3s/7s/60s, covering both the production and preprod deployments. The two are one implementation parameterised by a profile that carries base URL, list path (prod appends `/search`), retry schedule, the kview key holding the starting page, and the event names each logs under.
- `server/src/digdir/llm/prompt_fragments.clj` — reusable prompt pieces (e.g., same-language preservation) injected across skills.

**How it works.**

**Provider abstraction.** There is no central protocol or dispatcher. Each provider has its own namespace, and callers import the one they need. This keeps provider-specific features (streaming, tool-calls, Azure deployment quirks) intact at the cost of per-call-site config reads.

**litellm-clj role.** Narrowly scoped. `digdir.rag.query_relaxation` (`server/src/digdir/rag/query_relaxation.clj:6,31`) and `digdir.skills.builtin.query_planner` use `litellm-clj` for tool-use routing against Azure. The agent loop explicitly avoids litellm to preserve multi-turn `:tool_calls` context (explanatory comment at `server/src/digdir/skills/builtin/agent/loop.clj:85`).

**Streaming vs non-streaming.** OpenAI supports both; Anthropic is non-streaming in this codebase; Kudos and Marker are ordinary HTTP request/response. The streaming path is only used from the JVM (`#?(:clj …)` guard at `openai.cljc:18`).

**Azure OpenAI routing.** `(use-azure-openai tenant)` reads `:services :azure-openai :use-azure-openai-api` from the tenant config (`openai.cljc:40`). When true, the request uses `:deployment-name` as the `:model` and passes `:impl :azure`; when false, it uses `:model-name`. Azure also requires `:api-version` in the request params (e.g., `"2024-10-01-preview"` — see `server/src/digdir/rag/query_relaxation.clj:38`).

**structured_eval.** `parse-json-response` (`structured_eval.clj:40`) strips `` ``` `` fences, extracts the first balanced `{…}`, reads as JSON with keyword keys, and throws `ex-info` with `:stage :json-parse` on failure. `->validated-enum` (`structured_eval.clj:74`) downcases, trims, and converts underscores to hyphens (so the LLM can return `"Support_Found"` and get `:support-found`), throwing `:stage :enum-validation` on unknown values. `evaluate` composes the pieces and forwards typed exceptions to a caller-supplied fallback (`structured_eval.clj:133,167`).

**Design objectives (as implemented).**
- Keep provider-specific access direct so features like tool-calls don't get lost in a generic wrapper. The cost is duplication; the benefit is that each call site can tune for its provider.
- Structured evals always succeed — `evaluate` plus a caller-supplied fallback guarantees a return value, with `:degraded? true` on the fallback path so downstream code can still decide what to do.
- Backoff is opinionated per integration: Anthropic retries 429s quickly; `digdir.llm.client` mirrors that policy for OpenAI-compatible providers (honour `Retry-After`, else 60s) but bounds the retries so exhaustion raises an attributable error instead of looping; Marker retries on minute/hour timescales because PDF rendering is inherently slow; Kudos retries at seconds-to-minute cadence.

**Integrates with.** `digdir.rag.synthesis` (answer generation), `digdir.rag.query_relaxation` (LLM query expansion, litellm), `digdir.rag.rerank` (selects chunks to pass forward; ColBERT for rerank scoring itself), `digdir.skills.builtin.agent.loop` (ReAct tool-call loop), `digdir.skills.builtin.query_planner`, `digdir.skills.builtin.entity_extraction`, `digdir.skills.builtin.fact_checking`, `digdir.skills.builtin.summarization`, `digdir.skills.builtin.synthesis`, `digdir.config.accessor` (model and provider selection via config paths).

**Known rough edges.**
- GPT-5-family request quirks are normalized centrally in `digdir.llm.model_params` and applied by `digdir.llm.client/create-chat-completion` on both its branches (direct clj-http POST and the `:impl :azure` delegation to wkok): `:max_tokens` is renamed to `:max_completion_tokens`, and `:temperature` is dropped unless it is 1 — both are hard 400s on GPT-5. Call sites keep passing `:max_tokens`/`:temperature` as they do today; other model families are untouched. Detection is by model/deployment *name* (`gpt-5`, `gpt-5.5`, `gpt-5.4-mini`, …), which is the only signal available on the Azure path — a GPT-5 deployment named without its family stays on the GPT-4 shape.

### 4.6 Utilities

**Purpose.** Catalog of shared helpers that don't belong to any one domain: EDN I/O + file-backed atoms, log truncation for pipeline data, and miscellaneous UI/async helpers.

**Key files.**
- `server/src/digdir/util/core.cljc` — EDN parsing with time-literal readers (`#time/instant`) and `fileatom`, a thin duratom wrapper that persists a Clojure atom to disk as EDN. Used wherever state needs to survive restarts (duratom instances live under `server/state/`).
- `server/src/digdir/util/logging.clj` — Telemere-based logging with a `truncate-for-logging` walker that bounds string length, collection size, and depth before anything hits the log. RAG-specific defaults (200 chars, 5 items, depth 6) are tighter than the general defaults; exports `log-info!` / `log-warn!` / `log-error!` / `log-debug!` macros.
- `server/src/digdir/util/ui.cljc` — miscellaneous helpers: a `=>` composition macro, a `thread` macro for detached threads, `time-and-result` for light benchmarking, lenses-backed `cursor` for derived atoms, and Missionary `m-fail-with-success` / `m-fail-with-success-value` helpers that convert failures into successes with a caller-supplied value.

**How it works.** Each file is narrow. `fileatom` in particular is worth knowing: it is the idiom used in several places (agent workspace, chunk/phrase caches under `server/cache/`, runtime state under `server/state/`) to keep small persistent Clojure values without touching the DB.

**Integrates with.** Everywhere. LLM clients, RAG core, skills, pipeline, and the admin UI import one or more of these.

**Known rough edges.**
- `util/ui.cljc` is a grab-bag with very different concerns mixed together (async helpers, UI lenses, benchmarking). A future split would clarify intent; for now, callers import only what they need.

## 5. Core domain modules

### 5.1 Document ingestion pipelines

**Purpose.** Pull raw documents from a source (Kudos, EPiServer, website sitemap, filesystem), normalize and chunk them by headers, generate search phrases via LLM, and upsert the results into tenant-scoped Typesense collections (documents, chunks, phrases). A run is a *materialization* of a dataset against a configured pipeline; operators trigger one from the admin UI (`POST /api/datasets/{tenant}/{dataset-config-key}/{pipeline-id}/execute`) or from the CLI (`bb import-kudos`, `bb import-kudos-doc`).

**Entry points.**
- `digdir.pipeline.executor/execute-pipeline!` — `server/src/digdir/pipeline/executor.clj:152` — synchronous execution of a pipeline run.
- `digdir.pipeline.executor/execute-pipeline-async!` — `server/src/digdir/pipeline/executor.clj:260` — background execution, status tracked via the execution record.
- `digdir.pipeline.executor/dispatch-to-loader` — `server/src/digdir/pipeline/executor.clj:112` — source-type switch (`:kudos` / `:website` / `:folder` / `:episerver`) into the per-source adapter.
- `digdir.pipeline.core/get-dataset` — `server/src/digdir/pipeline/core.clj:60` — resolves the dataset entity + full pipeline config from the Dataset V2 config tree.

**Key files.**
- `server/src/digdir/pipeline/executor.clj` — execution orchestration: loads dataset config, creates execution record, dispatches to the loader, tracks progress, writes the final status.
- `server/src/digdir/pipeline/core.clj` — pipeline CRUD, ID parsing, dataset record creation/update.
- `server/src/digdir/pipeline/model.clj` — pipeline identity + source-config validation.
- `server/src/digdir/pipeline/collections.clj` — deterministic Typesense collection naming: SHA256 12-char hash over the slice of config that affects output.
- `server/src/digdir/pipeline/materialization.clj` — deployment-target contracts mapping dataset config → canonical loader config.
- `server/src/digdir/pipeline/loaders/{kudos,website,folder,episerver}.clj` — thin adapters delegating to the legacy implementations under `digdir.docs.*`.
- `server/src/digdir/docs/pipeline/protocol.clj` — `DocumentSource` protocol that every source implements.
- `server/src/digdir/docs/pipeline/orchestration.clj` — Missionary-based parallel document prepare / store flows with fault tolerance.
- `server/src/digdir/docs/pipeline/search_phrases.clj` — LLM-backed phrase generation with a file-based cache keyed by chunk CONTENT + model + prompt + parser version.
- `server/src/digdir/docs/pipeline/storage.clj` — Typesense upsert operations for documents / chunks / phrases.
- `server/src/digdir/docs/pipeline/core.clj` — shared helpers: SHA256 hashing, retry, telemetry.
- `server/src/digdir/docs/pipeline/telemetry.clj` — centralized telemetry handlers.
- `server/src/digdir/docs/{kudos,website,folder,episerver}.clj` — the legacy per-source implementations; concrete `DocumentSource` implementations live here.

**How it works.**

*End-to-end flow.*

1. **Trigger.** API or CLI calls `execute-pipeline-async!` with tenant, tenant-config-key (e.g. `"prod"`), pipeline-name, and master-key (`server/src/digdir/pipeline/executor.clj:260`).
2. **Load config.** The executor calls `digdir.pipeline.core/get-dataset` to resolve the pipeline's properties from the Dataset V2 config tree, including materialization node values and any encrypted fields (`server/src/digdir/pipeline/executor.clj:176`).
3. **Resolve collection names.** `digdir.pipeline.collections/get-or-generate-collection-names` (`server/src/digdir/pipeline/collections.clj:131`) hashes the slice of config that affects output (source type, chunk strategy, phrase model, prompt) to a 12-char SHA256. Collection names follow `{prefix}_{type}_{hash}`. If names don't exist yet, they are generated and persisted back into config so future runs target the same collections.
4. **Map config to loader shape.** `digdir.pipeline.materialization/dataset-config->loader-config` (`server/src/digdir/pipeline/materialization.clj:133`) translates dataset-config names (e.g. `:website-sitemap-url`) into the canonical loader shape (`:sitemap/url`) and validates that all required properties are present via `require-explicit-materialization-config!` (`server/src/digdir/pipeline/materialization.clj:116`).
5. **Dispatch to loader.** `dispatch-to-loader` (`server/src/digdir/pipeline/executor.clj:112`) switches on source type and requires the matching adapter from `digdir.pipeline.loaders.*`.
6. **Adapter → legacy implementation.** Each adapter (e.g., `digdir.pipeline.loaders.website/mk-materialize-t`) calls the corresponding legacy implementation (`digdir.docs.website/mk-materialize-t`) with the converted config.
7. **Legacy implementation executes.** The source-specific implementation satisfies the `DocumentSource` protocol (`server/src/digdir/docs/pipeline/protocol.clj:29-31`) and drives the core loop:
   - `fetch-entries` enumerates raw entries (sitemap URLs, folder files, Kudos IDs).
   - `mk-prepare-document-t` fetches content, converts to the canonical doc shape, chunks by headers, and generates search phrases (`server/src/digdir/docs/pipeline/protocol.clj:107-122`).
   - `mk-store-document-t` upserts documents + chunks + phrases into Typesense (`server/src/digdir/docs/pipeline/protocol.clj:124-131`).
   - `digdir.docs.pipeline.orchestration` runs the two flows in parallel under configured parallelism (`:parallelism/documents`, `:parallelism/store`) with fault tolerance (`:fault-tolerance/max-document-failures`) before cancelling the run (`server/src/digdir/docs/pipeline/orchestration.clj:20-77`).
8. **Finalize.** The executor updates the execution row with `:completed` status, document count, and failure count (`server/src/digdir/pipeline/executor.clj:208-210`).

*Materialization.* "Materialization" is the binding contract between a dataset's config and a deployment target's Typesense collections. `target-materialization-contract` (`server/src/digdir/pipeline/materialization.clj:75`) declares the canonical property set each known deployment target must provide (e.g., `["digdir" "public-docs"]` requires `:document-limit`, `:chunk-strategy`, etc.); the executor validates this before running.

*Collection naming.* Every run targets three Typesense collections (`documents`, `chunks`, `phrases`) whose names are deterministic functions of a config hash. When any hashed property changes (e.g., chunking strategy), the hash changes and new collections are created — the old ones are orphaned but not deleted, which lets an operator swap to the re-ingested version atomically by updating the dataset's collection-name references (`server/src/digdir/pipeline/collections.clj:42-70`).

*Search phrases.* Phrases are lightweight query-equivalent summaries of a chunk (comma-separated keyword phrases that should match the chunk in a BM25 search). `mk-distill-search-phrases-t` (`server/src/digdir/docs/pipeline/search_phrases.clj:112-151`) looks up a file-backed cache keyed by `{content_hash}-{model_hash}-{prompt_hash}-{parser_version}.edn`. NOT `chunk_id`: ids became document-scoped in #72, so keying on them would re-generate phrases for every copy of a duplicated chunk (this corpus is ~9% duplicates). The committed warm archive's own keys carry that shape. On a miss, it calls the configured model (default `gpt-4o`) and falls back to a cheaper model on failure, then writes the result to the cache.

*Split between `digdir.pipeline.*` and `digdir.docs.*`.* `digdir.pipeline.*` is the outer orchestration (CRUD, execution records, collection naming, validation); it does not touch documents. `digdir.docs.*` is where the actual fetching, chunking, and storing happens. The adapters in `digdir.pipeline.loaders.*` delegate from the first into the second. A full migration would fold the legacy implementations into `digdir.pipeline.*`, but that has not been completed.

**Design objectives (as implemented).**
- **Deterministic versioned collections.** Hashing the output-affecting config means a re-ingest gets new collections; the old ones remain intact until the operator explicitly flips the reference. Atomic swap, no downtime.
- **Pluggable sources.** Adding a source means implementing `DocumentSource` plus a `pipeline/loaders/*` adapter; chunking, phrases, storage, and telemetry are shared.
- **Parallel with budgeted failure.** `:parallelism/documents` and `:parallelism/store` tune throughput; `:fault-tolerance/max-document-failures` bounds how many failed documents the pipeline tolerates before giving up.
- **Orchestration separate from loading.** Pipeline CRUD, execution tracking, and materialization contracts live in one place; source-specific logic lives in another. Either can evolve without churning the other.

**Integrates with.** Config (dataset V2 tree, encrypted source credentials, pipeline CRUD — §4.1), LLM (`digdir.llm.openai` for phrase generation — §4.5), RAG core (`digdir.rag.typesense` for storage — §5.2), admin UI (operator console at `digdir.pipeline.ui.pipelines` / `.executions` — §6.2), import/export (pipeline + dataset definitions — §5.5), telemetry (`digdir.docs.pipeline.telemetry`).

**Known rough edges.**
- The `digdir.pipeline.*` vs. `digdir.docs.*` split is a mid-transition state. The `digdir.pipeline.loaders.*` adapters delegate to the legacy `digdir.docs.*` implementations; full consolidation has not happened.
- `server/src/digdir/docs/loader.clj` (the Kudos loader) is a heavy, mixed-concerns legacy module — fetching, chunking, phrases, storage, duratom-based telemetry — that has not been refactored into the protocol-based pattern used by the other sources.
- Chunk IDs are hashed from content only (`server/src/digdir/docs/pipeline/core.clj:17-21`); identical chunks across different documents collide, which can blur lineage in diagnostics.
- The search-phrase cache is file-based and local to the runtime. Multi-instance deployments each maintain their own cache; there is no cross-instance invalidation.

### 5.2 RAG core

**Purpose.** The heart of the read path. Given a user question and a conversation, the RAG core orchestrates multi-strategy Typesense search, automatic filtering, result merging and deduplication, semantic reranking via ColBERT, and finally an LLM synthesis pass that returns an answer with enforced `[N]`-style citations. The module is a facade (`digdir.rag.core`) that delegates to specialized namespaces, each owning one concern.

**Entry points.**
- `digdir.skills.builtin.retrieval/execute-retrieval` — `server/src/digdir/skills/builtin/retrieval.clj:416` — the multi-strategy retrieval entry used by the skill graph.
- `digdir.skills.builtin.rerank/execute-rerank` — `server/src/digdir/skills/builtin/rerank.clj:101` — skill wrapper that either delegates to `digdir.rag.rerank` or passes results through.
- `digdir.skills.builtin.synthesis/execute-synthesis` — `server/src/digdir/skills/builtin/synthesis.clj:188` — skill wrapper that builds the context YAML and calls the LLM.
- `digdir.rag.query-relaxation/query-relaxation` — `server/src/digdir/rag/query_relaxation.clj:54` — LLM query expansion via tool-use.
- `digdir.rag.auto-filter/detect-query-filters` — `server/src/digdir/rag/auto_filter.clj` — auto-detect org/year filters from query text.
- `digdir.rag.core/rag-generate` — `server/src/digdir/rag/core.cljc:131` — older top-level synthesis entry kept for backward compatibility.

**Key files** (ordered by pipeline position).

- **Retrieval** — `server/src/digdir/rag/retrieval.clj` (core multi-strategy search + faceting) and `server/src/digdir/skills/builtin/retrieval.clj` (skill wrapper with auto-filter + diversity capping).
- **Rerank** — `server/src/digdir/rag/rerank.clj` (ColBERT orchestration + context-budget enforcement) and `server/src/digdir/skills/builtin/rerank.clj` (skill wrapper).
- **Synthesis** — `server/src/digdir/rag/synthesis.clj` (LLM call + response extraction) and `server/src/digdir/skills/builtin/synthesis.clj` (citation enforcement + context assembly).
- **Query expansion** — `server/src/digdir/rag/query_relaxation.clj` and `server/src/digdir/skills/builtin/query_planner.clj`.
- **Filters + merge** — `server/src/digdir/rag/auto_filter.clj`, `server/src/digdir/rag/filters.cljc` (pure filter serialization), `server/src/digdir/rag/merge.cljc` (dedupe + score normalization).
- **Shared** — `server/src/digdir/rag/core.cljc` (facade), `server/src/digdir/rag/typesense.clj` (connection), `server/src/digdir/rag/typesense_admin.clj` (paginated bulk search), `server/src/digdir/rag/formatting.cljc` (header + metadata formatting), `server/src/digdir/rag/chunking.clj` (markdown-header chunking; called from ingestion).
- **Protocol** — `server/src/digdir/rag/skills/core.clj` (skill protocol + I/O schemas consumed by `digdir.skills.*`).
- **UI** — `server/src/digdir/rag/ui/knowledge.cljc`, `server/src/digdir/rag/ui/status.cljc` (covered in §6.2).

**How it works.**

The read path, step by step:

1. **Query planning.** Called upstream (typically in the skill graph or agent loop). `digdir.rag.query_relaxation/query-relaxation` (`server/src/digdir/rag/query_relaxation.clj:54`) sends the conversation to Azure OpenAI with a tool-use request; the LLM returns one or more `searchPhrases`. Retry logic: 5 attempts with 500ms backoff.

2. **Auto-filter detection.** `digdir.rag.auto_filter/detect-query-filters` scans the query for organization names and years using a cached facet list from Typesense. If it matches, a filter spec like `{:fields [{:field "orgs_long" :selected-options #{"Digdir"}}]}` is produced. Facet cache TTL is 5 minutes and hardcoded in `auto_filter.clj`.

3. **Multi-strategy retrieval.** `execute-retrieval` (`server/src/digdir/skills/builtin/retrieval.clj:416`) runs three Typesense searches per query in parallel:
   - **Phrase** (`lookup-search-phrases-similar`, `server/src/digdir/rag/retrieval.clj:93`) — hybrid keyword + vector search over the `phrases` collection.
   - **Metadata** (`search-chunks-by-metadata`, `server/src/digdir/rag/retrieval.clj:132`) — structured header matches on `chunks`.
   - **Content** (`search-chunks-by-content`, `server/src/digdir/rag/retrieval.clj:173`) — bag-of-words keyword match on chunk `content_markdown`.

   Each search returns hit IDs and per-strategy ranks; content is not fetched yet.

4. **Merge + normalize.** `digdir.rag.merge/merge-chunk-search-results` dedupes by `chunk_id`, normalizes each strategy's ranks to `[0, 1]`, and combines them with strategy weights (content 1.0, phrase 0.7, metadata 0.2 by default; `server/src/digdir/rag/merge.cljc:19-24`) capped by per-strategy contribution limit. Ties resolve by original search index.

5. **Fetch + diversity cap.** Full chunk content is fetched for the top-ranked IDs. `cap-per-document` limits hits per document so a single long doc can't dominate top-K.

6. **ColBERT rerank (optional).** When `rerank-with-colbert=true`, `digdir.rag.rerank/rerank-chunks` takes up to `rerankTopkChunks` (default 40) merged hits, serializes each as `Title: … [metadata]\n\n[content]`, sums lengths up to `rerankMaxLength` (10 KB), and POSTs to the ColBERT API with the query and context. On success, chunks get `:rerank-score` and `:rerank-rank`. On failure, chunks are returned unranked with `:rerank-error` noted — no retry.

7. **Synthesis.** `execute-synthesis` (`server/src/digdir/skills/builtin/synthesis.clj:188`) builds a numbered YAML list `[1] Title: …\n[metadata]\n\n[content]\n\n[2] …`, composes a system prompt with the current date in Norwegian time (`server/src/digdir/rag/synthesis.clj:10-16`), substitutes `{context}` and `{question}` in the template, calls Azure OpenAI with `temperature=0.1`, then parses `[N]` markers back against the citation index to produce the citations payload.

*Typesense layout.* Three collections per pipeline (`documents`, `chunks`, `phrases`) with naming and schema determined at ingestion time (§5.1). `rag.typesense` centralizes the connection; `rag.typesense_admin` provides a paginated bulk-search helper used by diagnostics (`bb retrieve-debug`, etc.).

*Chunking vs. chunking.* `rag.chunking` runs at ingestion time (markdown header splitting); the read path consumes already-chunked records. Listed here for completeness; the code path is §5.1.

**Design objectives (as implemented).**
- **Deterministic retrieval.** Given fixed config and index state, search → merge → rerank is reproducible. Only synthesis is non-deterministic. This enables replay, diff, and debugging.
- **Swappable rerank.** The skill wraps the core implementation so the reranker can change without touching retrieval or synthesis.
- **Pure filters.** `rag.filters` is data-only — no side effects — so filter specs can be logged, replayed, and diffed in diagnostics.
- **Explicit attribution.** Every hit carries its strategy tag through merge; the retrieval skill returns `:search-attribution` metadata so the UI and logs can explain ranking.
- **Enforced citation.** Synthesis always emits `[N]` markers and runs post-generation parsing to catch unsourced claims; flagged but not rejected.

**Integrates with.** Skills builtin (`retrieval`, `rerank`, `synthesis`, `query-planner` — thin wrappers around these namespaces), LLM (`digdir.llm.openai` for synthesis and query relaxation), config (thresholds, top-K, ColBERT URL, cache TTLs), Typesense, RAG UI (`rag.ui.knowledge`, `rag.ui.status`).

**Known rough edges.**
- **Inverted placement.** `digdir.rag.skills.core` defines the skill protocol and I/O schemas but sits under `rag/` and is consumed by `skills/*`. A cross-module dependency that would read more cleanly in a shared namespace.
- **Rerank error path ends the retrieval improvement loop.** ColBERT API failures produce unranked chunks; no retry, no degradation signal to callers beyond `:rerank-error`.
- **Ordering not formalized.** Query-aware boosting (`retrieval.clj:340`) runs post-merge and diversity capping (`retrieval.clj:246`) runs pre-rerank. The order is implicit; changing either affects top-K selection in ways that are hard to reason about.
- **Fallback only fires on empty.** Auto-filter fallback (`retrieval.clj:481-490`) triggers when merged hits are empty; a low-recall-but-non-empty result with tight filters is not retried.
- **Fragmented trace logging.** RAG debug logging is environment-gated per module (`retrieval.clj:15`, `rerank.clj:13`) without a unified switch.

### 5.3 Skills system

**Purpose.** Skills are composable, typed units of work — named functions with schema-validated I/O. A **graph** wires skills into a DAG with explicit input-to-output plumbing. The **runner** executes a graph topologically. Graphs come in two flavors: compile-time **templates** (declared in `templates/builtin.clj`) and dynamically synthesized graphs (built at runtime by the `graph-builder` skill or inside the agent loop). One runner powers both.

**Entry points.**
- `digdir.skills.api/execute` — `server/src/digdir/skills/api.clj:77` — invoke a single skill by id.
- `digdir.skills.api/run-graph` — `server/src/digdir/skills/api.clj:113` — execute an arbitrary graph definition.
- `digdir.skills.api/run-skill-graph` — `server/src/digdir/skills/api.clj:130` — execute a registered template with automatic hydration.
- `digdir.skills.api/initialize!` — `server/src/digdir/skills/api.clj:57` — idempotent first-time registration at boot.
- `digdir.skills.init/initialize!` — `server/src/digdir/skills/init.clj:103` — called from the dev and prod entrypoints.
- `digdir.skills.graph.runner/run-graph` — `server/src/digdir/skills/graph/runner.clj:185` — the synchronous execution engine.
- `digdir.skills.templates.core/instantiate-graph` — `server/src/digdir/skills/templates/core.clj:149` — hydrate a template with runtime + dataset config overrides.

**Key files.**
- **Core API** — `server/src/digdir/skills/api.clj` (public entry points + first-run registration).
- **Execution context** — `server/src/digdir/skills/context.clj` (`ExecutionContext` builder; resolves Typesense at invocation time, defers LLM clients to call time to honor per-tenant config).
- **Skill protocol** — `server/src/digdir/rag/skills/core.clj` (skill metadata, I/O schema vocabulary, execution-context contract — consumed by `skills/*`; see rough edges).
- **Observability** — `server/src/digdir/skills/events.cljc` (event + stage labels emitted by the runner; consumed by the Playground UI — §6.3).
- **Graph schema** — `server/src/digdir/skills/graph/schema.clj` (Malli schemas for graphs + steps; structural + semantic validation, including cycle detection).
- **Graph runner** — `server/src/digdir/skills/graph/runner.clj` (topological sort via Kahn's algorithm; step execution with per-step error modes `:skip` / `:default` / `:fail`; progress callbacks).
- **Graph optimizer** — `server/src/digdir/skills/graph/optimizer.clj` (dependency analysis + execution-level computation for future parallelism; currently unused by the sync runner).
- **Templates** — `server/src/digdir/skills/templates/core.clj` (registry + hydration) and `server/src/digdir/skills/templates/builtin.clj` (built-in templates: `basic-rag` at `:15`, `multi-query-rag` at `:55`, `retrieve-and-rerank`, `fact-checker`, `agent-rag`).
- **Built-in skills (non-agent)** — `server/src/digdir/skills/builtin/{retrieval,rerank,synthesis,query_planner,multi_retrieval,entity_extraction,fact_checking,summarization,graph_builder}.clj`. Each is a thin adapter from graph I/O to the underlying RAG/LLM module.
- **Agent skill** — `server/src/digdir/skills/builtin/agent.clj` is a stub; the implementation lives in `digdir.skills.builtin.agent.*`. See §5.4.
- **Admin UI** — `server/src/digdir/skills/ui.cljc` — browse registered skills + graphs; covered in §6.2.

**How it works.**

**A skill** is a map with `:skill-id` (e.g. `:builtin/retrieval`), `:name`, `:description`, `:inputs`, `:outputs`, optional `:parameters`, `:required-services`, `:tags`. The metadata conforms to `SkillMetadata` defined at `server/src/digdir/rag/skills/core.clj`. Each skill namespace calls `register!` at boot (orchestrated by `digdir.skills.init/initialize!`). Example: the retrieval skill declares inputs `:queries`, `:docs-collection`, `:chunks-collection`, `:phrases-collection` and outputs `:chunks`, `:search-attribution` (`server/src/digdir/skills/builtin/retrieval.clj:44-50`).

**A graph** is `{:id … :name … :inputs [...] :outputs [...] :steps [...]}`. Each step is `{:id :skill :inputs {…} :parameters {…} :condition … :on-error …}`. Input references use `$` for graph inputs (`:$user-query`) and step ids for prior outputs (`[:retrieve :chunks]` for a specific key, or `:retrieve` for the whole result). The `basic-rag` template wires `query-planner → retrieval → rerank → synthesis` (`server/src/digdir/skills/templates/builtin.clj:15`).

**The runner** validates (schema + semantics: no cycles, no forward references via Kahn), topologically sorts steps, then for each step resolves inputs from graph inputs and earlier step outputs, builds an execution context (tenant, parameters, pre-resolved services), calls the skill, and collects outputs. Error modes per step determine whether a failure halts the graph (`:fail`, default), records a default and continues (`:default`), or skips downstream steps that depended on this one (`:skip`). Progress is emitted at step start/complete.

**Templates vs. dynamic graphs.** Templates are Clojure literals registered at boot. `instantiate-graph` hydrates a template by merging in layers — skill defaults, runtime config (per-agent), dataset/pipeline config, then call-time overrides — in that order. Dynamic graphs are synthesized by the `graph-builder` skill or, in practice, by the agent loop (§5.4): the LLM returns a graph definition, it's validated via `schema/fully-validate-graph!`, and handed to the same runner.

**Context object.** The `ExecutionContext` passed to each skill carries the skill id, resolved inputs, merged parameters, pre-resolved services (e.g., Typesense client), the full `skill-params` for sub-skills, the selected agent + dataset ref, an `execution-id` for tracing, and a `validate-io?` flag. Typesense is resolved per-invocation so different tenants can route to different instances; LLM clients are resolved at call time by each skill via `cfg/get` to allow bring-your-own-key and per-agent overrides.

**Events and observability.** `digdir.skills.events` defines a small vocabulary — step started/completed/skipped/failed, graph completed, agent iteration/thinking/tool-call/finalized — that the runner emits through a caller-supplied progress callback. The Playground subscribes to these events to drive its observability view (§6.3).

**Relationship to RAG core.** The built-in skills are thin adapters. `builtin.retrieval` wraps `digdir.rag.retrieval`; `builtin.rerank` wraps `digdir.rag.rerank`; `builtin.synthesis` wraps `digdir.rag.synthesis`. The graph engine doesn't know about Typesense or LLM providers — it just wires skills.

**Design objectives (as implemented).**
- **Typed I/O at boundaries.** Wiring mistakes are caught on graph load, not mid-execution.
- **Graphs are data.** No macros, no runtime compilation. Serializable, inspectable, swappable.
- **Skills are thin.** The hard work happens in `digdir.rag.*` and `digdir.llm.*`; skills adapt I/O.
- **One runner, two sources.** The sync runner executes both templates and agent-generated graphs uniformly, so observability and error handling are shared.

**Integrates with.** `digdir.api.routes.*` (handlers invoke skills through `skills.api`), `digdir.rag.*` (backing implementations), `digdir.llm.*` (synthesis, planner, agent), `digdir.config.accessor` (per-skill parameters via runtime config), `digdir.playground.*` (observability consumes skill events).

**Known rough edges.**
- **Inverted placement of the skill protocol.** `digdir.rag.skills.core` defines the protocol but sits under `rag/` even though it is consumed by `skills/*`. Historical artifact.
- **Stub + core split for the agent skill.** `digdir.skills.builtin.agent` re-exports from `digdir.skills.builtin.agent.core`. The indirection is small but easy to miss when grepping.
- **Unused optimizer.** `graph/optimizer.clj` computes execution levels for potential parallelization, but the runner executes sequentially. No caller uses the optimizer output today.
- **Structural vs. semantic validation split.** Malli covers structure; cycle + forward-ref checks live separately in `schema.clj`. Both must pass for a graph to be safe; running only Malli is not enough.

### 5.4 Agents

**Purpose.** An *agent* in this codebase names two related things: (1) a durable **definition** stored in the config DB — id, name, allowed skill graphs, allowed dataset scopes, guardrails, instructions; and (2) an **execution engine** — an LLM-driven ReAct loop that uses the retrieval / rerank / synthesis skills as tool calls, with structured evidence evaluation and hard budget limits. Definitions live in `digdir.agents.*`; the loop lives in `digdir.skills.builtin.agent.*`.

**Entry points.**
- `digdir.agents.core/validate-agent` — `server/src/digdir/agents/core.clj:115` — normalize + validate an agent definition.
- `digdir.agents.core/builtin-agent-definitions` — `server/src/digdir/agents/core.clj:182` — the five built-in definitions (`simple-qa`, `research-assistant`, `retrieve-only`, `fact-checker`, `agent-rag`).
- `digdir.agents.db/upsert-agent!` — `server/src/digdir/agents/db.clj:92` — persist a definition.
- `digdir.agents.policy/resolve-execution-policy` — `server/src/digdir/agents/policy.clj:36` — compact policy view consumed by `digdir.api.context`.
- `digdir.skills.builtin.agent.core/execute-agent` — `server/src/digdir/skills/builtin/agent/core.clj:634` — skill entry point that creates the workspace and runs the loop.
- `digdir.skills.builtin.agent.loop/agentic-loop` — `server/src/digdir/skills/builtin/agent/loop.clj:473` — the ReAct loop itself.

**Key files.**

*Definitions.*
- `server/src/digdir/agents/core.clj` — agent schema, validation, and built-in definitions.
- `server/src/digdir/agents/db.clj` — Datahike reads + `upsert-agent!` + `seed-builtin-agents!`.
- `server/src/digdir/agents/policy.clj` — `allowed-skill-graph-ids`, `allows-dataset-scope?`, `resolve-execution-policy`.

*Execution.*
- `server/src/digdir/skills/builtin/agent.clj` — proxy stub re-exporting from `core.clj`.
- `server/src/digdir/skills/builtin/agent/core.clj` — skill registration; `execute-agent` builds the workspace, runs the loop, handles citation backfill, writes the trace.
- `server/src/digdir/skills/builtin/agent/loop.clj` — `agentic-loop` at `:473`; LLM call, tool-call processing, sufficiency gates, budget checks.
- `server/src/digdir/skills/builtin/agent/workspace.clj` — workspace atom (chunks, queries, search/read/sufficiency history, evidence plan, citations, budgets); `resolve-budget-limits`, `budget-state`.
- `server/src/digdir/skills/builtin/agent/read_signals.clj` — LLM evaluator for per-claim evidence coverage (support-found / gap-remaining / conflicting / unclear; scope assessment).
- `server/src/digdir/skills/builtin/agent/sufficiency.clj` — LLM gate deciding whether to finalize or keep searching (sufficient / insufficient / conflicting / off-topic).
- `server/src/digdir/skills/builtin/agent/tools.clj` — tool definitions (`agent-tools` at `:17`) bridged to the underlying skills.

**How it works.**

*Definition track.* An agent entity in the config DB has `:agent/id`, `:agent/name`, `:agent/description`, `:agent/instructions` (system-prompt override), `:agent/default-skill-graph`, `:agent/allowed-skill-graphs`, `:agent/allowed-dataset-scopes` (`[tenant dataset-config-key]` tuples), `:agent/guardrails` (e.g. `{:citations-required true}`), `:agent/enabled?`, timestamps. Validation enforces that skill-graph ids are registered, the default is in the allowed list, and dataset scopes are well-formed (`server/src/digdir/agents/core.clj:115-168`). Built-ins are seeded on startup via `seed-builtin-agents!`.

*Execution track.* When a request targets an agent via the Playground, `digdir.api.context/resolve-request-execution-context!` (§4.4) resolves the agent + policy, checks the request's dataset and skill-graph choices against the policy, and then `execute-agent` (`server/src/digdir/skills/builtin/agent/core.clj:634`) runs. MCP `tools/call` requests (§6.1 — the tool name encodes the agent) take a separate path: `digdir.mcp.tools` resolves the agent, skill-graph, and dataset scope itself (not through `digdir.api.context`) before invoking the same skill graph via `digdir.skills.invoke/invoke-rag`. Either way the underlying loop:

1. **Workspace init.** Create a fresh atom holding the chunk dictionary, query history, search/read/sufficiency history, budgets, and evidence plan.
2. **Budget setup.** Resolve `:max-search-passes`, `:max-read-operations`, `:max-read-content-length` from config/defaults.
3. **Initial messages.** Compose chat messages from the agent's instructions (or a built-in default), the conversation history, and the user question.
4. **Loop** (`server/src/digdir/skills/builtin/agent/loop.clj:473`). Until the LLM finalizes, budgets exhaust, or the iteration cap fires:
   - Call the LLM directly via `wkok.openai-clojure.api` (not litellm — the comment at `:85-90` explains this preserves multi-turn `:tool_calls` context).
   - If the response carries `:tool_calls`, dispatch each to the matching tool (`search` / `read_chunks` / `plan_queries` / `rerank_results` / `generate_response` / `inspect_filters`). Tools live in `tools.clj:17` and bridge to the underlying skills.
   - After `read_chunks`, run the **read-signals** evaluator: for each required claim, which are supported, which remain gaps, any contradictions, scope alignment.
   - Run the **sufficiency gate**: continue searching, read more, ask for clarification, or finalize.
   - Decrement the budget in the workspace; if any limit is exhausted, force finalization.
5. **Citation backfill.** If the response lacks explicit `[N]` citations but the workspace has read chunks, optionally run one extra synthesis pass to extract them (`server/src/digdir/skills/builtin/agent/core.clj:557-628`).
6. **Trace + return.** Write a detailed iteration-by-iteration trace file; return the response, evidence chunks, citations, sufficiency decisions, and budget-consumption metadata.

*Tool set.* Defined at `tools.clj:17`. `search` runs multi-query retrieval and returns metadata only (so the LLM doesn't spend context on chunk bodies it hasn't chosen to read). `read_chunks` materializes specified chunk bodies. `rerank_results` re-scores the workspace. `generate_response` runs the synthesis skill with accumulated evidence. `plan_queries` expands a question into diverse phrasings. `inspect_filters` enumerates available facet fields and values.

*Evidence gates.* Read-signals and sufficiency are separate evaluators run by `digdir.llm.structured_eval/evaluate` (§4.5). Keeping them separate from the main LLM call lets the loop reason about retrieval quality and stopping conditions independently of answer generation.

**Design objectives (as implemented).**
- **Separate definitions from execution.** A definition change ships as data; a behavior change ships as code. Operators can grant new dataset scopes or change instructions without touching the loop.
- **Hard budgets.** LLM-driven loops have a well-known footgun. Every tool call decrements a budget; exhausting any budget forces finalization. Budgets show up in the trace and the returned metadata.
- **Structured evidence, not self-assessment.** Read-signals and sufficiency use targeted LLM evaluators with their own schemas, not open-ended "are you done?" questions. Separates retrieval quality from generation quality.
- **Tools bridge to skills.** Changes to retrieval behavior (e.g., a new auto-filter heuristic) automatically reach the agent without touching the loop.

**Integrates with.** Config (agent entities + policy + runtime parameters), skills (every builtin reachable as a tool), RAG synthesis (for `generate_response` + citation backfill), LLM clients (direct to preserve `:tool_calls`), execution scope / api.context (agent resolution and policy checking), Playground observability (events streamed from the loop).

**Known rough edges.**
- **Naming.** "Agent" lives in two trees (`digdir.agents.*` vs. `digdir.skills.builtin.agent.*`) with no cross-reference beyond convention. Historical.
- **Proxy stub.** `digdir.skills.builtin.agent` re-exports from `digdir.skills.builtin.agent.core`. Small indirection, easy to miss when grepping.
- **Provider duplication.** The loop calls OpenAI and Anthropic directly rather than through a dispatcher, to preserve `:tool_calls` context (`agent/loop.clj:85-90`). Azure/deployment-name handling has to be duplicated here if/when needed.
- **Evidence model coupling.** Read-signals and sufficiency share the workspace's evidence-plan shape (required claims, coverage, gaps). Evolving the model touches multiple files.
- **No cross-check between definitions and templates.** Built-in agent definitions reference skill-graph ids that must exist in `digdir.skills.templates.builtin`; there is no automated check at boot beyond the generic graph-id-registered check in `validate-agent`.

### 5.5 Import / Export

**Purpose.** Round-trip migration of a tenant's worth of state — config, users, agents, API keys, conversations, folders — by exporting a canonical JSON snapshot and re-importing it into another environment. The typical use is dev → test → prod promotion; disaster recovery is a secondary use. A preview phase lets the operator validate the diff before any mutation, and conflict resolution is explicit per entity.

**Entry points.**
- `digdir.import_export.system/export-system` — `server/src/digdir/import_export/system.clj:6` — in-memory export coordination.
- `digdir.import_export.system/export-to-file` — `server/src/digdir/import_export/system.clj:18` — file-based export wrapper.
- `digdir.import_export.system/preview-import-system` — `server/src/digdir/import_export/system.clj:14` — dry-run validation.
- `digdir.import_export.system/import-system` — `server/src/digdir/import_export/system.clj:10` — apply phase.
- `digdir.import_export.system/import-from-file` — `server/src/digdir/import_export/system.clj:22`.
- `bb migration-export <file> [--include-audit true|false]` — `bb.edn:836`.
- `bb migration-import <file> [--on-conflict skip|overwrite]` — `bb.edn:864`.

**Key files.**
- `server/src/digdir/import_export/registry.clj` — ordered entity registry, each entry carrying `:export-fn` + `:preview-fn` + `:apply-fn`; dependency ordering is enforced at load time via `assert-entity-order!`.
- `server/src/digdir/import_export/export.clj` — export coordinator: walks the registry, merges payloads, passes through canonical normalization.
- `server/src/digdir/import_export/import.clj` — import coordinator: selects `:preview-fn` or `:apply-fn` per entity depending on phase.
- `server/src/digdir/import_export/model.clj` — canonical envelope: `{:version "2.0" :scope "system" :exported-at <ISO-8601> :data {…}}`, version constants, assert helpers.
- `server/src/digdir/import_export/canonical/system.clj` — 860-line normalization layer: legacy path renames, default-agent fill, dataset-scope synthesis from legacy API-key fields, node-tenant-config-key uniqueness enforcement.
- `server/src/digdir/import_export/entities/*.clj` — per-entity handlers (`users`, `agents`, `config`, `conversations`, `folders`, `api_keys`). Each owns its entity's encryption, refs, and uniqueness.
- `server/src/digdir/import_export/files.clj` — thin JSON read/write wrappers; keyword-to-string on encode, keyword rehydration on read.
- `server/src/digdir/import_export/report.clj` — shared result shape: `created` / `overwritten` / `skipped` per entity + totals.

**How it works.**

1. **Export.** `export-system` walks the registry in dependency order (config → users → agents → folders → api-keys → conversations). Each `:export-fn` pulls rows from Datahike and returns a payload keyed by its entity name. Payloads merge into a single `:data` map, wrap in a `model/system-envelope` (version `"2.0"`, ISO timestamp, scope `"system"`), pass through `canonical/system.clj` for normalization, and are written with `files/write-json-file!`.

2. **Canonical normalization** (`canonical/system.clj`) is a four-stage pipeline:
   - `normalize-system-export` — per-entity normalizers; synthesizes missing dataset scopes from legacy API-key `:tenants` / `:pipelines` fields; fills default agent id if missing.
   - `rewrite-runtime-config-paths` — renames legacy definition paths (e.g., `pipeline.generate.prompt.query-relax` → `skills.query-planner.prompt`; full map at `canonical/system.clj:8-24`) and updates config ids + audit rows to match.
   - `classify-definition-roots` — infers `:config-def/root` (platform / runtime / dataset) from the path prefix when missing.
   - `finalize-explicit-node-resolution-export` — enforces unique node tenant-config-keys per `(tenant, root)`, derives canonical defaults, synthesizes missing API-key `:allowed-config-keys`.

3. **Preview.** `preview-import-system` reads the envelope, asserts the version via `model/assert-system-envelope!`, then calls each entity's `:preview-fn` in registry order. Each returns counts `{:would-create :would-overwrite :would-skip :total}` by looking up entity-ids against the target DB without mutating. The aggregated report in `report.clj` gives the operator a diff to review.

4. **Apply.** `import-system` runs each `:apply-fn` in the same order with the on-conflict strategy (`:skip` default, `:overwrite` optional). Order matters: users before API keys (refs), config before users (decryption), API keys before conversations (agent refs). Config import supplies the master key so encrypted fields decrypt at read time on the new side.

*Audit trail.* Including the audit log on export is optional (`--include-audit`); when included, canonical normalization rewrites audit rows to match the renamed paths so history remains consistent.

**Design objectives (as implemented).**
- **Versioned envelope.** A version field on the outer shape means old exports can be rejected or migrated explicitly when code evolves.
- **Preview before apply.** Operators see exactly what will change. The two-phase shape is especially useful for prod migrations where silent overwrite would be disastrous.
- **Explicit conflict strategy.** Neither silent-overwrite nor silent-skip; the operator picks.
- **Per-entity knowledge lives with the entity.** Encryption, refs, and uniqueness all belong to the entity handler; the coordinator is generic.
- **Replay-safe canonicalization.** Normalization happens on export, so an older payload gets upgraded once — reimporting into a newer environment does not require the newer environment to know every historical shape.

**Integrates with.** Config (master key, encrypted fields, runtime paths), data.db (Datahike transactions), auth (users, permissions, API keys), agents, conversations, admin UI (download/upload triggers).

**Known rough edges.**
- `system.clj` is a pass-through shim; the real logic lives in `export.clj`, `import.clj`, and the registry. If the public API stabilizes, consolidating would cut one indirection.
- `canonical/system.clj` at ~860 lines mixes legacy schema migration and new-schema normalization; hard to test either in isolation.
- Dataset-scope synthesis from legacy API-key shapes (`canonical/system.clj:82-93, 248-257`) covers known cases but is not proven exhaustive.
- No `bb migration-preview` task. Callers wanting a preview must invoke `preview-import-system` programmatically or use the admin UI.

### 5.6 Setup workflow

**Purpose.** `bb setup` walks an operator through the minimum bootstrap from an empty Postgres to a service that can accept requests: check env vars, confirm DB connectivity, seed config definitions, create the first admin user with `admin-full` permission, and optionally bootstrap a tenant's platform/runtime/dataset trees. Not day-to-day operations — this is the narrow band at the start.

**Entry points.**
- `bb setup` — `bb.edn:1085` — top-level CLI task.
- `digdir.setup.workflow/-main` — `server/src/digdir/setup/workflow.clj:129` — orchestrates the full sequence.
- `bb bootstrap-platform-tree`, `bb bootstrap-runtime-tree`, `bb bootstrap-dataset-tree` — `bb.edn:1090-1154` — finer-grained helpers to seed individual trees after the wizard.
- `bb bootstrap-deployment-target-topology`, `bb seed-target-materialization-defaults` — `bb.edn:1156-1183` — target-topology seeding for prod migrations.

**Key files.**
- `server/src/digdir/setup.clj` — module facade re-exporting helpers.
- `server/src/digdir/setup/common.clj` — prompt helpers, `check-env-vars` (`ADH_POSTGRES_URL` / `_USER` / `_PWD`, `JWT_SECRET`, `CONFIG_MASTER_KEY`), `check-database-connection`, `reset-database!`, `get-global-config` against the `__platform-defaults__` internal tenant.
- `server/src/digdir/setup/config.clj` — `ensure-*-config-definitions!` upsert helpers (auth, email, Typesense, Azure OpenAI, pipelines, skills) with large inline spec maps.
- `server/src/digdir/setup/workflow.clj` — `-main` orchestration at `:129`.

**How it works.**

The wizard sequence, per `-main` at `server/src/digdir/setup/workflow.clj:129`:

1. **Env-var check** (`common.clj:47-75`). Required vars must be set; exit early if not.
2. **Database options.** Optionally reset via `reset-database!` (destructive, confirmation required); otherwise use the existing database.
3. **DB connectivity** (`common.clj:77-91`). Test via `config-db/get-conn`.
4. **Import config** (optional). Prompt for a JSON export file and run `config-sync/import-from-file` — useful for cloning a tenant from another environment. This is the bridge to §5.5.
5. **Config definitions.** Seed ~50+ definitions across auth, email, Typesense, LLM, pipeline, and skill concerns via the `ensure-*-config-definitions!` functions. Upsert semantics, so a re-run is safe.
6. **Auth config.** Prompt for JWT token expiry, session max-age, secure-cookies flag; store under the `__platform-defaults__` platform tree.
7. **Email config.** Prompt for Scaleway TEM region, project id, API key, from-email.
8. **Typesense defaults.** Set default collection prefix `digdir_rag_` if not already present.
9. **Admin users.** Check for existing admins; prompt to create more via `add-admin-user-interactive!` → `create-admin-user!` → `perms/grant-permission!` with `admin-full`. If `ADMIN_USER_EMAILS` env var is set, run the legacy-auth migration in `digdir.auth.migration`.
10. **Tenant config.** Optionally create a new tenant (`config-db/register-tenant!`), then bootstrap its platform tree, runtime tree (seeded with the default `builtin/agent-rag-agent`), and a materialization pipeline.
11. **Legacy auth migration** (optional, `workflow.clj:97`). Grant permissions to users previously recognized through domain-whitelist auth.
12. **Summary.** Show all users, admins, and permissions created; point to next steps (API-key creation via the operator console).

**Design objectives (as implemented).**
- **Idempotent-ish.** Re-running after a partial bootstrap re-validates env vars + DB, re-seeds definitions via upsert, and skips admin creation when admins exist. Not strictly idempotent — e.g., a re-run will re-prompt for Scaleway values — but tolerant of reruns.
- **Interactive, not flag-driven.** Operators see every decision explicitly. Less risk of silent misconfiguration.
- **Fail early on missing env.** Don't let the wizard get deep into the DB and then crash.

**Integrates with.** Config (`digdir.config.db` for upserts + tenant registration, `digdir.config.core` for the master key, `digdir.config.ops.bootstrap` for tree seeding, `digdir.config.sync` for import), auth (`digdir.auth.migration`, permission grants), data.db (schema transacts + connection test).

**Known rough edges.**
- `setup/config.clj` defines ~50+ definitions inline inside conditional `ensure-*-config-definitions!` functions rather than as EDN data. Edits require careful diff review.
- Input validation is loose. Prompted values (emails, URLs, Scaleway ids) are stored as-is; failures surface later at runtime.
- API-key creation is not part of the wizard — it is deferred to the admin UI. An operator expecting an end-to-end "ready for API traffic" bootstrap will hit this gap.

## 6. External surfaces

### 6.1 Headless HTTP API

**Purpose.** A stateless, language-agnostic surface for external applications to query RAG, manage conversations, and introspect datasets — without touching the admin UI. The API runs under the same Jetty server that hosts the Electric websocket (`server/src/digdir/api/http.clj`). Three auth channels coexist: `X-API-Key` for `/api/*`, `X-Debug-Api-Key` (env-var-backed) for `/api/debug/*`, and the admin JWT cookie for `/console-api/*`. Conversation endpoints additionally require an opaque `X-User-Id` that the external caller owns — it is not a Datahike user id.

**Entry points.** Three Reitit route tables under `server/src/digdir/api/routes/endpoints.clj`: `api-routes` (`:728`), `debug-routes` (`:777`), `console-api-routes` (`:785`). The `api-router-options` and the router instances are at `:854-866`. Major endpoints, grouped:

- **MCP** — `POST /api/mcp` (MCP JSON-RPC 2.0 over Streamable HTTP; `initialize`, `tools/list`, `tools/call`, `ping`). Each `(agent × allowed-skill-graph)` pair is exposed as a tool named `<agent-id>__<skill-graph-short-name>`; `tools/call` runs the full pipeline (retrieval + synthesis + conversation persist) and returns a blocking JSON result or, when `_meta.progressToken` is set, an SSE stream of `notifications/progress` frames. The legacy `POST /api/rag` (full pipeline) and `POST /api/retrieve` (retrieval only) were removed in Phase 0 of the MCP migration (`handlers.clj:4-6`). Full reference: `server/docs/api/endpoints/mcp.md`.
- **OpenAI-compatible** — `GET /v1/models`, `POST /v1/chat/completions` — surfaces each agent as an OpenAI "model" so generic OpenAI clients can call an agent without speaking MCP (`endpoints.clj:726-730`).
- **Conversations** — `GET/POST /api/conversations`, `GET/PUT/DELETE /api/conversations/:id`. Pagination + tag filter. Scoped by `X-User-Id`; mismatched id → 404 (intentional, does not disclose existence).
- **Datasets & config** — `GET /api/datasets`, `GET /api/datasets/:id`, `GET /api/config/:root/nodes`, `POST /api/runtime/config/resolve`, `POST /api/dataset/config/resolve`.
- **Skills & graphs** — `GET /api/skills`, `GET /api/skills/:id`, `POST /api/skills/:id/execute`, `GET /api/skills/tools` (OpenAI function-calling shape). **The mode-listing endpoints are GONE.** `GET /api/modes` and `GET /api/modes/:id` were removed by #350; verified against the route table rather than assumed — `/modes` has zero route literals at this tip while `/skills`, `/mcp` and `/conversations` are present. *(An earlier version of this line said `/api/skill-graphs`, which was corrected to `/api/modes` in #352 — and #350 retired that name shortly afterwards. A correction is a reading with an expiry like any other.)* **Where to look for modes instead** (pointers, not an equivalence — there is no drop-in replacement for a complete listing over HTTP): `tools/list` on `/api/mcp` returns `<agent>__<mode>` tool names, but it is **grant-filtered** by the calling API key (`digdir.mcp.tools/list-tools`), so it shows what *that key* may reach rather than what is registered; the console's Skill Graphs screen (`digdir.skills.ui/SkillGraphsList`) renders the **full** registry. Both were confirmed present in source at this commit; the wire behaviour of each was last observed on 2026-08-28 at an earlier commit, not re-run here. The skill-graph *execute* routes (`POST /api/skill-graphs/:id/execute`, `POST /api/skill-graphs/execute`) were removed alongside `/api/rag`/`/api/retrieve` in the MCP migration. **Skill listing and inspection stay because they are the PUBLIC DISCOVERY surface — not because the operator console uses them.** This document published the console reason after `endpoints.clj`'s own comment had already refuted it: the console has its own JWT-authenticated router under `/console-api`, and it reaches the registry **in process, never over HTTP**. Confirmed independently twice, three call sites: `skills/ui.cljc:544`, `playground/ui/common.cljc:81`, `agents/core.clj:53`. See the comment above `api-routes` in `endpoints.clj`.
- **Debug** — `GET /api/debug/dataset-config`, `GET /api/debug/chunk`. Returns EDN; auth via `X-Debug-Api-Key` compared to `RAG_DEBUG_API_KEY` env var (`server/src/digdir/api/routes/endpoints.clj:708-726`).
- **Console** (`/console-api/*`, JWT) — API-key CRUD + rotation + revoke + allowed-config-keys, user management, conversation admin, dataset + pipeline admin.

For the full enumeration see `server/docs/api/openapi.yaml` and the route definitions at the line refs above.

**Key files.**
- `server/src/digdir/api/routes.clj` — facade re-exporting handlers and router constructs.
- `server/src/digdir/api/routes/endpoints.clj` — the three route tables, auth middleware (`wrap-api-key-auth` at `:660`, `wrap-debug-api-key-auth` at `:708`), skill/skill-graph execution handlers, router wiring.
- `server/src/digdir/api/routes/handlers.clj` — RAG handlers (`api-rag-handler` at `:108`), API-key CRUD, access-policy listing.
- `server/src/digdir/api/routes/conversations.clj` — conversation CRUD + user admin (`list-conversations-handler` at `:87`).
- `server/src/digdir/api/routes/datasets.clj` — dataset, materialization, config-resolution, pipeline-execution routes.
- `server/src/digdir/api/routes/endpoints/debug.clj` — debug endpoint implementations.
- `server/src/digdir/api/http.clj` — Jetty + Ring + Electric adapter, middleware stack, login/confirm/logout, `wrap-api-routes` URI-prefix dispatcher.
- `server/src/digdir/api/context.clj` — request-context resolution (§4.4).
- `server/src/digdir/api/util.clj` — param normalization, `X-User-Id` assertion (`find-api-conversation!`), conversation-history shaping, skill parameter assembly.
- `server/src/digdir/api/rate_limit.clj` — per-IP rate limit for login endpoints (§4.2).

**How it works.**

A request flows through the middleware stack (`digdir.api.http/middleware`, bottom-up compose, top-down apply):

1. **Ring plumbing** — `wrap-params`, `wrap-cookies`, `wrap-session` (AES key derived from `JWT_SECRET`), static-resource serving, index-template injection.
2. **Admin auth** — `wrap-admin-auth` validates the JWT cookie for `/console-api/*`; unauthenticated calls get 401.
3. **Rate limiting** — `wrap-rate-limit` on login endpoints only (§4.2).
4. **URI-prefix dispatch** — `wrap-api-routes` splits traffic: `/api/debug/*` → debug router (requires `X-Debug-Api-Key`); `/api/*` (not debug) → API router (requires `X-API-Key`); everything else falls through to the Electric websocket or the HTTP index.
5. **API-key auth** — `wrap-api-key-auth` (`endpoints.clj:660`) looks up the key, attaches `:api-key/id`, `:api-key/client-id`, `:api-key/dataset-scopes`, `:api-key/agent-refs`, `:api-key/allowed-config-keys`, `:api-key/scopes` onto the request. Scope enforcement per route via `wrap-required-api-key-scope`.
6. **Reitit coercion** — Malli schemas validate request/response shapes; coercion errors become 400/500 before the handler runs.
7. **Handler** — parses params via `digdir.api.util`, resolves the execution context (§4.4), dispatches into `digdir.skills.api` or `digdir.rag.*`, catches exceptions, returns a Ring response.
8. **Response** — JSON via Cheshire for `/api/*` and `/console-api/*`; EDN for `/api/debug/*`.

**The Electric websocket** shares the Jetty process. Upgrade requests hit `electric-websocket-middleware` and never reach `wrap-api-routes`, so API traffic and UI traffic are fully separate channels on the same server.

**Response shapes.**

- `POST /api/mcp` `tools/call` (200, blocking): JSON-RPC result `{jsonrpc, id, result: {content: [{type, text}], _meta: {conversation_id, status}}}`. With `_meta.progressToken` set, the same call instead streams `notifications/progress` SSE frames (`_meta.event` carries the underlying skill-graph event name, e.g. `agent/iteration-started`, `response/chunk`) followed by the same result shape as the final frame.
- `GET /api/conversations` (200): `{conversations: [...], total, page-size, page-index}`.
- `GET /api/conversations/:id` (200): `{conversation, messages: [{id, text, role, created, tags, filter-value, chunks, diagnostics}]}`.
- Errors: `{error: "message"}`, sometimes augmented with `{available: [...]}` (e.g. allowed dataset scopes when the key lacks access).

**Design objectives (as implemented).**
- **One Jetty, three surfaces.** HTTP API, Electric UI, and auth routes share process and middleware without crosstalk.
- **Centralized API-key scope.** Middleware resolves the key once; handlers trust the attached scope and focus on business logic.
- **Thin handlers.** Request parsing, context resolution, and response serialization factored into `digdir.api.util` and `digdir.api.context`; handlers dispatch into skills/RAG/db.
- **Schema coercion on every route.** Malli catches mismatches before handlers run.
- **Caller-scoped conversations.** `X-User-Id` is caller-owned and opaque — decouples the API from internal user accounts (Playground users, admin users) so one API key can serve many independent end-users.
- **Debug endpoints isolated.** Separate auth, separate router, EDN responses — safe to expose behind a shared secret in dev.

**Integrates with.** Auth (§4.2), execution scope (§4.4), skills (§5.3), agents (§5.4), RAG core (§5.2), data.db (conversations), config (dataset + runtime resolution, API-key records).

**Known rough edges.**
- **Response envelope inconsistency.** Some handlers wrap in a top-level key (`{:conversation {…}}`), others return flat maps. No systematic convention.
- **Skill execution scope mismatch.** `execute-skill-handler` (`endpoints.clj:462`) requires an explicit `{:tenant, :dataset-config-key}` in the request and does not fall back to the API-key scopes the way the removed `/api/rag` handler did. Probably unintentional asymmetry.
- **Skill-graph allow-list without skill parallel.** `execute-skill-graph-handler` honors `:api-key/skill-graphs`, but no equivalent `:api-key/allowed-skills` check gates `execute-skill-handler`.
- **`X-User-Id` not gated globally.** Callers who forget it don't fail until they touch a conversation route; no early 400.
- **Facade pattern inflation.** `server/src/digdir/api/routes.clj` re-exports the majority of handler symbols from the submodules; useful but adds a fan-in point that must be updated on every new endpoint.

### 6.2 Admin UI

**Purpose.** A single-process Electric/Hyperfiddle app that operators use to configure the system, manage data sources, trigger and monitor ingestion, browse the knowledge base, and debug chat behavior through the Playground (§6.3). URL-backed navigation via the browser History API, so every view is bookmarkable. Per-domain UIs live inside each domain's folder (e.g. `digdir.config.ui.*`, `digdir.pipeline.ui.*`), not under `digdir.ui.*` — the root only composes.

**Entry points.**
- `digdir.ui.main/Main` — root Electric component (`server/src/digdir/ui/main.cljc:51`).
- `digdir.ui.main/electric-boot` — server and client boot (`server/src/digdir/ui/main.cljc:128`).
- `digdir.ui.routing/UseRoutedTab`, `UseRoutedFilter` — URL ↔ state sync helpers.
- `digdir.ui.components/routed-tabs` — declarative tab-composition macro (`server/src/digdir/ui/components.cljc:85`).

**Key files.**
- **Root.** `server/src/digdir/ui/main.cljc`, `server/src/digdir/ui/routing.cljc`, `server/src/digdir/ui/components.cljc`.
- **Per-domain UIs.** `server/src/digdir/config/ui.cljc` + `config/ui/*.cljc`; `server/src/digdir/docs/ui.cljc`; `server/src/digdir/pipeline/ui/{pipelines,executions}.cljc`; `server/src/digdir/playground/ui.cljc` + `playground/ui/*` (§6.3); `server/src/digdir/rag/ui/{knowledge,status}.cljc`; `server/src/digdir/skills/ui.cljc`; `server/src/digdir/auth/ui.cljc`.
- **i18n.** `server/src/digdir/i18n.cljc` (`load-translations-cljs!` at `:67`), translations at `server/resources/i18n/base.edn`.

**How it works.**

*Boot.* The dev and prod entry points (`server/src-dev/dev.cljc`, `server/src-prod/prod.cljc`) pass each incoming ring-request into `electric-boot`, which wraps `Main`. On the client, `electric-boot` is called symmetrically with no request. The first thing `Main` does is fetch translations from the server and push them into the client-side atom so subsequent components can call `(t :nav/chat)` etc.

*Authentication integration.* User email and preferred language are already on the ring-request when `Main` runs (middleware resolved the JWT cookie — see §4.2). Components read these via `(e/server (:user/email e/http-request))` (see `auth/ui.cljc:12`).

*Top-level navigation.* `Main` uses the `routed-tabs` macro (`ui/components.cljc:85`) to declare top-level tabs: **Chat** (the Playground), **Datasets** (the pipeline operator console), **Config**, **Import** (data-source import). The active tab is resolved from the URL via `routing/UseRoutedTab`; changing tabs calls `pushState`, so the browser back/forward buttons work as expected.

*Debug UI modes.* The `debug-ui` query param is normalized by `normalize-debug-ui-mode` (`main.cljc:29-35`) to one of `bare` / `shell` / `playground` / `config` / `import` / `full` (default). The `case` at `main.cljc:73-126` short-circuits rendering to a single sub-app. A `DebugUiNotice` bar (`main.cljc:37`) shows the active mode when not `full`. This lets developers and operators load isolated surfaces without paying for the rest of the UI to render.

*Per-domain surfaces.* Each domain owns a public Electric component that `ui/main` composes:

- **Config UI** (`config/ui.cljc` + `config/ui/*`) — tree view with diagnostics, inheritance editor (`config/ui/inheritance.cljc`), permissions matrix (`config/ui/permissions.cljc`), audit log viewer (`config/ui/audit.cljc`), API-key management (`config/ui/api_keys.cljc`).
- **Pipeline UI** (`pipeline/ui/pipelines.cljc`, `pipeline/ui/executions.cljc`) — operator console to create/edit pipelines, trigger runs, monitor status.
- **Docs UI** (`docs/ui.cljc`) — data-source import tabs (Kudos, Folder, Website, EPiServer), each triggering the matching ingestion pipeline.
- **Skills UI** (`skills/ui.cljc`) — browse registered skills and graph templates.
- **RAG UI** (`rag/ui/knowledge.cljc`, `rag/ui/status.cljc`) — knowledge search and Typesense status.
- **Playground UI** (`playground/ui.cljc` + `playground/ui/*`) — chat and deep observability; §6.3.

*Shared patterns.* The `StatusBar` at the top composes Typesense health (`rag/ui/status.cljc:9`, `vibed-get-typesense-health`) and the user profile menu (`auth/ui.cljc`). Side-effecting event handlers use the `e/Token` idiom documented in `CLAUDE.md`. Turn-based state mutations (e.g., "create conversation then select it") use the pending-signal pattern, also in `CLAUDE.md`.

**Design objectives (as implemented).**
- **Colocation.** UI lives next to the domain it renders. `ui/main` imports a component from each domain rather than duplicating domain logic.
- **URL-backed state.** Tabs and filters sync to the URL. Operators can bookmark, share, and navigate.
- **Debug isolation modes.** Short-circuit rendering for single sub-apps keeps iteration tight during development.
- **Electric transparency.** The server/client split is inline (`e/client` / `e/server`), so cross-boundary data flows look local.

**Integrates with.** Every domain (each contributes a component), auth (user identity on ring-request), config + data.db (UI state reads), i18n, the API routes (admin-side calls from within Electric components), Playground (§6.3).

**Known rough edges.**
- `ui/main.cljc` must import every domain UI explicitly; adding a domain requires editing this file.
- There is no "UI module" — the admin UI is a dozen files scattered across domain folders. The file-path index (§10) is the navigation aid.
- Debug-UI modes are an ad-hoc enum; adding a new mode requires two edits (the normalizer + the `case`).

### 6.3 Playground & observability

**Purpose.** The main interactive chat surface in the admin UI. The Playground drives exactly the same skill graph the public `/api/mcp` does, but adds **deep observability**: live event streams, per-stage diagnostics, retrieved-evidence tables with rerank scores, citation drilldowns, and a tool-call timeline for agent runs. Three view modes (Focused, Detailed, Classic) provide progressive disclosure. Conversations are persisted as a tree with branching and regeneration.

**Entry points.**
- `digdir.playground.ui/PlaygroundChat` — top-level component (`server/src/digdir/playground/ui.cljc:1796`).
- `digdir.playground.core/!playground-executions` — per-execution state atom, keyed by execution-id (`server/src/digdir/playground/core.cljc:21`).
- `digdir.playground.ui/!playground-chat-state` — client-side chat state: selected agent/tenant/dataset, `:view-mode` (`:detailed` / `:focused` / `:classic`), active branch, pending signals (`server/src/digdir/playground/ui.cljc:185,197`).
- `digdir.playground.core/execute-playground-chat-pipeline` — turn kick-off (`server/src/digdir/playground/core.cljc:594`).
- `digdir.playground.live_status_scheduler/schedule-live-status-summary!` — sidecar job for mid-turn status synthesis (`server/src/digdir/playground/live_status_scheduler.clj:74`).

**Key files.**
- **State + events** — `playground/core.cljc` (execution state, skill-graph orchestration, event emission), `playground/chat_session.cljc` (headless chat state machine normalizing events into messages + diagnostics), `playground/diagnostics.cljc` (event/trace normalization).
- **Derived views** — `playground/citations.cljc` (citation parsing, source metadata), `playground/timeline.cljc` (tool-call timeline flatten from agent trace), `playground/status.cljc` (live-status snapshot + synthesis helpers).
- **UI composition** — `playground/ui.cljc` (top chat component, config panel, message rendering), `playground/ui/common.cljc` (server-side data loaders for agent scopes, dataset options), `playground/ui/components.cljc` (markdown rendering, source chips, diagnostic badges).
- **Observability panes** — `playground/ui/observability.cljc` (dispatcher), `playground/ui/observability/live.cljc` (older live component), `playground/ui/observability/live_next.cljc` (newer unified view for live + completed), `playground/ui/observability/panels.cljc` (status badges, agent callouts), `playground/ui/observability/results.cljc` (evidence + source tables).
- **Ancillary** — `playground/live_status_scheduler.clj` (best-effort sidecar), `playground/ui/styles.cljc`.

**How it works.**

1. **Turn initiation.** The chat widget sets `:pending-send {:query …}` in `!playground-chat-state` (`playground/ui.cljc:1226,1249`). A reactive block on the server picks it up, clears the flag, and calls `execute-playground-chat-pipeline`. This is the pending-signal pattern from `CLAUDE.md` — turn dispatch is server-side to avoid races between the client input and the reactive view.
2. **Execution setup.** `execute-playground-chat-pipeline` (`playground/core.cljc:594`) creates an execution-id, persists the user message into the conversation tree with branch tracking, initializes the execution atom with `:status :running` and `:events []`, resolves the full runtime context (agent policy, dataset config, runtime skill config), and returns immediately. The skill graph runs asynchronously in a future (`playground/core.cljc:776`).
3. **Streaming events.** The skill graph calls the runner with a progress callback. The callback converts graph events (from `digdir.skills.events`) into canonical execution events (`:stage/started`, `:response/chunk`, `:response/finalized`, `:agent/thinking`, `:agent/turn-completed`, …) and appends them to the execution's `:events` vector via `emit-execution-event!` (`playground/core.cljc:120`).
4. **Progressive render.** The observability view subscribes to the execution atom via `e/watch`. `chat_session.cljc` consumes events into a view-model (running flag, latest stage label, running assistant text, diagnostics map). The UI dispatches on `:view-mode` to pick the observability panel.
5. **Post-run enrichment.** When the graph completes, full diagnostics land in `execution[:results]` via `update-execution-results!` (`playground/core.cljc:510-576`): retrieved chunks grouped by search type, rerank scores, per-chunk retrieval-boost labels, search history, read operations, agent trace. `observability/live_next.cljc` normalizes these into a single view-model that renders the same way for live and completed runs.
6. **Citation drilldown.** `citations.cljc` derives titles and heading breadcrumbs from chunk metadata; `bind-citations` joins inline `[N]` markers in the response to resolved source info (`playground/citations.cljc:39-80`).
7. **Live-status sidecar.** `live_status_scheduler.clj` runs a best-effort worker: each event emission schedules a synthesis job (`schedule-live-status-summary!`) that snapshots the execution state and publishes a summary (e.g. "currently reading chunk N", "tool call in progress") to `execution[:live-status]`. Stale snapshots are dropped; failures swallowed. This keeps the UI alive during long LLM calls.

*View modes.*
- **Focused** (`:focused`) — minimal: response text, status badge, one-line retrieval-filters summary. Daily Q&A.
- **Detailed** (`:detailed`, default) — adds the event timeline, search-results pane with hit counts and rerank status, used-chunks table with retrieval-boost labels, and a tool-call timeline for agent runs.
- **Classic** (`:classic`) — legacy layout kept for parity; slated for deprecation once Detailed stabilizes.

**Design objectives (as implemented).**
- **Same pipeline as production.** `skills.api/run-skill-graph` is called with the same skill-params resolution as `/api/mcp`, so what an operator sees here is what external clients get.
- **Events-first observability.** The UI subscribes to structured events rather than scraping logs — a change to a step's internals doesn't break the dashboard as long as it still emits the same events.
- **Pending signals for turns.** Client declares intent, server performs I/O, state updates atomically. Avoids races between widget input and reactive view.
- **Progressive disclosure.** Focused for daily use; Detailed for tuning; Classic while the new views finish maturing.

**Integrates with.** Skills (`skills.api` is the graph runner; `digdir.skills.events` produces the event stream), RAG core (retrieval/rerank/synthesis produce evidence and rerank scores surfaced in tables), agents (agent policy + trace drive the tool-call timeline), config (per-user UI preferences are in-memory today), data.db (conversation tree + branching), admin UI root (§6.2).

**Known rough edges.**
- **`live.cljc` and `live_next.cljc` coexist.** `live_next` is the newer unified view; callers haven't fully migrated. Expect duplicate logic between the two.
- **View-mode preference is in-memory.** Not persisted to DB, lost across reloads.
- **Retrieval-filter normalization is best-effort.** `diagnostics.cljc/collect-retrieval-filters` pulls filter explanations from search attribution and agent trace; complex agent runs with multiple filter passes may show incomplete attribution.
- **Playground conversations are persisted separately** from `/api/mcp` conversations (different schema entities, different ownership model — internal user id vs. opaque `X-User-Id`).

## 7. Operations

### 7.1 First-run setup

`bb setup` walks through the full bootstrap — see §5.6 for the step-by-step. The finer-grained helpers (`bb bootstrap-platform-tree`, `bootstrap-runtime-tree`, `bootstrap-dataset-tree`, `bootstrap-deployment-target-topology`) let operators reseed one tree at a time without re-running the interactive wizard.

### 7.2 Environment variables

| Variable | Purpose | Read at |
| --- | --- | --- |
| `CONFIG_MASTER_KEY` | Master key for AES-256-GCM encryption of secret config values. Presence also toggles DB-backed config mode. | `server/src/digdir/config/core.clj:39,81` |
| `JWT_SECRET` | HMAC key for signing the admin UI's JWT cookie. | `server/src/digdir/config/core.clj:40`, `digdir.auth.core` |
| `ADH_POSTGRES_URL` / `_USER` / `_PWD` / `_TABLE` | Remote Postgres backend for Datahike. | `server/src/digdir/config/core.clj:35-38` |
| `DATAHIKE_FILE_PATH` | Local-file Datahike backend (preferred for dev). Mutually exclusive with the Postgres vars. | `server/src/digdir/config/core.clj:34,42-54` |
| `TYPESENSE_API_KEY_ADMIN` | Typesense admin key. Written into each imported tenant's config by `bb migration-import`; read back via `digdir.rag.typesense` from config, never from the env directly. | `server/src/digdir/config/env_bridge.clj`, `server/src/digdir/rag/typesense.clj` |
| `TYPESENSE_API_KEY` | Declared, read by nothing. | — |
| `AZURE_OPENAI_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | LLM provider keys, picked up by the matching provider namespace. | `digdir.llm.*` |
| `OPENAI_API_ENDPOINT` | Base URL (including `/v1`) for the OpenAI-compatible path — i.e. whenever `services.azure-openai.use-azure-openai-api` is false, which is how a local OpenAI-compatible server is reached (see `docs/onboarding.md` §4a, which also records which of them has actually been run). Unset falls back to `https://api.openai.com/v1`. Cannot be set through config; read from the environment per call. | `server/src/digdir/llm/client.clj` (`openai-compat-completion`) |
| `RAG_DEBUG_API_KEY` | Shared-secret header (`X-Debug-Api-Key`) for `/api/debug/*`. | `server/src/digdir/api/routes/endpoints.clj:708-726` |
| `RAG_API_TEST_KEY` | API key used by the integration test suite. | `bb integration-test` in `bb.edn:974` |
| `ADMIN_USER_EMAILS` | Optional — seed admin user list processed by the legacy-auth migration at setup time. | `digdir.auth.migration`, invoked from `digdir.setup.workflow` |
| `TENANT` / `DATASET_CONFIG_KEY` | **Are** read, in two places. `digdir.mcp.tools/env-default-scope` supplies the fallback `(tenant, dataset-config-key)` for a `tools/call` that carries neither — a call with no scope from any source is refused with a message naming these two variables. `digdir.e2e.seed` uses `TENANT` to pick which tenant the `AZURE_OPENAI_*` boot seed writes to. Scope from the tool arguments still wins; these are the fallback, not the source of truth. | `server/src/digdir/mcp/tools.clj:509`, `server/src/digdir/e2e/seed.clj:122` |
| `ENV` | Declared in `mise.toml` for convenience but **not** read by the server. | — |
| `LOGLEVEL` | Consumed by Telemere via Logback on start. | `mise.toml:30` |

Secrets should not be committed. Local dev typically uses `mise.local.toml` (gitignored) or shell profile; prod reads `ADH_POSTGRES_PWD`, `ADH_POSTGRES_URL`, `ADH_POSTGRES_USER`, and `KAMAL_REGISTRY_PASSWORD` from `.kamal/secrets*` per `deploy.yml:47-51`.

### 7.3 Running dev and prod

Covered in §3.3–3.4. Dev: `bb dev` (port 8081). Prod: `clj -X:build:prod uberjar` then `clj -M:prod -m prod` (port 8080).

### 7.4 Deployment

Covered in §3.5. `bb deploy-server <dest>` runs `kamal deploy`. There are no accessory log/exec tasks: the five that existed pointed at an `accessories.yml` that is not in the repo and were removed (issue #9) — see §3.5.

### 7.5 Diagnostics and admin CLI tasks

`bb.edn` exposes several read-only and introspection tasks that are handy during triage. None mutate state except the config-write tasks (`config-set`, `bootstrap-*`).

- **Config introspection.** `bb config-get <path> <tenant> <config-root> <config-key>` reads a resolved value with trace. `bb config-set <path> <edn> <tenant> <config-root> <config-key>` writes. `bb diagnose-db-config` compares the current Datahike bootstrap config against the stored store-identity, with remediation guidance if they mismatch (`bb.edn:915-972`).
- **Chunk / retrieval / rerank.** `bb chunk-find`, `bb retrieve-debug`, `bb rerank-debug` give deterministic views of what the RAG pipeline sees for a query (`bb.edn:442,462,482`).
- **Quality benchmarks.** `bb rerank-benchmark`, `bb rerank-benchmark-report`, `bb rerank-language-benchmark`, `bb agent-budget-benchmark`, `bb agent-budget-report` drive evaluation suites against a configured dataset.
- **Ingestion.** `bb import-kudos`, `bb import-kudos-doc <doc-id>` trigger ingestion outside the UI. `bb backfill-schema` fills missing chunk/document fields on legacy collections.
- **Migration.** `bb migration-export`, `bb migration-import` — see §5.5.
- **Namespace ops.** `bb namespace-audit`, `bb namespace-inventory`, `bb namespace-first-split`, `bb namespace-extract` — developer helpers for breaking up large namespaces.
- **Clear config DB.** `bb clear-config-db --confirm` — destructive; used with care.

### 7.6 Persistent state and caches

- **Postgres** — the config tree and all runtime state. Bind-mounted volume or managed service.
- **Typesense** — `documents` / `chunks` / `phrases` collections per pipeline.
- **`server/cache/`** — search-phrase cache, chunk content caches. File-backed. Survives restarts.
- **`server/state/`** — duratom-backed atoms (pipeline execution state, rate-limit counters). Survives restarts.
- **`server/logs/`** — Logback-rolled Telemere output.
- **`/opt/admin/cache`** in prod — bind-mounted into `/app/cache` per `deploy.yml:66-67`.

### 7.7 Logging and telemetry

Telemere 1.2.0 with a Logback adapter. `digdir.util.logging/truncate-for-logging` bounds string length, collection size, and depth before emission so a large retrieval result doesn't flood the log. `log-info!` / `log-warn!` / `log-error!` / `log-debug!` are the common macros (`server/src/digdir/util/logging.clj`). The dev and prod `logback.xml` files live in `src-dev/` and `src-prod/`. `bb logs-admin <dest>` tails the container log in production.

## 8. Testing

### 8.1 Runner and mode

- `bb test` — runs the full unit suite via `cognitect.test-runner` under `-M:test` (`bb.edn:899-901`). RCF is enabled (`-Dhyperfiddle.rcf.enable=true`, `server/deps.edn:90`). The regex excludes `digdir.tools.diagnostics-test` because it needs the `:diagnostics` alias.
- `bb test-config` — a targeted subset covering config resolution (`bb.edn:907-913`): `digdir.config.{accessor,db,ops,ui,permissions}-test`.
- `bb test-diagnostics` — runs `digdir.tools.diagnostics-test` under the `:diagnostics` alias, which has `src-dev` on the classpath (`bb.edn:903-905`).
- `bb lint` — `clj-kondo` (`bb.edn:1060`).
- `bb integration-test <env>` — hits a live deployment using `RAG_API_BASE_URL` + `RAG_API_TEST_KEY`; runs `digdir.api.integration-test` (`bb.edn:974-981`).
- `bb rerank-eval`, `rerank-isolation-eval`, `auto-filter-eval`, `budget-gate-test` — evaluation suites for specific subsystems (`bb.edn:983-997`).

### 8.2 Test layout

Tests live under `server/test/digdir/` mirroring the production namespace tree. 77 `*_test.clj`/`.cljc` files distributed roughly:

| Area | Files |
| --- | --- |
| `rag/*` | 12 |
| `docs/*` (incl. `docs/pipeline/*`) | 14 |
| `playground/*` | 7 |
| `config/*` | 7 |
| `skills/*` (incl. `builtin/*`, `builtin/agent/*`, `graph/*`) | 15 |
| `pipeline/*` | 5 |
| `api/*` | 5 |
| `agents/*` | 3 |
| `tools`, `util`, `ui`, `llm`, `import_export`, `data` | 1–2 each |
| `rag/skills/*` | 2 |

Fixtures under `server/test/fixtures/` — `playground/`, `agent/`, `synthesis/`, and rerank fixture bundles.

### 8.3 RCF pattern

Many namespaces use Hyperfiddle's RCF for **inline tests** directly next to the implementation, using the `tests` macro + `:=` assertions. RCF is disabled by default (macros elide) and turned on with `-Dhyperfiddle.rcf.enable=true`. Details in `server/TESTING.md`, but note that some example references in that file (e.g., `agent.graph.tree`, `agent.graph.test_runner`) predate the current `digdir.*` namespacing.

## 9. Glossary

Grounded definitions for terms used throughout this document. Each term has a canonical definition point in the code.

- **Tenant.** The organization or product that owns a slice of configuration. Supplied per request (`server/src/digdir/config/core.clj:13-14,83-86`). The server is stateless with respect to tenant.
- **Config root.** One of `:platform`, `:runtime`, `:dataset` — the three top-level partitions of config definitions (`server/src/digdir/config/schema.clj:68`). §4.1.
- **Config node / tree.** A tenant-scoped inheritance tree inside a root; each node has at most one parent. Resolution walks parent pointers until a value is found. `server/src/digdir/config/db.clj`.
- **Dataset.** A named input corpus for the RAG system, stored as a config entity with its own sub-tree under the `:dataset` root. Used by the RAG pipeline and the Playground. `server/src/digdir/pipeline/core.clj:60` (`get-dataset`).
- **Dataset ref.** The minimal identifier for a dataset: `{:tenant … :dataset-config-key …}`. Canonicalized by `digdir.execution.scope/normalize-dataset-ref` (`server/src/digdir/execution/scope.clj:8`).
- **Pipeline.** A concrete ingestion materialization of a dataset — source, extraction, chunking, phrase generation, Typesense collection names. §5.1. Live under `digdir.pipeline.*`.
- **Materialization.** The binding contract between a dataset's config and its deployment target's Typesense collections. `server/src/digdir/pipeline/materialization.clj:75` (`target-materialization-contract`).
- **Collection (Typesense).** A searchable document store — three per pipeline (`documents`, `chunks`, `phrases`). Names are `{prefix}_{type}_{hash}` where `hash` is a deterministic function of the output-affecting config (`server/src/digdir/pipeline/collections.clj:27`).
- **Chunk.** A piece of a source document, produced by markdown-header splitting during ingestion (`server/src/digdir/rag/chunking.clj`). Identified by content hash (`server/src/digdir/docs/pipeline/core.clj:17-21`).
- **Phrase (search phrase).** An LLM-generated keyword summary of a chunk used to improve BM25 recall (`server/src/digdir/docs/pipeline/search_phrases.clj`).
- **Skill.** A named, schema-typed unit of work. Metadata schema at `server/src/digdir/rag/skills/core.clj`; registration via `digdir.skills.init/initialize!`. §5.3.
- **Skill graph.** A DAG of skills with explicit input-to-output wiring. Templates live in `server/src/digdir/skills/templates/builtin.clj`; runner is `digdir.skills.graph.runner` at `:185`.
- **Agent (definition).** A durable entity in the config DB (`server/src/digdir/agents/core.clj:115,182`) describing which skill graphs, dataset scopes, and guardrails are allowed.
- **Agent (execution).** The ReAct-loop skill `digdir.skills.builtin.agent.*` (`server/src/digdir/skills/builtin/agent/core.clj:634`) that the system dispatches into when a request targets an agent. §5.4.
- **Workspace.** Per-turn state atom used by the agent loop: chunks, queries, budget counters, evidence plan (`server/src/digdir/skills/builtin/agent/workspace.clj`).
- **Read-signals.** LLM evaluator that scores per-claim evidence after a `read_chunks` tool call (`server/src/digdir/skills/builtin/agent/read_signals.clj`). §5.4.
- **Sufficiency gate.** LLM evaluator that decides whether the agent has enough evidence to finalize (`server/src/digdir/skills/builtin/agent/sufficiency.clj`). §5.4.
- **API key.** A long-lived credential for `/api/*`. Carries `:dataset-scopes`, `:agent-refs`, `:allowed-config-keys`, `:scopes`. **Each grant list means UNRESTRICTED when empty** — a grant narrows access rather than conferring it (#349). Validated by `wrap-api-key-auth` (`server/src/digdir/api/routes/endpoints.clj:660`). §4.2 and §6.1.
- **Permission.** An ABAC row in the config DB matching user attributes (role, team) against definition attributes (service, sensitivity, function) plus actions (`:read`/`:write`). `server/src/digdir/config/permissions.clj`. §4.2.
- **Execution scope / context.** Fully resolved per-request view — tenant, dataset, agent, merged runtime config, traces — assembled by `digdir.api.context/resolve-request-execution-context!` at `:475`. §4.4.
- **Auto-filter.** LLM-free structured-filter detection from the query text (orgs, years). `server/src/digdir/rag/auto_filter.clj`. §5.2.
- **Query relaxation.** LLM-driven query expansion into search-phrase variants via tool use. `server/src/digdir/rag/query_relaxation.clj:54`. §5.2.
- **Rerank.** ColBERT-based semantic reordering of top-K merged hits. `server/src/digdir/rag/rerank.clj`. §5.2.
- **Pending signal.** The `CLAUDE.md` pattern where a UI button sets an intent flag on state and a reactive block performs the I/O. Used heavily in the Playground (§6.3) to avoid races.
- **`e/Token`.** Electric idiom for scoping side effects to a single event. `CLAUDE.md` is the reference.
- **View mode.** Playground UI setting — `:focused` / `:detailed` / `:classic` — controlling observability density. §6.3.

## 10. Appendix: file-path index

Every production Clojure source file under `server/src/digdir/`, grouped by module and with a one-line intent. Test files (`*_test.clj`) are covered in [§8](#8-testing). Paths are relative to the repository root. 163 files.

Groups are ordered to match the document's section order: cross-cutting concerns (§4), core domain (§5), then external surfaces (§6).

### Cross-cutting (§4)

#### Configuration (`digdir.config`)

- `server/src/digdir/config/accessor.clj` — primary config accessor API with automatic decryption and DB-backed resolution
- `server/src/digdir/config/api_keys.clj` — API key generation, storage, and management for RAG API authentication
- `server/src/digdir/config/audit.clj` — audit logging for config changes with before/after values
- `server/src/digdir/config/core.clj` — configuration management using Datahike with bootstrap from environment
- `server/src/digdir/config/crypto.clj` — AES-256-GCM encryption utilities for config secrets
- `server/src/digdir/config/db.clj` — database operations for node-based, tenant-local configuration trees
- `server/src/digdir/config/permissions.clj` — ABAC permission system evaluating service, sensitivity, and function attributes
- `server/src/digdir/config/schema.clj` — Datahike schema definitions for config definitions and nodes
- `server/src/digdir/config/ui.cljc` — configuration management UI with tree inspection, editing, and audit viewing
- `server/src/digdir/config/validator.clj` — Clojure spec validation for configuration values
- `server/src/digdir/config/ops/bootstrap.clj` — bootstrap helpers for initial root and node configuration
- `server/src/digdir/config/ops/clone.clj` — tenant cloning helpers for V2 config model
- `server/src/digdir/config/ops/materialization.clj` — materialization contract reconciliation for pipeline datasets
- `server/src/digdir/config/ops/retirement.clj` — tenant retirement and config/playground cleanup
- `server/src/digdir/config/ops/sync.clj` — export and import operations for the V2 config model
- `server/src/digdir/config/ops/topology.clj` — deployment topology and target definitions
- `server/src/digdir/config/ops/util.clj` — serialization, encryption metadata, and pipeline cleaning utilities
- `server/src/digdir/config/ui/api_keys.cljc` — API key management UI components
- `server/src/digdir/config/ui/audit.cljc` — audit log viewer UI component for configuration changes
- `server/src/digdir/config/ui/common.cljc` — common helpers and server-side data functions for config UI
- `server/src/digdir/config/ui/inheritance.cljc` — interactive inheritance editor for config value resolution
- `server/src/digdir/config/ui/permissions.cljc` — permission management UI for configuration access control
- `server/src/digdir/config/ui/styles.cljc` — CSS styles for the V2 config management UI

#### Auth (`digdir.auth`)

- `server/src/digdir/auth/cookies.clj` — HTTP-only secure cookie utilities for JWT token storage
- `server/src/digdir/auth/core.clj` — authentication core with JWT signing and user session management
- `server/src/digdir/auth/migration.clj` — migration utilities from legacy auth to permissions-based auth
- `server/src/digdir/auth/ui.cljc` — user profile menu and language selection UI component
- `server/src/digdir/auth/views.clj` — HTML view templates for authentication and error pages

#### Data layer (`digdir.data`)

- `server/src/digdir/data/background_worker.clj` — asynchronous transaction processor for non-blocking Datahike operations
- `server/src/digdir/data/db.cljc` — Datahike schema and database connection management

#### Execution (`digdir.execution`)

- `server/src/digdir/execution/scope.clj` — helpers for canonical dataset and execution scope normalization

#### LLM integrations (`digdir.llm`)

- `server/src/digdir/llm/anthropic.cljc` — Anthropic API chat completion client
- `server/src/digdir/llm/kudos.clj` — Kudos knowledge base API client with retry and pagination; one implementation serving both the production and preprod deployments via `kudos/prod` / `kudos/preprod` profiles
- `server/src/digdir/llm/marker.clj` — wrapper for the Marker PDF-to-markdown service API
- `server/src/digdir/llm/openai.cljc` — OpenAI GPT chat completion streaming client
- `server/src/digdir/llm/prompt_fragments.clj` — reusable prompt fragments for LLM-backed skills
- `server/src/digdir/llm/structured_eval.clj` — shared helpers for JSON-output LLM evaluations with fallback

#### Utilities (`digdir.util`)

- `server/src/digdir/util/core.cljc` — EDN parsing and file-based persistent atoms with time literal support
- `server/src/digdir/util/logging.clj` — safe and efficient logging utilities with Telemere truncation
- `server/src/digdir/util/ui.cljc` — shared utility functions for the UI layer

#### Tools (`digdir.tools`)

- `server/src/digdir/tools/namespace_decomposition.clj` — static analysis tool for namespace form extraction

#### Root namespaces (`digdir`)

- `server/src/digdir/i18n.cljc` — internationalization support loading translations from `resources/i18n/base.edn`
- `server/src/digdir/setup.clj` — interactive setup wizard facade; delegates to `digdir.setup.*` submodules

### Core domain (§5)

#### Ingestion pipelines (`digdir.pipeline`)

- `server/src/digdir/pipeline/collections.clj` — generate Typesense collection names and hashes for versioning
- `server/src/digdir/pipeline/core.clj` — pipeline CRUD operations and ID parsing
- `server/src/digdir/pipeline/executor.clj` — orchestrate pipeline execution and track status
- `server/src/digdir/pipeline/loaders/episerver.clj` — EPiServer XML document loader adapter
- `server/src/digdir/pipeline/loaders/folder.clj` — folder-based markdown loader adapter
- `server/src/digdir/pipeline/loaders/kudos.clj` — Kudos knowledge base loader adapter
- `server/src/digdir/pipeline/loaders/website.clj` — website sitemap-based loader adapter
- `server/src/digdir/pipeline/materialization.clj` — deployment-target pipeline config contracts
- `server/src/digdir/pipeline/model.clj` — pure pipeline identity and source configuration
- `server/src/digdir/pipeline/ui/executions.cljc` — pipeline execution monitoring UI
- `server/src/digdir/pipeline/ui/pipelines.cljc` — operator console for pipeline management

#### Document loaders (`digdir.docs`)

- `server/src/digdir/docs/episerver.clj` — EPiServer XML export ingestion (parse, chunk, phrase, store)
- `server/src/digdir/docs/folder.clj` — recursive markdown directory ingestion pipeline
- `server/src/digdir/docs/loader.clj` — Kudos document loader with telemetry aggregation (legacy, heavy)
- `server/src/digdir/docs/pipeline/core.clj` — shared utilities (hashing, composition, telemetry)
- `server/src/digdir/docs/pipeline/orchestration.clj` — parallel document and storage flows
- `server/src/digdir/docs/pipeline/protocol.clj` — `DocumentSource` protocol for extensible sources
- `server/src/digdir/docs/pipeline/search_phrases.clj` — LLM-based phrase generation and caching
- `server/src/digdir/docs/pipeline/storage.clj` — Typesense storage operations and collection IDs
- `server/src/digdir/docs/pipeline/telemetry.clj` — centralized telemetry state and handlers
- `server/src/digdir/docs/ui.cljc` — data source import UI components
- `server/src/digdir/docs/website.clj` — sitemap-based markdown ingestion pipeline

#### Retrieval & RAG (`digdir.rag`)

- `server/src/digdir/rag/auto_filter.clj` — auto-detect org/year filters for retrieval
- `server/src/digdir/rag/chunking.clj` — markdown header-based chunk splitting
- `server/src/digdir/rag/core.cljc` — RAG facade delegating to specialized namespaces
- `server/src/digdir/rag/filters.cljc` — pure Typesense filter serialization helpers
- `server/src/digdir/rag/formatting.cljc` — formatting for headers, metadata, and truncation
- `server/src/digdir/rag/merge.cljc` — search result merging and rank normalization
- `server/src/digdir/rag/query_relaxation.clj` — LLM query expansion via tool use
- `server/src/digdir/rag/rerank.clj` — ColBERT semantic reranking orchestration
- `server/src/digdir/rag/retrieval.clj` — Typesense multi-strategy search and faceting
- `server/src/digdir/rag/skills/core.clj` — skill protocol, I/O schemas, and abstractions (consumed by `digdir.skills.*`)
- `server/src/digdir/rag/synthesis.clj` — LLM response generation with system prompts
- `server/src/digdir/rag/typesense.clj` — shared Typesense connection utilities
- `server/src/digdir/rag/typesense_admin.clj` — paginated search helper for bulk operations
- `server/src/digdir/rag/ui/knowledge.cljc` — knowledge base search and browse UI
- `server/src/digdir/rag/ui/status.cljc` — Typesense health and metrics display

#### Skills (`digdir.skills`)

- `server/src/digdir/skills/api.clj` — public API for skill and graph invocation
- `server/src/digdir/skills/context.clj` — skill execution context and service resolution
- `server/src/digdir/skills/events.cljc` — shared event and stage label helpers
- `server/src/digdir/skills/init.clj` — skill system initialization and registration
- `server/src/digdir/skills/ui.cljc` — skills management UI for operator console
- `server/src/digdir/skills/builtin/agent.clj` — agent skill proxy stub
- `server/src/digdir/skills/builtin/entity_extraction.clj` — extract entities from text via LLM
- `server/src/digdir/skills/builtin/fact_checking.clj` — verify claims against evidence
- `server/src/digdir/skills/builtin/graph_builder.clj` — dynamically create skill graphs from tasks
- `server/src/digdir/skills/builtin/multi_retrieval.clj` — multi-query search with result merging
- `server/src/digdir/skills/builtin/query_planner.clj` — LLM query expansion into phrases
- `server/src/digdir/skills/builtin/rerank.clj` — semantic reranking wrapper
- `server/src/digdir/skills/builtin/retrieval.clj` — multi-strategy document retrieval
- `server/src/digdir/skills/builtin/summarization.clj` — LLM-based content summarization
- `server/src/digdir/skills/builtin/synthesis.clj` — LLM response generation
- `server/src/digdir/skills/builtin/agent/core.clj` — agent skill entry points and registration
- `server/src/digdir/skills/builtin/agent/loop.clj` — ReAct-style agentic loop orchestration
- `server/src/digdir/skills/builtin/agent/read_signals.clj` — LLM read-time evidence evaluation
- `server/src/digdir/skills/builtin/agent/sufficiency.clj` — sufficiency-gate evidence adequacy judgment
- `server/src/digdir/skills/builtin/agent/tools.clj` — agent tool definitions and bridges
- `server/src/digdir/skills/builtin/agent/workspace.clj` — agent workspace state and budgeting
- `server/src/digdir/skills/graph/optimizer.clj` — graph optimization and dependency analysis
- `server/src/digdir/skills/graph/runner.clj` — skill graph execution engine
- `server/src/digdir/skills/graph/schema.clj` — Malli schemas for graph validation
- `server/src/digdir/skills/graph/trace.clj` — trace-file writer for graph-runner runs (full, untruncated per-step I/O)
- `server/src/digdir/skills/templates/builtin.clj` — built-in skill graph definitions
- `server/src/digdir/skills/templates/core.clj` — template registry and instantiation

#### Agents (`digdir.agents`)

- `server/src/digdir/agents/core.clj` — agent model, validation, and built-in definitions
- `server/src/digdir/agents/db.clj` — persistent agent definitions in the config DB
- `server/src/digdir/agents/policy.clj` — policy helpers for resolved agent definitions

#### Demo scenarios (`digdir.demo`)

- `server/src/digdir/demo/altinn_authoring.clj` — S7 Altinn Authoring Assistant demo scenario (agent + optional/required `propose_outline` tool)
- `server/src/digdir/demo/altinn_release_notes.clj` — S2 Altinn Release-Notes Cross-Check demo scenario (entity extraction → multi-retrieval → structured TODO list)
- `server/src/digdir/demo/altinn_translation_drift.clj` — S5 Altinn NB/EN Translation-Drift Detector demo scenario (bilingual retrieval + divergence report)

#### Import / Export (`digdir.import_export`)

- `server/src/digdir/import_export/canonical/system.clj` — canonical steady-state system export normalization; legacy config path migration
- `server/src/digdir/import_export/entities/agents.clj` — agent import/export helpers
- `server/src/digdir/import_export/entities/api_keys.clj` — API key import/export with dataset scopes, agent refs, and allowed config keys
- `server/src/digdir/import_export/entities/config.clj` — config data import/export with master-key and encryption handling
- `server/src/digdir/import_export/entities/conversations.clj` — conversation and message import/export with metadata preservation
- `server/src/digdir/import_export/entities/folders.clj` — folder import/export with conflict resolution
- `server/src/digdir/import_export/entities/users.clj` — user import/export including email, language preference, and permissions
- `server/src/digdir/import_export/export.clj` — system export coordination; orchestrates entity export and canonicalization
- `server/src/digdir/import_export/files.clj` — thin JSON file wrappers for import/export edges; handles keyword/symbol encoding
- `server/src/digdir/import_export/import.clj` — system import coordination; runs preview/apply phases against ordered entity registry
- `server/src/digdir/import_export/model.clj` — canonical import/export model: version constants, envelope helpers, ISO timestamp generation
- `server/src/digdir/import_export/registry.clj` — shared entity registry with export/preview/apply functions in dependency order
- `server/src/digdir/import_export/report.clj` — shared import/export reporting; item counts and conflict resolution summaries
- `server/src/digdir/import_export/system.clj` — public import/export entry points for full system migration

#### Setup (`digdir.setup`)

- `server/src/digdir/setup/common.clj` — shared setup helpers: prompts, env-var checks, DB validation, global config getters
- `server/src/digdir/setup/config.clj` — interactive setup for config definitions and service defaults
- `server/src/digdir/setup/workflow.clj` — interactive setup workflow orchestration; delegates to config setup and bootstrapping

(See also `server/src/digdir/setup.clj` under *Root namespaces*.)

### External surfaces (§6)

#### Headless API (`digdir.api`)

- `server/src/digdir/api/context.clj` — unified request and execution-context resolution with dataset/agent/config validation
- `server/src/digdir/api/http.clj` — Ring/Jetty integration, auth middleware, Electric websocket setup, and static file serving
- `server/src/digdir/api/rate_limit.clj` — rate limiting middleware for auth endpoints; tracks failed attempts per IP address
- `server/src/digdir/api/routes.clj` — main routing facade; delegates to handlers, conversations, datasets, and endpoints submodules
- `server/src/digdir/api/routes/conversations.clj` — conversation and user-management handlers for paginated list, CRUD, and user admin operations
- `server/src/digdir/api/routes/datasets.clj` — dataset, materialization, config-resolution, and pipeline-execution routes
- `server/src/digdir/api/routes/endpoints.clj` — debug endpoints, skill endpoints, middleware, and comprehensive router wiring
- `server/src/digdir/api/routes/endpoints/debug.clj` — debug endpoints and low-level request helpers for dataset config and chunk introspection
- `server/src/digdir/api/routes/handlers.clj` — RAG API handlers, conversation management, API key lifecycle, and user-permission handlers
- `server/src/digdir/api/util.clj` — shared utilities: param parsing, JSON normalization, conversation-history transformation, skill config building

#### Admin UI root (`digdir.ui`)

- `server/src/digdir/ui/components.cljc` — reusable UI components: status bar, routed-tabs macro, Electric state watching
- `server/src/digdir/ui/main.cljc` — main Electric entry point; composites playground, config, pipeline, and docs modules
- `server/src/digdir/ui/routing.cljc` — URL-based routing for Electric/Hyperfiddle using browser History API

#### Playground & observability (`digdir.playground`)

- `server/src/digdir/playground/chat_session.cljc` — chat state machine consuming execution events; tracks messages, diagnostics, citations, timeline
- `server/src/digdir/playground/citations.cljc` — citation/source parsing and metadata extraction from execution results
- `server/src/digdir/playground/core.cljc` — backend logic for the Playground: execution state, event tracking, live-status scheduling
- `server/src/digdir/playground/diagnostics.cljc` — event/trace normalization helpers; decision status/action standardization
- `server/src/digdir/playground/live_status_scheduler.clj` — best-effort live-status sidecar job scheduling
- `server/src/digdir/playground/status.cljc` — optional live-status snapshot and synthesis helpers for event display
- `server/src/digdir/playground/timeline.cljc` — tool timeline shaping; extracts search phrases and flattens trace tool calls
- `server/src/digdir/playground/ui.cljc` — UI components for the Playground: chat, dataset selection, agent config, observability
- `server/src/digdir/playground/ui/common.cljc` — common helpers and server-side data loading for Playground UI
- `server/src/digdir/playground/ui/components.cljc` — shared base UI components: markdown rendering, source display, diagnostic badges
- `server/src/digdir/playground/ui/observability.cljc` — observability and diagnostics UI wrapper; composes live and live-next views
- `server/src/digdir/playground/ui/observability/live.cljc` — live execution controls and status components during Playground runs
- `server/src/digdir/playground/ui/observability/live_next.cljc` — next-gen unified observability view; works for live and completed runs
- `server/src/digdir/playground/ui/observability/panels.cljc` — status badges, tag styles, and helper status panels
- `server/src/digdir/playground/ui/observability/results.cljc` — result, evidence, and source tables with compact/full display modes
- `server/src/digdir/playground/ui/styles.cljc` — CSS styles for card, label, input, select, and button elements
