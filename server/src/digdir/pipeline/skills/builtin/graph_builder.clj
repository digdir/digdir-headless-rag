(ns digdir.pipeline.skills.builtin.graph-builder
  "Graph Builder meta-skill - dynamically create skill graphs.

   Uses LLM to analyze a task and generate an appropriate
   skill graph definition that can be validated and executed."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.graph.schema :as schema]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [wkok.openai-clojure.api :as openai]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [nano-id.core :as nano-id]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def graph-builder-metadata
  {:skill-id :builtin/graph-builder
   :name "Graph Builder"
   :description "Dynamically create skill graphs based on task description"
   :category :orchestration
   :inputs [:task-description :available-skills]
   :outputs [:graph :reasoning]
   :parameters {:model :string
                :temperature :number
                :validate :boolean}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:llm :orchestration :meta-skill}})

;; =============================================================================
;; Available Skills Description
;; =============================================================================

(def builtin-skills-description
  "Description of available skills for the LLM.

  Available skills:

  1. :builtin/query-planner
     - Inputs: :query, :conversation-history (optional)
     - Outputs: :search-phrases
     - Purpose: Expand a user query into multiple search phrases

  2. :builtin/retrieval
     - Inputs: :queries, :docs-collection, :chunks-collection, :phrases-collection
     - Outputs: :chunks, :search-attribution
     - Purpose: Search for relevant document chunks

  3. :builtin/multi-retrieval
     - Inputs: :queries, :docs-collection, :chunks-collection, :phrases-collection
     - Outputs: :chunks, :search-attribution, :query-results
     - Purpose: Execute multiple queries and merge results

  4. :builtin/rerank
     - Inputs: :chunks, :query, :docs-collection
     - Outputs: :reranked-chunks, :context-docs
     - Purpose: Rerank chunks by semantic relevance

  5. :builtin/synthesis
     - Inputs: :query, :context-docs
     - Outputs: :response, :prompts
     - Purpose: Generate a response using retrieved context

  6. :builtin/summarization
     - Inputs: :content
     - Outputs: :summary, :key-points
     - Purpose: Summarize content

  7. :builtin/entity-extraction
     - Inputs: :text
     - Outputs: :entities, :entity-types
     - Purpose: Extract named entities from text

  8. :builtin/fact-checking
     - Inputs: :claim, :evidence
     - Outputs: :verdict, :confidence, :explanation, :citations
     - Purpose: Verify claims against evidence")

;; =============================================================================
;; Tool Definition for Graph Output
;; =============================================================================

(def graph-builder-tools
  [{:type "function"
    :function
    {:name "createSkillGraph"
     :description "Create a skill graph definition"
     :parameters
     {:type "object"
      :properties
      {:name {:type "string"
              :description "Name for the graph"}
       :description {:type "string"
                     :description "Description of what the graph does"}
       :inputs {:type "array"
                :items {:type "string"}
                :description "Graph input names (without $ prefix)"}
       :outputs {:type "array"
                 :items {:type "string"}
                 :description "Graph output names"}
       :steps {:type "array"
               :items {:type "object"
                       :properties
                       {:id {:type "string"
                             :description "Unique step identifier"}
                        :skill {:type "string"
                                :description "Skill ID (e.g., 'builtin/retrieval')"}
                        :inputs {:type "object"
                                 :description "Input mappings (key: input-name, value: '$input' or 'step-id' or ['step-id', 'output-key'])"}
                        :parameters {:type "object"
                                     :description "Optional parameters"}}
                       :required ["id" "skill" "inputs"]}
               :description "Graph steps in execution order"}
       :reasoning {:type "string"
                   :description "Explanation of the graph design"}}
      :required ["name" "inputs" "outputs" "steps"]}}}])

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn build-graph-prompt
  "Build the prompt for graph generation."
  [task-description available-skills]
  (str "You are a skill graph architect. Based on the task description, "
       "create a skill graph that accomplishes the goal.\n\n"
       "TASK: " task-description "\n\n"
       "AVAILABLE SKILLS:\n" (or available-skills builtin-skills-description) "\n\n"
       "RULES:\n"
       "1. Steps must be in valid execution order (dependencies before dependents)\n"
       "2. Input references use $prefix for graph inputs (e.g., '$user-query')\n"
       "3. To reference a previous step's output, use the step id (e.g., 'retrieve')\n"
       "4. To reference a specific output key, use ['step-id', 'output-key']\n"
       "5. All graph inputs must be declared in the inputs array\n"
       "6. Standard inputs to include: docs-collection, chunks-collection, phrases-collection\n"
       "\nCreate an appropriate skill graph:"))

