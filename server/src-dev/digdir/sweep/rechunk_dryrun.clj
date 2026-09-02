(ns digdir.sweep.rechunk-dryrun
  "Read-only dry-run of Lever B on the real long goldens: fetch each oversized
   golden's content, apply split-oversized-content, and report (a) how it splits
   and (b) WHICH sub-chunk carries the answer — so we can see whether sub-splitting
   fragments answers (bad) and produce the re-grounding map before writing anything."
  (:require [digdir.sweep.runner :as runner]
            [digdir.sweep.questions :as questions]
            [digdir.rag.core :as rag]
            [digdir.rag.chunking :as ck]
            [clojure.string :as str]))

(defn run [max-len overlap]
  (let [scope {:tenant "digdir" :dataset-config-key "default"}
        {:keys [collections]} (#'runner/resolve-dataset-config! scope)
        {:keys [docs-collection chunks-collection]} collections
        qs (filter #(str/starts-with? (:id %) "ue-") (questions/load-questions!))
        all-ids (vec (distinct (mapcat :golden-chunk-ids qs)))
        chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection
                                          (mapv (fn [id] {:chunk_id id}) all-ids)
                                          {:tenant "digdir" :retrieve-top-k 500})
        by-id (into {} (map (juxt :chunk_id identity)) chunks)]
    (println (format "max=%d overlap=%d | questions=%d goldens=%d" max-len overlap (count qs) (count all-ids)))
    (println "id,golden_id,old_len,n_parts,part_lens,answer_part,answer_fragmented")
    (doseq [q (sort-by :id qs)
            gid (:golden-chunk-ids q)]
      (when-let [c (by-id gid)]
        (let [content (:content_markdown c)
              olen (count (str content))]
          (when (> olen max-len)
            (let [parts (ck/split-oversized-content content max-len overlap)
                  pat (some-> (:expected-answer-pattern q) re-pattern)
                  ;; which sub-chunks contain the answer pattern?
                  hits (when pat
                         (->> parts (map-indexed vector)
                              (filter (fn [[_ p]] (re-find pat p)))
                              (mapv first)))]
              (println (format "%s,%s,%d,%d,%s,%s,%s"
                               (:id q) (subs gid 0 (min 12 (count gid)))
                               olen (count parts)
                               (str/join "|" (map count parts))
                               (if (seq hits) (str/join "+" hits) (if pat "NONE" "no-pattern"))
                               (boolean (and hits (> (count hits) 1))))))))))))
