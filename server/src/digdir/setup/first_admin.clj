(ns digdir.setup.first-admin
  "Create the first admin on a fresh deployment, as a `-main` on the jar the
   image ships (#436).

   ## The gap this closes

   Creating a user required already being an admin, and nothing created the
   first admin. On an empty database `POST /auth` ends at `302 /not-approved`
   — *\"User not found. Contact an administrator to request access.\"* — and on
   a laptop there is no administrator to contact.

   ## Why a `-main` and not a boot hook

   ⛔ **Claiming a fresh instance must be an explicit operator action.** A
   deployment that silently grants admin to the first caller — or to whatever
   address happens to be in the environment at boot — is a worse defect than
   the one being fixed, and this ships open source. Requiring a command inside
   the container means the person who runs it already has shell access, which
   is the authority being exercised. Nobody reaching the HTTP surface can
   trigger it.

   Same shape as `digdir.setup.bootstrap` (#496) and `digdir.setup.config-cli`
   (#508), for the same reason: `/app` holds one file, `app.jar`. There is no
   source tree and no `bb`, so anything callable in a container has to live in
   `src` and be reachable as a `-main`:

       docker compose exec digdir-rag \\
         java -cp /app/app.jar clojure.main -m digdir.setup.first-admin

   ## It adds no new mechanism

   `ADMIN_USER_EMAILS` already exists, is already bridged
   (`env-bridge` → `services.auth.admin-user-emails`), and
   `auth.migration/migrate-admin-users!` already CREATES those users and grants
   them `admin-full`. That function lives in `src`, so it is already in the
   image — it simply had no non-interactive caller. Its only route was the
   interactive wizard, behind a `prompt-yn`.

   ⚠️ **One variable, two consumers, opposite behaviour** — this is the trap
   the issue documents. The boot-time hook
   `permissions/sync-admin-permissions!` only GRANTS to users that already
   exist, so on an empty database it prints `User not found` per address and
   continues. A newcomer sets the variable, restarts, sees that, and correctly
   concludes the variable does not work — while the same variable would have
   worked through a path nothing told them about. This is that path, made
   callable."
  (:require [digdir.auth.migration :as migration]
            [digdir.config.db :as config-db]
            [digdir.setup.common :as common]))

(defn- report-no-emails!
  "Refuse, naming the variable and both places it can be set.

   Refusing is the point: with no addresses there is nothing to create, and
   inventing a default would be the automatic grant this must not do."
  []
  (println)
  (println "  ✖ Refusing: ADMIN_USER_EMAILS is empty.")
  (println)
  (println "    This command creates the accounts named in that variable and")
  (println "    nothing else. It will not invent an address, because a fresh")
  (println "    deployment that grants admin to a default is worse than one")
  (println "    that cannot be logged into.")
  (println)
  (println "    Set it on the HOST, in .env, then restart the stack:")
  (println)
  (println "      ADMIN_USER_EMAILS=you@example.com")
  (println)
  (println "    Comma- or space-separate several if more than one person")
  (println "    should claim this instance.")
  (println))

(defn -main
  "Create the ADMIN_USER_EMAILS accounts and grant them admin-full. Idempotent."
  [& _args]
  (println)
  (println "digdir — first admin")
  (println "====================")
  ;; #493: this writes from its own JVM, and while the server is up that write
  ;; is LOST rather than merely unseen — measured on two fresh container stacks.
  ;; Refusing beats documenting the order, because the command otherwise reports
  ;; success and creates nothing.
  (common/refuse-if-server-running! "digdir.setup.first-admin")
  (let [emails (migration/get-admin-emails-from-env)]
    (if-not (seq emails)
      (do (report-no-emails!)
          (flush)
          (System/exit 1))
      (do
        (println (str "  ADMIN_USER_EMAILS names " (count emails) " address"
                      (when (not= 1 (count emails)) "es") ":"))
        (doseq [e (sort emails)]
          (println (str "      " e)))
        (println)
        (let [result (migration/migrate-admin-users! (config-db/get-conn))]
          (println)
          (println (str "  ✔ created " (count (:created result))
                        ", granted " (count (:granted result))
                        ", already admin " (count (:skipped result))))
          (println)
          ;; ⚠️ THIS IS REACHED ONLY WITH THE SERVER STOPPED — `refuse-if-server-
          ;; running!` above exits before here whenever a server answers /up. So
          ;; by the time this prints there is nothing running to restart, and the
          ;; operator needs to START the server. (The one exception is someone
          ;; forcing past the guard with DIGDIR_ALLOW_SEED_WITH_SERVER_RUNNING,
          ;; in which case the write has most likely been lost and the account
          ;; below does not exist.)
          ;;
          ;; The text that stood here said the opposite, and was wrong in both
          ;; directions. It claimed a second JVM reading the same store reported
          ;; `can-login? -> true` while `POST /auth` still returned
          ;; 302 /not-approved, and concluded the server merely held a stale
          ;; connection that a restart would refresh. MEASURED on two independent
          ;; fresh container stacks (#493): with the server running that second
          ;; JVM reports `can-login? -> false` and zero admins — immediately
          ;; after the command and again 45s later — and `docker compose restart`
          ;; does NOT recover it. The write never lands, so the restart was never
          ;; catching the server up to data that was already there.
          (println "  ▸ START THE SERVER, then log in:")
          (println)
          (println "      docker compose start digdir-rag")
          (println)
          (println "    This ran with the server stopped, which is the only way")
          (println "    the write survives. Nothing needs restarting: on a store")
          (println "    seeded this way, login succeeds on the first attempt.")
          (println)
          (println "  Then log in at /auth with one of those addresses.")
          (println)
          (println "  If no email service is configured the confirmation code")
          (println "  cannot be delivered and the login will fail at that step.")
          (println "  Configure Scaleway TEM, or for a local stack only, set")
          (println "  DIGDIR_LOG_CONFIRMATION_CODES=true and read the code from")
          (println "  the server log.")
          (println)
          ;; Explicit: a -main that returns normally still leaves the JVM
          ;; waiting on non-daemon threads the config DB starts.
          (flush)
          (System/exit 0))))))
