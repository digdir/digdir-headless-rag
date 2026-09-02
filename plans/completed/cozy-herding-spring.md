# Config Schema Integrity — Four-Part Defense Plan

## Context

A subtle, compounding bug corrupted the live config DB: `:config-def/path` was
declared `:db/unique :db.unique/identity` in `server/src/digdir/config/schema.clj`,
but that schema was never transacted on boot — only `dh-schema` from
`server/src/digdir/data/db.cljc` was. With Datahike's `:schema-flexibility :read`,
writes of `:config-def/path` landed in an inferred (non-unique) mode, silently
allowing duplicate config-def entities to accumulate. The UI then rendered
whichever duplicate the query returned first — often the stale one without
`:config-def/ownership`.

I already added `ensure-config-schema-on-boot!` (Phase-1/2 fix) plus three
one-shot migrations (`2026-04-23-ownership-reset`, `-v2`,
`2026-04-23-dedupe-config-defs`) that heal existing DBs. This plan prevents the
class of bug from ever recurring and adds defense-in-depth against related
regressions.

## Goals

Four complementary defenses:
1. **Runtime audit** — detect historical corruption and future regressions on boot.
2. **Test coverage** — lock in the "upsert same path twice = one entity" invariant.
3. **Write-time guard** — `upsert-definition!` fails loud when the data is already corrupted instead of silently writing to one of the duplicates.
4. **Schema consolidation** — make the boot path iterate a single schema registry so future additions can't drift.

---

## Part 1: Boot-time uniqueness audit

### Files
- `server/src/digdir/config/db.clj` — new private `audit-uniqueness-invariants!` fn
- `server/src/digdir/data/db.cljc` — wire the call into the boot chain

### Approach
Walk `schema/config-migration-schema` (not the live DB meta-schema — the declared
schema is the source of truth). For every map with
`:db/unique :db.unique/identity` or `:db/unique :db.unique/value`:

```clojure
(d/q '[:find ?v (count ?e)
       :in $ ?attr
       :where [?e ?attr ?v]]
     db attr)
```

For any result where `(count ?e) > 1`, log `log/warn` with the attribute, the
violating value, and the set of entity ids. Do NOT throw — historical DBs may
still be mid-heal, and blocking startup on a stale corruption would be worse
than a loud log line. The log message names the relevant one-shot migration
(e.g. `2026-04-23-dedupe-config-defs`) so operators know the fix.

Also audit `dh-schema` uniques — attrs like `:conversation/id`, `:message/id`,
`:api-key/id`, etc. — using the same logic.

### Where it runs
Inside `init-db` in `data/db.cljc`, after `apply-config-one-shot-migrations!`
and before `ensure-config-definitions-on-boot!`. That order means:
1. Schema is registered with proper constraints.
2. Known one-shot fixes have had a chance to run.
3. Audit reports any remaining violations — caught before the app serves traffic.

### Non-goals
Not a full consistency check — doesn't verify referential integrity of refs,
doesn't check cardinality violations. Scope is strictly unique-key duplicates.

---

## Part 2: Test the invariant

### Files
- `server/test/digdir/config/db_test.clj` — two new deftests

### Test A — happy path: upsert-definition is idempotent
```clojure
(deftest test-upsert-definition-does-not-create-duplicates
  (let [conn (create-test-db)]
    (try
      (config-db/upsert-definition! conn {:path "x" :root :platform ...})
      (config-db/upsert-definition! conn {:path "x" :root :platform ...})
      (is (= 1 (count (d/q '[:find [?e ...] :where [?e :config-def/path "x"]]
                           @conn)))
          "second upsert must update the existing entity, not insert a new one")
      (finally (delete-test-db conn)))))
```

