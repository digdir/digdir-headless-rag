# Deployment Polish Audit

Date: 2026-03-29

## Purpose

This audit captures the current live topology before tenant and materialization cleanup work begins.

It is the concrete output for:

- Workstream A / Phase A1: audit current live state
- Workstream B / Phase B1: inventory remaining hard-coded materialization settings

## Current Live Topology

Live config DB snapshot:

- tenants:
  - `altinn`
  - `altinn-docs`
  - `ka`
- datasets:
  - `assistant`
  - `kudos`
- dataset pipelines:
  - `assistant` -> dataset `assistant`
  - `kudos` -> dataset `kudos`
- durable agents:
  - `builtin/agent-rag-agent`
  - `builtin/fact-checker-agent`
  - `builtin/research-assistant-agent`
  - `builtin/retrieve-only-agent`
  - `builtin/simple-qa-agent`

Important observation:

- the live DB does not currently contain a first-class `altinn-docs` dataset or pipeline record
- tenant `altinn-docs` exists only as a Platform-root shell
- preserving the requested `altinn-docs` pipeline will therefore require reconstruction from code or historical exports, not a simple in-place rename

Recovered lineage:

- historical export data ties tenant `altinn-docs` consistently to pipeline `assistant`
- the strongest practical interpretation is that `assistant` is the surviving public-docs materialization lineage under an older pipeline identity
- this audit therefore treats `altinn/dev/assistant` as the source configuration to recover into the target `digdir/public-docs` dataset, with a first-class target pipeline renamed to `altinn-docs`

## Current Root State By Tenant

### `altinn`

- Platform:
  - nodes:
    - `platform/altinn/default`
    - `platform/altinn/prod`
  - bindings:
    - `:platform-profile "dev"` -> `platform/altinn/prod`
- Runtime:
  - no runtime tree yet
- Dataset:
  - nodes:
    - `dataset/altinn/assistant/default`
    - `dataset/altinn/dev/assistant/materialization`
  - bindings:
    - `:dataset "assistant"` -> `dataset/altinn/assistant/default`
    - `:pipeline "assistant"` -> `dataset/altinn/assistant/default`
    - `:dataset-profile "dev::assistant"` -> `dataset/altinn/dev/assistant/materialization`

Effective materialization values found on the leaf node:

- `pipeline.ui.name` = `[DEV] Altinn Assistant`
- `pipeline.storage.docs-collection` = `website_documents_ab897fbdedfa`
- `pipeline.storage.chunks-collection` = `website_chunks_ab897fbdedfa`
- `pipeline.storage.phrases-collection` = `website_phrases_ab897fbdedfa`

### `altinn-docs`

- Platform:
  - nodes:
    - `platform/altinn-docs/default`
    - `platform/altinn-docs/prod`
  - bindings:
    - `:platform-profile "dev"` -> `platform/altinn-docs/prod`
- Runtime:
  - no runtime tree
- Dataset:
  - no dataset tree

Important observation:

- `altinn-docs` currently has no dataset bindings, no dataset nodes, and no dataset pipeline record
- this tenant looks like a leftover namespace rather than an active materialization tenant

### `ka`

- Platform:
  - nodes:
    - `platform/ka/default`
    - `platform/ka/prod`
  - bindings:
    - `:platform-profile "dev"` -> `platform/ka/prod`
- Runtime:
  - nodes:
    - `runtime/ka/default`
    - `runtime/ka/default-runtime`
  - bindings:
    - `:runtime-profile "default"` -> `runtime/ka/default-runtime`
    - `:agent "builtin/agent-rag-agent"` -> `runtime/ka/default-runtime`
- Dataset:
  - nodes:
    - `dataset/ka/kudos/default`
    - `dataset/ka/dev/kudos/materialization`
  - bindings:
    - `:dataset "kudos"` -> `dataset/ka/kudos/default`
    - `:pipeline "kudos"` -> `dataset/ka/kudos/default`
    - `:dataset-profile "dev::kudos"` -> `dataset/ka/dev/kudos/materialization`

Effective materialization values found on the leaf node:

- `pipeline.ui.name` = `Kunnskapsassistent`
- `pipeline.storage.docs-collection` = `KUDOS_preprod_v4_documents_ab897fbdedfa`
- `pipeline.storage.chunks-collection` = `KUDOS_preprod_v4_chunks_ab897fbdedfa`
- `pipeline.storage.phrases-collection` = `KUDOS_preprod_v4_phrases_ab897fbdedfa`

## Current-To-Target Mapping Draft

### Target Tenant: `digdir`

Target intent:

- combine the public-documentation surfaces currently split across `altinn` and `altinn-docs`
- replace the current `assistant` dataset identity with a clearer public-docs dataset identity

Draft mapping:

- current tenant `altinn` -> target tenant `digdir`
- current tenant `altinn-docs` -> retire after any needed pipeline/source reconstruction is complete
- current dataset `assistant` -> target dataset `public-docs`
- current pipeline `assistant` -> likely becomes one of:
  - `altinn-docs`, if it is the preserved public-docs pipeline in practice
  - or a retired assistant-era pipeline if it is not actually the `altinn-docs` source definition

