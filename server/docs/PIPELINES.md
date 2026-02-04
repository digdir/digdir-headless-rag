# Pipeline System Documentation

## Overview

The Pipeline system unifies data loading and query-time configuration for RAG (Retrieval-Augmented Generation) operations. Pipelines replace the previous dual system of "Views" (data loading) and "Entities" (query-time config) with a single, unified configuration model.

## Key Concepts

### What is a Pipeline?

A Pipeline is a database-backed configuration that controls:

1. **Data Loading**: How documents are fetched, chunked, and indexed
2. **Query-Time Retrieval**: Which collections to search and how to rank results
3. **Generation**: Prompt templates and LLM settings for answer generation

### Benefits

- ✅ **Single Source of Truth**: One configuration for both loading and querying
- ✅ **User-Configurable**: Non-developers can manage pipelines via UI
- ✅ **Multi-Tenancy**: Full tenant/environment/pipeline scope support
- ✅ **Audit Trail**: All changes logged via config system
- ✅ **8-Level Inheritance**: Global → Tenant → Environment → Pipeline hierarchy
- ✅ **Version Control**: Config-based hashing ensures collection versioning

## Architecture

### Database Schema

Pipelines use the existing config system with properties under the `pipeline.*` namespace:

```clojure
;; UI Properties
pipeline.ui/name              - Display name
pipeline.ui/image             - Icon/image URL
pipeline.ui/description       - Description

;; Data Source
pipeline.source/type          - :kudos, :website, :folder, :episerver
pipeline.source/sitemap-url   - For website sources
pipeline.source/folder-path   - For folder sources
pipeline.source/api-endpoint  - For EPiServer sources
pipeline.source/use-preprod   - Use preprod API

;; Document Filtering
pipeline.documents/types      - Document types to include
pipeline.documents/limit      - Max documents to process
pipeline.documents/offset     - Documents to skip
pipeline.documents/transducer - Custom filter function

;; Chunking
pipeline.chunks/strategy              - :header-based, :semantic, :markdown
pipeline.chunks/minimum-length        - Min chunk size (default: 333)
pipeline.chunks/maximum-length        - Max chunk size (default: 256000)

;; Search Phrases
pipeline.search-phrases/model         - LLM model for phrase generation
pipeline.search-phrases/fallback-model - Fallback model
pipeline.search-phrases/prompt        - Prompt template

;; Storage/Collections
pipeline.storage/collection-prefix    - TypeSense collection prefix
pipeline.storage/docs-collection      - Generated docs collection name
pipeline.storage/chunks-collection    - Generated chunks collection name
pipeline.storage/phrases-collection   - Generated phrases collection name

;; Retrieval - Rerank
pipeline.retrieval.rerank/enabled           - Enable ColBERT reranking
pipeline.retrieval.rerank/top-k             - Top K chunks after rerank
pipeline.retrieval.rerank/max-chunk-length  - Max length per chunk
pipeline.retrieval.rerank/max-total-length  - Max total length

;; Retrieval - Context
pipeline.retrieval.context/top-k             - Top K chunks for context
pipeline.retrieval.context/max-docs          - Max documents
pipeline.retrieval.context/max-chunk-length  - Max length per chunk
pipeline.retrieval.context/max-total-length  - Max total context length

;; Generation Prompts
pipeline.generate.prompt/phrase-gen    - Search phrase generation prompt
pipeline.generate.prompt/query-relax   - Query relaxation prompt
pipeline.generate.prompt/rag-generate  - RAG answer generation prompt

;; Operations
pipeline.operations/parallelism-documents  - Parallel document processing
pipeline.operations/parallelism-store      - Parallel storage operations
pipeline.operations/max-document-failures  - Max failures before stopping
```

### Pipeline IDs

Pipelines are identified by: `{tenant}:{environment}:{pipeline-name}`

Examples:
- `ka:prod:main-pipeline`
- `altinn:test:website-pipeline`
- `_:_:default-pipeline` (global default)

