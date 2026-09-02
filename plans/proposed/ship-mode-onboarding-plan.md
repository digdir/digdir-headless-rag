# Ship-mode onboarding plan

> **Status:** Proposed. Not yet approved or in progress.
> **Goal:** Make this (open-source) repo safe and pleasant for a new Digdir developer — or an external contributor — to onboard onto in under 48 hours, without softening the fact that it is still labelled a **PROTOTYPE**.
> **Baseline:** commit `0cf3032` on branch `release-v0.1-details`, 2026-04-22.

## Ground rules

Four guiding decisions shape the scope:

1. **Keep the "PROTOTYPE" framing** in `README.md:2` and elsewhere. The plan does not try to make the repo *look* production-finished; it tries to make it *navigable*. Setting expectations honestly is part of that.
2. **`plans/` stays as-is.** WIP visibility is intentional. `docs/system-overview.md:150` already notes that `plans/` is "not a source of truth for implementation." We inherit that stance.
3. **`docs/system-overview.md` is the spine.** It is code-grounded, line-anchored, and the authoritative "how does this thing work" doc. The plan references it rather than restating it. Onboarding material points *into* it; drift fixes update *it* alongside the code.
4. **Open-source repo; no Digdir-internal companion repo.** Secrets flow through a shared secrets manager (1Password, Doppler, Vault — whichever Digdir already uses for similar services), not through a second git repo. Concretely:
   - Env-var lists, example configs, and deploy workflows can all be public.
   - No real secrets, no real API keys, no real Kamal `.secrets` file ever commit here. `.kamal/secrets*` stays gitignored and is fetched at deploy time.
   - CI/deploy credentials live in **GitHub Environments + Secrets** (scoped per `prod` / `test`, with required-reviewers on prod).
   - Human/dev credentials live in a **shared secrets manager** vault that new contributors are invited to on onboarding. `docs/onboarding.md` names the specific vault (see open question Q1).
   - See the "Why not a companion repo" note below for the reasoning.

**Why not a companion repo.** A separate Digdir-internal repo for secrets and operational notes was considered and rejected for the following reasons:

- **Kamal v2 already has first-class secret integrations.** `kamal secrets fetch` pulls from 1Password / Doppler / Vault / `bitwarden-cli` at deploy time. A git repo full of encrypted secret files would reinvent that badly.
- **GitHub Environments already solve CI/deploy secret scoping** — per-environment access lists, required reviewers on prod, full audit log. A companion repo adds another access list to maintain without replacing that one.
- **The operational surface is narrow.** Today: Kamal registry password, Postgres creds, LLM provider keys, Typesense keys, `JWT_SECRET`, `CONFIG_MASTER_KEY`. All fit cleanly in a shared vault with a per-env section. There is no mass of internal operational material that needs versioning.
- **Companion repos drift into dumping grounds.** Oncall rotations, internal URLs, one-off scripts — these belong in the team wiki / Notion / Confluence, not in a git repo that has to stay in sync with this one.
- **Git history of secret rotations is a minor benefit with a real downside.** Rotations leave the old value in history forever; secret managers naturally drop it.

