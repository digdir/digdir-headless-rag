(ns digdir.skills.builtin.synthesis
  "Synthesis skill - wraps LLM generation functions.

   This skill generates responses using retrieved context
   and Azure OpenAI."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [digdir.llm.prompt-fragments :as prompt-fragments]
            [digdir.llm.client :as openai]
            [clojure.string :as str]
            [clojure.set :as set]))

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
      :context_docs
      {:type "array"
       :items {:type "object"}
       :description "Context documents to use during synthesis"}}
     :required ["query" "context_docs"]}}})

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def synthesis-metadata
  {:skill-id :builtin/synthesis
   :name "Response Synthesis"
   :description "Generate responses using retrieved context and LLM"
   :category :generation
   :inputs [:query :context-docs]
   :outputs [:response :insufficient-context :insufficient-context-signal
             :citations :citation-index :citation-validation :prompts]
   :parameters {:model :string
                :temperature :number
                :max-tokens :number
                :system-prompt :string
                :generation-prompt :string
                :max-docs :number}
   :version "1.0.0"
   :tags #{:llm :azure :generation :production}
   :tool-definition synthesis-tool-definition})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn build-context-yaml
  "Build numbered context from docs for citation support."
  [context-docs]
  (->> context-docs
       (map-indexed (fn [idx doc]
                      (str "[" (inc idx) "] " (:page_content doc))))
       (str/join "\n\n")))

(defn build-citation-index
  "Build a map from 1-based citation number to chunk-id."
  [context-docs]
  (->> context-docs
       (map-indexed (fn [idx doc]
                      [(inc idx) (get-in doc [:metadata :source])]))
       (into {})))

(def citation-instruction
  "When making claims based on the provided sources, cite them using [N] notation where N is the source number. Every factual claim should have at least one citation. Place citations immediately after the relevant statement.")

(def ambiguity-instruction
  "If the sources contain multiple plausible values, labels, or interpretations, do not hide the ambiguity. Compare the user’s wording to the source labels, preserve the source terminology, and explicitly mention ambiguity when the question is underspecified. Do not silently collapse distinct source labels into one concept. Do not merge or arithmetic-combine values from different labels, sentences, or chunks unless the source explicitly states that they belong together. If one label is clearly the closest semantic match, use it and state that label in the answer. If different chunks describe different scopes or definitions, explain that difference instead of producing a single blended number.")

(defn build-generation-prompt
  "Build the full generation prompt with context and question."
  [prompt-template context-yaml query]
  (-> (or prompt-template "Answer the question based on the context.\n\nContext:\n{context}\n\nQuestion: {question}")
      (str/replace "{context}" context-yaml)
      (str/replace "{question}" query)
      (str "\n\n" ambiguity-instruction
           "\n\n" citation-instruction
           "\n\n" prompt-fragments/same-language-rule)))

