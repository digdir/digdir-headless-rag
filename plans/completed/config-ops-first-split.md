# `config/ops.clj` First Split Proposal

## Goal
Move the lowest-risk helper and constant definitions out of `server/src/digdir/config/ops.clj` first, before touching the larger bootstrap, retirement, export, and import flows.

## Recommended First Slice
Extract the pre-bootstrap helper layer into a new namespace such as `digdir.config.ops.common`.

### Candidate forms
- `export-version`
- `encryption-metadata`
- `clean-db-pipeline`
- `keyword->string`
- `serialize-pipeline`

## Why This Slice
- These forms are small and self-contained.
- They sit before the main config-tree bootstrap section.
- They are mostly pure helpers or constants, so they are unlikely to create cascading call-site changes.
- The extraction boundary is easy to verify with a narrow compile/test pass.
- `iso-timestamp` stays in `config/ops.clj` for now because the same helper is duplicated in `migration/system.clj` and should be handled as a separate shared-utility follow-up.

## Next Step
- Move the candidate helpers first.
- Leave the bootstrap and import/export orchestration in place until the helper slice is stable.
- Then split the bootstrap section into its own namespace.
