Update:
  Request-facing tenant node identity is now `node_slug`, not `node_id`.
  The canonical docs for this are:
  - docs/architecture/explicit-node-resolution-schema-proposal.md
  - docs/architecture/explicit-node-resolution-gap-analysis.md
  - plans/in-progress/config-resolution-algorithm/execution-plan.md

Status Update (2026-03-31)
  Workstream status:
  - Workstream 1: complete
  - Workstream 2: complete
  - Workstream 3: complete
  - Workstream 4: complete for request-facing resolution; legacy profile selectors remain only in setup/materialization internals owned by Workstream 8
  - Workstream 5: complete
  - Workstream 6: complete
  - Workstream 7: complete
  - Workstream 8: complete for fresh-tenant bootstrap and pipeline CRUD; deployment-polish migration helpers still retain profile-based source reads where they operate on legacy source tenants
  - Workstream 9: complete for the cleanup-focused suites touched by the cutover
  - Workstream 10: complete, including the final removal of runtime/dataset profile fallback and migration-time config-grant conversion from the active export/import path

  Completed since the schema/gap-analysis phase:
  - schema support for `config.node/slug`, `config.node/system-managed?`, root compatibilities, and API-key ceilings
  - cutover dry-run reporting for slug gaps, missing canonical `default` nodes, compatibility broadening, and non-convertible grants
  - export/import support for compatibility rows and API-key ceilings
  - explicit `node_slug` resolution support in config DB/accessor layers
  - root-node compatibility validation for explicit runtime and dataset selection
  - API-key ceiling storage, normalization, and descendant authorization helpers
  - bootstrap seeding of root compatibility rows
  - live admin/API payloads now read and write `config-ceilings`
  - API-key auth middleware now exposes ceilings only
  - live route helpers no longer inject a default runtime profile during request-time resolution
  - route/auth tests now pin ceiling-only behavior
  - public request-time config routes now require explicit node slugs
  - public node discovery and explicit config resolve endpoints now exist
  - runtime and dataset accessors now require explicit node selection instead of profile-driven fallback
  - playground and diagnostics runtime lookups now default to the canonical `default` node slug instead of a profile
  - live API-key storage/validation no longer reads or writes config grants
  - dataset tree bootstrap now reuses the canonical tenant `default` dataset root instead of creating conflicting top-level defaults
  - admin diagnostics now surface node slugs, root compatibility rows, and legacy binding metadata separately
  - admin diagnostics now support root compatibility create/delete flows and slug-aware node editing
  - API-key admin UI now shows config ceilings, applies canonical default ceilings for new keys, and supports ceiling editing in place
  - setup-time platform defaults now resolve through the canonical `default` node instead of a bootstrap profile binding
  - fresh tenant platform/runtime bootstrap now seeds explicit canonical `default` nodes without profile bindings
  - fresh dataset bootstrap and pipeline CRUD now use explicit materialization node slugs only
  - fresh dataset trees attach root compatibility rows to the canonical dataset root and reuse the canonical tenant root for shared datasets
  - runtime and dataset resolution now require explicit tenant node selection end-to-end; binding-based compatibility fallback has been removed
  - system export/import and migration tests now operate on ceiling-only API keys
  - cleanup removed dormant config-grant schema, migration conversion/reporting code, and unused dataset-profile helpers
  - cutover-focused suites now pass:
    - `bb test-config`
    - `clj -M:test -n digdir.api.routes-test -n digdir.playground.ui-test -n digdir.playground.core-test`
    - `clj -M:test -n digdir.pipeline.core-test -n digdir.pipeline.integration-test`
    - `clj -M:test -n digdir.migration.system-test`
    - `clj -M:test -n digdir.config.api-keys-test`

  Remaining follow-on work:
  - none on the explicit-node cutover path; only optional schema/documentation cleanup remains

Model
  Concrete schema proposal:
  - docs/architecture/explicit-node-resolution-schema-proposal.md
  Gap analysis:
  - docs/architecture/explicit-node-resolution-gap-analysis.md
  Clean-cut implementation plan:
  - plans/in-progress/config-resolution-algorithm/execution-plan.md

  For each tenant x root:

  - there is exactly one tenant tree
  - that tree has a canonical root node with node_slug = "default"
  - clients always send the specific tenant node_slug to use
  - API keys carry 1..N ceiling nodes per root
  - values resolve by nearest ancestor wins
  - compatibility never selects a node; it only validates the requested node’s tree

  Each root also has a fixed system-managed chain above the tenant tree:

  requested tenant node
  -> tenant ancestors
  -> tenant tree root ("default")
  -> fixed root chain nodes
  -> stop

  Resolution Algorithm
  Given:

  - api_key
  - root in platform | runtime | dataset
  - node_slug required
  - path or paths
  - compatibility context:
      - platform: none
      - runtime: agent_id required, dataset_id optional
      - dataset: dataset_id required, pipeline_id optional

  Algorithm:

  1. Authenticate API key.
  2. Load requested node by (tenant, root, node_slug).
  3. Fail if node does not exist, is disabled, or belongs to another tenant/root.
  4. Load the API key’s ceiling nodes for that same (tenant, root).
  5. Authorize the request:
      - allowed if requested node is equal to or a descendant of at least one ceiling node
      - choose the nearest matching ceiling for trace purposes
      - otherwise return 403
  6. Find the tenant tree root for the requested node.
      - this must be the canonical tenant root node, normally default
  7. Validate compatibility against that tenant tree root:
      - platform: no compatibility check
      - runtime:
          - root must allow agent_id
          - if dataset_id provided, root must also allow that dataset
      - dataset:
          - root must allow dataset_id
          - if pipeline_id provided, root must also allow that pipeline
      - fail closed with 422 if incompatible
  8. Validate that each requested config path belongs to the requested root.
      - wrong-root path is 422
      - unknown definition is either:
          - 404 if you want strict mode
          - resolved as null/default with trace definition-not-found if you want permissive mode
  9. Build the effective resolution chain:
      - requested node
      - parent chain up to tenant root
      - fixed root chain nodes
  10. For each path, walk the chain in order.
      - first node with a value wins
      - if none found, return default/null with root-exhausted
  11. Return:
      - resolved value(s)
      - authorization trace
      - compatibility trace
      - resolution trace per path

  Pseudocode:

  resolve(api_key, root, node_slug, paths, context):
    node = load_node(api_key.tenant, root, node_slug)
    authorize_descendant_of_any_ceiling(api_key, root, node)

    tenant_root = find_tenant_root(node)
    validate_compatibility(tenant_root, root, context)

    chain = [node .. ancestors .. tenant_root .. fixed_root_chain(root)]

    for path in paths:
      def = load_definition(path)
      assert def.root == root
      winner = first node in chain with value(path)
      emit trace(path, requested=node_slug, winner, chain, matched_ceiling, tenant_root)

