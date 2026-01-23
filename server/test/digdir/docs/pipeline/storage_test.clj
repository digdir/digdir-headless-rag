(ns digdir.docs.pipeline.storage-test
  "Tests for digdir.docs.pipeline.storage - TypeSense storage operations."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [digdir.docs.pipeline.storage :as storage]
            [typesense.client :as ts]))

;; ============================================================================
;; Configuration Utilities Tests
;; ============================================================================

(deftest extract-ns-from-map-basic
  (testing "Extracts keys with matching namespace"
    (let [m {:search-phrases/model "gpt-4"
             :search-phrases/prompt "test"
             :chunks/minimum-length 100}
          result (storage/extract-ns-from-map m "search-phrases")]
      (is (= 2 (count result)))
      (is (contains? result :search-phrases/model))
      (is (contains? result :search-phrases/prompt))
      (is (not (contains? result :chunks/minimum-length))))))

(deftest extract-ns-from-map-nested
  (testing "Extracts nested namespace keys"
    (let [m {:store/coll-prefix "test_"
             :store.backup/enabled true
             :other/key "value"}
          result (storage/extract-ns-from-map m "store")]
      (is (contains? result :store/coll-prefix))
      (is (contains? result :store.backup/enabled)))))

(deftest extract-ns-from-map-empty
  (testing "Returns empty map when no matches"
    (let [m {:chunks/minimum-length 100}
          result (storage/extract-ns-from-map m "search-phrases")]
      (is (empty? result)))))

;; ============================================================================
;; config-hash Tests
;; ============================================================================

(deftest config-hash-deterministic
  (testing "Same config produces same hash"
    (let [config {:hash-changer 1 :strategy :header-based}
          hash1 (storage/config-hash config "documents")
          hash2 (storage/config-hash config "documents")]
      (is (= hash1 hash2)))))

(deftest config-hash-is-string
  (testing "Returns a string hash"
    (let [config {:store/coll-prefix "test_"}
          hash (storage/config-hash config "documents")]
      (is (string? hash))
      (is (= 12 (count hash))))))

;; ============================================================================
;; coll-ids Tests
;; ============================================================================

(deftest coll-ids-returns-three-collections
  (testing "Returns three collection IDs"
    (let [config {:store/coll-prefix "website_"}
          ids (storage/coll-ids config)]
      (is (= 3 (count ids)))
      (is (every? string? ids)))))

(deftest coll-ids-includes-prefix
  (testing "Collection IDs include prefix"
    (let [config {:store/coll-prefix "test_"}
          [docs chunks phrases] (storage/coll-ids config)]
      (is (str/starts-with? docs "test_"))
      (is (str/starts-with? chunks "test_"))
      (is (str/starts-with? phrases "test_")))))

(deftest coll-ids-includes-type-suffix
  (testing "Collection IDs include type names"
    (let [config {:store/coll-prefix "test_"}
          [docs chunks phrases] (storage/coll-ids config)]
      (is (str/includes? docs "documents_"))
      (is (str/includes? chunks "chunks_"))
      (is (str/includes? phrases "phrases_")))))

(deftest coll-ids-different-prefixes
  (testing "Different prefixes produce different IDs"
    (let [config1 {:store/coll-prefix "website_"}
          config2 {:store/coll-prefix "folder_"}
          ids1 (storage/coll-ids config1)
          ids2 (storage/coll-ids config2)]
      (is (not= ids1 ids2)))))

;; ============================================================================
;; document-inserted? Tests (with mocks)
;; ============================================================================

(deftest document-inserted-returns-true-when-exists
  (testing "Returns true when document exists"
    (with-redefs [ts/retrieve-document (fn [_ _ _] {:id "exists"})]
      (is (true? (storage/document-inserted? "test_coll" {:id "exists"}))))))

(deftest document-inserted-returns-false-when-missing
  (testing "Returns false when document doesn't exist"
    (with-redefs [ts/retrieve-document (fn [_ _ _]
                                         (throw (ex-info "Not found" {})))]
      (is (false? (storage/document-inserted? "test_coll" {:id "missing"}))))))

;; ============================================================================
;; prepare-chunks Tests
;; ============================================================================

(deftest prepare-chunks-transforms-location
  (testing "Transforms location using provided function"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :chunk_index 0
                   :content_markdown "content"
                   :metadata "{}"
                   :url "http://localhost:1313/docs/test.md"}]
          transform-fn #(str/replace % "http://localhost:1313" "")
          result (storage/prepare-chunks chunks :url transform-fn)]
      (is (= "/docs/test.md" (:url (first result)))))))

(deftest prepare-chunks-selects-fields
  (testing "Selects only required fields"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :chunk_index 0
                   :content_markdown "content"
                   :metadata "{}"
                   :url "/test.md"
                   :extra-field "should-be-removed"}]
          result (storage/prepare-chunks chunks :url identity)]
      (is (not (contains? (first result) :extra-field))))))

;; ============================================================================
;; extract-phrases Tests
;; ============================================================================

(deftest extract-phrases-basic
  (testing "Extracts phrases from chunks"
    (let [chunks [{:chunk_id "c1"
                   :search-phrases ["phrase1" "phrase2"]}
                  {:chunk_id "c2"
                   :search-phrases ["phrase3"]}]
          phrases (storage/extract-phrases chunks "doc-1")]
      (is (= 3 (count phrases)))
      (is (every? :search_phrase phrases))
      (is (every? :chunk_id phrases))
      (is (every? #(= "doc-1" (:doc_num %)) phrases)))))

(deftest extract-phrases-filters-blank
  (testing "Filters out blank phrases"
    (let [chunks [{:chunk_id "c1"
                   :search-phrases ["valid" "" "  " nil "another"]}]
          phrases (storage/extract-phrases chunks "doc-1")]
      (is (= 2 (count phrases)))
      (is (= #{"valid" "another"} (set (map :search_phrase phrases)))))))

(deftest extract-phrases-handles-empty-chunks
  (testing "Handles chunks with no phrases"
    (let [chunks [{:chunk_id "c1" :search-phrases []}
                  {:chunk_id "c2"}]
          phrases (storage/extract-phrases chunks "doc-1")]
      (is (empty? phrases)))))

;; ============================================================================
;; create-collection! Tests (with mocks)
;; ============================================================================

(deftest create-collection-success
  (testing "Creates collection successfully"
    (let [created (atom nil)]
      (with-redefs [ts/create-collection! (fn [_ schema]
                                            (reset! created schema)
                                            schema)]
        (let [schema {:name "test_coll" :fields []}
              result (storage/create-collection! schema)]
          (is (= schema result))
          (is (= schema @created)))))))

(deftest create-collection-already-exists
  (testing "Returns :already-exists for conflict"
    (with-redefs [ts/create-collection!
                  (fn [_ _]
                    (throw (ex-info "Conflict"
                                    {:type :typesense.client/conflict})))]
      (let [result (storage/create-collection! {:name "test_coll"})]
        (is (= :already-exists result))))))

(deftest create-collection-other-error-throws
  (testing "Throws for other errors"
    (with-redefs [ts/create-collection!
                  (fn [_ _]
                    (throw (ex-info "Server error"
                                    {:type :typesense.client/server-error})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (storage/create-collection! {:name "test_coll"}))))))
