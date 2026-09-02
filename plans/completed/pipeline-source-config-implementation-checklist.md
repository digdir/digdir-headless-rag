# Pipeline Source Config Implementation Checklist

## Scope
Implement strict, source-specific pipeline configuration with the agreed renames:
- `pipeline.documents.types` -> `pipeline.source.kudos.document-types`
- `pipeline.documents.transducer` -> `pipeline.source.kudos.transducer`

## Phase 1: Canonical Path Model
1. Update `pipeline-property-to-path` in `server/src/digdir/config/db.clj`:
- Add `:kudos-document-types -> "pipeline.source.kudos.document-types"`
- Add `:kudos-transducer -> "pipeline.source.kudos.transducer"`
- Add/normalize source keys:
- `pipeline.source.website.sitemap-url`
- `pipeline.source.website.base-url`
- `pipeline.source.folder.path`
- `pipeline.source.episerver.xml-path`
- `pipeline.source.episerver.language`
- `pipeline.source.episerver.include-page-types`
- `pipeline.source.kudos.use-preprod`
- `pipeline.source.kudos.starting-page`
2. Remove/stop writing ambiguous keys:
- `pipeline.source.sitemap-url`
- `pipeline.source.folder-path`
- `pipeline.source.api-endpoint`
- `pipeline.source.use-preprod`

## Phase 2: Loader Mapping
1. Update `convert-pipeline-config-to-loader-format` in `server/src/digdir/pipeline/executor.clj`:
- `:website` uses `:website-sitemap-url` and `:website-base-url`
- `:folder` uses `:folder-path`
- `:kudos` uses `:kudos-use-preprod`, `:kudos-starting-page`, `:kudos-document-types`, `:kudos-transducer`
- `:episerver` uses `:episerver-xml-path`, `:episerver-language`, `:episerver-include-page-types`
2. Remove dead EPiServer API mapping unless EPiServer API mode is reintroduced.

## Phase 3: Validation Rules (Strict)
1. Add source-specific validation in pipeline create/update path (`server/src/digdir/pipeline/core.clj` or `server/src/digdir/api/routes.clj`):
- Required keys by `pipeline.source.type`
- Allowed keys by `pipeline.source.type`
- Reject irrelevant keys with clear error messages
2. Ensure unknown properties are rejected, not silently ignored.

## Phase 4: Migration
1. Add migration tool/script for config paths:
- Rename `pipeline.documents.types` -> `pipeline.source.kudos.document-types`
- Rename `pipeline.documents.transducer` -> `pipeline.source.kudos.transducer`
- Rename old generic source keys to source-specific variants
2. Migrate both definitions and values (`config-def/path`, `config/definition-path`, and scoped values).
3. Add dry-run + report mode.

## Phase 5: Seed/Import Data
1. Update `config-defs/import-live-2026-02-06-124739-hard-cut-final.json`:
- Include definition entries for `pipeline.source.kudos.document-types` and `pipeline.source.kudos.transducer`.
- If old paths exist in future exports, rewrite both definitions and values to the new paths.
2. Keep JSON schema-valid and deterministic ordering where possible.

## Phase 6: UI and API Contract
1. Update pipeline UI forms to show source-specific fields and hide irrelevant ones (`server/src/digdir/pipeline/ui/pipelines.cljc`).
2. Update API docs and examples (`server/docs/PIPELINES.md`, `server/docs/PIPELINES-QUICKSTART.md`).
3. Document per-source required and optional fields.

## Phase 7: Tests
1. Add/extend tests for:
- Source-type-specific validation success/failure cases
- Loader mapping correctness per source
- Migration correctness for both definitions and values
2. Regression test EPiServer XML mode to ensure required xml-path is enforced.

## Acceptance Criteria
1. Each source type has explicit required/allowed config paths.
2. Ambiguous source paths are no longer used for writes.
3. Kudos-specific fields use `pipeline.source.kudos.*` names.
4. Import/migration data includes renamed paths.
5. Pipeline creation/update fails fast on invalid source config.
