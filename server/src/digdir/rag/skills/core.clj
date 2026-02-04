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
           :pipeline-config - Full pipeline configuration
           :execution-id - Optional execution ID for tracking}

    result = {:outputs - Map of output data
              :metadata - Execution metadata (duration, etc.)
              :error - Optional error information}"
  (:require [malli.core :as m]
            [malli.error :as me]))

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
   [:outputs [:vector {:min 1} keyword?]]
   [:parameters [:map-of keyword? [:enum :string :number :boolean :keyword :edn :map :vector :set]]]
   [:required-services [:set keyword?]]
   [:version {:optional true} string?]
   [:tags {:optional true} [:set keyword?]]])

;; Execution Context Schema
(def ExecutionContext
  "Schema for skill execution context"
  [:map
   {:doc "Context passed to skill execution function"}
   [:skill-id [:and keyword? [:fn namespace]]]
   [:inputs map?]
   [:parameters map?]
   [:services map?]
   [:pipeline-config map?]
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

  Args:
    skill-metadata - Skill metadata
    inputs - Input data map

  Returns: nil if valid, error map if invalid"
  [skill-metadata inputs]
  (let [required-inputs (:inputs skill-metadata)
        missing-inputs (remove #(contains? inputs %) required-inputs)]
    (when (seq missing-inputs)
      {:error-type :missing-inputs
       :error-message (str "Missing required inputs: " (pr-str missing-inputs))
       :error-data {:required required-inputs
                    :provided (keys inputs)
                    :missing missing-inputs}})))

(defn check-required-services
  "Check that all required services are present in the context.

  Args:
    skill-metadata - Skill metadata
    services - Service clients map

  Returns: nil if valid, error map if invalid"
  [skill-metadata services]
  (let [required-services (:required-services skill-metadata)
        missing-services (remove #(contains? services %) required-services)]
    (when (seq missing-services)
      {:error-type :missing-services
       :error-message (str "Missing required services: " (pr-str missing-services))
       :error-data {:required required-services
                    :provided (keys services)
                    :missing missing-services}})))

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
    pipeline-config - Full pipeline configuration
    opts - Optional map with :execution-id

  Returns: Execution context map"
  [skill-id inputs parameters services pipeline-config & [opts]]
  (cond-> {:skill-id skill-id
           :inputs inputs
           :parameters parameters
           :services services
           :pipeline-config pipeline-config}
    (:execution-id opts) (assoc :execution-id (:execution-id opts))))

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

  Args:
    e - Exception
    skill-id - Skill ID that threw the exception
    ctx - Execution context (optional)

  Returns: Error result map"
  [e skill-id & [ctx]]
  (error-result
    :execution-exception
    (.getMessage e)
    {:skill-id skill-id
     :exception-type (type e)
     :context ctx
     :stack-trace (when e
                    (mapv str (.getStackTrace e)))}))

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
      {:tenant "ka" :environment "prod"}
      {:execution-id "exec-123"}))

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
