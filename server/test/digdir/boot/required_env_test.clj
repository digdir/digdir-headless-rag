(ns digdir.boot.required-env-test
  "`:tier :boot` says the server refuses to start without a value. Nothing
   derived a check from that sentence (#521).

   ## The red these were written against

   Measured on the pre-fix tree, with every one of the six `:tier :boot`
   variables absent, against the only boot gate keyed to them:

       (placeholder-secrets/check! (constantly nil))
       => {:checked 14, :violations [], :overridden? false}

   No refusal. The server started. `an-environment-missing-every-boot-variable-
   is-refused` is that same environment, and it is the assertion that was
   impossible to satisfy before this namespace existed.

   ## The other half: it must NOT fire on what already worked

   A gate that refuses every environment enforces nothing useful — and a flat
   presence check over these six rows would do exactly that, because the four
   database variables are two ALTERNATIVE families and no correct deployment
   sets both. `a-complete-file-backend-environment-boots` and its Postgres
   twin are the environments that worked before and must keep working.

   ## Why the complete-environment fixtures are hand-written

   Deliberately NOT derived from `env-config-bindings`. The derivation is the
   thing under test; a fixture built from the same table would agree with it
   however wrong it got, which is a differential check with no absolute
   anchor. The cost is that a seventh `:boot` variable turns these two tests
   red — which is the correct outcome. Someone must then decide whether the
   new variable belongs in the base set or in an alternative group, and say so
   here. `every-boot-tier-variable-is-reachable-by-the-check` is the derived
   companion that fails for the opposite reason."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.boot.required-env :as required-env]
            [digdir.config.env-bridge :as env-bridge]))

(defn- env-fn [m] (fn [k] (get m k)))

(def ^:private file-backend-env
  "A complete environment using the local file store — the `bb dev` shape."
  {"DATAHIKE_FILE_PATH" "./local-db/dh_test"
   "CONFIG_MASTER_KEY" "master-key-value"
   "JWT_SECRET" "jwt-secret-value"})

(def ^:private postgres-env
  "A complete environment using the Postgres backend — the deployed shape."
  {"ADH_POSTGRES_URL" "jdbc:postgresql://host/db"
   "ADH_POSTGRES_USER" "user-value"
   "ADH_POSTGRES_PWD" "pwd-value"
   "CONFIG_MASTER_KEY" "master-key-value"
   "JWT_SECRET" "jwt-secret-value"})

;; ---------------------------------------------------------------------------
;; The refusal
;; ---------------------------------------------------------------------------

(deftest an-environment-missing-every-boot-variable-is-refused
  (testing "The fresh-clone environment. This is what booted before."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Refusing to start"
          (required-env/check! (env-fn {}))))))

