
# Digdir RAG as a Service - PROTOTYPE

This repository is a prototype to explore the possibilities for rapid experimentation and development of Retrieval-Augmented Generation (RAG) applications.

> ### This stack is not the productization target
>
> Digdir plans to **migrate to a different tech stack** for productization, so
> this repository is expected to stay prototype-grade for the foreseeable
> future. That is a stronger statement than "expect rough
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


## Run it with Docker

Docker is the only thing you need to get a working stack. You do not need Java,
Clojure, Node or any of the tooling further down this page — those are for
working *on* the backend.

Everything on this page runs from the container image, including fetching the
demo corpus.

### 1. Configure it

```sh
cp .env.example .env
./scripts/setup-env.sh
```

The script generates the secrets that can be generated, and asks you for the
ones nobody can invent: your LLM provider credentials, and the email address
that should be allowed to administer this instance. It writes them into `.env`.

Add one more line to `.env` so you can log in without an email service:

```sh
echo 'DIGDIR_LOG_CONFIRMATION_CODES=true' >> .env
```

Login normally emails you a six-digit code. With this set, the code is written
to the server log instead, which is what you want on a local stack.

### 2. Build the image

```sh
docker compose -f docker-compose.newcomer.yml build
```

This compiles the server into a single jar, so it takes a few minutes the first
time. Rebuild with the same command whenever you change server code. If you have only
changed `.env`, you do not need to rebuild — run the start command in step 4
again, which recreates the containers with the new values.

**Give Docker at least 6 GB of memory.** The build asks for up to 4 GB of heap
for the JavaScript optimizer, and Docker Desktop's default allocation is not
always enough to cover that plus the rest of the build. The ceiling is pinned
in the project rather than left to the JVM default, so this no longer depends
on a host setting nobody mentions — but the machine still has to be able to
supply it. On Docker Desktop the setting is under Settings → Resources.

### 3. Set up the database, before starting the server

Start the search backend on its own first:

```sh
docker compose -f docker-compose.newcomer.yml up -d typesense
```

Then run four setup commands. Each runs in its own short-lived container and
exits:

```sh
docker compose -f docker-compose.newcomer.yml run --rm --no-deps digdir-rag \
  java -cp app.jar clojure.main -m digdir.setup.bootstrap

docker compose -f docker-compose.newcomer.yml run --rm --no-deps digdir-rag \
  java -cp app.jar clojure.main -m digdir.setup.first-admin

docker compose -f docker-compose.newcomer.yml run --rm --no-deps digdir-rag \
  java -cp app.jar clojure.main -m digdir.setup.demo-tenant

docker compose -f docker-compose.newcomer.yml run --rm --no-deps digdir-rag \
  java -cp app.jar clojure.main -m digdir.setup.demo-dataset
```

They create the configuration database, your admin account, the demo tenant and
the demo dataset, in that order.

**Run these while the server is stopped.** They write to the same database file
the server holds open, and a write made behind a running server does not
survive — the command reports success and the change is not there afterwards.
Each command checks for a running server and refuses rather than doing that
silently, so if you see it refuse, stop the server and run it again.

⚠️ **Your credentials must already be in `.env` before this step.** The
`demo-tenant` command copies your LLM provider settings and Typesense admin key
out of the environment and into the tenant's configuration **as it runs** — it
does not read them again later. If you skipped those prompts in step 1, or
filled them in afterwards, you get a tenant whose LLM configuration is empty,
and the only fix is to put the values in `.env` and run `demo-tenant` again.

### 4. Start it

```sh
docker compose -f docker-compose.newcomer.yml up -d
```

| Address | What it is |
| --- | --- |
| <http://localhost:8080> | the admin UI and the API |
| <http://localhost:3030> | a chat UI (Open WebUI) |

If port 3030 is taken, set `OPENWEBUI_PORT` in `.env` to something else.

### 5. Log in

Open <http://localhost:8080/auth> and enter the address you gave the setup
script. Then find the confirmation code in the log:

```sh
docker compose -f docker-compose.newcomer.yml logs digdir-rag | grep dev-login
```

Enter the code and you are in. You should not need to restart anything.

### 6. Fetch the demo corpus

The stack starts with an empty corpus, and until it has one a query returns a
`404` that names the missing step rather than failing obscurely.

The demo corpus is **352 Norwegian Wikipedia articles** — the ones behind the
NorQuAD question-answering set — plus a set of unrelated articles (800 by
default) so that retrieval has something to get wrong.

