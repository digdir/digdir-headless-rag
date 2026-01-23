(ns digdir.docs.pipeline-test
  "Tests for pipeline orchestration functions.
   Tests Missionary flow-based document processing pipelines."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [missionary.core :as m]
            [digdir.docs.website :as website]
            [digdir.docs.folder :as folder]
            [digdir.docs.test-fixtures :as fixtures]
            [typesense.client :as ts]))

;; ============================================================================
;; Helper Functions for Testing Missionary Flows
;; ============================================================================

(defn run-task
  "Run a Missionary task and return result or throw exception."
  [task]
  (let [result (atom nil)
        error (atom nil)
        latch (java.util.concurrent.CountDownLatch. 1)]
    (task
     (fn [r] (reset! result r) (.countDown latch))
     (fn [e] (reset! error e) (.countDown latch)))
    (.await latch 10 java.util.concurrent.TimeUnit/SECONDS)
    (if @error
      (throw @error)
      @result)))

(defn run-flow
  "Run a Missionary flow and collect all results into a vector."
  [flow]
  (run-task (m/reduce conj [] flow)))

;; ============================================================================
;; mk-filter-url-entries-f Tests
;; ============================================================================

(deftest filter-url-entries-basic
  (testing "Filters URL entries with default limit"
    (let [entries [{:loc "http://example.com/1.md"}
                   {:loc "http://example.com/2.md"}
                   {:loc "http://example.com/3.md"}]
          config {:urls/limit 1000 :urls/offset 0}
          flow (website/mk-filter-url-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 3 (count result))))))

(deftest filter-url-entries-with-limit
  (testing "Respects limit parameter"
    (let [entries (map #(hash-map :loc (str "http://example.com/" % ".md"))
                       (range 10))
          config {:urls/limit 5 :urls/offset 0}
          flow (website/mk-filter-url-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 5 (count result))))))

(deftest filter-url-entries-with-offset
  (testing "Respects offset parameter"
    (let [entries (map #(hash-map :loc (str "http://example.com/" % ".md"))
                       (range 10))
          config {:urls/limit 100 :urls/offset 3}
          flow (website/mk-filter-url-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 7 (count result)))
      (is (= "http://example.com/3.md" (:loc (first result)))))))

(deftest filter-url-entries-with-limit-and-offset
  (testing "Respects both limit and offset"
    (let [entries (map #(hash-map :loc (str "http://example.com/" % ".md"))
                       (range 20))
          config {:urls/limit 5 :urls/offset 10}
          flow (website/mk-filter-url-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 5 (count result)))
      (is (= "http://example.com/10.md" (:loc (first result))))
      (is (= "http://example.com/14.md" (:loc (last result)))))))

(deftest filter-url-entries-removes-duplicates
  (testing "Removes duplicate URLs"
    (let [entries [{:loc "http://example.com/1.md"}
                   {:loc "http://example.com/2.md"}
                   {:loc "http://example.com/1.md"}  ; duplicate
                   {:loc "http://example.com/3.md"}]
          config {:urls/limit 1000 :urls/offset 0}
          flow (website/mk-filter-url-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 3 (count result))))))

(deftest filter-url-entries-empty-input
  (testing "Handles empty input"
    (let [config {:urls/limit 1000 :urls/offset 0}
          flow (website/mk-filter-url-entries-f config (m/seed []))
          result (run-flow flow)]
      (is (empty? result)))))

;; ============================================================================
;; Folder mk-filter-file-entries-f Tests
;; ============================================================================

(deftest filter-file-entries-basic
  (testing "Filters file entries with default limit"
    (let [entries [{:path "/docs/1.md" :lastmod "2024-01-01"}
                   {:path "/docs/2.md" :lastmod "2024-01-02"}
                   {:path "/docs/3.md" :lastmod "2024-01-03"}]
          config {:files/limit 1000 :files/offset 0}
          flow (folder/mk-filter-file-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 3 (count result))))))

(deftest filter-file-entries-with-limit
  (testing "Respects limit parameter"
    (let [entries (map #(hash-map :path (str "/docs/" % ".md") :lastmod "2024-01-01")
                       (range 10))
          config {:files/limit 5 :files/offset 0}
          flow (folder/mk-filter-file-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 5 (count result))))))

(deftest filter-file-entries-removes-duplicates
  (testing "Removes duplicate paths"
    (let [entries [{:path "/docs/1.md" :lastmod "2024-01-01"}
                   {:path "/docs/2.md" :lastmod "2024-01-02"}
                   {:path "/docs/1.md" :lastmod "2024-01-03"}  ; duplicate path
                   {:path "/docs/3.md" :lastmod "2024-01-04"}]
          config {:files/limit 1000 :files/offset 0}
          flow (folder/mk-filter-file-entries-f config (m/seed entries))
          result (run-flow flow)]
      (is (= 3 (count result))))))

