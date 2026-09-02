# Electric `e/defn` Bytecode-Bloat Investigation

Date: 2026-05-09 (planning) → 2026-05-11 (resolved, commit `f52d775`)
Status: **Resolved**. Unblocks Phase 5 of `first-kamal-deploy-plan.md`.

## Problem

`clojure -X:build:prod uberjar` (the cljs release build invoked by
`bb deploy-server prod`) failed with:

```
IndexOutOfBoundsException: Method code too large!
File: server/src/digdir/config/ui.cljc:554:1     ;; (e/defn Modal …)
File: server/src/digdir/playground/ui.cljc:222:1 ;; (e/defn SourceTitleMarkdown …)
```

The JVM bytecode emitted for some macroexpanded form exceeded the 64KB
per-method limit. The error reported the form being compiled when the
limit was hit, but the actual culprit was elsewhere — see resolution.

`bb test` and dev-mode (`bb dev` / `bb server-dev`) still passed; only
the prod uberjar's cljs release step failed.

## Working hypothesis (going in)

The bloat was thought to live in the **transitive Electric dependency
graph** of the failing namespaces — Electric's whole-program compilation
inlining ctors from required namespaces past 64KB.

## What actually happened

The hypothesis was wrong. The bloat was per-form, not per-graph. The
error pointed to the first `e/defn` in each file because Electric's
`serialized-require` loads the whole `.cljc` file when the first
`e/defn` macroexpands — and during that load, **some other `e/defn`
later in the same file** is where the JVM compiler actually hits the
64KB limit. The reporting site (line 222 / 554) was misleading.

Two distinct culprits, one per file:

- **`config/ui.cljc`** — the entire diagnostics block (`TraceNodePill`,
  `RuntimeTraceDiagnostics`, `ConfigTreeDiagnostics`, lines 1095-2196,
  ~1100 lines) had been dead code since `2c8e7c4` disabled
  `DiagnosticsPanel` (April 2026). Removing it dropped the file's total
  emit below the limit.

- **`playground/ui.cljc`** — `PlaygroundChatFull` (204 lines, 48
  `let`-bindings, many of them `e/server` / `e/Offload` calls) emitted
  a single ctor table large enough to exceed 64KB on its own. The fix
  was to extract pure-CLJ derivation into plain `defn` helpers
  (`derive-chat-tenant-state`, `derive-chat-scope-state`,
  `derive-chat-message-state`) so they don't become Electric ctors, and
  to pull the dom composition into a separate `e/defn PlaygroundChatFullLayout`.
  The remaining Electric `let` has ~15 bindings.

## What we ruled out along the way

- **Not a regression from the dump-flow branch.** Same error on parent
  commit `4d582e6`, before any of the recent dump-feature work.
- **Not a separate namespace-load failure.** A `digdir.rag.typesense`
  ns-load NPE was masking the bytecode error in the very first attempt;
  fixed in `7ca1569` (broaden top-level `Throwable` catch around
  `(def ts-admin (make-ts-settings))`).
- **Not the body of the *reported* `e/defn`.** Truncating `config/ui`
  to contain only `Modal` (a ~40-line component) still produced the
  same line 554 error. Truncating `playground/ui` to its first ~250
  lines made the build pass — proving the failing form was further down
  the file, not at line 222.
- **Not in-file decomposition of the wrong form.** Stubbing out
  `MessageBubbleWithBranching` (235 lines) didn't help.
- **Not the `playground.ui.observability` transitive graph alone.**
  Manual `(require 'digdir.playground.ui)` from a clj REPL with
  `*compile-files* true` succeeded — i.e. the namespace load by itself
  isn't the problem. Only the cljs build path triggered it, because
  the per-form bytecode limit applied to the specific bloated form.
- **Not the in-file e/defn *count*.** Splitting `PlaygroundChatFull`'s
  body into a new helper `e/defn` in the *same* file (just renaming
  layout vs. body) didn't help — total emit unchanged, individual
  forms still over limit.
