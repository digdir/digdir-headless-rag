(ns digdir.api.routes-test
  (:require [digdir.test-utils :as tu]
            [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [datahike.api :as d]
            [digdir.api.routes :as routes]
            [digdir.api.routes.endpoints :as routes-endpoints]
            [digdir.api.routes.handlers :as handlers]
            [digdir.auth.core :as auth]
            [digdir.agents.db :as agents-db]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]
            [digdir.data.db :as db]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.executor :as executor]
            [digdir.pipeline.collections :as collections]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.naming :as enrichment-naming]
            [digdir.playground.diagnostics :as playground-diagnostics]
            [digdir.skills.api :as skills-api]
            [digdir.skills.builtin.retrieval :as retrieval-skill]
            [digdir.api.routes.endpoints.debug :as debug-handlers]
            [ring.middleware.params :refer [wrap-params]]
            [typesense.client :as ts-client]))

;; ===== Helper functions =====

(defn json-body
  "Create an input stream from a map for request body"
  [data]
  (io/input-stream (.getBytes (json/generate-string data) "UTF-8")))

(defn example-agent
  ([] (example-agent {}))
  ([overrides]
   (merge {:id "builtin/agent-rag-agent"
           :name "Agentic RAG Agent"
           :default-skill-graph "builtin/agent-rag"
           :allowed-skill-graphs ["builtin/agent-rag"]
           :allowed-dataset-scopes []
           :guardrails {}
           :enabled? true}
          overrides)))

;; ===== wrap-api-key-auth tests =====

(deftest test-wrap-api-key-auth-valid
  (testing "Valid API key passes through with explicit dataset scopes and allowed config keys"
    (let [handler-called (atom false)
          handler (fn [req]
                    (reset! handler-called true)
                    (is (= [{:tenant "ka"
                             :dataset-config-key "prod"}]
                           (:api-key/dataset-scopes req)))
                    (is (= ["builtin/agent-rag-agent"] (:api-key/agent-refs req)))
                    (is (= [{:api-key.allowed-config-key/id "ceiling-1"
                             :api-key.allowed-config-key/root :runtime
                             :api-key.allowed-config-key/tenant "ka"
                             :api-key.allowed-config-key/node-id "runtime/ka/default"
                             :api-key.allowed-config-key/tenant-config-key "default"}]
                           (:api-key/allowed-config-keys req)))
                    (is (= #{:query} (:api-key/scopes req)))
                    {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {"x-api-key" "rag_valid123"}}]

      (with-redefs [api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "prod"}]
                                                   :agent-refs ["builtin/agent-rag-agent"]
                                                   :allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                                          :api-key.allowed-config-key/root :runtime
                                                                          :api-key.allowed-config-key/tenant "ka"
                                                                          :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                                          :api-key.allowed-config-key/tenant-config-key "default"}]
                                                   :scopes #{:query}}))]
        (with-log
          (let [response (wrapped request)]
            (is @handler-called)
            (is (= 200 (:status response)))
            (is (logged? 'digdir.api.routes.endpoints
                         :info
                         #"API request received"))))))))

(deftest test-wrap-api-key-auth-does-not-enforce-scopes
  (testing "API key auth only authenticates and injects grants; route handlers enforce scopes"
    (let [handler-called (atom false)
          handler (fn [req]
                    (reset! handler-called true)
                    (is (= #{:admin} (:api-key/scopes req)))
                    {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {"x-api-key" "rag_scope_limited"}}]
      (with-redefs [api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_scope_limited")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                   :dataset-config-key "prod"}]
                                                   :agent-refs []
                                                   :scopes #{:admin}}))]
        (let [response (wrapped request)]
          (is @handler-called)
          (is (= 200 (:status response))))))))

(deftest test-wrap-api-key-auth-invalid
  (testing "Invalid API key returns 401"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {"x-api-key" "rag_invalid"}}]

      (with-redefs [api-keys/validate-api-key (fn [_ _] nil)]
        (let [response (wrapped request)]
          (is (= 401 (:status response)))
          (is (= "Bearer" (get-in response [:headers "WWW-Authenticate"]))
              "a 401 must say how to authenticate (#121)")
          (is (= "application/json" (get-in response [:headers "Content-Type"]))))))))

(deftest test-wrap-api-key-auth-missing
  (testing "Missing API key returns 401"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-api-key-auth handler)
          request {:headers {}}]

      (with-redefs [api-keys/validate-api-key (fn [_ _] nil)]
        (let [response (wrapped request)]
          (is (= 401 (:status response)))
          (is (= "Bearer" (get-in response [:headers "WWW-Authenticate"]))
              "the challenge is what tells an unauthenticated client what to send"))))))

;; ===== 401 body shape (#137) =====
;;
;; Asserted against the DECODED JSON body, not the Ring response map: in
;; Clojure an absent key and a nil value are the same lookup, but on the
;; wire they are different bytes, and it is the bytes an SDK parses.

(defn- decoded-401-body
  "Drive the real middleware to a 401 for `uri` and decode what went out."
  [uri]
  (let [wrapped (routes/wrap-api-key-auth (fn [_] {:status 200 :body "OK"}))]
    (with-redefs [api-keys/validate-api-key (fn [_ _] nil)]
      (let [response (wrapped {:uri uri :headers {}})]
        (is (= 401 (:status response)))
        (json/parse-string (:body response) true)))))

(deftest the-401-bodies-carry-exactly-these-keys
  (testing "the whole key set, because a stray key is invisible to value assertions (#191)"
    ;; These bodies come from shared auth middleware, so a field added for one
    ;; surface silently appears on every other. None of the value-level
    ;; assertions below would notice.
    (let [v1 (decoded-401-body "/v1/chat/completions")
          mcp (decoded-401-body "/api/mcp")]
      (is (= #{:error} (set (keys v1))))
      (is (= #{:message :type :code} (set (keys (:error v1))))
          "OpenAI's error object has exactly these three; an extra one is a wire change")
      (is (= #{:error} (set (keys mcp)))))))

(deftest test-401-on-v1-is-an-openai-error-object
  (testing "/v1 clients read error.message off an object — a bare string is undefined to them"
    (let [body (decoded-401-body "/v1/chat/completions")]
      (is (map? (:error body))
          "error must be an object, not a string")
      (is (string? (get-in body [:error :message])))
      (is (= "Invalid or missing API key" (get-in body [:error :message])))
      (is (= "invalid_request_error" (get-in body [:error :type])))
      (is (= "invalid_api_key" (get-in body [:error :code]))))))

(deftest test-401-on-v1-models-is-also-an-openai-error-object
  (testing "the shape is chosen per surface, not per route"
    (is (map? (:error (decoded-401-body "/v1/models"))))))

(deftest test-401-on-mcp-body-is-unchanged
  (testing "MCP keeps the string body it has always returned"
    ;; Pinned deliberately: MCP clients are not OpenAI SDKs, and changing
    ;; this would be a wire break with no reader asking for it.
    (let [body (decoded-401-body "/api/mcp")]
      (is (string? (:error body)))
      (is (= "Invalid or missing API key" (:error body))))))

(deftest test-401-on-other-api-routes-is-unchanged
  (testing "every non-/v1 surface behind this middleware keeps the string body"
    (is (string? (:error (decoded-401-body "/api/datasets"))))
    (is (string? (:error (decoded-401-body "/api/conversations"))))))

;; ===== wrap-debug-api-key-auth tests =====

(deftest test-wrap-debug-api-key-auth-valid
  (testing "Valid debug API key passes through"
    (let [handler-called (atom false)
          handler (fn [_req]
                    (reset! handler-called true)
                    {:status 200 :body "OK"})
          request {:headers {"x-debug-api-key" "debug-secret"}}]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] "debug-secret")]
        (let [wrapped (routes/wrap-debug-api-key-auth handler)
              response (wrapped request)]
          (is @handler-called)
          (is (= 200 (:status response))))))))

(deftest test-wrap-debug-api-key-auth-invalid
  (testing "Invalid debug API key returns 401"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-debug-api-key-auth handler)
          request {:headers {"x-debug-api-key" "wrong"}}]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] "debug-secret")]
        (let [response (wrapped request)]
          (is (= 401 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"]))))))))

(deftest test-wrap-debug-api-key-auth-not-configured
  (testing "Missing server debug key config returns 503"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (routes/wrap-debug-api-key-auth handler)
          request {:headers {"x-debug-api-key" "anything"}}]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] nil)]
        (let [response (wrapped request)]
          (is (= 503 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"]))))))))

;; ===== api-rag-handler tests =====

(deftest build-rag-skill-params-forwards-strategy-overrides
  (testing "Request params win over config for strategy weight/contribution-cap overrides"
    (let [config {:retrieval-strategy-weights {:phrase 0.5 :content 0.5}
                  :retrieval-strategy-contribution-caps {:phrase 1}}
          params {:retrieve-strategy-weights {:phrase 1.0 :content 0.5}
                  :retrieve-strategy-contribution-caps {:phrase 2}}
          result (routes/build-rag-skill-params config params)]
      (is (= {:phrase 1.0 :content 0.5}
             (get-in result [:builtin/retrieval :strategy-weights])))
      (is (= {:phrase 2}
             (get-in result [:builtin/retrieval :strategy-contribution-caps])))))
  (testing "Config supplies defaults when request params omit overrides"
    (let [config {:retrieval-strategy-weights {:phrase 0.7}
                  :retrieval-strategy-contribution-caps {:content 3}}
          result (routes/build-rag-skill-params config {})]
      (is (= {:phrase 0.7}
             (get-in result [:builtin/retrieval :strategy-weights])))
      (is (= {:content 3}
             (get-in result [:builtin/retrieval :strategy-contribution-caps])))))
  (testing "Absent config and params drop keys entirely (compact-map)"
    (let [result (routes/build-rag-skill-params {} {})]
      (is (not (contains? (:builtin/retrieval result) :strategy-weights)))
      (is (not (contains? (:builtin/retrieval result) :strategy-contribution-caps))))))

(deftest test-list-config-nodes-handler-returns-only-ceiling-reachable-config-keys
  (testing "Public config node discovery only returns tenant nodes reachable from the API key allowed config keys"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/list-config-nodes (fn [_ tenant root]
                                                (is (= ["ka" :runtime] [tenant root]))
                                                [{:config.node/id "runtime/ka/default"
                                                  :config.node/tenant-config-key "default"
                                                  :config.node/label "Default"
                                                  :config.node/enabled? true}
                                                 {:config.node/id "runtime/ka/frontpage"
                                                  :config.node/tenant-config-key "frontpage"
                                                  :config.node/label "Frontpage"
                                                  :config.node/enabled? true
                                                  :config.node/parent {:config.node/id "runtime/ka/default"}}
                                                 {:config.node/id "runtime/ka/_system"
                                                  :config.node/tenant-config-key "_system"
                                                  :config.node/label "System"
                                                  :config.node/system-managed? true
                                                  :config.node/enabled? true}])
                  api-keys/allowed-config-key-allows? (fn [_ ceilings request]
                                                    (is (= [{:api-key.allowed-config-key/root :runtime
                                                             :api-key.allowed-config-key/tenant "ka"
                                                             :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                             :api-key.allowed-config-key/tenant-config-key "default"}]
                                                           ceilings))
                                                    (= "runtime/ka/default" (:node-id request)))]
      (let [response (routes/list-config-nodes-handler
                      {:path-params {:root "runtime"}
                       :params {"tenant" "ka"}
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                  :api-key.allowed-config-key/tenant "ka"
                                                  :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                  :api-key.allowed-config-key/tenant-config-key "default"}]})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "runtime" (:root body)))
        (is (= ["default"] (:allowed-config-keys body)))
        (is (= [{:config-key "default"
                 :label "Default"
                 :enabled? true}]
               (:nodes body)))))))

(deftest test-api-router-coerces-config-node-discovery-params
  (testing "API router parses and validates config node discovery path and query params"
    (let [handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                                          :api-key.allowed-config-key/tenant "ka"
                                                                          :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                                          :api-key.allowed-config-key/tenant-config-key "default"}]
                                                   :scopes #{:query}}))
                    config-db/list-config-nodes (fn [_ tenant root]
                                                  (is (= ["ka" :runtime] [tenant root]))
                                                  [{:config.node/id "runtime/ka/default"
                                                    :config.node/tenant-config-key "default"
                                                    :config.node/label "Default"
                                                    :config.node/enabled? true}])
                    api-keys/allowed-config-key-allows? (fn [_ _ request]
                                                          (= "runtime/ka/default" (:node-id request)))]
        (let [response (handler {:request-method :get
                                 :uri "/api/config/runtime/nodes"
                                 :path-info "/api/config/runtime/nodes"
                                 :query-string "tenant=ka"
                                 :headers {"x-api-key" "rag_valid123"}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "runtime" (:root body)))
          (is (= [{:config-key "default"
                   :label "Default"
                   :enabled? true}]
                 (:nodes body))))))))

(deftest test-api-router-rejects-invalid-config-node-discovery-params
  (testing "API router rejects malformed config node discovery params before listing nodes"
    (let [listed? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:allowed-config-keys []
                                                   :scopes #{:query}}))
                    config-db/list-config-nodes (fn [& _]
                                                  (reset! listed? true)
                                                  nil)]
        (let [response (handler {:request-method :get
                                 :uri "/api/config/runtime/nodes"
                                 :path-info "/api/config/runtime/nodes"
                                 :headers {"x-api-key" "rag_valid123"}})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @listed?)))))))

(deftest test-resolve-runtime-config-handler-success
  (testing "Explicit runtime config resolve endpoint requires runtime-config-key and returns config plus traces"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                      (when (= [tenant root slug] ["ka" :runtime "frontpage"])
                                                        {:config.node/id "runtime/ka/frontpage"
                                                         :config.node/tenant-config-key "frontpage"
                                                         :config.node/enabled? true}))
                  api-keys/require-allowed-config-key! (fn [_ _ request]
                                                     (is (= {:root :runtime
                                                             :tenant "ka"
                                                             :node-id "runtime/ka/frontpage"}
                                                            request))
                                                     {:api-key.allowed-config-key/tenant-config-key "website"})
                  cfg/load-runtime-config-v2-with-trace (fn [opts]
                                                          (is (= {:tenant "ka"
                                                                  :tenant-config-key "frontpage"
                                                                  :agent-id "builtin/agent-rag-agent"
                                                                  :paths ["skills.rerank.top-k"]}
                                                                 opts))
                                                          {:config {:skills {:rerank {:top-k 40}}}
                                                           :traces {"skills.rerank.top-k" {:winning-node "runtime/ka/frontpage"}}})]
      (let [response (routes/resolve-runtime-config-handler
                      {:body (json-body {:tenant "ka"
                                         :runtime-config-key "frontpage"
                                         :agent-id "builtin/agent-rag-agent"
                                         :paths ["skills.rerank.top-k"]})
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                  :api-key.allowed-config-key/tenant "ka"
                                                  :api-key.allowed-config-key/node-id "runtime/ka/website"
                                                  :api-key.allowed-config-key/tenant-config-key "website"}]})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "runtime" (:root body)))
        (is (= "frontpage" (:runtime-config-key body)))
        (is (= "website" (get-in body [:authorization :matched-allowed-config-key])))
        (is (= 40 (get-in body [:config :skills :rerank :top-k])))))))

(deftest test-resolve-runtime-config-handler-supports-coerced-body-params
  (testing "Runtime config resolve accepts route-coerced body params"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                   (when (= [tenant root slug] ["ka" :runtime "frontpage"])
                                                                     {:config.node/id "runtime/ka/frontpage"
                                                                      :config.node/tenant-config-key "frontpage"
                                                                      :config.node/enabled? true}))
                  api-keys/require-allowed-config-key! (fn [& _]
                                                         {:api-key.allowed-config-key/tenant-config-key "website"})
                  cfg/load-runtime-config-v2-with-trace (fn [opts]
                                                          (is (= {:tenant "ka"
                                                                  :tenant-config-key "frontpage"
                                                                  :agent-id "builtin/agent-rag-agent"
                                                                  :paths ["skills.rerank.top-k"]}
                                                                 opts))
                                                          {:config {:skills {:rerank {:top-k 40}}}
                                                           :traces {"skills.rerank.top-k" {:winning-node "runtime/ka/frontpage"}}})]
      (let [response (routes/resolve-runtime-config-handler
                      {:parameters {:body {:tenant "ka"
                                           :runtime-config-key "frontpage"
                                           :agent-id "builtin/agent-rag-agent"
                                           :paths ["skills.rerank.top-k"]}}
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                      :api-key.allowed-config-key/tenant "ka"
                                                      :api-key.allowed-config-key/node-id "runtime/ka/website"
                                                      :api-key.allowed-config-key/tenant-config-key "website"}]})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "frontpage" (:runtime-config-key body)))))))

