(ns digdir.api.routes
  "RAG API endpoints and handlers for the headless API.

  This namespace provides:
  - Skill execution (POST /api/skills/:id/execute)
  - Conversation management endpoints
  - API key management endpoints
  - User management endpoints
  - API key authentication middleware

  RAG query traffic goes through the MCP endpoint (POST /api/mcp),
  defined in digdir.api.routes.endpoints."
  (:require
   [digdir.api.context :as api-ctx]
   [digdir.api.routes.handlers :as handlers]
   [digdir.api.routes.conversations :as conversations]
   [digdir.api.routes.datasets :as datasets]
   [digdir.api.routes.endpoints.debug :as debug-handlers]
   [digdir.api.routes.endpoints :as endpoints]
   [digdir.api.util :as api-util]))

;; ===== Utilities =====

(defn compact-map
  "Remove keys with nil values from a map."
  [m]
  (api-util/compact-map m))

(defn api-error-body
  "Build a standardized error response body from an exception."
  [e]
  (api-util/api-error-body e))

(defn require-external-api-user-id!
  "Extract the external user ID from the request or throw if missing."
  [ring-req]
  (api-util/require-external-api-user-id! ring-req))

(defn require-api-conversation-owner!
  "Verify that the external user ID matches the conversation owner."
  [conversation convo-id external-user-id]
  (api-util/require-api-conversation-owner! conversation convo-id external-user-id))

(defn find-api-conversation!
  "Find a conversation by ID and verify ownership."
  [conn convo-id external-user-id]
  (api-util/find-api-conversation! conn convo-id external-user-id))

(defn camel->kebab-keyword
  "Convert a camelCase string to a kebab-case keyword."
  [k]
  (api-util/camel->kebab-keyword k))

(defn normalize-request-key
  "Normalize a request map key to kebab-case keyword."
  [k]
  (api-util/normalize-request-key k))

(defn normalize-request-keys
  "Deeply normalize map keys in a request body to kebab-case keywords."
  [x]
  (api-util/normalize-request-keys x))

(defn non-blank-value
  "Returns v if it is a non-blank string, else nil."
  [v]
  (api-util/non-blank-value v))

(defn param-value
  "Extract a parameter value by either keyword or string key."
  [params k]
  (api-util/param-value params k))

(defn dataset-ref-key
  "Returns a canonical string key for a dataset-ref."
  [dataset-ref]
  (api-ctx/dataset-ref-key dataset-ref))

(defn public-dataset-scope
  "Returns a public-safe version of a dataset scope."
  [dataset-ref]
  (api-ctx/public-dataset-scope dataset-ref))

(defn normalize-dataset-ref
  "Normalize a dataset-ref map to use standardized keys."
  [dataset-ref]
  (api-ctx/normalize-dataset-ref dataset-ref))

(defn request-dataset-ref
  "Extract a dataset-ref from request parameters."
  [params]
  (api-ctx/request-dataset-ref params))

(defn request-explicit-dataset-ref
  "Extract a dataset scope from request parameters, requiring explicit tenant and dataset-config-key."
  [params]
  (api-ctx/request-explicit-dataset-ref params))

(defn normalize-agent-ref
  "Normalize an agent-ref map to use standardized keys."
  [agent-ref]
  (api-ctx/normalize-agent-ref agent-ref))

(defn normalize-agent-refs
  "Normalize a collection of agent-ref maps."
  [agent-refs]
  (api-ctx/normalize-agent-refs agent-refs))

(defn request-agent-id
  "Extract an agent-id from request parameters."
  [params]
  (api-ctx/request-agent-id params))

(defn normalize-dataset-scopes!
  "Normalize a collection of dataset-scope maps."
  [dataset-scopes]
  (api-ctx/normalize-dataset-scopes! dataset-scopes))

(defn root-config-key-param
  "Returns the expected parameter name for a given config root's tenant-config-key."
  [root]
  (api-ctx/root-config-key-param root))

(defn normalize-allowed-config-key
  "Normalize one allowed-config-key map."
  [allowed-config-key]
  (api-ctx/normalize-allowed-config-key allowed-config-key))

(defn normalize-allowed-config-keys!
  "Normalize a collection of allowed-config-key maps."
  [allowed-config-keys]
  (api-ctx/normalize-allowed-config-keys! allowed-config-keys))

