# Implementation Plan: Phase 1 & 2 - Skill-Based RAG Foundation

**Goal**: Extract current pipeline into skills and verify with Playground Chat
**Estimated Duration**: 3-5 days
**Status**: Not Started

---

## Overview

This plan transforms the fixed 10-stage RAG pipeline into a skill-based architecture while maintaining 100% backwards compatibility. The Playground Chat will be the proving ground.

---

## Phase 1: Extract Current Pipeline as Skills

### Step 1.1: Create Skill Namespace Structure

**Files to Create**:
```
server/src/digdir/rag/skills/
├── core.clj                    # Skill execution engine
├── registry.clj                # Skill registry and lookup
├── parameters.clj              # Parameter resolution from pipeline config
├── query_expansion.clj         # Query expansion skills
├── search.clj                  # Search skills (phrase, metadata, content, multi)
├── rerank.clj                  # Reranking skills (ColBERT, LLM judge)
└── generation.clj              # Answer generation skills
```

**Why**: Clear separation of concerns, each file handles one category of skills

---

### Step 1.2: Define Skill Protocol

**File**: `server/src/digdir/rag/skills/core.clj`

Define the core skill abstraction:

```clojure
(ns digdir.rag.skills.core
  "Core skill execution protocol")

;; Skill metadata schema
(def skill-metadata-schema
  {:id keyword?              ; e.g., :query-expansion/llm-v1
   :name string?             ; Human-readable name
   :description string?      ; What this skill does
   :category keyword?        ; :query-transformation, :retrieval, :reranking, :generation
   :inputs [keyword?]        ; Required input keys
   :outputs [keyword?]       ; Output keys
   :parameters {keyword? keyword?}  ; Parameter name -> type
   :required-services #{keyword?}}) ; Services needed (e.g., #{:typesense :azure-openai})

;; Skill execution function signature
(defn execute-skill
  "Execute a skill with given inputs and configuration.

  Args:
    ctx - Execution context map:
      :skill-id - Keyword identifying the skill
      :inputs - Map of input data (keys match skill's :inputs)
      :parameters - Resolved parameters from pipeline config
      :services - Map of service clients (Typesense, OpenAI, etc.)
      :pipeline-config - Full pipeline configuration
      :execution-id - Current execution ID for logging

  Returns:
    {:outputs - Map of output data (keys match skill's :outputs)
     :metadata - Execution metadata (duration, model used, etc.)
     :error - Optional error information}"
  [ctx]
  ;; Implemented by registry
  )
```

**Tasks**:
- [ ] Define skill metadata schema
- [ ] Define execution context structure
- [ ] Define execution result structure
- [ ] Add validation functions

---

### Step 1.3: Build Skill Registry

**File**: `server/src/digdir/rag/skills/registry.clj`

```clojure
(ns digdir.rag.skills.registry
  "Skill registration and lookup")

(def skill-registry
  "Global atom storing all registered skills"
  (atom {}))

(defn register-skill!
  "Register a skill in the global registry"
  [metadata execute-fn]
  (when-not (s/valid? skill-metadata-schema metadata)
    (throw (ex-info "Invalid skill metadata" {:metadata metadata})))
  (swap! skill-registry assoc (:id metadata)
         {:metadata metadata
          :execute execute-fn}))

(defn get-skill [skill-id]
  (get @skill-registry skill-id))

(defn list-skills
  "List all registered skills, optionally filtered by category"
  ([] (vals @skill-registry))
  ([category] (filter #(= category (get-in % [:metadata :category])) (vals @skill-registry))))
```

**Tasks**:
- [ ] Create registry atom
- [ ] Implement registration function with validation
- [ ] Implement lookup functions
- [ ] Add helper for listing skills by category

---

### Step 1.4: Extract Query Expansion Skill

**File**: `server/src/digdir/rag/skills/query_expansion.clj`

Extract logic from `query-relaxation` stage in `digdir.rag.core`:

