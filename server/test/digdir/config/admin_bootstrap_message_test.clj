(ns digdir.config.admin-bootstrap-message-test
  "#436 — the boot-time ADMIN_USER_EMAILS path must name the path that works.

   ONE VARIABLE, TWO CONSUMERS, OPPOSITE BEHAVIOUR:

     boot   `permissions/sync-admin-permissions!`     GRANTS to existing users only
     wizard `auth.migration/migrate-admin-users!`     CREATES the user, then grants

   On an empty database the boot path prints `User not found` per address and
   continues. That is accurate and it reads as a dead end — a newcomer sets the
   variable, restarts, sees it, and correctly concludes the variable does not
   work, while the SAME variable would have worked through a path nothing told
   them about.

   An absent feature sends you looking elsewhere. A feature that half-works
   teaches you it is a dead end, which is worse.

   This asserts the not-found branch names the wizard. It deliberately does NOT
   assert that boot creates users — whether it should is an open decision, and a
   test asserting today's behaviour either way would prejudge it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]))

(defn- empty-db
  "A fresh config DB with schema and no users — a newcomer's situation."
  []
  (let [cfg {:store {:backend :mem :id (str "admin-msg-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(deftest not-found-names-the-wizard
  (let [conn (empty-db)]
    (try
      (let [output (with-out-str
                     (with-redefs [perms/get-admin-emails
                                   (fn [] ["nobody@example.com"])]
                       (perms/sync-admin-permissions! conn)))]

        (testing "the honest report is still there"
          (is (str/includes? output "User not found")
              "the accurate statement must not be removed, only completed"))

        (testing "and it now says what to do instead of ending the story"
          (is (str/includes? output "does not create users")
              "the newcomer must learn that THIS path only grants")
          (is (str/includes? output "digdir.setup")
              "and be given the wizard that does create them")
          (is (str/includes? output "bb setup")
              "in both the container and repo-toolchain forms, since the reader may be in either"))

        (testing "the message distinguishes the two consumers rather than just naming a command"
          ;; Without this, a bare `run bb setup` would satisfy the assertions
          ;; above while leaving the reader unable to tell WHY this one failed.
          (is (str/includes? output "only GRANTS")
              "naming the command is not enough; the split is the thing that confuses")))
      (finally
        (let [cfg (:config @(:wrapped-atom conn))]
          (d/release conn)
          (d/delete-database cfg))))))
