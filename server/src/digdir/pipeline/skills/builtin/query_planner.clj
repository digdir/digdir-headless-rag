(ns digdir.pipeline.skills.builtin.query-planner
  "Query Planner skill - wraps query expansion function.

   This skill expands a user query into multiple search phrases
   using LLM-based query relaxation."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]
            [digdir.config.accessor :as cfg]
            [litellm.core :as litellm]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def query-planner-metadata
  {:skill-id :builtin/query-planner
   :name "Query Planner"
   :description "Expand user query into multiple search phrases using LLM"
   :category :query-transformation
   :inputs [:query :conversation-history]
   :outputs [:search-phrases]
   :parameters {:model :string
                :prompt :string
                :max-phrases :number
                :temperature :number}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:llm :query-expansion :production}})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(def search-results-tools
  "Tool definition for structured search phrase output."
  [{:type "function"
    :function
    {:name "searchPhrases"
     :parameters
     {:type "object"
      :properties
      {:searchPhrases
       {:type "array"
        :items {:type "string"}}}}}}])

(defn format-conversation-history
  "Format conversation history for the prompt."
  [messages]
  (->> messages
       (map (fn [msg]
              (let [role (if (= :user (:role msg)) "User" "Assistant")
                    text (or (:text msg) (:message/text msg) "")]
                (str role ": \"" text "\""))))
       (str/join "\n\n")))

(defn execute-query-planner
  "Execute the query planner skill.

   Inputs:
     :query - User query to expand
     :conversation-history - Vector of previous messages (optional)

   Parameters:
     :model - Model to use (default from config)
     :prompt - Custom prompt template with {messages} placeholder
     :max-phrases - Max phrases to generate (default 7)
     :temperature - Temperature (default 0.1)

   Returns:
     :search-phrases - Vector of expanded search phrases"
  [{:keys [inputs parameters] :as ctx}]
  (let [{:keys [query conversation-history]} inputs
        {:keys [model prompt max-phrases temperature]} parameters

        ;; Build messages list
        messages (if (seq conversation-history)
                   conversation-history
                   [{:role :user :text query}])

        ;; Format for prompt
        message-string (format-conversation-history messages)

        ;; Build prompt
        default-prompt "Based on the conversation, generate search phrases that would help find relevant documents:\n\n{messages}\n\nGenerate diverse search phrases."
        full-prompt (-> (or prompt default-prompt)
                       (str/replace "{messages}" message-string))

        ;; Get model config
        deployment (cfg/get :services :azure-openai :deployment-name)

        ;; Call LLM with structured output
        query-result
        (litellm/completion :azure-openai deployment
                           {:messages [{:role :user :content full-prompt}]
                            :tools search-results-tools
                            :tool_choice :required
                            :temperature (or temperature 0.1)}
                           {:api-key (cfg/get :services :azure-openai :api-key)
                            :api-base (cfg/get :services :azure-openai :api-endpoint)
                            :api-version (cfg/get :services :azure-openai :api-version)
                            :deployment deployment})

        ;; Extract search phrases from tool calls
        tool-calls (-> query-result :choices first :message :tool_calls)
        search-phrases
        (mapcat (fn [tool-call]
                  (try
                    (let [json-str (-> tool-call :function :arguments)
                          decoded (json/read-str json-str :key-fn keyword)]
                      (:searchPhrases decoded))
                    (catch Exception e
                      (println "Error decoding search phrases:" (.getMessage e))
                      nil)))
                tool-calls)]

    (if (seq search-phrases)
      (skills/success-result
        {:search-phrases (vec search-phrases)}
        {:phrase-count (count search-phrases)
         :model-used deployment})
      ;; Fallback to original query if no phrases generated
      (skills/success-result
        {:search-phrases [query]}
        {:phrase-count 1
         :fallback true
         :model-used deployment}))))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def query-planner-skill
  {:metadata query-planner-metadata
   :execute execute-query-planner})

(defn register!
  "Register the query planner skill."
  []
  (skills/register-skill! query-planner-skill))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def query-planner-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "plan_queries"
    :description "Expand a user question into multiple search queries for better retrieval"
    :parameters
    {:type "object"
     :properties
     {:query
      {:type "string"
       :description "The user's question to expand"}
      :context
      {:type "string"
       :description "Optional conversation context"}}
     :required ["query"]}}})
