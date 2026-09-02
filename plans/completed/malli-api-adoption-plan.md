# Malli Adoption Plan For API Consistency

Status: Completed on 2026-04-11.

Review note: Route-level Malli coercion now covers the public API, skill/graph
execution routes, console mutation routes, and the main query-heavy
GET/query endpoints. The Phase 1 hardening items also landed
(`api-json-body-middleware`, standardized coercion errors, strict boolean
coercion, shared path schemas, extracted coerce middleware factory, and
tightened JSON-only `value-type` handling). The remaining Phase 6 items are
follow-up maintenance rather than blockers for the migration goal.

## Goal

Introduce Reitit route-level request schemas and Malli-based coercion for the
API layer so request parsing, validation, and coercion become explicit,
consistent, and easier to keep aligned with documentation.

This plan focuses on transport-level consistency:

- query params
- path params
- JSON request bodies
- standardized coercion and validation errors

It does **not** attempt to replace domain validation, authorization, or
database existence checks inside handlers.

## Current State

The API already uses:

- Reitit for route registration
- Ring `wrap-params` for query parsing
- Malli as a dependency

But it does **not** currently use:

- route `:parameters`
- Reitit coercion middleware
- Malli request schemas for the API handlers

Instead, handlers mostly:

1. read `:path-params` and `:params` manually
2. `slurp` request bodies
3. call `json/parse-string`
4. normalize keys ad hoc
5. throw `ex-info` for missing fields

## Desired End State

For API routes that accept structured input:

- route definitions declare `:parameters`
- Reitit + Malli coerce path/query/body input before handler code
- handlers receive typed, validated request data
- transport-level errors are standardized and centralized
- OpenAPI and handler behavior drift less over time

Handlers should still own:

- authorization checks
- ownership checks
- domain invariants
- DB existence checks
- cross-resource compatibility checks

## Proposed Technical Pattern

### Route Shape

Each route should declare input schemas close to the route definition.

Example shape:

```clojure
["/conversations/:id"
 {:get {:parameters {:path  [:map [:id string?]]
                     :query [:map [:include_diagnostics {:optional true} boolean?]]}
        :handler get-conversation-handler}}]
```

### Handler Shape

Handlers should stop parsing transport input directly and instead read coerced
values from `ring-req`.

Preferred handler input access pattern:

```clojure
(let [path-params  (get-in ring-req [:parameters :path])
      query-params (get-in ring-req [:parameters :query])
      body-params  (get-in ring-req [:parameters :body])]
  ...)
```

### Coercion Scope

Use Malli for:

- booleans
- integers
- enums
- optional fields
- nested request body maps
- array/vector element validation

Do not use Malli as a replacement for:

- “dataset must exist”
- “API key owner must match”
- “selected agent may access this dataset”
- “runtime config resolution succeeded”

Those should remain imperative checks in handler/domain code.

## Migration Principles

1. Start with public JSON endpoints where transport parsing is most repetitive.
2. Prefer additive migration at route boundaries over large handler rewrites.
3. Keep request normalization rules explicit.
4. Remove duplicated helper logic only after the new route-schema path is in use.
5. Do not attempt full response-schema adoption in the first pass.

## Phase 1: Foundation

### Objective

Prepare the API router to support Malli-backed request coercion without changing
all handlers at once.

### Tasks

1. Add Reitit coercion support to the API router stack.
2. Choose one canonical coercion and error format for invalid request input.
3. Add a shared helper for reading coerced parameters from
   `[:parameters :path|query|body]`.
4. Add one example route migrated end-to-end.

### Deliverables

- router-level coercion wiring
- one migrated endpoint proving the pattern
- tests for coercion success/failure behavior

### Recommended First Endpoint

`GET /api/conversations/:id`

Why:

- small query contract
- one path param
- no JSON body
- already recently touched
- good proof of boolean query coercion

### Status

**Completed.** Foundation wiring landed alongside Milestone A (and most of
Milestone B). Concretely:

