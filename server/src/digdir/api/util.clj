(ns digdir.api.util
  "Shared API utility helpers used across route namespaces."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.api.body :as request-body]
            [digdir.data.db :as db]
            [ring.util.response :as res]))

(defn compact-map [m]
  (into {} (filter (comp some? val) m)))

(defn api-error-body
  [^Exception e]
  (let [data (ex-data e)
        body (cond-> {:error (.getMessage e)}
               (:available data) (assoc :available (:available data)))]
    (json/generate-string body)))

(defn require-external-api-user-id!
  [ring-req]
  (let [external-user-id (some-> (get-in ring-req [:headers "x-user-id"]) str/trim)]
    (when (str/blank? external-user-id)
      (throw (ex-info "Missing X-User-Id header" {:status 400})))
    external-user-id))

(defn require-api-conversation-owner!
  [conversation convo-id external-user-id]
  (when-not conversation
    (throw (ex-info "Conversation not found" {:status 404
                                              :conversation-id convo-id})))
  (when-not (= external-user-id (:conversation/user-id conversation))
    (throw (ex-info "Conversation not found" {:status 404
                                              :conversation-id convo-id
                                              :external-user-id external-user-id})))
  conversation)

(defn find-api-conversation!
  [conn convo-id external-user-id]
  (require-api-conversation-owner!
   (db/conversation-by-id @conn convo-id)
   convo-id
   external-user-id))

(defn camel->kebab-keyword
  [k]
  (-> (name k)
      (str/replace "_" "-")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case
      keyword))

(defn normalize-request-key
  [k]
  (if (or (keyword? k) (string? k) (symbol? k))
    (camel->kebab-keyword k)
    k))

(defn normalize-request-keys
  [x]
  (cond
    (map? x) (into {}
                    (map (fn [[k v]]
                           [(normalize-request-key k) (normalize-request-keys v)]))
                    x)
    (vector? x) (mapv normalize-request-keys x)
    (seq? x) (map normalize-request-keys x)
    :else x))

(defn non-blank-value [v]
  (cond
    (string? v) (when-not (str/blank? v) v)
    :else v))

(defn param-value [params k]
  (let [k-name (name k)]
    (or (non-blank-value (get params k))
        (non-blank-value (get params k-name))
        (non-blank-value (get params (keyword k-name))))))

(defn request-path-params
  [ring-req]
  (or (get-in ring-req [:parameters :path])
      (:path-params ring-req)
      {}))

(defn request-query-params
  [ring-req]
  (or (get-in ring-req [:parameters :query])
      (:params ring-req)
      {}))

(defn read-json-body
  "Parse JSON request body into a map with normalized keyword keys.
   Returns {} when body is empty."
  [ring-req]
  (let [body-str (request-body/read-body-string ring-req)]
    (if (str/blank? body-str)
      {}
      (try
        (-> (json/parse-string body-str true)
            normalize-request-keys)
        (catch Throwable e
          (if (request-body/body-too-large? e)
            (throw e)
            (throw (ex-info "Invalid JSON body" {:status 400}))))))))

(defn request-body-params
  [ring-req]
  (or (get-in ring-req [:parameters :body])
      (:body-params ring-req)
      (read-json-body ring-req)))

(defn- api-request-context
  [ring-req]
  (select-keys ring-req
               [:request-method
                :uri
                :path-info
                :api-key/id
                :api-key/name
                :api-key/client-id
                :api-key/scopes
                :api-key/dataset-scopes]))

(defn normalize-request-paths [params]
  (let [single-path (param-value params :path)
        multi-paths (or (:paths params) (get params "paths") [])
        paths (cond-> []
                single-path (conj single-path)
                (seq multi-paths) (into multi-paths))]
    (->> paths
         (map non-blank-value)
         (remove nil?)
         vec)))

(defn require-api-key-scope! [ring-req required-scope]
  (let [granted-scopes (set (or (:api-key/scopes ring-req) []))]
    (when (and required-scope
               (not (contains? granted-scopes required-scope)))
      (log/debug "Rejecting API request because the API key is missing the required scope"
                 (merge (api-request-context ring-req)
                        {:required-scope required-scope
                         :granted-scopes granted-scopes}))
      (throw (ex-info (str "API key does not grant the required scope: " (name required-scope))
                      {:status 403
                       :required-scope required-scope
                       :granted-scopes granted-scopes})))))

(defn wrap-required-api-key-scope [required-scope handler]
  (fn [request]
    (try
      (require-api-key-scope! request required-scope)
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 403)]
          (-> (res/response (api-error-body e))
              (res/status status)
              (res/content-type "application/json")))))))

(def pipeline-property-aliases
  {:sitemap-url :website-sitemap-url
   :base-url :website-base-url
   :xml-path :episerver-xml-path
   :language :episerver-language
   :include-page-types :episerver-include-page-types
   :document-types :kudos-document-types
   :transducer :kudos-transducer})

