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

(defn- ts
  "Typesense settings for the tenant this pipeline is running for.

   Resolved per call from `config`, not from a namespace-level value. The old
   `ts-admin` was a load-time `def` with no tenant that fell back to a hardcoded
   tenant list, so a pipeline for one tenant wrote using another tenant's
   credentials (#476). Every function below therefore takes `config` — its
   callers all already had it, and two of them destructured it as `_config` and
   threw it away."
  [config]
  (ts-utils/make-ts-settings {:tenant (:tenant config)}))

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
  "Hash of the `ns`-namespaced config values, used to version collection names.

   ⚠️ THIS RETURNED A CONSTANT UNTIL #501, AND THE BUG IS ONE LINE OF ORDERING.
   It read:

     (extract-ns-from-map (select-keys config [:hash-changer :strategy]) ns)

   `select-keys` ran FIRST, against a config whose keys are NAMESPACED —
   `:chunks/strategy`, `:chunks/hash-changer`. There is no bare `:strategy` or
   `:hash-changer`, so it selected nothing and `extract-ns-from-map` was handed an
   empty map. Every call hashed `{}` and returned the same twelve characters, for
   every tenant, every dataset and every collection kind:

     (sha256-short-hash {}) = ab897fbdedfa

   The tell was visible in the output without running anything: `coll-ids` calls
   this with THREE different `ns` arguments and got ONE suffix back.

   So the suffix — whose entire job is to make a config change land in a NEW
   collection — never versioned anything, and a re-ingest after a chunking change
   silently overwrote the collection the old config had built.

   The fix is to extract the namespace from the config itself, which is what
   `extract-ns-from-map` was always for. ⚠️ COLLECTION NAMES THEREFORE CHANGE, and
   existing corpora must be re-materialised under the new names — that is the
   point of the fix, not a side effect of it."
  [config ns]
  (core/sha256-short-hash (extract-ns-from-map config ns)))

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
  [config coll-name doc]
  (try
    (ts/retrieve-document (ts config) coll-name (:id doc))
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
  [config schema]
  (try
    (t/event! :pipeline/creating-collection {:data {:name (:name schema)}})
    (ts/create-collection! (ts config) schema)
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
  (let [[docs-coll _chunks-coll _phrases-coll :as ids] (coll-ids config)]
    (t/event! :pipeline/creating-stores {:data {:coll-ids ids}})
    (create-collection! config (docs-schema-fn docs-coll))
    (create-collection! config (chunks-schema-fn ids))
    (create-collection! config (phrases-schema-fn ids))
    ids))

;; ============================================================================
;; Document Storage
;; ============================================================================

(defn upsert-document!
  "Upserts a single document into the collection.
   Returns the document on success."
  [config coll-name doc]
  (t/event! :pipeline/upserting-document {:data {:id (:id doc)}})
  (ts/upsert-document! (ts config) coll-name doc))

(defn upsert-documents!
  "Upserts multiple documents into the collection.
   Returns the results on success."
  [config coll-name docs]
  (when (seq docs)
    (t/event! :pipeline/upserting-documents {:data {:count (count docs)}})
    (ts/upsert-documents! (ts config) coll-name docs)))

;; ============================================================================
;; Chunk Storage
;; ============================================================================

(defn prepare-chunks
  "Prepares chunks for storage by selecting only required fields.
   location-key should be :url or :path depending on source type.
   location-transform-fn transforms the location value (e.g., make-relative-url).

   Sets `:id` to the chunk's `:chunk_id` so Typesense upsert keys on a
   stable, content-derived id. Without this, Typesense auto-generates
   a new `:id` on every upsert and the same logical chunk gets inserted
   as a duplicate row each time — discovered 2026-05-26 after re-ingest
   bloated the collection ~2x with byte-identical rows distinguished
   only by their auto-generated numeric ids."
  [chunks location-key location-transform-fn]
  (mapv #(-> %
             (update location-key location-transform-fn)
             (assoc :id (:chunk_id %))
             (select-keys [:id :chunk_id :doc_num :chunk_index :content_markdown :content_length :metadata location-key]))
        chunks))

