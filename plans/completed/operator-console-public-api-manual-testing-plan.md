# Manual Testing Plan: Operator Console and Public API Cutover

Status: Drafted on 2026-03-30.

## 1. Goal

Provide a full manual verification plan for a system administrator to confirm that the completed Operator Console/Public API cutover works correctly in a live environment.

This plan verifies:

- Operator Console branding and dataset-first navigation
- JWT-only protection of `/console-api/...`
- API-key-only protection of `/api/...`
- dataset parent / pipeline child behavior
- public read-only dataset and nested pipeline state
- operator-only pipeline execution
- API key creation, visibility, and revocation
- public RAG/retrieval/conversation flows
- operator-only user, permission, and conversation listing routes
- removal of the old flat/ambiguous operator route behavior

## 2. Scope And Environment Guidance

Use this plan in `staging` first.

Use it in `production` only for read-only checks unless you already have:

- a dedicated manual-test dataset
- a dedicated manual-test pipeline
- a dedicated manual-test API key
- a dedicated manual-test user account

Important limitation:

- dataset creation is implemented
- dataset deletion is not currently exposed through the Operator Console API surface

Because of that:

- perform dataset creation tests in a disposable environment when possible
- in production, prefer an existing sandbox dataset instead of creating a new one

## 3. Prerequisites

Prepare the following before starting:

- one administrator account that can log into the Operator Console
- one browser session with that admin account
- `curl` and `jq`
- one known-good, already queryable dataset/pipeline pair that is safe to query
- one dedicated external caller ID for public conversation tests
- one disposable email address or test mailbox for user-creation tests

Recommended optional prerequisites:

- one non-admin user account for `403 Admin access required` checks
- one known-good pipeline that is safe to execute during the test window

Suggested shell variables:

```bash
export BASE_URL="https://admin.staging.kunnskap.digdir.cloud"
export JWT_COOKIE='auth-token=REPLACE_ME'
export ADMIN_EMAIL='replace-me@example.com'
export EXTERNAL_USER_ID='manual-test-user-20260330'

# Existing known-good query target
export KNOWN_DATASET_ID='REPLACE_ME'
export KNOWN_TENANT='REPLACE_ME'
export KNOWN_ENV='REPLACE_ME'
export KNOWN_PIPELINE='REPLACE_ME'

# Staging-only temporary objects
export TEMP_SUFFIX='20260330'
export TEMP_DATASET_NAME="ZZ Manual Test ${TEMP_SUFFIX}"
export TEMP_TENANT='manual'
export TEMP_ENV='staging'
export TEMP_PIPELINE="smoke-${TEMP_SUFFIX}"
export TEMP_USER_EMAIL="manual-test+${TEMP_SUFFIX}@example.invalid"
```

During execution, capture these returned values:

- `TEMP_DATASET_ID`
- `TEMP_API_KEY_ID`
- `TEMP_API_KEY`
- `KNOWN_QUERY_API_KEY`
- `TEMP_USER_ID`
- `CONVERSATION_ID`
- `EXECUTION_ID`

## 4. Pass/Fail Rules

Treat the run as failed if any of the following occurs:

- a `/console-api/...` route succeeds without a valid admin JWT cookie
- a public `/api/...` route succeeds without a valid API key
- a public `/api/...` route accepts JWT alone as sufficient auth
- a pipeline can be created without an existing parent dataset
- a public route allows pipeline mutation or execution
- an old flat operator route under `/api/...` still returns operator/admin data
- the Operator Console no longer presents datasets as the parent resource

## 5. Test 1: Operator Console Branding And Dataset-First Navigation

1. Log into the web UI.
2. Confirm the application title/branding uses `Digdir Operator Console`.
3. Navigate to the datasets area.
4. Confirm datasets are presented as the top-level resource.
5. Confirm the dataset list text explains that datasets are the read targets and pipelines feed data into them.
6. Open the configuration area and confirm `API Keys` and `Permissions` are still available.

Expected result:

