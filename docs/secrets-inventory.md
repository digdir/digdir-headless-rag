# Where secrets come from today — inventory for #22

**Issue:** #22 · **Base commit:** `8e86d93` · **Measured:** 2026-08-22

The first task for a secrets abstraction is not the interface, it is finding
every existing read: *a layer half the codebase bypasses is a layer that lies
about where secrets come from.* This is that search. Nothing here changes
behaviour except one `.gitignore` line, called out in §5.

---

## 1. Method, and why a grep was not enough

An env read is a **text-level** fact in some places and a **reader-level** one
in others, so both instruments were run and reconciled against each other.

| instrument | what it counts | result |
|---|---|---|
| `grep -rn "System/getenv"` | occurrences of the text | **61** |
| Clojure reader, walking every form for `(System/getenv …)` | actual call sites | **59** |

**The gap is fully explained**, which matters more than the numbers: one
occurrence is inside a string literal in `bb.edn:2025` (an escaped
`\"DATAHIKE_FILE_PATH\"` passed to a subprocess) and one is inside a docstring
in `setup/common_test.clj:11`. Neither is a call site. The reader is the
accurate instrument here and the text grep over-counts.

Two reader configurations were required, and both fail by naming something
irrelevant: auto-resolved `::alias/kw` keywords cannot be read without loading
the namespace (normalise the text instead), and a registered-but-unbound data
reader tag is *found*, so `*default-data-reader-fn*` never fires — binding
`*data-readers*` to `{}` is what fixes it. **Unreadable files: 0.** That
assertion is load-bearing: a file the scanner cannot read is reported clean by
every instrument that skips it.

**51 of the 59 call sites pass a string literal. 8 do not**, and those 8 are
where a name-based grep lies:

```
(System/getenv k)          config/core.clj, llm/client.clj ×2, sweep/runner.clj ×2
(System/getenv %)          setup/common.clj:111   (injected seam)
(System/getenv var-name)   setup/common.clj:130   (loop over required/optional vars)
(System/getenv env-name)   e2e/seed.clj:82        (loop over a name→config-path map)
```

Tracing those eight by hand found **17 names the direct scan never saw** — the
five `AUTH_*` server settings, `RATE_LIMIT_TRUST_X_FORWARDED_FOR`, three
`OPENAI_*` tuning vars, five `AZURE_OPENAI_*` siblings, `TYPESENSE_API_KEY`,
`TYPESENSE_API_KEY_ADMIN`, and `OPENAI_PRESERVE_THINKING`. A name-keyed
inventory would have shipped without them.

---

## 2. The finding that shapes the interface: there are already TWO sources

Secrets do not come from one place today, and the split is not arbitrary — it
is a **bootstrap ordering constraint**.

**Tier 0 — environment, read before anything else exists.** `CONFIG_MASTER_KEY`
decrypts the config database, so it cannot itself live there. `JWT_SECRET` and
the database pointer are read in the same breath, in
`digdir.config.core/load-bootstrap-config`.

```
CONFIG_MASTER_KEY   JWT_SECRET   DATAHIKE_FILE_PATH
ADH_POSTGRES_URL    ADH_POSTGRES_USER   ADH_POSTGRES_PWD   ADH_POSTGRES_TABLE
```

**Tier 1 — the config database, read through `cfg/get`.** Service credentials
live here, encrypted at rest with `CONFIG_MASTER_KEY`. The Typesense admin key
is one of these (`services.typesense.api-key-admin`), and
`digdir.e2e.seed/azure-env->config-path` shows the established pattern: an env
var is read **once at seed time** and written into config, after which the
runtime reads config rather than the environment.

**This is the constraint the abstraction has to respect.** A secrets layer can
front Tier 0 — that is exactly what environment / Key Vault / GitHub secrets
are three implementations *of*. It cannot front Tier 1 without either becoming
a second door to the same values or making the config DB a fourth backend.
**Recommendation: the layer owns Tier 0 only, and says so.**

---

## 3. Tier 3, which fits neither: runtime env reads in production code

Two production call sites read a credential from the environment at request
time, bypassing both tiers.

