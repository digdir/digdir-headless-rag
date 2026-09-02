(ns digdir.skills.enrichment.propose-questions
  "Phase B.2 — :builtin/enrichment-propose-questions

   Pure-shape skill: given a chunk's content + minimal document context,
   ask an LLM for N hypothetical questions the chunk answers. Returns
   the proposals plus provenance (model, prompt-hash, timestamp). Does
   NOT write to Typesense — `:builtin/enrichment-apply-questions`
   (Phase B.3) does that.

   Lives in `src-dev/` because the self-improvement agent runs offline.
   Production agents never invoke this; the only effect on the runtime
   retrieval skill is the opt-in `:enrichment-search-targets` parameter
   added in Phase B.4.

   Default prompt asks for K (default 4) Norwegian questions, one per
   line, no numbering. We strip common list-marker prefixes anyway so a
   slightly chatty model doesn't break the parser."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.docs.pipeline.core :as core]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def propose-questions-metadata
  {:skill-id :builtin/enrichment-propose-questions
   :name "Propose hypothetical questions"
   :description "Given a chunk's content + minimal doc context, propose K hypothetical questions the chunk answers. Returns proposals plus provenance; does not write to Typesense."
   :category :augmentation
   :inputs [:chunk-id :chunk-content :doc-title :doc-url]
   :outputs [:chunk-id :questions :provenance]
   :parameters {:model :string
                :temperature :number
                :question-count :number
                :prompt-template :string}
   :version "1.0.0"
   :tags #{:llm :enrichment :self-improve}})

;; =============================================================================
;; Prompt
;; =============================================================================

(def default-prompt-template
  "Default prompt for hypothetical-questions generation. Placeholders:
     {{question-count}} — how many questions to produce
     {{doc-title}}      — document title (may be blank)
     {{doc-url}}        — document URL (may be blank)
     {{chunk-content}}  — the chunk text

   Output contract: one question per line, no numbering, no bullets,
   nothing else after the last question. We still defensively strip
   list markers in the parser."
  (str
   "Du leser et utdrag fra et offentlig dokument og skal foreslå "
   "{{question-count}} hypotetiske spørsmål som dette utdraget besvarer "
   "direkte. Spørsmålene skal:\n"
   "- være på samme språk som utdraget (vanligvis norsk),\n"
   "- være konkrete og søkbare (egne navn, datoer, tall der det er relevant),\n"
   "- ikke gjenta hverandre,\n"
   "- kunne besvares fullt ut av innholdet i utdraget alene.\n"
   "\n"
   "Dokumenttittel: {{doc-title}}\n"
   "Dokument-URL: {{doc-url}}\n"
   "\n"
   "<utdrag>\n"
   "{{chunk-content}}\n"
   "</utdrag>\n"
   "\n"
   "Skriv ett spørsmål per linje. Ingen nummerering. Ingen punktmerker. "
   "Ingen tekst før eller etter spørsmålene."))

(defn render-prompt
  "Substitute placeholders in `template` with the values from `args`.
   We do plain string replacement (no template engine) because the
   placeholders are well-known and the inputs are not user-controlled
   at runtime — the corpus owner runs the self-improvement agent."
  [template {:keys [question-count doc-title doc-url chunk-content]}]
  (-> template
      (str/replace "{{question-count}}" (str (or question-count 4)))
      (str/replace "{{doc-title}}" (or doc-title ""))
      (str/replace "{{doc-url}}" (or doc-url ""))
      (str/replace "{{chunk-content}}" (or chunk-content ""))))

;; =============================================================================
;; Parsing
;; =============================================================================

(def ^:private list-marker-re
  ;; Strip leading numbering like "1.", "1)", "(1)", a leading dash/bullet,
  ;; and any whitespace that follows. Keep questions that happen to start
  ;; with a question word.
  #"^\s*(?:[-•*]\s+|\(?\d+[\.\)]\s+)")

(defn parse-questions-response
  "Pull questions out of a raw LLM response. Splits the full content on
   newlines (the prompt asks for one question per line), strips common
   list markers, trims, and filters blanks. Returns a deduped vector."
  [response]
  (let [content (-> response :choices first :message :content (or ""))
        lines (str/split-lines content)]
    (->> lines
         (map #(str/replace % list-marker-re ""))
         (map str/trim)
         (remove str/blank?)
         distinct
         vec)))

;; =============================================================================
;; LLM call
;; =============================================================================

;; Provider resolution follows the search-phrases convention
;; (`digdir.docs.pipeline.search-phrases`): a `:services :<usage> :provider`
;; keyword selects an OpenAI-compatible provider whose endpoint/key live under
;; `:services :<provider> :*`. Here the usage is `self-improvement`, so the
;; enrichment-generation LLM can be redirected to a local model (e.g. LM Studio)
;; for cost, INDEPENDENT of the agent/judge models. Mirrored locally rather than
;; shared because this skill is dev-only and we don't want to touch the
;; production ingest path. Only the :lmstudio + Azure-default cases are needed.

(defn- self-improvement-provider
  "Active provider for the self-improvement (enrichment-generation) usage. Read
   from `:services :self-improvement :provider` (a keyword); defaults to
   `:azure-openai` when unset, so existing behaviour is unchanged."
  [tenant]
  (or (try (cfg/get {:tenant tenant :default nil} :services :self-improvement :provider)
           (catch Exception _ nil))
      :azure-openai))

(defn- provider-impl
  "wkok client opts for an OpenAI-compatible provider keyword. Mirrors
   `search-phrases/openai-implementation`: local endpoints omit `:impl :azure`
   so the wkok client uses the standard OpenAI URL shape, and get a generous
   timeout (local models serve in-queue under parallel load)."
  [tenant provider]
  (case provider
    :lmstudio {:api-key (cfg/get {:tenant tenant} :services :lmstudio :api-key)
               :api-endpoint (cfg/get {:tenant tenant} :services :lmstudio :api-endpoint)
               :request {:timeout 300000}}
    {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
     :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
     :impl :azure}))

(defn- resolve-model
  "Pick the model. Caller's `:model` parameter wins; then the usage-level
   override `services.self-improvement.model` (lets self-improvement pin a model
   distinct from the shared provider default); then the active provider's
   conventional model key; finally the Azure deployment-name."
  [tenant provider explicit-model]
  (or explicit-model
      (try (cfg/get {:tenant tenant :default nil} :services :self-improvement :model)
           (catch Exception _ nil))
      (case provider
        :lmstudio (cfg/get {:tenant tenant} :services :lmstudio :model)
        (cfg/get {:tenant tenant} :services :azure-openai :deployment-name))))

(defn- reasoning-effort
  "Optional `services.self-improvement.reasoning-effort` (e.g. \"none\"/\"low\").
   Reasoning-class local models otherwise burn most of the token budget on a
   thinking channel before emitting the questions; passing it trims that."
  [tenant]
  (try (cfg/get {:tenant tenant :default nil} :services :self-improvement :reasoning-effort)
       (catch Exception _ nil)))

(defn- local-chat-completion
  "Direct POST to an OpenAI-compatible endpoint, bypassing wkok. wkok routes
   through martian + a bundled OpenAPI spec that PREDATES `reasoning_effort`, so
   it silently strips that key from the body — which leaves reasoning-class local
   models (e.g. gemma-4) spending the whole token budget on a thinking channel
   before emitting the questions (verified: ~600 reasoning tokens, 40-100s/chunk).
   A direct call passes the body through verbatim, so `reasoning_effort \"none\"`
   actually reaches the server. Returns the parsed JSON in wkok's response shape
   (`{:choices [{:message {:content ...}}]}`) so `parse-questions-response`
   is unchanged."
  [{:keys [api-endpoint api-key]} body]
  (:body (http/post (str api-endpoint "/chat/completions")
                    {:headers {"Authorization" (str "Bearer " api-key)}
                     :content-type :json
                     :body (json/encode body)
                     :as :json
                     :socket-timeout 300000
                     :connection-timeout 300000})))

(defn- chat-completion
  "Single-call wrapper. Routes enrichment generation to whichever provider the
   self-improvement usage selects (default: shared Azure deployment). The
   :lmstudio (local OpenAI-compatible) path bypasses wkok so `reasoning_effort`
   survives; the Azure path goes through `digdir.llm.client`, which delegates to
   wkok for `:impl :azure` but first applies the GPT-5-family parameter mapping
   (a reasoning deployment such as `gpt-5.6-sol` 400s on the `:temperature`
   built below) and adds 429 retry. Kept private so the skill body stays
   focused on plumbing inputs/outputs."
  [tenant provider model prompt temperature]
  (let [re (reasoning-effort tenant)
        ;; `OPENAI_DISABLE_THINKING=true` on the local (lmstudio) path appends a
        ;; closed-`<think>` assistant turn — forces NON-thinking mode on Qwen3.6
        ;; GGUF (the `enable_thinking` flag is a no-op in LM Studio; this prefill
        ;; isn't). Generation rarely benefits from reasoning, so this is ~6x faster.
        disable-thinking? (and (= provider :lmstudio)
                               (contains? #{"true" "1" "yes" "on"}
                                          (some-> (System/getenv "OPENAI_DISABLE_THINKING")
                                                  str/trim str/lower-case)))
        messages (cond-> [{:role "system"
                           :content "You generate hypothetical search questions from passages of text. Reply only with the questions, one per line."}
                          {:role "user" :content prompt}]
                   disable-thinking? (conj {:role "assistant" :content "<think></think>"}))
        body (cond-> {:model model
                      :messages messages
                      :temperature (or temperature 0.4)}
               re (assoc :reasoning_effort re))]
    (if (= provider :lmstudio)
      (local-chat-completion (provider-impl tenant provider) body)
      (openai/create-chat-completion body (provider-impl tenant provider)))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-propose-questions
  "Build the prompt, call the LLM, parse the questions, and stamp
   provenance. Returns `:questions` capped at `:question-count`. The
   prompt-hash uses the *rendered* template so swapping the static
   template or its inputs both invalidate cached enrichments naturally."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id chunk-content doc-title doc-url]} inputs
        {:keys [model temperature question-count prompt-template]} parameters
        tenant (:tenant skill-params)
        n (or question-count 4)
        template (or prompt-template default-prompt-template)
        prompt (render-prompt template
                              {:question-count n
                               :doc-title doc-title
                               :doc-url doc-url
                               :chunk-content chunk-content})
        provider (self-improvement-provider tenant)
        selected-model (resolve-model tenant provider model)
        response (chat-completion tenant provider selected-model prompt temperature)
        raw (parse-questions-response response)
        questions (vec (take n raw))
        provenance {:model selected-model
                    :provider provider
                    :prompt-hash (core/sha256-short-hash prompt)
                    :generated-at-ms (System/currentTimeMillis)
                    :question-count (count questions)}]
    (skills/success-result
     {:chunk-id chunk-id
      :questions questions
      :provenance provenance}
     {:model-used selected-model
      :raw-line-count (count raw)
      :emitted-count (count questions)})))

;; =============================================================================
;; Registration
;; =============================================================================

(def propose-questions-skill
  {:metadata propose-questions-metadata
   :execute execute-propose-questions})

(defn register!
  "Register the propose-questions skill. Idempotent."
  []
  (skills/register-skill! propose-questions-skill))

(register!)
