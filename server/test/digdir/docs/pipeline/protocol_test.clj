(ns digdir.docs.pipeline.protocol-test
  "Tests for digdir.docs.pipeline.protocol - DocumentSource protocol."
  (:require [clojure.test :refer [deftest testing is ]]
            [digdir.docs.pipeline.protocol :as proto]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; header-based-chunks Tests
;; ============================================================================

(deftest header-based-chunks-basic
  (testing "Splits markdown by headers"
    (let [config fixtures/sample-pipeline-config
          chunks (proto/header-based-chunks config fixtures/sample-markdown-simple)]
      (is (vector? chunks))
      (is (pos? (count chunks)))
      (is (every? :content_markdown chunks)))))

(deftest header-based-chunks-does-not-assign-ids
  (testing "Chunking yields no chunk_id — it cannot know the document"
    ;; Ids used to be hashed from content here, which collided across
    ;; documents and dropped rows on upsert (#72). chunk-document assigns
    ;; them instead, where :doc_num is in hand.
    (let [config fixtures/sample-pipeline-config
          chunks (proto/header-based-chunks config fixtures/sample-markdown-simple)]
      (is (every? #(nil? (:chunk_id %)) chunks)))))

(deftest chunk-document-generates-ids
  (testing "Generates 12-character chunk IDs"
    (let [config fixtures/sample-pipeline-config
          doc {:doc_num "doc-1"
               :content_markdown fixtures/sample-markdown-simple
               :url "/a"}
          chunks (:chunks (proto/chunk-document config doc :url))]
      (is (pos? (count chunks)))
      (is (every? #(= 12 (count (:chunk_id %))) chunks)))))

(deftest header-based-chunks-includes-metadata
  (testing "Includes metadata as string"
    (let [config fixtures/sample-pipeline-config
          chunks (proto/header-based-chunks config fixtures/sample-markdown-simple)]
      (is (every? :metadata chunks))
      (is (every? #(string? (:metadata %)) chunks)))))

;; ============================================================================
;; chunk-document Tests
;; ============================================================================

(deftest chunk-document-basic
  (testing "Chunks document and adds to :chunks key"
    (let [config fixtures/sample-pipeline-config
          doc {:id "doc-1"
               :doc_num "doc-1"
               :url "/test.md"
               :content_markdown fixtures/sample-markdown-simple}
          result (proto/chunk-document config doc :url)]
      (is (contains? result :chunks))
      (is (vector? (:chunks result)))
      (is (pos? (count (:chunks result)))))))

(deftest chunk-document-adds-doc-info
  (testing "Each chunk gets doc_num, chunk_index, content_length, and location"
    (let [config fixtures/sample-pipeline-config
          doc {:id "doc-1"
               :doc_num "doc-1"
               :url "/test.md"
               :content_markdown fixtures/sample-markdown-simple}
          result (proto/chunk-document config doc :url)
          chunk (first (:chunks result))]
      (is (= "doc-1" (:doc_num chunk)))
      (is (= 0 (:chunk_index chunk)))
      (is (= "/test.md" (:url chunk)))
      (is (integer? (:content_length chunk)))
      (is (pos? (:content_length chunk)))
      (is (= (count (:content_markdown chunk)) (:content_length chunk))))))

;; Note: chunk-document filtering by length is tested via chunking-test.clj

(deftest chunk-document-with-path-key
  (testing "Works with :path location key"
    (let [config fixtures/sample-pipeline-config
          doc {:id "doc-1"
               :doc_num "doc-1"
               :path "/docs/test.md"
               :content_markdown fixtures/sample-markdown-simple}
          result (proto/chunk-document config doc :path)
          chunk (first (:chunks result))]
      (is (= "/docs/test.md" (:path chunk))))))

;; ============================================================================
;; Protocol Definition Tests
;; ============================================================================

(deftest protocol-exists
  (testing "DocumentSource protocol is defined"
    (is (some? proto/DocumentSource))))

;; ============================================================================
;; Protocol Implementation Helper Tests
;; ============================================================================

(defrecord TestDocumentSource []
  proto/DocumentSource
  (source-name [_] :test-source)
  (fetch-entries [_ _config] nil)
  (entry-to-doc [_ _entry] {:id "test" :doc_num "test" :type "test"})
  (fetch-content [_ _config _entry] nil)
  (docs-schema [_ coll-name] {:name coll-name :fields []})
  (chunks-schema [_ [_ chunks-coll _]] {:name chunks-coll :fields []})
  (phrases-schema [_ [_ _ phrases-coll]] {:name phrases-coll :fields []})
  (prepare-doc-for-storage [_ _config doc] (select-keys doc [:id :type]))
  (prepare-chunks-for-storage [_ _config chunks] chunks)
  (location-key [_] :url))

(deftest protocol-implementation-works
  (testing "Can implement DocumentSource protocol"
    (let [source (->TestDocumentSource)]
      (is (= :test-source (proto/source-name source)))
      (is (= :url (proto/location-key source)))
      (is (= {:name "test_docs" :fields []}
             (proto/docs-schema source "test_docs"))))))

(deftest protocol-entry-to-doc
  (testing "entry-to-doc creates document structure"
    (let [source (->TestDocumentSource)
          entry {:loc "http://example.com/test.md"}
          doc (proto/entry-to-doc source entry)]
      (is (= "test" (:id doc)))
      (is (= "test" (:doc_num doc)))
      (is (= "test" (:type doc))))))

(deftest protocol-prepare-doc-for-storage
  (testing "prepare-doc-for-storage selects fields"
    (let [source (->TestDocumentSource)
          doc {:id "1" :type "test" :extra "removed"}
          prepared (proto/prepare-doc-for-storage source {} doc)]
      (is (= {:id "1" :type "test"} prepared)))))
