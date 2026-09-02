(ns digdir.api.rate-limit-api-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.api.rate-limit-api :as rl]))

(use-fixtures :each (fn [f] (rl/reset-store!) (f) (rl/reset-store!)))

(defn- echo-handler [_] {:status 200 :body "ok"})

(deftest passes-through-when-unauthenticated
  (testing "Requests without :api-key/id bypass the limiter"
    (let [h (rl/wrap-api-rate-limit echo-handler {:max-requests 1 :window-ms 60000})]
      (is (= 200 (:status (h {}))))
      (is (= 200 (:status (h {}))))
      (is (= 200 (:status (h {})))))))

(deftest counts-requests-per-key
  (testing "Hitting the cap returns 429"
    (let [h (rl/wrap-api-rate-limit echo-handler {:max-requests 2 :window-ms 60000})]
      (is (= 200 (:status (h {:api-key/id "k1"}))))
      (is (= 200 (:status (h {:api-key/id "k1"}))))
      (let [resp (h {:api-key/id "k1"})]
        (is (= 429 (:status resp)))
        (is (= "rate_limited"
               (get-in (json/parse-string (:body resp) keyword)
                       [:error :code])))))))

(deftest keys-are-isolated
  (testing "Two keys count independently"
    (let [h (rl/wrap-api-rate-limit echo-handler {:max-requests 1 :window-ms 60000})]
      (is (= 200 (:status (h {:api-key/id "a"}))))
      (is (= 200 (:status (h {:api-key/id "b"}))))
      (is (= 429 (:status (h {:api-key/id "a"})))))))
