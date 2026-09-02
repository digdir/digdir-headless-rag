(ns digdir.auth.core-test
  "Covers admin-login confirmation codes: the dev delivery fallback and the
   validity check.

   First login needs a 6-digit code delivered by Scaleway TEM; with no email
   service configured the send throws and the admin UI is unreachable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.auth.core :as auth]
            [digdir.config.accessor]
            [digdir.data.db :as db]
            [datahike.api :as d]))

(def ^:private test-addresses
  ["a@b.com" "nobody@example.com" "someone-else@b.com" "restart@example.com"])

(defn- expire-code!
  "Back-date an outstanding code's expiry so it is past, without sleeping.

   Deliberately edits the stored row rather than redefining `now`: the row is
   what production reads, and the point of these tests is what the DATABASE
   holds after a restart."
  [email]
  (let [conn (db/get-conn)]
    (when-let [entity (d/entity @conn [:confirmation-code/email email])]
      (d/transact conn {:tx-data [{:db/id (:db/id entity)
                                   :confirmation-code/expires-at
                                   (- (System/currentTimeMillis) 1000)}]}))))

(defn- clear-codes!
  "Remove every code these tests mint. Codes live in the database now (#63),
   so a test that leaves one behind leaks into the next one."
  []
  (run! auth/consume-confirmation-code! test-addresses))

(defn- reset-auth-state
  "The dev switch is process-global and the code store is the database, so
   clear both whatever a test leaves behind."
  [f]
  (clear-codes!)
  (try (f)
       (finally
         (auth/set-dev-confirmation-code-logging! false)
         (clear-codes!))))

(use-fixtures :each reset-auth-state)

(deftest dev-switch-defaults-off-test
  (testing "the fallback is disarmed unless something arms it"
    (is (false? (auth/dev-confirmation-code-logging?))))

  (testing "arming and disarming round-trips"
    (auth/set-dev-confirmation-code-logging! true)
    (is (true? (auth/dev-confirmation-code-logging?)))
    (auth/set-dev-confirmation-code-logging! false)
    (is (false? (auth/dev-confirmation-code-logging?)))))