- `reitit.coercion.malli/coercion` is wired into `api-router`,
  `debug-router`, and `console-api-router` via `api-router-options` /
  `debug-router-options` in `digdir.api.routes.endpoints`.
- A custom `api-json-body-middleware` parses + normalizes JSON bodies into
  `:body-params` for routes that declare `:parameters {:body ...}`.
- A custom `api-coerce-exceptions-middleware` (and EDN-formatted twin
  `debug-coerce-exceptions-middleware`) translates Reitit coercion failures
  into a standardized JSON `400` body
  `{"error": "Request validation failed", "details": ...}`.
- Shared helpers `request-path-params`, `request-query-params`,
  `request-body-params`, and `read-json-body` live in `digdir.api.util`.
- ~30 schemas covering the Phase 2/3/4 endpoint groups are colocated in
  `digdir.api.routes.endpoints`.

## Phase 1 Hardening (review findings)

A code review of the Phase 1 landing surfaced several issues that should be
fixed before declaring the foundation done. These are scoped tightly to
correctness and consistency of the new wiring; they do not expand the
migration scope.

### Issue 1 — Invalid JSON body produces 500, not 400

`api-json-body-middleware` parses request bodies via `read-json-body`, which
throws `(ex-info "Invalid JSON body" {:status 400})` on parse failure. The
exception bubbles into `api-coerce-exceptions-middleware`, but
`handle-coercion-exception` only recognizes `::coercion/request-coercion` and
`::coercion/response-coercion` types — so the ex-info is re-raised and the
client gets a 500 with a stack trace instead of a 400.

**Fix:** translate the JSON parse failure into a 400 response inside the JSON
body middleware itself (or extend the coerce-exceptions middleware to handle
generic `:status` ex-infos). Add a regression test posting a malformed body.

### Issue 2 — Dead `truthy-query-param?` fallback in `get-conversation-handler`

The new schema `[:include_diagnostics {:optional true} boolean?]` causes
Reitit's string coercer to reject anything other than `"true"` / `"false"`
*before* the handler runs. The handler still has a fallback branch that calls
`truthy-query-param?` for `"1" / "yes" / "on"` — that branch is now dead, and
the regression it would have papered over is now silent: the public API
previously accepted `?include_diagnostics=1`, and no longer does.

**Fix:** pick a direction explicitly. Either:

- Accept the strict behavior (recommended): drop the fallback and the
  `truthy-query-param?` helper, document the alias change in this plan.
- Preserve the lenient behavior: drop the schema for that parameter (or use
  a custom Malli decoder) and keep the helper.

This plan adopts the strict approach: clients should send proper booleans on
the public API. The alias change is intentional; document it in the
release notes for this branch.

### Issue 3 — Reitit Malli silently strips unknown fields

`reitit.coercion.malli/default-options` sets `:compile mu/closed-schema` plus
`:strip-extra-keys true`, so any field not declared in the body/query/path
schema is removed before the handler sees it — there is no validation error,
the field simply disappears.

This is acceptable for the migration but easy to trip on. Add a comment near
`api-router-options` documenting the behavior, and treat schema-vs-handler
field drift as a review checklist item: if a handler reads `(:foo params)`,
the schema must declare `:foo`.

**Fix:** add the explanatory comment now. Defer any automated audit/lint to
Phase 6.

### Issue 4 — Inline path schemas instead of reusing existing defs

`endpoints.clj` defines `resource-id-path-parameters` and
`api-key-path-parameters` but several routes still inline
`[:map [:id [:string {:min 1}]]]`. Replace each inline schema with the
shared def so that future tightening (e.g. adding `:re #"[a-z0-9-]+"`) only
needs to happen in one place.

### Issue 5 — `debug-coerce-exceptions-middleware` duplicates the JSON variant

`api-coerce-exceptions-middleware` and `debug-coerce-exceptions-middleware`
share ~35 lines of try/catch wiring; the only delta is JSON vs EDN response
shaping. Extract a `make-coerce-exceptions-middleware` factory.