(deftest test-resolve-dataset-config-handler-success
  (testing "Explicit dataset config resolve endpoint requires dataset-config-key and returns config plus traces"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-config-node (fn [_ node-id]
                                              (when (= node-id "dataset/ka/public-docs/default")
                                                {:config.node/id node-id
                                                 :config.node/tenant-config-key "default"
                                                 :config.node/enabled? true}))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                      (when (= [tenant root slug] ["ka" :dataset "frontpage"])
                                                        {:config.node/id "dataset/ka/frontpage"
                                                         :config.node/tenant-config-key "frontpage"
                                                         :config.node/enabled? true}))
                  config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                 (is (= {:tenant "ka"
                                                         :dataset-config-key "frontpage"}
                                                        dataset-ref))
                                                 {:tenant "ka"
                                                  :dataset-id "public-docs"
                                                  :dataset-config-key "public-docs"
                                                  :dataset-node-id "dataset/ka/public-docs/default"})
                  api-keys/require-allowed-config-key! (fn [_ _ request]
                                                     (is (= {:root :dataset
                                                             :tenant "ka"
                                                             :node-id "dataset/ka/public-docs/default"}
                                                            request))
                                                     {:api-key.allowed-config-key/tenant-config-key "default"})
                  cfg/load-dataset-config-v2-with-trace (fn [opts]
                                                          (is (= {:tenant "ka"
                                                                  :dataset-config-key "public-docs"
                                                                  :dataset-id "public-docs"
                                                                  :paths ["pipeline.chunks.minimum-length"]}
                                                                 opts))
                                                          {:config {:pipeline {:chunks {:minimum-length 200}}}
                                                           :traces {"pipeline.chunks.minimum-length" {:winning-node "dataset/ka/public-docs/default"}}})]
      (let [response (routes/resolve-dataset-config-handler
                      {:body (json-body {:tenant "ka"
                                         :dataset-config-key "frontpage"
                                         :paths ["pipeline.chunks.minimum-length"]})
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :dataset
                                                  :api-key.allowed-config-key/tenant "ka"
                                                  :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                  :api-key.allowed-config-key/tenant-config-key "default"}]})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "dataset" (:root body)))
        (is (= "public-docs" (:dataset-config-key body)))
        (is (= 200 (get-in body [:config :pipeline :chunks :minimum-length])))))))

(deftest test-resolve-dataset-config-handler-supports-coerced-body-params
  (testing "Dataset config resolve accepts route-coerced body params"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-config-node (fn [_ node-id]
                                              (when (= node-id "dataset/ka/public-docs/default")
                                                {:config.node/id node-id
                                                 :config.node/tenant-config-key "default"
                                                 :config.node/enabled? true}))
                  config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                 (is (= {:tenant "ka"
                                                         :dataset-config-key "frontpage"}
                                                        dataset-ref))
                                                 {:tenant "ka"
                                                  :dataset-id "public-docs"
                                                  :dataset-config-key "public-docs"
                                                  :dataset-node-id "dataset/ka/public-docs/default"})
                  api-keys/require-allowed-config-key! (fn [& _]
                                                         {:api-key.allowed-config-key/tenant-config-key "default"})
                  cfg/load-dataset-config-v2-with-trace (fn [opts]
                                                          (is (= {:tenant "ka"
                                                                  :dataset-config-key "public-docs"
                                                                  :dataset-id "public-docs"
                                                                  :paths ["pipeline.chunks.minimum-length"]}
                                                                 opts))
                                                          {:config {:pipeline {:chunks {:minimum-length 200}}}
                                                           :traces {"pipeline.chunks.minimum-length" {:winning-node "dataset/ka/public-docs/default"}}})]
      (let [response (routes/resolve-dataset-config-handler
                      {:parameters {:body {:tenant "ka"
                                           :dataset-config-key "frontpage"
                                           :paths ["pipeline.chunks.minimum-length"]}}
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :dataset
                                                      :api-key.allowed-config-key/tenant "ka"
                                                      :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                      :api-key.allowed-config-key/tenant-config-key "default"}]})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "public-docs" (:dataset-config-key body)))))))

;; ===== Debug endpoint tests =====

(deftest test-debug-dataset-config-handler-success
  (testing "Dataset debug endpoint returns resolved dataset map"
    (with-redefs [config-db/get-conn (fn [] (atom :mock-db))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-dataset-by-ref (fn [_db dataset-ref _master]
                                                 (is (= {:tenant "ka"
                                                         :dataset-config-key "dev"}
                                                        dataset-ref))
                                                 {:id "ka:public-docs"
                                                  :dataset-config-key "public-docs"
                                                  :docs-collection "docs"})]
        (let [response (routes/debug-dataset-config-handler
                      {:params {"tenant" "ka"
                                "dataset-config-key" "dev"}})
            body (edn/read-string (:body response))]
        (is (= 200 (:status response)))
        (is (true? (:dataset-found? body)))
        (is (= "public-docs" (:dataset-config-key body)))
        (is (= "docs" (get-in body [:dataset-config :docs-collection])))))))

(deftest test-debug-chunk-handler-success
  (testing "Chunk debug endpoint returns chunk payload"
    (with-redefs [config-db/get-conn (fn [] (atom :mock-db))
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                 (when (= dataset-ref {:tenant "ka"
                                                                       :dataset-config-key "dev"})
                                                   {:id "ka:public-docs"
                                                    :dataset-config-key "public-docs"
                                                    :name "Public Docs"}))
                  collections/get-or-generate-collection-names (fn [_]
                                                                  {:chunks-collection "chunks_col"
                                                                   :docs-collection "docs_col"})
                  ts-utils/make-ts-settings (fn [opts]
                                              (is (= {:tenant "ka"
                                                      :dataset-config-key "public-docs"}
                                                     opts))
                                              {:uri "http://localhost:8108" :key "k"})
                  ts-client/multi-search (fn [_settings _searches _opts]
                                           {:results [{:hits [{:document {:chunk_id "chunk-1"
                                                                          :doc_num "42"
                                                                          :content_markdown "chunk content"
                                                                          :metadata {:type "doc"}
                                                                          :docs_col {:title "A title"
                                                                                     :url "https://example.com"}}}]}]})]
      (let [response (routes/debug-chunk-handler
                      {:params {"tenant" "ka"
                                "dataset_config_key" "dev"
                                "chunk-id" "chunk-1"}})
            body (edn/read-string (:body response))]
        (is (= 200 (:status response)))
        (is (true? (:found? body)))
        (is (= "chunk-1" (:chunk-id body)))
        (is (= "public-docs" (:dataset-config-key body)))
        (is (= "A title" (:title body)))))))

;; ----- Typesense filesystem-style debug handlers (search / get) -----

(defn- ts-search-mocks
  "Returns a map suitable for use with `with-redefs` that stubs out the
   dataset/collection plumbing the typesense-search/get handlers depend
   on. Callers supply their own `multi-search` stub via merge."
  []
  {#'config-db/get-conn (fn [] (atom :mock-db))
   #'config-core/get-master-key (fn [] "master-key")
   #'config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                    (when (= dataset-ref {:tenant "ka"
                                                          :dataset-config-key "dev"})
                                      {:tenant "ka"
                                       :dataset-config-key "public-docs"
                                       :pipeline-name "public-docs"}))
   #'collections/get-or-generate-collection-names (fn [_]
                                                    {:docs-collection "docs_col"
                                                     :chunks-collection "chunks_col"
                                                     :phrases-collection "phrases_col"})
   #'enrichment-naming/enrichment-collection-name (fn [_ enrichment-type]
                                                         (str "enrichment_" (name enrichment-type) "_col"))
   #'ts-utils/make-ts-settings (fn [_] {:uri "http://localhost:8108" :key "k"})})

(defn- with-ts-mocks*
  "Helper to install ts-search-mocks plus an extra `multi-search` capture
   that records the search request for assertions."
  [multi-search-result f]
  (let [captured (atom nil)]
    (with-redefs-fn
      (assoc (ts-search-mocks)
             #'ts-client/multi-search
             (fn [_settings searches-map _opts]
               (reset! captured (first (:searches searches-map)))
               multi-search-result))
      (fn []
        (let [response (f)]
          {:response response
           :captured @captured})))))

(deftest test-debug-typesense-search-handler-defaults-strip-content
  (testing "typesense-search returns hits and strips content_markdown by default"
    (let [ts-result {:results [{:found 2
                                :hits [{:document {:chunk_id "c1" :doc_num "42" :metadata {}}
                                        :highlights []}
                                       {:document {:chunk_id "c2" :doc_num "43" :metadata {}}
                                        :highlights []}]}]}
          {:keys [response captured]}
          (with-ts-mocks* ts-result
            #(routes/debug-typesense-search-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "chunks"
                        "q" "altinn"}}))
          body (edn/read-string (:body response))]
      (is (= 200 (:status response)))
      (is (= 2 (count (:hits body))))
      (is (= "chunks_col" (:collection body)))
      (is (= :chunks (:role body)))
      ;; default include-fields is small + the $docs join — content_markdown excluded
      (is (not (clojure.string/includes? (:include_fields captured) "content_markdown")))
      (is (clojure.string/includes? (:include_fields captured) "chunk_id"))
      (is (clojure.string/includes? (:include_fields captured) "$docs_col(url,title)")))))

(deftest test-debug-typesense-search-handler-explicit-include-fields
  (testing "typesense-search honors explicit include-fields opt-in for big fields"
    (let [{:keys [captured]}
          (with-ts-mocks* {:results [{:found 0 :hits []}]}
            #(routes/debug-typesense-search-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "chunks"
                        "q" "altinn"
                        "include-fields" "content_markdown,chunk_id"}}))]
      (is (= "content_markdown,chunk_id" (:include_fields captured))))))

(deftest test-debug-typesense-search-handler-facets
  (testing "typesense-search surfaces facet counts when facet-by is set"
    (let [ts-result {:results [{:found 5 :hits []
                                :facet_counts [{:field_name "orgs_long"
                                                :counts [{:value "Digdir" :count 3}
                                                         {:value "DSB" :count 2}]}]}]}
          {:keys [response captured]}
          (with-ts-mocks* ts-result
            #(routes/debug-typesense-search-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "chunks"
                        "q" "*"
                        "facet-by" "orgs_long"}}))
          body (edn/read-string (:body response))]
      (is (= 200 (:status response)))
      (is (= "orgs_long" (:facet_by captured)))
      (is (= [{:value "Digdir" :count 3} {:value "DSB" :count 2}]
             (get-in body [:facets "orgs_long"]))))))

(deftest test-debug-typesense-search-handler-enrichment-role
  (testing "typesense-search resolves enrichment role to its collection"
    (let [{:keys [response captured]}
          (with-ts-mocks* {:results [{:found 0 :hits []}]}
            #(routes/debug-typesense-search-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "enrichment/hypothetical-questions"
                        "q" "Når ble Altinn 3 lansert?"}}))
          body (edn/read-string (:body response))]
      (is (= 200 (:status response)))
      (is (= "enrichment_hypothetical-questions_col" (:collection captured)))
      (is (= "enrichment_hypothetical-questions_col" (:collection body))))))

(deftest test-debug-typesense-search-handler-missing-role
  (testing "typesense-search requires role param"
    (let [response (routes/debug-typesense-search-handler
                    {:params {"tenant" "ka"
                              "dataset-config-key" "dev"
                              "q" "altinn"}})
          body (edn/read-string (:body response))]
      (is (= 400 (:status response)))
      (is (clojure.string/includes? (:error body) "role")))))

(deftest test-debug-typesense-search-handler-unknown-role
  (testing "typesense-search rejects unknown role"
    (let [response (routes/debug-typesense-search-handler
                    {:params {"tenant" "ka"
                              "dataset-config-key" "dev"
                              "role" "bogus"
                              "q" "altinn"}})
          body (edn/read-string (:body response))]
      (is (= 400 (:status response)))
      (is (clojure.string/includes? (:error body) "Unknown role")))))

(deftest test-debug-typesense-get-handler-by-ids
  (testing "typesense-get filters by chunk_id and returns documents"
    (let [ts-result {:results [{:found 2
                                :hits [{:document {:chunk_id "abc" :doc_num "1"}}
                                       {:document {:chunk_id "def" :doc_num "1"}}]}]}
          {:keys [response captured]}
          (with-ts-mocks* ts-result
            #(routes/debug-typesense-get-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "chunks"
                        "ids" "abc,def"}}))
          body (edn/read-string (:body response))]
      (is (= 200 (:status response)))
      (is (= 2 (:found-count body)))
      (is (= [{:chunk_id "abc" :doc_num "1"} {:chunk_id "def" :doc_num "1"}]
             (:documents body)))
      (is (clojure.string/includes? (:filter_by captured) "chunk_id:=["))
      (is (clojure.string/includes? (:filter_by captured) "`abc`"))
      (is (clojure.string/includes? (:filter_by captured) "`def`")))))

(deftest test-debug-typesense-get-handler-docs-uses-doc-num
  (testing "typesense-get with role=docs filters by doc_num instead of chunk_id"
    (let [{:keys [captured]}
          (with-ts-mocks* {:results [{:found 0 :hits []}]}
            #(routes/debug-typesense-get-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "docs"
                        "ids" "42"}}))]
      (is (clojure.string/includes? (:filter_by captured) "doc_num:=["))
      (is (clojure.string/includes? (:filter_by captured) "`42`")))))

(deftest test-debug-typesense-get-handler-range
  (testing "typesense-get with --range builds doc_num + chunk_index window filter"
    (let [{:keys [captured]}
          (with-ts-mocks* {:results [{:found 0 :hits []}]}
            #(routes/debug-typesense-get-handler
              {:params {"tenant" "ka"
                        "dataset-config-key" "dev"
                        "role" "chunks"
                        "range" "42:3-7"}}))]
      (is (clojure.string/includes? (:filter_by captured) "doc_num:=`42`"))
      (is (clojure.string/includes? (:filter_by captured) "chunk_index:>=3"))
      (is (clojure.string/includes? (:filter_by captured) "chunk_index:<=7"))
      (is (= "chunk_index:asc" (:sort_by captured))))))

(deftest test-debug-typesense-get-handler-range-only-for-chunks
  (testing "typesense-get rejects --range for non-chunks roles"
    (let [response (routes/debug-typesense-get-handler
                    {:params {"tenant" "ka"
                              "dataset-config-key" "dev"
                              "role" "phrases"
                              "range" "42:0-9"}})
          body (edn/read-string (:body response))]
      (is (= 400 (:status response)))
      (is (clojure.string/includes? (:error body) "range")))))

(deftest test-debug-typesense-get-handler-requires-ids-or-range
  (testing "typesense-get without ids or range returns 400"
    (let [response (routes/debug-typesense-get-handler
                    {:params {"tenant" "ka"
                              "dataset-config-key" "dev"
                              "role" "chunks"}})
          body (edn/read-string (:body response))]
      (is (= 400 (:status response)))
      (is (clojure.string/includes? (:error body) "ids")))))

;; ----- Typesense multi-strategy retrieve (option b) -----

(defn- with-ts-retrieve-mocks*
  "Install retrieve mocks plus capture of the execute-retrieval input.
   The handler is gated on RAG_TS_RETRIEVE_ENABLED; tests that need it
   enabled redef ts-retrieve-enabled? via the env or via the helper below."
  [retrieval-result f]
  (let [captured (atom nil)]
    (with-redefs-fn
      (assoc (ts-search-mocks)
             #'retrieval-skill/execute-retrieval
             (fn [input]
               (reset! captured input)
               retrieval-result))
      (fn []
        (let [response (f)]
          {:response response
           :captured @captured})))))

(deftest test-debug-typesense-retrieve-disabled-by-default
  (testing "typesense-retrieve returns 503 when RAG_TS_RETRIEVE_ENABLED is unset"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly false)]
      (let [response (routes/debug-typesense-retrieve-handler
                      {:params {"tenant" "ka"
                                "dataset-config-key" "dev"
                                "queries" "altinn"}})
            body (edn/read-string (:body response))]
        (is (= 503 (:status response)))
        (is (clojure.string/includes? (:error body) "disabled"))
        (is (clojure.string/includes? (:error body) "RAG_TS_RETRIEVE_ENABLED"))))))

(deftest test-debug-typesense-retrieve-when-enabled-invokes-skill
  (testing "typesense-retrieve calls execute-retrieval with correctly shaped input"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly true)]
      (let [retrieval-result {:outputs {:chunks [{:chunk_id "c1" :doc_num "1"}
                                                 {:chunk_id "c2" :doc_num "2"}]
                                        :search-attribution {:phrase 10 :metadata 5 :content 15 :merged 20}}
                              :metadata {:search-strategies-used 3}}
            {:keys [response captured]}
            (with-ts-retrieve-mocks* retrieval-result
              #(routes/debug-typesense-retrieve-handler
                {:params {"tenant" "ka"
                          "dataset-config-key" "dev"
                          "queries" "altinn 3 launch"}}))
            body (edn/read-string (:body response))]
        (is (= 200 (:status response)))
        (is (= 2 (:found-count body)))
        (is (= ["altinn 3 launch"] (:queries body)))
        (is (= {:phrase 10 :metadata 5 :content 15 :merged 20}
               (:search-attribution body)))
        (is (= ["altinn 3 launch"] (-> captured :inputs :queries)))
        (is (= "docs_col" (-> captured :inputs :docs-collection)))
        (is (= "chunks_col" (-> captured :inputs :chunks-collection)))
        (is (= "phrases_col" (-> captured :inputs :phrases-collection)))
        ;; auto-filter defaults to false in debug handler (skill default is true)
        (is (= false (-> captured :parameters :auto-filter)))
        ;; metadata-only defaults to true so responses stay small
        (is (= true (-> captured :parameters :metadata-only)))))))

(deftest test-debug-typesense-retrieve-multiple-queries
  (testing "comma-separated queries split into a vector"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly true)]
      (let [{:keys [captured]}
            (with-ts-retrieve-mocks* {:outputs {:chunks [] :search-attribution {}}}
              #(routes/debug-typesense-retrieve-handler
                {:params {"tenant" "ka"
                          "dataset-config-key" "dev"
                          "queries" "altinn 3, dsop, BankID launch"}}))]
        (is (= ["altinn 3" "dsop" "BankID launch"]
               (-> captured :inputs :queries)))))))

(deftest test-debug-typesense-retrieve-enrichment-types
  (testing "enrichment-types resolves to enrichment-search-targets map"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly true)]
      (let [{:keys [response captured]}
            (with-ts-retrieve-mocks* {:outputs {:chunks [] :search-attribution {}}}
              #(routes/debug-typesense-retrieve-handler
                {:params {"tenant" "ka"
                          "dataset-config-key" "dev"
                          "queries" "altinn"
                          "enrichment-types" "hypothetical-questions,verified-phrases"}}))
            body (edn/read-string (:body response))]
        (is (= 200 (:status response)))
        (let [targets (-> captured :parameters :enrichment-search-targets)]
          (is (= #{:hypothetical-questions :verified-phrases} (set (keys targets))))
          (is (= "enrichment_hypothetical-questions_col"
                 (get targets :hypothetical-questions)))
          (is (= "enrichment_verified-phrases_col"
                 (get targets :verified-phrases))))
        (is (= "enrichment_hypothetical-questions_col"
               (get-in body [:enrichment-targets :hypothetical-questions])))))))

