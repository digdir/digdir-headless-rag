(ns digdir.skills.enrichment.propose-phrases
  "Phase D1 — `:builtin/enrichment-propose-phrases`

   Pure-shape skill: given a chunk's content + minimal document context,
   ask an LLM for N short *verified phrases* that capture the chunk's
   topical hooks. A verified phrase is a 3–10 word fragment that a user
   query is likely to share tokens with — proper nouns, dates, numbers,
   product features, etc. — chosen so that token-overlap or vector
   similarity finds this chunk for queries it should answer.

   Contrast with `:builtin/enrichment-propose-questions`:
   - Questions are full sentences the chunk answers
     (`Når ble Altinn 3 lansert?`)
   - Phrases are search-style fragments the chunk should match
     (`Altinn 3 lanseringsdato`, `Altinn 3 juni 2020 produksjon`)

   Both feed parallel retrieval strategies; phrases bias toward
   exact-overlap, questions bias toward semantic-similarity.

   Lives in `src-dev/` because the self-improvement agent runs offline.
   Production agents never invoke this; the only effect on the runtime
   retrieval skill is the opt-in `:enrichment-search-targets` parameter
   (extended in D1.5 to dispatch on :verified-phrases).

   Default prompt asks for K (default 5) Norwegian phrases, one per
   line, no numbering. We strip common list-marker prefixes anyway so a
   slightly chatty model doesn't break the parser."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.docs.pipeline.core :as core]
            [digdir.rag.skills.core :as skills]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def propose-phrases-metadata
  {:skill-id :builtin/enrichment-propose-phrases
   :name "Propose verified phrases"
   :description "Given a chunk's content + minimal doc context, propose K short topical phrases the chunk should be retrievable by. Returns proposals plus provenance; does not write to Typesense."
   :category :augmentation
   :inputs [:chunk-id :chunk-content :doc-title :doc-url]
   :outputs [:chunk-id :phrases :provenance]
   :parameters {:model :string
                :temperature :number
                :phrase-count :number
                :prompt-template :string}
   :version "1.0.0"
   :tags #{:llm :enrichment :self-improve}})

;; =============================================================================
;; Prompt
;; =============================================================================

(def default-prompt-template
  "Default prompt for verified-phrase generation. Placeholders:
     {{phrase-count}}   — how many phrases to produce
     {{doc-title}}      — document title (may be blank)
     {{doc-url}}        — document URL (may be blank)
     {{chunk-content}}  — the chunk text

   Output contract: one phrase per line, no numbering, no bullets,
   nothing else after the last phrase. The parser strips common list
   markers defensively.

   D2.21 — the earlier prompt asked for SEARCH-friendly phrasings:
   synonyms, paraphrases, topical hooks. In live runs (2026-05-20)
   this consistently produced phrases that were too BROAD: they
   matched the chunk, but they also matched many other chunks on
   the same topic. The verify step then saw enriched-rank < baseline-
   rank — the generic phrases were diluting the chunk's distinctive
   signal — and reverted every chunk.

   The new brief asks for DISCRIMINATIVE phrases: things that single
   THIS chunk out from its neighbors. At least one must be a verbatim
   quote from the chunk. The others should combine distinctive
   entities, code identifiers, headings, numbers, or terminology that
   appears here but not in nearby chunks on the same topic."
  (str
   "Du leser et utdrag fra et offentlig dokument. Foreslå "
   "{{phrase-count}} korte søkefraser som UNIKT identifiserer "
   "akkurat dette utdraget — fraser som matcher dette utdraget "
   "men IKKE andre utdrag i samme korpus om samme tema.\n"
   "\n"
   "Hver frase skal:\n"
   "- være på samme språk som utdraget (vanligvis norsk),\n"
   "- være 3–10 ord lang,\n"
   "- inneholde noe DISTINKTIVT fra akkurat dette utdraget: en "
   "  eksakt formulering, et egennavn, et tall, en kodenøkkel/"
   "  variabel-navn, en seksjonsoverskrift, en feilmelding, eller "
   "  en kombinasjon av termer som forekommer her men ikke i "
   "  nabodokumenter om samme emne,\n"
   "- IKKE være generelle aktualitetsfraser (f.eks. hvis utdraget "
   "  handler om tekstredigering i Altinn, så er 'endre tekster i "
   "  Altinn-app' for generell — den matcher tjue andre utdrag. "
   "  Bedre: 'edit-texts-in-designer.png' eller 'file_uploader_"
   "  validation_error vedlegg' — noe som faktisk særpreger dette "
   "  utdraget),\n"
   "- IKKE være fullstendige spørsmål.\n"
   "\n"
   "Minst ÉN av frasene må være en verbatim 3–7 ord-sitatfrase "
   "fra utdraget — en streng som faktisk forekommer i teksten "
   "ovenfor. De andre kan være kombinasjoner av distinktive "
   "entiteter, kodeord, eller tallverdier som tilsammen identifiserer "
   "dette utdraget.\n"
   "\n"
   "Dokumenttittel: {{doc-title}}\n"
   "Dokument-URL: {{doc-url}}\n"
   "\n"
   "<utdrag>\n"
   "{{chunk-content}}\n"
   "</utdrag>\n"
   "\n"
   "Skriv én frase per linje. Ingen nummerering. Ingen punktmerker. "
   "Ingen tekst før eller etter frasene."))

(defn render-prompt
  "Substitute placeholders in `template` with the values from `args`.
   Plain string replacement — placeholders are well-known and inputs
   are not user-controlled at runtime."
  [template {:keys [phrase-count doc-title doc-url chunk-content]}]
  (-> template
      (str/replace "{{phrase-count}}" (str (or phrase-count 5)))
      (str/replace "{{doc-title}}" (or doc-title ""))
      (str/replace "{{doc-url}}" (or doc-url ""))
      (str/replace "{{chunk-content}}" (or chunk-content ""))))

;; =============================================================================
;; Parsing
;; =============================================================================

(def ^:private list-marker-re
  ;; Strip leading numbering like "1.", "1)", "(1)", a leading dash/bullet,
  ;; and any whitespace that follows.
  #"^\s*(?:[-•*]\s+|\(?\d+[\.\)]\s+)")

(defn parse-phrases-response
  "Pull phrases out of a raw LLM response. Splits the full content on
   newlines (the prompt asks for one phrase per line), strips common
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

(defn- resolve-model
  "Pick the model. Caller's `:model` parameter wins; otherwise fall
   back to the tenant's configured Azure deployment-name."
  [tenant explicit-model]
  (or explicit-model
      (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)))

(defn- chat-completion
  "Single chat call. Private so the body stays focused on plumbing."
  [tenant model prompt temperature]
  (openai/create-chat-completion
   {:model model
    :messages [{:role "system"
                :content "You generate short, search-friendly topical phrases from passages of text. Reply only with the phrases, one per line, no numbering."}
               {:role "user" :content prompt}]
    :temperature (or temperature 0.4)}
   {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
    :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
    :impl :azure}))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-propose-phrases
  "Build the prompt, call the LLM, parse the phrases, stamp provenance.
   Returns `:phrases` capped at `:phrase-count`. The prompt-hash uses
   the *rendered* template so swapping the static template or its
   inputs both invalidate cached enrichments naturally."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id chunk-content doc-title doc-url]} inputs
        {:keys [model temperature phrase-count prompt-template]} parameters
        tenant (:tenant skill-params)
        n (or phrase-count 5)
        template (or prompt-template default-prompt-template)
        prompt (render-prompt template
                              {:phrase-count n
                               :doc-title doc-title
                               :doc-url doc-url
                               :chunk-content chunk-content})
        selected-model (resolve-model tenant model)
        response (chat-completion tenant selected-model prompt temperature)
        raw (parse-phrases-response response)
        phrases (vec (take n raw))
        provenance {:model selected-model
                    :prompt-hash (core/sha256-short-hash prompt)
                    :generated-at-ms (System/currentTimeMillis)
                    :phrase-count (count phrases)}]
    (skills/success-result
     {:chunk-id chunk-id
      :phrases phrases
      :provenance provenance}
     {:model-used selected-model
      :raw-line-count (count raw)
      :emitted-count (count phrases)})))

;; =============================================================================
;; Registration
;; =============================================================================

(def propose-phrases-skill
  {:metadata propose-phrases-metadata
   :execute execute-propose-phrases})

(defn register!
  "Register the propose-phrases skill. Idempotent."
  []
  (skills/register-skill! propose-phrases-skill))

(register!)
