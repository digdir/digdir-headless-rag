(ns digdir.api.routes.endpoints.debug
  "Debug endpoints and low-level request helpers for the API entrypoint."
  (:require
   [cheshire.core :as json]
   [clojure.edn]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [digdir.agents.db :as agents-db]
   [digdir.api.context :as api-ctx]
   [digdir.api.util :as api-util :refer [param-value
                                          request-query-params]]
   [digdir.config.core :as config-core]
   [digdir.config.db :as config-db]
   [digdir.pipeline.collections :as collections]
   [digdir.rag.typesense :as ts-utils]
   [digdir.skills.builtin.query-planner :as query-planner-skill]
   [digdir.skills.builtin.retrieval :as retrieval-skill]
   [digdir.skills.enrichment.naming :as enrichment-naming]
   [ring.util.response :as res]
   [typesense.client :as ts-client]))

(defn edn-response
  [data status]
  (-> (res/response (pr-str data))
      (res/status status)
      (res/content-type "application/edn")))

(defn blank->nil
  [x]
  (let [s (some-> x str)]
    (when (and s (not (str/blank? s)))
      s)))

(defn read-json-body
  "Parse JSON request body into a map with keyword keys.
   Returns {} when body is empty."
  [ring-req]
  (let [body-str (some-> ring-req :body slurp)]
    (if (str/blank? body-str)
      {}
      (try
        (json/parse-string body-str true)
        (catch Throwable _
          (throw (ex-info "Invalid JSON body" {:status 400})))))))

(defn param-presence
  "Read param from either keyword or string key and report if it was provided."
  [params k]
  (let [k-str (name k)]
    (cond
      (contains? params k) {:present? true :value (get params k)}
      (contains? params k-str) {:present? true :value (get params k-str)}
      :else {:present? false :value nil})))

(defn explicit-param-or-default
  "Use explicitly provided param value (including explicit nil via blank), else default."
  [params k default]
  (let [{:keys [present? value]} (param-presence params k)]
    (if present?
      (blank->nil value)
      default)))

;; =============================================================================
;; Last-invocation recorder (E2E testability scaffold)
;; =============================================================================
;;
;; Records the most recent resolved skill-params per agent-id so the
;; Layer-C Playwright tests can verify that an agent's :skill-params
;; field actually shaped the retrieval call. The capture is opt-in via
;; the `DIGDIR_DEBUG_LAST_INVOCATION` env var so production traffic
;; doesn't pay the per-call atom-swap cost.
;;
;; Lifetime: process-local atom, no persistence. The endpoint is meant
;; for test fixtures to read immediately after exercising an agent —
;; not a general observability tool.

(defonce ^:private !last-invocations (atom {}))

(defn capture-enabled?
  "Predicate gating the last-invocation recorder. Public (rather than
   private) so tests can `with-redefs` it; the handler still gates
   itself on the live predicate."
  []
  (= "true" (System/getenv "DIGDIR_DEBUG_LAST_INVOCATION")))

(defn record-last-invocation!
  "Snapshot the resolved skill-params for `agent-id`. No-op when capture
   is disabled. Caller is expected to pass the *resolved* (merged)
   skill-params — i.e. the output of build-rag-skill-params — so the
   recorded value reflects what the skills actually saw."
  [agent-id {:keys [user-query skill-params skill-graph-id source]}]
  (when (and (capture-enabled?) (some? agent-id))
    (swap! !last-invocations assoc agent-id
           {:agent-id agent-id
            :user-query user-query
            :skill-params skill-params
            :skill-graph-id skill-graph-id
            :source source
            :captured-at-ms (System/currentTimeMillis)})
    nil))

(defn debug-last-invocation-handler
  "GET /api/debug/last-invocation?agent-id=<id>

   Returns the most-recent resolved skill-params recorded for that agent
   (or 404 when nothing has been captured). When capture is disabled by
   the operator, returns 503 so a test fixture can fail loudly instead
   of silently passing on stale or empty data."
  [ring-req]
  (let [params (request-query-params ring-req)
        agent-id (or (param-value params :agent-id)
                     (param-value params :agent))]
    (cond
      (not (capture-enabled?))
      (edn-response {:error "capture disabled — set DIGDIR_DEBUG_LAST_INVOCATION=true"} 503)

      (str/blank? agent-id)
      (edn-response {:error "missing query param: agent-id"} 400)

      :else
      (if-let [record (get @!last-invocations agent-id)]
        (edn-response record 200)
        (edn-response {:error (str "no invocation recorded for agent " agent-id)
                       :agent-id agent-id}
                      404)))))

(defn debug-agent-resolution-handler
  "GET /api/debug/agent-resolution?agent-id=<id>

   Synchronously load the agent and return the resolved skill-params —
   the output of `build-rag-skill-params` with empty dataset-config and
   empty per-call params, so the only non-default contribution comes
   from the agent's own `:skill-params`. Lets Layer-C Playwright tests
   verify the agent → merge plumbing without needing a live dataset,
   Typesense, or LLM credentials.

   Same env-var gate as the recorder (`DIGDIR_DEBUG_LAST_INVOCATION`)
   so both E2E scaffolds enable/disable as a pair."
  [ring-req]
  (let [params (request-query-params ring-req)
        agent-id (or (param-value params :agent-id)
                     (param-value params :agent))]
    (cond
      (not (capture-enabled?))
      (edn-response {:error "capture disabled — set DIGDIR_DEBUG_LAST_INVOCATION=true"} 503)

      (str/blank? agent-id)
      (edn-response {:error "missing query param: agent-id"} 400)

      :else
      (if-let [agent (agents-db/get-agent @(config-db/get-conn) agent-id)]
        (let [agent-skill-params (or (:skill-params agent) {})
              resolved (api-util/build-rag-skill-params {} {} agent-skill-params)]
          (edn-response {:agent-id agent-id
                         :found? true
                         :enabled? (:enabled? agent)
                         :agent-skill-params agent-skill-params
                         :resolved-skill-params resolved}
                        200))
        (edn-response {:error (str "Agent not found: " agent-id)
                       :agent-id agent-id
                       :found? false}
                      404)))))

