(ns digdir.llm.client-retry-test
  "Pins the HTTP 429 retry policy of `digdir.llm.client/create-chat-completion`
   on both of its branches.

   No live provider, and deliberately belt-and-braces about it: issue 38 found
   this suite making real billable calls to api.openai.com through stubs that
   had drifted from the var production actually calls. So the stubs here are on
   the vars the client really invokes — `clj-http.client/post` for the direct
   branch, `wkok.openai-clojure.api/create-chat-completion` for the Azure one —
   AND a fixture redefines `clj-http.core/request`, the bottom of clj-http that
   both of those ultimately reach, to fail loudly. If a future edit routes
   around a stub, these tests break instead of spending money.

   `sleep-ms!` is redefined throughout, so the backoff is asserted rather than
   waited out."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-http.client :as http]
            [clj-http.core]
            [digdir.llm.client :as client]
            [wkok.openai-clojure.api :as wkok]))

(defn- no-network
  "Fail any attempt to actually reach the network, and neutralise ambient
   OPENAI_* env vars so the defaults under test are the ones that apply."
  [f]
  (with-redefs [clj-http.core/request
                (fn [& _]
                  (throw (AssertionError.
                          "OUTBOUND HTTP REQUEST ATTEMPTED from a unit test")))
                client/env-num (constantly nil)]
    (f)))

(use-fixtures :each no-network)

(def ^:private unroutable
  "Discard port: if anything escaped the stubs it would be refused here rather
   than reaching api.openai.com."
  {:api-key "test-key" :api-endpoint "http://127.0.0.1:9"})

(def ^:private ok-response {:body {:choices [{:message {:content "hi"}}]}})

(defn- rate-limited
  ([] (rate-limited {}))
  ([headers] (ex-info "clj-http: status 429" {:status 429 :headers headers})))

(defn- responder
  "Stand-in serving `outcomes` in order — throwing Throwables, returning anything
   else — and reusing the last one once exhausted. Counts its calls."
  [!calls outcomes]
  (fn [& _]
    (let [i (dec (swap! !calls inc))
          outcome (nth outcomes (min i (dec (count outcomes))))]
      (if (instance? Throwable outcome) (throw outcome) outcome))))

(defn- run-direct
  "Drive the direct branch against `outcomes`, returning
   {:result/:error, :calls, :sleeps}."
  [outcomes]
  (let [!calls (atom 0)
        !sleeps (atom [])]
    (with-redefs [http/post (responder !calls outcomes)
                  client/sleep-ms! (fn [ms] (swap! !sleeps conj ms))]
      (let [outcome (try {:result (client/create-chat-completion
                                   {:model "gpt-4o" :messages []} unroutable)}
                         (catch Exception e {:error e}))]
        (assoc outcome :calls @!calls :sleeps @!sleeps)))))

(deftest retries-429-then-succeeds
  (testing "a 429 is absorbed: one retry, then the caller gets the response"
    (let [{:keys [result error calls sleeps]}
          (run-direct [(rate-limited) ok-response])]
      (is (nil? error))
      (is (= (:body ok-response) result))
      (is (= 2 calls))
      (is (= 1 (count sleeps))))))

(deftest honours-retry-after-when-the-provider-sends-one
  (testing "Retry-After in seconds drives the wait, like the Anthropic client"
    (is (= [7000] (:sleeps (run-direct [(rate-limited {"Retry-After" "7"}) ok-response]))))

    (testing "and the lookup is case-tolerant"
      (is (= [7000] (:sleeps (run-direct [(rate-limited {"retry-after" "7"}) ok-response]))))))

  (testing "no Retry-After falls back to 60s — the Anthropic default"
    (is (= [60000] (:sleeps (run-direct [(rate-limited) ok-response])))))

  (testing "a Retry-After HTTP date is not a number, so the default applies rather than failing"
    (is (= [60000] (:sleeps (run-direct [(rate-limited {"Retry-After" "Fri, 21 Aug 2026 06:10:05 GMT"})
                                         ok-response])))))

  (testing "a non-positive Retry-After is ignored too"
    (is (= [60000] (:sleeps (run-direct [(rate-limited {"Retry-After" "0"}) ok-response]))))))