- branding uses `Operator Console`, not old admin naming
- datasets are the primary navigation entry for this surface
- pipelines are only managed from within a dataset context

## 6. Test 2: Auth Boundary Smoke Checks

### 2A. Operator routes require JWT

```bash
curl -i "$BASE_URL/console-api/datasets"
curl -i "$BASE_URL/console-api/datasets" -H "X-API-Key: invalid"
```

Expected result:

- both calls return `401`
- body includes `Unauthorized`

### 2B. Public routes require API keys

```bash
curl -i "$BASE_URL/api/datasets"
curl -i "$BASE_URL/api/datasets" --cookie "$JWT_COOKIE"
```

Expected result:

- both calls return `401`
- body indicates invalid or missing API key

### 2C. Old ambiguous routes do not behave like operator APIs anymore

Run these with any valid API key you already have, or after Test 5 creates one:

```bash
curl -i "$BASE_URL/api/pipelines" -H "X-API-Key: $TEMP_API_KEY"
curl -i "$BASE_URL/api/users" -H "X-API-Key: $TEMP_API_KEY"
curl -i "$BASE_URL/api/permissions" -H "X-API-Key: $TEMP_API_KEY"
```

Expected result:

- these routes do not return operator data
- `404` is the expected successful outcome for the cutover
- any `200` operator/admin payload is a failure

### 2D. Optional non-admin check

If you have a non-admin JWT session:

```bash
curl -i "$BASE_URL/console-api/datasets" --cookie 'auth-token=NON_ADMIN_TOKEN'
```

Expected result:

- returns `403`
- body includes `Admin access required`

## 7. Test 3: Create And Inspect A Parent Dataset

Run this in staging or another disposable environment.

Create the dataset through the Operator Console UI if possible. If you need a direct API check, use:

```bash
curl -sS -X POST "$BASE_URL/console-api/datasets" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE" \
  -d "{\"name\":\"$TEMP_DATASET_NAME\",\"description\":\"Manual test dataset\"}" | jq .
```

Capture the returned `datasetId` as `TEMP_DATASET_ID`.

Then verify:

```bash
curl -sS "$BASE_URL/console-api/datasets" \
  --cookie "$JWT_COOKIE" | jq .

curl -sS "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID" \
  --cookie "$JWT_COOKIE" | jq .
```

Expected result:

- `TEMP_DATASET_ID` exists and starts with `ds_`
- the dataset appears in the list and detail endpoints
- the dataset has `pipelineCount: 0`
- the UI detail page shows no child pipelines yet
- dataset creation happens before pipeline creation; there is no standalone top-level pipeline-creation flow

## 8. Test 4: Create, Update, And Delete A Child Pipeline Under The Dataset

Create the child pipeline from the dataset detail UI if possible. API equivalent:

```bash
curl -sS -X POST "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID/pipelines" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE" \
  -d "{
    \"tenant\":\"$TEMP_TENANT\",
    \"environment\":\"$TEMP_ENV\",
    \"pipelineName\":\"$TEMP_PIPELINE\",
    \"properties\":{
      \"name\":\"Manual Smoke Pipeline\",
      \"description\":\"Created by manual testing\",
      \"sourceType\":\"website\"
    }
  }" | jq .
```

Verify the nested operator routes:

```bash
curl -sS "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID" \
  --cookie "$JWT_COOKIE" | jq .

curl -sS "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID/pipelines?tenant=$TEMP_TENANT&environment=$TEMP_ENV" \
  --cookie "$JWT_COOKIE" | jq .

curl -sS "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE?tenant=$TEMP_TENANT&environment=$TEMP_ENV" \
  --cookie "$JWT_COOKIE" | jq .
```

Update the pipeline:

```bash
curl -sS -X PUT "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE?tenant=$TEMP_TENANT&environment=$TEMP_ENV" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE" \
  -d '{
    "properties":{
      "name":"Manual Smoke Pipeline Updated",
      "description":"Updated by manual testing",
      "sourceType":"folder"
    }
  }' | jq .
```

