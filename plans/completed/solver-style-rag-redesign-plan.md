# Solver-Style RAG Redesign Plan

## Objective
Replace one-shot “retrieve then answer” behavior with an iterative, evidence-first workflow that mirrors how strong agents solve hard retrieval tasks: search broadly, read selectively, decide if evidence is sufficient, and loop only when needed.

## North-Star Behavior
The system should:
- answer from grounded evidence only,
- say “insufficient evidence” when needed,
- adapt retrieval effort to question difficulty,
- improve multi-hop accuracy without exploding token/latency cost.

## Proposed Architecture

### 1. Query Understanding Layer
Inputs: user query, conversation context, tenant constraints.

Outputs:
- `intent` (factoid, comparison, troubleshooting, policy, multi-hop),
- `subquestions` (if decomposition needed),
- `query_variants` (paraphrases + entity-expansion variants),
- `retrieval_budget` (initial pass limits).

Implementation notes:
- Use a small LLM or deterministic rules for decomposition first.
- Keep decomposition bounded (e.g., max 4 subquestions).
- Preserve provenance for each variant to support debugging.

### 2. Phase A: Broad Search (High Recall) — via `search` tool
The agent calls the `search` tool with query variants. Internally this runs 3-strategy hybrid retrieval against Typesense:
- **Phrase similarity** — BM25 + vector search on LLM-generated search phrases (`phrase_vec`, 384-dim cosine),
- **Metadata match** — BM25 on header hierarchy (`metadata` field),
- **Content match** — BM25 on full chunk markdown (`content_markdown`),
- **Auto-filters** — organization and year detection applied as Typesense `filter_by` clauses against the `$docs_collection` reference.

The agent receives back a **metadata-only** result set: `chunk_id`, `doc_num`, `chunk_index`, `total_chunks`, `content_length`, header metadata, document title/URL, and composite search scores. No full content is returned.

Candidate policy:
- Collect top `N` from each channel (default 30 per query per channel).
- Deduplicate by `chunk_id` and combine ranks across channels (`merge-chunk-search-results`).
- Apply query-aware boosting (title overlap, year match, org match) and per-document diversity cap.
- Return a broad candidate pool (e.g., 60–200 chunk metadata records).

### 3. Phase B: Focused Read (High Precision) — via `read` tool
The agent examines search results and decides which chunks to read. It uses `chunk_index`, `total_chunks`, `content_length`, and header metadata to make informed decisions:

- **Targeted reads:** Fetch specific high-scoring chunks by `chunk_ids`.
- **Context expansion:** When a chunk looks relevant but sits mid-document (e.g., chunk 5 of 12), use `read` with `doc_num` + `chunk_range` to fetch surrounding chunks (e.g., chunks 4–6) for complete context.
- **Document skimming:** For long documents with many hits, use `max_content_length` to skim chunk content before committing to full reads.

After reading, rerank with a stronger relevance model:
- cross-encoder reranker (preferred), or
- LLM grader with strict rubric if reranker unavailable.

Then extract evidence:
- extract short evidence spans with source IDs (`chunk_id` + `doc_url`),
- label per-span support type: `direct`, `partial`, `contextual`, `conflicting`.

### 4. Evidence Sufficiency Gate
Before synthesis, run an explicit decision step:
- Is the question fully answerable from current evidence?
- Are key entities/constraints unresolved?
- Are there contradictions across top evidence?

Decision output:
- `sufficient`: synthesize now,
- `insufficient`: trigger targeted re-search,
- `conflict`: re-search with disambiguation query.

### 5. Targeted Re-Search Loop
If `insufficient/conflict`, the agent loops using the same `search` and `read` tools:

**Search refinement strategies:**
- Narrower queries targeting specific gaps: "Need release date for product X" → `search({queries: ["product X release date", "product X versjon lansering"]})`.
- Filter adjustments: add/remove org or year filters based on what the first pass revealed.
- Different query formulations: if semantic search missed, try more lexical/exact queries.

**Read refinement strategies:**
- Expand chunk ranges: if evidence was in chunk 5, read chunks 3–7 for fuller context.
- Read chunks from documents that appeared in search but weren't read yet.
- Skim long documents with `max_content_length` to find the right section, then do a full read.