```clojure
(ns digdir.rag.skills.query-expansion
  (:require [digdir.rag.skills.registry :as registry]))

(def query-expansion-llm-v1-metadata
  {:id :query-expansion/llm-v1
   :name "LLM Query Expansion v1"
   :description "Expands user query into search phrases using Azure OpenAI structured output"
   :category :query-transformation
   :inputs [:user-query :conversation-history]
   :outputs [:search-phrases :confidence]
   :parameters {:prompt :string
                :model :string
                :max-phrases :number
                :temperature :number}
   :required-services #{:azure-openai}})

(defn execute-query-expansion-llm-v1
  [{:keys [inputs parameters services pipeline-config]}]
  (let [{:keys [user-query conversation-history]} inputs
        {:keys [prompt model max-phrases temperature]} parameters
        openai-service (:azure-openai services)]

    ;; This is the EXACT logic from query-relaxation stage
    ;; in digdir.rag.core (lines ~1150-1200)
    ;; Just extracted and parameterized

    (try
      (let [result (call-azure-openai-with-structured-output
                     openai-service
                     {:prompt prompt
                      :user-query user-query
                      :conversation-history conversation-history
                      :model model
                      :temperature temperature})
            search-phrases (extract-search-phrases result)]

        {:outputs {:search-phrases search-phrases
                   :confidence (calculate-confidence result)}
         :metadata {:model-used model
                    :tokens-used (:usage result)
                    :duration-ms (- (System/currentTimeMillis) start-time)}})

      (catch Exception e
        {:error {:message (.getMessage e)
                 :type :query-expansion-failed}}))))

;; Auto-register on namespace load
(registry/register-skill! query-expansion-llm-v1-metadata execute-query-expansion-llm-v1)
```

**Tasks**:
- [ ] Extract query expansion logic from `digdir.rag.core`
- [ ] Define skill metadata
- [ ] Implement execute function
- [ ] Add error handling
- [ ] Auto-register skill
- [ ] Write unit tests

**Reference**: Current implementation in `digdir.rag.core` lines ~1150-1200

---

### Step 1.5: Extract Search Skills

**File**: `server/src/digdir/rag/skills/search.clj`

Extract three search strategies from `lookup-search-phrases` stage:

```clojure
(ns digdir.rag.skills.search
  (:require [digdir.rag.skills.registry :as registry]))

;; 1. Phrase Search Skill
(def phrase-search-metadata
  {:id :search/phrase
   :name "Search Phrases Collection"
   :description "Searches pre-generated search phrases collection using vector similarity"
   :category :retrieval
   :inputs [:search-phrases :filter-by]
   :outputs [:chunk-ids :scores]
   :parameters {:phrases-collection :string
                :top-k :number
                :hybrid-weights :map}
   :required-services #{:typesense}})

(defn execute-phrase-search [ctx] ...)

;; 2. Metadata Search Skill
(def metadata-search-metadata
  {:id :search/metadata
   :name "Search Chunks by Metadata"
   :description "Full-text search on chunk metadata field"
   :category :retrieval
   :inputs [:search-phrases :filter-by]
   :outputs [:chunk-ids :scores]
   :parameters {:chunks-collection :string
                :top-k :number}
   :required-services #{:typesense}})

(defn execute-metadata-search [ctx] ...)

;; 3. Content Search Skill
(def content-search-metadata
  {:id :search/content
   :name "Search Chunks by Content"
   :description "Full-text search on chunk content field"
   :category :retrieval
   :inputs [:search-phrases :filter-by]
   :outputs [:chunk-ids :scores]
   :parameters {:chunks-collection :string
                :top-k :number}
   :required-services #{:typesense}})

(defn execute-content-search [ctx] ...)

;; 4. Multi-Strategy Search Skill (composition)
(def multi-strategy-search-metadata
  {:id :search/multi-strategy
   :name "Multi-Strategy Search"
   :description "Combines phrase, metadata, and content search with rank fusion"
   :category :retrieval
   :inputs [:search-phrases :filter-by]
   :outputs [:ranked-chunk-ids :scores :search-info]
   :parameters {:phrases-collection :string
                :chunks-collection :string
                :top-k :number
                :merge-strategy :keyword}  ; :rank-fusion, :max-score, etc.
   :required-services #{:typesense}})

(defn execute-multi-strategy-search
  "Calls phrase, metadata, and content search, then merges results"
  [{:keys [inputs parameters services] :as ctx}]

  ;; Call three search skills in parallel
  (let [phrase-results (execute-phrase-search (assoc ctx :skill-id :search/phrase))
        metadata-results (execute-metadata-search (assoc ctx :skill-id :search/metadata))
        content-results (execute-content-search (assoc ctx :skill-id :search/content))]

    ;; Merge using rank fusion (same logic as current merge-chunk-search-results)
    (let [merged (merge-search-results
                   [phrase-results metadata-results content-results]
                   (:merge-strategy parameters))]

      {:outputs {:ranked-chunk-ids (:chunk-ids merged)
                 :scores (:scores merged)
                 :search-info {:strategies-used 3
                               :total-results (count (:chunk-ids merged))}}
       :metadata {:phrase-results-count (count (:chunk-ids phrase-results))
                  :metadata-results-count (count (:chunk-ids metadata-results))
                  :content-results-count (count (:chunk-ids content-results))}})))

;; Register all skills
(doseq [[metadata execute-fn]
        [[phrase-search-metadata execute-phrase-search]
         [metadata-search-metadata execute-metadata-search]
         [content-search-metadata execute-content-search]
         [multi-strategy-search-metadata execute-multi-strategy-search]]]
  (registry/register-skill! metadata execute-fn))
```