Expected result:

- the pipeline exists only under the parent dataset
- dataset detail now shows `pipelineCount: 1`
- UI shows tenant, environment, type, status, and actions on the child row
- update succeeds and the changed name/description/type are visible in UI and API

Negative check:

```bash
curl -i -X POST "$BASE_URL/console-api/datasets/missing-dataset/pipelines" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE" \
  -d "{
    \"tenant\":\"$TEMP_TENANT\",
    \"environment\":\"$TEMP_ENV\",
    \"pipelineName\":\"missing-parent\",
    \"properties\":{\"name\":\"Missing Parent\",\"sourceType\":\"website\"}
  }"
```

Expected result:

- request fails
- a pipeline cannot be created without an existing parent dataset

Delete the temporary pipeline after the remaining temp-key tests are complete:

```bash
curl -sS -X DELETE "$BASE_URL/console-api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE?tenant=$TEMP_TENANT&environment=$TEMP_ENV" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE" | jq .
```

Expected result:

- delete succeeds
- the pipeline disappears from the dataset detail view

## 9. Test 5: Create A Temporary API Key And Verify Public Read-Only Dataset State

Create an API key scoped only to the temporary pipeline:

```bash
curl -sS -X POST "$BASE_URL/console-api/api-keys" \
  -H "Content-Type: application/json" \
  --cookie "$JWT_COOKIE" \
  -d "{
    \"name\":\"Manual temp key $TEMP_SUFFIX\",
    \"dataset-refs\":[
      {\"tenant\":\"$TEMP_TENANT\",\"environment\":\"$TEMP_ENV\",\"pipeline\":\"$TEMP_PIPELINE\"}
    ]
  }" | tee /tmp/manual-temp-key.json | jq .
```

Capture:

- `TEMP_API_KEY_ID=$(jq -r '.\"api-key-id\"' /tmp/manual-temp-key.json)`
- `TEMP_API_KEY=$(jq -r '.\"api-key\"' /tmp/manual-temp-key.json)`

Verify public dataset and nested pipeline state:

```bash
curl -sS "$BASE_URL/api/datasets" \
  -H "X-API-Key: $TEMP_API_KEY" | jq .

curl -sS "$BASE_URL/api/datasets/$TEMP_DATASET_ID" \
  -H "X-API-Key: $TEMP_API_KEY" | jq .

curl -sS "$BASE_URL/api/datasets/$TEMP_DATASET_ID/pipelines" \
  -H "X-API-Key: $TEMP_API_KEY" | jq .

curl -sS "$BASE_URL/api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE" \
  -H "X-API-Key: $TEMP_API_KEY" | jq .
```

Expected result:

- the temp key sees the temp dataset and not unrelated datasets
- the public API uses the parent dataset ID returned by the Operator Console
- the child pipeline is nested under the dataset
- the pipeline payload includes `datasetId`, `status`, and `contexts`
- the context shows the compatibility `tenant/environment/pipeline` identity through `externalPipelineId`

Negative public-mutation checks:

```bash
curl -i -X POST "$BASE_URL/api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE/execute" \
  -H "X-API-Key: $TEMP_API_KEY"

curl -i -X DELETE "$BASE_URL/api/datasets/$TEMP_DATASET_ID/pipelines/$TEMP_PIPELINE" \
  -H "X-API-Key: $TEMP_API_KEY"
```

Expected result:

- public mutation/execution is not available
- `404` or another non-success response is acceptable
- any `200` or `202` response is a failure

Optional config-grant route check:

If you have a safe config-grant example for the environment, call:

```bash
curl -sS -X PUT "$BASE_URL/console-api/api-keys/$TEMP_API_KEY_ID/config-grants" \
  -H "Content-Type: application/json" \
  --cookie "$JWT_COOKIE" \
  -d '{"config-grants":[]}' | jq .
```

Expected result:

- route returns `200`
- response echoes the updated grant set for that key

