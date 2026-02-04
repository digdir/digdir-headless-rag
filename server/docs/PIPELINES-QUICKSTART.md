# Pipeline Quick Start Guide

## 5-Minute Setup

### 1. Create a Pipeline

Navigate to `/config/pipelines` in the admin UI, or use the API:

```bash
curl -X POST https://your-domain/api/pipelines \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tenant": "your-tenant",
    "environment": "prod",
    "pipelineName": "my-first-pipeline",
    "properties": {
      "name": "My First Pipeline",
      "sourceType": "kudos",
      "chunkStrategy": "semantic",
      "searchPhrasesModel": "gpt-4o",
      "rerankEnabled": true,
      "contextTopK": 10
    }
  }'
```

### 2. Execute the Pipeline

```bash
curl -X POST https://your-domain/api/pipelines/your-tenant:prod:my-first-pipeline/execute \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Returns: { "executionId": "exec-abc123" }
```

### 3. Monitor Execution

Check status via UI at `/config/pipelines` or via API:

```bash
curl https://your-domain/api/pipelines/your-tenant:prod:my-first-pipeline/executions \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### 4. Create API Key

```bash
curl -X POST https://your-domain/config/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My API Key",
    "pipelines": ["your-tenant:prod:my-first-pipeline"],
    "scopes": ["query"]
  }'

# Returns: { "apiKey": "rag_abc123..." }
```

### 5. Query via RAG API

```bash
curl -X POST https://your-domain/api/rag \
  -H "X-API-Key: rag_abc123..." \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the budget for 2024?"
  }'
```

## Common Configurations

### Website Pipeline

```json
{
  "name": "Website Documentation",
  "sourceType": "website",
  "sitemapUrl": "/sitemap.xml",
  "baseUrl": "https://your-site.com",
  "chunkStrategy": "header-based",
  "chunkMinimumLength": 333,
  "collectionPrefix": "docs_"
}
```

### Folder Pipeline

```json
{
  "name": "Local Markdown Files",
  "sourceType": "folder",
  "folderPath": "/path/to/docs",
  "chunkStrategy": "markdown",
  "chunkMinimumLength": 500
}
```

### EPiServer Pipeline

```json
{
  "name": "CMS Content",
  "sourceType": "episerver",
  "apiEndpoint": "https://cms-api.example.com",
  "usePreprod": false,
  "chunkStrategy": "semantic"
}
```

## Quick Tips

✅ **DO:**
- Use descriptive pipeline names
- Set `collectionPrefix` to organize collections
- Test in dev/test environment first
- Monitor execution logs for errors
- Use inheritance for common settings

❌ **DON'T:**
- Run multiple executions simultaneously on the same pipeline
- Set `documentLimit` too high initially
- Skip error monitoring
- Forget to create API keys with proper permissions

## Troubleshooting

### Execution Failed

1. Check execution details in UI
2. Look for error message
3. Verify source configuration (URLs, paths, credentials)
4. Check LLM API quotas

### No Results from Query

1. Verify execution completed successfully
2. Check API key has pipeline access
3. Verify collection names match
4. Check TypeSense contains documents

### Collections Not Created

1. Execution must complete successfully first
2. Check pipeline config for collection names
3. Verify TypeSense connectivity

## Next Steps

- Read [PIPELINES.md](PIPELINES.md) for complete documentation
- See [pipeline-architecture.md](pipeline-architecture.md) for technical details
- Review [CLAUDE.md](CLAUDE.md) for development patterns
