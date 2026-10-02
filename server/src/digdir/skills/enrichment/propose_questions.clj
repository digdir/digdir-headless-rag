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
            [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.docs.pipeline.core :as core]
            [digdir.llm.client :as openai]
            [digdir.llm.provider :as provider]))

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

;; Provider resolution: the one decision every LLM call follows,
;; through `provider/resolve` - the tenant's provider, and its own credentials
;; (a missing one refuses, naming its path). `services.self-improvement.provider`
;; is retired and no longer read, and so is the direct POST to
;; `services.lmstudio.*`.
;;
;; That direct POST existed because wkok strips `reasoning_effort`. But the
;; OpenAI-compatible branch of `digdir.llm.client` is itself a direct POST that
;; passes the body through verbatim, so the reason is gone. The client also
;; applies OPENAI_DISABLE_THINKING's closed-<think> prefill, which this namespace
;; used to add itself: adding it here as well would send it twice.
;;
;; ⚠️ What that changes on the OpenAI-compatible path, stated so nobody finds it
;; from moved results: the client's process-wide inference overrides
;; (OPENAI_TEMPERATURE and its family, `digdir.llm.client/env-inference-overrides`)
;; and the GPT-5 parameter mapping now apply to enrichment calls, which the direct
;; POST never saw - on a sweep machine with those exported, enrichment OUTPUT
;; changes. So does the client's 429 retry. It is the collapse working: one path,
;; one set of knobs.
;;
;; Kept, because neither selects a provider: the usage-level model override
;; `services.self-improvement.model`, and `services.self-improvement.reasoning-effort`.

(defn- usage-model
  "The usage-level model override, `services.self-improvement.model` (lets
   self-improvement pin a model distinct from the provider's default), or nil."
  [tenant]
  (try (cfg/get {:tenant tenant :default nil} :services :self-improvement :model)
       (catch Exception _ nil)))

(defn- call-spec
  "`provider/resolve`'s call spec for `tenant`. The model: the caller's `:model`
   parameter wins; then the usage-level override; then the provider's default
   (the Azure deployment name, or `services.azure-openai.model-name`)."
  [tenant explicit-model]
  (provider/resolve tenant {:model (or explicit-model (usage-model tenant))}))

(defn- reasoning-effort
  "Optional `services.self-improvement.reasoning-effort` (e.g. \"none\"/\"low\").
   Reasoning-class local models otherwise burn most of the token budget on a
   thinking channel before emitting the questions; passing it trims that."
  [tenant]
  (try (cfg/get {:tenant tenant :default nil} :services :self-improvement :reasoning-effort)
       (catch Exception _ nil)))

(defn- chat-completion
  "Single-call wrapper on `spec`, through `digdir.llm.client` on both branches.
   On Azure it delegates to wkok but first applies the GPT-5-family parameter
   mapping (a reasoning deployment such as `gpt-5.6-sol` 400s on the
   `:temperature` built below) and adds 429 retry. On the OpenAI-compatible
   branch it is a direct POST: `reasoning_effort` reaches the server, and
   `OPENAI_DISABLE_THINKING=true` gets its closed-<think> prefill (Qwen3.6 GGUF in
   LM Studio: ~6x faster; generation rarely benefits from reasoning). Kept private
   so the skill body stays focused on plumbing inputs/outputs."
  [spec tenant prompt temperature]
  (let [re (reasoning-effort tenant)
        body (cond-> {:model (:model spec)
                      :messages [{:role "system"
                                  :content "You generate hypothetical search questions from passages of text. Reply only with the questions, one per line."}
                                 {:role "user" :content prompt}]
                      :temperature (or temperature 0.4)}
               re (assoc :reasoning_effort re))]
    (openai/create-chat-completion body spec)))

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
        spec (call-spec tenant model)
        selected-model (:model spec)
        response (chat-completion spec tenant prompt temperature)
        raw (parse-questions-response response)
        questions (vec (take n raw))
        ;; `:provider` is `provider/resolve`'s vocabulary since Phase 3 of the provider-resolver change
        ;; (`:azure` / `:openai-compatible`, was `:azure-openai` / `:lmstudio`).
        ;; Informational: apply-questions persists only :model, :prompt-hash and
        ;; :generated-at-ms.
        provenance {:model selected-model
                    :provider (:provider spec)
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
