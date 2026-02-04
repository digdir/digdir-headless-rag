(ns digdir.pipeline.skills.builtin.entity-extraction
  "Entity Extraction skill - extract structured entities from text.

   Uses LLM to identify and extract entities like people, organizations,
   dates, locations, and custom entity types from input text."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [wkok.openai-clojure.api :as openai]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def entity-extraction-metadata
  {:skill-id :builtin/entity-extraction
   :name "Entity Extraction"
   :description "Extract structured entities from text using LLM"
   :category :augmentation
   :inputs [:text]
   :outputs [:entities :entity-types]
   :parameters {:model :string
                :entity-types :vector
                :temperature :number
                :max-entities :number}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:llm :extraction :ner}})

;; =============================================================================
;; Tool Definition for Structured Output
;; =============================================================================

(def extraction-tools
  [{:type "function"
    :function
    {:name "extractEntities"
     :description "Extract entities from text"
     :parameters
     {:type "object"
      :properties
      {:entities
       {:type "array"
        :items {:type "object"
                :properties
                {:text {:type "string"
                        :description "The extracted entity text"}
                 :type {:type "string"
                        :description "Entity type (person, organization, location, date, etc.)"}
                 :confidence {:type "number"
                              :description "Confidence score 0-1"}
                 :context {:type "string"
                           :description "Surrounding context where entity was found"}}
                :required ["text" "type"]}}}
      :required ["entities"]}}}])

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn build-extraction-prompt
  "Build the extraction prompt."
  [text entity-types]
  (let [types-str (if (seq entity-types)
                    (str/join ", " (map name entity-types))
                    "person, organization, location, date, product, event")]
    (str "Extract all entities from the following text.\n\n"
         "Entity types to extract: " types-str "\n\n"
         "Text:\n" text)))

(defn execute-entity-extraction
  "Execute the entity extraction skill.

   Inputs:
     :text - Text to extract entities from

   Parameters:
     :model - Model to use (default from config)
     :entity-types - Vector of entity types to extract (default: common types)
     :temperature - Temperature (default 0.1)
     :max-entities - Maximum entities to return (default 50)

   Returns:
     :entities - Vector of entity maps
     :entity-types - Set of entity types found"
  [{:keys [inputs parameters] :as ctx}]
  (let [{:keys [text]} inputs
        {:keys [model entity-types temperature max-entities]} parameters

        selected-model (or model
                          (if (llm/use-azure-openai)
                            (cfg/get :services :azure-openai :deployment-name)
                            (cfg/get :services :azure-openai :model-name)))

        prompt (build-extraction-prompt text entity-types)

        ;; Call LLM with tool forcing
        response
        (if (llm/use-azure-openai)
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "user" :content prompt}]
             :tools extraction-tools
             :tool_choice {:type "function" :function {:name "extractEntities"}}
             :temperature (or temperature 0.1)}
            {:api-key (cfg/get :services :azure-openai :api-key)
             :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "user" :content prompt}]
             :tools extraction-tools
             :tool_choice {:type "function" :function {:name "extractEntities"}}
             :temperature (or temperature 0.1)}))

        ;; Extract entities from tool call
        tool-call (-> response :choices first :message :tool_calls first)
        entities (when tool-call
                   (try
                     (let [json-str (-> tool-call :function :arguments)
                           decoded (json/read-str json-str :key-fn keyword)]
                       (:entities decoded))
                     (catch Exception e
                       (println "Error parsing entities:" (.getMessage e))
                       [])))

        ;; Apply max-entities limit
        limited-entities (if max-entities
                          (take max-entities entities)
                          entities)

        ;; Collect entity types found
        types-found (set (map :type limited-entities))]

    (skills/success-result
      {:entities (vec limited-entities)
       :entity-types types-found}
      {:entity-count (count limited-entities)
       :model-used selected-model})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def entity-extraction-skill
  {:metadata entity-extraction-metadata
   :execute execute-entity-extraction})

(defn register!
  "Register the entity extraction skill."
  []
  (skills/register-skill! entity-extraction-skill))

(def entity-extraction-tool-definition
  {:type "function"
   :function
   {:name "extract_entities"
    :description "Extract structured entities (people, organizations, dates, etc.) from text"
    :parameters
    {:type "object"
     :properties
     {:text {:type "string"
             :description "Text to extract entities from"}
      :entity_types {:type "array"
                     :items {:type "string"}
                     :description "Types of entities to extract"}}
     :required ["text"]}}})
