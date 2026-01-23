(ns digdir.docs.pipeline.telemetry-test
  "Tests for digdir.docs.pipeline.telemetry - telemetry state management."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.docs.pipeline.telemetry :as telemetry]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-telemetry-fixture [f]
  (telemetry/reset-telemetry!)
  (reset! telemetry/!job-canceller nil)
  (reset! telemetry/!start-job-button-disabled? false)
  (reset! telemetry/!store-threads 0)
  (f)
  (telemetry/reset-telemetry!)
  (reset! telemetry/!job-canceller nil)
  (reset! telemetry/!start-job-button-disabled? false)
  (reset! telemetry/!store-threads 0))

(use-fixtures :each reset-telemetry-fixture)

;; ============================================================================
;; Telemetry Aggregator Tests
;; ============================================================================

(deftest telemetry-aggregator-counts-signals
  (testing "Aggregates signal counts by ID"
    (let [agg {}
          sig1 {:id :test/signal-a}
          sig2 {:id :test/signal-a}
          sig3 {:id :test/signal-b}
          result (-> agg
                     (telemetry/telemetry-aggregator sig1)
                     (telemetry/telemetry-aggregator sig2)
                     (telemetry/telemetry-aggregator sig3))]
      (is (= 2 (get-in result [:id-counts :test/signal-a])))
      (is (= 1 (get-in result [:id-counts :test/signal-b]))))))

(deftest telemetry-aggregator-empty-start
  (testing "Handles starting from empty map"
    (let [result (telemetry/telemetry-aggregator {} {:id :first/signal})]
      (is (= 1 (get-in result [:id-counts :first/signal]))))))

;; ============================================================================
;; Job State Management Tests
;; ============================================================================

(deftest start-job-sets-state
  (testing "start-job! sets canceller and disables button"
    (let [cancel-fn (fn [] :cancelled)]
      (telemetry/start-job! cancel-fn)
      (is (= cancel-fn @telemetry/!job-canceller))
      (is (true? @telemetry/!start-job-button-disabled?)))))

(deftest stop-job-clears-state
  (testing "stop-job! clears state and returns true"
    (let [cancelled? (atom false)
          cancel-fn (fn [] (reset! cancelled? true))]
      (telemetry/start-job! cancel-fn)
      (is (true? (telemetry/stop-job!)))
      (is (true? @cancelled?))
      (is (nil? @telemetry/!job-canceller))
      (is (false? @telemetry/!start-job-button-disabled?)))))

(deftest stop-job-no-running-job
  (testing "stop-job! returns nil when no job running"
    (is (nil? (telemetry/stop-job!)))))

(deftest job-running-checks-state
  (testing "job-running? returns correct state"
    (is (false? (telemetry/job-running?)))
    (telemetry/start-job! (fn []))
    (is (true? (telemetry/job-running?)))
    (telemetry/stop-job!)
    (is (false? (telemetry/job-running?)))))

;; ============================================================================
;; Telemetry Query Functions Tests
;; ============================================================================

(deftest get-signal-counts-empty
  (testing "Returns empty map when no signals"
    (is (= {} (telemetry/get-signal-counts)))))

(deftest get-signal-counts-with-data
  (testing "Returns signal counts"
    (swap! telemetry/!transient-telemetry-aggregate
           assoc :id-counts {:test/a 5 :test/b 3})
    (let [counts (telemetry/get-signal-counts)]
      (is (= 5 (:test/a counts)))
      (is (= 3 (:test/b counts))))))

(deftest get-recent-signals-empty
  (testing "Returns empty list when no signals"
    (is (empty? (telemetry/get-recent-signals)))))

(deftest get-recent-signals-with-limit
  (testing "Returns limited number of signals"
    (reset! telemetry/!signal-window (list {:id :a} {:id :b} {:id :c}))
    (is (= 2 (count (telemetry/get-recent-signals 2))))
    (is (= :a (:id (first (telemetry/get-recent-signals 2)))))))

;; ============================================================================
;; Reset Telemetry Tests
;; ============================================================================

(deftest reset-telemetry-clears-all
  (testing "reset-telemetry! clears aggregate and window"
    (swap! telemetry/!transient-telemetry-aggregate
           assoc :id-counts {:test 5})
    (reset! telemetry/!signal-window (list {:id :test}))
    (telemetry/reset-telemetry!)
    (is (= {} @telemetry/!transient-telemetry-aggregate))
    (is (empty? @telemetry/!signal-window))))

;; ============================================================================
;; Store Threads Tracking Tests
;; ============================================================================

(deftest store-threads-tracking
  (testing "Store threads can be tracked via atom"
    (is (= 0 @telemetry/!store-threads))
    (swap! telemetry/!store-threads inc)
    (is (= 1 @telemetry/!store-threads))
    (swap! telemetry/!store-threads inc)
    (is (= 2 @telemetry/!store-threads))
    (swap! telemetry/!store-threads dec)
    (is (= 1 @telemetry/!store-threads))))
