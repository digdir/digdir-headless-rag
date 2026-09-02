# Shadow-first auto-filter decisions

## Context

Auto-filter detection (`server/src/digdir/rag/auto_filter.clj` `detect-query-filters`) is invoked from the retrieval skill (`server/src/digdir/skills/builtin/retrieval.clj` lines 467–490) on every search pass. When it detects org/year constraints from query text, those filters are applied **immediately and silently**:

- Detection exception → printed to stdout via `println`, then dropped (line 472).
- Detection success → fields merged into `effective-filter-by`, search runs.
- Zero hits with auto-filter → silent fallback to unfiltered search; `auto-filter-fallback` flag set.

The output today (lines 525–552) carries `:auto-filter-applied` and `:auto-filter-fallback` in `search-attribution` — diagnostics consumers see them post-hoc, but:

1. The agent loop and trace can't distinguish "auto-filter never fired" from "auto-filter fired and was overridden by fallback" at decision-making time.
2. There's no per-pass history — only the latest pass's attribution survives.
3. There's no observable record of WHY a filter was rejected (zero hits? exception?).
4. Tenants can't tune the policy without code changes — e.g. they may want to forbid the auto fallback for cost reasons.

This plan records auto-filter decisions as first-class workspace state, surfaces them in the trace, and gives the loop a hook to override the fallback policy. The pattern mirrors the shadow-sufficiency-decisions infrastructure already used by read signals.

Intended outcome: every auto-filter event is observable end-to-end. A retrieval-debug or trace consumer can tell whether a filter was suggested, whether it was applied, whether the fallback fired, and what reason was given. Tenants get a knob to disable the silent fallback if they want strict filtering.

## Scope

Six concrete changes, in dependency order.

### 5.1 — Capture filter decisions in the retrieval skill

**File:** `server/src/digdir/skills/builtin/retrieval.clj`

**Add a `filter-decision` builder** (private helper) that constructs a structured record:
```clojure
(defn- build-filter-decision
  [{:keys [detected-filter applied? fallback? fallback-reason error]}]
  (cond-> {:detected-filter detected-filter
           :applied? (boolean applied?)
           :fallback? (boolean fallback?)}
    fallback-reason (assoc :fallback-reason fallback-reason)
    error (assoc :error {:message (.getMessage error)
                         :type (str (type error))})))
```

**Wrap the existing detection + fallback flow** so each branch produces a decision:

- Detection exception (line 471–473) → record `{:applied? false :error e}`
- Detection returned `nil` (no constraints inferred) → no decision recorded (nothing happened)
- Detection succeeded, applied first pass → record `{:applied? true}`
- Fallback fired (zero hits with detected filter) → mutate previous decision to `{:applied? false :fallback? true :fallback-reason :zero-hits-with-filter}`

**Add `:filter-decisions` to skill outputs:**

Update `retrieval-metadata :outputs` (line 50):
```clojure
:outputs [:chunks :search-attribution :filter-decisions]
```

Build the output (after `search-attribution`) and pass through `skills/success-result`:
```clojure
:filter-decisions (when filter-decision [filter-decision])
```

Single-element vector for now; future passes (e.g. multi-query batches inside one search) could add more.

**New parameter `:auto-filter-fallback-mode`** in `:parameters`:
- `:silent` (default, current behavior) — fallback fires, decision marks it
- `:strict` — fallback does NOT fire, return zero hits
- `:hint-only` — never apply detected filter, just record the suggestion

Wire through `execute-retrieval`'s destructuring and the fallback branch (line 484–490). Default behavior unchanged.

### 5.2 — Workspace state for filter-decisions

**File:** `server/src/digdir/skills/builtin/agent/workspace.clj`

Add to `create-workspace` initial state (after `:non-supporting-chunk-ids`, line ~31):
```clojure
:filter-decisions []
```

Add a recorder helper (alongside `record-search!` at ~line 178):
```clojure
(defn record-filter-decisions!
  "Track auto-filter decisions emitted by a single search pass."
  [!workspace decisions]
  (when (seq decisions)
    (swap! !workspace update :filter-decisions
           (fnil into []) decisions))
  nil)
```

### 5.3 — Wire decisions through the search tool

**File:** `server/src/digdir/skills/builtin/agent/tools.clj`

