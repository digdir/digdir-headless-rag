(ns digdir.config.ui-test
  "Tests for config UI functions, specifically column key parsing."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.sync :as ops-sync]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]
            [digdir.config.structure :as structure]
            [digdir.config.ui :as ui]
            [digdir.config.ui.common :as common]
            [digdir.config.ui.inheritance :as inheritance]
            [digdir.config.ui.api-keys :as api-keys]
            [digdir.config.ui.agents :as ui-agents]
            [digdir.agents.db :as agents-db]
            [digdir.skills.api :as skills-api]))

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "ui-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn seed-compatibility!
  [conn {:keys [compatibility-id root tenant node-id type value]}]
  (d/transact conn
              {:tx-data [{:config.compatibility/id compatibility-id
                          :config.compatibility/root root
                          :config.compatibility/tenant tenant
                          :config.compatibility/node [:config.node/id node-id]
                          :config.compatibility/type type
                          :config.compatibility/value value
                          :config.compatibility/created-at 1}]}))

(defn seed-runtime-tree!
  ([conn]
   (seed-runtime-tree! conn "ka"))
  ([conn tenant]
   (let [base-node-id (if (= tenant "ka") "runtime-base" (str "runtime-" tenant "-base"))
         frontpage-node-id (if (= tenant "ka") "runtime-frontpage" (str "runtime-" tenant "-frontpage"))
         agent-compat-id (if (= tenant "ka")
                           "compat-runtime-agent-default"
                           (str "compat-runtime-agent-default-" tenant))
         dataset-compat-id (if (= tenant "ka")
                             "compat-runtime-dataset-default"
                             (str "compat-runtime-dataset-default-" tenant))]
     (config-db/upsert-definition! conn
                                   {:path "skills.retrieval.top-k"
                                    :root :runtime
                                    :value-type :number
                                    :encrypted? false
                                    :category :skills
                                    :service :search
                                    :sensitivity :internal
                                    :function :settings})
     (when-not (config-db/get-dataset-record @conn "kudos")
       (config-db/create-dataset! conn
                                  {:dataset-id "kudos"
                                   :name "Kudos"}))
     (config-db/create-config-node! conn
                                    {:root :runtime
                                     :tenant tenant
                                     :node-id base-node-id
                                     :label "Base"
                                     :tenant-config-key "default"})
     (config-db/create-config-node! conn
                                    {:root :runtime
                                     :tenant tenant
                                     :node-id frontpage-node-id
                                     :label "Frontpage"
                                     :tenant-config-key "frontpage"
                                     :parent-id base-node-id})
     (seed-compatibility! conn
                          {:compatibility-id agent-compat-id
                           :root :runtime
                           :tenant tenant
                           :node-id base-node-id
                           :type :agent
                           :value "research-assistant"})
     (seed-compatibility! conn
                          {:compatibility-id dataset-compat-id
                           :root :runtime
                           :tenant tenant
                           :node-id base-node-id
                           :type :dataset
                           :value "kudos"}))))