This pins the invariant directly against the running test DB, which uses the
full `config-migration-schema`. If `:config-def/path` ever loses its
`:db.unique/identity` in the schema (or if a refactor moves it to a schema
fragment that isn't transacted at boot), this test fails.

### Test B — guard fires on pre-existing duplicates
```clojure
(deftest test-upsert-definition-rejects-when-duplicates-already-exist
  (let [conn (create-test-db)]
    (try
      ;; Bypass the unique constraint by direct-transacting two entities.
      ;; Simulates the historical corruption scenario.
      (d/transact conn {:tx-data [{:db/id -1 :config-def/path "y" ...}
                                   {:db/id -2 :config-def/path "y" ...}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate config-def"
                            (config-db/upsert-definition! conn
                              {:path "y" :root :platform ...})))
      (finally (delete-test-db conn)))))
```

Note: the schema will reject the direct two-entity transact under
`:db.unique/identity`. Test B may need to construct a DB *without* that
constraint (e.g. a test-only schema that omits the unique flag on
`:config-def/path`) to reproduce the historical state. This is acceptable —
the test documents what we're protecting against.

---

## Part 3: Write-time guard in `upsert-definition!`

### Files
- `server/src/digdir/config/db.clj` — amend `upsert-definition!`

### Approach
Before the existing lookup via `get-definition`, query for ALL entities
matching the path:

```clojure
(let [dupe-eids (d/q '[:find [?e ...]
                       :in $ ?p
                       :where [?e :config-def/path ?p]]
                     db path)]
  (when (> (count dupe-eids) 1)
    (throw (ex-info "Duplicate config-def entities found for path — run the 2026-04-23-dedupe-config-defs migration"
                    {:path path
                     :eids (vec dupe-eids)
                     :migration "2026-04-23-dedupe-config-defs"}))))
```

Current code uses `(d/q '[:find (pull ?e [*]) . ...])` with a `.` aggregator
that picks one arbitrary result — silent when duplicates exist. The new
guard turns silent corruption into a loud failure with a specific remediation
hint.

Also apply the same guard to `upsert-definitions-batch!` (line ~225 in
`config/db.clj`).

### Cost
Each `upsert-definition!` call gains one extra small query. Upserts are rare
(setup + boot-time ensure), not hot-path. Negligible.

---

## Part 4: Consolidate schema registration

### Files
- `server/src/digdir/data/db.cljc` — add `all-schemas` registry, iterate it in `init-db`
- `server/src/digdir/config/db.clj` — leave `ensure-schema!` intact (used by `init-config-db!` and test fixtures)

### Approach
Introduce a single registry in `data/db.cljc`:

```clojure
(def all-schemas
  "Single registration point for every schema fragment that must be transacted
   on boot. Add new schema defs here; never rely on a side-effecting require."
  [{:label :main-data :tx dh-schema}
   {:label :config    :tx schema/config-migration-schema}])
```

Replace the current `ensure-config-schema-on-boot!` + `d/transact dh-schema`
pair with a single loop:

```clojure
(doseq [{:keys [label tx]} all-schemas]
  (try
    (d/transact conn {:tx-data tx})
    (catch Exception e
      ;; "already exists" is benign — Datahike's idempotent attr registration
      (when-not (re-find #"already exists" (str (.getMessage e)))
        (throw (ex-info (str "Failed to transact schema " label)
                        {:label label} e))))))
```

This removes the separate boot helper (`ensure-config-schema-on-boot!`). The
bug class that enabled the original corruption — "new schema fragment added,
forgot to wire it into boot" — now requires editing a single, obvious
registry.

### Keep separate
- `config-db/ensure-schema!` — still used by `init-config-db!` (setup wizard)
  and test fixtures. These paths need a single-schema entry point.
- Per-fragment `ensure-X-schema-tx` helpers in `data/db.cljc` — they're
  data-migration-aware (check `:db/ident` presence before emitting tx-data).
  Leave them; they're conceptually a different layer (schema drift repair, not
  initial registration).

---

## Sequencing

The four parts are largely independent. Implement order:

1. **Part 4 first** (schema consolidation) — low-risk, touches boot path once;
   subsequent parts can assume the schema is reliably transacted.
2. **Part 3** (write-time guard in `upsert-definition!`) — small, targeted.
3. **Part 1** (boot-time audit) — builds on Part 4's schema registry (audit walks
   the same registry).
4. **Part 2** (tests) — last so they can validate the contract end-to-end.

All four can ship in a single branch/PR; they belong together conceptually.

---

## Verification

After each part:
- `bb test-config` — 114 tests / 526 assertions, expected to stay green.
- `clj -M:test -d test -n digdir.config.global-test -n digdir.config.db-test`
  — extended config suite.

End-to-end:
- Run `bb dev` against the existing local DB. Boot log should show no
  uniqueness-audit warnings (live DB is already deduped from the Phase-2 fix).
- REPL check: `(digdir.config.db/audit-uniqueness-invariants! (digdir.config.db/get-conn))`
  should return `{:violations 0}`.
- Manually corrupt a test DB (direct transact of two entities sharing a unique
  attr) and verify the audit emits a warning on next boot.

Lint:
- `bb lint` — error count unchanged (pre-existing Electric/kondo noise only).

---

## Out of scope

- Cleaning up `digdir/config/validator.clj` stale specs (dead code at runtime).
- Period-scheduled audit job (boot-time is sufficient; cron would be overkill).
- Migrating data-migration helpers (`ensure-X-schema-tx`) into the unified
  registry — they're a distinct mechanism.