(deftest test-debug-typesense-retrieve-skill-error-propagated
  (testing "skill error result returns 500 with the message preserved"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly true)]
      (let [error-result {:error {:error-type :retrieval-backend-failure
                                  :error-message "Typesense connection refused"
                                  :error-data {:queries ["altinn"]}}}
            {:keys [response]}
            (with-ts-retrieve-mocks* error-result
              #(routes/debug-typesense-retrieve-handler
                {:params {"tenant" "ka"
                          "dataset-config-key" "dev"
                          "queries" "altinn"}}))
            body (edn/read-string (:body response))]
        (is (= 500 (:status response)))
        (is (= :retrieval-backend-failure (:error-type body)))
        (is (clojure.string/includes? (:error body) "Typesense connection refused"))))))

(deftest test-debug-typesense-retrieve-missing-queries
  (testing "typesense-retrieve without queries returns 400"
    (with-redefs [debug-handlers/ts-retrieve-enabled? (constantly true)]
      (let [response (routes/debug-typesense-retrieve-handler
                      {:params {"tenant" "ka"
                                "dataset-config-key" "dev"}})
            body (edn/read-string (:body response))]
        (is (= 400 (:status response)))
        (is (clojure.string/includes? (:error body) "queries"))))))

(deftest test-debug-router-coerces-dataset-config-query-params
  (testing "Debug router parses and validates dataset-config query params"
    (let [handler (-> routes/debug-router
                      routes/wrap-debug-api-key-auth
                      wrap-params)]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] "debug-key")
                    config-db/get-conn (fn [] (atom :mock-db))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:tenant "ka"
                                                      :dataset-config-key "prod"}))]
        (let [response (handler {:request-method :get
                                 :uri "/api/debug/dataset-config"
                                 :path-info "/api/debug/dataset-config"
                                 :query-string "tenant=ka&dataset-config-key=prod"
                                 :headers {"x-debug-api-key" "debug-key"}})
              body (edn/read-string (:body response))]
          (is (= 200 (:status response)))
          (is (true? (:dataset-found? body)))
          (is (= "prod" (:dataset-config-key body))))))))

(deftest test-debug-router-rejects-invalid-dataset-config-query-params
  (testing "Debug router rejects malformed dataset-config query params before handler execution"
    (let [resolved? (atom false)
          handler (-> routes/debug-router
                      routes/wrap-debug-api-key-auth
                      wrap-params)]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] "debug-key")
                    config-db/get-dataset-by-ref (fn [& _]
                                                   (reset! resolved? true)
                                                   nil)]
        (let [response (handler {:request-method :get
                                 :uri "/api/debug/dataset-config"
                                 :path-info "/api/debug/dataset-config"
                                 :query-string "tenant=ka"
                                 :headers {"x-debug-api-key" "debug-key"}})
              body (edn/read-string (:body response))]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @resolved?)))))))

(deftest test-debug-router-coerces-chunk-query-params
  (testing "Debug router parses and validates chunk query params"
    (let [handler (-> routes/debug-router
                      routes/wrap-debug-api-key-auth
                      wrap-params)]
      (with-redefs [routes-endpoints/debug-api-key-secret (fn [] "debug-key")
                    config-db/get-conn (fn [] (atom :mock-db))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "dev"})
                                                     {:tenant "ka"
                                                      :dataset-config-key "public-docs"}))
                    collections/get-or-generate-collection-names (fn [dataset-config]
                                                                   (is (= "public-docs" (:dataset-config-key dataset-config)))
                                                                   {:chunks-collection "chunks_ka_public_docs"
                                                                    :docs-collection "docs_ka_public_docs"})
                    ts-utils/make-ts-settings (fn [opts]
                                                (is (= {:tenant "ka"
                                                        :dataset-config-key "public-docs"}
                                                       opts))
                                                {:uri "http://localhost:8108" :key "k"})
                    ts-client/multi-search (fn [_settings _searches _opts]
                                             {:results [{:hits [{:document {:chunk_id "chunk-1"
                                                                            :doc_num "42"
                                                                            :content_markdown "chunk content"
                                                                            :metadata {:type "doc"}
                                                                            :docs_ka_public_docs {:title "A title"
                                                                                                  :url "https://example.com"}}}]}]})]
        (let [response (handler {:request-method :get
                                 :uri "/api/debug/chunk"
                                 :path-info "/api/debug/chunk"
                                 :query-string "tenant=ka&dataset-config-key=dev&chunk-id=chunk-1"
                                 :headers {"x-debug-api-key" "debug-key"}})
              body (edn/read-string (:body response))]
          (is (= 200 (:status response)))
          (is (true? (:found? body)))
          (is (= "chunk-1" (:chunk-id body))))))))

(deftest test-list-pipelines-handler-denies-non-dataset-ceiling
  (testing "Listing pipelines requires a dataset-root ceiling for the tenant"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                      (when (= [tenant root slug] ["ka" :dataset "default"])
                                                        {:config.node/id "dataset/ka/default"}))]
      (let [request {:path-params {:dataset-id "ds_123"}
                     :params {"tenant" "ka"
                              "dataset-config-key" "prod"}
                     :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                :api-key.allowed-config-key/root :runtime
                                                :api-key.allowed-config-key/tenant "ka"
                                                :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                :api-key.allowed-config-key/tenant-config-key "default"}]}
            response (routes/list-pipelines-handler request)
            body (json/parse-string (:body response) true)]
        (is (= 403 (:status response)))
        (is (re-find #"config node" (:error body)))))))

(deftest test-get-pipeline-handler-allows-dataset-ceiling
  (testing "Getting a pipeline accepts a matching dataset-root ceiling"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                      (when (= [tenant root slug] ["ka" :dataset "default"])
                                                        {:config.node/id "dataset/ka/default"}))
                  config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                   (when (= "assistant" pipeline-id)
                                                     {:dataset.pipeline/id "assistant"
                                                      :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                  config-core/get-master-key (fn [] "master-key")
                  ;; Mirrors pipeline/get-dataset's real contract: it assoc's
                  ;; :id, :tenant, :tenant-config-key and :pipeline-name onto the
                  ;; resolved dataset config. The previous stub returned
                  ;; :config-key, a key that function never emits, which mattered
                  ;; because the handler dissoc's :tenant-config-key — so the stub
                  ;; manufactured a `config-key` field the real response cannot
                  ;; contain (#191).
                  pipeline/get-dataset (fn [_ tenant tenant-config-key pipeline-name _]
                                          {:id (pipeline/make-pipeline-id tenant tenant-config-key pipeline-name)
                                           :tenant tenant
                                           :tenant-config-key tenant-config-key
                                           :pipeline-name pipeline-name
                                           :name pipeline-name})]
      (let [request {:path-params {:dataset-id "ds_123"
                                   :pipeline-id "assistant"}
                     :params {"tenant" "ka"
                              "dataset-config-key" "prod"}
                     :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                :api-key.allowed-config-key/root :dataset
                                                :api-key.allowed-config-key/tenant "ka"
                                                :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                :api-key.allowed-config-key/tenant-config-key "default"}]}
            response (routes/get-pipeline-handler request)
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "ka:prod:assistant" (get-in body [:pipeline :execution-pipeline-id])))
        (is (= "prod" (get-in body [:pipeline :dataset-config-key])))))))

(deftest test-create-pipeline-handler-allows-dataset-ceiling
  (testing "Creating a pipeline accepts a matching dataset-root ceiling"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                        (when (= [tenant root slug] ["ka" :dataset "default"])
                                                          {:config.node/id "dataset/ka/default"}))
                    config-core/get-master-key (fn [] "master-key")
                    pipeline/create-pipeline! (fn [_ opts]
                                                (reset! captured-opts opts)
                                                (pipeline/make-pipeline-id (:tenant opts)
                                                                           (:tenant-config-key opts)
                                                                           (:pipeline-name opts)))]
        (let [request {:headers {"x-user-email" "user@example.com"}
                       :path-params {:dataset-id "ds_123"}
                       :body (json-body {:tenant "ka"
                                         :dataset-config-key "prod"
                                         :pipeline-name "assistant"
                                         :properties {:sourceType "website"}})
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                  :api-key.allowed-config-key/root :dataset
                                                  :api-key.allowed-config-key/tenant "ka"
                                                  :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                  :api-key.allowed-config-key/tenant-config-key "default"}]}
              response (routes/create-pipeline-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "assistant" (:pipeline-name body)))
          (is (= "ka:prod:assistant" (:execution-pipeline-id body)))
          (is (= {:tenant "ka"
                  :tenant-config-key "prod"
                  :dataset-id "ds_123"
                  :pipeline-name "assistant"
                  :properties {:source-type :website}
                  :master-key "master-key"}
                 @captured-opts)))))))

(deftest test-create-pipeline-handler-supports-coerced-params
  (testing "Direct pipeline creation accepts route-coerced path and body params"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-core/get-master-key (fn [] "master-key")
                    pipeline/create-pipeline! (fn [_ opts]
                                                (reset! captured-opts opts)
                                                (pipeline/make-pipeline-id (:tenant opts)
                                                                           (:tenant-config-key opts)
                                                                           (:pipeline-name opts)))]
        (let [response (routes/create-pipeline-handler
                        {:headers {"x-user-email" "user@example.com"}
                         :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                        :api-key.allowed-config-key/root :dataset
                                                        :api-key.allowed-config-key/tenant "ka"
                                                        :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                        :api-key.allowed-config-key/tenant-config-key "default"}]
                         :parameters {:path {:dataset-id "ds_123"}
                                      :body {:tenant "ka"
                                             :dataset-config-key "prod"
                                             :pipeline-name "assistant"
                                             :properties {:sourceType "website"}}}})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "assistant" (:pipeline-name body)))
          (is (= "ka:prod:assistant" (:execution-pipeline-id body)))
          (is (= {:tenant "ka"
                  :tenant-config-key "prod"
                  :dataset-id "ds_123"
                  :pipeline-name "assistant"
                  :properties {:source-type :website}
                  :master-key "master-key"}
                 @captured-opts)))))))

(deftest test-list-datasets-handler-returns-dataset-records-with-child-pipelines
  (testing "Listing datasets returns durable parent datasets and their child pipeline summaries"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    pipeline/list-dataset-records (fn [_]
                                                    [{:dataset/id "ds_123"
                                                      :dataset/name "Public Docs"
                                                      :dataset/description "Shared docs"
                                                      :dataset/enabled? true}])
                    config-db/effective-dataset-pipeline-record (fn [_ pipeline-record & _]
                                                                  (assoc pipeline-record
                                                                         :dataset.pipeline/effective-name "Assistant"
                                                                         :dataset.pipeline/effective-source-type :website))
                    config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                    config-db/list-dataset-pipelines (fn
                                                       ([_]
                                                        [{:dataset.pipeline/id "ka:prod:assistant"
                                                          :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                          :dataset.pipeline/enabled? true}])
                                                       ([_ _] []))]
        (let [response (routes/list-datasets-handler {})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= [{:id "ds_123"
                   :name "Public Docs"
                   :description "Shared docs"
                   :enabled? true
                   :pipelineCount 1
                   :pipelines [{:id "ka:prod:assistant"
                                :datasetId "ds_123"
                                :name "Assistant"
                                :sourceType "website"
                                :enabled? true}]}]
                 (:datasets body))))))))

(deftest test-get-dataset-handler-returns-dataset-with-child-pipelines
  (testing "Getting a dataset returns the parent record and its child pipeline summaries"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    pipeline/get-dataset-record (fn [_ dataset-id]
                                                  (when (= "ds_123" dataset-id)
                                                    {:dataset/id "ds_123"
                                                     :dataset/name "Public Docs"
                                                     :dataset/enabled? true}))
                    config-db/effective-dataset-pipeline-record (fn [_ pipeline-record & _]
                                                                  (assoc pipeline-record
                                                                         :dataset.pipeline/effective-name "Assistant"))
                    config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                    config-db/list-dataset-pipelines (fn [_ dataset-id]
                                                       (when (= "ds_123" dataset-id)
                                                         [{:dataset.pipeline/id "ka:prod:assistant"
                                                           :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                           :dataset.pipeline/enabled? true}]))]
        (let [response (routes/get-dataset-handler {:path-params {:dataset-id "ds_123"}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= {:id "ds_123"
                  :name "Public Docs"
                  :description nil
                  :enabled? true
                  :pipelineCount 1
                  :pipelines [{:id "ka:prod:assistant"
                               :datasetId "ds_123"
                               :name "Assistant"
                               :sourceType nil
                               :enabled? true}]}
                 (:dataset body))))))))

(deftest test-update-dataset-handler-applies-mutable-fields
  (testing "PUT /console-api/datasets/:id updates the dataset and returns the refreshed summary"
    (let [conn (atom :mock-db)
          captured-update (atom nil)]
      (with-redefs [db/get-conn (fn [] conn)
                    pipeline/update-dataset! (fn [_ payload]
                                               (reset! captured-update payload)
                                               {:dataset/id (:dataset-id payload)
                                                :dataset/name (:name payload)
                                                :dataset/description (:description payload)
                                                :dataset/enabled? (if (contains? payload :enabled?)
                                                                    (:enabled? payload)
                                                                    true)})
                    config-db/list-dataset-pipelines (fn [_ _] [])
                    config-db/materialization-contexts-by-pipeline-id (fn [_] {})]
        (let [response (routes/update-dataset-handler
                        {:headers {"x-user-email" "ops@example.com"}
                         :path-params {:dataset-id "ds_123"}
                         :body-params {:name "Renamed"
                                       :description "Updated description"
                                       :enabled? false}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= {:dataset-id "ds_123"
                  :name "Renamed"
                  :description "Updated description"
                  :enabled? false}
                 @captured-update))
          (is (= {:id "ds_123"
                  :name "Renamed"
                  :description "Updated description"
                  :enabled? false
                  :pipelineCount 0
                  :pipelines []}
                 (:dataset body))))))))

(deftest test-update-dataset-handler-rejects-empty-body
  (testing "PUT /console-api/datasets/:id requires at least one update field"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    pipeline/update-dataset! (fn [& _]
                                               (throw (ex-info "Should not be called" {})))]
        (let [response (routes/update-dataset-handler
                        {:headers {"x-user-email" "ops@example.com"}
                         :path-params {:dataset-id "ds_123"}
                         :body-params {}})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Missing dataset update fields" (:error body))))))))

(deftest test-list-public-datasets-handler-returns-api-key-visible-datasets
  (testing "Public dataset listing groups granted dataset scopes by parent dataset and includes readonly status"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    config-db/resolve-dataset-ref-materializations (fn [_ dataset-ref]
                                                                     (when (= {:tenant "ka"
                                                                               :dataset-config-key "prod"}
                                                                              dataset-ref)
                                                                       {:pipeline-records [{:dataset.pipeline/id "assistant"
                                                                                            :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                                                            :dataset.pipeline/name "Assistant"
                                                                                            :dataset.pipeline/source-type :website
                                                                                            :dataset.pipeline/enabled? true}]}))
                    pipeline/get-dataset-record (fn [_ dataset-id]
                                                  (when (= "ds_123" dataset-id)
                                                    {:dataset/id "ds_123"
                                                     :dataset/name "Public Docs"
                                                     :dataset/description "Shared docs"
                                                     :dataset/enabled? true}))
                    executor/list-executions (fn [_ external-pipeline-id]
                                               (when (= "ka:prod:assistant" external-pipeline-id)
                                                 [{:pipeline-execution/id "exec_1"
                                                   :pipeline-execution/pipeline-id external-pipeline-id
                                                   :pipeline-execution/status :completed
                                                   :pipeline-execution/started-at "2026-03-30T10:00:00Z"
                                                   :pipeline-execution/completed-at "2026-03-30T10:05:00Z"
                                                   :pipeline-execution/documents-processed 12
                                                   :pipeline-execution/documents-failed 0
                                                   :pipeline-execution/started-by "operator@example.com"}]))]
        (let [response (routes/list-public-datasets-handler
                        {:api-key/dataset-scopes [{:tenant "ka"
                                                 :dataset-config-key "prod"}]})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= [{:id "ds_123"
                   :name "Public Docs"
                   :description "Shared docs"
                   :enabled? true
                   :status "ready"}]
                 (:datasets body))))))))

(deftest test-list-pipelines-handler-prefers-effective-pipeline-projection
  (testing "Pipeline listing prefers effective pipeline projection fields over durable duplicates"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-db/list-dataset-pipelines (fn [_ dataset-id]
                                                       (when (= "ds_123" dataset-id)
                                                         [{:dataset.pipeline/id "assistant"
                                                           :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                           :dataset.pipeline/name "Stale Projection"
                                                           :dataset.pipeline/source-type :folder
                                                           :dataset.pipeline/enabled? true}]))
                    config-db/effective-dataset-pipeline-record (fn [_ pipeline-record & _]
                                                                  (assoc pipeline-record
                                                                         :dataset.pipeline/effective-name "Config Canonical"
                                                                         :dataset.pipeline/effective-source-type :website))
                    api-keys/require-allowed-config-key! (fn [& _] true)]
        (let [response (routes/list-pipelines-handler
                        {:path-params {:dataset-id "ds_123"}
                         :params {"tenant" "ka"}
                         :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                        :api-key.allowed-config-key/root :dataset
                                                        :api-key.allowed-config-key/tenant "ka"
                                                        :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                        :api-key.allowed-config-key/tenant-config-key "default"}]})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= [{:id "assistant"
                   :datasetId "ds_123"
                   :name "Config Canonical"
                   :sourceType "website"
                   :enabled? true}]
                 (:pipelines body))))))))

