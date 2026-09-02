(ns digdir.skills.enrichment.extract-intent
  "`:builtin/extract-user-intent` — turn a noisy user prompt into a
   clean topical query (in the corpus language) plus any explicitly
   named chunk_ids.

   Single LLM call. The model is asked to return JSON; if it strays
   (no JSON, malformed JSON, missing keys), the skill falls back to a
   safe default rather than failing the whole graph — same graceful-
   degradation pattern as the LLM-selection branch of analyze-corpus.

   This skill exists because the self-improve graph's `:analyze` step
   was doing keyword content-search against a noisy English request
   over a Norwegian corpus. Result: zero hits or wrong hits. Pulling
   intent extraction out as a discrete graph node gives:

   - **Cross-language normalisation** — translate the topical part to
     the corpus language.
   - **Noise stripping** — drop instructional scaffolding (\"propose 4
     questions, then call run_eval_delta, ...\") and keep only what's
     about the corpus.
   - **Explicit-reference detection** — when the user names a chunk_id
     verbatim, surface it so analyze can honour the named target
     instead of inferring one from a search.

   Lives in `src-dev/` alongside its siblings — offline tooling, not
   part of the runtime retrieval path."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.rag.skills.core :as skills]
            [taoensso.timbre :as timbre]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def extract-intent-metadata
  {:skill-id :builtin/extract-user-intent
   :name "Extract user intent"
   :description "Single LLM call that transforms a raw user prompt into a clean topical query (in the corpus language), an optional list of explicit chunk_ids the user named verbatim, and a one-line goal summary. Used by the self-improve graph as the first step so downstream chunk-selection has a clean signal."
   :category :orchestration
   :inputs [:user-query]
   :outputs [:topic :explicit-chunk-ids :goal]
   :parameters {:corpus-language :string
                :model :string}
   :version "1.0.0"
   :tags #{:llm :enrichment :self-improve :graph-only}})

;; =============================================================================
;; Prompt
;; =============================================================================

(defn render-prompt
  "Build the LLM prompt. Public so tests can pin wording without
   needing an LLM."
  [user-query corpus-language]
  (str
   "You are extracting the user's intent from a request to a "
   "documentation-enrichment agent. Reply with a single JSON object "
   "and nothing else (no prose, no Markdown code fence).\n\n"
   "The JSON object must have exactly these three keys:\n"
   "- \"topic\": a short topical phrase (5-15 words) suitable as a "
   "content-search query. Translate to the corpus language: "
   (or corpus-language "Norwegian (bokmål)") ". "
   "Drop instructional scaffolding like \"propose questions\", "
   "\"apply enrichments\", \"run eval\", etc. — keep only what the "
   "user wants to LEARN ABOUT or ENRICH.\n"
   "- \"explicit_chunk_ids\": an array of strings naming specific "
   "chunk_ids the user mentioned verbatim. A chunk_id is a hex-like "
   "token (12+ characters, lowercase a-f and 0-9). Empty array if "
   "none mentioned.\n"
   "- \"goal\": one sentence (no more than 25 words) summarising "
   "what the user wants to accomplish.\n\n"
   "User request:\n"
   user-query))

;; =============================================================================
;; Parsing
;; =============================================================================

(def ^:private chunk-id-re
  ;; Hex-like token of 8+ chars, lowercase. Matches the shape this
  ;; corpus uses (e.g. "8e22ae4b88b1"). Tightening below 8 chars would
  ;; risk picking up year numbers and stray hex literals.
  #"(?<![a-f0-9])[a-f0-9]{8,}(?![a-f0-9])")

(defn- strip-json-fence
  "LLMs occasionally wrap JSON in a ```json … ``` fence even when asked
   not to. Drop a single leading/trailing fence pair if present."
  [s]
  (-> s
      str/trim
      (str/replace #"^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")))

(defn parse-intent-response
  "Parse the LLM response into `{:topic :explicit-chunk-ids :goal}`.
   Tolerant: any missing/wrong-shaped field gets a safe default rather
   than failing the parse. Public so tests can pin behaviour without
   hitting an LLM."
  [response]
  (let [content (-> response :choices first :message :content (or ""))
        cleaned (strip-json-fence content)
        parsed (try (json/read-str cleaned :key-fn keyword)
                    (catch Throwable _ nil))
        topic (when (map? parsed)
                (let [t (:topic parsed)]
                  (when (and (string? t) (seq (str/trim t)))
                    (str/trim t))))
        raw-ids (when (map? parsed)
                  (let [v (:explicit_chunk_ids parsed)]
                    (cond
                      (sequential? v) v
                      (string? v) [v]
                      :else [])))
        chunk-ids (->> raw-ids
                       (filter string?)
                       (map str/trim)
                       (filter #(re-matches chunk-id-re %))
                       distinct
                       vec)
        goal (when (map? parsed)
               (let [g (:goal parsed)]
                 (when (and (string? g) (seq (str/trim g)))
                   (str/trim g))))]
    {:topic topic
     :explicit-chunk-ids chunk-ids
     :goal goal}))

;; =============================================================================
;; Regex fallback
;; =============================================================================

(defn regex-extract-chunk-ids
  "Belt-and-braces extraction: even if the LLM fails to surface
   explicit chunk_ids, a regex scan of the raw user-query catches any
   it mentioned verbatim. Public for tests."
  [user-query]
  (->> (re-seq chunk-id-re (or user-query ""))
       distinct
       vec))

;; =============================================================================
;; LLM call
;; =============================================================================

(defn- resolve-model
  [tenant explicit-model]
  (or explicit-model
      (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)))

(defn- chat-completion
  "Single chat call. Kept private so tests can plumb a fake llm-call-fn
   to `extract-user-intent` instead of stubbing Azure config."
  [tenant model prompt]
  (openai/create-chat-completion
   {:model model
    :messages [{:role "system"
                :content "You extract user intent and return only JSON objects with the requested shape. No prose."}
               {:role "user" :content prompt}]
    :temperature 0.1}
   {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
    :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
    :impl :azure}))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-extract-user-intent
  "Build the prompt, ask the LLM, parse the JSON response. On any
   failure (LLM error, JSON malformed, missing keys), degrade to a
   safe default that lets the graph progress:

     :topic            → the raw user-query (so content-search has
                         SOMETHING to work with, even if noisy)
     :explicit-chunk-ids → regex-extracted chunk_ids from the raw text
                         (catches the user-names-a-chunk case even
                         when the LLM didn't return it)
     :goal             → nil"
  [{:keys [inputs parameters skill-params] :as _ctx}]
  (let [{:keys [user-query]} inputs
        {:keys [corpus-language model llm-call-fn]} parameters
        tenant (:tenant skill-params)
        prompt (render-prompt user-query corpus-language)
        regex-ids (regex-extract-chunk-ids user-query)
        ;; `:llm-call-fn` in parameters is the test seam — production
        ;; callers leave it nil and we use the default Azure path.
        call (or llm-call-fn
                 (fn [p] (chat-completion tenant (resolve-model tenant model) p)))
        {:keys [topic explicit-chunk-ids goal]}
        (try
          (parse-intent-response (call prompt))
          (catch Throwable t
            (timbre/warn t "extract-user-intent: LLM/JSON failed; using fallback")
            {:topic nil :explicit-chunk-ids nil :goal nil}))]
    (skills/success-result
     {:topic (or topic user-query)
      :explicit-chunk-ids (or (seq explicit-chunk-ids) regex-ids)
      :goal goal}
     {:tenant tenant
      :had-llm-topic? (some? topic)
      :had-llm-chunk-ids? (boolean (seq explicit-chunk-ids))
      :regex-chunk-ids regex-ids})))

;; =============================================================================
;; Registration
;; =============================================================================

(def extract-intent-skill
  {:metadata extract-intent-metadata
   :execute execute-extract-user-intent})

(defn register!
  "Register the extract-intent skill. Idempotent."
  []
  (skills/register-skill! extract-intent-skill))

(register!)
