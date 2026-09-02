(ns digdir.skills.enrichment.verify-prune-benefit
  "`:builtin/enrichment-verify-prune-benefit` — the LOCAL \"worth removing?\" gate.

   verify-prune answers SAFETY (won't strand the chunk). This answers BENEFIT (is
   removing the phrase actually worth it?) — the prune-side counterpart of the
   add-side's verify-retrieval. The asymmetry that makes it harder: an ADD's benefit
   is INWARD (the enriched chunk is its own target, measurable locally), but a
   PRUNE's benefit is OUTWARD (removing a non-discriminative phrase un-buries OTHER
   chunks on OTHER queries). Judging that needs ground truth for those queries — the
   batch eval-suite gets it from the benchmark; here we derive it LOCALLY two ways,
   so self-improvement can run at 'batch of one' with no benchmark:

   1. **LLM-judge (gold).** Show the model the chunk content + the phrase and ask the
      D2.21 discriminative question: is `p` a DISTINCTIVE identifier that should
      retrieve THIS chunk (KEEP), or a GENERIC term many chunks share that makes this
      chunk surface for unrelated queries (REMOVE)? High quality, world-knowledge,
      but an LLM call per candidate.

   2. **Semantic-similarity proxy (cheap).** Pure vector-search the phrases collection
      with `p`: if `p`'s nearest neighbours are near-duplicate phrases on MANY OTHER
      chunks, `p` is semantically non-discriminative (REMOVE-leaning); if its nearest
      other-chunk neighbour is far, `p` distinctively marks this chunk (KEEP-leaning).
      No LLM. Distinct from propose-prune's TOKEN-IDF — catches token-rare-but-
      semantically-generic and vice versa.

   Emitting both + `:agree?` lets us measure whether the cheap proxy tracks the gold
   judge well enough to carry scale. Lives in `src-dev/` with its siblings."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as tsu]
            [typesense.client :as ts]
            [digdir.llm.client :as openai]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def verify-prune-benefit-metadata
  {:skill-id :builtin/enrichment-verify-prune-benefit
   :name "Verify phrase prune benefit (local)"
   :description "Local 'worth removing?' signal for a candidate phrase: an LLM-judge KEEP/REMOVE on the discriminative criterion (gold) plus a cheap semantic-similarity discriminativeness proxy, with an agreement flag. Enables batch-of-one self-improvement without a benchmark."
   :category :validation
   :inputs [:chunk-id :candidate-phrase :phrases-collection-name :chunks-collection]
   :outputs [:candidate-phrase :chunk-id
             :llm-verdict :llm-remove? :llm-rationale
             :semantic-nearest-other-distance :semantic-other-frac :semantic-remove?
             :content-rank :content-rank-remove?
             :agree? :agree-rank? :summary]
   :parameters {:model :string :semantic-k :number :semantic-distance-threshold :number
                :content-rank-threshold :number :use-llm? :boolean}
   :required-services #{:typesense :azure-openai}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :prune :validation :llm}})

;; =============================================================================
;; Chunk content
;; =============================================================================

(defn- fetch-chunk-content
  "content_markdown for `chunk-id` from `chunks-coll` (nil if absent)."
  [settings chunks-coll chunk-id]
  (-> (ts/multi-search settings
                       {:searches [{:collection chunks-coll :q "*"
                                    :filter_by (str "chunk_id:=" chunk-id)
                                    :include_fields "chunk_id,content_markdown" :per_page 1}]}
                       {:query_by "content_markdown"})
      :results first :hits first :document :content_markdown))

;; =============================================================================
;; (1) LLM judge
;; =============================================================================

(defn- snip [s n] (let [s (str s)] (-> (subs s 0 (min n (count s))) (str/replace #"\s+" " ") str/trim)))

(defn- competitors
  "Top-k chunks whose CONTENT matches `phrase` — the real competition c faces for
   a search like `phrase`. Returns [{:chunk_id :is-c? :snippet} ...] in rank order
   (c included if it ranks; flagged :is-c?)."
  [settings chunks-coll c-id phrase k]
  (->> (ts/multi-search settings
                        {:searches [{:collection chunks-coll :q phrase :query_by "content_markdown"
                                     :include_fields "chunk_id,content_markdown" :limit k}]}
                        {})
       :results first :hits (map :document)
       (mapv (fn [d] {:chunk_id (:chunk_id d) :is-c? (= c-id (:chunk_id d))
                      :snippet (snip (:content_markdown d) 220)}))))

(def ^:private judge-system
  "You judge whether a search phrase is a useful retrieval bridge or just crowding. Removing a phrase is BENEFICIAL only when the chunk merely contains it while being no more relevant than many alternatives; removing it is HARMFUL when the chunk is a genuinely good answer for that search — even if the phrase is a shared/standard term. Reply with one line: KEEP or REMOVE, then ' - ' and a brief reason.")

(defn- judge-prompt [phrase target-snippet comps]
  (let [others (remove :is-c? comps)]
    (str "A user searched for: \"" phrase "\"\n\n"
         "TARGET chunk (the one carrying this phrase):\n  " target-snippet "\n\n"
         (if (seq others)
           (str "OTHER chunks that also match this search:\n"
                (str/join "\n" (map-indexed (fn [i o] (str "  " (inc i) ". " (:snippet o))) (take 6 others)))
                "\n\n")
           "(no clearly competing chunks surfaced for this search)\n\n")
         "Question: for this search, is the TARGET a genuinely strong, on-topic answer — clearly as good as or better than the alternatives, so the phrase is a real bridge worth keeping? "
         "Or is the TARGET no more relevant than the alternatives and surfacing mainly because it contains the phrase, so the phrase just adds crowding?\n"
         "Answer KEEP (real bridge) or REMOVE (crowding), then ' - ' and a brief reason.")))

(defn- content-rank
  "Position of c among the content-search results for its own phrase (0 = c is the
   single best content match → a real bridge; higher = other chunks answer this
   search better → c is crowding). nil = c absent from the top-k."
  [comps]
  (->> comps (keep-indexed (fn [i x] (when (:is-c? x) i))) first))

(defn- llm-judge [tenant model phrase content comps]
  (let [target (snip content 300)
        resp (openai/create-chat-completion
              {:model model
               :messages [{:role "system" :content judge-system}
                          {:role "user" :content (judge-prompt phrase target comps)}]}
              {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
               :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
               :impl :azure})
        line (-> resp :choices first :message :content str str/trim)
        remove? (str/starts-with? (str/upper-case line) "REMOVE")]
    {:verdict (if remove? :remove :keep)
     :remove? remove?
     :rationale line
     :n-competitors (count (remove :is-c? comps))}))

;; =============================================================================
;; (2) Semantic-similarity proxy (pure vector search on the phrases collection)
;; =============================================================================

(defn- semantic-signal
  "Pure vector-search the phrases collection with `phrase`; read how
   discriminative it is from its nearest neighbours:
     :nearest-other-distance — vector_distance to the closest phrase on a DIFFERENT
                               chunk (large = distinctive to this chunk; small =
                               a near-duplicate lives elsewhere = generic)
     :other-frac             — fraction of the top-k that belong to other chunks."
  [settings coll chunk-id phrase k]
  (let [resp (ts/multi-search settings
                              {:searches [{:collection coll :q phrase :query_by "phrase_vec"
                                           :include_fields "chunk_id,search_phrase"
                                           :exclude_fields "phrase_vec" :limit k}]}
                              {})
        hits (->> resp :results first :hits
                  (map (fn [h] {:chunk_id (-> h :document :chunk_id)
                                :dist (:vector_distance h)})))
        others (filter #(not= chunk-id (:chunk_id %)) hits)
        other-dists (keep :dist others)]
    {:nearest-other-distance (when (seq other-dists) (apply min other-dists))
     :other-frac (when (seq hits) (/ (double (count (distinct (map :chunk_id others))))
                                     (count (distinct (map :chunk_id hits)))))
     :n-hits (count hits)}))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-verify-prune-benefit
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id candidate-phrase phrases-collection-name chunks-collection]} inputs
        {:keys [model semantic-k semantic-distance-threshold content-rank-threshold use-llm?]
         :or {use-llm? true}} parameters
        tenant (:tenant skill-params)
        k (or semantic-k 10)
        dist-threshold (or semantic-distance-threshold 0.15)
        rank-threshold (or content-rank-threshold 1)
        settings (tsu/make-ts-settings (when tenant {:tenant tenant}))
        comps (when (and chunks-collection (seq (str chunks-collection)))
                (competitors settings chunks-collection chunk-id candidate-phrase k))
        c-rank (content-rank comps)
        ;; use-llm? false = cheap content-rank-only pass (candidate generation over
        ;; all of a chunk's phrases). Skips the LLM call AND the content fetch it needs.
        content (when (and use-llm? chunks-collection (seq (str chunks-collection)))
                  (fetch-chunk-content settings chunks-collection chunk-id))
        selected-model (or model (cfg/get {:tenant tenant} :services :azure-openai :deployment-name))
        {:keys [verdict remove? rationale]}
        (if use-llm?
          (llm-judge tenant selected-model candidate-phrase content comps)
          {:verdict :skipped :remove? nil :rationale "llm-skipped"})
        {:keys [nearest-other-distance other-frac n-hits]}
        (semantic-signal settings phrases-collection-name chunk-id candidate-phrase k)
        ;; semantic REMOVE = a near-duplicate phrase exists on another chunk
        ;; (small nearest-other-distance) → p is semantically non-discriminative.
        semantic-remove? (boolean (and (number? nearest-other-distance)
                                       (< nearest-other-distance dist-threshold)))
        ;; content-rank REMOVE = c is NOT among the top `rank-threshold+1` content
        ;; matches for its own phrase → better answers exist → p is crowding. This
        ;; cheaply mirrors the LLM's "is c the best answer for query=p?" question.
        content-rank-remove? (boolean (or (nil? c-rank) (> c-rank rank-threshold)))
        agree? (= remove? semantic-remove?)
        agree-rank? (= remove? content-rank-remove?)]
    (skills/success-result
     {:candidate-phrase candidate-phrase :chunk-id chunk-id
      :llm-verdict verdict :llm-remove? remove? :llm-rationale rationale
      :semantic-nearest-other-distance nearest-other-distance
      :semantic-other-frac other-frac
      :semantic-remove? semantic-remove?
      :content-rank c-rank
      :content-rank-remove? content-rank-remove?
      :agree? agree? :agree-rank? agree-rank?
      :summary (format "LLM=%s  sem=%s(d=%s)  rank=%s(%s)"
                       (name verdict) (if semantic-remove? "REM" "KEEP")
                       (if nearest-other-distance (format "%.3f" (double nearest-other-distance)) "-")
                       (str c-rank) (if content-rank-remove? "REM" "KEEP"))}
     {:tenant tenant :model selected-model :semantic-hits n-hits
      :content-chars (count (str content))})))

;; =============================================================================
;; Registration
;; =============================================================================

(def verify-prune-benefit-skill
  {:metadata verify-prune-benefit-metadata
   :execute execute-verify-prune-benefit})

(defn register! [] (skills/register-skill! verify-prune-benefit-skill))

(register!)