(deftest test-get-public-dataset-handler-hides-ungranted-dataset
  (testing "Public dataset detail returns 404 when the API key has no dataset scope under that dataset"
    (let [conn (atom :mock-db)]
      (with-redefs [db/get-conn (fn [] conn)
                    config-db/resolve-dataset-ref-materializations (fn [_ dataset-ref]
                                                                     (when (= {:tenant "ka"
                                                                               :dataset-config-key "prod"}
                                                                              dataset-ref)
                                                                       {:pipeline-records [{:dataset.pipeline/id "assistant"
                                                                                            :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                                                            :dataset.pipeline/name "Assistant"
                                                                                            :dataset.pipeline/enabled? true}]}))
                    pipeline/get-dataset-record (fn [_ dataset-id]
                                                  (case dataset-id
                                                    "ds_123" {:dataset/id "ds_123" :dataset/name "Public Docs" :dataset/enabled? true}
                                                    "ds_999" {:dataset/id "ds_999" :dataset/name "Secret Docs" :dataset/enabled? true}
                                                    nil))
                    executor/list-executions (fn [& _] [])]
        (let [response (routes/get-public-dataset-handler
                        {:path-params {:dataset-id "ds_999"}
                         :api-key/dataset-scopes [{:tenant "ka"
                                                 :dataset-config-key "prod"}]})
              body (json/parse-string (:body response) true)]
          (is (= 404 (:status response)))
          (is (= "Dataset not found" (:error body))))))))

(deftest test-api-router-coerces-public-dataset-path-params
  (testing "API router parses and validates public dataset path params"
    (let [conn (atom :mock-db)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] conn)
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "prod"}]
                                                   :scopes #{:query}}))
                    config-db/resolve-dataset-ref-materializations (fn [_ dataset-ref]
                                                                     (when (= {:tenant "ka"
                                                                               :dataset-config-key "prod"}
                                                                              dataset-ref)
                                                                       {:pipeline-records [{:dataset.pipeline/id "assistant"
                                                                                            :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                                                            :dataset.pipeline/name "Assistant"
                                                                                            :dataset.pipeline/enabled? true}]}))
                    pipeline/get-dataset-record (fn [_ dataset-id]
                                                  (case dataset-id
                                                    "ds_123" {:dataset/id "ds_123" :dataset/name "Public Docs" :dataset/enabled? true}
                                                    "ds_999" {:dataset/id "ds_999" :dataset/name "Secret Docs" :dataset/enabled? true}
                                                    nil))
                    executor/list-executions (fn [& _] [])]
        (let [response (handler {:request-method :get
                                 :uri "/api/datasets/ds_999"
                                 :path-info "/api/datasets/ds_999"
                                 :headers {"x-api-key" "rag_valid123"}})
              body (json/parse-string (:body response) true)]
          (is (= 404 (:status response)))
          (is (= "Dataset not found" (:error body))))))))

(deftest test-create-dataset-handler-creates-parent-dataset
  (testing "Creating a dataset returns the generated parent dataset record"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    pipeline/create-dataset! (fn [_ opts]
                                               (reset! captured-opts opts)
                                               {:dataset/id "ds_123"
                                                :dataset/name (:name opts)
                                                :dataset/description (:description opts)
                                                :dataset/enabled? true})]
        (let [request {:headers {"x-user-email" "user@example.com"}
                       :body (json-body {:name "Public Docs"
                                         :description "Shared docs"})}
              response (routes/create-dataset-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:name "Public Docs"
                  :description "Shared docs"}
                 @captured-opts))
          (is (= "ds_123" (:datasetId body)))
          (is (= {:id "ds_123"
                  :name "Public Docs"
                  :description "Shared docs"
                  :enabled? true
                  :pipelineCount 0
                  :pipelines []}
                 (:dataset body))))))))

(deftest test-create-dataset-handler-supports-coerced-body-params
  (testing "Direct dataset creation accepts route-coerced body params"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    pipeline/create-dataset! (fn [_ opts]
                                               (reset! captured-opts opts)
                                               {:dataset/id "ds_123"
                                                :dataset/name (:name opts)
                                                :dataset/description (:description opts)
                                                :dataset/enabled? true})]
        (let [response (routes/create-dataset-handler
                        {:headers {"x-user-email" "user@example.com"}
                         :parameters {:body {:name "Public Docs"
                                             :description "Shared docs"}}})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:name "Public Docs"
                  :description "Shared docs"}
                 @captured-opts))
          (is (= "ds_123" (:datasetId body))))))))

(deftest test-update-dataset-handler-updates-parent-dataset
  (testing "Updating a dataset returns the updated parent dataset summary"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    pipeline/update-dataset! (fn [_ opts]
                                               (reset! captured-opts opts)
                                               {:dataset/id (:dataset-id opts)
                                                :dataset/name (:name opts)
                                                :dataset/description (:description opts)
                                                :dataset/enabled? (:enabled? opts)})
                    ;; update-dataset-handler gained this call (datasets.clj:610)
                    ;; while this test was asleep in the nested block. Empty map
                    ;; is what the real fn returns for a dataset with no
                    ;; pipelines, which is this case.
                    config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                    config-db/list-dataset-pipelines (tu/recording-fn [])]
        (let [request {:headers {"x-user-email" "user@example.com"}
                       :path-params {:dataset-id "ds_123"}
                       :body (json-body {:name "Public Docs v2"
                                         :description "Updated docs"
                                         :enabled? false})}
              response (routes/update-dataset-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= {:dataset-id "ds_123"
                  :name "Public Docs v2"
                  :description "Updated docs"
                  :enabled? false}
                 @captured-opts))
          (is (= {:id "ds_123"
                  :name "Public Docs v2"
                  :description "Updated docs"
                  :enabled? false
                  :pipelineCount 0
                  :pipelines []}
                 (:dataset body))))))))

(deftest test-update-pipeline-handler-denies-without-dataset-ceiling
  (testing "Updating a pipeline requires a dataset-root ceiling"
    (with-redefs [db/get-conn (fn [] (atom :mock-db))
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                      (when (= [tenant root slug] ["ka" :dataset "default"])
                                                        {:config.node/id "dataset/ka/default"}))
                  config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                   (when (= "assistant" pipeline-id)
                                                     {:dataset.pipeline/id "assistant"
                                                      :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                  config-core/get-master-key (fn [] "master-key")]
      (let [request {:headers {"x-user-email" "user@example.com"}
                     :path-params {:dataset-id "ds_123"
                                   :pipeline-id "assistant"}
                     :params {"tenant" "ka"
                              "dataset-config-key" "prod"}
                     :body (json-body {:properties {:sourceType "website"}})
                     :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                :api-key.allowed-config-key/root :runtime
                                                :api-key.allowed-config-key/tenant "ka"
                                                :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                :api-key.allowed-config-key/tenant-config-key "default"}]}
            response (routes/update-pipeline-handler request)
            body (json/parse-string (:body response) true)]
        (is (= 403 (:status response)))
        (is (re-find #"not allowed" (:error body)))))))

(deftest test-update-pipeline-handler-supports-coerced-params
  (testing "Direct pipeline updates accept route-coerced path, query, and body params"
    (let [captured-opts (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                     (when (= "assistant" pipeline-id)
                                                       {:dataset.pipeline/id "assistant"
                                                        :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                    config-core/get-master-key (fn [] "master-key")
                    pipeline/update-pipeline! (fn [_ opts]
                                                (reset! captured-opts opts)
                                                true)]
        (let [response (routes/update-pipeline-handler
                        {:headers {"x-user-email" "user@example.com"}
                         :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                        :api-key.allowed-config-key/root :dataset
                                                        :api-key.allowed-config-key/tenant "ka"
                                                        :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                        :api-key.allowed-config-key/tenant-config-key "default"}]
                         :parameters {:path {:dataset-id "ds_123"
                                             :pipeline-id "assistant"}
                                      :query {:tenant "ka"
                                              :dataset-config-key "prod"}
                                      :body {:properties {:sourceType "website"}}}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (true? (:success body)))
          (is (= {:tenant "ka"
                  :tenant-config-key "prod"
                  :pipeline-name "assistant"
                  :properties {:source-type :website}
                  :master-key "master-key"}
                 @captured-opts)))))))

;; ===== Low-level skill execution tests =====

(deftest test-execute-skill-handler-requires-explicit-dataset-selection
  (testing "Direct skill execution requires an explicit dataset scope"
    (let [request {:api-key/dataset-scopes [{:tenant "ka"
                                           :dataset-config-key "prod"}]
                   :path-params {:id "retrieval"}
                   :body (json-body {:inputs {:query "hello"}})}
          response (routes/execute-skill-handler request)
          body (json/parse-string (:body response) true)]
      (is (= 400 (:status response)))
      (is (re-find #"Dataset selection" (:error body))))))

(deftest test-execute-skill-handler-rejects-dataset-outside-key-grants
  ;; The one guarantee this endpoint actually makes, and it had no test —
  ;; neither here nor at select-request-dataset-ref!. The sibling test above
  ;; covers "you must name a dataset"; this covers "and it must be yours".
  ;; Contract recorded in decisions/execute-skill-authorization.md.
  (testing "A dataset outside the key's granted scopes is refused"
    (let [response (routes/execute-skill-handler
                    {:api-key/dataset-scopes [{:tenant "ka"
                                               :dataset-config-key "prod"}]
                     :api-key/client-id "client-1"
                     :parameters {:path {:id "retrieval"}
                                  :body {:inputs {:query "hello"}
                                         :dataset-ref {:tenant "ka"
                                                       :dataset-config-key "staging"}}}})
          body (json/parse-string (:body response) true)]
      ;; 403, not 404: the dataset may well exist — the caller may not reach it.
      ;; No config-db stubs are needed because select-request-dataset-ref!
      ;; throws before the dataset is ever resolved.
      (is (= 403 (:status response)))
      (is (re-find #"not allowed to access" (:error body)))))

  (testing "A key with no dataset scopes at all is refused before authorization"
    (let [response (routes/execute-skill-handler
                    {:api-key/client-id "client-1"
                     :parameters {:path {:id "retrieval"}
                                  :body {:inputs {:query "hello"}
                                         :dataset-ref {:tenant "ka"
                                                       :dataset-config-key "prod"}}}})
          body (json/parse-string (:body response) true)]
      ;; 401 rather than 403 — the key is unusable here, not merely unauthorized
      ;; for this dataset. Asserted as the code returns it, not as it reads.
      (is (= 401 (:status response)))
      (is (re-find #"missing dataset scopes" (:error body))))))

(deftest test-execute-skill-handler-has-no-skill-allowlist
  ;; Pins DECISION 2 of decisions/execute-skill-authorization.md: v0.1 ships no
  ;; :allowed-skills gate, because every skill in the production artifact reads
  ;; or computes over a dataset the caller already holds a grant for.
  ;;
  ;; If this test ever fails, the decision is being changed — which is the
  ;; point. The ADR names the trigger: promoting the writing enrichment skills
  ;; out of src-dev (#82) invalidates the reasoning and must revisit the gate.
  (testing "Any registered builtin skill is executable, not just the retrieval one"
    (let [captured (atom nil)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:tenant "ka"
                                                      :dataset-id "ds_123"
                                                      :dataset-config-key "prod"
                                                      :dataset-config {:tenant "ka"
                                                                       :dataset-config-key "prod"}
                                                      :traces {}}))
                    config-db/get-conn (fn [] (atom :config-db))
                    config-core/get-master-key (fn [] "master-key")
                    db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "prod"])
                                                                       {:config.node/id "dataset/ka/prod"
                                                                        :config.node/tenant-config-key "prod"
                                                                        :config.node/enabled? true}))
                    skills-api/execute (fn [skill-id inputs opts]
                                         (reset! captured {:skill-id skill-id
                                                           :inputs inputs
                                                           :opts opts})
                                         {:ok true})]
        (let [response (routes/execute-skill-handler
                        {:api-key/dataset-scopes [{:tenant "ka"
                                                   :dataset-config-key "prod"}]
                         :api-key/client-id "client-1"
                         :parameters {:path {:id "summarization"}
                                      :body {:inputs {:text "hello"}
                                             :dataset-ref {:tenant "ka"
                                                           :dataset-config-key "prod"}}}})]
          (is (= 200 (:status response)))
          (is (= :builtin/summarization (:skill-id @captured))
              "the path param is keywordized into builtin/* with no allowlist"))))))

(deftest test-execute-skill-handler-supports-coerced-params
  (testing "Direct skill execution accepts route-coerced path and body params"
    (let [captured (atom nil)
          _mock-config {:tenant "ka"
                       :dataset-config-key "prod"}]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:tenant "ka"
                                                      :dataset-id "ds_123"
                                                      :dataset-config-key "prod"
                                                      :dataset-config {:tenant "ka"
                                                                       :dataset-config-key "prod"}
                                                      :traces {}}))
                    config-db/get-conn (fn [] (atom :config-db))
                    config-core/get-master-key (fn [] "master-key")
                    db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "prod"])
                                                                       {:config.node/id "dataset/ka/prod"
                                                                        :config.node/tenant-config-key "prod"
                                                                        :config.node/enabled? true}))
                    skills-api/execute (fn [skill-id inputs opts]
                                         (reset! captured {:skill-id skill-id
                                                           :inputs inputs
                                                           :opts opts})
                                         {:ok true})]
        (let [response (routes/execute-skill-handler
                        {:api-key/dataset-scopes [{:tenant "ka"
                                                   :dataset-config-key "prod"}]
                         :api-key/client-id "client-1"
                         :parameters {:path {:id "retrieval"}
                                      :body {:inputs {:query "hello"}
                                             :dataset-ref {:tenant "ka"
                                                           :dataset-config-key "prod"}
                                             :parameters {:top-k 3}}}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= :builtin/retrieval (:skill-id @captured)))
          (is (= {:query "hello"} (:inputs @captured)))
          (is (= {:top-k 3} (get-in @captured [:opts :parameters])))
          (is (= {:ok true} (:result body))))))))

(deftest test-api-router-coerces-execute-skill-body
  (testing "API router parses and validates execute-skill bodies"
    (let [captured (atom nil)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "prod"}]
                                                   :agent-refs []
                                                   :client-id "client-1"
                                                   :scopes #{:query}}))
                    config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:tenant "ka"
                                                      :dataset-id "ds_123"
                                                      :dataset-config-key "prod"
                                                      :dataset-config {:tenant "ka"
                                                                       :dataset-config-key "prod"}
                                                      :traces {}}))
                    config-db/get-conn (fn [] (atom :config-db))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "prod"])
                                                                       {:config.node/id "dataset/ka/prod"
                                                                        :config.node/tenant-config-key "prod"
                                                                        :config.node/enabled? true}))
                    skills-api/execute (fn [skill-id inputs opts]
                                         (reset! captured {:skill-id skill-id
                                                           :inputs inputs
                                                           :opts opts})
                                         {:ok true})]
        (let [response (handler {:request-method :post
                                 :uri "/api/skills/retrieval/execute"
                                 :path-info "/api/skills/retrieval/execute"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:inputs {:query "hello"}
                                                   :datasetRef {:tenant "ka"
                                                                :datasetConfigKey "prod"}
                                                   :parameters {:top-k 3}})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= :builtin/retrieval (:skill-id @captured)))
          (is (= {:top-k 3} (get-in @captured [:opts :parameters])))
          (is (= {:ok true} (:result body))))))))

(deftest test-api-router-rejects-invalid-execute-skill-body
  (testing "API router rejects malformed execute-skill bodies before execution"
    (let [executed? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "prod"}]
                                                   :agent-refs []
                                                   :client-id "client-1"
                                                   :scopes #{:query}}))
                    skills-api/execute (fn [& _]
                                         (reset! executed? true)
                                         nil)]
        (let [response (handler {:request-method :post
                                 :uri "/api/skills/retrieval/execute"
                                 :path-info "/api/skills/retrieval/execute"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:datasetRef {:tenant "ka"
                                                                :datasetConfigKey "prod"}})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @executed?)))))))

(deftest test-create-api-key-handler-missing-name
  (testing "Missing name returns 400"
    (let [request {:user/id "user-123"
                   :body (json-body {:dataset-scopes [{:tenant "ka"
                                                    :dataset-config-key "prod"}]})}
          response (routes/create-api-key-handler request)]
      (is (= 400 (:status response))))))

(deftest test-create-api-key-handler-missing-dataset-scopes
  (testing "Missing dataset-scopes returns 400"
    (let [request {:user/id "user-123"
                   :body (json-body {:name "My API Key"})}
          response (routes/create-api-key-handler request)]
      (is (= 400 (:status response))))))

