(ns digdir.skills.builtin.query-planner
  "Query Planner skill - wraps query expansion function.

   This skill expands a user query into multiple search phrases
   using LLM-based query relaxation."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.rag.retrieval :as retrieval]
            [digdir.llm.client :as client]
            [digdir.llm.openai :as llm]
            [clojure.data.json :as json]
            [clojure.string :as str]))

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


;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def query-planner-metadata
  {:skill-id :builtin/query-planner
   :name "Query Planner"
   :description "Extract a clean topical user-intent (in the corpus language) and expand into multiple search phrases via a single LLM call."
   :category :query-transformation
   ;; :phrases-collection is declared for documentation but marked :optional-inputs
   ;; — only the corpus-aware expansion modes consult it (blind falls back without
   ;; it), and graphs whose plan-queries step doesn't wire it (e.g. the
   ;; self-improve-graph) must not be rejected. :optional-inputs exempts it from
   ;; check-required-inputs (which otherwise treats every :inputs entry as
   ;; REQUIRED). Still received when provided (resolved-inputs is a full merge).
   :inputs [:query :conversation-history :phrases-collection]
   :optional-inputs [:phrases-collection]
   ;; :user-intent is the LLM-canonicalized topical version of the user's
   ;; question — translated to the corpus language, stripped of noise.
   ;; :queries is a vec whose FIRST element is :user-intent (no-regression
   ;; guarantee against the literal-query baseline) followed by N
   ;; expansion phrases.
   :outputs [:queries :user-intent]
   :parameters {:model :string
                :prompt :string
                :max-phrases :number
                :temperature :number
                :enabled :boolean
                ;; Corpus language hint passed to the LLM. When set, the
                ;; planner translates the :user-intent into this language
                ;; (e.g. an EN question against an NB corpus gets an NB
                ;; topic). Empty/nil disables translation.
                :corpus-language :string
                ;; :blind (default) | :corpus-aware-1hop | :corpus-aware-2hop.
                ;; Corpus-aware modes harvest real corpus vocabulary from the
                ;; phrases collection (PRF) and ground a 2nd LLM call on it.
                :expansion-mode :keyword
                ;; :two-call (default) | :one-call. Corpus-aware only. one-call
                ;; drops the blind-expansion LLM call (deterministic raw-query
                ;; probes + a single grounding+intent call) → halves LLM calls.
                :expansion-variant :keyword
                ;; Salient-noun coverage (opt-in). When true, the planner must
                ;; lead with a short feature-noun query in BOTH languages (NB+EN)
                ;; — fixes the query-variance pool-miss (golden buried when the
                ;; planner emits generic/wrong-language phrases). See
                ;; plans/proposed/query-planner-salient-noun-plan.md.
                :salient-noun-coverage :boolean}
   :version "2.0.0"
   :tags #{:llm :query-expansion :production}
   :tool-definition query-planner-tool-definition})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(def search-results-tools
  "Tool spec for structured query-planning output. The LLM returns both:
   - :user_intent — the canonical clean topical question in the corpus language
   - :search_phrases — a vector of expansion phrases for retrieval

   Inspired by `digdir.skills.enrichment.extract-intent` (which lives in
   src-dev/ as offline tooling) — the canonical-question extraction
   pattern is the load-bearing piece for v3 retrieval quality (see
   plans/in-progress/target-optimal-baseline-v3/17-llm-query-expansion-validation.md
   — without it, LLM expansion can regress on queries where the literal
   form was the best probe)."
  [{:type "function"
    :function
    {:name "planQueries"
     :description "Extract the user's topical intent and generate search phrases for retrieval."
     :parameters
     {:type "object"
      :properties
      {:user_intent
       {:type "string"
        :description "The user's topical intent as a short clean question or phrase (5-15 words). Translate to the corpus language. Drop instructional scaffolding."}
       :search_phrases
       {:type "array"
        :items {:type "string"}
        :description "Diverse search phrases the retriever should try, covering the same intent from different angles."}}
      :required ["user_intent" "search_phrases"]}}}])

(defn format-conversation-history
  "Format conversation history for the prompt."
  [messages]
  (->> messages
       (map (fn [msg]
              (let [role (if (= :user (:role msg)) "User" "Assistant")
                    text (or (:content msg) (:text msg) (:message/text msg) "")]
                (str role ": \"" text "\""))))
       (str/join "\n\n")))

