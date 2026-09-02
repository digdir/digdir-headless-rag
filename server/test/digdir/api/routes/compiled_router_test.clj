(ns digdir.api.routes.compiled-router-test
  "Routing assertions against the COMPILED router (#112).

   Three documented /api discovery routes were absent from the production
   artifact: a reitit route vector that carries both data and children
   registers only the children, silently - no warning, no error, no startup
   failure. Reading the route table in `endpoints.clj` showed all of them
   present, which is why this survived.

   So the assertions here take intent from the source and TRUTH FROM THE
   COMPILED ROUTER. A test that read `endpoints.clj` would have passed on the
   broken code."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.routes.endpoints :as ep]
            [reitit.core :as r]
            [reitit.ring :as ring]))

(def ^:private all-route-trees
  "Every route tree the server compiles. #112 checked only the first."
  [["api" ep/api-routes]
   ["console-api" ep/console-api-routes]
   ["debug" ep/debug-routes]])

(defn- router [] (ring/router ep/api-routes ep/api-router-options))

(def ^:private documented-routes
  "Every public /api and /v1 path, with the template it must resolve to.

   Adding a documented endpoint means adding a line here. That is the point:
   the list is the intent, and the compiled router is what gets checked."
  {"/api/datasets"                 "/api/datasets"
   "/api/datasets/d1"              "/api/datasets/:dataset-id"
   "/api/skills/some-skill/execute" "/api/skills/:id/execute"
   "/api/conversations"            "/api/conversations"
   "/api/conversations/c1"         "/api/conversations/:id"
   "/api/mcp"                      "/api/mcp"
   "/v1/models"                    "/v1/models"
   "/v1/chat/completions"          "/v1/chat/completions"})

(deftest every-documented-route-resolves-in-the-compiled-router
  (testing "the discovery endpoints an integrator reaches for first"
    (let [rtr (router)]
      (doseq [[path expected-template] documented-routes]
        (let [m (r/match-by-path rtr path)]
          (is (some? m) (str path " does not resolve - it would 404 in the artifact"))
          (is (= expected-template (str (:template m)))
              (str path " resolved to the wrong route")))))))

(def ^:private deleted-routes
  "Public paths removed in #350, and the paths that must NOT start resolving
   again as a side effect of removing them.

   The listing endpoints went because `tools/list` and GET /v1/models already
   enumerate the same (agent, mode) axis, filtered per API key. Recorded here
   rather than simply deleted from `documented-routes`, because a route can come
   back by accident: the parent /skills vector still exists to carry `execute`,
   and re-adding a child to it is exactly how one would reappear.

   /api/skills itself is listed because the parent is no longer addressable -
   only /skills/:id/execute is. A bare [\"\" {...}] child added back would
   restore it silently, which is the failure mode the `api-routes` docstring
   already warns about in the other direction."
  ["/api/skills"
   "/api/skills/tools"
   "/api/skills/some-skill"
   "/api/modes"
   "/api/modes/some-mode"])

(deftest deleted-routes-stay-deleted
  (testing "#350 removed the listing surface; only execute remains under /skills"
    (let [rtr (router)]
      (doseq [path deleted-routes]
        (is (nil? (r/match-by-path rtr path))
            (str path " resolves again. It was deleted in #350 - if it is being "
                 "restored deliberately, remove it from deleted-routes and say why.")))
      (is (= "/api/skills/:id/execute"
             (str (:template (r/match-by-path rtr "/api/skills/some-skill/execute"))))
          "execute survives the deletion - its contract was settled in #27"))))

(defn- http-method-keys
  [data]
  (when (map? data)
    (seq (filter data [:get :post :put :delete :patch :head :options]))))

(defn- endpoint-parents-with-children
  "Walk the route tree and return every vector that defines an endpoint (its
   data map carries an HTTP method) AND also has child routes. Reitit drops
   the parent in exactly that shape."
  [route-vec path-so-far]
  (when (vector? route-vec)
    (let [[segment & more] route-vec
          data (when (map? (first more)) (first more))
          children (filter vector? more)
          here (str path-so-far segment)]
      (concat
        (when (and (http-method-keys data) (seq children)) [here])
        (mapcat #(endpoint-parents-with-children % here) children)))))

(deftest no-route-defines-an-endpoint-and-children-in-the-same-vector
  (testing "the shape that silently deletes a route"
    ;; The compiled-router assertions above catch a dropped route we know to
    ;; look for. This catches the SHAPE, so a route added later cannot
    ;; introduce the defect without either failing here or being added to the
    ;; documented list above.
    ;; EVERY router, not just the public one. Scoping this to ep/api-routes
    ;; was the gap that let the identical defect sit in the console router
    ;; from #112 until #231 - the guard was right and its reach was not.
    (doseq [[label routes] all-route-trees]
      (let [offenders (mapcat #(endpoint-parents-with-children % "") routes)]
        (is (empty? offenders)
            (str label ": these define an endpoint and children in one vector, "
                 "so reitit registers only the children: " (pr-str offenders)))))))

(defn- declared-endpoints
  "Every path in the source tree that declares at least one HTTP method,
   paired with the set of methods it declares."
  [route-vec path-so-far]
  (when (vector? route-vec)
    (let [[segment & more] route-vec
          data (when (map? (first more)) (first more))
          children (filter vector? more)
          here (str path-so-far segment)]
      (concat
        (when-let [ms (http-method-keys data)] [[here (set ms)]])
        (mapcat #(declared-endpoints % here) children)))))

(deftest compiled-router-answers-exactly-the-methods-each-path-declares
  (testing "no declared method is lost, and no undeclared method is answered"
    ;; The shape check above catches the CAUSE. This catches the EFFECT, and it
    ;; catches more: when reitit drops a parent it also MERGES the parent's
    ;; method keys onto the children, so a child answers verbs it never
    ;; declared - #231's /pipelines answering :put against the dataset-update
    ;; route data. A dropped route is a visible 404; a path answering an
    ;; undeclared method is a silent success on a surface nobody reviewed.
    (doseq [[label routes] all-route-trees]
      (let [rtr (r/router routes)]
        (doseq [[path declared] (mapcat #(declared-endpoints % "") routes)]
          (let [concrete (str/replace path #":[^/]+" "X")
                match (r/match-by-path rtr concrete)
                answered (set (http-method-keys (:data match)))]
            (is (some? match)
                (str label ": " path " declares " (sort declared)
                     " but the compiled router does not match it at all"))
            (when match
              (is (= declared answered)
                  (str label ": " path
                       " declares " (sort declared)
                       " but answers " (sort answered)
                       (let [leak (set/difference answered declared)]
                         (when (seq leak) (str " — LEAKED " (sort leak))))
                       (let [lost (set/difference declared answered)]
                         (when (seq lost) (str " — LOST " (sort lost)))))))))))))
