# Import/Export Transform Retirement Decision Plan

Status: Completed on 2026-04-13.

## Decision

Retire the explicit transform path now.

## Evidence

- steady-state export/import already runs through `digdir.import-export.system`
- canonical `export -> import` roundtrip coverage already exists in `server/test/digdir/import_export/system_test.clj`
- the retained system export artifacts audited in the repo are already canonical `version: "2.0"`
- no remaining runbook in the active docs requires `bb migration-transform`
- the only remaining callers of the transform path were the dedicated Babashka task, transform-specific tests, and cutover-only reporting helpers

## Artifact / Support Inventory

Canonical and still supported:

- `bb migration-export` output
- canonical `2.0` system exports retained under `server/state/migrations`
- canonical `2.0` dry-run exports retained under `tmp/migrations`

Pre-canonical but worth preserving:

- none found in the retained operator artifact inventory audited in this repo

Pre-canonical and unsupported:

- historical export families that require explicit transform logic before import
- legacy examples embedded in old plans/tests/history

## Support Boundary

Supported:

- canonical system exports with `version: "2.0"` produced by the current import/export routine

Unsupported as of 2026-04-13:

- pre-canonical exports
- mixed legacy exports that require offline normalization before import

Rollback / DR policy:

- use canonical exports only when restoring from exported artifacts
- otherwise prefer repointing to the previous table or restoring infrastructure snapshots

## Changes Executed

- deleted `server/src/digdir/import_export/transform.clj`
- deleted `server/src/digdir/import_export/transforms/system.clj`
- deleted `server/src/digdir/import_export/transforms/explicit_node_resolution.clj`
- removed `bb migration-transform` from `bb.edn`
- removed transform-only test coverage and cutover-only public entry points
- updated the cutover runbook to document the canonical-only support boundary

## Verification Target

The supported workflow is now:

1. `bb migration-export <system-export.json>`
2. `bb migration-import <system-export.json> --on-conflict ...`

No transform step remains in the active repo workflow.