**Tasks**:
- [ ] Extract phrase search logic
- [ ] Extract metadata search logic
- [ ] Extract content search logic
- [ ] Extract merge/rank fusion logic
- [ ] Implement multi-strategy composition skill
- [ ] Define all skill metadata
- [ ] Auto-register all skills
- [ ] Write unit tests for each skill

**Reference**: Current implementation in `digdir.rag.core` lines ~1200-1300

---

### Step 1.6: Extract Reranking Skills

**File**: `server/src/digdir/rag/skills/rerank.clj`

```clojure
(ns digdir.rag.skills.rerank
  (:require [digdir.rag.skills.registry :as registry]))

;; 1. ColBERT Reranking Skill
(def colbert-rerank-metadata
  {:id :rerank/colbert
   :name "ColBERT Reranking"
   :description "Reranks chunks using external ColBERT API"
   :category :reranking
   :inputs [:chunk-ids :chunks-data :user-query]
   :outputs [:reranked-chunk-ids :scores]
   :parameters {:top-k :number
                :max-chunk-length :number
                :max-total-length :number
                :colbert-api-url :string}
   :required-services #{:http-client}})

(defn execute-colbert-rerank [ctx] ...)

;; 2. LLM Judge Reranking Skill (future)
(def llm-judge-rerank-metadata
  {:id :rerank/llm-judge
   :name "LLM Judge Reranking"
   :description "Reranks chunks using LLM relevance judgments"
   :category :reranking
   :inputs [:chunk-ids :chunks-data :user-query]
   :outputs [:reranked-chunk-ids :scores :judgments]
   :parameters {:model :string
                :prompt :string
                :top-k :number}
   :required-services #{:azure-openai}})

(defn execute-llm-judge-rerank [ctx] ...)

;; Register skills
(registry/register-skill! colbert-rerank-metadata execute-colbert-rerank)
(registry/register-skill! llm-judge-rerank-metadata execute-llm-judge-rerank)
```

**Tasks**:
- [ ] Extract ColBERT reranking logic
- [ ] Implement LLM judge reranking (optional for Phase 1)
- [ ] Define skill metadata
- [ ] Auto-register skills
- [ ] Write unit tests

**Reference**: Current implementation in `digdir.rag.core` lines ~1300-1350

---

### Step 1.7: Extract Generation Skills

**File**: `server/src/digdir/rag/skills/generation.clj`

```clojure
(ns digdir.rag.skills.generation
  (:require [digdir.rag.skills.registry :as registry]))

(def answer-generation-metadata
  {:id :generate/answer-with-citations
   :name "Answer Generation with Citations"
   :description "Generates answer using LLM with chunk context and citations"
   :category :generation
   :inputs [:user-query :chunks-data :conversation-history]
   :outputs [:answer :chunks-used :reasoning]
   :parameters {:prompt :string
                :model :string
                :max-context-length :number
                :temperature :number
                :include-citations :boolean}
   :required-services #{:azure-openai}})

(defn execute-answer-generation
  [{:keys [inputs parameters services pipeline-config]}]
  (let [{:keys [user-query chunks-data conversation-history]} inputs
        {:keys [prompt model max-context-length temperature]} parameters
        openai-service (:azure-openai services)]

    ;; This is the logic from generate-response stage
    ;; Build context from chunks, call LLM, extract answer

    (try
      (let [context (build-context-from-chunks chunks-data max-context-length)
            result (call-azure-openai
                     openai-service
                     {:prompt prompt
                      :user-query user-query
                      :context context
                      :conversation-history conversation-history
                      :model model
                      :temperature temperature})
            answer (extract-answer result)]

        {:outputs {:answer answer
                   :chunks-used (map :id chunks-data)
                   :reasoning (extract-reasoning result)}
         :metadata {:model-used model
                    :tokens-used (:usage result)
                    :context-length (count context)}})

      (catch Exception e
        {:error {:message (.getMessage e)
                 :type :generation-failed}}))))

(registry/register-skill! answer-generation-metadata execute-answer-generation)
```

