(ns digdir.docs.pipeline.search-phrases-provider-paths-test
  "Search-phrases must be able to resolve its model
   on a REAL config DB.

   `search-phrases-test` stubs `digdir.config.accessor/get` wholesale, and a
   stub answers for a path whether or not it is registered. That is how the
   :openrouter arm could throw on every install while its tests stayed green:
   `services.openrouter.model` had no definition, and an unregistered path
   throws rather than resolving to nil (`accessor/get`). Only a real
   definition table can see that, so this test registers definitions the way
   an install does and asks through the real accessor.

   Since Phase 3 of the provider-resolver change search-phrases has no per-provider model paths: it sends
   the provider's default model. Phase 4 of the provider-resolver change removed the `services.openrouter.*`
   definitions, so the check that `services.openrouter.model` ships went with
   them (`digdir.config.removed-definitions-test` pins their absence)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.schema :as schema]
            [digdir.llm.provider :as provider]
            [digdir.setup.config :as setup-config]))

(defn- sp-model
  "The model search-phrases sends for `tenant`: the provider's default model
   (`digdir.docs.pipeline.search-phrases/create-chat-completion` overwrites the
   caller's). `provider/model-for` is that value, read without a credential."
  [tenant]
  (provider/model-for tenant))

(defn- create-test-db []
  (let [cfg {:store {:backend :mem :id (str "sp-provider-paths-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- attempt
  "Run `f`, answering {:value v} or {:threw message} — so a throw is a FAIL
   that names itself instead of an ERROR that aborts the rest of the test."
  [f]
  (try {:value (f)}
       (catch Exception e {:threw (ex-message e)})))

(deftest search-phrases-resolves-its-model-on-a-real-config-db
  ;; Phase 3 of the provider-resolver change replaced the per-provider model paths this test used to walk
  ;; (`resolve-model` for :azure-openai, :lmstudio and :openrouter, the OpenRouter model-registration issue) with the
  ;; provider decision: search-phrases now sends the provider's default model,
  ;; `provider/model-for`, which reads the decision and one model path per branch.
  ;; The reason this test exists is unchanged: a stubbed accessor answers for an
  ;; unregistered path, so only a real definition table can see a read of a path
  ;; nothing registers.
  (let [conn (create-test-db)]
    (try
      (with-redefs [config-db/get-conn (constantly conn)]
        (with-out-str (setup-config/ensure-all-config-definitions!))
        (doseq [[tenant values] [["az" {"services.llm.provider" :azure
                                        "services.azure-openai.deployment-name" "dep"}]
                                 ["oc" {"services.llm.provider" :openai-compatible
                                        "services.azure-openai.model-name" "generic-model"}]
                                 ["legacy" {"services.azure-openai.use-azure-openai-api" false
                                            "services.azure-openai.model-name" "generic-model"}]]]
          (ops-bootstrap/bootstrap-config-tree! conn {:root :platform :tenant tenant :base-values values}))

        (testing "the instrument can see the throw this test is about"
          (is (re-find #"No config definition registered"
                       (str (:threw (attempt #(accessor/get {:tenant "az"} :services :openrouter :no-such-path)))))))

        (testing "each branch resolves its model through registered paths"
          (is (= {:value "dep"} (attempt #(sp-model "az"))) ":azure - the deployment name")
          (is (= {:value "generic-model"} (attempt #(sp-model "oc"))) ":openai-compatible - model-name")
          (is (= {:value "generic-model"} (attempt #(sp-model "legacy")))
              "the legacy boolean, read as the decision's fallback")))
      (finally (delete-test-db conn)))))
