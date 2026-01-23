# Document Pipeline Architecture

This document describes the architecture of the document processing pipelines in `digdir.docs.*`.

## Overview

The pipeline system processes documents from various sources (websites, folders, Episerver) into a format suitable for search and retrieval. Each pipeline follows the same general flow:

```
Source Data → Extract → Normalize → Chunk → Search Phrases → Store
```

## Shared Infrastructure

All pipelines share common infrastructure located in `digdir.docs.pipeline.*`:

### Core (`pipeline/core.clj`)
Shared utility functions:
- `sha256-short-hash` - Generates 12-character hashes for IDs and cache keys
- `=>` - Left-to-right function composition
- `exponential-backoff` - Generates retry delays with jitter
- `worth-retrying?` - Determines if an error is transient
- `say` - Emits user-facing progress messages
- `run-task-async` - Runs Missionary tasks asynchronously

### Telemetry (`pipeline/telemetry.clj`)
Centralized telemetry and job state:
- `!transient-telemetry-aggregate` - Accumulates signal counts
- `!signal-window` - Sliding window of recent signals
- `!job-canceller` - Cancel function for running jobs
- `start-job!` / `stop-job!` - Job lifecycle management
- `get-signal-counts` / `get-recent-signals` - Query telemetry

### Storage (`pipeline/storage.clj`)
TypeSense operations:
- `coll-ids` - Generates collection names with config-based hashing
- `create-collection!` / `create-collections!` - Collection management
- `document-inserted?` - Check document existence
- `upsert-document!` / `upsert-documents!` - Document storage
- `store-chunks!` / `store-phrases!` - Chunk and phrase storage
- `store-complete-document!` - Full document storage workflow

### Search Phrases (`pipeline/search_phrases.clj`)
LLM-based search phrase generation:
- `create-chat-completion` - Azure OpenAI API wrapper
- `parse-phrases-response` - Extract phrases from LLM response
- `mk-distill-search-phrases-t` - Generate phrases for a chunk (with caching)
- `mk-distill-doc-search-phrases-t` - Generate phrases for all chunks in a document

### Orchestration (`pipeline/orchestration.clj`)
Missionary flow patterns:
- `mk-filter-entries-f` - Filter with limit/offset and deduplication
- `mk-prepare-documents-f` - Parallel document preparation with fault tolerance
- `mk-store-documents-f` - Parallel document storage
- `mk-materialize-t` - Complete pipeline materialization

### Protocol (`pipeline/protocol.clj`)
DocumentSource protocol definition:
- `DocumentSource` - Protocol all sources must implement
- `chunk-document` - Default chunking implementation
- Helper functions for building pipelines

## Document Sources

### Website (`website.clj`)
Imports markdown from sitemap URLs:
1. Fetches and parses sitemap XML
2. Downloads markdown files from URLs
3. Chunks content by headers
4. Generates search phrases
5. Stores in TypeSense

Configuration:
```clojure
{:sitemap/url "/nb/sitemap-markdown.xml"
 :base-url "http://localhost:1313"
 :parallelism/documents 3
 :parallelism/store 1
 :urls/offset 0
 :urls/limit 30000
 :chunks/strategy :header-based
 :chunks/minimum-length 333
 :chunks/maximum-length 256000
 :search-phrases/model "gpt-4o"
 :store/coll-prefix "website_"}
```

### Folder (`folder.clj`)
Imports markdown from local directories:
1. Recursively scans directory for .md files
2. Reads markdown content
3. Chunks content by headers
4. Generates search phrases
5. Stores in TypeSense

Configuration:
```clojure
{:folder/path "~/dev/digdir/docs/"
 :base-path "/full/path/to/docs/"
 :base-url "https://docs.digdir.no/docs/"
 :parallelism/documents 3
 :files/offset 0
 :files/limit 300000
 :store/coll-prefix "folder_"}
```

### Episerver (`episerver.clj`)
Imports content from Episerver XML export:
1. Parses XML export file
2. Extracts page content and converts HTML to markdown
3. Chunks content by headers
4. Generates search phrases
5. Stores in TypeSense

