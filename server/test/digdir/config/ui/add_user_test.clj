(ns digdir.config.ui.add-user-test
  "#573 — creating a user from the Permissions panel, and who is allowed to.

   ⚠️ THE POINT OF THIS FILE IS THE RED CASES, of which there are now two kinds.
   A check that only proves the happy path cannot tell a working feature from
   one that swallows its errors. The first kind is the admin's own expected
   mistake — adding an address that already exists, which the input gives no
   way to see. The second kind is the one that matters more: until the guard
   these tests pin, this panel was reachable by anyone holding ANY permission,
   so a read-only user could grant themselves admin-full."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]
            [digdir.config.ui.permissions :as ui-perms]
            [digdir.data.db :as data-db]))

(def ^:dynamic *conn* nil)

;; The caller every happy-path test acts as. A test that passed an unprivileged
;; actor would be asserting the guard is absent.
(def admin-actor "test-admin-1")

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

(defn- seed-admin!
  "The actor. Holding admin-full is what makes the panel's writes legal."
  [conn]
  (seed-permission! conn "admin-full")
  (d/transact conn {:tx-data [{:user/id admin-actor
                               :user/email "admin@digdir.no"}]})
  (perms/grant-permission! conn admin-actor "admin-full"))

(defn- seed-unprivileged!
  "A user who can log in — `can-login?` needs only one permission — but who is
   not an admin. This is the account the escalation tests act as."
  [conn user-id email]
  (d/transact conn {:tx-data [{:permission/id "reader"
                               :permission/name "Read only"}
                              {:user/id user-id :user/email email}]})
  (perms/grant-permission! conn user-id "reader"))

(defn- users-with-email [db email]
  (count (d/q '[:find ?e :in $ ?email :where [?e :user/email ?email]] db email)))

(defn- user-count [db]
  (count (d/q '[:find ?e :where [?e :user/id] [?e :user/email]] db)))

;; =============================================================================
;; The feature
;; =============================================================================

(deftest test-creates-user-and-grants-so-they-can-log-in
  (testing "a genuinely new address is created AND granted"
    (seed-admin! *conn*)
    (let [result (ui-perms/create-user-and-grant! "newcomer@digdir.no" "admin-full" admin-actor)]
      (is (:ok result) (str "expected success, got " (pr-str result)))
      (is (= "newcomer@digdir.no" (:email result)))
      ;; The end-to-end property the feature exists for. Asserting the grant
      ;; alone would still pass if login refused, which is the state #573
      ;; describes: you can hold a permission and still be told "User not found".
      (is (:allowed? (perms/can-login? @*conn* "newcomer@digdir.no"))
          "user was created and granted but can-login? still refuses"))))

(deftest test-refuses-an-address-that-already-exists
  (testing "a duplicate is refused — not silently duplicated, not a silent no-op"
    (seed-admin! *conn*)
    (is (:ok (ui-perms/create-user-and-grant! "dup@digdir.no" "admin-full" admin-actor)))
    (let [again (ui-perms/create-user-and-grant! "dup@digdir.no" "admin-full" admin-actor)]
      (is (:error again) "second add of the same address was not refused")
      (is (not (:ok again))
          "second add reported success for an address that already existed"))
    (is (= 1 (users-with-email @*conn* "dup@digdir.no"))
        "duplicate add created a second user row")))

(deftest test-blank-email-is-refused
  (testing "blank input is refused rather than creating an empty user"
    (seed-admin! *conn*)
    (let [before (user-count @*conn*)]
      (is (:error (ui-perms/create-user-and-grant! "   " "admin-full" admin-actor)))
      (is (:error (ui-perms/create-user-and-grant! nil "admin-full" admin-actor)))
      (is (= before (user-count @*conn*))
          "a blank address created a user row"))))

(deftest test-existing-grant-path-is-untouched
  (testing "granting to a user who already exists still works"
    (seed-admin! *conn*)
    (d/transact *conn* {:tx-data [{:user/id "existing-1"
                                   :user/email "existing@digdir.no"}]})
    (is (= :ok (ui-perms/grant-permission-to-user! "existing-1" "admin-full" admin-actor)))
    (is (:allowed? (perms/can-login? @*conn* "existing@digdir.no"))
        "the pre-existing grant path stopped working")))

;; =============================================================================
;; Who is allowed to use it
;;
;; ⚠️ These are the tests that would have caught the escalation. Each asserts
;; BOTH that the call was refused AND that the database did not move — a guard
;; that throws after writing is not a guard.
;; =============================================================================

(deftest test-non-admin-cannot-create-a-user
  (testing "a caller without admin-full is refused, and no user is created"
    (seed-admin! *conn*)
    (seed-unprivileged! *conn* "reader-1" "reader@digdir.no")
    (let [before (user-count @*conn*)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ui-perms/create-user-and-grant! "sneak@digdir.no" "admin-full" "reader-1")))
      (is (zero? (users-with-email @*conn* "sneak@digdir.no"))
          "refused the call but created the user anyway")
      (is (= before (user-count @*conn*))
          "refused the call but the user table moved"))))

(deftest test-non-admin-cannot-grant-themselves-admin
  (testing "THE ESCALATION: one permission must not be enough to award admin-full"
    (seed-admin! *conn*)
    (seed-unprivileged! *conn* "reader-1" "reader@digdir.no")
    (is (not (perms/is-admin? @*conn* "reader-1"))
        "precondition: the actor must start out unprivileged")
    (is (thrown? clojure.lang.ExceptionInfo
                 (ui-perms/grant-permission-to-user! "reader-1" "admin-full" "reader-1")))
    (is (not (perms/is-admin? @*conn* "reader-1"))
        "a non-admin granted themselves admin-full")))

(deftest test-non-admin-cannot-revoke
  (testing "a caller without admin-full cannot strip someone else's permission"
    (seed-admin! *conn*)
    (seed-unprivileged! *conn* "reader-1" "reader@digdir.no")
    (is (thrown? clojure.lang.ExceptionInfo
                 (ui-perms/revoke-permission-from-user! admin-actor "admin-full" "reader-1")))
    (is (perms/is-admin? @*conn* admin-actor)
        "a non-admin revoked the admin's own permission")))

(deftest test-unknown-actor-is-refused
  (testing "a nil or unknown actor is refused rather than treated as trusted"
    (seed-admin! *conn*)
    (is (thrown? clojure.lang.ExceptionInfo
                 (ui-perms/create-user-and-grant! "nobody@digdir.no" "admin-full" nil)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ui-perms/create-user-and-grant! "nobody@digdir.no" "admin-full" "no-such-user")))
    (is (zero? (users-with-email @*conn* "nobody@digdir.no"))
        "an unauthenticated caller created a user")))
