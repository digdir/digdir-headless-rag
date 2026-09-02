# Unknown request fields are silently discarded — measurement

**Issue:** #174 · **Base commit:** `8978be0` · **Measured:** 2026-08-21

Evidence for a decision that has not been taken. Nothing here changes behaviour;
the transformer is untouched. Every number below was produced by running
something, and where a measurement contradicted my expectation that is called
out rather than smoothed over.

The question: **every unknown field on every public request is silently dropped
and the request succeeds.** #172 fixed one instance by hand. Should the platform
reject instead of strip?

---

## 1. The mechanism

`reitit.coercion.malli/default-options` — read from
`reitit-malli-0.9.2.jar`, not from memory:

```clojure
;; schema identity function (default: close all map schemas)
:compile mu/closed-schema
;; strip-extra-keys (affects only predefined transformers)
:strip-extra-keys true
;; set of keys to include in error messages
:error-keys #{:type :coercion :in :value :humanized}
```

Both are on. **Every map schema is already closed**, and the decoder strips
extra keys *before* validation runs — so the closing never fires. That ordering
is the whole defect.

### Three consequences, each measured

**`{:closed true}` on a schema does nothing.** I added it to
`create-api-key-body-parameters` during #172 expecting rejection; the request
still returned 201. The key is gone before the closed check sees it. Anyone
reaching for that fix will reach for it again — it is the obvious move and it is
inert.

**Declaring the field is the only lever at schema level.** A declared field
survives stripping and can then fail validation, and its `:error/message`
reaches the client because `:error-keys` includes `:humanized`. That is how
#172's 400 works, and it is per-field, not general.

**Flipping the transformer works and needs one option.**
`reitit.coercion.malli/create` merges over `default-options`, so
`:strip-extra-keys false` is a supported configuration, not a fight:

| request | today | with `:strip-extra-keys false` |
|---|---|---|
| clean body | 201 | 201 |
| body with `typo-field` | **201, field discarded** | **400** |

The 400 body is already well-formed for a client:

```json
{"error":"Request validation failed",
 "details":{"type":"reitit.coercion/request-coercion","coercion":"malli",
            "in":["request","body-params"],
            "humanized":{"typo-field":["disallowed key"]}}}
```

### One thing that surprised me, and it matters for the decision

**camelCase survives the flip.** `digdir.api.util/read-json-body` normalises
`filterValue` → `:filter-value` *before* coercion, so a camelCase caller is
unaffected by strict mode. Measured: `{"name":"K","datasetScopes":[…]}` returns
201 under both settings.

This also means several apparent doc/schema disagreements are **not** real. My
first inventory pass reported `filterValue` as documented-but-not-accepted; it
is accepted. Any future comparison must normalise both sides or it will
manufacture findings.

---

## 2. Inventory: accepted vs advertised

Route tables walked programmatically (`ep/api-routes`, `console-api-routes`,
`debug-routes`), schemas read from the malli definitions, advertisements read
off disk from `openapi.yaml`. Names normalised on both sides.

**13 endpoints declare a request body schema.** Two of the three shapes appear;
one does not.

### Body: real drift

| endpoint | finding | severity |
|---|---|---|
| `POST /api/conversations` | accepts `agent-id`, documented nowhere | accepted-but-undocumented |
| `PUT /console-api/datasets/{id}/pipelines/{id}` | spec marks `properties` **required**; schema says optional | wrongly-marked-required |

**No endpoint documents a body field it does not accept** — after normalisation,
the shape that caused #172 does not recur anywhere else in a body schema. That
is a negative result and it is the most decision-relevant number here: the class
is live in the mechanism, but the *instance* was rare.

### Body: no advertisement at all

Six endpoints accept a body that `openapi.yaml` never mentions:

```
POST /api/runtime/config/resolve        9 fields
POST /api/dataset/config/resolve        6 fields
POST /api/skills/{id}/execute           5 fields
POST /console-api/users                 2 fields
PUT  /console-api/users/{id}/permissions 2 fields
PUT  /console-api/datasets/{dataset-id}  3 fields   (path documented, PUT method absent)
```

