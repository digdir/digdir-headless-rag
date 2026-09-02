# Typesense filesystem-style tools for Claude

## Goal

Give Claude two debug tools that make exploring a Typesense dataset feel
like exploring a filesystem with `grep` and `Read`. The aim is exploration
and context-window discipline, *not* production retrieval — the
multi-strategy retrieval skill stays where it is.

The design constraint: small, cheap probes by default. Big payload fields
must be opted into, never delivered by accident. That is what makes the
filesystem analogy work — `ls`/`grep -l`/`Read offset,limit` are cheap
because the tool shape forces you to commit before reading.

## Mental model

| Filesystem | Typesense                                              |
| ---------- | ------------------------------------------------------ |
| directory  | collection (one of: docs, chunks, phrases, enrichment) |
| file       | document row                                           |
| filename   | `id` / `chunk_id` / `doc_num`                          |
| `grep`     | full-text or vector search returning IDs + snippets    |
| `find`     | filter_by + sort_by                                    |
| `ls -l`    | facets                                                 |
| `Read`     | get-by-id with explicit `include_fields`               |

## Tool surface (option a)

Two HTTP endpoints + two `bb` wrappers, modelled on the existing
`/api/debug/chunk` + `bb chunk` pair in
`server/src/digdir/api/routes/endpoints/debug.clj` and `bb.edn`.

### 1. `GET /api/debug/typesense-search`

Discovery — always cheap by default.

**Query params**
- `tenant`, `dataset-config-key` (or `dataset-ref`) — resolves the
  dataset and computes collection names. Same shape as `bb chunk`.
- `role` — one of `docs`, `chunks`, `phrases`,
  `enrichment/hypothetical-questions`, `enrichment/verified-phrases`,
  `enrichment/fact-assertions`. Resolves to the actual hashed
  collection name via `digdir.pipeline.collections` +
  `digdir.skills.enrichment.collections`.
- `q` — query string. `*` lists. Vector search is implicit when the
  collection has an embedding field; otherwise BM25.
- `query-by` (optional) — comma-separated field list. Defaults per role
  (see "Default projections" below).
- `filter-by` (optional) — raw Typesense filter expression.
- `sort-by` (optional) — raw Typesense sort expression.
- `facet-by` (optional) — comma-separated facet fields. When set,
  the response includes facet counts.
- `limit` (optional, default 20, max 100) — `per_page`.
- `include-fields` (optional) — explicit projection. When omitted,
  the role-default *small* projection is used. To opt into big fields,
  the caller passes `include-fields=content_markdown` (or `*`).
- `highlight` (optional, default true) — return `highlights` snippets
  in the response.

**Response shape (EDN)**
```clojure
{:found-count 42
 :hits [{:chunk_id "..." :doc_num "..." :title "..." :snippet "..."} ...]
 :facets {"orgs_long" [{:value "..." :count 12} ...]}   ;; only when facet-by set
 :collection "my_pipeline_chunks_abc123"
 :role :chunks
 :query-by "content_markdown,title,metadata"}
```

### 2. `GET /api/debug/typesense-get`

Retrieval — explicit, slice-friendly.

**Query params**
- `tenant`, `dataset-config-key` (or `dataset-ref`), `role` — same as
  search.
- `ids` — comma-separated list of IDs. The ID field depends on role:
  `doc_num` for docs, `chunk_id` for chunks/phrases/enrichment.
- `range` (optional) — alternative to `ids`, only valid for `chunks`.
  Format: `<doc_num>:<start>-<end>` returns the requested
  `chunk_index` neighbourhood. Maps onto
  `digdir.rag.retrieval/retrieve-chunks-by-range`.
- `include-fields` (optional) — default small projection per role; opt
  in to `content_markdown` / vectors explicitly.

**Response shape (EDN)**
```clojure
{:found-count 3
 :documents [{:chunk_id "..." :doc_num "..." :content_markdown "..."} ...]
 :collection "..."
 :role :chunks}
```

## Default projections per role

The point of these defaults: even if I forget `include-fields`, the
response stays small.

| Role                                  | Default `include-fields`                                                            |
| ------------------------------------- | ----------------------------------------------------------------------------------- |
| `docs`                                | `id, doc_num, title, url, orgs_long, orgs_short, content_length, total_chunks`      |
| `chunks`                              | `id, chunk_id, doc_num, chunk_index, title, metadata, $docs(url,title)`             |
| `phrases`                             | `id, chunk_id, doc_num, search_phrase`                                              |
| `enrichment/hypothetical-questions`   | `id, chunk_id, doc_num, question, model, prompt_hash`                               |
| `enrichment/verified-phrases`         | `id, chunk_id, doc_num, phrase, model, prompt_hash`                                 |
| `enrichment/fact-assertions`          | `id, chunk_id, doc_num, subject, predicate, object, triple_text, model, prompt_hash` |

`content_markdown` and any `*_vec` field are always excluded by default
and require explicit opt-in.

Default `query-by` for search:
| Role           | `query-by`                              |
| -------------- | --------------------------------------- |
| `docs`         | `title, url, metadata`                  |
| `chunks`       | `content_markdown, title, metadata`     |
| `phrases`      | `search_phrase`                         |
| enrichment/H-Q | `question, question_vec`                |
| enrichment/V-P | `phrase, phrase_vec`                    |
| enrichment/F-A | `triple_text, triple_vec, subject, object` |

## `bb` wrapper tasks