(deftest test-create-api-key-handler-pipeline-not-found
  (testing "Unknown dataset scope returns 404"
    (with-redefs [config-db/get-dataset-by-ref (fn [_ _ _] nil)
                  config-core/get-master-key (fn [] "master-key")]
      (let [request {:user/id "user-123"
                     :body (json-body {:name "My Key"
                                       :dataset-scopes [{:tenant "ka"
                                                       :dataset-config-key "missing"}]})}
            response (routes/create-api-key-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-create-api-key-handler-success
  (testing "Successful API key creation returns key"
    (let [captured-opts (atom nil)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:id "entity-1"}))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-conn (fn [] (atom :config-db))
                    agents-db/get-agent (fn [_ agent-id]
                                          (when (= agent-id "builtin/agent-rag-agent")
                                            {:id agent-id}))
                    api-keys/generate-api-key (fn [] "rag_newkey123")
                    api-keys/get-api-key-info (fn [_ key-id]
                                                (when (= key-id "key-id-123")
                                                  {:api-key/id "key-id-123"
                                                   :api-key/name "My Key"
                                                   :api-key/scopes []
                                                   :api-key/clients []
                                                   :api-key/dataset-scopes [{:tenant "ka"
                                                                            :dataset-config-key "prod"}]
                                                   :api-key/agent-refs ["builtin/agent-rag-agent"]
                                                   :api-key/allowed-config-keys [{:root :runtime
                                                                                  :tenant "ka"
                                                                                  :runtime-config-key "default"}]
                                                   :api-key/skill-graphs []}))
                    api-keys/store-api-key (fn [_ key _name _created-by opts]
                                             (reset! captured-opts opts)
                                           {:api-key-id "key-id-123"
                                            :api-key key})]
        (let [request {:user/id "user-123"
                       :body (json-body {:name "My Key"
                                         :dataset-scopes [{:tenant "ka"
                                                         :dataset-config-key "prod"}]
                                         :agent-refs ["builtin/agent-rag-agent"]
                                         :allowed-config-keys [{:root "runtime"
                                                            :tenant "ka"
                                                            :runtime-config-key "default"}]})}
              response (routes/create-api-key-handler request)]
          (is (= 201 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= "key-id-123" (:api-key-id body)))
            (is (= "rag_newkey123" (:api-key body)))
            (is (= [{:tenant "ka"
                     :dataset-config-key "prod"}]
                   (:dataset-scopes body)))
            (is (= ["builtin/agent-rag-agent"] (:agent-refs body)))
            (is (= [{:root "runtime"
                     :tenant "ka"
                     :runtime-config-key "default"}]
                   (:allowed-config-keys body)))
            (is (some? (:warning body))))
          (is (= {:clients nil
                  :dataset-scopes [{:tenant "ka"
                                  :dataset-config-key "prod"}]
                  :agent-refs ["builtin/agent-rag-agent"]
                  :allowed-config-keys [{:root :runtime
                                     :tenant "ka"
                                     :runtime-config-key "default"}]
                  :skill-graphs []
                  :user-email nil}
                 (select-keys @captured-opts [:clients :dataset-scopes :agent-refs :allowed-config-keys :skill-graphs :user-email]))))))))

(deftest test-create-api-key-handler-supports-coerced-body-params
  (testing "Direct API key creation accepts route-coerced body params"
    (let [captured-opts (atom nil)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:id "entity-1"}))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-conn (fn [] (atom :config-db))
                    agents-db/get-agent (fn [_ agent-id]
                                          (when (= agent-id "builtin/agent-rag-agent")
                                            {:id agent-id}))
                    api-keys/generate-api-key (fn [] "rag_newkey123")
                    api-keys/get-api-key-info (fn [_ key-id]
                                                (when (= key-id "key-id-123")
                                                  {:api-key/id "key-id-123"
                                                   :api-key/name "My Key"
                                                   :api-key/scopes []
                                                   :api-key/clients ["client-1"]
                                                   :api-key/dataset-scopes [{:tenant "ka"
                                                                            :dataset-config-key "prod"}]
                                                   :api-key/agent-refs ["builtin/agent-rag-agent"]
                                                   :api-key/allowed-config-keys []
                                                   :api-key/skill-graphs []}))
                    api-keys/store-api-key (fn [_ _ _ _ opts]
                                             (reset! captured-opts opts)
                                             {:api-key-id "key-id-123"
                                              :api-key "rag_newkey123"})]
        (let [response (routes/create-api-key-handler
                        {:user/id "user-123"
                         :parameters {:body {:name "My Key"
                                             :dataset-scopes [{:tenant "ka"
                                                               :dataset-config-key "prod"}]
                                             :agent-refs ["builtin/agent-rag-agent"]
                                             :client-id "client-1"}}})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "key-id-123" (:api-key-id body)))
          (is (= "client-1" (first (:clients @captured-opts))))
          (is (= ["builtin/agent-rag-agent"] (:agent-refs @captured-opts))))))))

(deftest test-create-api-key-handler-defaults-allowed-config-keys-to-default-root-config-keys
  (testing "Creating an API key without explicit ceilings defaults each root to config-key default"
    (let [captured-opts (atom nil)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:id "entity-1"}))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-conn (fn [] (atom :config-db))
                    api-keys/generate-api-key (fn [] "rag_newkey123")
                    api-keys/store-api-key (fn [_ key _name _created-by opts]
                                             (reset! captured-opts opts)
                                             {:api-key-id "key-id-123"
                                              :api-key key})]
        (let [request {:user/id "user-123"
                       :body (json-body {:name "My Key"
                                         :dataset-scopes [{:tenant "ka"
                                                         :dataset-config-key "prod"}]})}
              response (routes/create-api-key-handler request)]
          (is (= 201 (:status response)))
          (is (= [{:root "platform" :tenant "ka" :tenant-config-key "default"}
                  {:root "runtime" :tenant "ka" :runtime-config-key "default"}
                  {:root "dataset" :tenant "ka" :dataset-config-key "default"}]
                 (:allowed-config-keys @captured-opts))))))))

(deftest test-list-api-keys-handler-success
  (testing "List API keys returns user's keys"
    (let [mock-keys [{:api-key/id "key-1" :api-key/name "Key 1"}
                     {:api-key/id "key-2" :api-key/name "Key 2"}]]
      (with-redefs [api-keys/list-api-keys (fn [_ user-id]
                                             (when (= user-id "user-123")
                                               mock-keys))]
        (let [request {:user/id "user-123"}
              response (routes/list-api-keys-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= 2 (count (:api-keys body))))))))))

(deftest test-revoke-api-key-handler-not-found
  (testing "Revoke non-existent key returns 404"
    (with-redefs [api-keys/get-api-key-info (fn [_ _] nil)]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "nonexistent"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-revoke-api-key-handler-unauthorized
  (testing "Revoke key owned by another user returns 403"
    (with-redefs [api-keys/get-api-key-info (fn [_ _]
                                              {:api-key/id "key-123"
                                               :api-key/created-by "other-user"})]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "key-123"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 403 (:status response)))))))

(deftest test-revoke-api-key-handler-success
  (testing "Successful revocation returns success"
    (with-redefs [api-keys/get-api-key-info (fn [_ _]
                                              {:api-key/id "key-123"
                                               :api-key/created-by "user-123"})
                  api-keys/revoke-api-key (fn [_ _ _] true)]
      (let [request {:user/id "user-123"
                     :user/email "user@example.com"
                     :path-params {:key-id "key-123"}}
            response (routes/revoke-api-key-handler request)]
        (is (= 200 (:status response)))
        (let [body (json/parse-string (:body response) true)]
          (is (true? (:success body))))))))

(deftest test-console-api-router-coerces-api-key-rotate-path-params
  (testing "Console API router passes coerced API key path params into rotation"
    (with-redefs [api-keys/get-api-key-info (fn [_ key-id]
                                              (case key-id
                                                "key-123" {:api-key/id "key-123"
                                                           :api-key/name "Rotated Key"
                                                           :api-key/created-by "user-123"}
                                                "key-456" {:api-key/id "key-456"
                                                           :api-key/name "Rotated Key"
                                                           :api-key/created-by "user-123"}
                                                nil))
                  api-keys/rotate-api-key! (fn [_ _ _]
                                             {:api-key-id "key-456"
                                              :api-key "rag_rotated123"})]
      (let [handler (-> routes/console-api-router
                        wrap-params)
            response (handler {:request-method :post
                               :uri "/console-api/api-keys/key-123/rotate"
                               :path-info "/console-api/api-keys/key-123/rotate"
                               :user/id "user-123"
                               :user/email "user@example.com"})
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "key-456" (:api-key-id body)))
        (is (= "rag_rotated123" (:api-key body)))))))

(deftest test-update-api-key-allowed-config-keys-handler-missing-allowed-config-keys
  (testing "Update allowed config keys requires an explicit allowed-config-keys field"
    (let [request {:user/id "user-123"
                   :path-params {:key-id "key-123"}
                   :body (json-body {})}
          response (routes/update-api-key-allowed-config-keys-handler request)]
      (is (= 400 (:status response))))))

(deftest test-update-api-key-allowed-config-keys-handler-not-found
  (testing "Update allowed config keys on a non-existent key returns 404"
    (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                  api-keys/get-api-key-info (fn [_ _] nil)]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "missing"}
                     :body (json-body {:allowed-config-keys []})}
            response (routes/update-api-key-allowed-config-keys-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-update-api-key-allowed-config-keys-handler-unauthorized
  (testing "Update allowed config keys on another user's key returns 403"
    (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                  api-keys/get-api-key-info (fn [_ _]
                                              {:api-key/id "key-123"
                                               :api-key/created-by "other-user"})]
      (let [request {:user/id "user-123"
                     :path-params {:key-id "key-123"}
                     :body (json-body {:allowed-config-keys []})}
            response (routes/update-api-key-allowed-config-keys-handler request)]
        (is (= 403 (:status response)))))))

(deftest test-update-api-key-allowed-config-keys-handler-success
  (testing "Successful allowed config key replacement returns the updated ceiling set"
    (let [captured-ceilings (atom nil)
          captured-audit-opts (atom nil)]
      (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                    api-keys/get-api-key-info (fn [_ _]
                                                {:api-key/id "key-123"
                                                 :api-key/name "Scoped API Key"
                                                 :api-key/created-by "user-123"})
                    api-keys/replace-api-key-allowed-config-keys! (fn [_ key-id allowed-config-keys opts]
                                                                (reset! captured-ceilings [key-id allowed-config-keys])
                                                                (reset! captured-audit-opts opts)
                                                                {:api-key/id key-id
                                                                 :api-key/name "Scoped API Key"
                                                                 :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                                                            :api-key.allowed-config-key/tenant "ka"
                                                                                            :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                                                            :api-key.allowed-config-key/tenant-config-key "default"}]})]
        (let [request {:user/id "user-123"
                       :user/email "user@example.com"
                       :path-params {:key-id "key-123"}
                       :body (json-body {:allowed-config-keys [{:root "runtime"
                                                            :tenant "ka"
                                                            :node-id "runtime/ka/default"
                                                            :runtime-config-key "default"}]})}
              response (routes/update-api-key-allowed-config-keys-handler request)]
          (is (= 200 (:status response)))
          (is (= ["key-123"
                  [{:root :runtime
                    :tenant "ka"
                    :node-id "runtime/ka/default"
                    :runtime-config-key "default"}]]
                 @captured-ceilings))
          (is (= {:user-email "user@example.com"
                  :user-id "user-123"}
                 @captured-audit-opts))
          (let [body (json/parse-string (:body response) true)]
            (is (= "key-123" (:api-key-id body)))
            (is (= "Scoped API Key" (:name body)))
            (is (= [{:root "runtime"
                     :tenant "ka"
                     :node-id "runtime/ka/default"
                     :runtime-config-key "default"}]
                   (:allowed-config-keys body)))))))))

(deftest test-update-api-key-allowed-config-keys-handler-supports-coerced-params
  (testing "Direct allowed config key updates accept route-coerced path and body params"
    (let [captured-ceilings (atom nil)]
      (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                    api-keys/get-api-key-info (fn [_ _]
                                                {:api-key/id "key-123"
                                                 :api-key/name "Scoped API Key"
                                                 :api-key/created-by "user-123"})
                    api-keys/replace-api-key-allowed-config-keys! (fn [_ key-id allowed-config-keys _]
                                                                    (reset! captured-ceilings [key-id allowed-config-keys])
                                                                    {:api-key/id key-id
                                                                     :api-key/name "Scoped API Key"
                                                                     :api-key/allowed-config-keys []})]
        (let [response (routes/update-api-key-allowed-config-keys-handler
                        {:user/id "user-123"
                         :user/email "user@example.com"
                         :parameters {:path {:key-id "key-123"}
                                      :body {:allowed-config-keys [{:root "runtime"
                                                                   :tenant "ka"
                                                                   :runtime-config-key "default"}]}}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= ["key-123"
                  [{:root :runtime
                    :tenant "ka"
                    :runtime-config-key "default"}]]
                 @captured-ceilings))
          (is (= "key-123" (:api-key-id body))))))))

;; ===== User Handler tests =====

(deftest test-create-user-handler-supports-coerced-body-params
  (testing "Direct user creation accepts route-coerced body params"
    (let [granted-permissions (atom [])]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    ;; The real fn returns a Datahike transaction report on
                    ;; success and {:error ...} on conflict; the caller only
                    ;; reads :error. An empty map is the honest stand-in — the
                    ;; previous {:ok true} invented a key auth/create-new-user
                    ;; has never returned (#191).
                    auth/create-new-user (fn [_] {})
                    auth/user-by-email (fn [email]
                                         {:user/id "user-123"
                                          :user/email email})
                    perms/grant-permission! (fn [_ user-id perm-id]
                                              (swap! granted-permissions conj [user-id perm-id]))
                    d/q (fn [& _]
                          {:user/id "user-123"
                           :user/email "new@example.com"
                           :user/created "2026-04-10T12:00:00Z"
                           :user/permissions [{:permission/id "perm.read"
                                               :permission/name "Read"}]})]
        (let [response (routes/create-user-handler
                        {:user/id "admin-1"
                         :parameters {:body {:email "new@example.com"
                                             :permissions ["perm.read"]}}})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "user-123" (get-in body [:user :id])))
          (is (= [["user-123" "perm.read"]] @granted-permissions)))))))

(deftest test-update-user-permissions-handler-supports-coerced-params
  (testing "Direct user permission updates accept route-coerced path and body params"
    (let [granted-permissions (atom [])
          revoked-permissions (atom [])
          query-count (atom 0)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    d/q (fn [& _]
                          (swap! query-count inc)
                          (if (= 1 @query-count)
                            :user-eid
                            {:user/id "user-123"
                             :user/email "user@example.com"
                             :user/created "2026-04-10T12:00:00Z"
                             :user/permissions [{:permission/id "perm.read"
                                                 :permission/name "Read"}]}))
                    perms/grant-permission! (fn [_ user-id perm-id]
                                              (swap! granted-permissions conj [user-id perm-id]))
                    perms/revoke-permission! (fn [_ user-id perm-id]
                                               (swap! revoked-permissions conj [user-id perm-id]))]
        (let [response (routes/update-user-permissions-handler
                        {:parameters {:path {:id "user-123"}
                                      :body {:add ["perm.read"]
                                             :remove ["perm.write"]}}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "user-123" (get-in body [:user :id])))
          (is (= [["user-123" "perm.read"]] @granted-permissions))
          (is (= [["user-123" "perm.write"]] @revoked-permissions)))))))

;; ===== Conversation Handler tests =====

(deftest test-list-conversations-handler-requires-x-user-id
  (testing "List conversations rejects requests without the external API user id header"
    (let [response (routes/list-conversations-handler {:params {}})
          body (json/parse-string (:body response) true)]
      (is (= 400 (:status response)))
      (is (= "Missing X-User-Id header" (:error body))))))

(deftest test-list-conversations-handler-uses-external-user-id-only
  (testing "List conversations scopes directly to X-User-Id and does not touch internal auth provisioning"
    (let [requested-user-ids (atom [])
          mock-result {:conversations [{:conversation/id "conv-1"
                                        :conversation/topic "Topic 1"
                                        :conversation/agent-id "builtin/agent-rag-agent"
                                        :conversation/user-id "external-user-123"}]
                       :total 1
                       :page-size 50
                       :page-index 0}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    auth/create-new-user (fn [& _]
                                           (throw (ex-info "should not provision internal user" {})))
                    d/entity (fn [& _]
                               (throw (ex-info "should not query internal user entity" {})))
                    d/pull (fn [& _]
                             (throw (ex-info "should not pull internal user by email" {})))
                    db/conversations-by-user-paginated (fn [_ user-id _ _ _]
                                                         (swap! requested-user-ids conj user-id)
                                                         mock-result)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :params {}}
              response (routes/list-conversations-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= ["external-user-123"] @requested-user-ids))
          (is (= 1 (count (:conversations body))))
          (is (= "external-user-123"
                 (get-in body [:conversations 0 :userId])))
          (is (= 1 (:total body))))))))