| site | reads | on absence |
|---|---|---|
| `llm/client.clj:107` | `OPENAI_API_KEY` | `nil` → passed to the provider |
| `llm/anthropic.cljc:27` | `ANTHROPIC_API_KEY` | **the literal string `"Not set"`** |

The second is the exact failure this issue's constraint 2 forbids: a missing
secret does not fail at the point of use, it becomes an authentication failure
at the provider, one layer away from the cause and with a misleading message.
These two are the clearest candidates for the new layer's first callers.

---

## 4. Declared but never read

`bb setup` checks four "optional" variables:

```clojure
optional-vars ["ADMIN_USER_EMAILS" "AZURE_OPENAI_API_KEY"
               "TYPESENSE_API_KEY" "TYPESENSE_API_KEY_ADMIN"]
```

**`TYPESENSE_API_KEY` and `TYPESENSE_API_KEY_ADMIN` appear nowhere else in the
repository.** Nothing reads them at runtime; the Typesense key comes from
config. So the setup wizard reports `MISSING` for variables that would change
nothing if set — a check that describes something other than the truth. Worth
correcting when the layer lands, since the wizard is a natural first consumer.

*(Correcting the brief: the Typesense key is **not** read straight from the
environment. It is a Tier 1 config value.)*

**Reaching a Typesense at all is ambiguous by construction**, which is worth
recording next to the key that opens it. Verified in `bb.edn` 2026-08-22: all
three forward tasks bind the **same local port** — dev
`-L 8108:localhost:8308`, test `-L 8108:localhost:8208`, prod
`-L 8108:localhost:8108` — so `localhost:8108` says nothing about which
environment answered. The aggregate `bb port-forward` depends on the **test**
forward, and `docs/onboarding.md` §3 offers it as the way to borrow a
populated corpus. Meanwhile the local dev container binds `0.0.0.0:8108` and
answers `/health` with `{"ok":true}` — a healthy server that is neither. With
two instances also sharing the collection name
`KUDOS_preprod_v4_documents_ab897fbdedfa`, that is four ways to query the
wrong corpus and none of them errors.

> ⚠️ **Do not kill the listener on 8108, and `ps` the pid before killing
> anything named `ssh`.** `lsof` reports the holder as a process named `ssh`,
> which looks exactly like a stale lane tunnel. It is not: its args are
> `ssh: ~/.colima/_lima/colima-closser-ci/ssh.sock [mux]` — Colima's
> multiplexer, which is simply how a container publishes a port on macOS.
> Killing it breaks every local container on the machine. Verified 2026-08-22
> (pid 56146). The name misleads; the args settle it.
>
> **The route that sidesteps all of this: do not port-forward at all.** Run
> `curl` on the box over `ssh`, against the box-side port. No local port is
> bound, so there is no race, no address ambiguity and nothing shared to stop,
> and the admin key can be resolved inside the `ssh` command so it never
> reaches a local machine or a transcript.

The only discriminator is asserting `num_documents` before analysing — **and
when that assertion fails, the action is "establish which instance I am on",
never "update the constant to the number I just measured".** Prod's count
moves with ingestion, so the assertion will one day fail legitimately;
refreshing the constant to make it pass deletes the discriminator, because a
constant fitted to what you just measured cannot tell you that you measured
the wrong thing.

---

## 5. The standing constraint has a hole: `.env` is not gitignored

The issue states *"no secrets in tracked files, ever"* and that
`.kamal/secrets*` stays gitignored. The kamal half holds — `.gitignore:9`
ignores `.kamal/` wholesale, verified with `git check-ignore` rather than by
grepping for the word "secret", which does not appear in that rule.

**`.env` does not.** `.gitignore` contains no env rule at all, and
`docs/onboarding.md:39` instructs every new contributor to *"Copy
`.env.example` (repo root) to `.env` and fill it in"* — a file holding
`CONFIG_MASTER_KEY`, `JWT_SECRET` and provider keys, at the repo root, that
`git add -A` will happily stage. Nothing is committed today; the exposure is
prospective and it is aimed squarely at the person least able to notice.