Loop constraints:
- max iterations (e.g., 2 additional passes),
- max token budget (track cumulative `content_length` of all read chunks),
- max retrieval latency budget.

### 6. Final Synthesis Layer
Generate answer from evidence table only:
- no unsupported claims,
- include citations per major claim,
- include confidence + known unknowns,
- include explicit uncertainty when evidence is weak.

Recommended response schema:
- `answer`
- `citations[]`
- `confidence` (high/medium/low)
- `known_unknowns[]`
- `follow_up_questions[]` (optional)

## Orchestration Graph (State Machine)
Use a small graph/state-machine orchestration instead of a linear chain.

Core states:
1. `understand_query`
2. `broad_retrieve`
3. `rerank_and_extract`
4. `judge_sufficiency`
5. `targeted_retrieve` (conditional loop)
6. `synthesize`
7. `finalize`

State payload to persist:
- query variants and subquestions,
- search results per pass: `chunk_id`, `doc_num`, `chunk_index`, `total_chunks`, `content_length`, metadata, scores,
- read history: which `chunk_ids` and `doc_num`/`chunk_range` pairs have been read (avoids re-reading),
- extracted evidence spans with source attribution (`chunk_id`, `doc_url`),
- cumulative token/character budget consumed (sum of `content_length` for all read chunks),
- insufficiency reasons,
- iteration count and budget consumption.

This persisted state is key for observability and deterministic debugging.

## Ranking and Scoring Strategy
Use a combined score to reduce brittle ranking:

`final_score = a*bm25 + b*dense + c*reranker + d*metadata_prior`

Guidelines:
- tune `a,b,c,d` by offline eval,
- upweight exact matches for IDs/numbers,
- penalize stale docs when freshness matters,
- diversify top results to avoid near-duplicate chunks.

## Adaptive Budgeting
Not all queries need multi-pass retrieval.

Simple policy:
- Easy single-hop questions: 1 pass, low candidate count.
- Ambiguous/multi-hop questions: higher candidate pool + loop allowance.
- High-stakes queries (compliance/financial/medical): stricter sufficiency threshold and lower hallucination tolerance.

Initial heuristic signals:
- number of entities,
- presence of comparative/multi-constraint language,
- past failure likelihood for similar queries.

## Failure Modes and Safeguards
- Hallucinated joins across unrelated chunks: require citation support for each claim.
- Dominance by one retrieval channel: enforce channel diversity in top candidates.
- Conflicting sources: detect and present conflict instead of flattening.
- Infinite loop risk: hard iteration and budget caps.
- Over-answering: explicit abstain path when insufficient evidence remains.

## Evaluation Plan

### Offline Evaluation Set
Build a benchmark split by type:
- single-hop factual,
- multi-hop compositional,
- comparison queries,
- temporal/freshness-sensitive,
- intentionally unanswerable questions.

For each item, store:
- gold answer,
- accepted evidence docs,
- required reasoning hops,
- abstain-expected flag.

### Metrics
Primary:
- grounded accuracy,
- citation precision/recall,
- abstain correctness,
- multi-hop success rate.

Secondary:
- p95 latency,
- token cost per answered query,
- average loop count,
- retrieval recall@K against gold evidence.

### Online Experimentation
Run A/B against current pipeline:
- control: current agentic RAG,
- treatment: solver-style iterative graph.

Guardrails:
- block rollout if latency/cost exceed agreed thresholds without clear quality gains.
- prioritize quality lift on hard-query cohorts over overall average only.

## Implementation Plan (Phased)

### Phase 0: Instrumentation First (1 week)
- Add trace IDs across retrieval, rerank, synthesis.
- Log candidate sets, rerank order, sufficiency decisions.
- Add basic dashboards for latency, loop count, abstain rate.

### Phase 1: Search/Read Tool Core (1–2 weeks)
- Add `content_length` (int32) field to chunks collection schema; compute at ingestion in `chunk-document`.
- Add `total_chunks` (int32) field to documents collection schema; update after chunk storage in `store-complete-document!`.
- Backfill both fields for existing collections.
- Implement `search` tool: wrap existing `run-3-strategy-search`, return metadata-only response (strip `content_markdown`, add `chunk_index`, `total_chunks`, `content_length`, `doc_title`, `doc_url`).
- Implement `read` tool: existing `retrieve-chunks-by-id` for chunk_id-based reads + new `retrieve-chunks-by-range` for `doc_num` + `chunk_index` range queries + `max_content_length` truncation.
- Wire both tools into single-pass agent loop with evidence-table-based synthesis and strict citation policy.

