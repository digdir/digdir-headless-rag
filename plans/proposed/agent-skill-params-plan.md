# Plan — Agent-scoped skill-params

Currently `agent` entities are UI scopes (allowed skill-graphs + dataset
scopes + display name); their behaviour is shaped by **dataset-config
defaults, UI control overrides, and code-level defaults** — but *not* by
anything carried on the agent itself. The Round 1-9 sweep arc's winning
config was applied per-call via the sweep runner's `:skill-params` arg;
no equivalent path exists for Playground or Open WebUI users.

This plan adds `:agent/skill-params` as a first-class field, plumbs it
through Playground + HTTP API + MCP, and adds tests in both UI
environments so that "switch agent in the dropdown" actually changes the
retrieval/rerank/synthesis behaviour end-to-end.

## Motivation

Concretely, this lets us ship the Round-5 / Round-8-control winning
config as a named agent — say, `digdir/altinn-docs-tuned` — embodying:

```clojure
{:builtin/retrieval {:strategy-weights {:content 0.0 :phrase 1.0 :metadata 0.0}
                     :strategy-contribution-caps {:phrase 5 :content 0 :metadata 0}
                     :retrieve-top-k 100}
 :builtin/rerank    {:top-k 20}
 :builtin/agent     {:search-strategy-quota 5}}
```

…and have every Playground conversation and Open WebUI session that
selects that agent automatically pick up those knobs. Future tuning rounds
ship as new agent definitions rather than as global config edits, which
keeps experiments scoped and reversible.

## Out of scope

- A UI for *editing* agent skill-params interactively. Seed via
  ingestion / config code; editing is a separate UX story.
- Versioning of agent definitions over time. We get rough provenance from
  `:agent/updated-at`; richer versioning is its own plan.
- Per-conversation skill-params overrides on top of an agent's
  skill-params. The merge order below leaves that path open if we want it
  later, but no UI affordance.

## Schema change

One new Datahike attribute, modelled on the existing `:agent/guardrails`
(EDN serialised to string):

```clojure
{:db/ident :agent/skill-params
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "EDN map of skill-params overrides applied to every invoke
          call that resolves to this agent. Merged into the
          api-util/build-rag-skill-params output below the per-call
          API overrides and above the dataset-config defaults."}
```

In `digdir.config.schema/agent-schema` (where `:agent/default-skill-graph`
etc. live). EDN is encoded with `pr-str` on write and `edn/read-string`
on read, with a try/catch to fall back to `{}` on parse failure (same
guardrail-handling pattern in `normalize-stored-agent`).

## Merge precedence (highest wins)

```
1. Per-call API body  (existing :params shape used by HTTP API + sweep)
2. UI controls        (Playground only, narrow set today)
3. Agent skill-params (NEW)
4. Dataset config     (existing fields like :rerank-top-k on the dataset)
5. Skill code defaults (in retrieval.clj / rerank.clj / etc.)
```

This places the agent below per-call overrides (sweep users can still
A/B knobs without rebuilding agents) and above the dataset (so the same
dataset can be used by multiple agents with different tuning).

Merge is **deep** so partial overrides stack. If the agent sets
`{:builtin/retrieval {:strategy-weights X}}` and a per-call body sends
`{:builtin/retrieval {:rerank-candidate-k 100}}`, the resolved
skill-params has both keys on `:builtin/retrieval`. The merge helper
already exists for guardrails (`clojure.core/merge` recursively up to one
level — agent and per-call both have `:builtin/<skill> {}` shape — a
single `(merge-with merge ...)` does it).

## Touch points

### 1. `digdir.agents.db`

- Extend the pull-pattern in `agent-pull` to include `:agent/skill-params`.
- In `normalize-stored-agent`, decode the EDN string into `{}` on
  empty/missing, parsed map on present:
  ```clojure
  :skill-params (if-let [s (:agent/skill-params agent-entity)]
                  (try (edn/read-string s) (catch Exception _ {}))
                  {})
  ```
