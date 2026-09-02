# E2E: OpenWebUI driving our MCP server

End-to-end test harness that runs the dev server, [MCPO](https://github.com/open-webui/mcpo)
(MCP-to-OpenAPI proxy), and [Open WebUI](https://github.com/open-webui/open-webui)
in Docker Compose, then drives the UI with Playwright. The chain:

```
Playwright  ──HTTP──▶  Open WebUI  ──OpenAPI──▶  MCPO  ──MCP/HTTP──▶  digdir-rag /api/mcp
```

This is the regression net that exercises everything below the UI in
the same flow real users hit: auth, tool resolution, dataset scope,
conversation persistence, and SSE streaming.

## What this gives you

- **One command brings the stack up** — `bb e2e:up`.
- **Playwright drives a real browser** against Open WebUI — no scraping
  the chat as HTML, full client behavior with tool calls.
- **Reusable** — every future MCP-touching change can run this without
  per-feature integration plumbing.

## Status

The compose stack and Playwright scaffold are in this branch.
**Running the full happy-path test requires production-equivalent
credentials** (Typesense reachable, an Azure OpenAI key, a seeded
dataset). The default `bb e2e:up` brings up the stack so you can
verify the wiring even without a working LLM key — health-check tests
pass against the unauthenticated MCP endpoint and against MCPO's
OpenAPI schema discovery.

## Layout

```
server/e2e/
  README.md                          # this file
  docker-compose.e2e.yml             # the three services
  mcpo/
    config.json                      # MCPO -> /api/mcp config (reads ${E2E_API_KEY})
  .env.example                       # template — copy to .env and customize
  playwright/
    package.json                     # @playwright/test
    playwright.config.ts             # browser config + base URL
    tests/
      00-stack-up.spec.ts            # health-check: services reachable, tools discoverable
      10-tool-invocation.spec.ts     # happy path (skipped without credentials)
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
- **`mcpo`** — env-interpolated into `config.json`'s `X-API-Key`
  header, so MCPO can authenticate the same key the dev server seeded.

Set `AZURE_OPENAI_API_KEY` / `AZURE_OPENAI_ENDPOINT` in `.env` too if
you want the `10-tool-invocation.spec.ts` happy path to actually return
text instead of skipping.

### 2. Bring the stack up

```bash
bb e2e:up
```

Builds and starts the three services:

| Service          | Port  | Notes                                                    |
|------------------|-------|----------------------------------------------------------|
| `digdir-rag`     | 8080  | The dev server. Auto-seeds on boot from `E2E_API_KEY`.   |
| `mcpo`           | 8765  | MCPO. Reads `E2E_API_KEY` from the same `.env`.          |
| `open-webui`     | 3030  | Open WebUI. First boot creates an admin user.            |

Open Web UI lands at `http://localhost:3030`. MCPO's per-server
OpenAPI spec is at `http://localhost:8765/digdir-rag/openapi.json`
(the root `/openapi.json` is a directory listing). The MCP server is
at `http://localhost:8080/api/mcp` (`X-API-Key` required).

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

`bb e2e:*` is **not** part of `bb test`. It's slow (Docker pulls plus
browser startup) and needs Docker available. Run it on:

- PRs that touch `digdir.mcp.*` or `digdir.skills.invoke`
- Nightly via the existing GitHub Actions cron job (see `.github/workflows/`)

A future GH Actions step would: `bb e2e:up && bb e2e:seed && bb e2e:test && bb e2e:down`.

## Gotchas hit during initial setup

- **macOS keychain prompt loops** on `docker pull` even after "Always Allow". The fix is to remove `"credsStore": "osxkeychain"` from `~/.docker/config.json`. Public-image pulls from `ghcr.io` don't need keychain creds; the helper only matters if you `docker login`.
- **MCPO doesn't honor `MCPO_PORT` env var.** The `--port` CLI flag is the way. The compose entrypoint passes `--port 8765` explicitly.
- **Docker-compose does NOT substitute env vars inside mounted files.** `mcpo/config.json.template` has a literal `${E2E_API_KEY}`; MCPO's entrypoint runs `sed` to substitute it from the container's env before exec'ing MCPO. Substitution happens in the *YAML* but not in arbitrary mounted JSON.
- **OpenWebUI on host port 3030, not 3000.** Most local node dev servers grab 3000; we default to 3030 (override with `OPENWEBUI_PORT=...` in `.env`).
- **MCPO image has `curl`, not `wget`.** Healthcheck uses curl.

## Risks worth knowing

- **MCPO's Streamable-HTTP MCP support is recent.** If MCPO can't
  speak the wire shape we ship, `bb e2e:up` will report it in the
  MCPO logs (`docker compose logs mcpo`). The compose stack pins
  MCPO to a known-good tag.
- **Open WebUI updates break selectors.** The Playwright tests rely
  on text/aria selectors rather than CSS classes, but UI rewrites
  can still bite. The compose stack also pins Open WebUI to a tag.
- **No LLM key in the dev workflow.** The default seed doesn't ship
  one — set `AZURE_OPENAI_API_KEY` and `AZURE_OPENAI_ENDPOINT` in the
  shell that runs `bb e2e:up` for the full happy path to pass.
