(ns digdir.config.global-test
  "Tests for the :global / inherit-with-overrides config layer."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]
            [digdir.config.ops.global :as global-ops]
            [digdir.config.ops.ownership :as ownership]
            [digdir.config.schema :as schema]))

(defn- create-test-db
  []
  (let [cfg {:store {:backend :mem :id (str "global-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- seed-definition!
  [conn {:keys [path root value-type ownership]
         :or {value-type :string}}]
  (config-db/upsert-definition! conn
                                (cond-> {:path path
                                         :root root
                                         :value-type value-type
                                         :encrypted? false
                                         :description "Test"
                                         :category :general
                                         :service :other
                                         :sensitivity :internal
                                         :function :settings}
                                  ownership (assoc :ownership ownership))))

(defn- seed-tenant-node!
  [conn {:keys [root tenant node-id tenant-config-key parent-id enabled?]
         :or {enabled? true}}]
  (config-db/register-tenant! conn tenant {:name tenant :created-by "test"})
  (config-db/create-config-node! conn
                                 (cond-> {:root root
                                          :tenant tenant
                                          :node-id node-id
                                          :label node-id
                                          :tenant-config-key tenant-config-key
                                          :enabled? enabled?}
                                   parent-id (assoc :parent-id parent-id))))

(defn- set-global!
  [conn path value & {:keys [changelog]
                      :or {changelog "test change"}}]
  (global-ops/set-global-value! conn
                                {:path path
                                 :value value
                                 :changelog changelog
                                 :master-key nil}))

(defn- with-def-ownership
  "Transact a config-def ownership flip. Upsert-definition! doesn't accept the
   ownership key via opts for every caller, so use it directly when needed."
  [conn path ownership]
  (d/transact conn
              {:tx-data [{:db/id [:config-def/path path]
                          :config-def/ownership ownership}]}))

;; ---------------------------------------------------------------------------
;; Core resolver behavior
;; ---------------------------------------------------------------------------

(deftest inherit-definition-resolves-from-global-on-tenant-miss
  (let [conn (create-test-db)]
    (try
      ;; Inherit-owned def, global baseline, tenant has a tree but no value set.
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform
                              :ownership :inherit})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (set-global! conn "services.scaleway-tem.region" "fr-par")
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.scaleway-tem.region")]
        (is (some? value) "value resolves from global")
        (is (= :matched-global (:stop-reason trace)))
        (is (= :global (:winning-layer trace)))
        (is (some? (:global-fallback trace))))
      (finally (delete-test-db conn)))))

(deftest fork-definition-does-not-fall-through-to-global
  (let [conn (create-test-db)]
    (try
      ;; Even with a global value set, fork-owned defs ignore it.
      (seed-definition! conn {:path "services.other.fork-only" :root :platform})
      ;; Force global to have a value via direct node write (bypass ownership check).
      (seed-definition! conn {:path "services.other.inherit-prime"
                              :root :platform :ownership :inherit})
      (set-global! conn "services.other.inherit-prime" "baseline")
      (with-def-ownership conn "services.other.inherit-prime" :fork) ;; flip after seed
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.other.inherit-prime")]
        (is (nil? value) "fork-owned def returns nil even if a global value is present")
        (is (not= :matched-global (:stop-reason trace)))
        (is (nil? (:winning-layer trace)))
        (is (nil? (:global-fallback trace))))
      (finally (delete-test-db conn)))))

(deftest tenant-override-masks-global
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (set-global! conn "services.scaleway-tem.region" "fr-par")
      (config-db/set-node-value! conn
                                 {:root :platform :tenant "ka"
                                  :node-id "ka/platform/default"
                                  :path "services.scaleway-tem.region"
                                  :value "nl-ams" :master-key nil})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.scaleway-tem.region")]
        (is (= "nl-ams" (:config.value/raw value)))
        (is (= :matched (:stop-reason trace)))
        (is (= :tenant (:winning-layer trace))))
      (finally (delete-test-db conn)))))