(defn request-config-key
  "Extract a tenant-config-key for a root from request parameters."
  [params root]
  (api-ctx/request-config-key params root))

(defn require-request-config-key!
  "Extract a tenant-config-key for a root from request parameters, or throw if missing."
  [params root message]
  (api-ctx/require-request-config-key! params root message))

(defn normalize-request-paths
  "Normalize a list of paths from request parameters."
  [params]
  (api-util/normalize-request-paths params))

(defn resolve-request-config-node!
  "Resolve a config node from request context and verify access."
  [ring-req conn opts]
  (api-ctx/resolve-request-config-node! ring-req conn opts))

(defn filter-dataset-scopes
  "Filter dataset scopes against an allowed set."
  [dataset-scopes allowed-dataset-scopes]
  (api-ctx/filter-dataset-scopes dataset-scopes allowed-dataset-scopes))

(defn available-dataset-scopes-for-error
  "Build a helpful error message with available dataset scopes."
  [effective-granted-scopes]
  (api-ctx/available-dataset-scopes-for-error effective-granted-scopes))

(defn select-request-dataset-ref!
  "Select a dataset-ref from request context, authorizing against API key grants."
  ([ring-req params]
   (api-ctx/select-request-dataset-ref! ring-req params))
  ([ring-req params opts]
   (api-ctx/select-request-dataset-ref! ring-req params opts)))

(defn resolve-request-dataset-context!
  "Resolve full dataset context (ref + config) from request parameters."
  ([ring-req params]
   (api-ctx/resolve-request-dataset-context! ring-req params))
  ([ring-req params opts]
   (api-ctx/resolve-request-dataset-context! ring-req params opts)))

(defn select-request-agent!
  "Select an agent-id from request parameters, verifying authorization."
  ([ring-req params]
   (api-ctx/select-request-agent! ring-req params))
  ([ring-req params opts]
   (api-ctx/select-request-agent! ring-req params opts)))

(defn load-agent!
  "Load agent record from database."
  [agent-id]
  (api-ctx/load-agent! agent-id))

(defn resolve-request-agent-policy!
  "Resolve agent policy for the current request."
  ([ring-req params]
   (api-ctx/resolve-request-agent-policy! ring-req params))
  ([ring-req params current-agent-id]
   (api-ctx/resolve-request-agent-policy! ring-req params current-agent-id)))

(defn validate-agent-refs!
  "Verify that agent-refs exist in the database."
  [agent-refs]
  (api-ctx/validate-agent-refs! agent-refs))

(defn require-api-key-scope!
  "Verify that the current API key has the required scope."
  [ring-req required-scope]
  (api-util/require-api-key-scope! ring-req required-scope))

(defn wrap-required-api-key-scope
  "Middleware to require a specific API key scope."
  [required-scope handler]
  (api-util/wrap-required-api-key-scope required-scope handler))

(defn normalize-pipeline-properties
  "Normalize pipeline properties to kebab-case keywords."
  [properties]
  (api-util/normalize-pipeline-properties properties))

;; build-rag-skill-params is re-exported for the Playground (see
;; digdir.playground.core/build-playground-skill-params); it remains
;; the canonical builder even though the HTTP /api/rag handler that
;; shared it was retired in Phase 0.
(defn build-rag-skill-params
  "Build skill parameters for RAG workflows.

   Two-arity preserves the legacy call shape; three-arity threads the
   resolved agent's :skill-params between dataset config and per-call
   params (highest wins: params > agent > config > defaults)."
  ([config params]
   (api-util/build-rag-skill-params config params))
  ([config params agent-skill-params]
   (api-util/build-rag-skill-params config params agent-skill-params)))

;; ===== API Key Management Handlers =====

(defn create-api-key-handler
  "Create a new API key for the authenticated user"
  [ring-req]
  (handlers/create-api-key-handler ring-req))

(defn list-api-keys-handler
  "List all API keys for the authenticated user"
  [ring-req]
  (handlers/list-api-keys-handler ring-req))

(defn revoke-api-key-handler
  "Revoke an API key"
  [ring-req]
  (handlers/revoke-api-key-handler ring-req))

(defn update-api-key-allowed-config-keys-handler
  "Replace the allowed config keys for an API key owned by the authenticated user."
  [ring-req]
  (handlers/update-api-key-allowed-config-keys-handler ring-req))

;; ===== Conversation Management Handlers =====

(defn parse-page-params
  "Extract page-size and page-index from ring request query params."
  [ring-req]
  (conversations/parse-page-params ring-req))

