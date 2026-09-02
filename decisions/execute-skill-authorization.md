# Architecture Decision: Authorization contract for `execute-skill-handler`

**Status:** accepted for v0.1 · Resolves #27

## Context

`POST /api/skills/:id/execute` executes a single built-in skill directly,
outside the agent loop. When `/api/rag` was retired, the question of what
authorization it should enforce was never settled — and #27 asked whether the
old per-key scoping was *intentionally* dropped or simply not carried across.

The answer needed reading the code rather than the prior description, because
the code had drifted from it in one respect. What it enforces today, verified on
current HEAD:

| # | Enforced | Where |
|---|---|---|
| 1 | Caller has an API key with the `:query` scope | `wrap-required-api-key-scope :query` at the route |
| 2 | `tenant` + `dataset-config-key` must be **explicit**; there is no fallback to the key's scopes | `select-request-dataset-ref!` → `400` *"Dataset selection is required"* |
| 3 | The requested dataset must be among the key's granted scopes | `select-request-dataset-ref!` → `403` |
| 4 | — no restriction on *which* skill may be executed | `(keyword "builtin" skill-id-str)`, no allowlist |
| 5 | — no agent policy is applied | no agent is selected on this path |

Two findings came out of establishing that.

**The handler passes an option nothing reads.** It calls
`resolve-request-dataset-context!` with `{:require-explicit? true}`, but that key
appears in no destructuring anywhere in `src/` or `test/` — its only occurrence
is the call site. Explicit-only is enforced regardless, *unconditionally*, for
every caller of that function. So the behaviour is correct and the flag is
decorative. It is worth removing rather than leaving: it implies the requirement
is opt-in, so a future caller omitting it would reasonably expect a fallback, and
someone passing `:require-explicit? false` would get explicit-only anyway with no
error. That is the same failure shape as #174's `{:closed true}` — an option that
looks load-bearing, changes nothing, and fails in the direction that looks like
success.

**Agent policy applies on one surface and not the other.** `mcp/tools.clj`
intersects the key's dataset scopes with the agent's `:allowed-dataset-scopes`
before exposing a tool. `execute-skill-handler` passes no
`:allowed-dataset-scopes`, so `policy-restricted?` is false and the agent's
narrowing never applies. A key whose agent is restricted to a subset of its
datasets can reach the rest by calling this endpoint directly.

## Decision

1. **Explicit-only is the contract, and it is intended.** Deriving a dataset from
   the key's scopes would make the effective target of a call depend on the key's
   configuration rather than on the request, which is precisely the ambiguity the
   `400` prevents when a key holds more than one scope. Remove the dead
   `:require-explicit?` flag; keep the behaviour.

2. **No `:allowed-skills` gate in v0.1.** The executable set in the production
   artifact is the registered `builtin/*` skills, all of which read or compute
   over a dataset the caller has *already been granted* — the enrichment skills
   that write live in `src-dev` and are not in the artifact. So the residual
   exposure is **LLM spend**, not data access or mutation, and it is bounded by
   the key's dataset grants. A new grant surface would have to be modelled,
   migrated onto existing keys and kept in sync with the skill registry; that is
   not justified against a spend exposure that rate limiting addresses more
   directly.

3. **Agent `:allowed-dataset-scopes` is NOT a security boundary.** It is a
   routing and UX constraint that shapes what the MCP surface exposes. The
   security boundary is the API key's own dataset grants, which both surfaces
   enforce. This is written down because the asymmetry is invisible from either
   surface alone, and the natural assumption — that a restricted agent restricts
   the key — is false.

4. **MCP tool-name construction constrains discovery, not invocation.** #27 asked
   whether `<agent-id>__<skill-graph-short-name>` already constrains this
   adequately. It does not: it determines which tools a caller can *see*, which
   is a different thing from what they can *call*.

## Consequences

- The security property in row 3 now has a test. It previously had none — neither
  at the handler nor at `select-request-dataset-ref!` — so the one guarantee this
  endpoint actually makes was unpinned.
- If enrichment skills are ever promoted from `src-dev` into the artifact (#82),
  **decision 2 must be revisited before that lands**, because it rests on the
  executable set being non-mutating. That is the trigger condition, and it is the
  reason this is dated rather than permanent.
- Rate limiting, not an `:allowed-skills` gate, is the lever for the spend
  exposure.

## Related

- #174 — the same silently-ignored-option failure shape, at the coercion layer
- #82 — promoting enrichment skills is the trigger to revisit decision 2
- `decisions/agents-skills-and-datasets.md` — where agent policy is defined