**Tasks**:
- [ ] Extract answer generation logic
- [ ] Implement context building
- [ ] Implement citation extraction
- [ ] Define skill metadata
- [ ] Auto-register skill
- [ ] Write unit tests

**Reference**: Current implementation in `digdir.rag.core` lines ~1350-1400

---

## Phase 2: Build Skill Execution Infrastructure

### Step 2.1: Implement Parameter Resolution

**File**: `server/src/digdir/rag/skills/parameters.clj`

Bridge between pipeline config and skill parameters:

```clojure
(ns digdir.rag.skills.parameters
  "Resolve skill parameters from pipeline configuration")

(def skill-parameter-paths
  "Mapping from skill-id + parameter name to config path"
  {:query-expansion/llm-v1
   {:prompt "pipeline.skills.query-expansion.llm-v1.prompt"
    :model "pipeline.skills.query-expansion.llm-v1.model"
    :max-phrases "pipeline.skills.query-expansion.llm-v1.max-phrases"
    :temperature "pipeline.skills.query-expansion.llm-v1.temperature"}

   :search/phrase
   {:phrases-collection "pipeline.storage.phrases-collection"
    :top-k "pipeline.skills.search.phrase.top-k"
    :hybrid-weights "pipeline.skills.search.phrase.hybrid-weights"}

   :search/multi-strategy
   {:phrases-collection "pipeline.storage.phrases-collection"
    :chunks-collection "pipeline.storage.chunks-collection"
    :top-k "pipeline.retrieval.search.top-k"
    :merge-strategy "pipeline.retrieval.search.merge-strategy"}

   :rerank/colbert
   {:top-k "pipeline.retrieval.rerank.top-k"
    :max-chunk-length "pipeline.retrieval.rerank.max-chunk-length"
    :max-total-length "pipeline.retrieval.rerank.max-total-length"
    :colbert-api-url "services.colbert.api-url"}

   :generate/answer-with-citations
   {:prompt "pipeline.generate.prompt.rag-generate"
    :model "pipeline.generate.model"
    :max-context-length "pipeline.retrieval.context.max-total-length"
    :temperature "pipeline.generate.temperature"
    :include-citations "pipeline.generate.include-citations"}})

(defn resolve-skill-parameters
  "Resolve skill parameters from pipeline config using 8-level inheritance.

  Args:
    db - Datahike database value
    tenant - Tenant identifier
    environment - Environment
    pipeline-name - Pipeline name
    skill-id - Skill identifier
    master-key - Encryption key

  Returns: Map of parameter-name -> resolved-value"
  [db tenant environment pipeline-name skill-id master-key]
  (let [param-paths (get skill-parameter-paths skill-id)
        skill (registry/get-skill skill-id)
        param-defs (:parameters (:metadata skill))]

    (reduce
      (fn [acc [param-name param-type]]
        (let [config-path (get param-paths param-name)
              resolved (when config-path
                        (config-db/resolve-value db tenant environment pipeline-name config-path))
              value (when resolved
                     (config-db/decode-value
                       (:config/value resolved)
                       param-type
                       false  ; Skills params are not encrypted by default
                       master-key))]
          (assoc acc param-name value)))
      {}
      param-defs)))
```

**Tasks**:
- [ ] Define skill parameter path mappings
- [ ] Implement parameter resolution using existing config system
- [ ] Add defaults for missing parameters
- [ ] Add validation
- [ ] Write unit tests

---

### Step 2.2: Build Service Context

**File**: `server/src/digdir/rag/skills/services.clj`

Provide service clients to skills:

```clojure
(ns digdir.rag.skills.services
  "Build service context for skill execution")

(defn build-service-context
  "Build map of service clients needed by skills.

  Args:
    pipeline-config - Full pipeline configuration

  Returns: Map of service-id -> service-client
    {:typesense - Typesense client
     :azure-openai - Azure OpenAI client
     :http-client - HTTP client
     :colbert-api - ColBERT API client}"
  [pipeline-config]
  {:typesense (build-typesense-client pipeline-config)
   :azure-openai (build-openai-client pipeline-config)
   :http-client (build-http-client)
   :colbert-api (build-colbert-client pipeline-config)})
```

