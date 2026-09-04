(ns digdir.sweep.rechunk-migrate
  "Lever B migration: read the existing chunks, sub-split the oversized ones to a
   reranker-sized max, and write the result to a NEW chunks collection (existing
   collection untouched; rollback = drop the new one). A pure chunk-level transform
   — no source re-fetch, no header re-parse — because existing chunks already carry
   their header metadata. chunk_id = sha256-short-hash(content), identical to the
   production pipeline, so short (<=max) chunks keep their exact id."
  (:require [digdir.sweep.runner :as runner]
            [digdir.rag.chunking :as ck]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.rag.typesense :as tsu]
            [typesense.client :as ts]
            [valuehash.api]))

(def ^:private dev-tenant
  "This tool already pins tenant \"digdir\" when it resolves its dataset config;
   #476 removed the resolver's hidden default, so it states the same tenant for
   its Typesense writes instead of inheriting one."
  {:tenant "digdir"})

(defn- ts-admin
  "Resolved per call, not at namespace load: a load-time resolution would throw
   on a classpath with no config DB and take every src-dev namespace with it."
  []
  (tsu/make-ts-settings dev-tenant))

(defn- chunk-id [content] (->> content valuehash.api/sha-256-str (take 12) (apply str)))

(defn- read-all-chunks [coll]
  (let [settings (tsu/make-ts-settings {:tenant "digdir"})]
    (loop [page 1 acc []]
      (let [resp (ts/multi-search settings
                                  {:searches [{:collection coll :q "*" :per_page 250 :page page
                                               :include_fields "id,chunk_id,doc_num,chunk_index,content_markdown,content_length,metadata,url"}]}
                                  {:query_by "content_markdown"})
            hits (->> resp :results first :hits (mapv :document))]
        (if (empty? hits) acc (recur (inc page) (into acc hits)))))))

(defn- rechunk-doc
  "Split a doc's oversized chunks, re-index chunk_index sequentially, assign new
   content-derived ids. Pass-through chunks keep their exact id (content unchanged)."
  [max-len overlap chunks]
  (let [expanded (mapcat (fn [c]
                           (map #(assoc c :content_markdown %)
                                (ck/split-oversized-content (:content_markdown c) max-len overlap)))
                         (sort-by :chunk_index chunks))]
    (map-indexed (fn [idx c]
                   (let [content (:content_markdown c)
                         id (chunk-id content)]
                     (-> c
                         (assoc :chunk_id id :id id :chunk_index idx :content_length (count content))
                         (select-keys [:id :chunk_id :doc_num :chunk_index :content_markdown :content_length :metadata :url]))))
                 expanded)))

(defn run [{:keys [max-len overlap new-coll]}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config! {:tenant "digdir" :dataset-config-key "default"})
        src (:chunks-collection collections)
        all (read-all-chunks src)
        oversized (count (filter #(> (count (str (:content_markdown %))) max-len) all))
        new-chunks (vec (mapcat (fn [[_ cs]] (rechunk-doc max-len overlap cs))
                                (group-by :doc_num all)))
        ;; clone schema verbatim (preserves any join references), new name
        schema (ts/retrieve-collection (ts-admin) src)
        new-schema (-> (select-keys schema [:fields :default_sorting_field :token_separators
                                            :symbols_to_index :enable_nested_fields])
                       (assoc :name new-coll))
        ls (map :content_length new-chunks)
        ;; correctness self-check: every pass-through (<=max) chunk's id is unchanged
        passthrough-ok? (let [old-ids (set (map :chunk_id (filter #(<= (count (str (:content_markdown %))) max-len) all)))
                              new-ids (set (map :chunk_id new-chunks))]
                          (every? new-ids old-ids))]
    (println (format "src=%s total=%d oversized=%d -> new=%s total=%d"
                     src (count all) oversized new-coll (count new-chunks)))
    (println (format "new size dist: max=%d p50=%d over-max=%d | passthrough-ids-preserved=%s"
                     (apply max ls) (nth (sort ls) (quot (count ls) 2))
                     (count (filter #(> % max-len) ls)) passthrough-ok?))
    (storage/create-collection! dev-tenant new-schema)
    (doseq [batch (partition-all 200 new-chunks)]
      (ts/upsert-documents! (ts-admin) new-coll (vec batch)))
    (let [verify (ts/retrieve-collection (ts-admin) new-coll)]
      (println "DONE — new collection num_documents:" (:num_documents verify)))))
