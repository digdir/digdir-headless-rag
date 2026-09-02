(ns digdir.config.core-test
  "#326 — a missing bootstrap variable must name itself.

   `load-bootstrap-config` returns nil on THREE distinct causes and the caller
   could not tell them apart. On a fresh clone all three surfaced as
   `NullPointerException at datahike.writer/transact!`, byte identical, naming
   none of them. These tests pin the diagnosis that replaced it.

   Values never appear here — the assertions are on variable NAMES."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.core :as core]))

(defn- env-fn
  "Env lookup over a fixed map, mimicking `System/getenv` (nil when unset)."
  [m]
  (fn [k] (get m k)))

(def ^:private complete
  {"DATAHIKE_FILE_PATH" "./local-db/dh_dev_v1"
   "CONFIG_MASTER_KEY" "a-key"
   "JWT_SECRET" "a-secret"})

(deftest a-complete-environment-has-nothing-missing
  (is (= [] (core/missing-bootstrap-requirements (env-fn complete))))
  (testing "the Postgres spelling of the pointer satisfies it too"
    ;; One requirement with two spellings, matching load-bootstrap-config's
    ;; own cond — not two requirements, which would report a false miss for
    ;; every correctly-configured Postgres install.
    (is (= [] (core/missing-bootstrap-requirements
               (env-fn (-> complete
                           (dissoc "DATAHIKE_FILE_PATH")
                           (assoc "ADH_POSTGRES_URL" "postgresql://h:5432/d"))))))))

(deftest the-three-causes-are-distinguishable
  ;; This is the defect. Before #326 these three were one nil and one NPE.
  (testing "no database pointer"
    (is (= [:database-pointer]
           (mapv :id (core/missing-bootstrap-requirements
                      (env-fn (dissoc complete "DATAHIKE_FILE_PATH")))))))

  (testing "no master key"
    (is (= [:config-master-key]
           (mapv :id (core/missing-bootstrap-requirements
                      (env-fn (dissoc complete "CONFIG_MASTER_KEY")))))))

  (testing "no jwt secret"
    ;; The issue as filed named two causes. There are three.
    (is (= [:jwt-secret]
           (mapv :id (core/missing-bootstrap-requirements
                      (env-fn (dissoc complete "JWT_SECRET")))))))

  (testing "an empty environment reports ALL THREE, not just the first"
    ;; One run has to tell a newcomer everything they must set. Reporting only
    ;; the first cause turns one failure into three sequential ones.
    (is (= [:database-pointer :config-master-key :jwt-secret]
           (mapv :id (core/missing-bootstrap-requirements (env-fn {})))))))

(deftest blank-is-missing-not-present
  ;; A variable exported as "" is the shape a half-filled .env produces, and
  ;; load-bootstrap-config treats it as absent via not-empty. The diagnosis
  ;; must agree with it, or the error would say everything is set.
  (is (= [:database-pointer]
         (mapv :id (core/missing-bootstrap-requirements
                    (env-fn (assoc complete "DATAHIKE_FILE_PATH" "   ")))))))

(deftest the-error-names-every-missing-variable-and-the-remedy
  (let [msg (.getMessage (core/bootstrap-config-error
                          (core/missing-bootstrap-requirements (env-fn {}))))]
    (testing "every variable that could satisfy the requirement is named"
      (doseq [v ["DATAHIKE_FILE_PATH" "ADH_POSTGRES_URL" "CONFIG_MASTER_KEY" "JWT_SECRET"]]
        (is (str/includes? msg v) (str v " must be named"))))

    (testing "it matches the directory pre-flight's standard: cwd and a remedy"
      (is (str/includes? msg "cwd=") "relative paths resolve against it")
      (is (str/includes? msg "export ") "and there is something to copy"))

    (testing "it says why these cannot come from config"
      ;; Otherwise the reader's next move is `bb config-set`, which cannot
      ;; work — these are read before any config database is opened.
      (is (str/includes? msg "bb config-set")))

    (testing "the data carries the ids and names for a machine reader"
      (let [d (ex-data (core/bootstrap-config-error
                        (core/missing-bootstrap-requirements (env-fn {}))))]
        (is (= [:database-pointer :config-master-key :jwt-secret] (:missing d)))
        (is (some #{"CONFIG_MASTER_KEY"} (:env-vars d)))))))

(deftest the-error-carries-no-value
  ;; The names are the payload. A value reaching this message would reach logs.
  (let [secret "sk-do-not-log-me"
        msg (.getMessage (core/bootstrap-config-error
                          (core/missing-bootstrap-requirements
                           (env-fn {"CONFIG_MASTER_KEY" secret}))))]
    (is (not (str/includes? msg secret))
        "a set variable's value must never appear in the diagnosis")))
