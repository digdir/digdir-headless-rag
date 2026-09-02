(ns digdir.api.routes.endpoints
   "Debug endpoints, skill endpoints, middleware, and router wiring for the API entrypoint."
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [taoensso.telemere :as t]
   [digdir.api.context :as api-ctx]
   [digdir.api.util :refer [api-error-body
                            
                            read-json-body
                            request-body-params
                            request-path-params
                            wrap-required-api-key-scope]]
   [digdir.api.routes.conversations :refer [list-conversations-handler
                                            admin-list-conversations-handler
                                            create-conversation-handler
                                            get-conversation-handler
                                            update-conversation-handler
                                            delete-conversation-handler
                                            list-users-handler
                                            create-user-handler
                                            get-user-handler
                                            update-user-permissions-handler
                                            delete-user-handler
                                            list-permissions-handler
                                            
                                            ]]
   [digdir.api.routes.datasets :refer [list-config-nodes-handler
                                       list-public-datasets-handler
                                       get-public-dataset-handler
                                       resolve-runtime-config-handler
                                       resolve-dataset-config-handler
                                       list-datasets-handler
                                       get-dataset-handler
                                       create-dataset-handler
                                       update-dataset-handler
                                       list-pipelines-handler
                                       get-pipeline-handler
                                       create-pipeline-handler
                                       update-pipeline-handler
                                       delete-pipeline-handler
                                       execute-pipeline-handler
                                       list-executions-handler]]
   [digdir.api.routes.endpoints.debug :refer [edn-response
                                              
                                              
                                              
                                              debug-dataset-config-handler
                                              debug-chunk-handler
                                              debug-typesense-search-handler
                                              debug-typesense-get-handler
                                              debug-typesense-retrieve-handler
                                              debug-query-planner-handler
                                              debug-config-refresh-handler
                                              debug-last-invocation-handler
                                              debug-agent-resolution-handler]]
   [digdir.api.routes.endpoints.openai-compat :refer [list-models-handler
                                                       chat-completions-handler]]
   [digdir.api.rate-limit-api :as rate-limit-api]
   [digdir.api.routes.handlers :refer [create-api-key-handler
                                       list-api-keys-handler
                                       list-access-policies-handler
                                       revoke-api-key-handler
                                       rotate-api-key-handler
                                       update-api-key-allowed-config-keys-handler]]
   [digdir.config.api-keys :as api-keys]
   [digdir.data.db :as db]
   [digdir.mcp.transport :as mcp]
   [digdir.skills.api :as skills-api]
   [reitit.coercion :as coercion]
   [reitit.coercion.malli]
   [ring.util.response :as res]
   [reitit.ring :as ring]
   [reitit.ring.coercion]))

(defn- coercion-error-status
  "Map a coercion ex-info :type to the HTTP status it should produce, or nil
   if the exception is not a coercion error."
  [ex-data]
  (case (:type ex-data)
    ::coercion/request-coercion 400
    ::coercion/response-coercion 500
    nil))

(defn- json-error-response
  [status body]
  (-> (res/response (json/generate-string body))
      (res/status status)
      (res/content-type "application/json")))