(def ^:private retry-delays-ms
  "Exponential backoff schedule for transient LLM-call failures
   (timeouts, 429, 5xx). 3 attempts total: first call + 2 retries.
   Total worst-case wall-time on failure = 7.5s before fallback fires."
  [500 2000 5000])

(defn- transient-llm-error?
  "Classify an exception as worth retrying. Connection timeouts and HTTP
   errors with 5xx or 429 are transient; everything else is a real
   failure (bad input, missing config, etc.) that retry won't help."
  [^Throwable t]
  (let [msg (.getMessage t)
        msg-lc (some-> msg clojure.string/lower-case)]
    (boolean
      (or (instance? java.net.http.HttpTimeoutException t)
          (instance? java.net.SocketTimeoutException t)
          (instance? java.io.IOException t)
          (and msg-lc
               (or (clojure.string/includes? msg-lc "timeout")
                   (clojure.string/includes? msg-lc "timed out")
                   (clojure.string/includes? msg-lc " 429 ")
                   (clojure.string/includes? msg-lc " 500 ")
                   (clojure.string/includes? msg-lc " 502 ")
                   (clojure.string/includes? msg-lc " 503 ")
                   (clojure.string/includes? msg-lc " 504 ")))))))

(defn- with-llm-retries
  "Invoke `f` with exponential backoff on transient errors. Returns the
   result on success; rethrows the last exception on persistent failure.

   Logs each retry attempt so flaky Azure deployments are visible. The
   outer caller has its own try/catch that converts any final failure
   into the planner's safe `[raw-query]` fallback."
  [f]
  (loop [attempt 0]
    (let [{:keys [result error]}
          (try {:result (f)}
               (catch Throwable t {:error t}))]
      (cond
        result result

        (and error
             (transient-llm-error? error)
             (< attempt (count retry-delays-ms)))
        (let [delay-ms (nth retry-delays-ms attempt)]
          (println (str "query-planner LLM call attempt " (inc attempt)
                        " failed (" (.getMessage ^Throwable error) "); "
                        "retrying after " delay-ms "ms"))
          (Thread/sleep delay-ms)
          (recur (inc attempt)))

        :else
        (throw error)))))

