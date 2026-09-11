# Onboarding

A linear walkthrough for a new contributor's first day or two: toolchain →
local stack → a first `/api/mcp` query → where to go next. For *how the
system works* (modules, data flow, design choices), this doc links into
**[`docs/system-overview.md`](system-overview.md)** rather than repeating it —
read that when you need the "why," come back here for the "what do I run."

## 1. Who this is for, and what "PROTOTYPE" means

This repo is a **prototype**: an environment for rapid experimentation with
agentic RAG, not a hardened production service. Expect rough edges — some
documented honestly in §7 below, a full catalog in
[`plans/proposed/release-v0.1-rough-edges-inventory.md`](../plans/proposed/release-v0.1-rough-edges-inventory.md).
Code quality and test coverage vary by area; when in doubt, read the code
over trusting a comment or doc that might have drifted.

## 2. Toolchain

Install [Mise](https://mise.jdx.dev/) and let it pin everything for you:

```sh
# macOS only — required by Mise on some systems
brew install libyaml

mise trust     # a fresh clone's mise.toml is untrusted; `mise install` refuses without it
mise install
```

Without `mise trust`, the very first command in this doc fails with *"Config
files ... are not trusted. Trust them with `mise trust`."* — the error names
its own fix, so it costs a step rather than an hour, but there is no reason to
meet it at all.

This installs the versions pinned in `mise.toml` (Java, Clojure CLI, Node,
Yarn, Babashka). Babashka (`bb`) drives the task runner used throughout this
doc — every `bb <task>` command below assumes it's on your `PATH` (via Mise
or otherwise).

## 3. Boot minimum + local-vs-remote stack matrix

The server needs exactly **one database pointer** plus two secrets to boot
(`digdir.config.core/load-bootstrap-config`) — everything else (Typesense,
LLM provider, admin emails) is only needed once you try to run a real query
or log in.

