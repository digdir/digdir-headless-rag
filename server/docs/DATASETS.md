# Datasets

This document explains the dataset model used for read-time access in the Digdir RAG system.

## Core Distinction

The distinction should be read like this:

- pipelines produce datasets
- datasets are the targets that agents read
- API keys grant access to datasets
- agents do not read pipelines directly

A pipeline is the configured ingestion and materialization unit.

A dataset is the resulting target addressed at query time.

## What A Dataset Is

A dataset is the externally visible read target used for:

- query-time retrieval
- RAG configuration selection
- API-key access control
- agent access control
- conversation scoping

When a caller selects content to query, they are selecting a dataset.

When an agent is allowed to query content, it is allowed to query datasets.

## What A Dataset Is Not

A dataset is not the ingestion process itself.

Datasets are refreshed and materialized by pipelines. Pipelines fetch source data, chunk it, store it, and publish the result that agents later read as a dataset.

## Dataset ID Shape

Datasets have stable dataset IDs such as:

- `public-docs`
- `kudos`

The public/runtime scope that selects one dataset is separate from the internal operator/materialization identifiers used to build it.

## Dataset Ref

A dataset ref is the public selection object used in API keys, agents, and query routing.

Required fields:

- `tenant`
- `dataset-config-key`

Example:

```json
{
  "tenant": "digdir",
  "dataset-config-key": "public-docs"
}
```

## Agents Read Datasets

Agents are granted dataset access through dataset-scope lists.

That means:

- an agent is allowed to read a dataset
- an agent is not granted direct access to a pipeline execution/config surface
- the pipeline remains an ingestion concern behind the dataset target

This is the key boundary to preserve in code and docs.

## API Keys Grant Dataset Access

API keys are scoped with `dataset-scopes`, not legacy `pipelines` arrays.

Example:

```json
{
  "name": "Production integration",
  "dataset-scopes": [
    {
      "tenant": "digdir",
      "dataset-config-key": "public-docs"
    }
  ]
}
```

This determines which datasets the key may access at query time.

It does not mean the key can manage or execute pipelines.

## Public API Naming

The read-time contract is dataset-oriented:

- access model: `dataset-scope`, `dataset-scopes`
- dataset IDs identify read targets
- agents and API keys are scoped to datasets

This is appropriate for read-time and access-control concepts, because callers and agents operate on datasets.

## Internal Naming Still In Transition

Internal/materialization naming still present in some areas:

- low-level Clojure functions such as `create-pipeline!`
- ingestion and execution namespaces under `digdir.pipeline.*`
- operator/materialization routes under `/console-api/datasets/:dataset-id/pipelines`

Those are operator-side implementation details, not the read-time contract.

## Operator Console API Boundary

The Operator Console APIs manage pipelines, because pipelines are the producer/configuration side of the system:

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

Those routes manage the ingestion units that produce datasets.

## Relationship To Pipelines

Use this split consistently:

- dataset docs describe what is read
- pipeline docs describe what is configured and executed to produce those targets

If you are explaining ingestion, source config, chunking, execution, or materialization, you are writing about pipelines.

If you are explaining query access, agents, API keys, or dataset selection, you are writing about datasets.

See [PIPELINES.md](./PIPELINES.md) for the ingestion/configuration side of the system.