In the `search` / `search_documents` tool branch (~line 604–684), after `record-search!`, also call:

```clojure
(when-let [decisions (get-in result [:outputs :filter-decisions])]
  (workspace/record-filter-decisions! !workspace decisions))
```

Both the filtered-result and the fallback-result branches need this — filter-decisions can come from either.

### 5.4 — Surface decisions in the trace file

**File:** `server/src/digdir/skills/builtin/agent/core.clj`

In `format-trace-file` (~line 220+), add a new section after `SEARCH HISTORY`:

```
== AUTO-FILTER DECISIONS ==
[1] applied=true fallback=false fields=[orgs_short=Digdir]
[2] applied=false fallback=true reason=zero-hits-with-filter fields=[year=2020]
[3] error=Connection refused fields=(none)
```

Format helper:
```clojure
(defn- format-filter-decision-line
  [idx decision]
  (let [{:keys [detected-filter applied? fallback? fallback-reason error]} decision
        fields-str (->> (get-in detected-filter [:fields])
                        (map (fn [{:keys [field selected-options]}]
                               (str field "=" (str/join "," selected-options))))
                        (str/join "; "))]
    (str "[" idx "] applied=" applied?
         " fallback=" fallback?
         (when fallback-reason (str " reason=" (name fallback-reason)))
         (when error (str " error=" (:message error)))
         " fields=" (if (str/blank? fields-str) "(none)" fields-str)
         "\n")))
```

Add `:filter-decisions (:filter-decisions @!workspace)` to the trace-data payload built in `execute-agent`.

### 5.5 — Surface decisions in playground diagnostics

**File:** `server/src/digdir/playground/diagnostics.cljc`

Add a `compact-filter-decision` helper alongside `compact-shadow-decision`. Add `:filter-decisions` to the `compact-run-state` output. UI components consuming the diagnostics payload can then display a per-pass filter timeline.

Lightweight — mostly mirrors the shadow-decision shape. Defer the actual UI rendering to whoever owns playground UI work.

### 5.6 — Honor `:auto-filter-fallback-mode` parameter

**File:** `server/src/digdir/skills/builtin/retrieval.clj`

In the fallback branch (line 484–490), gate by mode:

```clojure
[result auto-filter-fallback]
(case (or auto-filter-fallback-mode :silent)
  :strict
  [result false]  ;; never fall back; return zero hits

  :hint-only
  ;; If hint-only is set, we shouldn't have applied detected-filter at all.
  ;; This branch handles a config error — log + treat as :silent.
  (do (println "Warning: :hint-only mode but detected-filter was applied; falling back to :silent")
      (if (and detected-filter effective-filter-by (empty? (:merged-hits result)))
        [(run-3-strategy-search ...) true]
        [result false]))

  ;; :silent (default)
  (if (and detected-filter effective-filter-by (empty? (:merged-hits result)))
    [(run-3-strategy-search ...) true]
    [result false]))
```

For `:hint-only`, also gate the FIRST-pass `effective-filter-by` to skip detected fields:
```clojure
effective-filter-by (if (= auto-filter-fallback-mode :hint-only)
                      filter-by
                      (merge-filter-by filter-by detected-filter))
```

The detection still runs and a `:hint-only`-marked decision is still recorded, so the LLM can see "auto-filter detected X but mode is hint-only".

## Tests

**`server/test/digdir/skills/builtin/retrieval_test.clj`** — add:

- `auto-filter-decision-recorded-on-successful-application` — auto-filter detects org, search returns hits → assert `:filter-decisions` contains one decision with `:applied? true :fallback? false`.
- `auto-filter-decision-records-fallback` — auto-filter detects, search returns 0, fallback fires → decision is `{:applied? false :fallback? true :fallback-reason :zero-hits-with-filter}`.
- `auto-filter-decision-records-detection-exception` — stub `detect-query-filters` to throw; assert decision contains `:error`.
- `auto-filter-fallback-mode-strict-suppresses-fallback` — with `:auto-filter-fallback-mode :strict` and 0 hits, no fallback search runs and decision is `{:fallback? false}`.
- `auto-filter-fallback-mode-hint-only-skips-application` — first search ignores detected filter; decision shows `:applied? false`.

**`server/test/digdir/skills/builtin/agent_test.clj`** — add:

