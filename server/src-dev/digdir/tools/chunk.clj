(ns digdir.tools.chunk
  "CLI tool for fetching and displaying a single chunk from Typesense.
   Usage: bb chunk <tenant> <env> <chunk-id> [pipeline-id]"
  (:require [digdir.config.db :as config-db]
            [digdir.config.core :as config-core]
            [digdir.pipeline.collections :as collections]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]))

(defn lookup
  "Fetch and pretty-print a chunk by ID.
   Called via clojure -X with keyword args."
  [{:keys [tenant environment chunk-id pipeline-id]}]
  (let [tenant (name tenant)
        environment (name environment)
        chunk-id (name chunk-id)
        pipeline-id (name (or pipeline-id :kudos))
        conn (config-db/get-conn)
        db @conn
        master-key (config-core/get-master-key)
        ;; `get-pipeline` was removed by the entity->pipeline->dataset rename;
        ;; `get-dataset` is its direct successor (same arity, same arg shape,
        ;; and it returns the :docs-collection/:chunks-collection keys that
        ;; get-or-generate-collection-names reads below).
        pipeline-config (config-db/get-dataset db tenant environment
                                               pipeline-id master-key)
        collection-names (collections/get-or-generate-collection-names pipeline-config)
        chunks-collection (:chunks-collection collection-names)
        docs-collection (:docs-collection collection-names)
        ts-settings (ts-utils/make-ts-settings {:tenant tenant :environment environment})
        response (ts-client/multi-search
                  ts-settings
                  {:searches [{:collection chunks-collection
                               :q chunk-id
                               :include_fields (str "id,chunk_id,doc_num,content_markdown,metadata,$"
                                                    docs-collection "(url,title)")
                               :filter_by (str "chunk_id:=`" chunk-id "`")
                               :page 1
                               :per_page 1}]}
                  {:query_by "chunk_id"})
        hit (first (mapcat :hits (get response :results)))]
    (if hit
      (let [doc (:document hit)
            ref-doc (get doc (keyword docs-collection))]
        (println "== CHUNK ==" chunk-id)
        (println)
        (println "[title]" (or (:title ref-doc) "(none)"))
        (println "[doc_num]" (:doc_num doc))
        (println "[url]" (or (:url ref-doc) "(none)"))
        (println "[metadata]" (:metadata doc))
        (println)
        (println "== CONTENT ==")
        (println (:content_markdown doc)))
      (do
        (println "No chunk found with id:" chunk-id)
        (println "Collections searched:" chunks-collection docs-collection)))))
