(ns digdir.config.verify-test
  "#275 - can the runtime see its own service config?

   The stubs return what the real helpers really return: list-config-nodes
   yields nodes carrying :config.node/tenant-config-key and, when a parent
   exists, :config.node/parent {:config.node/id ...}; list-node-values yields
   values whose path lives at [:config.value/definition :config-def/path].
   Both shapes were read off the producers rather than assumed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.verify :as verify]
            [digdir.secrets :as secrets]
            [taoensso.telemere :as t]))

(defn- node
  ([id key] (node id key nil))
  ([id key parent-id]
   (cond-> {:config.node/id id :config.node/tenant-config-key key}
     parent-id (assoc :config.node/parent {:config.node/id parent-id}))))

(defn- value [path]
  {:config.value/definition {:config-def/path path}})

(defn- with-tree [nodes values-by-node f]
  (with-redefs [config-db/list-config-nodes (fn [_ _ _] nodes)
                config-db/list-node-values (fn [_ _ _ node-id]
                                             (mapv value (get values-by-node node-id [])))]
    (f)))

(deftest reports-the-275-shape-values-on-an-unreachable-child
  ;; The deployed shape: bootstrap writes to the leaf and makes it a CHILD of
  ;; the base; the runtime enters at the base. Inheritance runs child -> parent,
  ;; so the values are one step in the direction resolution never travels.
  (testing "every required path is reported, and the node holding it is named"
    (with-tree [(node "platform/digdir/default" "default")
                (node "platform/digdir/shared" "shared" "platform/digdir/default")]
      {"platform/digdir/shared" ["services.typesense.api-host"
                                 "services.typesense.api-tls"
                                 "services.typesense.api-key-admin"
                                 "services.azure-openai.model-name"
                                 "services.azure-openai.use-azure-openai-api"]}
      (fn []
        (let [found (verify/unresolved-service-config :db "digdir")]
          (is (= 5 (count found))
              "all five required paths are unreachable from the entry node")
          (is (= ["platform/digdir/default"] (distinct (map :entry-node-id found)))
              "and the entry node is named, so the reader knows where it looked")
          (is (= [["platform/digdir/shared"]] (distinct (map :defined-on found)))
              "and where the values actually are - a diagnosis, not just a failure"))))))

(deftest silent-when-the-runtime-can-see-them
  (testing "values on the entry node itself resolve"
    (with-tree [(node "platform/digdir/default" "default")]
      {"platform/digdir/default" (mapv #(str/join "." %)
                                       verify/runtime-required-service-paths)}
      (fn []
        (is (= [] (verify/unresolved-service-config :db "digdir"))))))

  (testing "values on an ANCESTOR of the entry node also resolve"
    ;; Inheritance upward is real and works - this pins that the check models
    ;; the actual direction rather than merely demanding same-node values.
    (with-tree [(node "platform/digdir/base" "base")
                (node "platform/digdir/default" "default" "platform/digdir/base")]
      {"platform/digdir/base" (mapv #(str/join "." %)
                                    verify/runtime-required-service-paths)}
      (fn []
        (is (= [] (verify/unresolved-service-config :db "digdir")))))))

(deftest distinguishes-wrong-node-from-genuinely-absent
  ;; These are different problems with different fixes, and a check that
  ;; conflated them would send someone looking for a node that does not exist.
  (testing "a path defined nowhere reports an empty defined-on"
    (with-tree [(node "platform/digdir/default" "default")
                (node "platform/digdir/shared" "shared" "platform/digdir/default")]
      {"platform/digdir/shared" ["services.typesense.api-host"]}
      (fn []
        (let [by-path (into {} (map (juxt :path identity))
                            (verify/unresolved-service-config :db "digdir"))]
          (is (= ["platform/digdir/shared"]
                 (:defined-on (get by-path "services.typesense.api-host")))
              "on the wrong node")
          (is (= [] (:defined-on (get by-path "services.azure-openai.model-name")))
              "genuinely absent"))))))