(deftest the-refusal-names-what-is-missing-and-which-choice-is-open
  (let [e (try (required-env/check! (env-fn {})) nil
               (catch clojure.lang.ExceptionInfo e e))
        {:keys [violations missing unsatisfied-groups checked]} (ex-data e)]
    (is (some? e) "an empty environment must refuse")
    (is (pos? checked) ":checked 0 would mean the table came back empty")
    (is (= ["CONFIG_MASTER_KEY" "JWT_SECRET"] (vec missing))
        "the flat boot requirements, named individually")
    (is (= [:database] (mapv :group unsatisfied-groups))
        "and the database pointer reported as one open CHOICE, not four vars")
    (is (some #(str/includes? % "database-file") violations))
    (is (some #(str/includes? % "database-postgres") violations))))

(deftest a-half-supplied-backend-family-is-refused
  (testing "ADH_POSTGRES_URL alone selects the remote backend and then fails
            on the credentials. Neither family is complete, so refuse."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Refusing to start"
          (required-env/check!
            (env-fn (merge (dissoc postgres-env "ADH_POSTGRES_USER"
                                   "ADH_POSTGRES_PWD"))))))))

(deftest a-blank-value-is-absent
  (testing "An exported-but-empty variable is what a half-filled .env
            produces. Accepting it passes the check and fails the boot."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Refusing to start"
          (required-env/check!
            (env-fn (assoc file-backend-env "CONFIG_MASTER_KEY" "   ")))))))

;; ---------------------------------------------------------------------------
;; The input that already worked
;; ---------------------------------------------------------------------------

(deftest a-complete-file-backend-environment-boots
  (testing "Must be green. A guard that fires on everything is as useless as
            one that fires on nothing."
    (let [summary (required-env/check! (env-fn file-backend-env))]
      (is (= [] (:violations summary)))
      (is (pos? (:checked summary)))
      (is (false? (:overridden? summary))))))

(deftest a-complete-postgres-environment-boots
  (testing "The other family. Setting neither ADH_* nor DATAHIKE_* is the
            failure; setting either complete one is not."
    (is (= [] (:violations (required-env/check! (env-fn postgres-env)))))))

(deftest supplying-both-families-still-boots
  (testing "Precedence decides which is USED; the gate only asks whether any
            one option is complete."
    (is (= [] (:violations (required-env/check!
                             (env-fn (merge postgres-env file-backend-env))))))))

;; ---------------------------------------------------------------------------
;; Derived, not listed — the property the issue actually asks for
;; ---------------------------------------------------------------------------

(deftest every-boot-tier-variable-is-reachable-by-the-check
  (testing "A seventh :boot row must inherit the behaviour with no edit here.
            Every :tier :boot variable appears in the requirement the check
            computes for an empty environment — either as a flat miss or
            inside an alternative group."
    (let [declared (set (env-bridge/env-vars-for-tier :boot))
          {:keys [missing unsatisfied-groups]} (env-bridge/boot-requirements (env-fn {}))
          reached (into (set missing)
                        (mapcat (fn [g] (mapcat :missing (:options g))))
                        unsatisfied-groups)]
      (is (seq declared) "a vacuous run would pass this trivially")
      (is (= declared reached)
          "every declared boot variable is covered by the derivation"))))

(deftest the-check-holds-no-list-of-its-own
  (testing "The guard against the defect class: no variable name is typed in
            the boot-check namespace. If someone adds one, this fails and says
            why."
    (let [src (slurp (io/resource "digdir/boot/required_env.clj"))]
      (doseq [v (env-bridge/env-vars-for-tier :boot)]
        (is (not (str/includes? src (str "\"" v "\"")))
            (str v " is hard-coded in digdir.boot.required-env — the point of "
                 "the check is that it reads :tier off the table"))))))

;; ---------------------------------------------------------------------------
;; Wiring — a check nothing calls is the same defect one layer up
;; ---------------------------------------------------------------------------

(deftest both-server-entrypoints-run-the-check
  (testing "dev.cljc and prod.cljc both start a server; a gate wired into only
            one is a gate the other's operators do not have."
    (doseq [path ["src-dev/dev.cljc" "src-prod/prod.cljc"]]
      (let [src (slurp (io/file path))]
        (is (str/includes? src "required-env/check!")
            (str path " does not call the boot-environment check"))))))

;; ---------------------------------------------------------------------------
;; Never a value
;; ---------------------------------------------------------------------------

(deftest neither-the-message-nor-the-ex-data-carries-a-value
  (testing "Every value in scope here is a credential somebody set."
    (let [values ["master-key-value" "jwt-secret-value" "pwd-value" "user-value"]
          ;; Postgres complete except the URL: refuses, while three secret
          ;; values ARE present and readable by the check.
          e (try (required-env/check!
                   (env-fn (dissoc postgres-env "ADH_POSTGRES_URL")))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))
          text (str (ex-message e) " " (pr-str (ex-data e)))]
      (is (some? e))
      (doseq [v values]
        (is (not (str/includes? text v))
            (str "a value reached the refusal: " v))))))

;; ---------------------------------------------------------------------------
;; The escape hatch
;; ---------------------------------------------------------------------------

(deftest the-override-boots-and-reports-that-it-did
  (testing "Explicit, never silent — same policy as its placeholder sibling."
    (let [summary (required-env/check!
                    (env-fn {required-env/override-env-var "true"}))]
      (is (true? (:overridden? summary)))
      (is (seq (:violations summary))
          "the violations are still reported, not suppressed")))
  (testing "Casing is forgiven — an operator reaching for the hatch under
            pressure should not also have to guess its capitalisation."
    (doseq [v ["TRUE" "True" "  tRuE  "]]
      (is (true? (:overridden? (required-env/check!
                                 (env-fn {required-env/override-env-var v}))))
          (str "override value " (pr-str v) " should engage the override"))))
  (testing "The value must still SAY true; nothing else engages it."
    (doseq [v ["yes" "1" "" "false" "truthy"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (required-env/check!
                     (env-fn {required-env/override-env-var v})))
          (str "override value " (pr-str v) " should not disable the check")))))
