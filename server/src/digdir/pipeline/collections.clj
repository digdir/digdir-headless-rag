(ns digdir.pipeline.collections
  "Collection name generation and tracking for pipelines.

   Generates TypeSense collection names based on pipeline configuration,
   with hashing to ensure uniqueness when config changes."
  (:require [clojure.string :as str]
            [digdir.docs.pipeline.core :as core]
            [digdir.config.db :as config-db]))

;; =============================================================================
;; Configuration Hashing
;; =============================================================================

(defn extract-config-subset
  "Extracts configuration keys relevant for collection hashing.
   Returns map with source, chunking, and search phrase config."
  [pipeline-config]
  (select-keys pipeline-config
               [:source-type
                :chunk-strategy
                :chunk-minimum-length
                :chunk-maximum-length
                :search-phrases-model
                :search-phrases-fallback
                :search-phrases-prompt]))

(defn pipeline-config-hash
  "Generate a short hash from pipeline configuration.
   This hash changes when significant config changes (source, chunking, phrases).

   Args:
     pipeline-config - Pipeline configuration map

   Returns: 12-character hash string"
  [pipeline-config]
  (core/sha256-short-hash (extract-config-subset pipeline-config)))

;; =============================================================================
;; Collection Name Generation
;; =============================================================================

(defn pipeline-collection-names
  "Generate TypeSense collection names for a pipeline.

   Collection naming strategy:
   - Format: {prefix}_{type}_{hash}
   - Prefix: from :collection-prefix or auto-generated from pipeline ID
   - Type: 'documents', 'chunks', or 'phrases'
   - Hash: config-based hash for versioning

   Args:
     pipeline-config - Pipeline configuration map with keys:
       :collection-prefix (optional) - Custom prefix
       :pipeline-name (optional) - Used for auto-prefix if no custom prefix
       :source-type, :chunk-strategy, etc. - Used for hashing

   Returns: Map with keys:
     :docs-collection - Documents collection name
     :chunks-collection - Chunks collection name
     :phrases-collection - Phrases collection name"
  [pipeline-config]
  (let [;; Use explicit prefix, or auto-generate from pipeline name
        prefix (or (:collection-prefix pipeline-config)
                   (when-let [name (:pipeline-name pipeline-config)]
                     (str (str/replace name #"[^a-zA-Z0-9_]" "_") "_"))
                   "pipeline_")
        hash-val (pipeline-config-hash pipeline-config)]
    {:docs-collection (str prefix "documents_" hash-val)
     :chunks-collection (str prefix "chunks_" hash-val)
     :phrases-collection (str prefix "phrases_" hash-val)}))

(defn pipeline-collection-names-vec
  "Generate collection names as a vector [docs chunks phrases].
   Matches the format used by existing view code."
  [pipeline-config]
  (let [{:keys [docs-collection chunks-collection phrases-collection]}
        (pipeline-collection-names pipeline-config)]
    [docs-collection chunks-collection phrases-collection]))

;; =============================================================================
;; Collection Tracking
;; =============================================================================

(defn track-pipeline-collections!
  "Store generated collection names back into pipeline config.
   Updates the pipeline with actual collection names.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name
     collection-names - Map with :docs-collection, :chunks-collection, :phrases-collection
     master-key - Encryption key"
  [conn tenant environment pipeline-name collection-names master-key]
  (config-db/set-value! conn
                        {:tenant tenant
                         :environment environment
                         :entity pipeline-name
                         :path "pipeline.storage.docs-collection"
                         :value (:docs-collection collection-names)
                         :master-key master-key})
  (config-db/set-value! conn
                        {:tenant tenant
                         :environment environment
                         :entity pipeline-name
                         :path "pipeline.storage.chunks-collection"
                         :value (:chunks-collection collection-names)
                         :master-key master-key})
  (config-db/set-value! conn
                        {:tenant tenant
                         :environment environment
                         :entity pipeline-name
                         :path "pipeline.storage.phrases-collection"
                         :value (:phrases-collection collection-names)
                         :master-key master-key}))

(defn get-or-generate-collection-names
  "Get collection names from pipeline config, or generate if not set.
   Checks if collection names are already stored in the pipeline.
   If not, generates new names and optionally stores them.

   Args:
     pipeline-config - Pipeline configuration map
     conn - Datahike connection (optional, for storing names)
     master-key - Encryption key (optional, for storing names)

   Returns: Map with :docs-collection, :chunks-collection, :phrases-collection"
  ([pipeline-config]
   (get-or-generate-collection-names pipeline-config nil nil))
  ([pipeline-config conn master-key]
   (let [stored-names {:docs-collection (:docs-collection pipeline-config)
                       :chunks-collection (:chunks-collection pipeline-config)
                       :phrases-collection (:phrases-collection pipeline-config)}
         all-present? (every? some? (vals stored-names))]
     (if all-present?
       ;; Use stored names
       stored-names
       ;; Generate new names
       (let [generated (pipeline-collection-names pipeline-config)]
         ;; Store if conn provided
         (when (and conn master-key (:tenant pipeline-config) (:pipeline-name pipeline-config))
           (track-pipeline-collections! conn
                                        (:tenant pipeline-config)
                                        (:environment pipeline-config)
                                        (:pipeline-name pipeline-config)
                                        generated
                                        master-key))
         generated)))))

(comment
  ;; Generate collection names with custom prefix
  (pipeline-collection-names
   {:collection-prefix "prod_main_"
    :source-type :kudos
    :chunk-strategy :semantic})
  ;; => {:docs-collection "prod_main_documents_abc123..."
  ;;     :chunks-collection "prod_main_chunks_abc123..."
  ;;     :phrases-collection "prod_main_phrases_abc123..."}

  ;; Auto-generate prefix from pipeline name
  (pipeline-collection-names
   {:pipeline-name "my-pipeline"
    :source-type :website
    :chunk-strategy :markdown})
  ;; => {:docs-collection "my_pipeline_documents_xyz789..."
  ;;     :chunks-collection "my_pipeline_chunks_xyz789..."
  ;;     :phrases-collection "my_pipeline_phrases_xyz789..."}

  ;; Configuration changes produce different hashes
  (let [config1 {:source-type :kudos :chunk-strategy :semantic}
        config2 {:source-type :kudos :chunk-strategy :markdown}]
    [(pipeline-config-hash config1)
     (pipeline-config-hash config2)])
  ;; => ["abc123..." "def456..."] ; Different hashes
  )
