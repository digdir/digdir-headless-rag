(ns digdir.setup.bootstrap
  "The DB half of first-run setup, as a `-main` on the jar the image ships (#489).

   ## Why this is a `-main` and not a `bb` task

   Measured on the runtime image rather than assumed: `/app` contains exactly
   one file, `app.jar`. There is no `server/src`, no `bb.edn`, and no
   `deps.edn` beyond the Clojure CLI's own. Every `bb` task that does real work
   shells out to `clojure {:dir \"server\"} -M -e …`, which needs the source
   tree and dependency resolution — so adding `bb` to the image would mean
   shipping a dev image.

   What the image already has is a JVM and this jar, which is enough:

       docker compose exec server \\
         java -cp /app/app.jar clojure.main -m digdir.setup.bootstrap

   ## What it does NOT do, and cannot

   It does not write `.env`. `.env` is read by `docker compose` ON THE HOST, so
   no container-side tool can write it — a container writing its own `.env`
   would be writing a file nothing reads. Generating secrets is
   `scripts/setup-env.sh`, which runs on the host and needs no toolchain.

   The two halves meet here: this refuses to seed while a secret is still a
   placeholder, and names the host script as the fix. Running them in the wrong
   order therefore fails with an instruction rather than by writing config
   encrypted under a key that is public in the repository.

   ## Seeds the TENANT, not the dataset

   `demo-tenant/seed!` only. Seeding the dataset as well would leave a newcomer
   with a configured dataset and no corpus, which answers unhelpfully with no
   sign that a step remains (#473) — strictly worse than the no-datasets 404
   they get from this. `bb demo-seed` remains the way to do both, on a machine
   that has the toolchain and has fetched the corpus."
  (:require [digdir.boot.placeholder-secrets :as placeholder-secrets]
            [digdir.setup.common :as common]
            [digdir.setup.demo-tenant :as demo-tenant]))

(defn- report-placeholders!
  "Refuse while any secret still holds its placeholder.

   Reuses deliverable 1's derived set and marker rather than restating either —
   a second copy is the drift #488 and #489 both exist to prevent. The check
   itself throws; this only adds the instruction that is specific to setup,
   because at this point the caller has a fix available and should be told what
   it is."
  []
  (try
    (placeholder-secrets/check!)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [violations]} (ex-data e)]
        (println)
        (println "  ✖ Refusing to seed: these secrets still hold their placeholder —")
        (doseq [v (sort violations)]
          (println (str "      " v)))
        (println)
        (println "    Seeding now would write config encrypted under a key that is")
        (println "    published in this repository. Fix them on the HOST first:")
        (println)
        (println "      ./scripts/setup-env.sh")
        (println)
        (println "    then restart the stack and run this again.")
        (println)
        (throw e)))))

(defn -main
  "Seed the demo tenant into the config DB. Idempotent."
  [& _args]
  (println)
  (println "digdir — first-run setup (database half)")
  (println "========================================")
  ;; #493: same hazard as every other setup -main — a write from a second JVM
  ;; while the server runs does not land, and the command still prints success.
  (common/refuse-if-server-running! "digdir.setup.bootstrap")
  (let [status (report-placeholders!)]
    (println (str "  ✔ secrets: " (:checked status) " examined, none holding a placeholder"))
    (let [result (demo-tenant/seed!)]
      (println (str "  ✔ tenant '" (:tenant result) "' seeded — "
                    (count (:paths-written result)) " platform values"))
      (doseq [p (:paths-written result)]
        (println (str "      " p " (" (name (get (:actions result) p :unchanged)) ")")))
      (println)
      (println "  This tenant has NO dataset yet, which is deliberate: a dataset")
      (println "  with no fetched corpus answers unhelpfully and says nothing about")
      (println "  the missing step. Asking a question now returns a 404 naming what")
      (println "  to do next.")
      (println)
      (println "  Done.")
      (println)
      ;; Explicit, because a -main that returns normally still leaves the JVM
      ;; waiting on non-daemon threads the config DB starts.
      (flush)
      (System/exit 0))))