(defn parse-graph-response
  "Parse the LLM response into a graph definition."
  [tool-call]
  (when tool-call
    (try
      (let [json-str (-> tool-call :function :arguments)
            parsed (json/read-str json-str :key-fn keyword)
            ;; Convert string skill IDs to keywords
            steps (mapv (fn [step]
                          (-> step
                              (update :id keyword)
                              (update :skill #(keyword (str/replace % "/" "/")))
                              (update :inputs #(reduce-kv
                                                 (fn [m k v]
                                                   (assoc m (keyword k)
                                                          (cond
                                                            (string? v) (keyword v)
                                                            (vector? v) (mapv keyword v)
                                                            :else v)))
                                                 {}
                                                 %))))
                        (:steps parsed))]
        {:id (keyword (str "generated/" (nano-id/nano-id 8)))
         :name (:name parsed)
         :description (:description parsed)
         :inputs (mapv keyword (:inputs parsed))
         :outputs (mapv keyword (:outputs parsed))
         :steps steps
         :reasoning (:reasoning parsed)})
      (catch Exception e
        (println "Error parsing graph:" (.getMessage e))
        nil))))

(defn execute-graph-builder
  "Execute the graph builder skill.

   Inputs:
     :task-description - Natural language description of the task
     :available-skills - Optional custom skills description

   Parameters:
     :model - Model to use (default from config)
     :temperature - Temperature (default 0.2)
     :validate - Whether to validate the generated graph (default true)

   Returns:
     :graph - Generated skill graph definition
     :reasoning - LLM's explanation of the design"
  [{:keys [inputs parameters] :as ctx}]
  (let [{:keys [task-description available-skills]} inputs
        {:keys [model temperature validate]} parameters

        selected-model (or model
                          (if (llm/use-azure-openai)
                            (cfg/get :services :azure-openai :deployment-name)
                            (cfg/get :services :azure-openai :model-name)))

        prompt (build-graph-prompt task-description available-skills)

        response
        (if (llm/use-azure-openai)
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content "You are a skill graph architect. Create efficient, well-structured skill graphs."}
                        {:role "user" :content prompt}]
             :tools graph-builder-tools
             :tool_choice {:type "function" :function {:name "createSkillGraph"}}
             :temperature (or temperature 0.2)}
            {:api-key (cfg/get :services :azure-openai :api-key)
             :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content "You are a skill graph architect. Create efficient, well-structured skill graphs."}
                        {:role "user" :content prompt}]
             :tools graph-builder-tools
             :tool_choice {:type "function" :function {:name "createSkillGraph"}}
             :temperature (or temperature 0.2)}))

        tool-call (-> response :choices first :message :tool_calls first)
        graph (parse-graph-response tool-call)]

    (if graph
      ;; Optionally validate
      (if (not= validate false)
        (try
          (schema/fully-validate-graph! graph)
          (skills/success-result
            {:graph graph
             :reasoning (:reasoning graph)
             :valid true}
            {:model-used selected-model
             :step-count (count (:steps graph))})
          (catch Exception e
            (skills/success-result
              {:graph graph
               :reasoning (:reasoning graph)
               :valid false
               :validation-error (.getMessage e)}
              {:model-used selected-model})))
        (skills/success-result
          {:graph graph
           :reasoning (:reasoning graph)}
          {:model-used selected-model
           :step-count (count (:steps graph))}))

      (skills/error-result
        :graph-generation-failed
        "Failed to generate valid graph"
        {:task-description task-description}))))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def graph-builder-skill
  {:metadata graph-builder-metadata
   :execute execute-graph-builder})

(defn register!
  "Register the graph builder skill."
  []
  (skills/register-skill! graph-builder-skill))

(def graph-builder-tool-definition
  {:type "function"
   :function
   {:name "build_graph"
    :description "Dynamically create a skill graph for a complex task"
    :parameters
    {:type "object"
     :properties
     {:task_description {:type "string"
                         :description "Natural language description of what the graph should accomplish"}}
     :required ["task_description"]}}})