### Query parameters: one real cluster

**Six console pipeline endpoints advertise `configKey`; the schema requires
`dataset-config-key`.**

```
GET    /console-api/datasets/{id}/pipelines
GET    /console-api/datasets/{id}/pipelines/{pid}
PUT    /console-api/datasets/{id}/pipelines/{pid}
DELETE /console-api/datasets/{id}/pipelines/{pid}
POST   /console-api/datasets/{id}/pipelines/{pid}/execute
GET    /console-api/datasets/{id}/pipelines/{pid}/executions
```

**This one is loud, not silent** — `dataset-config-key` is *required*, so a
caller following the spec gets a 400 for a missing key rather than a quiet
success. Bad diagnostic, not lost data. Worth fixing on its own; it is not
evidence for the flip.

Ten further query surfaces are absent from the spec: eight `/api/debug/*`
(gated by `X-Debug-Api-Key`, arguably not public), `GET /api/config/{root}/nodes`,
and `GET /console-api/conversations`.

### Not affected at all

`/api/mcp` and both `/v1` routes declare **no** `:parameters`, so no coercion
runs on them. The two surfaces external integrators actually use cannot be
affected by this decision either way.

---

## 3. Blast radius of flipping

**The full test suite passes with `:strip-extra-keys false` applied.**
1672 tests, 6855 assertions, **0 failures, 0 errors** — the same numbers as
without it. Applied locally, measured, reverted.

| caller | verdict |
|---|---|
| `bb test` (1672 tests) | green under the flip |
| `postman-collection.json` — 5 bodies | no unknown fields; all clean |
| `curl-examples.md` | only `/api/mcp`, which is not coerced |
| e2e Playwright | only posts `/api/mcp`; unaffected |
| Operator console | calls Clojure fns through Electric; never crosses HTTP coercion |
| camelCase callers | unaffected — normalised before coercion |

I could not find an in-repo caller that would start receiving a 400.

**What this does not cover:** real external callers. Nothing in the repository
tells us what fields production traffic actually sends — which is the gap
section 4 exists to close.

---

## 4. Detecting it in production, without changing behaviour

**Yes, and it is cheap.** A log-only middleware placed inside the coercion
middleware sees both the pre-coercion body (`:body-params`, already
normalised) and the coerced result (`[:parameters :body]`). Their difference is
exactly what was discarded.

Prototyped and run:

```
clean body                      → 201, no log
body with an unknown field      → 201, WARN dropped [:typo-field]
body with two unknown fields    → 201, WARN dropped [:another :whatever]
```

```clojure
(def log-stripped-keys-middleware
  {:name ::log-stripped-keys
   :wrap (fn [handler]
           (fn [request]
             (let [sent    (set (keys (:body-params request)))
                   kept    (set (keys (get-in request [:parameters :body])))
                   dropped (set/difference sent kept)]
               (when (seq dropped)
                 ;; names only — request bodies carry secrets
                 (t/log! :warn [::request-fields-discarded
                                {:uri (:uri request)
                                 :method (:request-method request)
                                 :dropped (vec (sort dropped))}]))
               (handler request))))})
```

- **Behaviour-neutral.** It only reads and logs; every status code in the probe
  was identical with and without it.
- **Cost:** one set difference per request on a map that is already in memory —
  2000 iterations took 0.52 ms, ~0.26 µs each.
- **Safe to log:** key *names* only. Request bodies carry API keys and secrets;
  values must never be logged.
- **Placement:** appended to the router's middleware vector so it runs inside
  `coerce-request-middleware` and can see `:parameters`.

It answers the question the inventory cannot: whether real callers are sending
fields we discard. Two weeks of that log turns this decision from a judgement
into a count.

---

## My read

*Clearly marked as opinion; the evidence above is not.*

**Ship the detector now; defer the flip until it has reported.** The inventory
found no live silent-drop instance beyond the one #172 already fixed, and the
blast radius inside the repo is zero — so the flip is *safe* but not yet
*justified*. The one number that would justify it is the one we do not have:
what external callers actually send. The detector is cheap, behaviour-neutral
and answers exactly that.

