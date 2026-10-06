(ns digdir.api.override-whitelist-test
  "The per-call override layer is a WHITELIST.

   MCP's tool `overrides` (and the Playground's per-call params) become
   skill-params through `api-util/build-rag-skill-params`, whose per-call layer
   `build-skill-params-from-params` copies only named retrieval, rerank and
   synthesis leaves. So an override can never carry an IDENTITY key (a tenant, a
   dataset or a config node) into the skill-params a run reads.

   (`skills/context.clj build-context-from-skill-params`, named by the needle
   report, has no caller; this pin is on the builder the doors actually use.)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.api.util :as api-util]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]))

(def ^:private identity-keys
  #{:tenant :dataset-id :dataset-config-key :tenant-config-key :runtime-config-key :dataset-ref :node-id})

(def ^:private hostile-overrides
  {:tenant "ku" :runtime-config-key "x" :dataset-ref {:tenant "ku" :dataset-config-key "ds-ku"}
   :dataset-config-key "ds-ku" :dataset-id "ds-ku" :tenant-config-key "ds-ku" :node-id "dataset/ku/ds-ku"})

(defn- identity-keys-in
  "Every identity key, keyword or string spelled, anywhere in `m`."
  [m]
  (set (for [node (tree-seq coll? seq m)
             :when (map? node)
             k (keys node)
             :let [kw (keyword (name k))]
             :when (identity-keys kw)]
         kw)))

(deftest n3-a-per-call-override-cannot-carry-identity-into-skill-params
  (testing "CONTROL: the walker finds an identity key at any depth, in either spelling"
    (is (= #{:tenant} (identity-keys-in {:builtin/synthesis {:tenant "ku"}})))
    (is (= #{:dataset-ref} (identity-keys-in {:builtin/retrieval {"dataset-ref" {}}}))))
  (let [built (api-util/build-rag-skill-params {} (assoc hostile-overrides :retrieve-top-k 7) {})]
    (testing "CONTROL: a whitelisted override does come through"
      (is (= 7 (get-in built [:builtin/retrieval :retrieve-top-k]))))
    (is (= #{} (identity-keys-in built))
        "an identity key in the per-call overrides reached the skill-params")))

;; -----------------------------------------------------------------------------
;; The same pin at the MCP door: the skill-params invoke-rag is handed
;; -----------------------------------------------------------------------------

(def ^:private rag-agent
  {:id "builtin/rag-agent" :name "RAG Agent" :description "Answers questions."
   :default-skill-graph :builtin/agent-rag-graph-bundled
   :allowed-skill-graphs [:builtin/agent-rag-graph-bundled]
   :allowed-dataset-scopes [] :enabled? true})

(def ^:private key-kt
  {:api-key/id "key-kt" :api-key/agent-refs ["builtin/rag-agent"]
   :api-key/dataset-scopes [{:tenant "kt" :dataset-config-key "dev"}]
   :api-key/skill-graphs [] :api-key/client-id "client-kt"})

(defn- fresh-conn []
  (let [cfg {:store {:backend :mem :id (str "override-whitelist-" (random-uuid))} :schema-flexibility :read}]
    (d/create-database cfg)
    (doto (d/connect cfg) (d/transact {:tx-data data-db/dh-schema}))))

(declare skill-params-at-the-door*)

(defn- refused-at-the-door [overrides]
  (skill-params-at-the-door* overrides))

(defn- skill-params-at-the-door [overrides]
  (let [{:keys [error skill-params]} (skill-params-at-the-door* overrides)]
    (is (nil? error) (str "CONTROL: the call itself was refused: " (pr-str error)))
    skill-params))

(defn- skill-params-at-the-door*
  "Call the tool as key-kt in its own tenant with `overrides`; return what
   invoke-rag was handed. The builder is REAL; only its inputs are stubbed."
  [overrides]
  (let [captured (atom nil) conn (fresh-conn)]
    (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                  agents-db/list-enabled-agents (fn [_] [rag-agent])
                  agents-db/get-agent (fn [_ id] (when (= id (:id rag-agent)) rag-agent))
                  skills-api/initialize! (fn [] nil)
                  skills-api/get-skill-graph-info
                  (fn [g] {:id g :name (name g) :description "stub"
                           :input-schema [:map [:user-query [:string {:min 1}]]]})
                  config-core/get-master-key (fn [] "k")
                  config-db/get-dataset-by-ref (fn [_ _ _] {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"})
                  data-db/get-conn (fn [] conn)
                  invoke/invoke-rag (fn [args]
                                      (reset! captured args)
                                      {:status :complete :response "ok" :chunks [] :queries []
                                       :search-attribution {} :diagnostics {} :raw-result {} :error nil})]
      (let [r (mcp-tools/invoke-tool key-kt "builtin.rag-agent__agent-rag-graph-bundled"
                                     {"query" "q" "tenant" "kt" "dataset_config_key" "dev" "overrides" overrides}
                                     nil)]
        {:error (:error r) :invoked? (some? @captured) :skill-params (:skill-params @captured)}))))

(deftest n3-the-mcp-door-hands-no-override-identity-to-the-run
  (testing "CONTROL: an accepted override reaches the run, in either spelling"
    (is (= 7 (get-in (skill-params-at-the-door {:retrieve-top-k 7}) [:builtin/retrieval :retrieve-top-k])))
    (is (= 7 (get-in (skill-params-at-the-door {"retrieve-top-k" 7}) [:builtin/retrieval :retrieve-top-k]))))
  (doseq [[label overrides] {"keyword-keyed" (assoc hostile-overrides :retrieve-top-k 7)
                             "string-keyed, as a JSON-RPC body may arrive" (update-keys (assoc hostile-overrides :retrieve-top-k 7) name)}]
    (testing (str label " identity overrides are REFUSED at the door, before the run")
      (let [r (refused-at-the-door overrides)]
        (is (= "invalid_overrides" (get-in r [:error :code])))
        (is (false? (:invoked? r)))))))
