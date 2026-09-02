(ns digdir.api.discarded-fields-log-test
  "The discarded-field detector is a measurement, and must stay one.

   Coercion strips undeclared request-body keys before the handler runs, so a
   caller who sends a wrong field name gets a 2xx and a request that quietly
   did less than they asked (#172). The middleware under test makes that
   visible in the log without changing it (#174).

   Two properties matter more than the logging itself:

     - it changes nothing — same status, same body, with and without
     - it logs field NAMES and never values, because request bodies on this
       API carry API keys and config secrets

   The second is why this file exists. `add the values, they would be more
   useful` is the obvious next edit for someone who has not read the issue, and
   a test is the only thing standing in its way."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [taoensso.telemere :as t]
            [digdir.api.routes.endpoints :as endpoints]))

(defn- run-middleware
  "Drive the middleware directly with a pre/post-coercion request shape."
  [{:keys [body-params coerced]}]
  (let [handler-calls (atom 0)
        wrapped ((:wrap endpoints/log-discarded-request-fields-middleware)
                 (fn [_req] (swap! handler-calls inc) {:status 201 :body "created"}))
        response (wrapped {:uri "/console-api/api-keys"
                           :request-method :post
                           :body-params body-params
                           :parameters {:body coerced}})]
    {:response response :handler-calls @handler-calls}))

(deftest passes-the-request-through-unchanged
  (testing "a discarded field changes nothing about the response"
    (let [clean (run-middleware {:body-params {:name "K"} :coerced {:name "K"}})
          dropped (run-middleware {:body-params {:name "K" :typo-field "x"}
                                   :coerced {:name "K"}})]
      (is (= (:response clean) (:response dropped))
          "the response must be identical whether or not a field was discarded")
      (is (= 1 (:handler-calls clean)))
      (is (= 1 (:handler-calls dropped))
          "the handler still runs — this observes, it does not reject"))))

(deftest logs-only-when-something-was-discarded
  (testing "a clean request produces no line"
    ;; A detector that fires on every request is noise, and noise is how a
    ;; measurement gets switched off before it has reported anything.
    (let [{:keys [signals]} (t/with-signals
                              (run-middleware {:body-params {:name "K"} :coerced {:name "K"}}))]
      (is (empty? signals))))

  (testing "a discarded field produces exactly one"
    (let [{:keys [signals]} (t/with-signals
                              (run-middleware {:body-params {:name "K" :typo-field "x" :other 1}
                                               :coerced {:name "K"}}))]
      (is (= 1 (count signals))))))

(deftest logs-names-and-never-values
  (testing "the discarded field's VALUE must not reach the log"
    ;; The values here are what a real leak would look like: a caller who sends
    ;; `api-key` instead of `apiKey` would put a live credential in a field we
    ;; discard, and logging the value would then write it to disk.
    (let [secret "rag_super_secret_value"
          {:keys [signals]} (t/with-signals
                              (run-middleware
                                {:body-params {:name "K" :api-key secret :password "hunter2"}
                                 :coerced {:name "K"}}))
          sig (first signals)
          names (get-in sig [:data :discarded-field-names])
          ;; Force the message rather than pr-str-ing the signal: an unrealized
          ;; delay would print as an object and every assertion below would
          ;; pass without reading anything.
          rendered (str/join " " (map #(force (:msg_ %)) signals))
          ;; The value must not leak through EITHER channel. Since #183 moved
          ;; the field names into :data, checking only the message would have
          ;; quietly stopped covering the place the data now lives.
          everything (str rendered " " (pr-str (:data sig)))]
      (is (some #{:api-key} names)
          "the field name is the point of the measurement")
      (is (some #{:password} names))
      (is (not (str/includes? everything secret))
          "the VALUE must never be logged — request bodies carry credentials")
      (is (not (str/includes? everything "hunter2"))
          "no discarded value may appear, whatever its key, in :msg OR :data"))))

(deftest the-log-line-says-what-to-do-with-it
  (testing "someone meeting this in a log has never read #174"
    ;; Since #183 this event is emitted in the opts-map form, so the id and
    ;; the fields are QUERYABLE on the signal rather than rendered into prose.
    ;; The human-readable guidance stays in :msg, where a person reading a log
    ;; will meet it.
    (let [{:keys [signals]} (t/with-signals
                              (run-middleware {:body-params {:name "K" :typo-field "x"}
                                               :coerced {:name "K"}}))
          sig (first signals)
          message (force (:msg_ sig))]
      (is (= ::endpoints/request-fields-discarded (:id sig))
          "the id must be queryable, not spelled inside a sentence")
      (is (= [:typo-field] (get-in sig [:data :discarded-field-names])))
      ;; KEY-SET assertion, not just the fields we care about: a fabricated or
      ;; stray key in :data is invisible to per-value assertions, and a counting
      ;; pipeline built on this event would inherit it silently.
      (is (= #{:uri :method :discarded-field-names}
             (set (keys (:data sig))))
          "assert the whole shape — an extra key would otherwise sit here unnoticed")
      (is (str/includes? message "Not an error")
          "it must say it is not a failure — the request succeeded")
      (is (str/includes? message "#174")
          "it must point at the issue that explains why it is being measured"))))

;; ---------------------------------------------------------------------------
;; Is it actually IN THE CHAIN? (#174)
;; ---------------------------------------------------------------------------
;;
;; Everything above drives `(:wrap …)` directly, which tests the FUNCTION. A
;; detector that was defined, correct, and never wired into a router would pass
;; every one of those tests — and this fleet has already shipped one inert
;; guard that was believed for months because nothing exercised the real path.
;;
;; The entire deferred-flip decision rests on this middleware firing, so the
;; question "is it in the chain" needs a standing answer, not a one-off probe.
;; These tests send a request through `endpoints/api-router` — the same var
;; `digdir.api.http` serves — and assert on what comes out.
;;
;; Verified against a live server on 2026-08-24 as well: POST /api/conversations
;; and POST /api/runtime/config/resolve each emitted exactly one line naming
;; only the undeclared fields, and a canary value planted in the request body
;; appeared zero times in the whole server log.

(defn- through-the-real-router
  "Send a request through the actual api-router and capture any signals.

   The handler beyond the detector may reject the request — these routes
   require grants this test does not have — and that is FINE and is itself
   part of the property: the detector logs before the handler is invoked, so
   the response status cannot influence whether the measurement happens."
  [body]
  (t/with-signals
    (endpoints/api-router
      {:uri "/api/conversations"
       :request-method :post
       :headers {"content-type" "application/json"}
       :body (java.io.ByteArrayInputStream. (.getBytes (str body) "UTF-8"))})))

(deftest the-detector-is-wired-into-the-real-api-router
  (testing "a request through api-router with an undeclared field emits exactly one line"
    (let [{:keys [signals]} (through-the-real-router
                              "{\"title\":\"t\",\"undeclared-in-schema\":\"v\"}")
          discarded (some->> signals
                             (filter #(= ::endpoints/request-fields-discarded (:id %)))
                             first :data :discarded-field-names)]
      (is (= [:undeclared-in-schema] discarded)
          "if this is nil the middleware is not in the chain, whatever the
           direct-invocation tests above report")))

  (testing "and a clean request through the same router emits none"
    (let [{:keys [signals]} (through-the-real-router "{\"title\":\"t\"}")]
      (is (empty? (filter #(= ::endpoints/request-fields-discarded (:id %)) signals))))))

(deftest a-camelcase-alias-is-not-reported-as-discarded
  ;; read-json-body normalises camelCase to kebab-case BEFORE coercion, so
  ;; `filterValue` IS `filter-value` and is accepted. Reporting it would inflate
  ;; the count the flip decision depends on — a false positive here is not
  ;; noise, it is a wrong input to a decision.
  (let [{:keys [signals]} (through-the-real-router
                            "{\"title\":\"t\",\"filterValue\":\"x\"}")]
    (is (empty? (filter #(= ::endpoints/request-fields-discarded (:id %)) signals))
        "filterValue normalises to the declared filter-value and must not count")))

(deftest the-measurement-does-not-depend-on-the-response-status
  ;; The log call precedes `(handler request)`, so a rejected request is
  ;; measured too. Stated because whoever reads the counts must not assume
  ;; every line was a 2xx.
  (let [{:keys [value signals]} (through-the-real-router
                                  "{\"title\":\"t\",\"undeclared-in-schema\":\"v\"}")]
    (is (some #(= ::endpoints/request-fields-discarded (:id %)) signals))
    (is (not= 200 (:status value))
        "this request is rejected downstream — and was still measured")))
