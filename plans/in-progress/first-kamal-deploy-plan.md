# First Kamal Deploy Plan

Date: 2026-04-22
Updated: 2026-05-08

## Goal

Get the server app deployed via Kamal for the first time to
`rag.digdir.cloud` (host `5.75.220.232`), from the current branch
`release-v0.1-details`.

## Status as of 2026-05-08

Code-side preparation is complete. The plan is ready for the operational
verification + deploy steps (Phases 4-5 below). Phases 1-3 are kept here
for the audit trail; their work has shipped.

| Phase | Status | Notes |
|---|---|---|
| 1. Fix Dockerfile paths | ✅ done | `server.Dockerfile` uses `server/` prefixes; no `admin/` or `shared/` references remain. |
| 2. Complete env/secret wiring | ✅ done | `env.secret` in `deploy.yml` covers DB creds + `CONFIG_MASTER_KEY` + `JWT_SECRET` + `ADMIN_USER_EMAILS` + `AUTH_SECURE_COOKIES` + `RATE_LIMIT_TRUST_X_FORWARDED_FOR`. `KAMAL_REGISTRY_PASSWORD` is in `registry.password` (not env.secret) so it doesn't leak into runtime. |
| 3. Pick a secrets layout | ✅ done | **Option A** in place: single `.kamal/secrets-common`, deploy via `kamal deploy` (no destinations). |
| 4. Verify operational preconditions | ✅ done | DNS, SSH, registry login, target host all verified 2026-05-08. Volume-mount conflict surfaced + fixed. |
| 5. Run the first deploy | ⛔ blocked | First attempt 2026-05-09 failed at the in-image client uberjar step with `IndexOutOfBoundsException: Method code too large!` during Electric `e/defn` macroexpansion. Pre-existing — same failure on the parent commit `4d582e6` and reproduces locally with `clojure -X:build:prod uberjar`. See "Build blocker" below. |

## Out of scope for this plan

- Dockerfile size/multi-stage optimization (deferred — see "Deferred").
- CI-side build; `builder.remote` on the prod host is acceptable for v1.

## Phase 4 — Operational preconditions

Verify before running `kamal deploy`. None of these are code changes —
they're checks against the live infrastructure.

**Status as of 2026-05-08**: all checks passed. The volume-mount conflict
discovered during this phase has been addressed by switching `deploy.yml`
and `deploy.test.yml` to a service-specific host path
(`/opt/digdir-headless-rag/cache` and `/opt/digdir-headless-rag-test/cache`).

### DNS

`rag.digdir.cloud` must resolve to `5.75.220.232`. If DNS is wrong, Let's
Encrypt issuance inside kamal-proxy will fail and block first boot.

```sh
dig +short rag.digdir.cloud
# expected: 5.75.220.232
```

### SSH

`ssh root@5.75.220.232` must succeed with the deploying user's key. The
same host is both `servers.web[0]` and `builder.remote` in `deploy.yml`,
so one key serves both roles.

```sh
ssh root@5.75.220.232 -o BatchMode=yes 'echo ok'
# expected: ok
```

### Registry

`docker login altinnaicontainers.azurecr.io` with username
`digdir-rag-deploy` and the password in `.kamal/secrets-common`
(`KAMAL_REGISTRY_PASSWORD`) must succeed. Test from the build host before
the first deploy:

```sh
docker login altinnaicontainers.azurecr.io \
  --username digdir-rag-deploy \
  --password-stdin <<< "$KAMAL_REGISTRY_PASSWORD"
```

### Target host

- Docker installed on `5.75.220.232` (verified: v28.3.3).
- `kamal-proxy` is already running on the host from prior deployments
  (`basecamp/kamal-proxy:v0.9.0`); `kamal setup` will see this and skip
  the proxy install.
- `/opt/digdir-headless-rag/cache` will be created by Docker on first
  run. We use a service-specific host path so cache state isn't shared
  with the other Kamal apps already deployed to this host (notably
  `electric3-app-template-web*` mounts `/opt/admin/cache` and
  `/opt/admin-test/cache`). Pre-deploy verification confirmed no
  `digdir-headless-rag` containers exist.
- Disk pressure: target host is ~85% full (≈83 GB free). Enough for the
  first build (~2-3 GB) but a separate storage audit is worth doing
  later — `docker image prune -af` reclaimed less than 1 GB on
  2026-05-08, suggesting most usage is volumes/live images.

### Anthropic credentials

Production traffic does not call Anthropic directly (only via
Azure/OpenAI), so the `ANTHROPIC_API_KEY` env var doesn't need to be
set. `server/src/digdir/llm/anthropic.cljc:27` falls back to the
literal string `"Not set"`; if Anthropic is ever called from this
deploy it'll 401, surfacing the missing config loudly.

## Build blocker (added 2026-05-09)

