# Make `cfg/get` require explicit tenant (slice 1, option a)

## Context

`digdir.config.core/get-tenant` and `get-tenant-config-key` were env/bootstrap-scoped globals. Phases A-C removed the env reads and reduced those helpers to `(constantly nil)`. Every `cfg/get` callsite now effectively passes `nil` tenant, which downstream throws via `config-db/resolve-config-node!`'s `ensure-present-string!`.

The user's directive: tenants bring their own Azure/ColBERT/etc. config; every `cfg/get` call must be dynamically resolved with an explicit tenant from the natural source. No ambient state (no dynamic var, no env-backed helper). Hard break on the signature — no bridge arity.

## New `cfg/get` signature (done)

```clojure
(defn get
  "Get a platform config value from database, scoped by tenant.

   Usage:
     (cfg/get {:tenant t} :services :azure-openai :api-key)
     (cfg/get {:tenant t} [:services :azure-openai :api-key])
     (cfg/get {:tenant t :default \"fallback\"} :services :foo :bar)

   Opts (all optional — caller supplies from its natural source):
     :tenant, :tenant-config-key, :default"
  [opts & path-or-keys]
  ...)
```

Opts map is required as the first arg (not optional). `:tenant` within the opts IS optional but expected at virtually every callsite. If omitted at a site that has no natural tenant source, the caller passes `{:tenant nil}` and the downstream db call throws — surfacing the gap rather than hiding it.

## Status

### Done

- `accessor/get` signature changed to map-form (`server/src/digdir/config/accessor.clj`)
- `skills/context.clj` — deleted `resolve-azure-openai-client`, `resolve-colbert-client`; `resolve-all-services` now returns only `{:typesense ...}`
- Skill metadata `:required-services` adjusted for 8 skills (the set was the pre-resolved-service check; Azure/ColBERT are no longer pre-resolved):
  - Current state: `:required-services #{}` in 8 skills (placeholder, schema still mandates key present)
  - **Follow-up cleanup (pending):** make `:required-services` optional in the skill metadata schema at `server/src/digdir/rag/skills/core.clj:435` (add `{:optional true}`), then delete the `:required-services #{}` lines from: `entity_extraction.clj:49`, `fact_checking.clj:47`, `graph_builder.clj:46`, `rerank.clj:56`, `query_planner.clj:51`, `synthesis.clj:55`, `summarization.clj:52`, `agent/core.clj:52`. Preserve the `:required-services #{:typesense}` declarations in skills that legitimately need it (e.g. `retrieval.clj:65`, `multi_retrieval.clj:45`).
- `llm/openai.cljc` — `use-azure-openai` and `create-chat-completion` now take `tenant` as first arg
- `rag/synthesis.clj` — `rag-generate` and `simplify-convo-topic` pull tenant from `params` (`:tenant`, or `:tenant (:dataset-ref params)` fallback)
- `rag/query_relaxation.clj` — `query-relaxation` and `do-query-relaxation` take `tenant` as first arg; `rag.core/query-relaxation` bridge updated; `src-dev/digdir/tools/diagnostics.clj` 4 callers updated
- `rag/rerank.clj` — `rerank-chunks` pulls tenant from `(:tenant params)` or `(:tenant (:dataset-ref params))`
- `rag/typesense.clj` — removed the tenantless fallback branch from `make-ts-settings`
- `playground/core.cljc:718` — uses `effective-tenant` already in scope
- Auth/request handlers with no tenant source pass `{:tenant nil}` explicitly as a TODO:
  - `api/http.clj:79, 185-186, 274-275`
  - `auth/cookies.clj:12-13, 25`
  - `api/rate_limit.clj:24`
- Skill files — `cfg/get` calls migrated to use `tenant (:tenant skill-params)`:
  - `fact_checking.clj`
  - `query_planner.clj`
  - `summarization.clj`
  - `graph_builder.clj`
  - `entity_extraction.clj`
  - `synthesis.clj` (already destructured `:services`, added `skill-params`)
- `agent/loop.clj:call-llm` — takes `tenant` as first arg; `run-agent-loop` pulls from `(get-in ambient-ctx [:opts :tenant])` and passes on each call; `sufficiency-llm-fn` default is now `(partial call-llm tenant)`
- `agent/read_signals.clj:default-llm-fn` — takes `tenant` as first arg; `selected-model` takes `tenant`; `evaluate-read` accepts `:tenant` in opts and uses `(partial default-llm-fn tenant)` as the default `:llm-fn`

### Pending

#### 1. `workspace.clj:327` — `evaluate-read` caller

File: `server/src/digdir/skills/builtin/agent/workspace.clj`

The new `evaluate-read` signature takes `:tenant` inside its opts map. Current caller passes opts via `:llm-fn (:read-signals-llm-fn opts)` + `:model` + `:temperature`. Need to add `:tenant (get-in opts [:opts :tenant])` or similar.