### Issue 6 — `[:value-type [:or keyword? string?]]` is unreachable as keyword

JSON cannot transmit keyword values, so the `keyword?` alternative in
`retrieval-filter-field-body-parameters` is dead. Tighten to `string?`.

### Deferred to Phase 6

The following review items are real but fit better with the broader cleanup
phase:

- Two `param-value` implementations (`digdir.api.util` and
  `digdir.api.context`).
- `request-body-params` falls back to slurping when no `:body-params` /
  `[:parameters :body]` is present; this fallback should be removed once all
  body routes are migrated.
- ~30 schemas in `endpoints.clj` could move to a dedicated
  `digdir.api.routes.schemas` namespace.
- Pagination handlers still hand-roll defaults (`50`, `1`, etc.) instead of
  using Malli's `{:default ...}` support, even though `:default-values true`
  is enabled in the coercer.

### Test Coverage Gaps

- No test posting an invalid JSON body to verify the 400 path.
- No explicit test that an unknown body field is silently dropped (would
  document the strip-extras behavior for future maintainers).

## Phase 2: Public Query API

### Objective

Migrate the core public API endpoints that have the highest transport-validation
value.

### Target Endpoints

1. `GET /api/conversations`
2. `GET /api/conversations/:id`
3. `POST /api/conversations`
4. `PUT /api/conversations/:id`
5. `POST /api/rag`
6. `POST /api/retrieve`
7. `POST /api/runtime/config/resolve`
8. `POST /api/dataset/config/resolve`

### Why These First

- heavy manual body parsing
- repeated optional/default field logic
- mixed snake/kebab/camel handling
- public contract importance

### Example Schema Opportunities

#### Conversations

- `page_size` integer with bounds
- `page_index` integer with bounds
- `include_diagnostics` boolean
- request body title/filter shape

#### RAG

- `query` required string
- `conversation-id` optional string
- `model` optional string
- `dataset-ref` structured map
- retrieval tuning params as bounded integers

#### Retrieve

- `query` required string
- `include-query-expansion` boolean
- nested `filter` structure

#### Config Resolve

- typed `paths`
- required `tenant`
- required config key fields
- required `agent-id` where applicable

## Phase 3: Skills And Graph Execution

### Objective

Migrate the skill/graph execution surfaces, which currently repeat the same JSON
body validation patterns.

### Target Endpoints

1. `POST /api/skills/:id/execute`
2. `POST /api/skill-graphs/:id/execute`
3. `POST /api/skill-graphs/execute`

### Schema Opportunities

- required `inputs`
- optional `parameters`
- optional `overrides`
- required `graph` for custom graph execution
- explicit dataset selection shape

### Notes

These endpoints are good Malli candidates because they are structurally
regular, but response validation is likely not worth doing in the first pass
because execution results are flexible.

## Phase 4: Console Mutation API

### Objective

Migrate the console JSON endpoints that perform creation/update operations and
currently mix parsing, normalization, and domain checks heavily.

### Target Endpoints

1. `POST /console-api/api-keys`
2. `PUT /console-api/api-keys/:key-id/allowed-config-keys`
3. `POST /console-api/api-keys/:key-id/revoke`
4. `POST /console-api/api-keys/:key-id/rotate`
5. `POST /console-api/users`
6. `PUT /console-api/users/:id/permissions`
7. dataset and pipeline create/update endpoints

### Why These Matter

- nested request bodies
- repeated required-field checks
- nested array/map normalization
- higher admin-surface correctness requirements

### Boundary

Use Malli to validate the request shape, but keep:

- “dataset exists”
- “user owns API key”
- “agent refs are valid”
- “pipeline belongs to dataset”

in imperative code after coercion.

## Phase 5: Query-Heavy Console And Debug Endpoints

### Objective

Clean up the remaining GET/query endpoints with inconsistent query param
handling.

### Target Endpoints

1. `GET /api/config/:root/nodes`
2. debug endpoints under `/api/debug/*`
3. console dataset/pipeline execution listing routes

### Why Later

