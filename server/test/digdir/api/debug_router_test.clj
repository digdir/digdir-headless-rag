(ns digdir.api.debug-router-test
  "the debug router is an OPERATOR door.
   Its authority is one deployment secret, `RAG_DEBUG_API_KEY`, not an API key's
   grants, so it is declared `:operator-secret` and EXEMPT from the tenant axis.
   The exemption rests on premises, each pinned here over EVERY compiled debug
   route, through the production composition (`http/wrap-api-routes`):
   1. the secret unset, or blank → 503, never 200;
   2. a wrong secret → 401;
   3. the right secret gets past the door (the control: 1 and 2 are not vacuous);
   4. an API key - any, even one marked all-tenant - without the secret → 401;
   5. the secret is compared in constant time, through THE one comparison
      (`auth/secure-digest=`): pinned structurally, since a timing test would be
      flaky."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [digdir.api.http :as http]
            [digdir.api.routes.endpoints :as ep]
            [reitit.core :as r]
            [reitit.ring :as ring]))

(def ^:private secret "SYNTHSECRET-debug")

(def ^:private debug-routes
  "Every route of the COMPILED debug router, as `[method path]`."
  (for [[path data] (r/routes (ring/get-router ep/debug-router))
        m [:get :post :put :delete :patch]
        :when (get data m)]
    [m path]))

(def ^:private app (http/wrap-api-routes (fn [_] {:status 599 :body "fell through"})))

(defn- status [configured headers [m path]]
  (with-redefs [ep/debug-api-key-secret (constantly configured)]
    (:status (app {:request-method m :uri path :headers headers}))))

(deftest the-router-has-the-routes-this-pins
  (is (= 9 (count debug-routes)) "PREMISE: the census covers the whole compiled debug router")
  (is (every? #(str/starts-with? (second %) "/api/debug") debug-routes)))

(deftest the-secret-unset-or-blank-closes-every-route
  (doseq [route debug-routes
          configured [nil "" "   "]]
    (is (= 503 (status configured {"x-debug-api-key" secret} route)) (str (pr-str route) " with the secret " (pr-str configured)))
    (is (= 503 (status configured {} route)) (str (pr-str route) " with the secret " (pr-str configured) " and no header"))))

(deftest a-wrong-secret-is-refused-on-every-route
  (doseq [route debug-routes
          provided [nil "" "wrong" (str secret "x") (subs secret 1)]]
    (is (= 401 (status secret (cond-> {} provided (assoc "x-debug-api-key" provided)) route))
        (str (pr-str route) " with " (pr-str provided)))))

(deftest an-api-key-is-not-the-operator-secret
  (doseq [route debug-routes]
    (is (= 401 (status secret {"x-api-key" "rag_any_key" "authorization" "Bearer rag_any_key"} route))
        (str (pr-str route) ": an API key without the debug secret"))))

(deftest the-right-secret-gets-past-the-door
  ;; The CONTROL. Only routes that refuse an EMPTY query by coercion (400), so
  ;; no handler runs against a store: getting a 400 there proves the door let
  ;; the request through.
  (let [coerced (filter (fn [[m p]] (and (= :get m) (not (#{"/api/debug/last-invocation" "/api/debug/agent-resolution"} p))))
                        debug-routes)]
    (is (= 6 (count coerced)) "PREMISE: the six tenant-taking routes")
    (doseq [route coerced]
      (is (not (#{401 503} (status secret {"x-debug-api-key" secret} route))) (pr-str route)))))

(deftest the-secret-is-compared-in-constant-time
  (let [src (slurp "src/digdir/api/routes/endpoints.clj")
        forms (binding [*read-eval* false
                        *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                            (currentNS [_] 'user) (resolveClass [_ s] s)
                                            (resolveAlias [_ s] s) (resolveVar [_ s] s))]
                (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. src))]
                  (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof :read-cond :allow} rdr))))))
        mw (some #(when (and (seq? %) (= 'defn (first %)) (= 'wrap-debug-api-key-auth (second %))) %) forms)
        calls (filter seq? (tree-seq coll? seq mw))
        compares-secret? (fn [c] (some #{'provided-key} c))]
    (is (some? mw) "PREMISE: the middleware is found")
    (is (some #(= 'auth/secure-digest= (first %)) calls) "the secret goes through THE constant-time comparison")
    (is (not-any? #(and (#{'= 'not= '.equals 'identical?} (first %)) (compares-secret? %)) calls)
        "and through no other comparison")))