(deftest exhausted-retries-produce-an-attributable-error
  (testing "persistent 429: 1 initial attempt + 3 retries, then a clear error"
    (let [{:keys [error calls sleeps]} (run-direct [(rate-limited)])
          data (ex-data error)]
      (is (= 4 calls) "one initial attempt plus the three retries")
      (is (= 3 (count sleeps)))
      (is (re-find #"429" (ex-message error)))

      (testing "the error names the status, the attempts, and what was being called"
        (is (= 429 (:status data)))
        (is (= 4 (:attempts data)))
        (is (= 3 (:max-retries data)))
        (is (= "gpt-4o" (:model data)))
        (is (= :openai (:impl data))))

      (testing "and keeps the provider's own exception as the cause"
        (is (= 429 (:status (ex-data (ex-cause error)))))))))

(deftest non-429-failures-are-not-retried
  (testing "a 400 propagates untouched on the first attempt"
    (let [{:keys [error calls sleeps]}
          (run-direct [(ex-info "clj-http: status 400" {:status 400})])]
      (is (= 1 calls))
      (is (empty? sleeps))
      (is (= 400 (:status (ex-data error))) "the original error, not a wrapped one")))

  (testing "a 401 is likewise surfaced immediately"
    (let [{:keys [error calls]}
          (run-direct [(ex-info "clj-http: status 401" {:status 401})])]
      (is (= 1 calls))
      (is (= 401 (:status (ex-data error)))))))

(deftest azure-branch-is-retried-on-the-same-policy
  (testing "the wkok-delegated Azure path retries 429 too"
    (let [!wkok-calls (atom 0)
          !http-calls (atom 0)
          !sleeps (atom [])]
      (with-redefs [wkok/create-chat-completion (responder !wkok-calls
                                                           [(rate-limited {"Retry-After" "2"})
                                                            {:choices []}])
                    http/post (fn [& _] (swap! !http-calls inc) ok-response)
                    client/sleep-ms! (fn [ms] (swap! !sleeps conj ms))]
        (is (= {:choices []}
               (client/create-chat-completion {:model "gpt-4o" :messages []}
                                              (assoc unroutable :impl :azure))))
        (is (= 2 @!wkok-calls))
        (is (= [2000] @!sleeps))
        (is (zero? @!http-calls) "the Azure branch must not touch the direct path"))))

  (testing "exhaustion on the Azure branch is attributed to :azure"
    (let [!calls (atom 0)]
      (with-redefs [wkok/create-chat-completion (responder !calls [(rate-limited)])
                    client/sleep-ms! (fn [_])]
        (let [error (try (client/create-chat-completion {:model "gpt-5.5" :messages []}
                                                        (assoc unroutable :impl :azure))
                         (catch Exception e e))]
          (is (= 4 @!calls))
          (is (= :azure (:impl (ex-data error))))
          (is (= "gpt-5.5" (:model (ex-data error)))))))))

(deftest retrying-can-be-disabled
  (testing "OPENAI_MAX_RETRIES=0 surfaces the 429 on the first attempt"
    (let [!calls (atom 0)]
      (with-redefs [client/env-num (fn [k] (when (= "OPENAI_MAX_RETRIES" k) 0))
                    http/post (responder !calls [(rate-limited)])
                    client/sleep-ms! (fn [_] (throw (AssertionError. "must not sleep")))]
        (let [error (try (client/create-chat-completion {:model "gpt-4o" :messages []} unroutable)
                         (catch Exception e e))]
          (is (= 1 @!calls))
          (is (= 1 (:attempts (ex-data error))))
          (is (= 0 (:max-retries (ex-data error)))))))))

(deftest the-stubs-are-what-served-the-calls
  (testing "the request went to the endpoint under test, not to a real provider"
    (let [!urls (atom [])]
      (with-redefs [http/post (fn [url _opts] (swap! !urls conj url) ok-response)]
        (client/create-chat-completion {:model "gpt-4o" :messages []} unroutable))
      (is (= ["http://127.0.0.1:9/chat/completions"] @!urls))))

  (testing "the no-network fixture really would catch an escape"
    (is (thrown? AssertionError (clj-http.core/request {:url "http://example.com"})))))
