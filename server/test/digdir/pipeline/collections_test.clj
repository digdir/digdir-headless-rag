(ns digdir.pipeline.collections-test
  "Tests for pipeline collection name generation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.pipeline.collections :as collections]))

;; =============================================================================
;; Collection Name Generation Tests
;; =============================================================================

(deftest test-pipeline-config-hash
  (testing "Config hashing is deterministic"
    (let [config1 {:source-type :kudos :chunk-strategy :semantic}
          config2 {:source-type :kudos :chunk-strategy :semantic}
          hash1 (collections/pipeline-config-hash config1)
          hash2 (collections/pipeline-config-hash config2)]
      (is (= hash1 hash2))
      (is (string? hash1))
      (is (= 12 (count hash1)))))

  (testing "Different configs produce different hashes"
    (let [config1 {:source-type :kudos :chunk-strategy :semantic}
          config2 {:source-type :kudos :chunk-strategy :markdown}
          hash1 (collections/pipeline-config-hash config1)
          hash2 (collections/pipeline-config-hash config2)]
      (is (not= hash1 hash2)))))

(deftest test-pipeline-collection-names
  (testing "Collection names with custom prefix"
    (let [config {:collection-prefix "prod_main_"
                  :source-type :kudos
                  :chunk-strategy :semantic}
          names (collections/pipeline-collection-names config)]

      (is (map? names))
      (is (contains? names :docs-collection))
      (is (contains? names :chunks-collection))
      (is (contains? names :phrases-collection))

      (is (clojure.string/starts-with? (:docs-collection names) "prod_main_documents_"))
      (is (clojure.string/starts-with? (:chunks-collection names) "prod_main_chunks_"))
      (is (clojure.string/starts-with? (:phrases-collection names) "prod_main_phrases_"))))

  (testing "Collection names with auto-generated prefix from pipeline name"
    (let [config {:pipeline-name "my-pipeline"
                  :source-type :website
                  :chunk-strategy :header-based}
          names (collections/pipeline-collection-names config)]

      (is (clojure.string/starts-with? (:docs-collection names) "my_pipeline_documents_"))
      (is (clojure.string/starts-with? (:chunks-collection names) "my_pipeline_chunks_"))
      (is (clojure.string/starts-with? (:phrases-collection names) "my_pipeline_phrases_"))))

  (testing "Collection names with default prefix"
    (let [config {:source-type :folder
                  :chunk-strategy :semantic}
          names (collections/pipeline-collection-names config)]

      (is (clojure.string/starts-with? (:docs-collection names) "pipeline_documents_"))
      (is (clojure.string/starts-with? (:chunks-collection names) "pipeline_chunks_"))
      (is (clojure.string/starts-with? (:phrases-collection names) "pipeline_phrases_")))))

(deftest test-pipeline-collection-names-vec
  (testing "Collection names as vector"
    (let [config {:collection-prefix "test_"
                  :source-type :kudos
                  :chunk-strategy :semantic}
          names (collections/pipeline-collection-names-vec config)]

      (is (vector? names))
      (is (= 3 (count names)))
      (is (every? string? names))
      (is (every? #(clojure.string/starts-with? % "test_") names)))))

(deftest test-config-hash-includes-relevant-fields
  (testing "Hash changes when relevant fields change"
    (let [base-config {:source-type :kudos
                       :chunk-strategy :semantic
                       :chunk-minimum-length 100
                       :chunk-maximum-length 2000
                       :search-phrases-model "gpt-4o"}

          ;; Change source type
          config-diff-source (assoc base-config :source-type :website)
          ;; Change chunk strategy
          config-diff-chunk (assoc base-config :chunk-strategy :header-based)
          ;; Change model
          config-diff-model (assoc base-config :search-phrases-model "gpt-3.5-turbo")
          ;; Change irrelevant field (should NOT change hash)
          config-diff-irrelevant (assoc base-config :name "Different Name")

          base-hash (collections/pipeline-config-hash base-config)]

      ;; Relevant changes should produce different hashes
      (is (not= base-hash (collections/pipeline-config-hash config-diff-source)))
      (is (not= base-hash (collections/pipeline-config-hash config-diff-chunk)))
      (is (not= base-hash (collections/pipeline-config-hash config-diff-model)))

      ;; Irrelevant changes should NOT change hash
      (is (= base-hash (collections/pipeline-config-hash config-diff-irrelevant))))))

(deftest test-get-or-generate-collection-names
  (testing "Use stored collection names if present"
    (let [config {:docs-collection "existing_docs_abc123"
                  :chunks-collection "existing_chunks_abc123"
                  :phrases-collection "existing_phrases_abc123"}
          names (collections/get-or-generate-collection-names config)]

      (is (= "existing_docs_abc123" (:docs-collection names)))
      (is (= "existing_chunks_abc123" (:chunks-collection names)))
      (is (= "existing_phrases_abc123" (:phrases-collection names)))))

  (testing "Generate new names if not stored"
    (let [config {:collection-prefix "test_"
                  :source-type :kudos
                  :chunk-strategy :semantic}
          names (collections/get-or-generate-collection-names config)]

      (is (clojure.string/starts-with? (:docs-collection names) "test_documents_"))
      (is (clojure.string/starts-with? (:chunks-collection names) "test_chunks_"))
      (is (clojure.string/starts-with? (:phrases-collection names) "test_phrases_"))))

  (testing "Generate new names if only partially stored"
    (let [config {:docs-collection "existing_docs_abc123"
                  ;; Missing chunks and phrases
                  :collection-prefix "test_"
                  :source-type :kudos
                  :chunk-strategy :semantic}
          names (collections/get-or-generate-collection-names config)]

      ;; Should generate completely new set (not mix stored and generated)
      (is (clojure.string/starts-with? (:docs-collection names) "test_documents_"))
      (is (clojure.string/starts-with? (:chunks-collection names) "test_chunks_"))
      (is (clojure.string/starts-with? (:phrases-collection names) "test_phrases_")))))
