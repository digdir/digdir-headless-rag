(ns digdir.demo.altinn-translation-drift
  "S5 — Altinn NB/EN Translation-Drift Detector demo scenario.

   Given a topic (free-form text), retrieve related doc pages from
   https://docs.altinn.studio across both Norwegian Bokmål (/nb/) and
   English (/en/) URL trees, identify which pages exist in both
   languages, and report substantive divergence between the language
   pairs.

   Pipeline (S5-A, MVP shape — no foreach support in the runner yet):

     :builtin/entity-extraction          ; topic -> entities
     -> :docs/entities->queries          ; entities -> retrieval queries (reused from S2-A)
     -> :builtin/multi-retrieval         ; one search per entity, merge results
     -> :docs/translation-page-pairer    ; group chunks by canonical URL stem,
                                         ;   retain only stems with both
                                         ;   /nb/ and /en/ chunks present
     -> :docs/translation-drift-synthesis; structured drift report via tool-forcing JSON

   Per-pair fan-out of summarization + fact-checking (called for in the
   plan) needs :foreach support and stays under the S2-B roadmap. For the
   MVP, the synthesis step consumes all pairs in a single LLM call
   (bundled-skill pattern mirroring S2-A).

   The pairer relies on Altinn's URL convention: every translated page
   lives at /nb/<path>/index.md and /en/<path>/index.md with the same
   <path>. So the canonical pairing key is the URL with the /nb/ or /en/
   prefix stripped."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.demo.altinn-release-notes :as release-notes]
            [digdir.llm.openai :as llm]
            [digdir.rag.retrieval :as rag-retrieval]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            [digdir.llm.client :as openai]
            [taoensso.timbre :as timbre]))

;; =============================================================================
;; Bridge skill: chunks -> NB/EN page pairs
;; =============================================================================

(defn- extract-page-url
  "Walks chunk values for the first nested map exposing :url. Same convention
   as altinn-release-notes — the URL lives under a collection-named key like
   :website_documents_<hash>."
  [chunk]
  (some (fn [v]
          (when (and (map? v) (:url v))
            (:url v)))
        (vals chunk)))

(defn- detect-lang
  [url]
  (cond
    (and url (str/starts-with? url "/nb/")) :nb
    (and url (str/starts-with? url "/en/")) :en
    :else                                   :unknown))

