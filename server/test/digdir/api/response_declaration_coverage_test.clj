(ns digdir.api.response-declaration-coverage-test
  "#432 stage 1 — the coverage boundary, made countable.

   Ten of the public operations declare a response body; the rest are listed in
   `endpoints/response-schema-not-established`. THE POINT OF THIS FILE IS THAT
   THE SPLIT CANNOT GO QUIET. Every operation must be in exactly one of those two
   sets, so a route added without either a schema or an entry fails here, and an
   entry deleted without a schema being added fails here too.

   WHY A PARTIAL DECLARATION IS THE RIGHT SHAPE, since a partial anything is
   usually the wrong one: declaring all forty would mean porting response schemas
   that name no `required` fields, and a malli map whose every key is optional
   validates `{}`. That is a guard with no path on which it can fail — the #323
   defect — and it would read as complete. This split classifies 100% of
   operations while enforcing 25%, and those are different numbers doing
   different jobs.

   The enumeration is the deliverable, not the caveat: before it, `which
   endpoints have an established response contract` had no answer anywhere."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.routes.endpoints :as endpoints]
            [reitit.core :as r]
            [reitit.ring :as reitit-ring]))

(defn- operations
  "Every [method path] the two public routers actually expose.

   Taken from the COMPILED router rather than by reading the route vectors: a
   route vector carrying both data and children registers only the children,
   silently (see the note on `api-routes`), so a source-level walk can count an
   operation the router does not serve."
  []
  (vec
   (for [routes [endpoints/api-routes endpoints/console-api-routes]
         [path data] (r/routes (r/router routes))
         [method m] data
         :when (and (keyword? method) (map? m) (:handler m))]
     [(name method) path])))

(defn- declared
  "Operations whose route data declares a response body."
  []
  (set
   (for [routes [endpoints/api-routes endpoints/console-api-routes]
         [path data] (r/routes (r/router routes))
         [method m] data
         :when (and (keyword? method) (map? m) (seq (:responses m)))]
     [(name method) path])))

(deftest every-operation-is-declared-or-enumerated
  (let [ops (set (operations))
        dec (declared)
        enum endpoints/response-schema-not-established]

    (testing "the walk found the routers at all"
      ;; Without this the two assertions below pass vacuously over empty sets —
      ;; the failure mode this whole issue is about.
      (is (< 30 (count ops))
          "expected the public surface to be dozens of operations, not a handful"))

    (testing "no operation is both declared and enumerated"
      (is (empty? (set/intersection dec enum))
          (str "declared AND listed as not-established: "
               (sort (set/intersection dec enum)))))

    (testing "every operation is accounted for by exactly one of the two sets"
      (let [unaccounted (set/difference ops dec enum)]
        (is (empty? unaccounted)
            (str "these operations neither declare a response body nor appear in "
                 "response-schema-not-established. Add a schema, or add an entry "
                 "saying the emitted key set is not established: "
                 (sort unaccounted)))))

    (testing "the enumeration names no route that does not exist"
      ;; An entry for a deleted route is stale coverage: it makes the remaining
      ;; work look larger and quietly stops describing anything.
      (let [phantom (set/difference enum ops)]
        (is (empty? phantom)
            (str "listed as not-established but not served by any router: "
                 (sort phantom)))))))

(deftest the-coverage-numbers-are-pinned
  ;; Pinned so that the split is a number somebody has to change on purpose.
  ;; Raising `declared` and lowering `enumerated` together is the intended edit;
  ;; lowering `enumerated` alone is the edit this catches.
  (let [dec (count (declared))
        enum (count endpoints/response-schema-not-established)]
    (is (= 10 dec)
        (str "operations declaring a response body changed to " dec
             ". If that was deliberate, update this number in the same commit."))
    (is (= 30 enum)
        (str "operations enumerated as not-established changed to " enum
             ". Removing an entry requires adding its schema."))))

(deftest enforcement-follows-the-flag-and-nothing-else
  ;; SUCCESSOR TO `stage-1-is-demonstrably-inert`, which asserted UNCONDITIONALLY
  ;; that a schema-violating response reaches the caller untouched. That was the
  ;; right assertion while stage 1 was inert by construction, and it became the
  ;; wrong one the moment enforcement became switchable — it would have failed in
  ;; CI for the correct reason, which is the definition of an over-specified
  ;; check (#323 row 10).
  ;;
  ;; The INVARIANT that survives both stages: enforcement happens if and only if
  ;; `response-coercion-enabled?` says so. That is true in production with the
  ;; flag unset, true in CI with it set, and false in exactly the situation worth
  ;; catching — the middleware being wired unconditionally.
  ;;
  ;; Built with `endpoints/api-router-options`, the options the application
  ;; itself uses, so it exercises the real chain rather than one assembled to
  ;; prove the point.
  (let [wrong-schema [:map [:a-field-the-handler-never-returns string?]]
        app (reitit-ring/ring-handler
             (reitit-ring/router
              ["/enforcement-probe"
               {:get {:responses {200 {:body wrong-schema}}
                      :handler (fn [_] {:status 200 :body {:actually "unvalidated"}})}}]
              endpoints/api-router-options))
        response (app {:request-method :get :uri "/enforcement-probe"})]

    (if endpoints/response-coercion-enabled?
      (testing "flag ON — a schema-violating response is refused"
        (is (= 500 (:status response))
            "coercion is enabled, so this must not reach the caller as a 200"))
      (testing "flag OFF — a schema-violating response reaches the caller unchanged"
        (is (= 200 (:status response))
            "coercion is disabled, so declarations must not gate traffic")
        (is (= {:actually "unvalidated"} (:body response))
            "the body reaches the caller untouched, schema notwithstanding")))

    (testing "and the declaration really was present on the route"
      ;; Without this, both branches above pass just as well against a route that
      ;; declares nothing, which would prove nothing about declarations.
      (is (some? (get-in (r/match-by-path
                          (r/router ["/enforcement-probe"
                                     {:get {:responses {200 {:body wrong-schema}}
                                            :handler identity}}])
                          "/enforcement-probe")
                         [:data :get :responses 200 :body]))))))

(deftest the-middleware-is-wired-if-and-only-if-the-flag-says-so
  ;; The mechanism behind the behaviour above, asserted directly so the REASON is
  ;; pinned and not only its consequence.
  ;;
  ;; Note the shape: not "coercion is off", but "the wiring agrees with the flag".
  ;; An unconditional `conj` would satisfy an off-only assertion in CI and ship
  ;; enforcement to production; this catches that, which the previous version
  ;; could not once the flag existed.
  (let [wired? (boolean (some #(re-find #"coerce-response" (str %))
                              (get-in endpoints/api-router-options [:data :middleware])))]
    (is (= endpoints/response-coercion-enabled? wired?)
        (str "response coercion wiring disagrees with the flag. flag="
             endpoints/response-coercion-enabled? " wired=" wired?
             ". Production must be unaffected unless DIGDIR_RESPONSE_COERCION=true."))))

(deftest production-default-is-off
  ;; The default is the safety property, so it is asserted rather than assumed.
  ;; Reads the environment directly instead of the def, so it still means
  ;; something if the def's logic is changed.
  (let [raw (System/getenv "DIGDIR_RESPONSE_COERCION")]
    (is (= (= "true" raw) endpoints/response-coercion-enabled?)
        (str "the flag must be true only for the literal string \"true\"; env=" (pr-str raw)))
    (when (nil? raw)
      (is (false? endpoints/response-coercion-enabled?)
          "with the variable unset — production's situation — enforcement must be off"))))