(defn ensure-chunk-id
  "Defensive helper: ensure a chunk has its Typesense `:id` set to its
   `:chunk_id`. Each pipeline (website / folder / episerver / kudos)
   has its own prepare-chunks-fn that select-keys's its way to a
   storage shape; it's easy to forget to include `:id`, in which case
   Typesense auto-generates one and upsert becomes insert. Applying
   this at the store-chunks! boundary makes the invariant pipeline-
   independent."
  [chunk]
  (cond-> chunk
    (not (:id chunk)) (assoc :id (:chunk_id chunk))))

(defn store-chunks!
  "Stores chunks for a document into the chunks collection.
   Enforces `:id := :chunk_id` so upsert is keyed correctly even if
   the upstream prepare-fn omitted the field."
  [config chunks-coll chunks]
  (when (seq chunks)
    (let [chunks-with-ids (mapv ensure-chunk-id chunks)]
      (t/event! :pipeline/upserting-chunks {:data {:count (count chunks-with-ids)}})
      (ts/upsert-documents! (ts config) chunks-coll chunks-with-ids))))

(defn- ts-id-list
  "Render a seq of alphanumeric IDs as a Typesense filter list:
   `[id1,id2,id3]`. The IDs we use (chunk_id, doc_num) are sha256
   short hashes — all alphanumeric — so no escaping is needed."
  [ids]
  (str "[" (str/join "," ids) "]"))