(defn- coercion-error-message
  "The human-facing message, chosen by DIRECTION rather than hardcoded.

   `Request validation failed` was previously returned for both directions, so a
   caller who sent a perfectly valid request was told they had sent something
   wrong when the SERVER had emitted something wrong (#438). The direction is
   already known here — it is the same `:type` that picks the status above."
  [ex-data]
  (case (:type ex-data)
    ::coercion/request-coercion "Request validation failed"
    ::coercion/response-coercion "Response validation failed"
    "Validation failed"))

(defn- coercion-error-details
  "Encoded coercion error, with `:value` withheld on the RESPONSE side (#438).

   `:value` is the payload that failed. On the REQUEST side that is what the
   caller just sent, so echoing it is both harmless and useful. On the RESPONSE
   side it is the HANDLER'S OUTPUT, and returning it hands whoever triggered the
   failure a body they were never entitled to — a disclosure surface that opens
   precisely when a schema is wrong, which is the case nobody plans for.

   THE DISCRIMINATOR IS DIRECTION, NOT ENVIRONMENT. That matters because this
   codebase has no `am I in production` signal for the handler to read, so an
   environment gate would have meant inventing one — and a rule that is wrong
   only in production is a rule nobody exercises before it matters.

   The withheld value is not lost: `:humanized` still names the offending keys,
   and the full value is logged server-side, where a developer can see it and a
   caller cannot."
  [data]
  (let [encoded (coercion/encode-error data)]
    (if (= ::coercion/response-coercion (:type data))
      (do (log/warn "Response failed its declared schema"
                    {:in (:in encoded)
                     :humanized (:humanized encoded)
                     :value (:value encoded)})
          (dissoc encoded :value))
      encoded)))

(defn- json-coercion-error-response
  [status data]
  (json-error-response status {:error (coercion-error-message data)
                               :details (coercion-error-details data)}))

(defn- edn-coercion-error-response
  [status data]
  (edn-response {:error (coercion-error-message data)
                 :details (coercion-error-details data)}
                status))

(defn- make-coerce-exceptions-middleware
  "Factory for the coerce-exceptions middleware. `error-response-fn` builds
   the response body for a given status + coercion ex-data."
  [middleware-name error-response-fn]
  (let [handle (fn handle-coercion-exception [e respond raise]
                 (let [data (ex-data e)]
                   (if-let [status (coercion-error-status data)]
                     (respond (error-response-fn status data))
                     (raise e))))]
    {:name middleware-name
     :compile (fn [{:keys [coercion parameters responses]} _]
                (when (and coercion (or parameters responses))
                  (fn [handler]
                    (fn
                      ([request]
                       (try
                         (handler request)
                         (catch Exception e
                           (handle e identity #(throw %)))))
                      ([request respond raise]
                       (try
                         (handler request respond #(handle % respond raise))
                         (catch Exception e
                           (handle e respond raise))))))))}))

(def api-coerce-exceptions-middleware
  (make-coerce-exceptions-middleware ::api-coerce-exceptions
                                     json-coercion-error-response))

(def debug-coerce-exceptions-middleware
  (make-coerce-exceptions-middleware ::debug-coerce-exceptions
                                     edn-coercion-error-response))

(defn- json-body-parse-error-response []
  (json-error-response 400 {:error "Invalid JSON body"}))

(defn- parse-json-body-or-error
  "Parse the JSON body into normalized :body-params, returning either
   `{:body-params m}` on success or `{:error-response r}` on parse failure.
   Other exceptions propagate."
  [request]
  (try
    {:body-params (read-json-body request)}
    (catch clojure.lang.ExceptionInfo e
      (if (= "Invalid JSON body" (.getMessage e))
        {:error-response (json-body-parse-error-response)}
        (throw e)))))

(def api-json-body-middleware
  "Parse the JSON request body into normalized :body-params before reitit
   coercion runs. Returns a 400 directly when the body is not valid JSON,
   so a malformed body never reaches the coerce-exceptions middleware as an
   uncaught exception."
  {:name ::api-json-body
   :compile (fn [{:keys [parameters]} _]
              (when (:body parameters)
                (fn [handler]
                  (fn
                    ([request]
                     (let [{:keys [body-params error-response]} (parse-json-body-or-error request)]
                       (or error-response
                           (handler (assoc request :body-params body-params)))))
                    ([request respond raise]
                     (let [{:keys [body-params error-response]} (parse-json-body-or-error request)]
                       (if error-response
                         (respond error-response)
                         (handler (assoc request :body-params body-params) respond raise))))))))})

;; Note on strip-extras behavior:
;;   reitit.coercion.malli/default-options sets `:compile mu/closed-schema`
;;   together with `:strip-extra-keys true`. The net effect is that any field
;;   in a request body / query / path that is not declared in the schema is
;;   silently removed before the handler runs — no validation error is raised,
;;   the field simply disappears. When adding a new handler-read field, make
;;   sure it is also declared in the corresponding schema, otherwise the
;;   handler will see nil for it in production. See plans/in-progress
;;   /malli-api-adoption-plan.md, Phase 1 Hardening, Issue 3.
(def log-discarded-request-fields-middleware
  "Log request-body fields that coercion discarded. Measurement only — it
   changes no status code, no body, and no behaviour of any kind.

   Why it exists: coercion strips any key the schema does not declare, before
   the handler runs, so a caller who sends a wrong or outdated field name gets
   a 2xx and a request that quietly did less than they asked for (#172, #174).
   Nothing in a log points at that today, which is why we cannot tell whether
   real callers are hitting it. This makes the invisible case visible without
   deciding what to do about it — that decision waits on what this reports.

   NEVER LOG THE VALUES. Only key names. Request bodies on this API carry API
   keys, JWT secrets and config values; the whole point of the diff is the
   NAMES that were dropped, and adding the values would turn a measurement into
   a credential leak.

   Body only, deliberately. Query strings routinely carry keys we never
   declared — cache-busters, analytics parameters — so the same diff there
   would be mostly noise, and the harm in #172 was a body field.

   Runs inside `coerce-request-middleware` (last in the vector = innermost) so
   both the pre-coercion `:body-params` and the coerced `[:parameters :body]`
   are visible."
  {:name ::log-discarded-request-fields
   :wrap (fn [handler]
           (fn [request]
             (let [sent (set (keys (:body-params request)))
                   kept (set (keys (get-in request [:parameters :body])))
                   discarded (set/difference sent kept)]
               (when (seq discarded)
                 ;; Opts-map form, NOT the `[::id {..}]` vector form (#183).
                 ;; `(t/log! :warn [::id {..}])` renders BOTH the id and the map
                 ;; into the message string and leaves :id and :data nil on the
                 ;; signal, so the event is greppable prose rather than data.
                 ;; This one exists to be COUNTED — #174 needs to know how many
                 ;; callers send fields we discard, and which — so it must be
                 ;; queryable. Same shape as digdir.llm.marker.
                 (t/log! {:level :warn
                          :id ::request-fields-discarded
                          :data {:uri (:uri request)
                                 :method (:request-method request)
                                 :discarded-field-names (vec (sort discarded))}
                          :msg (str "Not an error: request coercion removed these fields "
                                    "before the handler ran. This line is a measurement "
                                    "for #174 — it tells us a caller sent field names we do "
                                    "not accept. Check them against the endpoint's schema: "
                                    "either the caller is wrong, or our docs are (#172). "
                                    "NOTE when counting: this is logged BEFORE the handler, "
                                    "so it does not imply the request went on to succeed — "
                                    "a rejected request with a stripped field is measured "
                                    "too.")}))
               (handler request))))})

(def response-coercion-enabled?
  "Whether declared `:responses` schemas are ENFORCED (#432 stage 2).

   OPT-IN, DEFAULT OFF, AND THAT DEFAULT IS THE POINT. Production is unchanged
   unless somebody sets `DIGDIR_RESPONSE_COERCION=true`, so this cannot alter
   deployed behaviour by being merged. Dev and CI set it; nothing else does.

   WHY DEV AND CI FIRST, RATHER THAN EVERYWHERE. The ten schemas declared in
   stage 1 are KEY-SET verified and TYPE-UNVERIFIED: they came from key sets the
   `*_shape_test` family establishes against real handlers, but those tests
   assert key sets and nothing else, while the schemas carry 38 type predicates
   that have never been checked against handler output. So the first enforcement
   failure is at least as likely to be a wrong `string?` here as a misbehaving
   handler — which is a fine thing to discover in CI and a poor thing to
   discover in production.

   Running it in CI is what converts those 38 predicates from assertions into
   evidence, one run at a time. A schema earns production enforcement when a
   test exercises its handler and asserts TYPES rather than key sets; until
   then, enforcing it in production would punish the schema author for a
   modelling gap.

   Read once at load: the router is built once at startup, so a live predicate
   would buy nothing and would hide when the decision was actually taken.

   ⚠️ ENABLING THIS TODAY DOES NOT WORK, AND THAT IS NOT A CONFIGURATION
   PROBLEM — SEE #440. reitit validates the response body BEFORE
   serialization, and this codebase serializes INSIDE its handlers: 76
   occurrences of `json/generate-string` in `server/src`, 60 in the
   `res/response (json/generate-string …)` form, 59 of those in `api/routes/`.
   So a handler returns a String where the declared schema describes a map,
   and coercion fails as an invalid type no matter how correct the schema
   is. Measured: the same valid data passes as a map and fails as its own
   serialized string.

   Turning this on against the current convention produces 16 test failures,
   all of them attributable to the flag — established by set-differencing the
   failure lines of a flag-off and a flag-on run of the same code, not by
   comparing totals.

   THE SWITCH IS KEPT DELIBERATELY RATHER THAN DELETED: it is verified, it is
   wired to nothing, and whoever takes #440 would otherwise rebuild it after
   rediscovering why it is needed. It is not a supported setting until the
   response convention changes."
  (= "true" (System/getenv "DIGDIR_RESPONSE_COERCION")))

(def api-router-options
  {:data {:coercion reitit.coercion.malli/coercion
          :middleware (cond-> [api-coerce-exceptions-middleware
                               api-json-body-middleware
                               reitit.ring.coercion/coerce-request-middleware
                               log-discarded-request-fields-middleware]
                        ;; Appended, so it sits INSIDE the exceptions middleware
                        ;; and the coercion failure it raises propagates outward
                        ;; through it — which is what turns the raised exception
                        ;; into the structured 500 rather than a stack trace.
                        response-coercion-enabled?
                        (conj reitit.ring.coercion/coerce-response-middleware))}})

(def debug-router-options
  {:data {:coercion reitit.coercion.malli/coercion
          :middleware [debug-coerce-exceptions-middleware
                       reitit.ring.coercion/coerce-request-middleware]}})

(def conversation-pagination-query-parameters
  [:map
   [:page_size {:optional true} [:int {:min 1 :max 100}]]
   [:page_index {:optional true} [:int {:min 0}]]
   [:tags {:optional true} [:or string? [:vector [:string {:min 1}]]]]])

(def conversation-detail-query-parameters
  [:map
   [:include_diagnostics {:optional true} boolean?]])

(def conversation-create-body-parameters
  [:map
   [:agent-id {:optional true} string?]
   [:title {:optional true} string?]
   [:filter-value {:optional true} any?]
   [:tags {:optional true} [:vector [:string {:min 1}]]]])

(def conversation-update-body-parameters
  [:map
   [:title {:optional true} string?]
   [:tags {:optional true} [:vector [:string {:min 1}]]]])

(def dataset-ref-body-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]])

;; rag-body-parameters, retrieve-body-parameters, and the retrieval-filter*
;; schemas were retired in Phase 0 along with the /api/rag and /api/retrieve
;; routes they validated.

(def runtime-config-resolve-body-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:runtime-config-key [:string {:min 1}]]
   [:agent-id {:optional true} string?]
   [:agent {:optional true} string?]
   [:dataset-id {:optional true} string?]
   [:dataset-config-key {:optional true} string?]
   [:dataset-ref {:optional true} dataset-ref-body-parameters]
   [:path {:optional true} [:string {:min 1}]]
   [:paths {:optional true} [:vector [:string {:min 1}]]]])

(def dataset-config-resolve-body-parameters
  [:map
   [:tenant {:optional true} string?]
   [:dataset-id {:optional true} string?]
   [:dataset-config-key {:optional true} string?]
   [:dataset-ref {:optional true} dataset-ref-body-parameters]
   [:path {:optional true} [:string {:min 1}]]
   [:paths {:optional true} [:vector [:string {:min 1}]]]])

(def skill-execution-body-parameters
  [:map
   [:inputs any?]
   [:parameters {:optional true} any?]
   [:tenant {:optional true} string?]
   [:dataset-config-key {:optional true} string?]
   [:dataset-ref {:optional true} dataset-ref-body-parameters]])

;; skill-graph-execution-body-parameters and custom-graph-execution-body-
;; parameters were retired in Phase 0 along with the
;; /api/skill-graphs/:id/execute and /api/skill-graphs/execute routes.

(def allowed-config-key-body-parameters
  [:map
   [:root [:or keyword? string?]]
   [:tenant [:string {:min 1}]]
   [:node-id {:optional true} [:string {:min 1}]]
   [:tenant-config-key {:optional true} [:string {:min 1}]]
   [:runtime-config-key {:optional true} [:string {:min 1}]]
   [:dataset-config-key {:optional true} [:string {:min 1}]]])

(def api-key-path-parameters
  [:map
   [:key-id [:string {:min 1}]]])

(def rejected-create-api-key-fields
  "Field names declared in `create-api-key-body-parameters` solely so a request
   carrying one is answered with a 400 instead of a silent 201. They are NOT
   accepted fields, and the doc-drift test excludes them when comparing the
   schema against what api-keys.md and openapi.yaml advertise (#172)."
  #{:skill-graphs :scopes})

(def create-api-key-body-parameters
  "Accepted body of POST /console-api/api-keys.

   Coercion STRIPS unknown keys before the handler runs, so a wrong field name
   is not merely tolerated — it is deleted, and the request succeeds with a 201
   and a key that quietly lacks whatever the caller thought they were granting.
   Measured on two fields at once (#172): `skill-graphs`, renamed to `modes` in
   #167, and `scopes`, which this endpoint has never read despite being
   documented as optional.

   Note what does NOT fix this: `{:closed true}` is inert here, because the
   decoder strips the extra key before the closed check ever sees it — measured
   too. The only way to answer a stale field with a 400 instead of a silent 201
   is to DECLARE it and let it fail validation, which is what the two entries at
   the bottom do. Their :error/message reaches the caller in `details.humanized`
   of the coercion error, so the rejection names the replacement rather than
   just refusing."
  [:map
   [:name [:string {:min 1}]]
   [:policy-id {:optional true} string?]
   [:dataset-scopes {:optional true} [:vector dataset-ref-body-parameters]]
   [:client-id {:optional true} string?]
   [:agent-refs {:optional true} [:vector [:string {:min 1}]]]
   [:allowed-config-keys {:optional true} [:vector allowed-config-key-body-parameters]]
   ;; `modes` on the wire, stored as :api-key/skill-graphs (#167)
   [:modes {:optional true} [:vector [:string {:min 1}]]]
   ;; Declared only to be rejected — see the docstring. Both were advertised in
   ;; api-keys.md and neither did anything.
   [:skill-graphs {:optional true}
    [:fn {:error/message "`skill-graphs` was renamed to `modes` (#167); send `modes` instead"}
     (fn [_] false)]]
   [:scopes {:optional true}
    [:fn {:error/message "`scopes` is not settable on this endpoint; a new key gets the default `query` scope"}
     (fn [_] false)]]])

(def update-api-key-allowed-config-keys-body-parameters
  [:map
   [:allowed-config-keys [:vector allowed-config-key-body-parameters]]])

(def user-id-path-parameters
  [:map
   [:id [:string {:min 1}]]])

(def create-user-body-parameters
  [:map
   [:email [:string {:min 1}]]
   [:permissions {:optional true} [:vector [:string {:min 1}]]]])

(def update-user-permissions-body-parameters
  [:map
   [:add {:optional true} [:vector [:string {:min 1}]]]
   [:remove {:optional true} [:vector [:string {:min 1}]]]])

(def dataset-id-path-parameters
  [:map
   [:dataset-id [:string {:min 1}]]])

(def resource-id-path-parameters
  [:map
   [:id [:string {:min 1}]]])

(def dataset-pipeline-path-parameters
  [:map
   [:dataset-id [:string {:min 1}]]
   [:pipeline-id [:string {:min 1}]]])

(def config-root-path-parameters
  [:map
   [:root [:enum "platform" "runtime" "dataset"]]])

(def tenant-query-parameters
  [:map
   [:tenant [:string {:min 1}]]])

(def debug-dataset-config-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]])

(def debug-chunk-query-parameters
  [:map
   [:chunk-id [:string {:min 1}]]
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]])

(def debug-typesense-search-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]
   [:role [:string {:min 1}]]
   [:q [:string {:min 1}]]
   [:query-by {:optional true} [:string {:min 1}]]
   [:filter-by {:optional true} [:string {:min 1}]]
   [:sort-by {:optional true} [:string {:min 1}]]
   [:facet-by {:optional true} [:string {:min 1}]]
   [:include-fields {:optional true} [:string {:min 1}]]
   [:limit {:optional true} [:string {:min 1}]]])

(def debug-typesense-get-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]
   [:role [:string {:min 1}]]
   [:ids {:optional true} [:string {:min 1}]]
   [:range {:optional true} [:string {:min 1}]]
   [:include-fields {:optional true} [:string {:min 1}]]])

(def debug-typesense-retrieve-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]
   [:queries {:optional true} [:string {:min 1}]]
   [:q {:optional true} [:string {:min 1}]]
   [:limit {:optional true} [:string {:min 1}]]
   [:retrieve-top-k {:optional true} [:string {:min 1}]]
   [:max-per-document {:optional true} [:string {:min 1}]]
   [:metadata-only {:optional true} [:string {:min 1}]]
   [:query-aware-boost {:optional true} [:string {:min 1}]]
   [:rerank-with-colbert {:optional true} [:string {:min 1}]]
   [:rerank-candidate-k {:optional true} [:string {:min 1}]]
   [:auto-filter {:optional true} [:string {:min 1}]]
   [:enrichment-types {:optional true} [:string {:min 1}]]
   [:title-fields {:optional true} [:string {:min 1}]]
   [:doc-title-chunk-fanout {:optional true} [:string {:min 1}]]
   [:auto-filter-rules {:optional true} [:string {:min 1}]]
   [:merge-mode {:optional true} [:string {:min 1}]]
   [:rrf-k {:optional true} [:string {:min 1}]]
   [:chunk-content-fields {:optional true} [:string {:min 1}]]
   [:chunk-metadata-fields {:optional true} [:string {:min 1}]]
   [:retrieval-mode {:optional true} [:string {:min 1}]]
   ;; Slice 23 — user-intent first-pass union toggles.
   [:user-intent {:optional true} [:string {:min 1}]]
   [:user-intent-union-enabled {:optional true} [:string {:min 1}]]
   [:user-intent-union-mode {:optional true} [:string {:min 1}]]
   [:user-intent-union-cap {:optional true} [:string {:min 1}]]
   [:user-intent-union-rrf-k {:optional true} [:string {:min 1}]]
   ;; f85c984 — per-strategy ColBERT rerank fan-out.
   [:per-strategy-rerank {:optional true} [:string {:min 1}]]
   [:rerank-final-cap {:optional true} [:string {:min 1}]]
   ;; EDN-encoded retrieval-tuning maps (for agent-preset A/B work).
   [:strategy-weights {:optional true} [:string {:min 1}]]
   [:strategy-contribution-caps {:optional true} [:string {:min 1}]]])