(defn debug-dataset-config-handler
  "Debug endpoint equivalent to bb dataset-config.
   Query params:
   - tenant/dataset-config-key or dataset-ref (required)"
  [ring-req]
  (let [params (request-query-params ring-req)
        dataset-ref (api-ctx/request-dataset-ref params)
        tenant (:tenant dataset-ref)
        dataset-config-key (:dataset-config-key dataset-ref)
        conn (config-db/get-conn)]
    (try
      (when (= dataset-ref api-ctx/invalid-dataset-ref)
        (throw (ex-info "Dataset selection must include tenant and dataset-config-key" {:status 400})))
      (when-not dataset-ref
        (throw (ex-info "Missing required dataset selection: tenant and dataset-config-key" {:status 400})))
      (when-not conn
        (throw (ex-info "Config DB connection is not available" {:status 500})))
      (let [db @conn
            master-key (config-core/get-master-key)
            dataset-config (config-db/get-dataset-by-ref db dataset-ref master-key)]
        (edn-response {:tenant (or (:tenant dataset-config) tenant)
                       :dataset-config-key (or (:dataset-config-key dataset-config) dataset-config-key)
                       :dataset-found? (boolean dataset-config)
                       :dataset-config dataset-config}
                      200))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug pipeline-config failed")
        (edn-response {:error "Internal server error"} 500)))))

(defn debug-chunk-handler
  "Debug endpoint equivalent to bb chunk.
   Query params:
   - chunk-id (required)
   - tenant and dataset-config-key, or dataset-ref (required)"
  [ring-req]
  (let [params (request-query-params ring-req)
        chunk-id (or (param-value params :chunk-id)
                     (param-value params :chunk_id))
        dataset-ref (api-ctx/request-dataset-ref params)
        tenant (:tenant dataset-ref)
        dataset-config-key (:dataset-config-key dataset-ref)
        conn (config-db/get-conn)]
    (try
      (when-not chunk-id
        (throw (ex-info "Missing required query param: chunk-id" {:status 400})))
      (when (= dataset-ref api-ctx/invalid-dataset-ref)
        (throw (ex-info "Dataset selection must include tenant and dataset-config-key" {:status 400})))
      (when-not dataset-ref
        (throw (ex-info "Missing required dataset selection: tenant and dataset-config-key" {:status 400})))
      (when-not conn
        (throw (ex-info "Config DB connection is not available" {:status 500})))
      (let [db @conn
            master-key (config-core/get-master-key)
            dataset-config (config-db/get-dataset-by-ref db dataset-ref master-key)
            canonical-dataset-config-key (:dataset-config-key dataset-config)
            _ (when-not dataset-config
                (throw (ex-info "Dataset not found"
                                {:status 404
                                 :tenant tenant
                                 :dataset-config-key dataset-config-key})))
            collection-names (collections/get-or-generate-collection-names dataset-config)
            chunks-collection (:chunks-collection collection-names)
            docs-collection (:docs-collection collection-names)
            ts-settings (ts-utils/make-ts-settings {:tenant tenant
                                                   :dataset-config-key canonical-dataset-config-key})
            response (ts-client/multi-search
                      ts-settings
                      {:searches [{:collection chunks-collection
                                   :q chunk-id
                                   :include_fields (str "id,chunk_id,doc_num,content_markdown,metadata,$"
                                                        docs-collection "(url,title)")
                                   :filter_by (str "chunk_id:=`" chunk-id "`")
                                   :page 1
                                   :per_page 1}]}
                      {:query_by "chunk_id"})
            hit (first (mapcat :hits (get response :results)))]
        (if hit
          (let [doc (:document hit)
                ref-doc (get doc (keyword docs-collection))]
            (edn-response {:found? true
                           :chunk-id chunk-id
                           :dataset-config-key canonical-dataset-config-key
                           :title (:title ref-doc)
                           :doc-num (:doc_num doc)
                           :url (:url ref-doc)
                           :metadata (:metadata doc)
                           :content-markdown (:content_markdown doc)
                           :chunks-collection chunks-collection
                           :docs-collection docs-collection}
                          200))
          (edn-response {:found? false
                         :chunk-id chunk-id
                         :dataset-config-key canonical-dataset-config-key
                         :chunks-collection chunks-collection
                         :docs-collection docs-collection}
                        200)))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug chunk lookup failed")
        (edn-response {:error "Internal server error"} 500)))))

;; =============================================================================
;; Typesense filesystem-style tools (search / get)
;;
;; Two debug endpoints that let callers explore a Typesense dataset the way
;; you'd explore a filesystem with grep + Read. Defaults bias toward small
;; payloads — big text fields (content_markdown, *_vec) require explicit
;; opt-in via include-fields. See
;; plans/proposed/typesense-filesystem-tools-plan.md.
;; =============================================================================

