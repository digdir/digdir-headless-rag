# Agents, Skills, and Datasets Cutover Runbook

> ## ⚠️ HISTORICAL — do not run the commands below (marked 2026-08-28)
>
> **This documents a cutover that has already happened.** It is kept as a record
> of how the migration was carried out, not as a procedure to follow.
>
> **Several commands below call endpoints that no longer exist.** `POST /api/rag`
> and `POST /api/retrieve` were removed in Phase 0 of the MCP migration; the
> query surface is now `POST /api/mcp`. Copy-pasting the `curl` blocks in
> sections 3 and 4 will fail.
>
> **They have deliberately NOT been rewritten to `/api/mcp`.** That endpoint
> takes a different request *shape* — protocol headers, a JSON-RPC method, the
> full envelope — so swapping the path would turn a `404` into a `400` and leave
> the reader in exactly the same place, while making this page look freshly
> maintained. A wrong command that looks current is worse than one that is
> visibly old. For a working call see
> [`server/docs/api/endpoints/mcp.md`](../../server/docs/api/endpoints/mcp.md).

This runbook recorded the migration checklist as a dry-run and cutover procedure
at the time it was performed.

It assumes the import/export routine is already in place:

- `bb migration-export`
- `bb migration-import`
- canonical `2.0` system exports from `digdir.import-export.system`
- agent-scoped conversations
- explicit dataset-ref public APIs

## Support Boundary

Supported export family:

- canonical system exports with `version: "2.0"` produced by `bb migration-export`

Unsupported export family as of 2026-04-13:

- pre-canonical exports
- mixed legacy exports that require an explicit transform step before import

Artifact inventory from the repo audit completed on 2026-04-13:

- retained system export artifacts under `server/state/migrations` are already `version: "2.0"`
- retained dry-run exports under `config/` are already `version: "2.0"`
- remaining pre-canonical examples exist only in historical plans/tests, not as supported operator artifacts

Rollback and disaster recovery policy:

- prefer repointing to the previous DB/table or restoring an infrastructure snapshot
- if restoring from exported artifacts, use canonical `2.0` exports only

## Terminology

- in the current deployment, `config` and `main` are logical domains over the same underlying Datahike table
- a "fresh target" means a fresh `ADH_POSTGRES_TABLE`, not separate fresh config and main databases
- config-domain entities include definitions, nodes, datasets, node-values, and config-scoped users
- main-domain entities include API keys, folders, and conversations

## Scope

Use this runbook for:

- local or staging dry runs against realistic data
- final production cutover prep
- rollback planning

It does not replace environment-specific infrastructure provisioning for Postgres or deploy orchestration.

## Prerequisites

Required environment variables:

- `ADH_POSTGRES_URL`
- `ADH_POSTGRES_USER`
- `ADH_POSTGRES_PWD`
- `CONFIG_MASTER_KEY`
- `JWT_SECRET`

Useful variables for verification:

- `ADH_POSTGRES_TABLE`
- `RAG_API_BASE_URL`
- `RAG_DEBUG_API_KEY`

Recommended local defaults:

```bash
export RAG_API_BASE_URL="${RAG_API_BASE_URL:-http://localhost:8081}"
export CUTOVER_TS="$(date -u +%Y%m%dT%H%M%SZ)"
export CUTOVER_DATE="$(date -u +%Y%m%d)"
export MIGRATION_DIR="$PWD/config"
export CANONICAL_EXPORT="$MIGRATION_DIR/system-export.${CUTOVER_DATE}.json"
mkdir -p "$MIGRATION_DIR"
```

## Readiness Checks

Run these before exporting:

```bash
bb agents-list
bb pipeline-config digdir altinn-docs-materialization altinn-docs
bb config-get pipeline.ui.name digdir dataset altinn-docs-materialization
bb config-get pipeline.source.type digdir dataset altinn-docs-materialization
```

These commands inspect the current config DB directly. They do not depend on the deprecated debug HTTP endpoints.

Adjust the tenant, config root, and config key for `bb config-get`, and the tenant, config key, and pipeline for `bb pipeline-config`, to combinations that actually exist in the target table. The values above are the currently validated local dev example.

If you also want to verify the local app process before later API smoke checks, run:

```bash
curl -sS "$RAG_API_BASE_URL/up"
```

Optional targeted verification:

```bash
cd server
clj -M:test -n digdir.import-export.system-test
clj -M:test -n digdir.api.routes-test
cd ..
```

If these fail, stop before exporting.

## Dry Run

### 1. Export current system state

```bash
bb migration-export "$CANONICAL_EXPORT"
shasum -a 256 "$CANONICAL_EXPORT" | tee "$CANONICAL_EXPORT.sha256"
```

The export command prints an EDN summary. Preserve both the JSON file and the checksum file.

### 2. Point the app at a fresh dry-run backing store

Use a fresh Datahike table in Postgres. The repo currently boots Datahike from:

- `ADH_POSTGRES_URL`
- `ADH_POSTGRES_USER`
- `ADH_POSTGRES_PWD`
- `ADH_POSTGRES_TABLE`

Example using a dedicated dry-run table:

```bash
export ADH_POSTGRES_TABLE="rag_cutover_dry_run_${CUTOVER_TS}"
```

If you are using a separate dry-run database instead, switch `ADH_POSTGRES_URL` instead of or in addition to the table name.