;; ---------------------------------------------------------------------------
;; Reachability is not usability (#279 masked by #275's fix)
;; ---------------------------------------------------------------------------

(defn- encrypted-value [path]
  {:config.value/definition {:config-def/path path :config-def/encrypted? true}})

(deftest undecryptable-reports-values-that-arrive-but-cannot-be-opened
  ;; After #275 moved the values onto the tenant root they became REACHABLE.
  ;; The five encrypted ones still cannot be read on a fresh install, because
  ;; the master key that sealed them is not the one present. A check that only
  ;; asked "is it reachable" would have gone green and called that fixed.
  (testing "a decryption failure is reported, with the reason the runtime gave"
    (with-redefs [config-db/list-config-nodes
                  (fn [_ _ _] [(node "platform/digdir/default" "default")])
                  config-db/list-node-values
                  (fn [_ _ _ _] [(encrypted-value "services.typesense.api-key-admin")
                                 (value "services.typesense.api-host")])
                  accessor/get-platform-value
                  (fn [_ _] (throw (ex-info "Tag mismatch" {})))]
      (let [found (verify/undecryptable-service-config :db "digdir")]
        (is (= ["services.typesense.api-key-admin"] (mapv :path found))
            "only the ENCRYPTED value is a decryption candidate")
        (is (= ["Tag mismatch"] (mapv :reason found))
            "and the reason is carried through, not swallowed"))))

  (testing "nothing is reported when the values open"
    (with-redefs [config-db/list-config-nodes
                  (fn [_ _ _] [(node "platform/digdir/default" "default")])
                  config-db/list-node-values
                  (fn [_ _ _ _] [(encrypted-value "services.typesense.api-key-admin")])
                  accessor/get-platform-value (fn [_ _] "a-real-key")]
      (is (= [] (verify/undecryptable-service-config :db "digdir")))))

  (testing "an unreachable encrypted value is NOT reported here"
    ;; It belongs to the reachability check. Reporting it twice would make the
    ;; two counts overlap and neither would mean anything on its own.
    (with-redefs [config-db/list-config-nodes
                  (fn [_ _ _] [(node "platform/digdir/default" "default")
                               (node "platform/digdir/shared" "shared" "platform/digdir/default")])
                  config-db/list-node-values
                  (fn [_ _ _ node-id]
                    (if (= node-id "platform/digdir/shared")
                      [(encrypted-value "services.typesense.api-key-admin")]
                      []))
                  accessor/get-platform-value (fn [_ _] (throw (ex-info "Tag mismatch" {})))]
      (is (= [] (verify/undecryptable-service-config :db "digdir"))
          "the leaf is below the entry node, so it is a reachability problem"))))

;; ---------------------------------------------------------------------------
;; A shopping list, not a diagnosis
;;
;; "These cannot be decrypted" is true and unactionable. The finding has to
;; carry the environment variable that supplies it, and the variable name has
;; to come from the same table the bridge writes from - a second list here
;; would drift, and the drift would be invisible in exactly the way the
;; original defect was.
;; ---------------------------------------------------------------------------

(deftest unreachable-findings-name-the-variable-that-supplies-them
  (with-tree [(node "platform/digdir/default" "default")] {}
    (fn []
      (let [by-path (into {} (map (juxt :path identity))
                          (verify/unresolved-service-config :db "digdir"))
            ts (get by-path "services.typesense.api-key-admin")]
        (is (= "TYPESENSE_API_KEY_ADMIN" (:env-var ts)))
        (is (= :config-db (:supplied-by ts))
            "and how it gets there, so a caller can say whether setting it is enough")))))

(deftest undecryptable-findings-name-the-variable-too
  ;; This is the #279 shape: the value arrived, sealed with a key this install
  ;; does not have. Supplying the variable overwrites it, which is the fix the
  ;; reader needs to be told about at the point of failure.
  (with-redefs [config-db/list-config-nodes
                (fn [_ _ _] [(node "platform/digdir/default" "default")])
                config-db/list-node-values
                (fn [_ _ _ _] [(encrypted-value "services.typesense.api-key-admin")])
                accessor/get-platform-value
                (fn [_ _] (throw (ex-info "Tag mismatch" {})))]
    (let [found (first (verify/undecryptable-service-config :db "digdir"))]
      (is (= "TYPESENSE_API_KEY_ADMIN" (:env-var found)))
      (is (= :config-db (:supplied-by found))))))

(deftest the-report-hands-over-a-checklist
  (testing "the logged message tells the reader which variables to set"
    (with-redefs [config-db/list-config-nodes
                  (fn [_ _ _] [(node "platform/digdir/default" "default")])
                  config-db/list-node-values
                  (fn [_ _ _ _] [(encrypted-value "services.typesense.api-key-admin")])
                  accessor/get-platform-value
                  (fn [_ _] (throw (ex-info "Tag mismatch" {})))]
      (let [{:keys [signals]} (t/with-signals
                                (verify/report-unresolved-service-config! :db "digdir"))
            ;; Force the message: an unrealized delay prints as an object and
            ;; every assertion below would pass without reading anything.
            rendered (str/join " " (map #(force (:msg_ %)) signals))]
        (is (str/includes? rendered "TYPESENSE_API_KEY_ADMIN")
            "the variable that supplies it, by name")
        (is (str/includes? rendered "#279")
            "without losing the diagnosis that explains why it is unreadable")
        (is (some #(some #{"TYPESENSE_API_KEY_ADMIN"} (:env-vars (:data %))) signals)
            "and in :data too, so a machine reader is not left parsing prose"))))

  (testing "a path nothing supplies is not given a variable name it does not have"
    ;; Inventing one sends the reader to set something that will never be read.
    (let [rendered (#'verify/checklist "heading" [{:path "services.judge.model"}])]
      (is (str/includes? rendered "no environment variable supplies this"))
      (is (str/includes? rendered "bb config-set services.judge.model"))
      (is (not (str/includes? rendered "_API_"))
          "and no variable name is invented for it"))))

(deftest the-message-does-not-claim-a-node-holds-the-value-when-none-does
  ;; Misplaced and absent need different actions. Since #279 removed the
  ;; shipped secrets, ABSENT is the ordinary first-run case - and the message
  ;; used to assert "values exist on other nodes" for it unconditionally,
  ;; sending the reader to look for a node that does not exist.
  (letfn [(rendered [nodes values]
            (with-tree nodes values
              (fn []
                (let [{:keys [signals]} (t/with-signals
                                          (verify/report-unresolved-service-config! :db "digdir"))]
                  (str/join " " (map #(force (:msg_ %)) signals))))))]

    (testing "absent everywhere says so, and does not invoke the #275 inheritance story"
      (let [msg (rendered [(node "platform/digdir/default" "default")] {})]
        (is (str/includes? msg "not defined anywhere"))
        (is (not (str/includes? msg "Values exist on other nodes"))
            "there is no such node")))

    (testing "genuinely misplaced still gets the #275 explanation"
      (let [msg (rendered [(node "platform/digdir/default" "default")
                           (node "platform/digdir/shared" "shared" "platform/digdir/default")]
                          {"platform/digdir/shared" ["services.typesense.api-host"]})]
        (is (str/includes? msg "Values exist on other nodes"))
        (is (str/includes? msg "#275"))))))

;; ---------------------------------------------------------------------------
;; Two lists, deliberately
;;
;; `runtime-required-service-paths` answers "can the runtime SEE its config".
;; The first-query check answers "what must a NEWCOMER supply". Merging them by
;; proximity is the mistake these tests exist to make expensive.
;; ---------------------------------------------------------------------------

(deftest the-reachability-list-does-not-cover-what-a-first-query-needs
  ;; The live example: services.azure-openai.api-key ships no value since #279
  ;; and is not on the reachability list. A check built only on that list tells
  ;; a newcomer everything resolves, and their first LLM call fails.
  (is (not (contains? (set (map #(str/join "." (map name %))
                                verify/runtime-required-service-paths))
                      "services.azure-openai.api-key"))
      "the reachability list does not ask about the LLM key")
  (is (contains? (set (map :path (env-bridge/first-query-bindings)))
                 "services.azure-openai.api-key")
      "and the supply list does - which is why both exist"))

(deftest unsupplied-first-query-config-asks-the-real-resolver
  ;; These stub the accessor wholesale, which also decides the provider - the
  ;; switch is read through the same door. Each case says which path it is on.
  (testing "an absent value is reported, with the variable that supplies it"
    ;; Everything absent -> the switch falls back to its :default true -> Azure.
    (with-redefs [accessor/get-platform-value (fn [_ opts] (:default opts))]
      (let [found (verify/unsupplied-first-query-config :db "digdir")
            by-path (into {} (map (juxt :path identity)) found)]
        (is (= (set (map :path (env-bridge/first-query-bindings :azure)))
               (set (keys by-path)))
            "everything the AZURE path needs is reported when nothing is set")
        (is (= "AZURE_OPENAI_API_KEY"
               (:env-var (get by-path "services.azure-openai.api-key")))))))

  (testing "nothing is reported when the values resolve"
    (with-redefs [accessor/get-platform-value (fn [_ _] "a-value")]
      (is (= [] (verify/unsupplied-first-query-config :db "digdir")))))

  (testing "a legitimately false boolean is NOT reported as missing"
    ;; use-azure-openai-api is a switch. Testing truthiness rather than a
    ;; sentinel would report a correctly-disabled provider as unconfigured.
    ;; `false` for everything also selects the OpenAI-compatible path, so its
    ;; env-only half is supplied here to isolate what this case is about.
    (with-redefs [accessor/get-platform-value (fn [_ _] false)]
      (binding [secrets/*env-lookup* {"OPENAI_API_ENDPOINT" "set" "OPENAI_API_KEY" "set"}]
        (is (= [] (verify/unsupplied-first-query-config :db "digdir"))))))

  (testing "a blank string counts as unsupplied"
    (with-redefs [accessor/get-platform-value (fn [_ _] "   ")]
      ;; A blank switch is not `false`, so this is still the Azure path.
      (is (= (count (env-bridge/first-query-bindings :azure))
             (count (verify/unsupplied-first-query-config :db "digdir"))))))

  (testing "a value that cannot be decrypted counts as unsupplied, with the reason"
    (with-redefs [accessor/get-platform-value (fn [_ _] (throw (ex-info "Tag mismatch" {})))]
      ;; A throwing switch falls back to Azure, so every reported row here has
      ;; a config path and the reason is carried through for all of them.
      (is (= ["Tag mismatch"]
             (distinct (map :reason (verify/unsupplied-first-query-config :db "digdir"))))))))

(deftest the-report-does-not-count-one-path-in-two-places
  ;; Overlapping counts make each of them meaningless on its own - the same
  ;; reasoning that already keeps unreachable and undecryptable disjoint.
  (with-tree [(node "platform/digdir/default" "default")] {}
    (fn []
      (with-redefs [accessor/get-platform-value (fn [_ opts] (:default opts))]
        (let [{:keys [unreachable unsupplied]}
              (verify/report-unresolved-service-config! :db "digdir")
              unreachable-paths (set (map :path unreachable))]
          (is (seq unreachable-paths))
          (is (seq unsupplied) "the supply gap is still reported")
          (is (empty? (filter unreachable-paths (map :path unsupplied)))
              "but no path appears in both lists")
          (is (contains? (set (map :path unsupplied)) "services.azure-openai.api-key")
              "and the LLM key - invisible to the reachability check - is in it"))))))

;; ---------------------------------------------------------------------------
;; The switch decides which credentials are required
;; ---------------------------------------------------------------------------

(defn- with-config
  "Stub the accessor with a path->value map; anything absent returns :default."
  [m f]
  (with-redefs [accessor/get-platform-value
                (fn [path opts]
                  (get m (str/join "." (map name path)) (:default opts)))]
    (f)))

(deftest the-local-model-path-is-not-asked-for-an-azure-key
  ;; #314 made the OpenAI-compatible path first-class. Demanding
  ;; AZURE_OPENAI_API_KEY from someone who set use-azure-openai-api false is a
  ;; false positive on a supported configuration, and a checklist that cries
  ;; wolf is one people learn to skip.
  (with-config {"services.azure-openai.use-azure-openai-api" false}
    (fn []
      (let [paths (set (map :path (verify/unsupplied-first-query-config :db "digdir")))]
        (is (not (contains? paths "services.azure-openai.api-key"))
            "not required when the switch says this install does not use Azure")
        (is (not (contains? paths "services.azure-openai.deployment-name")))
        (is (contains? paths "services.azure-openai.model-name")
            "the local path reads model-name instead")
        (is (contains? paths "services.typesense.api-key-admin")
            "and Typesense is needed either way")))))

(deftest the-azure-path-is-still-asked-for-its-key
  (with-config {"services.azure-openai.use-azure-openai-api" true}
    (fn []
      (let [paths (set (map :path (verify/unsupplied-first-query-config :db "digdir")))]
        (is (contains? paths "services.azure-openai.api-key"))
        (is (contains? paths "services.azure-openai.deployment-name"))))))

(deftest an-absent-switch-defaults-to-azure
  ;; The shipped snapshot sets it true for both tenants. Defaulting the other
  ;; way would quietly stop asking for the Azure credentials on the installs
  ;; that need them.
  (with-config {}
    (fn []
      (is (contains? (set (map :path (verify/unsupplied-first-query-config :db "digdir")))
                     "services.azure-openai.api-key")))))

(deftest the-env-only-half-of-the-local-path-is-checked-in-the-ENVIRONMENT
  ;; OPENAI_API_ENDPOINT / OPENAI_API_KEY have no config path at all. Looking
  ;; for them in the config DB would report every install as broken.
  (with-config {"services.azure-openai.use-azure-openai-api" false}
    (fn []
      (binding [secrets/*env-lookup* (constantly nil)]
        (let [by (into {} (map (juxt :path identity))
                       (verify/unsupplied-first-query-config :db "digdir"))]
          (is (= "OPENAI_API_KEY" (:env-var (get by "OPENAI_API_KEY")))
              "reported under its variable name, since it has no path")
          (is (contains? by "OPENAI_API_ENDPOINT"))))
      (binding [secrets/*env-lookup* {"OPENAI_API_KEY" "set" "OPENAI_API_ENDPOINT" "set"}]
        (let [paths (set (map :path (verify/unsupplied-first-query-config :db "digdir")))]
          (is (not (contains? paths "OPENAI_API_KEY"))
              "supplied in the environment is supplied")
          (is (not (contains? paths "OPENAI_API_ENDPOINT"))))))))
