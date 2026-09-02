(ns digdir.rag.skills.core
  "Core skill protocol and abstractions for skill-based RAG.

  This namespace defines the foundational types and contracts for the skill system:
  - Skill metadata schema
  - Execution context structure
  - Result structure
  - Validation functions
  - Utilities for working with skills

  A skill is a discrete, composable capability that:
  - Takes a map of inputs
  - Has configurable parameters (from pipeline config)
  - Uses service clients (Typesense, OpenAI, etc.)
  - Returns a map of outputs and metadata

  Skills are pure functions that follow this signature:
    (defn execute-skill [ctx] -> result)

  Where:
    ctx = {:skill-id - Keyword identifying the skill
           :inputs - Map of input data
           :parameters - Map of resolved parameter values
           :services - Map of service clients
           :skill-params - Skill parameter overrides by skill-id
           :execution-id - Optional execution ID for tracking}

    result = {:outputs - Map of output data
              :metadata - Execution metadata (duration, etc.)
              :error - Optional error information}"
  (:require [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Canonical I/O Vocabulary
;; =============================================================================
;;
;; Standard type schemas for data flowing between skills.  Skill graphs wire
;; outputs to inputs explicitly, so key names may differ across skills — but
;; these schemas define the *shape* of each concept so graphs can be validated
;; and developers can reason about compatibility.
;;
;; Naming conventions used by the built-in skills:
;;
;;  Concept            Producer(s)                 Consumer(s)
;;  ─────────────────  ─────────────────────────── ──────────────────────────
;;  :query             graph input                 synthesis, rerank, agent
;;  :queries           graph input / query-planner retrieval, multi-retrieval
;;  :search-phrases    query-planner               (wired to :queries)
;;  :chunks            retrieval, multi-retrieval   rerank, agent
;;  :reranked-chunks   rerank                       (display / diagnostics)
;;  :context-docs      rerank                       synthesis
;;  :response          synthesis, agent             (final output)
;;  :search-attribution retrieval, multi-retrieval  (diagnostics)
;;  :citations         synthesis                    (final output)
;;  :conversation-history  graph input              query-planner, agent

(def Query
  "A single user query string."
  [:string {:min 1}])

(def Queries
  "One or more search phrases / queries."
  [:vector {:min 1} [:string {:min 1}]])

(def Content
  "Generic text content."
  [:string {:min 1}])

(def CollectionName
  "A concrete collection binding."
  [:string {:min 1}])

(def DatasetRef
  "Canonical dataset scope."
  [:map
   [:tenant [:string {:min 1}]]
   [:dataset-config-key [:string {:min 1}]]])

(def ConversationMessage
  "Conversation turn. Accept both canonical :content and the current :text shape."
  [:or
   [:map
    [:role [:or [:enum "user" "assistant" "system"]
            [:enum :user :assistant :system]]]
    [:content [:string {:min 1}]]]
   [:map
    [:role [:or [:enum "user" "assistant" "system"]
            [:enum :user :assistant :system]]]
    [:text [:string {:min 1}]]]])

(def Chunk
  "A single retrieval chunk.  Required keys mirror the Typesense document shape."
  [:map
   [:chunk_id string?]
   [:chunk_index {:optional true} [:maybe nat-int?]]
   [:content_markdown {:optional true} [:maybe string?]]
   [:content_length {:optional true} [:maybe number?]]
   [:doc_num {:optional true} [:maybe string?]]
   [:metadata {:optional true} [:maybe any?]]
   [:title {:optional true} [:maybe string?]]])

(def Chunks
  "An ordered collection of retrieval chunks."
  [:vector Chunk])

(def ContextDoc
  "A doc-shaped context entry passed to synthesis."
  [:map
   [:page_content string?]
   [:metadata {:optional true} [:maybe [:map
                                        [:source {:optional true} [:maybe string?]]]]]])

(def ContextDocs
  "Chunks selected and trimmed for the synthesis context window."
  [:vector ContextDoc])

(defn- string-coll-schema
  []
  [:or [:vector string?] [:set string?]])

(def RetrievalFilterField
  "Structured retrieval filter field."
  [:map
   [:field string?]
   [:selected-options (string-coll-schema)]
   [:type {:optional true} keyword?]
   [:value-type {:optional true} [:or keyword? string?]]
   [:expanded? {:optional true} boolean?]])

(def RetrievalFilter
  "Structured retrieval filter passed to Typesense-backed skills."
  [:map
   [:fields [:vector RetrievalFilterField]]
   [:max-options {:optional true} nat-int?]])

(def SearchAttribution
  "Structured retrieval diagnostics produced by retrieval skills."
  [:map
   [:phrase {:optional true} nat-int?]
   [:metadata {:optional true} nat-int?]
   [:content {:optional true} nat-int?]
   [:merged {:optional true} nat-int?]
   [:retrieve-top-k {:optional true} nat-int?]
   [:query-aware-boost-enabled {:optional true} boolean?]
   [:max-per-document {:optional true} nat-int?]
   [:effective-max-per-document {:optional true} nat-int?]
   [:chunks-before-diversity {:optional true} nat-int?]
   [:chunks-after-diversity {:optional true} nat-int?]
   [:dropped-by-diversity {:optional true} nat-int?]
   [:filter-applied {:optional true} RetrievalFilter]
   [:filter-source {:optional true} [:maybe keyword?]]
   [:auto-filter-applied {:optional true} RetrievalFilter]
   [:auto-filter-fallback {:optional true} boolean?]])

(def Citation
  "A single citation reference within a synthesized response."
  [:map
   [:id {:optional true} [:maybe string?]]
   [:chunk_id {:optional true} [:maybe string?]]
   [:text {:optional true} [:maybe string?]]])

(def Citations
  "Citations extracted from a synthesis response."
  [:vector Citation])

(def CitationIndex
  "Map from 1-based citation index to chunk id."
  [:map-of pos-int? string?])

(def Response
  "A synthesized natural-language response."
  [:string])

(def ClarificationOption
  "A suggested answer option for a clarification prompt."
  [:string {:min 1}])

(def ClarificationRequest
  "Structured request for user clarification."
  [:map
   [:question [:string {:min 1}]]
   [:options {:optional true} [:vector ClarificationOption]]
   [:context-summary [:string {:min 1}]]])

(def ConversationHistory
  "Prior turns in the conversation, each with :role and :content."
  [:vector ConversationMessage])

(def Prompts
  "Prompt bundle recorded for observability."
  [:map
   [:system string?]
   [:full string?]])

(def TraceToolCall
  "A single agent tool call entry."
  [:map
   [:tool string?]
   [:args map?]
   [:result-summary {:optional true} [:maybe string?]]
   [:effective-parameters {:optional true} [:maybe map?]]])

(def TraceStep
  "A single agent trace step."
  [:map
   [:iteration nat-int?]
   [:reasoning {:optional true} [:maybe string?]]
   [:tool-calls [:vector TraceToolCall]]])

(def Trace
  "Agent execution trace."
  [:vector TraceStep])

(def QueryIntent
  "Structured query intent metadata."
  map?)

(def BudgetState
  "Structured agent budget state."
  map?)

(def SearchHistoryEntry
  "Structured search pass entry."
  [:map
   [:queries Queries]
   [:result-count nat-int?]
   [:new-count nat-int?]
   [:chunk-ids [:vector string?]]
   [:fallback? boolean?]
   [:attribution {:optional true} SearchAttribution]
   [:filter-by {:optional true} [:maybe RetrievalFilter]]
   [:chunk-summaries {:optional true} [:vector map?]]])

(def SearchHistory
  "Accumulated search history."
  [:vector SearchHistoryEntry])

(def SearchErrorEntry
  "Structured search failure entry."
  [:map
   [:queries Queries]
   [:fallback? boolean?]
   [:error-type keyword?]
   [:error-message string?]
   [:error-data {:optional true} [:maybe map?]]
   [:filter-by {:optional true} [:maybe RetrievalFilter]]])

(def SearchErrors
  "Search backend failures."
  [:vector SearchErrorEntry])

(def BackendIssue
  "Structured backend/tool issue."
  [:map
   [:source keyword?]
   [:tool string?]
   [:issue-type keyword?]
   [:message string?]
   [:details {:optional true} [:maybe map?]]
   [:queries {:optional true} [:maybe Queries]]
   [:filter-by {:optional true} [:maybe RetrievalFilter]]
   [:fallback? {:optional true} boolean?]])

(def BackendIssues
  "Collection of backend issues."
  [:vector BackendIssue])

(def ReadHistory
  "Structured read history."
  [:vector map?])

(def EvidencePlan
  "Structured evidence plan built from the active query."
  [:maybe map?])

(def ReadEvaluations
  "Read-time evidence updates."
  [:vector map?])

(def ClaimCoverage
  "Accumulated claim coverage keyed by claim id."
  map?)

(def ShadowSufficiencyDecisions
  "Shadow sufficiency decisions derived from read signals."
  [:vector map?])

(def SufficiencyStatus
  "Gate status produced by the solver-style sufficiency evaluator."
  [:enum :sufficient :insufficient :conflicting :off-topic])

(def SufficiencyStrategy
  "Next action proposed by the sufficiency gate."
  [:enum :re-search :read-more :ask-clarification :finalize])

(def SufficiencyDecision
  "Structured sufficiency-gate decision."
  [:map
   [:status SufficiencyStatus]
   [:reasoning string?]
   [:missing-info [:vector string?]]
   [:contradiction-detected? boolean?]
   [:suggested-strategy SufficiencyStrategy]
   [:message {:optional true} [:maybe string?]]
   [:iteration {:optional true} nat-int?]
   [:insufficiency {:optional true} [:maybe map?]]])

(def SufficiencyDecisions
  "Workspace sufficiency decisions."
  [:vector SufficiencyDecision])

(def Entity
  "Structured extracted entity."
  [:map
   [:text string?]
   [:type string?]
   [:confidence {:optional true} [:maybe number?]]
   [:context {:optional true} [:maybe string?]]])

(def Entities
  "Extracted entities."
  [:vector Entity])

(def canonical-io-registry
  "Maps skill I/O keys to Malli schemas.
   Canonical graph-facing keys use the strongest schemas; legacy aliases are
   retained where needed for compatibility during the migration."
  {:query                 Query
   :user-query            Query
   :claim                 Query
   :queries               Queries
   :search-phrases        Queries
   :content               [:or Content ContextDocs Chunks map?]
   :text                  Content
   :docs-collection       CollectionName
   :chunks-collection     CollectionName
   :phrases-collection    CollectionName
   :dataset-ref           DatasetRef
   :chunks                Chunks
   :reranked-chunks       Chunks
   :context-docs          ContextDocs
   :evidence              [:or Content ContextDocs Chunks]
   :response              Response
   :clarification-request [:maybe ClarificationRequest]
   :summary               Response
   :verification          Response
   :explanation           Response
   :reasoning             Response
   :search-attribution    SearchAttribution
   :citations             Citations
   :citation              Citation
   :citation-index        CitationIndex
   :citation-validation   map?
   :prompts               Prompts
   :conversation-history  ConversationHistory
   :insufficient-context  boolean?
   :insufficient-context-signal [:maybe keyword?]
   :trace                 Trace
   :query-intent          QueryIntent
   :budget-state          BudgetState
   :search-history        SearchHistory
   :search-errors         SearchErrors
   :backend-issues        BackendIssues
   :read-history          ReadHistory
   :evidence-plan         EvidencePlan
   :claim-coverage        ClaimCoverage
   :read-evaluations      ReadEvaluations
   :open-evidence-gaps    [:vector map?]
   :evidence-contradictions [:vector map?]
   :last-read-signal      [:maybe map?]
   :shadow-sufficiency-decisions ShadowSufficiencyDecisions
   :sufficiency-decisions SufficiencyDecisions
   :last-insufficiency    map?
   :last-response-validation-insufficiency map?
   :entities              Entities
   :entity-types          [:set string?]
   :summary-length        nat-int?
   :key-points            [:vector string?]
   :verdict               string?
   :confidence            number?
   :graph                 map?
   :available-skills      string?
   :task-description      Content})

;; =============================================================================
;; Skill Categories
;; =============================================================================

(def skill-categories
  "Valid skill categories for organization and filtering"
  #{:query-transformation  ; Query expansion, reformulation, intent detection
    :retrieval            ; Search, lookup, fetching documents
    :reranking            ; Reordering, scoring, filtering results
    :generation           ; LLM-based answer generation
    :augmentation         ; Enrichment, metadata extraction
    :validation           ; Quality checks, fact verification
    :orchestration})      ; Multi-step coordination, workflow management

;; =============================================================================
;; Parameter Types
;; =============================================================================

(def parameter-types
  "Valid parameter types for skill parameters"
  #{:string
    :number
    :boolean
    :keyword
    :edn        ; Any EDN-serializable data structure
    :map
    :vector
    :set})

;; =============================================================================
;; Malli Schemas
;; =============================================================================

;; Skill Metadata Schema
(def SkillMetadata
  "Schema for skill metadata"
  [:map
   {:doc "Complete skill metadata specification"}
   [:skill-id [:and keyword? [:fn {:error/message "Skill ID must be namespaced keyword"}
                              namespace]]]
   [:name [:string {:min 1}]]
   [:description [:string {:min 1}]]
   [:category [:enum {:doc "Skill category"}
               :query-transformation :retrieval :reranking :generation
               :augmentation :validation :orchestration]]
   [:inputs [:vector keyword?]]
   ;; Inputs that are accepted (and received via the resolved-inputs merge) but
   ;; NOT required — a subset of (or disjoint from) :inputs. Listing a key here
   ;; exempts it from `check-required-inputs`. Use this for inputs that only some
   ;; call paths supply (e.g. :user-intent, only set after a prior plan_queries),
   ;; so declaring them for documentation/discoverability never makes them a hard
   ;; requirement that breaks the paths which omit them.
   [:optional-inputs {:optional true} [:vector keyword?]]
   [:outputs [:vector {:min 1} keyword?]]
   [:parameters [:map-of keyword? [:enum :string :number :boolean :keyword :edn :map :vector :set]]]
   [:required-services {:optional true} [:set keyword?]]
   [:version {:optional true} string?]
   [:tags {:optional true} [:set keyword?]]
   [:tool-definition {:optional true} map?]])

;; Execution Context Schema
(def ExecutionContext
  "Schema for skill execution context"
  [:map
   {:doc "Context passed to skill execution function"}
   [:skill-id [:and keyword? [:fn namespace]]]
   [:inputs map?]
   [:parameters map?]
   [:services map?]
   [:skill-params map?]
   [:agent-id {:optional true} [:maybe string?]]
   [:dataset-ref {:optional true} [:map
                                   [:tenant string?]
                                   [:dataset-config-key string?]]]
   [:validate-io? {:optional true} boolean?]
   [:execution-id {:optional true} [:maybe string?]]])

;; Execution Result Schema
(def ExecutionMetadata
  "Schema for execution metadata"
  [:map
   {:doc "Metadata about skill execution"}
   [:duration-ms {:optional true} [:and number? pos?]]])

(def SkillError
  "Schema for skill execution error"
  [:map
   {:doc "Error information if skill execution failed"}
   [:error-type keyword?]
   [:error-message string?]
   [:error-data {:optional true} map?]])

(def ExecutionResult
  "Schema for skill execution result"
  [:or
   ;; Success result
   [:map
    {:doc "Successful execution result"}
    [:outputs map?]
    [:metadata map?]]
   ;; Error result
   [:map
    {:doc "Error execution result"}
    [:error SkillError]
    [:outputs {:optional true} map?]
    [:metadata {:optional true} map?]]])

;; Skill Definition Schema
(def Skill
  "Schema for complete skill definition"
  [:map
   {:doc "Complete skill definition with metadata and execute function"}
   [:metadata SkillMetadata]
   [:execute fn?]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn valid-skill-metadata?
  "Check if skill metadata is valid.

  Args:
    metadata - Skill metadata map

  Returns: true if valid, false otherwise"
  [metadata]
  (m/validate SkillMetadata metadata))

(defn validate-skill-metadata!
  "Validate skill metadata, throw exception if invalid.

  Args:
    metadata - Skill metadata map

  Throws: ex-info if validation fails
  Returns: metadata if valid"
  [metadata]
  (if (valid-skill-metadata? metadata)
    metadata
    (throw (ex-info "Invalid skill metadata"
                    {:metadata metadata
                     :errors (me/humanize (m/explain SkillMetadata metadata))}))))

(defn valid-execution-context?
  "Check if execution context is valid.

  Args:
    ctx - Execution context map

  Returns: true if valid, false otherwise"
  [ctx]
  (m/validate ExecutionContext ctx))

(defn validate-execution-context!
  "Validate execution context, throw exception if invalid.

  Args:
    ctx - Execution context map

  Throws: ex-info if validation fails
  Returns: context if valid"
  [ctx]
  (if (valid-execution-context? ctx)
    ctx
    (throw (ex-info "Invalid execution context"
                    {:context ctx
                     :errors (me/humanize (m/explain ExecutionContext ctx))}))))

(defn valid-execution-result?
  "Check if execution result is valid.

  Args:
    result - Execution result map

  Returns: true if valid, false otherwise"
  [result]
  (m/validate ExecutionResult result))

(defn validate-execution-result!
  "Validate execution result, throw exception if invalid.

  Args:
    result - Execution result map

  Throws: ex-info if validation fails
  Returns: result if valid"
  [result]
  (if (valid-execution-result? result)
    result
    (throw (ex-info "Invalid execution result"
                    {:result result
                     :errors (me/humanize (m/explain ExecutionResult result))}))))

;; =============================================================================
;; Context Validation
;; =============================================================================

(defn check-required-inputs
  "Check that all required inputs are present in the context.

  Inputs listed in the skill's :optional-inputs are exempt — they are still
  accepted (and received via the resolved-inputs merge) when provided, but their
  absence is not an error. This lets a skill declare an input for documentation
  without forcing every call path to supply it.

  Args:
    skill-metadata - Skill metadata
    inputs - Input data map

  Returns: nil if valid, error map if invalid"
  [skill-metadata inputs]
  (let [optional-inputs (set (:optional-inputs skill-metadata))
        required-inputs (remove optional-inputs (:inputs skill-metadata))
        missing-inputs (remove #(contains? inputs %) required-inputs)]
    (when (seq missing-inputs)
      {:error-type :missing-inputs
       :error-message (str "Missing required inputs: " (pr-str missing-inputs))
       :error-data {:required required-inputs
                    :optional (vec optional-inputs)
                    :provided (keys inputs)
                    :missing missing-inputs}})))

(def use-site-resolved-services
  "Services that skills declare as `:required-services` for documentation but
   actually resolve via `cfg/get` at use site (per
   `digdir.skills.context/resolve-all-services` — only TypeSense is pre-resolved
   so per-tenant overrides take effect for Azure OpenAI and ColBERT). These
   keys are skipped by `check-required-services` so a graph-runner invocation
   doesn't reject built-in LLM skills that work fine.

   Note: a follow-up could refactor the affected skills to actually consume
   the services map, at which point this set should empty out."
  #{:azure-openai :colbert})

(defn check-required-services
  "Check that all required services are present in the context.

  Args:
    skill-metadata - Skill metadata
    services - Service clients map

  Returns: nil if valid, error map if invalid"
  [skill-metadata services]
  (let [required-services (:required-services skill-metadata)
        missing-services (->> required-services
                              (remove use-site-resolved-services)
                              (remove #(contains? services %)))]
    (when (seq missing-services)
      {:error-type :missing-services
       :error-message (str "Missing required services: " (pr-str missing-services))
       :error-data {:required required-services
                    :provided (keys services)
                    :missing missing-services
                    :resolved-at-use-site (vec (filter use-site-resolved-services required-services))}})))

(defn validate-context-for-skill
  "Validate that execution context has all requirements for a skill.

  Args:
    skill-metadata - Skill metadata
    ctx - Execution context

  Returns: nil if valid, error map if invalid"
  [skill-metadata ctx]
  (or (check-required-inputs skill-metadata (:inputs ctx))
      (check-required-services skill-metadata (:services ctx))))

;; =============================================================================
;; Result Helpers
;; =============================================================================

(defn success-result
  "Create a successful execution result.

  Args:
    outputs - Map of output data
    metadata - Optional execution metadata map

  Returns: Execution result map"
  ([outputs]
   (success-result outputs {}))
  ([outputs metadata]
   {:outputs outputs
    :metadata metadata}))

(defn error-result
  "Create an error execution result.

  Args:
    error-type - Keyword error type
    error-message - String error message
    error-data - Optional map of additional error context

  Returns: Execution result map with error"
  ([error-type error-message]
   (error-result error-type error-message nil))
  ([error-type error-message error-data]
   {:error (cond-> {:error-type error-type
                    :error-message error-message}
             error-data (assoc :error-data error-data))}))

(defn result-success?
  "Check if a result represents success (no error).

  Args:
    result - Execution result map

  Returns: true if successful, false if error"
  [result]
  (not (contains? result :error)))

(defn result-error?
  "Check if a result represents an error.

  Args:
    result - Execution result map

  Returns: true if error, false if successful"
  [result]
  (contains? result :error))

(defn get-result-outputs
  "Get outputs from a result, or nil if error.

  Args:
    result - Execution result map

  Returns: Output data map or nil"
  [result]
  (:outputs result))

(defn get-result-error
  "Get error from a result, or nil if success.

  Args:
    result - Execution result map

  Returns: Error map or nil"
  [result]
  (:error result))

(defn get-result-metadata
  "Get execution metadata from a result.

  Args:
    result - Execution result map

  Returns: Metadata map"
  [result]
  (:metadata result))

;; =============================================================================
;; Skill Metadata Helpers
;; =============================================================================

(defn skill-id
  "Get skill ID from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Skill ID keyword"
  [skill-metadata]
  (:skill-id skill-metadata))

(defn skill-category
  "Get skill category from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Category keyword"
  [skill-metadata]
  (:category skill-metadata))

(defn skill-inputs
  "Get required inputs from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Vector of input keywords"
  [skill-metadata]
  (:inputs skill-metadata))

(defn skill-outputs
  "Get outputs from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Vector of output keywords"
  [skill-metadata]
  (:outputs skill-metadata))

(defn skill-parameters
  "Get parameters from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Map of parameter-name -> parameter-type"
  [skill-metadata]
  (:parameters skill-metadata))

(defn skill-required-services
  "Get required services from metadata.

  Args:
    skill-metadata - Skill metadata map

  Returns: Set of service keywords"
  [skill-metadata]
  (:required-services skill-metadata))

;; =============================================================================
;; Context Builders
;; =============================================================================

(defn make-execution-context
  "Build an execution context map.

  Args:
    skill-id - Keyword skill identifier
    inputs - Map of input data
    parameters - Map of resolved parameter values
    services - Map of service clients
    skill-params - Skill parameter overrides by skill-id
    opts - Optional map with :execution-id, :agent-id, :dataset-ref, :validate-io?

  Returns: Execution context map"
  [skill-id inputs parameters services skill-params & [opts]]
  (cond-> {:skill-id skill-id
           :inputs inputs
           :parameters parameters
           :services services
           :skill-params skill-params}
    (:execution-id opts) (assoc :execution-id (:execution-id opts))
    (:agent-id opts) (assoc :agent-id (:agent-id opts))
    (:dataset-ref opts) (assoc :dataset-ref (:dataset-ref opts))
    (contains? opts :validate-io?) (assoc :validate-io? (:validate-io? opts))))

(defn- schema-for-io-key
  [io-key]
  (get canonical-io-registry io-key))

(defn- io-validation-enabled?
  [{:keys [validate-io?]}]
  (boolean validate-io?))

(defn validate-skill-io!
  "Validate declared skill inputs or outputs against the I/O registry.

  Args:
    skill-metadata - The skill metadata
    io-kind - Either :inputs or :outputs
    data - Actual input or output map

  Throws: ex-info on missing, unexpected, or schema-invalid data
  Returns: data"
  [skill-metadata io-kind data]
  (let [skill-id (:skill-id skill-metadata)
        declared-keys (vec (get skill-metadata io-kind []))
        unexpected-keys (when (= io-kind :outputs)
                          (vec (remove (set declared-keys) (keys (or data {})))))]
    (when (and (= io-kind :outputs) (seq unexpected-keys))
      (throw (ex-info "Skill emitted undeclared outputs"
                      {:skill-id skill-id
                       :validation-stage :io
                       :io-kind io-kind
                       :declared declared-keys
                       :unexpected unexpected-keys
                       :outputs data})))
    (doseq [io-key declared-keys]
      (when-not (contains? data io-key)
        (throw (ex-info "Skill I/O is missing a declared key"
                        {:skill-id skill-id
                         :validation-stage :io
                         :io-kind io-kind
                         :io-key io-key
                         :declared declared-keys
                         :data data})))
      (when-let [schema (schema-for-io-key io-key)]
        (let [value (get data io-key)]
          (when-not (m/validate schema value)
            (throw (ex-info "Skill I/O failed schema validation"
                            {:skill-id skill-id
                             :validation-stage :io
                             :io-kind io-kind
                             :io-key io-key
                             :schema schema
                             :value value
                             :errors (me/humanize (m/explain schema value))})))))))
  data)

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn format-skill-summary
  "Format skill metadata as a human-readable summary.

  Args:
    skill-metadata - Skill metadata map

  Returns: Multi-line string summary"
  [skill-metadata]
  (str "Skill: " (:name skill-metadata) "\n"
       "ID: " (:skill-id skill-metadata) "\n"
       "Category: " (:category skill-metadata) "\n"
       "Description: " (:description skill-metadata) "\n"
       "Inputs: " (pr-str (:inputs skill-metadata)) "\n"
       "Outputs: " (pr-str (:outputs skill-metadata)) "\n"
       "Parameters: " (pr-str (keys (:parameters skill-metadata))) "\n"
       "Required Services: " (pr-str (:required-services skill-metadata))))

(defn merge-outputs
  "Merge outputs from multiple skill results.
  Later results override earlier results for duplicate keys.

  Args:
    results - Collection of execution result maps

  Returns: Merged output data map"
  [results]
  (apply merge (map get-result-outputs results)))

(defn skill-metadata->parameter-map
  "Convert skill metadata parameters to a map of parameter names for display.

  Args:
    skill-metadata - Skill metadata map

  Returns: Map of parameter-name -> {:type param-type}"
  [skill-metadata]
  (reduce-kv
    (fn [acc param-name param-type]
      (assoc acc param-name {:type param-type}))
    {}
    (:parameters skill-metadata)))

;; =============================================================================
;; Exception Helpers
;; =============================================================================

(defn wrap-execution-error
  "Wrap an exception into a skill error result.

  When the exception is a graph-runner's `\"Step execution failed\"`
  ex-info (carrying the inner step's error in its ex-data), we drill
  through and surface the INNER error message + data — otherwise every
  layer of graph nesting (outer playground graph → :builtin/agent → its
  bundled sub-graph) just produces another generic
  \"Step execution failed\" message, and operators can't tell which
  step actually threw or why.

  The chain is preserved in :wrapped-skill-id (the outer skill the
  exception escaped from) and :step-chain (each :step-id we unwrapped
  through), so downstream telemetry can still see the full path.

  Args:
    e - Exception
    skill-id - Skill ID that threw the exception
    ctx - Execution context (optional)

  Returns: Error result map"
  [e skill-id & [ctx]]
  (let [data (ex-data e)
        ;; The runner throws (ex-info \"Step execution failed\"
        ;; {:step-id <id> :error <inner-error-result>}); when we see
        ;; that shape, the meaningful error is `(:error data)` and
        ;; nesting more layers around it just hides it.
        wrapped? (and (contains? data :step-id) (contains? data :error))
        inner-error (when wrapped? (or (:error (:error data))
                                       (:error data)))]
    (if (and wrapped? inner-error)
      ;; Re-emit the inner error with the outer skill-id appended to a
      ;; chain so we know how deep the unwrap went.
      (-> inner-error
          (assoc-in [:error :error-data :wrapped-skill-id] skill-id)
          (update-in [:error :error-data :step-chain]
                     (fnil conj [])
                     (:step-id data)))
      ;; Not a wrapped step-failure — use the exception's own message.
      (error-result
        :execution-exception
        (.getMessage e)
        {:skill-id skill-id
         :exception-type (type e)
         :context ctx
         :stack-trace (when e
                        (mapv str (.getStackTrace e)))}))))

;; =============================================================================
;; Skill Registry
;; =============================================================================

(defonce ^{:private true
           :doc "Atom containing registered skills, keyed by :skill/id"}
  !skill-registry
  (atom {}))

(defn register-skill!
  "Register a skill in the registry.

  Args:
    skill - Complete skill definition map with :metadata and :execute keys

  Throws: ex-info if skill metadata is invalid
  Returns: The registered skill"
  [skill]
  (let [metadata (:metadata skill)
        execute-fn (:execute skill)]
    ;; Validate metadata
    (validate-skill-metadata! metadata)
    ;; Validate execute function
    (when-not (fn? execute-fn)
      (throw (ex-info "Skill :execute must be a function"
                      {:skill-id (:skill-id metadata)
                       :execute execute-fn})))
    ;; Register the skill
    (let [skill-id (:skill-id metadata)]
      (swap! !skill-registry assoc skill-id skill)
      skill)))

(defn get-skill
  "Get a skill by ID from the registry.

  Args:
    skill-id - Keyword identifier for the skill

  Returns: Skill definition map or nil if not found"
  [skill-id]
  (get @!skill-registry skill-id))

(defn list-skills
  "List all registered skills.

  Returns: Sequence of skill definition maps"
  []
  (vals @!skill-registry))

(defn list-skill-ids
  "List all registered skill IDs.

  Returns: Sequence of skill ID keywords"
  []
  (keys @!skill-registry))

(defn unregister-skill!
  "Remove a skill from the registry.

  Args:
    skill-id - Keyword identifier for the skill

  Returns: The removed skill or nil if not found"
  [skill-id]
  (let [skill (get-skill skill-id)]
    (swap! !skill-registry dissoc skill-id)
    skill))

(defn clear-registry!
  "Clear all skills from the registry. Primarily for testing.

  Returns: Empty map"
  []
  (reset! !skill-registry {}))

;; =============================================================================
;; Skill Execution
;; =============================================================================

(defn execute-skill
  "Execute a skill with context validation.

  Args:
    skill-id - Keyword identifier for the skill
    ctx - Execution context map (without :skill-id, will be added)

  Returns: Execution result map with :outputs/:metadata on success,
           or :error on failure"
  [skill-id ctx]
  (let [skill (get-skill skill-id)]
    (if-not skill
      ;; Skill not found
      (error-result
        :skill-not-found
        (str "Skill not found: " skill-id)
        {:skill-id skill-id
         :available-skills (list-skill-ids)})
      ;; Skill found, validate and execute
        (let [metadata (:metadata skill)
            execute-fn (:execute skill)
            full-ctx (assoc ctx :skill-id skill-id)
            validate-io? (io-validation-enabled? full-ctx)]
        ;; Validate execution context structure
        (if-not (valid-execution-context? full-ctx)
          (error-result
            :invalid-context
            "Invalid execution context"
            {:skill-id skill-id
             :errors (me/humanize (m/explain ExecutionContext full-ctx))})
          ;; Validate skill-specific requirements (inputs, services)
          (if-let [validation-error (validate-context-for-skill metadata full-ctx)]
            (error-result
              (:error-type validation-error)
              (:error-message validation-error)
              (:error-data validation-error))
            ;; Execute the skill
            (let [start-time (System/currentTimeMillis)]
              (try
                (when validate-io?
                  (validate-skill-io! metadata :inputs (:inputs full-ctx)))
                (let [result (execute-fn full-ctx)
                      end-time (System/currentTimeMillis)
                      duration-ms (- end-time start-time)]
                  (validate-execution-result! result)
                  (let [result (if (result-success? result)
                                 (update result :metadata assoc :duration-ms duration-ms)
                                 result)]
                    (when (and validate-io? (result-success? result))
                      (validate-skill-io! metadata :outputs (:outputs result)))
                    result))
                (catch clojure.lang.ExceptionInfo e
                  (let [data (ex-data e)]
                    (if (= :io (:validation-stage data))
                      (error-result
                       (case (:io-kind data)
                         :inputs :invalid-skill-inputs
                         :outputs :invalid-skill-outputs
                         :invalid-skill-io)
                       (.getMessage e)
                       data)
                      (wrap-execution-error e skill-id full-ctx))))
                (catch Exception e
                  (wrap-execution-error e skill-id full-ctx))))))))))

(comment
  ;; Example skill metadata
  (def example-metadata
    {:skill-id :query-expansion/llm-v1
     :name "LLM Query Expansion v1"
     :description "Expands user query into search phrases using Azure OpenAI"
     :category :query-transformation
     :inputs [:user-query :conversation-history]
     :outputs [:search-phrases :confidence]
     :parameters {:prompt :string
                  :model :string
                  :max-phrases :number
                  :temperature :number}
     :required-services #{:azure-openai}
     :version "1.0.0"
     :tags #{:llm :azure :production}})

  ;; Validate it
  (valid-skill-metadata? example-metadata)
  ;; => true

  (format-skill-summary example-metadata)

  ;; Check for errors
  (me/humanize (m/explain SkillMetadata {:skill-id :invalid}))

  ;; Example execution context
  (def example-ctx
    (make-execution-context
      :query-expansion/llm-v1
      {:user-query "hva er skattefradrag?"
       :conversation-history []}
      {:prompt "Expand this query..."
       :model "gpt-4o"
       :max-phrases 5
       :temperature 0.7}
      {:azure-openai {:client "..."}}
      {:tenant "ka" :tenant-config-key "prod"}      {:execution-id "exec-123"}))

  (valid-execution-context? example-ctx)
  ;; => true

  ;; Example results
  (def success-example
    (success-result
      {:search-phrases ["skattefradrag" "tax deduction" "skatteavdrag"]
       :confidence 0.95}
      {:duration-ms 250
       :model-used "gpt-4o"
       :tokens-used 150}))

  (result-success? success-example)
  ;; => true

  (get-result-outputs success-example)
  ;; => {:search-phrases [...] :confidence 0.95}

  (def error-example
    (error-result
      :api-timeout
      "Azure OpenAI request timed out"
      {:timeout-ms 5000}))

  (result-error? error-example)
  ;; => true

  (get-result-error error-example)
  ;; => {:error-type :api-timeout, :error-message "...", :error-data {...}}

  ;; Check required inputs
  (check-required-inputs
    example-metadata
    {:user-query "test"})
  ;; => {:error-type :missing-inputs, :error-message "...", :error-data {...}}

  (check-required-inputs
    example-metadata
    {:user-query "test" :conversation-history []})
  ;; => nil (valid)
  )