Start the application once against the fresh target table so Datahike can create the store and schema if your import path does not already do so.

### 3. Import canonical data into the fresh target table

```bash
bb migration-import "$CANONICAL_EXPORT" --on-conflict overwrite
```

Expected result:

- definitions imported
- values imported
- agents upserted
- folders imported
- API keys imported
- conversations imported

### 4. Verify imported state

Run the same config-level checks again against the fresh target table:

```bash
bb agents-list
bb pipeline-config digdir altinn-docs-materialization altinn-docs
bb config-get pipeline.ui.name digdir dataset altinn-docs-materialization
bb config-get pipeline.source.type digdir dataset altinn-docs-materialization
```

If you will continue into API smoke checks, also confirm the app is serving the fresh target table:

```bash
curl -sS "$RAG_API_BASE_URL/up"
```

If these drift unexpectedly from the source environment, stop and inspect before smoke-testing the API.

Transient import failure note:

- during validation, one fresh-table import failed once with a transport-level Postgres/Datahike socket error (`Connection reset`)
- clean reruns against new fresh tables completed successfully with the same artifact and the same importer code path
- treat these as backend I/O failures first, not as proof of a bad export artifact
- if this happens:
  - capture the EDN error report path
  - inspect the fresh target table to see how far the import got
  - rerun the import on a new fresh table first
  - if needed, rerun on the partially populated table with `--on-conflict overwrite`

## Smoke Checks

These should be executed against the fresh imported environment.

### 1. List agents

```bash
bb agents-list
```

Expected:

- enabled builtin agents are present
- default skill graph and allowed dataset refs look plausible

### 2. Create an API key

This path is JWT-protected. Use the Admin UI with your normal authenticated session:

- open the API key management screen
- create a key with:
  - one enabled agent
  - one explicit dataset ref such as `altinn-docs / dev / assistant`

Record the generated API key value for the remaining smoke checks:

```bash
export SMOKE_API_KEY="rag_..."
```

### 3. Run retrieval against an explicit dataset ref

```bash
curl -sS -X POST "$RAG_API_BASE_URL/api/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $SMOKE_API_KEY" \
  -d '{
    "query": "Hva er Altinn?",
    "tenant": "digdir",
    "dataset-config-key": "public-docs"
  }' | jq .
```

Expected:

- HTTP 200
- chunk results returned
- request uses canonical dataset refs (`tenant` plus `dataset-config-key`)

### 4. Run chat against an agent

```bash
curl -sS -X POST "$RAG_API_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $SMOKE_API_KEY" \
  -H "X-User-Id: smoke-user-1" \
  -d '{
    "query": "Hva er Altinn?",
    "agent-id": "builtin/agent-rag-agent",
    "tenant": "digdir",
    "dataset-config-key": "public-docs",
    "runtime-config-key": "default"
  }' | jq .
```

Expected:

- HTTP 200
- `conversation-id` returned
- response generated through the selected agent
- `/api/rag` currently requires both `X-User-Id` and `runtime-config-key`

### 5. Continue the same conversation

```bash
export SMOKE_CONVERSATION_ID="..."

curl -sS -X POST "$RAG_API_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $SMOKE_API_KEY" \
  -H "X-User-Id: smoke-user-1" \
  -d "{
    \"query\": \"Oppsummer kort.\",
    \"conversation-id\": \"$SMOKE_CONVERSATION_ID\",
    \"agent-id\": \"builtin/agent-rag-agent\",
    \"tenant\": \"digdir\",
    \"dataset-config-key\": \"public-docs\",
    \"runtime-config-key\": \"default\"
  }" | jq .
```

Expected:

- HTTP 200
- same conversation continues
- agent identity remains stable across turns

### 6. Open the playground and verify agent-scoped persistence

Manual checks:

- start a new playground conversation
- choose an agent first
- confirm visible datasets reflect the agent grant set
- send a message
- reload and reopen the conversation
- confirm the conversation label remains agent-first

## Cutover Procedure

Production cutover follows the same sequence with stricter controls:

1. Freeze writes.
2. Export current state.
3. Checksum and archive the canonical export.
4. Provision fresh target DB/table.
5. Deploy the application pointed at the fresh target.
6. Import canonical data.
7. Run the smoke checks above.
8. Unfreeze writes.

Recommended archive layout:

```text
config/
  system-export.<YYYYMMDD>.json
  system-export.<YYYYMMDD>.json.sha256
```

## Rollback

If smoke checks fail after import:

1. Stop the application pointed at the fresh target.
2. Repoint the app to the original DB/table or restore the original snapshot.
3. Redeploy the previous application build.
4. Verify:
   - legacy chat flow
   - legacy retrieval flow
   - admin login
   - API key validation

Do not attempt partial mixed-mode rollback against the newly imported target.

## Notes

- The migration tasks operate against whatever backing store the current environment points to. Double-check `ADH_POSTGRES_URL` and `ADH_POSTGRES_TABLE` before import.
- The validated local smoke path on 2026-04-11 used:
  - `tenant=digdir`
  - `dataset-config-key=public-docs`
  - `agent-id=builtin/agent-rag-agent`
  - `runtime-config-key=default`
  - `X-User-Id: smoke-user-1`
- If a retained historical export is not already canonical `2.0`, treat it as unsupported for current operations rather than expecting the live repo to normalize it.
