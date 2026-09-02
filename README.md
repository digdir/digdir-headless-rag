
# Digdir RAG as a Service - PROTOTYPE

This repository is a prototype to explore the possibilities for rapid experimentation and development of Retrieval-Augmented Generation (RAG) applications.

> ### This stack is not the productization target
>
> Digdir plans to **migrate to a different tech stack** for productization, so
> this repository is expected to stay prototype-grade for the foreseeable
> future (PI, 2026-08-21). That is a stronger statement than "expect rough
> edges", and it changes the answer to ordinary engineering questions:
>
> - **Deep refactors and legacy retirement have low return.** Code that will be
>   superseded does not repay being made beautiful.
> - **What survives a stack migration is knowledge, not implementation** — the
>   eval methodology, the golden question sets, the corpus findings, the
>   negative results. Those deserve investment the Clojure does not, and they
>   belong in `docs/`, where a migration can carry them, rather than only in
>   code or a branch.
> - **Tests are worth writing where they protect a property that will be
>   carried forward**, and less so where they pin an implementation detail that
>   will not. A test of *what the system must be true of* migrates; a test of
>   *how this namespace happens to do it* does not.
>
> None of this licenses carelessness in what ships: the release path, the auth
> surface and the public API contract are used by real callers now, and a
> prototype that silently returns wrong answers is worse than no prototype.
> It licenses **choosing where the effort goes**.

It provides:

- **A headless HTTP API** for agentic RAG (`/api/mcp`, an MCP server), plus datasets, conversations, and API-key management
- **An admin web UI** for configuration, access control, and document ingestion
- **Document ingestion pipelines** that turn source content into Typesense collections usable by the RAG system

> For an end-to-end explanation of how the system is structured — modules, data flow, and design choices as implemented — see **[`docs/system-overview.md`](docs/system-overview.md)**.

## Documentation map

| Doc | Use it for |
| --- | --- |
| [`docs/system-overview.md`](docs/system-overview.md) | How the system works — modules, data flow, design choices (the spine; everything else links into it) |
| [`docs/onboarding.md`](docs/onboarding.md) | Getting started as a new contributor |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Branch/commit conventions, running checks, PR expectations |
| [`decisions/`](decisions/) | Architecture Decision Records |
| [`docs/runbooks/`](docs/runbooks/) | Operating procedures (config export/import, cutover, etc.) |
| [`server/docs/api/README.md`](server/docs/api/README.md) | API reference (`/api/mcp` and the console API) |

## Repository layout