Exit criteria:
- improved grounded accuracy and citation precision offline,
- no severe latency regressions,
- agent demonstrably uses search → read → synthesize pattern.

### Phase 2: Sufficiency Gate + Targeted Loop (1–2 weeks)
- Introduce insufficiency classifier and focused re-search.
- Add hard caps for iterations/tokens.
- Add conflict detection path.

Exit criteria:
- measurable lift on multi-hop set,
- stable or improved abstain correctness.

### Phase 3: Adaptive Budget + Policy Tuning (1 week)
- Add dynamic budgets by query complexity.
- Tune channel weights and thresholds from experiment data.
- Harden fallback behavior for low-confidence cases.

## Concrete Defaults to Start
- Query variants: 3–6
- Broad candidate pool: 100 chunks
- Reranked shortlist: 12 chunks
- Max extra loops: 2
- Max total retrieved chunks across loops: 220
- Confidence threshold for synthesis: calibrated from eval (start conservative)

## Agent Tool Definitions — Mapped to Typesense

The solver agent interacts with the knowledge base through two primitive tools that mirror how a developer navigates a codebase with `Search` and `Read`. The separation is critical: **Search returns lightweight metadata (cheap to scan), Read returns full content (expensive in tokens).**

### Tool 1: `search` — Broad Discovery

**Purpose:** Find candidate chunks without paying the token cost of reading them.

**LLM-facing parameters:**
- `queries` (string[], required) — 1–6 search queries to run in parallel.
- `filter` (object, optional) — Explicit facet filters (orgs, years, doc type).

**What it does internally (invisible to agent):**
Runs the existing 3-strategy retrieval against Typesense:
1. **Phrase similarity** — queries `phrases` collection via hybrid BM25 + vector search on `search_phrase` / `phrase_vec` (384-dim, `ts/all-MiniLM-L12-v2`, cosine).
2. **Metadata match** — queries `chunks` collection on `metadata` field (header hierarchy text).
3. **Content match** — queries `chunks` collection on `content_markdown` (lexical BM25).

Results are merged with per-channel rank normalization (`merge-chunk-search-results`), query-aware boosting (title overlap, year match, org match), and per-document diversity capping.

**What it returns to the agent (per hit):**
```
{:chunk_id        "abc123"
 :doc_num         "doc-xyz"          ;; parent document ID
 :chunk_index     3                  ;; position within document (0-based)
 :total_chunks    12                 ;; total chunks in this document
 :content_length  1847               ;; character count of content_markdown
 :metadata        {"Header 1" "API Reference" "Header 2" "Authentication"}
 :doc_title       "Developer Guide"
 :doc_url         "/en/developer-guide"
 :search_types    #{:phrase :content} ;; which strategies matched
 :hit_count       2                  ;; how many strategies matched
 :rank            0.87}              ;; normalized composite score
```

**Key design choice:** Content is NOT included. The agent sees enough to decide *which* chunks to read and *where they sit* within their parent document. `chunk_index` and `total_chunks` let the agent reason about context window position (e.g., "chunk 3 of 12 — there's likely more context in chunks 2 and 4").

**Implementation changes needed:**
- Add `total_chunks` per document (count chunks with matching `doc_num` — can be cached or stored as a field on the document record).
- Add `content_length` (character count of `content_markdown` — compute at index time and store as a new int32 field on chunks, or compute on retrieval).
- Return `chunk_index` in search results (currently stored but not surfaced).
- Return `doc_title` and `doc_url` from the joined docs collection (already fetched via `$docs_collection(url,title)` reference).

### Tool 2: `read` — Selective Content Retrieval

**Purpose:** Retrieve full content of specific chunks, with support for reading adjacent chunks in the same document.