(defn- create-conversation-with-agents
  "Drive create-conversation-handler with a stubbed agent registry. Stubs
   `config-db/get-conn` as well as `db/get-conn` because #349 made agent
   selection consult the registry — without it this reaches whatever config DB
   the runner happens to have, and the assertion depends on the machine."
  [enabled-agents body]
  (with-redefs [db/get-conn (fn [] (atom {:db true}))
                config-db/get-conn (fn [] (atom {:db true}))
                agents-db/list-enabled-agents (fn [_] enabled-agents)
                agents-db/get-agent (fn [_ id]
                                      (some #(when (= id (:id %)) %) enabled-agents))
                ;; Persistence, so a SUCCESSFUL selection reaches 201 rather
                ;; than dying on the fake conn and reporting 500.
                db/transact-new-msg-thread (fn [_ _ _ _] {:conversation-id "conv-1"})
                db/normalize-tags (fn [tags] (vec tags))
                db/conversation-by-id (fn [_ _]
                                        {:conversation/id "conv-1"
                                         :conversation/topic nil
                                         :conversation/agent-id "a/one"
                                         :conversation/user-id "external-user-123"
                                         :conversation/tags []
                                         :conversation/created 1})]
    (let [response (routes/create-conversation-handler
                     {:headers {"x-user-id" "external-user-123"}
                      :body (json-body body)})]
      {:status (:status response)
       :error (:error (json/parse-string (:body response) true))})))

(deftest test-create-conversation-handler-no-grants-asks-which-agent
  (testing "A key with no agent grants is AUTHORIZED for every agent (#349), so
            with several reachable it is asked which — not refused with a 401.
            This replaces an assertion that pinned the old 401; the change is
            deliberate and is called out in the PR body."
    (let [{:keys [status error]}
          (create-conversation-with-agents
            [{:id "a/one" :enabled? true} {:id "a/two" :enabled? true}]
            {})]
      (is (= 400 status))
      (is (= "Multiple agents are available; specify agent-id" error)))))

(deftest test-create-conversation-handler-no-grants-one-agent-defaults
  (testing "and with exactly one reachable agent it defaults to it rather than
            refusing — the row that used to 401 with one agent available"
    (let [{:keys [status]}
          (create-conversation-with-agents [{:id "a/one" :enabled? true}] {})]
      (is (= 201 status)))))

(deftest test-create-conversation-handler-rejects-unknown-agent
  (testing "THE DATA-INTEGRITY ROW: naming an agent that does not exist used to
            return 201 and PERSIST a conversation with that agentId (#349)"
    (let [{:keys [status]}
          (create-conversation-with-agents
            [{:id "a/one" :enabled? true}]
            {:agent-id "no/such-agent-xyz"})]
      (is (= 404 status)))))

(deftest test-create-conversation-handler-success
  (testing "Create conversation stores the opaque external user id and never touches internal auth provisioning"
    (let [created (atom nil)
          mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Ny tråd"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    config-db/get-conn (fn [] (atom {:db true}))
                    agents-db/list-enabled-agents (fn [_] [(example-agent)])
                    auth/create-new-user (fn [& _]
                                           (throw (ex-info "should not provision internal user" {})))
                    d/entity (fn [& _]
                               (throw (ex-info "should not query internal user entity" {})))
                    d/pull (fn [& _]
                             (throw (ex-info "should not pull internal user by email" {})))
                    db/transact-new-msg-thread (fn [_ agent-id user-id filter-value]
                                                 (reset! created {:agent-id agent-id
                                                                  :user-id user-id
                                                                  :filter-value filter-value})
                                                 {:conversation-id "conv-1"})
                    db/conversation-by-id (fn [_ _] mock-conv)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :api-key/agent-refs ["builtin/agent-rag-agent"]
                       :body (json-body {:filterValue {:source "api"}})}
              response (routes/create-conversation-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:agent-id "builtin/agent-rag-agent"
                  :user-id "external-user-123"
                  :filter-value {:source "api"}}
                 @created))
          (is (= "builtin/agent-rag-agent"
                 (get-in body [:conversation :agentId])))
          (is (= "external-user-123"
                 (get-in body [:conversation :userId]))))))))

(deftest test-create-conversation-handler-supports-tags
  (testing "Create conversation accepts tags and persists them through the public API"
    (let [created (atom nil)
          tagged-conv {:conversation/id "conv-1"
                       :conversation/topic "Ny tråd"
                       :conversation/agent-id "builtin/agent-rag-agent"
                       :conversation/user-id "external-user-123"
                       :conversation/tags ["alpha" "beta"]
                       :conversation/created 1234567890}
          tagged-tags (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    config-db/get-conn (fn [] (atom {:db true}))
                    agents-db/list-enabled-agents (fn [_] [(example-agent)])
                    db/transact-new-msg-thread (fn [_ agent-id user-id filter-value]
                                                 (reset! created {:agent-id agent-id
                                                                  :user-id user-id
                                                                  :filter-value filter-value})
                                                 {:conversation-id "conv-1"})
                    db/rename-convo-topic (fn [& _] nil)
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))
                    db/conversation-by-id (fn [_ _] tagged-conv)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :api-key/agent-refs ["builtin/agent-rag-agent"]
                       :body (json-body {:title "Tagged thread"
                                         :tags ["alpha" "beta"]})}
              response (routes/create-conversation-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:agent-id "builtin/agent-rag-agent"
                  :user-id "external-user-123"
                  :filter-value nil}
                 @created))
          (is (= ["conv-1" ["alpha" "beta"]] @tagged-tags))
          (is (= ["alpha" "beta"] (get-in body [:conversation :tags]))))))))

(deftest test-create-conversation-handler-supports-coerced-body-params
  (testing "Create conversation accepts route-coerced body params"
    (let [created (atom nil)
          tagged-tags (atom nil)
          mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Ny tråd"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    config-db/get-conn (fn [] (atom {:db true}))
                    agents-db/list-enabled-agents (fn [_] [(example-agent)])
                    db/transact-new-msg-thread (fn [_ agent-id user-id filter-value]
                                                 (reset! created {:agent-id agent-id
                                                                  :user-id user-id
                                                                  :filter-value filter-value})
                                                 {:conversation-id "conv-1"})
                    db/rename-convo-topic (fn [& _] nil)
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))
                    db/conversation-by-id (fn [_ _] mock-conv)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :api-key/agent-refs ["builtin/agent-rag-agent"]
                       :parameters {:body {:filter-value {:source "api"}
                                           :tags ["alpha" "beta"]}}}
              response (routes/create-conversation-handler request)]
          (is (= 201 (:status response)))
          (is (= {:agent-id "builtin/agent-rag-agent"
                  :user-id "external-user-123"
                  :filter-value {:source "api"}}
                 @created))
          (is (= ["conv-1" ["alpha" "beta"]] @tagged-tags)))))))

(deftest test-get-conversation-handler-not-found
  (testing "Get non-existent conversation returns 404"
    (with-redefs [db/get-conn (fn [] (atom {:db true}))
                  db/conversation-by-id (fn [_ _] nil)]
      (let [request {:headers {"x-user-id" "external-user-123"}
                     :path-params {:id "nonexistent"}}
            response (routes/get-conversation-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-get-conversation-handler-enforces-ownership
  (testing "Get conversation returns 404 when the conversation belongs to a different external user id"
    (with-redefs [db/get-conn (fn [] (atom {:db true}))
                  db/conversation-by-id (fn [_ _]
                                          {:conversation/id "conv-1"
                                           :conversation/user-id "other-external-user"})]
      (let [request {:headers {"x-user-id" "external-user-123"}
                     :path-params {:id "conv-1"}}
            response (routes/get-conversation-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-get-conversation-handler-success
  (testing "Get existing conversation returns conversation with messages"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/tags ["alpha" "beta"]
                     :conversation/created 1234567890}
          mock-messages [{:message/id "msg-1"
                          :message/text "Hello"
                          :message/role :user
                          :message/tags ["alpha"]
                          :message/created 1234567891}]]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _] mock-messages)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}}
              response (routes/get-conversation-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (= "conv-1" (get-in body [:conversation :id])))
            (is (= "builtin/agent-rag-agent"
                   (get-in body [:conversation :agentId])))
            (is (= ["alpha" "beta"]
                   (get-in body [:conversation :tags])))
            (is (= 1 (count (:messages body))))
            (is (= ["alpha"] (get-in body [:messages 0 :tags])))
            (is (nil? (get-in body [:messages 0 :diagnostics])))))))))

(deftest test-get-conversation-handler-includes-diagnostics-when-requested
  (testing "Get conversation returns normalized message diagnostics when the coerced flag is true"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}
          stored-diagnostics {:status :needs-clarification
                              :clarification-request {:question "Which year?"
                                                      :context-summary "Two matching reports."
                                                      :options ["2022" "2023"]}
                              :search-history [{:queries ["digdir report"]
                                                :result-count 2
                                                :new-count 2}]
                              :read-history [{:mode :targeted
                                              :doc-num 12
                                              :returned-count 1
                                              :content-length 400}]}
          mock-messages [{:message/id "msg-1"
                          :message/text "Which year?"
                          :message/role :assistant
                          :message/created 1234567891
                          :message/diagnostics (pr-str stored-diagnostics)}]]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _] mock-messages)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :parameters {:path {:id "conv-1"}
                                    :query {:include_diagnostics true}}}
              response (routes/get-conversation-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= (json/parse-string
                  (json/generate-string
                   (playground-diagnostics/normalize-diagnostics stored-diagnostics))
                  true)
                 (get-in body [:messages 0 :diagnostics]))))))))

(deftest test-get-conversation-handler-supports-coerced-parameters
  (testing "Get conversation accepts route-coerced path and query parameters"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}
          mock-messages [{:message/id "msg-1"
                          :message/text "Hello"
                          :message/role :assistant
                          :message/created 1234567891
                          :message/diagnostics (pr-str {:search-history [{:queries ["q"]
                                                                          :result-count 1}]})}]]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _] mock-messages)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :parameters {:path {:id "conv-1"}
                                    :query {:include_diagnostics true}}}
              response (routes/get-conversation-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (some? (get-in body [:messages 0 :diagnostics]))))))))

(deftest test-get-conversation-handler-ignores-malformed-diagnostics
  (testing "Malformed stored diagnostics do not break the endpoint"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}
          mock-messages [{:message/id "msg-1"
                          :message/text "Hello"
                          :message/role :assistant
                          :message/created 1234567891
                          :message/diagnostics "{not valid edn"}]]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _] mock-messages)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}
                       :params {"include_diagnostics" "true"}}
              response (routes/get-conversation-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (nil? (get-in body [:messages 0 :diagnostics]))))))))

(deftest test-update-conversation-handler-enforces-ownership
  (testing "Update conversation rejects requests for a conversation owned by another external user id"
    (let [rename-called? (atom false)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _]
                                            {:conversation/id "conv-1"
                                             :conversation/user-id "other-external-user"})
                    db/rename-convo-topic (fn [& _]
                                            (reset! rename-called? true))]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}
                       :body (json-body {:title "Updated"})}
              response (routes/update-conversation-handler request)]
          (is (= 404 (:status response)))
          (is (false? @rename-called?)))))))

(deftest test-update-conversation-handler-success
  (testing "Update conversation allows the owning external user id"
    (let [renamed-titles (atom [])
          tagged-tags (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _]
                                            {:conversation/id "conv-1"
                                             :conversation/topic "Updated"
                                             :conversation/agent-id "builtin/agent-rag-agent"
                                             :conversation/tags ["old"]
                                             :conversation/user-id "external-user-123"
                                             :conversation/created 1234567890})
                    db/rename-convo-topic (fn [_ convo-id new-title]
                                            (swap! renamed-titles conj [convo-id new-title]))
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}
                       :body (json-body {:title "Updated"
                                         :tags ["alpha" "beta"]})}
              response (routes/update-conversation-handler request)]
          (is (= 200 (:status response)))
          (is (= [["conv-1" "Updated"]] @renamed-titles))
          (is (= ["conv-1" ["alpha" "beta"]] @tagged-tags)))))))

(deftest test-update-conversation-handler-supports-coerced-body-and-path-params
  (testing "Update conversation accepts route-coerced path and body params"
    (let [renamed-titles (atom [])
          tagged-tags (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _]
                                            {:conversation/id "conv-1"
                                             :conversation/topic "Updated"
                                             :conversation/agent-id "builtin/agent-rag-agent"
                                             :conversation/tags []
                                             :conversation/user-id "external-user-123"
                                             :conversation/created 1234567890})
                    db/rename-convo-topic (fn [_ convo-id new-title]
                                            (swap! renamed-titles conj [convo-id new-title]))
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :parameters {:path {:id "conv-1"}
                                    :body {:title "Updated"
                                           :tags ["alpha"]}}}
              response (routes/update-conversation-handler request)]
          (is (= 200 (:status response)))
          (is (= [["conv-1" "Updated"]] @renamed-titles))
          (is (= ["conv-1" ["alpha"]] @tagged-tags)))))))

(deftest test-delete-conversation-handler-not-found
  (testing "Delete non-existent conversation returns 404"
    (with-redefs [db/get-conn (fn [] (atom {:db true}))
                  db/conversation-by-id (fn [_ _] nil)]
      (let [request {:headers {"x-user-id" "external-user-123"}
                     :path-params {:id "nonexistent"}}
            response (routes/delete-conversation-handler request)]
        (is (= 404 (:status response)))))))

(deftest test-delete-conversation-handler-enforces-ownership
  (testing "Delete conversation rejects requests for a conversation owned by another external user id"
    (let [delete-called? (atom false)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _]
                                            {:db/id 123
                                             :conversation/id "conv-1"
                                             :conversation/user-id "other-external-user"})
                    db/delete-convo (fn [& _]
                                      (reset! delete-called? true))]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}}
              response (routes/delete-conversation-handler request)]
          (is (= 404 (:status response)))
          (is (false? @delete-called?)))))))

(deftest test-delete-conversation-handler-success
  (testing "Delete existing conversation returns success"
    (let [mock-conv {:db/id 123
                     :conversation/id "conv-1"
                     :conversation/user-id "external-user-123"}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/delete-convo (fn [_ _] nil)]
        (let [request {:headers {"x-user-id" "external-user-123"}
                       :path-params {:id "conv-1"}}
              response (routes/delete-conversation-handler request)]
          (is (= 200 (:status response)))
          (let [body (json/parse-string (:body response) true)]
            (is (true? (:success body)))))))))

;; ===== Admin Conversation Handler tests =====

(deftest test-admin-list-conversations-handler-success
  (testing "Admin list conversations returns all conversations across users"
    (let [mock-result {:conversations [{:conversation/id "conv-1"
                                        :conversation/topic "Topic 1"
                                        :conversation/agent-id "builtin/agent-rag-agent"
                                        :conversation/user-id "user-a"
                                        :conversation/tags ["alpha"]}
                                       {:conversation/id "conv-2"
                                        :conversation/topic "Topic 2"
                                        :conversation/agent-id "builtin/agent-rag-agent"
                                        :conversation/user-id "user-b"
                                        :conversation/tags ["beta"]}]
                       :total 2
                       :page-size 50
                       :page-index 0}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (fn [_ page-size page-index _]
                                                (is (= 50 page-size))
                                                (is (= 0 page-index))
                                                mock-result)]
        (let [request {:params {}}
              response (routes/admin-list-conversations-handler request)
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= 2 (count (:conversations body))))
          (is (= "user-a" (get-in body [:conversations 0 :userId])))
          (is (= "user-b" (get-in body [:conversations 1 :userId])))
          (is (= ["alpha"] (get-in body [:conversations 0 :tags])))
          (is (= ["beta"] (get-in body [:conversations 1 :tags])))
          (is (= 2 (:total body))))))))

(deftest test-admin-list-conversations-handler-pagination
  (testing "Admin list conversations respects page params"
    (let [requested-params (atom nil)
          mock-result {:conversations [] :total 0 :page-size 10 :page-index 2}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (fn [_ page-size page-index _]
                                                (reset! requested-params {:page-size page-size
                                                                          :page-index page-index})
                                                mock-result)]
        (let [request {:params {"page_size" "10" "page_index" "2"}}
              response (routes/admin-list-conversations-handler request)]
          (is (= 200 (:status response)))
          (is (= {:page-size 10 :page-index 2} @requested-params)))))))

(deftest test-parse-page-params-supports-keyword-keys-and-fallbacks
  (testing "Page params use shared extraction and reject invalid ranges"
    (is (= {:page-size 25 :page-index 3}
           (routes/parse-page-params {:params {:page_size "25"
                                               :page_index "3"}})))
    (is (= {:page-size 50 :page-index 0}
           (routes/parse-page-params {:params {:page_size "-5"
                                               :page_index "-1"}})))
    (is (= {:page-size 50 :page-index 0}
           (routes/parse-page-params {:params {:page_size "abc"
                                               :page_index "def"}})))
    (is (= {:page-size 100 :page-index 0}
           (routes/parse-page-params {:params {:page_size "1000000"
                                               :page_index "0"}})))))

(deftest test-admin-list-conversations-handler-does-not-require-x-user-id
  (testing "Admin list conversations does not require X-User-Id header"
    (let [mock-result {:conversations [] :total 0 :page-size 50 :page-index 0}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (fn [_ _ _ _] mock-result)]
        (let [response (routes/admin-list-conversations-handler {:params {}})]
          (is (= 200 (:status response))))))))

(deftest test-api-router-coerces-conversation-query-params
  (testing "API router coerces include_diagnostics before the conversation handler runs"
    (let [mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Test Topic"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}
          requested-diagnostics? (atom nil)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversation-by-id (fn [_ _] mock-conv)
                    db/fetch-convo-messages-mapped (fn [_ _]
                                                    (reset! requested-diagnostics? true)
                                                    [{:message/id "msg-1"
                                                      :message/text "Hello"
                                                      :message/role :assistant
                                                      :message/created 1234567891
                                                      :message/diagnostics (pr-str {:search-history [{:queries ["q"]
                                                                                                      :result-count 1}]})}])]
        (let [response (handler {:request-method :get
                                 :uri "/api/conversations/conv-1"
                                 :path-info "/api/conversations/conv-1"
                                 :query-string "include_diagnostics=true"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"}})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is @requested-diagnostics?)
          (is (some? (get-in body [:messages 0 :diagnostics]))))))))

(deftest test-api-router-rejects-invalid-conversation-query-params
  (testing "API router returns 400 JSON for invalid Malli-coerced query params"
    (let [conversation-looked-up? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversation-by-id (fn [& _]
                                            (reset! conversation-looked-up? true)
                                            nil)]
        (let [response (handler {:request-method :get
                                 :uri "/api/conversations/conv-1"
                                 :path-info "/api/conversations/conv-1"
                                 :query-string "include_diagnostics=not-a-bool"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"}})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"])))
          (is (= "Request validation failed" (:error body)))
          (is (false? @conversation-looked-up?)))))))

