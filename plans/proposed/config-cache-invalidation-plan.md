# Config cache invalidation — hybrid file-marker + HTTP refresh

Eliminate the bb-dev-restart requirement after external config writes
(`bb config-set`, `bb dump-import`, future admin scripts), without losing
the property that those tools work when bb dev is down.

## Background

`digdir.data.db/delayed-connection` is a `defonce`-wrapped `delay` holding a
single datahike connection. Every read in the running server derefs that
connection — and the connection's in-memory snapshot only advances when
THIS JVM commits a transaction. External writes (a separate JVM running
`bb config-set` or `bb dump-import`) reach disk but never propagate into
bb dev's in-memory index. The running server keeps reading the old value
indefinitely until restart.

This was diagnosed during the slice-23 wrap-up (memory:
`feedback_bb_config_set_concurrency.md`). The proposed push-only fix
("route writes through bb dev") was rejected because it would break the
offline-writability invariant.

## Goal

External config writes propagate into the running bb dev's view within
seconds, with no restart, while preserving:
- `bb config-set` works when bb dev is down.
- `bb dump-import` works when bb dev is down.
- The running server doesn't have to re-do schema migrations / one-shot
  data migrations to pick up the change.

## Approach

**Hybrid: file marker (durable, pull) + HTTP endpoint (immediate, push).**

1. **File marker**. Writers (`bb config-set`, `bb dump-import`) touch
   `<datahike-dir>/.config-changed` after committing. Always written —
   it's just an mtime bump, costs nothing, and works whether bb dev is
   running or not.

2. **Background poll inside bb dev**. A daemon thread polls the marker
   mtime every 5s. On change, runs `refresh-conn!`. Covers writers that
   don't know about bb dev (dump-import, scripts, manual transactions).

3. **HTTP push for fast feedback**. `POST /api/admin/config/refresh`
   triggers `refresh-conn!` immediately. `bb config-set` POSTs to it
   after writing, best-effort (silent fail if bb dev is down). Sub-second
   propagation for the developer-tight loop.

`refresh-conn!` is the single point of mutation: release the current
datahike connection, open a fresh one against the same configured
backend, swap the two atom homes that hold it
(`data.db/!conn` + `config.db/!config-conn`). No migrations re-run —
those are init-time only.

## What's in scope

### 1. Refactor `data/db.cljc` for swappable connection

- Replace `(defonce delayed-connection (delay (init-db)))` with
  `(defonce !conn (atom nil))`.
- Split `init-db` into:
  - `init-db!` — full boot sequence (schema, migrations, ensure-defs,
    audit). Sets `!conn`. Runs once per process.
  - `reconnect!` — opens a fresh datahike connection against the
    bootstrap config's current env, releases the old one, swaps both
    atoms. No migrations.
- `get-conn` deref-or-init: returns `@!conn` if non-nil, else triggers
  `init-db!` once.

### 2. File-marker poller

- New ns `digdir.config.cache-invalidation`. Spawns a daemon thread on
  first call to `start-poller!`. Polls
  `<datahike-dir>/.config-changed` every 5s. On mtime change, calls
  `data.db/reconnect!`.
- Started from `init-db!` after migrations complete. Idempotent —
  re-entrant calls are no-ops.

### 3. HTTP refresh endpoint

- New handler `refresh-config-handler` in
  `api/routes/endpoints.clj` (or a small admin namespace).
- Route: `POST /api/admin/config/refresh`. Debug-API-key gated.
- Body: empty (or `{:reason "..."}` for telemetry).
- Action: `(data.db/reconnect!)`, return `204`.

### 4. Writers touch the marker

- `bb config-set` task (`bb.edn:1427-1464`): after the existing
  `set-node-value!` call, write the marker file. Use the same `:dir
  "server"` clojure subprocess.
- `bb dump-import` task (`bb.edn:1574-1600`): same.
- Also reach for the marker from any other writer paths (scan for
  `set-node-value!` / `upsert-definition!` callers outside the dev-only
  flows).

### 5. bb config-set best-effort HTTP refresh

- After the marker touch, attempt `curl -fsS -m 1 -X POST
  $RAG_API_BASE_URL/api/admin/config/refresh -H 'X-Debug-Api-Key: $RAG_DEBUG_API_KEY'`.
- Errors swallowed. If `RAG_API_BASE_URL` unset, skip.

## What's out of scope

- Multi-tenant write authorization. The admin refresh endpoint is
  debug-key-only; a future admin UI will need a per-tenant write API
  that's beyond this slice.
- Live UI auto-refresh after external changes. The Electric/Hyperfiddle
  reactive flow already picks up server-side state changes through its
  own subscriptions; the conn refresh is enough.
- Per-tenant connections. Single shared conn today; the refresh logic
  scales when (if) that becomes per-tenant.

## Phased implementation

### Phase 1 — Refactor connection lifecycle

1. `data/db.cljc`: split init-db into init-db! + reconnect!, swap to
   atom-based `!conn`, keep `get-conn` interface stable.
2. Update `share-connection-with-config-db!` to be re-callable.
3. Unit tests: assert get-conn returns the same conn across calls;
   assert reconnect! swaps to a new conn (identity check); assert
   transacted writes are visible after reconnect.

### Phase 2 — File-marker poller

4. New ns `digdir.config.cache-invalidation`.
5. `start-poller!` defonce'd daemon thread polling marker mtime.
6. Integration test: write marker → poll wakes up → reconnect! called.

### Phase 3 — HTTP refresh endpoint

7. Route + handler + Malli schema.
8. Wire `refresh-config-handler` through `wrap-required-debug-api-key`.
9. Integration test: POST refreshes conn (identity check).

### Phase 4 — Writers touch + push

10. `bb config-set`: write marker after transact.
11. `bb config-set`: best-effort POST refresh after marker.
12. `bb dump-import`: write marker after import.
13. Manual smoke: bb dev up, `bb config-set X true`, immediately query
    bb dev — should see X=true without restart.

### Phase 5 — Cleanup memory + docs

14. Update `feedback_bb_config_set_concurrency.md` memory to reflect
    that restarts are no longer needed.
15. Add a short section to the system overview doc (if it exists) about
    how external config writes propagate.

## Risks

1. **Reconnect race with in-flight transactions in bb dev.** If a
   transaction is mid-commit when reconnect! fires, `d/release` could
   throw or the new conn might miss the just-committed data. Mitigation:
   reconnect! runs in a `locking` block on a small mutex; transactions
   that beat reconnect! are visible in the next connection because they
   went to disk first.
2. **Datahike doesn't support clean release+reconnect on file backend.**
   Empirically untested; if `d/release` doesn't drop the konserve store
   cleanly, the new connection might see stale data. Mitigation: test
   early in Phase 1.
3. **Marker file races.** Two writers touching the marker at nearly the
   same time — fine; both bump mtime, poll picks up the most recent.
   Marker write isn't atomic with the transact (writer crash between
   them = lost notification); the poll's 5s heartbeat is the safety net.
4. **Polling overhead.** 5s `lastModified` checks are essentially free
   (single stat syscall). No worry.

## Done definition

- `bb config-set` from a separate JVM propagates into bb dev's view
  within 1s (HTTP push path) or 5s (marker-poll fallback).
- `bb dump-import` propagates within 5s.
- A manual smoke verifies an external config write changes bb dev's
  agent-loop behavior with no restart.
- Existing tests pass; new tests cover refresh + marker + endpoint.
- Memory note updated.
