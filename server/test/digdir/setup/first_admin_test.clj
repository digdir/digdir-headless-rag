(ns digdir.setup.first-admin-test
  "#436 gate 1 — the claim that the first-admin path actually unlocks login.

   The issue's symptom is `POST /auth -> 302 /not-approved` on a fresh
   database, and the gate producing it is `perms/can-login?`. So the assertion
   worth making is not 'a user row appeared' — it is that **the same predicate
   that rejected before now permits**, on the same database, with the operator
   command as the only thing that changed.

   Asserting the row instead would pass even if the permission grant were
   dropped, which is the half `can-login?` actually turns on: a user with zero
   permissions is still rejected, with a different message."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.auth.migration :as migration]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]))

(defn- empty-db
  "A fresh config DB with schema and no users — a newcomer's situation."
  []
  (let [cfg {:store {:backend :mem :id (str "first-admin-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(deftest empty-database-rejects-login
  (testing "the state the issue reports, reproduced at the gate that causes it"
    (let [conn (empty-db)
          result (perms/can-login? @conn "keel-436@example.com")]
      (is (false? (:allowed? result))
          "a fresh deployment must reject — this is the defect, not the fix")
      (is (nil? (:user result)))
      ;; Pin the message: it is what a newcomer reads, and the issue quotes it.
      (is (= "User not found. Contact an administrator to request access."
             (:reason result))))))

(deftest creating-the-first-admin-unlocks-login
  (testing "after the operator command, the SAME predicate permits"
    (let [conn (empty-db)
          email "keel-436@example.com"]
      ;; Only the environment read is stubbed. `migrate-admin-users!` itself
      ;; runs for real, because it is the thing under test.
      (with-redefs [migration/get-admin-emails-from-env (fn [] #{email})]
        (with-out-str (migration/migrate-admin-users! conn)))

      (let [result (perms/can-login? @conn email)]
        (is (true? (:allowed? result))
            "gate 1 must now be open for the address that was named")
        (is (some? (:user result)))
        (is (= email (:user/email (:user result)))))))

  (testing "and does NOT open it for anyone else"
    ;; The red line: this must create exactly who was named. A path that
    ;; admits the first caller, or any address, would be a worse defect than
    ;; the lockout it replaces.
    (let [conn (empty-db)]
      (with-redefs [migration/get-admin-emails-from-env
                    (fn [] #{"named@example.com"})]
        (with-out-str (migration/migrate-admin-users! conn)))

      (let [other (perms/can-login? @conn "someone-else@example.com")]
        (is (false? (:allowed? other))
            "an address that was not named must still be rejected")))))

(deftest idempotent
  (testing "running it twice is safe — an operator will"
    (let [conn (empty-db)
          email "keel-436@example.com"]
      (with-redefs [migration/get-admin-emails-from-env (fn [] #{email})]
        (with-out-str (migration/migrate-admin-users! conn))
        (let [second-run (with-out-str (migration/migrate-admin-users! conn))]
          (is (re-find #"SKIPPED|Already has admin-full" second-run)
              "the second run should report the user as already admin")))
      (is (true? (:allowed? (perms/can-login? @conn email)))
          "and login must still work afterwards"))))