Configuration:
```clojure
{:xml/path "/path/to/export.xml"
 :language "no"
 :include-page-types #{"ArticlePage" "StandardPage"}
 :parallelism/documents 3
 :pages/offset 0
 :pages/limit 100000
 :store/coll-prefix "episerver_"}
```

## TypeSense Collections

Each source creates three collections:

### Documents Collection
```clojure
{:name "source_documents_<hash>"
 :fields [{:name "id" :type "string" :facet true}
          {:name "doc_num" :type "string" :facet true}
          {:name "title" :type "string" :index true}
          {:name "url" :type "string" :facet true}  ; or :path
          {:name "type" :type "string" :facet true}]}
```

### Chunks Collection
```clojure
{:name "source_chunks_<hash>"
 :fields [{:name "chunk_id" :type "string" :facet true}
          {:name "doc_num" :type "string" :reference "docs.doc_num"}
          {:name "chunk_index" :type "int32" :sort true}
          {:name "content_markdown" :type "string" :index true}
          {:name "url" :type "string" :facet true}]}  ; or :path
```

### Phrases Collection
```clojure
{:name "source_phrases_<hash>"
 :fields [{:name "chunk_id" :type "string" :facet true}
          {:name "doc_num" :type "string" :reference "docs.doc_num"}
          {:name "search_phrase" :type "string" :index true}
          {:name "phrase_vec" :type "float[]"
           :embed {:from ["search_phrase"]
                   :model_config {:model_name "ts/all-MiniLM-L12-v2"}}}]}
```

## Missionary Patterns

The pipelines use Missionary for async flow processing:

### Task (`m/sp`)
A single async computation that produces one value:
```clojure
(m/sp
  (let [data (m/? (fetch-data-t url))]
    (process data)))
```

### Flow (`m/ap`)
A stream of values with parallel processing:
```clojure
(m/ap
  (let [entry (m/?> parallelism flow)]
    (process entry)))
```

### Common Operations
- `m/?` - Await a task
- `m/?>` - Fork a flow with parallelism
- `m/seed` - Create flow from collection
- `m/eduction` - Apply transducers to flow
- `m/reduce` - Collect flow into single value
- `m/via m/blk` - Run blocking operation on thread pool

## Adding a New Document Source

To add a new source:

1. Create `digdir/docs/newsource.clj`
2. Implement the DocumentSource protocol:
   ```clojure
   (defrecord NewSource []
     proto/DocumentSource
     (source-name [_] :newsource)
     (fetch-entries [_ config] ...)
     (entry-to-doc [_ entry] ...)
     (fetch-content [_ config entry] ...)
     (docs-schema [_ coll-name] ...)
     (chunks-schema [_ coll-ids] ...)
     (phrases-schema [_ coll-ids] ...)
     (prepare-doc-for-storage [_ config doc] ...)
     (prepare-chunks-for-storage [_ config chunks] ...)
     (location-key [_] :url))  ; or :path
   ```

3. Or use the functional approach (like website.clj):
   - Define schema functions
   - Define prepare functions
   - Use orchestration helpers directly

## Running Pipelines

Each source has `-main` and `stop-job` functions:

```clojure
;; Start pipeline
(website/-main)

;; Stop running pipeline
(website/stop-job)
```

Monitor progress via telemetry:
```clojure
@telemetry/!transient-telemetry-aggregate
(telemetry/get-recent-signals 10)
(telemetry/job-running?)
```

## Configuration Keys Reference

| Key | Description |
|-----|-------------|
| `:parallelism/documents` | Concurrent document preparation |
| `:parallelism/store` | Concurrent storage operations |
| `:fault-tolerance/max-document-failures` | Failures before termination |
| `:chunks/strategy` | Chunking method (`:header-based`) |
| `:chunks/minimum-length` | Min chunk size in chars |
| `:chunks/maximum-length` | Max chunk size in chars |
| `:search-phrases/model` | Primary LLM model |
| `:search-phrases/fallback-model` | Fallback if primary fails |
| `:search-phrases/prompt` | Prompt template |
| `:store/coll-prefix` | TypeSense collection prefix |
| `:urls/limit` / `:files/limit` / `:pages/limit` | Max items to process |
| `:urls/offset` / `:files/offset` / `:pages/offset` | Items to skip |
