(ns digdir.pipeline.skills.builtin.fact-checking
  "Fact-Checking skill - verify claims against evidence.

   Uses LLM to analyze claims and determine if they are
   supported, refuted, or uncertain based on provided evidence."
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

(def fact-checking-metadata
  {:skill-id :builtin/fact-checking
   :name "Fact Checking"
   :description "Verify claims against evidence using LLM"
   :category :validation
   :inputs [:claim :evidence]
   :outputs [:verdict :confidence :explanation :citations]
   :parameters {:model :string
                :temperature :number}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:llm :fact-checking :validation}})

;; =============================================================================
;; Tool Definition for Structured Output
;; =============================================================================

(def verification-tools
  [{:type "function"
    :function
    {:name "verifyFact"
     :description "Provide a fact verification verdict"
     :parameters
     {:type "object"
      :properties
      {:verdict {:type "string"
                 :enum ["SUPPORTED" "REFUTED" "NOT_ENOUGH_INFO"]
                 :description "The verification verdict"}
       :confidence {:type "number"
                    :description "Confidence score 0-1"}
       :explanation {:type "string"
                     :description "Detailed explanation of the verdict"}
       :citations {:type "array"
                   :items {:type "object"
                           :properties
                           {:text {:type "string"}
                            :relevance {:type "string"
                                        :enum ["supports" "refutes" "neutral"]}}
                           :required ["text" "relevance"]}
                   :description "Relevant citations from evidence"}}
      :required ["verdict" "explanation"]}}}])

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn build-verification-prompt
  "Build the fact verification prompt."
  [claim evidence]
  (let [evidence-text (if (string? evidence)
                        evidence
                        (str/join "\n\n---\n\n"
                                  (map (fn [e]
                                         (or (:page_content e)
                                             (:content e)
                                             (str e)))
                                       evidence)))]
    (str "You are a fact checker. Analyze the following claim against the provided evidence.\n\n"
         "Determine if the claim is:\n"
         "- SUPPORTED: Evidence clearly supports the claim\n"
         "- REFUTED: Evidence clearly contradicts the claim\n"
         "- NOT_ENOUGH_INFO: Evidence is insufficient to verify\n\n"
         "CLAIM: " claim "\n\n"
         "EVIDENCE:\n" evidence-text)))

(defn execute-fact-checking
  "Execute the fact checking skill.

   Inputs:
     :claim - The claim to verify
     :evidence - Evidence to check against (string or vector of docs)

   Parameters:
     :model - Model to use (default from config)
     :temperature - Temperature (default 0.1)

   Returns:
     :verdict - SUPPORTED, REFUTED, or NOT_ENOUGH_INFO
     :confidence - Confidence score
     :explanation - Detailed explanation
     :citations - Relevant evidence citations"
  [{:keys [inputs parameters] :as ctx}]
  (let [{:keys [claim evidence]} inputs
        {:keys [model temperature]} parameters

        selected-model (or model
                          (if (llm/use-azure-openai)
                            (cfg/get :services :azure-openai :deployment-name)
                            (cfg/get :services :azure-openai :model-name)))

        prompt (build-verification-prompt claim evidence)

        ;; Call LLM with tool forcing
        response
        (if (llm/use-azure-openai)
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "user" :content prompt}]
             :tools verification-tools
             :tool_choice {:type "function" :function {:name "verifyFact"}}
             :temperature (or temperature 0.1)}
            {:api-key (cfg/get :services :azure-openai :api-key)
             :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "user" :content prompt}]
             :tools verification-tools
             :tool_choice {:type "function" :function {:name "verifyFact"}}
             :temperature (or temperature 0.1)}))

        ;; Extract verification from tool call
        tool-call (-> response :choices first :message :tool_calls first)
        result (when tool-call
                 (try
                   (let [json-str (-> tool-call :function :arguments)]
                     (json/read-str json-str :key-fn keyword))
                   (catch Exception e
                     (println "Error parsing verification:" (.getMessage e))
                     {:verdict "NOT_ENOUGH_INFO"
                      :explanation "Error processing verification"})))]

    (skills/success-result
      {:verdict (or (:verdict result) "NOT_ENOUGH_INFO")
       :confidence (or (:confidence result) 0.5)
       :explanation (or (:explanation result) "")
       :citations (vec (or (:citations result) []))}
      {:model-used selected-model})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def fact-checking-skill
  {:metadata fact-checking-metadata
   :execute execute-fact-checking})

(defn register!
  "Register the fact checking skill."
  []
  (skills/register-skill! fact-checking-skill))

(def fact-checking-tool-definition
  {:type "function"
   :function
   {:name "verify_fact"
    :description "Verify a claim against evidence documents"
    :parameters
    {:type "object"
     :properties
     {:claim {:type "string"
              :description "The claim to verify"}
      :evidence_chunk_ids {:type "array"
                           :items {:type "string"}
                           :description "IDs of evidence chunks to check against"}}
     :required ["claim"]}}})
