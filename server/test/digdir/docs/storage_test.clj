(ns digdir.docs.storage-test
  "Tests for TypeSense storage operations.
   Uses mocked TypeSense client."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [digdir.docs.website :as website]
            [digdir.docs.folder :as folder]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

(def ^:private cfg
  "#476: the storage functions take the pipeline config so they resolve
   Typesense for the tenant the pipeline belongs to, instead of a hidden
   namespace-level default."
  {:tenant "test-tenant"})

;; ============================================================================
;; Mock TypeSense State
;; ============================================================================

(def ^:dynamic *mock-ts-store* nil)

(defn with-mock-typesense [f]
  (binding [*mock-ts-store* (atom {:collections {} :documents {}})]
    (f)))

(defn- stub-resolver
  "These tests exercise storage LOGIC, not config resolution. #476 made the
   storage functions resolve Typesense per tenant, so without this they would
   fail on `cfg` having no platform tree — a fact about the test DB, not about
   the code under test."
  [f]
  (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub:8108" :key "stub"})]
    (f)))

;; Composed, not replaced: two separate `use-fixtures :each` calls would leave
;; only the second in effect and silently drop the mock Typesense store.
(use-fixtures :each with-mock-typesense stub-resolver)

;; ============================================================================
;; Schema Tests
;; ============================================================================

(deftest website-docs-schema-structure
  (testing "Website docs schema has required fields"
    (let [schema (website/website-docs-schema "test_docs")]
      (is (= "test_docs" (:name schema)))
      (is (vector? (:fields schema)))
      (let [field-names (set (map :name (:fields schema)))]
        (is (contains? field-names "id"))
        (is (contains? field-names "title"))
        (is (contains? field-names "url"))
        (is (contains? field-names "type"))))))

(deftest website-docs-schema-field-types
  (testing "Fields have correct types"
    (let [schema (website/website-docs-schema "test_docs")
          fields-by-name (into {} (map (juxt :name identity) (:fields schema)))]
      (is (= "string" (get-in fields-by-name ["id" :type])))
      (is (= "string" (get-in fields-by-name ["title" :type])))
      (is (= "string" (get-in fields-by-name ["url" :type]))))))

(deftest website-chunks-schema-structure
  (testing "Website chunks schema has required fields"
    (let [coll-ids ["test_docs" "test_chunks" "test_phrases"]
          schema (website/website-chunks-schema coll-ids)]
      (is (= "test_chunks" (:name schema)))
      (let [field-names (set (map :name (:fields schema)))]
        (is (contains? field-names "chunk_id"))
        (is (contains? field-names "doc_num"))
        (is (contains? field-names "chunk_index"))
        (is (contains? field-names "content_markdown"))))))

(deftest website-chunks-schema-reference
  (testing "Chunks schema has reference to docs collection"
    (let [coll-ids ["test_docs" "test_chunks" "test_phrases"]
          schema (website/website-chunks-schema coll-ids)
          doc-num-field (first (filter #(= "doc_num" (:name %)) (:fields schema)))]
      (is (some? doc-num-field))
      (is (= "test_docs.doc_num" (:reference doc-num-field))))))

(deftest website-phrases-schema-structure
  (testing "Website phrases schema has required fields"
    (let [coll-ids ["test_docs" "test_chunks" "test_phrases"]
          schema (website/website-phrases-schema coll-ids)]
      (is (= "test_phrases" (:name schema)))
      (let [field-names (set (map :name (:fields schema)))]
        (is (contains? field-names "chunk_id"))
        (is (contains? field-names "doc_num"))
        (is (contains? field-names "search_phrase"))
        (is (contains? field-names "phrase_vec"))))))

(deftest website-phrases-schema-embedding
  (testing "Phrases schema has embedding configuration"
    (let [coll-ids ["test_docs" "test_chunks" "test_phrases"]
          schema (website/website-phrases-schema coll-ids)
          phrase-vec-field (first (filter #(= "phrase_vec" (:name %)) (:fields schema)))]
      (is (some? phrase-vec-field))
      (is (contains? phrase-vec-field :embed))
      (is (= "float[]" (:type phrase-vec-field))))))

;; ============================================================================
;; Folder Schema Tests
;; ============================================================================

(deftest folder-docs-schema-structure
  (testing "Folder docs schema has required fields"
    (let [schema (folder/folder-docs-schema "test_docs")]
      (is (= "test_docs" (:name schema)))
      (let [field-names (set (map :name (:fields schema)))]
        (is (contains? field-names "id"))
        (is (contains? field-names "title"))
        (is (contains? field-names "path"))
        (is (contains? field-names "type"))))))

;; ============================================================================
;; prepare-website-doc Tests
;; ============================================================================

(deftest prepare-website-doc-selects-fields
  (testing "Prepares document with only required fields"
    (let [config {:base-url "http://localhost:1313"}
          doc {:id "doc-1"
               :doc_num "doc-1"
               :title "Test Doc"
               :url "http://localhost:1313/docs/test.md"
               :lastmod "2024-01-15"
               :type "website"
               :markdown "# Content"
               :chunks []}
          prepared (website/prepare-website-doc config doc)]
      (is (contains? prepared :id))
      (is (contains? prepared :doc_num))
      (is (contains? prepared :title))
      (is (contains? prepared :url))
      (is (contains? prepared :type))
      (is (not (contains? prepared :markdown)))
      (is (not (contains? prepared :chunks))))))

(deftest prepare-website-doc-makes-url-relative
  (testing "Converts absolute URL to relative"
    (let [config {:base-url "http://localhost:1313"}
          doc {:id "doc-1"
               :doc_num "doc-1"
               :title "Test"
               :url "http://localhost:1313/docs/test.md"
               :type "website"}
          prepared (website/prepare-website-doc config doc)]
      (is (= "/docs/test.md" (:url prepared))))))

;; ============================================================================
;; document-inserted? Tests (with mocks)
;; ============================================================================

(deftest document-inserted-returns-true-when-exists
  (testing "Returns true when document exists in collection"
    (with-redefs [ts/retrieve-document (fn [_ _ _] {:id "exists"})]
      (is (true? (website/document-inserted? {} "test_coll" {:id "exists"}))))))

(deftest document-inserted-returns-false-when-missing
  (testing "Returns false when document doesn't exist"
    (with-redefs [ts/retrieve-document (fn [_ _ _]
                                         (throw (ex-info "Not found" {:type :typesense.client/not-found})))]
      (is (false? (website/document-inserted? {} "test_coll" {:id "missing"}))))))

;; ============================================================================
;; Collection Creation Tests (with mocks)
;; ============================================================================

(deftest create-website-docs-coll-success
  (testing "Creates collection successfully"
    (let [created-schema (atom nil)]
      (with-redefs [ts/create-collection! (fn [_ schema]
                                            (reset! created-schema schema)
                                            schema)]
        (let [result (website/create-website-docs-coll cfg "test_docs")]
          (is (= "test_docs" (:name result)))
          (is (= "test_docs" (:name @created-schema))))))))

(deftest create-website-docs-coll-already-exists
  (testing "Returns :already-exists when collection exists"
    (with-redefs [ts/create-collection! (fn [_ _]
                                          (throw (ex-info "Conflict" {:type :typesense.client/conflict})))]
      (let [result (website/create-website-docs-coll cfg "test_docs")]
        (is (= :already-exists result))))))

;; ============================================================================
;; Integration: Collection workflow
;; ============================================================================

(deftest collection-ids-workflow
  (testing "Collection IDs can be used to create collections"
    (let [config {:store/coll-prefix "test_"}
          [docs-coll chunks-coll phrases-coll] (website/coll-ids config)]
      (is (string? docs-coll))
      (is (string? chunks-coll))
      (is (string? phrases-coll))
      ;; Each should have the prefix
      (is (str/starts-with? docs-coll "test_"))
      (is (str/starts-with? chunks-coll "test_"))
      (is (str/starts-with? phrases-coll "test_")))))

(deftest schema-references-workflow
  (testing "Schemas reference correct collections"
    (let [config {:store/coll-prefix "test_"}
          coll-ids (website/coll-ids config)
          [docs-coll _chunks-coll _phrases-coll] coll-ids
          chunks-schema (website/website-chunks-schema coll-ids)
          doc-num-field (first (filter #(= "doc_num" (:name %)) (:fields chunks-schema)))]
      (is (= (str docs-coll ".doc_num") (:reference doc-num-field))))))
