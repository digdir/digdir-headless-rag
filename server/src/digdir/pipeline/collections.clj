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
     tenant-config-key - Environment
     dataset-id - Target dataset ID
     pipeline-name - Pipeline name
     collection-names - Map with :docs-collection, :chunks-collection, :phrases-collection
     master-key - Encryption key"
  ([_conn tenant tenant-config-key pipeline-name _collection-names _master-key]
   ;; ⚠️ #509: THIS ARITY USED TO SUPPLY `pipeline-name` FOR THE DATASET ID.
   ;; It was correct only where the two coincide — true for the demo tenant
   ;; (`norquad-docs`/`norquad-docs`) and false for any tenant whose pipeline is
   ;; not named after its dataset. A convenience whose correctness depends on two
   ;; unrelated identities happening to be equal is not a convenience: it reads
   ;; as an intentional default and silently resolves the wrong node.
   ;;
   ;; Refusing rather than deleting outright, so an out-of-tree caller gets a
   ;; sentence naming the fix rather than an arity error.
   (throw (ex-info (str "track-pipeline-collections! needs an explicit dataset-id. "
                        "The 5-arity used the pipeline name as the dataset id, which is "
                        "only correct when they coincide. Call the 7-arity with the "
                        "durable dataset-id (see digdir.pipeline.executor).")
                   {:tenant tenant
                    :tenant-config-key tenant-config-key
                    :pipeline-name pipeline-name})))
  ([conn tenant _tenant-config-key dataset-id pipeline-name collection-names master-key]
   (doseq [path ["pipeline.storage.docs-collection"
                 "pipeline.storage.chunks-collection"
                 "pipeline.storage.phrases-collection"]]
     (when-not (config-db/get-definition @conn path)
       (config-db/upsert-definition! conn
                                     {:path path
                                      :root :dataset
                                      :value-type :string
                                      :description "Pipeline collection name"
                                      :category :pipelines
                                      :service :docs
                                      :function :storage})))
   (when (seq tenant)
     (let [db @conn
           dataset-base-node (or (config-db/get-config-node db
                                                            (config-db/dataset-base-node-id tenant dataset-id))
                                 (throw (ex-info "Dataset base node not found"
                                                 {:tenant tenant
                                                  :dataset-id dataset-id
                                                  :pipeline-name pipeline-name})))
           value-map {"pipeline.storage.docs-collection" (:docs-collection collection-names)
                      "pipeline.storage.chunks-collection" (:chunks-collection collection-names)
                      "pipeline.storage.phrases-collection" (:phrases-collection collection-names)}]
       (doseq [[path value] value-map]
         (config-db/set-node-value! conn
                                    {:root :dataset
                                     :tenant tenant
                                     :node-id (:config.node/id dataset-base-node)
                                     :path path
                                     :value value
                                     :master-key master-key}))))))

(defn get-or-generate-collection-names
  "Get collection names from pipeline config, or generate if not set.
   Returns the stored names when all three are present, otherwise the names
   `pipeline-collection-names` generates. Never stores anything.

   ⚠️ the stale stored-collection-names issue: IT USED TO STORE WHAT IT GENERATED, and that is why it no longer
   takes a connection. The executor called that arity before every run, which
   persisted generated names that no loader writes to (loaders name by
   `digdir.docs.pipeline.storage/coll-ids`), so a first run that failed left three
   names naming no collection. The executor now records names only after a run
   succeeds, and only the names that run wrote.

   Args:
     pipeline-config - Pipeline configuration map

   Returns: Map with :docs-collection, :chunks-collection, :phrases-collection"
  [pipeline-config]
  (let [stored-names {:docs-collection (:docs-collection pipeline-config)
                      :chunks-collection (:chunks-collection pipeline-config)
                      :phrases-collection (:phrases-collection pipeline-config)}
        all-present? (every? some? (vals stored-names))]
    (if all-present?
      stored-names
      (pipeline-collection-names pipeline-config))))

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
