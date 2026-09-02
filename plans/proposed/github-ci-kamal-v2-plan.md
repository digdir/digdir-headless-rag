# GitHub CI with Kamal v2 — implementation plan

> **Status:** Proposed. Not yet approved or in progress.
> **Parent:** [`ship-mode-onboarding-plan.md`](ship-mode-onboarding-plan.md), Phase 4.
> **Goal:** Automate lint/test on every PR and deploy via Kamal v2 from GitHub Actions, with zero secrets in the repo and a single authoritative deploy path that matches today's `bb deploy-server` flow.
> **Baseline:** commit `0cf3032` on branch `release-v0.1-details`, 2026-04-22.

## 1. Resolved decisions (carried from parent plan)

| Decision | Value | Rationale |
| --- | --- | --- |
| Shipping line for v0.1 | `release-v0.1-details` (or squashed equivalent) | Plan-owner direction, 2026-04-22. |
| Build strategy | **Remote build** on the existing Hetzner host (`5.75.220.232`) | Warm cache; CI runner never needs Docker; one deploy path. |
| Companion repo for secrets | **No** | Shared secrets manager + GitHub Environments instead. |
| Kamal major version | **v2** | Native secret-fetch adapters, GH-Actions-friendly flow. |

## 2. Current state — what Kamal looks like today

Grounded in the current tree, not from memory.

