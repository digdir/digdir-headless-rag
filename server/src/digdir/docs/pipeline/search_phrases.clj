(ns digdir.docs.pipeline.search-phrases
  "LLM-based search phrase generation for document chunks.

   This namespace handles generating search phrases from document content
   using OpenAI (or compatible) APIs. Features:
   - Configurable model selection with fallback
   - File-based caching of generated phrases
   - Parallel phrase generation for document chunks"
  (:require [wkok.openai-clojure.api :as openai]
            [missionary.core :as m]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [taoensso.telemere :as t]
            [digdir.docs.pipeline.core :as core]
            [digdir.config.accessor :as cfg]))

;; ============================================================================
;; OpenAI Configuration
;; ============================================================================

(def openai-implementations
  "Available OpenAI API implementations.
   Keys are :azure-openai and :openrouter."
  {:azure-openai {:api-key (cfg/get :services :azure-openai :api-key)
                  :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
                  :impl :azure
                  :request {:timeout 30000}}
   :openrouter {:api-key (cfg/get :services :openrouter :api-key)
                :api-endpoint "https://openrouter.ai/api/v1"
                :request {:timeout 30000}}})

(defn create-chat-completion
  "Creates a chat completion using the Azure OpenAI API.
   conversation should be {:model model :messages [{:role ... :content ...}]}"
  [conversation]
  (openai/create-chat-completion
   (assoc conversation
          :model (cfg/get :services :azure-openai :deployment-name))
   (openai-implementations :azure-openai)))

;; ============================================================================
;; Response Parsing
;; ============================================================================

(defn parse-phrases-response
  "Parses LLM response to extract comma-separated phrases.
   Takes the last line of the response (which should contain only phrases)
   and splits by commas."
  [response]
  (-> response
      :choices
      first
      :message
      :content
      str/split-lines
      last
      (str/split #",")
      (->> (mapv str/trim))))

;; ============================================================================
;; Caching
;; ============================================================================

(defn ensure-cache-dir!
  "Ensures the cache directory exists, creating it if needed."
  [cache-dir]
  (when-not (java.io.File/.exists (jio/file cache-dir))
    (jio/make-parents (str cache-dir "placeholder"))))

(defn cache-key
  "Generates a cache key for a chunk's search phrases based on:
   - chunk_id
   - model name
   - prompt (hashed)"
  [chunk model prompt]
  (str (:chunk_id chunk) "-"
       (core/sha256-short-hash model) "-"
       (core/sha256-short-hash prompt)))

(defn read-cached-phrases
  "Reads cached phrases from file if they exist.
   Returns nil if cache miss."
  [cache-path]
  (let [file (jio/file cache-path)]
    (when (java.io.File/.exists file)
      (edn/read-string (slurp file)))))

(defn write-cached-phrases!
  "Writes phrases to cache file."
  [cache-path phrases]
  (spit cache-path (pr-str phrases)))

;; ============================================================================
;; Phrase Generation
;; ============================================================================

(defn generate-phrases-with-model
  "Generates search phrases for a chunk using the specified model."
  [model prompt chunk-content]
  (let [convo {:model model
               :messages [{:role "user"
                           :content (str/replace prompt "REPLACE_ME" chunk-content)}]}]
    (parse-phrases-response (create-chat-completion convo))))

(defn mk-distill-search-phrases-t
  "Creates a Missionary task that generates search phrases for a chunk.
   Uses caching and model fallback.

   Config keys used:
   - :search-phrases/model - primary model
   - :search-phrases/fallback-model - fallback if primary fails
   - :search-phrases/prompt - prompt template (REPLACE_ME is replaced with content)

   cache-dir-name is used to create source-specific cache directories
   (e.g., 'website' -> 'cache/website-search-phrases/')"
  [{:search-phrases/keys [model fallback-model prompt] :as config} chunk cache-dir-name]
  (m/via m/blk
         (let [cache-dir (str "cache/" cache-dir-name "-search-phrases/")
               cache-path (str cache-dir (cache-key chunk model prompt) ".edn")]

           (ensure-cache-dir! cache-dir)

           (if-let [cached-phrases (read-cached-phrases cache-path)]
             (do
               (t/event! :search-phrases/cache-hit)
               (assoc chunk :search-phrases cached-phrases))

             (do
               (t/event! :search-phrases/cache-miss {:data {:chunk_id (:chunk_id chunk)}})
               (let [search-phrases
                     (try
                       (generate-phrases-with-model model prompt (:content_markdown chunk))
                       (catch Exception e
                         (t/error! {:id :search-phrases/primary-model-error
                                    :msg ["Search phrase generation failed with" model]} e)
                         (t/log! {:id :search-phrases/using-fallback}
                                 ["Using fallback model" fallback-model])
                         (generate-phrases-with-model fallback-model prompt (:content_markdown chunk))))
                     result-chunk (assoc chunk :search-phrases search-phrases)]

                 (write-cached-phrases! cache-path search-phrases)
                 (t/event! :search-phrases/generated {:data {:chunk_id (:chunk_id chunk)
                                                             :count (count search-phrases)}})
                 result-chunk))))))

(defn mk-distill-doc-search-phrases-t
  "Creates a Missionary task that generates search phrases for all chunks in a document.
   Processes all chunks in parallel using m/join."
  [config doc cache-dir-name]
  (m/sp
    (assoc doc :chunks
           (m/? (apply m/join
                       vector
                       (map #(mk-distill-search-phrases-t config % cache-dir-name)
                            (:chunks doc)))))))

;; ============================================================================
;; Default Prompt
;; ============================================================================

(def default-search-phrases-prompt
  "Default prompt for search phrase generation.
   Can be overridden in config via :search-phrases/prompt"
  "Analyze the following chunk and generate a list of keyword search phrases
that have high BM25 information retrieval precision, using the same language as the document.
If the text is not comprehensible, just return an empty list.
Output the search phrases comma separated on the last line.
Make sure that the last line only contains the search phrases and nothing else.

<chunk>
REPLACE_ME
</chunk>")
