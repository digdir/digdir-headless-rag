(ns digdir.sweep.phrase-prune
  "Thread-2 subtractive lever: prune corpus-generic ingest phrases. Reads the
   existing phrases collection, scores each phrase by corpus-relative token-IDF
   specificity (a phrase is noise when ALL its tokens are corpus-common, e.g.
   'event-driven architecture' in an Altinn-events corpus), and writes the
   surviving phrases to a NEW collection (original untouched; rollback = drop the
   new one). Deterministic ids preserved → idempotent. Pruning only removes phrase
   rows: chunk ids / golden ids / docs are untouched, so the sweep A/B needs NO
   re-grounding.

   Two modes:
     (run {:mode :analyze})                       — distribution + calibration only
     (run {:mode :build :threshold T :new-coll C}) — write the pruned clone"
  (:require [clojure.string :as str]
            [digdir.sweep.runner :as runner]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.rag.typesense :as tsu]
            [typesense.client :as ts]))

(def ^:private ts-admin storage/ts-admin)

(defn- read-all-phrases [coll]
  (let [settings (tsu/make-ts-settings {:tenant "digdir"})]
    (loop [page 1 acc []]
      (let [resp (ts/multi-search settings
                                  {:searches [{:collection coll :q "*" :per_page 250 :page page
                                               :include_fields "id,chunk_id,doc_num,search_phrase"}]}
                                  {:query_by "search_phrase"})
            hits (->> resp :results first :hits (mapv :document))]
        (if (empty? hits) acc (recur (inc page) (into acc hits)))))))

(defn- tokenize [s]
  (->> (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")
       (remove str/blank?)
       (remove #(= 1 (count %)))     ; drop single-char noise
       set))

(defn- score-phrases
  "Attach :tokens and :specificity (= max token IDF; corpus-relative) to each phrase.
   df(token) = # distinct docs whose phrases contain the token. A phrase whose every
   token is corpus-common scores low; one rare token rescues it."
  [phrases]
  (let [n-docs (count (distinct (map :doc_num phrases)))
        toks   (mapv #(assoc % :tokens (tokenize (:search_phrase %))) phrases)
        ;; token -> set of doc_nums
        tok->docs (reduce (fn [m p]
                            (reduce (fn [m t] (update m t (fnil conj #{}) (:doc_num p)))
                                    m (:tokens p)))
                          {} toks)
        idf (fn [t] (Math/log (/ (double n-docs) (double (max 1 (count (get tok->docs t)))))))]
    {:n-docs n-docs
     :phrases (mapv (fn [p]
                      (assoc p :specificity
                             (if (seq (:tokens p))
                               (apply max (map idf (:tokens p)))
                               0.0)))
                    toks)}))

(defn- pct [s q] (nth (vec (sort s)) (min (dec (count s)) (int (* q (count s))))))

(defn- show-doc [phrases doc-num label]
  (let [ps (filter #(= doc-num (:doc_num %)) phrases)]
    (println (format "  %-26s doc=%s n=%d  specificity min/med/max = %.2f / %.2f / %.2f"
                     label doc-num (count ps)
                     (apply min (map :specificity ps))
                     (pct (map :specificity ps) 0.5)
                     (apply max (map :specificity ps))))))

(defn- apply-prune
  "Per-doc volume normalization: within each doc keep the top-`cap` phrases by
   specificity (descending); globally also drop any phrase below `floor` (pure
   junk). Focused docs (<= cap phrases) are untouched; verbose overview docs get
   their generic tail trimmed — directly cutting hit-count promiscuity. Returns
   {:kept :pruned}."
  [phrases {:keys [cap floor]}]
  (let [kept (->> (group-by :doc_num phrases)
                  (mapcat (fn [[_ ps]]
                            (->> ps
                                 (sort-by :specificity >)
                                 (take (or cap Long/MAX_VALUE))
                                 (filter #(>= (:specificity %) (or floor 0.0))))))
                  vec)
        kept-ids (set (map :id kept))]
    {:kept kept :pruned (remove #(kept-ids (:id %)) phrases)}))

(defn- doc-kept-count [kept doc] (count (filter #(= doc (:doc_num %)) kept)))

(defn run [{:keys [mode cap floor new-coll]}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config! {:tenant "digdir" :dataset-config-key "default"})
        src (:phrases-collection collections)
        raw (read-all-phrases src)
        {:keys [n-docs phrases]} (score-phrases raw)
        specs (map :specificity phrases)
        per-doc (map count (vals (group-by :doc_num phrases)))]
    (println (format "src=%s phrases=%d docs=%d" src (count raw) n-docs))
    (println (format "specificity dist: p10=%.2f p25=%.2f p50=%.2f p75=%.2f p90=%.2f"
                     (pct specs 0.10) (pct specs 0.25) (pct specs 0.50) (pct specs 0.75) (pct specs 0.90)))
    (println (format "phrases/doc dist: p10=%d p25=%d p50=%d p75=%d p90=%d max=%d"
                     (pct per-doc 0.10) (pct per-doc 0.25) (pct per-doc 0.50)
                     (pct per-doc 0.75) (pct per-doc 0.90) (apply max per-doc)))
    (println "\nground-truth docs (goldens should be SHORT/specific, overview distractors LONG):")
    (show-doc phrases "f9c39f17a534" "GOLDEN broker-03")
    (show-doc phrases "88a046dc8cb8" "DISTRACTOR Events")
    (show-doc phrases "b66bdced0835" "DISTRACTOR Publish events")
    (show-doc phrases "221250e04c48" "DISTRACTOR Expr validation")
    (when (or cap floor)
      (let [{:keys [kept pruned]} (apply-prune phrases {:cap cap :floor floor})]
        (println (format "\ncap=%s floor=%s -> prune %d (%.1f%%), keep %d"
                         (str cap) (str floor) (count pruned)
                         (* 100.0 (/ (count pruned) (count phrases))) (count kept)))
        (println (format "  GOLDEN broker-03 kept %d/10 | Events kept %d/60 | Publish %d/32 | Expr %d/23"
                         (doc-kept-count kept "f9c39f17a534") (doc-kept-count kept "88a046dc8cb8")
                         (doc-kept-count kept "b66bdced0835") (doc-kept-count kept "221250e04c48")))
        (println "  sample PRUNED:" (->> pruned (map :search_phrase) (take 10) vec))
        (when (= mode :build)
          (let [keep-rows (mapv #(select-keys % [:id :chunk_id :doc_num :search_phrase]) kept)
                schema (ts/retrieve-collection ts-admin src)
                new-schema (-> (select-keys schema [:fields :default_sorting_field :token_separators
                                                    :symbols_to_index :enable_nested_fields])
                               (assoc :name new-coll))]
            (println (format "\nBUILD -> %s with %d kept rows (re-embeds phrase_vec on upsert)" new-coll (count keep-rows)))
            (storage/create-collection! new-schema)
            (doseq [batch (partition-all 200 keep-rows)]
              (ts/upsert-documents! ts-admin new-coll (vec batch)))
            (let [verify (ts/retrieve-collection ts-admin new-coll)]
              (println "DONE — new collection num_documents:" (:num_documents verify)))))))))