The first `bb deploy-server prod` attempt failed inside the Docker image
build at the cljs release step:

```
IndexOutOfBoundsException: Method code too large!
File: server/src/digdir/config/ui.cljc:554:1     ;; (e/defn Modal …)
File: server/src/digdir/playground/ui.cljc:222:1 ;; (e/defn SourceTitleMarkdown …)
```

The JVM bytecode for the macroexpanded form exceeds the 64KB-per-method
limit. Reproduces locally with `clojure -X:build:prod uberjar` from
`server/`.

What we ruled out during 2026-05-09 investigation:

- **Not a regression from this branch.** Same error on parent commit
  `4d582e6` (before any of the dump-flow work).
- **Not a `digdir.rag.typesense` load issue.** That separate cljs build
  bug — `namespace 'digdir.rag.typesense' not found` from a load-time
  `cfg/get-platform-value` NPE — was masking the bytecode error in the
  first attempt. Fixed in commit `7ca1569` (broaden top-level
  `Throwable` catch); typesense now loads cleanly under cljs build.
- **Not "first e/defn body too large".** Truncating `config/ui.cljc` to
  contain *only* `Modal` (a ~40-line component) still produced the same
  error at line 554. The bloat is not in `Modal`'s body.
- **Not the in-file `e/defn` count.** Removing the entire ~1100-line
  diagnostics block (`ConfigTreeDiagnostics`/`ConfigTreeNodeCard`/
  `RuntimeTraceDiagnostics`/etc., all currently dead code rendered as a
  "Diagnostics Temporarily Disabled" placeholder) did not change the
  error.
- **Decomposition within the file does not help.** Extracting
  `ConfigTreeNodeCard` (~411 lines) out of `ConfigTreeDiagnostics`
  produced no observable build improvement.

What this points at:

The bloat appears tied to the **transitive Electric dependency graph**
of the failing namespaces. Both `config/ui.cljc` and `playground/ui.cljc`
require many other Electric namespaces (`config/ui/inheritance` (1452
lines), `config/ui/global`, `config/ui/audit`, `config/ui/permissions`,
`config/ui/api-keys`, `skills/ui`; `playground/ui/components`,
`playground/ui/observability`, etc.). Smaller leaf namespaces in those
trees build cleanly. So the working hypothesis is that the macroexpansion
of any `e/defn` in a namespace that requires many large Electric
namespaces inflates past 64KB regardless of the `e/defn`'s own body.

What needs to happen next (likely needs Electric expertise):

1. Confirm the hypothesis by inspecting the macroexpansion with
   `::print-clj-source` (set in `build.clj:32`-ish via `:config-merge`).
2. Identify which transitively required `e/defn`s contribute the most
   bytecode (probably the largest ones in inheritance.cljc:
   `ConfigInheritanceEditor` 421 lines, `InheritanceViewLegend` 313
   lines, `FocusedInheritanceEditor` 297 lines).
3. Decompose those into smaller `e/defn` helpers (Electric-friendly
   refactor; user's note: "decomposing large Electric e/defns is
   something we need to do from time to time").
4. If splitting individual `e/defn` bodies isn't enough, split the whole
   namespace into multiple Electric files so the failing site's
   transitive dep graph shrinks.

Until this is resolved the prod build can't produce an image. `bb test`
and dev mode still pass, so the rest of the codebase is healthy.

## Phase 5 — Deploy

From a clean worktree on `release-v0.1-details`:

```sh
kamal setup          # first-time: provisions kamal-proxy on the target
kamal deploy         # subsequent updates
```

`bb.edn` exposes a wrapper task `bb deploy-server prod` that runs
`kamal deploy --config-file=deploy.yml` directly — works for the first
deploy too (no `--destination` flag needed for Option A).

Watch `kamal app logs -f` during the first boot. Expected successful
signals:

- `prod/-main` logs the merged config (`server/src-prod/prod.cljc:28`).
- Electric manifest assertion passes (`prod.cljc:29`).
- HTTP listener comes up on `0.0.0.0:8080`.
- kamal-proxy reports the cert was issued for `rag.digdir.cloud`.

## Verification

After `kamal deploy` returns success:

1. `curl -I https://rag.digdir.cloud/` returns 200 (or the expected
   redirect to the admin app).
2. `bb exec-server prod bash` (or `kamal app exec --interactive --reuse
   bash`) connects; inside, `env | grep ADH_POSTGRES_URL` confirms
   secrets reached the container.
3. A direct app check passes, not just proxy connectivity. Hit the
   admin UI or a known endpoint and confirm the response body matches
   the expected app shell rather than an error page.
4. Admin login flow works end-to-end for an address in
   `ADMIN_USER_EMAILS` — proves `JWT_SECRET` and `CONFIG_MASTER_KEY` are
   live, and Scaleway TEM credentials in DB config are reachable for
   sending the login code.
5. A Playground conversation renders — proves the DB connection,
   Typesense connectivity, ColBERT API, Azure deployment listing, and
   the reactive pipeline are all intact end-to-end.

## Risks / Rollback

- **First-deploy failures are usually cert-related.** If Let's Encrypt
  fails, re-check DNS, wait out rate limits, and retry. Kamal retries
  issuance automatically on container restart.
- **Build pressure on the prod host.** `builder.remote: ssh://root@5.75.220.232`
  means the very first build pulls JDK base images and all Clojure
  deps on the target. Expect the first `kamal deploy` to run noticeably
  longer than subsequent ones and consume ~2-3 GB of disk for image
  layers.
- **Rollback:** `kamal rollback` reverts to the previous container; on
  the very first deploy there is no prior version, so rollback means
  `kamal app stop` + repair + retry.
- **Login flow depends on Scaleway TEM** for the confirmation-code
  email. If `services.scaleway-tem.api-key` / `project-id` aren't seeded
  in the production DB before the first admin login attempt, the login
  flow will fail silently from the user's POV (the email won't arrive).
  Either pre-seed via `bb config-set`, or accept that the first login
  will need a manual config write afterwards.