- In `upsert-agent!` (and the bulk import path), pr-str the
  `:skill-params` map back to a string before write. Drop the field from
  the tx when missing/empty so we don't carry empty strings.

### 2. `digdir.agents.core`

`normalize-agent` should validate `:skill-params` is a map of
`{<skill-id-keyword> <param-map>}` and reject anything else. Mirror the
existing `:guardrails` validation. No semantic validation of inner
contents — the skills validate their own params at execution time.

### 3. `digdir.api.util/build-rag-skill-params`

This is the canonical merge surface used by Playground (via
`build-playground-skill-params`) and the HTTP API. Today its signature
is `(dataset-config api-params) → skill-params`. Extend to accept an
optional `agent-skill-params` map and merge it between the two.

```clojure
(defn build-rag-skill-params
  ([dataset-config api-params]
   (build-rag-skill-params dataset-config api-params {}))
  ([dataset-config api-params agent-skill-params]
   (-> (build-from-dataset dataset-config)
       (deep-merge-skill-params agent-skill-params)
       (deep-merge-skill-params (build-from-api-params api-params)))))
```

The MCP path and the sweep runner both ultimately route through this
function; once it accepts an agent's skill-params, every entry point
gets the behaviour for free.

### 4. `digdir.playground.core/build-playground-skill-params`

Already calls `api-util/build-rag-skill-params`; just thread through the
resolved agent's `:skill-params` so the call site receives it. The
agent is already in scope as `selected-agent` in `playground/ui.cljc`.

### 5. HTTP API endpoint (`digdir.api.*`)

Resolve the API key → agent binding to look up the agent's skill-params
at request time (most likely in the request-context middleware where
`request-agent-id` / `resolve-agent-id` already runs). Pass into
`build-rag-skill-params`.

### 6. MCP

The MCP tool already infers agent-id from the API key in
`digdir.api.context`. With (5) above, MCP gets agent skill-params for
free — no extra plumbing.

### 7. Sweep runner (optional)

The sweep already passes skill-params explicitly per matrix cell. Once
agent skill-params exist, matrix authors can choose between:

- "Cell pins skill-params explicitly" (today, A/B knob sweeps)
- "Cell pins an agent-id, inherits its skill-params" (new — useful for
  testing that a published agent maintains its claimed recall)

The runner doesn't need code changes for the first case. For the second
case, add a helper that resolves `:agent-id → :skill-params` before
calling invoke-rag if the matrix cell doesn't pin skill-params. Marked
optional because it isn't blocking the user-facing wins.

## Testing strategy

Three layers, in order from cheapest to most expensive.

### Layer A — Clojure unit tests (cheap, fast)

`digdir.agents.db-test` (new) — round-trip a skill-params EDN map through
upsert + get. Verify partial overrides parse, malformed EDN falls back
to `{}` instead of crashing the agent lookup.

`digdir.api.util-test` (extend) — confirm
`build-rag-skill-params` honours the 5-tier precedence:
- agent below per-call body
- agent above dataset-config
- partial overrides deep-merge correctly

Roughly 6-10 deftest blocks.

### Layer B — Playground UI test (mid)

`digdir.playground.ui-test` (extend, file already exists). Electric
components are unit-testable via the existing pattern in
`playground/ui_test.clj`. Add a test that:

1. Seeds two agents in a test conn:
   - `test/agent-default` — empty `:skill-params`
   - `test/agent-phrase-only` — `:skill-params` with the production-winner phrase-only config
2. Mounts the Playground chat component pointing at that conn
3. Switches the agent dropdown to `test/agent-phrase-only`
4. Dispatches a "send message" interaction
5. Asserts the invocation captured by the mocked `execute-skill-graph`
   received the agent's skill-params merged into its skill-params arg

The send-message dispatch is mocked at `execute-skill-graph` so we don't
hit a live LLM. The assertion is purely on the args shape.

### Layer C — Open WebUI E2E (expensive, real-traffic)

