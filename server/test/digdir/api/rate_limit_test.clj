(ns digdir.api.rate-limit-test
  "The limiter must count what the handler reports, not what the status implies.

   #211: the previous version recorded an attempt when the status was `nil`,
   `< 200` or `>= 300`, while its own comment said \"only failed login
   attempts\". On this login those sets are nearly opposite — a wrong
   confirmation code renders a 200 page and a successful login is a 302 — so
   it counted successes and ignored failures. Measured against a live server:
   15 wrong codes, 0 blocks.

   THE ONLY TEST HERE THAT COULD HAVE CAUGHT THAT is
   `a-200-that-reports-failure-is-counted`, because the defect is in WHICH
   RESPONSES REACH THE COUNTER. A unit test on `record-attempt!` passes
   happily while nothing ever calls it — which is what the codebase had."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.api.rate-limit :as rl]
            [digdir.config.core :as config-core]))

(defn- clear-store [f]
  (reset! rl/rate-limit-store {})
  (try (f) (finally (reset! rl/rate-limit-store {}))))

(use-fixtures :each clear-store)

(def ^:private guess-uri "/auth/confirm-email")
(def ^:private send-uri "/auth")

(defn- req
  ([uri] (req uri "10.0.0.1" nil))
  ([uri ip] (req uri ip nil))
  ([uri ip email]
   (cond-> {:request-method :post :uri uri :remote-addr ip}
     email (assoc :params {"email" email}))))

(defn- handler-returning [response] (fn [_] response))

(def ^:private ok-page {:status 200 :body "invalid code"})
(def ^:private redirect {:status 302 :headers {"Location" "/"}})

(defn- run-n
  "Send `n` requests through the middleware, returning the statuses."
  [wrapped request n]
  (mapv (fn [_] (:status (wrapped request))) (range n)))

;; ---------------------------------------------------------------------------
;; The inversion
;; ---------------------------------------------------------------------------

