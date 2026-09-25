(ns digdir.mcp.tools
  "MCP tool listing and invocation.

   Each tool corresponds to one (agent × allowed-skill-graph) pair. Tool
   listings are filtered by the calling API key's `:agent-refs` and
   `:skill-graphs` scope. Tool invocation maps to
   `digdir.skills.invoke/invoke-rag` and persists the resulting turn via
   the same Datahike store the API already uses."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes.endpoints.debug :as debug]
            [digdir.api.util :as api-util]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.rag.filters :as filters]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]
            [nano-id.core :refer [nano-id]]
            [taoensso.telemere :as t]))

;; ----------------------------------------------------------------------------
;; Malli -> minimal JSON Schema
;; ----------------------------------------------------------------------------

(defn- malli-type->json-type
  [malli-type]
  (case malli-type
    :string "string"
    :int "integer"
    :double "number"
    :number "number"
    :boolean "boolean"
    :map "object"
    :vector "array"
    "string"))

(defn- malli->json-schema
  "Translate the small subset of Malli used in skill-graph input schemas
   into JSON Schema. Supports :map, :vector, primitive types, and the
   `{:optional true}` properties marker."
  [schema]
  (cond
    (keyword? schema) {"type" (malli-type->json-type schema)}

    (and (vector? schema) (= :map (first schema)))
    (let [fields (rest schema)
          [props required]
          (reduce
            (fn [[props req] field]
              (let [[k & rest-field] field
                    has-opts? (and (seq rest-field) (map? (first rest-field)))
                    opts (when has-opts? (first rest-field))
                    inner (if has-opts? (second rest-field) (first rest-field))
                    inner-schema (malli->json-schema inner)
                    optional? (:optional opts)]
                [(assoc props (name k) inner-schema)
                 (if optional? req (conj req (name k)))]))
            [{} []]
            fields)]
      (cond-> {"type" "object"
               "properties" props}
        (seq required) (assoc "required" required)))

    (and (vector? schema) (= :vector (first schema)))
    {"type" "array"
     "items" (malli->json-schema (second schema))}

    (and (vector? schema) (= :string (first schema)))
    (let [opts (when (and (> (count schema) 1) (map? (second schema)))
                 (second schema))]
      (cond-> {"type" "string"}
        (:min opts) (assoc "minLength" (:min opts))
        (:max opts) (assoc "maxLength" (:max opts))))

    :else {"type" "string"}))

;; ----------------------------------------------------------------------------
;; Tool naming
;; ----------------------------------------------------------------------------

(def ^:private name-separator "__")

