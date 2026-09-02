(ns digdir.llm.marker-test
  "Pins that the Marker request timeout and retry ladder are configurable, and
   — the part that matters for existing deployments — that they fall back to
   the values that used to be hardcoded whenever config says nothing or the
   config layer is unavailable.

   No live Marker service: `clj-http.client/post` and `digdir.config.accessor/get`
   are redefined."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [digdir.config.accessor :as cfg]
            [digdir.llm.marker :as marker])
  (:import [java.io File]))

(defn- stub-config
  "A `cfg/get` stand-in serving `values` (keyed by the last path segment) and
   nil for anything else."
  [values]
  (fn [_opts & path]
    (get values (last path))))

(def ^:private historical-timeout-ms (* 6 60 60 1000))
(def ^:private historical-delays-ms
  [60000 600000 1800000 3600000 7200000 86400000])

(deftest defaults-match-the-previously-hardcoded-values
  (testing "the defaults are the values marker.clj used to hardcode"
    (is (= historical-timeout-ms marker/default-timeout-ms))
    (is (= historical-delays-ms marker/default-retry-delays-ms))))

(deftest unset-config-keeps-todays-behaviour
  (testing "nothing configured — deployments that set no config are unaffected"
    (with-redefs [cfg/get (stub-config {})]
      (is (= historical-timeout-ms (marker/timeout-ms "t")))
      (is (= historical-delays-ms (marker/retry-delays-ms "t")))))

  (testing "config layer unavailable (no CONFIG_MASTER_KEY / no definition) — throws, still defaults"
    (with-redefs [cfg/get (fn [& _] (throw (ex-info "Database config required" {})))]
      (is (= historical-timeout-ms (marker/timeout-ms "t")))
      (is (= historical-delays-ms (marker/retry-delays-ms "t"))))))

(deftest configured-values-are-used
  (testing "a configured timeout and ladder win over the defaults"
    (with-redefs [cfg/get (stub-config {:timeout-ms 5000
                                        :retry-delays-ms [1000 2000]})]
      (is (= 5000 (marker/timeout-ms "t")))
      (is (= [1000 2000] (marker/retry-delays-ms "t")))))

  (testing "an empty ladder is a legitimate choice — no retries, not 'unset'"
    (with-redefs [cfg/get (stub-config {:retry-delays-ms []})]
      (is (= [] (marker/retry-delays-ms "t")))))

  (testing "numbers arrive as longs even if configured as doubles"
    (with-redefs [cfg/get (stub-config {:timeout-ms 5000.0
                                        :retry-delays-ms [1000.0]})]
      (is (= 5000 (marker/timeout-ms "t")))
      (is (= [1000] (marker/retry-delays-ms "t"))))))

(deftest unusable-config-falls-back-instead-of-breaking-ingestion
  (testing "wrong type, non-positive timeout, or a ladder holding non-numbers"
    (doseq [bad ["6 hours" 0 -1 [1000]]]
      (with-redefs [cfg/get (stub-config {:timeout-ms bad})]
        (is (= historical-timeout-ms (marker/timeout-ms "t"))
            (str "timeout " (pr-str bad) " should fall back"))))

    (doseq [bad [1000 "1000" [1000 "nope"] [1000 -5] {:a 1}]]
      (with-redefs [cfg/get (stub-config {:retry-delays-ms bad})]
        (is (= historical-delays-ms (marker/retry-delays-ms "t"))
            (str "ladder " (pr-str bad) " should fall back"))))))

(defn- temp-pdf ^File []
  (doto (File/createTempFile "marker-test" ".pdf") (.deleteOnExit)))

(deftest configured-timeout-reaches-the-http-request
  (testing "the timeout is applied to both socket and connection timeouts"
    (let [!opts (atom nil)]
      (with-redefs [cfg/get (stub-config {:timeout-ms 1234
                                          :api-url "http://marker.example"
                                          :api-key "k"})
                    http/post (fn [_url opts]
                                (reset! !opts opts)
                                {:status 200 :body {:success true :pages ["p1"]}})]
        (marker/->md "tenant" (.getPath (temp-pdf))))
      (is (= 1234 (:socket-timeout @!opts)))
      (is (= 1234 (:connection-timeout @!opts))))))

(deftest configured-ladder-drives-the-retry-count
  (testing "two configured delays means one initial attempt plus two retries"
    (let [!calls (atom 0)]
      (with-redefs [cfg/get (stub-config {:retry-delays-ms [0 0]
                                          :api-url "http://marker.example"
                                          :api-key "k"})
                    http/post (fn [_url _opts]
                                (swap! !calls inc)
                                (throw (ex-info "unavailable" {:status 503})))]
        (is (thrown? Exception (marker/->md "tenant" (.getPath (temp-pdf))))))
      (is (= 3 @!calls))))

  (testing "an empty ladder disables retries entirely"
    (let [!calls (atom 0)]
      (with-redefs [cfg/get (stub-config {:retry-delays-ms []
                                          :api-url "http://marker.example"
                                          :api-key "k"})
                    http/post (fn [_url _opts]
                                (swap! !calls inc)
                                (throw (ex-info "unavailable" {:status 503})))]
        (is (thrown? Exception (marker/->md "tenant" (.getPath (temp-pdf))))))
      (is (= 1 @!calls))))

  (testing "a non-retryable status is never retried, whatever the ladder says"
    (let [!calls (atom 0)]
      (with-redefs [cfg/get (stub-config {:retry-delays-ms [0 0 0]
                                          :api-url "http://marker.example"
                                          :api-key "k"})
                    http/post (fn [_url _opts]
                                (swap! !calls inc)
                                (throw (ex-info "bad request" {:status 400})))]
        (is (thrown? Exception (marker/->md "tenant" (.getPath (temp-pdf))))))
      (is (= 1 @!calls)))))