**Tasks**:
- [ ] Implement service client builders
- [ ] Use existing client construction logic
- [ ] Add service validation
- [ ] Write unit tests

---

### Step 2.3: Create Skill Executor

**File**: `server/src/digdir/rag/skills/executor.clj`

Main entry point for executing skills:

```clojure
(ns digdir.rag.skills.executor
  "Execute skills with pipeline configuration")

(defn execute-skill
  "Execute a skill with full context resolution.

  Args:
    skill-id - Keyword identifying the skill
    inputs - Map of input data
    opts - Map with:
      :tenant - Tenant identifier
      :environment - Environment
      :pipeline-name - Pipeline name
      :pipeline-config - Optional pre-loaded config (for performance)
      :execution-id - Optional execution ID for tracking
      :master-key - Encryption key

  Returns: Skill result map with :outputs and :metadata"
  [skill-id inputs {:keys [tenant environment pipeline-name pipeline-config execution-id master-key]}]

  (let [;; Load pipeline config if not provided
        config (or pipeline-config
                   (pipeline/get-pipeline @conn tenant environment pipeline-name master-key))

        ;; Verify skill is enabled (if pipeline specifies enabled skills)
        enabled-skills (:enabled-skills config)
        _ (when (and enabled-skills (not (contains? enabled-skills skill-id)))
            (throw (ex-info "Skill not enabled in pipeline"
                           {:skill-id skill-id :enabled-skills enabled-skills})))

        ;; Get skill from registry
        skill (registry/get-skill skill-id)
        _ (when-not skill
            (throw (ex-info "Skill not found" {:skill-id skill-id})))

        ;; Resolve parameters
        parameters (parameters/resolve-skill-parameters
                     @conn tenant environment pipeline-name skill-id master-key)

        ;; Build service context
        services (services/build-service-context config)

        ;; Build execution context
        ctx {:skill-id skill-id
             :inputs inputs
             :parameters parameters
             :services services
             :pipeline-config config
             :execution-id execution-id}

        ;; Execute skill
        start-time (System/currentTimeMillis)
        result ((:execute skill) ctx)
        duration (- (System/currentTimeMillis) start-time)]

    ;; Add execution metadata
    (assoc-in result [:metadata :duration-ms] duration)))

(defn execute-skill-sequence
  "Execute a sequence of skills, threading outputs between them.

  Args:
    skill-sequence - Vector of skill-ids (e.g., [:query-expansion/llm-v1 :search/multi-strategy])
    initial-inputs - Initial input map
    opts - Same as execute-skill

  Returns: Final skill result with :trace of all skill executions"
  [skill-sequence initial-inputs opts]

  (loop [skills skill-sequence
         inputs initial-inputs
         trace []]

    (if (empty? skills)
      {:outputs inputs
       :trace trace}

      (let [skill-id (first skills)
            result (execute-skill skill-id inputs opts)

            ;; Check for errors
            _ (when (:error result)
                (throw (ex-info "Skill execution failed"
                               {:skill-id skill-id
                                :error (:error result)
                                :trace trace})))

            ;; Merge outputs into inputs for next skill
            next-inputs (merge inputs (:outputs result))]

        (recur (rest skills)
               next-inputs
               (conj trace {:skill-id skill-id
                           :outputs (:outputs result)
                           :metadata (:metadata result)}))))))
```

**Tasks**:
- [ ] Implement single skill execution
- [ ] Implement skill sequence execution
- [ ] Add error handling
- [ ] Add execution logging
- [ ] Write unit tests

---

### Step 2.4: Create Fixed Composition for Current Pipeline

**File**: `server/src/digdir/rag/skills/compositions.clj`

Define the standard RAG workflow:

```clojure
(ns digdir.rag.skills.compositions
  "Pre-defined skill compositions")

(def standard-rag-workflow
  {:id :composition/standard-rag
   :name "Standard RAG Workflow"
   :description "Current fixed pipeline as skill composition"
   :skills [:query-expansion/llm-v1
            :search/multi-strategy
            :rerank/colbert
            :generate/answer-with-citations]})

(defn execute-standard-rag
  "Execute standard RAG workflow.

  Args:
    inputs - Map with :user-query, :conversation-history, :filter-by
    opts - Pipeline configuration options

  Returns: Answer with full trace"
  [inputs opts]
  (executor/execute-skill-sequence
    (:skills standard-rag-workflow)
    inputs
    opts))
```

**Tasks**:
- [ ] Define standard RAG composition
- [ ] Implement composition executor
- [ ] Add composition validation
- [ ] Write integration tests