(def ^:private role-aliases
  "Canonical role keywords keyed by every accepted spelling. Roles select
   *which* collection (docs / chunks / phrases / one of the enrichment
   collections) to act on. Strings come in via query params; keywords
   come in via direct in-process callers."
  {"docs"                              :docs
   "documents"                         :docs
   "chunks"                            :chunks
   "phrases"                           :phrases
   "enrichment/hypothetical-questions" :enrichment/hypothetical-questions
   "enrichment/verified-phrases"       :enrichment/verified-phrases
   "enrichment/fact-assertions"        :enrichment/fact-assertions
   "enrichment/knowledge-graph"        :enrichment/knowledge-graph
   :docs                               :docs
   :documents                          :docs
   :chunks                             :chunks
   :phrases                            :phrases
   :enrichment/hypothetical-questions  :enrichment/hypothetical-questions
   :enrichment/verified-phrases        :enrichment/verified-phrases
   :enrichment/fact-assertions         :enrichment/fact-assertions
   :enrichment/knowledge-graph         :enrichment/knowledge-graph})

(defn- parse-role
  [raw]
  (or (get role-aliases raw)
      (throw (ex-info (str "Unknown role: " (pr-str raw)
                           ". Expected one of: "
                           (str/join ", " (sort (filter string? (keys role-aliases)))))
                      {:status 400 :role raw}))))

(defn- role->enrichment-type
  [role]
  (case role
    :enrichment/hypothetical-questions :hypothetical-questions
    :enrichment/verified-phrases       :verified-phrases
    :enrichment/fact-assertions        :fact-assertions
    :enrichment/knowledge-graph        :knowledge-graph
    nil))

(defn- resolve-collection-for-role
  "Return the Typesense collection name for `role` paired to `dataset-config`.
   Throws ex-info with :status 400 if `role` is unknown."
  [dataset-config role]
  (let [base-names (collections/get-or-generate-collection-names dataset-config)]
    (case role
      :docs    (:docs-collection base-names)
      :chunks  (:chunks-collection base-names)
      :phrases (:phrases-collection base-names)
      (if-let [enrichment-type (role->enrichment-type role)]
        (enrichment-naming/enrichment-collection-name dataset-config enrichment-type)
        (throw (ex-info (str "Cannot resolve collection for role: " (pr-str role))
                        {:status 400 :role role}))))))

(def ^:private default-include-fields-by-role
  "Default `include_fields` per role. Big text fields (`content_markdown`,
   `*_vec`) are deliberately excluded — callers opt in by passing
   `include-fields` explicitly. The `$docs(...)` join is included for
   roles that reference docs so callers get title/url without a second
   round-trip.

   Fields listed here are the *minimum common shape* across the
   pipelines in this codebase (website, kudos, episerver, folder).
   Pipeline-specific fields (`orgs_long`, `orgs_short`, `content_length`
   on docs; etc.) are not in the default — callers can opt in via
   `include-fields` when they know the pipeline."
  {:docs    "id,doc_num,title,url,total_chunks"
   :chunks  "id,chunk_id,doc_num,chunk_index,metadata"
   :phrases "id,chunk_id,doc_num,search_phrase"
   :enrichment/hypothetical-questions "id,chunk_id,doc_num,question,model,prompt_hash"
   :enrichment/verified-phrases       "id,chunk_id,doc_num,phrase,model,prompt_hash"
   :enrichment/fact-assertions        "id,chunk_id,doc_num,subject,predicate,object,triple_text,model,prompt_hash"
   :enrichment/knowledge-graph        "id,chunk_id,doc_num"})

(defn- default-include-fields-for-role
  "Default include_fields, with the `$docs(url,title)` join appended for
   chunk-shaped roles so callers don't need a second fetch to identify
   the source document."
  [role docs-collection]
  (let [base (get default-include-fields-by-role role)
        wants-doc-join? (#{:chunks :phrases
                           :enrichment/hypothetical-questions
                           :enrichment/verified-phrases
                           :enrichment/fact-assertions
                           :enrichment/knowledge-graph} role)]
    (cond-> base
      (and wants-doc-join? docs-collection)
      (str ",$" docs-collection "(url,title)"))))

(def ^:private default-query-by-by-role
  "Default `query_by` per role for the search endpoint. Vector fields
   are included where present so semantic search kicks in for short
   queries; Typesense ranks vector vs. text hits together.

   Only fields that exist in the *minimum common shape* across pipelines
   are listed here. Typesense returns 0 hits silently when `query_by`
   references an unknown field, so phantom defaults are dangerous —
   e.g. chunks have no `title` field of their own (it lives on the
   joined docs collection)."
  {:docs                               "title,url"
   :chunks                             "content_markdown,metadata"
   :phrases                            "search_phrase,phrase_vec"
   :enrichment/hypothetical-questions  "question,question_vec"
   :enrichment/verified-phrases        "phrase,phrase_vec"
   :enrichment/fact-assertions         "triple_text,triple_vec,subject,object"
   :enrichment/knowledge-graph         ""})

(defn- id-field-for-role
  "The id field the `get` endpoint filters on, per role. Docs are
   identified by `doc_num`; everything else by `chunk_id`."
  [role]
  (case role
    :docs "doc_num"
    "chunk_id"))