Execution Slice
  Initial implementation slice:
  - add schema support for system-managed nodes, root compatibility rows, and API-key ceilings
  - add a dry-run cutover report that detects slug gaps, root-compatibility broadening, and non-convertible grants
  - add focused tests that pin those migration-prep semantics before the resolver rewrite

  API Contract
  You need 4 API surfaces.

  1. API key definition

  {
    "id": "key_123",
    "tenant": "public-sector-knowledge",
    "ceilings": {
      "platform": ["default"],
      "runtime": ["default", "website-a"],
      "dataset": ["default"]
    },
    "enabled": true
  }

  Rules:

  - each root may have 0..N ceilings
  - total per root must be >= 1 if that root is enabled for the key
  - omitted ceilings default to ["default"] when creating a new key
  - all ceiling nodes must exist in that tenant/root
  - redundant ceilings may be normalized away if one is ancestor of another

  2. Node navigation

  Clients need a way to discover valid node slugs.

  GET /v1/config/{root}/nodes

  Response returns only nodes reachable from at least one ceiling:

  {
    "root": "runtime",
    "tenant": "public-sector-knowledge",
    "ceilings": ["default", "website-a"],
    "nodes": [
      {"node_slug": "default", "parent_slug": null},
      {"node_slug": "website", "parent_slug": "default"},
      {"node_slug": "website-a", "parent_slug": "website"},
      {"node_slug": "frontpage", "parent_slug": "website-a"}
    ]
  }

  3. Generic resolution

  POST /v1/config/resolve

  {
    "root": "runtime",
    "node_slug": "frontpage",
    "paths": [
      "skills.retrieval.top-k",
      "skills.rerank.top-k"
    ],
    "context": {
      "agent_id": "research-assistant",
      "dataset_id": "public-docs"
    }
  }

  4. Convenience domain endpoints

  POST /v1/runtime/config/resolve

  {
    "node_slug": "frontpage",
    "agent_id": "research-assistant",
    "dataset_id": "public-docs",
    "paths": ["skills.retrieval.top-k"]
  }

  POST /v1/dataset/config/resolve

  {
    "node_slug": "prod-a",
    "dataset_id": "public-docs",
    "pipeline_id": "digdir-docs",
    "paths": ["pipeline.chunks.minimum-length"]
  }

  Resolve Response
  Single response shape for generic and convenience endpoints:

  {
    "root": "runtime",
    "tenant": "public-sector-knowledge",
    "node_slug": "frontpage",
    "results": {
      "skills.retrieval.top-k": {
        "value": 40,
        "trace": {
          "requested_node_slug": "frontpage",
          "matched_ceiling": "website-a",
          "tenant_root_slug": "default",
          "resolution_chain": ["frontpage", "website-a", "website", "default", "_runtime_global"],
          "winning_node_slug": "website",
          "stop_reason": "matched"
        }
      }
    },
    "authorization": {
      "allowed": true,
      "matched_ceiling": "website-a"
    },
    "compatibility": {
      "checked_on_node_slug": "default",
      "status": "ok",
      "agent_id": "research-assistant",
      "dataset_id": "public-docs"
    }
  }

  Error Contract
  Use explicit failure reasons.

  403 forbidden_node_scope

  {
    "error": "forbidden_node_scope",
    "root": "runtime",
    "requested_node_slug": "frontpage",
    "allowed_ceiling_slugs": ["default", "website-a"]
  }

  422 incompatible_node_context

  {
    "error": "incompatible_node_context",
    "root": "runtime",
    "requested_node_slug": "frontpage",
    "checked_on_node_slug": "default",
    "required": {
      "agent_id": "research-assistant",
      "dataset_id": "public-docs"
    }
  }

  422 definition_root_mismatch

  {
    "error": "definition_root_mismatch",
    "path": "pipeline.chunks.minimum-length",
    "requested_root": "runtime",
    "definition_root": "dataset"
  }

  404 unknown_node

  {
    "error": "unknown_node",
    "root": "runtime",
    "node_slug": "frontpage-x"
  }

  Recommended Invariants

  - node_slug is always required in resolve requests.
  - Every tenant/root always has canonical node slug `default`.
  - API key ceilings are root-specific.
  - Compatibility metadata is stored only on the tenant root node unless you later need finer scoping.
  - No fallback node selection from compatibility metadata.
  - Every response includes both authorization and resolution traces.

  