Two things I would not wait for:

- **`configKey` on the six pipeline endpoints** is a doc bug with a bad
  diagnostic, independent of this decision.
- **The six undocumented body schemas** are the same doc-vs-code gap that made
  #172 possible, and #172's drift test is a template that generalises.

If the decision is to flip anyway, the mechanism is one option and the error
body is already client-usable. My hesitation is not about the change's
correctness but about its evidence: we would be choosing a stricter contract for
callers we have never observed.

---

## 5. Is the detector actually firing? (#174 follow-up, 2026-08-24)

The decision above — ship the detector, defer the flip — makes the flip wait on
what the detector reports. So the whole decision rests on one piece of
middleware running, and this fleet has shipped an inert guard before: a
regex deployed, believed, and measured months later at exactly zero effect
because its pattern never matched a real shape.

**The two silences.** A zero from this detector has two possible causes that
produce identical evidence: *no caller is sending undeclared fields*, or *the
detector never fires*. Distinguishing them is the point of this section.

### It is in the chain, and it fires

Measured against a live server, not reasoned about:

| probe | route | result |
|---|---|---|
| undeclared field | `POST /api/conversations` | **one line**, `[:second-bogus :totally-undeclared-field]` |
| undeclared field | `POST /api/runtime/config/resolve` | **one line**, `[:bogus-field-2xx]` |
| clean body | both | **no line** |
| `filterValue` (camelCase alias of a declared field) | `/api/conversations` | **no line** — correctly not a false positive |

**No value ever appeared.** Each probe planted a canary string as the value of
the undeclared field; the canary occurs **zero** times in the whole server log.

### And that is now a standing guard, not a one-off

The four tests that existed drove `(:wrap …)` **directly**, with a synthetic
pre/post-coercion request. They test the function. **A detector that was
defined, correct, and never wired into a router would have passed all four.**

`discarded-fields-log-test` now also drives requests through
`endpoints/api-router` — the same var `digdir.api.http` serves. Demonstrated by
removing the middleware from `api-router-options`: the four original tests
stayed **green** and only the two new chain tests failed. That is the blindness,
shown rather than asserted.

### One correction to the log line

It fires **before** the handler runs, so a request that is later rejected is
measured too — observed directly, since the probes above were rejected
downstream for missing grants and still logged. The message claimed *"the
request succeeded with these fields removed"*, which would mislead anyone
counting. It now says the measurement happens before the handler and does not
imply the request succeeded.

### The arity question, settled from the producer

The detector declares only the 1-arity `:wrap`, while two sibling middlewares
in the same file implement both 1- and 3-arity forms. If any route were served
asynchronously, a 1-arity fn invoked with three arguments would throw.

**There is no async path.** `ring.adapter.jetty/run-jetty` is synchronous
unless `:async? true`, and neither caller sets it — `src-dev/dev.cljc` and
`src-prod/prod.cljc` pass explicit option maps with no `:async?`, and the only
other contributor to prod's options is `electric-manifest.edn`, which
`src-build/build.clj` writes from build flags (`optimize`, `debug`, `version`).
So the asymmetry is harmless and the siblings are merely defensive.

### What it has seen: not answerable from a lane, and here is exactly why

The detector landed **2026-08-21** (`e580f8d`). Reading production logs means
`bb logs-server prod`, which shells to `kamal app logs --follow` against the
deploy host — outward-facing, blocking, and not run from here.

**The one fact that separates a real zero from an unarmed detector is whether
the deployed image contains `e580f8d`.** With that confirmed, a zero is a real
zero, because the detector is now proven to fire on the real router. Without
it, a zero says nothing at all.

**Recommendation, not done here (it is an enhancement, not a fix):** have the
detector emit a single line at startup — armed, with the build's commit — so
the log itself distinguishes *armed and silent* from *absent*. That converts
this question from an investigation into a grep, permanently.