Mirror `bb chunk` exactly: read args, call
`call-debug-endpoint-edn`, pretty-print.

```
bb ts-search <tenant> <env> <role> "<query>" [--filter-by ...] [--facet-by ...] [--limit N] [--include-fields f1,f2]
bb ts-get    <tenant> <env> <role> <id1,id2,...> [--include-fields f1,f2]
bb ts-get    <tenant> <env> chunks --range <doc_num>:<start>-<end>
```

Default pipeline is `kudos` (matches `bb chunk`).

Pretty-printer outputs one hit per line for `ts-search` (so transcripts
stay narrow) and a per-document EDN block for `ts-get`.

## Implementation map

- `server/src/digdir/api/routes/endpoints/debug.clj`
  - Add `debug-typesense-search-handler`
  - Add `debug-typesense-get-handler`
  - Extract a private helper `resolve-collection-for-role` that maps
    `role` → collection name using `pipeline-collections` and
    `enrichment-collections`.
- `server/src/digdir/api/routes/endpoints.clj`
  - Add `debug-typesense-search-query-parameters` schema
  - Add `debug-typesense-get-query-parameters` schema
  - Wire both into `debug-routes`
- `server/src/digdir/api/routes.clj`
  - Add thin re-exports if the existing pattern delegates through here.
- `bb.edn`
  - Add `ts-search` and `ts-get` tasks.
- `server/test/digdir/api/routes/endpoints/debug_test.clj`
  - New test ns covering both handlers.

## Tests

Use the same conventions as existing handler tests. Cover:
- Role resolution: `chunks` → `..._chunks_<hash>`, all four enrichment
  roles, unknown role → 400.
- Default projection strips `content_markdown` for `chunks`.
- Explicit `include-fields=content_markdown` puts it back.
- Missing required params (`tenant`, `dataset-config-key`, `role`) → 400.
- Unknown dataset → 404.
- `ts-get` with `range` returns ordered chunks.
- `ts-search` with `facet-by` returns facet counts in response.

## Out of scope

- Writes (no `typesense_upsert` / `typesense_delete`).
- Production retrieval orchestration — that stays in the retrieval skill.
- Schema introspection — Typesense already exposes `/collections/:name`,
  add only if needed.

## Option (b) — multi-strategy retrieval wrapper (implemented, disabled by default)

The third tool exists. It wraps the existing multi-strategy retrieval
skill so callers can hit phrase + metadata + content (+ optional
enrichments, + optional ColBERT rerank) in one call.

**Endpoint**: `GET /api/debug/typesense-retrieve`
**bb wrapper**: `bb ts-retrieve <tenant> <env> "<q1,q2,...>" [flags]`

**Gated on `RAG_TS_RETRIEVE_ENABLED` env var. Default OFF, returning 503.**

The default-off stance is deliberate. During the first sweeps we want
to isolate the contribution of the exploration primitives
(`ts-search`/`ts-get`). With the multi-strategy tool live alongside,
an agent or sweep harness could call it instead and contaminate the
measurement of what the primitives alone can do. Once the baseline
sweeps complete, set `RAG_TS_RETRIEVE_ENABLED=true` in the worktree's
`mise.local.toml` (a commented-out line is provided as a hint) and
re-run with the wrapper enabled to attribute the delta.

**Exposed parameters** (all defaults chosen so the tool gives "raw"
retrieval output rather than the production-tuned shape):
- `queries` (comma-separated, or single `q`) — required
- `limit` — per-strategy per-query cap (default 30)
- `retrieve-top-k` — merged-top-k cap (default 100)
- `max-per-document` — diversity cap (only forwarded if set)
- `metadata-only` — default `true` (strips `content_markdown`)
- `query-aware-boost` — default `true`
- `rerank-with-colbert` — default `false`
- `auto-filter` — default `false` *here* (skill default is true; we
  flip it off so org/year auto-extraction doesn't muddy isolation
  experiments)
- `enrichment-types` — comma-separated enrichment-type names; resolves
  to `enrichment-search-targets` automatically

**Response shape**:
```clojure
{:found-count N
 :chunks [...]            ; from retrieval skill
 :search-attribution {…}  ; phrase/metadata/content/merged counts + diversity stats
 :collections {:docs-collection ... :chunks-collection ... :phrases-collection ...}
 :enrichment-targets {...}        ; only when enrichment-types was set
 :queries [...]
 :dataset-config-key "..."
 :parameters {...}}               ; effective params after defaulting
```

**Skill errors** propagate as 500 with `:error-type` and `:error-data`
preserved from `skills/error-result`.

**Tests** (6 new):
- disabled by default → 503 with helpful pointer to the env var
- enabled → calls `execute-retrieval` with correctly shaped input/parameters/skill-params
- comma-separated `queries` splits to a vector
- `enrichment-types` → `enrichment-search-targets` map keyed by canonical keywords
- skill error result → 500 with message + type preserved
- missing `queries`/`q` → 400

## Open questions

1. **Auth**: the existing debug router uses `X-Debug-Api-Key`. Are these
   tools intended for the same dev/loopback context, or should they
   require a stronger token? Assume same context unless told otherwise.
2. **Roles vs. raw collection names**: should we also accept a raw
   `collection` query param as an escape hatch, for cases where I want
   to hit a collection that doesn't fit the docs/chunks/phrases/enrichment
   taxonomy? Tentatively yes, but documented as advanced use.
3. **Big-field opt-in syntax**: `include-fields=*` for "everything"
   matches Typesense convention. OK?
