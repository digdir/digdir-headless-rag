(ns digdir.docs.pipeline.search-phrases
  "LLM-based search phrase generation for document chunks.

   This namespace handles generating search phrases from document content
   using OpenAI (or compatible) APIs. Features:
   - Configurable model selection with fallback
   - File-based caching of generated phrases
   - Parallel phrase generation for document chunks"
  (:require [digdir.llm.client :as openai]
            [cheshire.core :as json]
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

(defn openai-implementation
  "Resolve OpenAI implementation config on demand from runtime config.
   `impl` is one of `:azure-openai`, `:openrouter`, `:lmstudio`."
  [tenant impl]
  (case impl
    :azure-openai
    {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
     :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
     :impl :azure
     :request {:timeout 30000}}

    :openrouter
    {:api-key (cfg/get {:tenant tenant} :services :openrouter :api-key)
     :api-endpoint "https://openrouter.ai/api/v1"
     :request {:timeout 30000}}

    ;; OpenAI-compatible local endpoint (LM Studio, Ollama with
    ;; OpenAI-compatible API, vLLM, etc.). Omits `:impl :azure` so the
    ;; wkok client uses the standard OpenAI URL shape
    ;; (`{endpoint}/v1/chat/completions`).
    ;;
    ;; Timeout is generous (5 min) because (a) local models can be large
    ;; (we tested with gemma-3 26B), (b) the pipeline fires all chunks of
    ;; a document in parallel via m/join — LM Studio serves them
    ;; sequentially on one GPU, so the Nth chunk waits in queue before
    ;; even starting. A 60s timeout caused most calls to fail under
    ;; parallel load (2026-05-26).
    :lmstudio
    {:api-key (cfg/get {:tenant tenant} :services :lmstudio :api-key)
     :api-endpoint (cfg/get {:tenant tenant} :services :lmstudio :api-endpoint)
     :request {:timeout 300000}}

    (throw (ex-info "Unknown OpenAI implementation" {:impl impl}))))

(defn- resolve-provider
  "Which provider is active for the search-phrases primary path. Read
   from `:services :search-phrases :provider` (a keyword); defaults to
   `:azure-openai` for backward compatibility when the key is absent."
  [tenant]
  (or (cfg/get {:tenant tenant} :services :search-phrases :provider)
      :azure-openai))

(defn- resolve-model
  "Per-provider model-name resolution. Each provider has its own
   conventional config key so different deployments can coexist:
     :azure-openai  → :services :azure-openai :deployment-name
     :openrouter    → :services :openrouter :model
     :lmstudio      → :services :lmstudio :model"
  [tenant provider]
  (case provider
    :azure-openai (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
    :openrouter   (cfg/get {:tenant tenant} :services :openrouter :model)
    :lmstudio     (cfg/get {:tenant tenant} :services :lmstudio :model)))

(defn create-chat-completion
  "Creates a chat completion via whichever provider is configured as
   the primary for search-phrases. The active provider is read from
   `:services :search-phrases :provider` (default `:azure-openai`),
   and the model name from the provider's own config tree.

   `conversation` is `{:messages [...] [:response_format ...]}`. The
   `:model` key is set here from config — any caller-supplied `:model`
   is overwritten."
  [tenant conversation]
  (let [provider (resolve-provider tenant)]
    (openai/create-chat-completion
     (assoc conversation :model (resolve-model tenant provider))
     (openai-implementation tenant provider))))

;; ============================================================================
;; Response Parsing
;; ============================================================================

(defn- parse-phrases-json
  "Parse an LLM response that's expected to be a JSON object with a
   `phrases` array, e.g. `{\"phrases\": [\"a\", \"b\"]}`. Returns the
   vector of phrase strings (filtered to non-blank), or nil if the
   content isn't valid JSON or doesn't have the expected shape."
  [content]
  (try
    (let [parsed (json/parse-string content true)
          phrases (:phrases parsed)]
      (when (sequential? phrases)
        (->> phrases
             (filter string?)
             (map str/trim)
             (remove str/blank?)
             vec)))
    (catch Exception _ nil)))

(defn- parse-phrases-heuristic
  "Fallback parser for free-form LLM responses. Walks the response's
   lines from the end and returns the first one that plausibly looks
   like a comma-separated phrase list: ≥1 comma, doesn't start with a
   list/quote/heading marker, and isn't suspiciously short.

   This handles old-style prompts and providers (e.g. the OpenRouter
   gemma fallback) that don't honor `response_format`.

   **LIMITATION**: cannot distinguish a comma WITHIN a phrase
   (e.g. \"Altinn 3, juni 2020\") from a comma SEPARATING phrases.
   This path will over-split such inputs. Models / use-cases that
   require commas-in-phrases must rely on the JSON-mode primary path
   — `parse-phrases-json` preserves them correctly because each
   phrase is a discrete JSON-array element."
  [content]
  (->> (str/split-lines (or content ""))
       reverse
       (some (fn [line]
               (let [trimmed (str/trim line)
                     starts-meta? (re-matches #"(?s)^[-*#>`].*|^\d+[\.\)]\s.*" trimmed)
                     comma-count (count (filter #(= % \,) trimmed))]
                 (when (and (>= comma-count 1)
                            (not starts-meta?)
                            (>= (count trimmed) 10))
                   (->> (str/split trimmed #",")
                        (map str/trim)
                        (remove str/blank?)
                        vec)))))))

(defn parse-phrases-response
  "Parses LLM response to extract search phrases. Tries the JSON-mode
   shape first (`{phrases: [...]}` — requested via response_format
   `json_object`), then falls back to a line-walking heuristic for
   providers/models that don't honor structured output.

   If the response has no `:choices` (typical shape: `{:error \"...\"}`
   when the provider rejected the request — e.g. LM Studio returning
   `Unexpected endpoint or method` for a misconfigured api-endpoint),
   throws an ex-info so the caller's existing error-handling path
   (report-primary-failure! → fallback model) fires and the issue is
   surfaced loudly. Without this, hundreds of chunks would silently
   cache `[]` and the misconfiguration would only show up via
   downstream coverage statistics — discovered the hard way
   2026-05-26.

   The previous implementation took the LAST line of the response
   unconditionally and split on commas. That broke once gpt-4o began
   appending meta-commentary after the phrase line (\"3. **keywords
   only**.\" etc.) — 99.9% of cached responses were captured
   meta-commentary, not phrases. See README / commit log for the
   diagnosis trail."
  [response]
  (when-not (seq (:choices response))
    (throw (ex-info "LLM response has no :choices — provider likely rejected the request"
                    {:response response
                     :error (:error response)})))
  (let [content (-> response :choices first :message :content)]
    (or (parse-phrases-json content)
        (parse-phrases-heuristic content)
        [])))

;; ============================================================================
;; Caching
;; ============================================================================

(defn ensure-cache-dir!
  "Ensures the cache directory exists, creating it if needed."
  [cache-dir]
  (when-not (java.io.File/.exists (jio/file cache-dir))
    (jio/make-parents (str cache-dir "placeholder"))))

(def parser-version
  "Bump when parse-phrases-response changes in a way that makes prior
   cache entries unreliable. Included in `cache-key` so old cache
   files (with no version suffix or a different one) are ignored —
   they become orphan disk space that can be cleaned up out-of-band.

   v2 (2026-05-26): switched parser to JSON-mode-first with a
   line-walking heuristic fallback. The v1 parser took the last line
   unconditionally, which captured LLM meta-commentary (\"3. keywords
   only.\" etc.) instead of phrases for 99.9% of cached responses."
  "v2")

(defn cache-key
  "Generates a cache key for a chunk's search phrases based on:
   - chunk content (hashed)
   - model name
   - prompt (hashed)
   - parser version (so a parser change invalidates prior cache)

   Keyed on content rather than `chunk_id`, because content is what
   determines the answer: `distill-search-phrases` sends the prompt with
   `REPLACE_ME` replaced by the chunk text and nothing else — no title, no
   document metadata. Keying on `chunk_id` only worked while ids *were*
   content hashes; once they became document-scoped (#72) the same key would
   have meant re-generating phrases for every copy of a duplicated chunk, and
   the corpus has 9% duplicates. Same inputs, same key, one LLM call.

   Cache files written under the old key are simply never read again — the
   same orphaning as a `parser-version` bump."
  [chunk model prompt]
  (str (core/sha256-short-hash (:content_markdown chunk)) "-"
       (core/sha256-short-hash model) "-"
       (core/sha256-short-hash prompt) "-"
       parser-version))

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
;; Error Parsing (Azure content filter / OpenAI errors)
;; ============================================================================
;;
;; wkok/openai-clojure throws an ExceptionInfo whose ex-data includes the
;; HTTP response. For Azure, content-policy rejections come back as a 400
;; with `error.code == "content_filter"` and an `innererror.content_filter_result`
;; map showing which category (hate / self_harm / sexual / violence / jailbreak)
;; tripped and at what severity. We extract that here so callers can fire
;; structured telemetry instead of just stringifying the stack trace.

(defn parse-error-response
  "Attempt to extract a structured error map from a wkok/openai-clojure
   exception. Returns nil if the exception didn't carry a recognizable
   response body, or a map with :status :error-code :error-message
   :innererror when parsing succeeded."
  [^Exception e]
  (let [data (ex-data e)
        status (:status data)
        body-raw (:body data)]
    (when body-raw
      (let [body (cond
                   (map? body-raw) body-raw
                   (string? body-raw)
                   (try (json/parse-string body-raw true)
                        (catch Exception _ nil)))
            error (some-> body :error)]
        (when error
          {:status status
           :error-code (:code error)
           :error-message (:message error)
           :innererror (:innererror error)})))))

(defn content-filter-rejection?
  "True if the parsed error describes an Azure content-management rejection."
  [parsed]
  (and parsed
       (or (= "content_filter" (:error-code parsed))
           (= "ResponsibleAIPolicyViolation"
              (get-in parsed [:innererror :code])))))

(defn triggered-content-filter-categories
  "Extract `[{:category :severity}]` for categories that actually tripped
   (`filtered: true`) from an Azure error response. Falls back to an empty
   vec when the shape doesn't match (e.g., older API versions)."
  [parsed]
  (let [cfr (get-in parsed [:innererror :content_filter_result])]
    (->> (or cfr {})
         (keep (fn [[category v]]
                 (when (and (map? v) (true? (:filtered v)))
                   {:category (name category)
                    :severity (:severity v)
                    :detected (:detected v)})))
         vec)))

;; ============================================================================
;; Phrase Generation
;; ============================================================================

(def ^:private json-schema-phrases
  "OpenAI Structured-Outputs JSON Schema constraining the phrase-
   generation LLM to produce exactly `{phrases: [string]}`. Used by
   providers that support `response_format: json_schema` (LM Studio,
   recent Azure OpenAI deployments)."
  {:name "search_phrases"
   :strict true
   :schema {:type "object"
            :properties {:phrases {:type "array" :items {:type "string"}}}
            :required ["phrases"]
            :additionalProperties false}})

(defn- response-format-for-provider
  "Return the value to put under `:response_format` in a chat-completion
   request, scoped to the provider:

   - `:lmstudio`     → `json_schema` (the only structured-output mode
                        LM Studio accepts — it rejects `json_object`
                        with HTTP 400)
   - `:azure-openai` → `json_object` (widely supported on Azure gpt-4o)
   - `:openrouter`   → nil (most compatible — many routed models
                        don't honor structured-output requests; the
                        prompt itself instructs the model to emit JSON
                        and the heuristic parser handles free-form
                        responses)
   - default         → nil"
  [provider]
  (case provider
    :lmstudio     {:type "json_schema" :json_schema json-schema-phrases}
    :azure-openai {:type "json_object"}
    :openrouter   nil
    nil))

(defn- ensure-json-mentioned-when-required
  "Azure OpenAI REJECTS requests with response_format `json_object`
   if the literal word 'json' (case-insensitive) doesn't appear
   somewhere in the messages — HTTP 400
   `'messages' must contain the word 'json' in some form`. Observed
   on gpt-5.4-mini 2026-05-26; documented as a general Azure policy
   across the gpt-4o / gpt-5 families. Custom
   `:search-phrases/prompt` configs that omit the word (e.g. a terse
   'Generate search phrases for: ...') fail every call.

   Defense: when we're about to send response_format json_object and
   the final message content has no 'json' in it, append a small
   marker. Doesn't change behavior for prompts that already mention
   JSON. (json_schema mode — used by LM Studio — doesn't impose this
   restriction, so we only patch when type='json_object'.)"
  [content response-format]
  (if (and (= (:type response-format) "json_object")
           (not (re-find #"(?i)json" content)))
    (str content "\n\nRespond with a JSON object.")
    content))

(defn generate-phrases-with-model
  "Generates search phrases for a chunk using the specified model.

   Builds a chat-completion request with a provider-appropriate
   `response_format` (the structured-output mode each provider accepts
   differs — see response-format-for-provider). Falls through to the
   `parse-phrases-response` JSON-then-heuristic parser, which handles
   both structured and free-form responses."
  [tenant model prompt chunk-content]
  (let [rf (response-format-for-provider (resolve-provider tenant))
        content (-> prompt
                    (str/replace "REPLACE_ME" chunk-content)
                    (ensure-json-mentioned-when-required rf))
        convo (cond-> {:model model
                       :messages [{:role "user" :content content}]}
                rf (assoc :response_format rf))]
    (parse-phrases-response (create-chat-completion tenant convo))))

(defn mk-distill-search-phrases-t
  "Creates a Missionary task that generates search phrases for a chunk.
   Uses caching and model fallback.

   Config paths used:
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
               (t/event! :search-phrases/cache-hit
                         {:data {:chunk_id (:chunk_id chunk)
                                 :count (count cached-phrases)}})
               (assoc chunk :search-phrases cached-phrases))

             (do
               (t/event! :search-phrases/cache-miss {:data {:chunk_id (:chunk_id chunk)}})
               (let [tenant (:tenant config)
                     ;; Effective provider/model — the actual values used by
                     ;; create-chat-completion, which OVERWRITES the caller-
                     ;; supplied :model with `(resolve-model tenant provider)`.
                     ;; Logging only `model` (the pipeline-config arg) was
                     ;; misleading: a timeout from LM Studio surfaced as
                     ;; "failed with gpt-5.4-mini" even though gpt-5.4-mini
                     ;; was never sent anywhere (2026-05-26).
                     provider (resolve-provider tenant)
                     effective-model (resolve-model tenant provider)
                     ;; Chunk context shared by all failure events so an
                     ;; operator can locate the offending source content.
                     chunk-ref {:chunk_id (:chunk_id chunk)
                                :doc_num (:doc_num chunk)
                                :chunk_index (:chunk_index chunk)
                                :url (:url chunk)
                                :path (:path chunk)
                                :content_length (or (:content_length chunk)
                                                    (count (:content_markdown chunk)))}
                     report-primary-failure!
                     (fn [e]
                       (let [parsed (parse-error-response e)]
                         (if (content-filter-rejection? parsed)
                           (t/event! :search-phrases/content-filtered
                                     {:data (merge chunk-ref
                                                   {:model model
                                                    :provider provider
                                                    :effective-model effective-model
                                                    :triggered (triggered-content-filter-categories parsed)
                                                    :error-message (:error-message parsed)
                                                    :status (:status parsed)
                                                    :stage :primary})})
                           (t/error! {:id :search-phrases/primary-model-error
                                      :msg ["Search phrase generation failed with" effective-model "(provider" provider ")"]
                                      :data (merge chunk-ref
                                                   {:model model
                                                    :provider provider
                                                    :effective-model effective-model
                                                    :parsed-error parsed})}
                                     e))))
                     report-fallback-failure!
                     (fn [e]
                       (let [parsed (parse-error-response e)]
                         (if (content-filter-rejection? parsed)
                           (t/event! :search-phrases/content-filtered
                                     {:data (merge chunk-ref
                                                   {:model fallback-model
                                                    :provider provider
                                                    :effective-model effective-model
                                                    :triggered (triggered-content-filter-categories parsed)
                                                    :error-message (:error-message parsed)
                                                    :status (:status parsed)
                                                    :stage :fallback})})
                           (t/error! {:id :search-phrases/fallback-model-error
                                      :msg ["Fallback search phrase generation also failed with" effective-model "(provider" provider ")"]
                                      :data (merge chunk-ref
                                                   {:model fallback-model
                                                    :provider provider
                                                    :effective-model effective-model
                                                    :parsed-error parsed})}
                                     e))))
                     search-phrases
                     (try
                       (generate-phrases-with-model tenant model prompt (:content_markdown chunk))
                       (catch Exception e
                         (report-primary-failure! e)
                         (t/log! {:id :search-phrases/using-fallback}
                                 ["Using fallback model" fallback-model])
                         (try
                           (generate-phrases-with-model tenant fallback-model prompt (:content_markdown chunk))
                           (catch Exception e2
                             (report-fallback-failure! e2)
                             ;; Re-raise so orchestration counts this as a
                             ;; document-level failure. Both providers
                             ;; refused — there's nothing reasonable to cache.
                             (throw e2)))))
                     result-chunk (assoc chunk :search-phrases search-phrases)]

                 ;; Cache ONLY non-empty successful results.
                 ;; - Failures: never reach here (re-thrown above).
                 ;; - Empty results: skip the write so the next run sees a
                 ;;   cache miss and retries. The model may have been mis-
                 ;;   loaded, prompt-tuning may improve, or the chunk may
                 ;;   become parseable in a later run — none of which we
                 ;;   want frozen into the cache as `[]`.
                 (when (seq search-phrases)
                   (write-cached-phrases! cache-path search-phrases))
                 (t/event! :search-phrases/generated {:data {:chunk_id (:chunk_id chunk)
                                                             :count (count search-phrases)
                                                             :cached? (boolean (seq search-phrases))}})
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
   Can be overridden in config via :search-phrases/prompt.

   The prompt asks for JSON output to pair with the
   `response_format: {type: json_object}` request param. The
   `parse-phrases-response` parser accepts both the JSON shape and a
   free-form comma-separated line (heuristic fallback) so providers
   that don't honor structured output still work."
  "Analyze the following chunk and generate a list of keyword search phrases
that have high BM25 information retrieval precision, using the same language
as the document. If the text is not comprehensible, return an empty array.

Respond with a single JSON object of the form:
{\"phrases\": [\"phrase one\", \"phrase two\", \"phrase three\"]}

Output ONLY the JSON object — no commentary before or after.

<chunk>
REPLACE_ME
</chunk>")