(defn format-conversation-list-response
  "Build a JSON Ring response from a paginated db result."
  [result]
  (conversations/format-conversation-list-response result))

(defn list-conversations-handler
  "List conversations with pagination support for the caller-provided external API user id."
  [ring-req]
  (conversations/list-conversations-handler ring-req))

(defn admin-list-conversations-handler
  "List all conversations across all users with pagination (admin-only, JWT-authenticated)."
  [ring-req]
  (conversations/admin-list-conversations-handler ring-req))

(defn create-conversation-handler
  "Create a new conversation"
  [ring-req]
  (conversations/create-conversation-handler ring-req))

(defn get-conversation-handler
  "Get a specific conversation with its messages"
  [ring-req]
  (conversations/get-conversation-handler ring-req))

(defn update-conversation-handler
  "Update a conversation's topic or metadata"
  [ring-req]
  (conversations/update-conversation-handler ring-req))

(defn delete-conversation-handler
  "Delete a conversation"
  [ring-req]
  (conversations/delete-conversation-handler ring-req))

;; ===== User Management Handlers =====

(defn list-users-handler
  "List all users (admin only)"
  [ring-req]
  (conversations/list-users-handler ring-req))

(defn create-user-handler
  "Create a new user with initial permissions (admin only)"
  [ring-req]
  (conversations/create-user-handler ring-req))

(defn get-user-handler
  "Get a specific user with their permissions (admin only)"
  [ring-req]
  (conversations/get-user-handler ring-req))

(defn update-user-permissions-handler
  "Update a user's permissions (admin only)"
  [ring-req]
  (conversations/update-user-permissions-handler ring-req))

(defn delete-user-handler
  "Delete a user (admin only)"
  [ring-req]
  (conversations/delete-user-handler ring-req))

(defn list-permissions-handler
  "List all available permissions (admin only)"
  [ring-req]
  (conversations/list-permissions-handler ring-req))

;; ===== Dataset Materialization Handlers =====

(defn authorize-config-request!
  "Verify that the user is authorized to access the requested config node."
  [ring-req conn path tenant action]
  (datasets/authorize-config-request! ring-req conn path tenant action))

(defn authorize-dataset-materialization-request!
  "Verify that the user is authorized to materialize the requested dataset."
  [ring-req conn opts]
  (datasets/authorize-dataset-materialization-request! ring-req conn opts))

(defn list-public-datasets-handler
  "List the datasets visible to the current API key through dataset scopes."
  [ring-req]
  (datasets/list-public-datasets-handler ring-req))

(defn get-public-dataset-handler
  "Get one visible dataset."
  [ring-req]
  (datasets/get-public-dataset-handler ring-req))

(defn list-config-nodes-handler
  "List config nodes reachable through the API key's allowed config keys for a tenant/root."
  [ring-req]
  (datasets/list-config-nodes-handler ring-req))

(defn resolve-runtime-config-handler
  "Resolve runtime config explicitly against a tenant runtime config key."
  [ring-req]
  (datasets/resolve-runtime-config-handler ring-req))

(defn resolve-dataset-config-handler
  "Resolve dataset config explicitly against a tenant dataset config key."
  [ring-req]
  (datasets/resolve-dataset-config-handler ring-req))

(defn dataset-materialization-record!
  "Create or update a dataset materialization record."
  [db dataset-id pipeline-id]
  (datasets/dataset-materialization-record! db dataset-id pipeline-id))

(defn required-console-materialization-context
  "Verify that the console request has the required context for materialization."
  [ring-req]
  (datasets/required-console-materialization-context ring-req))

(defn list-datasets-handler
  "List durable parent datasets for the operator console."
  [ring-req]
  (datasets/list-datasets-handler ring-req))

(defn get-dataset-handler
  "Get a durable parent dataset and its child materialization pipeline summaries."
  [ring-req]
  (datasets/get-dataset-handler ring-req))

(defn create-dataset-handler
  "Create a durable parent dataset for operator workflows."
  [ring-req]
  (datasets/create-dataset-handler ring-req))

(defn update-dataset-handler
  "Update mutable attributes on a durable parent dataset."
  [ring-req]
  (datasets/update-dataset-handler ring-req))

(defn list-pipelines-handler
  "List child materialization pipelines for a durable parent dataset."
  [ring-req]
  (datasets/list-pipelines-handler ring-req))

