(ns digdir.auth.migration
  "Migration utilities for transitioning from legacy auth to permissions-based auth.

   This namespace provides functions to:
   1. Grant admin-full permission to users in ADMIN_USER_EMAILS
   2. Grant can-login permission to users from whitelisted domains
   3. Clean up legacy allowed-domain entities"
  (:require [datahike.api :as d]
            [clojure.string :as str]
            [nano-id.core :refer [nano-id]]
            [digdir.data.db :as db]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]))

(defn get-admin-emails-from-env
  "Get admin emails from ADMIN_USER_EMAILS environment variable."
  []
  (let [admins-str (or (System/getenv "ADMIN_USER_EMAILS") "")]
    (when (seq admins-str)
      (set (map str/trim (str/split admins-str #" "))))))

(defn get-allowed-domains-from-db
  "Get allowed domains from legacy database entities."
  [db]
  (d/q '[:find [?domain ...]
         :where [?e :allowed-domain/domain ?domain]]
       db))

(defn get-allowed-domains-from-config
  "Get allowed domains from AUTH_APPROVED_DOMAINS env var."
  []
  (config-core/auth-approved-domains))

(defn get-all-users
  "Get all users from the database."
  [db]
  (d/q '[:find [(pull ?e [:user/id :user/email]) ...]
         :where [?e :user/id]]
       db))

(defn user-domain-matches?
  "Check if a user's email domain matches any of the allowed domains."
  [email allowed-domains]
  (let [[_local domain] (str/split email #"@")]
    (or (contains? allowed-domains (str "@" domain))
        (contains? allowed-domains domain))))

(defn ensure-permissions-exist!
  "Ensure default permissions are seeded in the database."
  [conn]
  (println "Ensuring default permissions exist...")
  (config-db/init-config-db! conn :sync-admins? false)
  (println "Default permissions verified."))

(defn create-user!
  "Create a new user in the database."
  [conn email created-by]
  (let [user-id (nano-id)]
    (d/transact conn [{:user/id user-id
                       :user/email email
                       :user/preferred-language "en"
                       :user/created (str (java.time.Instant/now))
                       :user/created-by created-by}])
    user-id))

(defn migrate-admin-users!
  "Create users and grant admin-full permission to all emails in ADMIN_USER_EMAILS.

   If a user doesn't exist, they will be created first.

   Returns: {:created [...] :granted [...] :skipped [...]}"
  [conn]
  ;; Ensure default permissions exist before trying to grant them
  (ensure-permissions-exist! conn)

  (let [admin-emails (get-admin-emails-from-env)
        results (atom {:created [] :granted [] :skipped []})]

    (println "=== Migrating Admin Users ===")
    (println "Found" (count admin-emails) "admin emails in ADMIN_USER_EMAILS")

    (doseq [email admin-emails]
      (let [db @conn
            user (perms/get-user-by-email db email)]
        (cond
          (nil? user)
          ;; User doesn't exist - create them and grant admin-full
          (let [user-id (create-user! conn email "system-migration")]
            (println "  [CREATED] Created user:" email)
            (swap! results update :created conj email)
            (perms/grant-permission! conn user-id "admin-full")
            (println "  [GRANTED] Granting admin-full to:" email)
            (swap! results update :granted conj email))

          ;; Check if already has admin-full
          (some #(= "admin-full" (:permission/id %))
                (perms/get-user-permissions db (:user/id user)))
          (do
            (println "  [SKIPPED] Already has admin-full:" email)
            (swap! results update :skipped conj email))

          :else
          (do
            (println "  [GRANTED] Granting admin-full to:" email)
            (perms/grant-permission! conn (:user/id user) "admin-full")
            (swap! results update :granted conj email)))))

    (println "Admin migration complete:"
             (count (:created @results)) "created,"
             (count (:granted @results)) "granted,"
             (count (:skipped @results)) "skipped")

    @results))

(defn migrate-domain-users!
  "Grant can-login permission to all users from whitelisted domains.

   This ensures existing users who could previously log in via domain whitelist
   can still log in with the new permissions-based system.

   Returns: {:granted [...] :skipped [...]}"
  [conn]
  (let [db @conn
        ;; Get domains from both sources
        db-domains (get-allowed-domains-from-db db)
        config-domains (get-allowed-domains-from-config)
        all-domains (into #{} (concat db-domains config-domains))
        all-users (get-all-users db)
        results (atom {:granted [] :skipped []})]

    (println "\n=== Migrating Domain Users ===")
    (println "Found" (count all-domains) "allowed domains")
    (println "Found" (count all-users) "total users")

    (doseq [{:user/keys [id email]} all-users]
      (when (user-domain-matches? email all-domains)
        (let [current-perms (perms/get-user-permissions @conn id)]
          (if (seq current-perms)
            ;; User already has permissions (possibly admin-full from previous step)
            (do
              (println "  [SKIPPED] Already has permissions:" email)
              (swap! results update :skipped conj email))
            ;; Grant can-login
            (do
              (println "  [GRANTED] Granting can-login to:" email)
              (perms/grant-permission! conn id "can-login")
              (swap! results update :granted conj email))))))

    (println "Domain migration complete:"
             (count (:granted @results)) "granted,"
             (count (:skipped @results)) "skipped")

    @results))

(defn cleanup-legacy-domains!
  "Remove legacy allowed-domain entities from the database.

   Only run this after confirming all users have been migrated.

   Returns: {:removed count}"
  [conn]
  (let [db @conn
        domain-eids (d/q '[:find [?e ...]
                           :where [?e :allowed-domain/domain]]
                         db)]

    (println "\n=== Cleaning Up Legacy Domains ===")
    (println "Found" (count domain-eids) "legacy domain entities to remove")

    (when (seq domain-eids)
      (d/transact conn {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) domain-eids)}))

    (println "Cleanup complete:" (count domain-eids) "entities removed")

    {:removed (count domain-eids)}))

(defn migrate-legacy-auth!
  "Full migration from legacy auth to permissions-based auth.

   Steps:
   1. Grant admin-full to users in ADMIN_USER_EMAILS
   2. Grant can-login to users from whitelisted domains (who don't already have permissions)
   3. Optionally clean up legacy domain entities

   Args:
     conn - Datahike connection
     opts - Options:
       :cleanup? - If true, remove legacy domain entities (default: false)

   Returns: Combined results from all migration steps"
  [conn & [{:keys [cleanup?] :or {cleanup? false}}]]
  (println "\n========================================")
  (println "  LEGACY AUTH MIGRATION")
  (println "========================================\n")

  (let [admin-results (migrate-admin-users! conn)
        domain-results (migrate-domain-users! conn)
        cleanup-results (when cleanup?
                         (cleanup-legacy-domains! conn))]

    (println "\n========================================")
    (println "  MIGRATION SUMMARY")
    (println "========================================")
    (println "Admin users created:" (count (:created admin-results)))
    (println "Admins granted:" (count (:granted admin-results)))
    (println "Domain users granted:" (count (:granted domain-results)))
    (when cleanup?
      (println "Legacy domains removed:" (:removed cleanup-results)))
    (println "========================================\n")

    {:admin-migration admin-results
     :domain-migration domain-results
     :cleanup cleanup-results}))

(comment
  ;; Example usage:

  ;; Run full migration (without cleanup)
  (migrate-legacy-auth! (db/get-conn))

  ;; Run full migration with cleanup
  (migrate-legacy-auth! (db/get-conn) {:cleanup? true})

  ;; Run individual steps
  (migrate-admin-users! (db/get-conn))
  (migrate-domain-users! (db/get-conn))
  (cleanup-legacy-domains! (db/get-conn)))
