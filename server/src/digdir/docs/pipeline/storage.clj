(ns digdir.docs.pipeline.storage
  "TypeSense storage operations for document processing pipelines.

   This namespace provides generic TypeSense operations used across all
   document sources:
   - Collection ID generation with config-based hashing
   - Document/chunk/phrase storage with upsert semantics
   - Collection creation with conflict handling"
  (:require [typesense.client :as ts]
            [digdir.rag.typesense :as ts-utils]
            [digdir.docs.pipeline.core :as core]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; ============================================================================
;; TypeSense Admin Client
;; ============================================================================

(def ts-admin
  "TypeSense admin client instance."
  ts-utils/ts-admin)

;; ============================================================================
;; Configuration Utilities
;; ============================================================================

(defn extract-ns-from-map
  "Extracts all keys from map m that have namespace ns or start with ns.
   Useful for extracting config subsets like :chunks/* or :search-phrases/*"
  [m ns]
  (into {}
        (filter (core/=> key namespace #(and % (or (str/starts-with? % (str ns ".")) (= ns %)))))
        m))

(defn config-hash
  "Generates a hash of config values in a specific namespace.
   Used to create unique collection names based on configuration."
  [config ns]
  (core/sha256-short-hash
   (extract-ns-from-map
    (select-keys config [:hash-changer :strategy]) ns)))

(defn coll-ids
  "Generates collection IDs for documents, chunks, and phrases based on config.
   Returns [docs-coll chunks-coll phrases-coll] vector.

   Collection names include a hash of relevant config to allow version migration."
  [{:store/keys [coll-prefix] :as config}]
  [(str coll-prefix "documents_" (config-hash config "documents"))
   (str coll-prefix "chunks_" (config-hash config "chunks"))
   (str coll-prefix "phrases_" (config-hash config "search-phrases"))])

;; ============================================================================
;; Document Existence Check
;; ============================================================================

(defn document-inserted?
  "Checks if a document with the given ID exists in the collection.
   Returns true if found, false otherwise."
  [coll-name doc]
  (try
    (ts/retrieve-document ts-admin coll-name (:id doc))
    true
    (catch Exception _
      false)))

;; ============================================================================
;; Collection Creation
;; ============================================================================

(defn create-collection!
  "Creates a TypeSense collection with the given schema.
   Returns the created schema on success, :already-exists if collection exists,
   or throws for other errors."
  [schema]
  (try
    (t/event! :pipeline/creating-collection {:data {:name (:name schema)}})
    (ts/create-collection! ts-admin schema)
    (catch clojure.lang.ExceptionInfo e
      (if (= (:type (ex-data e)) :typesense.client/conflict)
        (do
          (t/log! ["Collection already exists:" (:name schema)])
          :already-exists)
        (throw e)))))

(defn create-collections!
  "Creates all three collections (docs, chunks, phrases) for a pipeline.
   Takes a config map and three schema builder functions.

   Example:
     (create-collections! config
       (fn [name] (docs-schema name))
       (fn [ids] (chunks-schema ids))
       (fn [ids] (phrases-schema ids)))"
  [config docs-schema-fn chunks-schema-fn phrases-schema-fn]
  (let [[docs-coll chunks-coll phrases-coll :as ids] (coll-ids config)]
    (t/event! :pipeline/creating-stores {:data {:coll-ids ids}})
    (create-collection! (docs-schema-fn docs-coll))
    (create-collection! (chunks-schema-fn ids))
    (create-collection! (phrases-schema-fn ids))
    ids))

;; ============================================================================
;; Document Storage
;; ============================================================================

(defn upsert-document!
  "Upserts a single document into the collection.
   Returns the document on success."
  [coll-name doc]
  (t/event! :pipeline/upserting-document {:data {:id (:id doc)}})
  (ts/upsert-document! ts-admin coll-name doc))

(defn upsert-documents!
  "Upserts multiple documents into the collection.
   Returns the results on success."
  [coll-name docs]
  (when (seq docs)
    (t/event! :pipeline/upserting-documents {:data {:count (count docs)}})
    (ts/upsert-documents! ts-admin coll-name docs)))

;; ============================================================================
;; Chunk Storage
;; ============================================================================

(defn prepare-chunks
  "Prepares chunks for storage by selecting only required fields.
   location-key should be :url or :path depending on source type.
   location-transform-fn transforms the location value (e.g., make-relative-url)."
  [chunks location-key location-transform-fn]
  (mapv #(-> %
             (update location-key location-transform-fn)
             (select-keys [:chunk_id :doc_num :chunk_index :content_markdown :metadata location-key]))
        chunks))

(defn store-chunks!
  "Stores chunks for a document into the chunks collection."
  [chunks-coll chunks]
  (when (seq chunks)
    (t/event! :pipeline/upserting-chunks {:data {:count (count chunks)}})
    (ts/upsert-documents! ts-admin chunks-coll chunks)))

;; ============================================================================
;; Phrase Storage
;; ============================================================================

(defn extract-phrases
  "Extracts search phrases from chunks into phrase documents.
   Returns a vector of {:search_phrase :chunk_id :doc_num} maps."
  [chunks doc-num]
  (let [chunks-with-phrases (filter #(seq (:search-phrases %)) chunks)
        chunks-without-phrases (remove #(seq (:search-phrases %)) chunks)]
    (when (seq chunks-without-phrases)
      (t/log! ["Chunks without search phrases:" (count chunks-without-phrases)]))
    (vec (for [chunk chunks-with-phrases
               phrase (:search-phrases chunk)
               :when (and phrase (string? phrase) (not (str/blank? phrase)))]
           {:search_phrase phrase
            :chunk_id (:chunk_id chunk)
            :doc_num doc-num}))))

(defn store-phrases!
  "Stores search phrases for a document into the phrases collection."
  [phrases-coll phrases doc-id]
  (t/event! :pipeline/phrase-count {:data {:count (count phrases) :doc-id doc-id}})
  (when (seq phrases)
    (try
      (ts/upsert-documents! ts-admin phrases-coll phrases)
      (catch Exception e
        (t/error! {:id :pipeline/upsert-phrases-error
                   :msg ["Failed to upsert phrases" "doc:" doc-id "count:" (count phrases)]}
                  e)
        (t/log! ["Sample phrases:" (take 3 phrases)])
        (throw e)))))

;; ============================================================================
;; Complete Document Storage
;; ============================================================================

(defn store-complete-document!
  "Stores a fully prepared document with its chunks and phrases.

   Parameters:
   - config: pipeline configuration
   - doc: prepared document with :id, :chunks (with :search-phrases)
   - prepare-doc-fn: (fn [config doc] -> doc-for-storage)
   - prepare-chunks-fn: (fn [chunks] -> chunks-for-storage)

   Returns nil on success, throws on error."
  [config doc prepare-doc-fn prepare-chunks-fn]
  (let [[docs-coll chunks-coll phrases-coll] (coll-ids config)]
    (if (document-inserted? docs-coll doc)
      (do
        (Thread/sleep 1000)  ; Rate limiting for existing docs
        (t/event! :pipeline/document-already-exists))
      (do
        (try
          ;; Store document
          (upsert-document! docs-coll (prepare-doc-fn config doc))

          ;; Store chunks
          (let [prepared-chunks (prepare-chunks-fn (:chunks doc))]
            (store-chunks! chunks-coll prepared-chunks))

          ;; Store phrases
          (let [phrases (extract-phrases (:chunks doc) (:doc_num doc))]
            (store-phrases! phrases-coll phrases (:id doc)))

          (core/say "Stored document")
          (t/event! :pipeline/document-stored {:data {:id (:id doc)}})

          (catch Exception e
            (t/error! {:id :pipeline/store-document-error
                       :msg ["Failed to store document" (:id doc)]}
                      e)
            (throw e)))))))
