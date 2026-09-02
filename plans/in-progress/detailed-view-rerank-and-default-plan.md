# Plan — Detailed view: rerank, Classic parity, default toggle

## Goals

1. Surface the new reranking diagnostics (ColBERT rerank-score, rerank timings, rerank-enabled / fallback / error) in the new Detailed view's Retrieval search drill-down.
2. Bring back the Classic Evidence details the new view is missing: `chars` (content_length), read/metadata state, and the retrieval-boost "why" explanation per hit.
3. Reorder the `ViewModeToggle` so `Detailed` is the first button and the default mode for new conversations.

## Background (from investigation)

- **Rerank now lives in the retrieval skill.** `apply-colbert-rerank` in `server/src/digdir/skills/builtin/retrieval.clj:346` attaches `:rerank-score` + `:rerank-rank` to each chunk and returns `{:chunks :rerank-ms :rerank-candidate-count :rerank-error}`. The attribution carried on search results includes `:rerank-enabled?`, `:rerank-ms`, `:rerank-error`.
- **Search result string format has changed** (`format-search-metadata-results` in `server/src/digdir/skills/builtin/agent/tools.clj:410`):
  - Dropped fields: `hits=`, `via=`, `rank=`, `content_length=` — **no longer in the LLM-facing string**.
  - `score=` now rounded to 2 decimals (was 3).
  - New field per hit: `rerank=X.XX`.
  - New trailing line: either `Ranking: ColBERT semantic rerank (Nms). …` or `ColBERT rerank failed (…). …` or `Showing top N by rank; X lower-ranked chunks omitted.`
  - Capped at `display-limit` (default 20) rows.
- **Richer per-chunk data is available in diagnostics**: `diagnostics/retrieved-evidence-entries` at `diagnostics.cljc:847` returns structured `:merged-results` with `:content_length`, `:search-types`, `:read?`, `:retrieval-boosts`, doc title/url. Classic `RetrievedEvidenceTable` at `results.cljc:359` uses this. This data is only available post-run (not in the live stream-view).
- **My current `parse-search-summary` in `live_next.cljc`** extracts `content-length`, `hits`, `rank-bm25`, `via` — all now gone from the string. My `SearchHitRow` references those fields.
- **Toggle order**: `ViewModeToggle` in `server/src/digdir/playground/ui/observability/live.cljc:103` renders `Focused | Detailed | Classic`. Default view-mode comes from `server/src/digdir/playground/ui/common.cljc:197` (`:view-mode :focused`) and per-conversation initializer at `ui.cljc:1581,1739`.

## Proposed changes

### A. Update the search parser + view-model for the new format

**`live_next.cljc`**
- Update `parse-search-hit-line` to reflect the new format:
  - Keep: `chunk-id`, `doc-num`, `chunk-index`, `title`, `url`, `headers-raw`, `score`.
  - Drop: `content-length`, `hits`, `rank-bm25`, `via`.
  - Add: `rerank-score` (parses `rerank=X.XX`).
- Extend `parse-search-summary` to also parse trailing notes:
  - Rerank status (`:rerank-ok <ms>` / `:rerank-failed <msg>` / `:rerank-disabled`).
  - Omitted-count note (e.g. "N lower-ranked chunks omitted" → `:omitted-count`).
  - Auto-filter note (field + values) → `:auto-filter {:field :values}`.
  - Return `{:headline :hits :rerank :omitted-count :auto-filter}`.

### B. Enrich post-run search events with `:merged-results`

**`live_next.cljc` — `diagnostics->view-model`**
- Pull `:merged-results` from diagnostics once.
- Build an index `{chunk-id → merged-chunk}` with `:content_length`, `:search-types`, `:read?`, `:retrieval-boosts`, doc-ref title/url.
- In `tool-call->event`, when `kind=:search`, join each parsed hit by `chunk-id` against the index and attach fallback fields (`:content-length`, `:search-type-labels`, `:read?`, `:retrieval-labels`) to the hit.
- Live path falls back to parser-only data: chars/state/why will render as `-`.

### C. Redesign `SearchHitRow` to match Classic + surface rerank

New row layout (keeps existing progressive-disclosure style):

```
N. Source title                                              [rerank X.XX] [score X.XX]
   chunk M/Total · NNNN chars · doc=…               [read|metadata only] · content·phrase
   headers: {"Header 2" "…"}                                                 why: org match, …
```

- `rerank-score` is the primary visible score when present; `retrieval-prior` shows secondary.
- `state` pill: green "read" when `:read?`, amber "metadata only" otherwise.
- `search-type-labels` rendered as existing-style pills (content/phrase/metadata).
- `retrieval-labels` ("why") shown in a small muted row.
- Chars right-aligned.
- Click still fires `on-select-chunk` (unchanged).

### D. Rerank summary in `SearchEventDetail`

Add a status line above the hit list:
- When rerank-enabled & ok: `ColBERT rerank · Nms` (green pill).
- When rerank-failed: `ColBERT rerank failed (reason) — original ranking preserved` (red pill).
- When omitted-count > 0: subtle note `Showing top X, M omitted`.
- Auto-filter applied: small note `Auto-filtered by {field}: {values}`.

### E. Make `Detailed` the first + default option

- **Reorder buttons** in `ViewModeToggle` (`live.cljc:103`) → `Detailed | Focused | Classic`. Selected-state colors stay the same.
- **Change default** in `common.cljc:197` (`:view-mode :detailed`) and in any "new conversation" initializers (`ui.cljc:1581, 1739`) where `:focused` is hardcoded.
- **Fallback dispatch**: in `ui.cljc` live dispatch (line ~1155) and post-run dispatch (line ~436), make the default case render the Detailed components instead of Focused so any unknown/unset value lands on the new view. `:focused` remains explicitly selectable.

### F. Tests / verification

- Lint changed files (`bb lint`).
- Reload namespaces to catch macroexpansion errors.
- Run `digdir.playground.chat-session-integration-test` (touched by diagnostics path).
- **Not** browser-verified by me — user should smoke-test:
  - Run a query; Detailed is now the selected mode.
  - Expand a Retrieval search event — see rerank scores per hit, rerank timing pill, chars/state/why columns, auto-filter note if applicable.
  - Switch to Classic for comparison; old view unchanged.
  - Trigger a rerank failure (e.g. point to unreachable ColBERT) — see the red rerank-failed note.

## Out of scope

- Surfacing rerank data to the LIVE search drill-down beyond what the LLM-facing string exposes. Live will still show `-` for chars/state/why since `:merged-results` isn't on the stream-view today. Can be threaded through later if needed.
- Changing the actual `retrieval` or `tools.clj` server code.
- Any changes to Classic mode (`live.cljc` internals).
- Redoing Chunk read / Answer synthesis drill-downs.

## File touch-list

- `server/src/digdir/playground/ui/observability/live_next.cljc` — parser, view-model, SearchHitRow, SearchEventDetail status line.
- `server/src/digdir/playground/ui/observability/live.cljc` — reorder toggle buttons.
- `server/src/digdir/playground/ui/common.cljc` — default `:view-mode`.
- `server/src/digdir/playground/ui.cljc` — dispatch default branch, any hardcoded `:focused` initializer.
