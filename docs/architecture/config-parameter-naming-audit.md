# Config Parameter Naming Audit

## Goal

Use configuration names that match the product model:

- `pipeline.*` for dataset materialization and storage
- `skills.*` for runtime skill behavior
- `skill-graph` as the runtime composition concept
- `agent` for policy/governed actor selection
- context-specific request-facing node selectors such as `platform-config-key`, `runtime-config-key`, and `dataset-config-key`

## Findings

Before this audit, runtime RAG knobs were still primarily stored and described as pipeline parameters:

- `pipeline.retrieval.rerank.*`
- `pipeline.retrieval.retrieve.*`
- `pipeline.retrieval.context.*`
- `pipeline.generate.prompt.*`

Those settings drive builtin skills, not ingestion pipelines.

## Canonical Runtime Names

Runtime skill settings should now use these canonical paths:

- `skills.query-planner.enabled`
- `skills.query-planner.prompt`
- `skills.query-planner.max-phrases`
- `skills.retrieval.enabled`
- `skills.retrieval.phrase-gen-prompt`
- `skills.retrieval.top-k`
- `skills.retrieval.max-per-document`
- `skills.retrieval.query-aware-boost`
- `skills.rerank.enabled`
- `skills.rerank.top-k`
- `skills.rerank.max-chunk-length`
- `skills.rerank.max-total-length`
- `skills.rerank.context.top-k`
- `skills.rerank.context.min-chunks`
- `skills.rerank.context.relative-score-threshold`
- `skills.rerank.context.max-chunk-length`
- `skills.rerank.max-context-length`
- `skills.synthesis.model`
- `skills.synthesis.temperature`
- `skills.synthesis.max-tokens`
- `skills.synthesis.system-prompt`
- `skills.synthesis.generation-prompt`
- `skills.synthesis.max-docs`

## Canonical Terminology

To keep the naming model unambiguous:

- `config path`: a dot-separated setting path such as `skills.retrieval.top-k`
- `config definition`: the metadata/schema row for one config path
- `config value`: the value stored or resolved at a config path on a config node

This repo should not use `config key` for config paths anymore.

Request-facing node selectors should also be root-specific rather than generic:

- `platform-config-key`
- `runtime-config-key`
- `dataset-config-key`

The generic `config_key` term is deprecated and should not be expanded further.

## Rename Phases

To avoid overloading `config-key`, apply the naming transition in two phases:

1. Phase 1: rename old `config key` terminology to `config path`
2. Phase 2: rename request-facing node selectors to their root-specific forms and remove `config_key` from public APIs

## Cutover

This repo now treats the rename as a data migration, not a permanent runtime compatibility layer.

- export the current database
- transform the export so legacy runtime paths are rewritten to `skills.*`
- import into a fresh database
- keep application code canonical and remove legacy `pipeline.*` runtime aliases

That keeps the runtime model aligned with the product model and avoids carrying dual-name resolution in the live code.