(defn get-pipeline-handler
  "Get a specific child materialization pipeline under a dataset."
  [ring-req]
  (datasets/get-pipeline-handler ring-req))

(defn create-pipeline-handler
  "Create a child materialization pipeline under an existing parent dataset."
  [ring-req]
  (datasets/create-pipeline-handler ring-req))

(defn update-pipeline-handler
  "Update an existing child materialization pipeline."
  [ring-req]
  (datasets/update-pipeline-handler ring-req))

(defn delete-pipeline-handler
  "Delete a child materialization pipeline."
  [ring-req]
  (datasets/delete-pipeline-handler ring-req))

(defn execute-pipeline-handler
  "Execute a child materialization pipeline asynchronously."
  [ring-req]
  (datasets/execute-pipeline-handler ring-req))

(defn list-executions-handler
  "List executions for a child materialization pipeline."
  [ring-req]
  (datasets/list-executions-handler ring-req))

;; ===== Debug Handlers =====

(defn edn-response
  "Helper to build an EDN Ring response."
  [data status]
  (debug-handlers/edn-response data status))

(defn blank->nil
  "Convert blank strings to nil."
  [x]
  (debug-handlers/blank->nil x))

(defn read-json-body
  "Parse JSON request body into a map with keyword keys."
  [ring-req]
  (debug-handlers/read-json-body ring-req))

(defn param-presence
  "Read param from either keyword or string key and report if it was provided."
  [params k]
  (debug-handlers/param-presence params k))

(defn explicit-param-or-default
  "Use explicitly provided param value (including explicit nil via blank), else default."
  [params k default]
  (debug-handlers/explicit-param-or-default params k default))

(defn debug-dataset-config-handler
  "Debug endpoint equivalent to bb dataset-config."
  [ring-req]
  (debug-handlers/debug-dataset-config-handler ring-req))

(defn debug-chunk-handler
  "Debug endpoint equivalent to bb chunk."
  [ring-req]
  (debug-handlers/debug-chunk-handler ring-req))

(defn debug-typesense-search-handler
  "Debug endpoint equivalent to bb ts-search."
  [ring-req]
  (debug-handlers/debug-typesense-search-handler ring-req))

(defn debug-typesense-get-handler
  "Debug endpoint equivalent to bb ts-get."
  [ring-req]
  (debug-handlers/debug-typesense-get-handler ring-req))

(defn debug-typesense-retrieve-handler
  "Debug endpoint equivalent to bb ts-retrieve. Disabled unless
   RAG_TS_RETRIEVE_ENABLED env var is truthy."
  [ring-req]
  (debug-handlers/debug-typesense-retrieve-handler ring-req))

;; ===== Skill Execution Handler =====

(defn execute-skill-handler
  "Execute a skill by ID."
  [ring-req]
  (endpoints/execute-skill-handler ring-req))

;; execute-skill-graph-handler and execute-graph-handler removed in Phase 0
;; alongside their /api/skill-graphs/:id/execute and /api/skill-graphs/execute
;; routes. The MCP server (Phase 3) replaces them.
;;
;; The listing shims - list-skills-handler, get-skill-handler,
;; get-skill-tools-handler, list-skill-graphs-handler, get-skill-graph-handler -
;; went with their routes in #350. Execute is the only /api/skills verb left.

;; ===== Auth Middleware =====

(defn wrap-api-key-auth
  "Middleware to validate API key from X-API-Key header"
  [handler]
  (endpoints/wrap-api-key-auth handler))

(defn debug-api-key-secret
  "Get the secret for debug API key authentication."
  []
  (endpoints/debug-api-key-secret))

(defn wrap-debug-api-key-auth
  "Middleware to validate debug API key from X-Debug-Api-Key header."
  [handler]
  (endpoints/wrap-debug-api-key-auth handler))

;; ===== Routes & Routers =====

(def api-routes
  "Routing table for the headless RAG API."
  endpoints/api-routes)

(def debug-routes
  "Routing table for debug endpoints."
  endpoints/debug-routes)

(def console-api-routes
  "Routing table for the operator console API."
  endpoints/console-api-routes)

(def api-router
  "Router for the headless RAG API."
  endpoints/api-router)

(def debug-router
  "Router for debug endpoints."
  endpoints/debug-router)

(def console-api-router
  "Router for the operator console API."
  endpoints/console-api-router)
