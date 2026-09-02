# Standards alignment: does v0.1 map onto what new users expect?

**Analysis for [#108](https://github.com/itonomi/digdir-headless-rag/issues/108).**
**Date: 2026-08-21.** Author: analysis pass, branch `analysis/108-standards-alignment`.

This is a findings document. It does not contain an epic. The shape
recommendation in §7 is what the epic should be cut from.

> **Superseded in part (2026-08-22, #226).** This is a dated snapshot and is
> kept as written. Two things it records as current are no longer true: the
> server implemented MCP `2025-03-26` when this was analysed and now
> implements **`2026-07-28` only** (#146), and MCP tool names appear here in
> the slash form, which is now the **dot** form on the wire
> (`builtin.agent-rag-agent__…`, #122). Read the version and identifier
> claims below as history. For what a client must send today, see
> [`server/docs/api/endpoints/mcp.md`](../server/docs/api/endpoints/mcp.md).

---

## 0. Method, and what it cost

Every claim below about what a standard says was fetched during this
analysis on **2026-08-21**. Sources with dates are listed in §9. Where I
could not verify something I have written **UNVERIFIED** rather than
asserting it. Three things I expected to be true from prior knowledge
turned out to be stale, and they changed the answer:

1. MCP is no longer at the revision I assumed. The current specification
   is **2026-07-28** — published three weeks before this analysis — and it
   removed the `initialize` handshake, protocol-level sessions, and the
   GET SSE stream. Our server implements **2025-03-26**.
   **[SUPERSEDED 2026-08-21 by #146: the server now implements 2026-07-28
   only, and accepts no other revision. The sentence above was true when
   written; it is kept because it is the finding that prompted the move.]**
2. "Agent Skills" is no longer only an Anthropic product concept. It is a
   standalone specification at `agentskills.io`, has a `.well-known` discovery
   RFC, and has an **MCP working group** (`Skills over MCP`, converted from
   interest group to working group on 2026-04-16) with an open extension
   proposal that would put `skills/list` and `skills/get` on the MCP wire.
3. A2A reached **v1.0** under the Linux Foundation and its `AgentCard`
   carries a `skills[]` array — a third, different meaning of "skill".

The judgement throughout is the one the issue asks for: **does a new user
succeed faster?** Not "can we claim conformance". Several divergences below
are good and should be kept.

**Local gates (CI was down account-wide during this analysis; it is back as of
today, and this branch touches documentation only):**

```
bb lint   0 errors, 219 warnings   (pre-existing baseline; no code changed)
bb test   1612 tests, 6386 assertions, 0 failures, 0 errors
```

---

## 1. Headline

Our concepts are internally coherent and, in one case (`dataset` /
`pipeline`), sharper than the ecosystem's. The problem is not the model.
The problem is that **the model is only partially published**:

- We implement one of MCP's three server primitives and declare no
  decision about the other two.
- We ship a second public surface (`/v1`) that appears in no API document.
- The one argument that makes multi-turn work — `conversation_id` — is
  accepted by the server, documented in prose, and **absent from the
  machine-readable schema the client is given**, so no LLM-driven client
  can discover it.
- Our most actionable error message is delivered in the envelope the MCP
  specification designates as *least* likely to let a model recover.

The `skill` / `skill graph` collision the issue leads with is real, is
getting worse, and is the **least urgent** thing in this document — because
it causes mis-prediction, not failure. The items above cause failure.

---

## 2. Area 1 — Concept and vocabulary alignment

### 2.1 `skill` and `skill graph` — the collision is real and has three edges

**Ours.** A `skill` is a pure function with typed inputs and outputs
(`retrieval`, `rerank`, `synthesis`, `query-planner`). A `skill graph` is a
DAG composing them — e.g. `basic-rag` = `query-planner → retrieval → rerank
→ synthesis` (`server/src/digdir/skills/templates/builtin.clj:15`).

**The ecosystem's, as of today.** Three established, mutually consistent,
and all differently-shaped meanings:

| Source (fetched 2026-08-21) | "skill" means |
|---|---|
| [Agent Skills specification](https://agentskills.io/specification) | A **directory** containing `SKILL.md` (YAML frontmatter: `name` ≤64 chars lowercase-alnum-hyphen, `description` ≤1024 chars, optional `license` / `compatibility` / `metadata` / `allowed-tools`) plus optional `scripts/`, `references/`, `assets/`. Three-level progressive disclosure: metadata always loaded (~100 tokens), body on activation (<5k tokens), resources on demand. |
| [Anthropic platform docs](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview) | Same artefact, delivered via the `container` parameter alongside the code-execution tool, uploaded through the `/v1/skills` endpoints. `name` may not contain the reserved words "anthropic" or "claude". |
| [A2A v1.0 specification](https://a2a-protocol.org/latest/specification/) | An `AgentSkill` object inside an `AgentCard` — `{id, name, description, tags, examples, inputModes, outputModes, security}`. A **capability advertisement**, not an executable unit. Cards are published at `/.well-known/agent-card.json`. |

None of the three is "a node in a DAG". The ecosystem word for our thing is
**step**, **stage**, or **node**; for our `skill graph` it is **workflow** or
**graph**.

**And the collision is still hardening.** The MCP project has a
[Skills over MCP working group](https://modelcontextprotocol.io/community/working-groups/skills-over-mcp)
(interest group formed 2026-02-01, converted to working group 2026-04-16)
whose current direction is
[SEP-2640, the Skills Extension](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640)
— extension identifier `io.modelcontextprotocol/skills`, methods
`skills/list` and `skills/get`, resources addressed as
`skill://<path>/SKILL.md`. Opened 2026-04-23, still open with review
comments as of 2026-08-20. The 2026-07-28 specification already lists
"Skills over MCP" among its notable extensions.

**Classification: ACCIDENTAL.** This is the strongest evidence in the
document, and it is worth being precise about, because the fair reading is
"a naming slip", not "negligence".

`decisions/skill-based-agentic-rag.md` (dated 2026-02-04) adopts the word
and lists exactly one external reference: *"Anthropic MCP Skills and
Agents — https://cra.mr/mcp-skills-and-agents/"*. That post (David Cramer,
published **2026-01-20**, fetched today) defines skills as *"reusable
prompts, with optional bundled artifacts such as scripts or other
material"* — i.e. the packaged-bundle meaning, two weeks before our ADR.
The ADR cites it and then uses the word for a different shape, and records
no comparison. `decisions/agents-skills-and-datasets.md` (2026-03-25)
carries the vocabulary forward and likewise never contrasts it.

So: the *word* was in the air and deliberately reached for. The *shape
divergence* was never noticed. That is the definition of accidental.

**Where it leaks to a newcomer — and this is the expensive part.** The word
is not confined to internals. It is in:

- public routes: `GET /api/skills`, `GET /api/skills/tools`, `GET /api/skill-graphs`
  (all listed in `server/docs/api/README.md`);
- **MCP tool names**: `builtin/agent-rag-agent__agent-rag-graph-bundled`;
- **OpenAI model ids**: the same strings, byte for byte;
- API-key grant fields: `:api-key/skill-graphs`;
- stored conversation rows: `:skill-graph-id`.

URLs, identifiers, and access-control grants — precisely the places the
issue names as expensive after release.

**Impact assessment.** The concrete failure mode is narrow: a user who
knows Agent Skills reads `/api/skills` and expects to POST a `SKILL.md`
bundle, or expects `skills/*` over MCP to return bundles. They get neither
and are confused for ten minutes. Nobody's integration *breaks*. The
forward-compatibility trap is sharper: if SEP-2640 ships and we ever adopt
it, `skills` on our MCP endpoint would mean the other thing.

### 2.2 `agent` — keep it, with one caveat

`decisions/agents-skills-and-datasets.md` (2026-03-25) defines an agent as
a policy-governed actor: instructions, allowed skill-graphs, allowed
dataset refs, guardrails, response policy. That matches the ecosystem
closely — A2A's specification describes an agent as "the complete service
publishing multiple skills". **No divergence worth acting on.**

The caveat: some of our agents run a *fixed* graph (`basic-rag`), others run
a ReAct loop (`agent-rag-graph-*`). By
[Anthropic's own distinction](https://www.anthropic.com/engineering/building-effective-agents)
(published 2024-12-19, fetched today) — *"Workflows are systems where LLMs
and tools are orchestrated through predefined code paths"* versus
*"Agents… are systems where LLMs dynamically direct their own processes and
tool usage"* — a digdir agent bound to `basic-rag` is a **workflow**, and we
call it an agent with no signal to the caller. Low impact; fixable for free
via tool `title`/`annotations` (§3.5) rather than a rename.

**Classification: ACCIDENTAL. Impact: LOW.**

### 2.3 `tool` — overloaded three ways inside one public surface

A newcomer reading our output encounters three unrelated things called
"tool":

1. **MCP tool** = one `(agent × skill-graph)` pair — a coarse product unit
   (`server/src/digdir/mcp/tools.clj:150`).
2. **`GET /api/skills/tools`** = OpenAI function-calling definitions for
   individual skills (`digdir.skills.api/get-all-tool-definitions`, whose
   docstring reads *"tool definition maps for OpenAI function calling"*).
   This route is in the public API table.
3. **Agent-loop tools** = `search`, `read_chunks`, `plan_queries`,
   `rerank_results`, `generate_response`, `inspect_filters`
   (`server/src/digdir/skills/builtin/agent/tools.clj:17-115`), which surface
   to clients as `:tool/call` / `:tool/result` progress events during an
   MCP `tools/call`.

So a client streaming progress sees `tool/call search` *inside* a tool call
named `builtin/agent-rag-agent__agent-rag-graph-bundled`, and can also `GET`
a third, disjoint list of "tools". Nothing tells them these are different
kinds.

**Classification: ACCIDENTAL. Impact: MEDIUM** (mostly for anyone debugging
or building on the progress stream).

### 2.4 `dataset` and `pipeline` — a good divergence, already decided

`decisions/agents-skills-and-datasets.md` (2026-03-25) states it plainly:
*"Conceptually, a pipeline is a dataset materialization definition, even if
the implementation and namespace naming continue to use the term
`pipeline`"*, and lists "renaming all `pipeline` namespaces immediately" as
an explicit **Non-Goal**.

**Classification: CONSIDERED**, documented, reasoned.

And the public surface already did the right thing:
`server/docs/api/README.md` leads with *"the contract is dataset-first…
public callers do not read or mutate materialization pipelines"*, and the
console routes are `/console-api/datasets/{id}/pipelines/{id}` — dataset as
parent, pipeline as child materialization.

That two-level split is **more precise than the market leader**. AWS
[Bedrock Knowledge Bases](https://docs.aws.amazon.com/bedrock/latest/userguide/knowledge-base.html)
(fetched today) collapses the same concepts into one "knowledge base" object
with attached "data sources". Vertex AI's equivalent is Vertex AI Search.
(Azure AI Search's term — "index" — is **UNVERIFIED**; I did not fetch it.)

Residual risk is only that "dataset" in the wider ML world means training
data. One glossary line fixes it. **Do not change this. Document it.**

---

## 3. Area 2 — Protocol surface: do we use MCP's vocabulary, or only our part?

The issue's hypothesis was: *"if we expose everything as a tool because that
is what we built first, a user will find capabilities missing where they
expect them."* That is what happened, and the plan document proves it was
not a decision.

### 3.1 We are two protocol eras behind, and it is undocumented

> **SUPERSEDED 2026-08-21 by #146.** This section describes the transport as
> it was. Verified against the code on 2026-08-22: `mcp-protocol-version` is
> now `2026-07-28`, `supported-protocol-versions` contains only that, and
> `handle-initialize` no longer exists — the transport reads the client's
> requested version from the `MCP-Protocol-Version` header and the body
> `_meta`, and rejects a mismatch with `-32022` naming what it speaks. The
> section is kept because it is the finding that prompted the move, not
> because it still describes the code.

`server/src/digdir/mcp/transport.clj:21` hardcodes:

```clojure
(def ^:private mcp-protocol-version "2025-03-26")
```

and `handle-initialize` takes `[request-id _params]` — it never reads the
version the client asked for.

The current revision is **2026-07-28** (published 2026-07-28; locked as a
release candidate 2026-05-21). MCP revisions are date-named, so `2025-06-18`
and `2025-11-25` both predate our May 2026 implementation: we shipped two
revisions stale, not merely drifted.

The 2026-07-28 revision draws a hard line. Its own terminology:
**"Modern"** = per-request metadata (2026-07-28 and later); **"Legacy"** =
`initialize` handshake (2025-11-25 and earlier). Between eras:

- `initialize`/`initialized` and `Mcp-Session-Id` retired; `server/discover`
  is now a **MUST** for servers.
- Every result **MUST** carry `resultType` (`"complete"` or `"input_required"`).
- Every request **MUST** carry `_meta` fields
  `io.modelcontextprotocol/protocolVersion` and
  `io.modelcontextprotocol/clientCapabilities`.
- Streamable HTTP **REQUIRES** the headers `MCP-Protocol-Version`,
  `Mcp-Method`, and (for `tools/call`) `Mcp-Name`, with server-side
  header/body validation and error code `-32020`.
- Sampling / elicitation / roots are replaced by Multi Round-Trip Requests;
  Roots, Sampling and Logging are deprecated with a 12-month window.
- List results gain `ttlMs` and `cacheScope`.

**What this actually costs us.** The specification's own compatibility
matrix says **Modern client + Legacy server = "Fails."** Dual-era clients
still work — they attempt a modern request, inspect the `400` body, and fall
back to `initialize`. So the practical blast radius depends entirely on
whether the clients our users bring retain legacy fallback. All Tier-1 SDKs
supported the new specification on 2026-07-28; **whether specific clients
keep dual-era fallback is UNVERIFIED** and is the single most useful thing
to test before release.

There is a subtler hazard. A modern client that POSTs `tools/list` at us
will *succeed*: we ignore the `_meta` and the `MCP-Protocol-Version` header
rather than returning `UnsupportedProtocolVersionError` (`-32022`), and the
spec tells clients to treat an absent `resultType` as `complete`. So the
client gets an answer and never learns it is talking to a legacy server —
exactly the *"may even process an era-ambiguous method under legacy
semantics"* failure the matrix warns about. Silent wrong-era success is
worse than a clean rejection.

**Classification: ACCIDENTAL.** `plans/completed/mcp-server-plan.md` has an
explicit six-item Non-Goals section (OAuth, stdio, replacing the Playground,
branching, generic skill-graph tools, retrieval-only). A version policy is
not among them, and nothing anywhere records a decision to sit on
2025-03-26. **Impact: MEDIUM-HIGH, but mitigable by documentation alone.**

### 3.2 One primitive of three, and the other two were never considered

`handle-initialize` returns `{:capabilities {:tools {:listChanged false}}}`.
No `resources`, no `prompts`, no `completions`, no `logging`.

Per the 2026-07-28 specification, servers offer three features:
**Resources** ("context and data, for the user or the AI model to use"),
**Prompts** ("templated messages and workflows for users"), and **Tools**
("functions for the AI model to execute").

We have material for both of the two we do not expose:

- **Resources.** Retrieved chunks and their source documents. Today
  `tools/call` returns a *snapshot*: `structuredContent.chunks` capped at 20
  entries, `select-keys`-ed down to
  `chunk_id / doc_num / chunk_index / content_length / total_chunks / title / metadata`
  (`server/src/digdir/mcp/tools.clj:330`). Nothing is addressable. A user who
  wants to re-read a cited chunk without re-running the whole agent loop has
  no way to. The specification provides `resource_link` content precisely for
  this: *"A tool MAY return links to Resources, to provide additional context
  or data."* Separately, `/api/datasets` exists as a REST list that MCP
  clients cannot see at all.
- **Prompts.** Nothing. Prompts are explicitly **user-controlled** —
  *"typically… triggered through user-initiated commands in the user
  interface"*, with slash commands given as the worked example. Our agents
  carry instructions and a response policy; a client has no way to offer
  "Ask the Altinn docs assistant…" as a slash command.
- **`instructions`.** The `DiscoverResult` (and, in our era, `initialize`)
  carries an optional `instructions` field: *"natural-language guidance for
  LLMs on how to use this server effectively."* We send none. We have
  per-agent descriptions but no server-level orientation.

**Classification: ACCIDENTAL — and this is the cleanest instance of the
issue's hypothesis.** The plan document is thorough, argues its non-goals
carefully, and the word "resource" never appears in it in the primitive
sense. Prompts are never mentioned. These were not weighed and rejected;
they were not seen.

**Impact: MEDIUM-HIGH.** A newcomer arriving with the MCP mental model
opens our server, sees "tools only", and correctly concludes there is
nothing here but a question-answering function — which undersells a system
that has a document corpus, per-agent instructions, and a citation graph.

### 3.3 Structured output is returned but never declared

We emit `structuredContent` on every `tools/call` and declare no
`outputSchema` on any tool. The specification is explicit: *"If an output
schema is provided: Servers **MUST** provide structured results that conform
to this schema. Clients **SHOULD** validate structured results against this
schema."* It also says *"a tool that returns structured content SHOULD also
return the serialized JSON in a TextContent block"* — our text block is the
prose answer, not the JSON.

Net effect: a client cannot learn the shape of our chunks, queries, search
attribution, or clarification payload except by reading our prose docs. The
schema exists in our heads.

**Classification: ACCIDENTAL. Impact: MEDIUM.**

### 3.4 The error taxonomy is inverted — actionable errors go out the dead channel

The specification defines two channels deliberately:

- **Protocol errors** — *"unknown tool, malformed requests, server errors"* —
  returned as JSON-RPC errors; the worked example for an unknown tool is
  `-32602 "Unknown tool: invalid_tool_name"`. *"Clients MAY provide protocol
  errors to language models, though these are less likely to result in
  successful recovery."*
- **Tool execution errors** — *"API failures, input validation errors…,
  business logic errors"* — returned as `isError: true` inside the result.
  *"Clients SHOULD provide tool execution errors to language models to enable
  self-correction."*

`digdir.mcp.transport/run-tools-call` collapses both into one:

```clojure
(if error
  (internal-error request-id (:message error) {:code (:code error)})  ; -32603
  (jsonrpc-response request-id result))
```

Everything `invoke-tool` can fail with lands in `-32603 Internal error`:
`invalid_tool_name`, `agent_not_found`, `agent_disabled`,
`agent_not_authorized`, `skill_graph_not_allowed`,
`skill_graph_not_authorized`, `no_dataset_scope`, `missing_query`. Of those:

- `invalid_tool_name` and `agent_not_found` are the unknown-tool case, whose
  specified code is `-32602`;
- `missing_query` is input validation — the model could fix it on retry, so
  it belongs in `isError`;
- `no_dataset_scope` carries the single most useful message in the codebase
  (*"pass `tenant` and `dataset_config_key` in tool arguments, attach scopes
  to the API key, or set TENANT / DATASET_CONFIG_KEY env vars"*) and is
  delivered in the envelope the spec designates as least likely to produce
  recovery.

**Classification: ACCIDENTAL** (the two-channel model appears nowhere in the
plan). **Impact: HIGH** — this is a "new user succeeds faster" defect, not a
conformance box.

### 3.5 Tool names carry a `/`, and the promised uniqueness guard was never built

Actual tool name: `builtin/agent-rag-agent__agent-rag-graph-bundled` (48
characters). The specification says tool names **SHOULD** contain only
`A-Z a-z 0-9 _ - .` — `/` is outside that set.

Beyond conformance, three concrete consequences:

- The same string is the OpenAI `model` id, so the standard
  `GET /v1/models/{model}` route is unroutable for our ids. (We do not
  implement that route anyway — §4.2.)
- MCPO, which **our own E2E stack uses** (`plans/completed/mcp-server-plan.md`
  §6, `server/e2e/`), converts MCP tools into OpenAPI paths; a `/` inside a
  tool name is a path-segment hazard.
- If anything ever wraps our tools as OpenAI functions, the name is invalid:
  the OpenAI schema requires function names to *"be a-z, A-Z, 0-9, or contain
  underscores and dashes, with a maximum length of 64"* (verified against
  `openai/openai-openapi` `openapi.yaml`, fetched today). Model ids carry no
  such documented constraint — only function names do.

**Classification: MIXED.** The *separator* was considered — the plan reserves
`__` and states "agent ids must not contain it", and `mcp.md` documents it.
The *character set of the agent-id half* was not. And the plan promised a
guard that does not exist: *"we validate uniqueness at listing time and fall
back to the fully-qualified name with a normalized separator if needed"* —
`list-tools` performs no uniqueness check.

**Impact: LOW today, but this is the one item whose cost is a public
identifier.** It is cheap now and expensive after release.

Also unused and free: `title`, `annotations` (the specification lists
"optional properties describing tool behavior"; **the individual annotation
field names are UNVERIFIED — I did not fetch them**), `icons`. Today the
description is assembled as `agent-desc " — " graph-name ": " graph-desc`,
so a client's tool list shows a run-on string per tool with no way to tell
that `…__agent-rag-graph-bundled` and `…__agent-rag-graph-faithful` are two
*modes of the same agent*, except by parsing the `__`. `_meta.default` marks
the default, but `_meta` is server-defined metadata clients are told not to
interpret.

### 3.6 Multi-turn is accepted, documented, and undiscoverable

This is the most concrete defect in the document.

The advertised `inputSchema` is derived solely from
`digdir.skills.templates.core/agent-tool-input-schema`:

```clojure
[:map [:user-query [:string {:min 1}]]
      [:conversation-history {:optional true} [:vector :map]]
      [:model {:optional true} :string]
      [:temperature {:optional true} :double]
      [:claim {:optional true} :string]]
```

`invoke-tool` additionally reads **`tenant`, `dataset_config_key`,
`conversation_id`, and `overrides`**. All four are documented in prose in
`server/docs/api/endpoints/mcp.md`. **None of them is in the schema the
client is handed.** And `conversation_id` comes back only in
`result._meta.conversation_id` — not in `structuredContent`.

The 2026-07-28 specification's non-normative "Stateful Tools" guidance
describes exactly the pattern we chose, and exactly the two things we got
wrong:

> "Servers… should do so by returning an explicit handle from a creation
> tool and accepting that handle as an argument on subsequent calls… **The
> model is responsible for carrying `basket_id` forward**… the server's
> retention policy should be stated in the creation tool's description…
> **so the model can see it**."

We mint the handle and hide both ends of it from the model. Result: an
LLM-driven MCP client cannot hold a conversation with a digdir agent. Every
turn starts fresh unless a human hardcodes the id.

Two smaller things in the same object: the argument names mix kebab-case
(`user-query`, `conversation-history`) with snake_case (`dataset_config_key`,
`conversation_id`); and `invoke-tool` accepts `query` as an alias for
`user-query`, which the schema does not permit — a strict client validating
against `inputSchema` would reject the form the plan document's own design
section used.

**Classification: ACCIDENTAL. Impact: HIGH.**

### 3.7 Auth: the divergence is considered; the missing signpost is not

The specification is unambiguous: **"Authorization is OPTIONAL for MCP
implementations."** Static API keys are *not* non-conformant. The `MUST`s
around OAuth 2.1, RFC 9728 protected-resource metadata, and RFC 8707
resource indicators all sit *inside* that optional framework.

`plans/completed/mcp-server-plan.md` lists MCP-native OAuth as an explicit
Non-Goal with a reason: *"Keep API-key bearer auth behind the MCP transport.
OAuth can come later if we onboard customers who need it."*

**Classification: CONSIDERED.** No change needed for v0.1.

What was *not* considered, and costs one line: our 401 is

```clojure
(-> (res/response (json/generate-string {:error "Invalid or missing API key"}))
    (res/status 401))
```

with **no `WWW-Authenticate` header** (`server/src/digdir/api/routes/endpoints.clj:644`).
There is also no `/.well-known/` anything (repo-wide grep: zero hits). A
client probing our endpoint receives a bare 401 and cannot tell whether we
want OAuth, a bearer key, or a cookie. Emitting `WWW-Authenticate: Bearer`
— even with no OAuth metadata behind it — converts a dead end into a
signpost, and is the standard RFC 6750 behaviour every HTTP client already
understands.

**Classification of the gap: ACCIDENTAL. Impact: MEDIUM.**

### 3.8 Two small transport details

- **`GET /api/mcp` returns 404, not 405.** The route declares only `:post`,
  and `api-router` is built with a single default handler (`json-not-found`),
  so a method mismatch falls to `404 {"error":"API endpoint not found"}`. The
  2026-07-28 back-compatibility rules say a server receiving a GET on the MCP
  endpoint **SHOULD** answer `405 Method Not Allowed`; and dual-era clients
  key their fallback on `400`/`404`/`405` **plus** body inspection. Our 404
  with a non-JSON-RPC body is the exact signature that tells a client "fall
  back to the deprecated 2024-11-05 HTTP+SSE transport" — which we do not
  host, so the client then fails at its opening GET. **ACCIDENTAL, LOW.**
- **`notifications/initialized` returns 204.** The current specification
  requires `202 Accepted` for an accepted notification POST. Whether
  2025-03-26 said the same is **UNVERIFIED** — I did not fetch that revision.
  Our own conformance script asserts 204 as correct, so if it is wrong it is
  wrong in a place we would not notice.
  **[SUPERSEDED 2026-08-21 by #146: the server and the conformance script
  both moved to `202` together. Verified 2026-08-22 —
  `mcp_conformance_check.clj` check 5 now asserts `202`. See §9 item 3.]**

---

## 4. Area 3 — Interop coherence: one system or two?

### 4.1 The unit is shared, deliberately, and that part is right

Both surfaces derive from `mcp-tools/list-tools`, so the unit is identical:
one `(agent × skill-graph)` pair, and the OpenAI `model` id is byte-identical
to the MCP tool name. Commit `5f8ecd4` states the intent: *"IDs mirror MCP
tool names so a client reasons about both surfaces identically."*

**Classification: CONSIDERED and good. Keep.**

### 4.2 Everything downstream of the unit diverges

| | MCP `/api/mcp` | OpenAI `/v1` |
|---|---|---|
| Conversation | Server-owned; `conversation_id` handle; history loaded from Datahike | Client-owned; **no persistence at all** |
| Dataset scope | `tenant` / `dataset_config_key` accepted as arguments | **Never accepted** — `resolve-invocation` calls `(pick-dataset-scope agent ring-req {})` with empty args |
| Agent `:skill-params` | Resolved and applied (`invoke-tool`) | **Dropped** — calls the 2-arity `(build-rag-skill-params dataset-config {})`, whose docstring says it *"is equivalent to passing `{}` as the agent layer"* |
| `model` / `overrides` | Honoured | Ignored |
| Citations | `structuredContent.chunks` + `_meta` | Top-level `citations` (Perplexity shape) + `sources` (Open WebUI shape) |
| Progress | `notifications/progress`, per-paragraph token deltas | One content delta with the entire body |
| Errors | JSON-RPC `-32603` | OpenAI `{error:{message,type,code}}` |

Two rows are worse than cosmetic:

**The same agent behaves differently depending on which door you use.**
Commit `3710cff` ("Agent skill-params: schema + 3-layer merge +
Playground/MCP plumbing") wired per-agent tuning into the Playground and MCP
paths. `/v1` was not updated, so a `/v1` caller silently gets un-tuned
answers from an agent whose whole point is that tuning. **This is a bug, not
a naming issue.** Classification: **ACCIDENTAL**.

**The statefulness is inverted relative to both protocols' own models.**
MCP 2026-07-28 opens with *"The Model Context Protocol is a stateless
protocol"*; Chat Completions is stateless by design. We made MCP the stateful
surface and `/v1` the stateless one. Someone who learns either surface
predicts the other wrong, in both directions. (Our MCP statefulness is at
least *documented as a choice* — the plan says *"This means the MCP server is
not stateless. That is a real choice"* with reasons — and, happily, the
handle pattern it chose is the one the new spec now recommends. So the
divergence is **CONSIDERED**; only its asymmetry with `/v1` is not.)

### 4.3 OpenAI wire-format deviations

Verified against `openai/openai-openapi` `openapi.yaml`, fetched 2026-08-21.

| Deviation | Evidence | Impact |
|---|---|---|
| `usage` hardcoded to `{prompt_tokens 0, completion_tokens 0, total_tokens 0}` | `usage` is **not** in the response's `required` list — omitting it is honest; zeroing it makes every cost dashboard read zero | MEDIUM for anyone metering |
| `stream_options.include_usage` ignored | Spec: *"an additional chunk will be streamed before the `data: [DONE]` message"* with whole-request usage | LOW-MEDIUM |
| `finish_reason` always `"stop"` — including on `:error` and `:needs-clarification` | Enum is `stop\|length\|tool_calls\|content_filter\|function_call`, so no better value exists for clarification; but returning `stop` on a hard failure is a lie a client cannot detect | MEDIUM |
| Non-streaming `choices[]` omits `logprobs` | The schema marks `finish_reason`, `index`, `message`, **`logprobs`** as `required` (nullable) | LOW for lenient SDKs; breaks strictly-generated clients |
| Streaming `choices[]` omits `finish_reason` on intermediate chunks | Schema marks `delta`, **`finish_reason`**, `index` as `required` (`finish_reason` nullable) | same |
| `error` object omits `param` | `Error` schema requires all four of `type`, `message`, `param`, `code` | LOW-MEDIUM |
| `error.type` is **always** `"invalid_request_error"` | `openai-error`'s 3-arity is never called (grep: seven 2-arity call sites, zero 3-arity), so `internal_error`, `invoke_failed`, and `list_models_failed` — all HTTP 500s — are labelled as client-side request errors | **MEDIUM** — client retry logic keys on `type`; we tell it "don't retry" when the truth is "retrying may work" |
| `GET /v1/models/{model}` (`retrieveModel`) not implemented | Standard endpoint in the spec | LOW |

**Classification: ACCIDENTAL, with an honest origin.** Commit `5f8ecd4` is
candid that the target was Open WebUI's empty model dropdown, not the schema:
*"OWUI needed a separately-configured outer LLM… That's two LLM hops per turn
and 5 minutes of manual Settings clicks."* Solving a real onboarding problem
first is the right instinct. It just means the schema was never weighed.

---

## 5. Area 4 — What a newcomer expects to find and does not

### 5.1 The `/v1` surface is invisible to anyone reading the API docs

Repo-wide grep for `/v1/chat`, `/v1/models`, or `openai` across
`server/docs/` and `README.md` returns **nothing**. The surface is absent
from:

- `server/docs/api/README.md` — the Public API table lists `/api/mcp`,
  `/api/datasets`, `/api/conversations`, `/api/skills`, `/api/skill-graphs`;
- `server/docs/api/openapi.yaml` — paths are `/api/mcp`,
  `/api/conversations`, `/api/datasets`, `/console-api/*`;
- `server/docs/api/endpoints/` — five files, none for `/v1`.

Its only mention anywhere is `docs/system-overview.md:814`, an internal
engineering document.

Worse: `server/docs/api/authentication.md` contains **zero** occurrences of
"Bearer" — it documents only `X-API-Key`. `Authorization: Bearer` is the one
header an OpenAI client will send, and it is the header `wrap-api-key-auth`
was extended to accept for exactly that purpose.

So the surface built specifically to make onboarding one click is
undiscoverable, and the credential header it requires is undocumented.

**Classification: ACCIDENTAL. Impact: HIGH.** This is the cheapest fix in
the entire document.

### 5.2 Four error shapes, one documented

`server/docs/api/README.md` documents exactly one:

```json
{ "error": "Description of what went wrong" }
```

Reality:

1. `{"error": "..."}` — `/api/*` and the 401 path;
2. JSON-RPC `{jsonrpc, id, error:{code, message, data}}` — `/api/mcp`;
3. OpenAI `{error:{message, type, code}}` — `/v1`;
4. `{"error": {"code": "rate_limited", …}}` — the rate limiter (documented
   in `mcp.md`), an *object*-valued `error` on a non-JSON-RPC response, i.e.
   shape (1)'s key with shape (3)'s value.

Three shapes are defensible — each surface follows its own protocol's
convention, which is correct. The fourth is not, and none of it is written
down in one place.

**Classification: ACCIDENTAL. Impact: MEDIUM.**

### 5.3 No machine-readable discovery of anything

Repo-wide grep for `.well-known`: **zero hits**. The conventions a newcomer
arrives carrying, all fetched today:

- MCP: `/.well-known/oauth-protected-resource` (RFC 9728) — a `MUST` inside
  the optional authorization framework;
- A2A v1.0: `/.well-known/agent-card.json`;
- Agent Skills: `/.well-known/agent-skills/index.json`
  ([Cloudflare discovery RFC](https://github.com/cloudflare/agent-skills-discovery-rfc),
  `$schema` currently `https://schemas.agentskills.io/discovery/0.2.0/schema.json`).

We publish none of these. Nor is there any unauthenticated capability
endpoint: `tools/list`, `GET /v1/models`, and `/api/datasets` all require a
key, so a prospective user with no credentials can learn nothing about the
server from the server.

**This one is genuinely arguable.** An unauthenticated capability page leaks
tenant and agent structure. It is a decision to *make*, not a defect to fix.
Flagging it so that it gets made. **Impact: LOW-MEDIUM.**

---

## 6. Divergence register

**C** = considered (we know, here is why) · **A** = accidental (the
convention was not seen) · **M** = mixed.

| # | Divergence | Class | Evidence | Impact |
|---|---|---|---|---|
| F1 | `skill` / `skill graph` mean a DAG step and a DAG, not a `SKILL.md` bundle or an `AgentSkill` | **A** | ADR 2026-02-04 cites a source that already used the other meaning; no comparison recorded | MED (mis-prediction, not failure) |
| F2 | Fixed-graph agents are called "agents", not "workflows" | **A** | Not discussed in any ADR | LOW |
| F3 | "tool" means three different things across the public surface | **A** | Not discussed | MED |
| F4 | `pipeline` = child materialization of a `dataset` | **C** | ADR 2026-03-25, explicit non-goal | LOW — **good divergence** |
| F5 | MCP protocol version pinned at 2025-03-26 — **RESOLVED 2026-08-21 by #146; now 2026-07-28, modern-era only** | **A** | No version policy anywhere; six-item Non-Goals list omits it | MED-HIGH |
| F6 | Only `tools` capability; no `resources`, `prompts`, `instructions` | **A** | Word "resource" absent from the plan in the primitive sense | MED-HIGH |
| F7 | `structuredContent` returned, `outputSchema` never declared | **A** | Not discussed | MED |
| F8 | All tool failures become `-32603`; none use `isError` | **A** | Two-channel model absent from the plan | **HIGH** |
| F9 | Tool names contain `/`; promised uniqueness guard not implemented | **M** | Separator reasoned in the plan; charset not; guard promised, absent | LOW now, expensive later |
| F10 | `conversation_id` / `tenant` / `dataset_config_key` / `overrides` absent from `inputSchema` | **A** | Prose-only in `mcp.md` | **HIGH** |
| F11 | API-key auth instead of OAuth 2.1 | **C** | Explicit Non-Goal with reason; and auth is OPTIONAL per spec | none |
| F11b | 401 carries no `WWW-Authenticate` | **A** | Not discussed | MED |
| F12 | `GET /api/mcp` → 404 not 405 | **A** | Default-handler wiring | LOW |
| F13 | `/v1` drops agent `:skill-params` and ignores dataset-scope args | **A** | `3710cff` wired Playground + MCP only | **HIGH** (behavioural) |
| F14 | MCP stateful, `/v1` stateless — inverted vs both protocols | **M** | MCP statefulness reasoned in the plan; the asymmetry is not | MED |
| F15 | OpenAI schema deviations (zeroed `usage`, missing `param`/`logprobs`, always-`invalid_request_error`) | **A** | `5f8ecd4` targeted the OWUI dropdown | MED |
| F16 | `/v1` absent from every API document; `Authorization: Bearer` undocumented | **A** | grep: zero hits in `server/docs/` | **HIGH** |
| F17 | Four error shapes, one documented | **A** | `server/docs/api/README.md` | MED |
| F18 | No `.well-known` discovery, no unauthenticated capability view | **A** | grep: zero hits | LOW-MED (and arguable) |
| F19 | Streaming `/v1` reports a failed agent run as `200` with `finish_reason: "error"`, a value outside OpenAI's enum | **C** | Decision below; measured against the stock `openai` Python SDK 1.109.1 | LOW (clients branch on a value they can see) |

### 6.1 F19 — how a failed stream reports itself (decided 2026-08-21)

**The problem.** When the agent's upstream LLM call failed, the streaming
`/v1` path returned `200` with `finish_reason: "stop"` and the error text as
assistant *content*. A client could not tell that from a real answer, and
neither could an eval harness, a cost tracker or a retry policy — all three
key off status and `finish_reason`, and both said success. This is the
sibling of F15: that was a fabricated *measurement*, this was a fabricated
*outcome*, and the outcome is the more dangerous because the content is prose,
so it renders as the model saying something odd rather than as an outage.

**Why the blocking and streaming paths differ.** The blocking path already
answers `500` with an OpenAI error object and is unchanged. The streaming path
cannot: the `200` and its headers are on the wire before the agent runs, so by
the time the failure is known the status is committed. Returning a non-2xx
would mean buffering the entire agent run before responding, which defeats
streaming.

**The options, and what was measured.** With the status fixed at 200, the
choice was between `finish_reason: "error"` — outside OpenAI's closed enum of
`stop` / `length` / `tool_calls` / `content_filter` / `function_call` — and
keeping `"stop"` alongside a vendor extension. The objection to the first was
that it might break the strict-SDK parsing F15 was fixed to achieve. That was
tested rather than argued, against the stock `openai` Python SDK 1.109.1
pointed at a local server:

| response | stock SDK 1.109.1 |
|---|---|
| `finish_reason: "error"` | parses; value arrives verbatim; a client can branch on it |
| `finish_reason: "stop"` + vendor extension | parses; every stock client still believes the call succeeded |
| non-2xx error object | raises `InternalServerError` (blocking path already does this) |

The objection did not survive the measurement: openai-python parses
leniently, so the out-of-enum value costs nothing and buys detectability
without requiring the client to know anything digdir-specific.

**Decision.** Streaming keeps `200` and the error text as content — chat UIs
that handle non-2xx poorly still render something — but the terminating chunk
reports `finish_reason: "error"`. The same applies to a mid-stream exception.
No vendor extension was added: nothing has asked for structured error detail
on this surface, and adding it unrequested would be speculative surface.

**What would change this.** A client that rejects the out-of-enum value, or a
concrete need for structured error detail mid-stream, would move this to a
vendor extension alongside a non-`stop` reason.

---

## 7. Recommendation on shape

The test is whether a new user succeeds faster. Sorted by that, not by
conformance.

### Tier A — change before public release

Each of these is here because a newcomer currently **fails**, not because we
would gain a conformance claim.

**A1. Publish the conversation handle. (F10)**
Add `conversation_id`, `tenant`, `dataset_config_key`, and `overrides` to
the advertised `inputSchema`; return `conversation_id` in
`structuredContent` as well as `_meta`; state the retention behaviour in the
tool description. Without this, multi-turn does not work for any LLM-driven
client. Small, self-contained change.

**A2. Split the error channel. (F8)**
Unknown tool / unknown agent → `-32602`. Argument, scope, and authorization
failures → `isError: true` with the actionable text intact. Reserve `-32603`
for genuine internals. Our best error message is currently delivered in the
worst envelope.

**A3. Document `/v1`. (F16)**
Add it to `server/docs/api/README.md`, to `openapi.yaml`, and as
`server/docs/api/endpoints/openai-compat.md`; add `Authorization: Bearer` to
`authentication.md`. Cheapest fix in this document by an order of magnitude.

**A4. Make the two doors behave the same. (F13)**
Apply the agent's `:skill-params` on the `/v1` path (use the 3-arity
`build-rag-skill-params`), and accept dataset scope from the request. Today
the same agent gives materially different answers depending on which surface
you call. This is a bug.

**A5. Make the OpenAI shape honest. (F15)**
Emit real `usage` or omit the key — do not report zeros. Include
`param: null`. Use a server-side `error.type` for 5xx. Include the
schema-required `logprobs` / `finish_reason` keys with `null` values.

**A6. `WWW-Authenticate: Bearer` on 401. (F11b)** One line.

### Tier B — decide before release; cheap either way, expensive after

**B1. Fix the tool-name character set, and build the promised guard. (F9)**
Get `/` out of the identifier. This is the only item in the document whose
cost is a *public identifier* — the same string is the MCP tool name, the
OpenAI model id, and (via MCPO) an OpenAPI path segment. It is nearly free
now and a migration later. Do it in the same edit as the `skill-graph`
rename in §7.1.

**B2. Add the free description surface. (F6, F7, F18)**
`title` and `outputSchema` on tools; `instructions` on `initialize`;
`resource_link` content blocks for cited source documents so the affordance
exists even before we declare a `resources` capability. All pure additions.

**B3. Write down the MCP version policy. (F5)**
Staying legacy-era for v0.1 is defensible — dual-era clients work. Sitting
on it *silently* is not; today the pin reads as an oversight rather than a
decision. State the current revision, the target, and the trigger to move.
**And test it**: run at least one current MCP client against `/api/mcp`
before release, because the one thing I could not verify is whether the
clients our users bring still carry legacy fallback.

### Tier C — document, do not change

**C1. `dataset` / `pipeline`.** Already decided (ADR 2026-03-25) and already
correct in the public docs. Add one glossary line: our `dataset` is what AWS
Bedrock calls a *knowledge base*; our `pipeline` is the materialization
definition beneath it.

**C2. `agent`.** Keep. It matches A2A's usage. Signal
workflow-vs-agent through tool `title`/`annotations`, not a rename.

**C3. Resources and prompts.** v0.1 can ship without them — but say so *as a
decision* in the API docs, so the absence reads as a boundary rather than a
gap. That is the whole difference between considered and accidental.

**C4. API-key auth.** Already a documented non-goal. Nothing to do beyond A6.

### 7.1 The `skill` question specifically

**Recommendation: do not rename `skill` / `skill graph` for v0.1. Do get the
word off the public wire. Do write the glossary.**

Reasoning:

- A full rename is a broad refactor across persistence, routes, UI, config
  keys, API-key grant fields (`:api-key/skill-graphs`), stored conversations
  (`:skill-graph-id`), MCP tool names, and OpenAI model ids. The ADR that
  introduced this vocabulary already priced an equivalent refactor as *"a
  broad refactor across persistence, routes, UI, and tests."* That is not a
  pre-release change.
- The cost of the collision is mis-prediction, not failure. Weighed against
  F8, F10, F13, and F16 — where things actually do not work — it loses.
- The forward-compatibility trap is specific and narrow: if SEP-2640 ships
  and we adopt it, `skills/*` on our MCP endpoint would mean `SKILL.md`
  bundles. That argues for keeping the word **off the MCP wire**, not for
  renaming the internals.

Concretely:

- **(a)** Keep `skill` and `skill graph` internally. They are a coherent,
  well-documented model and the ADRs behind them are sound.
- **(b)** Remove the string `skill-graph` from the MCP tool name and the
  `/v1` model id. To a caller, the second axis is not a graph — it is a
  **mode** (or profile) of the agent: `…__bundled`, `…__faithful`. This
  reads better, sheds the collision at the only place a stranger meets it,
  and fixes B1's `/` problem in the same edit. One public-identifier change,
  during the only window where that is free.
- **(c)** Add a glossary to `server/docs/api/README.md` that says it
  plainly: *in this API a "skill" is a step in a graph — not an Anthropic
  Agent Skill (a `SKILL.md` bundle with progressive disclosure) and not an
  A2A `AgentSkill` (a capability advertisement on an agent card).* Cite both,
  with dates. One paragraph.
- **(d)** Record (a)–(c) as an ADR, so the next person does not re-litigate
  it and so the divergence is permanently **considered** rather than
  accidental.

### 7.2 What this analysis does *not* recommend

> **REVERSED 2026-08-21 by the PI, implemented in #146.** The bullet below
> advised against adopting MCP 2026-07-28 before release. We adopted it. The
> reversal is recorded here rather than silently overtaken, because a
> recommendation that quietly stops being true is worse than one that was
> wrong.
>
> **Why the analysis's reasoning did not survive contact.** It weighed the cost
> of *moving* and found it high. It did not weigh the cost of *staying*, which
> turned out to be higher: a legacy-era server has to implement the dual-era
> fallback trigger correctly, and #139 established that we did not — we
> answered an unknown modern method with HTTP `200` where the fallback rule
> needs a `4xx`. Two real clients worked only because they were more lenient
> than the spec required. A modern-only server has no fallback path to get
> wrong, so the move **deletes** that bug class rather than adding a fix we
> would have to keep correct. The analysis was also right that we were behind
> on revision but not on design — the conversation-handle pattern we had
> already chosen is the one 2026-07-28 recommends — which made the move
> cheaper than it looked from here.
>
> The trade is real and is documented in
> `server/docs/api/endpoints/mcp.md`: legacy-only clients no longer work.
> Measured, not assumed — MCP Inspector 2.3.0 fails, Claude Code 2.1.238
> works.

- No rewrite. No rename of `pipeline`. No OAuth for v0.1. ~~No adoption of
  MCP 2026-07-28 before release.~~ (reversed — see above)
- No standards compliance for its own sake. F11 (API keys) and F4
  (`pipeline`) are divergences we should keep and advertise. F4 in particular
  is a place where our model is *better* than the incumbents' and should be
  sold as such rather than apologised for.

---

## 8. What I could not verify

Marked UNVERIFIED in the text; collected here so nothing is lost:

1. ~~**Whether the MCP clients our users will actually bring retain
   dual-era fallback.**~~ **RESOLVED — twice, in both directions.**
   - #124 measured it against the legacy server: Claude Code 2.1.238 is
     dual-era (probes modern, falls back to `initialize`); MCP Inspector 2.3.0
     is legacy-first. Both worked — but Claude Code only because it keys
     fallback on the JSON-RPC error body rather than the HTTP status the spec
     names, i.e. by leniency rather than by our conformance (#139).
   - #146 then moved us to modern-only and re-measured: Claude Code 2.1.238
     works and never attempts `initialize`; **MCP Inspector 2.3.0 now fails**,
     having no fall-forward. The question inverted along with the server.

   The instrument both times was a logging reverse proxy in front of a booted
   production uberjar, reading actual bytes. Worth noting *why* that was
   necessary: reading the spec was not sufficient in either direction.
2. **The individual field names inside MCP tool `annotations`.** The 2026-07-28
   tools page confirms `annotations` exists as "optional properties describing
   tool behavior"; I did not fetch the field list.
3. ~~**Whether MCP 2025-03-26 required `202` or `204` for an accepted
   notification POST.**~~ **RESOLVED and now moot.** 2026-07-28 requires
   `202 Accepted`; #146 changed the server and the conformance script to `202`
   together, so the discrepancy this item flagged is closed rather than
   answered. (This revision defines no client-to-server notifications over
   Streamable HTTP at all, so the path is politeness for a client that sends
   one anyway.)
4. **Azure AI Search's term for a RAG corpus.** Asserted as "index" from
   prior knowledge in an early draft; removed. Not fetched, not claimed.
5. **OpenAI model-id character constraints.** The 64-char alnum/underscore/
   dash rule is documented for *function* names; I found no equivalent
   documented constraint for `model` ids.

---

## 9. Sources

All fetched **2026-08-21** unless a different date is given for the source's
own publication.

**Model Context Protocol**
- [Specification 2026-07-28 — overview](https://modelcontextprotocol.io/specification/2026-07-28) (published 2026-07-28)
- [The 2026-07-28 Specification (blog)](https://blog.modelcontextprotocol.io/posts/2026-07-28/) — RC locked 2026-05-21, final 2026-07-28
- [Base protocol / `_meta` / error codes](https://modelcontextprotocol.io/specification/2026-07-28/basic)
- [Versioning and compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning) — era model, compatibility matrix
- [Streamable HTTP transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) — required headers, back-compat rules
- [Server features: Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) — naming, `outputSchema`, error channels, Stateful Tools
- [Server features: Prompts](https://modelcontextprotocol.io/specification/2026-07-28/server/prompts)
- [`server/discover`](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
- [Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) — "Authorization is OPTIONAL"
- [Skills over MCP working-group charter](https://modelcontextprotocol.io/community/working-groups/skills-over-mcp) — IG 2026-02-01, WG 2026-04-16
- [SEP-2640 — Skills Extension](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640) — opened 2026-04-23, open as of 2026-08-20

**Agent Skills**
- [Agent Skills specification](https://agentskills.io/specification)
- [Anthropic — Agent Skills overview](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
- [Cloudflare — Agent Skills discovery RFC](https://github.com/cloudflare/agent-skills-discovery-rfc)
- [David Cramer — "MCP, Skills, and Agents"](https://cra.mr/mcp-skills-and-agents/) (published 2026-01-20; the reference cited by our own ADR)

**A2A**
- [Agent2Agent Protocol specification, v1.0](https://a2a-protocol.org/latest/specification/)

**OpenAI**
- [`openai/openai-openapi` `openapi.yaml`](https://raw.githubusercontent.com/openai/openai-openapi/master/openapi.yaml) — `CreateChatCompletionResponse`, `CreateChatCompletionStreamResponse`, `CompletionUsage`, `ChatCompletionStreamOptions`, `Error`, `Model`, `FunctionObject`, `/models/{model}`

**Other**
- [AWS — Amazon Bedrock Knowledge Bases](https://docs.aws.amazon.com/bedrock/latest/userguide/knowledge-base.html)
- [Anthropic — Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) (published 2024-12-19)

**Internal (this repository)**
- `decisions/skill-based-agentic-rag.md` (2026-02-04)
- `decisions/agents-skills-and-datasets.md` (2026-03-25)
- `plans/completed/mcp-server-plan.md`
- `server/scripts/conformance-finding-2026-05-21.md`
- `server/src/digdir/mcp/{transport,tools}.clj`
- `server/src/digdir/api/routes/endpoints/openai_compat.clj`
- `server/src/digdir/api/routes/endpoints.clj`
- `server/docs/api/{README.md,authentication.md,openapi.yaml,endpoints/mcp.md}`
- Commits `d066e4f`, `84b6d67`, `c43f359`, `5f8ecd4`, `7f60d38`, `f78b4f6`, `c5b8b60`, `3710cff`
