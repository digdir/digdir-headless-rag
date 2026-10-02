(ns digdir.config.permission-seed-test
  "`:permission/created-at` must be a CREATION time.

   `init-config-db!` seeds the default permissions on every system import, dump
   restore and first-admin run. `:permission/id` is unique/identity, so seeding
   an id that already exists UPSERTS it, and the seed stamped `created-at` with
   the time of that run. A second seed therefore re-stamped every existing
   default permission.

   The premise is checked before the claim: the second seed must demonstrably
   RUN (it re-creates a default permission removed between the two seeds), and
   the clock must have moved, or an unchanged `created-at` would prove nothing.

   THE FIX IS NOT \"skip existing ids\". The seed exists to make the store
   match the code: a default whose definition changes in code must converge
   onto stores that already hold it, which only the upsert does. So the rest
   of each default is still written, and only `created-at` is left alone once
   set. Convergence is pinned here too, so that skipping goes red.

   THE SAME DEFECT HAD A SECOND DOOR: `create-permission!` stamped
   `created-at` on every call, and upserts on an existing id the same way."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :mem :id (str "permission-seed-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- release! [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- seed! [conn]
  (config-db/init-config-db! conn :seed-agents? false :sync-admins? false))

(def ^:private default-ids (mapv :permission/id schema/default-permissions))

(defn- created-at-by-id [conn]
  (into {} (d/q '[:find ?id ?t :where [?e :permission/id ?id] [?e :permission/created-at ?t]] @conn)))

(defn- wait-past! [t]
  (while (<= (System/currentTimeMillis) t) (Thread/sleep 1)))

(deftest a-second-seed-keeps-each-permissions-creation-time
  (let [conn (fresh-conn)
        removed (peek default-ids)
        kept (pop default-ids)]
    (try
      (seed! conn)
      (let [first-seed (created-at-by-id conn)]
        (testing "PREMISE: the first seed created every default permission, each with a creation time"
          (is (= (set default-ids) (set (keys first-seed)))
              (str "the first seed did not create the defaults: " (pr-str first-seed))))
        (wait-past! (apply max 0 (vals first-seed)))
        (d/transact conn {:tx-data [[:db/retractEntity [:permission/id removed]]]})
        (seed! conn)
        (let [second-seed (created-at-by-id conn)]
          (testing "PREMISE: the second seed ran, and the clock moved past the first seed"
            (is (contains? second-seed removed)
                (str "the second seed did not re-create " removed "; it may not have run"))
            (is (< (apply max 0 (vals first-seed)) (get second-seed removed 0))
                "the clock did not move, so an unchanged created-at would prove nothing"))
          (doseq [id kept]
            (is (= (get first-seed id) (get second-seed id))
                (str "a second seed re-stamped :permission/created-at on the existing permission " id)))))
      (finally (release! conn)))))

(defn- description-of [conn id]
  (d/q '[:find ?d . :in $ ?id :where [?e :permission/id ?id] [?e :permission/description ?d]] @conn id))

(deftest a-second-seed-still-converges-each-default-onto-the-code
  (let [conn (fresh-conn)
        {id :permission/id code-description :permission/description} (first schema/default-permissions)]
    (try
      (seed! conn)
      (d/transact conn {:tx-data [{:permission/id id :permission/description "drifted from the code"}]})
      (testing "PREMISE: the stored default has drifted from the code"
        (is (= "drifted from the code" (description-of conn id)) "the drift did not take; this measures nothing"))
      (seed! conn)
      (is (= code-description (description-of conn id))
          (str "a second seed no longer converges the existing default " id " onto the code"))
      (finally (release! conn)))))

(defn- created-at-of [conn id]
  (get (created-at-by-id conn) id))

(defn- create! [conn id name]
  (perms/create-permission! conn {:id id :name name :description "Test" :attributes {:service :*}
                                  :tenants :* :tenant-config-keys :* :actions #{:read}}))

(deftest create-permission-keeps-an-existing-permissions-creation-time
  (let [conn (fresh-conn)
        id "created-twice"]
    (try
      (seed! conn)
      (create! conn id "first")
      (let [created (created-at-of conn id)]
        (wait-past! created)
        (create! conn id "second")
        (testing "PREMISE: the second call ran and updated the permission"
          (is (= "second" (d/q '[:find ?n . :in $ ?id :where [?e :permission/id ?id] [?e :permission/name ?n]] @conn id))
              "the second create-permission! did not write; this measures nothing"))
        (is (= created (created-at-of conn id))
            "create-permission! re-stamped :permission/created-at on an existing permission"))
      (finally (release! conn)))))
