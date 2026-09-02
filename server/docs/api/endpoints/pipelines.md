# Operator Console Dataset and Pipeline Endpoints

Manage datasets and child pipelines through the JWT-authenticated Operator Console APIs.

This surface is internal:

- datasets are parent resources
- pipelines are child producer/configuration resources under datasets
- pipeline execution is Operator Console only
- agents and public API clients read datasets, not pipelines directly

## Authentication

All endpoints require JWT authentication via the `auth-token` cookie.

Mutation routes also require `X-User-Email`:

- `POST /console-api/datasets`
- `POST /console-api/datasets/:dataset-id/pipelines`
- `PUT /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `DELETE /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `POST /console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`

## Dataset Endpoints

### List Datasets

`GET /console-api/datasets`

Returns durable parent datasets with child pipeline summaries.

### Create Dataset

`POST /console-api/datasets`

#### Request Body

```json
{
  "name": "Public Docs",
  "description": "Shared docs"
}
```

#### Success Response

```json
{
  "datasetId": "ds_123",
  "dataset": {
    "id": "ds_123",
    "name": "Public Docs",
    "description": "Shared docs",
    "enabled?": true,
    "pipelineCount": 0,
    "pipelines": []
  }
}
```

### Get Dataset

`GET /console-api/datasets/:dataset-id`

Returns the parent dataset and its current child pipeline summaries.

## Pipeline Endpoints

Pipeline routes are nested under a dataset.

### List Pipelines

`GET /console-api/datasets/:dataset-id/pipelines?tenant=digdir&dataset-config-key=public-docs`

List child pipelines under a dataset.

The console must provide `tenant` and `dataset-config-key` so the backing config tree can resolve the materialization context.

### Create Pipeline

`POST /console-api/datasets/:dataset-id/pipelines`

#### Request Body

```json
{
  "tenant": "digdir",
  "dataset-config-key": "public-docs",
  "pipeline-name": "assistant",
  "properties": {
    "name": "Assistant",
    "description": "Primary assistant pipeline",
    "sourceType": "website"
  }
}
```

#### Success Response

```json
{
  "pipeline-name": "assistant",
  "execution-pipeline-id": "digdir:public-docs:assistant",
  "datasetId": "ds_123"
}
```

### Get Pipeline

`GET /console-api/datasets/:dataset-id/pipelines/:pipeline-id?tenant=digdir&dataset-config-key=public-docs`

Returns resolved pipeline details for one child pipeline.

### Update Pipeline

`PUT /console-api/datasets/:dataset-id/pipelines/:pipeline-id?tenant=digdir&dataset-config-key=public-docs`

#### Request Body

```json
{
  "properties": {
    "name": "Assistant",
    "description": "Updated description",
    "sourceType": "website"
  }
}
```

### Delete Pipeline

`DELETE /console-api/datasets/:dataset-id/pipelines/:pipeline-id?tenant=digdir&dataset-config-key=public-docs`

Soft-deletes the child pipeline.

### Execute Pipeline

`POST /console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute?tenant=digdir&dataset-config-key=public-docs`

Starts asynchronous ingestion/execution for the child pipeline.

#### Success Response

```json
{
  "executionId": "exec-abc123"
}
```

### List Pipeline Executions

`GET /console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions?tenant=digdir&dataset-config-key=public-docs`

Returns execution history for the child pipeline.

## Notes

- The canonical request field is `pipeline-name`.
- The canonical execution identity field is `execution-pipeline-id`.
- The parent dataset ID is the canonical dataset identifier used in dataset-oriented APIs.
