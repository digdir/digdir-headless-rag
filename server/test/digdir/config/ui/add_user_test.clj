(ns digdir.config.ui.add-user-test
  "#573 — creating a user from the Permissions panel.

   ⚠️ THE POINT OF THIS FILE IS THE RED CASE. A check that only proves the happy
   path cannot tell a working feature from one that swallows its errors, and the
   expected mistake here is adding an address that already exists — the admin
   cannot see from the input whether that person is already in the dropdown
   immediately above it."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]
            [digdir.config.ui.permissions :as ui-perms]
            [digdir.data.db :as data-db]))

(def ^:dynamic *conn* nil)

(defn with-test-db [f]
  (let [cfg {:store {:backend :mem :id (str "add-user-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      ;; ⚠️ BOTH CONNECTIONS ARE REDEFINED, AND THAT IS NOT BELT-AND-BRACES.
      ;; `auth/create-new-user` reaches the database through `digdir.data.db`
      ;; and the panel's own helpers through `digdir.config.db`. Redefining only
      ;; one would let this test pass while the real wrapper wrote the user to
      ;; one database and the grant to another.
      (with-redefs [config-db/get-conn (constantly conn)
                    data-db/get-conn (constantly conn)]
        (binding [*conn* conn]
          (try (f) (finally (d/release conn))))))))

(use-fixtures :each with-test-db)

(defn- seed-permission! [conn permission-id]
  (d/transact conn {:tx-data [{:permission/id permission-id
                               :permission/name "Full admin"}]}))

(defn- users-with-email [db email]
  (count (d/q '[:find ?e :in $ ?email :where [?e :user/email ?email]] db email)))

(deftest test-creates-user-and-grants-so-they-can-log-in
  (testing "a genuinely new address is created AND granted"
    (seed-permission! *conn* "admin-full")
    (let [result (ui-perms/create-user-and-grant! "newcomer@digdir.no" "admin-full")]
      (is (:ok result) (str "expected success, got " (pr-str result)))
      (is (= "newcomer@digdir.no" (:email result)))
      ;; The end-to-end property the feature exists for. Asserting the grant
      ;; alone would still pass if login refused, which is the state #573
      ;; describes: you can hold a permission and still be told "User not found".
      (is (:allowed? (perms/can-login? @*conn* "newcomer@digdir.no"))
          "user was created and granted but can-login? still refuses"))))

(deftest test-refuses-an-address-that-already-exists
  (testing "a duplicate is refused — not silently duplicated, not a silent no-op"
    (seed-permission! *conn* "admin-full")
    (is (:ok (ui-perms/create-user-and-grant! "dup@digdir.no" "admin-full")))
    (let [again (ui-perms/create-user-and-grant! "dup@digdir.no" "admin-full")]
      (is (:error again) "second add of the same address was not refused")
      (is (not (:ok again))
          "second add reported success for an address that already existed"))
    (is (= 1 (users-with-email @*conn* "dup@digdir.no"))
        "duplicate add created a second user row")))

(deftest test-blank-email-is-refused
  (testing "blank input is refused rather than creating an empty user"
    (seed-permission! *conn* "admin-full")
    (is (:error (ui-perms/create-user-and-grant! "   " "admin-full")))
    (is (:error (ui-perms/create-user-and-grant! nil "admin-full")))
    (is (zero? (count (d/q '[:find ?e :where [?e :user/id] [?e :user/email]] @*conn*)))
        "a blank address created a user row")))

(deftest test-existing-grant-path-is-untouched
  (testing "granting to a user who already exists still works"
    (seed-permission! *conn* "admin-full")
    (d/transact *conn* {:tx-data [{:user/id "existing-1"
                                   :user/email "existing@digdir.no"}]})
    (is (= :ok (ui-perms/grant-permission-to-user! "existing-1" "admin-full")))
    (is (:allowed? (perms/can-login? @*conn* "existing@digdir.no"))
        "the pre-existing grant path stopped working")))