(defn- build-default-prompt
  "Compose the planner prompt — derived from `digdir.skills.enrichment.extract-intent`'s
   canonical-question extraction crossed with the original query-planner's
   phrase generation. Single LLM call returns both.

   Language handling:
   - When `corpus-language` is nil/blank (default), the user-intent stays
     in the user's original language. Safe for bilingual or
     language-mixed corpora where forcing translation can move the
     intent away from the docs that actually contain the answer.
   - When `corpus-language` is set explicitly, the LLM translates the
     user-intent into that language. Use this for monolingual corpora
     when the user may ask in a different language."
  [message-string corpus-language salient-noun?]
  (let [translate? (and corpus-language (not (str/blank? corpus-language)))]
    (str
      "You plan retrieval queries against a documentation corpus. The user's "
      "message may contain instructional scaffolding or conversation context.\n\n"
      "Conversation:\n" message-string "\n\n"
      "Do two things in a single response:\n\n"
      "1. **Extract the user's topical intent** as a short clean question or phrase "
      "(5-15 words). "
      (if translate?
        (str "Translate it to the corpus language: " corpus-language ". ")
        (str "LANGUAGE: Use EXACTLY the same language as the user's message. "
             "If the user wrote in English, the user-intent MUST be in English. "
             "If Norwegian, Norwegian. Do NOT translate between languages even "
             "if the corpus has docs in a different language — the retriever "
             "searches the same language as the query, so translation moves the "
             "search away from same-language matches. "))
      "Strip instructional scaffolding (\"propose phrases\", \"run eval\", etc.) — "
      "keep only what the user wants to LEARN ABOUT. Resolve anaphora using "
      "conversation context. This becomes the canonical query for the retriever.\n\n"
      "2. **Generate 4-6 diverse search phrases** the retriever should try, "
      "covering the same intent from different angles. "
      ;; str, not two bare strings: `when-not` returns only its LAST form, so
      ;; the first half was discarded and the prompt shipped a dangling
      ;; "(see LANGUAGE rule above)." with the rule it referred to missing (#75).
      (when-not translate?
        (str "Every phrase MUST be in the same language as the user's message "
             "(see LANGUAGE rule above). "))
      "\n\nPhrase-generation rules:\n"
      "- Prefer phrases that could appear verbatim in an answer passage, not just "
      "topical keywords. For \"Når ble X lansert?\" include phrases like \"X ble "
      "lansert\" or \"første versjon av X\".\n"
      "- Include one short precise phrase (2-4 words) and one longer specific "
      "phrase (5-10 words). Avoid single-word phrases that match everything in "
      "the corpus.\n"
      "- Vary the angle (entity+fact, entity+time, entity+event) but stay on-topic. "
      "Do not restate the same phrase with trivial word-order changes.\n"
      (if salient-noun?
        ;; Salient-noun coverage: guarantee a short feature-noun query in BOTH
        ;; languages, leading the batch. The query-variance diagnostic found the
        ;; golden pools top-10 iff such a query is issued in the golden's
        ;; language, and is buried when the planner collapses to generic/
        ;; wrong-language phrases (6 of 8 high-variance questions).
        (str "- SALIENT-NOUN COVERAGE (REQUIRED): the FIRST TWO search phrases "
             "MUST be short (2-5 words) phrases naming the question's single most "
             "specific feature, API, endpoint, file, or concept — ONE in "
             "Norwegian (bokmål) and ONE in English — regardless of the user's "
             "language. They pin the exact vocabulary the answer passage uses. "
             "Examples: adding a PDF step → \"PDF service task\" + "
             "\"PDF-tjenesteoppgave\"; publishing events → \"POST /events publish\" "
             "+ \"publisere hendelse\"; recipient filter → \"recipient filter\" + "
             "\"mottakerfilter\". Put these two FIRST; never lead with generic "
             "topic words like \"Altinn events\" or \"process editor\". Emit at "
             "most these two — do not pad with many near-duplicate short phrases.")
        (str "- The first search phrase should be the user-intent verbatim — this "
             "is the no-regression baseline against the literal query.")))))

;; =============================================================================
;; Corpus-aware expansion (pseudo-relevance feedback)
;; =============================================================================
;; The blind planner expands using the LLM's general knowledge, so it never
;; emits the corpus's idiosyncratic vocabulary (e.g. "dynamic expressions").
;; Corpus-aware mode harvests real phrases from the live `phrases` collection,
;; then a 2nd LLM call grounds the expansion on them. See
;; plans/proposed/corpus-aware-prf-expansion-plan.md.

(def ^:private grounding-tools
  "Tool spec for the 2nd (grounding) LLM call — returns only the
   corpus-grounded search phrases."
  [{:type "function"
    :function
    {:name "groundedQueries"
     :description "Produce retrieval search phrases using the corpus's actual vocabulary."
     :parameters
     {:type "object"
      :properties
      {:search_phrases
       {:type "array"
        :items {:type "string"}
        :description "Search phrases built from the corpus's own terminology that will retrieve the answer passages."}}
      :required ["search_phrases"]}}}])

(defn- build-grounding-prompt
  "Grounding = GENERATE phrases biased toward the harvested corpus vocabulary.
   (A pure select-from-harvested variant under-performed — it constrained the
   LLM to a noisy candidate pool and lost golden-matching phrasings the
   generate form produces. Keep BOTH the harvested terms AND the procedural/
   specific phrasings front-and-centre, and tell it to keep verbatim ones.)"
  [user-query blind-phrases candidates]
  (str
    "You refine retrieval queries for a documentation corpus.\n\n"
    "User's question:\n" user-query "\n\n"
    "A first-pass expansion produced these phrases (generated WITHOUT seeing the corpus):\n"
    (str/join "\n" (map #(str "- " %) blind-phrases)) "\n\n"
    "The following phrases were harvested from the corpus near this topic, ordered "
    "best-match first — this is the corpus's REAL vocabulary:\n"
    (str/join "\n" (map #(str "- " %) candidates)) "\n\n"
    "Produce 6-10 search phrases that will retrieve the passages answering the "
    "user's question.\n"
    "- Build them from the corpus's actual terminology above — prefer the harvested "
    "wording (keep strong ones VERBATIM) over generic paraphrases.\n"
    "- Keep SPECIFIC and PROCEDURAL phrasings (exact feature/property names, step "
    "wording), not only general/topical ones.\n"
    "- Each phrase should read like text that appears in an answer passage."))

(defn- planner-completion
  "Forced-tool-call completion for the planner, routed through `digdir.llm.client`
   (migrated off litellm-azure so the planner runs on the same model as the rest of
   the pipeline — Qwen3.6 MTP locally, or Azure). Builds an OpenAI body, routes
   azure-vs-local via `use-azure-openai-api`, and normalizes the response's
   `:tool_calls` → `:tool-calls` so the existing parsing is unchanged.

   `req` mirrors the old litellm call's request map: `{:messages :tools :tool-choice
   :temperature}` (`:tool-choice :required` → OpenAI `\"required\"`). Locally the
   client also honours `OPENAI_*` sampling/prefill env. NOTE: tool-calling on the
   Azure path still flows through wkok — if a newer `tool_choice` value is stripped
   there, the cloud planner falls back via the caller's try/catch; local (the
   target config) passes the body verbatim."
  [tenant deployment {:keys [messages tools tool-choice temperature]}]
  (let [azure? (llm/use-azure-openai tenant)
        ;; The planner is non-reasoning, but it runs INSIDE the (thinking) agent
        ;; process, so process-global `OPENAI_DISABLE_THINKING` can't single it out.
        ;; So prefill a closed think block here (local path only) — forces
        ;; non-thinking on Qwen3.6 GGUF regardless of env; validated to compose with
        ;; forced tool-calling. Azure path leaves messages untouched.
        messages (cond-> (vec messages)
                   (not azure?) (conj {:role "assistant" :content "<think></think>"}))
        body (cond-> {:model deployment
                      :messages messages
                      :temperature (or temperature 0.0)}
               (seq tools) (assoc :tools tools)
               tool-choice (assoc :tool_choice (name tool-choice)))
        opts (when azure?
               {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
                :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
                :impl :azure})
        resp (client/create-chat-completion body opts)]
    (if-let [tcs (get-in resp [:choices 0 :message :tool_calls])]
      (assoc-in resp [:choices 0 :message :tool-calls] tcs)
      resp)))

(defn- call-grounding-llm
  "Second LLM call: ground expansions on harvested corpus vocabulary.
   Returns a vec of grounded phrases, or nil on failure (caller falls back)."
  [tenant deployment temperature user-query blind-phrases candidates]
  (let [prompt (build-grounding-prompt user-query blind-phrases candidates)
        result (try
                 (with-llm-retries
                   (fn []
                     (planner-completion tenant deployment
                                         {:messages [{:role :user :content prompt}]
                                          :tools grounding-tools
                                          :tool-choice :required
                                          ;; Grounding wants determinism — default 0
                                          ;; (see results.md Phase E variance arc).
                                          :temperature (or temperature 0.0)})))
                 (catch Throwable t
                   (println (str "query-planner grounding LLM call failed: " (.getMessage t)))
                   nil))
        tool-calls (-> result :choices first :message :tool-calls)
        parsed (some (fn [tc]
                       (try (json/read-str (-> tc :function :arguments) :key-fn keyword)
                            (catch Exception _ nil)))
                     tool-calls)]
    (some->> (:search_phrases parsed)
             (map str/trim)
             (remove str/blank?)
             distinct
             vec)))

(defn- rrf-rank
  "Aggregate harvested phrases across probes by Reciprocal Rank Fusion:
   score(phrase) = Σ 1/(k + probe-rank+1) over every probe hit for that
   phrase. Rank-based (not raw score) so it's comparable across probe
   queries — phrases that rank high across MULTIPLE conceptual probes float
   up. Returns `[{:search-phrase :doc-num :rrf} ...]` sorted desc. k=60 is the
   standard RRF constant."
  [harvested]
  (let [k 60.0]
    (->> harvested
         (remove (comp str/blank? str :search-phrase))
         (group-by :search-phrase)
         (map (fn [[phrase hits]]
                {:search-phrase phrase
                 :doc-num (:doc-num (first hits))
                 :rrf (reduce + (map (fn [h] (/ 1.0 (+ k (inc (:probe-rank h 0))))) hits))}))
         ;; Secondary key (phrase string) breaks equal-RRF ties deterministically
         ;; — group-by/hash-map iteration order is otherwise unstable run-to-run
         ;; (results.md Phase E, source #5).
         (sort-by (juxt (comp - :rrf) :search-phrase))
         vec)))

(defn- top-doc-nums
  "Top-N distinct doc_nums from RRF-RANKED harvested phrases. Used to scope
   the hop-2 harvest to the docs hop-1 surfaced most strongly — NEVER to
   goldens. This is the in-code blindness guarantee."
  [ranked n]
  (->> ranked (keep :doc-num) distinct (take n) vec))

(defn- doc-num-filter
  "Raw Typesense exact-match filter over a set of doc_nums, or nil."
  [doc-nums]
  (when (seq doc-nums)
    (str "doc_num:=[" (str/join "," doc-nums) "]")))

(defn- corpus-aware-expand
  "PRF expansion. Returns {:queries [...] :metadata {...}} on success, or
   nil when it cannot run (no phrases-collection, empty harvest, or grounding
   failure) so the caller falls back to the blind result.

   `two-hop?` adds a second harvest scoped to the docs hop-1 surfaced most
   strongly (by score), targeting procedural vocabulary the conceptual hop-1
   probes miss. Candidates are score-ranked before grounding so the LLM sees
   the best corpus phrases first."
  [{:keys [tenant deployment temperature phrases-collection two-hop?
           user-intent blind-phrases query max-phrases]}]
  (when phrases-collection
    (let [probes (->> (cons user-intent blind-phrases) (remove nil?) distinct vec)
          hop1 (retrieval/harvest-search-phrases phrases-collection probes nil
                                                 {:tenant tenant :limit 12})
          ranked1 (rrf-rank hop1)
          ;; Scope hop-2 to the strongest hop-1 docs (wider than the first
          ;; cut's 5 → 10, so golden docs ranked just outside top-5 still get
          ;; their procedural phrases harvested).
          hop1-docs (top-doc-nums ranked1 15)
          hop2 (when (and two-hop? (seq hop1-docs))
                 (retrieval/harvest-search-phrases
                   phrases-collection probes
                   (doc-num-filter hop1-docs)
                   {:tenant tenant :limit 12}))
          candidates (->> (rrf-rank (concat hop1 hop2))
                          (map :search-phrase)
                          (take 25)
                          vec)]
      (when (seq candidates)
        (when-let [grounded (call-grounding-llm tenant deployment temperature
                                                query blind-phrases candidates)]
          ;; Corpus-aware modes want more probes than the blind default, so
          ;; the harvested corpus phrases actually reach the retriever.
          (let [cap (max (or max-phrases 0) 10)
                queries (->> (cons user-intent grounded)
                             (remove nil?) distinct vec)
                queries (vec (take cap queries))]
            {:queries queries
             :metadata {:expansion-mode (if two-hop? :corpus-aware-2hop :corpus-aware-1hop)
                        :hop1-phrase-count (count hop1)
                        :hop2-phrase-count (count (or hop2 []))
                        :hop1-docs hop1-docs
                        :harvested-phrase-count (count candidates)
                        :grounded-phrase-count (count grounded)}}))))))

;; ---------------------------------------------------------------------------
;; Single-call variant (Design 1): drop the blind-expansion LLM call; probe the
;; harvest deterministically from the raw query; one grounding call that ALSO
;; extracts the user-intent. Halves the per-query LLM calls (→ halves the Azure
;; failure surface). See plans/proposed/corpus-aware-prf-expansion-plan.md.

(def ^:private probe-stopwords
  #{"how" "do" "i" "the" "a" "an" "to" "in" "my" "of" "for" "on" "and" "or" "is"
    "are" "can" "what" "when" "without" "each" "time" "with" "that" "this" "it"
    "as" "by" "from" "into" "at" "be" "should" "would" "could" "you" "your" "we"
    "our" "them" "they" "if" "only" "some" "one" "another" "has" "have" "does"
    "me" "so" "but" "not" "no" "up" "out" "get" "got" "use" "using" "want"})

(defn- keyword-probes
  "Deterministic harvest probes from the raw query (no LLM): the raw query plus
   a stopword-stripped content-word probe. Cheap stand-in for the blind LLM
   expansion phrases the two-call variant used as probes."
  [query]
  (let [content (->> (str/split (str/lower-case (str query)) #"[^a-z0-9]+")
                     (remove str/blank?)
                     (remove probe-stopwords)
                     (filter #(> (count %) 2)))]
    (->> [query (str/join " " content)]
         (remove str/blank?)
         distinct
         vec)))

(defn- build-onecall-prompt
  "Single-call prompt: extract user-intent AND ground search phrases on the
   harvested corpus vocabulary, in one shot."
  [query candidates]
  (str
    "You plan retrieval queries for a documentation corpus.\n\n"
    "User's question:\n" query "\n\n"
    "The phrases below were harvested from the corpus near this topic, ordered "
    "best-match first — this is the corpus's REAL vocabulary:\n"
    (str/join "\n" (map #(str "- " %) candidates)) "\n\n"
    "Do two things:\n"
    "1. Extract the user's topical intent as a short clean question or phrase "
    "(5-15 words), in the user's own language.\n"
    "2. Produce 6-10 search phrases that will retrieve the passages answering the "
    "question. Build them from the harvested corpus vocabulary above — prefer the "
    "harvested wording (keep strong ones VERBATIM) over generic paraphrases; keep "
    "SPECIFIC and PROCEDURAL phrasings; drop clearly off-topic ones."))

(defn- call-grounding-with-intent
  "One LLM call returning BOTH user-intent and grounded phrases (reuses the
   {user_intent, search_phrases} tool shape). Returns {:user-intent :phrases} or
   nil on failure / empty phrases (caller then falls back to the raw query)."
  [tenant deployment temperature query candidates]
  (let [prompt (build-onecall-prompt query candidates)
        result (try
                 (with-llm-retries
                   (fn []
                     (planner-completion tenant deployment
                                         {:messages [{:role :user :content prompt}]
                                          :tools search-results-tools
                                          :tool-choice :required
                                          :temperature (or temperature 0.0)})))
                 (catch Throwable t
                   (println (str "query-planner one-call LLM failed: " (.getMessage t)))
                   nil))
        parsed (some (fn [tc]
                       (try (json/read-str (-> tc :function :arguments) :key-fn keyword)
                            (catch Exception _ nil)))
                     (-> result :choices first :message :tool-calls))
        phrases (some->> (:search_phrases parsed)
                         (map str/trim) (remove str/blank?) distinct vec)]
    (when (seq phrases)
      {:user-intent (some-> (:user_intent parsed) str/trim not-empty)
       :phrases phrases})))

(defn- corpus-aware-expand-one-call
  "Design-1 PRF: deterministic raw-query probes → harvest (+ optional hop-2) →
   ONE grounding+intent LLM call. Returns {:queries :user-intent :metadata} or
   nil (no collection / empty harvest / grounding failure → raw-query fallback)."
  [{:keys [tenant deployment temperature phrases-collection two-hop?
           query max-phrases]}]
  (when phrases-collection
    (let [probes (keyword-probes query)
          hop1 (retrieval/harvest-search-phrases phrases-collection probes nil
                                                 {:tenant tenant :limit 12})
          ranked1 (rrf-rank hop1)
          hop1-docs (top-doc-nums ranked1 15)
          hop2 (when (and two-hop? (seq hop1-docs))
                 (retrieval/harvest-search-phrases
                   phrases-collection probes
                   (doc-num-filter hop1-docs)
                   {:tenant tenant :limit 12}))
          candidates (->> (rrf-rank (concat hop1 hop2))
                          (map :search-phrase)
                          (take 25)
                          vec)]
      (when (seq candidates)
        (when-let [{:keys [user-intent phrases]}
                   (call-grounding-with-intent tenant deployment temperature
                                               query candidates)]
          (let [cap (max (or max-phrases 0) 10)
                ui (or user-intent query)
                queries (vec (take cap (->> (cons ui phrases)
                                            (remove nil?) distinct vec)))]
            {:queries queries
             :user-intent ui
             :metadata {:expansion-mode (if two-hop? :corpus-aware-2hop :corpus-aware-1hop)
                        :expansion-variant :one-call
                        :hop1-docs hop1-docs
                        :harvested-phrase-count (count candidates)
                        :grounded-phrase-count (count phrases)}}))))))

(defn execute-query-planner
  "Execute the query planner skill.

   Inputs:
     :query - User query to expand
     :conversation-history - Vector of previous messages (optional)
     :phrases-collection - Phrases collection name (corpus-aware modes only;
                            wired by the retrieval graph / debug endpoint)

   Parameters:
     :model - Model to use (default from config)
     :prompt - Custom prompt template with {messages} placeholder
     :max-phrases - Max phrases to generate (default 7)
     :temperature - Temperature (default 0.1)
     :enabled - Whether to run query expansion (default true)
     :corpus-language - Language hint for user-intent translation
                         (default 'Norwegian (bokmål)')
     :expansion-mode - :blind (default) | :corpus-aware-1hop | :corpus-aware-2hop.
                        Corpus-aware modes harvest real corpus vocabulary from
                        the phrases collection and ground a 2nd LLM call on it.
                        Falls back to :blind when phrases-collection is absent
                        or the harvest/grounding yields nothing.

   Returns:
     :queries - Vector of search phrases. First element is the
                LLM-extracted :user-intent (canonical clean question in
                the corpus language). Remaining are diverse expansions.
                If the planner falls back, this is `[query]`.
     :user-intent - The canonical clean question as a string. Nil when
                     the planner falls back (LLM error or disabled)."
  [{:keys [inputs parameters skill-params] :as _ctx}]
  (let [{:keys [query conversation-history phrases-collection]} inputs
        {:keys [model prompt max-phrases temperature enabled corpus-language
                expansion-mode expansion-variant salient-noun-coverage]} parameters
        salient-noun? (boolean salient-noun-coverage)
        tenant (:tenant skill-params)
        expansion-mode (or expansion-mode :blind)
        corpus-aware? (contains? #{:corpus-aware-1hop :corpus-aware-2hop} expansion-mode)
        ;; :two-call (default) = intent+blind-expansion call THEN grounding call.
        ;; :one-call (Design 1) = deterministic raw-query probes, single
        ;; grounding+intent call. Only meaningful in corpus-aware modes.
        one-call? (and corpus-aware? phrases-collection
                       (= :one-call (or expansion-variant :two-call)))
        ;; Corpus-aware mode wants stable blind probes (they seed the PRF
        ;; harvest), so the intent/expansion call defaults to temp 0 there;
        ;; blind mode keeps its historical 0.1. Explicit :temperature wins.
        intent-temp (or temperature (if corpus-aware? 0.0 0.1))
        enabled? (not= false enabled)]
    (cond
      (not enabled?)
      (skills/success-result
        {:queries [query]
         :user-intent nil}
        {:phrase-count 1
         :disabled true})

      ;; Single-call variant: skip the blind-expansion LLM call entirely.
      one-call?
      (let [deployment (or model
                           ;; Match the agent loop (agent/loop.clj): when azure is OFF
                           ;; (local/OpenAI-compatible endpoints) the deployment-name is a
                           ;; stale azure value that 404s — use model-name instead.
                           (if (llm/use-azure-openai tenant)
                             (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                             (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
            result (corpus-aware-expand-one-call
                     {:tenant tenant
                      :deployment deployment
                      :temperature temperature
                      :phrases-collection phrases-collection
                      :two-hop? (= expansion-mode :corpus-aware-2hop)
                      :query query
                      :max-phrases max-phrases})]
        (if result
          (skills/success-result
            {:queries (:queries result)
             :user-intent (:user-intent result)}
            (merge {:phrase-count (count (:queries result))
                    :had-llm-intent? (some? (:user-intent result))
                    :model-used deployment}
                   (:metadata result)))
          ;; No candidates / grounding failed → raw-query fallback (one-call has
          ;; a single failure point, so the OVERALL fallback rate is lower than
          ;; two-call even though this fallback is rawer).
          (skills/success-result
            {:queries [query]
             :user-intent nil}
            {:phrase-count 1
             :fallback true
             :expansion-variant :one-call
             :corpus-aware-fallback true
             :model-used deployment})))

      :else
      (let [messages (if (seq conversation-history)
                       conversation-history
                       [{:role :user :text query}])
            message-string (format-conversation-history messages)
            default-prompt (build-default-prompt message-string corpus-language salient-noun?)
            full-prompt (-> (or prompt default-prompt)
                            (str/replace "{messages}" message-string))
            deployment (or model
                           ;; Match the agent loop (agent/loop.clj): when azure is OFF
                           ;; (local/OpenAI-compatible endpoints) the deployment-name is a
                           ;; stale azure value that 404s — use model-name instead.
                           (if (llm/use-azure-openai tenant)
                             (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                             (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
            ;; Wrapped in with-llm-retries to handle Azure flakiness
            ;; (HttpTimeoutException, 429, 5xx). Up to 3 attempts with
            ;; exponential backoff. Persistent failure falls through to
            ;; the outer try/catch below and returns the raw-query
            ;; fallback.
            query-result
            (try
              (with-llm-retries
                (fn []
                  (planner-completion tenant deployment
                                      {:messages [{:role :user :content full-prompt}]
                                       :tools search-results-tools
                                       :tool-choice :required
                                       :temperature intent-temp})))
              (catch Throwable t
                (println (str "query-planner LLM call failed after retries: "
                              (.getMessage t) " — falling back to raw query"))
                nil))
            ;; Single tool-call expected; defensive against multiple.
            tool-calls (-> query-result :choices first :message :tool-calls)
            parsed (some (fn [tool-call]
                           (try
                             (let [json-str (-> tool-call :function :arguments)]
                               (json/read-str json-str :key-fn keyword))
                             (catch Exception e
                               (println "Error decoding planner output:" (.getMessage e))
                               nil)))
                         tool-calls)
            user-intent (some-> parsed :user_intent str/trim not-empty)
            raw-phrases (or (:search_phrases parsed) [])
            ;; Build the final :queries vec — user-intent prepended as
            ;; the no-regression baseline phrase. Dedupe to avoid the
            ;; LLM emitting user-intent twice. Cap by :max-phrases
            ;; (inclusive of the user-intent slot).
            combined (->> (cons user-intent raw-phrases)
                          (remove nil?)
                          distinct
                          vec)
            queries (vec (cond->> combined
                           (and max-phrases (pos? max-phrases)) (take max-phrases)))]
        (if (seq queries)
          ;; Blind expansion succeeded. In corpus-aware modes, attempt PRF
          ;; on top; fall back to the blind result if PRF can't run.
          ;; Additive salient-noun (fix a): the salient phrases (first two search
          ;; phrases) still seed the PRF harvest — that grounding is what wins the
          ;; hard cohort (e.g. sys-02 grounds on "altinn pdp"). The regression was
          ;; pure DISPLACEMENT: the original lever evicted the user-intent query
          ;; that pools the clean questions at rank 1. Fix = RESERVE the user-intent
          ;; first (non-droppable), then the salient nouns, then the salient-grounded
          ;; PRF — so clean questions keep their baseline AND hard ones keep the lift.
          (let [salient-phrases (when salient-noun? (vec (take 2 (rest queries))))
                prf (when (and corpus-aware? user-intent)
                      (corpus-aware-expand
                        {:tenant tenant
                         :deployment deployment
                         :temperature temperature
                         :phrases-collection phrases-collection
                         :two-hop? (= expansion-mode :corpus-aware-2hop)
                         :user-intent user-intent
                         :blind-phrases (rest queries)
                         :query query
                         :max-phrases max-phrases}))]
            (if prf
              (let [prf-queries (if salient-noun?
                                  (->> (concat [user-intent]
                                               salient-phrases
                                               (:queries prf))
                                       (remove nil?) distinct vec)
                                  (:queries prf))]
              (skills/success-result
                {:queries prf-queries
                 :user-intent user-intent}
                (merge {:phrase-count (count prf-queries)
                        :had-llm-intent? (some? user-intent)
                        :salient-noun-coverage salient-noun?
                        :model-used deployment}
                       (:metadata prf))))
              (skills/success-result
                {:queries queries
                 :user-intent user-intent}
                (cond-> {:phrase-count (count queries)
                         :had-llm-intent? (some? user-intent)
                         :model-used deployment}
                  ;; Record that corpus-aware was requested but degraded to
                  ;; blind, so measurement transcripts aren't misread.
                  corpus-aware? (assoc :expansion-mode :blind
                                       :corpus-aware-fallback true)))))
          (skills/success-result
            {:queries [query]
             :user-intent nil}
            {:phrase-count 1
             :fallback true
             :model-used deployment}))))))

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