(deftest test-api-router-coerces-conversation-pagination-query-params
  (testing "API router coerces conversation pagination query params before the handler runs"
    (let [requested-page-params (atom nil)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversations-by-user-paginated (fn [_ external-user-id page-size page-index tags]
                                                         (reset! requested-page-params {:external-user-id external-user-id
                                                                                        :page-size page-size
                                                                                        :page-index page-index
                                                                                        :tags tags})
                                                         {:conversations []
                                                          :total 0
                                                          :page-size page-size
                                                          :page-index page-index})]
        (let [response (handler {:request-method :get
                                 :uri "/api/conversations"
                                 :path-info "/api/conversations"
                                 :query-string "page_size=10&page_index=2&tags=alpha,beta"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"}})]
          (is (= 200 (:status response)))
          (is (= {:external-user-id "external-user-123"
                  :page-size 10
                  :page-index 2
                  :tags ["alpha" "beta"]}
                 @requested-page-params)))))))

(deftest test-api-router-rejects-invalid-conversation-pagination-query-params
  (testing "API router rejects invalid pagination query params before listing conversations"
    (let [list-called? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversations-by-user-paginated (tu/recording-fn
                                                         (reset! list-called? true)
                                                         nil)]
        (let [response (handler {:request-method :get
                                 :uri "/api/conversations"
                                 :path-info "/api/conversations"
                                 :query-string "page_size=not-an-int"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"}})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @list-called?)))))))

(deftest test-api-router-coerces-create-conversation-body
  (testing "API router parses and coerces conversation create request bodies"
    (let [created (atom nil)
          tagged-tags (atom nil)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)
          mock-conv {:conversation/id "conv-1"
                     :conversation/topic "Ny tråd"
                     :conversation/agent-id "builtin/agent-rag-agent"
                     :conversation/tags ["alpha" "beta"]
                     :conversation/user-id "external-user-123"
                     :conversation/created 1234567890}]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    config-db/get-conn (fn [] (atom {:db true}))
                    agents-db/list-enabled-agents (fn [_] [(example-agent)])
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs ["builtin/agent-rag-agent"]
                                                   :scopes #{:query}}))
                    db/transact-new-msg-thread (fn [_ agent-id user-id filter-value]
                                                 (reset! created {:agent-id agent-id
                                                                  :user-id user-id
                                                                  :filter-value filter-value})
                                                 {:conversation-id "conv-1"})
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))
                    db/conversation-by-id (fn [_ _] mock-conv)]
        (let [response (handler {:request-method :post
                                 :uri "/api/conversations"
                                 :path-info "/api/conversations"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"
                                           "content-type" "application/json"}
                                 :body (json-body {:filterValue {:source "api"}
                                                   :tags ["alpha" "beta"]})})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:agent-id "builtin/agent-rag-agent"
                  :user-id "external-user-123"
                  :filter-value {:source "api"}}
                 @created))
          (is (= ["conv-1" ["alpha" "beta"]] @tagged-tags))
          (is (= "conv-1" (get-in body [:conversation :id]))))))))

(deftest test-api-router-rejects-invalid-create-conversation-body
  (testing "API router rejects invalid create-conversation bodies before handler execution"
    (let [create-called? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs ["builtin/agent-rag-agent"]
                                                   :scopes #{:query}}))
                    db/transact-new-msg-thread (fn [& _]
                                                 (reset! create-called? true)
                                                 nil)]
        (let [response (handler {:request-method :post
                                 :uri "/api/conversations"
                                 :path-info "/api/conversations"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"
                                           "content-type" "application/json"}
                                 :body (json-body {:title 123})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @create-called?)))))))

(deftest test-api-router-rejects-malformed-json-body
  (testing "API router returns a 400 JSON response when the request body is not valid JSON"
    (let [create-called? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs ["builtin/agent-rag-agent"]
                                                   :scopes #{:query}}))
                    db/transact-new-msg-thread (fn [& _]
                                                 (reset! create-called? true)
                                                 nil)]
        (let [response (handler {:request-method :post
                                 :uri "/api/conversations"
                                 :path-info "/api/conversations"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"
                                           "content-type" "application/json"}
                                 :body (io/input-stream (.getBytes "{not json" "UTF-8"))})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "application/json" (get-in response [:headers "Content-Type"])))
          (is (= "Invalid JSON body" (:error body)))
          (is (false? @create-called?)))))))

(deftest test-api-router-coerces-update-conversation-body
  (testing "API router parses and coerces conversation update request bodies"
    (let [renamed-titles (atom [])
          tagged-tags (atom nil)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversation-by-id (fn [_ _]
                                            {:conversation/id "conv-1"
                                             :conversation/topic "Updated"
                                             :conversation/agent-id "builtin/agent-rag-agent"
                                             :conversation/tags []
                                             :conversation/user-id "external-user-123"
                                             :conversation/created 1234567890})
                    db/rename-convo-topic (fn [_ convo-id new-title]
                                            (swap! renamed-titles conj [convo-id new-title]))
                    db/set-conversation-tags (fn [_ convo-id tags]
                                               (reset! tagged-tags [convo-id tags]))]
        (let [response (handler {:request-method :put
                                 :uri "/api/conversations/conv-1"
                                 :path-info "/api/conversations/conv-1"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"
                                           "content-type" "application/json"}
                                 :body (json-body {:title "Updated"
                                                   :tags ["alpha" "beta"]})})]
          (is (= 200 (:status response)))
          (is (= [["conv-1" "Updated"]] @renamed-titles))
          (is (= ["conv-1" ["alpha" "beta"]] @tagged-tags)))))))

(deftest test-api-router-rejects-invalid-update-conversation-body
  (testing "API router rejects invalid update-conversation bodies before lookup"
    (let [lookup-called? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :scopes #{:query}}))
                    db/conversation-by-id (fn [& _]
                                            (reset! lookup-called? true)
                                            nil)]
        (let [response (handler {:request-method :put
                                 :uri "/api/conversations/conv-1"
                                 :path-info "/api/conversations/conv-1"
                                 :headers {"x-api-key" "rag_valid123"
                                           "x-user-id" "external-user-123"
                                           "content-type" "application/json"}
                                 :body (json-body {:title 42})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @lookup-called?)))))))

(deftest test-api-router-coerces-runtime-config-resolve-body
  (testing "API router parses and validates runtime config resolve bodies"
    (let [handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                                          :api-key.allowed-config-key/tenant "ka"
                                                                          :api-key.allowed-config-key/node-id "runtime/ka/website"
                                                                          :api-key.allowed-config-key/tenant-config-key "website"}]
                                                   :scopes #{:query}}))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :runtime "frontpage"])
                                                                       {:config.node/id "runtime/ka/frontpage"
                                                                        :config.node/tenant-config-key "frontpage"
                                                                        :config.node/enabled? true}))
                    api-keys/require-allowed-config-key! (fn [& _]
                                                           {:api-key.allowed-config-key/tenant-config-key "website"})
                    cfg/load-runtime-config-v2-with-trace (fn [opts]
                                                            (is (= {:tenant "ka"
                                                                    :tenant-config-key "frontpage"
                                                                    :agent-id "builtin/agent-rag-agent"
                                                                    :paths ["skills.rerank.top-k"]}
                                                                   opts))
                                                            {:config {:skills {:rerank {:top-k 40}}}
                                                             :traces {"skills.rerank.top-k" {:winning-node "runtime/ka/frontpage"}}})]
        (let [response (handler {:request-method :post
                                 :uri "/api/runtime/config/resolve"
                                 :path-info "/api/runtime/config/resolve"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:tenant "ka"
                                                   :runtimeConfigKey "frontpage"
                                                   :agentId "builtin/agent-rag-agent"
                                                   :paths ["skills.rerank.top-k"]})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "frontpage" (:runtime-config-key body)))
          (is (= 40 (get-in body [:config :skills :rerank :top-k]))))))))

(deftest test-api-router-rejects-invalid-runtime-config-resolve-body
  (testing "API router rejects malformed runtime config resolve bodies before resolution"
    (let [resolved? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes []
                                                   :agent-refs []
                                                   :allowed-config-keys []
                                                   :scopes #{:query}}))
                    cfg/load-runtime-config-v2-with-trace (fn [& _]
                                                            (reset! resolved? true)
                                                            nil)]
        (let [response (handler {:request-method :post
                                 :uri "/api/runtime/config/resolve"
                                 :path-info "/api/runtime/config/resolve"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:tenant "ka"
                                                   :runtimeConfigKey 123
                                                   :agentId "builtin/agent-rag-agent"})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @resolved?)))))))

(deftest test-api-router-coerces-dataset-config-resolve-body
  (testing "API router parses and validates dataset config resolve bodies"
    (let [handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "frontpage"}]
                                                   :agent-refs []
                                                   :allowed-config-keys [{:api-key.allowed-config-key/root :dataset
                                                                          :api-key.allowed-config-key/tenant "ka"
                                                                          :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                                          :api-key.allowed-config-key/tenant-config-key "default"}]
                                                   :scopes #{:query}}))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-config-node (fn [_ node-id]
                                                (when (= node-id "dataset/ka/public-docs/default")
                                                  {:config.node/id node-id
                                                   :config.node/tenant-config-key "default"
                                                   :config.node/enabled? true}))
                    config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "frontpage"})
                                                     {:tenant "ka"
                                                      :dataset-id "public-docs"
                                                      :dataset-config-key "public-docs"
                                                      :dataset-node-id "dataset/ka/public-docs/default"}))
                    api-keys/require-allowed-config-key! (fn [& _]
                                                           {:api-key.allowed-config-key/tenant-config-key "default"})
                    cfg/load-dataset-config-v2-with-trace (fn [opts]
                                                            (is (= {:tenant "ka"
                                                                    :dataset-config-key "public-docs"
                                                                    :dataset-id "public-docs"
                                                                    :paths ["pipeline.chunks.minimum-length"]}
                                                                   opts))
                                                            {:config {:pipeline {:chunks {:minimum-length 200}}}
                                                             :traces {"pipeline.chunks.minimum-length" {:winning-node "dataset/ka/public-docs/default"}}})]
        (let [response (handler {:request-method :post
                                 :uri "/api/dataset/config/resolve"
                                 :path-info "/api/dataset/config/resolve"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:tenant "ka"
                                                   :datasetConfigKey "frontpage"
                                                   :paths ["pipeline.chunks.minimum-length"]})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "public-docs" (:dataset-config-key body)))
          (is (= 200 (get-in body [:config :pipeline :chunks :minimum-length]))))))))

(deftest test-api-router-rejects-invalid-dataset-config-resolve-body
  (testing "API router rejects malformed dataset config resolve bodies before resolution"
    (let [resolved? (atom false)
          handler (-> routes/api-router
                      routes/wrap-api-key-auth
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    api-keys/validate-api-key (fn [_ key]
                                                (when (= key "rag_valid123")
                                                  {:dataset-scopes [{:tenant "ka"
                                                                     :dataset-config-key "frontpage"}]
                                                   :agent-refs []
                                                   :allowed-config-keys []
                                                   :scopes #{:query}}))
                    cfg/load-dataset-config-v2-with-trace (fn [& _]
                                                            (reset! resolved? true)
                                                            nil)]
        (let [response (handler {:request-method :post
                                 :uri "/api/dataset/config/resolve"
                                 :path-info "/api/dataset/config/resolve"
                                 :headers {"x-api-key" "rag_valid123"
                                           "content-type" "application/json"}
                                 :body (json-body {:tenant "ka"
                                                   :datasetConfigKey 123})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @resolved?)))))))

(deftest test-console-api-router-coerces-conversation-pagination-query-params
  (testing "Console API router coerces admin conversation pagination query params"
    (let [requested-page-params (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (fn [_ page-size page-index _]
                                                (reset! requested-page-params {:page-size page-size
                                                                              :page-index page-index})
                                                {:conversations []
                                                 :total 0
                                                 :page-size page-size
                                                 :page-index page-index})]
        (let [response (handler {:request-method :get
                                 :uri "/console-api/conversations"
                                 :path-info "/console-api/conversations"
                                 :query-string "page_size=25&page_index=4"})]
          (is (= 200 (:status response)))
          (is (= {:page-size 25
                  :page-index 4}
                 @requested-page-params)))))))

(deftest test-console-api-router-rejects-invalid-conversation-pagination-query-params
  (testing "Console API router rejects invalid admin conversation pagination query params"
    (let [list-called? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom {:db true}))
                    db/conversations-paginated (tu/recording-fn
                                                (reset! list-called? true)
                                                nil)]
        (let [response (handler {:request-method :get
                                 :uri "/console-api/conversations"
                                 :path-info "/console-api/conversations"
                                 :query-string "page_index=-1"})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @list-called?)))))))

(deftest test-console-api-router-coerces-create-api-key-body
  (testing "Console API router parses and validates API key creation bodies"
    (let [captured-opts (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (when (= dataset-ref {:tenant "ka"
                                                                         :dataset-config-key "prod"})
                                                     {:id "entity-1"}))
                    config-core/get-master-key (fn [] "master-key")
                    config-db/get-conn (fn [] (atom :config-db))
                    api-keys/generate-api-key (fn [] "rag_newkey123")
                    api-keys/get-api-key-info (fn [_ key-id]
                                                (when (= key-id "key-id-123")
                                                  {:api-key/id "key-id-123"
                                                   :api-key/name "Scoped Key"
                                                   :api-key/scopes []
                                                   :api-key/clients ["client-1"]
                                                   :api-key/dataset-scopes [{:tenant "ka"
                                                                            :dataset-config-key "prod"}]
                                                   :api-key/agent-refs []
                                                   :api-key/allowed-config-keys [{:root :platform
                                                                                  :tenant "ka"
                                                                                  :tenant-config-key "default"}
                                                                                 {:root :runtime
                                                                                  :tenant "ka"
                                                                                  :runtime-config-key "default"}
                                                                                 {:root :dataset
                                                                                  :tenant "ka"
                                                                                  :dataset-config-key "default"}]
                                                   :api-key/skill-graphs []}))
                    api-keys/store-api-key (fn [_ _ _ _ opts]
                                             (reset! captured-opts opts)
                                             {:api-key-id "key-id-123"
                                              :api-key "rag_newkey123"})]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/api-keys"
                                 :path-info "/console-api/api-keys"
                                 :headers {"content-type" "application/json"}
                                 :user/id "user-123"
                                 :body (json-body {:name "Scoped Key"
                                                   :datasetScopes [{:tenant "ka"
                                                                    :datasetConfigKey "prod"}]
                                                   :clientId "client-1"})})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "key-id-123" (:api-key-id body)))
          (is (= "client-1" (first (:clients @captured-opts))))
          (is (= [{:tenant "ka"
                   :dataset-config-key "prod"}]
                 (:dataset-scopes @captured-opts))))))))

(deftest test-console-api-router-rejects-invalid-create-api-key-body
  (testing "Console API router rejects malformed API key creation bodies before creation"
    (let [created? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [api-keys/store-api-key (tu/recording-fn
                                             (reset! created? true)
                                             nil)]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/api-keys"
                                 :path-info "/console-api/api-keys"
                                 :headers {"content-type" "application/json"}
                                 :user/id "user-123"
                                 :body (json-body {:datasetScopes [{:tenant "ka"
                                                                    :datasetConfigKey "prod"}]})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @created?)))))))

(deftest test-console-api-router-coerces-api-key-allowed-config-keys-update
  (testing "Console API router parses and validates API key allowed config key updates"
    (let [captured-ceilings (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                    api-keys/get-api-key-info (fn [_ _]
                                                {:api-key/id "key-123"
                                                 :api-key/name "Scoped API Key"
                                                 :api-key/created-by "user-123"})
                    api-keys/replace-api-key-allowed-config-keys! (fn [_ key-id allowed-config-keys _]
                                                                    (reset! captured-ceilings [key-id allowed-config-keys])
                                                                    {:api-key/id key-id
                                                                     :api-key/name "Scoped API Key"
                                                                     :api-key/allowed-config-keys [{:api-key.allowed-config-key/root :runtime
                                                                                                    :api-key.allowed-config-key/tenant "ka"
                                                                                                    :api-key.allowed-config-key/node-id "runtime/ka/default"
                                                                                                    :api-key.allowed-config-key/tenant-config-key "default"}]})]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/api-keys/key-123/allowed-config-keys"
                                 :path-info "/console-api/api-keys/key-123/allowed-config-keys"
                                 :headers {"content-type" "application/json"}
                                 :user/id "user-123"
                                 :user/email "user@example.com"
                                 :body (json-body {:allowedConfigKeys [{:root "runtime"
                                                                        :tenant "ka"
                                                                        :nodeId "runtime/ka/default"
                                                                        :runtimeConfigKey "default"}]})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= ["key-123"
                  [{:root :runtime
                    :tenant "ka"
                    :node-id "runtime/ka/default"
                    :runtime-config-key "default"}]]
                 @captured-ceilings))
          (is (= "key-123" (:api-key-id body))))))))

(deftest test-console-api-router-rejects-invalid-api-key-allowed-config-keys-update
  (testing "Console API router rejects malformed allowed config key updates before mutation"
    (let [updated? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [api-keys/replace-api-key-allowed-config-keys! (tu/recording-fn
                                                                    (reset! updated? true)
                                                                    nil)]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/api-keys/key-123/allowed-config-keys"
                                 :path-info "/console-api/api-keys/key-123/allowed-config-keys"
                                 :headers {"content-type" "application/json"}
                                 :user/id "user-123"
                                 :body (json-body {:allowedConfigKeys [{:root "runtime"
                                                                        :tenant 7
                                                                        :runtimeConfigKey "default"}]})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @updated?)))))))

(deftest test-console-api-router-coerces-create-user-body
  (testing "Console API router parses and validates user creation bodies"
    (let [granted-permissions (atom [])
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    ;; The real fn returns a Datahike transaction report on
                    ;; success and {:error ...} on conflict; the caller only
                    ;; reads :error. An empty map is the honest stand-in — the
                    ;; previous {:ok true} invented a key auth/create-new-user
                    ;; has never returned (#191).
                    auth/create-new-user (fn [_] {})
                    auth/user-by-email (fn [email]
                                         {:user/id "user-123"
                                          :user/email email})
                    perms/grant-permission! (fn [_ user-id perm-id]
                                              (swap! granted-permissions conj [user-id perm-id]))
                    d/q (fn [& _]
                          {:user/id "user-123"
                           :user/email "new@example.com"
                           :user/created "2026-04-10T12:00:00Z"
                           :user/permissions [{:permission/id "perm.read"
                                               :permission/name "Read"}]})]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/users"
                                 :path-info "/console-api/users"
                                 :headers {"content-type" "application/json"}
                                 :user/id "admin-1"
                                 :body (json-body {:email "new@example.com"
                                                   :permissions ["perm.read"]})})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "user-123" (get-in body [:user :id])))
          (is (= [["user-123" "perm.read"]] @granted-permissions)))))))

