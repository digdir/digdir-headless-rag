(ns digdir.docs.pipeline.storage-test
  "Tests for digdir.docs.pipeline.storage - TypeSense storage operations."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

(defn- stub-resolver
  "These tests exercise storage LOGIC, not config resolution. #476 made the
   storage functions resolve Typesense per tenant, so without this they would
   fail on `cfg` having no platform tree — which is a fact about the test DB,
   not about the code under test."
  [f]
  (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub:8108" :key "stub"})]
    (f)))

(use-fixtures :each stub-resolver)

(def ^:private cfg
  "#476: the storage functions take the pipeline config so they resolve
   Typesense for the tenant the pipeline belongs to, instead of a hidden
   namespace-level default."
  {:tenant "test-tenant"})

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
      (is (true? (storage/document-inserted? cfg "test_coll" {:id "exists"}))))))

(deftest document-inserted-returns-false-when-missing
  (testing "Returns false when document doesn't exist"
    (with-redefs [ts/retrieve-document (fn [_ _ _]
                                         (throw (ex-info "Not found" {})))]
      (is (false? (storage/document-inserted? cfg "test_coll" {:id "missing"}))))))

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
  (testing "Selects only required fields including content_length"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :chunk_index 0
                   :content_markdown "content"
                   :content_length 7
                   :metadata "{}"
                   :url "/test.md"
                   :extra-field "should-be-removed"}]
          result (storage/prepare-chunks chunks :url identity)]
      (is (not (contains? (first result) :extra-field)))
      (is (= 7 (:content_length (first result)))))))

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
              result (storage/create-collection! cfg schema)]
          (is (= schema result))
          (is (= schema @created)))))))

(deftest create-collection-already-exists
  (testing "Returns :already-exists for conflict"
    (with-redefs [ts/create-collection!
                  (fn [_ _]
                    (throw (ex-info "Conflict"
                                    {:type :typesense.client/conflict})))]
      (let [result (storage/create-collection! cfg {:name "test_coll"})]
        (is (= :already-exists result))))))