---

## Verification: Update Playground Chat

### Step 3.1: Modify Playground RAG Handler

**File**: `server/src/digdir/playground/core.cljc`

Replace current `rag/rag-pipeline` call with skill-based execution:

```clojure
;; BEFORE (current):
(rag/rag-pipeline
  conn
  {:query user-query
   :conversation-id conversation-id
   :entity-id entity-id
   :tenant tenant
   :environment environment
   ...})

;; AFTER (skill-based):
(skills.compositions/execute-standard-rag
  {:user-query user-query
   :conversation-history (fetch-conversation-history conn conversation-id)
   :filter-by filter-by}
  {:tenant tenant
   :environment environment
   :pipeline-name (or pipeline-name "default")
   :execution-id (create-execution-record!)
   :master-key master-key})
```

**Tasks**:
- [ ] Replace RAG pipeline call with skill composition
- [ ] Verify all inputs are provided
- [ ] Test in Playground Chat UI
- [ ] Verify answer quality is unchanged

---

### Step 3.2: Add Skill Trace Visualization (Optional)

Add UI to show which skills were executed:

**File**: `server/src/digdir/playground/ui.cljc`

```clojure
(e/defn SkillTrace [trace]
  (dom/div
    (dom/props {:class "skill-trace"})
    (dom/h4 (dom/text "Execution Trace"))
    (e/for-by :skill-id [step trace]
      (dom/div
        (dom/props {:class "skill-step"})
        (dom/text (str (:skill-id step) " - " (get-in step [:metadata :duration-ms]) "ms"))))))
```

**Tasks**:
- [ ] Add trace visualization component
- [ ] Show skill execution timeline
- [ ] Show parameters used per skill
- [ ] Add expand/collapse for details

---

## Testing Strategy

### Unit Tests
- [ ] Test each skill in isolation with mocked services
- [ ] Test parameter resolution for each skill
- [ ] Test service context building
- [ ] Test skill executor with various inputs

### Integration Tests
- [ ] Test full skill sequence execution
- [ ] Test standard RAG composition end-to-end
- [ ] Test with real Typesense and OpenAI (dev environment)
- [ ] Compare results with current pipeline (regression test)

### UI Tests
- [ ] Test Playground Chat with skill-based execution
- [ ] Verify answer quality matches current implementation
- [ ] Test error handling and error display

---

## Success Criteria

**Phase 1 & 2 is complete when**:

1. ✅ All skills are extracted and registered
2. ✅ Skill executor can run standard RAG composition
3. ✅ Playground Chat uses skill-based execution
4. ✅ All unit tests pass
5. ✅ Integration tests show no regression
6. ✅ Performance is within 10% of current implementation
7. ✅ Code is documented and reviewed
8. ✅ Clear path to Phase 3 (MCP server) is evident

---

## File Structure Summary

```
server/src/digdir/rag/skills/
├── core.clj                    # Skill protocol and schemas
├── registry.clj                # Skill registration and lookup
├── parameters.clj              # Parameter resolution from config
├── services.clj                # Service client building
├── executor.clj                # Skill execution engine
├── compositions.clj            # Pre-defined workflows
├── query_expansion.clj         # Query expansion skills
├── search.clj                  # Search skills
├── rerank.clj                  # Reranking skills
└── generation.clj              # Answer generation skills

server/test/digdir/rag/skills/
├── query_expansion_test.clj
├── search_test.clj
├── rerank_test.clj
├── generation_test.clj
├── executor_test.clj
└── integration_test.clj
```

---

## Next Steps After Phase 1 & 2

Once foundation is proven:

1. **Implement Phase 3**: Build MCP server that exposes skills as tools
2. **Implement Phase 4**: Build agent executor with Claude orchestration
3. **Add new skill variants**: Different query expansion strategies, LLM judge reranking, etc.
4. **Build UI for skill composition**: Visual workflow builder
5. **Implement A/B testing**: Compare different skill compositions
6. **Optimize performance**: Parallel skill execution, caching, etc.

---

## Questions to Resolve

- [ ] Should skills be synchronous or async (Missionary)?
- [ ] How granular should skill parameters be?
- [ ] Should we support skill-level caching?
- [ ] How to handle partial failures in skill sequences?
- [ ] Should we add skill execution timeouts?

---

## Notes

- Keep skills pure and testable
- Leverage existing 8-level config hierarchy
- Maintain backwards compatibility during transition
- Log everything for auditability
- Performance should not regress
