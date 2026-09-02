(ns digdir.skills.enrichment.propose-facts
  "Phase D2 — `:builtin/enrichment-propose-facts`

   Pure-shape skill: given a chunk's content + minimal document context,
   ask an LLM for N *fact assertions* — (subject, predicate, object)
   triples that the chunk states. Each triple is a single propositional
   claim a downstream lookup can match against entity-style queries.

   Contrast with the other Phase D propose skills:
   - **Hypothetical questions** are full sentences the chunk answers
     (`Når ble Altinn 3 lansert?`).
   - **Verified phrases** are short topical fragments a user might type
     (`Altinn 3 lanseringsdato`).
   - **Fact assertions** are structured triples extracted from the chunk
     (`{:subject \"Altinn 3\" :predicate \"ble lansert\" :object \"juni 2020\"}`).

   All three feed parallel retrieval strategies; facts bias toward
   exact-overlap of entity tokens in the surface form of the triple.

   Lives in `src-dev/` because the self-improvement agent runs offline.
   Production agents never invoke this; the only effect on the runtime
   retrieval skill is the opt-in `:enrichment-search-targets` parameter
   (extended in D2.5 to dispatch on :fact-assertions).

   Default prompt asks for K (default 5) triples per chunk, one per
   line, in a `subject | predicate | object` pipe-delimited format that
   the parser tolerates with surrounding whitespace and list markers."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.docs.pipeline.core :as core]
            [digdir.rag.skills.core :as skills]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def propose-facts-metadata
  {:skill-id :builtin/enrichment-propose-facts
   :name "Propose fact assertions"
   :description "Given a chunk's content + minimal doc context, propose K (subject, predicate, object) triples the chunk asserts. Returns proposals plus provenance; does not write to Typesense."
   :category :augmentation
   :inputs [:chunk-id :chunk-content :doc-title :doc-url]
   :outputs [:chunk-id :facts :provenance]
   :parameters {:model :string
                :temperature :number
                :fact-count :number
                :prompt-template :string}
   :version "1.0.0"
   :tags #{:llm :enrichment :self-improve}})

;; =============================================================================
;; Prompt
;; =============================================================================

(def default-prompt-template
  "Default prompt for fact-assertion generation. Placeholders:
     {{fact-count}}     — how many triples to produce
     {{doc-title}}      — document title (may be blank)
     {{doc-url}}        — document URL (may be blank)
     {{chunk-content}}  — the chunk text

   Output contract: one triple per line as
     subject | predicate | object
   No numbering, no bullets, nothing else after the last triple. The
   parser strips common list markers and trims each cell defensively."
  (str
   "Du leser et utdrag fra et offentlig dokument og skal trekke ut "
   "{{fact-count}} faktapåstander som tydelig formuleres i utdraget. "
   "Hver påstand skal være en (subjekt, predikat, objekt)-trippel:\n"
   "- Subjekt: hva påstanden handler om (egennavn, produkt, person, "
   "  organisasjon, lov, dato e.l.).\n"
   "- Predikat: relasjonen, fortrinnsvis en kort verbal frase "
   "  (`ble lansert`, `gjelder for`, `trådte i kraft`).\n"
   "- Objekt: verdien, målet eller mottakeren av relasjonen.\n"
   "\n"
   "Regler:\n"
   "- Bruk samme språk som utdraget (vanligvis norsk).\n"
   "- IKKE gjett — bare faktapåstander som faktisk står i utdraget.\n"
   "- Ikke gjenta samme påstand med små variasjoner.\n"
   "- Skriv én trippel per linje med pipe-skilletegn: "
   "`subjekt | predikat | objekt`.\n"
   "- Ingen nummerering, ingen punktmerker, ingen forklaring før eller "
   "etter triplene.\n"
   "\n"
   "Dokumenttittel: {{doc-title}}\n"
   "Dokument-URL: {{doc-url}}\n"
   "\n"
   "<utdrag>\n"
   "{{chunk-content}}\n"
   "</utdrag>\n"))

(defn render-prompt
  "Substitute placeholders in `template` with the values from `args`.
   Plain string replacement — placeholders are well-known and inputs
   are not user-controlled at runtime."
  [template {:keys [fact-count doc-title doc-url chunk-content]}]
  (-> template
      (str/replace "{{fact-count}}" (str (or fact-count 5)))
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

(defn triple-text
  "Synthesized surface form `\"<subject> <predicate> <object>\"`. Used
   both as the embedded field in Typesense and as a dedupe key during
   parsing so two triples that disagree only on whitespace collapse."
  [{:keys [subject predicate object]}]
  (->> [subject predicate object]
       (map #(some-> % str/trim))
       (remove str/blank?)
       (str/join " ")))

(defn- parse-line
  "Pull one `{:subject :predicate :object}` triple out of a single line.
   Returns nil if the line is blank or has fewer than three pipe-delimited
   cells after marker-stripping."
  [line]
  (let [cleaned (-> line (str/replace list-marker-re "") str/trim)]
    (when-not (str/blank? cleaned)
      (let [parts (->> (str/split cleaned #"\|")
                       (map str/trim))]
        (when (and (>= (count parts) 3)
                   (every? (complement str/blank?) (take 3 parts)))
          {:subject (nth parts 0)
           :predicate (nth parts 1)
           :object (nth parts 2)})))))

(defn parse-facts-response
  "Pull triples out of a raw LLM response. Splits on newlines, strips
   common list markers, parses each line as `subject | predicate | object`,
   filters incomplete or blank rows, and dedupes on the synthesized
   `triple-text`. Returns a vector of triple maps."
  [response]
  (let [content (-> response :choices first :message :content (or ""))
        lines (str/split-lines content)]
    (loop [remaining lines
           seen #{}
           acc (transient [])]
      (if (empty? remaining)
        (persistent! acc)
        (let [triple (parse-line (first remaining))
              key (some-> triple triple-text str/lower-case)]
          (recur (rest remaining)
                 (cond-> seen key (conj key))
                 (if (and triple key (not (contains? seen key)))
                   (conj! acc triple)
                   acc)))))))

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
                :content "You extract (subject, predicate, object) triples from passages of text. Reply only with the triples, one per line, in `subject | predicate | object` format."}
               {:role "user" :content prompt}]
    :temperature (or temperature 0.2)}
   {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
    :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
    :impl :azure}))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-propose-facts
  "Build the prompt, call the LLM, parse the triples, stamp provenance.
   Returns `:facts` capped at `:fact-count`. The prompt-hash uses the
   *rendered* template so swapping the static template or its inputs
   both invalidate cached enrichments naturally."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id chunk-content doc-title doc-url]} inputs
        {:keys [model temperature fact-count prompt-template]} parameters
        tenant (:tenant skill-params)
        n (or fact-count 5)
        template (or prompt-template default-prompt-template)
        prompt (render-prompt template
                              {:fact-count n
                               :doc-title doc-title
                               :doc-url doc-url
                               :chunk-content chunk-content})
        selected-model (resolve-model tenant model)
        response (chat-completion tenant selected-model prompt temperature)
        raw (parse-facts-response response)
        facts (vec (take n raw))
        provenance {:model selected-model
                    :prompt-hash (core/sha256-short-hash prompt)
                    :generated-at-ms (System/currentTimeMillis)
                    :fact-count (count facts)}]
    (skills/success-result
     {:chunk-id chunk-id
      :facts facts
      :provenance provenance}
     {:model-used selected-model
      :raw-line-count (count raw)
      :emitted-count (count facts)})))

;; =============================================================================
;; Registration
;; =============================================================================

(def propose-facts-skill
  {:metadata propose-facts-metadata
   :execute execute-propose-facts})

(defn register!
  "Register the propose-facts skill. Idempotent."
  []
  (skills/register-skill! propose-facts-skill))

(register!)