(defn delete-orphan-chunks!
  "Delete chunks for `doc-num` whose Typesense `:id` is NOT in the
   `current-ids` set. Use after upserting new chunks so that orphan
   rows from previous ingests get removed.

   Filters on `id` (the Typesense document id), not on `chunk_id`,
   because legacy rows have auto-generated numeric ids (since chunks
   were stored without an explicit `:id` field). After the
   prepare-chunks fix that pins `:id := :chunk_id`, the keep-set is
   the chunk_ids and this filter:
     - Excludes new rows (id == chunk_id, in keep-set)
     - Includes legacy auto-id rows (id like \"5802\", not in keep-set)
     - Includes prior-revision rows whose chunk_id is no longer current

   No-op when `current-ids` is empty (defensive: avoid wiping the
   doc's chunks if upstream produced zero)."
  [config chunks-coll doc-num current-ids]
  (when (and (seq current-ids) (not (str/blank? (str doc-num))))
    (let [filter-by (str "doc_num:=" doc-num
                         " && id:!=" (ts-id-list current-ids))]
      (t/event! :pipeline/deleting-orphan-chunks
                {:data {:doc-num doc-num
                        :keep-count (count current-ids)
                        :filter filter-by}})
      (ts/delete-documents! (ts config) chunks-coll {:filter_by filter-by}))))

;; ============================================================================
;; Phrase Storage
;; ============================================================================

(defn extract-phrases
  "Extracts search phrases from chunks into phrase documents.
   Returns a vector of {:id :search_phrase :chunk_id :doc_num} maps.

   `:id` is a deterministic sha256-short-hash of `chunk_id|phrase` so
   the upsert correctly replaces the same (chunk, phrase) pair instead
   of inserting a duplicate row on every ingest. See the same note on
   prepare-chunks for context."
  [chunks doc-num]
  (let [chunks-with-phrases (filter #(seq (:search-phrases %)) chunks)
        chunks-without-phrases (remove #(seq (:search-phrases %)) chunks)]
    (when (seq chunks-without-phrases)
      (t/log! ["Chunks without search phrases:" (count chunks-without-phrases)]))
    (vec (for [chunk chunks-with-phrases
               phrase (:search-phrases chunk)
               :when (and phrase (string? phrase) (not (str/blank? phrase)))]
           {:id (core/sha256-short-hash (str (:chunk_id chunk) "|" phrase))
            :search_phrase phrase
            :chunk_id (:chunk_id chunk)
            :doc_num doc-num}))))

(defn ensure-phrase-id
  "Defensive helper: ensure a phrase has its Typesense `:id` set to a
   deterministic sha256-short-hash of `chunk_id|search_phrase`.
   Same rationale as ensure-chunk-id — without this, Typesense
   auto-generates a numeric id and upsert becomes insert."
  [phrase]
  (cond-> phrase
    (not (:id phrase))
    (assoc :id (core/sha256-short-hash
                (str (:chunk_id phrase) "|" (:search_phrase phrase))))))

(defn store-phrases!
  "Stores search phrases for a document into the phrases collection.
   Enforces a deterministic `:id` per (chunk_id, search_phrase) pair
   so upsert is keyed correctly even if the upstream caller omitted
   the field."
  [config phrases-coll phrases doc-id]
  (t/event! :pipeline/phrase-count {:data {:count (count phrases) :doc-id doc-id}})
  (when (seq phrases)
    (let [phrases-with-ids (mapv ensure-phrase-id phrases)]
      (try
        (ts/upsert-documents! (ts config) phrases-coll phrases-with-ids)
        (catch Exception e
          (t/error! {:id :pipeline/upsert-phrases-error
                     :msg ["Failed to upsert phrases" "doc:" doc-id "count:" (count phrases-with-ids)]}
                    e)
          (t/log! ["Sample phrases:" (take 3 phrases-with-ids)])
          (throw e))))))

(defn delete-orphan-phrases!
  "Delete phrases for `doc-num` whose Typesense `:id` is NOT in
   `current-ids`. After the extract-phrases fix that pins `:id` to
   a deterministic hash of (chunk_id, phrase), the keep-set is those
   hash values and this filter:
     - Excludes new rows (id == hash(chunk_id, phrase), in keep-set)
     - Includes legacy auto-id rows (id like \"13213\", not in keep-set)
     - Includes prior-revision rows for chunks no longer present
       (because their hash isn't in the new keep-set)

   No-op when `current-ids` is empty."
  [config phrases-coll doc-num current-ids]
  (when (and (seq current-ids) (not (str/blank? (str doc-num))))
    (let [filter-by (str "doc_num:=" doc-num
                         " && id:!=" (ts-id-list current-ids))]
      (t/event! :pipeline/deleting-orphan-phrases
                {:data {:doc-num doc-num
                        :keep-count (count current-ids)
                        :filter filter-by}})
      (ts/delete-documents! (ts config) phrases-coll {:filter_by filter-by}))))

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

   Always upserts — docs / chunks / phrases are all upserted unconditionally.
   The previous skip-if-doc-exists branch produced bugs (docs present but
   chunks / phrases missing) and prevented schema-evolution backfills like
   adding new doc-level fields. Upsert is the right strategy in all cases;
   any rate-limiting belongs in the underlying upsert primitives, not here.

   After upserting, deletes orphan chunks / phrases (those whose
   chunk_id is no longer in the current set for this doc). Necessary
   because chunk_id = sha256(content_markdown), so when source content
   changes between ingests the new chunks get new IDs and the old ones
   linger as duplicates unless explicitly removed. The window between
   upsert and delete is sub-second per doc; during that window a query
   may see both revisions of one doc's chunks.

   Returns nil on success, throws on error."
  [config doc prepare-doc-fn prepare-chunks-fn]
  (let [[docs-coll chunks-coll phrases-coll] (coll-ids config)
        doc-num (:doc_num doc)]
    (try
      ;; Store document
      (upsert-document! config docs-coll (prepare-doc-fn config (assoc doc :total_chunks (count (:chunks doc)))))

      ;; Store chunks + delete orphan chunks for this doc.
      ;; Apply ensure-chunk-id to the prepared chunks BEFORE both
      ;; storage and keep-set extraction so the deleted-orphans
      ;; filter exactly matches the upserted :id values, regardless
      ;; of whether the upstream prepare-fn included :id.
      (let [prepared-chunks (mapv ensure-chunk-id (prepare-chunks-fn (:chunks doc)))
            current-chunk-ids (mapv :id prepared-chunks)]
        (store-chunks! config chunks-coll prepared-chunks)
        (delete-orphan-chunks! config chunks-coll doc-num current-chunk-ids)

        ;; Store phrases + delete orphan phrases. Same defense-in-depth:
        ;; pass through ensure-phrase-id before extracting ids.
        (let [phrases (mapv ensure-phrase-id (extract-phrases (:chunks doc) doc-num))
              current-phrase-ids (mapv :id phrases)]
          (store-phrases! config phrases-coll phrases (:id doc))
          (delete-orphan-phrases! config phrases-coll doc-num current-phrase-ids)))

      (core/say "Stored document")
      (t/event! :pipeline/document-stored {:data {:id (:id doc)}})

      (catch Exception e
        (t/error! {:id :pipeline/store-document-error
                   :msg ["Failed to store document" (:id doc)]}
                  e)
        (throw e)))))

;; ============================================================================
;; Backfill Functions
;; ============================================================================

(defn backfill-content-length!
  "Backfill content_length on chunks that are missing it.
   Iterates through all chunks in the collection, computes content_length
   from content_markdown, and upserts the updated chunks.
   Returns {:updated count :skipped count :errors count}."
  [chunks-coll opts]
  (t/event! :pipeline/backfill-content-length-start {:data {:collection chunks-coll}})
  (let [ts-settings (ts-utils/make-ts-settings opts)
        per-page 250
        stats (atom {:updated 0 :skipped 0 :errors 0})]
    (loop [page 1]
      (let [result (ts/search ts-settings chunks-coll
                              {:q "*"
                               :query_by "chunk_id"
                               :include_fields "id,chunk_id,content_markdown,content_length"
                               :per_page per-page
                               :page page})
            hits (:hits result)
            total-found (:found result)]
        (doseq [hit hits]
          (let [doc (:document hit)
                existing-length (:content_length doc)]
            (if (some? existing-length)
              (swap! stats update :skipped inc)
              (let [content (or (:content_markdown doc) "")
                    computed-length (count content)]
                (try
                  (ts/upsert-document! ts-settings chunks-coll
                                       {:id (:id doc)
                                        :content_length computed-length})
                  (swap! stats update :updated inc)
                  (catch Exception e
                    (t/error! {:id :pipeline/backfill-content-length-error
                               :msg ["Failed to update chunk" (:id doc)]} e)
                    (swap! stats update :errors inc)))))))
        (let [fetched-so-far (* page per-page)]
          (when (and (seq hits) (< fetched-so-far total-found))
            (recur (inc page))))))
    (let [final-stats @stats]
      (t/event! :pipeline/backfill-content-length-done {:data final-stats})
      final-stats)))

(defn backfill-total-chunks!
  "Backfill total_chunks on documents that are missing it.
   For each document, counts the chunks in the chunks collection and updates.
   Returns {:updated count :skipped count :errors count}."
  [docs-coll chunks-coll opts]
  (t/event! :pipeline/backfill-total-chunks-start {:data {:docs-coll docs-coll :chunks-coll chunks-coll}})
  (let [ts-settings (ts-utils/make-ts-settings opts)
        per-page 250
        stats (atom {:updated 0 :skipped 0 :errors 0})]
    (loop [page 1]
      (let [result (ts/search ts-settings docs-coll
                              {:q "*"
                               :query_by "doc_num"
                               :include_fields "id,doc_num,total_chunks"
                               :per_page per-page
                               :page page})
            hits (:hits result)
            total-found (:found result)]
        (doseq [hit hits]
          (let [doc (:document hit)
                existing-count (:total_chunks doc)]
            (if (some? existing-count)
              (swap! stats update :skipped inc)
              (try
                (let [chunk-result (ts/search ts-settings chunks-coll
                                             {:q "*"
                                              :query_by "chunk_id"
                                              :filter_by (str "doc_num:=`" (:doc_num doc) "`")
                                              :per_page 0
                                              :page 1})
                      chunk-count (or (:found chunk-result) 0)]
                  (ts/upsert-document! ts-settings docs-coll
                                       {:id (:id doc)
                                        :total_chunks chunk-count})
                  (swap! stats update :updated inc))
                (catch Exception e
                  (t/error! {:id :pipeline/backfill-total-chunks-error
                             :msg ["Failed to update doc" (:id doc)]} e)
                  (swap! stats update :errors inc))))))
        (let [fetched-so-far (* page per-page)]
          (when (and (seq hits) (< fetched-so-far total-found))
            (recur (inc page))))))
    (let [final-stats @stats]
      (t/event! :pipeline/backfill-total-chunks-done {:data final-stats})
      final-stats)))
