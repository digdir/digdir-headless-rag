(ns digdir.docs.pipeline.protocol
  "Protocol definition for document source pipelines.

   This namespace defines the DocumentSource protocol that all document
   pipelines must implement. This enables a consistent interface for
   adding new document sources while sharing common infrastructure."
  (:require [missionary.core :as m]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.docs.pipeline.orchestration :as orchestration]
            [digdir.rag.chunking :as chunking]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Document Source Protocol
;; ============================================================================

(defprotocol DocumentSource
  "Protocol for document source implementations.

   Each document source (website, folder, episerver, etc.) implements this
   protocol to define its specific behaviors."

  (source-name [this]
    "Returns the name of this source as a keyword (e.g., :website, :folder).
     Used for telemetry event namespacing and cache directory names.")

  (fetch-entries [this config]
    "Returns a Missionary task that fetches raw source entries.
     Returns a seq of entry maps (e.g., {:loc url :lastmod date} or {:path path :lastmod date}).")

  (entry-to-doc [this entry]
    "Converts a raw entry to a document structure.
     Must return a map with at least :id, :doc_num, :title, :type.")

  (fetch-content [this config entry]
    "Returns a Missionary task that fetches content for an entry.
     Returns the content as a string (typically markdown).")

  (docs-schema [this coll-name]
    "Returns the TypeSense schema for the documents collection.")

  (chunks-schema [this coll-ids]
    "Returns the TypeSense schema for the chunks collection.
     coll-ids is [docs-coll chunks-coll phrases-coll].")

  (phrases-schema [this coll-ids]
    "Returns the TypeSense schema for the phrases collection.
     coll-ids is [docs-coll chunks-coll phrases-coll].")

  (prepare-doc-for-storage [this config doc]
    "Prepares a document for TypeSense storage.
     Should select only the fields needed and transform paths/URLs as needed.")

  (prepare-chunks-for-storage [this config chunks]
    "Prepares chunks for TypeSense storage.
     Should select only the fields needed and transform paths/URLs as needed.")

  (location-key [this]
    "Returns the key used for document location (:url or :path)."))

;; ============================================================================
;; Default Chunking Implementation
;; ============================================================================

(defn header-based-chunks
  "Chunks markdown text by headers. Common implementation for all sources."
  [config text]
  (let [doc {:page-content text}]
    (->> (chunking/split-into-chunks-by-headers config [doc])
         (mapv (fn [{:keys [page-content metadata]}]
                 {:chunk_id (core/sha256-short-hash page-content)
                  :content_markdown page-content
                  :metadata (pr-str metadata)})))))

(defn chunk-document
  "Chunks a document based on configuration.
   config must include :chunks/strategy, :chunks/minimum-length, :chunks/maximum-length"
  [{:chunks/keys [strategy minimum-length maximum-length] :as config} doc location-key]
  (let [all-chunks (case strategy
                     :header-based (header-based-chunks config (:content_markdown doc)))
        filtered-chunks (filter (fn [chunk]
                                  (let [l (count (:content_markdown chunk))]
                                    (cond
                                      (< l minimum-length)
                                      (do (t/event! :chunking/filtered-too-short) false)

                                      (> l maximum-length)
                                      (do (t/event! :chunking/filtered-too-long) false)

                                      :else true)))
                                all-chunks)]
    (assoc doc :chunks
           (vec (map-indexed (fn [index chunk]
                               (assoc chunk
                                      :doc_num (:doc_num doc)
                                      :chunk_index index
                                      location-key (get doc location-key)))
                             filtered-chunks)))))

;; ============================================================================
;; Generic Pipeline Builder
;; ============================================================================

(defn mk-prepare-document-t
  "Creates a document preparation task for a source.
   Handles: fetch content -> chunk -> generate search phrases"
  [source config entry]
  (let [source-kw (source-name source)
        loc-key (location-key source)]
    (m/sp
      (as-> entry doc
        (entry-to-doc source doc)
        (assoc doc :content_markdown (m/? (fetch-content source config entry)))
        (m/? (m/via m/blk (chunk-document config doc loc-key)))
        (m/? (search-phrases/mk-distill-doc-search-phrases-t config doc (name source-kw)))
        (do (core/say (str "Prepared " (name source-kw) " doc"))
            (t/event! (keyword (name source-kw) "document-prepared")
                      {:data {loc-key (get doc loc-key)}})
            doc)))))

(defn mk-store-document-t
  "Creates a document storage task for a source."
  [source config doc]
  (m/via m/blk
         (storage/store-complete-document!
          config doc
          (fn [cfg d] (prepare-doc-for-storage source cfg d))
          (fn [chunks] (prepare-chunks-for-storage source config chunks)))))

(defn create-stores!
  "Creates all TypeSense collections for a source."
  [source config]
  (storage/create-collections!
   config
   (fn [name] (docs-schema source name))
   (fn [ids] (chunks-schema source ids))
   (fn [ids] (phrases-schema source ids))))

(defn mk-materialize-t
  "Creates a complete materialization task for a document source."
  [source config filter-entries-fn]
  (let [source-kw (source-name source)]
    (m/sp
      (t/event! (keyword (name source-kw) "materializing")
                {:data {:config config
                        :colls (storage/coll-ids config)}})

      (m/? (m/via m/blk (create-stores! source config)))

      (orchestration/mk-materialize-t
       config
       (fetch-entries source config)
       filter-entries-fn
       (fn [cfg entry] (mk-prepare-document-t source cfg entry))
       (fn [cfg doc] (mk-store-document-t source cfg doc))
       source-kw))))

;; ============================================================================
;; Pipeline Runner
;; ============================================================================

(defn run-pipeline!
  "Runs a document source pipeline."
  [source config filter-entries-fn]
  (let [pipeline-t (mk-materialize-t source config filter-entries-fn)]
    (orchestration/run-pipeline! pipeline-t (source-name source))))

(defn stop-pipeline!
  "Stops the currently running pipeline."
  [source]
  (orchestration/stop-pipeline! (source-name source)))