(deftest create-collection-other-error-throws
  (testing "Throws for other errors"
    (with-redefs [ts/create-collection!
                  (fn [_ _]
                    (throw (ex-info "Server error"
                                    {:type :typesense.client/server-error})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (storage/create-collection! cfg {:name "test_coll"}))))))

;; ============================================================================
;; Orphan-cleanup Tests
;; ============================================================================

(deftest delete-orphan-chunks-builds-correct-filter
  (testing "delete-orphan-chunks! issues delete-documents! filtered on :id (catches legacy auto-id rows)"
    (let [captured (atom nil)]
      (with-redefs [ts/delete-documents! (fn [_settings coll opts]
                                           (reset! captured {:coll coll :opts opts})
                                           {:num_deleted 3})]
        (storage/delete-orphan-chunks! cfg "chunks_coll" "doc-A" ["c1" "c2" "c3"])
        (is (= "chunks_coll" (:coll @captured)))
        (is (= "doc_num:=doc-A && id:!=[c1,c2,c3]"
               (get-in @captured [:opts :filter_by])))))))

(deftest delete-orphan-chunks-noop-on-empty-ids
  (testing "delete-orphan-chunks! is a no-op when current-chunk-ids is empty"
    (let [called? (atom false)]
      (with-redefs [ts/delete-documents! (fn [& _] (reset! called? true) nil)]
        (storage/delete-orphan-chunks! cfg "chunks_coll" "doc-A" [])
        (storage/delete-orphan-chunks! cfg "chunks_coll" "doc-A" nil)
        (is (false? @called?)
            "must not issue a delete when keep-set is empty — would wipe the doc's chunks")))))

(deftest delete-orphan-chunks-noop-on-blank-doc-num
  (testing "delete-orphan-chunks! is a no-op when doc-num is blank"
    (let [called? (atom false)]
      (with-redefs [ts/delete-documents! (fn [& _] (reset! called? true) nil)]
        (storage/delete-orphan-chunks! cfg "chunks_coll" nil ["c1"])
        (storage/delete-orphan-chunks! cfg "chunks_coll" "" ["c1"])
        (is (false? @called?))))))

(deftest delete-orphan-phrases-builds-correct-filter
  (testing "delete-orphan-phrases! issues delete filtered on :id (catches legacy auto-id rows)"
    (let [captured (atom nil)]
      (with-redefs [ts/delete-documents! (fn [_settings coll opts]
                                           (reset! captured {:coll coll :opts opts}))]
        (storage/delete-orphan-phrases! cfg "phrases_coll" "doc-B" ["p1" "p2"])
        (is (= "phrases_coll" (:coll @captured)))
        (is (= "doc_num:=doc-B && id:!=[p1,p2]"
               (get-in @captured [:opts :filter_by])))))))

(deftest store-complete-document-issues-upserts-then-deletes-orphans
  (testing "store-complete-document! calls upserts then orphan deletes with :id-based keep-sets"
    (let [calls (atom [])
          fake-doc {:id "doc1"
                    :doc_num "dn1"
                    :chunks [{:chunk_id "c1" :content_markdown "x"
                              :search-phrases ["p1" "p2"]}
                             {:chunk_id "c2" :content_markdown "y"
                              :search-phrases ["p3"]}]}
          prepare-doc-fn (fn [_ d] (select-keys d [:id :doc_num :total_chunks]))
          ;; Real prepare-chunks would set :id := :chunk_id. Mimic that here
          ;; so the test exercises the real :id keep-set passed to deletes.
          prepare-chunks-fn (fn [chunks] (mapv #(assoc % :id (:chunk_id %)) chunks))]
      (with-redefs [storage/coll-ids (fn [_] ["docs_c" "chunks_c" "phrases_c"])
                    storage/upsert-document!   (fn [_cfg coll _doc]
                                                 (swap! calls conj [:upsert-doc coll]))
                    storage/store-chunks!      (fn [_cfg coll chunks]
                                                 (swap! calls conj [:upsert-chunks coll (count chunks)
                                                                    :chunk-ids (mapv :id chunks)]))
                    storage/store-phrases!     (fn [_cfg coll phrases _id]
                                                 (swap! calls conj [:upsert-phrases coll (count phrases)
                                                                    :phrase-ids (mapv :id phrases)]))
                    storage/delete-orphan-chunks!  (fn [_cfg coll doc-num keep-ids]
                                                     (swap! calls conj [:del-orphan-chunks coll doc-num keep-ids]))
                    storage/delete-orphan-phrases! (fn [_cfg coll doc-num keep-ids]
                                                     (swap! calls conj [:del-orphan-phrases coll doc-num keep-ids]))]
        (storage/store-complete-document! {} fake-doc prepare-doc-fn prepare-chunks-fn)
        (let [phrase-ids-from-upsert (->> @calls (filter #(= (first %) :upsert-phrases)) first (drop-while #(not= % :phrase-ids)) second)]
          (is (= [[:upsert-doc      "docs_c"]
                  [:upsert-chunks   "chunks_c" 2 :chunk-ids ["c1" "c2"]]
                  [:del-orphan-chunks  "chunks_c"  "dn1" ["c1" "c2"]]
                  [:upsert-phrases  "phrases_c" 3 :phrase-ids phrase-ids-from-upsert]
                  [:del-orphan-phrases "phrases_c" "dn1" phrase-ids-from-upsert]]
                 @calls)
              "deletes use :id keep-sets — chunk-ids from chunks, hash-ids from phrases"))))))

(deftest prepare-chunks-pins-id-to-chunk-id
  (testing "prepare-chunks sets :id := :chunk_id so Typesense upsert is keyed correctly"
    (let [chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                   :content_markdown "x" :metadata "{}" :url "/a"}
                  {:chunk_id "c2" :doc_num "d1" :chunk_index 1
                   :content_markdown "y" :metadata "{}" :url "/a"}]
          prepared (storage/prepare-chunks chunks :url identity)]
      (is (= ["c1" "c2"] (mapv :id prepared))
          "every prepared chunk has :id equal to its :chunk_id")
      (is (= ["c1" "c2"] (mapv :chunk_id prepared))
          ":chunk_id is also preserved (used as a facet/sort field)"))))

(deftest store-chunks-enforces-id-when-upstream-prepare-omits-it
  (testing "store-chunks! sets :id := :chunk_id even if the prepare-fn dropped :id"
    (let [captured (atom nil)
          ;; Simulates a per-pipeline prepare-chunks like prepare-website-chunks
          ;; that select-keys's away :id.
          chunks-without-id [{:chunk_id "c1" :doc_num "d1" :content_markdown "x"}
                             {:chunk_id "c2" :doc_num "d1" :content_markdown "y"}]]
      (with-redefs [ts/upsert-documents! (fn [_settings _coll docs]
                                           (reset! captured docs))]
        (storage/store-chunks! cfg "chunks_coll" chunks-without-id)
        (is (= ["c1" "c2"] (mapv :id @captured))
            "store-chunks! must enforce :id := :chunk_id at the storage boundary")))))

(deftest store-phrases-enforces-id-when-upstream-omits-it
  (testing "store-phrases! sets :id deterministically even if upstream dropped :id"
    (let [captured (atom nil)
          phrases-without-id [{:chunk_id "c1" :doc_num "d1" :search_phrase "alpha"}
                              {:chunk_id "c1" :doc_num "d1" :search_phrase "beta"}]]
      (with-redefs [ts/upsert-documents! (fn [_settings _coll docs]
                                           (reset! captured docs))]
        (storage/store-phrases! cfg "phrases_coll" phrases-without-id "doc1")
        (is (every? :id @captured)
            ":id must be set on every phrase row")
        (is (apply distinct? (map :id @captured))
            "different (chunk, phrase) pairs → different ids")
        ;; Determinism: storing the same phrases again yields the same ids
        (let [captured2 (atom nil)]
          (with-redefs [ts/upsert-documents! (fn [_ _ docs] (reset! captured2 docs))]
            (storage/store-phrases! cfg "phrases_coll" phrases-without-id "doc1")
            (is (= (mapv :id @captured) (mapv :id @captured2)))))))))

(deftest extract-phrases-pins-deterministic-id
  (testing "extract-phrases sets :id := sha256-short-hash(chunk_id|phrase)"
    (let [chunks [{:chunk_id "cA" :search-phrases ["hello" "world"]}
                  {:chunk_id "cB" :search-phrases ["hello"]}]
          phrases (storage/extract-phrases chunks "dn1")]
      (is (= 3 (count phrases)))
      ;; :id is a function of (chunk_id, phrase) — same pair → same id
      (let [id-by-pair (into {} (map (juxt (juxt :chunk_id :search_phrase) :id)) phrases)]
        (is (apply distinct? (vals id-by-pair))
            "different (chunk_id, phrase) pairs get different ids")
        ;; Re-running extract-phrases produces the same ids (deterministic)
        (let [phrases2 (storage/extract-phrases chunks "dn1")]
          (is (= (mapv :id phrases) (mapv :id phrases2))
              "phrase ids are deterministic across runs"))))))