(deftest disabled-tenant-node-still-falls-through-to-global
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "skills.synthesis.model"
                              :root :runtime :ownership :inherit})
      (set-global! conn "skills.synthesis.model" "gpt-5")
      ;; A two-node tenant chain where the parent is disabled.
      (seed-tenant-node! conn {:root :runtime :tenant "ka"
                               :node-id "ka/runtime/base"
                               :tenant-config-key "default"})
      (seed-tenant-node! conn {:root :runtime :tenant "ka"
                               :node-id "ka/runtime/leaf"
                               :tenant-config-key "leaf"
                               :parent-id "ka/runtime/base"})
      (config-db/update-config-node! conn {:node-id "ka/runtime/base"
                                           :enabled? false})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :runtime "ka" "ka/runtime/leaf" "skills.synthesis.model")]
        (is (= "gpt-5" (:config.value/raw value)))
        (is (= :matched-global (:stop-reason trace)))
        (is (= :global (:winning-layer trace)))
        ;; Tenant stopped at the disabled node — captured on top-level :stopped-at.
        (is (= "ka/runtime/base" (:stopped-at trace)))
        ;; The global-fallback subtrace describes the separate walk over the
        ;; __global__ chain; since the global value is set, that walk matched.
        (is (= :matched (-> trace :global-fallback :stop-reason))))
      (finally (delete-test-db conn)))))

(deftest tenant-chain-walks-fully-before-global-fallback
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (set-global! conn "services.scaleway-tem.region" "fr-par")
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/base"
                               :tenant-config-key "default"})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/leaf"
                               :tenant-config-key "leaf"
                               :parent-id "ka/platform/base"})
      ;; Put the tenant value on the PARENT — resolution must find it on the
      ;; tenant chain, never consulting global.
      (config-db/set-node-value! conn
                                 {:root :platform :tenant "ka"
                                  :node-id "ka/platform/base"
                                  :path "services.scaleway-tem.region"
                                  :value "ams"
                                  :master-key nil})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/leaf" "services.scaleway-tem.region")]
        (is (= "ams" (:config.value/raw value)))
        (is (= :tenant (:winning-layer trace)))
        (is (= "ka/platform/base" (:winning-node trace)))
        (is (nil? (:global-fallback trace)) "global fallback should not fire when tenant chain hits"))
      (finally (delete-test-db conn)))))

(deftest inherit-with-no-global-tree-returns-nil
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.scaleway-tem.region")]
        (is (nil? value))
        (is (nil? (:winning-layer trace)))
        (is (= :no-global-tree (-> trace :global-fallback :stop-reason))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; Batch resolver
;; ---------------------------------------------------------------------------

(deftest batch-resolver-mixes-fork-and-inherit
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.fork.only" :root :platform})
      (seed-definition! conn {:path "services.inherit.region"
                              :root :platform :ownership :inherit})
      (set-global! conn "services.inherit.region" "fr-par")
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (let [{:keys [results]}
            (config-db/resolve-node-values-batch
             @conn :platform "ka" "ka/platform/default"
             ["services.fork.only" "services.inherit.region"])]
        (is (nil? (get-in results ["services.fork.only" :value]))
            "fork miss returns nil")
        (is (nil? (-> results (get "services.fork.only") :trace :winning-layer))
            "fork miss has no winning layer")
        (is (= "fr-par" (-> results (get "services.inherit.region") :value :config.value/raw))
            "inherit miss falls through to global")
        (is (= :global (-> results (get "services.inherit.region") :trace :winning-layer))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; Write API: set-global-value!, pin-tenant-value!, unpin-tenant-value!
;; ---------------------------------------------------------------------------

(deftest set-global-value-rejects-fork-definitions
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.fork.only" :root :platform})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Global writes require an :inherit-owned definition"
                            (global-ops/set-global-value!
                             conn {:path "services.fork.only"
                                   :value "x"
                                   :changelog "nope"
                                   :master-key nil})))
      (finally (delete-test-db conn)))))

(deftest set-global-value-bumps-version
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (is (= 0 (global-ops/current-global-version @conn)))
      (let [{:keys [version]} (set-global! conn "services.scaleway-tem.region" "fr-par"
                                           :changelog "initial")]
        (is (= 1 version)))
      (let [{:keys [version]} (set-global! conn "services.scaleway-tem.region" "nl-ams"
                                           :changelog "switch")]
        (is (= 2 version)))
      (is (= 2 (global-ops/current-global-version @conn)))
      (finally (delete-test-db conn)))))

