# Dump Export/Import — YAML/JSONL alternative to JSON migration

Date: 2026-05-08 → 2026-05-09 (planning + ship)

## Goal

Build a parallel system export/import flow that produces a folder of small,
human-reviewable files instead of the existing single monolithic JSON, so
state changes can be diffed in PRs. The existing
`bb migration-export`/`bb migration-import` flow stays in place and runs in
parallel — the new flow is opt-in.

## Decisions taken

| Question | Choice | Why |
|---|---|---|
| Format for configs | YAML, **natural-key map** shape (`_namespace:` header + collection keyed by the entity's natural id) | Smallest diffs; least ceremony; the dump is the document |
| Format for conversations | JSONL (originally proposed XML; pivoted day 1) | One record per line beats nested ceremony; greppable; easy to stream |
| Conversation partitioning | One file per UTC day, by `:conversation/created` | Keeps individual files small; mirrors typical operator review |
| Config-values partitioning | One file per tenant under `04-config-values/<tenant>.yaml` | Tenant is the natural review boundary |
| Config-values record shape | 2-level nested: outer `node-id` → inner `def-path` → leaf | Reviewable; clusters related values; chosen via AskUserQuestion preview |
| Folder name | UTC timestamp only: `dumps/<UTC-timestamp>/` | Sortable; no naming decision per export |
| Secrets | `:api-key/key` encrypted via AES-256-GCM (`digdir.config.crypto`) using `CONFIG_MASTER_KEY`; `enc:` prefix marks ciphertext | Existing JSON exporter dumped api-keys plaintext — actual exposure |
| Cross-deployment | **Not** supported. Encrypted-at-rest values tied to live `CONFIG_MASTER_KEY` | Postpone; same-deployment backup/restore is the primary use case |
| Dependent on existing flow | New flow uses `config-sync/import-data` for config-related entities; per-entity `:apply-fn` for users/agents/folders/api-keys/conversations | Reuse upstream import code; no duplicate transaction logic |
| Canonical only | Yes — no v2.0 JSON migration support | Greenfield; no v2.0 dumps in the wild |

## Architecture

`digdir.import-export.dump` orchestrates a table of entity specs. Each spec
describes one file (or folder) and binds:

- `:filename` (or `:folder` for partitioned)
- `:namespace`, `:collection-key`, `:natural-key` for standard YAML records
- `:secret-keys` for encrypted fields
- `:envelope-key` if the entity feeds the bundled `config-sync/import-data`
  call, OR `:apply-fn` if it has its own import path
- Optional `:write-fn` / `:read-fn` overrides for non-standard layouts
  (config-values 2-level nest, conversations JSONL+date)
- `:extract-fn` to pull records from the right DB

Format primitives are deliberately minimal:

- `digdir.import-export.format.yaml` — natural-key map shape, `_namespace:`
  header, `:drop-keys` (write) + `:reinject-key` (read) so the natural-key
  field doesn't appear twice, `enc:` AES-256-GCM secrets, deterministic
  field ordering.
- `digdir.import-export.format.jsonl` — one record per line; namespaced keys
  round-trip via cheshire.

## Upstream patches that landed alongside (benefit BOTH flows)

- **`digdir.rag.typesense`**: `resolve-tenant-ts-settings` catches
  `:kind :tenant-root-missing` and returns nil. Without this, any namespace
  that transitively required typesense failed to load against an empty DB,
  blocking fresh-DB import for both `dump-import` and `migration-import`.
- **`entities/users.clj`**: accept `:user/permissions` as either a string id
  or a `{:permission/id ...}` map (the export shape is the latter).
- **`entities/conversations.clj`**: move nillable conversation/message
  fields into `cond->` so datahike doesn't reject minimal records.
- **Timestamp preservation across import** for config-defs, config-nodes,
  config-values, datasets, dataset-pipelines, agents. Pattern: "input
  wins" — dump data overrides the now-timestamps that `init-config-db!`
  pre-seeds. UI write paths pass no timestamps and fall through to the
  existing-or-now behavior, so no regression.
- **`bootstrap/ensure-config-node!`**: forwards `system-managed?` and
  timestamps to the update path so dump-import can fully restore those
  fields (previously dropped on overwrite).
- **`import-node-values!`**: preserves `:config.value/pin-of-version`
  on round-trip, including retracting the existing pin when the dump
  unpinned a value.

## Verification

End-to-end round-trip against the live local DB (Datahike file backend):

1. `bb dump-export` → `dumps/<timestamp>/`
2. `DATAHIKE_FILE_PATH=local-db/dh_roundtrip_X bb dump-import dumps/<timestamp> --on-conflict overwrite`
3. `DATAHIKE_FILE_PATH=local-db/dh_roundtrip_X bb dump-export dumps/verify-X`
4. Diff `dumps/<timestamp>` against `dumps/verify-X`

Result: every per-entity file is **byte-identical** except for the
`api-key/key` ciphertext lines — AES-256-GCM uses a fresh random IV per
encrypt, so on-disk bytes differ even though the recovered plaintexts
are identical.

Tests: 30 / 195 assertions across `dump_test`, `format/yaml_test`,
`format/jsonl_test`, plus 7 / 85 in the existing `system_test` (proves the
upstream patches don't regress the JSON migration flow). All green;
lint-clean for all touched files.

## Known limitations (post-ship)

- **`:agent/guardrails` round-trips through `pr-str` / `edn/read-string`.**
  YAML deserializes nested maps as `flatland.ordered.map`, which `pr-str`
  writes as `#ordered/map(…)` — unreadable by EDN. The dump flow plain-ifies
  these in `apply-agents!` before handing to `agents/upsert-agent!`. Any
  future code that builds its own apply path needs the same step.
- **Cross-deployment imports require the same `CONFIG_MASTER_KEY`** as the
  source. `crypto/re-encrypt` exists for the rotation case; no automation
  yet.
- **`.gitignore` was previously listed inside itself.** The dumps/ ignore
  was added at the same time but didn't propagate via the normal ignore
  mechanism. Subsequent commit (`f960ab0`) tracked `.gitignore` so the
  rule is now repo-wide.

## Commits

- `64366be` — dump-export/import flow + upstream patches (16 files,
  +1686/-63)
- `f960ab0` — track `.gitignore` (1 file, +48)
- `74d0f9e` — preserve `:config.value/pin-of-version` (3 files, +130/-22)

All on `release-v0.1-details`, pushed to origin.

## See also

- `docs/runbooks/dump-export-import-runbook.md` — operator-facing usage doc
- `server/src/digdir/import_export/dump.clj` — orchestration entry points
- `server/src/digdir/import_export/format/{yaml,jsonl}.clj` — primitives
- `server/test/digdir/import_export/dump_test.clj` — round-trip tests
