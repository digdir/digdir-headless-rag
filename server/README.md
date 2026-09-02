# Digdir RAG Server

This directory is the Clojure/Electric application: the RAG-as-a-service admin UI, the
headless MCP API (`POST /api/mcp`), and the document-ingestion pipelines. It is a
**PROTOTYPE** — see the repo-root [`README.md`](../README.md) for the overall picture and
[`docs/system-overview.md`](../docs/system-overview.md) for how the system is structured
(modules, data flow, design choices).

For the API contract, start at [`docs/api/README.md`](docs/api/README.md) and
[`docs/api/getting-started.md`](docs/api/getting-started.md). For ingestion, see
[`docs/pipeline-architecture.md`](docs/pipeline-architecture.md) and
[`docs/PIPELINES-QUICKSTART.md`](docs/PIPELINES-QUICKSTART.md). For running tests, see
[`TESTING.md`](TESTING.md).

## Prerequisites

If using [Mise](https://mise.jdx.dev/) to manage tool versions, you'll need to install
libyaml first:

```shell
# macOS
brew install libyaml

# Ubuntu/Debian
sudo apt-get install libyaml-dev

# Fedora/RHEL
sudo dnf install libyaml-devel
```

## Running the app (dev)

From the repo root:

```shell
bb dev
```

This starts the Jetty web server and a REPL, listening on **http://localhost:8081** by default
(override with `HTTP_PORT`). Since #330 it does **not** run the Shadow-CLJS client build, and
therefore needs no Hyperfiddle activation token.

For UI work use `bb dev-fullstack`, which adds the Shadow-CLJS watch build: hot code reloading
works there — edit -> save -> see the app reload in browser. To serve the admin UI from `bb dev`
instead, build the client once with `bb build-client` (~100 s, no token needed).

**The API binds before the client build, deliberately** (#165/#182). The API does not depend on
the CLJS bundle, so it serves whether or not the client compiles. Two consequences worth knowing
before you conclude something is broken:

- **Give it about a minute.** Launch to serving API is roughly 50-60s. The reliable check is
  `curl` against the port, or the literal `digdir.api.http: ... http://0.0.0.0:<port>` line.
  Do *not* grep for the server name: shadow-cljs runs its own Undertow on 9630, so `Undertow`
  is a false positive, and a serving run can show zero `Jetty` lines.
- **An Electric activation prompt is expected and is not a failure — in `bb dev-fullstack`.**
  That task prints `Please sign up or login to activate: https://hyperfiddle-auth.fly.dev/...`
  and the *client* build waits there. It gates the admin UI only — the API serves anyway.
  **`bb dev` never reaches that prompt**, because it does not run the watch build (#330), and
  neither `bb build-client` nor a production build (`clj -X:build:prod uberjar`) is gated at
  all: the Electric build hook is on the `:dev` build only. Measured in
  `docs/investigations/330-dev-client-decoupling-measurement.md`.

### Running two dev servers at once

You can. Set `HTTP_PORT` per worktree for the API; **shadow-cljs needs nothing** — if 9630 is
taken it logs `TCP Port 9630 in use.` and binds 9631, then 9632, and so on. Its nrepl and
socket-repl failures are caught and logged rather than fatal, so no port in the dev-server
startup path is a hard stop.

One caveat, and it is the reason to still say which port you are on: **9630 is then ambiguous.**
Browsing `localhost:9630` reaches whichever dev server started first, not necessarily yours.
The shadow-cljs dashboard shows the build it is attached to, so check that before trusting what
you see there.

The Electric root function is
[`src/digdir/ui/main.cljc`](src/digdir/ui/main.cljc) (`digdir.ui.main/electric-boot`).

See the repo-root README's "Quick start" section for required environment variables
(`DATAHIKE_FILE_PATH` or `ADH_POSTGRES_*`, `JWT_SECRET`, `CONFIG_MASTER_KEY`, an LLM API
key).

## Building & running in production

```shell
# Build the uberjar (also builds the client)
clj -X:build:prod uberjar :build/jar-name "app.jar"

# Run it
java -cp target/app.jar clojure.main -m prod
```

`bb server-jar` (run from the repo root) wraps this exact sequence. The prod entrypoint is
`prod/-main` (`src-prod/prod.cljc`), listening on **http://localhost:8080**.

To build the client only, without the uberjar step:

```shell
clj -X:build:prod build-client
clj -M:prod -m prod
```

## License

Electric v3 is **free for bootstrappers and non-commercial use,** and otherwise available
commercially under a business source available license, see: [Electric v3 license
change](https://tana.pub/lQwRvGRaQ7hM/electric-v3-license-change) (2024 Oct). License
activation is experimentally implemented through the Electric compiler, requiring
**compile-time** login for **dev builds only**. That means: no license check at runtime, no
login in prod builds, CI/CD etc, no licensing code even on the classpath at runtime.