### Collection Naming

Collections are auto-generated based on pipeline config:

Format: `{prefix}_{type}_{hash}`

Where:
- `prefix`: From `:collection-prefix` or auto-generated from pipeline name
- `type`: `documents`, `chunks`, or `phrases`
- `hash`: 12-character hash of relevant config (source, chunking, phrases)

Example: `prod_main_documents_abc123def456`

The hash ensures that when config changes, new collections are created automatically.

## Usage Guide

### Creating a Pipeline

#### Via REST API

```bash
POST /api/pipelines
Authorization: Bearer {jwt-token}
Content-Type: application/json

{
  "tenant": "ka",
  "environment": "prod",
  "pipelineName": "main-pipeline",
  "properties": {
    "name": "Main Production Pipeline",
    "description": "Primary pipeline for production data",
    "sourceType": "kudos",
    "chunkStrategy": "semantic",
    "chunkMinimumLength": 333,
    "chunkMaximumLength": 256000,
    "searchPhrasesModel": "gpt-4o",
    "collectionPrefix": "prod_main_",
    "rerankEnabled": true,
    "rerankTopK": 20,
    "contextTopK": 10
  }
}
```

#### Via Clojure Code

```clojure
(require '[digdir.pipeline.core :as pipeline]
         '[digdir.config.db :as config-db])

(def conn (config-db/get-conn))

(pipeline/create-pipeline! conn
  {:tenant "ka"
   :environment "prod"
   :pipeline-name "main-pipeline"
   :properties {:name "Main Production Pipeline"
                :source-type :kudos
                :chunk-strategy :semantic
                :chunk-minimum-length 333
                :search-phrases-model "gpt-4o"
                :collection-prefix "prod_main_"
                :rerank-enabled true}
   :master-key "encryption-key"})
```

### Executing a Pipeline

#### Via REST API

```bash
POST /api/pipelines/ka:prod:main-pipeline/execute
Authorization: Bearer {jwt-token}

Response:
{
  "executionId": "exec-abc123def456"
}
```

#### Via Clojure Code

```clojure
(require '[digdir.pipeline.executor :as executor])

;; Async execution (returns immediately)
(def execution-id
  (executor/execute-pipeline-async! conn
                                   "ka"
                                   "prod"
                                   "main-pipeline"
                                   "encryption-key"
                                   "user@example.com"))

;; Sync execution (blocks until complete)
(m/? (executor/execute-pipeline! conn
                                "ka"
                                "prod"
                                "main-pipeline"
                                "encryption-key"
                                "user@example.com"))
```

### Querying via RAG API

Once a pipeline is executed and collections are created, use it for RAG queries:

```bash
POST /api/rag
X-API-Key: rag_abc123...
Content-Type: application/json

{
  "query": "What is the budget for 2024?",
  "model": "gpt-4o-2024-11-20",
  "rerankTopK": 15,
  "contextTopK": 8
}
```

The API key must have the pipeline ID in its `:api-key/pipelines` field.

### Monitoring Executions

#### List Executions

```bash
GET /api/pipelines/ka:prod:main-pipeline/executions
Authorization: Bearer {jwt-token}

Response:
{
  "executions": [
    {
      "id": "exec-abc123",
      "pipelineId": "ka:prod:main-pipeline",
      "status": "completed",
      "startedAt": "2024-02-04T10:30:00Z",
      "completedAt": "2024-02-04T10:45:00Z",
      "documentsProcessed": 1250,
      "documentsFailed": 5,
      "startedBy": "user@example.com"
    }
  ]
}
```

#### Via Clojure Code

```clojure
(require '[digdir.pipeline.executor :as executor])

(def executions
  (executor/list-executions @conn "ka:prod:main-pipeline"))

(def execution
  (executor/get-execution @conn "exec-abc123"))
```

## Configuration Inheritance

Pipelines support 8-level configuration inheritance:

1. **Pipeline + Tenant + Environment** (most specific)
2. **Tenant + Environment**
3. **Pipeline + Environment**
4. **Pipeline + Tenant**
5. **Environment**
6. **Tenant**
7. **Pipeline** (pipeline defaults)
8. **Global** (system defaults)

This allows you to:
- Set global defaults that apply everywhere
- Override at tenant level for tenant-specific settings
- Override at environment level for prod vs test differences
- Override at pipeline level for pipeline-specific behavior

### Example

```clojure
;; Set global default chunk size
(config-db/set-value! conn
  {:tenant nil
   :environment nil
   :entity nil
   :path "pipeline.chunks.minimum-length"
   :value 333
   :master-key master-key})

;; Override for prod environment
(config-db/set-value! conn
  {:tenant nil
   :environment "prod"
   :entity nil
   :path "pipeline.chunks.minimum-length"
   :value 500
   :master-key master-key})

;; Override for specific pipeline
(config-db/set-value! conn
  {:tenant "ka"
   :environment "prod"
   :entity "main-pipeline"
   :path "pipeline.chunks.minimum-length"
   :value 1000
   :master-key master-key})

;; When queried, ka:prod:main-pipeline will use 1000
;; Other prod pipelines will use 500
;; Non-prod pipelines will use 333
```

## Source Types

### Kudos

Loads documents from Kudos (Norwegian document management system).

**Configuration:**
```clojure
{:source-type :kudos
 :use-preprod true           ;; Use preprod API
 :document-types #{"Årsrapport" "Statusrapport"}
 :document-limit 20000
 :document-offset 0}
```

### Website

Loads markdown content from website sitemaps.

**Configuration:**
```clojure
{:source-type :website
 :sitemap-url "/nb/sitemap-markdown.xml"
 :base-url "http://localhost:1313"
 :document-limit 30000
 :document-offset 0}
```

### Folder

Loads markdown files from local filesystem.

**Configuration:**
```clojure
{:source-type :folder
 :folder-path "/path/to/markdown/files"
 :document-limit 300000
 :document-offset 0}
```

### EPiServer/Optimizely

Loads content from EPiServer/Optimizely CMS.

**Configuration:**
```clojure
{:source-type :episerver
 :api-endpoint "https://api.episerver.com/content"
 :use-preprod false
 :document-limit 100000
 :document-offset 0}
```

## API Key Management

API keys must reference pipelines to grant access:

```clojure
(require '[digdir.config.api-keys :as api-keys])

;; Create API key with pipeline access
(api-keys/create-api-key! conn
  "Production API Key"
  "user@example.com"
  {:tenants ["ka"]
   :environments ["prod"]
   :pipelines ["ka:prod:main-pipeline"]
   :scopes [:query]
   :expires-in-days 365})
```

## UI Components

### Pipeline Management

The pipeline management UI is available at `/config/pipelines` and provides:

- **List View**: All pipelines with filters
- **Create/Edit Form**: Tabbed interface for configuration
  - Basic: Name, description
  - Source: Type and source-specific settings
  - Chunking: Strategy and parameters
  - Retrieval: Rerank and context settings
  - Generation: Prompt templates
- **Actions**: Execute, Edit, Delete, Duplicate

### Execution Monitoring

The execution monitoring UI shows:

- **Execution History**: All pipeline runs
- **Status Tracking**: Running, completed, failed, cancelled
- **Progress Bars**: Real-time progress for running executions
- **Metrics**: Documents processed/failed
- **Error Details**: Full error messages for failed runs

## Migration from Entities/Views

### For Existing Entities

Entities continue to work via backwards compatibility. The RAG API accepts both:
- `pipeline-id` (new format)
- `entity-id` (legacy format)

To migrate an entity to a pipeline:

1. Create a new pipeline with the entity's configuration
2. Execute the pipeline to create collections
3. Update API keys to reference the pipeline instead of entity
4. Deprecate the entity

### For Existing Views

Views were code-based configurations. To migrate:

