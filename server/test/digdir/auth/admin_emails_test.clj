(ns digdir.auth.admin-emails-test
  "ADMIN_USER_EMAILS is documented as \"Comma/space-separated\" and was parsed
   by splitting on a single SPACE (#515).

   ## Why the count is the assertion

   The defect passes every check that asks whether the step WORKED. With
   `ADMIN_USER_EMAILS=a@x,b@y`, migration creates a user, grants it
   admin-full, and reports \"1 created, 1 granted\" - all true. The account it
   made is `a@x,b@y`, one address with a comma in it, and nobody can log in as
   it. A test asserting success would have been green on the bug.

   So these assert HOW MANY accounts a three-address list produces, and which
   addresses they are. Three in, three out, none of them containing a
   separator.

   ## The pair is the point

   `comma-separated-...` is red before the fix and green after.
   `space-separated-...` is green BOTH times - it is the form that already
   worked, and a parser change that broke it would be a different defect of
   the same size. A guard that fires on every input distinguishes nothing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.auth.migration :as migration]
            [digdir.config.permissions :as perms]
            [digdir.secrets :as secrets]))

(defn- run-migration
  "Drive `migrate-admin-users!` with ADMIN_USER_EMAILS set to `env-value`.

   Everything below the parse is stubbed at the datahike boundary, so the
   split, the doseq and the result accounting are the real ones. Returns
   {:result <migration result> :created-emails [...]} - the emails are
   captured from the actual `create-user!` calls rather than re-derived, so
   this reports what the migration would have written."
  [env-value]
  (let [created (atom [])]
    (binding [secrets/*env-lookup* (fn [k] (when (= k "ADMIN_USER_EMAILS") env-value))
              ;; The migration narrates to stdout; keep the suite readable.
              *out* (java.io.StringWriter.)]
      (with-redefs [migration/ensure-permissions-exist! (fn [_] nil)
                    migration/create-user! (fn [_conn email _by]
                                             (swap! created conj email)
                                             (str "user-" (count @created)))
                    perms/get-user-by-email (fn [_db _email] nil)
                    perms/get-user-permissions (fn [_db _id] [])
                    perms/grant-permission! (fn [_conn _id _perm] nil)]
        {:result (migration/migrate-admin-users! (atom :stub))
         :created-emails @created}))))

(deftest comma-separated-list-of-three-creates-three-accounts
  (testing "The documented comma form yields THREE accounts, not one address
            with commas in it."
    (let [{:keys [result created-emails]}
          (run-migration "a@example.com,b@example.com,c@example.com")]
      (is (= 3 (count created-emails))
          "three addresses in, three accounts created")
      (is (= 3 (count (:created result)))
          "and the migration reports three, not one")
      (is (= #{"a@example.com" "b@example.com" "c@example.com"}
             (set created-emails)))
      (is (every? #(not (str/includes? % ",")) created-emails)
          "no account may carry a separator inside its address"))))

(deftest space-separated-list-of-three-still-creates-three-accounts
  (testing "The form that already worked keeps working - this is green before
            the fix as well as after."
    (let [{:keys [result created-emails]}
          (run-migration "a@example.com b@example.com c@example.com")]
      (is (= 3 (count created-emails)))
      (is (= 3 (count (:created result))))
      (is (= #{"a@example.com" "b@example.com" "c@example.com"}
             (set created-emails))))))

(deftest a-single-address-is-one-account
  (testing "The other input that already worked: no separator at all."
    (let [{:keys [created-emails]} (run-migration "solo@example.com")]
      (is (= ["solo@example.com"] created-emails)))))

(deftest the-parse-accepts-both-forms-and-drops-empties
  (testing "Mixed separators, runs of them, and a trailing comma."
    (is (= #{"a@x.no" "b@x.no" "c@x.no"}
           (perms/parse-admin-emails "a@x.no, b@x.no ,  c@x.no,")))
    (is (= #{"a@x.no"} (perms/parse-admin-emails "  a@x.no  ")))
    (is (= #{"a@x.no" "b@x.no"} (perms/parse-admin-emails "a@x.no\tb@x.no"))))
  (testing "Absence and punctuation-only reach callers as the same nil."
    (is (nil? (perms/parse-admin-emails nil)))
    (is (nil? (perms/parse-admin-emails "")))
    (is (nil? (perms/parse-admin-emails "   ")))
    (is (nil? (perms/parse-admin-emails ", ,")))))

(deftest both-doors-into-the-variable-agree
  (testing "`migration/get-admin-emails-from-env` and
            `permissions/get-admin-emails` read the same variable and used to
            hold byte-identical copies of the same wrong split. Whatever the
            separator is, it must be one answer."
    (binding [secrets/*env-lookup* (constantly "a@x.no,b@x.no")]
      (is (= (perms/get-admin-emails) (migration/get-admin-emails-from-env)))
      (is (= 2 (count (migration/get-admin-emails-from-env)))))))

;; ---------------------------------------------------------------------------
;; The other half of the same broken step (#515b, found by Conduit on a fresh
;; deployment).
;;
;; The separator defect makes a comma-separated list produce one unusable
;; account. This half is upstream of it: `.env.example` ships the variable
;; COMMENTED OUT, so the newcomer who edits the address but leaves the `#`
;; gets no admin at all — and every later step reports success. Fixed
;; separately these are two half-fixes of one step; the step only works when
;; both are.
;;
;; WHY THE LINE IS STILL COMMENTED. Uncommenting it would make whatever ships
;; a REAL admin on every deployment that did not edit it. Measured, on this
;; tree: `placeholder-secrets/secret-env-vars` covers 14 variables and
;; ADMIN_USER_EMAILS is not one of them (it is `:secret? false`), so
;; `placeholder-violations` returns [] for a value of "changeme@example.com".
;; The boot check cannot catch it. So the fix is to make the STEP not depend
;; on noticing a `#` — `scripts/setup-env.sh` asks — rather than to ship a
;; live placeholder.
;; ---------------------------------------------------------------------------

(defn- env-example [] (slurp (java.io.File. "../.env.example")))
(defn- setup-script [] (slurp (java.io.File. "../scripts/setup-env.sh")))

(deftest env-example-never-ships-a-live-admin-assignment
  (testing "A bare ADMIN_USER_EMAILS=… line in the shipped example becomes a
            real admin on every deployment that does not edit it, and the
            placeholder boot check does not cover this variable."
    (let [src (env-example)]
      (is (str/includes? src "ADMIN_USER_EMAILS")
          "the instrument can see the variable at all")
      (is (nil? (re-find #"(?m)^ADMIN_USER_EMAILS=" src))
          (str "ADMIN_USER_EMAILS is assigned uncommented in .env.example. "
               "That value is a real admin wherever the file is copied "
               "unedited. Keep it commented and let scripts/setup-env.sh "
               "ask for it.")))))

(deftest env-example-tells-the-reader-the-line-must-be-uncommented
  (testing "Nothing told them. The comment has to, because leaving the `#` is
            indistinguishable at every later step from never setting it."
    (let [src (env-example)]
      (is (re-find #"(?i)uncomment" src)
          ".env.example never says a line must be uncommented"))))

(deftest the-host-script-asks-for-the-admin-address
  (testing "The step that does not depend on the newcomer noticing a `#`.
            setup-env.sh copies .env.example to .env and then prompts; if the
            variable is in none of its lists it is never mentioned, which is
            the state this fixes."
    (let [src (setup-script)]
      (is (str/includes? src "ADMIN_USER_EMAILS")
          (str "scripts/setup-env.sh never mentions ADMIN_USER_EMAILS, so a "
               "newcomer running it is never asked who may log in")))))

(deftest the-migration-says-why-when-it-finds-none
  (testing "\"Found 0 admin emails\" then \"0 created, 0 granted, 0 skipped\"
            reads as a clean run. It is the state in which nobody can log in."
    (let [out (java.io.StringWriter.)]
      (binding [secrets/*env-lookup* (constantly nil)
                *out* out]
        (with-redefs [migration/ensure-permissions-exist! (fn [_] nil)]
          (migration/migrate-admin-users! (atom :stub))))
      (let [text (str out)]
        (is (str/includes? text "ADMIN_USER_EMAILS"))
        (is (re-find #"(?i)no admin|nobody can log in|not set" text)
            (str "the empty case reports counts but never names the cause; "
                 "a newcomer reads it as success. Got: " (pr-str text)))))))

(deftest the-runtime-instruction-does-not-teach-a-separator-the-parser-rejects
  (testing "`digdir.setup.first-admin` prints the guidance a stuck operator
            actually reads — it is step 2 of docs/onboarding.md, and it
            refuses with instructions when the variable is empty. It told
            them to SPACE-separate, which is a third place documenting the
            separator and the only one a container operator sees. #515 is
            docs and parser disagreeing; this is where that disagreement
            would come back."
    (let [src (slurp (io/resource "digdir/setup/first_admin.clj"))]
      (is (str/includes? src "Comma- or space-separate")
          "first-admin's empty-variable instruction should name both forms")
      (is (not (re-find #"(?m)^\s*\(println \"    Space-separate several" src))
          "first-admin still teaches the space-only form"))))