(defn- ts-escape-value
  "Escape a value for use inside a Typesense filter_by backtick literal.
   Backslash and backtick are the two characters that need escaping."
  [v]
  (-> (str v)
      (str/replace "\\" "\\\\")
      (str/replace "`" "\\`")))

(defn- ids-filter-by
  "Build a `filter_by` clause matching any of `ids` on `field-name`,
   using backtick-quoted literals so commas/spaces in IDs are safe."
  [field-name ids]
  (str field-name ":=[" (str/join ","
                                  (map #(str "`" (ts-escape-value %) "`") ids))
       "]"))

(defn- parse-ids
  "Comma-separated id list → vector of trimmed non-blank strings.
   Returns nil when input is blank."
  [raw]
  (when-let [s (blank->nil raw)]
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec
         not-empty)))

(defn- positive-int
  [v default-val max-val]
  (let [n (cond
            (number? v) (long v)
            (string? v) (try (Long/parseLong (str/trim v))
                             (catch Exception _ nil))
            :else nil)]
    (cond
      (or (nil? n) (not (pos? n))) default-val
      (> n max-val) max-val
      :else n)))

(defn- resolve-dataset
  "Resolve the dataset config from query params (tenant + dataset-config-key
   or dataset-ref). Throws ex-info with :status 400/404 on the standard
   failure modes so handlers can stay focused."
  [params]
  (let [dataset-ref (api-ctx/request-dataset-ref params)
        conn (config-db/get-conn)]
    (cond
      (= dataset-ref api-ctx/invalid-dataset-ref)
      (throw (ex-info "Dataset selection must include tenant and dataset-config-key"
                      {:status 400}))

      (nil? dataset-ref)
      (throw (ex-info "Missing required dataset selection: tenant and dataset-config-key"
                      {:status 400}))

      (nil? conn)
      (throw (ex-info "Config DB connection is not available" {:status 500}))

      :else
      (let [db @conn
            master-key (config-core/get-master-key)
            dataset-config (config-db/get-dataset-by-ref db dataset-ref master-key)]
        (when-not dataset-config
          (throw (ex-info "Dataset not found"
                          {:status 404
                           :tenant (:tenant dataset-ref)
                           :dataset-config-key (:dataset-config-key dataset-ref)})))
        {:dataset-ref dataset-ref
         :dataset-config dataset-config}))))

(defn- normalize-search-hits
  "Flatten Typesense `multi-search` results into a flat vector of
   `{:document ... :highlights ...}` hits."
  [response]
  (vec
   (for [r (get response :results)
         hit (get r :hits)]
     {:document (get hit :document)
      :highlights (get hit :highlights)})))