(deftest pin-tenant-value-copies-global-and-stamps-version
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (set-global! conn "services.scaleway-tem.region" "fr-par"
                   :changelog "v1")
      (let [{:keys [pin-of-version]}
            (global-ops/pin-tenant-value! conn {:tenant "ka"
                                                :node-id "ka/platform/default"
                                                :path "services.scaleway-tem.region"
                                                :master-key nil})]
        (is (= 1 pin-of-version)))
      ;; Subsequent global change should NOT affect the pinned tenant.
      (set-global! conn "services.scaleway-tem.region" "nl-ams"
                   :changelog "v2")
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.scaleway-tem.region")]
        (is (= "fr-par" (:config.value/raw value))
            "pinned tenant still reads the originally-pinned value")
        (is (= :tenant (:winning-layer trace))))
      ;; Verify the pin-of-version stamp landed on the tenant value entity.
      (let [value-id (config-db/make-node-value-id
                      :platform "ka" "ka/platform/default" "services.scaleway-tem.region")
            pin-version (d/q '[:find ?v .
                               :in $ ?id
                               :where
                               [?e :config.value/id ?id]
                               [?e :config.value/pin-of-version ?v]]
                             @conn value-id)]
        (is (= 1 pin-version)))
      (finally (delete-test-db conn)))))