(def debug-query-planner-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:query [:string {:min 1}]]
   [:max-phrases {:optional true} [:string {:min 1}]]
   [:temperature {:optional true} [:string {:min 1}]]
   ;; Optional dataset selection — required only for corpus-aware
   ;; expansion-mode, to resolve the phrases collection for PRF harvest.
   [:dataset-config-key {:optional true} [:string {:min 1}]]
   ;; blind (default) | corpus-aware-1hop | corpus-aware-2hop
   [:expansion-mode {:optional true} [:string {:min 1}]]
   ;; two-call (default) | one-call  (corpus-aware only)
   [:expansion-variant {:optional true} [:string {:min 1}]]])

(def debug-last-invocation-query-parameters
  [:map
   [:agent-id [:string {:min 1}]]])

(def debug-agent-resolution-query-parameters
  [:map
   [:agent-id [:string {:min 1}]]])

(def console-materialization-query-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]])

(def create-dataset-body-parameters
  [:map
   [:name [:string {:min 1}]]
   [:description {:optional true} string?]])

(def update-dataset-body-parameters
  [:map
   [:name {:optional true} [:string {:min 1}]]
   [:description {:optional true} string?]
   [:enabled? {:optional true} boolean?]])

(def create-pipeline-body-parameters
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]
   [:pipeline-name [:string {:min 1}]]
   [:properties {:optional true} any?]])

