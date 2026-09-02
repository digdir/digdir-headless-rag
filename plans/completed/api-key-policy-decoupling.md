# Strategy: Decoupling API Keys from Access Policies

## 1. Goal
Decouple API keys from their access policies. Currently, an "API key" entity in the database holds both the secret key material and the authorization policy (dataset scopes, agent grants, config ceilings). This makes key rotation difficult because the entire policy must be recreated for a new key.

By introducing an `access-policy` entity, we can:
- Rotate keys by creating a new `api-key` pointing to the same `access-policy`.
- Share a single policy across multiple keys (e.g., for different environments or microservices).
- Maintain a stable identity for the "client" or "integrator" regardless of the current active key.

## 2. Current Architecture
API keys are stored as a single entity with the following key attributes:
- `:api-key/key`: The secret string.
- `:api-key/dataset-refs`, `:api-key/agent-refs`, `:api-key/config-ceilings`: The policy/grants.
- `:api-key/scopes`, `:api-key/clients`, `:api-key/skill-graphs`: Additional policy constraints.

## 3. Target Architecture

### 3.1 Schema Changes
We will introduce a new `access-policy` entity and update `api-key` to reference it.

**New Entity: `access-policy`**
- `:access-policy/id`: Stable unique identifier.
- `:access-policy/name`: Descriptive name.
- `:access-policy/dataset-refs`: Ref to dataset grant entities.
- `:access-policy/agent-refs`: Ref to agent grant entities.
- `:access-policy/config-ceilings`: Ref to config ceiling entities.
- `:access-policy/skill-graphs`: Vector of allowed skill graphs.
- `:access-policy/scopes`: Vector of keywords (`:query`, `:ingest`, `:admin`).
- `:access-policy/tenants`: List of accessible tenants.
- `:access-policy/clients`: List of associated client IDs.

**Updated Entity: `api-key`**
- `:api-key/policy`: Reference to an `access-policy`.
- `:api-key/key`, `:api-key/id`, etc.: Remain as they are.
- Policy attributes on `api-key` (e.g., `:api-key/dataset-refs`) will be marked as **deprecated** but kept for backward compatibility during the transition.

### 3.2 Core Logic Changes (`digdir.config.api-keys`)
- `validate-api-key`: Update to resolve policy from either the direct attributes (legacy) or the linked `access-policy`.
- `create-api-key!`: Update to support linking to an existing policy ID.
- `store-api-key`: Refactor to handle policy entity creation/linking.
- New functions for managing policies: `create-policy!`, `update-policy!`, `list-policies`.

## 4. Implementation Plan

### Phase 1: Schema Migration
1. Add `access-policy` attributes to `digdir.data.db`.
2. Add `:api-key/policy` reference attribute.
3. Implement a one-time migration to create policies for all existing API keys and link them.

### Phase 2: Core Logic Refactoring
1. Update `digdir.config.api-keys` to support the new entity.
2. Refactor `normalize-key-entity` and `pull-api-key-entity` to merge policy data from the linked entity.
3. Ensure `validate-api-key` continues to work seamlessly.

### Phase 3: API & Management
1. Update Operator Console handlers in `digdir.api.routes.handlers` to support policy management.
2. Update the "Create API Key" UI/API to allow selecting an existing policy or creating a new one.
3. Add a "Rotate Key" endpoint that generates a new key for an existing policy.

### Phase 4: Verification
1. Add unit tests for `access-policy` lifecycle.
2. Add integration tests for key rotation (Verify new key inherits old policy).
3. Verify backward compatibility with existing keys.

## 5. Migration Strategy (Datahike)
The migration will:
1. Find all `api-key` entities.
2. For each, create an `access-policy` with the same name and grants.
3. Link the `api-key` to the new policy.
4. This ensures no downtime or breaking changes for existing clients.