(defn parse-citations
  "Extract citation references from response text.
   Returns a vector of maps: [{:index 1 :chunk-id \"abc\"} ...]"
  [response-text citation-index]
  (let [matcher (re-seq #"\[(\d+)\]" response-text)]
    (->> matcher
         (map (fn [[_ n]]
                (let [idx (Integer/parseInt n)]
                  (when-let [chunk-id (get citation-index idx)]
                    {:index idx :chunk-id chunk-id}))))
         (remove nil?)
         (distinct)
         (sort-by :index)
         vec)))

(defn renumber-citations
  "Renumber citation indices in the response to be sequential starting from 1.
   Returns {:response updated-text :citations updated-citations :citation-index updated-index}."
  [response-text citations citation-index]
  (if (empty? citations)
    {:response response-text :citations citations :citation-index citation-index}
    (let [old-indices (->> citations (map :index) distinct sort)
          old->new (zipmap old-indices (range 1 (inc (count old-indices))))]
      (if (= old->new (zipmap old-indices old-indices))
        ;; Already sequential from 1 — no renumbering needed
        {:response response-text :citations citations :citation-index citation-index}
        (let [new-response (str/replace (or response-text "")
                                        #"\[(\d+)\]"
                                        (fn [[full idx-str]]
                                          (if-let [new-idx (get old->new (Integer/parseInt idx-str))]
                                            (str "[" new-idx "]")
                                            full)))
              new-citations (mapv (fn [c] (assoc c :index (get old->new (:index c)))) citations)
              new-citation-index (into {} (map (fn [[old-idx new-idx]]
                                                 [new-idx (get citation-index old-idx)])
                                               old->new))]
          {:response new-response
           :citations (->> new-citations
                           (sort-by :index)
                           vec)
           :citation-index new-citation-index})))))

(defn validate-citations-programmatically
  "Validate all [N] citation references in the response against citation-index keys.
   Returns {:valid-indices #{} :invalid-indices #{} :all-valid? bool
            :total-references int :grounded? bool}.

   `:all-valid?` and `:grounded?` answer DIFFERENT questions, and conflating
   them is what let ungrounded answers through:

     :all-valid?  integrity  — are the [N] markers the model emitted real?
     :grounded?   presence   — did it cite anything real at all?

   An answer with no citations has no INVALID ones, so it satisfies
   `:all-valid?` trivially while resting entirely on model knowledge. Measured:
   asked an off-corpus question, the agent retrieved 8-11 chunks, found nothing
   relevant, and answered from parametric knowledge with zero citations — and
   no signal here distinguished that from a well-grounded answer."
  [response-text citation-index]
  (let [refs (re-seq #"\[(\d+)\]" (or response-text ""))
        referenced-indices (->> refs
                                (map (fn [[_ n]] (Integer/parseInt n)))
                                set)
        valid-keys (set (keys (or citation-index {})))
        valid-indices (set/intersection referenced-indices valid-keys)
        invalid-indices (set/difference referenced-indices valid-keys)]
    {:valid-indices valid-indices
     :invalid-indices invalid-indices
     :all-valid? (empty? invalid-indices)
     :total-references (count referenced-indices)
     ;; At least one citation that was BOTH emitted and resolves to a real
     ;; chunk. Deliberately keyed off valid-indices rather than
     ;; total-references, so a fabricated marker cannot pass as evidence.
     :grounded? (boolean (seq valid-indices))}))

(def ^:private insufficient-context-signals
  [{:signal :not-enough-info
    :pattern #"(?s)\b(not enough|insufficient|don't have enough|do not have enough)\b.{0,80}\b(info|information|context|sources?|documents?)\b"}
   {:signal :cannot-answer-from-sources
    :pattern #"(?s)\b(cannot|can't|unable to|could not)\b.{0,80}\b(answer|determine|find|locate)\b.{0,120}\b(context|sources?|documents?|provided)\b"}
   {:signal :no-relevant-information
    :pattern #"(?s)\b(no|not)\b.{0,40}\b(relevant|available|matching)\b.{0,80}\b(info|information|context|sources?|documents?)\b"}
   {:signal :ingen-nok-informasjon
    :pattern #"(?s)\b(ikke nok|utilstrekkelig|ingen)\b.{0,80}\b(informasjon|kontekst|kilder)\b"}
   {:signal :kan-ikke-svare-fra-kilder
    :pattern #"(?s)\b(kan ikke|klarer ikke|kunne ikke)\b.{0,100}\b(svare|finne)\b.{0,120}\b(informasjon|kontekst|kilder)\b"}
   {:signal :finner-ikke-i-kildene
    :pattern #"(?s)\b(jeg (har|finner) ikke)\b.{0,120}\b(informasjon|grunnlag|kilder)\b"}])

(defn detect-insufficient-context
  "Detect if a response indicates insufficient source/context information.
   Returns {:insufficient-context? boolean :signal keyword|nil}."
  [response-text]
  (let [normalized (-> (or response-text "")
                       str/lower-case
                       (str/replace #"\s+" " ")
                       str/trim)
        matched (some (fn [{:keys [signal pattern]}]
                        (when (re-find pattern normalized)
                          signal))
                      insufficient-context-signals)]
    {:insufficient-context? (boolean matched)
     :signal matched}))

(defn insufficient-context-response?
  "True if response text indicates insufficient source/context information."
  [response-text]
  (:insufficient-context? (detect-insufficient-context response-text)))

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
     :max-docs - Max number of context docs to include (optional)

   Returns:
     :response - Generated response text
     :prompts - Map with :system and :full prompts used"
  [{:keys [inputs parameters _services skill-params] :as _ctx}]
  (let [{:keys [query context-docs]} inputs
        {:keys [model temperature max-tokens system-prompt generation-prompt max-docs]} parameters
        tenant (:tenant skill-params)
        context-docs (if (and max-docs (pos? max-docs))
                       (take max-docs context-docs)
                       context-docs)

        ;; Resolve model
        selected-model (or model
                          (if (llm/use-azure-openai tenant)
                            (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                            (cfg/get {:tenant tenant} :services :azure-openai :model-name)))

        ;; Build citation index
        citation-index (build-citation-index context-docs)

        ;; Build prompts
        system-prompt-text (or system-prompt (rag/system-prompt-with-date))
        context-yaml (build-context-yaml context-docs)
        full-prompt (build-generation-prompt generation-prompt context-yaml query)

        ;; Call LLM
        request-params (cond-> {:model selected-model
                                :messages [{:role "system" :content system-prompt-text}
                                           {:role "user" :content full-prompt}]
                                :temperature (or temperature 0.1)}
                         max-tokens (assoc :max_tokens max-tokens))
        chat-response
        (if (llm/use-azure-openai tenant)
          (openai/create-chat-completion
            request-params
            {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
             :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
             :impl :azure})
          (openai/create-chat-completion request-params))

        response-text (-> chat-response :choices first :message :content)

        ;; Structural citation diagnostics only. We intentionally preserve the
        ;; model's generated answer and parse whatever valid [N] markers it emitted.
        parsed-citations (parse-citations response-text citation-index)
        renumbered (renumber-citations response-text parsed-citations citation-index)
        final-response (:response renumbered)
        parsed-citations (:citations renumbered)
        citation-index (:citation-index renumbered)
        insufficiency (detect-insufficient-context final-response)
        citation-validation (validate-citations-programmatically final-response citation-index)]

    (skills/success-result
      {:response final-response
       :insufficient-context (:insufficient-context? insufficiency)
       :insufficient-context-signal (:signal insufficiency)
       :citations parsed-citations
       :citation-index citation-index
       :citation-validation citation-validation
       :prompts {:system system-prompt-text
                 :full full-prompt}}
      {:model-used selected-model
       :context-length (count context-yaml)
       :response-length (count final-response)
       :insufficient-context (:insufficient-context? insufficiency)
       :insufficient-context-signal (:signal insufficiency)
       :citation-count (count parsed-citations)})))

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