- **Namespace splitting (move PlaygroundChatFull to `playground.ui.chat`)
  didn't help on its own either.** Same bytecode bloat appeared in the
  new file, with a secondary "Can't specify more than 20 params"
  failure mode in the clj REPL. The fix had to be per-form size
  reduction, not relocation. (Move was reverted.)

## Bisection technique that worked

To find the *actual* failing form, replace tail-end content of the file
with a stub `e/defn PlaygroundChat` (the externally-referenced entry
point) and re-run `clojure -X:build:prod uberjar`. Binary-search the
keep/drop boundary. This isolated the bloat to lines 1591-1794
(`PlaygroundChatFull`) in playground/ui.cljc.

## Resolution

Single commit `f52d775` on `release-v0.1-details`:

- `config/ui.cljc`: deleted dead diagnostics block (1102 lines removed).
- `playground/ui.cljc`: decomposed `PlaygroundChatFull` (48 Electric
  bindings → ~15), extracted pure-CLJ derivation into three plain
  `defn` helpers, extracted layout into `PlaygroundChatFullLayout`.

Verified end-to-end: `clojure -X:build:prod uberjar` produces
`target/<sha>.jar`, and the playground chat panel round-trips correctly
under `bb dev` (Electric reactor boots, message submit reaches the
server skill-graph).

## Lessons / what to remember

1. **Electric error locations lie.** "Method too large at line N" means
   "we were macroexpanding line N when *some other* form in the same
   transitively-loaded file blew up." Don't trust the line number — it
   tells you the *file*, not the form.

2. **Bisect by file truncation, not by stubbing individual e/defns.**
   Truncating the tail of the file (replacing PlaygroundChat with a
   stub) lets you binary-search for the failing form. Stubbing one
   suspected e/defn body at a time is much slower and doesn't catch
   cases where the bloat is *cumulative* across multiple forms.

3. **Reduce Electric `let` bindings by hoisting pure CLJ to `defn`
   helpers.** Every binding in an `e/defn` `let` body becomes part of
   the Electric ctor table. Pure-CLJ derivations don't need to be there
   — push them into plain `defn`s that take Electric values as inputs
   and return maps. ~30 of `PlaygroundChatFull`'s 48 bindings turned
   out to be pure-CLJ derivation that didn't need to be reactive.

4. **`(::print-clj-source true)` metadata fires *before* `->source`
   returns** — i.e. before the JVM compiles the emitted form. Useful
   for inspecting Electric's emit when it succeeds, but won't help
   debug a "Method too large" because the print never reaches stdout
   (the failure is in a later transitive load, not in this `e/defn`'s
   own `->source`).

5. **The `serialized-require` chain is the load-order trap.** When
   Electric expands `e/defn` at line N, it calls
   `serialized-require (ns-name *ns*)`, which triggers a clj load of
   the *whole .cljc file*. That load compiles every other `e/defn` in
   the file. A too-big form anywhere in the file fails the load, and
   the error gets reported as if it belonged to the form Electric was
   currently expanding.

## Background reading

- `~/.gitlibs/libs/com.hyperfiddle/electric/.../src/hyperfiddle/electric/impl/lang3.clj`
  (`->source`, `emit-ctor`, `get-ordered-ctors-e`, `emit-deps`,
  `serialized-require`, `macroexpand-clj`) — how Electric assembles the
  source it hands to the JVM compiler.
- `server/src-build/build.clj` — the cljs release entrypoint.
- Commit `7ca1569` — the typesense fix that unblocked the load-time
  failure masking this bytecode error in the original deploy attempt.
- Commit `2c8e7c4` — when the diagnostics panel was disabled (April
  2026); the dead code that was still being compiled lived in
  `config/ui.cljc:1099-2210`.
- Commit `f52d775` — this fix.
