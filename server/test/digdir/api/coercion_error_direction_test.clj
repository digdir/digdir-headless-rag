(ns digdir.api.coercion-error-direction-test
  "#438 — the shared coercion error handler must answer for the RIGHT DIRECTION.

   Two defects, both latent until response coercion is enabled (#432 stage 2) and
   both unavoidable afterwards:

     1. `Request validation failed` was returned for BOTH directions, so a caller
        who sent a valid request was told they had sent something wrong when the
        SERVER had emitted something wrong.

     2. `:value` — the payload that failed — was echoed for both. On the request
        side that is what the caller just sent, so it is harmless and useful. On
        the response side it is the HANDLER'S OUTPUT, handed to whoever triggered
        the failure.

   THE DISCRIMINATOR IS DIRECTION, NOT ENVIRONMENT, and that is the load-bearing
   choice: this codebase has no `am I in production` signal for the handler to
   read, so an environment gate would have meant inventing one — and a rule that
   is wrong only in production is a rule nobody exercises before it matters.

   These tests drive the REAL middleware from `endpoints/api-router-options`, so
   they fail if the wiring changes and not only if the two helpers do."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.routes.endpoints :as endpoints]
            [reitit.ring :as reitit-ring]
            [reitit.ring.coercion :as rrc]))

(def ^:private handler-output
  "Stands in for whatever a handler emits. Named so an assertion about it
   leaking reads as what it is."
  {:secret "internal-only"})

(defn- app-with-response-coercion []
  (reitit-ring/ring-handler
   (reitit-ring/router
    ["/probe"
     {:get {:responses {200 {:body [:map [:required-field string?]]}}
            :handler (fn [_] {:status 200 :body handler-output})}
      :post {:parameters {:body [:map [:needed string?]]}
             :handler (fn [_] {:status 200 :body {:ok true}})}}]
    (update-in endpoints/api-router-options [:data :middleware]
               conj rrc/coerce-response-middleware))))

(defn- body-of [response]
  (json/parse-string (:body response) true))

(deftest response-failure-says-response-and-withholds-the-body
  (let [app (app-with-response-coercion)
        response (app {:request-method :get :uri "/probe"})
        body (body-of response)]

    (testing "the status already distinguished the directions; now the message does"
      (is (= 500 (:status response)))
      (is (= "Response validation failed" (:error body))
          "a caller who sent a valid request must not be told their request failed"))

    (testing "the handler's output is NOT returned to the caller"
      (is (not (contains? (:details body) :value))
          "`:value` on the response side is the handler's output, not the caller's input")
      ;; Asserted on the serialized body as well, because a nested occurrence
      ;; would not show up in a top-level key check — and the leak that matters
      ;; is the string reaching the wire, wherever it sits in the structure.
      (is (not (str/includes? (:body response) "internal-only"))
          "the handler's payload must not appear anywhere in the response bytes"))

    (testing "and the failure is still diagnosable without it"
      (is (= {:required-field ["missing required key"]} (:humanized (:details body)))
          "humanized names the offending key, which is what makes withholding :value affordable"))))

(deftest request-failure-is-unchanged
  ;; The fix must not be "strip :value everywhere". On the request side echoing
  ;; it is the useful behaviour, and a change that broke this would be a
  ;; regression dressed as a security fix.
  (let [app (app-with-response-coercion)
        response (app {:request-method :post :uri "/probe"
                       :headers {"content-type" "application/json"}
                       :body-params {:wrong "thing"}})
        body (body-of response)]
    (is (= 400 (:status response)))
    (is (= "Request validation failed" (:error body)))
    (is (contains? (:details body) :value)
        "on the request side :value is what the caller sent, and showing it is the point")))
