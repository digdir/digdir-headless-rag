(ns digdir.pipeline.skills.builtin.synthesis
  "Synthesis skill - wraps LLM generation functions.

   This skill generates responses using retrieved context
   and Azure OpenAI."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [wkok.openai-clojure.api :as openai]
            [clojure.string :as str]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def synthesis-metadata
  {:skill-id :builtin/synthesis
   :name "Response Synthesis"
   :description "Generate responses using retrieved context and LLM"
   :category :generation
   :inputs [:query :context-docs]
   :outputs [:response :prompts]
   :parameters {:model :string
                :temperature :number
                :max-tokens :number
                :system-prompt :string
                :generation-prompt :string}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:llm :azure :generation :production}})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn build-context-yaml
  "Build YAML-formatted context from docs."
  [context-docs]
  (str/join "\n\n" (map :page_content context-docs)))

(defn build-generation-prompt
  "Build the full generation prompt with context and question."
  [prompt-template context-yaml query]
  (-> (or prompt-template "Answer the question based on the context.\n\nContext:\n{context}\n\nQuestion: {question}")
      (str/replace "{context}" context-yaml)
      (str/replace "{question}" query)))

(defn execute-synthesis
  "Execute the synthesis skill.

   Inputs:
     :query - User query to answer
     :context-docs - Vector of context docs from reranking

   Parameters:
     :model - Model to use (default from config)
     :temperature - Temperature (default 0.1)
     :max-tokens - Max tokens (default nil/unlimited)
     :system-prompt - Custom system prompt (default includes date)
     :generation-prompt - Prompt template with {context} and {question} placeholders

   Returns:
     :response - Generated response text
     :prompts - Map with :system and :full prompts used"
  [{:keys [inputs parameters services] :as ctx}]
  (let [{:keys [query context-docs]} inputs
        {:keys [model temperature max-tokens system-prompt generation-prompt]} parameters

        ;; Resolve model
        selected-model (or model
                          (if (llm/use-azure-openai)
                            (cfg/get :services :azure-openai :deployment-name)
                            (cfg/get :services :azure-openai :model-name)))

        ;; Build prompts
        system-prompt-text (or system-prompt (rag/system-prompt-with-date))
        context-yaml (build-context-yaml context-docs)
        full-prompt (build-generation-prompt generation-prompt context-yaml query)

        ;; Call LLM
        chat-response
        (if (llm/use-azure-openai)
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content system-prompt-text}
                        {:role "user" :content full-prompt}]
             :temperature (or temperature 0.1)
             :max_tokens max-tokens}
            {:api-key (cfg/get :services :azure-openai :api-key)
             :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content system-prompt-text}
                        {:role "user" :content full-prompt}]
             :temperature (or temperature 0.1)
             :max_tokens max-tokens}))

        response-text (-> chat-response :choices first :message :content)]

    (skills/success-result
      {:response (or response-text "")
       :prompts {:system system-prompt-text
                 :full full-prompt}}
      {:model-used selected-model
       :context-length (count context-yaml)
       :response-length (count (or response-text ""))})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def synthesis-skill
  {:metadata synthesis-metadata
   :execute execute-synthesis})

(defn register!
  "Register the synthesis skill."
  []
  (skills/register-skill! synthesis-skill))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def synthesis-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "synthesize_response"
    :description "Generate a response using retrieved context"
    :parameters
    {:type "object"
     :properties
     {:query
      {:type "string"
       :description "The question to answer"}
      :context_chunk_ids
      {:type "array"
       :items {:type "string"}
       :description "IDs of chunks to use as context"}}
     :required ["query" "context_chunk_ids"]}}})
