# Secrets: one interface, and where a value actually comes from

**Issue:** #22 · The evidence this design rests on is
[`secrets-inventory.md`](secrets-inventory.md).

`digdir.secrets/get!` answers one question — *what is the value of this named
secret* — and refuses to answer anything else. Rotation, versioning, leasing
and dynamic credentials are all deliberately absent: each is a reason the
abstraction stops being thin and becomes a wrapper that fits none of the
backends well.

```clojure
(require '[digdir.secrets :as secrets])

(secrets/get! :openai-api-key)   ;; => "sk-…", or throws naming the secret
```

## Two tiers, and why this layer only owns one

`CONFIG_MASTER_KEY` decrypts the config database, so it cannot live in the
config database. That is not a design preference, it is an ordering
constraint, and it splits secrets in two:

| tier | lives in | read via | in this layer? |
|---|---|---|---|
| **0 — bootstrap** | environment / vault | `digdir.secrets/get!` | **yes** |
| **1 — service credentials** | the config database, encrypted at rest | `digdir.config.accessor/get` | **no, deliberately** |

The Typesense admin key is Tier 1. Fronting it here as well would give one
value two doors, which is the failure this issue exists to prevent. If you are
reaching for `get!` and the value is already a config path, you want
`cfg/get`.

## Resolution order: the environment wins

```clojure
digdir.secrets/*resolution-order*  ;; => [:env]
```

It is a **vector of backends**, consulted in order, first non-nil wins — not
the order of a `cond`. The rule, stated once so nobody has to derive it at
2am:

> **If both an environment variable and a vault entry exist, the environment
> wins.**

That is a decision with a reason: every existing reader in this repository is
env-first by construction (59 direct `System/getenv` call sites), so any other
order would silently change what a running system resolves. A vault is
consulted only where the environment is silent.

To add one: `(alter-var-root #'secrets/*resolution-order* (constantly [:env :azure-key-vault]))`.

## The three backends

| backend | mechanism | status |
|---|---|---|
| `:env` | `System/getenv` | working |
| `:github-actions` | `System/getenv` | working |
| `:azure-key-vault` | installed resolver | **seam — no resolver shipped** |

**`:github-actions` resolves through the environment on purpose, and is not a
copy of `:env`.** GitHub delivers secrets to a job as environment variables;
there is no in-process API to read them, so resolution is identical and only
*provisioning* differs. It exists as a separate name so configuration can say
where a value is meant to come from, and so a missing one can say *"expected
as a GitHub Actions secret mapped into `OPENAI_API_KEY`"* rather than *"set
this variable"*. Note that CI currently references no secrets at all — it
inlines dummy values because it only lints and tests — so this backend has no
consumer yet.

**`:azure-key-vault` ships as a seam, not a client.** There is no Azure
environment reachable from this repository to exercise a real Key Vault call
against, and an unexercised credential path is worse than an absent one: it
would look supported and fail in the one place nobody can test. The resolution
position is fixed and documented; installing a resolver is what remains:

```clojure
(reset! secrets/azure-key-vault-resolver
        (fn [{:keys [env-var]}] ...))   ;; returns a value, or nil if absent
```

Selecting it without installing a resolver throws and says so, rather than
looking like an absent secret.

## A missing secret fails here, naming itself

There is no arity that returns `nil`. This repository's most-repeated defect
is a configuration error arriving later as something else — and the clearest
example was in this very surface: `digdir.llm.anthropic` substituted the
literal string `"Not set"` for an absent `ANTHROPIC_API_KEY`, so a missing
key became an authentication failure at Anthropic, one layer from its cause.

```
Missing secret :openai-api-key: set OPENAI_API_KEY. Tried [:env].
```

The exception names the secret, the variable to set, and the backends tried.
**It never carries the value** — `ex-data` holds names only, because `ex-data`
reaches logs and error reporters.

### `present?` reports absence — and only absence

Diagnostics like `bb setup` need "is this configured?" without pulling the
value, which is what `present?` is for. **It returns `false` only when the
secret is genuinely absent.** An undeclared name and a misconfigured backend
both *propagate*:

```clojure
(secrets/present? :openai-api-key)   ;; => true / false
(secrets/present? :openai-api-kye)   ;; => throws — not a declared secret
```

That distinction is the point of the function, not a detail of it. A blanket
`catch` here would report a typo as MISSING and send an operator to set a
variable that is already set — the silent-nil this layer exists to remove,
reintroduced in the one function diagnostics actually call. The same catch
would report *every* secret as missing when a vault is named in the
resolution order with no resolver installed.

## Adding a secret

Declare it in `digdir.secrets/declared`. Declaration is not bureaucracy: it is
what lets an absent secret name itself, and it is what makes the **dynamic**
call sites auditable. Of the 59 environment reads in this repository, 8 pass a
computed name rather than a literal — `(System/getenv k)` returns `nil` for a
name nobody declared, silently. `get!` throws, and lists what *is* declared.

```clojure
:my-provider-key {:env-var "MY_PROVIDER_KEY" :tier :runtime
                  :doc "What this opens, and who calls it."}
```

`:tier` is `:bootstrap` or `:runtime`. **Do not declare `:service` secrets** —
those are Tier 1 and belong in the config database.

## Never log a value

Names only. `secrets/redact` exists for the moment you want a value in a debug
line: it yields `<redacted:N chars>`, never the value. A resolved secret must
not reach a log line, an exception message, `ex-data`, or a transcript.

## Migration status

The interface has landed with its first real callers, not with a big-bang
rewrite of all 59 sites — a change to how a function is called propagates to
every stub of it anywhere in the tree, which is a separate and riskier change.

- ✅ `digdir.llm.client` — `OPENAI_API_KEY`, was degrading to `nil`.
- ✅ `digdir.llm.anthropic` — `ANTHROPIC_API_KEY`, was degrading to `"Not set"`.
  (Unreferenced code today, so this is illustration rather than a live fix.)
- ⏭ **Tier 0 bootstrap** (`digdir.config.core/load-bootstrap-config`) is not
  migrated, and it needs a decision first: it deliberately returns `nil` when
  the environment is incomplete, and `bb setup` depends on that to report
  MISSING per variable. `get!` throws by design, so migrating it either
  changes boot-time degradation or needs a non-throwing probe. `present?`
  covers the diagnostic half; the boot half is a product decision, not a
  refactor.