(deftest deliver-confirmation-code-without-dev-fallback-test
  (testing "with the fallback disarmed the send is attempted"
    (let [sent (atom nil)]
      (with-redefs [auth/send-confirmation-code (fn [tenant to code] (reset! sent [tenant to code]))]
        (is (= :sent (auth/deliver-confirmation-code! "t" "a@b.com" "123456")))
        (is (= ["t" "a@b.com" "123456"] @sent)))))

  (testing "a send failure still propagates - production behaviour is unchanged"
    (with-redefs [auth/send-confirmation-code (fn [_ _ _] (throw (ex-info "SCW_SECRET_KEY must be configured" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SCW_SECRET_KEY"
                            (auth/deliver-confirmation-code! "t" "a@b.com" "123456"))))))

(deftest deliver-confirmation-code-with-dev-fallback-test
  (testing "no email service configured: the code is logged, not sent"
    (auth/set-dev-confirmation-code-logging! true)
    (let [sent (atom nil)]
      (with-redefs [auth/email-configured? (constantly false)
                    auth/send-confirmation-code (fn [tenant to code] (reset! sent [tenant to code]))]
        (is (= :logged (auth/deliver-confirmation-code! "t" "a@b.com" "123456")))
        (is (nil? @sent) "the unconfigured send must not be attempted"))))

  (testing "a configured email service still wins over the fallback"
    (auth/set-dev-confirmation-code-logging! true)
    (let [sent (atom nil)]
      (with-redefs [auth/email-configured? (constantly true)
                    auth/send-confirmation-code (fn [tenant to code] (reset! sent [tenant to code]))]
        (is (= :sent (auth/deliver-confirmation-code! "t" "a@b.com" "123456")))
        (is (= ["t" "a@b.com" "123456"] @sent)))))

  (testing "a failing send falls back to the log instead of breaking login"
    (auth/set-dev-confirmation-code-logging! true)
    (with-redefs [auth/email-configured? (constantly true)
                  auth/send-confirmation-code (fn [_ _ _] (throw (ex-info "boom" {})))]
      (is (= :logged (auth/deliver-confirmation-code! "t" "a@b.com" "123456"))))))

(deftest email-configured-swallows-lookup-failure-test
  (testing "a config lookup that throws counts as unconfigured, not as an error"
    ;; On a fresh DB cfg/get raises "Canonical tenant root node not found" for
    ;; __global__ instead of returning nil - the exact first-run case here.
    (with-redefs [digdir.config.accessor/get (fn [& _] (throw (ex-info "Canonical tenant root node not found" {})))]
      (is (false? (auth/email-configured? "__global__")))))

  (testing "so the fallback logs the code rather than propagating that failure"
    (auth/set-dev-confirmation-code-logging! true)
    (with-redefs [digdir.config.accessor/get (fn [& _] (throw (ex-info "Canonical tenant root node not found" {})))
                  auth/send-confirmation-code (fn [_ _ _] (throw (ex-info "should not be reached" {})))]
      (is (= :logged (auth/deliver-confirmation-code! "__global__" "a@b.com" "123456"))))))

(deftest dev-code-log-message-test
  (testing "the logged line carries the code and the address it was meant for"
    (let [msg (auth/dev-code-log-message "a@b.com" "123456")]
      (is (str/includes? msg "123456"))
      (is (str/includes? msg "a@b.com"))
      (is (str/includes? msg "dev-login") "grep tag so the docs can point at it"))))

(deftest valid-code-no-outstanding-entry-test
  (testing "no code was ever requested for this address"
    ;; The case that used to throw IllegalArgumentException out of tick: the
    ;; entry is missing, so expiry is nil and (t/< (t/now) nil) blew up.
    (is (false? (auth/valid-code? "nobody@example.com" "123456")))
    (is (= :unknown (auth/code-status "nobody@example.com" "123456"))))

  (testing "the entry was consumed by a successful login"
    ;; digdir.api.http consumes the entry on success, so a double-submit or a
    ;; back-then-resubmit lands here.
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (is (true? (auth/valid-code? "a@b.com" code)))
      (is (true? (auth/consume-confirmation-code! "a@b.com")) "something was retracted")
      (is (false? (auth/valid-code? "a@b.com" code)))
      (is (= :unknown (auth/code-status "a@b.com" code)))
      (is (false? (auth/consume-confirmation-code! "a@b.com"))
          "consuming twice is a no-op, not an error")))

  (testing "a different address than the one holding a code"
    (auth/generate-confirmation-code "a@b.com")
    (is (false? (auth/valid-code? "someone-else@b.com" "123456")))))

(deftest valid-code-outstanding-entry-test
  (testing "the right code for an unexpired entry is accepted"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (is (true? (auth/valid-code? "a@b.com" code)))))

  (testing "a wrong code for an unexpired entry is rejected"
    (auth/generate-confirmation-code "a@b.com")
    (is (false? (auth/valid-code? "a@b.com" "000000"))))

  (testing "an expired entry is rejected rather than throwing"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (expire-code! "a@b.com")
      (is (false? (auth/valid-code? "a@b.com" code))))))

;; ---------------------------------------------------------------------------
;; #63 — where codes live, and whether a failure can be explained
;; ---------------------------------------------------------------------------

(deftest a-code-outlives-the-process-that-minted-it
  (testing "the code is in the database, not in a var this namespace holds"
    ;; This is the whole of #63. Before the fix the code lived in a
    ;; process-global atom, so every deploy and every crash invalidated every
    ;; outstanding code — and the resulting page was byte-for-byte identical
    ;; to a wrong code (measured against a live server, see
    ;; docs/confirmation-code-lifetime.md). A test cannot restart the JVM, so
    ;; it asserts the property that makes a restart survivable: the code is
    ;; recoverable from the database alone.
    (let [code (auth/generate-confirmation-code "restart@example.com")
          entity (d/entity @(db/get-conn) [:confirmation-code/email "restart@example.com"])]
      (is (nil? (:confirmation-code/code entity))
          "the delivered bearer code must never be persisted in plaintext")
      (is (string? (:confirmation-code/code-digest entity))
          "a fresh process validates against the persisted digest")
      (is (true? (auth/valid-code? "restart@example.com" code))))))