Verify the opts shape available at line 327 — this is inside a workspace evaluation loop; trace back to find where opts originates. The `ambient-ctx` is in scope per earlier observation.

#### 2. `agent/core.clj:683` — `:read-signals-llm-fn read-signals/default-llm-fn`

File: `server/src/digdir/skills/builtin/agent/core.clj:683`

`default-llm-fn` now requires `tenant` as its first arg. At line 683 it's assigned as `:read-signals-llm-fn`, which is eventually called as `(llm-fn messages tools model temperature)` — no tenant passed. Options:

- **(a)** Use `(partial read-signals/default-llm-fn (:tenant skill-params))` here — tenant is in scope at line 677
- **(b)** Drop the `:read-signals-llm-fn` default entirely and let `evaluate-read` compute it from tenant via its new default path

Option (a) is cleaner — keeps explicit wiring visible.

#### 3. `docs/loader.clj` (4 callsites at lines 505, 506, 513, 526)

File: `server/src/digdir/docs/loader.clj`

These are inside `openai-implementation` called during document pipeline runs. **Tenant source at ingestion time:** every pipeline has a parent (target) dataset, and datasets are tenant-scoped. Resolve tenant as `pipeline → target dataset → :tenant`.

Concretely: trace `openai-implementation` upward to the pipeline entry point. The pipeline definition carries (or can carry) a dataset reference; the dataset record has a `:tenant` field. Thread tenant from that source through to `openai-implementation`.

If the chain passes a pipeline-config map, add `:tenant` to it at the top and read `(:tenant pipeline-config)` in `openai-implementation`. Alternatively, make `openai-implementation` take `tenant` as an explicit arg.

#### 4. `docs/pipeline/search_phrases.clj` (4 callsites at lines 27, 28, 33, 45)

Same pattern as loader.clj — document-pipeline code. Tenant via `pipeline → dataset → :tenant`.

Also: `llm/openai.cljc:create-chat-completion` is called from this file (line 43). Now requires tenant — this file's callsite needs tenant too.

#### 5. `auth/core.clj:133` — `jwt-secret`

File: `server/src/digdir/auth/core.clj:133`

`cfg/get {:tenant nil} :services :auth :jwt-secret` — reads JWT signing secret.

Decision needed: is the JWT secret truly per-tenant? Admin auth (email confirmation flow) creates JWT tokens. If JWT signing is tenant-scoped, we need tenant from somewhere at token-creation time — but admin login is pre-tenant-identification.

**Recommendation:** Treat JWT secret as platform-scoped for now (same as session cookie settings). Pass `{:tenant nil}` with a TODO noting this may need tenant-awareness in a future slice if admin auth becomes multi-tenant.

#### 6. `auth/core.clj:174-179` — Scaleway `def/delay` blocks

```clojure
(def scaleway-region (delay (or (cfg/get {:tenant nil} :services :scaleway-tem :region) "fr-par")))
(def scaleway-project-id (delay ...))
(def scaleway-api-key (delay ...))
(def scaleway-from-email (delay ...))
```

User flagged these during analysis as "not the best example" of needing per-tenant config, but the broader principle (dynamic resolution, no server-start snapshots) still applies.

**Conversion plan:**
- `(def scaleway-region (delay ...))` → `(defn scaleway-region [tenant] (or (cfg/get {:tenant tenant} :services :scaleway-tem :region) "fr-par"))`
- Same for `scaleway-project-id`, `scaleway-api-key`, `scaleway-from-email`

**Callers to update** (grep `@scaleway-` in `auth/core.clj`):
- `scaleway-email-url` (line 181) — no tenant argument currently
- `send-confirmation-code` — caller of `scaleway-email-url`; email-based, pre-tenant-identification

These are all in the admin email-confirmation-code flow, which is pre-auth. Tenant unknown at call time. Options:

- **(a)** Pass `nil` tenant throughout the Scaleway stack, relying on a platform-scoped default tenant in the DB. Consistent with `jwt-secret` above.
- **(b)** Derive tenant from the user's email (look up which tenant the email belongs to, if any, before sending the confirmation).

**Recommendation:** (a) for this slice. (b) is a bigger auth-architecture change.

#### 7. `auth/migration.clj:33` — approved domains

File: `server/src/digdir/auth/migration.clj:33`

`(set (cfg/get {:tenant nil} :services :auth :approved-domains))` — reads the global set of approved email domains for admin login.

Same platform-scoped concern as jwt-secret. Recommendation: pass `{:tenant nil}` for this slice.

#### 8. `llm/marker.clj:11-12` — Marker `def/delay`

```clojure
(def marker-api-url (delay (cfg/get {:tenant nil} :services :marker :api-url)))
(def marker-api-key (delay (cfg/get {:tenant nil} :services :marker :api-key)))
```

Marker is a document-processing service — used during document ingestion. Callers: check for `@marker-api-url` / `@marker-api-key` usages.

**Conversion:** same as Scaleway — convert to `(defn marker-api-url [tenant] ...)`. Callers in the ingestion pipeline should have tenant from the dataset-ref.

