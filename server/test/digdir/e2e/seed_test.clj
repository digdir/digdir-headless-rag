(ns digdir.e2e.seed-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.agents.db :as agents-db]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.e2e.seed :as seed]))

(deftest maybe-seed-no-op-without-env-var
  (testing "When E2E_API_KEY is unset, maybe-seed! is a no-op.
            This protects production from accidental seeding."
    ;; If E2E_API_KEY is somehow set in the `bb test` shell, this
    ;; assertion is vacuously skipped — prod-shaped runs must not
    ;; have that var set in the first place.
    (when (nil? (System/getenv "E2E_API_KEY"))
      (with-redefs [agents-db/seed-builtin-agents!
                    (fn [& _]
                      (throw (ex-info "must not run without E2E_API_KEY" {})))
                    api-keys/store-api-key
                    (fn [& _]
                      (throw (ex-info "must not run without E2E_API_KEY" {})))]
        (is (= {:seeded? false :reason :not-gated}
               (seed/maybe-seed!)))))))

(deftest seed-azure-config-skips-blank-env-vars
  (testing "Env vars that are missing or empty produce no config writes."
    (let [writes (atom [])]
      (with-redefs [config-db/get-config-node-by-tenant-config-key
                    (fn [& _] {:config.node/id "fake-node"})
                    config-db/set-node-value!
                    (fn [_ opts] (swap! writes conj (:path opts)))]
        (let [result (seed/seed-azure-config-from-env! (atom :stub-conn) "test-tenant")
              azure-set? (some? (System/getenv "AZURE_OPENAI_API_KEY"))]
          (when-not azure-set?
            (is (= [] (:azure-paths-written result)))
            (is (empty? @writes))))))))

(deftest azure-env-mapping-covers-expected-paths
  (testing "The env-name → config-path mapping points at the paths
            the LLM call code actually reads from.

            The mapping itself moved to `digdir.config.env-bridge` when the
            import path needed it too; this asserts on the :azure-openai
            projection, which is what the E2E seed still writes."
    (let [paths (set (vals (env-bridge/env-var->config-path :azure-openai)))]
      (is (contains? paths "services.azure-openai.api-key"))
      (is (contains? paths "services.azure-openai.api-endpoint"))
      (is (contains? paths "services.azure-openai.deployment-name"))
      (is (contains? paths "services.azure-openai.use-azure-openai-api")))))

(deftest seed-is-idempotent
  (testing "Calling seed! twice with the same key is a no-op on the second pass."
    (let [calls (atom {:agents-seeded 0 :e2e-agents-seeded 0 :keys-stored 0 :key-existed? false})
          api-key "rag_test_fixed_key"]
      (with-redefs [agents-db/list-enabled-agents (fn [_] [])
                    agents-db/seed-builtin-agents!
                    (fn [_] (swap! calls update :agents-seeded inc))
                    seed/seed-e2e-fixture-agents!
                    (fn [_] (swap! calls update :e2e-agents-seeded inc))
                    api-keys/store-api-key
                    (fn [& _] (swap! calls update :keys-stored inc))
                    seed/seed-azure-config-from-env!
                    (fn [_] {:azure-paths-written []})
                    config-bootstrap/bootstrap-config-tree!
                    (fn [_ _] nil)]
        ;; First pass — key doesn't exist, gets stored.
        (with-redefs [seed/key-already-stored? (fn [_ _] false)]
          (seed/seed! (atom :stub-conn) api-key))
        ;; Second pass — key exists, skip storing.
        (with-redefs [seed/key-already-stored? (fn [_ _] true)]
          (seed/seed! (atom :stub-conn) api-key)))
      (is (= 2 (:agents-seeded @calls))
          "seed-builtin-agents! runs on every call (it's idempotent internally).")
      (is (= 2 (:e2e-agents-seeded @calls))
          "seed-e2e-fixture-agents! runs on every call (idempotent via upsert-agent!).")
      (is (= 1 (:keys-stored @calls))
          "store-api-key only runs when the key isn't already present."))))

(deftest e2e-fixture-agents-have-required-shape
  (testing "Both Layer-C fixture agents declare the fields the agents/db
            validator and the agent-resolution endpoint expect."
    (let [agents seed/e2e-fixture-agents
          ids (set (map :id agents))]
      (is (= #{"e2e/altinn-docs-default" "e2e/altinn-docs-tuned"} ids)
          "Exactly the two e2e fixtures we expect by id.")
      (doseq [a agents]
        (is (string? (:id a)))
        (is (string? (:name a)))
        (is (string? (:description a)))
        (is (string? (:instructions a)))
        (is (string? (:default-skill-graph a)))
        (is (vector? (:allowed-skill-graphs a)))
        (is (some #(= % (:default-skill-graph a)) (:allowed-skill-graphs a))
            ":default-skill-graph is in :allowed-skill-graphs."))
      (let [tuned (some #(when (= (:id %) "e2e/altinn-docs-tuned") %) agents)
            sp (:skill-params tuned)]
        (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
               (get-in sp [:builtin/retrieval :strategy-weights]))
            "Tuned agent carries the Round-5 phrase-only weights.")
        (is (= {:phrase 5 :content 0 :metadata 0}
               (get-in sp [:builtin/retrieval :strategy-contribution-caps])))
        (is (= 100 (get-in sp [:builtin/retrieval :retrieve-top-k])))
        (is (= 20 (get-in sp [:builtin/rerank :top-k]))))
      (let [default (some #(when (= (:id %) "e2e/altinn-docs-default") %) agents)]
        (is (= {} (:skill-params default))
            "Default agent has no overrides.")))))
