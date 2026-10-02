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
            [digdir.llm.provider :as provider]))

;; ============================================================================
;; The call spec
;; ============================================================================

(defn- call-spec
  "`provider/resolve`'s call spec for `tenant`: the tenant's
   provider decision, its model and its own credentials. A missing credential
   refuses there, naming its path.

   The Azure branch keeps the 30s timeout this namespace always gave it. The
   OpenAI-compatible branch needs none: `digdir.llm.client` does not read
   `:request` there (its socket timeout is `OPENAI_SOCKET_TIMEOUT_MS`,
   default 10 min), so the 5 min this namespace used to pass was never
   applied."
  [tenant]
  (let [spec (provider/resolve tenant)]
    (cond-> spec
      (= :azure (:provider spec)) (assoc :request {:timeout 30000}))))

(defn- complete
  "One chat completion on `spec`. The model is the provider's own: a
   caller-supplied `:model` is overwritten, as it always was here."
  [spec conversation]
  (openai/create-chat-completion (assoc conversation :model (:model spec)) spec))

(defn create-chat-completion
  "Creates a chat completion on `tenant`'s provider, which since Phase 3 of the provider-resolver change
   is the one decision every LLM call follows (`services.llm.provider`, the
   legacy boolean as its fallback) - `services.search-phrases.provider` is
   retired and no longer read.

   `conversation` is `{:messages [...] [:response_format ...]}`. The
   `:model` key is set here from the provider's default model (the Azure
   deployment name, or `services.azure-openai.model-name`) — any
   caller-supplied `:model` is overwritten."
  [tenant conversation]
  (complete (call-spec tenant) conversation))

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

(defn local-cache-dir
  "Where a run for `cache-dir-name` reads AND WRITES its own entries, e.g.
   'website' -> 'cache/website-search-phrases/'. Relative, so under the
   container's /app it lands inside the `digdir-cache` volume."
  [cache-dir-name]
  (str "cache/" cache-dir-name "-search-phrases/"))

(defn declared-cache-dir
  "Where the DECLARED generator's entries live for `cache-dir-name` (tier 2,
   the phrase negative-cache fix, option e): a directory of its own, which the boot unpack fills and NO
   RUN EVER WRITES.

   ⛔ THIS MUST NOT BE THE LOCAL DIRECTORY, and it once was. The
   identity in a key is two NAMES, so a local install on `:azure` whose
   deployment is called `gpt-4o` computes exactly the declared key. With one
   shared directory its misses were written INTO the archive's population,
   indistinguishable from the curated entries, and a rebuild would have packed
   and shipped them as the declared generator's output. No key shape can
   separate two identities whose names are equal. Separate storage keeps THIS
   installation's output out of the archive's population - which is all it
   does: it cannot tell the two models apart, so a mis-loaded model still
   writes junk under its own correct-looking name, locally.

   It cannot collide with any run's directory: `local-cache-dir` always ends in
   `-search-phrases/`, this always in `-search-phrases-declared/`."
  [cache-dir-name]
  (str "cache/" cache-dir-name "-search-phrases-declared/"))

