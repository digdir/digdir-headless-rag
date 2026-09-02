# Testing

Most tests use `clojure.test` (125 `*_test.clj` files under `server/test/`). A handful of
legacy `digdir.docs.*` source files also carry inline [Hyperfiddle RCF](https://github.com/hyperfiddle/rcf)
(`tests` macro) blocks colocated with the implementation.

## Running Tests

Use the Babashka tasks (run from the repo root):

```bash
# Run the full unit test suite
bb test

# Run config-resolution tests only (accessor, db, ops, ui, permissions)
bb test-config

# Run diagnostics tests that need src-dev on the classpath
bb test-diagnostics
```

- `bb test` (`bb.edn:1888`) runs `clj -M:test -d test` via `cognitect.test-runner`,
  excluding `digdir.tools.diagnostics-test`.
- `bb test-config` (`bb.edn:1934`) runs the five `digdir.config.*` test namespaces
  (`accessor-test`, `db-test`, `ops-test`, `ui-test`, `permissions-test`).
- `bb test-diagnostics` (`bb.edn:1930`) runs `digdir.tools.diagnostics-test` with the
  `:diagnostics:test` aliases (needs `src-dev` on the classpath).

There is no `bb test:unit` / `bb test:integration` / `bb test:watch` / `bb test:repl` — only
the three tasks above.

### Running from the REPL

The `:test` alias (`server/deps.edn`) already sets `-Dhyperfiddle.rcf.enable=true` and
`-Dhyperfiddle.rcf.generate-tests=true`, so `clj -M:test ...` picks up inline RCF blocks
automatically. If you start a REPL some other way and want RCF tests to run, set those JVM
properties yourself.

```bash
cd server
clj -M:test -n digdir.config.db-test
```

### Selecting a single namespace

```bash
cd server
clj -M:test -n namespace.name
```

## Writing RCF Tests

Inline RCF tests are written with the `tests` macro, colocated with the code they cover
(see `digdir.docs.website`, `digdir.docs.folder`, `digdir.docs.episerver` for examples):

```clojure
(ns my.namespace
  (:require [hyperfiddle.rcf :refer [tests]]))

(defn add [a b]
  (+ a b))

(tests
 "add function works correctly"
 (add 2 3) := 5
 (add -1 1) := 0)
```

RCF tests are automatically elided from production builds when
`hyperfiddle.rcf.enable` is not set.

New tests should generally be `clojure.test` `deftest`s under `server/test/`, matching the
rest of the suite — RCF is not the primary pattern in this codebase.

## Known pre-existing failures

Some tests are known to fail when run in isolation (`clj -M:test -n some.ns`) but pass as
part of the full `bb test` suite, due to test-order/shared-state coupling (e.g. one
namespace relying on setup performed by an earlier one). `bb test` is the source of truth
for pass/fail status; don't chase an isolation-only failure as a regression. If you're
adding coverage for an area with a brittle pre-existing test, prefer writing a fresh,
self-contained test alongside it rather than fixing the old one's isolation dependency.

## CI

There is no CI configured yet (`.github/workflows/` does not exist). Run `bb lint` and
`bb test` locally before opening a PR.

## Related

- `digdir.skills.builtin.agent.*` (`server/src/digdir/skills/builtin/agent/`) is the current
  agent-graph implementation — not `agent.graph.*`, which does not exist in this codebase.
