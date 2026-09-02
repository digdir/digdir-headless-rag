# Pipeline System Documentation

Pipelines are operator-managed ingestion and materialization resources. They fetch source content, transform it, and publish the dataset state that public callers and agents later read.

This is the boundary to preserve:

- datasets are the public/runtime read target
- pipelines are the operator/materialization mechanism behind those datasets

See [DATASETS.md](./DATASETS.md) for the dataset-facing read-time model.

## Operator Model

Each durable dataset can own one or more child materialization pipelines.

Operators manage that hierarchy through the Console API:

- `GET /console-api/datasets`
- `POST /console-api/datasets`
- `GET /console-api/datasets/:dataset-id`
- `GET /console-api/datasets/:dataset-id/pipelines`
- `POST /console-api/datasets/:dataset-id/pipelines`
- `GET /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `PUT /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `DELETE /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `POST /console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`
- `GET /console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions`

Console pipeline routes use dataset-first context:

- query params: `tenant` + `dataset-config-key`
- create body: `tenant`, `dataset-config-key`, `pipeline-name`
- execution identity: `tenant:dataset-config-key:pipeline-name`

## What Pipelines Control

Pipelines control operator-side ingestion/materialization concerns such as:

- source type and source-specific config
- document loading and filtering
- chunking strategy
- materialization execution
- storage collection generation

Runtime dataset selection stays dataset-first even when multiple child pipelines feed one dataset.

## Create A Pipeline

```bash
curl -X POST https://your-domain/console-api/datasets/ds_123/pipelines \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "tenant": "digdir",
    "dataset-config-key": "public-docs",
    "pipeline-name": "assistant",
    "properties": {
      "name": "Assistant",
      "sourceType": "website",
      "chunkStrategy": "semantic",
      "collectionPrefix": "public_docs_"
    }
  }'
```

## Execute A Pipeline

```bash
curl -X POST "https://your-domain/console-api/datasets/ds_123/pipelines/assistant/execute?tenant=digdir&dataset-config-key=public-docs" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

Successful responses return an execution record keyed by `execution-id`.

## List Executions

```bash
curl "https://your-domain/console-api/datasets/ds_123/pipelines/assistant/executions?tenant=digdir&dataset-config-key=public-docs" \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

Example response shape:

```json
{
  "executions": [
    {
      "id": "exec_123",
      "execution-pipeline-id": "digdir:public-docs:assistant",
      "status": "completed",
      "startedAt": "2026-04-07T10:30:00Z",
      "completedAt": "2026-04-07T10:45:00Z"
    }
  ]
}
```

## Querying The Resulting Dataset

Public callers do not query pipelines directly. They query datasets through API keys scoped with `dataset-scopes`.

Example dataset scope:

```json
{
  "tenant": "digdir",
  "dataset-config-key": "public-docs"
}
```

## Clojure Entry Points

These functions still belong to the operator/materialization layer:

```clojure
(pipeline/create-pipeline! conn opts)
(pipeline/get-dataset db tenant dataset-config-key pipeline-name master-key)
(pipeline/update-pipeline! conn opts)
(pipeline/list-pipelines db tenant dataset-config-key)
(executor/execute-pipeline! conn tenant dataset-config-key pipeline-name master-key user-id)
(executor/execute-pipeline-async! conn tenant dataset-config-key pipeline-name master-key user-id)
(executor/list-executions db execution-pipeline-id)
(executor/get-execution db execution-id)
```

## Best Practices

1. Use descriptive pipeline names that reflect the ingestion purpose.
2. Treat `tenant` + `dataset-config-key` as the canonical operator context.
3. Test pipeline changes in a non-production dataset scope before rollout.
4. Monitor execution history rather than inferring readiness from config alone.
5. Keep public/docs/API-key guidance dataset-first, and reserve pipeline details for operator workflows.

## Related Docs

- [DATASETS.md](./DATASETS.md)
- [PIPELINES-QUICKSTART.md](./PIPELINES-QUICKSTART.md)
- [api/endpoints/pipelines.md](./api/endpoints/pipelines.md)