- **`server/`**: Clojure/Electric application (admin UI + headless API)
- **`server/docs/`**: Documentation (API docs, pipeline architecture)
- **`config/`**: Point-in-time config export/import snapshots (live config is DB-backed, see [Configuration](#configuration))
- **`docs/`**: Design/engineering docs (system overview, onboarding, runbooks)
- **`bb.edn`**: Babashka task runner for common workflows (dev, test, setup, deploy helpers)
- **`mise.toml`**: Toolchain versions + convenience tasks/env


## Just want to run it? Docker is the only prerequisite

```sh
cp .env.example .env
docker compose -f docker-compose.newcomer.yml up --build
```

Brings up the server **and** its Typesense search backend. The admin UI is then
on <http://localhost:8080>. Nothing below this section is needed — no Mise, no
JVM, no Clojure.

**It starts with an empty corpus.** Retrieval finds nothing until a dataset is
materialised, and a query returns `Dataset ref does not resolve to a canonical
dataset runtime node` until then (#434 tracks that error being reported as an
internal error rather than as a missing prerequisite). What a newcomer's starting
corpus should be is an open question — see #328.

The sections below are for **working on** the backend, which needs the toolchain.


## Prerequisites

Recommended: use **Mise** to manage the toolchain (versions are pinned in `mise.toml`).

- Install Mise
- On macOS, install `libyaml` (required by Mise on some systems)

```sh
# macOS
brew install libyaml
```

Then install all tools:

```sh
mise install
```

If you don't use Mise, you'll need:

- **Java** (see `mise.toml`, currently `java = "24"`)
- **Clojure CLI** (`clj` / `clojure`)
- **Babashka** (`bb`) for tasks
- **Node/Yarn** for Electric/Shadow builds

## Quick start (local development)

### 1) Configure environment variables

The repo uses environment variables for secrets and for selecting config mode.
Set these for development in `mise.local.toml` (gitignored) or your shell profile — never in tracked files. **A `.env` file is not read by anything in this repo** (`mise.toml` has no `_.file` directive and no other loader picks one up), so values placed there are silently ignored (#302); `.env.example` is a reference catalog of the variables, not a file to copy into place.

**Config mode**

All runtime configuration is DB-backed (Datahike) — set `CONFIG_MASTER_KEY` to enable it.
Tenant and environment are supplied per request by API clients, not read from env. Two
Datahike backends are supported for the bootstrap connection:

- **Local file** (`DATAHIKE_FILE_PATH`) — no external DB to run; the easiest way to get a
  working dev instance (e.g. `DATAHIKE_FILE_PATH=./local-db/dh_dev_v1`).
- **Remote Postgres** (`ADH_POSTGRES_URL` + `ADH_POSTGRES_USER` + `ADH_POSTGRES_PWD`) — for
  connecting to a shared database.

**Common env vars** (see `mise.toml`; details in [`docs/system-overview.md`](docs/system-overview.md#41-configuration-system)):

- **Required for most real runs**
  - `DATAHIKE_FILE_PATH` (local file backend) — or `ADH_POSTGRES_URL` / `ADH_POSTGRES_USER` / `ADH_POSTGRES_PWD` (Postgres backend)
  - `JWT_SECRET`
  - `CONFIG_MASTER_KEY` (enables DB-backed config)
- **Other configuration settings required** — an LLM provider. Which one runs is
  decided in the config DB by `services.azure-openai.use-azure-openai-api`, and
  **both paths read that same `services.azure-openai.*` family** — the name is
  historical, not a scope:
  - **Azure OpenAI** (`use-azure-openai-api true`, the shipped default) —
    `AZURE_OPENAI_API_KEY`
  - **Any OpenAI-compatible server, including a local one** (`false`) —
    `OPENAI_API_ENDPOINT` *and* `OPENAI_API_KEY`, both read from the
    environment. This is the **no-cloud-credentials path**: a local server on
    your own machine runs the whole path — retrieval, the tool-calling agent
    loop, streaming, synthesis. Verified end to end against **LM Studio**;
    Ollama, vLLM and llama.cpp reach the same client code but were not run.
    See [`docs/onboarding.md` §4a](docs/onboarding.md#4a-run-with-a-local-model--no-cloud-credentials-at-all).
  - `ANTHROPIC_API_KEY` is read only by `digdir.llm.anthropic`, which no other
    namespace currently requires. Neither path above needs it.

The running server reads Typesense connection details (host, TLS, admin key) from
DB-backed config (`server/src/digdir/rag/typesense.clj`), never from the environment
directly. `TYPESENSE_API_KEY_ADMIN` still gets you there: `bb migration-import` writes it
into each imported tenant's config, so setting it before the import is enough
(`server/src/digdir/config/env_bridge.clj`). `TYPESENSE_API_KEY` is read by nothing.

### 2) Run the server (dev)

`bb dev` starts the **backend only** — Jetty plus a REPL, and no Electric
client build (#330):

```sh
bb dev
```

Dev server default is **http://localhost:8081**. It needs no Hyperfiddle
activation token, so it works on a fresh clone.

For UI work with hot reload, run `bb dev-fullstack` instead: that is the
server *plus* the Shadow-CLJS watch build, and it does need the token.

### 3) Open the admin UI

Once running, open:

- `http://localhost:8081`

**The UI needs a client build; `bb dev` does not make one.** Run
`bb build-client` once (~100 s, no activation token) and reload — `bb dev`
picks it up without a restart — or run `bb dev-fullstack`. Until then the UI
answers `:digdir.api.http/missing-shadow-build-manifest` and names the command
to run. The login form itself is server-rendered and works either way.

The Electric UI root is `server/src/digdir/ui/main.cljc`.

## Initial setup & user management

For first-time setup of the database-backed configuration and permissions, use:

```sh
bb setup
```

This wizard:

- Detects which Datahike backend your environment selects (`DATAHIKE_FILE_PATH`
  takes precedence over `ADH_POSTGRES_URL`, same as the boot path) and prints it
- Checks the env vars that backend actually needs — the Postgres variables are
  not required when you are on the local file backend
- Checks DB connectivity
- Helps bootstrap the first admin user and permissions
- Picks the LLM provider, including a local OpenAI-compatible server — it probes
  the endpoint, lists the models the server reports, and writes the choice per
  tenant (see [`docs/onboarding.md` §4a](docs/onboarding.md#4a-run-with-a-local-model--no-cloud-credentials-at-all))

Authorization is **permissions-based** (users must exist in the DB and have at least one permission).

### First login to the admin UI (dev)

Login sends a 6-digit confirmation code by email (Scaleway TEM). In dev you do
not need those credentials: `bb dev` arms a fallback that writes the code to the
server log and prefills the confirm-email form when no email service is
configured. The page labels this clearly as **Local development mode** and
explains that production sends codes by email. Click **Log in** and you're in;
grep the `bb dev` output for `dev-login` when diagnosing the flow. See
[`docs/onboarding.md`](docs/onboarding.md) ("Logging into the admin UI") for the
full flow.

The fallback is armed only from `server/src-dev/dev.cljc`, which is on the
`:dev`/`:test` classpath and not in a production build — there is no way to turn
it on in prod. With a configured email service the code is mailed as normal.
See [`docs/system-overview.md`](docs/system-overview.md#41-configuration-system) for how config and permissions resolve, and `server/docs/api/endpoints/api-keys.md` for the API-key management endpoints.

## Headless API

The query surface is **`POST /api/mcp`** — a [Model Context Protocol](https://modelcontextprotocol.io)
server over Streamable HTTP (JSON-RPC 2.0, with optional SSE streaming). Each configured
agent × skill-graph pair is exposed as an MCP tool; `tools/list` enumerates what a given
API key can call, and `tools/call` invokes one.

The legacy `POST /api/rag` and `POST /api/retrieve` endpoints were removed in Phase 0 of the
MCP server migration (`server/src/digdir/api/routes/handlers.clj:4`) and are not coming back.

- **API docs**: `server/docs/api/README.md`
- **MCP reference**: `server/docs/api/endpoints/mcp.md`
- **OpenAPI spec** (authoritative contract): `server/docs/api/openapi.yaml`

Also served from the same server under `/api/*`:

- Datasets — `GET /api/datasets`, `GET /api/datasets/:dataset-id`
- Conversations — `GET/POST /api/conversations`, `GET/PUT/DELETE /api/conversations/:id`

Authentication:

- **MCP, datasets, conversations** use API keys via `X-API-Key`.
- The admin interface (Operator Console API) uses **JWT (cookie-based)**.

For a first-request walkthrough (mint an API key, call `initialize`, then `tools/call`), see
`server/docs/api/getting-started.md`.

## Configuration

All runtime configuration (services, auth, LLM providers, agent behavior, dataset
definitions, skill parameters) lives in a Datahike-backed tree, resolved per request by
tenant/agent/dataset against a platform → runtime → dataset inheritance model. The `config/`
directory only holds point-in-time export/import snapshots, not live config.

- **Config resolution model**: [`docs/system-overview.md` §4.1](docs/system-overview.md#41-configuration-system)

DB-backed config mode is enabled by setting `CONFIG_MASTER_KEY`.

## Document ingestion pipelines

The system can ingest documents from multiple sources and store them into **Typesense** collections (documents/chunks/phrases).

- **Architecture doc**: `server/docs/pipeline-architecture.md`

High-level flow:

```
Source → Extract → Normalize → Chunk → Search Phrases → Store (Typesense)
```

Pipelines live under `digdir.docs.*` in the server codebase and are designed to run as jobs with telemetry.

## Testing

This repo uses **RCF (Rich Comment Forms)** for inline tests.

- **Testing guide**: `server/TESTING.md`

Common commands:

```sh
# Run unit tests
bb test

# Run config tests
bb test-config

# Lint
bb lint
```

## Building & running in production

See `server/README.md` for Electric starter build commands.
In short:

```sh
# Build client (prod)
clj -X:build:prod build-client

# Run server (prod)
clj -M:prod -m prod
```

Prod server default is **http://localhost:8080**.

## Deployment

This repo includes Kamal config files and Babashka wrappers:

- `deploy.yml` (+ `deploy.*.yml`)
- `bb deploy <destination>`

Destinations supported by `bb.edn` currently include:

- `prod`
- `test`

## Troubleshooting

- **Missing env vars / can’t boot**
  - Run `bb setup` to validate environment and DB connection.
- **Dev server port**
  - Dev runs on `8081` (see `server/src-dev/dev.cljc`). Prod runs on `8080` (see `server/src-prod/prod.cljc`).
- **Typesense / Postgres connectivity**
  - For a local instance, bring up `docker-compose.dev.yml`. `bb dev` does *not* port-forward; run `bb port-forward` separately to borrow the shared, populated corpus (needs SSH access to the deploy box). Each environment forwards to its own local port — dev `8308`, test `8208`, prod `8108` — so point `services.typesense.api-host` at the one you asked for; `bb port-forward` brings up test on `8208`.
- **Tests not running**
  - See `server/TESTING.md` (RCF enable flags and REPL invocation).