`e2e/playwright/tests/agent-skill-params.spec.ts` (new file). The
existing e2e stack already brings up Open WebUI + the MCP-OpenAPI proxy
(`docker-compose.e2e.yml`). Reuse it.

Two scenarios, each one a separate `test()`:

**Scenario 1 — default agent baseline**:
1. Open the Open WebUI chat, select the model that's wired to the
   `e2e/altinn-docs-default` agent (empty skill-params, falls through
   to dataset defaults)
2. Send the canary question: `Når ble Altinn 3 lansert?`
3. Assert: response contains `"juni 2020"` AND a citation reference
4. Read the most recent invocation's diagnostics from the server's
   `/api/debug/last-invocation?agent=...` endpoint (we may need to add
   this) — assert it does NOT carry phrase-only weights

**Scenario 2 — tuned agent**:
1. Switch model to the one wired to `e2e/altinn-docs-tuned` agent
   (with phrase-only-with-caps skill-params seeded in the e2e fixture)
2. Send a question that the production sweep showed *only* hits under
   phrase-only-with-caps (e.g. one of the `altinn-broker-technical-overview`
   variants — we can pick one from the sweep CSV)
3. Assert the response cites the expected golden chunk-id
4. Read the same `/api/debug/last-invocation` endpoint — assert it DOES
   carry phrase-only weights

The `/api/debug/last-invocation` endpoint is the testability scaffold I'd
add specifically for this — exposes the most recent invocation's
*resolved* skill-params per agent so the test can prove agent-scoping
worked end-to-end. Restricted to dev/E2E by config so we don't ship it.

Layer C is the one that actually proves "switch agent in dropdown =
different retrieval behaviour" works across the full stack. Layer A
proves merge math. Layer B proves Playground wiring. Layer C proves
Open WebUI inheritance.

## Migration / rollout

- Schema add is additive; existing agents get empty `:skill-params`
  parsed as `{}` (or just missing the attr, which `normalize-stored-agent`
  treats the same).
- Once the wiring lands, seed `builtin/agent-rag-agent` with the Round-5
  winner via the import path (`import_export/entities/agents.clj`).
- A separate agent like `digdir.demo/altinn-docs-tuned` can be added
  alongside for A/B in production traffic.

Rollback is just removing the attribute; agent behaviour reverts to
dataset-config defaults.

## Open questions

1. **Should the dataset-config still carry default skill-params at all,
   or should we eventually migrate all dataset-config knobs to agent
   definitions?** I'd defer that decision; the dual path is fine for
   now because datasets and agents map cleanly to different concerns
   (where the corpus lives vs how to query it).

2. **Per-conversation override on top of agent skill-params?** Not in
   scope for this plan — the existing Playground UI control set is
   small and works as a layer above the agent. Adding finer UI is its
   own story.

3. **What goes in `e2e/altinn-docs-tuned`'s skill-params?** Use the
   Round-5 winner verbatim. We can change it later as new sweeps land.

## Sequence of work (suggested)

1. **Schema + db round-trip** (~1h): change `config/schema.clj`,
   `agents/db.clj`, `agents/core.clj`. Run existing tests, ensure no
   regressions in the agent-import path.
2. **API merge layer** (~1h): extend `api/util/build-rag-skill-params`,
   thread through `playground/core` and `api/*` handlers. Add Layer-A
   unit tests.
3. **Debug endpoint** (~30m): `/api/debug/last-invocation` for E2E.
4. **Layer B test** (~1h): one Electric test in
   `playground/ui_test.clj`.
5. **Layer C E2E + fixture agents** (~2h): seed two e2e agents in the
   e2e/.env setup, add Playwright spec, verify both scenarios pass.
6. **Seed `builtin/agent-rag-agent` with the production winner** (~15m):
   one-line import update.

Total: roughly half a day, with the longest piece being the Layer C
Playwright work because it requires real Docker traffic.

The hard cut between (1-3) and (4-6) is at "merge math works"; if we
ship just 1-3 we get the *capability* without the *test coverage*.
Recommend doing all six in sequence.
