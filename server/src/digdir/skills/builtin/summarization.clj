(ns digdir.skills.builtin.summarization
  "Summarization skill - condense content.

   Uses LLM to generate summaries of documents or chunks
   at various compression levels."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [digdir.llm.client :as openai]
            [clojure.string :as str]))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def summarization-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "summarize"
    :description "Generate a summary of content"
    :parameters
    {:type "object"
     :properties
     {:content_chunk_ids {:type "array"
                          :items {:type "string"}
                          :description "IDs of chunks to summarize"}
      :style {:type "string"
              :enum ["executive" "technical" "brief" "detailed"]
              :description "Summary style"}
      :bullet_points {:type "boolean"
                      :description "Format as bullet points"}}
     :required ["content_chunk_ids"]}}})

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def summarization-metadata
  {:skill-id :builtin/summarization
   :name "Summarization"
   :description "Generate summaries of documents or chunks using LLM"
   :category :augmentation
   :inputs [:content]
   :outputs [:summary :key-points]
   :parameters {:model :string
                :temperature :number
                :max-length :number
                :style :keyword
                :bullet-points :boolean}
   :version "1.0.0"
   :tags #{:llm :summarization}
   :tool-definition summarization-tool-definition})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn format-content
  "Format content for summarization."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (str/join "\n\n---\n\n"
              (map (fn [item]
                     (or (:page_content item)
                         (:content_markdown item)
                         (:content item)
                         (str item)))
                   content))
    (map? content)
    (or (:page_content content)
        (:content_markdown content)
        (:content content)
        (pr-str content))
    :else (str content)))

(defn build-summarization-prompt
  "Build the summarization prompt."
  [content {:keys [max-length style bullet-points]}]
  (let [style-instruction (case style
                            :executive "Write an executive summary suitable for leadership."
                            :technical "Write a technical summary preserving key details."
                            :brief "Write a brief, concise summary."
                            :detailed "Write a detailed summary covering all main points."
                            "Write a clear and informative summary.")
        format-instruction (if bullet-points
                            "Format the summary as bullet points."
                            "Write in paragraph form.")
        length-instruction (when max-length
                            (str "Keep the summary under " max-length " words."))]
    (str "Summarize the following content.\n\n"
         style-instruction "\n"
         format-instruction "\n"
         (when length-instruction (str length-instruction "\n"))
         "\nContent:\n"
         content)))

(defn extract-key-points
  "Extract key points from a summary."
  [summary]
  (let [lines (str/split-lines summary)]
    (->> lines
         (filter #(or (str/starts-with? (str/trim %) "-")
                     (str/starts-with? (str/trim %) "•")
                     (re-matches #"^\d+\..+" (str/trim %))))
         (map #(str/replace % #"^[\-•\d\.]+\s*" ""))
         (map str/trim)
         (filter seq)
         vec)))

(defn execute-summarization
  "Execute the summarization skill.

   Inputs:
     :content - Content to summarize (string, doc map, or vector of docs)

   Parameters:
     :model - Model to use (default from config)
     :temperature - Temperature (default 0.3)
     :max-length - Maximum summary length in words
     :style - Summary style (:executive, :technical, :brief, :detailed)
     :bullet-points - Whether to format as bullet points

   Returns:
     :summary - Generated summary text
     :key-points - Extracted key points (if bullet-points or detected)"
  [{:keys [inputs parameters skill-params] :as _ctx}]
  (let [{:keys [content]} inputs
        {:keys [model temperature _max-length _style _bullet-points]} parameters
        tenant (:tenant skill-params)

        selected-model (or model
                          (if (llm/use-azure-openai tenant)
                            (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                            (cfg/get {:tenant tenant} :services :azure-openai :model-name)))

        formatted-content (format-content content)
        prompt (build-summarization-prompt formatted-content parameters)

        response
        (if (llm/use-azure-openai tenant)
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content "You are a skilled summarizer."}
                        {:role "user" :content prompt}]
             :temperature (or temperature 0.3)}
            {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
             :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion
            {:model selected-model
             :messages [{:role "system" :content "You are a skilled summarizer."}
                        {:role "user" :content prompt}]
             :temperature (or temperature 0.3)}))

        summary (-> response :choices first :message :content)
        key-points (extract-key-points (or summary ""))]

    (skills/success-result
      {:summary (or summary "")
       :key-points key-points}
      {:model-used selected-model
       :input-length (count formatted-content)
       :summary-length (count (or summary ""))})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def summarization-skill
  {:metadata summarization-metadata
   :execute execute-summarization})

(defn register!
  "Register the summarization skill."
  []
  (skills/register-skill! summarization-skill))