(defn- canonical-path
  "Strips /nb/ or /en/ prefix so paired pages share a key."
  [url]
  (when url
    (str/replace url #"^/(?:nb|en)/" "/")))

(def translation-page-pairer-metadata
  {:skill-id :docs/translation-page-pairer
   :name "Translation Page Pairer (demo)"
   :description "Pure data adapter — groups retrieval chunks by canonical (language-stripped) URL and retains only paths where both NB and EN chunks were retrieved. No LLM, no services."
   :category :augmentation
   :inputs [:chunks]
   :outputs [:pairs :unpaired]
   :parameters {:max-pairs :number}
   :version "1.0.0"
   :tags #{:demo :adapter :translation}})

(defn execute-translation-page-pairer
  [{:keys [inputs parameters]}]
  (let [{:keys [chunks]} inputs
        {:keys [max-pairs] :or {max-pairs 5}} (or parameters {})
        annotated (->> chunks
                       (map (fn [c]
                              (let [url (extract-page-url c)]
                                (assoc c
                                       ::url url
                                       ::path (canonical-path url)
                                       ::lang (detect-lang url)))))
                       (filter ::path))
        grouped (group-by ::path annotated)
        {paired-paths true unpaired-paths false}
        (group-by (fn [[_ chs]]
                    (let [by-lang (group-by ::lang chs)]
                      (boolean (and (seq (get by-lang :nb))
                                    (seq (get by-lang :en))))))
                  grouped)
        pairs (->> paired-paths
                   (map (fn [[path chs]]
                          (let [by-lang (group-by ::lang chs)
                                nb (vec (get by-lang :nb []))
                                en (vec (get by-lang :en []))]
                            {:canonical-path path
                             :nb-url (some ::url nb)
                             :en-url (some ::url en)
                             :nb-chunks (mapv #(dissoc % ::url ::path ::lang) nb)
                             :en-chunks (mapv #(dissoc % ::url ::path ::lang) en)})))
                   (sort-by #(- (+ (count (:nb-chunks %))
                                   (count (:en-chunks %)))))
                   (take max-pairs)
                   vec)
        unpaired (->> unpaired-paths
                      (map (fn [[path chs]]
                             {:canonical-path path
                              :lang (some-> chs first ::lang)
                              :chunk-count (count chs)}))
                      vec)]
    (skills/success-result
     {:pairs pairs
      :unpaired unpaired}
     {:input-chunk-count (count chunks)
      :unique-paths (count grouped)
      :paired-paths (count pairs)
      :unpaired-paths (count unpaired)})))

(def translation-page-pairer-skill
  {:metadata translation-page-pairer-metadata
   :execute execute-translation-page-pairer})

;; =============================================================================
;; Bridge skill: pairs -> pairs with FULL page content per side
;; =============================================================================
;;
;; Why this exists: retrieval returns matched chunks, not whole pages. When NB
;; and EN chunks for the same canonical page were retrieved, they often cover
;; DIFFERENT sections (NB matched the "concept" section, EN matched the
;; "implementation" section). Feeding those into the drift synthesizer reads
;; as "substantive divergence" even when the pages are faithful translations,
;; because the synthesis is comparing across-section, not across-language.
;;
;; This skill fixes that by fetching every chunk of each paired doc straight
;; from Typesense and replacing the retrieval-returned subset with the full
;; chunk set, sorted by chunk_index.

(def translation-pair-content-expander-metadata
  {:skill-id :docs/translation-pair-content-expander
   :name "Translation Pair Content Expander (demo)"
   :description "For each NB/EN pair, fetches every chunk of both docs from Typesense so the synthesizer sees full pages, not retrieval-selected fragments. Eliminates chunk-section mismatch as a source of false drift."
   :category :augmentation
   :inputs [:pairs :docs-collection :chunks-collection]
   :outputs [:pairs]
   :parameters {}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:demo :translation}})

(defn- nested-total-chunks
  "Extracts :total_chunks from the chunk's nested collection-keyed doc map.
   Returns nil if absent."
  [chunk]
  (some (fn [v]
          (when (map? v)
            (:total_chunks v)))
        (vals chunk)))

(defn- fetch-full-chunks-for-side
  "Returns all chunks for a side's doc, ordered by chunk_index. Falls back to
   the retrieval-supplied chunks if doc_num can't be resolved or the fetch
   returns empty."
  [docs-collection chunks-collection side-chunks opts]
  (let [first-chunk (first side-chunks)
        doc-num (:doc_num first-chunk)
        total-chunks (nested-total-chunks first-chunk)]
    (if (and doc-num total-chunks)
      (or (seq (rag-retrieval/retrieve-chunks-by-range
                docs-collection
                chunks-collection
                doc-num
                0
                (dec total-chunks)
                opts))
          side-chunks)
      side-chunks)))

(defn execute-translation-pair-content-expander
  [{:keys [inputs skill-params]}]
  (let [{:keys [pairs docs-collection chunks-collection]} inputs
        opts {:tenant (:tenant skill-params)
              :tenant-config-key (:tenant-config-key skill-params)}
        expanded (mapv (fn [{:keys [nb-chunks en-chunks] :as pair}]
                         (let [full-nb (fetch-full-chunks-for-side
                                        docs-collection chunks-collection nb-chunks opts)
                               full-en (fetch-full-chunks-for-side
                                        docs-collection chunks-collection en-chunks opts)]
                           (assoc pair
                                  :nb-chunks (vec full-nb)
                                  :en-chunks (vec full-en)
                                  :nb-retrieved-chunk-count (count nb-chunks)
                                  :en-retrieved-chunk-count (count en-chunks)
                                  :nb-full-chunk-count (count full-nb)
                                  :en-full-chunk-count (count full-en))))
                       pairs)]
    (skills/success-result
     {:pairs expanded}
     {:pair-count (count pairs)
      :total-nb-retrieved (reduce + (map :nb-retrieved-chunk-count expanded))
      :total-nb-full (reduce + (map :nb-full-chunk-count expanded))
      :total-en-retrieved (reduce + (map :en-retrieved-chunk-count expanded))
      :total-en-full (reduce + (map :en-full-chunk-count expanded))})))

(def translation-pair-content-expander-skill
  {:metadata translation-pair-content-expander-metadata
   :execute execute-translation-pair-content-expander})

;; =============================================================================
;; Structured-synthesis skill (drift detector)
;; =============================================================================

(def ^:private drift-report-tools
  "Forces the LLM to emit a structured per-pair drift report."
  [{:type "function"
    :function
    {:name "emitDriftReport"
     :description "Emit the structured per-page-pair drift report."
     :parameters
     {:type "object"
      :required ["pairs"]
      :properties
      {:pairs
       {:type "array"
        :description "One entry per (canonical_path) input pair. Status and divergences MUST satisfy the structural rule: status=substantive_drift requires non-empty divergences; status in {aligned, minor_drift} requires empty divergences."
        :items
        {:type "object"
         :required ["canonical_path" "status"]
         :properties
         {:canonical_path
          {:type "string"
           :description "URL path WITHOUT /nb or /en prefix, verbatim from the pair header (e.g. \"/community/about/index.md\")."}
          :status
          {:type "string"
           :enum ["aligned" "minor_drift" "substantive_drift"]
           :description "aligned = same factual content across NB and EN. minor_drift = phrasing/style differs but no factual divergence. substantive_drift = at least one substantive factual claim differs between NB and EN."}
          :divergences
          {:type "array"
           :description "Must be NON-EMPTY iff status is substantive_drift. Each entry pins a single factual divergence to specific sentences from each side."
           :items
           {:type "object"
            :required ["nb_claim" "en_claim" "why_substantive"]
            :properties
            {:nb_claim {:type "string"
                        :description "The NB sentence/claim that diverges."}
             :en_claim {:type "string"
                        :description "The EN sentence/claim that diverges."}
             :why_substantive {:type "string"
                               :description "Why this is a factual divergence rather than a phrasing difference. One sentence."}}}}
          :summary {:type "string"
                    :description "One-sentence summary of what this pair is about."}}}}
       :notes
       {:type "array"
        :items {:type "string"}
        :description "Cross-cutting observations across the input set (e.g. terminology shifts, systematic translation patterns). Empty if none."}}}}}])

(defn- truncate
  "Soft-truncate long chunk content for the prompt. Keeps the demo prompt
   bounded; full content is preserved in the trace."
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "…")
    (or s "")))

(defn- chunk-text
  "Pulls chunk text. Raw retrieval / Typesense chunks expose it as
   :content_markdown; reranker-produced docs would expose it as :page_content."
  [c]
  (or (:content_markdown c) (:page_content c) (:content c) ""))

(defn- pair-content-block
  "Joins all chunks on one side of a pair into a single page-level text, then
   soft-truncates the JOINED text to `side-char-limit`. Truncating per-side
   (rather than per-chunk) gives a predictable prompt budget independent of
   how many chunks a doc happens to be split into."
  [chunks side-char-limit]
  (truncate (->> chunks
                 (map chunk-text)
                 (str/join "\n\n"))
            side-char-limit))

(defn- format-pairs-block
  "Numbered pair blocks with NB and EN content surfaced separately. The
   `side-char-limit` parameter caps the per-side text length so the prompt
   stays bounded even when the expander has fetched many chunks per page."
  [pairs {:keys [side-char-limit] :or {side-char-limit 6000}}]
  (->> pairs
       (map-indexed
        (fn [i {:keys [canonical-path nb-url en-url nb-chunks en-chunks]}]
          (str "[" (inc i) "] pair=" canonical-path "\n"
               "    nb-url=" nb-url "\n"
               "    en-url=" en-url "\n\n"
               "  --- NB (full page) ---\n"
               (pair-content-block nb-chunks side-char-limit) "\n\n"
               "  --- EN (full page) ---\n"
               (pair-content-block en-chunks side-char-limit))))
       (str/join "\n\n===\n\n")))

(defn- build-drift-prompt
  [topic pairs-block]
  (str "You are a translation-drift reviewer for the Altinn documentation. "
       "You receive a topic and a numbered list of doc-page pairs, where "
       "each pair has the same canonical path on docs.altinn.studio but "
       "exists in both Norwegian Bokmål (NB) and English (EN). Your job is "
       "to identify SUBSTANTIVE factual divergence between the NB and EN "
       "versions — not phrasing, not word order, not stylistic differences.\n\n"
       "Topic the user asked about:\n" topic "\n\n"
       "Page pairs:\n" pairs-block "\n\n"
       "Emit the drift report by calling the emitDriftReport tool. Rules:\n"
       "- Phrasing differences and synonym choices are NOT drift. Treat them as aligned.\n"
       "- Substantive drift = one side states a fact (parameter name, deprecation, behavior, eligibility, version, endpoint, etc.) that the other side does not state, contradicts, or contradicts implicitly.\n"
       "- status=substantive_drift REQUIRES at least one entry in divergences. status in {aligned, minor_drift} REQUIRES an empty divergences array.\n"
       "- For each divergence, quote the exact sentence (or shortest fragment that contains the claim) from each language. Do not paraphrase the source.\n"
       "- canonical_path MUST be copied verbatim from the `pair=` field of the input header. Do not prepend /nb or /en.\n"
       "- If a pair's two sides describe essentially the same facts and the only differences are language-level, mark it aligned.\n"))

(defn generate-drift-report
  "Call Azure OpenAI with the emitDriftReport tool forced."
  [{:keys [topic pairs tenant temperature side-char-limit]
    :or {temperature 0.2}}]
  (cond
    (str/blank? topic)
    {:error "topic is required"}

    (empty? pairs)
    {:error "no NB/EN page pairs to compare"}

    (str/blank? tenant)
    {:error "tenant is required for LLM credential lookup"}

    :else
    (let [model (if (llm/use-azure-openai tenant)
                  (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                  (cfg/get {:tenant tenant} :services :azure-openai :model-name))
          pairs-block (format-pairs-block pairs
                                          (cond-> {}
                                            side-char-limit (assoc :side-char-limit side-char-limit)))
          prompt (build-drift-prompt topic pairs-block)
          request {:model model
                   :messages [{:role "user" :content prompt}]
                   :tools drift-report-tools
                   :tool_choice {:type "function" :function {:name "emitDriftReport"}}
                   :temperature temperature}
          response (try
                     (if (llm/use-azure-openai tenant)
                       (openai/create-chat-completion
                        request
                        {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
                         :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
                         :impl :azure})
                       (openai/create-chat-completion request))
                     (catch Throwable t
                       (timbre/warn t "translation-drift-synthesis LLM call failed")
                       {:error (.getMessage t)}))]
      (if (:error response)
        response
        (let [json-args (some-> response :choices first :message :tool_calls first :function :arguments)]
          (if json-args
            {:report (json/read-str json-args :key-fn keyword)
             :raw-json json-args
             :model-used model}
            {:error (str "Model returned no emitDriftReport call. Raw content: "
                         (-> response :choices first :message :content))}))))))

(defn- enforce-status-divergence-invariant
  "Programmatic post-processor: align status and divergences so the structural
   rule is always honored. Mirrors altinn-release-notes' XOR enforcer.

   - If status in {aligned, minor_drift} and divergences is non-empty: clear
     divergences (the model committed to 'no factual drift' but kept claims).
   - If status = substantive_drift and divergences is empty: downgrade to
     minor_drift (the model committed to 'drift' but didn't provide evidence).

   Any change is recorded under :enforced-status-fixes so the trace shows what
   was rewritten and why."
  [{:keys [pairs] :as report}]
  (let [{:keys [pairs fixes]}
        (reduce (fn [{:keys [pairs fixes]} pair]
                  (let [{:keys [status divergences canonical_path]} pair
                        divergences (or divergences [])
                        soft-status? (#{"aligned" "minor_drift"} status)
                        hard-status? (= status "substantive_drift")]
                    (cond
                      (and soft-status? (seq divergences))
                      {:pairs (conj pairs (assoc pair :divergences []))
                       :fixes (conj fixes
                                    {:canonical_path canonical_path
                                     :original-status status
                                     :resolved-status status
                                     :reason :stripped-divergences-from-non-drift-status})}

                      (and hard-status? (empty? divergences))
                      {:pairs (conj pairs (assoc pair :status "minor_drift"))
                       :fixes (conj fixes
                                    {:canonical_path canonical_path
                                     :original-status status
                                     :resolved-status "minor_drift"
                                     :reason :downgraded-substantive-drift-without-evidence})}

                      :else
                      {:pairs (conj pairs pair)
                       :fixes fixes})))
                {:pairs [] :fixes []}
                (or pairs []))]
    (cond-> (assoc report :pairs (vec pairs))
      (seq fixes)
      (assoc :enforced-status-fixes (vec fixes)))))

(defn- render-drift-markdown
  [{:keys [pairs notes enforced-status-fixes] :as _report}]
  (let [pair-md (fn [{:keys [canonical_path status summary divergences]}]
                  (str "### " canonical_path
                       " — _" (or status "?") "_\n"
                       (when-not (str/blank? summary)
                         (str "- **Summary:** " summary "\n"))
                       (when (seq divergences)
                         (str "- **Substantive divergences:**\n"
                              (str/join "\n"
                                        (map (fn [{:keys [nb_claim en_claim why_substantive]}]
                                               (str "  - NB: " nb_claim "\n"
                                                    "    EN: " en_claim "\n"
                                                    "    Why: " why_substantive))
                                             divergences))
                              "\n"))))]
    (str "# NB / EN translation-drift report\n\n"
         (when (seq pairs)
           (str/join "\n" (map pair-md pairs)))
         (when (seq notes)
           (str "\n## Cross-cutting notes\n\n"
                (str/join "\n" (map #(str "- " %) notes))
                "\n"))
         (when (seq enforced-status-fixes)
           (str "\n## Structural fixes applied\n\n"
                "The post-processor adjusted these pairs to honour the status×divergences invariant:\n"
                (str/join "\n"
                          (map (fn [{:keys [canonical_path original-status resolved-status reason]}]
                                 (str "- `" canonical_path "`: " original-status " -> " resolved-status
                                      " (" (name reason) ")"))
                               enforced-status-fixes))
                "\n")))))

(def translation-drift-synthesis-metadata
  {:skill-id :docs/translation-drift-synthesis
   :name "Translation-Drift Synthesis (demo)"
   :description "Synthesis-stage replacement that emits a structured NB/EN drift report given a topic plus pre-paired NB/EN chunk groups."
   :category :generation
   :inputs [:query :pairs]
   :outputs [:response :drift-report]
   :parameters {:model :string
                :temperature :number
                :side-char-limit :number}
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:demo :translation :synthesis}})

(defn execute-translation-drift-synthesis
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [query pairs]} inputs
        {:keys [temperature side-char-limit]} (or parameters {})
        tenant (or (:tenant skill-params) "digdir")
        result (generate-drift-report {:topic query
                                       :pairs pairs
                                       :tenant tenant
                                       :temperature (or temperature 0.2)
                                       :side-char-limit side-char-limit})]
    (if (:error result)
      (skills/error-result :docs/translation-drift-synthesis-failed
                           (:error result)
                           {:stage :docs/translation-drift-synthesis
                            :pair-count (count pairs)})
      (let [enforced (enforce-status-divergence-invariant (:report result))]
        (skills/success-result
         {:response (render-drift-markdown enforced)
          :drift-report enforced}
         (cond-> {:model-used (:model-used result)
                  :pair-count (count pairs)
                  :substantive-drift-count (count (filter #(= (:status %) "substantive_drift") (:pairs enforced)))
                  :aligned-count (count (filter #(= (:status %) "aligned") (:pairs enforced)))
                  :minor-drift-count (count (filter #(= (:status %) "minor_drift") (:pairs enforced)))}
           (:enforced-status-fixes enforced)
           (assoc :enforced-status-fixes (:enforced-status-fixes enforced))))))))

(def translation-drift-synthesis-skill
  {:metadata translation-drift-synthesis-metadata
   :execute execute-translation-drift-synthesis})

;; =============================================================================
;; Custom skill graph
;; =============================================================================

(def translation-drift-graph
  "entity-extraction -> entities->queries -> multi-retrieval -> translation-page-pairer -> translation-pair-content-expander -> translation-drift-synthesis."
  {:id :docs/translation-drift
   :name "Demo NB/EN Translation Drift"
   :description "Given a topic, find paired NB/EN doc pages on docs.altinn.studio and report substantive divergence."
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection :conversation-history]
   :outputs [:response :drift-report :chunks]
   :steps [{:id :extract
            :skill :builtin/entity-extraction
            :inputs {:text :$user-query}
            :parameters {:entity-types ["product" "feature" "api" "concept"]
                         :max-entities 10}}
           {:id :to-queries
            :skill :docs/entities->queries
            :inputs {:entities [:extract :entities]}
            :parameters {:dedupe? true}}
           {:id :retrieve
            :skill :builtin/multi-retrieval
            :inputs {:queries [:to-queries :queries]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}
            :parameters {:merge-strategy :union
                         :total-limit 120}}
           {:id :pair
            :skill :docs/translation-page-pairer
            :inputs {:chunks [:retrieve :chunks]}
            :parameters {:max-pairs 5}}
           {:id :expand
            :skill :docs/translation-pair-content-expander
            :inputs {:pairs [:pair :pairs]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection}}
           {:id :synthesize
            :skill :docs/translation-drift-synthesis
            :inputs {:query :$user-query
                     :pairs [:expand :pairs]}
            :parameters {:side-char-limit 6000}}]})

(def translation-drift-skill-graph
  (templates/make-skill-graph
   :docs/translation-drift
   "Demo NB/EN Translation Drift"
   "Entity-driven retrieval over Altinn docs, paired by canonical URL, with a structured drift report."
   translation-drift-graph
   {:version "1.0.0"
    :tags #{:demo :translation}
    :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Agent definition
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; Per-demo agent removed in Phase 0 — :builtin/docs-agent (agents/core.clj)
;; now owns docs/translation-drift along with the other docs/* skill graphs.

(defn register!
  "Register the demo's bridge skill, structured-synthesis skill, and skill graph.
   entities->queries is owned by altinn-release-notes and registered there;
   we just ensure that namespace is loaded so its register! runs first.
   Idempotent."
  []
  (when-not (skills/get-skill :docs/entities->queries)
    (release-notes/register!))
  (skills/register-skill! translation-page-pairer-skill)
  (skills/register-skill! translation-pair-content-expander-skill)
  (skills/register-skill! translation-drift-synthesis-skill)
  (templates/register-skill-graph! translation-drift-skill-graph))