(defn- normalize-facets
  "Pull facet counts out of a `multi-search` response into a compact
   `{field [{:value v :count n} ...]}` shape."
  [response]
  (let [facets (mapcat #(get % :facet_counts) (get response :results))]
    (when (seq facets)
      (into {}
            (map (fn [{:keys [field_name counts]}]
                   [field_name (mapv (fn [c] {:value (:value c)
                                              :count (:count c)})
                                     counts)]))
            facets))))

(defn debug-typesense-search-handler
  "GET /api/debug/typesense-search — explore a Typesense collection like
   `grep`. Returns hits + (optional) facet counts. Big fields are stripped
   by default — opt in by passing `include-fields` explicitly.

   Query params:
   - tenant + dataset-config-key (or dataset-ref) — required
   - role — required (`docs` / `chunks` / `phrases` /
     `enrichment/hypothetical-questions` / `enrichment/verified-phrases` /
     `enrichment/fact-assertions` / `enrichment/knowledge-graph`)
   - q — required (use `*` to list)
   - query-by, filter-by, sort-by, facet-by — optional Typesense fragments
   - include-fields — optional; defaults per role to small fields only
   - limit — optional, default 20, max 100"
  [ring-req]
  (let [params (request-query-params ring-req)]
    (try
      (let [role (parse-role (or (param-value params :role)
                                 (throw (ex-info "Missing required query param: role"
                                                 {:status 400}))))
            q (or (param-value params :q)
                  (throw (ex-info "Missing required query param: q"
                                  {:status 400})))
            {:keys [dataset-config]} (resolve-dataset params)
            base-names (collections/get-or-generate-collection-names dataset-config)
            docs-collection (:docs-collection base-names)
            collection (resolve-collection-for-role dataset-config role)
            include-fields (or (blank->nil (param-value params :include-fields))
                               (default-include-fields-for-role role docs-collection))
            query-by (or (blank->nil (param-value params :query-by))
                         (get default-query-by-by-role role))
            filter-by (blank->nil (param-value params :filter-by))
            sort-by-val (blank->nil (param-value params :sort-by))
            facet-by (blank->nil (param-value params :facet-by))
            limit (positive-int (param-value params :limit) 20 100)
            search (cond-> {:collection collection
                            :q q
                            :include_fields include-fields
                            :page 1
                            :per_page limit}
                     query-by   (assoc :query_by query-by)
                     filter-by  (assoc :filter_by filter-by)
                     sort-by-val (assoc :sort_by sort-by-val)
                     facet-by   (assoc :facet_by facet-by))
            ts-settings (ts-utils/make-ts-settings
                         {:tenant (:tenant dataset-config)
                          :dataset-config-key (:dataset-config-key dataset-config)})
            response (ts-client/multi-search ts-settings {:searches [search]} {})
            hits (normalize-search-hits response)
            facets (normalize-facets response)
            found-count (or (some :found (get response :results)) (count hits))]
        (edn-response (cond-> {:found-count found-count
                               :hits hits
                               :collection collection
                               :role role
                               :query-by query-by
                               :dataset-config-key (:dataset-config-key dataset-config)}
                        facets (assoc :facets facets)
                        filter-by (assoc :filter-by filter-by)
                        sort-by-val (assoc :sort-by sort-by-val))
                      200))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug typesense-search failed")
        (edn-response {:error "Internal server error"} 500)))))

(defn debug-typesense-get-handler
  "GET /api/debug/typesense-get — fetch documents by id like `Read`.
   Big fields are stripped by default — opt in via `include-fields`.

   Query params:
   - tenant + dataset-config-key (or dataset-ref) — required
   - role — required (same enum as search)
   - ids — comma-separated id list. ID field is `doc_num` for docs,
     `chunk_id` for everything else.
   - range — alternative to ids, only valid for `chunks`.
     Format: `<doc_num>:<start>-<end>` (inclusive). Returns chunks
     in `chunk_index` order.
   - include-fields — optional; defaults per role to small fields only"
  [ring-req]
  (let [params (request-query-params ring-req)]
    (try
      (let [role (parse-role (or (param-value params :role)
                                 (throw (ex-info "Missing required query param: role"
                                                 {:status 400}))))
            ids (parse-ids (param-value params :ids))
            range-raw (blank->nil (param-value params :range))
            _ (when (and (nil? ids) (nil? range-raw))
                (throw (ex-info "Provide either `ids` (comma-separated) or `range` (chunks only)"
                                {:status 400})))
            _ (when (and range-raw (not= role :chunks))
                (throw (ex-info "`range` is only supported for role=chunks"
                                {:status 400 :role role})))
            {:keys [dataset-config]} (resolve-dataset params)
            base-names (collections/get-or-generate-collection-names dataset-config)
            docs-collection (:docs-collection base-names)
            collection (resolve-collection-for-role dataset-config role)
            include-fields (or (blank->nil (param-value params :include-fields))
                               (default-include-fields-for-role role docs-collection))
            filter-by (if range-raw
                        (let [[doc-num range-part] (str/split range-raw #":" 2)
                              [start end] (when range-part
                                            (mapv #(Long/parseLong (str/trim %))
                                                  (str/split range-part #"-" 2)))]
                          (when-not (and doc-num start end (<= start end))
                            (throw (ex-info (str "Invalid range. Expected `<doc_num>:<start>-<end>`, got "
                                                 (pr-str range-raw))
                                            {:status 400 :range range-raw})))
                          (str "doc_num:=`" (ts-escape-value doc-num) "` && "
                               "chunk_index:>=" start " && chunk_index:<=" end))
                        (ids-filter-by (id-field-for-role role) ids))
            per-page (cond
                       ids (min 100 (count ids))
                       :else 100)
            sort-by-val (when range-raw "chunk_index:asc")
            search (cond-> {:collection collection
                            :q "*"
                            :include_fields include-fields
                            :filter_by filter-by
                            :page 1
                            :per_page per-page}
                     sort-by-val (assoc :sort_by sort-by-val))
            ts-settings (ts-utils/make-ts-settings
                         {:tenant (:tenant dataset-config)
                          :dataset-config-key (:dataset-config-key dataset-config)})
            response (ts-client/multi-search ts-settings {:searches [search]} {})
            documents (mapv :document (normalize-search-hits response))
            found-count (or (some :found (get response :results)) (count documents))]
        (edn-response {:found-count found-count
                       :documents documents
                       :collection collection
                       :role role
                       :dataset-config-key (:dataset-config-key dataset-config)
                       :filter-by filter-by}
                      200))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug typesense-get failed")
        (edn-response {:error "Internal server error"} 500)))))

;; =============================================================================
;; Multi-strategy retrieval — option (b)
;;
;; Wraps the production retrieval skill (digdir.skills.builtin.retrieval) so
;; callers can exercise the merged phrase+metadata+content (+enrichment) path
;; with ColBERT rerank etc. *without* going through an API-key auth flow.
;;
;; This is the "rich" sibling to ts-search/ts-get. It bypasses the
;; exploration primitives and runs production retrieval semantics in one
;; call.
;;
;; **Disabled by default.** Set `RAG_TS_RETRIEVE_ENABLED=true` (or 1/yes/on)
;; to enable. The default-off stance is deliberate: during the first sweeps
;; we want to isolate the contribution of the exploration primitives. With
;; this tool live, the agent (or a sweep harness) could call it instead and
;; muddle the measurement.
;; =============================================================================

(defn ts-retrieve-enabled?
  "Public so tests can redef. Returns true when the env var
   RAG_TS_RETRIEVE_ENABLED is set to one of the standard truthy values."
  []
  (contains? #{"true" "1" "yes" "on"}
             (some-> (System/getenv "RAG_TS_RETRIEVE_ENABLED")
                     str/lower-case)))

(defn- parse-enrichment-types
  "Comma-separated enrichment type names → vector of canonical keywords.
   Strings come in like `hypothetical-questions,verified-phrases` (without
   the `enrichment/` prefix — that prefix is search-tool-internal)."
  [raw]
  (when-let [s (blank->nil raw)]
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         (mapv keyword)
         not-empty)))

(defn- parse-bool
  [v default-val]
  (let [s (some-> v str str/lower-case)]
    (cond
      (nil? s) default-val
      (contains? #{"true" "1" "yes" "on"} s) true
      (contains? #{"false" "0" "no" "off"} s) false
      :else default-val)))

(defn- parse-positive-int
  [v default-val]
  (cond
    (number? v) (long v)
    (string? v) (try (let [n (Long/parseLong (str/trim v))]
                       (if (pos? n) n default-val))
                     (catch Exception _ default-val))
    :else default-val))

(defn debug-config-refresh-handler
  "POST /api/debug/config/refresh — invalidate the in-memory datahike snapshot
   and reopen the connection so external config writes (`bb config-set`,
   `bb dump-import`) take effect without a JVM restart.

   No body required. Returns 204 on success. Debug-API-key gated."
  [_ring-req]
  (try
    (require 'digdir.data.db)
    (let [reconnect! (resolve 'digdir.data.db/reconnect!)]
      (reconnect!)
      {:status 204
       :headers {}
       :body ""})
    (catch Exception e
      (log/error e "Config refresh failed")
      (edn-response {:error (str "Config refresh failed: " (.getMessage e))} 500))))

(defn debug-typesense-retrieve-handler
  "GET /api/debug/typesense-retrieve — run the multi-strategy retrieval
   skill (phrase + metadata + content + optional enrichments + optional
   ColBERT rerank) against a dataset.

   **Disabled by default**; set `RAG_TS_RETRIEVE_ENABLED=true` to enable.

   Query params:
   - tenant + dataset-config-key (or dataset-ref) — required
   - queries — comma-separated list of query strings (or a single `q` param)
   - limit — per-strategy per-query cap (default 30)
   - retrieve-top-k — total merged-top-k cap (default 100)
   - max-per-document — diversity cap (skill default ≈ 10)
   - metadata-only — `true` (default) strips content_markdown
   - query-aware-boost — `true` (default)
   - rerank-with-colbert — `false` (default; ColBERT is a separate service)
   - enrichment-types — comma-separated, e.g.
     `hypothetical-questions,verified-phrases,fact-assertions`. When set,
     enrichment-search-targets is computed automatically."
  [ring-req]
  (let [params (request-query-params ring-req)]
    (try
      (when-not (ts-retrieve-enabled?)
        (throw (ex-info (str "typesense-retrieve is disabled. "
                             "Set RAG_TS_RETRIEVE_ENABLED=true to enable. "
                             "Default-off is intentional during isolation sweeps "
                             "— see plans/proposed/typesense-filesystem-tools-plan.md.")
                        {:status 503})))
      (let [queries-raw (or (blank->nil (param-value params :queries))
                            (blank->nil (param-value params :q))
                            (throw (ex-info "Missing required query param: queries (or q)"
                                            {:status 400})))
            queries (->> (str/split queries-raw #",")
                         (map str/trim)
                         (remove str/blank?)
                         vec)
            _ (when (empty? queries)
                (throw (ex-info "queries parameter resolved to empty list"
                                {:status 400 :queries queries-raw})))
            {:keys [dataset-config dataset-ref]} (resolve-dataset params)
            base-names (collections/get-or-generate-collection-names dataset-config)
            enrichment-types (parse-enrichment-types (param-value params :enrichment-types))
            enrichment-search-targets (when (seq enrichment-types)
                                        (enrichment-naming/enrichment-collection-names
                                         dataset-config enrichment-types))
            title-fields-raw (param-value params :title-fields)
            title-fields (when (and title-fields-raw (not (str/blank? title-fields-raw)))
                           (try (clojure.edn/read-string title-fields-raw)
                                (catch Exception _
                                  (throw (ex-info "Invalid --title-fields EDN"
                                                  {:status 400 :value title-fields-raw})))))
            chunk-content-fields-raw (param-value params :chunk-content-fields)
            chunk-content-fields (when (and chunk-content-fields-raw
                                            (not (str/blank? chunk-content-fields-raw)))
                                   (try (clojure.edn/read-string chunk-content-fields-raw)
                                        (catch Exception _
                                          (throw (ex-info "Invalid --chunk-content-fields EDN"
                                                          {:status 400 :value chunk-content-fields-raw})))))
            chunk-metadata-fields-raw (param-value params :chunk-metadata-fields)
            chunk-metadata-fields (when (and chunk-metadata-fields-raw
                                             (not (str/blank? chunk-metadata-fields-raw)))
                                    (try (clojure.edn/read-string chunk-metadata-fields-raw)
                                         (catch Exception _
                                           (throw (ex-info "Invalid --chunk-metadata-fields EDN"
                                                           {:status 400 :value chunk-metadata-fields-raw})))))
            doc-title-chunk-fanout (when-let [raw (param-value params :doc-title-chunk-fanout)]
                                     (parse-positive-int raw 3))
            auto-filter-rules-raw (param-value params :auto-filter-rules)
            auto-filter-rules (when (and auto-filter-rules-raw
                                         (not (str/blank? auto-filter-rules-raw)))
                                (try (clojure.edn/read-string auto-filter-rules-raw)
                                     (catch Exception _
                                       (throw (ex-info "Invalid --auto-filter-rules EDN"
                                                       {:status 400 :value auto-filter-rules-raw})))))
            merge-mode-raw (param-value params :merge-mode)
            merge-mode (when (and merge-mode-raw (not (str/blank? merge-mode-raw)))
                         (keyword (str/replace merge-mode-raw #"^:" "")))
            retrieval-mode-raw (param-value params :retrieval-mode)
            retrieval-mode (when (and retrieval-mode-raw (not (str/blank? retrieval-mode-raw)))
                             (keyword (str/replace retrieval-mode-raw #"^:" "")))
            rrf-k-raw (param-value params :rrf-k)
            rrf-k (when (and rrf-k-raw (not (str/blank? rrf-k-raw)))
                    (parse-positive-int rrf-k-raw 60))
            ;; Slice 23: user-intent first-pass union (server-side equivalent
            ;; of the harness `--user-intent-first-pass true` flow).
            user-intent (blank->nil (param-value params :user-intent))
            user-intent-union-enabled (parse-bool
                                        (param-value params :user-intent-union-enabled) false)
            user-intent-union-mode-raw (param-value params :user-intent-union-mode)
            user-intent-union-mode (when (and user-intent-union-mode-raw
                                              (not (str/blank? user-intent-union-mode-raw)))
                                     (keyword (str/replace user-intent-union-mode-raw #"^:" "")))
            user-intent-union-cap (when-let [raw (param-value params :user-intent-union-cap)]
                                    (parse-positive-int raw 3))
            user-intent-union-rrf-k (when-let [raw (param-value params :user-intent-union-rrf-k)]
                                      (parse-positive-int raw 60))
            ;; f85c984 — per-strategy ColBERT rerank fan-out.
            per-strategy-rerank? (parse-bool
                                   (param-value params :per-strategy-rerank) false)
            rerank-final-cap (when-let [raw (param-value params :rerank-final-cap)]
                               (parse-positive-int raw 40))
            ;; EDN-encoded retrieval-tuning maps (for agent-preset A/B work).
            strategy-weights-raw (param-value params :strategy-weights)
            strategy-weights (when (and strategy-weights-raw (not (str/blank? strategy-weights-raw)))
                               (try (clojure.edn/read-string strategy-weights-raw)
                                    (catch Exception _
                                      (throw (ex-info "Invalid --strategy-weights EDN"
                                                      {:status 400 :value strategy-weights-raw})))))
            strategy-caps-raw (param-value params :strategy-contribution-caps)
            strategy-contribution-caps (when (and strategy-caps-raw (not (str/blank? strategy-caps-raw)))
                                         (try (clojure.edn/read-string strategy-caps-raw)
                                              (catch Exception _
                                                (throw (ex-info "Invalid --strategy-contribution-caps EDN"
                                                                {:status 400 :value strategy-caps-raw})))))
            parameters (cond-> {:limit (parse-positive-int (param-value params :limit) 30)
                                :retrieve-top-k (parse-positive-int (param-value params :retrieve-top-k) 100)
                                :metadata-only (parse-bool (param-value params :metadata-only) true)
                                :query-aware-boost (parse-bool (param-value params :query-aware-boost) true)
                                :rerank-with-colbert (parse-bool (param-value params :rerank-with-colbert) false)
                                ;; auto-filter is intentionally exposed because it
                                ;; affects whether org/year extraction muddles
                                ;; isolation experiments. Off-by-default here so
                                ;; the debug tool gives raw retrieval output.
                                :auto-filter (parse-bool (param-value params :auto-filter) false)}
                         (param-value params :rerank-candidate-k)
                         (assoc :rerank-candidate-k
                                (parse-positive-int (param-value params :rerank-candidate-k) 40))

                         (param-value params :max-per-document)
                         (assoc :max-per-document
                                (parse-positive-int (param-value params :max-per-document) 10))

                         (seq enrichment-search-targets)
                         (assoc :enrichment-search-targets enrichment-search-targets)

                         (and (sequential? title-fields) (seq title-fields))
                         (assoc :title-fields (vec title-fields))

                         (and (sequential? chunk-content-fields) (seq chunk-content-fields))
                         (assoc :chunk-content-fields (vec chunk-content-fields))

                         (and (sequential? chunk-metadata-fields) (seq chunk-metadata-fields))
                         (assoc :chunk-metadata-fields (vec chunk-metadata-fields))

                         (and doc-title-chunk-fanout (pos? (long doc-title-chunk-fanout)))
                         (assoc :doc-title-chunk-fanout (long doc-title-chunk-fanout))

                         (and (sequential? auto-filter-rules) (seq auto-filter-rules))
                         (assoc :auto-filter-rules (vec auto-filter-rules))

                         merge-mode
                         (assoc :merge-mode merge-mode)

                         retrieval-mode
                         (assoc :retrieval-mode retrieval-mode)

                         rrf-k
                         (assoc :rrf-k rrf-k)

                         user-intent-union-enabled
                         (assoc :user-intent-union-enabled true)

                         user-intent-union-mode
                         (assoc :user-intent-union-mode user-intent-union-mode)

                         user-intent-union-cap
                         (assoc :user-intent-union-cap (long user-intent-union-cap))

                         user-intent-union-rrf-k
                         (assoc :user-intent-union-rrf-k (long user-intent-union-rrf-k))

                         per-strategy-rerank?
                         (assoc :per-strategy-rerank? true)

                         rerank-final-cap
                         (assoc :rerank-final-cap (long rerank-final-cap))

                         (map? strategy-weights)
                         (assoc :strategy-weights strategy-weights)

                         (map? strategy-contribution-caps)
                         (assoc :strategy-contribution-caps strategy-contribution-caps))
            input {:inputs (cond-> {:queries queries
                                    :docs-collection (:docs-collection base-names)
                                    :chunks-collection (:chunks-collection base-names)
                                    :phrases-collection (:phrases-collection base-names)}
                             user-intent (assoc :user-intent user-intent))
                   :parameters parameters
                   :skill-params {:tenant (:tenant dataset-config)
                                  :dataset-config-key (:dataset-config-key dataset-config)
                                  :runtime-config-key (:dataset-config-key dataset-config)}
                   :dataset-ref dataset-ref}
            result (retrieval-skill/execute-retrieval input)]
        (if (:error result)
          (edn-response {:error (-> result :error :error-message)
                         :error-type (-> result :error :error-type)
                         :error-data (-> result :error :error-data)}
                        500)
          (edn-response {:found-count (count (get-in result [:outputs :chunks]))
                         :chunks (get-in result [:outputs :chunks])
                         :search-attribution (get-in result [:outputs :search-attribution])
                         :collections (select-keys base-names
                                                   [:docs-collection
                                                    :chunks-collection
                                                    :phrases-collection])
                         :enrichment-targets enrichment-search-targets
                         :queries queries
                         :dataset-config-key (:dataset-config-key dataset-config)
                         :parameters parameters}
                        200)))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug typesense-retrieve failed")
        (edn-response {:error "Internal server error"} 500)))))

(defn debug-query-planner-handler
  "GET /api/debug/query-planner — run the query-planner skill against
   a single user query and return the expanded search phrases. Used by
   bb v3-score --expand-queries N and by ad-hoc measurement of how well
   LLM-driven query expansion compares to the hand-crafted upper bound
   (see plans/in-progress/target-optimal-baseline-v3/16-query-expansion-handcrafted-upper-bound.md).

   Query params:
   - tenant — required (used to resolve LLM credentials via cfg/get)
   - query — required, the user's natural-language question
   - max-phrases — optional, default 7
   - temperature — optional, default 0.1
   - dataset-config-key (or dataset-ref) — optional; required for
     corpus-aware expansion-mode so the phrases collection can be resolved
     for the PRF harvest
   - expansion-mode — optional: blind (default) | corpus-aware-1hop |
     corpus-aware-2hop
   "
  [ring-req]
  (let [params (request-query-params ring-req)]
    (try
      (let [tenant (or (blank->nil (param-value params :tenant))
                       (throw (ex-info "Missing required query param: tenant" {:status 400})))
            query (or (blank->nil (param-value params :query))
                      (throw (ex-info "Missing required query param: query" {:status 400})))
            max-phrases (when-let [raw (param-value params :max-phrases)]
                          (Long/parseLong (str raw)))
            temperature (when-let [raw (param-value params :temperature)]
                          (Double/parseDouble (str raw)))
            expansion-mode (some-> (blank->nil (param-value params :expansion-mode))
                                   keyword)
            expansion-variant (some-> (blank->nil (param-value params :expansion-variant))
                                      keyword)
            ;; Resolve the phrases collection only when a dataset is selected
            ;; (corpus-aware modes need it; blind mode does not). Reuses the
            ;; same role→collection resolution as the ts-search endpoint.
            dataset-selected? (or (blank->nil (param-value params :dataset-config-key))
                                  (blank->nil (param-value params :dataset-ref)))
            phrases-collection (when dataset-selected?
                                 (let [{:keys [dataset-config]} (resolve-dataset params)]
                                   (resolve-collection-for-role dataset-config :phrases)))
            parameters (cond-> {}
                         max-phrases (assoc :max-phrases max-phrases)
                         temperature (assoc :temperature temperature)
                         expansion-mode (assoc :expansion-mode expansion-mode)
                         expansion-variant (assoc :expansion-variant expansion-variant))
            inputs (cond-> {:query query}
                     phrases-collection (assoc :phrases-collection phrases-collection))
            result (query-planner-skill/execute-query-planner
                    {:inputs inputs
                     :parameters parameters
                     :skill-params {:tenant tenant}})]
        (if (:error result)
          (edn-response {:error (-> result :error :error-message)
                         :error-type (-> result :error :error-type)}
                        500)
          (edn-response {:queries (get-in result [:outputs :queries])
                         :user-intent (get-in result [:outputs :user-intent])
                         :phrase-count (get-in result [:metadata :phrase-count])
                         :had-llm-intent? (boolean (get-in result [:metadata :had-llm-intent?]))
                         :fallback? (boolean (get-in result [:metadata :fallback]))
                         :expansion-mode (get-in result [:metadata :expansion-mode])
                         :expansion-variant (get-in result [:metadata :expansion-variant])
                         :corpus-aware-fallback? (boolean (get-in result [:metadata :corpus-aware-fallback]))
                         :hop1-docs (get-in result [:metadata :hop1-docs])
                         :harvested-phrase-count (get-in result [:metadata :harvested-phrase-count])
                         :model-used (get-in result [:metadata :model-used])
                         :original-query query}
                        200)))
      (catch clojure.lang.ExceptionInfo e
        (let [status (or (:status (ex-data e)) 500)]
          (edn-response {:error (.getMessage e)} status)))
      (catch Throwable t
        (log/error t "Debug query-planner failed")
        (edn-response {:error "Internal server error"} 500)))))