(deftest startup-migration-invalidates-legacy-plaintext-codes
  (let [conn (db/get-conn)]
    (d/transact conn
                {:tx-data [{:confirmation-code/email "someone-else@b.com"
                            :confirmation-code/code "123456"
                            :confirmation-code/created-at (System/currentTimeMillis)
                            :confirmation-code/expires-at
                            (+ (System/currentTimeMillis) 600000)
                            :confirmation-code/failed-attempts 0}]})
    (is (= 1 (db/purge-legacy-plaintext-confirmation-codes! conn)))
    (is (nil? (d/entity @conn
                        [:confirmation-code/email "someone-else@b.com"])))
    (is (= 0 (db/purge-legacy-plaintext-confirmation-codes! conn))
        "the migration is safe to repeat")))

(deftest verify-and-consume-is-single-use
  (let [code (auth/generate-confirmation-code "a@b.com")]
    (is (= :valid (:status (auth/verify-and-consume-confirmation-code!
                            "a@b.com" code))))
    (is (= :unknown (:status (auth/verify-and-consume-confirmation-code!
                              "a@b.com" code))))))

(deftest concurrent-success-consumes-a-code-exactly-once
  (let [code (auth/generate-confirmation-code "a@b.com")
        start (promise)
        attempts (doall
                  (repeatedly 2
                              #(future
                                 @start
                                 (:status
                                  (auth/verify-and-consume-confirmation-code!
                                   "a@b.com" code)))))]
    (deliver start true)
    (let [statuses (mapv deref attempts)]
      (is (= 1 (count (filter #{:valid} statuses))))
      (is (= 1 (count (filter #{:unknown} statuses)))))))

(deftest concurrent-wrong-guesses-cannot-exceed-the-budget
  (let [code (auth/generate-confirmation-code "a@b.com")
        wrong (format "%06d" (mod (inc (Long/parseLong code)) 1000000))
        start (promise)
        attempts (doall
                  (repeatedly 6
                              #(future
                                 @start
                                 (auth/verify-and-consume-confirmation-code!
                                  "a@b.com" wrong))))]
    (deliver start true)
    (let [results (mapv deref attempts)]
      (is (= (dec auth/max-code-attempts)
             (count (filter #(and (= :mismatch (:status %))
                                  (not (:destroyed? %)))
                            results))))
      (is (= 1 (count (filter :destroyed? results))))
      (is (= :unknown (auth/code-status "a@b.com" code))))))

(deftest requesting-a-second-code-replaces-the-first
  (testing "one code in flight per address, as the atom also enforced"
    (let [first-code (auth/generate-confirmation-code "a@b.com")
          second-code (auth/generate-confirmation-code "a@b.com")]
      (when (not= first-code second-code)      ; 1-in-a-million collision
        (is (false? (auth/valid-code? "a@b.com" first-code))))
      (is (true? (auth/valid-code? "a@b.com" second-code)))
      (is (= 1 (count (d/q '[:find [?e ...]
                             :in $ ?email
                             :where [?e :confirmation-code/email ?email]]
                           @(db/get-conn) "a@b.com")))
          "upsert on the unique email, not a second row"))))

(deftest code-status-separates-the-four-reasons
  ;; The question #63 asks that storage alone does not answer: can a server
  ;; tell a STALE code from a WRONG one? With the code in memory the answer
  ;; was no — after a restart there was no record at all, so every failure
  ;; looked the same. With a record that outlives its own validity, it can.
  (testing ":unknown — nothing on record"
    (is (= :unknown (auth/code-status "nobody@example.com" "123456"))))

  (testing ":valid — the right code, in its window"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (is (= :valid (auth/code-status "a@b.com" code)))))

  (testing ":mismatch — a code is outstanding, this is not it"
    (let [code (auth/generate-confirmation-code "a@b.com")
          wrong (format "%06d" (mod (inc (Long/parseLong code)) 1000000))]
      (is (= :mismatch (auth/code-status "a@b.com" wrong)))))

  (testing ":expired — it was the right code, too late"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (expire-code! "a@b.com")
      (is (= :expired (auth/code-status "a@b.com" code)))
      (is (= :expired (auth/code-status "a@b.com" "000000"))
          "expiry is reported before matching — the window closed either way")))

  (testing "every status except :valid is a rejection"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (expire-code! "a@b.com")
      (is (false? (auth/valid-code? "a@b.com" code))))))

(deftest expired-codes-survive-long-enough-to-be-explained-then-are-swept
  (testing "an expired code is retained, so :expired does not collapse into :unknown"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (expire-code! "a@b.com")
      (auth/purge-stale-confirmation-codes! (db/get-conn))
      (is (= :expired (auth/code-status "a@b.com" code))
          "swept too eagerly — the diagnosis is gone the moment it is needed")))

  (testing "but it is swept once the grace window has passed"
    (auth/generate-confirmation-code "a@b.com")
    (let [conn (db/get-conn)
          entity (d/entity @conn [:confirmation-code/email "a@b.com"])]
      ;; Two hours past expiry, beyond the one-hour grace window.
      (d/transact conn {:tx-data [{:db/id (:db/id entity)
                                   :confirmation-code/expires-at
                                   (- (System/currentTimeMillis) (* 2 60 60 1000))}]})
      (is (pos? (auth/purge-stale-confirmation-codes! conn)))
      (is (= :unknown (auth/code-status "a@b.com" "123456"))
          "unbounded growth: one row per address that ever logged in, forever"))))

;; ---------------------------------------------------------------------------
;; #211 — the per-code failure budget
;; ---------------------------------------------------------------------------

(deftest a-code-is-destroyed-after-enough-wrong-guesses
  (testing "the budget is per CODE, so rotating source addresses does not refresh it"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (dotimes [n (dec auth/max-code-attempts)]
        (let [{:keys [attempts destroyed?]} (auth/register-code-failure! "a@b.com")]
          (is (= (inc n) attempts))
          (is (false? destroyed?) "still alive below the limit")))
      (is (= :valid (auth/code-status "a@b.com" code))
          "a code the user still holds must survive guesses below the limit")

      (let [{:keys [attempts destroyed?]} (auth/register-code-failure! "a@b.com")]
        (is (= auth/max-code-attempts attempts))
        (is (true? destroyed?)))
      (is (= :unknown (auth/code-status "a@b.com" code))
          "the real code no longer works either — that is the point, and the
           user requests another"))))

(deftest failing-against-nothing-creates-nothing
  (testing "a guess at an address with no outstanding code leaves no trace"
    ;; Creating a row here would turn the guessing endpoint into a way to
    ;; write to the database, and would make :unknown decay into :mismatch.
    (is (nil? (auth/register-code-failure! "nobody@example.com")))
    (is (= :unknown (auth/code-status "nobody@example.com" "123456")))))

(deftest the-failure-count-is-per-code-not-per-address
  (testing "a fresh code starts with a fresh budget"
    (auth/generate-confirmation-code "a@b.com")
    (dotimes [_ (dec auth/max-code-attempts)] (auth/register-code-failure! "a@b.com"))
    (let [replacement (auth/generate-confirmation-code "a@b.com")]
      (is (= 1 (:attempts (auth/register-code-failure! "a@b.com")))
          "requesting a new code must not inherit the old code's guesses")
      (is (= :valid (auth/code-status "a@b.com" replacement))))))

(deftest a-correct-guess-after-wrong-ones-still-works
  (testing "wrong guesses below the limit do not invalidate the real code"
    (let [code (auth/generate-confirmation-code "a@b.com")]
      (auth/register-code-failure! "a@b.com")
      (auth/register-code-failure! "a@b.com")
      (is (true? (auth/valid-code? "a@b.com" code))))))
