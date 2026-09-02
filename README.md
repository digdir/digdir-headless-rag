
# Digdir RAG as a Service - PROTOTYPE

This repository is a prototype to explore the possibilities for rapid experimentation and development of Retrieval-Augmented Generation (RAG) applications.

It provides:

- **A headless HTTP API** for RAG and retrieval (`/api/rag`, `/api/retrieve`, conversations, etc.)
- **An admin web UI** for configuration, access control, and document ingestion
- **Document ingestion pipelines** that turn source content into Typesense collections usable by the RAG system


## Repository layout

- **`server/`**: Clojure/Electric application (admin UI + headless API)
- **`server/docs/`**: Documentation (API docs, pipeline architecture)
- **`config/`**: Hierarchical configuration system
- **`docs/`**: Design/engineering docs (e.g. configuration resolution model)
- **`bb.edn`**: Babashka task runner for common workflows (dev, test, setup, deploy helpers)
- **`mise.toml`**: Toolchain versions + convenience tasks/env


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
A good place to set these for development is `mise.toml` (`[env]` section), your shell profile, or a local `.env` (don’t commit secrets).

**Config mode selection**

- **DB-backed config mode**: set `CONFIG_MASTER_KEY` (and typically `TENANT` + `ENV`).
- **Legacy/EDN config mode**: set `ENTITY_CONFIG_FILE` to a file in `config/`.

**Common env vars** (see `mise.toml` and `config/README.md`):

- **Required for most real runs**
  - `ADH_POSTGRES_URL`
  - `ADH_POSTGRES_USER`
  - `ADH_POSTGRES_PWD`
  - `JWT_SECRET`
  - `CONFIG_MASTER_KEY` (required for DB-backed config mode)
- **Other configuration settings required**
  - `TYPESENSE_API_KEY`
  - `TYPESENSE_API_KEY_ADMIN`
  - One of:
    - `AZURE_OPENAI_API_KEY`
    - `OPENAI_API_KEY`
    - `ANTHROPIC_API_KEY`

The app also supports defaults for the “agent graph” LLM configuration:

- `AGENT_GRAPH_DEFAULT_LLM_CONFIG` (EDN string)

### 2) Run the server (dev)

The dev entrypoint starts:

- Shadow-CLJS compiler in watch mode
- Jetty web server

Using **Mise**:

```sh
mise run dev
```

Or via **Babashka**:

```sh
bb admin-dev
```

Dev server default is **http://localhost:8081**.

### 3) Open the admin UI

Once running, open:

- `http://localhost:8081`

The Electric UI root is `server/src/digdir/ui/main.cljc`.

## Initial setup & user management

For first-time setup of the database-backed configuration and permissions, use:

```sh
bb setup
```

This wizard:

- Checks required env vars
- Checks DB connectivity
- Helps bootstrap the first admin user and permissions

Authorization is **permissions-based** (users must exist in the DB and have at least one permission).
See `config/README.md` for details and the user management endpoint overview.

## Headless API

The headless API is served from the same server under `/api/*`.

- **API docs**: `server/docs/api/README.md`
- **OpenAPI spec**: `server/docs/api/openapi.yaml`

Key endpoints:

- `POST /api/rag`
  - Full RAG pipeline: retrieval + LLM answer
- `POST /api/retrieve`
  - Retrieval-only: ranked chunks, no LLM answer
- Conversations
  - `GET/POST /api/conversations`
  - `GET/PUT/DELETE /api/conversations/:id`

Authentication:

- **RAG/retrieve/conversations** use API keys via `X-API-Key`.
- API key management in the admin interface uses **JWT (cookie-based)**.

Example request:

```sh
curl -X POST http://localhost:8081/api/rag \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key_here" \
  -d '{"query":"Hva er Altinn?"}'
```

## Configuration

Tenant configuration lives in `config/` and is loaded using **Aero** with a **base + overrides** deep-merge pattern.

- **Config docs**: `config/README.md`
- **Config resolution model (DB-backed)**: `docs/config-resolution.md`

Typical legacy config pattern:

- `ENTITY_CONFIG_FILE=config/ka_dev.edn`

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

- `deploy-admin.yml` (+ `deploy-admin.*.yml`)
- `accessories.yml`
- `bb deploy-admin <destination>`

Destinations supported by `bb.edn` currently include:

- `prod`
- `test`

## Troubleshooting

- **Missing env vars / can’t boot**
  - Run `bb setup` to validate environment and DB connection.
- **Dev server port**
  - Dev runs on `8081` (see `server/src-dev/dev.cljc`). Prod runs on `8080` (see `server/src-prod/prod.cljc`).
- **Typesense / Postgres connectivity**
  - If you rely on remote accessories, see `bb port-forward` and related tasks in `bb.edn`.
- **Tests not running**
  - See `server/TESTING.md` (RCF enable flags and REPL invocation).

