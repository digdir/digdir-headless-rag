(ns digdir.api.auth-test
  "THE one tenant-and-dataset decision (`digdir.api.auth/authorize-scope!`),
   the one granted-tenant set (`api-keys/granted-tenants`), the
   explicit all-tenant marker, MCP's scope pick through the one decision, and
   the e2e seed's key. The door-level matrix is
   `digdir.api.tenant-scope-doors-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.api.auth :as api-auth]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.data.db :as db]
            [digdir.e2e.seed :as seed]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.secrets :as secrets]))

(defn- refusal [f]
  (try (f) :no-refusal (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:status :reason]))))

(def ^:private A {:api-key/granted-tenants ["kt"] :api-key/dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]})
(def ^:private B {:api-key/granted-tenants [] :api-key/dataset-scopes []})
(def ^:private C (assoc B :api-key/all-tenants? true))

;; =============================================================================
;; authorize-scope!
;; =============================================================================

(deftest authorize-scope-decides-the-tenant-axis-fail-closed
  (is (= "kt" (api-auth/authorize-scope! A {:tenant "kt"})) "CONTROL: a granted tenant")
  (is (= {:status 403 :reason :tenant-not-granted} (refusal #(api-auth/authorize-scope! A {:tenant "ku"}))))
  (is (= {:status 403 :reason :tenant-not-granted} (refusal #(api-auth/authorize-scope! B {:tenant "kt"})))
      "an EMPTY grant is no tenant, never every tenant")
  (is (= "ku" (api-auth/authorize-scope! C {:tenant "ku"})) "the explicit marker is every tenant")
  (is (= {:status 400 :reason :tenant-required} (refusal #(api-auth/authorize-scope! C {:tenant " "}))))
  (is (= {:status 403 :reason :tenant-not-granted}
         (refusal #(api-auth/authorize-scope! (assoc B :api-key/all-tenants? "true") {:tenant "kt"})))
      "only the boolean true marks a key"))

(deftest authorize-scope-decides-the-dataset-axis
  (is (= "kt" (api-auth/authorize-scope! A {:dataset-ref {:tenant "kt" :dataset-config-key "ds-kt"}})))
  (is (= {:status 403 :reason :dataset-not-granted}
         (refusal #(api-auth/authorize-scope! A {:dataset-ref {:tenant "kt" :dataset-config-key "ds-kt2"}})))
      "a tenant grant is not a dataset grant")
  (is (= {:status 403 :reason :dataset-not-granted}
         (refusal #(api-auth/authorize-scope! (assoc A :api-key/granted-tenants ["kt" "ku"])
                                              {:dataset-ref {:tenant "ku" :dataset-config-key "ds-ku"}})))
      "a tenant-only grant reaches no dataset in it")
  (is (= "ku" (api-auth/authorize-scope! C {:dataset-ref {:tenant "ku" :dataset-config-key "any"}})))
  (is (= {:status 400 :reason :scope-mismatch}
         (refusal #(api-auth/authorize-scope! A {:tenant "kt" :dataset-ref {:tenant "ku" :dataset-config-key "ds-ku"}})))))

;; =============================================================================
;; The granted set and the marker, through a real key
;; =============================================================================

(defn- with-store [f]
  (let [cfg {:store {:backend :mem :id (str "auth-test-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (doto (d/connect cfg) (d/transact {:tx-data db/dh-schema}) config-db/ensure-schema!)]
    (try (f conn) (finally (d/release conn)))))

(deftest the-granted-set-is-the-union-of-every-source
  (is (= ["kt" "ku"] (api-keys/granted-tenants ["kt"] nil ["ku" "kt"] ["" " "])))
  (with-store
    (fn [conn]
      (let [plaintext (:api-key (api-keys/create-api-key! conn "legacy" "test"
                                                          {:scopes #{:query}
                                                           :dataset-scopes [{:tenant "ku" :dataset-config-key "ds-ku"}]}))
            id (:api-key-id (api-keys/validate-api-key conn plaintext))]
        ;; a legacy key's explicit tenants used to REPLACE its dataset-scope tenants
        (d/transact conn {:tx-data [{:api-key/id id :api-key/tenants ["kt"]}]})
        (is (= #{"kt" "ku"} (set (:tenants (api-keys/validate-api-key conn plaintext)))))))))

(deftest the-marker-is-false-until-set-and-set-only-as-a-boolean
  (with-store
    (fn [conn]
      (let [plaintext (:api-key (api-keys/create-api-key! conn "k" "test" {:scopes #{:query}}))
            id (:api-key-id (api-keys/validate-api-key conn plaintext))]
        (is (false? (:all-tenants? (api-keys/validate-api-key conn plaintext))) "absent means false")
        (is (true? (api-keys/set-all-tenants! conn id true {:user-id "admin"})))
        (is (true? (:all-tenants? (api-keys/validate-api-key conn plaintext))))
        (api-keys/set-all-tenants! conn id false {:user-id "admin"})
        (is (false? (:all-tenants? (api-keys/validate-api-key conn plaintext))))
        (is (= {:status 400 :reason :bad-all-tenants} (refusal #(api-keys/set-all-tenants! conn id "yes" {}))))
        (is (= {:status 404 :reason :unknown-api-key} (refusal #(api-keys/set-all-tenants! conn "nope" true {}))))))))

;; =============================================================================
;; MCP, /api/tools and /v1: the scope pick goes through the one decision
;; =============================================================================

(def ^:private plain-agent {:id "a" :allowed-dataset-scopes []})
(def ^:private env {:tenant "kt" :dataset-config-key "ds-kt"})

(deftest the-tool-scope-is-authorized-for-the-key
  (with-redefs-fn {#'mcp-tools/env-default-scope (constantly env)}
    (fn []
      (testing "an explicit scope in the arguments"
        (is (= {:scope {:tenant "kt" :dataset-config-key "ds-kt"}}
               (mcp-tools/pick-dataset-scope plain-agent A {"tenant" "kt" "dataset_config_key" "ds-kt"})))
        (is (= "dataset_not_authorized" (get-in (mcp-tools/pick-dataset-scope plain-agent A {"tenant" "ku" "dataset_config_key" "ds-ku"}) [:error :code])))
        (is (= "dataset_not_authorized" (get-in (mcp-tools/pick-dataset-scope plain-agent B {"tenant" "ku" "dataset_config_key" "ds-ku"}) [:error :code]))
            "B named any tenant before the tenant-scope fix")
        (is (= {:scope {:tenant "ku" :dataset-config-key "ds-ku"}}
               (mcp-tools/pick-dataset-scope plain-agent C {"tenant" "ku" "dataset_config_key" "ds-ku"}))))
      (testing "no scope in the arguments: the env default serves only a MARKED key"
        (is (= {:scope {:tenant "kt" :dataset-config-key "ds-kt"}} (mcp-tools/pick-dataset-scope plain-agent A {})) "A's own scope")
        (is (= "no_dataset_scope" (get-in (mcp-tools/pick-dataset-scope plain-agent B {}) [:error :code])) "the env default is not a grant")
        (is (= {:scope env} (mcp-tools/pick-dataset-scope plain-agent C {}))))
      (testing "an agent's declaration can only narrow a key"
        (let [wide {:id "w" :allowed-dataset-scopes [{:tenant "ku" :dataset-config-key "ds-ku"}]}]
          (is (:error (mcp-tools/pick-dataset-scope wide A {})) "an agent listing ku's dataset does not widen A")
          (is (:error (mcp-tools/pick-dataset-scope wide A {"tenant" "ku" "dataset_config_key" "ds-ku"}))))))))

;; =============================================================================
;; The e2e seed's key is never scopeless-and-unmarked
;; =============================================================================

(defn- seed-with-env [env-map]
  (let [stored (atom nil) marked (atom nil)]
    (binding [secrets/*env-lookup* (fn [k] (get env-map k))]
      (with-redefs [agents-db/list-enabled-agents (fn [_] [])
                    agents-db/seed-builtin-agents! (fn [_] nil)
                    seed/seed-e2e-fixture-agents! (fn [_] nil)
                    seed/key-already-stored? (fn [_ _] false)
                    seed/seed-azure-config-from-env! (fn [_ _] {:azure-paths-written []})
                    config-bootstrap/bootstrap-config-tree! (fn [_ _] nil)
                    api-keys/store-api-key (fn [_ _ _ _ opts] (reset! stored opts) {:api-key-id "seed-key"})
                    api-keys/set-all-tenants! (fn [_ id v _] (reset! marked [id v]) v)]
        (seed/seed! (atom :stub-conn) "rag_seed")))
    {:stored @stored :marked @marked}))

(deftest the-seed-key-is-scoped-to-the-deployment-or-marked
  (testing "TENANT and DATASET_CONFIG_KEY set: scoped to that dataset, not marked"
    (let [{:keys [stored marked]} (seed-with-env {"TENANT" "kt" "DATASET_CONFIG_KEY" "ds-kt"})]
      (is (= [{:tenant "kt" :dataset-config-key "ds-kt"}] (:dataset-scopes stored)))
      (is (nil? marked))))
  (testing "unset: marked all-tenant, explicitly"
    (let [{:keys [stored marked]} (seed-with-env {})]
      (is (nil? (:dataset-scopes stored)))
      (is (= ["seed-key" true] marked)))))
