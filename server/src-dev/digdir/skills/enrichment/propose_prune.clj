(ns digdir.skills.enrichment.propose-prune
  "`:builtin/enrichment-propose-prune` — the PRUNE-side candidate generator.

   The pruning counterpart of `:builtin/enrichment-propose-phrases`. Where propose
   GENERATES new discriminative phrases for a chunk, this READS the chunk's CURRENT
   combined phrase set (primary generated + enrichment verified) and flags the
   corpus-GENERIC ones as removal candidates.

   ## Broadness lives here (token-IDF), not in the gate

   verify-prune showed a per-op retrieval probe can't separate broad from specific
   (the hybrid lookup returns ~15-20 hits for any phrase). Token-IDF over the
   phrase→doc graph does: a phrase whose every token is corpus-common (\"event-driven
   architecture\" in an Altinn-events corpus) scores low; a phrase with a rare token
   (\"theoretical maximum size 1.6TB\") scores high. So broadness — *which* phrases
   are even candidates — is decided here. verify-prune then checks removal SAFETY,
   and the batch eval-suite judges net benefit.

   `specificity(p) = max over p's tokens of idf(token)`, `idf = log(N_docs / df)`,
   `df(token)` = # distinct docs whose phrases contain the token. Flag `p` when
   `specificity(p) < :specificity-threshold`. The corpus df map is read ONCE and
   memoized per (tenant, collection) so the per-chunk graph foreach amortizes it.

   The primary generated phrases came from the OLD broad-style ingest prompt
   (D2.21) — exactly the style the enrichment ADD pass learned to stop producing —
   so the bulk of the candidates live there. Lives in `src-dev/` with its siblings."
  (:require [clojure.string :as str]
            [digdir.docs.pipeline.core :as core]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as tsu]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def propose-prune-metadata
  {:skill-id :builtin/enrichment-propose-prune
   :name "Propose phrase prune candidates"
   :description "Read a chunk's combined primary+enrichment phrase set and flag the corpus-generic (low token-IDF) ones as prune candidates. Broadness only — removal safety is verify-prune's job, net benefit the eval-suite's."
   :category :augmentation
   :inputs [:chunk-id :phrases-collection-name :enrichment-collection-name]
   :outputs [:chunk-id :candidates :kept :provenance]
   :parameters {:specificity-threshold :number}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :prune}})

;; =============================================================================
;; Corpus token-IDF (read once, memoized per tenant+collection)
;; =============================================================================

(defn- tokenize
  "Lowercase word/number tokens, drop single-char noise. Mirrors the sweep's
   phrase_prune scorer so the two agree on broadness."
  [s]
  (->> (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")
       (remove str/blank?)
       (remove #(= 1 (count %)))
       set))

(defn- read-all-rows
  "Paginate every row of `coll`, returning [{:chunk_id .. <field> ..} ...]."
  [settings coll field]
  (loop [page 1 acc []]
    (let [resp (ts/multi-search settings
                                {:searches [{:collection coll :q "*" :per_page 250 :page page
                                             :include_fields (str "chunk_id,doc_num," field)}]}
                                {:query_by field})
          hits (->> resp :results first :hits (mapv :document))]
      (if (empty? hits) acc (recur (inc page) (into acc hits))))))

(defn- build-corpus-df
  "Read every primary phrase once; return {:n-docs N :tok->df {token #docs}}.
   df is DOCUMENT frequency (distinct doc_num), so broadness is corpus-relative."
  [tenant coll]
  (let [settings (tsu/make-ts-settings (when tenant {:tenant tenant}))
        rows (read-all-rows settings coll "search_phrase")
        n-docs (count (distinct (map :doc_num rows)))
        tok->df (reduce (fn [m row]
                          (let [d (:doc_num row)]
                            (reduce (fn [m t] (update m t (fnil conj #{}) d))
                                    m (tokenize (:search_phrase row)))))
                        {} rows)]
    {:n-docs n-docs
     :tok->df (into {} (map (fn [[t ds]] [t (count ds)]) tok->df))}))

(def ^:private corpus-df
  "Memoized on [tenant coll] — the 47k-phrase read happens once per graph run."
  (memoize build-corpus-df))

(defn- specificity
  "max token IDF (corpus-relative). Empty/unknown tokens → 0.0 (most generic)."
  [{:keys [n-docs tok->df]} phrase]
  (let [toks (tokenize phrase)]
    (if (empty? toks)
      0.0
      (apply max (map (fn [t] (Math/log (/ (double n-docs)
                                           (double (max 1 (get tok->df t 1))))))
                      toks)))))

;; =============================================================================
;; Read a chunk's phrases (combined: primary + enrichment)
;; =============================================================================

(defn- chunk-phrases
  "Every phrase on `chunk-id` in `coll` (read from `field`), tagged with the
   row `:id` (so apply-prune deletes by exact id regardless of collection — primary
   uses deterministic ids, enrichment uses Typesense-auto ones) and `collection-kw`."
  [settings coll field collection-kw chunk-id]
  (let [resp (ts/multi-search settings
                              {:searches [{:collection coll :q "*"
                                           :filter_by (str "chunk_id:=" chunk-id)
                                           :include_fields (str "id,chunk_id," field)
                                           :per_page 250}]}
                              {:query_by field})]
    (->> resp :results first :hits
         (map :document)
         (keep (fn [d] (when-let [p (get d (keyword field))]
                         {:id (:id d) :chunk-id chunk-id :phrase p :collection collection-kw})))
         vec)))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-propose-prune
  "Flag the corpus-generic phrases on `:chunk-id` as prune candidates.

   Inputs:
     :chunk-id                  — the chunk to consider
     :phrases-collection-name   — primary generated phrases (search_phrase field)
     :enrichment-collection-name — optional verified-phrases (phrase field); nil = skip

   Parameters:
     :specificity-threshold — flag phrases with max-token-IDF below this (default 4.0)

   Outputs:
     :candidates — [{:chunk-id :phrase :collection :specificity}] flagged broad
     :kept       — the same shape for phrases left untouched (specific enough)
     :provenance — {:specificity-threshold :n-docs :evaluated :flagged}"
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id phrases-collection-name enrichment-collection-name]} inputs
        threshold (or (:specificity-threshold parameters) 4.0)
        tenant (:tenant skill-params)]
    (if (or (nil? chunk-id) (str/blank? (str chunk-id))
            (nil? phrases-collection-name) (str/blank? (str phrases-collection-name)))
      (skills/success-result
       {:chunk-id chunk-id :candidates [] :kept []
        :provenance {:specificity-threshold threshold :evaluated 0 :flagged 0
                     :note "missing :chunk-id or :phrases-collection-name"}}
       {:skipped? true})
      (let [settings (tsu/make-ts-settings (when tenant {:tenant tenant}))
            df (corpus-df tenant phrases-collection-name)
            primary (chunk-phrases settings phrases-collection-name
                                   "search_phrase" :primary chunk-id)
            enrich (if (and enrichment-collection-name
                            (seq (str enrichment-collection-name)))
                     (chunk-phrases settings enrichment-collection-name
                                    "phrase" :enrichment chunk-id)
                     [])
            scored (->> (concat primary enrich)
                        (map #(assoc % :specificity (specificity df (:phrase %)))))
            candidates (->> scored (filter #(< (:specificity %) threshold))
                            (sort-by :specificity) vec)
            kept (->> scored (remove #(< (:specificity %) threshold)) vec)]
        (skills/success-result
         {:chunk-id chunk-id
          :candidates candidates
          :kept kept
          :provenance {:specificity-threshold threshold
                       :n-docs (:n-docs df)
                       :prompt-hash (core/sha256-short-hash
                                     (str "propose-prune|" threshold "|" phrases-collection-name))
                       :evaluated (count scored)
                       :flagged (count candidates)}}
         {:tenant tenant
          :primary-count (count primary)
          :enrichment-count (count enrich)})))))

;; =============================================================================
;; Registration
;; =============================================================================

(def propose-prune-skill
  {:metadata propose-prune-metadata
   :execute execute-propose-prune})

(defn register!
  "Register the propose-prune skill. Idempotent."
  []
  (skills/register-skill! propose-prune-skill))

(register!)