**When this decision should be revisited.** If Digdir accumulates a body of operational runbooks that genuinely cannot be public (internal network diagrams, named-person oncall rotations, real hostnames beyond what's already in `deploy.yml`), a small private repo may earn its keep. Until then, wiki + secrets manager beats a companion repo.

## What already exists (so the plan builds on it, not over it)

- `README.md` — quickstart, env vars, bb tasks, troubleshooting.
- `docs/system-overview.md` — ~1300 lines, §§1–10, anchored to the source tree.
- `CLAUDE.md` — two Electric/Hyperfiddle patterns (`e/Token`, pending-signal).
- `server/TESTING.md` — RCF + test runner guide (has some stale namespace refs per `system-overview.md:1064`).
- `decisions/` — three ADRs (agents/skills/datasets, platform-runtime-dataset-config-roots, skill-based-agentic-rag).
- `docs/runbooks/agents-skills-datasets-cutover-runbook.md` — a template to reuse.
- `mise.toml` + `bb.edn` — reproducible toolchain, 59+ tasks.
- `.claude/` — project-level Claude config, kept in-repo.

## Known drift already surfaced in `system-overview.md`

These are load-bearing fixes. Phase 1 closes them in the same PR that touches the related code so the overview stays truthful.

| # | Drift | Source of truth | Fix direction |
| --- | --- | --- | --- |
| D1 | `bb.edn` deploy/boot/exec tasks invoke `deploy-admin.yml`; repo has `deploy.yml` and `deploy.test.yml`. | `system-overview.md:218`, current `bb.edn` | Rename in `bb.edn` to match actual files, or rename files — pick one direction and apply end-to-end. |
| D2 | `bb logs-typesense` / `logs-postgres` / `exec-*` reference `accessories.yml`, which the current branch deletes (`git status: D accessories.yml`). | `system-overview.md:220`, `git status` | Either restore accessories or remove the bb tasks and update any README/overview pointers. |
| D3 | `server.Dockerfile` copies from an `admin/` layout that no longer exists; real deploys use a different Dockerfile on the Kamal builder host. | `system-overview.md:206` | Either fix the in-repo Dockerfile to the flat `server/` layout, or add a visible header comment + `README` note that this file is illustrative only and the builder host holds the source of truth. |
| D4 | `README.md:163` references `config/README.md`, which does not exist. | `system-overview.md:147` | Either write `config/README.md` (thin, pointing to `system-overview.md §4.1`), or remove the README reference. |
| D5 | `server/TESTING.md` references `agent.graph.tree` / `agent.graph.test_runner` namespaces that predate the `digdir.*` rename. | `system-overview.md:1064` | Update namespace references in TESTING.md. |
| D6 | `README.md:1` still says `# Digdir RAG as a Service - PROTOTYPE` with the old `deploy-admin.yml` / `accessories.yml` Deployment section. User update on 2026-04-22 already tightened this; a final pass to confirm it matches `bb.edn` after D1/D2 land. | `README.md:220-230` | Reconcile after D1/D2. |

---

## Phase 1 — Truth pass (unblocks everything)

**Why first.** Every other doc assumes commands in the README and bb tasks actually work. Fixing drift is the cheapest, highest-ROI work and is a prerequisite for phases 2 and 4.

**Scope.** Resolve D1–D6. Run through `README.md` top to bottom on a clean machine (or a colleague's — fresh eyes matter) and execute every command. Anything that fails or needs undocumented prerequisites becomes a ticket.

**Deliverables.**

- `bb.edn`, `README.md`, `server/TESTING.md`, `server.Dockerfile` (or a visible note on it), and `docs/system-overview.md` all mutually consistent.
- A short "onboarding verification checklist" appended to `docs/onboarding.md` (see Phase 2) listing the exact commands a new contributor runs to prove their environment works.

**Acceptance.** A contributor who has never opened the repo can, starting from `README.md`, install toolchain, start the dev server, hit `http://localhost:8081`, and run `bb test` + `bb lint` successfully — without asking anyone anything except "where do I get secrets."

**Out of scope.** No refactors. No renames beyond what D1/D2 demand.

---

## Phase 2 — First-48-hours onboarding doc

**Why.** `README.md` is the front door; `system-overview.md` is the reference manual. Nothing today fills the middle: the *narrative* a new dev follows from nothing to "I have opinions about where to send my first PR." That gap is the single biggest onboarding blocker.

**Deliverables.**

- `docs/onboarding.md` — a single linear walkthrough covering:
  1. **Who this repo is for** — Digdir contributors, external OSS contributors, what PROTOTYPE means for expectations.
  2. **Getting credentials.** Names the specific Digdir shared-secrets vault (1Password / Doppler / etc. — see Q1) that new Digdir contributors are invited to, and the exact vault items to copy into their `mise.local.toml` or `.env`. Points external contributors at how to run against a local-only subset (see §3 below).
  3. **Minimum local stack.** Which backends are required, which are optional, which can be stubbed. Today that question is implicit across `system-overview.md §2.3`, `§4.3`, and `bb.edn` tasks like `bb port-forward`; onboarding.md should make the choice matrix explicit: local Postgres vs port-forwarded, local Typesense vs port-forwarded, `DATAHIKE_FILE_PATH` vs `ADH_POSTGRES_*`.
  4. **A smoke test.** The minimum sequence that proves "your environment is alive": bring the stack up, run one `/api/retrieve` call with a seeded dataset, see a result. Ideally scripted as `bb onboarding-smoke` or equivalent.
  5. **Where to look when stuck.** Points at `system-overview.md` (authoritative), `CLAUDE.md` (patterns), `server/TESTING.md` (tests), `docs/runbooks/` (ops), `plans/` ("WIP — not authoritative"), `decisions/` (ADRs).
  6. **Your first PR.** A curated starter-task list or a pointer to issues tagged `good-first-issue`.

- `.env.example` at the repo root, listing every env var from `system-overview.md §7.2` with placeholder values and one-line comments. Never real values.

- A short `docs/onboarding-for-external-contributors.md` stub (or a clearly marked section of `onboarding.md`) that describes what an external contributor can and cannot do without Digdir-internal credentials.

**Acceptance.** A contributor who follows `docs/onboarding.md` start-to-finish, with only the `.env.example` + access to the Digdir shared-secrets vault, reaches a working local server and runs the smoke test.

---

## Phase 3 — Open-source scaffolding

**Why.** The repo is public. Standard OSS files signal trustworthiness, set contribution norms, and reduce the cost of the first PR review.

**Deliverables.**

- `CONTRIBUTING.md` — branch naming, commit style (we use concise imperative subjects per `git log`), how to run `bb lint` + `bb test` before push, what CI expects, how to cut a PR, what the review bar is, and the **no-secrets-in-this-repo** rule.
- `.github/pull_request_template.md` — summary / test plan / risk section.
- `CODEOWNERS` — route reviews to the right humans per area (pipelines, skills, agents, admin UI, config, ops).
- `SECURITY.md` — how to report a vulnerability. Given the PROTOTYPE framing, this can be short and point to a Digdir security contact.
- `CODE_OF_CONDUCT.md` — standard Contributor Covenant or Digdir's house version.
- `LICENSE` — confirm one is in the repo; if not, add per Digdir policy.
- `README.md` badges — CI status, license. Added after Phase 4 CI exists.

**Acceptance.** The GitHub "Insights → Community Standards" checklist is green.

**Open question for the implementer.** Digdir already has an OSS posture; verify whether there is a canonical `CODE_OF_CONDUCT` / `SECURITY` / `LICENSE` template mandated by Digdir policy before writing fresh ones.

---

## Phase 4 — GitHub Actions CI with Kamal v2 (remote build)

**Why.** Today there is no visible CI (`.github/workflows/` is absent from the repo tree per Phase-0 inspection). Shipping an open-source repo without automated lint + test on PRs is a red flag for contributors. Deploy is already Kamal-driven via `bb deploy-admin`, which is a good fit for Kamal v2's GitHub Actions story.

**Shipping line.** `release-v0.1-details` (or a squashed version of it) is authoritative for v0.1 — the CI and deploy workflows target that branch as the release line. Trunk development on `agentic-skills` gets PR CI but does not auto-deploy.

**Build strategy: remote build, pinned.** We keep the current `builder.remote` Hetzner host from `deploy.yml`. Rationale (per plan-owner preference and technical merit):

- Build cache (deps, shadow-cljs, uberjar layers) stays warm on a long-lived host; GH runners would cold-build every time.
- The CI runner never needs Docker, a registry login, or the ability to push images — it only needs an SSH key that can trigger `kamal deploy` against the builder.
- Matches the existing local deploy flow (`bb deploy-admin`), so there is one deploy path, not two.

**This phase is large enough to deserve its own plan doc.** It will be spun out into `plans/proposed/github-ci-kamal-v2-plan.md` with the detailed design. The summary below sets scope.

**Proposed scope.**

1. **PR CI workflow** (`.github/workflows/ci.yml`):
   - Triggers: `pull_request` on all branches; `push` on `release-v0.1-details` and `agentic-skills`.
   - Steps: checkout → setup `mise` (pins JVM/Clojure/node/bb to `mise.toml`) → `bb lint` → `bb test-config` → `bb test`.
   - Caching: `~/.m2`, `~/.gitlibs`, `.shadow-cljs`, `node_modules` (yarn).
   - The workflow consumes `mise.toml` directly — versions are not duplicated in the YAML.
   - Typesense-dependent tests that need a live server are either (a) skipped in CI with a marker, or (b) run against an ephemeral container service. Decision owed by the detailed plan.

2. **Deploy workflow** (`.github/workflows/deploy.yml`):
   - Trigger: `workflow_dispatch` with `{prod | test}` destination; optional auto-deploy-to-`test` on push to `release-v0.1-details`. Prod is always manual.
   - GitHub Environments: `test` and `prod`, with `prod` gated by required reviewers.
   - Steps: checkout → setup mise → install SSH deploy key → `bb deploy-admin <dest>` (which wraps `kamal deploy`; Kamal handles the remote build against the pinned builder host).
   - Secret flow: Kamal v2's `kamal secrets fetch` adapter pulls from the shared secrets manager; the GH Actions runner supplies only the SSH key, the registry password (for Kamal to pass to the builder), and any manager-specific auth token. `.kamal/secrets*` is written on the fly at deploy time and never commits.
   - Rollback path: `bb release-admin-lock` is the escape hatch when a deploy wedges. The workflow exposes it as a separate manual action (`rollback.yml` or a flag on the deploy workflow).

3. **Integration test workflow** (optional, separate file):
   - Trigger: `workflow_dispatch` and nightly cron.
   - Steps: `bb integration-test test` against `test.rag.digdir.cloud`, using `RAG_API_TEST_KEY` stored as a GH secret (`system-overview.md §7.2` already names this var).

4. **Required status checks.** Once CI is green-stable, make lint + test required before merge to `release-v0.1-details` and `agentic-skills`.

**Acceptance for this phase (set in the detailed plan, summarized here).**

- Every PR gets lint + test feedback within a few minutes.
- Pushes to `release-v0.1-details` auto-deploy to `test`.
- Prod deploys are manual, reviewer-gated, and leave a GitHub Actions audit log.
- No secret ever appears in the repo, workflow logs, or commit history.

**Key risks for the detailed plan to address.**

- **SSH deploy key scoping.** Kamal's SSH-based deploy model requires a private key in CI. The detailed plan must specify: one key or two (app host vs builder), how it's rotated, how it's scoped so it cannot open an interactive shell outside the `kamal` account's command set.
- **Bootstrapping the builder host.** Today the builder host is implicit knowledge; the detailed plan documents how a new builder is provisioned (or at least who owns provisioning).
- **Open-source fork risk.** Forks inherit `.github/workflows/` but not secrets; the deploy workflow must fail cleanly and harmlessly in forks (e.g., guard on `github.repository == 'digdir/…'`).

---

## Phase 5 — Ops runbooks (the "paged at 2am" set)

**Why.** `docs/runbooks/` has one runbook (the agents/skills/datasets cutover). A real operator-facing repo has at least the "what do I do when X breaks" set written down. These are the docs that pay off precisely when whoever wrote the code is asleep.

**Deliverables, prioritized.**

1. `docs/runbooks/prod-down.md` — first steps when the prod URL 5xxs: check Kamal (`bb logs-admin prod`), container status, Postgres, Typesense.
2. `docs/runbooks/rollback-deploy.md` — how to roll back a Kamal deploy; when to use `bb release-admin-lock`.
3. `docs/runbooks/rotate-secret.md` — rotating `JWT_SECRET`, `CONFIG_MASTER_KEY`, provider API keys, Kamal registry password. For `CONFIG_MASTER_KEY` specifically: the key gates AES-256-GCM encryption of config values, so rotation is non-trivial — call that out.
4. `docs/runbooks/db-restore.md` — how to restore from a Datahike/Postgres backup. Points at the existing `bb migration-export`/`migration-import` (`system-overview.md §5.5`) as the logical-level path.
5. `docs/runbooks/typesense-reindex.md` — when collections are empty or corrupt; how to trigger reindexing via `bb import-kudos` / `bb backfill-schema`.

**Template.** Each runbook follows: *Symptom → Likely cause → Verification commands → Fix steps → When to escalate*. Use the existing cutover runbook as the shape reference.

**Acceptance.** A teammate who has never seen the code can follow any one of these runbooks to a resolution (or a clean escalation) without pinging the original author.

---

## Phase 6 — Capture tribal knowledge

**Why.** `CLAUDE.md` has two Electric patterns. `system-overview.md` is code-grounded but not pattern-grounded. The *"why we do it this way"* layer is thin. A new contributor will re-discover the same gotchas the current team already internalized.

**Deliverables.**

- Expand `CLAUDE.md` (or split into `docs/patterns/`, whichever the team prefers) with:
  - **Electric/Hyperfiddle patterns beyond `e/Token`.** The pending-signal pattern is already there — good. Add: `e/Offload` for DB operations and the latency tradeoffs (there are several recent commits tuning this — `0cf3032`, `70a282d`, `f3b2a98`, `1c4c633`); `e/client` / `e/server` transfer rules; when to use `e/watch` vs `e/fn`.
  - **Config resolution walkthrough.** Worked examples for platform vs runtime vs dataset roots and how `(cfg/get ...)` resolves — the ADR at `decisions/platform-runtime-dataset-config-roots.md` covers the "why" but a day-in-the-life example would save hours.
  - **Pipeline debugging story.** When a pipeline run wedges, which atom in `server/state/` to inspect, which log lines to grep, how `bb chunk-find` / `bb retrieve-debug` / `bb rerank-debug` (`system-overview.md §7.5`) fit into the triage flow.
  - **Testing beyond TESTING.md.** RCF pitfalls, how `-Ddigdir.skip-user-dev=true` affects test runs, which tests need the `:diagnostics` alias.

**Acceptance.** A contributor writing their first feature in skills, agents, or the admin UI can find the patterns they need without `git log`-archaeology.

---

## Phase 7 — README reconciliation

**Why last.** After phases 1–4 land, `README.md` has new neighbours to point at. Do the reconciliation pass then, not before, so it's done once.

**Deliverables.**

- `README.md` keeps "PROTOTYPE" framing.
- New "Documentation map" section near the top, pointing at: `docs/system-overview.md` (how it works), `docs/onboarding.md` (getting started), `CONTRIBUTING.md` (contributing), `docs/runbooks/` (operating), `decisions/` (architectural choices). A reader should find the right door in ten seconds.
- Badges for CI status and license.
- Verified consistency with `system-overview.md` on every factual claim (ports, tasks, env vars).

**Acceptance.** `README.md` and `docs/system-overview.md` agree on every factual claim, cross-checked by a reviewer who owns neither.

---

## Proposed ordering and rough sizing

| Phase | Blocks what | Rough effort | Can start |
| --- | --- | --- | --- |
| 1. Truth pass | Everything | 0.5–1 day | Now |
| 2. Onboarding doc | Real external contribution | 1–2 days | After Phase 1 |
| 3. OSS scaffolding | PR review flow | 0.5–1 day | Parallel to Phase 2 |
| 4. CI / Kamal v2 | Trusted deploys | 2–4 days + separate plan | Parallel; spin out detailed plan first |
| 5. Runbooks | 2am incidents | 1 day for top 2; rest incremental | After Phase 4 (runbooks reference CI) |
| 6. Patterns | Contributor productivity | Incremental, low-urgency | Anytime |
| 7. README reconciliation | Discoverability | 0.5 day | After 1–4 land |

## Explicit non-goals

- **No** rewrite of `plans/` or `docs/architecture/*gap-analysis*`. Per ground rule 2.
- **No** removal of "PROTOTYPE" framing. Per ground rule 1.
- **No** duplication of `system-overview.md` content into onboarding docs. Per ground rule 3 — onboarding docs *link* to the overview, they don't restate it.
- **No** architectural refactors triggered by the truth pass. If Phase 1 surfaces deeper issues (e.g., the `server.Dockerfile` layout mismatch reflects a real untangling we haven't finished), the runbook is to *document* it, file an issue, and move on — not to fix mid-stream.
- **No** committing secrets anywhere in this repo, ever. All real credentials stay in the Digdir shared-secrets vault (human access) and GitHub Environments + Secrets (CI/deploy access).
- **No** Digdir-internal companion repo for secrets or operational notes. See the "Why not a companion repo" note under Ground rules for the reasoning and the conditions under which to revisit.

## Resolved decisions (from plan-owner on 2026-04-22)

- **Shipping line for v0.1:** `release-v0.1-details` (or a squashed version of it). Phase 4 targets this branch as the release line; `agentic-skills` gets PR CI only.
- **No Digdir-internal companion repo.** Secrets flow via a shared secrets manager + GitHub Environments. See Ground rule 4 for the reasoning.
- **Build strategy for Phase 4:** remote build on the current Hetzner builder host.

## Open questions for the plan owner

1. **Which shared secrets manager does Digdir use** for services of this shape (1Password, Doppler, HashiCorp Vault, Bitwarden, Azure Key Vault)? This pins the `kamal secrets fetch` adapter in Phase 4 and the vault name in `docs/onboarding.md` (Phase 2).
2. **Does Digdir policy mandate specific `LICENSE` / `CODE_OF_CONDUCT` / `SECURITY` templates** for government-adjacent OSS? Phase 3 should use those if so.
3. **Who are the intended CODEOWNERS per area** (pipelines, skills, agents, admin UI, config, ops)? Cannot be inferred from the repo.
4. **Post-v0.1 branching model.** Once v0.1 ships, does `release-v0.1-details` continue as long-lived (with `v0.2`, `v0.3` release branches cut from `agentic-skills`), or does the project collapse to trunk-only? Affects how required status checks and auto-deploy rules are structured.

## What "done" looks like

When all seven phases land, a new contributor — internal or external — opens the repo, reads `README.md` (90 seconds), follows `docs/onboarding.md` (a morning), consults `docs/system-overview.md` when they hit a concrete "how does this work" question, files their first PR with the template, and gets green CI feedback within minutes. Nothing in that sequence requires messaging a teammate. The repo is still a PROTOTYPE, and says so — but it is a prototype that respects its readers' time.
