# Dataset Endpoints

Public dataset endpoints are read-only and dataset-first.

> **Glossary.** A **dataset** is the retrievable corpus — what AWS Bedrock calls a
> *knowledge base*. A **pipeline** is the materialization definition beneath it: how
> that corpus gets built, chunked and indexed. Keeping them separate is deliberate,
> so a dataset can be rebuilt by successive pipelines without its identity, or the
> API keys scoped to it, changing. See
> [considered divergences](../considered-divergences.md).

## Authentication

All endpoints require `X-API-Key`. Visibility is filtered by the API key's `dataset-scopes`.

## List Datasets

`GET /api/datasets`

### Success Response

```json
{
  "datasets": [
    {
      "id": "public-docs",
      "name": "Public Docs",
      "description": "Shared public Digdir documentation",
      "enabled?": true,
      "status": "ready"
    }
  ]
}
```

## Get Dataset

`GET /api/datasets/:dataset-id`

### Success Response

```json
{
  "dataset": {
    "id": "public-docs",
    "name": "Public Docs",
    "description": "Shared public Digdir documentation",
    "enabled?": true,
    "status": "ready"
  }
}
```

## Status Semantics

Dataset status is derived from the visible materialization state behind that dataset:

- `needs-pipeline`
- `configured`
- `running`
- `ready`
- `degraded`

## Notes

- Public callers select datasets with `tenant` + `dataset-config-key`.
- Public callers do not list, fetch, create, update, delete, or execute materialization pipelines.
- Materialization details belong to the Operator Console APIs.