**Set these in `mise.local.toml` (gitignored) or in your shell profile — not
in a `.env` file.** Nothing *on this path* reads `.env`: `mise.toml` carries no
`_.file` directive and no other loader picks one up, so a filled-in `.env` is
silently ignored and you meet the boot pre-flight several steps later with no
reason to suspect the file (#302). `.env.example` (repo root) is still worth
opening — it is the annotated catalog of every variable, grouped by when you
need it — but on this path read it as a reference list, not as a file to copy
into place.

> **The container path is the exception, and it is the opposite.**
> `docker-compose.newcomer.yml` declares `env_file: .env`, so there a `.env`
> *is* read — by docker compose, not by the JVM — and `cp .env.example .env` is
> the documented first step (§4b). Which rule applies is decided by how you
> start the server, not by the file.

**Database — pick one:**

| Option | Var(s) | When to use |
| --- | --- | --- |
| Local file (recommended) | `DATAHIKE_FILE_PATH` | Easiest path — no Postgres, no shared credentials. `digdir.config.core` treats this as the *preferred dev/test* backend. |
| Remote/shared Postgres | `ADH_POSTGRES_URL`, `ADH_POSTGRES_USER`, `ADH_POSTGRES_PWD` | Only if you specifically need to share state with a running remote instance. |

**On a fresh clone, create the DB directory's parent first:**

```sh
mkdir -p server/local-db
```

`server/local-db/` is gitignored, so it does not exist in a new checkout.
Datahike creates the database directory itself but not its parent, so the
boot pre-flight refuses with *"Datahike :file backend parent directory does
not exist"* — which is the first thing a new contributor hits, before any of
the steps in §4. Paths in `DATAHIKE_FILE_PATH` resolve relative to `server/`.

**Always required:** `CONFIG_MASTER_KEY` (any string for local dev) and
`JWT_SECRET` (generate one with `openssl rand -base64 32` — there is no
`gen-jwt-secret.sh` script in this repo).

`DATAHIKE_FILE_PATH` wins if both pointers are set. `bb setup` applies the same
precedence as `load-bootstrap-config` and prints the backend it selected
(`Database backend: local file (DATAHIKE_FILE_PATH)`), so the wizard and the
boot path can never disagree about which database you are on.

**Typesense — run it locally:** retrieval needs a Typesense instance. Bring
one up with the dev stack; no shared credentials, no SSH:

```sh
docker compose -f docker-compose.dev.yml up -d --wait
```

`--wait` blocks until the container reports healthy (about 6 seconds from a
cold start). Postgres is *not* needed for the recommended file-backed DB, so
it sits behind an opt-in profile: add `--profile postgres` only if you
specifically want to exercise that path.

The connection details are read from the **config DB**, not from environment
variables (see `digdir.rag.typesense/resolve-tenant-ts-settings`), so point
your tenant at the local instance once:

```sh
bb config-set services.typesense.api-host '"localhost:8108"'        digdir platform default
bb config-set services.typesense.api-tls false                       digdir platform default
bb config-set services.typesense.api-key-admin '"dev-typesense-key"' digdir platform default
```

> **Why `digdir`, and not the other tenant.** The snapshot imported in §4 step 2
> defines **two** — `digdir` (dataset `public-docs`) and
> `public-sector-knowledge` (dataset `kudos`) — and both carry a full set of
> `services.typesense.*` values, so both accept these commands. A call that does
> not name a tenant resolves Typesense by trying them in the hard-coded order
> `["digdir" "public-sector-knowledge"]` and taking the first that has an
> `api-host` (`digdir.rag.typesense/default-typesense-tenants`), so `digdir`
> wins. Configure `public-sector-knowledge` instead and every command still
> reports success while the query path goes on using `digdir`'s untouched
> remote settings — you would have configured a tenant you never query.

> **The third line supplies a secret the snapshot deliberately does not ship.**
> `services.typesense.api-key-admin` is the one required service config value
> the committed snapshot carries no value for (#279 removed the five it used to
> carry encrypted, under a master key no fresh checkout has). A clean import
> reports it as plainly absent — `1 unresolvable / 0 undecryptable` per tenant
> — and this line is what resolves it, to the local dev key, taking that to
> `0 / 0`. That is why a fresh install gets retrieval working and not the LLM
> half: this recipe supplies the one credential retrieval needs and says
> nothing about the LLM's. See §7 for where that lands you.

> **Run these after §4 step 2, not before.** They write against a tenant, and
> a fresh DB has no tenant until the config snapshot is imported. Run
> top-to-bottom on an empty database they fail with `Config node not found`.
> §4 step 4 already places them in the right order; this note is here for
> anyone reading §3 on its own.

A fresh local Typesense is **empty** — it has no collections and therefore no
chunks. That is enough to boot, to run the suite, and to exercise the query
path end-to-end; it is not enough to get *interesting answers*. To query real
content you still need either an ingestion run against your own documents, or
the shared box:

```sh
bb port-forward   # SSHes to the shared box; needs a credential you may not have
```

So the shared box is now an optional convenience for borrowing a populated
corpus, not a hard prerequisite for getting started.

## 4. First queryable dataset — the recipe

Once the server boots on an **empty** local file-DB, there's no tenant,
dataset, or API key yet. This is the sequence that gets you from empty DB to
a working `/api/mcp` call; none of the individual pieces are new, but they
weren't previously written down together.

1. **Boot minimum is set** (§3): `DATAHIKE_FILE_PATH`, `CONFIG_MASTER_KEY`,
   `JWT_SECRET`.
2. **Import the committed config snapshot** — config definitions and the
   built-in agents, but **no tenant, no dataset and no API key**:

   ```sh
   bb migration-import config/system-import.normalized.20260821.json
   ```

   ⚠️ **The snapshot ships no tenant, so this is two commands rather than one.**
   It used to carry the `digdir` and `public-sector-knowledge` tenants — our own
   deployment's configuration, which does not belong in the product. Give
   yourself the shipped demo tenant:

   ```sh
   bb demo-tenant
   ```

   The demo tenant is **deliberately seeded by a command rather than authored
   into the snapshot**: the snapshot is a generated export, so hand-written rows
   in it would survive only until somebody regenerated it, and then vanish
   without a sound.

   After these two you have a working tenant and **no datasets**, which is the
   intended state — asking a question returns a `404` naming the next step. That
   is deliberate: seeding a dataset whose corpus has not been fetched answers
   unhelpfully with no sign that a step remains (#473), which is worse than an
   error that tells you what to do. To go further now, fetch the corpus and seed
   the dataset together:

   ```sh
   bb demo-corpus     # 352 Wikipedia articles, nothing is checked in
   bb demo-seed       # the demo tenant AND its dataset
   ```

   The import also **writes whatever service config you already have in the
   environment** into each imported tenant, and then names what is still
   missing — the variable per missing value, not just the path. So
   `TYPESENSE_API_KEY_ADMIN` set before this step needs no `bb config-set`
   afterwards. The mapping is `digdir.config.env-bridge`.

3. **Get a working `X-API-Key`.** The admin UI's own key-creation flow
   requires being logged in first (see "Logging into the admin UI" below).
   The fast path skips the UI entirely: set `E2E_API_KEY` (any string) in your
   env *before* starting
   the server. On every boot, `digdir.e2e.seed/maybe-seed!` (wired into `bb
   dev` in `server/src-dev/dev.cljc`) will upsert an API key with exactly
   that plaintext into the config DB — idempotent, safe to leave set. That
   plaintext is now your `X-API-Key`.
4. **Reach a Typesense** — `docker compose -f docker-compose.dev.yml up -d
   --wait` (§3) gives you a local one, and the `bb config-set` lines in §3
   point the tenant at it. Note a local instance starts **empty**: the query
   path will work end-to-end but return no hits until you ingest something.
   To borrow a populated corpus instead, run `bb port-forward` against the
   shared box (credential required). **It does not land on 8108.** Each
   environment forwards to its own local port, mirroring the box — dev
   `8308`, test `8208`, prod `8108` — so the address tells you which corpus
   you reached. `bb port-forward` brings up the *test* one, so repoint the
   tenant or you will still be querying the empty local instance:

   ```sh
   bb config-set services.typesense.api-host '"localhost:8208"' digdir platform default
   ```

   Use the same tenant as §3 — `digdir`, for the reason given there.
5. **Start the server:** `bb dev` (see §5) — backend only, and it needs no
   Hyperfiddle activation token (#330), so this step works on a fresh clone.
   Once it's up, prove the wiring
   works with a `tools/list` call:

   ```sh
   curl -sS -X POST http://localhost:8081/api/mcp \
     -H "Content-Type: application/json" \
     -H "MCP-Protocol-Version: 2026-07-28" \
     -H "Mcp-Method: tools/list" \
     -H "X-API-Key: <your E2E_API_KEY value>" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
   ```

   **The MCP headers are required, and they are not boilerplate.** The
   server implements MCP revision `2026-07-28` only, which has no
   `initialize` handshake — so every request declares its own protocol
   version and mirrors its method into a header. Omit a required one and you
   get a `400` with JSON-RPC `-32020` naming the one you missed; send an older
   revision and you get `-32022` with the list of versions we accept.
   `Mcp-Method` must equal the `"method"` in the body.

   **`tools/list` takes the two above; `tools/call` takes a third.** That
   method also requires `Mcp-Name`, mirroring `params.name` (a name outside
   plain ASCII arrives base64-wrapped as `=?base64?<b64>?=`) — so the two-header
   call below is complete for `tools/list` and one header short of a
   `tools/call`.

   **What success looks like:** a JSON-RPC result whose `tools` array is
   non-empty, each entry named `<agent>__<mode>` — for example
   `builtin.agent-rag-agent__agent-rag-graph-bundled`. Those come from the
   agents in the snapshot you imported in step 2, so an empty list means the
   import did not take, not that the call failed.

   ```json
   {"jsonrpc":"2.0","id":1,"result":{"tools":[
     {"name":"builtin.agent-rag-agent__agent-rag-graph-bundled", "...": "..."}
   ], "...": "..."}}
   ```

   From there, see [`server/docs/api/endpoints/mcp.md`](../server/docs/api/endpoints/mcp.md)
   for the full `tools/call` method/params contract (tenant, dataset scope,
   streaming vs. blocking) — `/api/mcp` is the query surface; the legacy
   `/api/rag` and `/api/retrieve` endpoints were removed in Phase 0 of the
   MCP migration and are not coming back.

   **Where this recipe stops on a fresh install — read this before running a
   `tools/call`.** The `tools/list` above succeeds. A `tools/call` gets further
   than you might expect and then stops: the agent graph executes and retrieval
   runs (§3 configured that), and **the first LLM call fails**, because the
   snapshot ships no value for `services.azure-openai.api-key` — #279 removed
   the five secrets it used to carry encrypted, under a key no fresh checkout
   has. No change to this recipe fixes that — the key is not in the repo and
   is not meant to be. **If you have Azure OpenAI
   credentials of your own**, set `TENANT=digdir` together with the
   `AZURE_OPENAI_*` variables before `bb dev`: the same auto-seed as step 3
   (`digdir.e2e.seed/seed-azure-config-from-env!`) writes them onto that
   tenant's platform node, supplying the value the snapshot no longer carries,
   exactly as the `api-key-admin` line in §3 does. **Without them, [§4a](#4a-run-with-a-local-model--no-cloud-credentials-at-all)
   is the other way past, and it needs no cloud account at all:** point the same
   config family at an OpenAI-compatible server on your own machine. Verified
   end to end against LM Studio; §4a step 0 says what that does and does not
   license you to assume about Ollama, vLLM and llama.cpp.

### Logging into the admin UI

> **The console is the one part that needs a client build.** `bb dev` is
> backend-only (#330), so run `bb build-client` once (~100 s, no activation
> token) — or use `bb dev-fullstack` — before expecting the UI to load. The
> login form itself is server-rendered and works either way; what needs the
> bundle is the console behind it. Without one, the UI answers
> `:digdir.api.http/missing-shadow-build-manifest` and names the command to run.

Admin login is a two-step email flow: you enter your address at `/auth`, the
server emails you a 6-digit confirmation code, and you enter that code at
`/auth/confirm-email`. Sending the mail needs Scaleway TEM credentials, which
a new contributor will not have.

**In dev you don't need them.** `bb dev` arms a fallback
(`digdir.auth.core/set-dev-confirmation-code-logging!`, called from
`server/src-dev/dev.cljc`): when no email service is configured, the code is
written to the server log instead of being mailed and is prefilled in the
confirmation form. A **Local development mode** notice makes clear that this
convenience exists only locally and that production sends codes by email.
Click **Log in** to submit it; the log remains useful for non-browser clients
and diagnostics. Grep your `bb dev` output for `dev-login`:

```
WARN digdir.auth.core: [dev-login] No email service configured. Confirmation
code for you@example.com is 380465 — dev-only fallback; configure Scaleway TEM
to send real mail.
```

The whole flow, start to finish:

1. Your address must belong to a user **with at least one permission** —
   `perms/can-login?` rejects unknown users and users with no permissions.
   `bb setup` seeds one for you (it reads `ADMIN_USER_EMAILS`).
2. Open `http://localhost:8081` → you're redirected to the login form.
3. Enter your email → the confirmation form opens with the code prefilled,
   and the same code appears in the `bb dev` log tagged `[dev-login]`.
4. Click **Log in** → you get an `auth-token` cookie and land in the admin UI.

The fallback is **off by default outside dev**. In dev it is armed
unconditionally from `server/src-dev/dev.cljc`, and `src-dev` is on the
classpath of the `:dev` and `:test` aliases only — a production build carries
`src-prod` instead (see `server/deps.edn`). It also stays out of the way if you
*do* have credentials: when the email service is configured, the code is mailed
as normal, and the log fallback only kicks in if that send fails.

> ⚠️ **Corrected (#436).** This section used to say the fallback *"cannot be
> switched on in production"*. That is no longer true, and the sentence is
> corrected rather than deleted because it is exactly the kind someone relies
> on. A production build can arm it, but only when an operator sets
> `DIGDIR_LOG_CONFIRMATION_CODES=true` — see the container section below.

### First login in a container (a fresh deployment)

The dev instructions above assume `bb`, a source tree and `bb setup`. A
container has none of those: `/app` holds one file, `app.jar`. Before #436 a
fresh deployment could not be logged into at all — **two gates, stacked**:

1. `perms/can-login?` runs **before** the code is delivered, so an empty
   database is rejected at `302 /not-approved` and the mail path is never
   reached. There is no administrator to contact, because there is no
   administrator.
2. The log fallback above was armed only from `src-dev`, which the uberjar
   does not contain — so even past gate 1, the code had nowhere to go.

Both are addressed by explicit operator commands, never by anything automatic:

```bash
# 1. Name who may claim this instance, on the HOST, in .env.
#    .env.example ships this line COMMENTED OUT. Editing the address but
#    leaving the leading `#` is indistinguishable from never setting it:
#    step 2 refuses with "ADMIN_USER_EMAILS is empty" and nothing says why.
#    `./scripts/setup-env.sh` asks for it and writes it uncommented.
ADMIN_USER_EMAILS=you@example.com

# 2. Seed the database and create that account — BEFORE the server starts.
#    Both are second-JVM writers and neither survives a running server; see
#    the warning below. `run --rm` executes in a one-off container, which is
#    what lets these run with no server up. Requires shell access to the
#    container, which is the authority being exercised: nothing on the HTTP
#    surface can do it.
docker compose -f docker-compose.newcomer.yml run --rm digdir-rag \
  java -cp /app/app.jar clojure.main -m digdir.setup.bootstrap

docker compose -f docker-compose.newcomer.yml run --rm digdir-rag \
  java -cp /app/app.jar clojure.main -m digdir.setup.first-admin

# 3. Now start the server. There is NO restart step — see below.
docker compose -f docker-compose.newcomer.yml up -d
```

> ⚠️ **Seed before the server starts. There is no restart step, and the advice
> to restart that used to be here was wrong.**
>
> These commands open the Datahike file store in a **second JVM**. With the
> server already running, the write does not survive — and the command
> **reports success either way**, which is what makes it dangerous. Measured on
> two independent fresh container stacks: `first-admin` printed
> `[CREATED] … ✔ created 1` and the account did not exist afterwards; a second
> JVM reading the same store reported `can-login? -> false` and zero admins
> immediately after, and again 45 s later.
>
> Seeded with the server **down**, the identical command persists — a re-run
> reports `[SKIPPED] Already has admin-full` — and on a fresh volume **login
> succeeds on the first attempt, with no restart at all** (`302
> /auth/confirm-email` straight away).
>
> ⇒ So correct ordering **deletes** the restart step rather than improving the
> advice about when to restart. The old text here claimed the account existed
> but was merely unseen until the server reconnected, citing a
> `can-login? -> true` reading that does not reproduce. There is nothing for a
> restart to recover.
>
> `digdir.setup.first-admin` (#436) is a **restatement** of this passage in a
> code comment; both are corrected together under #526. The exact mechanism —
> whether the write never lands or is clobbered by the server's next write — is
> still being pinned down in #537 and #538, which currently describe it
> differently; the observed behaviour above is what you can rely on.
>
> Every setup entry point now **refuses** rather than documenting this: #538
> probes `/up` on three addresses (inside the container, a sibling `run`
> container, and the host's `HTTP_PORT`) and exits 1 if a server answers.
> `DIGDIR_ALLOW_SEED_WITH_SERVER_RUNNING=true` overrides it, mirroring
> `DIGDIR_ALLOW_PLACEHOLDER_SECRETS`.

`ADMIN_USER_EMAILS` is **not** a new mechanism — it already existed and was
already bridged to `services.auth.admin-user-emails`. Note the trap it carries:
**one variable, two consumers, opposite behaviour.** The boot-time hook
(`permissions/sync-admin-permissions!`) only *grants* to users that already
exist, so on an empty database it prints `User not found` and continues, which
reads as a dead end. The command above *creates* them.

For a local stack with no mail service, add:

```bash
DIGDIR_LOG_CONFIRMATION_CODES=true
```

and read the code from the server log, tagged `[dev-login]` exactly as in dev.
**Do not set this on a deployment anyone else can reach** — an armed instance
writes login codes to its own log, so anyone who can read logs can complete a
login as any permitted user. It warns loudly on every boot for that reason.

If you only need API access and not the web UI, the `E2E_API_KEY` auto-seed in
step 3 above is still the shortest path.

## 4a. Run with a local model — no cloud credentials at all

§4 gets a fresh clone to a live `/api/mcp` surface and stops at the first LLM
call, because that is the one thing the repo cannot ship you. **This section is
the way past it without any cloud account.** Point the system at an
OpenAI-compatible server on your own machine — LM Studio, Ollama, vLLM,
llama.cpp — and the whole path works: retrieval, the multi-turn tool-calling
agent loop, streaming, synthesis. Everything below was run against LM Studio;
step 0 says what that does and does not license you to assume about the others.

### The naming trap, first, because nothing else here makes sense without it

There is **one** family of LLM settings, `services.azure-openai.*`, and it
drives **both** providers. `services.azure-openai.use-azure-openai-api` is the
switch that decides which client the family configures:

| Switch | Client | Endpoint from | Key from | Model from |
| --- | --- | --- | --- | --- |
| `true` (must be set explicitly — unset is NOT this row) | wkok's Azure client (`:impl :azure`) | `services.azure-openai.api-endpoint` | `services.azure-openai.api-key` | `services.azure-openai.deployment-name` |
| `false` | `digdir.llm.client` — a plain OpenAI-compatible POST | **`OPENAI_API_ENDPOINT`** (environment) | **`OPENAI_API_KEY`** (environment) | `services.azure-openai.model-name` |

Nothing renames when you flip the switch. A config family named after Azure is
what points this system at LM Studio. Nobody guesses that, which is why it is
here and not in a reference page. The branch is
`digdir.skills.builtin.agent.loop/llm-opts` and `call-llm` in the same
namespace; read those two functions if you want to see the table above as
code.

Note the asymmetry in the second row. **On the local path the endpoint and the
key come from the environment, not from the config DB**
(`digdir.llm.client/openai-compat-completion`), so `bb config-set` cannot set
them and `services.azure-openai.api-endpoint` is read only by the Azure branch —
setting it changes nothing here.

That asymmetry decides which mistakes you get told about, and it is worth being
exact about where the telling stops. The two config values are both on
`digdir.config.verify/runtime-required-service-paths` — a five-entry list, the
other three being the Typesense trio from §3 — so a tenant missing either is
reported at import and at boot. The two environment variables are checked too:
since #327 `bb setup`'s first screen lists them in its own group, under
`AZURE_OPENAI_USE_AZURE=false`, so an **absent** one is named before you run
anything.

**What nothing checks is whether they are right.** Every check in the path is a
presence check: blank or not blank. An endpoint with a typo, an endpoint
pointing at a server that is not running, and a key a real provider rejects all
pass every one of them and fail at the first LLM call — which is what the
failure table below is for.

### Step 0 — a local server with a chat model loaded

You need an OpenAI-compatible server answering on a base URL that ends in
`/v1`. Two common ones:

| Server | Base URL | Start it | Exercised here? |
| --- | --- | --- | --- |
| LM Studio | `http://localhost:1234/v1` | Developer tab → Start Server, then load a model | **yes** — every measurement below |
| Ollama | `http://localhost:11434/v1` | `ollama serve`, then `ollama pull <model>` | no — its documented default, not run |

**The last column is not hedging, it is the boundary of what was tested.**
Everything measured in this section used `qwen/qwen3-8b` in LM Studio 0.4.21.
Ollama, vLLM and llama.cpp reach the same `digdir.llm.client` code path — it is
one OpenAI-compatible POST regardless of what answers it — so there is no reason
to expect them to differ, but nobody has run them through this recipe. Treat the
second row as a starting point, not a result.

**Pick a model that can call tools.** The agent is a multi-turn tool-calling
loop, not a single completion: it decides to search, reads what came back, and
decides again. A model without tool-calling returns prose at the first turn and
the loop degenerates.

Check what the server has loaded before you go further — the model id you
configure has to be one of these:

```sh
curl -sS http://localhost:1234/v1/models | jq '.data[].id'
```

### Step 1 — the guided way: `bb setup`

`bb setup` has an **LLM Provider** section. It probes the endpoint you give it,
lists the models the server actually reports, writes the two config values onto
each tenant you choose, and prints the two environment variables it cannot
write for you:

```sh
bb setup
# ... --- LLM Provider --- ...
#   1) Any OpenAI-compatible server on this machine — anything that speaks
#      /v1/chat/completions. LM Studio, Ollama, vLLM and llama.cpp are examples.
#   2) Azure OpenAI ...
#   3) Leave unchanged.
```

The default answer is **3**, so `bb setup < /dev/null` and every existing
scripted run leave the Azure path exactly as it was.

It writes to each tenant's own platform `default` node — the same place
`bb config-set <path> <value> <tenant> platform default` writes — and *not* to
the `__platform-defaults__` seed tree that the wizard's other sections use.
That tree is copied into a tenant only when the tenant is created, so a write
there would report success and change nothing for the tenants the snapshot
already brought in. The section seeds it too, as a separate line in its output,
so a tenant you create later inherits the choice instead of reverting to Azure.

### Step 2 — or the explicit way: two config writes and two variables

Same four settings, no wizard. The two config values, per tenant (use `digdir`,
for the reason given in §3):

```sh
bb config-set services.azure-openai.use-azure-openai-api false        digdir platform default
bb config-set services.azure-openai.model-name '"qwen/qwen3-8b"'      digdir platform default
```

The two environment variables, in `mise.local.toml` or your shell profile —
then restart `bb dev`, because these are read from the process environment:

```sh
OPENAI_API_ENDPOINT=http://localhost:1234/v1
OPENAI_API_KEY=local
```

**`OPENAI_API_KEY` must be non-empty even though a local server ignores it.**
`digdir.secrets/get!` throws on an absent secret rather than sending a keyless
request (#22), so an unset variable stops the call before it leaves the
process. Any placeholder works; it is not a credential.

### Step 3 — or all four from the environment, if you already set `E2E_API_KEY`

If you took §4 step 3's `E2E_API_KEY` auto-seed, you already have a hook that
writes config from the environment on every boot, and it covers both of the
config values above. Set these five and start `bb dev` — no `bb config-set` at
all:

```sh
E2E_API_KEY=<your key>          # already set, from §4 step 3
TENANT=digdir                   # which tenant the seed writes to
AZURE_OPENAI_USE_AZURE=false    # → services.azure-openai.use-azure-openai-api
AZURE_OPENAI_MODEL_NAME=qwen/qwen3-8b   # → services.azure-openai.model-name
OPENAI_API_ENDPOINT=http://localhost:1234/v1
OPENAI_API_KEY=local
```

`digdir.e2e.seed/seed-azure-config-from-env!` maps every `AZURE_OPENAI_*`
variable onto its config path and writes it to `TENANT`'s platform `default`
node — including the two that have nothing to do with Azure. It is gated on
`E2E_API_KEY` and armed only from `server/src-dev/dev.cljc`, so it is a **dev
convenience, not a deployment mechanism**; `bb dev` reports what it wrote:

```
digdir.e2e.seed :e2e/seeded {... :azure-paths-written
  ["services.azure-openai.model-name" "services.azure-openai.use-azure-openai-api"] ...}
```

**If you are running §4 step 2's import anyway, you do not need this step for
these two values.** The env-var → config-path mapping now lives in
`digdir.config.env-bridge` and `bb migration-import` applies it to every
imported tenant — ungated, and for every service rather than only
`AZURE_OPENAI_*`. So `AZURE_OPENAI_USE_AZURE` and `AZURE_OPENAI_MODEL_NAME`
set before the import land without `E2E_API_KEY` or `TENANT`, and the import
tells you what is still missing by variable name.

What this step still gives you that the import does not: it re-applies on
**every boot**, so you can change a variable and restart instead of
re-importing. `OPENAI_API_ENDPOINT` and `OPENAI_API_KEY` are unaffected either
way: as the table above marks them, both are read from the environment per
call and cannot be set through config at all.

### What each failure looks like

Getting one of the four wrong fails differently, and only one of the four names
itself. Every line below was observed on a fresh clone with no cloud
credentials, calling `tools/call` on `builtin.agent-rag-agent__agent-rag-graph-bundled`:

| What is wrong | What you see |
| --- | --- |
| Nothing changed yet — switch UNSET, which since #500 means the OpenAI-compatible path, not Azure | `LLM request failed at iteration 0: Missing secret :openai-api-key: set OPENAI_API_KEY. Tried [:env].` |
| Switch explicitly `true`, no Azure key (what "nothing changed yet" used to mean, when a value was still shipped) | `LLM request failed at iteration 0 (status 401): Interceptor Exception: status: 401` |
| Switch flipped, **neither** variable set | `LLM request failed at iteration 0: Missing secret :openai-api-key: set OPENAI_API_KEY. Tried [:env].` |
| `OPENAI_API_KEY` set, **`OPENAI_API_ENDPOINT` missing** | `LLM request failed at iteration 0 (status 401): clj-http: status 401` |
| Everything set correctly | a real answer |

The third row is the one that costs time. With no endpoint the client falls
back to `https://api.openai.com/v1` (`digdir.llm.client/default-openai-endpoint`),
so your placeholder key is sent to the **public OpenAI API**, which rejects it —
a 401 that says nothing about the setting you actually forgot. If you are
staring at a 401 while a local server is running, check `OPENAI_API_ENDPOINT`
first. `bb dev`'s log gives it away: an `Invalid cookie header` warning naming
`Domain=api.openai.com` means the request never went near your machine.

**A wrong `model-name` may not fail at all, and that is a trap.** Measured on
LM Studio 0.4.21: a `/chat/completions` request naming
`definitely-not-a-real-model-xyz` was answered normally, with `"model":
"qwen/qwen3-8b"` in the response — the id the server actually used. So a
`model-name` left over from the snapshot can appear to work here. Whether some
other server would reject it was **not** tested — only LM Studio's laxity was —
so the useful conclusion is narrow: on LM Studio this field is not a check, and
you cannot rely on it telling you that you got the model wrong. Set it to an id
from `/v1/models` even when it seems not to matter.

### What to expect once it answers

- **A local Typesense starts empty** (§3), so the honest answer to a real
  question is that nothing was found. That is the system working: the agent
  searches, reads an empty result, and says so. Ingest something, or borrow a
  populated corpus with `bb port-forward`, before judging answer quality.
- **Streaming works on this path.** It did not before #305/#306: wkok's Azure
  client returns `{:body <channel>}` and its OpenAI-compatible client returns
  the channel itself, and the streaming reader assumed the Azure shape, so every
  local run died with a core.async protocol error before the first token. Fixed;
  the local path is newly viable, not quietly long-standing.
- **Budget tens of seconds per question, not seconds.** The agent loop spends
  several turns on each one. Measured here: 35.6 s end to end for a single
  `tools/call` (`qwen/qwen3-8b` in LM Studio, Apple M4 Max), almost all of it in
  the agent-iteration stage. No cloud comparison was run, so treat that as one
  data point on one machine, not a ratio.

## 4b. See it answer in a chat UI — Open WebUI

Everything above reaches the system by composing an HTTP request. That is the
right way to *learn* the surface and a poor way to *see whether it works*: a
`/api/mcp` call needs three mirrored request-metadata headers a newcomer has no
way to guess ([`mcp.md`](../server/docs/api/endpoints/mcp.md)), and getting one
wrong returns `400` rather than an answer.

So the newcomer stack ships a chat UI. Nothing extra to run — it is a service in
the same compose file as the backend:

```sh
cp .env.example .env
./scripts/setup-env.sh          # generates the three local secrets

# setup-env.sh generates CONFIG_MASTER_KEY, JWT_SECRET and
# TYPESENSE_API_KEY_ADMIN, and skips the Azure prompts when nothing is
# attached to a terminal. It does NOT invent an Azure key, so ONE placeholder
# remains — and the boot check counts it. Measured: without the line below the
# server refuses to start with
# "1 secret(s) still hold their .env.example placeholder — AZURE_OPENAI_API_KEY".
#
# For THIS walkthrough that placeholder is the point: §4b stops at dataset
# resolution, before any LLM call, so a real Azure key would change nothing you
# are about to see.
echo 'DIGDIR_ALLOW_PLACEHOLDER_SECRETS=true' >> .env

docker compose -f docker-compose.newcomer.yml up --build
```

> ⚠️ **Run the script; do not skip it and set the flag alone.** Both boot, and
> they are not equivalent: the flag on its own ships a stack whose master key,
> JWT secret and Typesense admin key are the strings in `.env.example`, which
> anyone with a clone can read. The script means the override covers exactly one
> value that is not a credential to anything you are running.
>
> **And the flag is for a loopback-only stack you are about to throw away.**
> Before this port is reachable from anywhere else, supply a real
> `AZURE_OPENAI_API_KEY` and drop the flag. Without it the boot refuses and
> names exactly which secrets are still placeholders, which is the behaviour you
> want everywhere except here.

| Service | Where | What it is |
| --- | --- | --- |
| `digdir-rag` | <http://localhost:8080> | the backend and its admin UI |
| `open-webui` | <http://localhost:3030> | [Open WebUI](https://github.com/open-webui/open-webui) — pick an agent from the model dropdown and ask it something |
| `typesense` | (no host port) | the search backend |

The model dropdown is populated on boot, with no visit to Settings: the stack
seeds an API key (`E2E_API_KEY`, defaulted in the compose file) and points Open
WebUI at the backend, which advertises **one model per (agent × mode) pair** —
13 of them on a fresh database.

**The dropdown filling is as far as a fresh stack gets, and you should know
where it stops before you type a question.** Measured on this exact stack, not
inferred — asking anything returns:

```json
{"error":{"message":"No datasets are configured for this tenant. No datasets are configured yet. Create one, or run the demo corpus import, before querying.",
          "type":"invalid_request_error","param":null,"code":"no-datasets-configured"}}
```

…with HTTP **404**, not 500.

That is **not** "the corpus is empty, so the agent found nothing". It fails
*earlier* than that, at dataset resolution, before any search and before any LLM
call — so the placeholder `AZURE_OPENAI_API_KEY` your `.env` was copied with is
not what stops you either. It is the same wall every other path in this repo
meets on a fresh database (§7) — and it now reports itself as one. #492 fixed the
crash that made every failed dataset resolution a 500 (a tenant string handed to a
function that reduces over a collection), and #434 classified what remains: a
named `no-datasets-configured` code, a 404, and a next step in the message rather
than an invitation to the issue tracker. What corpus a newcomer should start with
is an open product question — #328.

So what §4b buys you is the wiring, proven end to end: auth, agent discovery,
the model surface, and a real third-party client that renders it. Materialise a
dataset and the same chat box answers.

### Why Open WebUI, specifically

Two reasons, and only the second is about onboarding.

1. **It is a client we did not write.** Every other check in this repo is our
   code calling our code — a `curl` we composed against a contract we authored.
   Open WebUI is an ordinary third-party client with its own idea of what an
   OpenAI-compatible endpoint owes it, so it fails on divergences our own tests
   are constitutionally unable to notice. That is worth more than the demo.
2. **It converts "is this alive" into a chat box**, which is the question a
   newcomer actually has on day one.

### How it attaches — two surfaces, neither of them MCP

An agent is reachable as two different things, and the stack wires both:

| | Open WebUI calls it | The agent is | Good for |
| --- | --- | --- | --- |
| [`/v1`](../server/docs/api/endpoints/openai-compat.md) | a **model** | the whole conversation | asking the RAG system a question |
| [`/api/tools/openapi.json`](../server/docs/api/endpoints/openapi-tools.md) | a **tool** | one capability another model calls | letting some other model reach our corpus mid-answer |

Neither goes through MCP, and that is measured rather than preferred.
**As of 2026-09-01 Open WebUI cannot be an MCP client of this server by either
route it offers:**

| Route | Result |
| --- | --- |
| [MCPO](https://github.com/open-webui/mcpo) (the MCP→OpenAPI bridge the e2e stack used to run) | ❌ opens with `initialize`, gets `400` / `-32022`, crashes, and serves an empty tool list — `{"paths":{}}` |
| Open WebUI 0.11.3's own native MCP client | ❌ `POST /api/mcp` → `400`; the UI reports *"Failed to create MCP client"* |
| *Control* — that same Open WebUI verify path against the official MCP `everything` server | ✅ `200`, full tool list |

The control is the point: the failure is ours, not the harness's. Both routes
build on the **v1-era** Python MCP SDK, whose `ClientSession.initialize()` is a
mandatory opening — and this server implements MCP revision `2026-07-28`, which
has no handshake. Open WebUI has no fall-forward path, so it cannot recover.

**The tools half is the interesting one, because it did not have to stay
broken.** MCPO existed only to translate MCP into OpenAPI so Open WebUI could
tool-call our agents. We own the server, so the backend now renders that
OpenAPI itself — same tools, same `list-tools`/`invoke-tool` code path as
`/api/mcp`, no bridge container and no unmaintained dependency in between.
`server/e2e/README.md` has the working: a dependency bump does not fix MCPO,
and MCPO has had no commit since 2026-02-27.

### If you specifically want an MCP client, use a modern one

**Do not read the table above as "MCP clients cannot talk to this server."** It
says Open WebUI cannot, and the reason is the SDK era it pins rather than
anything about the revision.

A modern client works, and this was run rather than argued. The MCP Python
SDK's own reference client at **2.1.1**, `Client(mode="auto")`, against
`http://digdir-rag:8080/api/mcp`:

```text
negotiated protocol_version: 2026-07-28
server_info: name='digdir-rag' version='1.0'
tools: 13
calling: builtin.retrieve-only-agent__retrieve-only
  → MCPError -32603: No datasets are configured for this tenant
```

`server/discover`, `tools/list` and `tools/call` all dispatch cleanly. The one
error is the empty corpus again — the same wall as above, reached *through* the
protocol rather than instead of it. Note what that proves in passing: a
third-party client's `tools/call` does carry the mirrored `Mcp-Name` header, so
our request-metadata contract is not an adoption barrier.

That is the same SDK Open WebUI and MCPO are built on, one major version later.

[Chatbox](https://github.com/chatboxai/chatbox) 1.23.0 negotiates the same
revision and is the client to reach for if you want a GUI over MCP rather than
over `/v1`. Point it at `http://localhost:8080/api/mcp` with an `X-API-Key`
header. It is a desktop Electron app, so it cannot be a service in the compose
stack — that is the trade.

So the split is deliberate: **Open WebUI for the chat UI, over `/v1`, in the
box; a modern MCP client for the MCP surface, outside it.**

## 5. Day-one `bb` tasks

`bb tasks` lists every task with a one-line summary, but there are ~73 of
them and nothing there says which ones matter on day one. These ~8 cover
almost everything you'll need in your first days:

| Task | What it does |
| --- | --- |
| `bb setup` | Interactive wizard: detects which database backend your environment selects (and says so), checks the env vars that backend needs, checks DB connectivity, helps bootstrap the first admin user, and picks the LLM provider — including a local OpenAI-compatible server (§4a). Works on the `DATAHIKE_FILE_PATH` path — no Postgres variables required. Runs non-interactively too: `bb setup < /dev/null` takes the default answer at every prompt, so it is safe to script. |
| `bb dev` | Starts the dev server **backend only** — Jetty + REPL, no Electric client build, default port 8081. Needs no Hyperfiddle activation token. Serves a prebuilt client if one is present. (#330) |
| `bb dev-fullstack` | The server **plus** the Electric client watch build — what `bb dev` did before #330. Needs the activation token; use it for UI work with hot reload. |
| `bb build-client` | Build the Electric client once (~100 s), as a release bundle. **Needs no activation token.** Run it once and `bb dev` can serve the admin UI. |
| `docker compose -f docker-compose.dev.yml up -d --wait` | Bring up a local Typesense (§3). |
| `bb port-forward` | SSH to the shared Typesense/Postgres box — optional, for borrowing a *populated* corpus. For a local empty instance use the dev stack instead (§3). |
| `bb test` | Run the unit test suite. |
| `bb test-config` | Run just the config-resolution test namespaces (faster inner loop for config work). |
| `bb lint` | Run `clj-kondo`. |
| `bb config-get` / `bb config-set` | Read/write a single resolved config value against the running config DB — `bb config-set` also pokes `bb dev`'s in-memory cache so changes show up within ~5s without a restart. |
| `bb dump-import` | Import a full system dump (YAML/JSONL) from a directory — the bulk counterpart to the single-file `bb migration-import` used in §4. |

## 6. Where to look when stuck

- **[`docs/system-overview.md`](system-overview.md)** — the authoritative
  description of how the system is built: modules, data flow, design
  choices. Start here for "how does X work."
- **`CLAUDE.md`** (repo root) — Electric/Hyperfiddle UI patterns (event
  handling, `e/Token`, the pending-signal pattern) and the retrieval
  evaluation harnesses.
- **[`decisions/`](../decisions/)** — Architecture Decision Records. Read
  [`platform-runtime-dataset-config-roots.md`](../decisions/platform-runtime-dataset-config-roots.md)
  and [`agents-skills-and-datasets.md`](../decisions/agents-skills-and-datasets.md)
  first; [`skill-based-agentic-rag.md`](../decisions/skill-based-agentic-rag.md)
  for earlier background.
- **[`docs/runbooks/`](runbooks/)** — operational procedures (config
  export/import, the agents/skills/datasets cutover, the global config
  root).
- **[`plans/`](../plans/)** — work-in-progress design docs and experiment
  logs. **Not authoritative** — these capture in-flight thinking, not
  shipped behavior; treat anything here as a proposal, not a fact about the
  current system.

## 7. Known first-run friction (honest)

These are real, currently-shipped gaps — each is tracked as an item in the
[rough-edges inventory](../plans/proposed/release-v0.1-rough-edges-inventory.md)
and has a fix tracked in the packaging plan:

- **The flagship dev command is `bb dev`, not `bb admin-dev`.** If you see
  `bb admin-dev` referenced anywhere, it's stale — that task doesn't exist.
  (Inventory B1.)
- **§4 gets you to a live `/api/mcp` surface and stops at the first LLM
  call.** Measured on a clean-room run of the recipe, not inferred: `tools/list`
  returns the documented tools, `tools/call` executes the agent graph, and the
  first LLM call fails because the committed snapshot ships no LLM credentials
  at all — #279 removed the five secrets it used to carry encrypted, none of
  which a fresh install could open. The recipe supplies exactly one of them —
  `services.typesense.api-key-admin`, in §3 — which is why the failure lands at
  the LLM boundary rather than earlier. A clean import flags only that one
  (`1 unresolvable / 0 undecryptable` per tenant, `0 / 0` once §3 has run);
  `services.azure-openai.api-key` is not on the runtime's required-paths list,
  so its absence surfaces here, at the first LLM call, rather than at import.
  **This is a harder stop than the item above:** that one is a wrong name you
  can correct, this one is a resource a newcomer does not inherit. There are two
  ways past it: bringing your own Azure OpenAI credentials (§4 step 5), or
  [§4a](#4a-run-with-a-local-model--no-cloud-credentials-at-all), which runs the
  whole path — retrieval, the tool-calling agent loop, streaming, synthesis —
  from a local model with no cloud credentials of any kind. Verified by running
  it from a fresh worktree, not by reading the code; against the empty local
  Typesense a fresh install has, so what it establishes is that every stage
  executes, not that the answers are good.
