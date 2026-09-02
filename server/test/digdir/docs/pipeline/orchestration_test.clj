(ns digdir.docs.pipeline.orchestration-test
  "Tests for digdir.docs.pipeline.orchestration - Missionary flow patterns."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [missionary.core :as m]
            [digdir.docs.pipeline.orchestration :as orch]
            [digdir.docs.pipeline.telemetry :as telemetry]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-telemetry-fixture [f]
  (telemetry/reset-telemetry!)
  (reset! telemetry/!store-threads 0)
  (f)
  (telemetry/reset-telemetry!)
  (reset! telemetry/!store-threads 0))

(use-fixtures :each reset-telemetry-fixture)

;; ============================================================================
;; Helpers
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
;; mk-filter-entries-f Tests
;; ============================================================================

(deftest filter-entries-basic
  (testing "Passes through all entries with default config"
    (let [entries [{:loc "a"} {:loc "b"} {:loc "c"}]
          config {:urls/limit 1000 :urls/offset 0}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "urls" :loc))]
      (is (= 3 (count result))))))

(deftest filter-entries-with-limit
  (testing "Respects limit"
    (let [entries (map #(hash-map :loc (str %)) (range 10))
          config {:urls/limit 5 :urls/offset 0}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "urls" :loc))]
      (is (= 5 (count result))))))

(deftest filter-entries-with-offset
  (testing "Respects offset"
    (let [entries (map #(hash-map :loc (str %)) (range 10))
          config {:urls/limit 100 :urls/offset 3}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "urls" :loc))]
      (is (= 7 (count result)))
      (is (= "3" (:loc (first result)))))))

(deftest filter-entries-with-limit-and-offset
  (testing "Respects both limit and offset"
    (let [entries (map #(hash-map :loc (str %)) (range 20))
          config {:urls/limit 5 :urls/offset 10}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "urls" :loc))]
      (is (= 5 (count result)))
      (is (= "10" (:loc (first result))))
      (is (= "14" (:loc (last result)))))))

(deftest filter-entries-removes-duplicates
  (testing "Removes duplicate entries"
    (let [entries [{:loc "a"} {:loc "b"} {:loc "a"} {:loc "c"}]
          config {:urls/limit 1000 :urls/offset 0}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "urls" :loc))]
      (is (= 3 (count result))))))

(deftest filter-entries-default-limit
  (testing "Uses default limit when not specified"
    (let [entries (map #(hash-map :path (str %)) (range 2000))
          config {:files/offset 0}
          result (run-flow (orch/mk-filter-entries-f config (m/seed entries) "files" :path))]
      ;; Default limit is 1000
      (is (= 1000 (count result))))))

;; ============================================================================
;; mk-filter-url-entries-f Tests
;; ============================================================================

(deftest filter-url-entries-convenience
  (testing "Convenience wrapper for URL entries"
    (let [entries [{:loc "http://a.com"} {:loc "http://b.com"}]
          config {:urls/limit 10 :urls/offset 0}
          result (run-flow (orch/mk-filter-url-entries-f config (m/seed entries)))]
      (is (= 2 (count result))))))

;; ============================================================================
;; mk-filter-file-entries-f Tests
;; ============================================================================

(deftest filter-file-entries-convenience
  (testing "Convenience wrapper for file entries"
    (let [entries [{:path "/a.md"} {:path "/b.md"}]
          config {:files/limit 10 :files/offset 0}
          result (run-flow (orch/mk-filter-file-entries-f config (m/seed entries)))]
      (is (= 2 (count result))))))

;; ============================================================================
;; mk-prepare-documents-f Tests
;; ============================================================================

(deftest prepare-documents-basic
  (testing "Prepares documents in parallel"
    (let [entries [{:id "1"} {:id "2"} {:id "3"}]
          config {:parallelism/documents 2
                  :fault-tolerance/max-document-failures 3}
          prepare-fn (fn [_ entry] (m/sp (assoc entry :prepared true)))
          result (run-flow (orch/mk-prepare-documents-f
                            config prepare-fn (m/seed entries) :test))]
      (is (= 3 (count result)))
      (is (every? :prepared result)))))

(deftest prepare-documents-handles-failures
  (testing "Continues after recoverable failures"
    (let [entries [{:id "1"} {:id "fail"} {:id "3"}]
          config {:parallelism/documents 1
                  :fault-tolerance/max-document-failures 5}
          prepare-fn (fn [_ entry]
                       (m/sp
                         (if (= "fail" (:id entry))
                           (throw (Exception. "Test failure"))
                           (assoc entry :prepared true))))
          result (run-flow (orch/mk-prepare-documents-f
                            config prepare-fn (m/seed entries) :test))]
      ;; Should have processed 2 docs (1 and 3), skipped fail
      (is (= 2 (count result))))))

;; ============================================================================
;; mk-store-documents-f Tests
;; ============================================================================

(deftest store-documents-basic
  (testing "Stores documents and tracks threads"
    (let [docs [{:id "1"} {:id "2"}]
          config {:parallelism/store 1}
          store-fn (fn [_ doc] (m/sp (assoc doc :stored true)))
          result (run-flow (orch/mk-store-documents-f
                            config store-fn (m/seed docs)))]
      (is (= 2 (count result)))
      (is (every? :stored result))
      ;; Should end with 0 threads
      (is (= 0 @telemetry/!store-threads)))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest complete-pipeline-flow
  (testing "Complete pipeline from entries to stored docs"
    (let [entries (map #(hash-map :loc (str "http://example.com/" % ".md")) (range 5))
          config {:urls/limit 3
                  :urls/offset 0
                  :parallelism/documents 2
                  :parallelism/store 1
                  :fault-tolerance/max-document-failures 3}
          prepare-fn (fn [_ entry] (m/sp (assoc entry :prepared true)))
          store-fn (fn [_ doc] (m/sp (assoc doc :stored true)))
          entries-flow (m/seed entries)
          filtered-flow (orch/mk-filter-url-entries-f config entries-flow)
          prepared-flow (orch/mk-prepare-documents-f
                         config prepare-fn filtered-flow :test)
          stored-flow (orch/mk-store-documents-f config store-fn prepared-flow)
          result (run-flow stored-flow)]
      (is (= 3 (count result)))  ;; Limited to 3
      (is (every? :prepared result))
      (is (every? :stored result)))))