**LLM-facing parameters:**
- `chunk_ids` (string[], optional) — Specific chunks to read by ID.
- `doc_num` (string, optional) — Read chunks from a specific document.
- `chunk_range` (object, optional) — `{:from <int> :to <int>}` index range within a document (requires `doc_num`).
- `max_content_length` (int, optional) — Truncate each chunk's content to this many characters (default: no truncation). Useful when the agent wants to skim long chunks.

**Usage patterns the agent can express:**

```
;; Read specific chunks found by search
(read {:chunk_ids ["abc123" "def456"]})

;; Read a chunk and its neighbors (context expansion)
(read {:doc_num "doc-xyz" :chunk_range {:from 2 :to 4}})

;; Skim a whole document (first + last chunks, truncated)
(read {:doc_num "doc-xyz" :chunk_range {:from 0 :to 11} :max_content_length 500})
```

**What it returns to the agent (per chunk):**
```
{:chunk_id         "abc123"
 :doc_num          "doc-xyz"
 :chunk_index      3
 :total_chunks     12
 :content_markdown "Full markdown content of the chunk..."
 :content_length   1847
 :metadata         {"Header 1" "API Reference" "Header 2" "Authentication"}
 :doc_title        "Developer Guide"
 :doc_url          "/en/developer-guide"}
```

**What it does internally:**
- **By `chunk_ids`:** Uses existing `retrieve-chunks-by-id` (exact match on `chunk_id` via Typesense filter).
- **By `doc_num` + `chunk_range`:** New query — filters by `doc_num:=<value> && chunk_index:>=[from] && chunk_index:<=[to]`, sorted by `chunk_index ASC`. This leverages the existing `chunk_index` (int32, sorted) and `doc_num` (faceted, referenced) fields.
- **`max_content_length`:** Truncates `content_markdown` server-side before returning to the agent, with a `[truncated]` marker appended.

**Implementation changes needed:**
- New function `retrieve-chunks-by-range` that queries chunks collection with `doc_num` + `chunk_index` range filter.
- Server-side content truncation option.
- Expose `chunk_index`, `total_chunks`, and `content_length` in response.

### Why This Separation Matters

| Concern | Search | Read |
|---------|--------|------|
| Token cost | Low (~100 tokens/hit) | High (~500–2000 tokens/chunk) |
| Breadth | 60–200 candidates | 5–15 chunks per call |
| Agent decision | "Which chunks look relevant?" | "Does this evidence answer the question?" |
| Adjacent context | Shows position (`chunk_index` 3 of 12) | Can fetch neighbors (`chunk_range: 2–4`) |

This mirrors the proven pattern from code agents: `Glob`/`Grep` return file names and line numbers (cheap), `Read` returns file content (expensive). The agent learns to search broadly, then read selectively — exactly the behavior the solver loop needs.

### Schema Changes Required

**Chunks collection — new fields:**
- `content_length` (int32, not faceted, sorted) — character count of `content_markdown`. Computed at ingestion time in `chunk-document` / `header-based-chunks`.

**Documents collection — new fields:**
- `total_chunks` (int32, not faceted, sorted) — count of chunks belonging to this document. Updated after chunk storage.

These are additive changes — no migration of existing data beyond backfilling the two new fields.

## Suggested Stack Mapping

- Retriever: Typesense hybrid search (BM25 on `content_markdown`/`metadata` + cosine vector on `phrase_vec`)
- Embedding model: `ts/all-MiniLM-L12-v2` (384-dim, built into Typesense)
- Reranker: cross-encoder (or compact LLM relevance grader)
- Orchestrator: explicit state graph (nodes + conditional edges)
- Observability: per-node traces + stored evidence table
- Eval harness: offline dataset runner + online experiment hooks
- Agent tools: `search` (3-strategy, metadata-only response) + `read` (content by ID or chunk_index range)

## First Build Recommendation
If implementation bandwidth is limited, start with this minimum viable upgrade:
1. Add `content_length` and `total_chunks` fields to schema + backfill
2. Implement `search` tool (wrap existing 3-strategy retrieval, return metadata-only response)
3. Implement `read` tool (existing ID-based retrieval + new range-based retrieval via `chunk_index`)
4. Wire tools into agent loop with sufficiency gate and abstain path

This already eliminates many failure modes from one-shot RAG (over-reading irrelevant content, missing adjacent context, no iterative refinement) and creates the foundation for the full solver loop.