## 10. Test 6: Known-Good Pipeline Execution Remains Operator-Only

Use a known-good pipeline that is safe to run in the target environment.

Start execution:

```bash
curl -i -X POST "$BASE_URL/console-api/datasets/$KNOWN_DATASET_ID/pipelines/$KNOWN_PIPELINE/execute?tenant=$KNOWN_TENANT&environment=$KNOWN_ENV" \
  -H "X-User-Email: $ADMIN_EMAIL" \
  --cookie "$JWT_COOKIE"
```

Expected result:

- returns `202`
- response contains an `executionId`

Capture `EXECUTION_ID`, then poll:

```bash
curl -sS "$BASE_URL/console-api/datasets/$KNOWN_DATASET_ID/pipelines/$KNOWN_PIPELINE/executions?tenant=$KNOWN_TENANT&environment=$KNOWN_ENV" \
  --cookie "$JWT_COOKIE" | jq .
```

Also inspect the dataset detail view in the UI.

Expected result:

- the new execution appears in API and UI history
- the child row shows running/completed/failed state correctly
- the dataset summary reflects recent activity
- `documentsProcessed`, `documentsFailed`, timestamps, and any error message are visible in execution history

## 11. Test 7: Public Query And Conversation Flows Still Work With API Keys

Use either:

- a dedicated known-good query API key, or
- a newly created key scoped to exactly one known-good pipeline

Store it in `KNOWN_QUERY_API_KEY`.

Verify the public dataset state:

```bash
curl -sS "$BASE_URL/api/datasets" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" | jq .
```

Expected result:

- the known-good dataset is visible
- status is reasonable for the live system, typically `ready` or `running`

Run retrieval:

```bash
curl -sS -X POST "$BASE_URL/api/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -d '{"query":"What does this dataset contain?","top_k":3}' | jq .
```

Expected result:

- returns `200`
- returns one or more chunks, or a valid empty result if the environment legitimately has no matching content

Run RAG:

```bash
curl -sS -X POST "$BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H "X-User-Id: $EXTERNAL_USER_ID" \
  -d '{"query":"Summarize this dataset in one sentence."}' | tee /tmp/manual-rag.json | jq .
```

Capture:

- `CONVERSATION_ID=$(jq -r '.\"conversation-id\"' /tmp/manual-rag.json)`

Expected result:

- returns `200`
- returns an answer
- returns a `conversation-id`
- response shape is still public/API-key-oriented and does not require JWT

Verify public conversation lifecycle:

```bash
curl -sS "$BASE_URL/api/conversations?page_size=10&page_index=0" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H "X-User-Id: $EXTERNAL_USER_ID" | jq .

curl -sS "$BASE_URL/api/conversations/$CONVERSATION_ID" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H "X-User-Id: $EXTERNAL_USER_ID" | jq .

curl -sS -X PUT "$BASE_URL/api/conversations/$CONVERSATION_ID" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H "X-User-Id: $EXTERNAL_USER_ID" \
  -d '{"title":"Manual test conversation"}' | jq .
```

Expected result:

- the conversation is listed for the same `X-User-Id`
- the conversation detail returns messages
- rename succeeds and is reflected on later reads

Cross-user isolation check:

```bash
curl -i "$BASE_URL/api/conversations/$CONVERSATION_ID" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H 'X-User-Id: different-external-user'
```

Expected result:

- the conversation is not exposed to another external caller ID
- any non-success response is acceptable
- any successful read is a failure

Delete the conversation:

```bash
curl -sS -X DELETE "$BASE_URL/api/conversations/$CONVERSATION_ID" \
  -H "X-API-Key: $KNOWN_QUERY_API_KEY" \
  -H "X-User-Id: $EXTERNAL_USER_ID" | jq .
```

Expected result:

- delete succeeds
- later reads of that conversation no longer succeed

## 12. Test 8: Operator Conversation Listing Still Works Separately From Public Conversation Scope

After Test 7 creates a conversation, verify the operator listing:

```bash
curl -sS "$BASE_URL/console-api/conversations?page_size=10&page_index=0" \
  --cookie "$JWT_COOKIE" | jq .
```

Expected result:

- route succeeds with JWT only
- no `X-User-Id` header is required
- the list includes conversations across users
- the conversation created in Test 7 is visible to the operator view

## 13. Test 9: Operator User And Permission Management

List permissions:

```bash
curl -sS "$BASE_URL/console-api/permissions" \
  --cookie "$JWT_COOKIE" | jq .
```

Choose one permission ID from the response and store it as `PERMISSION_ID`.

Create a temporary user:

```bash
curl -sS -X POST "$BASE_URL/console-api/users" \
  -H "Content-Type: application/json" \
  --cookie "$JWT_COOKIE" \
  -d "{\"email\":\"$TEMP_USER_EMAIL\",\"permissions\":[]}" | tee /tmp/manual-user.json | jq .
```

Capture:

- `TEMP_USER_ID=$(jq -r '.user.id' /tmp/manual-user.json)`

Verify list/detail:

```bash
curl -sS "$BASE_URL/console-api/users" \
  --cookie "$JWT_COOKIE" | jq .

curl -sS "$BASE_URL/console-api/users/$TEMP_USER_ID" \
  --cookie "$JWT_COOKIE" | jq .
```

Add one permission:

```bash
curl -sS -X PUT "$BASE_URL/console-api/users/$TEMP_USER_ID/permissions" \
  -H "Content-Type: application/json" \
  --cookie "$JWT_COOKIE" \
  -d "{\"add\":[\"$PERMISSION_ID\"],\"remove\":[]}" | jq .
```

Then remove it:

```bash
curl -sS -X PUT "$BASE_URL/console-api/users/$TEMP_USER_ID/permissions" \
  -H "Content-Type: application/json" \
  --cookie "$JWT_COOKIE" \
  -d "{\"add\":[],\"remove\":[\"$PERMISSION_ID\"]}" | jq .
```

Delete the user:

```bash
curl -sS -X DELETE "$BASE_URL/console-api/users/$TEMP_USER_ID" \
  --cookie "$JWT_COOKIE" | jq .
```

Expected result:

- `/console-api/permissions` returns a non-empty list
- the temporary user can be created, listed, updated, and deleted via `/console-api/...`
- the user payload reflects permission changes correctly

Operational note:

- if this environment sends invitations or triggers external identity side effects, use an approved test mailbox only

## 14. Test 10: API Key Revocation Stops Public Access Immediately

Revoke the temporary key from Test 5:

```bash
curl -sS -X POST "$BASE_URL/console-api/api-keys/$TEMP_API_KEY_ID/revoke" \
  --cookie "$JWT_COOKIE" | jq .
```

Then retry a public call with the same key:

```bash
curl -i "$BASE_URL/api/datasets" \
  -H "X-API-Key: $TEMP_API_KEY"
```

Expected result:

- revoke succeeds
- the old key immediately returns `401`

## 15. Cleanup

Perform all cleanup that the current surface supports:

- revoke temporary API keys
- delete the temporary child pipeline
- delete the temporary user
- delete the public test conversation

Record any temporary dataset that remains because dataset deletion is not currently exposed.

If run in staging, note the leftover dataset ID in the change log or handoff note so future operators know it is an intentional test artifact.

## 16. Final Sign-Off Checklist

Mark the run complete only when all of the following are true:

- Operator Console branding and dataset-first IA are visible
- `/console-api/...` works only with admin JWT
- `/api/...` works only with API keys
- old flat operator routes under `/api/...` no longer function as admin routes
- datasets behave as parent resources and pipelines behave as child resources
- public dataset/pipeline state is read-only and nested
- pipeline execution is Operator Console only
- API key create/revoke behavior is correct
- public RAG/retrieve/conversation flows still work
- operator conversation listing still works separately from public conversation scoping
- operator user and permission routes still work under `/console-api/...`
