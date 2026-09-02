# Release v0.1 packaging plan — onboarding & documentation review

> **Status:** Proposed (2026-07-08). Not yet approved.
> **Worktree:** `release-v0.1-packaging` (branch off `release-v0.1-details`, HEAD `efec3c6`).
> **Goal:** make v0.1 straightforward for a new team member to onboard onto, review all
> documentation from `README.md` outward, and hand off a flagged inventory of functional
> rough edges to a **separate polishing mission**.
> **Companion artifact:** [`release-v0.1-rough-edges-inventory.md`](release-v0.1-rough-edges-inventory.md)
> — the deduplicated, severity-ranked catalog this plan produces and references (IDs `B*`,
> `D*`, `P*` below refer to it).
> **Supersedes:** [`ship-mode-onboarding-plan.md`](ship-mode-onboarding-plan.md) (2026-04-22,
> baseline `0cf3032`) — good bones, but **387 commits stale**; see §1.

---

## 1. Why a new plan (not just executing ship-mode)

`ship-mode-onboarding-plan.md` already laid out a sensible 7-phase arc (truth pass →
onboarding doc → OSS scaffolding → CI → runbooks → patterns → README reconciliation) and
resolved several policy questions (keep "PROTOTYPE" framing; no companion secrets repo;
Kamal remote build). **We keep that skeleton and its resolved decisions.** But it is
anchored to commit `0cf3032` and 387 commits of drift have since:

- **Resolved** two of its six drift items (`deploy-admin.yml` → `deploy.yml`; the
  `server.Dockerfile` `admin/` layout is now flat) — see inventory D10/D11.
- **Introduced the single biggest doc problem it never saw: the MCP migration.**
  `/api/rag` + `/api/retrieve` were removed in "Phase 0 of the MCP server migration"
  (`server/src/digdir/api/routes/handlers.clj:4-6`); `/api/mcp` is now the query surface.
  Nearly all API docs — and the README and the system-overview "spine" — still present the
  removed endpoints as primary (inventory **S1**, D1, D8, D14–D19).
- **Changed the drift specifics** it did list: the dev command is now `bb dev` (not the
  README's `bb admin-dev`, and not ship-mode's `deploy-admin` framing).

So the corrective facts have moved. This plan re-grounds everything on current HEAD, folds
in the MCP reality, and is backed by a fresh five-stream audit (README, `docs/`,
`server/docs/`, first-run path, functional sweep) whose findings are the inventory.

## 2. Two systemic findings that shape the whole plan

- **S1 — A major API migration landed in code but not in docs.** This is not scattered
  drift; it is one migration whose trail must be swept coherently. It reframes the doc
  review: the primary correction is "make every doc describe `/api/mcp`, retire the
  `/api/rag`/`/api/retrieve` story." (Inventory §0, D-series.)
- **S2 — The documented first-run path is broken end-to-end.** A new hire cannot reach a
  working query by following the docs (wrong flagship command, no env template, remote-only
  retrieval, wizard/backend mismatch, no login bypass, no CI). (Inventory §0, B-series.)

Onboarding cannot be "fixed with a doc" until S2's underlying path works. So the plan pairs
each onboarding doc with the concrete unblock it depends on.

## 3. Mission boundary — what this plan owns vs. defers

The user asked us to **flag** functional rough edges here and **fix** them in a separate
mission. We draw the line cleanly:

| This packaging mission FIXES | The separate POLISH mission FIXES |
| --- | --- |
| Documentation drift (README → outward), incl. the MCP sweep | Functional/code rough edges (inventory `P*`) |
| The first-run onboarding path (env, seed, login, task discoverability) | Half-finished migrations, dead shims, duplication |
| OSS scaffolding (LICENSE, CONTRIBUTING, …) + PR CI | Robustness sharp edges (429 retry, GPT-5 params, rerank error path) |
| Producing & maintaining the **rough-edges inventory** (the flagging deliverable) | Hardcoded values, placeholder defs |

