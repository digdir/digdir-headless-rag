# E2E: Open WebUI driving our agents

End-to-end test harness that runs the dev server and
[Open WebUI](https://github.com/open-webui/open-webui) in Docker Compose, then
drives the UI with Playwright. **Two chains, because an agent is reachable as
two different things:**

```
Playwright ──HTTP──▶ Open WebUI ──/v1──────────────────▶ digdir-rag   (agent as MODEL)
                                └──OpenAPI tool server──▶ digdir-rag   (agent as TOOL)
```

This is the regression net that exercises everything below the UI in
the same flow real users hit: auth, tool resolution, dataset scope,
conversation persistence, and SSE streaming.

> ### The MCPO container is gone — and that is the point of this file now
>
> The tool chain used to run through [MCPO](https://github.com/open-webui/mcpo),
> which bridges MCP to OpenAPI. **It cannot reach us any more.** MCPO opens with
> `initialize`; this server implements MCP `2026-07-28`, which has no handshake,
> so it answers `400`/`-32022` and MCPO's client crashes with an empty tool
> list. That broke silently on 2026-08-21 and nobody noticed for 11 days — see
> [How it broke, and what it cost](#how-it-broke-and-what-it-cost).
>
> The bridge existed only because the MCP→OpenAPI translation had to happen
> *somewhere*. **We own the server, so it happens here**:
> `GET /api/tools/openapi.json` renders the same `(agent, mode)` tools as an
> OpenAPI document, and Open WebUI consumes it as an external tool server with
> no bridge at all. One less container, one less unmaintained dependency, and a
> protocol-era problem we no longer inherit.
>
> Why not fix MCPO instead: [What fixing MCPO would have
> taken](#what-fixing-mcpo-would-have-taken--measured-2026-09-01).

## How it broke, and what it cost

MCP moved to `2026-07-28` on 2026-08-21 (`94bc692`). `server/e2e/` was last
touched 2026-05-28. Nothing tested the pair in between, so the demo was broken
for **11 days** before anyone ran it.

**It was not undetectable. It was uninvoked.** The Playwright suite as it stood
on 2026-09-01 caught it on the first try — `3 passed, 1 failed`, *"MCPO
discovers our MCP tools via OpenAPI"* with `Expected: > 0 / Received: 0`. The
detector was correct and sitting right next to the defect. `bb e2e:*` was
simply outside `bb test`, and the nightly job this file used to point at was
still written in the conditional: *"A future GH Actions step would…"*.

Two things made it invisible rather than loud:

- **`bb e2e:up` exited 0 and MCPO reported `healthy`** while serving nothing.
  Its healthcheck GETs `/openapi.json`, a directory page that returns `200`
  whether or not any MCP server connected behind it.
- **Nothing scheduled the one check that could tell the difference.**

Both are fixed: the suite runs in CI now ([CI gating](#ci-gating)), and the
container whose healthcheck lied is gone.

## What fixing MCPO would have taken — measured 2026-09-01

The obvious guess is "MCPO pins an old SDK, bump it." **That was tested and it
is wrong**, which is worth writing down because the pin invites it: MCPO's
`pyproject.toml` says `mcp>=1.17.0` with *no upper bound*, so a rebuild today
already resolves the modern-era `mcp` 2.1.1.

Building `mcpo==0.0.20` against `mcp==2.1.1` and walking the failures:

| # | What breaks | Kind |
|---|---|---|
| 1 | `streamablehttp_client` → `streamable_http_client` | rename |
| 2 | `McpError` → `MCPError` | rename |
| 3 | `streamable_http_client()` no longer takes `headers=` — auth now goes through a caller-supplied `httpx` client | signature change |
| 4 | Past those, `main.py` still calls `ClientSession.initialize()` — the v1-era low-level API — so it would reach the same `400` | **architectural** |

Rows 1–3 are mechanical; row 4 is the actual work. The handshake-free flow
lives in SDK v2's *high-level* `Client(server, mode="auto")`, so a real fix
means porting MCPO's session-lifecycle class and its two call sites onto that
API. Bounded — one class — but it is a fork we would own: **MCPO has had no
commit since 2026-02-27**, and Open WebUI has closed two dependabot PRs
bumping `mcp` to 2.x (#27938, #29380).

**None of this is our server's problem.** The same SDK at 2.1.1, driven with
`Client(mode="auto")` against `http://digdir-rag:8080/api/mcp`, negotiates
`2026-07-28`, lists 13 tools and dispatches a `tools/call` into the agent —
failing only on the empty corpus. See
[`docs/onboarding.md` §4b](../../docs/onboarding.md#4b-see-it-answer-in-a-chat-ui--open-webui).

Three options were on the table — do nothing and keep `/v1` only; fork and port
MCPO onto the v2 `Client` API; or serve the OpenAPI ourselves.

**We took the third.** It removes a class of problem rather than an instance:
no bridge container, no unmaintained dependency, and no SDK era to be on the
wrong side of. `digdir.api.routes.endpoints.openapi-tools` is a wire-format
adapter over the same `list-tools` / `invoke-tool` pair `/api/mcp` uses, so a
tool cannot exist on one surface and not the other.

## What this gives you

- **One command brings the stack up** — `bb e2e:up`.
- **Playwright drives a real browser** against Open WebUI — no scraping
  the chat as HTML, full client behavior with tool calls.
- **Reusable** — every future MCP-touching change can run this without
  per-feature integration plumbing.

## Status

Measured 2026-09-01, `bb e2e:up && bb e2e:test`: **14 passed, 1 skipped,
0 failed.** What each group covers:

| Group | Covers |
|---|---|
| stack health | `/up`, MCP `401` on an unauthenticated call |
| **modern MCP** | `server/discover` + `tools/list` with mirrored request-metadata headers, including the caching hints (`ttlMs`, and `cacheScope: private` on `tools/list`) |
| **`/v1/models`** | agents as MODELS — Open WebUI's model dropdown |
| **`/api/tools/openapi.json`** | agents as TOOLS — the surface that replaced MCPO: absolute paths, `operationId` == tool name, auth required, unknown tool is a 404 |
| agent skill-params | Layer-C, via `/api/debug/agent-resolution` |

The **modern MCP**, **`/v1/models`** and **tool document** groups are the ones
with regression value: they assert our own contract, so they go red when *we*
break it, which is the direction the 2026-08-21 move broke things in. Verified
non-vacuous by sabotage rather than by reading them — run with a bogus
`E2E_API_KEY` and they go red; unset, the suite exits `0`.

**The happy path (`10-tool-invocation.spec.ts`) still skips** without
production-equivalent credentials (Typesense reachable, an Azure OpenAI key, a
materialised dataset). Everything else runs without an LLM key.

## The ingest check

`bb e2e:ingest` runs a **real ingest** in the **shipped newcomer compose**
(`docker-compose.newcomer.yml`), plus
[`ingest/compose.override.yml`](ingest/compose.override.yml). The override is
the COMPOSE half of how the check differs from what a newcomer runs. **The
complete list is [below](#how-the-check-differs-from-what-a-newcomer-runs)**,
and it is longer. Nothing else in this README's stack is involved: the MCP /
Open WebUI chain above is a separate job.

```
bb e2e:ingest                    # build, then all three scenarios
bb e2e:ingest --no-build staged-store   # the mission's done-condition alone
bb e2e:ingest --no-build stub    # one scenario, reusing THIS tree's image (refuses if there is none)
bb e2e:ingest --keep             # leave the last stack up; prints its teardown command
bb e2e:ingest --keep-going       # record every failed check, and run every scenario (for sabotage)
```

**What concurrent runs on one machine share, and what they do not:**
- **Per run:** the compose project (`digdir-rag-ingest-<random>`, unless
  `INGEST_PROJECT` pins it, as CI does), and with it every container, network
  and volume name. Every host port is ephemeral; the harness asks compose which
  port it got. Two runs never share any of these. A fixed project name and fixed
  ports let two lanes' runs tear each other's stacks down twice in one day
 Measured since: two runs at once on one machine, each green on its
  own ports.
- **The server image, in three parts: what is COVERED, what is REFUSED, and what
  is DECLARED RESIDUAL.** Docker tags are machine-wide, so a fixed tag let
  concurrent runs from two DIFFERENT trees test each other's code. The
  tag is `digdir-rag-server:ingest-e2e-<hash>`. This is a statement about what
  the harness ENFORCES, not about every input in the world.
  - **COVERED, hashed into the tag:**
    - `server.Dockerfile`, and `.dockerignore` and
      `server.Dockerfile.dockerignore`, whichever exist;
    - EVERY ENTRY under each `COPY` source, read from disk, so untracked and
      ignored files count. Each entry is hashed by its path and its TYPE:
      - a directory, with its mode (an empty one is copied too);
      - a regular file, with its mode and bytes;
      - a symlink, with its TARGET STRING (Docker copies the link, dangling or
        not).
  - **REFUSED, before anything is built or run:**
    - in the Dockerfile: `ADD`; a `--mount` that can bind (bind is the default
      type); an escape or syntax directive; ANY line that starts no known
      instruction. `COPY` (case-insensitive) is the only context-reading form
      accepted;
    - in the tree: a top-level `COPY` source that is a symlink, and any entry
      that is not a file, a directory or a symlink;
    - in compose: any build but `server.Dockerfile` from the repo root with no
      other build key (`args`, `additional_contexts`, `target`, ...), and a
      service-level `platform`. This is GUARDED, not hashed: the hash covers
      the Dockerfile inputs, and this guard covers compose. Neither covers the
      other;
    - in the environment: everything except where the Docker daemon is and how
      to reach it (`PATH`, `HOME`, `USER`, `TMPDIR`, `DOCKER_HOST`,
      `DOCKER_CONTEXT`, `DOCKER_CONFIG`, `DOCKER_CERT_PATH`,
      `DOCKER_TLS_VERIFY`, `SSH_AUTH_SOCK`, `XDG_RUNTIME_DIR`). Nothing else a
      shell exports reaches any compose call. That includes
      `DOCKER_DEFAULT_PLATFORM`, which once built amd64 under an arm64 tag,
      interpolated names, and a bare `environment:` pass-through;
    - in the image: an OS or architecture other than the daemon's, checked
      after every build and on `--no-build`. `DOCKER_CONFIG` and `HOME` stay
      allowed. `DOCKER_CONFIG` carries buildx's CURRENT builder, so a shell can
      build this tag with another builder (measured); `HOME` carries it by
      construction, because `DOCKER_CONFIG` defaults to `$HOME/.docker` (not
      measured). The platform assertion holds whichever builder ran.
  - **DECLARED RESIDUAL, not hashed:**
    - **What a build FETCHES:** the floating `FROM` tags, apt packages, and
      Maven and npm dependencies. They can differ between two builds, at
      different times or through different builders (each keeps its own
      cache), and the later build takes the tag.
    - **The builder,** apart from its platform. A shell's `DOCKER_CONFIG` can
      select another buildx builder (measured), and so can its `HOME` (by
      construction, as `$HOME/.docker`; not measured). Only the image's OS
      and architecture are asserted. On a containerd image store, a tag that
      points at a multi-platform INDEX reports the native platform, so it would
      pass (measured). A compose build loads a single-platform image unless
      `platforms` is set, and that key is refused.
    - **File timestamps.** They DO differ between checkouts, and they DO reach
      the image: the built `app.jar` carries the checkout mtimes of the
      project's own entries (measured with `jar tvf`), so its bytes differ.
      They are not hashed, because every checkout would get its own tag.
      Whether any difference in behaviour follows was not measured.
    - **Extended attributes.** A `user.*` xattr IS copied into the image
      (measured), and it is NOT hashed.
    - **Not an input: ownership.** `COPY` sets root: 501 on the host, 0:0 in
      the image (measured).

  Editing the harness, the override or `ingest.env` does not change the tag, so
  `--no-build` keeps working for sabotage runs; with no image for this tree, it
  refuses.
- **Shared, and NOT isolated:**
  - Docker's build cache, CPU and disk. That is contention, not correctness.
  - Old `ingest-e2e-<hash>` images accumulate, one per set of server inputs
    built. Remove them with `docker image ls 'digdir-rag-server'` and
    `docker image rm`.

It follows the README's own newcomer steps:
1. the four setup `-main`s, with the server stopped;
2. a scripted admin login;
3. one materialization of a **fixture corpus**: four short documents of our own
   text in [`ingest/corpus/`](ingest/corpus/). **No retry.** Typesense's
   embedding model is pre-warmed first, so the product gets one attempt;
4. assertions on what happened.

The LLM is an OpenAI-compatible **stub**
([`ingest/llm-stub/stub.py`](ingest/llm-stub/stub.py)) that logs every request
it receives.

| scenario | asserts |
|---|---|
| **`stub`**: phrase generation routed through `services.llm.*` to the stub | `completed`; `documentsProcessed = 4` **exactly**, and Typesense's own count = 4, read directly, not from the product's report; one stub request per chunk, each about exactly one fixture document, with the configured model, `json_schema` and the **configured key** (compared by hash); a second run processes 4 again with **0** stub requests, and with the run's phrase cache wiped, a third asks **once per chunk again** (the positive control: the zero IS the cache); **each of two queries with exactly one right answer ranks its own document FIRST**, with 0 stub requests |
| **`staged-store`**: a REAL migration, run against a STAGED store, exported and restored into a fresh install, with a query working afterwards | see [The staged store](#the-staged-store-632-the-missions-done-condition): the migrations retract the staged definitions and keep their audit rows; the default backup restores into a fresh install with a faithful audit trail and nothing resurrected; the RESTORED dataset ingests exactly 4 documents and both probes rank their own document first |
| **`azure-no-key`**: Azure selected, endpoint and deployment set, NO key, and the openai-compatible side pointed at the stub | nothing processed; `documentsFailed = 4` **exactly**; the refusal fires, naming `services.azure-openai.api-key`; nothing reaches the stub, which a product that ignored the Azure switch WOULD reach |

### How the check differs from what a newcomer runs

Each line below is something a green run does NOT cover about a newcomer's path.
1. **The compose override.** It adds a fixture corpus instead of the fetched one,
   an LLM stub, and the LLM variables pointed at it. It replaces the root `.env`
   with `ingest/ingest.env`. It uses per-run container names, ephemeral
   loopback ports, and its own image tag keyed on the server's build inputs
   (`digdir-rag-server:ingest-e2e-<hash>`, so the shared `:newcomer` tag is never
   rebuilt).
2. **`ingest/ingest.env`.** It has test secrets, plus two things a newcomer does
   not have:
   - `DIGDIR_LOG_CONFIRMATION_CODES=true`, so the login page carries the code and
     no email is sent;
   - `RAG_TS_RETRIEVE_ENABLED=true`, a DEBUG endpoint, which is what the query
     uses.
3. **The harness's own steps:**
   - Typesense's embedding model is pre-warmed before the product is called
    **This steps around the one first-run failure a newcomer is known to
     hit.**
   - The admin logs in, and triggers the run, over HTTP rather than through the
     admin UI. The UI calls the executor directly.
4. **The shell environment is shut out.** Every compose call sees only the
   daemon-location variables listed under "The server image" above. Everything
   else a shell exports is dropped, and so is any name the compose files
   interpolate. Compose interpolation prefers the process environment over
   `--env-file`, so a developer's exported `TYPESENSE_API_KEY_ADMIN` used to
   split the stack.

Every scenario's full server log is saved to
`ingest/.logs/<project>-<scenario>-digdir-rag.log` (gitignored, and uploaded by
CI). The harness counts known ERROR-level signals in it. A count is
only evidence because the whole log is kept.

### Measured 2026-09-23, locally, after the e2e real-ingest change review: every check passed in both scenarios

**The final run on the committed code: 57 checks passed, 0 failed, 1 FINDING.** After it, the merged head
(`dc2d10b8`) changed only two README sentences, and the harness does not read the README. Two more full runs went at once on one machine, each green on its own ports.
Each check the e2e real-ingest change review asked for was sabotaged, and each break went red on the check it targets. The e2e real-ingest change has the
table, and it names the checks that were NEVER observed red: most are setup and liveness steps, but not all.

- **The embedding-model pre-warm** took one attempt in every run: 5–50 s locally, and 3–8 s in the two CI runs whose
  logs were read for it (8 s and 4 s at `3fd0d0af`, 4 s and 3 s at `2d11f0a7`). The create call BLOCKS while Typesense downloads the model, and then succeeds; it does not fail fast. So the embedding-model download issue's
  "Model not found" is presumably the PRODUCT's client giving up first. That is **not verified**, and the harness's
  retry branch has **never executed**.
- **#556 did not reproduce.** The fixture ingested exactly 4 documents, and Typesense holds 4.
- ⚠ **A finding, recorded and not asserted:** with Azure selected and no key, every document is refused, yet the
  execution reports **`status: completed` with `errorMessage: null`**. The reason appears **only in the server log**.
  That is the newcomer-compose empty-corpus issue's family: a clean-looking run that ingested nothing. Filed as **#634**. It is recorded, not asserted, so
  it does not break the build. ⚠️ **Trigger: when the silent Azure no-key run issue is fixed, the harness's recording becomes an assertion.**
- **An ERROR the product logged in every ingest whose full log was kept:** `executor.clj:123`,
  `update-progress-from-signal`, calls `(name nil)` on a signal with no `:id`. Telemere's handler wrapper catches it,
  and that signal's progress update is lost. In the final run it was counted 2× in `stub` and 1× in `azure-no-key`.
- **Intermittent:** `:pipeline/progress-flush-error`, an `InterruptedException` at the end of an execution
  (`executor.clj:611`). It was counted 1× in some runs' `stub` logs, and 0× in others, the final run's included.

### What a green run ESTABLISHES

At the tested head, with the fixture corpus and the stub:
1. **The newcomer setup can ingest.** The README's setup (bootstrap →
   first-admin → demo-tenant → demo-dataset) produces a stack that ingests. It is
   the shipped compose, apart from the override's lines.
2. **An admin can trigger a materialization** over the console HTTP route, and
   it reaches the executor the admin UI also calls.
3. **The folder loader ingests exactly the fixture.** The product reports it and
   Typesense confirms it independently.
4. **Phrase generation reaches the openai-compatible provider** configured
   through `services.llm.*` (via the env bridge), with the configured model and
   the `json_schema` format. That is Phase 3 of the provider-resolver change's routing, measured.
5. **The local phrase cache** serves a second run with zero LLM calls, and the
   zero is the cache. Wiping the run's cache makes a third run ask once per chunk
   again.
6. **Retrieval relevance, at top-1.** For two queries that each have exactly
   one right answer in the fixture, hybrid retrieval ranks that document FIRST.
   At four documents this is a real relevance claim: every query returns all four
   chunks, so only the order can be right or wrong. It also shows that the
   tenant config, the collection naming and the embedding model agree between
   ingest and query.
7. **With Azure selected and no key, the refusal fires, names the path**, and
   nothing is indexed.

### What a green run does NOT establish. Read this BEFORE reading green as "ingestion works"

- **Any real model's behaviour, or answer quality.** The stub is deterministic,
  and its phrases carry no document signal at all.
- **Ranking quality beyond top-1** on two probe queries over four documents. It
  says nothing about ranking on a real corpus.
- **The Azure HAPPY path.** CI has no key, so only the no-key refusal runs.
- **That the refusal is REPORTED.** The Azure scenario passes while the run says
  `completed` with no error message (the finding above).
- **The demo corpus, or its committed warm cache (tier 2).** The fixture is not
  in the archive, and the demo text is Wikipedia's, so it cannot be committed.
  "A newcomer pays zero calls" stays unmeasured here.
- **The agent loop, MCP, `/v1`, or Open WebUI.** The query is retrieval-only,
  over the debug retrieve endpoint. MCP and Open WebUI are `e2e-stack`'s job.
- **The admin UI's own door.** The UI calls `execute-pipeline-async!` directly
  (`pipeline/ui/pipelines.cljc:775`). This check drives the console HTTP route to
  the same function, so the UI's wrapper and its authorization are not covered.
- **#555 itself.** The pre-warm steps around it on purpose.
- **Upgrades, or an existing volume.** Every scenario starts from empty volumes.
- **Export and import, in `stub` and `azure-no-key`.** Neither exports or
  imports. A fresh install's definition-retraction migrations retract NOTHING
  (they are marker-based one-shots, and a fresh store never held those
  definitions), so booting those stacks says nothing about the retracted-definition backup issue. The
  `staged-store` scenario is the one that does, and it has its own list of what
  it does NOT establish ([The staged store](#the-staged-store-632-the-missions-done-condition)).
- **The documented cold start.** No scenario imports the shipped
  snapshot.
- **Production, or the the production host deploy.** A different compose, secrets, TLS and
  volumes.

## The staged store: the mission's done-condition

> **"A REAL migration, run against a STAGED store, exported and restored into a fresh install, with a query working
> afterwards."**

**The migration is real. The export and the import are real. THE STARTING STATE IS CONSTRUCTED BY US.** "We verified a
real deployment backup restores" would be FALSE: no real deployment's backup is involved.

`bb e2e:ingest staged-store` runs it. It uses one compose project and two stacks, one after the other.

**Stack A:**
1. **Stage.** Nothing has booted on the store yet. [`staged/stage.clj`](ingest/staged/stage.clj), run by the image's
   own jar, writes what a deployment from before the April/May retraction migrations held:
   - 9 definitions at paths that three of those migrations retract;
   - values for the 3 of those paths that may carry one, on a tenant of their own;
   - an audit row for each path.

   It opens the store directly, never through the product's connection. It then proves that nothing booted: there is
   no migration marker yet.
2. **Migrate, for real.** The README's setup steps run, and the bootstrap's first connection runs boot's own
   `prepare-store!`, which applies the migrations. The harness reads their log lines, then reads the store:
   - every staged definition is gone;
   - no value is left at those paths;
   - every audit row is kept, naming its path and referencing nothing. That is the shape that broke restores.
3. **Export.** The default backup, as `bb migration-export` takes it, WITH audit.

**Stack B** is the same project with EMPTY volumes. Only the backup survives, on the host.

4. **Restore into a fresh install.** No setup step runs here.
   - The harness checks that the store does not exist yet. The restore's first connection runs boot on the empty
     store, and then the import runs (on-conflict skip).
   - The audit script [`round_trip.clj`](../test/digdir/config/round_trip.clj), mounted into the container, judges the
     audit trail: every row present, no field lost, and a reference exactly where this store defines the path.
   - The import must report exactly the staged rows as arriving without a definition, and must resurrect none of them.
5. **Query.** The server starts, the harness's admin logs in, and the RESTORED dataset is materialized. Both retrieval
   probes must rank their own document first. Without the restored dataset, the query fails.

### What the staged store does NOT establish. Read this before reading green as "backups restore"

- **A real deployment.** A real export would have answered whether a genuine deployment differs from our staged
  version in a way nobody modelled. **We test the failure we know about, on a store we built to contain it.** An
  unanticipated audit row, a partially applied migration, or an artefact from before the v0.2 cut: the staged test
  passes and misses each of them. A real export stays worth taking; the project owner will run one if one turns up.
- **What the staging does not model:**
  - The rest of a pre-April deployment's definitions and values. Boot's current catalogue stands in for them.
  - The dedupe migration's duplicates. It retracts no path: one definition survives.
  - A value at the post-definition migrations' paths. Both migrations REFUSE to boot while one is active.
  - The staged definitions' metadata (category, service and so on), which is ours.
- **A restore under a different key.** Both stacks share `ingest.env`'s `CONFIG_MASTER_KEY`.
- **The `bb` tasks, or the admin UI's import.** The harness calls the functions `bb migration-export` and
  `bb migration-import` call (`digdir.import-export.system`), with the same options, not the tasks themselves.
- **An upgrade.** Stack A runs today's image from its first boot; no older image ever touched its store.
- **A restore that fails half-way.** Only a restore that succeeds is exercised.
- Everything the ingest check's own list says it does not establish: the stub LLM, the fixture corpus, and top-1
  ranking over four documents.

## Layout

```
server/e2e/
  README.md                          # this file
  docker-compose.e2e.yml             # digdir-rag + open-webui (containerised backend)
  docker-compose.host.yml            # open-webui only, pointed at a host `bb dev`
  .env.example                       # template — copy to .env and customize
  ingest/                            # the ingest check - see "The ingest check"
    compose.override.yml             # the COMPOSE half of how it differs (full list: "How the check differs")
    ingest.env                       # test values; replaces the root .env for this stack
    harness.clj                      # bb e2e:ingest - steps, checks, both scenarios
    corpus/*.md                      # the fixture corpus: 4 documents, our own text
    llm-stub/stub.py                 # OpenAI-compatible stub that logs what it is sent
    staged/                          # the staged store: scripts the image's own jar runs
      stage.clj                      #   the pre-migration state, written before anything boots
      inspect.clj                    #   what a store holds, read directly
      export.clj                     #   the real default backup (WITH audit)
      restore.clj                    #   the real import, then the backup-restore round-trip fix's round-trip audit
    .logs/                           # saved server logs per scenario (gitignored)
  playwright/
    package.json                     # @playwright/test
    playwright.config.ts             # browser config + base URL
    tests/
      00-stack-up.spec.ts            # modern-MCP, /v1 and tool-document guards
      10-tool-invocation.spec.ts     # happy path (skipped without credentials)
      20-agent-skill-params.spec.ts  # Layer-C: agent-scoped skill-params
```

## Usage

Prerequisites: Docker Desktop 4+ (or Colima with docker-compose), Node 22+, Babashka.

### 1. Configure the local `.env`

```bash
cp server/e2e/.env.example server/e2e/.env
```

`server/e2e/.env` is gitignored. The same `E2E_API_KEY` value in there
is consumed by:

- **`digdir-rag`** — `digdir.e2e.seed/maybe-seed!` runs at boot, seeds
  the built-in agents, and stores this exact key in the config DB.
  Idempotent on subsequent boots.
- **`open-webui`** — passed through the compose file into both
  `OPENAI_API_KEYS` and the `TOOL_SERVER_CONNECTIONS` entry, so the UI
  authenticates with the same key the dev server seeded.

Set `AZURE_OPENAI_API_KEY` / `AZURE_OPENAI_ENDPOINT` in `.env` too if
you want the `10-tool-invocation.spec.ts` happy path to actually return
text instead of skipping.

### 2. Bring the stack up

```bash
bb e2e:up
```

Builds and starts two services:

| Service      | Port | Notes                                                  |
|--------------|------|--------------------------------------------------------|
| `digdir-rag` | 8080 | The dev server. Auto-seeds on boot from `E2E_API_KEY`. |
| `open-webui` | 3030 | Open WebUI. First boot creates an admin user.          |

Open WebUI lands at `http://localhost:3030`, wired to **both** surfaces of the
backend. All three of these want the API key, as `X-API-Key` or
`Authorization: Bearer`:

| URL | What it is |
|---|---|
| `http://localhost:8080/api/mcp` | MCP, revision `2026-07-28` |
| `http://localhost:8080/v1/models` | agents as OpenAI models |
| `http://localhost:8080/api/tools/openapi.json` | agents as OpenAPI tools — filtered per key |

No out-of-band seed step is needed — the dev server's boot-time
`maybe-seed!` makes the harness self-contained.

### 3. Run the tests

```bash
bb e2e:test          # all tests
bb e2e:test stack-up # just the smoke test that doesn't need LLM creds
```

The first run downloads the Playwright browsers (~250 MB). Subsequent
runs reuse the cache in `~/.cache/ms-playwright`.

### 4. Tear it down

```bash
bb e2e:down
```

Stops and removes the containers and their volumes.

## CI gating

**This runs in CI now** — [`.github/workflows/e2e-mcp.yml`](../../.github/workflows/e2e-mcp.yml).
It has two jobs: `e2e-stack` (the chain in this file) and `e2e-ingest`
([The ingest check](#the-ingest-check-611)). On PRs, both run for changes to:
the MCP and `/v1` surfaces, the boot and e2e seeds, the stack definitions, the
loader, the pipeline, `llm/`, `config/`, `setup/`, `boot/`, `rag/`, the
retrieval and query-planner skills, the debug and console routes, `auth/`,
`server/deps.edn`, the committed warm cache, and what the staged store drives
(`import_export/`, `data/`, and the round-trip audit it mounts from the tests).
A path filter is per workflow,
so both jobs run on any of them. The workflow also runs daily at 03:47 UTC, and
on `workflow_dispatch`.

Two things about that workflow are not free choices:

- **It runs on `ubuntu-latest`, not the self-hosted runner.** The self-hosted
  container **deliberately does not mount the docker socket**
  ([`infra/ci-runner/README.md`](../../infra/ci-runner/README.md): *"Mounting it
  would let workflow code reach the production containers"*), so a three-container
  stack cannot run there at all.
- **It is not required for merge**, matching the reasoning `ci.yml` records for
  `build-client`: a new check's early reds are disproportionately its own
  defects, and `continue-on-error` would hide the real ones.

`bb e2e:*` is still **not** part of `bb test` — it is slow and needs Docker.
Locally the sequence is `bb e2e:up && bb e2e:test && bb e2e:down`. There is no
`bb e2e:seed`: seeding happens at server boot from `E2E_API_KEY`
(`digdir.e2e.seed/maybe-seed!`), which is what made the harness self-contained.

## Gotchas hit during initial setup

- **macOS keychain prompt loops** on `docker pull` even after "Always Allow". The fix is to remove `"credsStore": "osxkeychain"` from `~/.docker/config.json`. Public-image pulls from `ghcr.io` don't need keychain creds; the helper only matters if you `docker login`.
- **OpenWebUI on host port 3030, not 3000.** Most local node dev servers grab 3000; we default to 3030 (override with `OPENWEBUI_PORT=...` in `.env`). Worth knowing what the collision *looks* like: docker still reports the publish, and a probe of `localhost:3000` gets a `200` — from the other program. Check `/api/config`, which names itself, rather than trusting a status code.
- **`config.enable` is not optional in `TOOL_SERVER_CONNECTIONS`.** Open WebUI's `get_tool_servers_data` skips any connection without it, silently. The symptom is an empty tool list and nothing in the logs.
- **A tool-level error is a `200` here, deliberately.** Open WebUI turns any status `>= 400` into an opaque exception string, so `no_dataset_scope` and `missing_query` come back as a normal result with `status: "error"` — see `digdir.api.routes.endpoints.openapi-tools`.

*(The MCPO-specific gotchas that used to live here — its `--port` flag, the
`sed` substitution into a mounted config, its curl-not-wget healthcheck — went
with the container.)*

## Risks worth knowing

- **Open WebUI updates break selectors.** The Playwright tests rely
  on text/aria selectors rather than CSS classes, but UI rewrites
  can still bite. The compose stack also pins Open WebUI to a tag.
- **No LLM key in the dev workflow.** The default seed doesn't ship
  one — set `AZURE_OPENAI_API_KEY` and `AZURE_OPENAI_ENDPOINT` in the
  shell that runs `bb e2e:up` for the full happy path to pass.
