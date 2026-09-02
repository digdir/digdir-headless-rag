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
in a `.env` file.** Nothing in this repo reads `.env`: `mise.toml` carries no
`_.file` directive and no other loader picks one up, so a filled-in `.env` is
silently ignored and you meet the boot pre-flight several steps later with no
reason to suspect the file (#302). `.env.example` (repo root) is still worth
opening — it is the annotated catalog of every variable, grouped by when you
need it — but read it as a reference list, not as a file to copy into place.

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
2. **Import the committed config snapshot** — datasets, dataset-pipelines,
   config definitions, and the built-in agents, but **no API key**:

   ```sh
   bb migration-import config/system-import.normalized.20260821.json
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

The fallback is **dev-only and cannot be switched on in production**: it is
armed exclusively from `server/src-dev/dev.cljc`, and `src-dev` is on the
classpath of the `:dev` and `:test` aliases only — a production build carries
`src-prod` instead (see `server/deps.edn`). It also stays out of the way if you
*do* have credentials: when the email service is configured, the code is mailed
as normal, and the log fallback only kicks in if that send fails.

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
| `true` (shipped default) | wkok's Azure client (`:impl :azure`) | `services.azure-openai.api-endpoint` | `services.azure-openai.api-key` | `services.azure-openai.deployment-name` |
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
| Nothing changed yet — the shipped `use-azure-openai-api true` with no Azure key | `LLM request failed at iteration 0 (status 401): Interceptor Exception: status: 401` |
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