Grep for callers once the `def` blocks are converted — the compile errors will point to them.

#### 9. Delete `core/get-tenant` and `core/get-tenant-config-key`

File: `server/src/digdir/config/core.clj`

Once no code calls them, delete the two `(constantly nil)` functions. Verify no remaining references via grep.

### Test fixes

Tests that need updating after the signature break:

| Test file | Issue |
|-----------|-------|
| `test/digdir/config/accessor_test.clj:487, 507, 528, 555` | Direct `(accessor/get :services ...)` calls → wrap with `{:tenant "ka"}` |
| `test/digdir/docs/pipeline/search_phrases_test.clj:153, 165, 177` | `with-redefs [digdir.config.accessor/get (fn [& ks] ...)]` stubs — update signature to `(fn [_opts & ks] ...)` |
| `test/digdir/rag/rerank_test.clj:65` | `with-redefs cfg/get stub-cfg` — update stub signature |
| `test/digdir/rag/core_test.clj:19` | `with-redefs cfg/get (fn [& path] ...)` — update stub signature |
| `test/digdir/api/rate_limit_test.clj:19` | `with-redefs cfg/get (fn [& args] ...)` — update stub signature |
| `test/digdir/skills/builtin/query_planner_test.clj:30` | `with-redefs [cfg/get stub-config]` — update stub signature |
| `test/digdir/skills/builtin/synthesis_test.clj:137` | `with-redefs [llm/use-azure-openai (constantly false)]` — signature changed, now takes tenant. Stub needs to ignore the arg: `(fn [_] false)` |
| Any other tests referencing `llm/use-azure-openai` | Update stubs to `(fn [_] ...)` or `(fn [_tenant] ...)` |
| `test/digdir/playground/core_test.clj:222, 281` | `llm/use-azure-openai (fn [] false)` → `(fn [_] false)` |

Run full `bb test` after the migration and address any remaining test failures. Some tests may rely on skill-params having a `:tenant` key — we added tenant reads from `(:tenant skill-params)`, so tests that construct skill-params without `:tenant` will get nil tenant and downstream will throw. Tests with minimal ctx fixtures (`{:inputs {} :parameters {} :services {} :skill-params {}}`) need a `:tenant "some-test-tenant"` added.

## Decisions resolved (per user)

1. **JWT secret, session cookies, approved domains, Scaleway, Marker** — treated as pre-auth with `{:tenant nil}` passed explicitly and a TODO. Long-term resolution deferred to a follow-up slice (either add a platform-level fallback in the DB, or plumb tenant from email at admin-login boundary).

2. **`read-signals-llm-fn` plumbing in `agent/core.clj:683`** — use option (a): `(partial read-signals/default-llm-fn (:tenant skill-params))`. Keeps explicit wiring visible.

3. **`docs/loader.clj` and `docs/pipeline/search_phrases.clj` tenant source** — pipeline → target dataset → `:tenant`. Datasets are tenant-scoped, so the dataset record is the natural source.

4. **Skill `:required-services` schema** — make the key optional in the schema (add `{:optional true}` at `server/src/digdir/rag/skills/core.clj:435`), then delete the `:required-services #{}` placeholder lines from the 8 skills that don't require any pre-resolved services.

## Order of operations for the remaining work

1. Fix the in-progress skill-plumbing leaks (workspace.clj, agent/core.clj tenant propagation) — #1, #2
2. Convert the two `def/delay` blocks (auth/core Scaleway + llm/marker) — #6, #8
3. Thread tenant through pipeline-time callers (`docs/loader.clj`, `docs/pipeline/search_phrases.clj`) — #3, #4
4. Migrate remaining request-time/platform-ish sites with `{:tenant nil}` TODOs — #5, #7
5. Delete `core/get-tenant` and `core/get-tenant-config-key` — #9
6. Run `bb lint` — triage any compile errors (expected: a handful from missed callsites)
7. Run `bb test` — fix test stubs per the table above
8. Full sweep: grep for any remaining positional `cfg/get` forms to confirm nothing was missed

## Verification

- `bb lint`: baseline was 61 errors / 222 warnings. After slice 1, expect the same errors (all pre-existing, in `ui/inheritance.cljc` Electric macros). Warnings may drop slightly as dead code is cleaned up.
- `bb test`: baseline had 5 pre-existing failures (`test-format-search-metadata-results` ×3, 2 import-export api-key tests). Slice 1 should not introduce new failures beyond those.
- Manual grep: `grep -rnE "cfg/get\s+:|cfg/get\s+\["` — should return zero hits (all calls must now start with `cfg/get {...}`).

## Non-goals for this slice

- Solving the admin-auth/pre-tenant architectural question (deferred — using `{:tenant nil}` as explicit TODO markers).
- Adding runtime warnings or metrics for `{:tenant nil}` calls.
- Migrating the auth flow to be tenant-qualified (e.g., `/auth/{tenant}/login` routes).
