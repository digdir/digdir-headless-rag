(ns digdir.api.rate-limit-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.api.rate-limit :as rate-limit]
            [digdir.config.accessor :as cfg]))

;; Fixture to reset rate limit store between tests
(defn reset-rate-limit-fixture [f]
  (let [original @rate-limit/rate-limit-store]
    (try
      (reset! rate-limit/rate-limit-store {})
      (f)
      (finally
        (reset! rate-limit/rate-limit-store original)))))

(use-fixtures :each reset-rate-limit-fixture)

;; Helper to mock config accessor
(defn- with-trust-x-forwarded-for [trust? f]
  (with-redefs [cfg/get (fn [& args]
                           (when (= args [:services :rate-limiting :trust-x-forwarded-for])
                             trust?))]
    (f)))

;; ===== get-client-ip tests =====

(deftest test-get-client-ip-remote-addr
  (testing "Returns remote-addr when X-Forwarded-For not trusted"
    (with-trust-x-forwarded-for false
      #(let [request {:remote-addr "192.168.1.1"
                      :headers {"x-forwarded-for" "10.0.0.1"}}]
         (is (= "192.168.1.1" (rate-limit/get-client-ip request)))))))

(deftest test-get-client-ip-x-forwarded-for
  (testing "Returns X-Forwarded-For when trusted"
    (with-trust-x-forwarded-for true
      #(let [request {:remote-addr "192.168.1.1"
                      :headers {"x-forwarded-for" "10.0.0.1"}}]
         (is (= "10.0.0.1" (rate-limit/get-client-ip request)))))))

(deftest test-get-client-ip-fallback
  (testing "Returns 'unknown' when no IP available"
    (with-trust-x-forwarded-for false
      #(let [request {:headers {}}]
         (is (= "unknown" (rate-limit/get-client-ip request)))))))

;; ===== rate-limited? tests (via wrap-rate-limit) =====

(deftest test-rate-limit-threshold
  (testing "Rate limiting activates after 10 failed attempts"
    (with-trust-x-forwarded-for false
      #(let [handler (fn [_] {:status 401 :body "Unauthorized"})
             wrapped (rate-limit/wrap-rate-limit handler)
             request {:uri "/auth"
                      :request-method :post
                      :remote-addr "10.0.0.1"
                      :headers {}}]
         ;; First 10 attempts should pass through
         (dotimes [_ 10]
           (let [response (wrapped request)]
             (is (= 401 (:status response)))))
         ;; 11th attempt should be rate limited
         (let [response (wrapped request)]
           (is (= 429 (:status response))))))))

(deftest test-rate-limit-only-counts-failures
  (testing "Successful requests don't count toward rate limit"
    (with-trust-x-forwarded-for false
      #(let [call-count (atom 0)
             handler (fn [_]
                       (swap! call-count inc)
                       {:status 200 :body "OK"})
             wrapped (rate-limit/wrap-rate-limit handler)
             request {:uri "/auth"
                      :request-method :post
                      :remote-addr "10.0.0.2"
                      :headers {}}]
         ;; 20 successful requests should all pass
         (dotimes [_ 20]
           (let [response (wrapped request)]
             (is (= 200 (:status response)))))
         (is (= 20 @call-count))))))

(deftest test-rate-limit-only-auth-endpoints
  (testing "Non-auth endpoints are not rate limited"
    (with-trust-x-forwarded-for false
      #(let [handler (fn [_] {:status 401 :body "Unauthorized"})
             wrapped (rate-limit/wrap-rate-limit handler)
             request {:uri "/api/rag"
                      :request-method :post
                      :remote-addr "10.0.0.3"
                      :headers {}}]
         ;; 20 failed requests to non-auth endpoint should all pass
         (dotimes [_ 20]
           (let [response (wrapped request)]
             (is (= 401 (:status response)))))))))

(deftest test-rate-limit-only-post-requests
  (testing "GET requests to auth endpoints are not rate limited"
    (with-trust-x-forwarded-for false
      #(let [handler (fn [_] {:status 200 :body "OK"})
             wrapped (rate-limit/wrap-rate-limit handler)
             request {:uri "/auth"
                      :request-method :get
                      :remote-addr "10.0.0.4"
                      :headers {}}]
         ;; GET requests should not be counted
         (dotimes [_ 20]
           (let [response (wrapped request)]
             (is (= 200 (:status response)))))))))

(deftest test-rate-limit-per-ip
  (testing "Rate limits are tracked per IP address"
    (with-trust-x-forwarded-for false
      #(let [handler (fn [_] {:status 401 :body "Unauthorized"})
             wrapped (rate-limit/wrap-rate-limit handler)
             make-request (fn [ip]
                            {:uri "/auth"
                             :request-method :post
                             :remote-addr ip
                             :headers {}})]
         ;; Exhaust rate limit for IP1
         (dotimes [_ 10]
           (wrapped (make-request "10.0.0.5")))
         ;; IP1 should be rate limited
         (is (= 429 (:status (wrapped (make-request "10.0.0.5")))))
         ;; IP2 should still work
         (is (= 401 (:status (wrapped (make-request "10.0.0.6")))))))))

;; ===== cleanup-old-entries! tests =====

(deftest test-cleanup-old-entries
  (testing "Old entries are removed during cleanup"
    (let [now (System/currentTimeMillis)
          old-time (- now (* 2 60 60 1000)) ; 2 hours ago
          recent-time (- now (* 30 60 1000))] ; 30 minutes ago
      ;; Set up test data directly
      (reset! rate-limit/rate-limit-store
              {"old-ip" {:attempts 5 :last-reset old-time}
               "recent-ip" {:attempts 3 :last-reset recent-time}})
      ;; Trigger cleanup by calling the private function
      (#'rate-limit/cleanup-old-entries!)
      ;; Old entry should be removed, recent should remain
      (is (nil? (get @rate-limit/rate-limit-store "old-ip")))
      (is (some? (get @rate-limit/rate-limit-store "recent-ip"))))))

;; ===== Time window reset tests =====

(deftest test-rate-limit-window-reset
  (testing "Rate limit resets after 15 minute window"
    ;; This test verifies the logic without waiting 15 minutes
    ;; by manipulating the stored timestamp
    (with-trust-x-forwarded-for false
      #(let [now (System/currentTimeMillis)
             old-time (- now (* 16 60 1000))] ; 16 minutes ago
         ;; Set up an exhausted rate limit from 16 minutes ago
         (reset! rate-limit/rate-limit-store
                 {"10.0.0.7" {:attempts 10 :last-reset old-time}})
         (let [handler (fn [_] {:status 401 :body "Unauthorized"})
               wrapped (rate-limit/wrap-rate-limit handler)
               request {:uri "/auth"
                        :request-method :post
                        :remote-addr "10.0.0.7"
                        :headers {}}]
           ;; Should NOT be rate limited because window has expired
           (is (= 401 (:status (wrapped request)))))))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'digdir.api.rate-limit-test))
