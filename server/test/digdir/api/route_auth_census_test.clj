(ns digdir.api.route-auth-census-test
  "EVERY route of the three
   compiled routers `wrap-api-routes` and the console compose declares how it is
   authorized, in its route data under `:auth`:
   - the API-key router: `{:key {:derived [fn ... authorize-scope!]}}`, a CHAIN
     of fully qualified vars from the handler to `digdir.api.auth/authorize-scope!`,
     each of which references the next IN ITS CODE (a call, a var-quote, an
     `apply`, or a fn value; a docstring or comment mention is not code), or
     `{:key {:none reason}}` for a route that returns no tenant data;
   - the debug router: `{:operator-secret \"RAG_DEBUG_API_KEY\" :reason ...}`;
   - the console router: `{:jwt-admin true}`.
   The census reads the COMPILED routers, not a list. A declaration is a claim:
   the behavioural matrix over every declared `:derived` route is
   `digdir.api.tenant-scope-doors-test`, and it is pinned to this census."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.routes.endpoints :as ep]
            [reitit.core :as r]
            [reitit.ring :as ring]))

(def routers
  "The three compiled routers, by the kind of declaration each must carry."
  {:key (ring/get-router ep/api-router)
   :operator-secret (ring/get-router ep/debug-router)
   :jwt-admin (ring/get-router ep/console-api-router)})

(defn declarations
  "Every `{:path :method :auth}` of `router`."
  [router]
  (for [[path data] (r/routes router)
        m [:get :post :put :delete :patch]
        :when (get data m)]
    {:path path :method m :auth (get-in data [m :auth])}))

(def ^:private eof (Object.))

(defn- code-forms
  "The top-level forms of `text`, read as CODE: a name in a string or a comment
   is not a reference."
  [text]
  (binding [*read-eval* false
            *reader-resolver* (reify clojure.lang.LispReader$Resolver
                                (currentNS [_] 'user) (resolveClass [_ s] s)
                                (resolveAlias [_ s] s) (resolveVar [_ s] s))]
    (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. text))]
      (doall (take-while #(not (identical? eof %)) (repeatedly #(read {:eof eof :read-cond :allow} rdr)))))))

(defn- defining-form
  "The `def`/`defn` form that defines the var named by fully qualified `sym`."
  [sym]
  (when-let [v (find-var sym)]
    (let [nm (symbol (name sym))]
      (some #(when (and (seq? %) ('#{def defn defn- defmacro} (first %)) (= nm (second %))) %)
            (code-forms (slurp (io/resource (:file (meta v)))))))))

(defn references?
  "Whether `form`'s CODE names `target` (a var), resolving each symbol in `in-ns`."
  [form in-ns target]
  (boolean (some #(and (symbol? %) (= target (try (ns-resolve in-ns %) (catch Exception _ nil))))
                 (tree-seq coll? seq form))))

(defn- chain-problems
  "What is wrong with a `:derived` chain, or nil."
  [chain]
  (let [syms (vec chain)]
    (cond
      (empty? syms) [:empty-chain]
      (not= 'digdir.api.auth/authorize-scope! (peek syms)) [:chain-does-not-end-at-authorize-scope]
      :else
      (seq (for [[from to] (partition 2 1 syms)
                 :let [form (defining-form from)]
                 :when (or (nil? (find-var to)) (nil? form)
                           (not (references? form (the-ns (symbol (namespace from))) (find-var to))))]
             [:no-reference from to])))))

(defn problems
  "Every route of `router` whose declaration is missing or wrong for `kind`."
  [kind router]
  (for [{:keys [path method auth]} (declarations router)
        :let [p (case kind
                  :key (let [{:keys [derived none] :as k} (:key auth)]
                         (cond
                           (not= #{:key} (set (keys auth))) :undeclared-or-wrong-kind
                           (not= 1 (count k)) :one-of-derived-or-none
                           derived (chain-problems derived)
                           (not (and (string? none) (seq none))) :none-without-reason))
                  :operator-secret (when-not (and (= "RAG_DEBUG_API_KEY" (:operator-secret auth)) (string? (:reason auth)))
                                     :undeclared-or-wrong-kind)
                  :jwt-admin (when-not (= {:jwt-admin true} auth) :undeclared-or-wrong-kind))]
        :when p]
    [method path p]))

(defn derived-routes
  "`#{[method path]}` of every API-key route declared `:derived`: the routes the
   behavioural matrix must cover."
  []
  (set (for [{:keys [path method auth]} (declarations (:key routers))
             :when (get-in auth [:key :derived])]
         [method path])))

(deftest every-route-of-every-compiled-router-declares-its-authorization
  (doseq [[kind router] routers]
    (testing (name kind)
      (is (seq (declarations router)) "PREMISE: the router has routes")
      (is (empty? (problems kind router))))))

(deftest the-census-counts
  (is (= {:key 18 :operator-secret 9 :jwt-admin 25} (update-vals routers (comp count declarations)))
      "the router sizes this census was written against; a new route must be declared, and this count moved")
  (is (= 14 (count (derived-routes))) "the tenant-taking API-key routes"))

(deftest the-census-can-see-what-it-must-refuse
  (testing "CONTROL: an undeclared route is found"
    (is (seq (problems :key (ring/router [["/api/x" {:get {:handler identity}}]])))))
  (testing "CONTROL: a derived fn that never reaches authorize-scope! is found"
    (is (seq (chain-problems '[digdir.api.routes.datasets/list-config-nodes-handler
                               digdir.api.routes.datasets/public-node-summary
                               digdir.api.auth/authorize-scope!]))))
  (testing "CONTROL: a chain not ending at authorize-scope! is found"
    (is (= [:chain-does-not-end-at-authorize-scope] (chain-problems '[digdir.api.routes.datasets/list-config-nodes-handler]))))
  (testing "a var-quote and an apply count as a reference; a docstring or comment does not"
    (is (some? (find-var 'digdir.api.auth/authorize-scope!)) "PREMISE: the one decision exists")
    (let [in (the-ns 'digdir.api.routes.datasets)
          target (find-var 'digdir.api.auth/authorize-scope!)
          refs? #(references? (first (code-forms %)) in target)]
      (is (refs? "(defn h [r] (#'api-auth/authorize-scope! r {}))"))
      (is (refs? "(defn h [r] (apply api-auth/authorize-scope! [r {}]))"))
      (is (refs? "(defn h [r] (mapv api-auth/authorize-scope! [r]))"))
      (is (not (refs? "(defn h \"calls api-auth/authorize-scope!\" [r] ;; api-auth/authorize-scope!\n r)"))))))
