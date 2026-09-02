(ns digdir.setup.common-test
  "Covers the setup wizard's database-backend detection.

   Regression guard for the wizard hard-requiring the Postgres variables while
   `digdir.config.core/load-bootstrap-config` prefers DATAHIKE_FILE_PATH."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.setup.common :as setup-common])
  (:import (java.io BufferedReader StringReader)))

(defn- env-fn
  "Env lookup over a fixed map, mimicking `System/getenv` (nil when unset)."
  [m]
  (fn [k] (get m k)))

(deftest selected-db-backend-test
  (testing "DATAHIKE_FILE_PATH alone selects the local file backend"
    (is (= :local (setup-common/selected-db-backend
                   (env-fn {"DATAHIKE_FILE_PATH" "./local-db/dh_dev_v1"})))))

  (testing "ADH_POSTGRES_URL alone selects the remote backend"
    (is (= :remote (setup-common/selected-db-backend
                    (env-fn {"ADH_POSTGRES_URL" "postgresql://host:5432/db"
                             "ADH_POSTGRES_USER" "u"
                             "ADH_POSTGRES_PWD" "p"})))))

  (testing "the file path wins when both are set, matching load-bootstrap-config"
    (is (= :local (setup-common/selected-db-backend
                   (env-fn {"DATAHIKE_FILE_PATH" "./local-db/dh_dev_v1"
                            "ADH_POSTGRES_URL" "postgresql://host:5432/db"})))))

  (testing "no pointer set means no backend"
    (is (nil? (setup-common/selected-db-backend (env-fn {}))))
    (is (nil? (setup-common/selected-db-backend
               (env-fn {"DATAHIKE_FILE_PATH" ""
                        "ADH_POSTGRES_URL" "   "})))))

  (testing "blank values are ignored on the way to the next option"
    (is (= :remote (setup-common/selected-db-backend
                    (env-fn {"DATAHIKE_FILE_PATH" "  "
                             "ADH_POSTGRES_URL" "postgresql://host:5432/db"}))))))

(deftest check-env-vars-reports-backend-test
  (testing "the wizard states which backend it selected"
    (let [output (with-out-str (setup-common/check-env-vars))]
      (is (re-find #"Database backend:" output))
      (is (re-find #"Required environment variables:" output)))))

;; ---------------------------------------------------------------------------
;; End-of-input handling
;;
;; `read-line` returns nil once stdin is closed or exhausted. Passing that to
;; `str/trim` used to end `bb setup` in a NullPointerException, which made the
;; wizard unscriptable.
;; ---------------------------------------------------------------------------

(defn- on-stdin
  "Run `f` with stdin bound to `input`. An empty string is an immediately
   exhausted stream, i.e. what `bb setup < /dev/null` sees. Returns f's value;
   the prompt echo is swallowed."
  [input f]
  (let [result (atom nil)]
    (with-out-str
      (binding [*in* (BufferedReader. (StringReader. input))]
        (reset! result (f))))
    @result))

(deftest read-answer-eof-test
  (testing "an exhausted stream reads as the eof sentinel, not nil"
    (is (setup-common/eof? (on-stdin "" #(setup-common/read-answer "q")))))

  (testing "a blank line is an answer, not end of input"
    (let [answer (on-stdin "\n" #(setup-common/read-answer "q"))]
      (is (= "" answer))
      (is (not (setup-common/eof? answer)))))

  (testing "an answer comes back trimmed"
    (is (= "value" (on-stdin "  value  \n" #(setup-common/read-answer "q"))))))

(deftest prompt-eof-test
  (testing "prompt returns empty at EOF instead of throwing"
    ;; The regression: (str/trim nil) => NullPointerException.
    (is (= "" (on-stdin "" #(setup-common/prompt "q")))))

  (testing "prompt still reads and trims a real answer"
    (is (= "hello" (on-stdin "  hello  \n" #(setup-common/prompt "q"))))))

(deftest prompt-secret-eof-test
  (testing "prompt-secret returns empty at EOF instead of throwing"
    ;; The second NPE site — it surfaced at the Scaleway key prompt.
    (is (= "" (on-stdin "" #(setup-common/prompt-secret "key")))))

  (testing "prompt-secret still reads and trims a real answer"
    (is (= "s3cret" (on-stdin "  s3cret \n" #(setup-common/prompt-secret "key"))))))

(deftest prompt-yn-eof-test
  (testing "EOF takes the default rather than throwing or looping"
    (is (false? (on-stdin "" #(setup-common/prompt-yn "go?" false))))
    (is (true? (on-stdin "" #(setup-common/prompt-yn "go?" true)))))

  (testing "an explicit answer still wins over the default"
    (is (true? (on-stdin "y\n" #(setup-common/prompt-yn "go?" false))))
    (is (false? (on-stdin "n\n" #(setup-common/prompt-yn "go?" true)))))

  (testing "an invalid answer followed by EOF falls back to the default"
    ;; prompt-yn recurs on unrecognised input; EOF must end that, not spin.
    (is (false? (on-stdin "maybe\n" #(setup-common/prompt-yn "go?" false))))))

(deftest prompt-with-default-eof-test
  (testing "EOF keeps the default"
    (is (= "fr-par" (on-stdin "" #(setup-common/prompt-with-default "region" "fr-par")))))

  (testing "an explicit answer overrides it"
    (is (= "nl-ams" (on-stdin "nl-ams\n" #(setup-common/prompt-with-default "region" "fr-par"))))))

(deftest prompt-required-eof-test
  (testing "EOF exits non-zero instead of looping forever on a blank answer"
    (let [exits (atom [])]
      (with-redefs [setup-common/exit! (fn [status] (swap! exits conj status) ::exited)]
        (is (= ::exited (on-stdin "" #(setup-common/prompt-required "tenant id")))))
      (is (= [1] @exits) "exits once, with a non-zero status")))

  (testing "a blank answer is still retried when input remains"
    (let [exits (atom [])]
      (with-redefs [setup-common/exit! (fn [status] (swap! exits conj status) ::exited)]
        (is (= "mycompany" (on-stdin "\nmycompany\n" #(setup-common/prompt-required "tenant id")))))
      (is (empty? @exits) "a retryable blank must not exit"))))