(def parser-version
  "Bump when parse-phrases-response changes in a way that makes prior
   cache entries unreliable. It is the LAST segment of `cache-key`, whose
   earlier segments name the chunk text, the provider that answers and the
   model actually sent. Old cache files (with no version suffix, a different
   one, or the four-segment key that predates the provider) are ignored —
   they become orphan disk space that can be cleaned up out-of-band.

   That includes NEGATIVE entries: the KUDOS loader caches `[]` when the
   primary answered in full and this parser found no phrases. So a
   change that finds phrases where the current parser finds none — even
   one that leaves every existing phrase list the same — must bump this,
   or those chunks are never asked again.

   v2 (2026-05-26): switched parser to JSON-mode-first with a
   line-walking heuristic fallback. The v1 parser took the last line
   unconditionally, which captured LLM meta-commentary (\"3. keywords
   only.\" etc.) instead of phrases for 99.9% of cached responses."
  "v2")

(defn identity-segments
  "`identity`'s two segments of `cache-key`, in key order: `[provider model]`.
   Public so the boot unpack verifies an archive with exactly the hashing the
   lookup will use."
  [{:keys [provider model]}]
  [(core/sha256-short-hash (if (keyword? provider) (name provider) (str provider)))
   (core/sha256-short-hash model)])

(defn cache-key
  "The cache key for one chunk's search phrases, under ONE `identity`:

     <sha256-short(chunk text)>-<sha256-short(provider)>-<sha256-short(model)>-<sha256-short(prompt)>-<parser-version>

   `identity` is `{:provider … :model …}`: the provider that answers and **the
   model that is actually sent** — `(provider/selected-provider tenant)` and
   `(provider/model-for tenant …)`. Neither reads a credential, so the key is
   computable before the call, which is what a cache needs.

   ⚠️ NOT the configured `:search-phrases/model`. That value is overwritten by
   `complete` before the request leaves, so keying on it named a model that was
   never sent, and two different effective models shared one entry. The
   provider is its own segment because the provider CHANGES THE PROMPT BYTES —
   `response-format-for-provider`, and the JSON mention Azure requires.

   Takes an identity rather than a tenant so the key can be built under a
   DECLARED identity that is not this install's: tier 2 reads the shipped warm
   cache that way (the phrase negative-cache issue option e). It reads only - a write always uses the
   identity that produced the entry, and always goes to `local-cache-dir`.

   Keyed on content rather than `chunk_id`, because content is what
   determines the answer: `distill-search-phrases` sends the prompt with
   `REPLACE_ME` replaced by the chunk text and nothing else — no title, no
   document metadata. Keying on `chunk_id` only worked while ids *were*
   content hashes; once they became document-scoped (#72) the same key would
   have meant re-generating phrases for every copy of a duplicated chunk, and
   the corpus has 9% duplicates. Same inputs, same key, one LLM call.

   Cache files written under any earlier key are simply never read again — the
   same orphaning as a `parser-version` bump, and there is no migration: an
   entry written before this change cannot say which model produced it, so
   re-keying one would assert an identity nobody measured."
  [chunk identity prompt]
  ;; REFUSE a non-map identity. The arity did not change when the second
  ;; argument became `{:provider … :model …}`, so a caller left on the old
  ;; `model` string would destructure to nils and key EVERY chunk under one
  ;; degenerate identity - silently, and only visible as a cache that never
  ;; hits. A missed call site fails here instead.
  (when-not (and (map? identity) (:provider identity) (:model identity))
    (throw (ex-info "cache-key needs {:provider … :model …}: the provider that answers and the model actually sent"
                    {:identity identity})))
  (let [[provider-seg model-seg] (identity-segments identity)]
    (str (core/sha256-short-hash (:content_markdown chunk)) "-"
         provider-seg "-"
         model-seg "-"
         (core/sha256-short-hash prompt) "-"
         parser-version)))

(defn legacy-cache-key
  "The FOUR-segment key an archive built before the phrase negative-cache issue carries:

     <sha256-short(chunk text)>-<sha256-short(model)>-<sha256-short(prompt)>-<parser-version>

   - exactly `main`'s key, whose model segment hashed the CONFIGURED model.
   Tier 2 looks the committed archive up under it, with the DECLARED model,
   because the unpack stores that archive AS SHIPPED rather than re-keying it:
   a re-key would write a provider segment nobody generated, copied from the
   declaration it would later be checked against.

   Read-only. Nothing writes under it: a run always writes `cache-key`."
  [chunk identity prompt]
  (str (core/sha256-short-hash (:content_markdown chunk)) "-"
       (second (identity-segments identity)) "-"
       (core/sha256-short-hash prompt) "-"
       parser-version))

(def declared-identity-resource
  "The generator that produced the committed warm cache, shipped as DATA beside
   the archive itself. Not derived from the archive's keys: a key's segments are
   hashes, and tier 2 must combine the DECLARED provider and model with the
   LOCAL prompt and parser version, so an installation that changed its prompt
   MISSES. Deriving by position would couple three segments where two travel."
  "demo-corpus/phrase-cache-folder-v2.identity.edn")

(defn validate-declared-identity
  "EXACTLY ONE identity, refused rather than iterated. Every extra generator is
   another model whose output we would serve, which is a policy decision and not
   a configuration value - so a list fails here instead of growing the lookup."
  [declaration]
  (when-not (map? declaration)
    (throw (ex-info "the declared phrase-cache identity must be ONE map: a collection of identities is refused, not iterated"
                    {:resource declared-identity-resource :type (type declaration)})))
  (when-not (and (:provider declaration) (:model declaration))
    (throw (ex-info "the declared phrase-cache identity needs :provider and :model"
                    {:resource declared-identity-resource :declaration (select-keys declaration [:provider :model])})))
  (select-keys declaration [:provider :model]))

(def declared-identity
  "The ONE generator whose entries this installation will read but never write
   (the phrase negative-cache issue option e) - read from `declared-cache-dir`, which no run writes. nil
   when no declaration ships, which is the normal state for any cache that is
   not the demo warm cache.

   Serving these entries is a DECLARED acceptance of one curated generator. The
   alternative is not safety: without it, two installations that happen to share
   a model name already serve each other's entries silently, with no policy and
   no record."
  (memoize
   (fn []
     (when-let [r (jio/resource declared-identity-resource)]
       (validate-declared-identity (edn/read-string (slurp r)))))))

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
   request, scoped to the provider (`provider/resolve`'s vocabulary):

   - `:azure`             → `json_object` (widely supported on Azure gpt-4o)
   - `:openai-compatible` → `json_schema`, the only structured-output mode
                            LM Studio accepts (it rejects `json_object` with
                            HTTP 400). LM Studio is the only OpenAI-compatible
                            server this path has been run against, so it
                            decides (Phase 3 of the provider-resolver change, which folded the old
                            `:lmstudio` and `:openrouter` arms into this one).
                            ⚠️ UNTESTED: an OpenRouter-routed model that rejects
                            structured output would fail here, where the old
                            `:openrouter` arm sent no `response_format`.
   - anything else        → nil"
  [provider]
  (case provider
    :openai-compatible {:type "json_schema" :json_schema json-schema-phrases}
    :azure             {:type "json_object"}
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
  (let [spec (call-spec tenant)
        rf (response-format-for-provider (:provider spec))
        content (-> prompt
                    (str/replace "REPLACE_ME" chunk-content)
                    (ensure-json-mentioned-when-required rf))
        convo (cond-> {:model model
                       :messages [{:role "user" :content content}]}
                rf (assoc :response_format rf))]
    (parse-phrases-response (complete spec convo))))

(defn mk-distill-search-phrases-t
  "Creates a Missionary task that generates search phrases for a chunk.
   Uses caching and model fallback.

   Config paths used:
   - :search-phrases/model - primary model
   - :search-phrases/fallback-model - fallback if primary fails
   - :search-phrases/prompt - prompt template (REPLACE_ME is replaced with content)

   cache-dir-name names two source-specific directories: `local-cache-dir`,
   which this reads and writes, and `declared-cache-dir`, which it only reads
   (e.g., 'website' -> 'cache/website-search-phrases/' and
   'cache/website-search-phrases-declared/')"
  [{:search-phrases/keys [model fallback-model prompt] :as config} chunk cache-dir-name]
  (m/via m/blk
         (let [cache-dir (local-cache-dir cache-dir-name)
               ;; The identity that will answer, resolved BEFORE the key: the
               ;; provider decision and the model this call actually sends.
               ;; `complete` overwrites the caller's `:model` with the
               ;; provider's own, so `model` above is never on the wire
               ;; — and both reads here are credential-free, so a cache HIT
               ;; still works on a tenant whose credentials are missing.
               call-identity {:provider (provider/selected-provider (:tenant config))
                              :model (provider/model-for (:tenant config))}
               cache-path (str cache-dir (cache-key chunk call-identity prompt) ".edn")
               ;; TIER 2, READ-ONLY: the declared generator's entries, under ITS
               ;; identity and this installation's prompt and parser version, in
               ;; ITS OWN DIRECTORY. The key alone cannot keep a write out: an
               ;; install whose provider and model are NAMED like the declared
               ;; ones computes this very key. So nothing below writes
               ;; outside `cache-dir`, and this path is never under it - a local
               ;; write can never land among the declared entries, whatever the
               ;; names are. Writing there would stamp our output with somebody
               ;; else's name, which is the laundering the KUDOS phrase-parser unification refused.
               ;; The declared directory holds its archive AS SHIPPED, so its
               ;; keys are in the grammar that archive was built under: the
               ;; current key, or `main`'s four-segment one (the committed
               ;; archive). One archive has one shape; both are tried.
               declared (declared-identity)
               declared-paths (when declared
                                (let [dir (declared-cache-dir cache-dir-name)]
                                  [(str dir (cache-key chunk declared prompt) ".edn")
                                   (str dir (legacy-cache-key chunk declared prompt) ".edn")]))
               local-entry (read-cached-phrases cache-path)
               declared-entry (when (and declared-paths (not (seq local-entry)))
                                (some read-cached-phrases declared-paths))
               ;; PRECEDENCE, and the order is the point: a local positive, then
               ;; the declared positive, then a local negative. A mis-loaded
               ;; local model is exactly what writes negatives, so serving a
               ;; local [] ahead of a declared generator's phrases would make
               ;; the archive stop helping precisely when the model is broken.
               ;; Tier 2 is POSITIVE-ONLY: "this chunk has no phrases" is a
               ;; claim about the chunk under a model, and only the local model
               ;; may make it here.
               cached-phrases (cond
                                (seq local-entry) local-entry
                                (seq declared-entry) declared-entry
                                (some? local-entry) local-entry
                                :else nil)]

           (ensure-cache-dir! cache-dir)

           (if (some? cached-phrases)
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
                     ;; supplied :model with the provider's default model.
                     ;; Logging only `model` (the pipeline-config arg) was
                     ;; misleading: a timeout from LM Studio surfaced as
                     ;; "failed with gpt-5.4-mini" even though gpt-5.4-mini
                     ;; was never sent anywhere (2026-05-26). Neither read
                     ;; touches a credential.
                     provider (provider/selected-provider tenant)
                     effective-model (provider/model-for tenant)
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