**It is not in this repository, and that is deliberate.** NorQuAD releases its
own questions and answers into the public domain under CC0, but it cannot
relicense the Wikipedia prose they are about. So this repo ships a manifest and
a fetch script, and the article text is downloaded from Wikipedia at setup under
CC BY-SA 4.0.

Fetching runs from the image, so it needs nothing installed but Docker:

```sh
docker compose -f docker-compose.newcomer.yml --profile fetch run --rm corpus-fetch
```

`--profile fetch` is why this is not started by `docker compose up`: it is a
one-shot job rather than part of the running stack. It writes to the same
directory the server later reads, but mounted read-**write** — the server's own
mount is read-only, which is correct for the server and wrong for the thing that
fills it.

With a source tree you can also run `bb demo-corpus`, which shells to the same
code. There is deliberately only one implementation: two fetchers would be free
to drift, and because the shipped cache is keyed by chunk hash, a fetcher
producing so much as a different trailing newline would silently orphan the
cache while still appearing to work.

It downloads roughly one article per request and **takes a while**. It is
resumable: an article already written is not fetched again, so if it stops you
can simply run it again. Files land in `./demo-corpus`, or wherever you point
`DEMO_CORPUS_DIR`.

You do not have to wire anything up. The compose file mounts that same
directory into the container for you, read-only, and reads `DEMO_CORPUS_DIR`
to find it — so one variable moves both the fetch and the mount, and leaving it
unset puts the corpus in `./demo-corpus` at the repository root.

⚠️ **On macOS, keep the repository somewhere under your home directory.**
Docker Desktop does not share `/tmp` or `/private/tmp`, and a bind mount from
there fails in the worst possible way: it resolves, `docker inspect` reports it
correctly, and the container sees an **empty** directory with no error at all.
If you have cloned into a scratch path, that phantom empty corpus looks exactly
like a broken setup.

If the corpus is missing, materializing **refuses and tells you where it
looked** — naming the resolved absolute path, which for the shipped
configuration is `/app/demo-corpus` inside the container rather than the
`./demo-corpus` you configured. It distinguishes two cases, because the fixes
are different: a directory that is not there at all, and a directory that is
there with no `.md` files in it.

### 7. Materialize the corpus

In the admin UI, open the demo tenant's dataset (`norquad-docs`) and run its
pipeline. This reads the articles, splits them up and indexes them for search.

⚠️ **The first attempt on a brand-new stack usually fails**, reporting
`Model not found` and zero documents. Nothing is wrong: the search backend is
still downloading a 128 MB embedding model in the background. Wait a moment and
run it again — the second attempt works. This is a known rough edge.

**Materializing this corpus costs no model calls.** A pre-computed search-phrase
cache is committed to this repository and unpacks itself the first time the
server boots, so the phrases that would normally be generated are already there.
You only pay for an article that has changed since the corpus was pinned.

### 8. Ask it something

Open <http://localhost:3030>, pick an agent from the model dropdown, and ask a
question about the corpus.

Open WebUI attaches to the backend twice, because an agent is reachable as two
different things: over [`/v1`](server/docs/api/endpoints/openai-compat.md) each
agent is a **model** — it *is* the conversation, and the dropdown fills itself
on boot — and over
[`/api/tools`](server/docs/api/endpoints/openapi-tools.md) each agent is a
**tool** another model can call mid-answer. It is included because it is the one
client here that we did not write, so it exercises things our own tests cannot
see. Why neither route is MCP is explained in
[`docs/onboarding.md` §4b](docs/onboarding.md#4b-see-it-answer-in-a-chat-ui--open-webui).

### 9. Stop it

```sh
docker compose -f docker-compose.newcomer.yml down
```

That leaves your data in place, so starting again picks up where you left off.
To throw the database and search index away as well:

```sh
docker compose -f docker-compose.newcomer.yml down -v
```

The sections below are for **working on** the backend, which needs the full
toolchain.


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
Set these for development in `mise.local.toml` (gitignored) or your shell profile — never in tracked files. **On this host path a `.env` file is not read** (`mise.toml` has no `_.file` directive and no other loader picks one up), so values placed there are silently ignored — nothing warns you. Here `.env.example` is a reference catalog of the variables, not a file to copy into place. (The container path above is the exception: `docker-compose.newcomer.yml` declares `env_file: .env`, so docker compose *does* read one there.)

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
  - **Azure OpenAI** (`use-azure-openai-api true` — you must SET this; there is
    no longer a shipped default, and **unset means NOT Azure**) —
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
client build. The client build needs a Hyperfiddle activation token that a
fresh clone does not have, so keeping it out of `bb dev` means backend work is
possible immediately:

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