(defn normalize-pipeline-properties [properties]
  (let [kebab (into {}
                    (map (fn [[k v]] [(camel->kebab-keyword k) v]))
                    (or properties {}))
        aliased (reduce-kv (fn [acc k v]
                             (assoc acc (get pipeline-property-aliases k k) v))
                           {}
                           kebab)
        source-type (some-> (:source-type aliased) name keyword)
        with-source-type (assoc aliased :source-type source-type)
        with-kudos-preprod (if (and (= source-type :kudos)
                                    (contains? with-source-type :use-preprod)
                                    (not (contains? with-source-type :kudos-use-preprod)))
                             (-> with-source-type
                                 (assoc :kudos-use-preprod (:use-preprod with-source-type))
                                 (dissoc :use-preprod))
                             with-source-type)]
    (cond-> with-kudos-preprod
      (contains? with-kudos-preprod :kudos-document-types)
      (update :kudos-document-types
              (fn [v]
                (cond
                  (set? v) v
                  (sequential? v) (set v)
                  (nil? v) #{}
                  :else #{v}))))))

;; messages->conversation-history was only used by the retired
;; /api/rag handler; removed in Phase 0. The Playground builds its
;; conversation history via digdir.playground.core/messages->context.

;; =============================================================================
;; RAG skill-params merge
;; =============================================================================
;;
;; Each invoke call resolves skill-params from up to four layers, in order
;; of precedence (highest wins):
;;
;;   1. per-call API body (`params`) — sweep matrices and HTTP body overrides
;;   2. agent-level overrides (`agent-skill-params`) — the agent's
;;      :skill-params field, applied to every call that resolves to this agent
;;   3. dataset `config` defaults — runtime config DB entries on the dataset
;;   4. hardcoded fallbacks — embedded in `build-skill-params-from-config`
;;
;; Layers (2), (3), (4) are produced as separate skill-params-shaped maps;
;; layer (1) is a flat per-call-shaped overlay produced by
;; `build-skill-params-from-params`. They're combined via `deep-merge-skill-params`,
;; which uses `(merge-with merge ...)` — one level deep on the outer
;; :builtin/<skill> keys, then plain replace at deeper levels. So
;; `:strategy-weights {:phrase 0.8}` from a higher layer REPLACES the whole
;; weights map rather than merging key-by-key (correct semantics for a
;; named tuning vector; partial weight tweaks would be confusing).

(defn- deep-merge-skill-params
  "Merge skill-params maps with `(merge-with merge ...)` so each
   :builtin/<skill> entry has its leaves overlaid from later args, while
   nested maps inside leaves (e.g. :strategy-weights) are replaced, not
   merged. Later args win."
  [& ms]
  (apply merge-with merge (remove nil? ms)))

(defn build-skill-params-from-config
  "Layer-2/3/4 builder: dataset-config defaults plus hardcoded fallbacks.
   No per-call or agent overrides applied here.

   Hardcoded fallbacks (`:retrieve-top-k 100`, `:max-per-document 10`,
   `:query-aware-boost true`) live in this layer because they're the
   *floor* — agent and per-call params can override, but in the absence
   of any override these are the production defaults."
  [config]
  (let [query-aware-boost (if (contains? config :retrieval-query-aware-boost)
                            (:retrieval-query-aware-boost config)
                            true)]
    {:builtin/query-planner (compact-map {:enabled (:query-planner-enabled config)
                                          :prompt (:query-planner-prompt config)
                                          :max-phrases (:query-planner-max-phrases config)
                                          ;; Corpus-aware PRF expansion is the production
                                          ;; default (corpus-aware-2hop). Overridable per
                                          ;; tenant/dataset via skills.query-planner.expansion-mode
                                          ;; (e.g. set "blind" for org-tagged corpora pending
                                          ;; per-corpus validation + auto-filter review).
                                          :expansion-mode (keyword (or (:query-planner-expansion-mode config)
                                                                       "corpus-aware-2hop"))})
     :builtin/retrieval (compact-map {:phrase-gen-prompt (:retrieval-phrase-gen-prompt config)
                                      :retrieve-top-k (or (:retrieval-top-k config) 100)
                                      :max-per-document (or (:retrieval-max-per-document config) 10)
                                      :query-aware-boost query-aware-boost
                                      :strategy-weights (:retrieval-strategy-weights config)
                                      :strategy-contribution-caps (:retrieval-strategy-contribution-caps config)
                                      :title-fields (:retrieval-title-fields config)
                                      :doc-title-chunk-fanout (:retrieval-doc-title-chunk-fanout config)
                                      :auto-filter-rules (:retrieval-auto-filter-rules config)
                                      ;; Enrichment lever: which enrichment collections to
                                      ;; search as sibling strategies. Parsed comma-separated
                                      ;; string → keyword vec; nil/absent = OFF (default). The
                                      ;; agent runtime resolves these types to collection names.
                                      ;; Enable per dataset whose collections exist.
                                      :enrichment-types (some->> (:retrieval-enrichment-types config)
                                                                 (#(clojure.string/split % #","))
                                                                 (map clojure.string/trim)
                                                                 (remove clojure.string/blank?)
                                                                 (map keyword)
                                                                 seq
                                                                 vec)
                                      :merge-mode (:retrieval-merge-mode config)
                                      :rrf-k (:retrieval-rrf-k config)
                                      ;; Slice 23: user-intent first-pass union (slice-21/22).
                                      :user-intent-union-enabled (:retrieval-user-intent-union-enabled config)
                                      :user-intent-union-mode (:retrieval-user-intent-union-mode config)
                                      :user-intent-union-cap (:retrieval-user-intent-union-cap config)
                                      :user-intent-union-rrf-k (:retrieval-user-intent-union-rrf-k config)})
     :builtin/rerank (compact-map {:enabled (:rerank-enabled config)
                                   :top-k (:rerank-top-k config)
                                   :max-chunk-length (:rerank-rag-max-chunk-length config)
                                   :max-total-length (:rerank-rag-max-total-length config)
                                   :context-top-k (:rerank-rag-context-top-k config)
                                   :context-min-chunks (:rerank-context-min-chunks config)
                                   :context-relative-score-threshold (:rerank-context-relative-score-threshold config)
                                   :context-max-chunk-length (:rerank-rag-context-max-chunk-length config)
                                   :max-context-length (:rerank-rag-max-context-length config)})
     :builtin/synthesis (compact-map {:model (:synthesis-model config)
                                      :temperature (:synthesis-temperature config)
                                      :max-tokens (:synthesis-max-tokens config)
                                      :system-prompt (:synthesis-system-prompt config)
                                      :generation-prompt (:synthesis-generation-prompt config)
                                      :max-docs (:synthesis-max-docs config)})}))

(defn build-skill-params-from-params
  "Layer-1 builder: per-call API body / sweep-matrix overrides only.
   Produces a sparse skill-params-shaped map containing ONLY the keys
   the caller explicitly provided. Empty top-level entries are dropped
   so deep-merge with lower layers doesn't accidentally clear leaves.

   `:retrieve-query-aware-boost` uses `contains?` semantics so a `false`
   value survives the merge — `(compact-map)` would drop it, hence the
   manual cond-> on that field."
  [params]
  (let [retrieval (cond-> (compact-map {:retrieve-top-k (:retrieve-top-k params)
                                        :max-per-document (:retrieve-max-per-document params)
                                        :strategy-weights (:retrieve-strategy-weights params)
                                        :strategy-contribution-caps (:retrieve-strategy-contribution-caps params)
                                        :title-fields (:retrieve-title-fields params)
                                        :doc-title-chunk-fanout (:retrieve-doc-title-chunk-fanout params)
                                        :auto-filter-rules (:retrieve-auto-filter-rules params)
                                        :merge-mode (:retrieve-merge-mode params)
                                        :rrf-k (:retrieve-rrf-k params)
                                        ;; Slice 23: user-intent first-pass union (slice-21/22).
                                        :user-intent-union-enabled (:retrieve-user-intent-union-enabled params)
                                        :user-intent-union-mode (:retrieve-user-intent-union-mode params)
                                        :user-intent-union-cap (:retrieve-user-intent-union-cap params)
                                        :user-intent-union-rrf-k (:retrieve-user-intent-union-rrf-k params)})
                    (contains? params :retrieve-query-aware-boost)
                    (assoc :query-aware-boost (:retrieve-query-aware-boost params)))
        rerank (compact-map {:top-k (:rerank-top-k params)
                             :max-chunk-length (:rerank-max-chunk-length params)
                             :max-total-length (:rerank-max-total-length params)
                             :context-top-k (:context-top-k params)
                             :context-min-chunks (:context-min-chunks params)
                             :context-relative-score-threshold (:context-relative-score-threshold params)
                             :context-max-chunk-length (:context-max-chunk-length params)
                             :max-context-length (:max-context-length params)})
        synthesis (compact-map {:model (:model params)
                                :temperature (:temperature params)
                                :max-tokens (:max-tokens params)})]
    (cond-> {}
      (seq retrieval) (assoc :builtin/retrieval retrieval)
      (seq rerank) (assoc :builtin/rerank rerank)
      (seq synthesis) (assoc :builtin/synthesis synthesis))))

(defn build-rag-skill-params
  "Build the merged :skill-params map for a RAG call.

   Precedence (highest wins): per-call `params` > `agent-skill-params` >
   dataset `config` defaults > hardcoded fallbacks.

   The two-arity form is preserved for call sites that haven't yet been
   updated to resolve an agent — it's equivalent to passing `{}` as the
   agent layer."
  ([config params]
   (build-rag-skill-params config params {}))
  ([config params agent-skill-params]
   (deep-merge-skill-params
     (build-skill-params-from-config config)
     agent-skill-params
     (build-skill-params-from-params params))))

;; build-retrieval-skill-params was only used by the retired /api/retrieve
;; handler; removed in Phase 0.