Open migration gap:

- the requested preserved `altinn-docs` pipeline definition is not present as a live dataset pipeline record
- before mutation, we need to identify whether:
  - the existing `assistant` materialization is the de facto `altinn-docs` pipeline under an old name
  - or the real `altinn-docs` definition survives only in code/setup/history

Planned target under `digdir`:

- dataset:
  - `public-docs`
- pipelines:
  - preserved `altinn-docs`
  - new `digdir-docs`
- agents:
  - keep all current builtin agents
  - add:
    - `interactive-doc-improve`
    - `plain-language-qualtiy-check`

### Target Tenant: `public-sector-knowledge`

Target intent:

- rename and clarify the current `ka` tenant
- preserve current `kudos` storage/materialization state

Draft mapping:

- current tenant `ka` -> target tenant `public-sector-knowledge`
- current dataset `kudos` -> keep as `kudos`
- current pipeline `kudos` -> keep as the stable pipeline feeding `kudos`

Planned target under `public-sector-knowledge`:

- dataset:
  - `kudos`
- pipelines:
  - preserved `kudos`
- agents:
  - keep all current builtin agents
  - add:
    - `cross-sector-researcher`

## Entity Disposition Draft

### Keep And Rebind

- dataset `kudos`
- dataset pipeline `kudos`
- `ka` runtime-tree values, after rebinding under `public-sector-knowledge`
- current builtin agent definitions

### Recreate Or Rename

- dataset `assistant` -> likely recreate or rename as `public-docs`
- dataset pipeline `assistant` -> likely rename or replace once the real `altinn-docs` mapping is clarified
- tenant `altinn` -> recreate or rename as `digdir`
- tenant `ka` -> recreate or rename as `public-sector-knowledge`

### Retire

- tenant `altinn-docs`, after any needed pipeline/source reconstruction is complete
- original tenant IDs `altinn` and `ka`, after cutover verification

## Remaining Hard-Coded Materialization Settings

### Already In Dataset-Root Config

Current dataset trees already hold these values:

- `pipeline.ui.name`
- `pipeline.storage.docs-collection`
- `pipeline.storage.chunks-collection`
- `pipeline.storage.phrases-collection`

Definitions already exist for broader Dataset-root materialization config:

- `pipeline.source.*`
- `pipeline.storage.collection-prefix`
- `pipeline.operations.parallelism-documents`
- `pipeline.operations.parallelism-store`
- `pipeline.operations.max-document-failures`

Important observation:

- the live dataset trees currently store only pipeline display name plus the three concrete collection names
- source selection and most materialization behavior are still not seeded into the DB for the active tenants

### Still Hard-Coded In Execution Path

From [pipeline/executor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/executor.clj):

- source defaults:
  - kudos:
    - `:kudos-use-preprod` default `false`
    - `:kudos-starting-page` default `1`
    - `:kudos-document-types` default `#{}`
    - `:document-limit` default `20000`
    - `:document-offset` default `0`
  - website:
    - `:website-base-url` default `"http://localhost:1313"`
    - `:document-limit` default `30000`
    - `:document-offset` default `0`
  - folder:
    - `:document-limit` default `300000`
    - `:document-offset` default `0`
  - episerver:
    - `:episerver-language` default `"no"`
    - `:episerver-include-page-types` default `[]`
    - `:document-limit` default `100000`
    - `:document-offset` default `0`
- chunking defaults:
  - `:chunk-strategy` default `:header-based`
  - `:chunk-minimum-length` default `333`
  - `:chunk-maximum-length` default `256000`
  - `:chunks/hash-changer` hard-coded `1`
- search phrase defaults:
  - `:search-phrases-model` default `"gpt-4o"`
  - `:search-phrases-fallback` default `:google/gemma-3-27b-it`
  - `:search-phrases-prompt` default `"Generate search phrases for: REPLACE_ME"`
  - `:search-phrases/hash-changer` hard-coded `1`
- storage fallback:
  - `:collection-prefix` default `"pipeline_"`
- operational defaults:
  - `:parallelism-documents` default `3`
  - `:parallelism-store` default `1`
  - `:max-document-failures` default `10`

### Derived Logic Likely To Remain Derived

From [pipeline/collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj):

- collection-name hashing based on:
  - `:source-type`
  - chunking fields
  - search phrase fields
- auto-generated collection names when explicit stored names are absent
- auto-prefix generation from pipeline name when explicit `collection-prefix` is absent

These look like code-derived behaviors, not all of which should become direct config.

## Immediate Recommendations

1. Clarify whether live pipeline `assistant` is actually the public-docs pipeline that should be preserved as `altinn-docs`.
2. If not, recover the missing `altinn-docs` source/materialization definition from code or historical export before tenant normalization.
3. Preserve the current `kudos` collection names exactly when rebinding into `public-sector-knowledge`.
4. Treat source parameters, chunking defaults, phrase-generation defaults, and execution controls as the main Workstream B migration scope.
5. Do not delete `altinn`, `altinn-docs`, or `ka` until the replacement tenants resolve cleanly and the reconstructed public-docs pipeline set is proven.