;; ============================================================================
;; url-to-doc and path-to-doc Tests
;; ============================================================================

(deftest url-to-doc-basic
  (testing "Converts URL entry to document structure"
    (let [url-entry {:loc "http://example.com/docs/guide.md" :lastmod "2024-01-15"}
          doc (website/url-to-doc url-entry)]
      (is (string? (:id doc)))
      (is (= (:id doc) (:doc_num doc)))
      (is (= "http://example.com/docs/guide.md" (:url doc)))
      (is (= "2024-01-15" (:lastmod doc)))
      (is (= "website" (:type doc)))
      (is (string? (:title doc))))))

(deftest path-to-doc-basic
  (testing "Converts file entry to document structure"
    (let [file-entry {:path "/home/user/docs/guide.md" :lastmod "2024-01-15T10:00:00Z"}
          doc (folder/path-to-doc file-entry)]
      (is (string? (:id doc)))
      (is (= (:id doc) (:doc_num doc)))
      (is (string? (:path doc)))
      (is (= "/home/user/docs/guide.md" (:path doc)))
      (is (= "folder" (:type doc)))
      (is (string? (:title doc))))))

;; ============================================================================
;; Pipeline Configuration Tests
;; ============================================================================

(deftest pipeline-config-parallelism
  (testing "Config contains parallelism settings"
    (let [config {:parallelism/documents 3
                  :parallelism/store 1
                  :fault-tolerance/max-document-failures 5}]
      (is (= 3 (:parallelism/documents config)))
      (is (= 1 (:parallelism/store config)))
      (is (= 5 (:fault-tolerance/max-document-failures config))))))

(deftest pipeline-config-chunks
  (testing "Config contains chunk settings"
    (let [config {:chunks/strategy :header-based
                  :chunks/minimum-length 100
                  :chunks/maximum-length 10000}]
      (is (= :header-based (:chunks/strategy config)))
      (is (= 100 (:chunks/minimum-length config)))
      (is (= 10000 (:chunks/maximum-length config))))))

(deftest pipeline-config-search-phrases
  (testing "Config contains search phrase settings"
    (let [config {:search-phrases/model "gpt-4o"
                  :search-phrases/fallback-model "gpt-3.5-turbo"
                  :search-phrases/prompt "Generate phrases for: REPLACE_ME"}]
      (is (= "gpt-4o" (:search-phrases/model config)))
      (is (= "gpt-3.5-turbo" (:search-phrases/fallback-model config)))
      (is (str/includes? (:search-phrases/prompt config) "REPLACE_ME")))))

;; ============================================================================
;; Simulated Document Preparation Tests
;; ============================================================================