(deftest unpin-reverts-tenant-to-live-global
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (seed-tenant-node! conn {:root :platform :tenant "ka"
                               :node-id "ka/platform/default"
                               :tenant-config-key "default"})
      (set-global! conn "services.scaleway-tem.region" "fr-par"
                   :changelog "v1")
      (global-ops/pin-tenant-value! conn {:tenant "ka"
                                          :node-id "ka/platform/default"
                                          :path "services.scaleway-tem.region"
                                          :master-key nil})
      (set-global! conn "services.scaleway-tem.region" "nl-ams"
                   :changelog "v2")
      ;; Now unpin — tenant should read the live global.
      (global-ops/unpin-tenant-value! conn {:tenant "ka"
                                            :node-id "ka/platform/default"
                                            :path "services.scaleway-tem.region"})
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "ka" "ka/platform/default" "services.scaleway-tem.region")]
        (is (= "nl-ams" (:config.value/raw value)))
        (is (= :global (:winning-layer trace))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; Direct global-only resolver (used by accessor when tenant has no tree)
;; ---------------------------------------------------------------------------

(deftest resolve-global-value-directly
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.scaleway-tem.region"
                              :root :platform :ownership :inherit})
      (set-global! conn "services.scaleway-tem.region" "fr-par"
                   :changelog "v1")
      (let [{:keys [value trace]}
            (config-db/resolve-global-value-with-trace
             @conn :platform "services.scaleway-tem.region")]
        (is (= "fr-par" (:config.value/raw value)))
        (is (= :global (:winning-layer trace)))
        (is (= core/global-tenant (:selected-tenant trace))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; Ownership ops: inspect / promote / demote / suggested-for-promotion
;; (Phase 3 of global-config-root-plan.md)
;; ---------------------------------------------------------------------------

(defn- seed-fork-tenants-with-values!
  "For each (tenant → value) in `values`, ensure the tenant has a default
   platform node and seed the given value at `path`. Idempotent on the node —
   safe to call across multiple paths that share tenants."
  [conn path values]
  (seed-definition! conn {:path path :root :platform :value-type :string})
  (doseq [[tenant v] values
          :let [node-id (str tenant "/platform/default")]]
    (when-not (config-db/get-config-node @conn node-id)
      (seed-tenant-node! conn {:root :platform :tenant tenant
                               :node-id node-id
                               :tenant-config-key "default"}))
    (when v
      (config-db/set-node-value! conn
                                 {:root :platform :tenant tenant
                                  :node-id node-id
                                  :path path :value v :master-key nil})))
  path)

(deftest inspect-definition-values-clusters-tenants-by-value
  (let [conn (create-test-db)]
    (try
      (seed-fork-tenants-with-values!
       conn "services.demo.flavor"
       {"ka" "vanilla" "altinn" "vanilla" "digdir" "chocolate"})
      (let [{:keys [clusters candidate-value candidate-cluster-size
                    with-value-count without-value-count]}
            (ownership/inspect-definition-values
             @conn {:path "services.demo.flavor" :master-key nil})]
        (is (= 3 with-value-count))
        (is (zero? without-value-count))
        (is (= 2 (count clusters)))
        (is (= "vanilla" candidate-value))
        (is (= 2 candidate-cluster-size))
        (let [by-value (into {} (map (juxt :value :count) clusters))]
          (is (= {"vanilla" 2 "chocolate" 1} by-value))))
      (finally (delete-test-db conn)))))

(deftest promote-to-global-with-pin-all-preserves-effective-values
  (let [conn (create-test-db)]
    (try
      (seed-fork-tenants-with-values!
       conn "services.demo.region"
       {"ka" "nl-ams" "altinn" "fr-par" "digdir" "fr-par"})
      (let [{:keys [version pinned inherited strategy]}
            (ownership/promote-to-global!
             conn {:path "services.demo.region"
                   :candidate-value "fr-par"
                   :changelog "promote region"
                   :master-key nil})]
        (is (= :pin-all strategy))
        (is (pos? version))
        (is (= #{"ka"} (set pinned)) "non-matching tenant is pinned")
        (is (= #{"altinn" "digdir"} (set inherited)) "matching tenants inherit"))
      ;; Effective values must be preserved across the promotion.
      (doseq [[tenant expected] {"ka" "nl-ams" "altinn" "fr-par" "digdir" "fr-par"}]
        (let [node-id (str tenant "/platform/default")
              {:keys [value]} (config-db/resolve-node-value-with-trace
                                @conn :platform tenant node-id
                                "services.demo.region")]
          (is (= expected (:config.value/raw value))
              (str tenant " keeps effective value after promotion"))))
      ;; Ownership flip confirmed.
      (is (= :inherit (:config-def/ownership
                        (config-db/get-definition @conn "services.demo.region"))))
      ;; Pinned tenant value carries :pin-of-version stamp.
      (let [vid (config-db/make-node-value-id :platform "ka" "ka/platform/default"
                                              "services.demo.region")
            stamped (d/q '[:find ?v .
                           :in $ ?id
                           :where [?e :config.value/id ?id]
                                  [?e :config.value/pin-of-version ?v]]
                         @conn vid)]
        (is (some? stamped) "pinned value has :pin-of-version stamp"))
      (finally (delete-test-db conn)))))

(deftest promote-to-global-with-revert-all-discards-tenant-overrides
  (let [conn (create-test-db)]
    (try
      (seed-fork-tenants-with-values!
       conn "services.demo.zone"
       {"ka" "zone-a" "altinn" "zone-b" "digdir" "zone-c"})
      (let [{:keys [reverted pinned strategy]}
            (ownership/promote-to-global!
             conn {:path "services.demo.zone"
                   :candidate-value "zone-a"
                   :non-matching-strategy :revert-all
                   :changelog "force zone-a"
                   :master-key nil})]
        (is (= :revert-all strategy))
        (is (empty? pinned))
        (is (= #{"altinn" "digdir"} (set reverted))))
      ;; After revert-all, altinn and digdir should resolve to the new global.
      (doseq [tenant ["altinn" "digdir" "ka"]]
        (let [node-id (str tenant "/platform/default")
              {:keys [value trace]}
              (config-db/resolve-node-value-with-trace
               @conn :platform tenant node-id "services.demo.zone")]
          (is (= "zone-a" (:config.value/raw value))
              (str tenant " now resolves to the new global value"))
          (when (not= tenant "ka")
            (is (= :global (:winning-layer trace))
                (str tenant " is inheriting")))))
      (finally (delete-test-db conn)))))

(deftest demote-from-global-pushes-global-value-back-into-tenants
  (let [conn (create-test-db)]
    (try
      (seed-fork-tenants-with-values!
       conn "services.demo.depot"
       {"ka" "north" "altinn" "south" "digdir" "south"})
      (ownership/promote-to-global!
       conn {:path "services.demo.depot"
             :candidate-value "south"
             :changelog "promote depot"
             :master-key nil})
      ;; After promote: altinn + digdir inherit "south", ka pinned at "north".
      (ownership/demote-from-global!
       conn {:path "services.demo.depot" :master-key nil})
      ;; Definition is back to fork.
      (is (not= :inherit (:config-def/ownership
                           (config-db/get-definition @conn "services.demo.depot"))))
      ;; All three tenants have direct values now.
      (doseq [[tenant expected] {"ka" "north" "altinn" "south" "digdir" "south"}]
        (let [node-id (str tenant "/platform/default")
              {:keys [value]} (config-db/resolve-node-value-with-trace
                                @conn :platform tenant node-id
                                "services.demo.depot")]
          (is (= expected (:config.value/raw value))
              (str tenant " keeps its effective value after demote"))))
      ;; Global tenant no longer has the value.
      (let [g-node-id "__global__/platform/default"
            v (config-db/get-node-value @conn :platform core/global-tenant
                                         g-node-id "services.demo.depot")]
        (is (nil? v) "global value retracted on demote"))
      ;; Pin stamp is gone (ka was pinned, now tenant-owned).
      (let [vid (config-db/make-node-value-id :platform "ka" "ka/platform/default"
                                              "services.demo.depot")
            stamped (d/q '[:find ?v .
                           :in $ ?id
                           :where [?e :config.value/id ?id]
                                  [?e :config.value/pin-of-version ?v]]
                         @conn vid)]
        (is (nil? stamped) "pin-of-version stamp retracted on demote"))
      (finally (delete-test-db conn)))))

(deftest pin-all-globals-for-tenant-snapshots-inherits
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "services.demo.model"
                              :root :platform :ownership :inherit
                              :value-type :string})
      (seed-definition! conn {:path "services.demo.region"
                              :root :platform :ownership :inherit
                              :value-type :string})
      (set-global! conn "services.demo.model" "gpt-7" :changelog "v1")
      (set-global! conn "services.demo.region" "fr-par" :changelog "v2")
      (seed-tenant-node! conn {:root :platform :tenant "newcomer"
                               :node-id "newcomer/platform/default"
                               :tenant-config-key "default"})
      (let [{:keys [pinned-paths]}
            (ownership/pin-all-globals-for-tenant!
             conn {:tenant "newcomer" :root :platform :master-key nil})]
        (is (= #{"services.demo.model" "services.demo.region"} (set pinned-paths))))
      ;; Later global edit must NOT affect the pinned tenant.
      (set-global! conn "services.demo.model" "gpt-8" :changelog "v3")
      (let [{:keys [value trace]}
            (config-db/resolve-node-value-with-trace
             @conn :platform "newcomer" "newcomer/platform/default"
             "services.demo.model")]
        (is (= "gpt-7" (:config.value/raw value))
            "newcomer tenant keeps its pinned value despite later global edit")
        (is (= :tenant (:winning-layer trace))))
      (finally (delete-test-db conn)))))

(deftest suggested-for-promotion-ranks-by-consensus
  (let [conn (create-test-db)]
    (try
      ;; 4/5 tenants share "v1", 1 differs — high consensus.
      (seed-fork-tenants-with-values!
       conn "services.demo.high"
       {"a" "v1" "b" "v1" "c" "v1" "d" "v1" "e" "v2"})
      ;; 3/5 tenants share "x" (60%) — below the 80% default.
      (seed-fork-tenants-with-values!
       conn "services.demo.low"
       {"a" "x" "b" "x" "c" "x" "d" "y" "e" "z"})
      (let [suggestions (ownership/suggested-for-promotion
                         @conn {:master-key nil})]
        (is (= ["services.demo.high"] (mapv :path suggestions))
            "only high-consensus paths are suggested at the default threshold")
        (is (= "v1" (-> suggestions first :candidate-value)))
        (is (= 4/5 (-> suggestions first :consensus-ratio double
                        rationalize))))
      (finally (delete-test-db conn)))))

;; ---------------------------------------------------------------------------
;; Rerank-mode split migration (post-definition migration)
;; ---------------------------------------------------------------------------

(deftest rerank-mode-split-migration-retracts-old-and-seeds-new-globals
  (let [conn (create-test-db)]
    (try
      ;; Pre-state: seed the 5 old fork defs and the 10 new inherit defs as
      ;; the boot path would do post-`ensure-skill-config-definitions!`.
      (let [old-defs ["skills.rerank.max-chunk-length"
                      "skills.rerank.max-total-length"
                      "skills.rerank.max-context-length"
                      "skills.rerank.context.top-k"
                      "skills.rerank.context.max-chunk-length"]
            new-defs ["skills.rerank.rag.max-chunk-length"
                      "skills.rerank.rag.max-total-length"
                      "skills.rerank.rag.max-context-length"
                      "skills.rerank.rag.context.top-k"
                      "skills.rerank.rag.context.max-chunk-length"
                      "skills.rerank.retrieval.max-chunk-length"
                      "skills.rerank.retrieval.max-total-length"
                      "skills.rerank.retrieval.max-context-length"
                      "skills.rerank.retrieval.context.top-k"
                      "skills.rerank.retrieval.context.max-chunk-length"]]
        (doseq [p old-defs]
          (seed-definition! conn {:path p :root :runtime :value-type :number}))
        (doseq [p new-defs]
          (seed-definition! conn {:path p :root :runtime :value-type :number
                                  :ownership :inherit})))
      ;; Run the migration via the private fn.
      (let [run-migration! (resolve 'digdir.config.db/rerank-mode-split-migration!)
            {:keys [definitions-retracted globals-written]} (run-migration! conn)]
        (is (= 5 definitions-retracted))
        (is (= 10 globals-written)))
      ;; The 5 old defs should be gone.
      (doseq [p ["skills.rerank.max-chunk-length"
                 "skills.rerank.max-total-length"
                 "skills.rerank.max-context-length"
                 "skills.rerank.context.top-k"
                 "skills.rerank.context.max-chunk-length"]]
        (is (nil? (config-db/get-definition @conn p))
            (str p " should have been retracted")))
      ;; The 10 new defs should each have a global value matching the spec.
      (let [expectations {"skills.rerank.rag.max-chunk-length" 4000 ; #463: raised from 400
                          "skills.rerank.rag.max-total-length" 16000
                          "skills.rerank.rag.max-context-length" 8000
                          "skills.rerank.rag.context.top-k" 10
                          "skills.rerank.rag.context.max-chunk-length" 2000
                          "skills.rerank.retrieval.max-chunk-length" 4000
                          "skills.rerank.retrieval.max-total-length" 32000
                          "skills.rerank.retrieval.max-context-length" 32000
                          "skills.rerank.retrieval.context.top-k" 10
                          "skills.rerank.retrieval.context.max-chunk-length" 4000}]
        (doseq [[p expected] expectations]
          (let [{:keys [value]}
                (config-db/resolve-global-value-with-trace @conn :runtime p)]
            (is (= (str expected) (:config.value/raw value))
                (str p " global value should be " expected)))))
      (finally (delete-test-db conn)))))

(deftest rerank-mode-split-migration-refuses-when-tenant-values-exist
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn {:path "skills.rerank.max-chunk-length"
                              :root :runtime :value-type :number})
      ;; Stamp a tenant value at one of the to-be-retracted paths.
      (seed-tenant-node! conn {:root :runtime :tenant "ka"
                               :node-id "ka/runtime/default"
                               :tenant-config-key "default"})
      (config-db/set-node-value! conn
                                 {:root :runtime :tenant "ka"
                                  :node-id "ka/runtime/default"
                                  :path "skills.rerank.max-chunk-length"
                                  :value 999 :master-key nil})
      (let [run-migration! (resolve 'digdir.config.db/rerank-mode-split-migration!)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Rerank-mode split refused"
             (run-migration! conn))
            "migration must refuse to run when tenant values exist on old paths"))
      (finally (delete-test-db conn)))))