(defn- ->keyword
  "Normalize a skill-graph identifier to a keyword. The agents DB stores
   skill-graph IDs as strings (e.g. \"builtin/agent-rag-graph-bundled\"),
   but the skill-graphs registry keys them as keywords. Callers may also
   pass keywords directly."
  [id]
  (cond
    (keyword? id) id
    (string? id)  (keyword id)
    :else         nil))

(defn- short-name
  "Strip the namespace from a skill-graph id, regardless of whether the id
   came in as a keyword or a 'ns/name' string."
  [id]
  (cond
    (keyword? id) (name id)
    (string? id)  (let [slash (.indexOf ^String id "/")]
                    (if (neg? slash) id (subs id (inc slash))))
    :else         nil))

;; ----------------------------------------------------------------------------
;; The public identifier (#122)
;;
;; One string is the MCP tool name, the OpenAI model id, and - through MCPO - an
;; OpenAPI path segment. Two properties it therefore needs:
;;
;;   no `/`   an identifier that becomes a URL path segment cannot carry a
;;            slash and rely on every client escaping it. Agent ids do carry
;;            one (`builtin/agent-rag-agent`), so the wire form swaps the first
;;            slash for a dot and the parser swaps it back. Exactly one slash
;;            separates namespace from name, which is what makes it reversible.
;;
;;   no `skill-graph`  the word has three live public meanings (Agent Skills at
;;            agentskills.io, A2A's AgentCard.skills[], and ours) and we adopted
;;            it citing a source that already meant something else. On the wire
;;            the second axis is a MODE of the agent. Internal names are
;;            unchanged - this is the public surface only. See
;;            decisions/public-identifiers.md.
;; ----------------------------------------------------------------------------

(def ^:private wire-namespace-separator
  "Replaces the `/` in an agent id for the public identifier."
  ".")

(defn agent-id->wire
  "Slash-free form of an agent id, for use inside a public identifier.
   `builtin/agent-rag-agent` -> `builtin.agent-rag-agent`."
  [agent-id]
  (str/replace-first (str agent-id) "/" wire-namespace-separator))

(defn wire->agent-id
  "Inverse of `agent-id->wire`. Reverses the FIRST separator only, mirroring
   `short-name`'s first-slash rule, so a name segment containing a dot survives."
  [wire-agent-id]
  (str/replace-first (str wire-agent-id) wire-namespace-separator "/"))

(defn- tool-name
  [agent-id skill-graph-id]
  (str (agent-id->wire agent-id) name-separator (short-name skill-graph-id)))

(defn parse-tool-name
  "Split a public tool name into [agent-id mode]. Returns nil when the
   separator is missing.

   The agent id comes back in its real, slash-carrying form, so callers
   resolve against the agent registry exactly as before and only this
   namespace knows the wire encoding.

   Public so sibling adapters (`api.routes.endpoints.openai-compat`)
   reuse the same parser instead of reinventing the convention."
  [tool-name-str]
  (let [idx (.indexOf ^String tool-name-str ^String name-separator)]
    (when (pos? idx)
      [(wire->agent-id (subs tool-name-str 0 idx))
       (subs tool-name-str (+ idx (count name-separator)))])))

;; ----------------------------------------------------------------------------
;; Tool listing
;; ----------------------------------------------------------------------------

(defn- visible-agents
  [principal]
  (let [agent-refs (set (:api-key/agent-refs principal))
        all (agents-db/list-enabled-agents @(config-db/get-conn))]
    (if (seq agent-refs)
      (filterv #(contains? agent-refs (:id %)) all)
      all)))

(defn- visible-skill-graphs-for-agent
  [agent principal]
  (let [key-graphs (set (keep ->keyword (:api-key/skill-graphs principal)))
        allowed (vec (keep ->keyword (or (:allowed-skill-graphs agent) [])))]
    (if (seq key-graphs)
      (filterv #(contains? key-graphs %) allowed)
      allowed)))

;; =============================================================================
;; Description surface (#123)
;;
;; MCP has three primitives - tools, resources, prompts - and we implement one.
;; That absence was accidental rather than decided (#125 records the rest as a
;; boundary). What follows is the half that costs nothing: saying what a tool is
;; called and what it returns, telling a client how to use the server at all,
;; and linking the documents an answer cited. The resource_link blocks are the
;; resource affordance existing before the capability does - a client can follow
;; a citation today without us declaring `resources`.
;; =============================================================================

(def server-instructions
  "The `instructions` field returned by `server/discover`: how to use this
   server.

   Lives here rather than in the transport because it describes the tool
   surface, and should be edited alongside it.

   Was returned by `initialize` until the 2026-07-28 migration (#146) removed
   that method; `DiscoverResult` has a first-class `instructions` field, so the
   string moved rather than being dropped. This is the only place on the wire
   that documents the tool naming, the `conversation_id` handle and the
   `isError` vs `-32603` split."
  (str
    "This server exposes one tool per (agent, mode) pair, named "
    "`<agent>__<mode>`, where `<agent>` is the agent id with its namespace "
    "separator written as a dot - `builtin.agent-rag-agent__agent-rag-graph-bundled`. "
    "Call `tools/list` to see which pairs this API key can reach.\n\n"
    "Ask a question with the `query` argument - `user-query` is accepted as an "
    "alias. The answer comes back as text, "
    "with `structuredContent` carrying the retrieved chunks, the queries that "
    "were run, and `conversation_id`.\n\n"
    "To continue a conversation, pass the `conversation_id` from the previous "
    "response back in the next call. Earlier turns are retained server-side and "
    "supplied to the model automatically; omit it to start fresh.\n\n"
    "Scope a call with `tenant` and `dataset_config_key` when the API key or "
    "agent does not already fix one.\n\n"
    "Errors: a result with `isError: true` carries a message you can act on - "
    "fix the arguments or choose another tool. A JSON-RPC error means the "
    "request could not be dispatched (-32602) or the server failed (-32603); "
    "neither is worth retrying unchanged."))

(def ^:private tool-output-schema
  "What `structuredContent` actually contains. We have always returned it and
   never declared it, so a client had to discover the shape by calling."
  {"type" "object"
   "properties"
   {"conversation_id" {"type" "string"
                       "description" "Handle for this conversation; pass it back to continue."}
    "chunks" {"type" "array"
              "description" "Retrieved source chunks the answer drew on."
              "items" {"type" "object"
                       "properties" {"chunk_id" {"type" "string"}
                                     "doc_num" {"type" "string"}
                                     "chunk_index" {"type" "integer"}
                                     "content_length" {"type" "integer"}
                                     "total_chunks" {"type" "integer"}
                                     "title" {"type" "string"}
                                     ;; CONDITIONAL, and said so rather than
                                     ;; implied (#504). A chunk carries a url
                                     ;; only when its source has one to give:
                                     ;; website corpora always do, folder
                                     ;; corpora only when the dataset supplies a
                                     ;; base URL to build them from. A local
                                     ;; markdown corpus has no public address,
                                     ;; and inventing one would be worse than
                                     ;; omitting it. Clients must treat this as
                                     ;; optional — it is absent, not empty.
                                     ;;
                                     ;; The WORDING below is unchanged by #506
                                     ;; and did not need changing: it already
                                     ;; described this end state. What changed is
                                     ;; that the folder branch became REACHABLE.
                                     ;; At #504 "only when the dataset supplies a
                                     ;; base URL" was true of folder corpora the
                                     ;; way any statement about an empty set is
                                     ;; true — there was no route to supply one.
                                     ;; #506 added `:folder-base-url`, so the
                                     ;; condition now discriminates between two
                                     ;; cases that both occur, which is what the
                                     ;; sentence always claimed.
                                     "url" {"type" "string"
                                            "description" (str "Public URL of the source document. PRESENT ONLY when the "
                                                               "corpus has one: website-sourced chunks always carry it, "
                                                               "folder-sourced chunks only when the dataset supplies a base "
                                                               "URL. Absent otherwise — do not build a link without checking.")}
                                     "metadata" {"type" "object"}}}}
    "queries" {"type" "array"
               "description" "Search queries that were run."
               "items" {"type" "string"}}
    "search_attribution" {"type" "object"
                          "description" "Which retrieval strategy contributed each hit."}
    "filters_applied" {"type" "array"
                       "description" (str "Each distinct filter retrieval ran with. `source` is explicit "
                                          "(caller or model), auto (detected from the query) or merged. "
                                          "`auto_detected` is the detected part; `auto_detected_dropped` "
                                          "means it found nothing and the search ran without it.")
                       "items" {"type" "object"}}
    "clarification" {"type" "object"
                     "description" "Present when the agent needs a clarifying answer before proceeding."}}
   "required" ["conversation_id"]})

(defn- tool-title
  "Human-facing display name, distinct from the `<agent>__<graph>` identifier.
   MCP clients show this to people; the name is for addressing."
  [agent skill-graph]
  (let [graph-name (:name skill-graph)]
    (str (or (:name agent) (:id agent))
         (when graph-name (str " \u2014 " graph-name)))))

(defn- chunk->resource-link
  "One MCP `resource_link` content block for the document a chunk came from.

   Prefers the document's real URL. Falls back to a stable `digdir://` URI so a
   document without one still gets a followable, dedupable identity rather than
   being dropped."
  [{:keys [doc_num title url metadata]}]
  (let [uri (or (not-empty url)
                (not-empty (:url metadata))
                (str "digdir://doc/" doc_num))]
    {:type "resource_link"
     :uri uri
     :name (or (not-empty title) (str "Document " doc_num))
     :mimeType "text/html"}))

(defn- resource-links
  "Resource links for the documents an answer cited, one per DOCUMENT rather
   than one per chunk - several chunks of the same document are one source to a
   reader. Order follows first appearance, so the best-ranked document leads."
  [chunks]
  (->> chunks
       (filter #(or (not-empty (:doc_num %)) (not-empty (:url %))))
       (reduce (fn [{:keys [seen] :as acc} chunk]
                 (let [k (or (not-empty (:doc_num chunk)) (:url chunk))]
                   (if (contains? seen k)
                     acc
                     (-> acc
                         (update :seen conj k)
                         (update :out conj (chunk->resource-link chunk))))))
               {:seen #{} :out []})
       :out))

;; ----------------------------------------------------------------------------
;; The query argument (#159)
;;
;; Internally the shared graph input schema calls it `:user-query`, and it stays
;; that way. On the wire it is `query`, for three reasons that were already true
;; before this was decided: server-instructions says `query`, the missing-query
;; error says `query`, and the reader below checks `query` first. The schema was
;; the outlier, not the majority. The `user-` prefix also distinguishes a user
;; query from a system query, which is a distinction a client does not have.
;;
;; `user-query` remains accepted as an alias - deliberately, and documented in
;; the property description rather than surviving as incidental leniency that
;; hides a disagreement between two authoritative surfaces.
;; ----------------------------------------------------------------------------

(def public-query-property
  "The argument name advertised to clients."
  "query")

(def query-aliases
  "Argument names `invoke-tool` accepts for the user's question, in precedence
   order. `query` is what we advertise; the rest are accepted so that callers
   written against the internal name, or against an older tool listing, keep
   working. Documented in the advertised schema, not silent."
  ["query" "user-query"])

(defn read-query-argument
  "Read the user's question from tool arguments, honouring `query-aliases` in
   order and accepting either string or keyword keys."
  [arguments]
  (some (fn [k]
          (let [v (or (get arguments k) (get arguments (keyword k)))]
            (when-not (str/blank? v) v)))
        query-aliases))

(defn- with-public-query-property
  "Rename the internal `user-query` property to the advertised `query` in a
   tool's input schema, in both the declared-schema and fallback branches, so
   the advertisement cannot disagree with itself depending on whether a graph
   declared an input schema."
  [json-schema]
  (let [rename (fn [coll] (mapv #(if (= "user-query" %) public-query-property %) coll))]
    (cond-> json-schema
      (get-in json-schema ["properties" "user-query"])
      (update "properties"
              (fn [props]
                (-> props
                    (dissoc "user-query")
                    (assoc public-query-property
                           (assoc (get props "user-query")
                                  "description"
                                  (str "The question to ask. Also accepted as `user-query`, "
                                       "the name used internally."))))))

      (get json-schema "required")
      (update "required" rename))))

(def ^:private conversation-properties
  "Protocol-level arguments every tool accepts, merged into whatever input
   schema the skill graph declares.

   These are read by `invoke-tool` and always have been, but a model generates
   tool arguments FROM the advertised inputSchema — it will never emit a field
   the schema does not mention. Leaving them out made multi-turn unreachable
   through the surface we advertise it on (#116)."
  {"conversation_id"
   {"type" "string"
    "description" (str "Handle for an ongoing conversation. Omit on the first "
                       "call; the response returns the handle in "
                       "structuredContent.conversation_id. Pass it back on "
                       "later calls to continue the same conversation — prior "
                       "turns are retained server-side and supplied to the "
                       "model automatically.")}
   "tenant"
   {"type" "string"
    "description" "Dataset tenant to scope this call to. Optional when the API key or agent already fixes a scope."}
   "dataset_config_key"
   {"type" "string"
    "description" "Dataset configuration key to scope this call to. Optional, as for `tenant`."}
   "overrides"
   {"type" "object"
    "description" (str "Per-call skill-parameter overrides, with kebab-case keys such as "
                       "`retrieve-top-k` and `retrieve-filter-by`. Optional.")}})

(def ^:private conversation-description-suffix
  " Multi-turn: the response carries structuredContent.conversation_id; pass it back as the conversation_id argument to continue the same conversation, and earlier turns are supplied to the model automatically."
)

(defn- with-protocol-properties
  "Merge the protocol-level arguments into a tool's advertised input schema.
   Declared properties win, so a graph that names one of these itself keeps its
   own description and type."
  [json-schema]
  (update json-schema "properties" #(merge conversation-properties %)))

(defn- skill-graph-tool
  [agent skill-graph-id]
  (let [skill-graph (skills-api/get-skill-graph-info skill-graph-id)
        input-schema (:input-schema skill-graph)
        json-schema (with-protocol-properties
                      (with-public-query-property
                       (if input-schema
                        (malli->json-schema input-schema)
                        ;; Fallback shape used by all preserved tools when
                        ;; a skill graph hasn't declared :input-schema yet.
                        {"type" "object"
                         "properties" {"user-query" {"type" "string"}}
                         "required" ["user-query"]})))
        agent-desc (:description agent)
        graph-name (:name skill-graph)
        graph-desc (:description skill-graph)
        default? (= skill-graph-id (->keyword (:default-skill-graph agent)))]
    (when skill-graph
      {:name (tool-name (:id agent) skill-graph-id)
       :title (tool-title agent skill-graph)
       :outputSchema tool-output-schema
       :description (str (or agent-desc (:name agent))
                         (when graph-name (str " — " graph-name))
                         (when graph-desc (str ": " graph-desc))
                         conversation-description-suffix)
       :inputSchema json-schema
       :_meta (cond-> {:agent-id (:id agent)
                       ;; The wire calls this axis a mode (#122); the value is
                       ;; still the internal skill-graph id.
                       :mode (short-name skill-graph-id)}
                default? (assoc :default true))})))

(defn tool-name-report
  "Check the public identifiers a set of agents would produce.

   The MCP plan promised this guard and never built it: nothing stops two
   agents producing the same tool name, and the first symptom would be one
   agent silently shadowing another on both the MCP and the /v1 surface.

   Returns {:ok bool :collisions [...] :round-trip-failures [...] :checked n}.

   `:round-trip-failures` guards the encoding itself: every generated name must
   parse back to the agent id it was built from. That is what keeps the
   slash-for-dot swap honest if an agent id ever arrives in a shape the
   first-separator rule does not reverse."
  [agents]
  (let [pairs (for [agent agents
                    graph-id (keep ->keyword (:allowed-skill-graphs agent))]
                {:agent-id (:id agent)
                 :mode (short-name graph-id)
                 :tool-name (tool-name (:id agent) graph-id)})
        collisions (->> pairs
                        (group-by :tool-name)
                        (filter (fn [[_ v]] (< 1 (count v))))
                        (mapv (fn [[nm v]]
                                {:tool-name nm
                                 :produced-by (mapv #(select-keys % [:agent-id :mode]) v)})))
        round-trip-failures (->> pairs
                                 (remove (fn [{:keys [agent-id tool-name]}]
                                           (= agent-id (first (parse-tool-name tool-name)))))
                                 (mapv #(select-keys % [:agent-id :tool-name])))]
    {:ok (and (empty? collisions) (empty? round-trip-failures))
     :collisions collisions
     :round-trip-failures round-trip-failures
     :checked (count pairs)}))

(defn list-tools
  "Return the full MCP tool list visible to `principal`. Each tool maps to
   exactly one (agent × allowed-skill-graph) pair."
  [principal]
  (skills-api/initialize!)
  (vec
    (for [agent (visible-agents principal)
          skill-graph-id (visible-skill-graphs-for-agent agent principal)
          :let [tool (skill-graph-tool agent skill-graph-id)]
          :when tool]
      tool)))

;; ----------------------------------------------------------------------------
;; Tool resolution
;; ----------------------------------------------------------------------------

(defn resolve-agent
  "Look up an agent by id and authorize the principal against it.
   Returns `{:agent agent}` on success or `{:error {...}}` on any
   failure (not found, disabled, or principal not authorized).

   Public so sibling adapters reuse the same authorization chain
   (e.g. the OpenAI-compat /v1/chat/completions handler)."
  [agent-id principal]
  (let [agent-refs (set (:api-key/agent-refs principal))
        agent (agents-db/get-agent @(config-db/get-conn) agent-id)]
    (cond
      (nil? agent) {:error {:code "agent_not_found"
                            :message (str "Agent not found: " agent-id)}}
      (not (:enabled? agent)) {:error {:code "agent_disabled"
                                       :message (str "Agent is disabled: " agent-id)}}
      (and (seq agent-refs) (not (contains? agent-refs agent-id)))
      {:error {:code "agent_not_authorized"
               :message (str "API key cannot access agent: " agent-id)}}
      :else {:agent agent})))

(defn resolve-skill-graph-id
  "Look up a skill-graph by short name within an agent's allowed set,
   authorizing the principal's allowed-skill-graphs along the way.
   Returns `{:skill-graph-id kw}` on success or `{:error {...}}`.

   Public for sibling-adapter reuse."
  [agent short-name-str principal]
  (let [key-graphs (set (keep ->keyword (:api-key/skill-graphs principal)))
        allowed (keep ->keyword (:allowed-skill-graphs agent))
        match (some #(when (= short-name-str (short-name %)) %) allowed)]
    (cond
      (nil? match) {:error {:code "mode_not_allowed"
                            :message (str "Mode not available for agent: " short-name-str)}}
      (and (seq key-graphs) (not (contains? key-graphs match)))
      {:error {:code "mode_not_authorized"
               :message (str "API key cannot use mode: " (short-name match))}}
      :else {:skill-graph-id match})))

(defn- env-default-scope
  "Fallback scope from environment vars. Mirrors how the deleted
   `/api/rag` endpoint resolved its tenant/dataset-config-key when the
   request didn't carry them. Returns nil when env is not set."
  []
  (let [tenant (System/getenv "TENANT")
        dataset-config-key (System/getenv "DATASET_CONFIG_KEY")]
    (when (and tenant dataset-config-key)
      {:tenant tenant :dataset-config-key dataset-config-key})))

(defn- scope-key
  "Identity of a dataset scope, for set membership."
  [s]
  [(:tenant s) (:dataset-config-key s)])

(defn pick-dataset-scope
  "Pick the (tenant, dataset-config-key) for this call.

   THE API KEY'S GRANT IS THE FLOOR (#464). A key's `:dataset-scopes`, when it
   has any, bound every dataset this call can reach. An agent's
   `:allowed-dataset-scopes` can only NARROW that further; it can never widen
   it, and neither can an explicit `tenant` / `dataset_config_key` in tool
   arguments.

   That was not true before. The explicit-argument path was reached only when
   the agent declared no scopes, and in that branch the arguments were accepted
   WITHOUT being checked against the key's grant — so confinement depended on
   the AGENT declaring scopes rather than on the KEY. All three shipped agents
   declare none, so it was the default path, not a corner. The REST path
   (`digdir.api.context/select-request-dataset-ref!`) never had the gap, and
   this now mirrors its contract; `dataset-scope-parity-test` drives both from
   one table so they cannot diverge again.

   Resolution:
     1. `effective` = the key's granted scopes, narrowed by the agent's
        declaration when it has one.
     2. An explicit scope in arguments must be a member of the key's grant, and
        of the agent's declaration when it has one. Otherwise
        `dataset_not_authorized`.
     3. With no explicit scope, the first effective scope wins, else the
        env-var default, else `no_dataset_scope`.

   ⚠️ A key with NO `:dataset-scopes` is UNRESTRICTED here, deliberately and
   unchanged. That is this surface's convention on every other axis — empty
   `:agent-refs` reaches any agent (`resolve-agent`), empty `:skill-graphs` any
   mode (`resolve-skill-graph-id`), empty `:allowed-config-keys` any config node
   (`api.context/resolve-request-config-node!`) — and the env-var default below
   exists to serve exactly that deployment. The REST path instead answers 401
   for a scopeless key, so the two still differ THERE; closing that difference
   would silently revoke MCP access from every key without scopes, which is a
   policy decision with real blast radius rather than a bug fix. It is recorded
   on #464 and deliberately not taken here.

   Returns `{:scope {...}}` on success or `{:error {...}}` on failure."
  [agent principal arguments]
  (let [tenant (or (get arguments "tenant") (get arguments :tenant))
        dataset_config_key (or (get arguments "dataset_config_key")
                                (get arguments :dataset_config_key))
        agent-scopes (vec (or (:allowed-dataset-scopes agent) []))
        key-scopes (vec (or (:api-key/dataset-scopes principal) []))
        key-granted (set (map scope-key key-scopes))
        ;; Empty grant = unrestricted on this axis; see the warning above.
        key-restricted? (boolean (seq key-granted))
        arg-scope (when (and tenant dataset_config_key)
                    {:tenant tenant :dataset-config-key dataset_config_key})
        ;; The floor, narrowed by the agent's declaration.
        effective (cond
                    (and (seq agent-scopes) key-restricted?)
                    (vec (filter #(contains? key-granted (scope-key %)) agent-scopes))

                    (seq agent-scopes) agent-scopes
                    :else key-scopes)
        effective-keys (set (map scope-key effective))]
    (cond
      arg-scope
      (cond
        ;; Checked FIRST and independently of the agent, because this is the
        ;; check that was missing: the agent's declaration must not be what
        ;; decides whether the key's grant is honoured.
        (and key-restricted? (not (contains? key-granted (scope-key arg-scope))))
        {:error {:code "dataset_not_authorized"
                 :message (str "API key is not allowed to access the requested "
                               "dataset: " tenant "/" dataset_config_key)}}

        (and (seq agent-scopes) (not (contains? effective-keys (scope-key arg-scope))))
        {:error {:code "dataset_not_authorized"
                 :message (str "Agent " (or (:id agent) "") " is not allowed to "
                               "access the requested dataset: "
                               tenant "/" dataset_config_key)}}

        :else {:scope arg-scope})

      (seq effective) {:scope (first effective)}
      (env-default-scope) {:scope (env-default-scope)}

      :else
      {:error {:code "no_dataset_scope"
               :message (str "No dataset scope available. Pass `tenant` and "
                             "`dataset_config_key` in tool arguments, attach "
                             "scopes to the API key, or set TENANT / "
                             "DATASET_CONFIG_KEY env vars.")}})))

(defn load-dataset-config
  "Resolve a `{:tenant :dataset-config-key}` scope into the materialized
   dataset config (collections + config-DB-resolved skill params).
   Returns nil when the dataset isn't found.

   Public for sibling-adapter reuse."
  [scope]
  (when-let [config-conn (config-db/get-conn)]
    (let [master-key (config-core/get-master-key)]
      (config-db/get-dataset-by-ref @config-conn scope master-key))))

(defn- history-from-conversation
  "Pull prior turns from Datahike into the `{:role :text}` shape that
   `messages->context` expects in the Playground path."
  [conversation-id]
  (when conversation-id
    (let [db @(data-db/get-conn)
          messages (data-db/fetch-conversation-tree db conversation-id)]
      (->> messages
           (filter #(#{:user :assistant} (:message/role %)))
           (filter #(not (str/blank? (:message/text %))))
           (mapv (fn [m]
                   {:role (:message/role m)
                    :text (:message/text m)
                    :message/role (:message/role m)
                    :message/text (:message/text m)}))))))

(defn- ensure-conversation!
  "Create a conversation row for first-turn invocations. Returns the
   conversation id (existing or freshly created)."
  [conversation-id agent-id scope skill-graph-id principal]
  (or conversation-id
      (let [conn (data-db/get-conn)
            result (data-db/create-playground-conversation
                     conn
                     agent-id
                     {:user-id (:api-key/client-id principal)
                      :tenant (:tenant scope)
                      :dataset-config-key (:dataset-config-key scope)
                      :skill-graph-id (name skill-graph-id)})]
        (:conversation-id result))))

(defn- ->content-blocks
  [response]
  [{:type "text" :text (or response "")}])

;; =============================================================================
;; Error channels (#117)
;;
;; MCP has two error channels and they mean different things:
;;
;;   isError: true      the failure text goes BACK TO THE MODEL, which can then
;;                      self-correct - fix an argument, pick another tool.
;;   JSON-RPC -32603    the server broke. A well-behaved client shows this to a
;;                      human and does not retry.
;;
;; Every `{:error {:code ...}}` returned from this namespace is a client-facing
;; condition by construction: genuine internal failures arrive as thrown
;; exceptions and are caught by the transport, which is where -32603 belongs.
;; So the default here is the model-facing channel, and only codes that name
;; something nonexistent are promoted to -32602. A new code added later
;; therefore degrades to "tell the model", never to "the server broke" - which
;; is the direction this issue existed to correct.
;; =============================================================================

(def ^:private invalid-params-codes
  "Codes where the caller sent a malformed request or named something that
   does not exist. JSON-RPC has a dedicated code for that and a client can act
   on it without a round trip through the model.

   `mode_not_allowed` belongs here rather than with the authorization
   codes: the tool name encodes agent__graph, so a graph outside the agent's
   allowed set is a tool that does not exist, not a permission the caller might
   otherwise have had."
  #{"invalid_tool_name" "agent_not_found" "mode_not_allowed" "invalid_overrides"})

(defn error-channel
  "Which MCP error channel `error` belongs on: :invalid-params or :tool-error."
  [error]
  (if (contains? invalid-params-codes (:code error))
    :invalid-params
    :tool-error))

(defn error->tool-result
  "Render `error` as a tools/call RESULT carrying isError:true, so the message
   reaches the model instead of being buried in a JSON-RPC error's :data."
  [error]
  {:content (->content-blocks (:message error))
   :isError true
   :_meta {:code (:code error)}})

(defn- filters-applied
  "Each distinct filter retrieval ran with during the call, and where it came from."
  [invoke-result]
  (->> (cons (:search-attribution invoke-result)
             (get-in invoke-result [:diagnostics :search-attributions]))
       (keep (fn [{:keys [filter-applied filter-source auto-filter-applied auto-filter-fallback]}]
               (when filter-applied
                 (cond-> {:filter filter-applied
                          :source (some-> filter-source name)}
                   auto-filter-applied (assoc :auto_detected auto-filter-applied)
                   auto-filter-fallback (assoc :auto_detected_dropped true)))))
       distinct
       vec))

(defn- ->structured-content
  "Structured payload for the tools/call result.

   `conversation-id` is included here as well as in `:_meta` because `_meta` is
   not a channel a model reads — the handle has to be in the body for a
   multi-turn client to find it (#116)."
  [invoke-result conversation-id]
  (let [{:keys [chunks queries search-attribution clarification]} invoke-result
        applied (filters-applied invoke-result)]
    (cond-> {:conversation_id conversation-id}
      (seq chunks) (assoc :chunks (mapv #(select-keys % [:chunk_id :doc_num :chunk_index
                                                          :content_length :total_chunks
                                                          :title :url :metadata])
                                        (take 20 chunks)))
      (seq queries) (assoc :queries queries)
      (seq search-attribution) (assoc :search_attribution search-attribution)
      (seq applied) (assoc :filters_applied applied)
      clarification (assoc :clarification clarification))))

(defn invoke-tool
  "Execute one MCP tool call.

   Args:
     principal — request map enriched by wrap-api-key-auth
     tool-name-str — MCP tool name (`<agent-id>__<skill-graph-name>`)
     arguments — tool-call arguments map
     progress-fn — optional event sink

   Returns an MCP tool result map (or error map with :error)."
  [principal tool-name-str arguments progress-fn]
  (let [parsed (parse-tool-name tool-name-str)]
    (if-not parsed
      {:error {:code "invalid_tool_name"
               :message (str "Tool name must contain '" name-separator "': "
                             tool-name-str)}}
      (let [[agent-id skill-graph-short] parsed
            {agent-err :error :keys [agent]} (resolve-agent agent-id principal)]
        (if agent-err
          {:error agent-err}
          (let [{sg-err :error :keys [skill-graph-id]}
                (resolve-skill-graph-id agent skill-graph-short principal)]
            (if sg-err
              {:error sg-err}
              (let [{scope-err :error :keys [scope]}
                    (pick-dataset-scope agent principal arguments)]
                (if scope-err
                  {:error scope-err}
                  (let [user-query (read-query-argument arguments)
                        raw-overrides (or (get arguments "overrides")
                                          (get arguments :overrides))
                        overrides (when (map? raw-overrides)
                                    (walk/keywordize-keys raw-overrides))
                        overrides-error (cond
                                          (nil? raw-overrides) nil
                                          (nil? overrides) "`overrides` must be an object."
                                          (and (contains? overrides :retrieve-auto-filter)
                                               (not (boolean? (:retrieve-auto-filter overrides))))
                                          "`retrieve-auto-filter` must be true or false."
                                          :else (when-let [errs (seq (filters/filter-map-errors
                                                                      (:retrieve-filter-by overrides)))]
                                                  (str "Invalid `retrieve-filter-by`: "
                                                       (str/join " " errs))))]
                    (cond
                      (str/blank? user-query)
                      {:error {:code "missing_query"
                               :message "Tool call requires a non-empty `query` argument."}}

                      overrides-error
                      {:error {:code "invalid_overrides" :message overrides-error}}

                      :else
                      (let [conversation-id (or (get arguments "conversation_id")
                                                (get arguments :conversation_id))
                            dataset-config (load-dataset-config scope)
                            _ (when-not dataset-config
                                (throw (ex-info "Dataset not found"
                                                {:scope scope})))
                            model (or (get arguments "model") (get arguments :model))
                            actual-convo-id (ensure-conversation! conversation-id
                                                                   agent-id
                                                                   scope
                                                                   skill-graph-id
                                                                   principal)
                            history (history-from-conversation conversation-id)
                            ;; Persist the user turn before running so it
                            ;; lands in the conversation tree even if the
                            ;; agent loop fails partway through.
                            _ (data-db/transact-playground-user-msg
                                (data-db/get-conn) actual-convo-id user-query
                                (or overrides {}) nil 0)
                            ;; Resolve the agent's :skill-params so per-agent
                            ;; tuning (e.g. a sweep-winner phrase-only config)
                            ;; flows through every MCP call bound to that
                            ;; agent's API key. Falls back to {} for unknown
                            ;; agents or agents without overrides — that path
                            ;; matches pre-agent-skill-params behaviour.
                            agent-skill-params (or (:skill-params
                                                     (agents-db/get-agent
                                                       @(config-db/get-conn)
                                                       agent-id))
                                                   {})
                            skill-params (api-util/build-rag-skill-params
                                           dataset-config
                                           (or overrides {})
                                           agent-skill-params)
                            _ (debug/record-last-invocation!
                                agent-id
                                {:user-query user-query
                                 :skill-params skill-params
                                 :skill-graph-id skill-graph-id
                                 :source :mcp})
                            invoke-result (invoke/invoke-rag
                                            {:user-query user-query
                                             :claim user-query
                                             :conversation-history history
                                             :collections {:docs-collection (:docs-collection dataset-config)
                                                           :chunks-collection (:chunks-collection dataset-config)
                                                           :phrases-collection (:phrases-collection dataset-config)}
                                             :skill-graph-id skill-graph-id
                                             :skill-params skill-params
                                             :execution-scope {:tenant (:tenant scope)
                                                               :dataset-config-key (:dataset-config-key scope)
                                                               :agent-id agent-id}
                                             :model model
                                             :progress-fn progress-fn})
                            response-text (:response invoke-result)
                            ;; Trim diagnostics to a minimal payload for
                            ;; the MCP response. Full diagnostics stay in
                            ;; the playground-internal envelope.
                            mcp-diagnostics (select-keys (:diagnostics invoke-result)
                                                         [:execution-metadata
                                                          :backend-issues
                                                          :search-attributions])]
                        ;; Persist the assistant turn (best-effort — log
                        ;; failures but still return the response).
                        (try
                          (data-db/transact-assistant-msg
                            (data-db/get-conn) actual-convo-id response-text
                            mcp-diagnostics)
                          (catch Throwable e
                            (t/log! :warn [:mcp.tools/persist-failed
                                           {:conversation-id actual-convo-id
                                            :error (.getMessage e)}])))
                        {:result
                         {:content (into (->content-blocks response-text)
                                         (resource-links (:chunks invoke-result)))
                          :structuredContent (->structured-content invoke-result actual-convo-id)
                          :isError (= :error (:status invoke-result))
                          :_meta (cond-> {:conversation_id actual-convo-id
                                          :mode (short-name skill-graph-id)
                                          :agent_id agent-id
                                          :status (name (:status invoke-result))
                                          :insufficient (boolean (:insufficient? invoke-result))}
                                   (= :needs-clarification (:status invoke-result))
                                   (assoc :clarification (:clarification invoke-result))
                                   (:execution-metadata mcp-diagnostics)
                                   (assoc :execution_metadata
                                          (:execution-metadata mcp-diagnostics)))}}))))))))))))

(defn- request-id
  []
  (nano-id))

(defn list-tools-response
  "Convenience used by the transport layer."
  [principal]
  {:tools (list-tools principal)
   :_meta {:request-id (request-id)}})