**Rule:** if fixing a rough edge means editing product source with runtime behavior, it is
POLISH — this mission documents/flags it and moves on (ship-mode's "document it, file an
issue, don't fix mid-stream" stance). The one grey-zone exception we *do* fix here is
`accessories.yml` (P1), because it breaks operator bb tasks a new hire will run — but even
that can be deferred if we prefer a hard boundary.

## 4. Deliverables at a glance

| # | Deliverable | New/edited files |
| --- | --- | --- |
| 1 | Rough-edges inventory (**done** — see companion) | `plans/proposed/release-v0.1-rough-edges-inventory.md` |
| 2 | Corrected README (front door + doc map) | `README.md` |
| 3 | Re-grounded system-overview | `docs/system-overview.md` |
| 4 | MCP-correct API docs | `server/docs/api/*`, `server/README.md`, `server/TESTING.md` |
| 5 | Fixed `docs/` links + archive labeling | `docs/architecture/*`, `docs/demo/*` |
| 6 | Onboarding narrative | `docs/onboarding.md` |
| 7 | Env template | `.env.example` (promoted from `server/e2e/.env.example`) |
| 8 | Local dev-stack + first-dataset recipe | `docs/onboarding.md`, optional `docker-compose.dev.yml` |
| 9 | OSS scaffolding | `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `.github/pull_request_template.md`, `CODEOWNERS` |
| 10 | PR CI | `.github/workflows/ci.yml` (see `github-ci-kamal-v2-plan.md`) |

---

## Workstream A — Documentation truth pass & review (README outward)

Reviewed in concentric layers, front door first. Each layer's fixes are the inventory's
`D*` items.

### A0 — The MCP correction (cross-cutting, do first)
The load-bearing edit. Establish the canonical description once — "the query surface is
`POST /api/mcp` (MCP JSON-RPC); the legacy `/api/rag`/`/api/retrieve` were removed in Phase
0 of the MCP migration; `openapi.yaml` is authoritative" — then apply it consistently
through A1–A3. `openapi.yaml` (already correct) + `endpoints/mcp.md` are the reference.
Covers **S1 / D1, D8, D14–D19, D23**.

### A1 — README (D1–D7)
Rewrite the "Headless API" section around `/api/mcp` (D1). Delete/replace the dead
`config/README.md` and `docs/config-resolution.md` references (D2, D3) — either write a
thin `config/README.md` pointing at system-overview §4.1, or drop the references. Remove
the fabricated `ENTITY_CONFIG_FILE` legacy-EDN mode and `AGENT_GRAPH_DEFAULT_LLM_CONFIG`
(D4, D5). Fix the Typesense env-var framing (D6). Add `DATAHIKE_FILE_PATH` as the easy dev
path (D7). Fix `bb admin-dev` → `bb dev` and drop `mise run dev` (B1). Add a **"Where to
go next" documentation map** (system-overview / onboarding / decisions / runbooks) so a
reader finds the right door in 10 seconds. Keep the **PROTOTYPE** framing.

### A2 — `docs/system-overview.md` (D8–D13)
Re-baseline the header to HEAD `efec3c6` and do a drift pass: fix §2.2's "`/api/rag` is the
central path" (D8), the resolved Dockerfile/deploy/accessories notes (D10, D11), the
`normalize-dataset-ref` false-flag (D12), and the §10 file index (add `demo/*`,
`skills/graph/trace.clj`) (D13). This doc stays the **spine**; onboarding links into it,
never restates it. Its per-module "Known rough edges" sections are the seed for the POLISH
inventory — keep them.

### A3 — `server/docs/*` API & pipeline docs (D14–D22)
- **Archive or rewrite** `MIGRATION-PROMPT.md` first (D14) — it actively misdirects AI
  assistants.
- Rewrite `getting-started.md` as a real MCP first-request flow: `POST /console-api/api-keys`
  → `POST /api/mcp` (`tools/call`) (D15). Fix `client-smoke-flow.md` steps 4–6 (D16),
  `curl-examples.md` (D17), `authentication.md` table (D18), `PIPELINES-QUICKSTART.md` step 5
  (D19).
- **Rewrite `server/README.md`** — it is unmodified Electric-starter boilerplate (D20).
- **Rewrite `server/TESTING.md`** — documents nonexistent `bb test:*` tasks and dead
  `agent.graph.*` namespaces; correct to `bb test`/`test-config`/`test-diagnostics` and
  `digdir.*`, and add the known pre-existing failures (D21).
- Add the missing `:kudos` source to `pipeline-architecture.md` (D22).

### A4 — `docs/` tree links & freshness (D24–D28)
- **Remove the machine-local `.claude` memory link** in `docs/demo/s5` (D24) — must not
  ship.
- Fix the 5 absolute `/Users/bdbrodie/...` architecture links (D25) and the two missing
  `ARCHITECTURE_DECISION.md`/`IMPLEMENTATION_PLAN.md` links (D26).
- Add a one-line "HISTORICAL — superseded by …" banner to archive/superseded docs (D27).
- Cross-reference or reconcile the two orphan runbooks (D28).

### A5 — `decisions/` ADRs
No fixes needed — the three ADRs are current and authoritative. Add them to the onboarding
reading path (read `platform-runtime-dataset-config-roots` and `agents-skills-and-datasets`
first; `skill-based-agentic-rag` for background).

**Acceptance (Workstream A):** every factual claim in README, system-overview, and
`server/docs/api/*` is consistent with each other and with code — cross-checked by a
reviewer who owns none of them. No doc references a removed endpoint or a missing file. No
machine-local paths ship.

---

## Workstream B — Make the first-run path actually work

Pair each onboarding doc with its unblock. These are the inventory `B*` items.

### B1 — Canonical env template (B2)
Promote `server/e2e/.env.example` to a root `.env.example` with placeholder (never real)
values and one-line comments per var, driven by the real boot contract
(`server/src/digdir/config/core.clj:77-80`). Document the JWT-secret generation one-liner
(`openssl rand`) since `gen-jwt-secret.sh` doesn't exist.

### B2 — Boot-minimum + stack matrix (B6, B7, B8)
Document the true boot minimum (one DB pointer + `CONFIG_MASTER_KEY` + `JWT_SECRET`) and a
**local-vs-remote choice matrix**: `DATAHIKE_FILE_PATH` (easy, Postgres-free) vs
`ADH_POSTGRES_*`; local vs port-forwarded Typesense. Reconcile the **setup-wizard vs
backend mismatch** (B6): make `bb setup` accept `DATAHIKE_FILE_PATH` instead of hard-gating
on Postgres. Fix `bb dev`'s commented-out port-forward + description (B7) and the phantom
`:personal/dev` alias (B8).

### B3 — First queryable dataset recipe (B3, B4)
Write the missing choreography end-to-end: file-DB + import the committed config JSON (or
E2E auto-seed for a working `X-API-Key`) + reach a Typesense with chunks + issue a first
`/api/mcp` call. **Decide the local-Typesense story (open question Q5):** either add a
`docker-compose.dev.yml` that brings up Typesense (+ optional Postgres) locally so a new
hire needs no shared-box SSH, or document the `bb port-forward` dependency honestly as a
prerequisite with who to ask for the key. A local option is strongly preferred for OSS.

### B4 — Admin-UI login dev bypass (B5)
Add a dev-only path that logs the 6-digit confirmation code (or documents the E2E_API_KEY
auto-seed escape hatch) so a newcomer can reach the admin UI without configuring Scaleway
email. Small, high-leverage.

### B5 — `docs/onboarding.md` (the first-48-hours narrative)
A single linear walkthrough: who the repo is for (+ what PROTOTYPE means) → get credentials
(names the shared secrets vault, Q1) → minimum local stack (B2 matrix) → smoke test
(ideally `bb onboarding-smoke`) → where to look when stuck (into system-overview) → your
first PR. Links into system-overview; does not restate it.

### B6 — bb task discoverability (B9)
Add a short "day-one tasks" list (the ~8 that matter) to README/onboarding, and consider
switching key task metadata so `bb tasks` shows help text (`:doc`), or add a `bb help`
wrapper.

**Acceptance (Workstream B):** a contributor who has never opened the repo can, from
`README.md` → `docs/onboarding.md` + `.env.example`, reach a running server and a first
`/api/mcp` result — without messaging anyone except "where do I get secrets."

---

## Workstream C — OSS scaffolding

Add the standard files (inventory context: all currently missing): `LICENSE`,
`CONTRIBUTING.md` (branch/commit style, run `bb lint`+`bb test` before push, CI
expectations, the no-secrets rule), `CODE_OF_CONDUCT.md`, `SECURITY.md`,
`.github/pull_request_template.md`, `CODEOWNERS`. Gated on open questions Q1–Q3 (secrets
vault, Digdir license/CoC policy, per-area owners). Target a green GitHub "Community
Standards" checklist.

## Workstream D — PR CI (B10)

There is no CI (`.github/` has only `PR_BODY_mcp.md`). Add a minimal
`.github/workflows/ci.yml`: setup `mise` → `bb lint` → `bb test-config` → `bb test` on PRs.
The detailed deploy/Kamal design already lives in
[`github-ci-kamal-v2-plan.md`](github-ci-kamal-v2-plan.md) — this plan only commits to the
PR-gating slice; deploy automation follows there. Decide Typesense-dependent test handling
(skip-marker vs ephemeral container).

## Workstream E — Rough-edges inventory (the flagging deliverable) — DONE

Already produced: [`release-v0.1-rough-edges-inventory.md`](release-v0.1-rough-edges-inventory.md).
It consolidates all five audit streams + system-overview's own per-module "Known rough
edges" into one deduplicated, severity-ranked, owner-tagged catalog, and ends with a
suggested triage order for the POLISH mission. This mission keeps it current as A–D land
(items get checked off or reclassified); the POLISH mission consumes the `P*` section.

---

## 5. Sequencing & rough sizing

| Phase | Workstream | Blocks | Effort | Start |
| --- | --- | --- | --- | --- |
| 1 | A0 MCP correction + A1 README | all doc work | 0.5 day | now |
| 2 | B1–B4 unblock first-run path | onboarding doc | 1–2 days | now (parallel) |
| 3 | A2–A5 remaining doc review | README reconciliation | 1–2 days | after A1 |
| 4 | B5–B6 onboarding.md + discoverability | real external contribution | 1 day | after B1–B4 |
| 5 | C OSS scaffolding | PR review flow | 0.5–1 day | parallel; needs Q1–Q3 |
| 6 | D PR CI | trusted PRs | 0.5 day (+ deploy plan) | parallel |
| 7 | Final README/system-overview reconciliation pass | — | 0.5 day | after 1–6 |

Inventory (E) is complete up front so every phase can check its items off against it.

## 6. Non-goals

- **No** fixing of functional rough edges (`P*`) — those are the separate POLISH mission.
  We flag and move on.
- **No** removal of the **PROTOTYPE** framing.
- **No** duplication of `system-overview.md` into onboarding docs — link, don't restate.
- **No** architectural refactors triggered by the truth pass (ship-mode ground rule).
- **No** secrets committed anywhere, ever. `.kamal/secrets*` stays gitignored.
- **No** Digdir-internal companion repo (ship-mode's resolved decision stands).

## 7. Open questions

Carried from ship-mode (still open) + new:

1. **Which shared secrets manager** does Digdir use (1Password / Doppler / Vault / …)? Pins
   `docs/onboarding.md` and the CI secret flow.
2. **Digdir policy for `LICENSE` / `CODE_OF_CONDUCT` / `SECURITY`.** *RESOLVED (2026-07-08)
   by aligning with the flagship `digdir/designsystemet`:* `LICENSE` = **MIT**, verbatim
   designsystemet format, `Copyright 2026 Digitaliseringsdirektoratet (Digdir)`;
   `CODE_OF_CONDUCT.md` = designsystemet's **Contributor Covenant** (v1.4+2.0) adapted to
   this project; `SECURITY.md` = private coordinated disclosure. Per plan-owner, CoC
   enforcement + security reports refer readers to Digdir's kept-current contact page
   (<https://www.digdir.no/digdir/kontakt-oss/943>) rather than a hardcoded email alias.
   All three files carry real content — no placeholders remain.
3. **CODEOWNERS per area** (pipelines, skills, agents, admin UI, config, ops)?
4. **Post-v0.1 branching model** — does `release-v0.1-details` stay long-lived, or collapse
   to trunk? Affects CI required-checks.
5. **(new) Local-Typesense story** — ship a `docker-compose.dev.yml` for a fully local
   stack, or document `bb port-forward` to the shared box as a hard prerequisite? Local is
   strongly preferred for an OSS repo.
6. **(new) MCP-migration completeness** — is `/api/rag` removal final for v0.1, or is a
   compatibility shim planned? Confirms we can retire the old endpoint docs outright rather
   than mark them "deprecated."

## 8. What "done" looks like

A new contributor opens the repo, reads `README.md` (90s), follows `docs/onboarding.md` (a
morning) to a running server and a first `/api/mcp` result, consults `docs/system-overview.md`
for "how does this work," files a first PR with the template, and gets green CI within
minutes — never needing to message a teammate except for secrets. Every doc agrees with the
code. And the team has a single ranked inventory of what to polish next, in a separate
mission, with nothing lost.
