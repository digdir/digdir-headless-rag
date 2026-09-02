# Pipeline Quick Start Guide

Pipelines are the ingestion units that produce datasets. Agents and query APIs read the resulting datasets, not pipelines directly.

## 5-Minute Setup

### 1. Create a Dataset, Then a Pipeline

Navigate to the Pipelines section of the Operator Console, or use the Operator Console APIs:

```bash
curl -X POST https://your-domain/console-api/datasets \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "name": "My First Dataset",
    "description": "Primary query target"
  }'
```

Then create the first child pipeline under that dataset:

```bash
curl -X POST https://your-domain/console-api/datasets/ds_123/pipelines \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "tenant": "digdir",
    "dataset-config-key": "public-docs",
    "pipeline-name": "my-first-pipeline",
    "properties": {
      "name": "My First Pipeline",
      "sourceType": "kudos",
      "kudosUsePreprod": true,
      "kudosDocumentTypes": ["Årsrapport", "Statusrapport"],
      "chunkStrategy": "semantic",
      "searchPhrasesModel": "gpt-4o",
      "rerankEnabled": true,
      "contextTopK": 10
    }
  }'
```

### 2. Execute the Pipeline

```bash
curl -X POST "https://your-domain/console-api/datasets/ds_123/pipelines/my-first-pipeline/execute?tenant=digdir&dataset-config-key=public-docs" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN"

# Returns: { "execution-id": "exec-abc123" }
```

### 3. Monitor Execution

Check status via the Operator Console or via API:

```bash
curl "https://your-domain/console-api/datasets/ds_123/pipelines/my-first-pipeline/executions?tenant=digdir&dataset-config-key=public-docs" \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

### 4. Create API Key

```bash
curl -X POST https://your-domain/console-api/api-keys \
  -H "Content-Type: application/json" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "name": "My API Key",
    "dataset-scopes": [
      {
        "tenant": "digdir",
        "dataset-config-key": "public-docs"
      }
    ]
  }'
# NOTE: do not send "scopes" — the endpoint refuses it with
# `scopes` is not settable on this endpoint; a new key gets the default `query` scope

# Returns: { "api-key-id": "key_abc123", "api-key": "rag_abc123...", ... }
```

### 5. Query via MCP

Retrieval and generation moved to the MCP server (`POST /api/mcp`, MCP JSON-RPC). See
[endpoints/mcp.md](api/endpoints/mcp.md) for the full contract.

```bash
curl -X POST https://your-domain/api/mcp \
  -H "X-API-Key: rag_abc123..." \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "arguments": {
        "user-query": "What is the budget for 2024?",
        "tenant": "digdir",
        "dataset_config_key": "public-docs"
      }
    }
  }'
```

## Common Configurations

### Website Pipeline

```json
{
  "name": "Website Documentation",
  "sourceType": "website",
  "websiteSitemapUrl": "/sitemap.xml",
  "websiteBaseUrl": "https://your-site.com",
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
  "episerverXmlPath": "/path/to/export.xml",
  "episerverLanguage": "no",
  "episerverIncludePageTypes": ["ArticlePage", "StandardPage"],
  "chunkStrategy": "semantic"
}
```

## Quick Tips

✅ **DO:**
- Use descriptive pipeline names
- Set `collectionPrefix` to organize collections
- Test in a non-production tenant/dataset before rollout
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
2. Check API key has dataset access
3. Verify collection names match
4. Check TypeSense contains documents

### Collections Not Created

1. Execution must complete successfully first
2. Check pipeline config for collection names
3. Verify TypeSense connectivity

## Next Steps

- Read [PIPELINES.md](PIPELINES.md) for complete documentation
- See [pipeline-architecture.md](pipeline-architecture.md) for technical details
- Review [CLAUDE.md](../../CLAUDE.md) for development patterns