(deftest test-console-api-router-rejects-invalid-create-user-body
  (testing "Console API router rejects malformed user creation bodies before creation"
    (let [created? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [auth/create-new-user (fn [& _]
                                           (reset! created? true)
                                           nil)]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/users"
                                 :path-info "/console-api/users"
                                 :headers {"content-type" "application/json"}
                                 :user/id "admin-1"
                                 :body (json-body {:email 7})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @created?)))))))

(deftest test-console-api-router-coerces-user-permission-update-body
  (testing "Console API router parses and validates user permission update bodies"
    (let [granted-permissions (atom [])
          revoked-permissions (atom [])
          query-count (atom 0)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    d/q (fn [& _]
                          (swap! query-count inc)
                          (if (= 1 @query-count)
                            :user-eid
                            {:user/id "user-123"
                             :user/email "user@example.com"
                             :user/created "2026-04-10T12:00:00Z"
                             :user/permissions [{:permission/id "perm.read"
                                                 :permission/name "Read"}]}))
                    perms/grant-permission! (fn [_ user-id perm-id]
                                              (swap! granted-permissions conj [user-id perm-id]))
                    perms/revoke-permission! (fn [_ user-id perm-id]
                                               (swap! revoked-permissions conj [user-id perm-id]))]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/users/user-123/permissions"
                                 :path-info "/console-api/users/user-123/permissions"
                                 :headers {"content-type" "application/json"}
                                 :body (json-body {:add ["perm.read"]
                                                   :remove ["perm.write"]})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "user-123" (get-in body [:user :id])))
          (is (= [["user-123" "perm.read"]] @granted-permissions))
          (is (= [["user-123" "perm.write"]] @revoked-permissions)))))))

(deftest test-console-api-router-rejects-invalid-user-permission-update-body
  (testing "Console API router rejects malformed user permission update bodies before mutation"
    (let [updated? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [perms/grant-permission! (fn [& _]
                                              (reset! updated? true)
                                              nil)]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/users/user-123/permissions"
                                 :path-info "/console-api/users/user-123/permissions"
                                 :headers {"content-type" "application/json"}
                                 :body (json-body {:add [7]})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @updated?)))))))

(deftest test-console-api-router-coerces-create-dataset-body
  (testing "Console API router parses and validates dataset creation bodies"
    (let [captured-opts (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    pipeline/create-dataset! (fn [_ opts]
                                               (reset! captured-opts opts)
                                               {:dataset/id "ds_123"
                                                :dataset/name (:name opts)
                                                :dataset/description (:description opts)
                                                :dataset/enabled? true})]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/datasets"
                                 :path-info "/console-api/datasets"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :body (json-body {:name "Public Docs"
                                                   :description "Shared docs"})})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= {:name "Public Docs"
                  :description "Shared docs"}
                 @captured-opts))
          (is (= "ds_123" (:datasetId body))))))))

(deftest test-console-api-router-rejects-invalid-create-dataset-body
  (testing "Console API router rejects malformed dataset creation bodies before mutation"
    (let [created? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [pipeline/create-dataset! (fn [& _]
                                               (reset! created? true)
                                               nil)]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/datasets"
                                 :path-info "/console-api/datasets"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :body (json-body {:name 7})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @created?)))))))

(deftest test-console-api-router-coerces-update-dataset-body
  (testing "Console API router parses and validates dataset update bodies"
    (let [captured-opts (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    pipeline/update-dataset! (fn [_ opts]
                                               (reset! captured-opts opts)
                                               {:dataset/id (:dataset-id opts)
                                                :dataset/name (or (:name opts) "Public Docs")
                                                :dataset/description (:description opts)
                                                :dataset/enabled? (if (contains? opts :enabled?)
                                                                    (:enabled? opts)
                                                                    true)})
                    config-db/materialization-contexts-by-pipeline-id (fn [_] {})
                    config-db/list-dataset-pipelines (tu/recording-fn [])]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/datasets/ds_123"
                                 :path-info "/console-api/datasets/ds_123"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :body (json-body {:name "Public Docs v2"
                                                   :enabled? false})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= {:dataset-id "ds_123"
                  :name "Public Docs v2"
                  :enabled? false}
                 @captured-opts))
          (is (= "Public Docs v2" (get-in body [:dataset :name]))))))))

(deftest test-console-api-router-coerces-pipeline-list-query-params
  (testing "Console API router coerces pipeline path and query params"
    (let [handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-db/list-dataset-pipelines (fn [_ dataset-id]
                                                       (when (= "ds_123" dataset-id)
                                                         [{:dataset.pipeline/id "ka:prod:assistant"
                                                           :dataset.pipeline/dataset {:dataset/id "ds_123"}
                                                           :dataset.pipeline/name "Assistant"
                                                           :dataset.pipeline/source-type :website
                                                           :dataset.pipeline/enabled? true}]))
                    ;; pipeline-summary reads :effective-name and
                    ;; :effective-source-type, not the durable fields this test
                    ;; was written against. The real resolver decorates the
                    ;; record with values from the dataset-tree config; stubbed
                    ;; here to resolve them from the record's own values, which
                    ;; is what a single unambiguous materialization node gives.
                    config-db/effective-dataset-pipeline-record
                    (fn [_db record _opts]
                      (assoc record
                             :dataset.pipeline/effective-name
                             (:dataset.pipeline/name record)
                             :dataset.pipeline/effective-source-type
                             (:dataset.pipeline/source-type record)))]
        (let [response (handler {:request-method :get
                                 :uri "/console-api/datasets/ds_123/pipelines"
                                 :path-info "/console-api/datasets/ds_123/pipelines"
                                 :query-string "tenant=ka&dataset-config-key=prod"
                                 :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                                :api-key.allowed-config-key/root :dataset
                                                                :api-key.allowed-config-key/tenant "ka"
                                                                :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                                :api-key.allowed-config-key/tenant-config-key "default"}]})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= [{:id "ka:prod:assistant"
                   :datasetId "ds_123"
                   :name "Assistant"
                   :sourceType "website"
                   :enabled? true}]
                 (:pipelines body))))))))

(deftest test-console-api-router-rejects-invalid-pipeline-list-query-params
  (testing "Console API router rejects missing materialization query params before listing pipelines"
    (let [listed? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [config-db/list-dataset-pipelines (tu/recording-fn
                                                       (reset! listed? true)
                                                       nil)]
        (let [response (handler {:request-method :get
                                 :uri "/console-api/datasets/ds_123/pipelines"
                                 :path-info "/console-api/datasets/ds_123/pipelines"
                                 :query-string "dataset-config-key=prod"})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @listed?)))))))

(deftest test-console-api-router-coerces-create-pipeline-body
  (testing "Console API router parses and validates pipeline creation bodies"
    (let [captured-opts (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-core/get-master-key (fn [] "master-key")
                    pipeline/create-pipeline! (fn [_ opts]
                                                (reset! captured-opts opts)
                                                (pipeline/make-pipeline-id (:tenant opts)
                                                                           (:tenant-config-key opts)
                                                                           (:pipeline-name opts)))]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/datasets/ds_123/pipelines"
                                 :path-info "/console-api/datasets/ds_123/pipelines"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                                :api-key.allowed-config-key/root :dataset
                                                                :api-key.allowed-config-key/tenant "ka"
                                                                :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                                :api-key.allowed-config-key/tenant-config-key "default"}]
                                 :body (json-body {:tenant "ka"
                                                   :datasetConfigKey "prod"
                                                   :pipelineName "assistant"
                                                   :properties {:sourceType "website"}})})
              body (json/parse-string (:body response) true)]
          (is (= 201 (:status response)))
          (is (= "assistant" (:pipeline-name body)))
          (is (= {:tenant "ka"
                  :tenant-config-key "prod"
                  :dataset-id "ds_123"
                  :pipeline-name "assistant"
                  :properties {:source-type :website}
                  :master-key "master-key"}
                 @captured-opts)))))))

(deftest test-console-api-router-rejects-invalid-create-pipeline-body
  (testing "Console API router rejects malformed pipeline creation bodies before mutation"
    (let [created? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [pipeline/create-pipeline! (fn [& _]
                                                (reset! created? true)
                                                nil)]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/datasets/ds_123/pipelines"
                                 :path-info "/console-api/datasets/ds_123/pipelines"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :body (json-body {:tenant "ka"
                                                   :datasetConfigKey "prod"})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @created?)))))))

(deftest test-console-api-router-coerces-update-pipeline-query-and-body
  (testing "Console API router parses and validates pipeline update path, query, and body params"
    (let [captured-opts (atom nil)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                     (when (= "assistant" pipeline-id)
                                                       {:dataset.pipeline/id "assistant"
                                                        :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                    config-core/get-master-key (fn [] "master-key")
                    pipeline/update-pipeline! (fn [_ opts]
                                                (reset! captured-opts opts)
                                                true)]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/datasets/ds_123/pipelines/assistant"
                                 :path-info "/console-api/datasets/ds_123/pipelines/assistant"
                                 :query-string "tenant=ka&dataset-config-key=prod"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                                :api-key.allowed-config-key/root :dataset
                                                                :api-key.allowed-config-key/tenant "ka"
                                                                :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                                :api-key.allowed-config-key/tenant-config-key "default"}]
                                 :body (json-body {:properties {:sourceType "website"}})})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (true? (:success body)))
          (is (= {:tenant "ka"
                  :tenant-config-key "prod"
                  :pipeline-name "assistant"
                  :properties {:source-type :website}
                  :master-key "master-key"}
                 @captured-opts)))))))

(deftest test-console-api-router-rejects-invalid-update-pipeline-query
  (testing "Console API router rejects malformed pipeline update query params before mutation"
    (let [updated? (atom false)
          handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [pipeline/update-pipeline! (fn [& _]
                                                (reset! updated? true)
                                                nil)]
        (let [response (handler {:request-method :put
                                 :uri "/console-api/datasets/ds_123/pipelines/assistant"
                                 :path-info "/console-api/datasets/ds_123/pipelines/assistant"
                                 :query-string "tenant=ka"
                                 :headers {"content-type" "application/json"
                                           "x-user-email" "user@example.com"}
                                 :body (json-body {:properties {:sourceType "website"}})})
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request validation failed" (:error body)))
          (is (false? @updated?)))))))

(deftest test-console-api-router-coerces-execute-pipeline-query-params
  (testing "Console API router parses and validates pipeline execution path and query params"
    (let [handler (-> routes/console-api-router
                      wrap-params)]
      (with-redefs [db/get-conn (fn [] (atom :mock-db))
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     (when (= [tenant root slug] ["ka" :dataset "default"])
                                                                       {:config.node/id "dataset/ka/default"}))
                    config-db/get-dataset-pipeline (fn [_ pipeline-id]
                                                     (when (= "assistant" pipeline-id)
                                                       {:dataset.pipeline/id "assistant"
                                                        :dataset.pipeline/dataset {:dataset/id "ds_123"}}))
                    config-core/get-master-key (fn [] "master-key")
                    executor/execute-pipeline-async! (fn [_ tenant dataset-config-key pipeline-id _ user-email]
                                                      (when (= [tenant dataset-config-key pipeline-id user-email]
                                                               ["ka" "prod" "assistant" "user@example.com"])
                                                        "exec_123"))]
        (let [response (handler {:request-method :post
                                 :uri "/console-api/datasets/ds_123/pipelines/assistant/execute"
                                 :path-info "/console-api/datasets/ds_123/pipelines/assistant/execute"
                                 :query-string "tenant=ka&dataset-config-key=prod"
                                 :headers {"x-user-email" "user@example.com"}
                                 :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "ceiling-1"
                                                                :api-key.allowed-config-key/root :dataset
                                                                :api-key.allowed-config-key/tenant "ka"
                                                                :api-key.allowed-config-key/node-id "dataset/ka/default"
                                                                :api-key.allowed-config-key/tenant-config-key "default"}]})
              body (json/parse-string (:body response) true)]
          (is (= 202 (:status response)))
          (is (= "exec_123" (:executionId body))))))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'digdir.api.routes-test))

;; =============================================================================
;; The skill-graph grant is `modes` on the wire, `skill-graphs` in storage (#167)
;;
;; The rename happens at the advertisement boundary only: renaming the Datahike
;; attributes would mean migrating every existing API key and access policy,
;; which is the wrong trade for a vocabulary fix. These assert that the two
;; names map to each other, so the public field and the stored attribute cannot
;; drift apart - the same guard #162 added for `query`.
;; =============================================================================


;; =============================================================================
;; What a client sees of an API key, asserted as a WHOLE KEY SET (#191)
;;
;; A fabricated or leaked key is invisible to assertions on individual values:
;; every existing test here reads fields it expects and none would notice a
;; field that should not be there. That matters more for this response than
;; most, because the stored entity holds credential lookup material. Neither
;; the digest nor any legacy plaintext may cross this projection boundary.
;;
;; It builds a fresh map today, so nothing leaks. These pin that: change it to
;; merge or pass the entity through and the key set moves, which fails here
;; rather than in someone's logs.
;; =============================================================================

(def ^:private expected-public-api-key-fields
  #{:scopes :expires-at :api-key-id :client-id :name :usage-count
    :allowed-config-keys :created :revoked :dataset-scopes :created-by
    :agent-refs :modes :last-used :key-prefix :key-last-four})

(deftest public-api-key-emits-exactly-the-expected-keys
  (testing "the whole key set, not just the fields a caller happens to read"
    (let [entity {:api-key/id "k1"
                  :api-key/name "test"
                  :api-key/key-digest "one-way-digest"
                  :api-key/prefix "rag_live"
                  :api-key/last-four "tial"
                  :api-key/skill-graphs []}
          public (#'handlers/public-api-key entity)]
      (is (= expected-public-api-key-fields (set (keys public)))
          "unexpected keys reached the client, or expected ones vanished")
      (is (not (contains? (set (keys public)) :api-key/key))
          "the stored plaintext key must never appear in a response")
      (is (not (contains? (set (keys public)) :api-key/key-digest))
          "the lookup digest must never appear in a response")
      (is (not-any? #(= "rag_live_plaintext_credential" %) (vals public))
          "nor may its VALUE appear under any other name"))))

(deftest public-api-key-adds-policy-id-only-when-there-is-one
  (testing "the one conditional key is conditional in both directions"
    (let [without (#'handlers/public-api-key {:api-key/id "k1"})
          with (#'handlers/public-api-key
                 {:api-key/id "k1" :api-key/policy {:access-policy/id "p1"}})]
      (is (= expected-public-api-key-fields (set (keys without))))
      (is (= (conj expected-public-api-key-fields :policy-id) (set (keys with))))
      (is (= "p1" (:policy-id with))))))

(deftest public-access-policy-emits-exactly-the-expected-keys
  (testing "the other response builder, same treatment"
    (let [public (#'handlers/public-access-policy
                   {:access-policy/id "p1"
                    :access-policy/name "policy"
                    :access-policy/skill-graphs []})]
      (is (= #{:policy-id :name :created :created-by :tenants :clients :scopes
               :modes :dataset-scopes :agent-refs :allowed-config-keys}
             (set (keys public)))))))

(deftest api-key-grant-is-advertised-as-modes
  (testing "the response names the public field, sourced from the stored attribute"
    (let [stored-attr (get handlers/stored-modes-attribute :api-key)
          entity {:api-key/id "k1"
                  :api-key/name "test"
                  stored-attr ["builtin/agent-rag-graph-bundled"]}
          public (#'handlers/public-api-key entity)]
      (is (= :modes handlers/public-modes-field)
          "the advertised name is `modes`")
      (is (= :api-key/skill-graphs stored-attr)
          "storage is unchanged - renaming it would need a data migration")
      (is (= ["builtin/agent-rag-graph-bundled"]
             (get public handlers/public-modes-field))
          "the stored value must surface under the public name")
      (is (not (contains? public :skill-graphs))
          "the internal name must not reach a client"))))

(deftest access-policy-grant-is-advertised-as-modes
  (testing "both response builders use the same public name"
    ;; A half-rename - one builder renamed and the other not - is worse than
    ;; the inconsistency it replaces, so both are pinned.
    (let [stored-attr (get handlers/stored-modes-attribute :access-policy)
          public (#'handlers/public-access-policy
                   {:access-policy/id "p1"
                    stored-attr ["builtin/agent-rag-graph-faithful"]})]
      (is (= :access-policy/skill-graphs stored-attr))
      (is (= ["builtin/agent-rag-graph-faithful"]
             (get public handlers/public-modes-field)))
      (is (not (contains? public :skill-graphs))))))

(deftest create-api-key-accepts-modes-and-stores-skill-graphs
  (testing "request side crosses the same boundary in the other direction"
    (let [captured-opts (atom nil)]
      (with-redefs [config-db/get-dataset-by-ref (fn [_ _ _] {:dataset-id "d1"})
                    api-keys/store-api-key (fn [_ key _name _created-by opts]
                                             (reset! captured-opts opts)
                                             {:api-key-id "key-id-123"
                                              :api-key key})]
        (let [response (routes/create-api-key-handler
                         {:user/id "user-123"
                          :body (json-body {:name "My Key"
                                            :dataset-scopes [{:tenant "ka"
                                                              :dataset-config-key "prod"}]
                                            :modes ["builtin/agent-rag-graph-bundled"]})})]
          (is (= 201 (:status response)))
          (is (= ["builtin/agent-rag-graph-bundled"] (:skill-graphs @captured-opts))
              "the public `modes` field must arrive at storage as :skill-graphs")
          (let [body (json/parse-string (:body response) true)]
            (is (not (contains? body :skill-graphs))
                "and the response must not leak the internal name")))))))