1. Extract the view configuration into pipeline properties
2. Create the pipeline via API or UI
3. Execute the pipeline
4. Verify collections were created correctly
5. Update client code to use the pipeline

## Troubleshooting

### Pipeline Execution Fails

Check the execution details in the UI or via API:

```clojure
(def execution (executor/get-execution @conn "exec-abc123"))
(:pipeline-execution/error-message execution)
```

Common issues:
- Invalid source configuration (wrong URL, missing credentials)
- LLM API failures (rate limits, invalid API keys)
- TypeSense connection issues
- Document format errors

### Collections Not Created

Verify:
1. Execution completed successfully
2. Collection names were tracked in pipeline config
3. TypeSense is accessible

```clojure
(require '[digdir.pipeline.collections :as collections])

(def pipeline (pipeline/get-pipeline db "ka" "prod" "main-pipeline" master-key))
(def coll-names (collections/get-or-generate-collection-names pipeline))

;; Check if names are stored
(:docs-collection pipeline)
(:chunks-collection pipeline)
(:phrases-collection pipeline)
```

### Query Returns No Results

Verify:
1. Pipeline execution completed and indexed documents
2. API key has access to the pipeline
3. Collection names in query match generated names
4. TypeSense contains documents in the collections

## Performance Considerations

### Parallelism

Adjust parallelism for optimal throughput:

```clojure
{:parallelism-documents 10  ;; Process 10 docs in parallel
 :parallelism-store 1}      ;; Store 1 at a time (TypeSense stability)
```

### Fault Tolerance

Set maximum failures to prevent bad data from blocking execution:

```clojure
{:max-document-failures 100}  ;; Stop after 100 failures
```

### Chunking

Balance chunk size for retrieval quality vs context window:

```clojure
{:chunk-minimum-length 333    ;; Min for meaningful content
 :chunk-maximum-length 256000} ;; Max for LLM context window
```

## Best Practices

1. **Use descriptive names**: Make pipeline purpose clear
2. **Set collection prefixes**: Organize collections by environment/purpose
3. **Monitor executions**: Check for failures and adjust config
4. **Test in dev/test**: Validate config before promoting to prod
5. **Use inheritance**: Set common defaults at global/tenant level
6. **Version with hashing**: Let config changes create new collections automatically
7. **Document changes**: Use description field to explain config changes
8. **Regular cleanup**: Archive or delete old collections after migration

## API Reference

### REST Endpoints

- `GET /api/pipelines` - List pipelines
- `POST /api/pipelines` - Create pipeline
- `GET /api/pipelines/:id` - Get pipeline details
- `PUT /api/pipelines/:id` - Update pipeline
- `DELETE /api/pipelines/:id` - Delete pipeline (soft delete)
- `POST /api/pipelines/:id/execute` - Execute pipeline
- `GET /api/pipelines/:id/executions` - List executions

### Clojure Functions

```clojure
;; Core operations
(pipeline/create-pipeline! conn opts)
(pipeline/get-pipeline db tenant env name master-key)
(pipeline/update-pipeline! conn opts)
(pipeline/delete-pipeline! conn tenant env name)
(pipeline/soft-delete-pipeline! conn tenant env name)
(pipeline/list-pipelines db tenant env)
(pipeline/duplicate-pipeline! conn opts)

;; Collections
(collections/pipeline-collection-names config)
(collections/pipeline-config-hash config)
(collections/track-pipeline-collections! conn tenant env name names master-key)
(collections/get-or-generate-collection-names config)

;; Execution
(executor/execute-pipeline! conn tenant env name master-key user-id)
(executor/execute-pipeline-async! conn tenant env name master-key user-id)
(executor/list-executions db pipeline-id)
(executor/get-execution db execution-id)
(executor/cancel-execution! conn execution-id)
```

## Support

For issues or questions:
- Check execution logs in the UI
- Review audit log for config changes
- See [pipeline-architecture.md](pipeline-architecture.md) for technical details
- Report bugs at https://github.com/anthropics/claude-code/issues