(defmacro with-runtime-ui-context
  "Point the process-global config conn at `conn` for the duration of `body`.

   The UI fns under test read `config-db/get-conn` instead of taking a conn, so
   the global is the only way to aim them at a test database.

   Restores the previous conn rather than nil-ing it: `get-conn` falls back to
   the file-backed application DB when the global is nil, so nil-ing it leaves
   later readers silently pointed at the real database instead of failing
   loudly. Same pattern as the fixture in `digdir.config.api-keys-test`.

   Mutating a process-global is only safe because nothing else writes it
   concurrently. One thing did: the `digdir.config.cache-invalidation` poller
   calls `data.db/reconnect!` from a daemon thread, which reassigns this global
   and was the cause of this namespace's intermittent
   \"Config node not found runtime-frontpage\". That poller is disabled in test
   JVMs (-Ddigdir.config.poller=false in the :test alias) for exactly that
   reason."
  [conn & body]
  `(let [previous# (config-db/get-conn)]
     (config-db/set-conn! ~conn)
     (try
       (with-redefs [core/use-db-config? (constantly true)
                     core/get-master-key (constantly nil)]
         ~@body)
       (finally
         (config-db/set-conn! previous#)))))

;; =============================================================================
;; parse-value-by-type Tests
;; =============================================================================

(deftest test-parse-value-by-type-boolean
  (testing "Boolean parser handles valid values and blank input"
    (is (true? (ui/parse-value-by-type "true" :boolean)))
    (is (false? (ui/parse-value-by-type "false" :boolean)))
    (is (false? (ui/parse-value-by-type "" :boolean)))
    (is (false? (ui/parse-value-by-type nil :boolean)))))

(deftest test-parse-trace-path-input
  (testing "Trace path input parsing trims blanks and preserves stable order"
    (is (= ["skills.retrieval.top-k"
            "skills.retrieval.enabled"]
           (ui/parse-trace-path-input "\n skills.retrieval.top-k \n\nskills.retrieval.enabled\nskills.retrieval.top-k\n")))))

(deftest test-config-node-option-label
  (testing "Config node labels support both UI-shaped and raw Datahike-shaped node maps"
    (is (= "Frontpage [frontpage] (runtime/ka/frontpage)"
           (ui/config-node-option-label {:label "Frontpage"
                                         :tenant-config-key "frontpage"
                                         :node-id "runtime/ka/frontpage"})))
    (is (= "Frontpage [frontpage] (runtime/ka/frontpage)"
           (ui/config-node-option-label {:config.node/label "Frontpage"
                                         :config.node/tenant-config-key "frontpage"
                                         :config.node/id "runtime/ka/frontpage"})))))

(deftest test-parent-preview-label
  (testing "Parent preview labels only appear for actual changes"
    (is (nil? (ui/parent-preview-label "runtime-base" "runtime-base")))
    (is (= "Preview: node will move from runtime-base to root"
           (ui/parent-preview-label "runtime-base" "")))
    (is (= "Preview: node will move from root to runtime-frontpage"
           (ui/parent-preview-label nil "runtime-frontpage")))))

(deftest test-reparent-impact-summary-label
  (testing "Semantic reparent previews summarize changed values and winners"
    (is (= "Effective config preview: no resolved paths would change."
           (ui/reparent-impact-summary-label {:changed-path-count 0
                                              :changed-value-count 0
                                              :changed-winning-node-count 0})))
    (is (= "Effective config preview: 3 path(s) would change, 2 with different values and 1 with a different winning node."
           (ui/reparent-impact-summary-label {:changed-path-count 3
                                              :changed-value-count 2
                                              :changed-winning-node-count 1})))))

(deftest test-binding-preview-labels
  (testing "Binding previews explain selector and compatibility semantics"
    (is (= "Binding preview: selector would target this node and resolve 2 path(s)."
           (ui/binding-preview-summary-label {:binding-kind :selector
                                              :resolved-path-count 2})))
    (is (nil? (ui/binding-preview-summary-label {:binding-kind :compatibility
                                                 :resolved-path-count 0})))
    (is (= "Binding runtime-profile 'default' would map to runtime/frontpage as legacy selector metadata; request-time callers still choose explicit node tenant-config-keys."
           (ui/binding-preview-note-label {:binding-kind :selector
                                           :binding-type :runtime-profile
                                           :binding-value "default"
                                           :node-id "runtime/frontpage"})))
    (is (nil? (ui/binding-preview-note-label {:binding-kind :compatibility
                                              :binding-type :dataset
                                              :binding-value "kudos"
                                              :node-id "runtime/frontpage"})))))

(deftest test-depth-and-effective-disabled-labels
  (testing "Diagnostics labels explain deep nodes and effective disablement"
    (is (nil? (ui/depth-warning-label 3)))
    (is (= "Depth warning: depth 4 is harder to reason about than the recommended maximum of 4."
           (ui/depth-warning-label 4)))
    (is (= "Disabled at this node (runtime/frontpage)."
           (ui/effective-disabled-label {:node-id "runtime/frontpage"
                                         :enabled? false
                                         :effectively-disabled? true
                                         :disabled-by-node-id "runtime/frontpage"})))
    (is (= "Effectively disabled by ancestor runtime/base."
           (ui/effective-disabled-label {:node-id "runtime/frontpage"
                                         :enabled? true
                                         :effectively-disabled? true
                                         :disabled-by-node-id "runtime/base"})))
    (is (nil? (ui/effective-disabled-label {:node-id "runtime/frontpage"
                                            :enabled? true
                                            :effectively-disabled? false
                                            :disabled-by-node-id nil})))))

(deftest test-suggest-config-node-id
  (testing "Node ID suggestions are derived from label/tenant-config-key and made unique"
    (is (= "runtime/ka/frontpage"
           (ui/suggest-config-node-id :runtime "ka" "Frontpage" "" #{})))
    (is (= "runtime/ka/frontpage-2"
           (ui/suggest-config-node-id :runtime "ka" "Frontpage" "" #{"runtime/ka/frontpage"})))
    (is (= "runtime/ka/custom"
           (ui/suggest-config-node-id :runtime "ka" "Ignored" "custom" #{})))))

(deftest test-inheritance-comparison-supports-node-id-selections
  (let [conn (create-test-db)]
    (try
      (seed-runtime-tree! conn)
      (with-runtime-ui-context conn
        (let [result (common/get-inheritance-editor-comparison-data
                      {:selections [{:tenant "ka"
                                     :root :runtime
                                     :node-id "runtime-base"}]})]
          (is (= :success (:status result)))
          (is (= "runtime-base"
                 (get-in result [:data :selections 0 :node-id])))
          (is (= "runtime-base"
                 (get-in result [:data :results 0 :data :selected-node :config.node/id])))))
      (finally
        (delete-test-db conn)))))

(deftest test-inheritance-comparison-preserves-explicit-selection-metadata
  (let [conn (create-test-db)]
    (try
      (seed-runtime-tree! conn)
      (with-runtime-ui-context conn
        (let [result (common/get-inheritance-editor-comparison-data
                      {:selections [{:tenant "ka"
                                     :root :runtime
                                     :node-id "runtime-frontpage"
                                     :column-label "Frontpage Runtime"
                                     :selection-id "sel-1"}]})]
          (is (= :success (:status result)))
          (is (= "Frontpage Runtime"
                 (get-in result [:data :selections 0 :column-label])))
          (is (= "sel-1"
                 (get-in result [:data :results 0 :selection-id])))))
      (finally
        (delete-test-db conn)))))

(deftest test-node-delete-preview-label
  (testing "Node delete preview summarizes binding, compatibility, and value counts"
    (is (= "Delete preview: removes 2 binding(s), and 1 direct value(s)."
           (ui/node-delete-preview-label {:bindings [1 2]
                                          :compatibilities [:compat]
                                          :values [:a]})))
    (is (= ["Binding: runtime-profile = default"
            "Direct value: skills.retrieval.top-k = 42"]
           (ui/node-delete-preview-lines {:bindings [{:type :runtime-profile
                                                      :value "default"}]
                                          :compatibilities [{:type :agent
                                                             :value "research-assistant"}]
                                          :values [{:path "skills.retrieval.top-k"
                                                    :display-value "42"}]})))))

(deftest test-destructive-preview-labels
  (testing "Binding and value delete previews describe the concrete removal"
    (is (= "Delete preview: removes agent = research-assistant from runtime/frontpage (binding-runtime-agent-default)."
           (ui/binding-delete-preview-label {:binding-id "binding-runtime-agent-default"
                                             :type :agent
                                             :value "research-assistant"
                                             :node-id "runtime/frontpage"})))
    (is (= "Reset preview: removes the direct override skills.retrieval.top-k = 42 from runtime/frontpage; this node will inherit or become unset."
           (ui/value-reset-preview-label {:path "skills.retrieval.top-k"
                                          :display-value "42"
                                          :node-id "runtime/frontpage"})))
    (is (= "After reset: 15 from runtime/base."
           (ui/value-reset-impact-summary-label {:after-value "15"
                                                 :after-winning-node "runtime/base"
                                                 :after-stop-reason :matched})))
    (is (= "After reset: this path would become unset."
           (ui/value-reset-impact-summary-label {:after-value "<unset>"
                                                 :after-winning-node nil
                                                 :after-stop-reason :root-exhausted})))))

(deftest test-mutation-success-label
  (testing "Mutation success labels stay stable for inspector feedback"
    (is (= "Reparented runtime-leaf to runtime-base"
           (ui/mutation-success-label {:op :set-node-parent
                                       :node-id "runtime-leaf"
                                       :parent-id "runtime-base"})))
    (is (= "Reset skills.retrieval.top-k on runtime-base"
           (ui/mutation-success-label {:op :delete-node-value
                                       :node-id "runtime-base"
                                       :path "skills.retrieval.top-k"})))))

(deftest test-node-dirty-fields-and-label
  (testing "Dirty field helpers summarize changed node draft state"
    (is (= [:label :tenant-config-key :parent]
           (ui/node-dirty-fields {:label "Base"
                                  :tenant-config-key "default"
                                  :enabled? true
                                  :parent-id nil}
                                 "Base v2"
                                 "default-v2"
                                 "enabled"
                                 "runtime-root")))
    (is (= "Unsaved changes: label, tenant-config-key, parent"
           (ui/dirty-fields-label [:label :tenant-config-key :parent])))
    (is (nil? (ui/dirty-fields-label [])))))

(deftest test-value-editor-status-label
  (testing "Value editor status explains why an action is disabled or ready"
    (is (= "Enter a path to edit a direct node value."
           (ui/value-editor-status-label "" nil)))
    (is (= "Unknown path for this root."
           (ui/value-editor-status-label "skills.unknown" nil)))
    (is (= "Ready to set skills.retrieval.top-k as :number, multiline"
           (ui/value-editor-status-label "skills.retrieval.top-k"
                                         {:value-type :number
                                          :encrypted? false
                                          :multiline? true})))))

;; =============================================================================
;; Runtime Trace Diagnostics Tests
;; =============================================================================

(deftest test-format-runtime-trace
  (testing "Runtime trace formatting exposes selected, winning, and stopped nodes"
    (is (= {:path "skills.retrieval.top-k"
            :selected-root :runtime
            :selected-tenant "ka"
            :selected-node "runtime-frontpage"
            :winning-node "runtime-base"
            :stopped-at "runtime-base"
            :resolved? true
            :decoded-value 42
            :stop-reason :disabled-node
            :stop-label "Traversal stopped at disabled node"
            :path-nodes [{:node-id "runtime-frontpage"
                          :selected? true
                          :winning? false
                          :stopped? false}
                         {:node-id "runtime-base"
                          :selected? false
                          :winning? true
                          :stopped? true}]}
           (ui/format-runtime-trace
            "skills.retrieval.top-k"
            {:selected-root :runtime
             :selected-tenant "ka"
             :selected-node "runtime-frontpage"
             :winning-node "runtime-base"
             :stopped-at "runtime-base"
             :decoded-value 42
             :stop-reason :disabled-node
             :traversal-path ["runtime-frontpage" "runtime-base"]})))))

(deftest test-build-config-tree-diagnostics
  (testing "Tree diagnostics combine nodes, bindings, and compatibilities into a UI-friendly summary"
    (is (= {:root :runtime
            :tenant "ka"
            :node-count 2
            :definition-count 1
            :binding-count 2
            :definitions [{:path "skills.retrieval.top-k"
                           :value-type :number
                           :encrypted? false
                           :multiline? false}]
            :nodes [{:node-id "runtime-base"
                     :label "Base"
                     :tenant-config-key "default"
                     :enabled? true
                     :depth 0
                     :effectively-disabled? false
                     :disabled-by-node-id nil
                     :depth-warning nil
                     :parent-id nil
                     :binding-count 1
                     :value-count 0
                     :bindings [{:binding-id "binding-1"
                                 :type :runtime-profile
                                 :value "default"}]
                     :values []}
                    {:node-id "runtime-frontpage"
                     :label "Frontpage"
                     :tenant-config-key "frontpage"
                     :enabled? true
                     :depth 1
                     :effectively-disabled? false
                     :disabled-by-node-id nil
                     :depth-warning nil
                     :parent-id "runtime-base"
                     :binding-count 1
                     :value-count 1
                     :bindings [{:binding-id "binding-2"
                                 :type :agent
                                 :value "research-assistant"}]
                     :values [{:path "skills.retrieval.top-k"
                               :value-type :number
                               :encrypted? false
                               :display-value "42"}]}]
            :dataset-catalog nil}
           (ui/build-config-tree-diagnostics
            :runtime
            "ka"
            [{:config.node/id "runtime-frontpage"
              :config.node/label "Frontpage"
              :config.node/tenant-config-key "frontpage"
              :config.node/enabled? true
              :config.node/parent {:config.node/id "runtime-base"}}
             {:config.node/id "runtime-base"
              :config.node/label "Base"
              :config.node/tenant-config-key "default"
              :config.node/enabled? true}]
            [{:config.binding/id "binding-2"
              :config.binding/node {:config.node/id "runtime-frontpage"}
              :config.binding/type :agent
              :config.binding/value "research-assistant"}
             {:config.binding/id "binding-1"
              :config.binding/node {:config.node/id "runtime-base"}
              :config.binding/type :runtime-profile
              :config.binding/value "default"}]
            {:definitions [{:config-def/path "skills.retrieval.top-k"
                            :config-def/value-type :number
                            :config-def/encrypted? false
                            :config-def/multiline? false}]
             :node-values-by-node {"runtime-frontpage"
                                   [{:config.value/definition {:config-def/path "skills.retrieval.top-k"
                                                               :config-def/value-type :number
                                                               :config-def/encrypted? false}
                                     :config.value/raw "42"}]}
             :master-key nil})))))

(deftest test-build-config-tree-diagnostics-flags_effective_disablement_and_depth
  (testing "Tree diagnostics derive depth warnings and ancestor disablement"
    (let [data (ui/build-config-tree-diagnostics
                :runtime
                "ka"
                [{:config.node/id "runtime/deep"
                  :config.node/label "Deep"
                  :config.node/enabled? true
                  :config.node/parent {:config.node/id "runtime/disabled-child"}}
                 {:config.node/id "runtime/disabled-child"
                  :config.node/label "Disabled Child"
                  :config.node/enabled? false
                  :config.node/parent {:config.node/id "runtime/level3"}}
                 {:config.node/id "runtime/level3"
                  :config.node/label "Level 3"
                  :config.node/enabled? true
                  :config.node/parent {:config.node/id "runtime/level2"}}
                 {:config.node/id "runtime/level2"
                  :config.node/label "Level 2"
                  :config.node/enabled? true
                  :config.node/parent {:config.node/id "runtime/root"}}
                 {:config.node/id "runtime/root"
                  :config.node/label "Root"
                  :config.node/enabled? true}]
                []
                {:definitions []
                 :node-values-by-node {}
                 :master-key nil})
          node-by-id (into {} (map (juxt :node-id identity) (:nodes data)))]
      (is (= 4 (:depth (get node-by-id "runtime/deep"))))
      (is (= "runtime/disabled-child" (:disabled-by-node-id (get node-by-id "runtime/deep"))))
      (is (true? (:effectively-disabled? (get node-by-id "runtime/deep"))))
      (is (= "Depth warning: depth 4 is harder to reason about than the recommended maximum of 4."
             (:depth-warning (get node-by-id "runtime/deep"))))
      (is (= "runtime/disabled-child" (:disabled-by-node-id (get node-by-id "runtime/disabled-child"))))
      (is (true? (:effectively-disabled? (get node-by-id "runtime/disabled-child")))))))

(deftest test-build-config-tree-diagnostics-prefers-effective-pipeline-projection-in-dataset-catalog
  (let [conn (create-test-db)]
    (try
      (config-db/upsert-definition! conn
                                    {:path "pipeline.ui.name"
                                     :root :dataset
                                     :value-type :string
                                     :encrypted? false
                                     :description "Pipeline name"
                                     :category :pipelines
                                     :service :other
                                     :sensitivity :internal
                                     :function :settings})
      (config-db/create-dataset! conn {:dataset-id "public-docs"
                                       :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"
                                                :name "Stale Projection"
                                                :enabled? true})
      (let [base-node-id (config-db/dataset-base-node-id "ka" "public-docs")
            materialization-node-id (config-db/dataset-materialization-node-id "ka" "prod" "public-docs" "assistant")]
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id base-node-id
                                             :label "Default"
                                             :tenant-config-key "default"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id materialization-node-id
                                             :label "Assistant Materialization"
                                             :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "assistant")
                                             :parent-id base-node-id})
        (config-db/set-node-value! conn {:root :dataset
                                         :tenant "ka"
                                         :node-id materialization-node-id
                                         :path "pipeline.ui.name"
                                         :value "Config Canonical"
                                         :master-key nil}))
      (let [data (ui/build-config-tree-diagnostics
                  :dataset
                  "ka"
                  []
                  []
                  {:db @conn
                   :datasets [(config-db/get-dataset-record @conn "public-docs")]
                   :pipelines [(config-db/get-dataset-pipeline @conn "assistant")]
                   :definitions []
                   :node-values-by-node {}
                   :master-key nil})]
        (is (= {:datasets [{:dataset-id "public-docs"
                            :name "Public Docs"
                            :enabled? true}]
                :pipelines [{:pipeline-id "assistant"
                             :name "Config Canonical"
                             :dataset-id "public-docs"
                             :enabled? true}]}
               (:dataset-catalog data))))
      (finally
        (delete-test-db conn)))))

(deftest test-get-runtime-trace-data-success
  (testing "Runtime trace diagnostics loader returns selected node, config, and per-path traces"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (ui/get-runtime-trace-data
                                       {:tenant "ka"
                                        :tenant-config-key "frontpage"
                                        :agent-id "research-assistant"
                                        :dataset-id "kudos"
                                        :paths ["skills.retrieval.top-k"
                                                "skills.retrieval.unknown"]})]
            (is (= :success status))
            (is (= {:id "runtime-frontpage"
                    :label "Frontpage"}
                   (:selected-node data)))
            (is (= 42 (get-in data [:config :skills :retrieval :top-k])))
            (is (= "runtime-base" (get-in data [:traces 0 :winning-node])))
            (is (= true (get-in data [:traces 0 :resolved?])))
            (is (= "Definition not found" (get-in data [:traces 1 :stop-label])))
            (is (= false (get-in data [:traces 1 :resolved?])))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-config-tree-diagnostics-data-success
  (testing "Tree diagnostics loader returns runtime nodes, bindings, and root compatibilities"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (ui/get-config-tree-diagnostics-data
                                       {:tenant "ka"
                                        :root :runtime})]
            (is (= :success status))
            (is (= :runtime (:root data)))
            (is (= "ka" (:tenant data)))
            (is (= 2 (:node-count data)))
            (is (= 1 (:definition-count data)))
            (is (= 0 (:binding-count data)))
            (is (= ["runtime-base" "runtime-frontpage"]
                   (mapv :node-id (:nodes data))))
            (is (= "runtime-base" (get-in data [:nodes 1 :parent-id])))
            (is (= "default" (get-in data [:nodes 0 :tenant-config-key])))
            (is (= 0 (get-in data [:nodes 0 :binding-count])))
            (is (nil? (get-in data [:nodes 0 :compatibility-count])))
            (is (= 0 (get-in data [:nodes 1 :binding-count])))
            (is (nil? (get-in data [:nodes 1 :compatibility-count])))
            (is (= 0 (get-in data [:nodes 0 :value-count])))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-inheritance-editor-data-success
  (testing "Inheritance loader returns Datahike-shaped nodes and direct values keyed by node id"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (ui/get-inheritance-editor-data
                                       {:tenant "ka"
                                        :root :runtime
                                        :node-id "runtime-frontpage"})]
            (is (= :success status))
            (is (= ["runtime-base" "runtime-frontpage"]
                   (mapv :config.node/id (:nodes data))))
            (is (= ["runtime-frontpage" "runtime-base"]
                   (mapv :config.node/id (get-in data [:resolution :chain]))))
            (is (= 42
                   (config-db/decode-value (get-in data [:values-by-node "runtime-base" "skills.retrieval.top-k" :config.value/raw])
                                           :number
                                           false
                                           nil)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-inheritance-editor-comparison-data-success
  (testing "Comparison loader returns one inheritance payload per selected tenant"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn "ka")
        (seed-runtime-tree! conn "digdir")
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-frontpage"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "digdir"
                                    :node-id "runtime-digdir-frontpage"
                                    :path "skills.retrieval.top-k"
                                    :value 7
                                    :master-key nil})
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (common/get-inheritance-editor-comparison-data
                                       {:root :runtime
                                        :tenants ["ka" "digdir"]
                                        :tenant-config-key "frontpage"})]
            (is (= :success status))
            (is (= [{:tenant "ka" :root :runtime :tenant-config-key "frontpage"}
                    {:tenant "digdir" :root :runtime :tenant-config-key "frontpage"}]
                   (:selections data)))
            (is (= [:success :success]
                   (mapv :status (:results data))))
            (is (= ["runtime-frontpage" "runtime-digdir-frontpage"]
                   (mapv #(get-in % [:data :selected-node :config.node/id])
                         (:results data))))
            (is (= 42
                   (config-db/decode-value (get-in data [:results 0 :data :values-by-node "runtime-frontpage" "skills.retrieval.top-k" :config.value/raw])
                                           :number
                                           false
                                           nil)))
            (is (= 7
                   (config-db/decode-value (get-in data [:results 1 :data :values-by-node "runtime-digdir-frontpage" "skills.retrieval.top-k" :config.value/raw])
                                           :number
                                            false
                                            nil)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-inheritance-selector-matrix-data-success
  (testing "Selector matrix loader returns rows by tenant and cells by root"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn "ka")
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (common/get-inheritance-selector-matrix-data
                                       {:tenants ["ka"]})]
            (is (= :success status))
            (is (= structure/config-roots-ordered (:roots data)))
            (is (= ["ka"] (mapv :tenant (:rows data))))
            (is (= ["runtime-base" "runtime-frontpage"]
                   (mapv :config.node/id (get-in data [:rows 0 :cells :runtime :nodes]))))))
        (finally
          (delete-test-db conn))))))

(deftest test-inheritance-filter-definitions-handles-category-and-text-search
  (testing "Definition filtering matches keyword categories and free-text path queries"
    (let [definitions [{:config-def/path "pipeline.documents.limit"
                        :config-def/category :pipelines}
                       {:config-def/path "pipeline.documents.offset"
                        :config-def/category :pipelines}
                       {:config-def/path "skills.retrieval.top-k"
                        :config-def/category :skills}]]
      (is (= ["pipeline.documents.limit"
              "pipeline.documents.offset"]
             (mapv :config-def/path
                   (inheritance/filter-definitions definitions :pipelines ""))))
      (is (= ["pipeline.documents.offset"]
             (mapv :config-def/path
                   (inheritance/filter-definitions definitions :pipelines "offset"))))
      (is (= ["pipeline.documents.limit"
              "pipeline.documents.offset"]
             (mapv :config-def/path
                   (inheritance/filter-definitions definitions :all "pipelines")))))))

(deftest test-inheritance-comparison-node-options-only-shows-shared-keys
  (testing "Multi-tenant comparison only offers nodes present in every selected tenant"
    (let [tenant-results [{:tenant "ka"
                           :status :success
                           :data {:tenant "ka"
                                  :nodes [{:config.node/id "runtime-base"
                                           :config.node/label "Base"
                                           :config.node/tenant-config-key "default"}
                                          {:config.node/id "runtime-frontpage"
                                           :config.node/label "Frontpage"
                                           :config.node/tenant-config-key "frontpage"}]}}
                          {:tenant "digdir"
                           :status :success
                           :data {:tenant "digdir"
                                  :nodes [{:config.node/id "runtime-digdir-base"
                                           :config.node/label "Base"
                                           :config.node/tenant-config-key "default"}
                                          {:config.node/id "runtime-digdir-special"
                                           :config.node/label "Special"
                                           :config.node/tenant-config-key "special"}]}}]]
      (is (= ["default"]
             (mapv :tenant-config-key
                   (inheritance/comparison-node-options tenant-results)))))))

(deftest test-inheritance-selector-tree-rows-orders-by-parent-child-depth
  (testing "Selector tree rows preserve hierarchy and emit stable depths"
    (let [nodes [{:config.node/id "runtime-child-b"
                  :config.node/label "Child B"
                  :config.node/tenant-config-key "child-b"
                  :config.node/parent {:config.node/id "runtime-root"}}
                 {:config.node/id "runtime-root"
                  :config.node/label "Root"
                  :config.node/tenant-config-key "default"}
                 {:config.node/id "runtime-child-a"
                  :config.node/label "Child A"
                  :config.node/tenant-config-key "child-a"
                  :config.node/parent {:config.node/id "runtime-root"}}
                 {:config.node/id "runtime-grandchild"
                  :config.node/label "Grandchild"
                  :config.node/tenant-config-key "grandchild"
                  :config.node/parent {:config.node/id "runtime-child-a"}}]
          rows (inheritance/selector-tree-rows nodes
                                               #{["ka" :runtime "default"]
                                                 ["ka" :runtime "child-a"]}
                                               "ka"
                                               :runtime)]
      (is (= [["runtime-root" 0 true 2 true]
              ["runtime-child-a" 1 true 1 true]
              ["runtime-grandchild" 2 false 0 false]
              ["runtime-child-b" 1 false 0 false]]
             (mapv (fn [{:keys [node depth has-children? child-count expanded?]}]
                     [(:config.node/id node)
                      depth
                      has-children?
                      child-count
                      expanded?])
                   rows))))))

(deftest test-get-node-parent-preview-data-success
  (testing "Parent preview loader reports semantic changes before reparenting"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/create-config-node! conn
                                       {:root :runtime
                                        :tenant "ka"
                                        :node-id "runtime-alt"
                                        :label "Alt"
                                        :tenant-config-key "alt"})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-alt"
                                    :path "skills.retrieval.top-k"
                                    :value 7
                                    :master-key nil})
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (ui/get-node-parent-preview-data
                                       {:tenant "ka"
                                        :root :runtime
                                        :node-id "runtime-frontpage"
                                        :parent-id "runtime-alt"})]
            (is (= :success status))
            (is (= "runtime-base" (:current-parent-id data)))
            (is (= "runtime-alt" (:preview-parent-id data)))
            (is (= 1 (:changed-path-count data)))
            (is (= 1 (:changed-value-count data)))
            (is (= 1 (:changed-winning-node-count data)))
            (is (= {:path "skills.retrieval.top-k"
                    :before-value "42"
                    :after-value "7"
                    :before-winning-node "runtime-base"
                    :after-winning-node "runtime-alt"
                    :before-stop-reason :matched
                    :after-stop-reason :matched
                    :changed-value? true
                    :changed-winning-node? true}
                   (first (:paths data))))))
        (finally
          (delete-test-db conn))))))

(deftest test-create-config-tree-binding-rejected
  (testing "Editable tree helper rejects binding creation now that bindings are deprecated"
    (let [conn (create-test-db)
          ;; Restore rather than nil — see with-runtime-ui-context (#88).
          previous-conn (config-db/get-conn)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-conn! conn)
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Binding metadata is deprecated"
               (ui/create-config-tree-binding!
                "ka"
                :runtime
                "runtime-base"
                :runtime-profile
                "default-base"
                "user-1"))))
        (finally
          (config-db/set-conn! previous-conn)
          (delete-test-db conn))))))

(deftest test-get-binding-preview-data-rejected
  (testing "Binding previews are rejected now that binding metadata is deprecated"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (with-runtime-ui-context conn
          (let [{:keys [status error]} (ui/get-binding-preview-data
                                       {:tenant "ka"
                                        :root :runtime
                                        :node-id "runtime-frontpage"
                                        :binding-type :runtime-profile
                                        :binding-value "frontpage-preview"})]
            (is (= :error status))
            (is (= "Binding metadata is deprecated. Use node tenant-config-keys directly." error))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-binding-preview-data-duplicate-binding
  (testing "Binding preview is rejected before duplicate detection now that bindings are deprecated"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (with-runtime-ui-context conn
          (let [{:keys [status error]} (ui/get-binding-preview-data
                                        {:tenant "ka"
                                         :root :runtime
                                         :node-id "runtime-frontpage"
                                         :binding-type :runtime-profile
                                         :binding-value "default"})]
            (is (= :error status))
            (is (= "Binding metadata is deprecated. Use node tenant-config-keys directly." error))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-node-value-reset-preview-data-success
  (testing "Node value reset preview reports the inherited effective value after removing an override"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 15
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-frontpage"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (with-runtime-ui-context conn
          (let [{:keys [status data]} (ui/get-node-value-reset-preview-data
                                       {:tenant "ka"
                                        :root :runtime
                                        :node-id "runtime-frontpage"
                                        :path "skills.retrieval.top-k"})]
            (is (= :success status))
            (is (= "42" (:before-value data)))
            (is (= "15" (:after-value data)))
            (is (= "runtime-frontpage" (:before-winning-node data)))
            (is (= "runtime-base" (:after-winning-node data)))
            (is (= :matched (:before-stop-reason data)))
            (is (= :matched (:after-stop-reason data)))))
        (finally
          (delete-test-db conn))))))

(deftest test-mutate-config-tree-node-lifecycle-success
  (testing "Generic tree mutation helper supports create, update, and delete for nodes"
    (let [conn (create-test-db)
          ;; Restore rather than nil — see with-runtime-ui-context (#88).
          previous-conn (config-db/get-conn)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-conn! conn)
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :create-node
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-leaf"
                   :label "Leaf"
                   :tenant-config-key "leaf"
                   :parent-id "runtime-frontpage"
                   :enabled? true
                   :user-id "user-1"})))
          (is (= "runtime-frontpage"
                 (get-in (config-db/get-config-node @conn "runtime-leaf")
                         [:config.node/parent :config.node/id])))
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :set-node-parent
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-leaf"
                   :parent-id "runtime-base"
                   :user-id "user-1"})))
          (is (= "runtime-base"
                 (get-in (config-db/get-config-node @conn "runtime-leaf")
                         [:config.node/parent :config.node/id])))
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :update-node
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-leaf"
                   :label "Leaf v2"
                   :enabled? false
                   :user-id "user-1"})))
          (let [node (config-db/get-config-node @conn "runtime-leaf")]
            (is (= "Leaf v2" (:config.node/label node)))
            (is (false? (:config.node/enabled? node))))
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :clear-node-parent
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-leaf"
                   :user-id "user-1"})))
          (is (nil? (get-in (config-db/get-config-node @conn "runtime-leaf")
                            [:config.node/parent :config.node/id])))
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :delete-node
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-leaf"
                   :user-id "user-1"})))
          (is (nil? (config-db/get-config-node @conn "runtime-leaf"))))
        (finally
          (config-db/set-conn! previous-conn)
          (delete-test-db conn))))))

(deftest test-mutate-config-tree-binding-and-value-success
  (testing "Generic tree mutation helper supports node value set/reset without compatibility mutations"
    (let [conn (create-test-db)
          ;; Restore rather than nil — see with-runtime-ui-context (#88).
          previous-conn (config-db/get-conn)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-conn! conn)
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :set-node-value
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-base"
                   :path "skills.retrieval.top-k"
                   :raw-value "42"
                   :user-id "user-1"})))
          (is (= 42
                 (config-db/decode-value
                  (:config.value/raw (config-db/get-node-value @conn :runtime "ka" "runtime-base" "skills.retrieval.top-k"))
                  :number
                  false
                  nil)))
          (is (= :ok
                 (ui/mutate-config-tree!
                  {:op :delete-node-value
                   :tenant "ka"
                   :root :runtime
                   :node-id "runtime-base"
                   :path "skills.retrieval.top-k"
                   :user-id "user-1"})))
          (is (nil? (config-db/get-node-value @conn :runtime "ka" "runtime-base" "skills.retrieval.top-k")))
          (is (= #{"compat-runtime-agent-default" "compat-runtime-dataset-default"}
                 (set (d/q '[:find [?id ...]
                             :in $ ?tenant ?root
                             :where
                             [?e :config.compatibility/id ?id]
                             [?e :config.compatibility/tenant ?tenant]
                             [?e :config.compatibility/root ?root]]
                           @conn
                           "ka"
                           :runtime)))))
        (finally
          (config-db/set-conn! previous-conn)
          (delete-test-db conn))))))

(deftest test-mutate-config-tree-destructive-ops-export-snapshot
  (testing "Destructive tree mutations export a tenant snapshot before commit"
    (let [conn (create-test-db)
          ;; Restore rather than nil — see with-runtime-ui-context (#88).
          previous-conn (config-db/get-conn)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-conn! conn)
        (let [snapshot-calls (atom [])]
          (with-redefs [perms/is-admin? (fn [_ _] true)
                        ops-sync/export-to-file (fn [_ file-path opts]
                                             (swap! snapshot-calls conj {:file-path file-path
                                                                         :opts opts})
                                             {:file-path file-path})]
            (is (= :ok
                   (ui/mutate-config-tree!
                    {:op :update-node
                     :tenant "ka"
                     :root :runtime
                     :node-id "runtime-frontpage"
                     :label "Frontpage v2"
                     :enabled? true
                     :user-id "user-1"})))
            (is (= :ok
                   (ui/mutate-config-tree!
                    {:op :set-node-parent
                     :tenant "ka"
                     :root :runtime
                     :node-id "runtime-frontpage"
                     :parent-id "runtime-base"
                     :user-id "user-1"})))
            (is (= 1 (count @snapshot-calls)))
            (is (= {:tenant "ka"
                    :include-audit? false}
                   (:opts (first @snapshot-calls))))
            (is (every? #(str/includes? (:file-path %) "server/state/config-tree-snapshots/ka/")
                        @snapshot-calls))))
        (finally
          (config-db/set-conn! previous-conn)
          (delete-test-db conn))))))

;; =============================================================================
;; API Key UI Helper Tests
;; =============================================================================

(deftest test-all-dataset-option-ids
  (testing "All dataset options normalize and sort canonical dataset IDs"
    (is (= ["altinn-docs:public-docs"
            "ka:kudos"]
           (api-keys/all-dataset-option-ids ["ka:kudos"
                                             "altinn-docs:public-docs"
                                             "ka:kudos"])))))

(deftest test-filter-dataset-ids-by-tenants
  (testing "Dataset options are restricted to the selected tenant set"
    (is (= ["digdir:public-docs"
            "digdir:reports"]
           (api-keys/filter-dataset-ids-by-tenants ["digdir:public-docs"
                                                    "other:docs"
                                                    "digdir:reports"]
                                                   ["digdir"])))
    (is (= []
           (api-keys/filter-dataset-ids-by-tenants ["digdir:public-docs"]
                                                   [])))))

(deftest test-build-dataset-name-index-uses-canonical-dataset-records
  (testing "Dataset name indexes resolve canonical tenant/dataset-config-key entries from dataset base nodes"
    (with-redefs [config-db/list-config-nodes (fn [_ tenant root]
                                                (is (= :dataset root))
                                                (case tenant
                                                  "digdir" [{:config.node/id "dataset/digdir/public-docs/default"
                                                             :config.node/enabled? true}
                                                            {:config.node/id "dataset/digdir/public-docs/altinn-docs/materialization"
                                                             :config.node/enabled? true}
                                                            {:config.node/id "dataset/digdir/private-docs/default"
                                                             :config.node/enabled? false}]
                                                  []))
                  config-db/parse-dataset-node-id (fn [node-id]
                                                    (case node-id
                                                      "dataset/digdir/public-docs/default" {:kind :base :dataset-id "public-docs"}
                                                      "dataset/digdir/public-docs/altinn-docs/materialization" {:kind :materialization :dataset-id "public-docs"}
                                                      "dataset/digdir/private-docs/default" {:kind :base :dataset-id "private-docs"}
                                                      nil))
                  config-db/get-dataset-record (fn [_ dataset-id]
                                                 (case dataset-id
                                                   "public-docs" {:dataset/id "public-docs"
                                                                  :dataset/name "Public Docs"}
                                                   "private-docs" {:dataset/id "private-docs"
                                                                   :dataset/name "Private Docs"}
                                                   nil))]
      (is (= {"digdir:public-docs" "Public Docs"}
             (api-keys/build-dataset-name-index :db ["digdir"]))))))

(deftest test-dataset-label-disambiguates-name-and-id
  (testing "Dataset labels use configured names but keep explicit scope visible"
    (is (= "Public Docs (digdir/public-docs)"
           (api-keys/dataset-label "digdir:public-docs"
                                   {"digdir:public-docs" "Public Docs"})))
    (is (= "digdir/public-docs"
           (api-keys/dataset-label "digdir:public-docs" {})))))

(deftest test-dataset-id->dataset-scope
  (testing "Dataset IDs convert to canonical dataset scopes"
    (is (= {:tenant "digdir"
            :dataset-config-key "public-docs"}
           (api-keys/dataset-id->dataset-scope "digdir:public-docs")))
    (is (nil? (api-keys/dataset-id->dataset-scope "assistant")))))

(deftest test-dataset-scope->dataset-id
  (testing "Canonical dataset scopes convert back to dataset IDs"
    (is (= "digdir:public-docs"
           (api-keys/dataset-scope->dataset-id
            {:tenant "digdir"
             :dataset-config-key "public-docs"})))
    (is (nil? (api-keys/dataset-scope->dataset-id
               {:tenant "digdir"})))))

(deftest test-agent-label
  (testing "Agent labels use configured names with stable IDs"
    (is (= "Agentic RAG Agent (builtin/agent-rag-agent)"
           (api-keys/agent-label "builtin/agent-rag-agent"
                                 {"builtin/agent-rag-agent" "Agentic RAG Agent"})))
    (is (= "builtin/agent-rag-agent"
           (api-keys/agent-label "builtin/agent-rag-agent" {})))))

(deftest test-dataset-ids->tenants
  (testing "Dataset IDs project onto a stable tenant set"
    (is (= ["altinn-docs" "ka"]
           (api-keys/dataset-ids->tenants ["ka:public-docs"
                                           "altinn-docs:public-docs"
                                           "ka:other"])))))

(deftest test-allowed-config-key-option-label
  (testing "Ceiling option labels prefer tenant-config-key and keep root context visible"
    (is (= "ka / runtime / frontpage - Frontpage (runtime/ka/frontpage)"
           (api-keys/allowed-config-key-option-label {:tenant "ka"
                                                      :root :runtime
                                                      :tenant-config-key "frontpage"
                                                      :node-id "runtime/ka/frontpage"
                                                      :label "Frontpage"})))
    (is (= "ka / runtime / default (runtime/ka/default)"
           (api-keys/allowed-config-key-option-label {:tenant "ka"
                                                      :root :runtime
                                                      :tenant-config-key "default"
                                                      :node-id "runtime/ka/default"})))))

(deftest test-default-allowed-config-key-keys
  (testing "Default allowed config keys select the canonical default tenant-config-key for each root in selected tenants"
    (let [options [{:tenant "ka" :root :runtime :tenant-config-key "default"}
                   {:tenant "ka" :root :dataset :tenant-config-key "default"}
                   {:tenant "ka" :root :platform :tenant-config-key "default"}
                   {:tenant "ka" :root :future-root :tenant-config-key "default"}
                   {:tenant "ka" :root :runtime :tenant-config-key "frontpage"}
                   {:tenant "other" :root :runtime :tenant-config-key "default"}]]
      (is (= ["ka|dataset|default"
              "ka|future-root|default"
              "ka|platform|default"
              "ka|runtime|default"]
             (api-keys/default-allowed-config-key-keys options ["ka"]))))))

(deftest test-filter-allowed-config-key-options-by-tenants
  (testing "Allowed config key choices are restricted to the selected tenant set"
    (let [options [{:tenant "digdir" :root :runtime :tenant-config-key "default"}
                   {:tenant "digdir" :root :dataset :tenant-config-key "public-docs"}
                   {:tenant "other" :root :runtime :tenant-config-key "default"}]]
      (is (= [{:tenant "digdir" :root :runtime :tenant-config-key "default"}
              {:tenant "digdir" :root :dataset :tenant-config-key "public-docs"}]
             (api-keys/filter-allowed-config-key-options-by-tenants options ["digdir"])))
      (is (= []
             (api-keys/filter-allowed-config-key-options-by-tenants options []))))))

(deftest test-selected-allowed-config-key-maps
  (testing "Selected allowed config key keys materialize stable tenant/root/node selections"
    (let [options [{:tenant "ka" :root :runtime :tenant-config-key "frontpage" :node-id "runtime/ka/frontpage" :label "Frontpage"}
                   {:tenant "ka" :root :dataset :tenant-config-key "default" :node-id "dataset/ka/default" :label "Default"}]]
      (is (= [{:tenant "ka" :root :dataset :tenant-config-key "default" :node-id "dataset/ka/default"}
              {:tenant "ka" :root :runtime :tenant-config-key "frontpage" :node-id "runtime/ka/frontpage"}]
             (api-keys/selected-allowed-config-key-maps options ["ka|dataset|default"
                                                                 "ka|runtime|frontpage"]))))))

(deftest test-allowed-config-key-tag-label
  (testing "Allowed config key summary labels surface the stored node id when present"
    (is (= "ka/runtime/frontpage (runtime/ka/frontpage)"
           (api-keys/allowed-config-key-tag-label {:tenant "ka"
                                                   :root :runtime
                                                   :tenant-config-key "frontpage"
                                                   :node-id "runtime/ka/frontpage"})))
    (is (= "ka/runtime/default"
           (api-keys/allowed-config-key-tag-label {:tenant "ka"
                                                   :root :runtime
                                                   :tenant-config-key "default"})))))

(defn- with-agent-db
  [f]
  (skills-api/reset-skills!)
  (skills-api/initialize!)
  (let [conn (create-test-db)
        previous-conn (config-db/get-conn)]
    (try
      (config-db/ensure-schema! conn)
      (config-db/set-conn! conn)
      (f conn)
      (finally
        (config-db/set-conn! previous-conn)
        (skills-api/reset-skills!)
        (delete-test-db conn)))))

(def ^:private an-agent
  {:id "t/saved"
   :name "Saved"
   :description "d"
   :instructions "i"
   :default-skill-graph "builtin/agent-rag-graph-bundled"
   :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
   :enabled? true})

(deftest test-save-agent
  (testing "Refuses a caller who does not hold admin-full"
    (with-agent-db
      (fn [_]
        (with-redefs [perms/is-admin? (fn [_ _] false)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Permission denied"
                                (ui-agents/save-agent! "user-1" :create nil an-agent "")))))))

  (testing "Blank skill params store as an empty map"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (= {:ok "t/saved"} (ui-agents/save-agent! "user-1" :create nil an-agent "")))
          (is (= {} (:skill-params (agents-db/get-agent @conn "t/saved"))))))))

  (testing "Unparseable skill params return an error rather than throwing"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (let [res (ui-agents/save-agent! "user-1" :create nil an-agent "{:builtin/retrieval ")]
            (is (contains? res :error)))
          (is (nil? (agents-db/get-agent @conn "t/saved")))))))

  (testing "Skill params that are not a map are refused"
    (with-agent-db
      (fn [_]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (contains? (ui-agents/save-agent! "user-1" :create nil an-agent "[1 2 3]") :error))))))

  (testing "Valid skill params are stored as read"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (ui-agents/save-agent! "user-1" :create nil an-agent "{:builtin/retrieval {:retrieve-top-k 5}}")
          (is (= {:builtin/retrieval {:retrieve-top-k 5}}
                 (:skill-params (agents-db/get-agent @conn "t/saved"))))))))

  (testing "An invalid agent surfaces the validation message"
    (with-agent-db
      (fn [_]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (re-find #"Unknown allowed skill graphs"
                       (:error (ui-agents/save-agent!
                                "user-1" :create nil
                                (assoc an-agent :allowed-skill-graphs ["nope/not-registered"]) ""))))))))

  (testing "Clearing skill params on an existing agent removes them"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (ui-agents/save-agent! "user-1" :create nil an-agent "{:builtin/retrieval {:retrieve-top-k 5}}")
          (ui-agents/save-agent! "user-1" :edit nil an-agent "")
          (is (= {} (:skill-params (agents-db/get-agent @conn "t/saved"))))))))

  (testing "Editing keeps the fields the form does not show"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (agents-db/upsert-agent! conn (assoc an-agent
                                               :guardrails {:citations-required true}
                                               :allowed-dataset-scopes [{:tenant "t" :dataset-config-key "k"}]))
          (is (= {:ok "t/saved"}
                 (ui-agents/save-agent! "user-1" :edit nil (assoc an-agent :name "Renamed") "")))
          (let [saved (agents-db/get-agent @conn "t/saved")]
            (is (= "Renamed" (:name saved)))
            (is (= {:citations-required true} (:guardrails saved)))
            (is (= [{:tenant "t" :dataset-config-key "k"}] (:allowed-dataset-scopes saved))))))))

  (testing "Editing an agent that no longer exists does not recreate it"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (is (contains? (ui-agents/save-agent! "user-1" :edit nil an-agent "") :error))
          (is (nil? (agents-db/get-agent @conn "t/saved")))))))

  (testing "Creating over an existing id is refused"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (ui-agents/save-agent! "user-1" :create nil an-agent "")
          (is (re-find #"already exists"
                       (:error (ui-agents/save-agent! "user-1" :create nil
                                                      (assoc an-agent :name "Other") ""))))
          (is (= "Saved" (:name (agents-db/get-agent @conn "t/saved"))))))))

  (testing "Duplicating copies the source's hidden fields under the new id"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (agents-db/upsert-agent! conn (assoc an-agent
                                               :guardrails {:citations-required true}
                                               :allowed-dataset-scopes [{:tenant "t" :dataset-config-key "k"}]))
          (is (= {:ok "t/copy"}
                 (ui-agents/save-agent! "user-1" :duplicate "t/saved"
                                        (assoc an-agent :id "t/copy" :name "Copy") "")))
          (let [copy (agents-db/get-agent @conn "t/copy")]
            (is (= {:citations-required true} (:guardrails copy)))
            (is (= [{:tenant "t" :dataset-config-key "k"}] (:allowed-dataset-scopes copy))))
          (is (contains? (ui-agents/save-agent! "user-1" :duplicate "t/saved" an-agent "") :error)))))))

(deftest test-merge-skill-param
  (let [merged (fn [value]
                 (edn/read-string (:ok (ui-agents/merge-skill-param "" ":builtin/retrieval" "p" value))))]
    (testing "A value that reads as one EDN form is stored as that form"
      (is (= {:builtin/retrieval {:p 5}} (merged "5"))))

    (testing "A value that is not a single EDN form is stored as the raw string"
      (is (= {:builtin/retrieval {:p "hello world"}} (merged "hello world")))
      (is (= {:builtin/retrieval {:p "hello"}} (merged "hello")))))

  (testing "A blank skill, parameter or value is refused"
    (is (contains? (ui-agents/merge-skill-param "" "" "retrieve-top-k" "5") :error))
    (is (contains? (ui-agents/merge-skill-param "" ":builtin/retrieval" "" "5") :error))
    (is (contains? (ui-agents/merge-skill-param "" ":builtin/retrieval" "retrieve-top-k" " ") :error))))

(deftest test-delete-agent
  (testing "Refuses a caller who does not hold admin-full"
    (with-agent-db
      (fn [_]
        (with-redefs [perms/is-admin? (fn [_ _] false)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Permission denied"
                                (ui-agents/delete-agent! "user-1" "t/saved")))))))

  (testing "Refuses an agent defined in code"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (agents-db/seed-builtin-agents! conn)
          (is (contains? (ui-agents/delete-agent! "user-1" "builtin/agent-rag-agent") :error))
          (is (some? (agents-db/get-agent @conn "builtin/agent-rag-agent")))))))

  (testing "Deletes a custom agent"
    (with-agent-db
      (fn [conn]
        (with-redefs [perms/is-admin? (fn [_ _] true)]
          (ui-agents/save-agent! "user-1" :create nil an-agent "")
          (is (some? (agents-db/get-agent @conn "t/saved")))
          (is (= {:ok "t/saved"} (ui-agents/delete-agent! "user-1" "t/saved")))
          (is (nil? (agents-db/get-agent @conn "t/saved"))))))))