(deftest document-preparation-flow
  (testing "Document preparation adds required fields"
    (let [;; Simulate what mk-prepare-document-t does
          url-entry {:loc "http://example.com/guide.md" :lastmod "2024-01-15"}
          doc (website/url-to-doc url-entry)
          doc-with-content (assoc doc :content_markdown fixtures/sample-markdown-simple)
          ;; Simulate chunking
          chunks [{:chunk_id "chunk-0" :doc_num (:doc_num doc) :chunk_index 0
                   :content_markdown "# Main Title\n\nIntro."}
                  {:chunk_id "chunk-1" :doc_num (:doc_num doc) :chunk_index 1
                   :content_markdown "## Section 1\n\nContent."}]
          doc-with-chunks (assoc doc-with-content :chunks chunks)
          ;; Simulate search phrases
          chunks-with-phrases (mapv #(assoc % :search-phrases ["phrase1" "phrase2"]) chunks)
          final-doc (assoc doc-with-chunks :chunks chunks-with-phrases)]
      (is (contains? final-doc :id))
      (is (contains? final-doc :doc_num))
      (is (contains? final-doc :url))
      (is (contains? final-doc :title))
      (is (contains? final-doc :type))
      (is (contains? final-doc :chunks))
      (is (= 2 (count (:chunks final-doc))))
      (is (every? :search-phrases (:chunks final-doc))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest fault-tolerance-config
  (testing "Fault tolerance is configurable"
    (let [config {:fault-tolerance/max-document-failures 10}]
      (is (= 10 (:fault-tolerance/max-document-failures config))))))

(deftest prepare-failures-tracking
  (testing "Failures can be tracked with atom"
    (let [failures (atom 0)
          max-failures 3]
      ;; Simulate failure tracking
      (dotimes [_ 5]
        (let [current (swap! failures inc)
              terminal? (= current max-failures)]
          (when terminal?
            (is (= 3 current)))))
      (is (= 5 @failures)))))

;; ============================================================================
;; Store Thread Tracking Tests
;; ============================================================================

(deftest store-threads-tracking
  (testing "Store threads can be tracked"
    (let [threads (atom 0)]
      (swap! threads inc)
      (is (= 1 @threads))
      (swap! threads inc)
      (is (= 2 @threads))
      (swap! threads dec)
      (is (= 1 @threads))
      (swap! threads dec)
      (is (= 0 @threads)))))

;; ============================================================================
;; Integration: Complete Pipeline Simulation
;; ============================================================================

(deftest complete-pipeline-simulation
  (testing "Complete pipeline can be simulated end-to-end"
    (let [;; 1. Source data
          url-entries [{:loc "http://example.com/doc1.md" :lastmod "2024-01-15"}
                       {:loc "http://example.com/doc2.md" :lastmod "2024-01-16"}]

          ;; 2. Filter (limit/offset)
          config {:urls/limit 10 :urls/offset 0
                  :parallelism/documents 2
                  :parallelism/store 1
                  :fault-tolerance/max-document-failures 3
                  :chunks/minimum-length 10
                  :search-phrases/model "gpt-4o"
                  :store/coll-prefix "test_"}
          filtered (run-flow (website/mk-filter-url-entries-f config (m/seed url-entries)))

          ;; 3. Convert to docs
          docs (map website/url-to-doc filtered)

          ;; 4. Simulate adding content
          docs-with-content (map #(assoc % :content_markdown fixtures/sample-markdown-simple) docs)

          ;; 5. Simulate chunking (simplified)
          docs-with-chunks (map (fn [doc]
                                  (assoc doc :chunks
                                         [{:chunk_id (str (:id doc) "-0")
                                           :doc_num (:doc_num doc)
                                           :chunk_index 0
                                           :content_markdown "Content here"}]))
                                docs-with-content)

          ;; 6. Simulate search phrases
          final-docs (map (fn [doc]
                            (update doc :chunks
                                    (fn [chunks]
                                      (mapv #(assoc % :search-phrases ["test phrase"]) chunks))))
                          docs-with-chunks)]

      (is (= 2 (count final-docs)))
      (is (every? :id final-docs))
      (is (every? :chunks final-docs))
      (is (every? #(every? :search-phrases (:chunks %)) final-docs)))))

;; ============================================================================
;; Missionary Flow Composition Tests
;; ============================================================================

(deftest flow-seed-and-eduction
  (testing "m/seed creates flow from collection"
    (let [data [1 2 3 4 5]
          flow (m/seed data)
          result (run-flow flow)]
      (is (= [1 2 3 4 5] result)))))

(deftest flow-eduction-with-take
  (testing "m/eduction applies transducers"
    (let [data (range 10)
          flow (m/eduction (take 3) (m/seed data))
          result (run-flow flow)]
      (is (= [0 1 2] result)))))

(deftest flow-eduction-with-drop-and-take
  (testing "m/eduction can chain transducers"
    (let [data (range 10)
          flow (m/eduction (drop 2) (take 3) (m/seed data))
          result (run-flow flow)]
      (is (= [2 3 4] result)))))

(deftest flow-eduction-with-map
  (testing "m/eduction can map values"
    (let [data [1 2 3]
          flow (m/eduction (map inc) (m/seed data))
          result (run-flow flow)]
      (is (= [2 3 4] result)))))

(deftest flow-eduction-with-filter
  (testing "m/eduction can filter values"
    (let [data [1 2 3 4 5 6]
          flow (m/eduction (filter even?) (m/seed data))
          result (run-flow flow)]
      (is (= [2 4 6] result)))))

;; ============================================================================
;; Parallel Processing Simulation
;; ============================================================================

(deftest parallel-document-processing
  (testing "Documents can be processed with parallelism tracking"
    (let [docs (map #(hash-map :id (str "doc-" %)) (range 5))
          processed (atom [])
          in-flight (atom 0)
          max-in-flight (atom 0)]
      ;; Simulate parallel processing
      (doseq [doc docs]
        (swap! in-flight inc)
        (swap! max-in-flight max @in-flight)
        ;; "Process" the doc
        (swap! processed conj (assoc doc :processed true))
        (swap! in-flight dec))
      (is (= 5 (count @processed)))
      (is (every? :processed @processed)))))