(deftest a-200-that-reports-failure-is-counted
  (testing "a wrong confirmation code renders a 200 and must still be throttled"
    (let [wrapped (rl/wrap-rate-limit
                    (handler-returning (rl/mark-failed-attempt ok-page)))
          statuses (run-n wrapped (req guess-uri) 12)]
      (is (= 10 (count (filter #(= 200 %) statuses)))
          "ten guesses allowed, then the budget is spent")
      (is (= 429 (last statuses))
          "the defect this whole namespace exists for: before #211 every one
           of these was a 200 and the counter was never touched"))))

(deftest a-302-success-is-not-counted
  (testing "a successful login must not consume the guessing budget"
    (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))
          statuses (run-n wrapped (req guess-uri) 20)]
      (is (every? #(= 302 %) statuses)
          "the old limiter blocked here after 10 — it counted successes"))))

(deftest the-marker-never-reaches-the-client
  (let [wrapped (rl/wrap-rate-limit
                  (handler-returning (rl/mark-failed-attempt ok-page)))
        response (wrapped (req guess-uri))]
    (is (= #{:status :body} (set (keys response)))
        "the signal is internal; it must be stripped before the response ships")))

(deftest an-unmarked-failure-page-is-not-counted
  (testing "the signal is explicit — nothing is inferred from a 200 either"
    (let [wrapped (rl/wrap-rate-limit (handler-returning ok-page))]
      (is (every? #(= 200 %) (run-n wrapped (req guess-uri) 15))))))

;; ---------------------------------------------------------------------------
;; Two surfaces, two budgets
;; ---------------------------------------------------------------------------

(deftest send-a-code-counts-every-request
  (testing "the send budget is about volume, not failure — deliberately unlike the guess rule"
    (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))
          statuses (run-n wrapped (req send-uri "10.0.0.2" "a@b.com") 8)]
      (is (= 429 (last statuses)))
      (is (= 5 (count (filter #(= 302 %) statuses)))
          "per-email budget of 5 bites before the per-IP budget of 10"))))

(deftest the-two-surfaces-do-not-share-a-budget
  (testing "exhausting the guess budget leaves send untouched"
    (let [guess (rl/wrap-rate-limit (handler-returning (rl/mark-failed-attempt ok-page)))
          send (rl/wrap-rate-limit (handler-returning redirect))
          ip "10.0.0.3"]
      (run-n guess (req guess-uri ip) 12)
      (is (= 429 (:status (guess (req guess-uri ip)))) "guessing is blocked")
      (is (= 302 (:status (send (req send-uri ip "fresh@b.com"))))
          "asking for a code is a different surface and still works"))))

(deftest per-email-budget-follows-the-address-across-sources
  (testing "one victim cannot be mailed repeatedly from many source addresses"
    (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))
          victim "victim@example.com"
          statuses (mapv (fn [i]
                           (:status (wrapped (req send-uri (str "10.0.1." i) victim))))
                         (range 8))]
      (is (= 429 (last statuses)))
      (is (= 5 (count (filter #(= 302 %) statuses)))
          "a new IP each time, and the email budget still bites"))))

(deftest per-ip-budget-bounds-enumeration-across-addresses
  (testing "one source cannot map who works here by probing many addresses"
    ;; The per-email budget does nothing here — every probe is a different
    ;; address with a fresh budget. Only the IP bucket bounds it, which is
    ;; why POST /auth carries both.
    (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))
          ip "10.0.2.1"
          statuses (mapv (fn [i]
                           (:status (wrapped (req send-uri ip (str "probe" i "@example.com")))))
                         (range 13))]
      (is (= 10 (count (filter #(= 302 %) statuses)))
          "ten distinct addresses probed, then the source is blocked")
      (is (= 429 (last statuses))))))

(deftest a-request-without-an-email-still-gets-an-ip-budget
  (testing "a missing bucket value is skipped, not lumped into a shared one"
    ;; Bucketing every anonymous caller under one placeholder key would let
    ;; any one of them lock out all the others.
    (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))
          statuses (run-n wrapped (req send-uri "10.0.3.1") 12)]
      (is (= 10 (count (filter #(= 302 %) statuses))))
      (is (= 429 (last statuses))))))

(deftest unrelated-endpoints-are-untouched
  (let [wrapped (rl/wrap-rate-limit (handler-returning redirect))]
    (is (every? #(= 302 %) (run-n wrapped (req "/api/conversations" "10.0.4.1") 30)))
    (is (every? #(= 302 %)
                (mapv (fn [_] (:status (wrapped {:request-method :get :uri send-uri
                                                 :remote-addr "10.0.4.2"})))
                      (range 30)))
        "GET /auth renders the form; only the POST is budgeted")))

;; ---------------------------------------------------------------------------
;; Carried over from the pre-#211 namespace
;; ---------------------------------------------------------------------------
;;
;; These predate the rewrite and still hold. One of the originals is
;; deliberately NOT carried over, and it is worth saying why, because it is
;; the reason the inversion survived review:
;;
;;   test-rate-limit-only-counts-failures asserted that 20 SUCCESSFUL POST
;;   /auth requests all pass — with a handler returning 200. It passed, and it
;;   certified exactly the property the docstring claimed. But 200 is the
;;   status a FAILED confirmation code returns on this login, and a real
;;   success is a 302, so the test asserted the intent using the one status
;;   that made the buggy code look correct. What it actually pinned was "a 200
;;   is not counted" — which was the defect.
;;
;;   Its intent lives on in `a-302-success-is-not-counted`, using the status a
;;   success actually has. `POST /auth` now counts every request by design, so
;;   the original could not be carried over unchanged in any case.

(defn- with-trust-x-forwarded-for [trust? f]
  (with-redefs [config-core/rate-limit-trust-x-forwarded-for? (constantly trust?)]
    (f)))

(deftest get-client-ip-prefers-remote-addr-when-the-proxy-is-not-trusted
  (with-trust-x-forwarded-for false
    #(is (= "192.168.1.1"
            (rl/get-client-ip {:remote-addr "192.168.1.1"
                               :headers {"x-forwarded-for" "10.0.0.1"}})))))

(deftest get-client-ip-uses-x-forwarded-for-when-trusted
  (with-trust-x-forwarded-for true
    #(is (= "10.0.0.1"
            (rl/get-client-ip {:remote-addr "192.168.1.1"
                               :headers {"x-forwarded-for" "10.0.0.1"}})))))

(deftest get-client-ip-falls-back-to-unknown
  (with-trust-x-forwarded-for false
    #(is (= "unknown" (rl/get-client-ip {:headers {}})))))

(deftest one-exhausted-source-does-not-block-another
  (testing "buckets are per source, so one abuser cannot lock everyone out"
    (let [wrapped (rl/wrap-rate-limit (handler-returning (rl/mark-failed-attempt ok-page)))]
      (run-n wrapped (req guess-uri "10.0.5.1") 10)
      (is (= 429 (:status (wrapped (req guess-uri "10.0.5.1")))))
      (is (= 200 (:status (wrapped (req guess-uri "10.0.5.2"))))
          "a different source has its own budget"))))

(deftest old-entries-are-cleaned-up
  (testing "entries older than an hour are dropped"
    (let [now (System/currentTimeMillis)]
      (reset! rl/rate-limit-store
              {"send-a-code|ip|old" {:attempts 5 :last-reset (- now (* 2 60 60 1000))}
               "send-a-code|ip|recent" {:attempts 3 :last-reset (- now (* 30 60 1000))}})
      (#'rl/cleanup-old-entries!)
      (is (nil? (get @rl/rate-limit-store "send-a-code|ip|old")))
      (is (some? (get @rl/rate-limit-store "send-a-code|ip|recent"))))))

(deftest the-window-rolls-per-bucket
  (testing "an exhausted bucket recovers once its window has passed"
    (let [sixteen-minutes-ago (- (System/currentTimeMillis) (* 16 60 1000))]
      (reset! rl/rate-limit-store
              {"guess-a-code|ip|10.0.0.7" {:attempts 10 :last-reset sixteen-minutes-ago}})
      (let [wrapped (rl/wrap-rate-limit (handler-returning (rl/mark-failed-attempt ok-page)))]
        (is (= 200 (:status (wrapped (req guess-uri "10.0.0.7"))))
            "the 15-minute window expired, so the bucket resets rather than blocking")))))