- `test-search-records-filter-decisions-in-workspace` — invoke the search tool with a stubbed retrieval result that includes `:filter-decisions`; assert workspace `:filter-decisions` accumulates them and `record-search!` still fires.

**`server/test/digdir/skills/builtin/agent_test.clj` (trace section)** — add:

- `test-trace-renders-filter-decisions-section` — populate workspace with synthetic filter-decisions, call `format-trace-file`, assert the `AUTO-FILTER DECISIONS` section appears with the expected lines.

**`server/test/digdir/playground/diagnostics_test.clj`** — add:

- `compact-run-state-includes-filter-decisions` — feed in a workspace snapshot with filter-decisions, assert the compacted diagnostics output preserves them.

## Files modified

- `server/src/digdir/skills/builtin/retrieval.clj` — 5.1 + 5.6 (decision builder, output key, fallback-mode parameter)
- `server/src/digdir/skills/builtin/agent/workspace.clj` — 5.2 (state slot + recorder fn)
- `server/src/digdir/skills/builtin/agent/tools.clj` — 5.3 (wire from search tool)
- `server/src/digdir/skills/builtin/agent/core.clj` — 5.4 (trace section + payload key)
- `server/src/digdir/playground/diagnostics.cljc` — 5.5 (compact for diagnostics consumers)
- `server/test/digdir/skills/builtin/retrieval_test.clj` — 5 new tests
- `server/test/digdir/skills/builtin/agent_test.clj` — 2 new tests
- `server/test/digdir/playground/diagnostics_test.clj` — 1 new test

## Rollout order

Apply in dependency order so each step is verifiable in isolation:

1. **5.1** (decision builder + output key) — pure addition to retrieval; existing search-attribution paths unchanged.
2. **5.2** (workspace state) — pure addition to workspace; no consumer changes.
3. **5.3** (tools wiring) — connects 5.1 + 5.2; verifiable end-to-end with an integration-style test.
4. **5.6** (fallback-mode parameter) — additive parameter with safe default; defer until 5.1–5.3 are landed and tested.
5. **5.4** (trace rendering) — pure presentation; depends on 5.2.
6. **5.5** (diagnostics compact) — pure presentation; depends on 5.2.

## Verification

After each step:
- `bb test` full suite — must stay at 0 failures.
- `bb lint` — no new warnings in touched files.
- For 5.1: `bb retrieve-debug digdir dataset public-docs "<query that triggers org auto-filter>"` and confirm the EDN output now includes `:filter-decisions` alongside `:filter`.
- For 5.3 + 5.4: run the agent against a query that triggers auto-filter (e.g. "Hvor mange ansatte har Digdir?") and confirm the new `AUTO-FILTER DECISIONS` section appears in the trace file.
- For 5.6 with `:strict`: configure the agent's retrieval skill with `:auto-filter-fallback-mode :strict`, run an org-named query against a corpus that lacks chunks for that org, and confirm the trace shows `applied=true fallback=false` and the agent gets zero hits (rather than silently re-fetching unfiltered).

## Out of scope (deferred)

- **Confidence scoring on detected filters.** `detect-query-filters` currently returns binary present/absent. Adding a confidence (e.g. score of how strong the org-name match is in the query) would make the controller smarter but requires touching `auto_filter.clj` internals. Worth a follow-up if filter precision becomes a concern.
- **Per-tenant fallback-mode UI in the playground.** The skill parameter is exposed; surfacing it as a config UI control is downstream UX work.
- **Hooking shadow-sufficiency into filter-decisions.** Today the agent loop only consults read-signal shadow decisions. A "low-confidence filter caused stagnation" signal could feed re-search hints, but that's an extension on top of this plan.

## Risks and mitigations

**Risk: silent behavior change for tenants relying on the current fallback timing.**
Mitigation: default `:auto-filter-fallback-mode :silent` keeps current behavior byte-for-byte. The new decision recording is additive — old consumers ignore the new key.

**Risk: workspace bloat from per-pass decisions on long agent runs.**
Mitigation: in practice agent runs cap at ~4 search passes; ~4 decisions per workspace is negligible. If this becomes a concern, cap retention to last N passes.

**Risk: trace section becomes noisy for runs that never trigger auto-filter.**
Mitigation: the trace section is only emitted when `(seq filter-decisions)` is truthy (same pattern as `SEARCH ERRORS`).
