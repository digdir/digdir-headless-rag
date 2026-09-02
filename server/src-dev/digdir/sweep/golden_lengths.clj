(ns digdir.sweep.golden-lengths
  "One-shot: tabulate golden chunk lengths for all sweep questions, to stratify
   which questions are rerank-truncation-suspect (long goldens)."
  (:require [digdir.sweep.runner :as runner]
            [digdir.sweep.questions :as questions]
            [digdir.rag.core :as rag]
            [clojure.string :as str]))

(defn run []
  (let [scope {:tenant "digdir" :dataset-config-key "default"}
        {:keys [collections]} (#'runner/resolve-dataset-config! scope)
        {:keys [docs-collection chunks-collection]} collections
        questions (questions/load-questions!)
        all-ids (vec (distinct (mapcat :golden-chunk-ids questions)))
        chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection
                                          (mapv (fn [id] {:chunk_id id}) all-ids)
                                          {:tenant "digdir" :retrieve-top-k 500})
        len-by-id (into {} (map (juxt :chunk_id #(or (:content_length %)
                                                     (count (or (:content_markdown %) ""))))) chunks)]
    (println (format "docs=%s chunks=%s | questions=%d golden-ids=%d resolved=%d"
                     docs-collection chunks-collection (count questions) (count all-ids) (count chunks)))
    (println "id,n_goldens,max_len,min_len,any_gt_2000,any_gt_1000,lens")
    (doseq [q (sort-by :id questions)]
      (let [gids (:golden-chunk-ids q)
            lens (sort > (keep len-by-id gids))]
        (when (seq gids)
          (println (format "%s,%d,%s,%s,%s,%s,%s"
                           (:id q) (count gids)
                           (if (seq lens) (apply max lens) "NA")
                           (if (seq lens) (apply min lens) "NA")
                           (boolean (some #(> % 2000) lens))
                           (boolean (some #(> % 1000) lens))
                           (str/join "|" lens))))))))