This inventory adds the one line. It is the only behavioural change here.

---

## 6. Full inventory

**Tier 0 — bootstrap secrets (env only, cannot move).** `CONFIG_MASTER_KEY`,
`JWT_SECRET`, `DATAHIKE_FILE_PATH`, `ADH_POSTGRES_{URL,USER,PWD,TABLE}`.

**Tier 1 — service credentials (config DB, seeded from env).**
`AZURE_OPENAI_{API_KEY,API_ENDPOINT,DEPLOYMENT_NAME,MODEL_NAME,API_VERSION,USE_AZURE}`,
Typesense admin key (config-only, no env name).

**Tier 3 — runtime env reads in production code.** `OPENAI_API_KEY`,
`OPENAI_API_ENDPOINT`, `ANTHROPIC_API_KEY`.

**Server settings — env, not secrets, all with code-level defaults.**
`HTTP_PORT`, `ADMIN_USER_EMAILS`, `AUTH_COOKIE_DOMAIN`, `AUTH_SECURE_COOKIES`,
`AUTH_SESSION_MAX_AGE_SECONDS`, `AUTH_JWT_TOKEN_EXPIRY_HOURS`,
`AUTH_JWT_COOKIE_MAX_AGE_SECONDS`, `RATE_LIMIT_TRUST_X_FORWARDED_FOR`.

**Tuning / diagnostics — env, not secrets.** `OPENAI_REASONING_EFFORT`,
`OPENAI_DISABLE_THINKING`, `OPENAI_ENABLE_THINKING`, `OPENAI_PRESERVE_THINKING`,
`OPENAI_MAX_RETRIES`, `OPENAI_RETRY_DELAY_MS`, `OPENAI_SOCKET_TIMEOUT_MS`,
`RAG_DEBUG_LOGGING`, `DIGDIR_DEBUG_LAST_INVOCATION`, `DIGDIR_DEPLOY_HOST`,
`TENANT`, `DATASET_CONFIG_KEY`.

**Test / CI gates — src-dev and test only.** `E2E_API_KEY`, `RAG_API_BASE_URL`,
`RAG_API_TEST_KEY`, `RAG_DEBUG_API_KEY`, `RAG_TS_RETRIEVE_ENABLED`,
`RERANK_{LANGUAGE_PARITY_ENFORCE,MAX_ACCEPTABLE_RANK}`, and the five
`RUN_*_INTEGRATION` switches.

**Declared, never read.** `TYPESENSE_API_KEY`, `TYPESENSE_API_KEY_ADMIN`.

### Deployment and CI surfaces

`deploy.yml` passes nine names through kamal's `env.secret`, sourced from the
gitignored `.kamal/secrets`: the four `ADH_POSTGRES_*`, `ADMIN_USER_EMAILS`,
`CONFIG_MASTER_KEY`, `JWT_SECRET`, `AUTH_SECURE_COOKIES`,
`RATE_LIMIT_TRUST_X_FORWARDED_FOR`.

**CI uses no `secrets.*` at all.** `.github/workflows/ci.yml` inlines dummy
values for `CONFIG_MASTER_KEY`, `JWT_SECRET` and `DATAHIKE_FILE_PATH` because
it only lints and tests. **The GitHub-secrets backend therefore has zero
current consumers** — it is the right third backend to name, but it will be
speculative until CI needs a real credential, and speculative backends are how
thin abstractions stop being thin.

---

## 7. What this implies for the interface

Not a design, just what the search constrains:

- **Scope it to Tier 0 and Tier 3.** Tier 1 already has a door (`cfg/get`), and
  a second door to the same value is the failure mode this issue exists to
  prevent.
- **Resolution order has one live case today**, so state it and stop:
  environment wins, vault is consulted only when the variable is absent. Every
  existing reader is env-first by construction, so any other order silently
  changes current behaviour.
- **Fail loudly, naming the secret.** Two of the three Tier 3 reads currently
  degrade instead — one to `nil`, one to `"Not set"`. Whatever the layer does,
  it must not preserve that.
- **Never log a value**, and put that rule at the line where someone would add
  one to a debug print, not only in a doc.