- smaller external-facing consistency benefit
- debug endpoints are less important than public API correctness
- some are operational and can tolerate helper-based parsing longer

## Phase 6: Consolidation

### Objective

Remove legacy transport helper duplication once enough routes are schema-driven.

### Target Cleanup

1. Remove duplicated `param-value` / `non-blank-value` helpers from multiple
   namespaces.
2. Eliminate one-off boolean/int parsing helpers where route coercion covers
   them.
3. Reduce direct `json/parse-string` / `slurp` usage in handlers.
4. Standardize request-validation error responses.

### Important Constraint

Do not remove helpers still needed by non-migrated routes.

## OpenAPI Alignment Plan

Malli adoption creates an opportunity to reduce schema drift, but OpenAPI should
be treated as a second step.

### Recommended Approach

1. First make request schemas explicit in route code.
2. Then decide whether to:
   - hand-maintain OpenAPI from the new schemas, or
   - generate parts of OpenAPI from shared schema definitions

### Recommendation

Do not block request-coercion adoption on full OpenAPI generation.

## Testing Strategy

For each migrated route, add tests for:

- valid request success
- invalid query/body/path shape returns `400`
- coercion of booleans and integers
- default values where intended
- unchanged domain-level failures after valid transport input

Also add a few router-level tests for:

- coercion failure format
- missing required path/query/body keys

## Risks

### Risk 1: Mixed-style transition

During migration, some routes will use Malli and some will not.

Mitigation:

- migrate by route group
- document the pattern
- do not partially convert one handler family

### Risk 2: Over-modeling

If schemas try to encode business rules, they will become noisy and brittle.

Mitigation:

- limit Malli to transport shape/coercion
- keep domain checks imperative

### Risk 3: Alias behavior changes

Some handlers currently accept multiple key styles.

Mitigation:

- make alias acceptance explicit in schemas or pre-normalization
- add regression tests before tightening behavior

### Risk 4: Error response changes

Coercion errors may differ from current hand-written `ex-info` messages.

Mitigation:

- define a clear coercion error contract early
- preserve existing status semantics where possible

## Recommended First Milestone

### Milestone A

Migrate the conversations slice:

1. `GET /api/conversations` ✅
2. `GET /api/conversations/:id` ✅
3. `POST /api/conversations` ✅
4. `PUT /api/conversations/:id` ✅

### Why

- small, bounded surface
- both query and JSON-body examples
- immediately visible consistency improvement
- good place to standardize pagination and optional booleans

### Success Criteria

- all request parsing removed from those handlers ✅
- route-level schemas define path/query/body inputs ✅
- focused tests cover coercion behavior ✅
- no default behavior change for successful existing requests
  ⚠️ **Intentional change:** `?include_diagnostics` now requires literal
  `true` / `false`. The previous implementation accepted `1`, `yes`, `on`.
  See Phase 1 Hardening, Issue 2.

## Recommended Second Milestone

### Milestone B

Migrate the public query execution slice:

1. `POST /api/rag`
2. `POST /api/retrieve`
3. `POST /api/runtime/config/resolve`
4. `POST /api/dataset/config/resolve`

### Why

- highest parsing repetition
- highest contract complexity
- biggest reduction in manual request handling

## Implementation Notes

When introducing Malli:

- prefer explicit schemas near route declarations
- keep schema names stable and human-readable
- separate reusable sub-schemas for:
  - dataset ref
  - runtime config selection
  - pagination
  - allowed config key
  - dataset scope

Avoid:

- giant global schema registries too early
- generating all responses from Malli in the first pass
- trying to migrate auth HTML routes in the same effort

## Deliverable From This Plan

At the end of the migration:

- request handling across the API becomes consistent
- handler bodies get smaller and more domain-focused
- helper duplication is reduced
- OpenAPI drift becomes easier to control

## Historical Note

The "Immediate Next Step" guidance above was accurate when the plan was first
written. The later route groups have since landed, so the migration itself is
complete and the remaining cleanup items are no longer gating work.
