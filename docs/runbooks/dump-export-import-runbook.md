# Dump Export/Import — Operator Runbook

A YAML/JSONL alternative to `bb migration-export` / `bb migration-import`. Same
data; folder of small files instead of one large JSON. Designed to be
human-reviewable in code review, with secrets encrypted at rest.

The JSON migration flow (`bb migration-export/import`) still works and runs in
parallel — pick whichever fits the task.

## When to use which

| Use the **dump** flow when… | Use the **migration** flow when… |
|---|---|
| You want to review changes in a PR | You want a single artifact to attach to a ticket |
| Diffing two snapshots matters | A single file is more convenient |
| Editing a value by hand before re-importing | You're piping through other JSON tooling |
| You're working with conversations and want them sliced by day | — |

## Quick reference

```sh
# Export current system to dumps/<UTC-timestamp>/
bb dump-export

# Export to an explicit folder
bb dump-export /path/to/my-dump

# Import a dump folder; defaults --on-conflict skip
bb dump-import /path/to/my-dump
bb dump-import /path/to/my-dump --on-conflict overwrite
```

`bb dump-export` always writes to an absolute path so output lands at the
project root regardless of where the JVM's CWD ends up.

## Folder layout

```
dumps/2026-05-08T22-49-36Z/
  manifest.yaml                              version + per-file counts
  00-config-defs.yaml                        keyed by :config-def/path
  01-config-nodes.yaml                       keyed by :config.node/id
  02-config-datasets.yaml                    keyed by :dataset/id
  03-config-dataset-pipelines.yaml           keyed by :dataset.pipeline/id
  04-config-values/
    <tenant>.yaml                            2-level nested: node-id → def-path → value
  05-users.yaml                              keyed by :user/email
  06-agents.yaml                             keyed by :agent/id
  07-folders.yaml                            keyed by :folder/id
  08-api-keys.yaml                           keyed by :api-key/id; digest only, never plaintext
  conversations/
    YYYY-MM-DD.jsonl                         one conversation per line; partitioned by created date
    undated.jsonl                            (only if any conversations lack :created)
```

Files with numeric prefixes are imported in lexical order so the registry
order is preserved. Per-tenant config-values and per-day conversations are
all loaded and merged into one envelope before the import phase runs.

## Secrets

API key plaintext is never exported. `08-api-keys.yaml` contains only the
one-way SHA-256 lookup digest and non-secret prefix/final-four display fields,
which preserve existing API-key authentication after restore without exposing
a bearer credential. Imports of older dumps that contain a decrypted
`:api-key/key` convert it to hashed storage instead of persisting plaintext.

Encrypted config values (defs with `:config-def/encrypted? true`) pass through
unchanged — they're already ciphertext in the DB.

**Implication:** encrypted configuration values remain tied to whichever
`CONFIG_MASTER_KEY` was active at export time. API-key digests do not depend
on that key. Cross-deployment imports of encrypted configuration still require
the same master key, or a key-rotation step using `crypto/re-encrypt`.

## End-to-end round-trip verification

Useful when you want to be sure a dump is loadable on a fresh DB, without
touching your active one.

```sh
# 1. Export current state.
bb dump-export

# 2. Spin up against a fresh local-db path. With file-mode Datahike,
#    just point DATAHIKE_FILE_PATH at a path that doesn't exist yet —
#    init-db creates it.
DATAHIKE_FILE_PATH=local-db/dh_roundtrip_$(date +%Y%m%d_%H%M%S) \
  bb dump-import /absolute/path/to/dumps/<timestamp>

# 3. Re-export from the freshly-imported DB.
DATAHIKE_FILE_PATH=local-db/dh_roundtrip_<same-as-above> \
  bb dump-export /tmp/verify

# 4. Compare counts.
diff <(grep -c '^' /absolute/path/to/dumps/<timestamp>/00-config-defs.yaml) \
     <(grep -c '^' /tmp/verify/00-config-defs.yaml)
```

For a true byte-for-byte round-trip you need `--on-conflict overwrite` so
that entities pre-seeded by `init-config-db!` (canonical defs, builtin
agents, default tenant nodes) are replaced with the dump's authoritative
values instead of being skipped:

```sh
DATAHIKE_FILE_PATH=local-db/dh_roundtrip_<...> \
  bb dump-import /absolute/path/to/dumps/<timestamp> --on-conflict overwrite
```

After that, every per-entity file diffs to **zero lines** except for the
api-key `key:` ciphertext: AES-256-GCM uses a fresh random IV per encrypt,
so the on-disk bytes differ even though the recovered plaintexts are
identical.

When done, just delete the throwaway `local-db/dh_roundtrip_*` folder.

## Known limitations

- **`:agent/guardrails` round-trips through `pr-str`/`edn/read-string`.** YAML
  deserializes nested maps as flatland.ordered.map, which `pr-str` writes as
  `#ordered/map(…)` — unreadable by EDN. The dump flow plain-ifies these
  before handing to `agents/upsert-agent!`. If you build a custom apply path,
  do the same.

## Bootstrap from a fresh DB

A fresh DB has no tenant root nodes, which used to make any namespace that
transitively required `digdir.rag.typesense` fail to load (the top-level
`(def ts-admin (make-ts-settings))` evaluation reaches `get-platform-value`,
which throws on missing tenant roots). `resolve-tenant-ts-settings` now
catches that specific `:kind :tenant-root-missing` error and returns nil, so
both `bb dump-import` and `bb migration-import` can target a brand-new DB
directly without running `bb setup` first.

## Source

- `server/src/digdir/import_export/dump.clj` — orchestration and entity specs
- `server/src/digdir/import_export/format/yaml.clj` — natural-key map writer/reader
- `server/src/digdir/import_export/format/jsonl.clj` — JSON Lines writer/reader
- `server/test/digdir/import_export/dump_test.clj` — round-trip tests per entity
- `bb.edn` — `dump-export` and `dump-import` task definitions