(def update-pipeline-body-parameters
  [:map
   [:properties {:optional true} any?]])

(defn execute-skill-handler
  "Execute a skill by ID.
   Expects JSON body with 'inputs' and optional 'parameters' fields."
  [ring-req]
  (try
    (let [client-id (get ring-req :api-key/client-id)
          skill-id-str (:id (request-path-params ring-req))
          skill-id (keyword "builtin" skill-id-str)
          params (request-body-params ring-req)
          inputs (:inputs params)
          _ (when-not inputs
              (throw (ex-info "Missing required field: inputs" {:status 400})))
          {:keys [dataset-ref config]}
          ;; No opts: explicit tenant + dataset-config-key is enforced
          ;; unconditionally by select-request-dataset-ref!, and agent policy
          ;; deliberately does not apply here. See
          ;; decisions/execute-skill-authorization.md.
          (api-ctx/resolve-request-dataset-context! ring-req params)

          ;; Build execution options
          opts {:tenant (:tenant config)
                :dataset-config-key (:dataset-config-key config)
                :client client-id
                :dataset-ref dataset-ref
                :skill-params config
                :parameters (:parameters params)}

          ;; Execute the skill
          result (skills-api/execute skill-id inputs opts)]

      (log/info "Skill executed" {:skill-id skill-id :dataset-ref dataset-ref})

      (if (:error result)
        (-> (res/response (json/generate-string {:error (:error result)}))
            (res/status 400)
            (res/content-type "application/json"))
        (-> (res/response (json/generate-string {:result result}))
            (res/status 200)
            (res/content-type "application/json"))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Skill execution failed")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error executing skill")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn- bearer-token
  "Extract the API key from an `Authorization: Bearer <key>` header,
   if present. OpenAI clients (incl. Open WebUI) send credentials this
   way; the existing `X-API-Key` header keeps working alongside."
  [request]
  (let [header (get-in request [:headers "authorization"])]
    (when (and (string? header)
               (str/starts-with? (str/lower-case header) "bearer "))
      (str/trim (subs header (count "bearer "))))))

(defn- unauthenticated-body
  "Render the 401 body in the shape the calling surface's clients parse.

   One middleware guards several surfaces with different error
   conventions, so the shape is chosen here rather than by duplicating
   authentication per surface — the same single-renderer argument that
   settled #120.

   `/v1` clients are OpenAI SDKs, and they read `error.message` off an
   object. A bare string there means `undefined` on the first response a
   new integrator is most likely to see, since getting the key wrong on
   the first try is the normal case (#137). Fields and values match
   OpenAI's own 401: type `invalid_request_error`, code `invalid_api_key`.

   Every other surface keeps the string body it has always returned. MCP
   and the operator API have their own conventions and their clients are
   not OpenAI SDKs; changing them would be a wire break for no reader."
  [uri]
  (if (str/starts-with? (str uri) "/v1/")
    {:error {:message "Invalid or missing API key"
             :type "invalid_request_error"
             :code "invalid_api_key"}}
    {:error "Invalid or missing API key"}))

(defn wrap-api-key-auth
  "Middleware to validate API key from X-API-Key header or
   Authorization: Bearer <key> header (OpenAI convention)."
  [handler]
  (fn [request]
    (let [api-key (or (get-in request [:headers "x-api-key"])
                      (bearer-token request))
          conn (db/get-conn)]
      (log/info "API request received"
                {:request-method (:request-method request)
                 :uri (:uri request)
                 :path-info (:path-info request)
                 :has-api-key-header (boolean api-key)})
      (if-let [key-info (and api-key (api-keys/validate-api-key conn api-key))]
        (let [dataset-scopes (vec (or (:dataset-scopes key-info) []))
              agent-refs (vec (or (:agent-refs key-info) []))
              allowed-config-keys (vec (or (:allowed-config-keys key-info) []))
              scopes (set (or (:scopes key-info) []))]
          (log/debug "Authenticated API key request"
                     {:request-method (:request-method request)
                      :uri (:uri request)
                      :path-info (:path-info request)
                      :api-key-id (:api-key-id key-info)
                      :api-key-name (:name key-info)
                      :client-id (:client-id key-info)
                      :scopes scopes
                      :dataset-scopes dataset-scopes})
          (handler (assoc request
                          :api-key/id (:api-key-id key-info)
                          :api-key/name (:name key-info)
                          :api-key/dataset-scopes dataset-scopes
                          :api-key/agent-refs agent-refs
                          :api-key/allowed-config-keys allowed-config-keys
                          :api-key/scopes scopes
                          :api-key/client-id (:client-id key-info)
                          :api-key/skill-graphs (:skill-graphs key-info))))
        (do
          (log/debug "Rejecting API request because API key authentication failed"
                     {:request-method (:request-method request)
                      :uri (:uri request)
                      :path-info (:path-info request)
                      :has-api-key-header (boolean api-key)})
          (-> (res/response (json/generate-string (unauthenticated-body (:uri request))))
            (res/status 401)
            ;; RFC 7235: a 401 has to say how to authenticate. API-key auth
            ;; instead of OAuth is a considered choice — MCP's own spec makes
            ;; Authorization optional — so this advertises the scheme rather
            ;; than apologising for it. Bare `Bearer`: the X-API-Key form is
            ;; not an HTTP auth scheme and has no challenge to offer (#121).
            (res/header "WWW-Authenticate" "Bearer")
            (res/content-type "application/json")))))))

(defn debug-api-key-secret
  []
  (System/getenv "RAG_DEBUG_API_KEY"))

(defn wrap-debug-api-key-auth
  "Middleware to validate debug API key from X-Debug-Api-Key header."
  [handler]
  (fn [request]
    (let [provided-key (get-in request [:headers "x-debug-api-key"])
          expected-key (debug-api-key-secret)]
      (cond
        (str/blank? expected-key)
        (-> (res/response (json/generate-string {:error "Debug API key is not configured"}))
            (res/status 503)
            (res/content-type "application/json"))

        (= provided-key expected-key)
        (handler request)

        :else
        (-> (res/response (json/generate-string {:error "Invalid or missing debug API key"}))
            (res/status 401)
            (res/content-type "application/json"))))))

;; =============================================================================
;; Response schemas (#432 stage 1)
;;
;; SCOPE IS THE DELIVERABLE HERE, SO IT IS STATED IN CODE RATHER THAN IN A PR.
;; Ten of the forty public operations declare a response body below. The other
;; thirty are listed in `response-schema-not-established`, with the reason.
;; `digdir.api.response-schema-coverage-test` pins both sets, so the split is a
;; tracked number rather than an invisible boundary.
;;
;; WHY ONLY TEN. OpenAPI treats a property as optional unless it is named in
;; `required`, and only 6 of the 44 object schemas reachable from a 2xx response
;; declare one. Porting the rest faithfully would produce malli maps in which
;; every field is optional — schemas that validate `{}` and validate
;; `{:unrelated 1}`. That is a declared guard with no path on which it can fail,
;; which is the defect #323 exists to catch, and switching response coercion on
;; against it in stage 2 would validate nothing while making every endpoint look
;; validated.
;;
;; The response half of the spec is loose for the same reason it was undeclared:
;; NOTHING EVER READ IT. Loosening is free when nothing checks, so an unenforced
;; schema drifts toward permissive and ends up recording what someone was willing
;; to promise rather than what the code emits.
;;
;; SO THE TEN ARE NOT "the easy ones". They are the operations whose emitted key
;; set has been ESTABLISHED AGAINST A HANDLER by the `*_shape_test` family, which
;; asserts `(= advertised (keyset body))`. For those, the spec's property names
;; are a verified statement about the response rather than an aspiration, which
;; is what makes them safe to declare as REQUIRED.
;;
;; STAGE 1 IS INERT BY CONSTRUCTION: no `coerce-response-middleware` is wired
;; (see `api-router-options`), so nothing reads these declarations yet. Stage 2
;; turns enforcement on and is deliberately a separate change, so that a wrong
;; schema cannot break production on the same commit that introduces it.
;; =============================================================================

(def permission-response
  [:map [:id string?] [:name string?]])

(def user-response-body
  "`UserResponse`. Key set verified against BOTH producers — create-user-handler
   (201) and update-user-permissions-handler (200) — by
   `digdir.api.user-response-shape-test`."
  [:map
   [:user [:map
           [:id string?]
           [:email string?]
           [:created string?]
           [:permissions [:sequential permission-response]]]]])

(def operator-pipeline-summary-response
  "`OperatorPipelineSummary`. The two `*Ambiguous` fields are OPTIONAL, and that
   is established rather than assumed: `pipeline-summary` builds the map with
   `cond->`, and `envelope-response-shape-test` asserts the unambiguous element
   equals the advertised set MINUS exactly those two."
  [:map
   [:id string?]
   [:datasetId string?]
   [:name string?]
   [:sourceType [:maybe string?]]
   [:enabled? boolean?]
   [:nameAmbiguous {:optional true} boolean?]
   [:sourceTypeAmbiguous {:optional true} boolean?]])

(def operator-dataset-summary-response
  "`OperatorDatasetSummary`, verified by `envelope-response-shape-test`."
  [:map
   [:id string?]
   [:name string?]
   [:description [:maybe string?]]
   [:enabled? boolean?]
   [:pipelineCount int?]
   [:pipelines [:sequential operator-pipeline-summary-response]]])

(def operator-dataset-list-response-body
  [:map [:datasets [:sequential operator-dataset-summary-response]]])

(def operator-dataset-response-body
  [:map [:dataset operator-dataset-summary-response]])

(def operator-pipeline-list-response-body
  [:map [:pipelines [:sequential operator-pipeline-summary-response]]])

(def skill-execution-response-body
  "`SkillExecutionResponse`. `:result` is an open map by design — the skill's own
   output shape is not part of this contract."
  [:map [:result [:map-of any? any?]]])

(def runtime-config-resolve-response-body
  "`RuntimeConfigResolveResponse`, verified by `config-resolve-shape-test`.
   `dataset-id` is optional because the handler adds it through a `cond->` only
   when the request selected a dataset — the spec says so in prose and the shape
   test pins it."
  [:map
   [:authorization [:map
                    [:allowed boolean?]
                    [:matched-allowed-config-key [:maybe string?]]]]
   [:compatibility [:map
                    [:status string?]
                    [:checked-on-node string?]
                    [:agent-id [:maybe string?]]
                    [:dataset-id {:optional true} string?]]]
   [:node [:map [:node-id string?] [:runtime-config-key string?]]]])

(def dataset-config-resolve-response-body
  "`DatasetConfigResolveResponse`, verified by `config-resolve-shape-test`."
  [:map
   [:authorization [:map
                    [:allowed boolean?]
                    [:matched-allowed-config-key [:maybe string?]]]]
   [:compatibility [:map
                    [:status string?]
                    [:checked-on-node string?]
                    [:dataset-id [:maybe string?]]]]
   [:node [:map [:node-id string?] [:dataset-config-key string?]]]])

(def response-schema-not-established
  "Operations that deliberately declare NO response body, as `[method path]`.

   NOT a to-do list left implicit: this is the enumeration that makes the
   coverage boundary countable. An entry means *the emitted key set has not been
   established against a handler*, so declaring one would record a guess. Each
   entry leaves when an operation gains a shape test that pins what it emits —
   and `response-schema-coverage-test` fails if this set and the declared set do
   not together account for every route.

   Deleting an entry without adding a schema is what the coverage test catches."
  #{["get" "/api/config/:root/nodes"]
    ["get" "/api/datasets"]
    ["get" "/api/datasets/:dataset-id"]
    ["get" "/api/conversations"]
    ["post" "/api/conversations"]
    ["get" "/api/conversations/:id"]
    ["put" "/api/conversations/:id"]
    ["delete" "/api/conversations/:id"]
    ["post" "/api/mcp"]
    ;; The two 405 handlers on /api/mcp. `2026-07-28` retired the GET SSE
    ;; stream, so these exist to answer 405 rather than to serve anything —
    ;; enumerated because their emitted body is not established either.
    ["get" "/api/mcp"]
    ["delete" "/api/mcp"]
    ["get" "/v1/models"]
    ["post" "/v1/chat/completions"]
    ["get" "/console-api/api-keys"]
    ["post" "/console-api/api-keys"]
    ["put" "/console-api/api-keys/:key-id/allowed-config-keys"]
    ["post" "/console-api/api-keys/:key-id/revoke"]
    ["post" "/console-api/api-keys/:key-id/rotate"]
    ["get" "/console-api/access-policies"]
    ["get" "/console-api/users"]
    ["delete" "/console-api/users/:id"]
    ["get" "/console-api/permissions"]
    ["get" "/console-api/conversations"]
    ["post" "/console-api/datasets"]
    ["post" "/console-api/datasets/:dataset-id/pipelines"]
    ["get" "/console-api/datasets/:dataset-id/pipelines/:pipeline-id"]
    ["put" "/console-api/datasets/:dataset-id/pipelines/:pipeline-id"]
    ["delete" "/console-api/datasets/:dataset-id/pipelines/:pipeline-id"]
    ["post" "/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute"]
    ["get" "/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions"]})


(def api-routes
  "API route definitions for API-key authenticated endpoints.

   /api/rag, /api/retrieve, /api/skill-graphs/:id/execute, and
   /api/skill-graphs/execute were removed in Phase 0 of the MCP server
   migration; the /api/mcp endpoint defined below replaces them.

   The skill and mode LISTING endpoints were removed in #350. They were
   kept as the public discovery surface, but `tools/list` and
   GET /v1/models are that surface: both enumerate the same (agent, mode)
   axis, both filter per API key, and both return the identifier a caller
   needs. The listing endpoints returned neither. Nothing in this
   repository called them over HTTP - the console consumes
   `skills-api/list-skill-graphs` and `get-all-tool-definitions` as
   function calls, which is why deleting the routes touched no consumer.
   An earlier version of this comment claimed the console needed the
   endpoints; that was already recorded as untrue before #350 confirmed
   it independently. GET /api/modes had also been returning 500 to every
   caller (#348), and all three 404'd in the built artifact before that
   (#112) - two failures that went unnoticed for the same reason the
   endpoints were deletable.

   Note the shape below: a route vector carrying BOTH data and children
   registers only the children, silently. Every parent that needs to be
   addressable therefore declares an explicit [\"\" {...}] child. Adding
   a child to a leaf route without doing this deletes the parent from
   the compiled router with no warning."
  [["/api"
    ["/config/:root/nodes" {:get {:parameters {:path config-root-path-parameters
                                               :query tenant-query-parameters}
                                  :handler (wrap-required-api-key-scope :query list-config-nodes-handler)}}]
    ["/runtime/config/resolve" {:post {:parameters {:body runtime-config-resolve-body-parameters}
                                       :responses {200 {:body runtime-config-resolve-response-body}}
                                       :handler (wrap-required-api-key-scope :query resolve-runtime-config-handler)}}]
    ["/dataset/config/resolve" {:post {:parameters {:body dataset-config-resolve-body-parameters}
                                       :responses {200 {:body dataset-config-resolve-response-body}}
                                       :handler (wrap-required-api-key-scope :query resolve-dataset-config-handler)}}]
    ["/datasets"
     ["" {:get {:parameters {}
                :handler (wrap-required-api-key-scope :query list-public-datasets-handler)}}]
     ["/:dataset-id" {:get {:parameters {:path dataset-id-path-parameters}
                            :handler (wrap-required-api-key-scope :query get-public-dataset-handler)}}]]
    ["/conversations" {:get {:parameters {:query conversation-pagination-query-parameters}
                            :handler (wrap-required-api-key-scope :query list-conversations-handler)}
                       :post {:parameters {:body conversation-create-body-parameters}
                              :handler (wrap-required-api-key-scope :query create-conversation-handler)}}]
    ["/conversations/:id" {:get {:parameters {:path resource-id-path-parameters
                                              :query conversation-detail-query-parameters}
                                 :handler (wrap-required-api-key-scope :query get-conversation-handler)}
                           :put {:parameters {:path resource-id-path-parameters
                                              :body conversation-update-body-parameters}
                                 :handler (wrap-required-api-key-scope :query update-conversation-handler)}
                           :delete {:parameters {:path resource-id-path-parameters}
                                    :handler (wrap-required-api-key-scope :query delete-conversation-handler)}}]
    ;; Only `execute` remains under /skills. The listing and inspection
    ;; endpoints - GET /api/skills, /api/skills/:id, /api/skills/tools and the
    ;; /api/modes pair - were deleted in #350: `tools/list` and GET /v1/models
    ;; already enumerate the same (agent, mode) axis, filtered per API key and
    ;; returning the identifiers a caller actually needs, so the listing
    ;; endpoints were a second discovery surface with no callers. Execute stays
    ;; because it is an execution route rather than a listing one and its
    ;; authorization contract was settled deliberately in #27.
    ;;
    ;; The `:conflicting true` markers went with /skills/tools: it was a
    ;; literal sibling of the /:id wildcard, and with it gone there is no
    ;; conflict left for reitit to refuse.
    ["/skills"
     ["/:id"
      ["/execute" {:post {:parameters {:path resource-id-path-parameters
                                       :body skill-execution-body-parameters}
                          :responses {200 {:body skill-execution-response-body}}
                          :handler (wrap-required-api-key-scope :query execute-skill-handler)}}]]]
    ;; MCP server — JSON-RPC over a single POST endpoint. Per-API-key
    ;; rate limited because each tool call can spawn a full agent loop.
    ;; tools/list and tools/call enforce per-agent/skill-graph scopes
    ;; downstream against the API key's :agent-refs and :skill-graphs.
    ;; GET and DELETE are answered with 405 rather than falling through to the
    ;; router's 404. Both verbs belonged to the pre-2026-07-28 transport (the
    ;; standalone SSE stream, and session termination); neither exists now, and
    ;; 405 lets a client tell "wrong verb" from "wrong endpoint".
    ["/mcp" {:post {:handler (rate-limit-api/wrap-api-rate-limit mcp/handle-mcp-request)}
             :get {:handler mcp/handle-mcp-method-not-allowed}
             :delete {:handler mcp/handle-mcp-method-not-allowed}}]]
   ;; OpenAI-compatible /v1 surface — exposes each agent as a `model`
   ;; so any OpenAI client (Open WebUI, cursor, continue) can talk to
   ;; a digdir agent directly. Same wrap-api-key-auth as /api/* — both
   ;; X-API-Key and Authorization: Bearer accepted.
   ["/v1"
    ["/models" {:get {:parameters {}
                      :handler list-models-handler}}]
    ["/chat/completions" {:post {:handler (rate-limit-api/wrap-api-rate-limit
                                            chat-completions-handler)}}]]])

(def debug-routes
  "API route definitions for debug endpoints protected by X-Debug-Api-Key."
  [["/api/debug"
    ["/dataset-config" {:get {:parameters {:query debug-dataset-config-query-parameters}
                              :handler debug-dataset-config-handler}}]
    ["/chunk" {:get {:parameters {:query debug-chunk-query-parameters}
                     :handler debug-chunk-handler}}]
    ["/typesense-search" {:get {:parameters {:query debug-typesense-search-query-parameters}
                                :handler debug-typesense-search-handler}}]
    ["/typesense-get" {:get {:parameters {:query debug-typesense-get-query-parameters}
                             :handler debug-typesense-get-handler}}]
    ["/typesense-retrieve" {:get {:parameters {:query debug-typesense-retrieve-query-parameters}
                                  :handler debug-typesense-retrieve-handler}}]
    ["/query-planner" {:get {:parameters {:query debug-query-planner-query-parameters}
                             :handler debug-query-planner-handler}}]
    ["/config/refresh" {:post {:handler debug-config-refresh-handler}}]
    ;; E2E-only — gated by DIGDIR_DEBUG_LAST_INVOCATION env var inside the
    ;; handler. Returns the resolved skill-params from the last invocation
    ;; recorded for the given agent-id; used by Layer-C Playwright tests
    ;; to prove agent-skill-params plumbing reached the skills layer.
    ["/last-invocation" {:get {:parameters {:query debug-last-invocation-query-parameters}
                               :handler debug-last-invocation-handler}}]
    ;; E2E-only — same env-var gate as /last-invocation. Synchronously
    ;; loads the agent and returns its raw + merge-resolved skill-params,
    ;; with no MCP call required. Lets Playwright assert agent → merge
    ;; plumbing without standing up Typesense, datasets, or an LLM.
    ["/agent-resolution" {:get {:parameters {:query debug-agent-resolution-query-parameters}
                                :handler debug-agent-resolution-handler}}]]])

(def console-api-routes
  "API route definitions for JWT-authenticated Operator Console operations."
  [["/console-api/api-keys" {:get {:parameters {}
                                   :handler list-api-keys-handler}
                             :post {:parameters {:body create-api-key-body-parameters}
                                    :handler create-api-key-handler}}]
   ["/console-api/api-keys/:key-id/allowed-config-keys" {:put {:parameters {:path api-key-path-parameters
                                                                             :body update-api-key-allowed-config-keys-body-parameters}
                                                                :handler update-api-key-allowed-config-keys-handler}}]
   ["/console-api/api-keys/:key-id/revoke" {:post {:parameters {:path api-key-path-parameters}
                                                   :handler revoke-api-key-handler}}]
   ["/console-api/api-keys/:key-id/rotate" {:post {:parameters {:path api-key-path-parameters}
                                                   :handler rotate-api-key-handler}}]
   ["/console-api/access-policies" {:get {:parameters {}
                                          :handler list-access-policies-handler}}]
   ["/console-api/users" {:get {:parameters {}
                                :handler list-users-handler}
                          :post {:parameters {:body create-user-body-parameters}
                                 :responses {201 {:body user-response-body}}
                                 :handler create-user-handler}}]
   ["/console-api/users/:id" {:get {:parameters {:path user-id-path-parameters}
                                    :responses {200 {:body user-response-body}}
                                    :handler get-user-handler}
                              :delete {:parameters {:path user-id-path-parameters}
                                       :handler delete-user-handler}}]
   ["/console-api/users/:id/permissions" {:put {:parameters {:path user-id-path-parameters
                                                             :body update-user-permissions-body-parameters}
                                                :responses {200 {:body user-response-body}}
                                                :handler update-user-permissions-handler}}]
   ["/console-api/permissions" {:get {:parameters {}
                                      :handler list-permissions-handler}}]
   ["/console-api/conversations" {:get {:parameters {:query conversation-pagination-query-parameters}
                                        :handler admin-list-conversations-handler}}]
   ["/console-api/datasets" {:get {:parameters {}
                                   :responses {200 {:body operator-dataset-list-response-body}}
                                   :handler list-datasets-handler}
                             :post {:parameters {:body create-dataset-body-parameters}
                                    :handler create-dataset-handler}}]
   ;; Both of these parents declare methods AND have children. Reitit drops a
   ;; parent's own handlers in that shape unless it is given an explicit ""
   ;; child - and it merges the parent's method keys onto the children, so the
   ;; children answer methods they never declared. #112 fixed the same trap on
   ;; the API router; the console router has the identical shape and was never
   ;; covered. Asserted against the COMPILED router in routes_test, not by
   ;; reading this form - a source-level assertion is what let #112 survive.
   ["/console-api/datasets/:dataset-id"
    ["" {:get {:parameters {:path dataset-id-path-parameters}
               :responses {200 {:body operator-dataset-response-body}}
               :handler get-dataset-handler}
         :put {:parameters {:path dataset-id-path-parameters
                            :body update-dataset-body-parameters}
               :responses {200 {:body operator-dataset-response-body}}
               :handler update-dataset-handler}}]
    ["/pipelines" {:get {:parameters {:path dataset-id-path-parameters
                                      :query console-materialization-query-parameters}
                         :responses {200 {:body operator-pipeline-list-response-body}}
                         :handler list-pipelines-handler}
                   :post {:parameters {:path dataset-id-path-parameters
                                       :body create-pipeline-body-parameters}
                          :handler create-pipeline-handler}}]
    ["/pipelines/:pipeline-id"
     ["" {:get {:parameters {:path dataset-pipeline-path-parameters
                             :query console-materialization-query-parameters}
                :handler get-pipeline-handler}
          :put {:parameters {:path dataset-pipeline-path-parameters
                             :query console-materialization-query-parameters
                             :body update-pipeline-body-parameters}
                :handler update-pipeline-handler}
          :delete {:parameters {:path dataset-pipeline-path-parameters
                                :query console-materialization-query-parameters}
                   :handler delete-pipeline-handler}}]
     ["/execute" {:post {:parameters {:path dataset-pipeline-path-parameters
                                      :query console-materialization-query-parameters}
                         :handler execute-pipeline-handler}}]
     ["/executions" {:get {:parameters {:path dataset-pipeline-path-parameters
                                        :query console-materialization-query-parameters}
                           :handler list-executions-handler}}]]]])

(defn- json-not-found [_]
  (-> (res/not-found (json/generate-string {:error "API endpoint not found"}))
      (res/content-type "application/json")))

(def api-router
  "Ring handler for /api/* endpoints that use API key authentication"
  (ring/ring-handler
   (ring/router api-routes api-router-options)
   json-not-found))

(def debug-router
  "Ring handler for /api/debug/* endpoints that use debug API key authentication."
  (ring/ring-handler
   (ring/router debug-routes debug-router-options)
   json-not-found))

(def console-api-router
  "Ring handler for /console-api/* Operator Console endpoints that use JWT authentication."
  (ring/ring-handler
   (ring/router console-api-routes api-router-options)
   json-not-found))