- **`deploy.yml`** targets service `digdir-headless-rag`, image `digdir-rag-deploy/digdir-headless-rag`, Azure Container Registry `altinnaicontainers.azurecr.io` (user `digdir-rag-deploy`), single web host `5.75.220.232`, proxy at `rag.digdir.cloud`, SSL via Let's Encrypt, app port 8080, persistent volume `/opt/admin/cache:/app/cache`.
- **`deploy.test.yml`** overlays: proxy host `test.rag.digdir.cloud`, volume `/opt/admin-test/cache:/app/cache`.
- **Builder** is set to `ssh://root@5.75.220.232` — the app host doubles as the builder. `arch: amd64`, `dockerfile: server.Dockerfile`, build args include the current git SHA.
- **`env.secret` list in `deploy.yml`:** `ADH_POSTGRES_TABLE`, `ADH_POSTGRES_PWD`, `ADH_POSTGRES_URL`, `ADH_POSTGRES_USER`, `ADMIN_USER_EMAILS`, `CONFIG_MASTER_KEY`, `JWT_SECRET`. Plus `KAMAL_REGISTRY_PASSWORD` under `registry.password` (sourced from `.kamal/secrets` or `.kamal/secrets-common`).
- **`.kamal/secrets-common`** exists on the working tree with real values but is **gitignored** (`.gitignore:5`) and `git ls-files .kamal/` is empty. No secret has been committed. Rotation is not a prerequisite for this plan, but see §9.6.
- **bb tasks** for deploy are `bb deploy-server`, `bb boot-server`, `bb exec-server`, `bb logs-server`, `bb release-server-lock`. They call `kamal` with `--config-file=deploy.yml` and, for non-prod destinations, `--destination=<name>` (so `bb deploy-server test` loads `deploy.test.yml` as overlay via Kamal's destination mechanism).
- **`server.Dockerfile`** in the repo still references an `admin/` layout that predates today's flat `server/` tree (`system-overview.md:206`). Today's real builds work because the builder host has a different Dockerfile; the in-repo file is effectively decorative. §9.1 treats fixing this as a prerequisite.
- **No `.github/workflows/`** directory exists.

### Known drift to resolve *before* wiring CI

| # | Drift | Fix |
| --- | --- | --- |
| K0-a | `server.Dockerfile` references `admin/*`; does not build against current `server/` layout. | Replace with a working Dockerfile that builds against the current tree. CI cannot be trusted until the in-repo Dockerfile is the one Kamal uses. |
| K0-b | `README.md` still says `bb deploy-admin <destination>`; actual task is `bb deploy-server`. | Update README to match `bb.edn`. (Also covered by ship-mode-plan D6.) |
| K0-c | `system-overview.md §3.5` and `§7.4` reference `bb deploy-admin` and `deploy-admin.yml`. | Update the overview to reflect `bb deploy-server` / `deploy.yml`. (Also covered by ship-mode-plan D1.) |

These are **preconditions for Phase 1** below.

## 3. Target state — what we want

- Every PR to `release-v0.1-details` or `agentic-skills` gets lint + unit tests run automatically and reports status.
- Every push to `release-v0.1-details` triggers an auto-deploy to the `test` environment.
- Prod deploys are manual, require reviewer approval, and leave a GitHub Actions audit trail.
- Rollback is one-click via a separate workflow.
- `bb integration-test test` runs nightly against the live test environment.
- Secrets flow: **GitHub Environments → `kamal secrets fetch` → container env vars.** No secrets in the repo, in commit history, or in workflow logs.
- Forks of the repo cannot deploy anywhere — workflows guard on the owning repository.

## 4. Workflows

Four workflow files, in `.github/workflows/`:

### 4.1 `ci.yml` — PR lint + test

**Triggers**
- `pull_request` targeting `release-v0.1-details` or `agentic-skills`.
- `push` to `release-v0.1-details` and `agentic-skills` (post-merge verification).
- `workflow_dispatch` for manual reruns.

**Jobs**

1. **`lint-and-test`** (ubuntu-latest):
   - `actions/checkout@v4`
   - `jdxcode/mise-action@v2` — installs toolchain per `mise.toml` (Java 24, Clojure, node 22, yarn, babashka). **Single source of truth for versions.**
   - Cache `~/.m2`, `~/.gitlibs`, `server/.shadow-cljs`, `server/node_modules`. Keys include `hashFiles('server/deps.edn', 'server/yarn.lock', 'server/shadow-cljs.edn')`.
   - `cd server && yarn install --frozen-lockfile` (needed for shadow-cljs deps and RCF toolchain).
   - `bb lint`
   - `bb test-config`
   - `bb test`

2. **`client-build-smoke`** (ubuntu-latest, optional — see §10 Q1):
   - Same setup. Runs `clj -X:build build-client` to confirm the ClojureScript build compiles. Catches shadow-cljs breakage that unit tests miss.

**Concurrency:** `group: ci-${{ github.ref }}`, `cancel-in-progress: true` — so pushing a new commit to a PR cancels the stale run.

**Timeouts:** 20 min per job (generous; tune after baseline).

### 4.2 `deploy.yml` — deploy to test or prod

**Triggers**
- `push` to `release-v0.1-details` → auto-deploy to `test`.
- `workflow_dispatch` with input `destination: choice(test, prod)`.

**Safeguard:** first step in every job — `if: github.repository == 'digdir/<repo-name>'` so forks fail fast without secrets.

**Jobs**

1. **`deploy`** (ubuntu-latest):
   - **Environment:** `test` or `prod` based on input/ref. `prod` environment has required reviewers configured in GitHub Settings → Environments.
   - `actions/checkout@v4` with `fetch-depth: 0` (Kamal build args use `git rev-parse HEAD`; deploy.yml line: `VERSION: <%= \`git rev-parse HEAD\`.strip %>`).
   - `jdxcode/mise-action@v2` + install Kamal. (Kamal runs on Ruby; use `ruby/setup-ruby` or `gem install kamal -v '~> 2.0'`.)
   - **SSH key install:** `webfactory/ssh-agent@v0.9.0` with a deploy private key pulled from environment-scoped secret `KAMAL_DEPLOY_SSH_KEY`.
   - **`ssh-keyscan 5.75.220.232 >> ~/.ssh/known_hosts`** (or pin via a committed `known_hosts` file — see §10 Q4).
   - **Secret fetch:** run `kamal secrets fetch` (adapter depends on §5) to materialize `.kamal/secrets` locally in the runner. The workflow itself passes only the adapter auth token (e.g., `OP_SERVICE_ACCOUNT_TOKEN` for 1Password, `AZURE_CLIENT_ID`/`AZURE_CLIENT_SECRET` for Azure Key Vault).
   - **Deploy:** `bb deploy-server test` or `bb deploy-server prod`.
   - **Artifact:** upload `kamal` logs on failure for post-mortem.

**Concurrency:** `group: deploy-${{ inputs.destination || 'test' }}`, `cancel-in-progress: false` — never cancel a deploy in flight; queue instead.

**Timeouts:** 30 min (Kamal deploys can take 10+ min with a cold builder cache).

### 4.3 `rollback.yml` — targeted rollback / lock release

**Triggers**
- `workflow_dispatch` with inputs:
  - `destination: choice(test, prod)`
  - `action: choice(release-lock, rollback-to-version)`
  - `version` (optional, only used with `rollback-to-version`)

**Jobs**

1. **`rollback`**:
   - Same environment + SSH setup as deploy.
   - Branch on `action`:
     - `release-lock` → `bb release-server-lock <dest>`
     - `rollback-to-version` → `kamal rollback <version> --config-file=deploy.yml [--destination=<dest>]`

Rationale for keeping this separate: deploys and rollbacks have different reviewer rules and different blast radius. Keeping them in distinct workflow files makes the audit log easier to read.

### 4.4 `integration-test.yml` — nightly + on-demand

**Triggers**
- `schedule: cron: '0 3 * * *'` (03:00 UTC)
- `workflow_dispatch`

**Jobs**

1. **`integration-test`**:
   - `actions/checkout@v4` + `mise`.
   - Run `bb integration-test test`, supplying `RAG_API_BASE_URL=https://test.rag.digdir.cloud` and `RAG_API_TEST_KEY` from environment-scoped secret.
   - On failure, create or reopen a GitHub issue via `peter-evans/create-issue-from-file` or similar — so a missed nightly doesn't sit silent in Actions.

## 5. Secret management

### 5.1 Where each secret lives

| Secret | Source of truth | Accessed by | Form in CI |
| --- | --- | --- | --- |
| `KAMAL_REGISTRY_PASSWORD` (Azure ACR token) | Shared secrets manager | `kamal secrets fetch` → `.kamal/secrets` | Env var for Kamal |
| `ADH_POSTGRES_PWD`, `_URL`, `_USER`, `_TABLE` | Shared secrets manager | `kamal secrets fetch` | Env var for Kamal |
| `CONFIG_MASTER_KEY`, `JWT_SECRET` | Shared secrets manager | `kamal secrets fetch` | Env var for Kamal |
| `ADMIN_USER_EMAILS` | Shared secrets manager (low-sensitivity but grouped) | `kamal secrets fetch` | Env var for Kamal |
| `KAMAL_DEPLOY_SSH_KEY` (private SSH key for the deploy/builder user) | GitHub Environment secret | `webfactory/ssh-agent` | Loaded into ssh-agent only |
| Secret-manager auth token (e.g. `OP_SERVICE_ACCOUNT_TOKEN`) | GitHub Environment secret | `kamal secrets fetch` at runtime | Env var for the fetch step only |
| `RAG_API_TEST_KEY` | GitHub Environment secret (test env) | `bb integration-test` | Env var |

### 5.2 Choice of secret-manager adapter

Depends on parent-plan open question: *which secrets manager does Digdir use?* Candidates, ranked by plausibility:

1. **Azure Key Vault** — *recommended default if no other manager is standard.* Digdir already uses Azure Container Registry (`altinnaicontainers.azurecr.io`), so an Azure tenant exists. Kamal v2 has an Azure Key Vault adapter (or one can be written as a thin wrapper around `az keyvault secret show`). GitHub Actions has first-class OIDC federation with Azure — no long-lived `AZURE_CLIENT_SECRET` required.
2. **1Password** — widely used, well-documented Kamal adapter. Requires a service-account token stored as a GitHub secret.
3. **Doppler** — fast to set up if team already uses it for other services.
4. **Bitwarden / LastPass** — only if the team already standardizes on these.

**Recommendation:** Azure Key Vault with GitHub OIDC federation, unless Digdir already standardizes on a different manager. This piggybacks on the existing Azure tenant, avoids storing any long-lived credential in GitHub, and gives a single pane of glass for rotation.

### 5.3 GitHub Environments

Configure two environments in repo Settings → Environments:

- **`test`**
  - Secrets: `KAMAL_DEPLOY_SSH_KEY`, secret-manager auth (or OIDC role), `RAG_API_TEST_KEY`.
  - Protection rules: none beyond branch-scope (`release-v0.1-details` only).
- **`prod`**
  - Same secrets as `test`, but distinct rotation cadence.
  - Protection rules: **required reviewers** (at least two named humans), **wait timer** (0–5 min optional), branch restriction to `release-v0.1-details` only.

### 5.4 Repo-level (non-environment) secrets

None. Every secret should be environment-scoped so `test` leaking can't affect `prod`.

## 6. SSH access model

Today, `builder.remote: ssh://root@5.75.220.232` in `deploy.yml` expects root SSH. The parent plan flagged this as a risk.

**Near-term (Phase 1):** use the existing `root` SSH flow — CI supplies the same private key that developers use locally. This gets CI deploying without a host-side change.

**Desired state (Phase 2 of this plan):** create a dedicated non-root deploy user on `5.75.220.232` (e.g. `kamal`) with:
- Shell access restricted to the set of commands Kamal invokes (via `authorized_keys` `command=` constraint, or a sudo-rule allow-list).
- Its own key pair; CI's `KAMAL_DEPLOY_SSH_KEY` uses this user, not root.
- Rotation runbook documented (Phase 5 of parent plan).

Two keys vs one: since app host and builder host are the same machine today, one key suffices. If they split later, the deploy workflow can carry two keys; Kamal accepts distinct SSH config for `servers` and `builder.remote`.

**Known-hosts pinning.** `ssh-keyscan` at deploy time works but exposes CI to a host-key-change attack. Commit a pinned `.github/known_hosts` (fingerprint of the builder/app host) and have the workflow copy it into `~/.ssh/known_hosts`. Rotate when the host rotates its SSH key (rare).

## 7. Caching

Expected cold-build times (to be measured in Phase 1 baseline):

- Clojure deps resolution + compilation: ~2–5 min cold, ~20 s warm.
- `shadow-cljs` closure build: ~3–6 min cold, ~30 s warm.
- Kamal build on the builder host: ~5–15 min cold, ~30–60 s warm (since the host keeps layers between deploys).

**Cacheable in GH Actions runners (for `ci.yml` only — the deploy workflow builds on the remote host):**
- `~/.m2` — Maven / Clojure deps. Key: `hashFiles('server/deps.edn')`.
- `~/.gitlibs` — git-sha-pinned Clojure libs.
- `server/.shadow-cljs` — shadow closure cache.
- `server/node_modules` — yarn deps. Key: `hashFiles('server/yarn.lock')`.

The builder host's Docker layer cache handles the deploy side — nothing to do in GH Actions.

## 8. Observability of the CI itself

- Workflow badges in `README.md` (added under Phase 7 of the parent plan, once CI is green-stable).
- Slack or email notification on deploy failure via GH Actions' built-in notifications or a step that posts to a webhook. Which channel to target is a Digdir operational decision — defer until after the workflows are running.
- Deploy annotations: every successful deploy tags the commit with `deploy-test-<timestamp>` or `deploy-prod-<timestamp>` so the deploy history is visible in `git log`.

## 9. Rollout plan

Ordered steps. Each should be a separate PR so review stays scoped.

### 9.1 Precondition: fix `server.Dockerfile`

The in-repo Dockerfile must produce a working image against the current tree *before* CI can trust Kamal builds. This is drift K0-a from §2. Confirm by running `bb deploy-server test` from a fresh clone; if it succeeds, the Dockerfile is fine. If not, fix first.

**Deliverable:** `server.Dockerfile` builds successfully against HEAD; `bb deploy-server test` works from a clean clone.

### 9.2 Land `ci.yml`

PR that adds only `ci.yml`. Run it against a throwaway PR to baseline timings. Tune caching until cold-cache CI finishes in < 10 min and warm-cache in < 4 min.

**Deliverable:** PR CI green on `release-v0.1-details` and `agentic-skills`. Required-status-check **not** enabled yet (wait one week for flakiness signal).

### 9.3 Pick and configure the secrets manager (§5.2)

Out-of-band work: select adapter, migrate the contents of `.kamal/secrets-common` into the chosen vault, test `kamal secrets fetch` locally from a clean clone and confirm it reconstructs a working `.kamal/secrets` file.

**Deliverable:** one developer can deploy to `test` from a clean clone with no pre-existing `.kamal/secrets-common` — only the manager auth token.

### 9.4 Land `deploy.yml`

PR that adds `deploy.yml` (workflow) + GitHub Environments config (documented in `CONTRIBUTING.md` or a `docs/ci.md`, since Environments themselves aren't repo files). Auto-deploy-to-test on push to `release-v0.1-details`; manual `workflow_dispatch` for prod with required reviewers.

**Verification gate:** do three successive auto-deploys to `test` without manual intervention before enabling prod.

**Deliverable:** push to `release-v0.1-details` auto-deploys to `test`; manual `workflow_dispatch` successfully deploys to `prod` with approval.

### 9.5 Land `rollback.yml`

Small, after deploy is proven. Dry-run `release-lock` once against `test` before wiring reviewer rules on `prod`.

### 9.6 Land `integration-test.yml`

After deploy is proven on `test`. Start with manual `workflow_dispatch` only; add the nightly cron after three successful manual runs.

### 9.7 Enable required status checks

Once `ci.yml` has been green-stable for ~1 week, mark `lint-and-test` as required for merges into `release-v0.1-details` and `agentic-skills`.

### 9.8 Secret rotation pass (piggyback)

Since `.kamal/secrets-common` has been sitting on developer machines with real secrets, §5.3 is a natural prompt to rotate at least `KAMAL_REGISTRY_PASSWORD`, `CONFIG_MASTER_KEY`, and `JWT_SECRET`. Not a hard prerequisite, but a good hygiene moment. Rotation of `CONFIG_MASTER_KEY` is non-trivial (see parent plan, runbook #3) — schedule accordingly.

### 9.9 Harden SSH (Phase 2 of this plan)

After steady-state operation, migrate from `root` to a restricted `kamal` user on the host. Separate PR + host-side change.

## 10. Risks and open questions

1. **Client build in `ci.yml` — run or skip?** Running `clj -X:build build-client` in CI catches shadow-cljs breakage but adds ~3–6 min cold. Recommendation: add it as a separate job that runs in parallel with tests, not serially. Owners can disable if it becomes flaky.
2. **Flakiness of Typesense-dependent tests.** `bb test` includes namespaces that may need a live Typesense. Options: (a) mark them and skip in CI via a test selector, (b) run Typesense as a GH Actions service container. Option (a) is faster to land; (b) is more faithful. Recommendation: start with (a), revisit after baseline.
3. **Kamal v2 minimum version.** Pin an exact minor in CI (`gem install kamal -v '2.x.y'`) and in a `.kamal/version` file for local devs. Prevents "works on my laptop" drift.
4. **SSH host-key pinning.** `ssh-keyscan` is the quick path; pinning `known_hosts` is the robust path. Start with the pinned file to avoid a known-hosts-TOFU attack window.
5. **Fork safety.** The `if: github.repository == '…'` guard on deploy/rollback workflows prevents forks from attempting deploy with absent secrets — but any required-secret env usage should also fail safely when the secret is missing.
6. **Runner sizing.** Default `ubuntu-latest` (2 vCPU, 7 GB) should handle `bb test`; if memory pressure from JVM + shadow-cljs bites, consider `ubuntu-latest-4-cores` (requires paid plan) or self-hosted runner on the builder host.
7. **Auto-deploy on `release-v0.1-details`.** If the team prefers PR-merge-only auto-deploy (not any direct push), restrict `deploy.yml`'s push trigger to merges-only via a `types: [closed]` filter on `pull_request` + `if: github.event.pull_request.merged == true`. Recommended for `prod`-adjacent branches.

## 11. Non-goals

- **No container-registry change.** We keep Azure Container Registry as today.
- **No image-build in GitHub Actions.** Remote build on the Hetzner host, full stop.
- **No multi-region, no multi-host rolling deploys.** Current setup is one host; not changing that here.
- **No self-hosted runners.** Unless §10 Q6 forces it. Adds infra to maintain.
- **No preview environments per PR.** Out of scope for v0.1.
- **No migration to a different orchestrator** (Nomad, k8s). Kamal v2 is the target.

## 12. Acceptance criteria

- [ ] `ci.yml` runs on every PR against `release-v0.1-details` and `agentic-skills`, reports status, takes < 10 min cold / < 4 min warm.
- [ ] Push to `release-v0.1-details` auto-deploys to `test` and the deployment is observable in GitHub Actions and via `bb logs-server test`.
- [ ] Prod deploys require reviewer approval and leave a GitHub Actions audit trail.
- [ ] `rollback.yml` can release a stuck deploy lock and roll back to a prior version, verified once on `test`.
- [ ] `integration-test.yml` runs nightly against `test.rag.digdir.cloud` and files an issue on failure.
- [ ] No secret exists in the repo, commit history, `git log -p`, or any workflow log. Verified by `trufflehog` or `gitleaks` scan.
- [ ] A developer can clone the repo fresh, install toolchain via `mise install`, fetch secrets from the chosen manager, and run `bb deploy-server test` without ever opening `.kamal/secrets-common`.
- [ ] `README.md` has CI status badge reflecting `release-v0.1-details`.

## 13. Resolved vs open

**Resolved in parent plan:**
- Shipping line: `release-v0.1-details`.
- Remote build on existing host.
- No companion repo.

**Open for this plan:**
- §10 Q1–Q7 above.
- Which secrets-manager adapter (Azure Key Vault recommended; confirm Digdir standard).
- Whether to restrict auto-deploy to merged-PR events only (§10 Q7).