## Deferred

Not part of this first deploy; revisit after we have something running:

- Convert `server.Dockerfile` to a proper multi-stage build (JDK for
  build, JRE for runtime) and strip the debugging tool pile (`vim`,
  `telnet`, `strace`, `tmux`, `nano`, `htop`, `lsof`, `net-tools`,
  `iputils-ping`, `dnsutils`, `traceroute`, `procps`) — likely 1-2 GB
  smaller.
- Add `apt-get clean && rm -rf /var/lib/apt/lists/*` after the install
  to stop shipping apt metadata.
- Tighten `.gitignore` from `.kamal/` to `.kamal/secrets*` so future
  `.kamal/hooks/` can be checked in.
- Reconsider whether `deploy.yml` `proxy.response_timeout: 1000` is
  really wanted, or whether a lower bound is safer.
- Migrate to **Option B** (destinations) the moment we want a second
  environment live. Today's `deploy.test.yml` placeholder is unused.
- **Storage audit** on `5.75.220.232`. The host is at ~85% disk usage;
  `docker image prune -af` reclaimed less than 1 GB on 2026-05-08, so
  most of the consumption is live images and bind-mount volumes. Worth
  a separate pass to identify what can be archived or pruned.

## Open Questions

- **Is `deploy.test.yml` meant to be live soon, or should it move out
  of the repo root until needed?** Today it's a 5-line placeholder; not
  a blocker either way. (Volume mount path updated to
  `/opt/digdir-headless-rag-test/cache` for consistency with the
  production layout.)

## Resolved during planning

- ~~Phases 1-3 complete~~ (see status table above).
- ~~Volume mount path conflict~~ (`/opt/admin/cache` was shared with
  `electric3-app-template-web`; switched to `/opt/digdir-headless-rag/cache`
  before any state accumulated). Same fix applied to `deploy.test.yml`.
- ~~Anthropic credentials decision~~ (production doesn't call Anthropic
  directly; no `ANTHROPIC_API_KEY` needed).

## Changes since 2026-04-22 worth noting

These are code-level changes that affect or interact with the deploy:

- `digdir.config.validator` was removed (commit 8b2a092). The previous
  plan referenced `validator.clj:179` for the `JWT_SECRET` error; that
  error path is now in `digdir.config.core` directly.
- `bb.edn` deploy helpers reference `deploy.yml` (no longer
  `deploy-admin.yml`).
- New env vars `AUTH_SECURE_COOKIES` and `RATE_LIMIT_TRUST_X_FORWARDED_FOR`
  are read by `digdir.config.core/auth-secure-cookies?` and
  `rate-limit-trust-x-forwarded-for?`. Both have code-level defaults
  (false), so they don't *need* to be set, but `secrets-common` does
  set them to `"true"` for production.
- Several optional auth env vars (`AUTH_APPROVED_DOMAINS`,
  `AUTH_COOKIE_DOMAIN`, `AUTH_JWT_COOKIE_MAX_AGE_SECONDS`,
  `AUTH_JWT_TOKEN_EXPIRY_HOURS`, `AUTH_SESSION_MAX_AGE_SECONDS`) are
  available with code defaults; uncomment in `secrets-common` only if
  the deployment needs to override.
- Azure OpenAI deployment listing (commit 67ebd68) now calls the data-plane
  `/openai/deployments` endpoint per tenant for the playground model
  picker. This is an additional network dependency from the running
  container outbound to the configured Azure endpoint — should be
  routable from `5.75.220.232` since it's the same endpoint already
  used for chat completions.
