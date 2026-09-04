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

## Layout

```
server/e2e/
  README.md                          # this file
  docker-compose.e2e.yml             # digdir-rag + open-webui (containerised backend)
  docker-compose.host.yml            # open-webui only, pointed at a host `bb dev`
  .env.example                       # template — copy to .env and customize
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
Daily at 03:47 UTC, on `workflow_dispatch`, and on PRs touching the MCP surface,
the `/v1` surface, the boot seed, or the stack definitions.

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
