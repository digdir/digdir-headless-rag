(ns digdir.config.promotion-atomicity-test
  "a REFUSED promotion must leave nothing behind.

   `promote-to-global!` flipped the path's ownership to :inherit in its OWN
   transaction, and only then called `set-global-value!`. When a later check
   refused the promotion, the flip had already committed, and it survived. So
   one refused attempt left the path :inherit-owned for good, and the outer
   global-write check (\"Global writes require an :inherit-owned definition\")
   passed every later write to it. The check that refused the operation was
   the thing the operation disarmed.

   The refusal used here is the deployment-specific guard in `set-node-value!`:
   a :fork-owned, deployment-specific path gets past the ownership flip and is
   refused at the global write, which is exactly the order that left the flip
   behind.

   THE BULK DOOR TOO. `promote-all-fork-definitions!` calls `promote-to-global!`
   per path and CATCHES each failure into :errors, so a refused path was
   reported as an error while its flip stayed committed. Both doors must STATE
   the refusal and leave the ownership as it was: a parity guard, so neither
   passes by the other's absence."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.ops.ownership :as ownership]
            [digdir.config.schema :as schema]))

(def ^:private path
  "A fork-owned, deployment-specific path: no correct global default, so the
   global write is refused AFTER the ownership flip."
  "services.promotion-test.deployment-secret")

(def ^:private tenant "promotion-t1")
(def ^:private node-id "promotion-t1/platform/default")

(defn- fresh-conn []
  (let [cfg {:store {:backend :mem :id (str "promotion-atomicity-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- release! [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- seed!
  "The path, fork-owned and deployment-specific, and one tenant holding a value
   for it: what a promotion would start from."
  [conn]
  (config-db/upsert-definition! conn {:path path
                                      :root :platform
                                      :value-type :string
                                      :encrypted? false
                                      :description "Test"
                                      :category :general
                                      :service :other
                                      :sensitivity :internal
                                      :function :settings
                                      :ownership :fork
                                      :deployment-specific? true})
  (config-db/register-tenant! conn tenant {:name tenant :created-by "test"})
  (config-db/create-config-node! conn {:root :platform :tenant tenant :node-id node-id
                                       :label node-id :tenant-config-key "default" :enabled? true})
  (config-db/set-node-value! conn {:root :platform :tenant tenant :node-id node-id
                                   :path path :value "tenant-value" :master-key nil}))

(defn- ownership-of [conn] (:config-def/ownership (config-db/get-definition @conn path)))

(defn- tenant-value? [conn]
  (some? (config-db/get-node-value @conn :platform tenant node-id path)))

(defn- global-value? [conn]
  (some? (d/q '[:find ?v . :in $ ?p
                :where [?def :config-def/path ?p] [?v :config.value/definition ?def]
                       [?v :config.value/tenant "__global__"]]
              @conn path)))

(deftest a-refused-promotion-leaves-the-ownership-as-it-was
  (let [conn (fresh-conn)]
    (try
      (seed! conn)
      (let [before-tx (:max-tx @conn)
            refusal (try (ownership/promote-to-global! conn {:path path
                                                             :candidate-value "global-value"
                                                             :changelog "the atomic-promotion fix test"
                                                             :master-key nil})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (testing "PREMISE: the promotion is refused, and by the guard that refuses AFTER the flip"
          (is (some? refusal) "the promotion was not refused, so this test measures nothing")
          (is (= :deployment-specific-has-no-global-default (:reason (ex-data refusal)))
              (str "refused for another reason, before the flip: " (ex-message refusal))))
        (is (= :fork (ownership-of conn))
            "a REFUSED promotion left the ownership flip behind")
        (testing "and nothing else was written either"
          (is (= before-tx (:max-tx @conn)) "a refused promotion committed a transaction")
          (is (tenant-value? conn) "the tenant's value was retracted by a refused promotion")
          (is (not (global-value? conn)) "a refused promotion wrote a global value")))
      (finally (release! conn)))))

(deftest the-bulk-door-states-the-refusal-and-leaves-the-ownership-as-it-was
  (let [conn (fresh-conn)]
    (try
      (seed! conn)
      (let [before-tx (:max-tx @conn)
            result (ownership/promote-all-fork-definitions! conn {:master-key nil})]
        (testing "PREMISE: the bulk door tried this path and STATES that it was refused, by the guard that refuses AFTER the flip"
          ;; Naming the reason is the point: a path in :errors for ANY failure
          ;; (an NPE, say) leaves the ownership as it was too, so without it
          ;; this test is green on a tree whose promotion is broken.
          (let [entry (first (filter #(= path (:path %)) (:errors result)))]
            (is (some? entry) (str "the bulk door did not report the refusal: " (pr-str result)))
            (is (= :deployment-specific-has-no-global-default (:reason entry))
                (str "the bulk door failed for another reason: " (:error entry)))))
        (is (= :fork (ownership-of conn))
            "a REFUSED bulk promotion left the ownership flip behind")
        (is (= before-tx (:max-tx @conn)) "a refused bulk promotion committed a transaction"))
      (finally (release! conn)))))
